package com.imrtc.demo

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.imrtc.engine.log.IMRTCLog
import kotlin.concurrent.thread

/**
 * 拨号页（草图 §02-B）：顶部身份卡 + 三块对应三种玩法：**1v1 / 群通话 / 会议房间**。
 *
 * **这一整屏都是宿主代码**——联系人从哪来、群怎么组织，SDK 一概不管。
 * 它只调 `DemoSession.placeCall` 与 `joinMeeting`。通话界面一行都不在这里。
 */
internal class DialerScreen(private val activity: Activity) : DemoScreen {

    private val serverField = DemoUI.field(activity, DemoSession.form.serverHint, DemoSession.form.defaultServer)
    private val userField = DemoUI.field(activity, "用户 ID", DemoSession.form.defaultUsername)
    private val calleeField = DemoUI.field(activity, "对方 ID", DemoSession.form.defaultCallee)
    private val roomField = DemoUI.field(activity, "房间号（留空则新建）", DemoSession.form.defaultRoom)

    private val statusLabel = DemoUI.label(activity, "", 13f, DemoUI.SECONDARY)

    /**
     * 登录失败的原因，**贴在登录按钮上方**。与页面底部的 [errorLabel] 分开是有意的：
     * 那个管通话/房间的报错，而登录报错要跟登录按钮待在一起——身份卡这块正是出事时
     * 人盯着的地方，让人为了看一句话去滚屏，等于没显示。
     */
    private val loginErrorLabel = DemoUI.label(activity, "", 13f, DemoUI.RED)

    /** 本地表单校验的报错（字段没填）。**不进 Session**——它跟换票请求没关系。 */
    private var formError: String? = null

    private val errorLabel = DemoUI.label(activity, "", 13f, DemoUI.RED)
    private val groupLabel = DemoUI.label(activity, "", 15f, DemoUI.LABEL)
    private val dot = View(activity)

    private val loginButton = DemoUI.button(activity, "登录") { onLogin() }
    private val logoutButton = DemoUI.button(activity, "退出") { DemoSession.logout() }
    private val callButtons: List<Button>

    override val title = "拨号"
    override val titleAction: Pair<String, () -> Unit>? = null
    override val view: View

    init {
        val audio = DemoUI.button(activity, "📞 语音") { place("audio") }
        val video = DemoUI.button(activity, "📹 视频") { place("video") }
        val pick = DemoUI.button(activity, "选人 ›") { onPickGroup() }
        val group = DemoUI.button(activity, "发起群通话") { onGroupCall() }
        val join = DemoUI.button(activity, "加入房间") { onJoinMeeting() }
        callButtons = listOf(audio, video, pick, group, join)

        errorLabel.maxLines = 4
        // 隧道那段提示是三行起步（见 LoginHint），4 行会被截掉命令那行。
        loginErrorLabel.maxLines = 8

        view = DemoUI.scroll(
            activity,
            DemoUI.stack(
                activity,
                listOf(
                    DemoUI.card(
                        activity, "身份",
                        listOf(
                            serverField,
                            DemoUI.note(activity, DemoSession.form.serverNote),
                            userField,
                            identityLine(),
                            loginErrorLabel,
                            loginButton,
                            logoutButton,
                        ),
                    ),
                    DemoUI.card(
                        activity, "单人通话",
                        listOf(calleeField, DemoUI.row(activity, listOf(audio, video))),
                    ),
                    DemoUI.card(
                        activity, "多人通话（最多 ${ContactPicker.LIMIT} 人）",
                        listOf(groupRow(pick), group),
                    ),
                    DemoUI.card(
                        activity, "会议房间",
                        listOf(
                            roomField,
                            join,
                            DemoUI.note(activity, "会议不走振铃，直接进房。把房间号发给另一台设备就能双开。"),
                        ),
                    ),
                    errorLabel,
                ),
            ),
        )
    }

    override fun refresh() {
        val loggedIn = DemoSession.isLoggedIn
        statusLabel.text = DemoSession.connectionText
        // 表单没填优先——那是用户刚做的动作；否则显示上一次换票失败的原因
        // （**自动重登的失败也走这里**，见 DemoSession.lastLoginError）。
        val loginError = formError ?: DemoSession.lastLoginError
        loginErrorLabel.text = loginError.orEmpty()
        // 空的时候要 GONE，不然身份卡上常年留着一条看不见的空行。
        loginErrorLabel.visibility = if (loginError.isNullOrEmpty()) View.GONE else View.VISIBLE
        // 连接态的绿点：草图 §02-B 的身份卡就靠它一眼看出信令通没通。
        dot.background = DemoUI.circle(if (loggedIn) DemoUI.GREEN else DemoUI.SEPARATOR)
        loginButton.visibility = if (loggedIn) View.GONE else View.VISIBLE
        // 换票是网络往返，慢的时候按钮看着像没反应，人就会再点一下——而**第二次点击正是
        // 那个「登录反被清空」故障的扳机**（见 DemoSession.login）。按钮自己说话，就没人补刀了。
        val busy = DemoSession.isLoggingIn
        loginButton.text = if (busy) "登录中…" else "登录"
        loginButton.isEnabled = !busy
        loginButton.alpha = if (busy) 0.4f else 1f
        logoutButton.visibility = if (loggedIn) View.VISIBLE else View.GONE
        serverField.isEnabled = !loggedIn
        userField.isEnabled = !loggedIn
        callButtons.forEach { it.isEnabled = loggedIn; it.alpha = if (loggedIn) 1f else 0.4f }
        groupLabel.text = if (DemoSession.groupPick.isEmpty()) {
            "👥 （请选人）"
        } else {
            "👥 " + DemoSession.groupPick.joinToString("、")
        }
    }

    // ── 动作 ──────────────────────────────────────────────────────────

    private fun onLogin() {
        errorLabel.text = ""
        formError = null
        val server = serverField.text.toString().trim()
        val user = userField.text.toString().trim()
        if (server.isEmpty() || user.isEmpty()) {
            formError = "服务器地址和用户 ID 都要填"
            refresh()
            return
        }
        // 失败文案由 [DemoSession.lastLoginError] 经 refresh() 显示，这里只补日志：
        // 原先失败原因**只进 label**，于是手动登录失败在 logcat 里一片空白
        // （自动重登反而有记录），「连不上」与「人压根没点」分不出来。
        // 地址不通时服务端一侧也没有任何请求进来，logcat 是唯一的现场。
        DemoSession.login(server, user) { reason ->
            IMRTCLog.w("demo", "手动登录失败：$reason")
        }
    }

    private fun place(mediaType: String) {
        errorLabel.text = ""
        val callee = calleeField.text.toString().trim()
        if (callee.isEmpty()) {
            errorLabel.text = "先填对方 ID"
            return
        }
        DemoSession.form.rememberCallee(callee)
        DemoSession.placeCall(listOf(callee), mediaType, isGroup = false)
    }

    private fun onPickGroup() {
        ContactPicker.show(activity, DemoSession.groupPick) { picked ->
            DemoSession.setGroupPick(picked)
        }
    }

    private fun onGroupCall() {
        errorLabel.text = ""
        if (DemoSession.groupPick.isEmpty()) {
            errorLabel.text = "先选人"
            return
        }
        DemoSession.placeCall(DemoSession.groupPick, "video", isGroup = true)
    }

    /**
     * 会议房。**新建是两步不是一步**：`POST /v1/rooms` 只回 `room_id`，
     * 票要走 `POST /v1/rooms/{id}/tokens` 再要一次（见 [DemoApi]）。
     */
    private fun onJoinMeeting() {
        errorLabel.text = ""
        val typed = roomField.text.toString().trim()
        val server = DemoSession.server
        val token = DemoSession.token
        thread {
            val api = DemoApi(server)
            runCatching {
                if (typed.isEmpty()) {
                    api.createMeetingRoom(token, DemoSession.deviceId)
                } else {
                    api.joinTicket(token, typed, DemoSession.deviceId)
                }
            }.onSuccess { room ->
                activity.runOnUiThread {
                    // 把房间号留在框里，方便复制给另一台设备。
                    roomField.setText(room.roomId)
                    DemoSession.form.rememberRoom(room.roomId)
                    DemoSession.joinMeeting(room.roomId, room.roomToken)
                }
            }.onFailure { error ->
                activity.runOnUiThread { errorLabel.text = "会议房失败：${error.message}" }
            }
        }
    }

    // ── 两个小拼装 ────────────────────────────────────────────────────

    private fun identityLine(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val size = DemoUI.dp(activity, 8)
        addView(dot, LinearLayout.LayoutParams(size, size).apply { rightMargin = size })
        addView(statusLabel)
    }

    private fun groupRow(pick: Button): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(groupLabel, LinearLayout.LayoutParams(0, DemoUI.WRAP, 1f))
        addView(
            pick,
            LinearLayout.LayoutParams(DemoUI.dp(activity, 96), DemoUI.dp(activity, 40))
                .apply { leftMargin = DemoUI.dp(activity, 8) },
        )
    }
}
