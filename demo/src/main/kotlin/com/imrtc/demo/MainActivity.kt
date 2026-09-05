package com.imrtc.demo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.webrtc.IMWebRTCAdapter
import com.imrtc.uikit.IMCallKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Demo App：**拨号 / 通话记录 / 设置** 三屏（草图 §02）。
 *
 * 它证明的是一件事：**只用公开回调表就能做出完整体验**。
 * 通话界面整套交给 `IMCallKit`（草图 §01 的用法 B），一行接管；
 * 通话记录完全由 `onCallEnd` 拼出来——**宿主只监听那一个回调也能完整记账**。
 *
 * 服务器地址：模拟器填 `http://10.0.2.2:8787`（那是宿主机），**真机要填 Mac 的局域网 IP**
 * ——`127.0.0.1` 在手机上指的是手机自己。`../im-rtc-server/scripts/dev.sh` 启动时会打印那一行。
 * 填过一次就记住，下次不用再敲。
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences

    private var engine: IMCallEngine? = null
    private var token: String = ""
    private var uid: String = ""

    private val records = mutableListOf<String>()

    private lateinit var container: FrameLayout
    private lateinit var statusLine: TextView
    private lateinit var recordsView: TextView

    private lateinit var serverField: EditText
    private lateinit var userField: EditText
    private lateinit var peerField: EditText
    private lateinit var roomField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("im-rtc-demo", Context.MODE_PRIVATE)
        DemoLogSink.install()
        setContentView(buildRoot())
        showTab(0)
        requestCallPermissions()
    }

    override fun onDestroy() {
        engine?.destroy()
        IMCallKit.stop()
        super.onDestroy()
    }

    // ── 三屏 ──────────────────────────────────────────────────────────

    private fun buildRoot(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(32), dp(16), dp(16))

        statusLine = TextView(this@MainActivity).apply {
            text = "未登录"
            textSize = 13f
        }
        addView(statusLine)

        val tabs = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabButton("拨号") { showTab(0) })
            addView(tabButton("通话记录") { showTab(1) })
            addView(tabButton("设置") { showTab(2) })
        }
        addView(tabs)

        container = FrameLayout(this@MainActivity)
        addView(container, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun showTab(index: Int) {
        container.removeAllViews()
        container.addView(
            when (index) {
                0 -> buildDialTab()
                1 -> buildRecordsTab()
                else -> buildSettingsTab()
            },
        )
    }

    private fun buildDialTab(): View = column {
        peerField = field("对方用户名（群呼用逗号分隔）", prefs.getString("peer", "").orEmpty())
        addView(peerField)
        addView(button("语音呼叫") { startCall("audio", group = false) })
        addView(button("视频呼叫") { startCall("video", group = false) })
        addView(button("群呼（视频）") { startCall("video", group = true) })

        addView(spacer())
        roomField = field("会议房号", prefs.getString("room", "").orEmpty())
        addView(roomField)
        // **新建与加入分成两个按钮**，不由一个按钮按输入框空不空自己猜——
        // 房号留在输入框里是有用的（要发给另一台设备），Web 端为此改过一次。
        addView(button("新建会议") { meeting(create = true) })
        addView(button("加入会议") { meeting(create = false) })
    }

    private fun buildRecordsTab(): View = column {
        recordsView = TextView(this@MainActivity).apply {
            text = renderRecords()
            textSize = 13f
        }
        addView(ScrollView(this@MainActivity).apply { addView(recordsView) })
    }

    private fun buildSettingsTab(): View = column {
        serverField = field(
            "服务器地址（真机填 Mac 的局域网 IP，别填 127.0.0.1）",
            prefs.getString("server", "http://10.0.2.2:8787").orEmpty(),
        )
        addView(serverField)
        userField = field("用户名（免密登录）", prefs.getString("user", "").orEmpty())
        addView(userField)
        addView(button("登录") { login() })
        addView(button("登出") { logout() })
        addView(
            TextView(this@MainActivity).apply {
                text = "服务端要带 -demo-login 才有免密登录这条路由。\n" +
                    "换成你自己的后台：把 DemoApi 换成你们签发 token 的接口即可。"
                textSize = 12f
            },
        )
    }

    // ── 动作 ──────────────────────────────────────────────────────────

    private fun login() {
        val server = serverField.text.toString().trim()
        val username = userField.text.toString().trim()
        if (server.isEmpty() || username.isEmpty()) {
            toast("服务器地址和用户名都要填")
            return
        }
        prefs.edit().putString("server", server).putString("user", username).apply()

        thread {
            runCatching { DemoApi(server).demoLogin(username) }
                .onSuccess { result ->
                    token = result.token
                    uid = result.uid
                    main.post { onLoggedIn(server) }
                }
                .onFailure { error -> main.post { toast("登录失败：${error.message}") } }
        }
    }

    private fun onLoggedIn(server: String) {
        val wsUrl = server.replaceFirst("http", "ws").trimEnd('/') + "/v1/ws"
        val instance = IMCallEngine(
            IMCallEngine.Config(url = wsUrl, deviceId = deviceId()),
            // **Kit 包一层**：宿主自己的 listener 照常收到全部回调，Kit 只是搭个便车。
            IMCallKit.wrap(DemoListener()),
            IMWebRTCAdapter(this),
        )
        engine = instance
        IMCallKit.start(this, instance)
        instance.login(token)
        statusLine.text = "已登录：$uid @ $server"
    }

    /** **房票绑定 device_id**，所以 REST 与握手必须用同一个值。 */
    private fun deviceId() = "android-${Build.MODEL}"

    private fun logout() {
        engine?.logout()
        statusLine.text = "已登出"
    }

    private fun startCall(mediaType: String, group: Boolean) {
        val instance = engine ?: return toast("先去「设置」登录")
        val peers = peerField.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (peers.isEmpty()) return toast("先填对方用户名")
        prefs.edit().putString("peer", peerField.text.toString()).apply()

        instance.call(peers, mediaType, group)
        // 拨出侧的界面靠这一条拉起来：回调里只有被叫侧的信息（主叫自己知道拨给了谁）。
        IMCallKit.notifyOutgoing(peers, mediaType, group)
    }

    private fun meeting(create: Boolean) {
        val instance = engine ?: return toast("先去「设置」登录")
        val server = prefs.getString("server", "").orEmpty()
        val roomId = roomField.text.toString().trim()
        if (!create && roomId.isEmpty()) return toast("先填会议房号")
        prefs.edit().putString("room", roomId).apply()

        thread {
            runCatching {
                val api = DemoApi(server)
                if (create) {
                    api.createMeetingRoom(token, deviceId())
                } else {
                    api.joinTicket(token, roomId, deviceId())
                }
            }.onSuccess { room ->
                main.post {
                    roomField.setText(room.roomId)
                    instance.joinRoom(room.roomId, room.roomToken)
                    IMCallKit.notifyMeeting(room.roomId)
                }
            }.onFailure { error -> main.post { toast("会议房失败：${error.message}") } }
        }
    }

    /**
     * 运行时权限。**清单里声明只是第一步**，麦克风、摄像头、以及 Android 13 起的通知
     * 都要在这里再要一次；没有通知权限的话前台服务的「通话中」用户看不见。
     */
    private fun requestCallPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions(wanted.toTypedArray(), 1)
    }

    // ── 回调：通话记录完全由 onCallEnd 拼出来 ─────────────────────────

    private inner class DemoListener : IMCallEngineListener {
        override fun onConnected(sessionId: String, resumed: Boolean) = main.post {
            statusLine.text = "已连接（resumed=$resumed）"
        }.let { }

        override fun onDisconnected(code: Int, reason: String) = main.post {
            statusLine.text = "已断开（code=$code $reason）"
        }.let { }

        override fun onKickedOut() = main.post {
            statusLine.text = "被踢下线，请重新登录"
            toast("被踢下线")
        }.let { }

        override fun onError(code: Int, message: String) = main.post {
            IMRTCLog.w("demo", "错误 $code $message")
        }.let { }

        override fun onCallEnd(callId: String, reason: String, durationSec: Long, endedBy: String) {
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            records.add(0, "$time  ${reasonText(reason)}  ${durationSec}s  by $endedBy")
            main.post { if (::recordsView.isInitialized) recordsView.text = renderRecords() }
        }
    }

    private fun renderRecords(): String =
        if (records.isEmpty()) "还没有通话记录。\n（这一页完全由 onCallEnd 拼出来）" else records.joinToString("\n")

    private fun reasonText(reason: String) = when (reason) {
        "hangup" -> "已结束"
        "cancel" -> "已取消"
        "reject" -> "对方拒绝"
        "busy" -> "对方忙线"
        "no_answer" -> "无人接听"
        "offline" -> "对方不在线"
        "network" -> "网络断开"
        else -> reason
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    private fun column(build: LinearLayout.() -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        build()
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 14f
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun tabButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(24))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        statusLine.gravity = Gravity.START
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    }
}
