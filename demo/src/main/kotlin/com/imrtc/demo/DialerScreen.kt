package com.imrtc.demo

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import kotlin.concurrent.thread

/**
 * 拨号页（草图 §02-B）：顶部身份卡 + 三块对应三种玩法：**1v1 / 群通话 / 会议房间**。
 *
 * **这一整屏都是宿主代码**——联系人从哪来、群怎么组织，SDK 一概不管。
 * 它只调 `DemoSession.placeCall` 与 `joinMeeting`。通话界面一行都不在这里。
 */
internal class DialerScreen(private val activity: Activity) : DemoScreen {

    private val serverField = DemoUI.field(activity, DemoSession.serverHint, DemoSession.defaultServer)
    private val userField = DemoUI.field(activity, "用户 ID", DemoSession.defaultUsername)
    private val calleeField = DemoUI.field(activity, "对方 ID", DemoSession.defaultCallee)
    private val roomField = DemoUI.field(activity, "房间号（留空则新建）", DemoSession.defaultRoom)

    private val statusLabel = DemoUI.label(activity, "", 13f, DemoUI.SECONDARY)
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

        view = DemoUI.scroll(
            activity,
            DemoUI.stack(
                activity,
                listOf(
                    DemoUI.card(
                        activity, "身份",
                        listOf(
                            serverField,
                            DemoUI.note(activity, DemoSession.serverNote),
                            userField,
                            identityLine(),
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
        // 连接态的绿点：草图 §02-B 的身份卡就靠它一眼看出信令通没通。
        dot.background = DemoUI.circle(if (loggedIn) DemoUI.GREEN else DemoUI.SEPARATOR)
        loginButton.visibility = if (loggedIn) View.GONE else View.VISIBLE
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
        val server = serverField.text.toString().trim()
        val user = userField.text.toString().trim()
        if (server.isEmpty() || user.isEmpty()) {
            errorLabel.text = "服务器地址和用户 ID 都要填"
            return
        }
        DemoSession.login(server, user) { errorLabel.text = it }
    }

    private fun place(mediaType: String) {
        errorLabel.text = ""
        val callee = calleeField.text.toString().trim()
        if (callee.isEmpty()) {
            errorLabel.text = "先填对方 ID"
            return
        }
        DemoSession.rememberCallee(callee)
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
                    DemoSession.rememberRoom(room.roomId)
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
