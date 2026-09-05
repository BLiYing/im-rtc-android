package com.imrtc.demo

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMVideoProfile
import com.imrtc.engine.webrtc.IMWebRTCAdapter
import com.imrtc.uikit.IMCallKit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Demo 的「宿主状态」：登录态、Engine + Kit、通话记录。三个 tab 共用它。
 *
 * # 通话记录完全由回调拼出来
 *
 * 草图 §02-C：记录是 `onCallEnd(reason, durationSec)` 的产物，Demo 自己存本地。
 * 宿主换成消息气泡、换成后台查 `/v1/calls`，都是同一份数据。
 *
 * **这一层属于宿主，不属于 SDK。** 联系人从哪来、记录存哪、登录怎么换票，SDK 一概不管。
 *
 * 这里用 `org.json` 与 `SharedPreferences` 是**故意的**：它们是宿主代码，跑在真设备上没有
 * 空壳桩问题。SDK 里禁用 `org.json` 的理由是 JVM 单测会假绿（CLAUDE.md 技术栈那节），与这里无关。
 */
internal object DemoSession {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    /** 一条通话记录。存成 JSON 进 SharedPreferences——Demo 不引数据库。 */
    data class Record(
        val callId: String,
        val peer: String,
        val mediaType: String,
        val isGroup: Boolean,
        /** "caller" 或 "callee"。未接来电＝被叫且时长为 0。 */
        val role: String,
        val reason: String,
        val durationSec: Long,
        val endedAtMs: Long,
    )

    var engine: IMCallEngine? = null
        private set
    var server = ""
        private set
    var username = ""
        private set
    var token = ""
        private set

    /** **房票绑定 device_id**，所以 REST 与握手必须用同一个值。 */
    val deviceId: String get() = "android-${Build.MODEL}"

    var records: List<Record> = emptyList()
        private set
    var connectionText = "未登录"
        private set

    val isLoggedIn: Boolean get() = engine != null

    /** 状态变了通知界面。三个 tab 各自订阅。 */
    var onChange: (() -> Unit)? = null

    /**
     * 采集画质档位。**换了要重登才生效**——适配器是登录时造的（见 [IMVideoProfile]）。
     */
    var videoProfile: IMVideoProfile = IMVideoProfile.DEFAULT
        set(value) {
            field = value
            prefs.edit().putString(KEY_PROFILE, value.name).apply()
            notifyChanged()
        }

    /** 详细日志。关掉就只留 info 以上，主讲人/网络质量那些周期事件不刷屏。 */
    var verboseLog: Boolean = true
        set(value) {
            field = value
            IMRTCLog.setMinLevel(if (value) IMRTCLog.Level.DEBUG else IMRTCLog.Level.INFO)
            prefs.edit().putBoolean(KEY_VERBOSE, value).apply()
            notifyChanged()
        }

    /** 群呼名单。**登录后要把自己剔掉**——带着自己发出去服务端会以 1004 拒掉整通电话。 */
    var groupPick: List<String> = listOf("alice", "carol")
        private set

    fun setGroupPick(picked: List<String>) {
        groupPick = picked.filter { it != username }
        prefs.edit().putString(KEY_GROUP, picked.joinToString(",")).apply()
        notifyChanged()
    }

    fun install(context: Context) {
        if (::prefs.isInitialized) return
        applicationContext = context.applicationContext
        prefs = applicationContext.getSharedPreferences("im-rtc-demo", Context.MODE_PRIVATE)
        records = loadRecords()
        groupPick = prefs.getString(KEY_GROUP, "alice,carol").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        videoProfile = IMVideoProfile.PRESETS
            .firstOrNull { it.name == prefs.getString(KEY_PROFILE, "") } ?: IMVideoProfile.DEFAULT
        verboseLog = prefs.getBoolean(KEY_VERBOSE, true)
        DemoLogSink.install()
    }

    // ── 记住上次填的东西 ──────────────────────────────────────────────

    /**
     * 上次用的服务器地址；没用过就给模拟器的默认值。
     *
     * **模拟器与真机的默认值必须不一样**：模拟器里 `10.0.2.2` 就是宿主机，开箱即用；
     * 真机上 `127.0.0.1` 指的是**手机自己**，永远连不上。
     *
     * 真机上**留空**，靠 hint 说该填什么。不预填一个像模像样的假 IP（比如
     * 192.168.1.100）：那种地址一眼看不出是错的，人会以为服务端挂了去查服务端日志——
     * 而那边根本没有请求进来，最难查的一类。
     */
    val defaultServer: String
        get() = prefs.getString(KEY_SERVER, null)
            ?: if (isEmulator) "http://10.0.2.2:8787" else ""

    val serverHint: String
        get() = if (isEmulator) {
            "服务器（模拟器用 10.0.2.2 指向 Mac）"
        } else {
            "http://<Mac 的局域网 IP>:8787"
        }

    val serverNote: String
        get() = if (isEmulator) {
            "模拟器里 10.0.2.2 就是宿主机，默认值直接可用。"
        } else {
            "真机请填 Mac 的局域网 IP（启动 dev.sh 时会打印）。127.0.0.1 在手机上指手机自己。"
        }

    /** 默认 **carol**：Web 默认 alice、iOS 默认 bob，三端错开，联调不用改用户名。 */
    val defaultUsername: String get() = prefs.getString(KEY_USER, null) ?: "carol"

    val defaultCallee: String
        get() = prefs.getString(KEY_CALLEE, null) ?: if (defaultUsername == "alice") "bob" else "alice"

    val defaultRoom: String get() = prefs.getString(KEY_ROOM, "").orEmpty()

    fun rememberCallee(value: String) = prefs.edit().putString(KEY_CALLEE, value).apply()

    fun rememberRoom(value: String) = prefs.edit().putString(KEY_ROOM, value).apply()

    private val isEmulator: Boolean
        get() = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("sdk_gphone")

    /**
     * 上次登录过就自动重登。**杀掉 app 再打开不该回到登录页。**
     *
     * 记的是「用什么去换 token」而不是 token 本身：token 会过期，重启后本来就该走一次
     * 正常的换票流程（真实宿主也一样——用自己的会话换新票）。
     */
    fun autoLogin() {
        if (isLoggedIn || !prefs.getBoolean(KEY_AUTO, false)) return
        val server = defaultServer
        val user = prefs.getString(KEY_USER, "").orEmpty()
        if (server.isEmpty() || user.isEmpty()) return
        // 自动重登失败就安静地留在登录页——不弹错误，用户没主动做这件事。
        login(server, user) { IMRTCLog.i("demo", "自动重登失败：$it") }
    }

    // ── 登录 / 退出 ───────────────────────────────────────────────────

    fun login(server: String, user: String, onError: (String) -> Unit) {
        thread {
            runCatching { DemoApi(server).demoLogin(user) }
                .onSuccess { result -> main.post { onLoggedIn(server, user, result.token) } }
                .onFailure { error -> main.post { onError("登录失败：${error.message}") } }
        }
    }

    private fun onLoggedIn(server: String, user: String, newToken: String) {
        this.server = server
        this.username = user
        this.token = newToken
        // 登录成功才记住——**失败的地址不该被记下来**，否则一次手滑之后每次启动都带着
        // 那个错地址，还以为是默认值有问题。
        prefs.edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_USER, user)
            .putBoolean(KEY_AUTO, true)
            .apply()
        setGroupPick(groupPick)

        val wsUrl = server.replaceFirst("http", "ws").trimEnd('/') + "/v1/ws"
        val instance = IMCallEngine(
            IMCallEngine.Config(url = wsUrl, deviceId = deviceId),
            // **Kit 包一层**：宿主自己的 listener 照常收到全部回调，Kit 只是搭个便车。
            IMCallKit.wrap(HostListener),
            IMWebRTCAdapter(applicationContext, videoProfile),
        )
        engine = instance
        IMCallKit.start(applicationContext, instance)
        instance.login(newToken)
        connectionText = "连接中…"
        notifyChanged()
    }

    fun logout() {
        // 主动退出就别再自动重登了——那是用户的明确意思。
        prefs.edit().putBoolean(KEY_AUTO, false).apply()
        engine?.destroy()
        engine = null
        IMCallKit.stop()
        connectionText = "未登录"
        notifyChanged()
    }

    // ── 拨号：记下这通电话是打给谁的 ──────────────────────────────────

    /** 主叫拨号时记下对方是谁——`onCallBegin` 的载荷里没有 callee。 */
    private var pending: Meta? = null

    private data class Meta(
        val peer: String,
        val mediaType: String,
        val isGroup: Boolean,
        val role: String,
    )

    fun placeCall(peers: List<String>, mediaType: String, isGroup: Boolean) {
        val instance = engine ?: return
        pending = Meta(
            peer = if (isGroup) "群通话 · ${peers.size + 1} 人" else peers.joinToString("、"),
            mediaType = mediaType,
            isGroup = isGroup,
            role = "caller",
        )
        instance.call(peers, mediaType, isGroup)
        // 拨出侧的界面靠这一条拉起来：回调里只有被叫侧的信息（主叫自己知道拨给了谁）。
        IMCallKit.notifyOutgoing(peers, mediaType, isGroup)
    }

    fun joinMeeting(roomId: String, roomToken: String) {
        val instance = engine ?: return
        // 会议房不产生 call，也就不会有 onCallEnd——记录页看不到它是对的。
        pending = null
        instance.joinRoom(roomId, roomToken)
        IMCallKit.notifyMeeting(roomId)
    }

    fun clearRecords() {
        records = emptyList()
        saveRecords()
        notifyChanged()
    }

    // ── 回调 → 连接态 + 通话记录 ──────────────────────────────────────

    private object HostListener : IMCallEngineListener {
        override fun onConnected(sessionId: String, resumed: Boolean) {
            connectionText = "已连接 · " + server.removePrefix("http://").removePrefix("https://")
            notifyChanged()
        }

        override fun onDisconnected(code: Int, reason: String) {
            connectionText = "已断开（$code $reason）"
            notifyChanged()
        }

        override fun onKickedOut() {
            connectionText = "登录态失效，请重新登录"
            // 被踢之后别再自动重登，否则重启就撞回同一个死胡同。
            main.post { logout() }
        }

        override fun onError(code: Int, message: String) {
            IMRTCLog.w("demo", "错误 $code $message")
        }

        override fun onCallReceived(callId: String, caller: String, mediaType: String, isGroup: Boolean) {
            pending = Meta(caller, mediaType, isGroup, "callee")
        }

        override fun onCallBegin(callId: String, roomId: String, mediaType: String, role: String) {
            // 主叫这边 onCallReceived 不会来；正常路径上 placeCall 已经填好了 pending，
            // 但多端登录时这通电话可能是**在别的设备上发起、这台设备接进来的**（joinCall）。
            if (pending == null) pending = Meta("", mediaType, false, role)
        }

        override fun onCallEnd(callId: String, reason: String, durationSec: Long, endedBy: String) {
            val meta = pending ?: Meta("", "audio", false, "caller")
            records = listOf(
                Record(
                    callId = callId,
                    peer = meta.peer,
                    mediaType = meta.mediaType,
                    isGroup = meta.isGroup,
                    role = meta.role,
                    reason = reason,
                    durationSec = durationSec,
                    endedAtMs = System.currentTimeMillis(),
                ),
            ) + records
            pending = null
            saveRecords()
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        main.post { onChange?.invoke() }
    }

    // ── 持久化 ────────────────────────────────────────────────────────

    private lateinit var applicationContext: Context

    private fun loadRecords(): List<Record> {
        val text = prefs.getString(KEY_RECORDS, "").orEmpty()
        if (text.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val json = array.getJSONObject(index)
                Record(
                    callId = json.optString("call_id"),
                    peer = json.optString("peer"),
                    mediaType = json.optString("media_type", "audio"),
                    isGroup = json.optBoolean("is_group"),
                    role = json.optString("role", "caller"),
                    reason = json.optString("reason"),
                    durationSec = json.optLong("duration_sec"),
                    endedAtMs = json.optLong("ended_at_ms"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRecords() {
        // 只留最近 100 条，Demo 不做翻页。
        val array = JSONArray()
        records.take(100).forEach { record ->
            array.put(
                JSONObject()
                    .put("call_id", record.callId)
                    .put("peer", record.peer)
                    .put("media_type", record.mediaType)
                    .put("is_group", record.isGroup)
                    .put("role", record.role)
                    .put("reason", record.reason)
                    .put("duration_sec", record.durationSec)
                    .put("ended_at_ms", record.endedAtMs),
            )
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private const val KEY_SERVER = "server"
    private const val KEY_USER = "user"
    private const val KEY_CALLEE = "callee"
    private const val KEY_ROOM = "room"
    private const val KEY_GROUP = "group"
    private const val KEY_AUTO = "auto_login"
    private const val KEY_PROFILE = "video_profile"
    private const val KEY_VERBOSE = "verbose_log"
    private const val KEY_RECORDS = "records"
}
