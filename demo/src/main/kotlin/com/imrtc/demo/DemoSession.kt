package com.imrtc.demo

import com.imrtc.engine.IMKickedOutReason
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
import com.imrtc.uikit.IMCallKitConfig
import com.imrtc.uikit.IMInviteCandidate
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

    /**
     * **房票绑定 device_id**，所以 REST 与握手必须用同一个值。
     *
     * `Build.MODEL` **必须清洗**：协议 §2.5 规定 charset 只有 `[A-Za-z0-9_-]`，
     * 而机型名里带空格是常态——"Pixel 2 XL"、"Redmi Note 8 Pro" 都是。
     * 不清洗的症状是**握手一律 1004 bad_params、无限退避重连**，界面上只写着
     * 「登录失败」，从服务端一侧也只看得见「参数不合法」，很难联想到是机型名。
     *
     * 这个 bug 之前没暴露，是因为验收用的 OPPO PKD130 型号里恰好没有空格。
     */
    val deviceId: String get() = "android-" + sanitizeDeviceId(Build.MODEL)

    /** 非法字符一律换成 `-`，并压掉连续与首尾的 `-`；空了就退回 `unknown`。 */
    private fun sanitizeDeviceId(raw: String): String {
        val cleaned = raw.map { ch ->
            if (ch.isLetterOrDigit() && ch.code < 128 || ch == '_' || ch == '-') ch else '-'
        }.joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        // 整个 device_id 还要 ≤64 字节，前缀占了 8 个。
        return cleaned.ifEmpty { "unknown" }.take(56)
    }

    var records: List<Record> = emptyList()
        private set
    var connectionText = "未登录"
        private set

    /**
     * 上一次换票失败的原因，**给身份卡直接显示用**。
     *
     * 存在 Session 而不是页面里，是因为**自动重登也要能显示**：那条路的失败发生在
     * `onCreate` 里，页面还没建好，回调塞不进任何 label。而它恰恰是最需要说话的一条——
     * 记住的地址后来失效（隧道断了、Mac 换了网段）时，用户只看见一个不动的登录页，
     * 而真正的原因（`Failed to connect to /127.0.0.1:8787`）只躺在 logcat 里。
     *
     * 登录成功与主动退出都会清掉它。
     */
    var lastLoginError: String? = null
        private set

    val isLoggedIn: Boolean get() = engine != null

    /**
     * 登录世代。**每拆一次 Engine 就 +1**，回调里拿它跟自己出生时的号对一下，
     * 对不上就说明这条回调来自一个已经被换掉的 Engine，一律不算数。
     *
     * 没有它的症状见 [login] 里那段注释：旧 Engine 被踢时会把**新** Engine 一起清掉。
     */
    private var loginGeneration = 0L

    /** 换票请求在途。UI 靠它把按钮变成「登录中…」，[login] 靠它挡住第二次点击。 */
    var isLoggingIn = false
        private set

    /** 状态变了通知界面。三个 tab 各自订阅。 */
    var onChange: (() -> Unit)? = null

    /**
     * Kit 的可配项。**是引用类型、随时可改**：设置页拨完开关不用重登，
     * 下一次形态切换就读到新值（这正是它不做成构造参数的原因）。
     */
    val kitConfig = IMCallKitConfig()

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

    /** 来电先出横幅（[IMCallKitConfig.bannerFirst]）。存本地，重启还在。 */
    var bannerFirst: Boolean
        get() = kitConfig.bannerFirst
        set(value) {
            kitConfig.bannerFirst = value
            prefs.edit().putBoolean(KEY_BANNER, value).apply()
            notifyChanged()
        }

    /** 悬浮窗（[IMCallKitConfig.floatingWindow]）。 */
    var floatingWindow: Boolean
        get() = kitConfig.floatingWindow
        set(value) {
            kitConfig.floatingWindow = value
            prefs.edit().putBoolean(KEY_FLOATING, value).apply()
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
        bannerFirst = prefs.getBoolean(KEY_BANNER, true)
        floatingWindow = prefs.getBoolean(KEY_FLOATING, true)
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
        // 不弹窗——用户没主动做这件事；但**原因要留在身份卡上**（[lastLoginError]）。
        // 「安静地留在登录页」曾经等于「什么都不说」：地址失效时人只看见一个不动的页面。
        login(server, user) { IMRTCLog.i("demo", "自动重登失败：$it") }
    }

    // ── 登录 / 退出 ───────────────────────────────────────────────────

    /**
     * 换一枚 token，然后拿它起一个新 Engine。
     *
     * **一次只许一个请求在飞。** 没有这道闸时的真实故障（真机日志可见）：
     * `onCreate` 里的 [autoLogin] 与用户点的那一下各起了一个 Engine，两个 Engine 拿着
     * **同一对 (uid, device_id)** 去握手，服务端按「顶号」把先连上的那个踢下线（4403）。
     * 被踢的是旧的，可 [HostListener] 是全局的、`logout()` 关的是**当前**那个——
     * 于是刚连上的新 Engine 被自己人清掉，界面弹回未登录。
     * 用户看到的就是「点了登录没反应」，再点一次反而好了。
     *
     * 三道防线各修一层：这里挡住并发换票，[onLoggedIn] 换 Engine 前先拆旧的，
     * [loginGeneration] 让漏网的旧回调彻底失效。
     */
    fun login(server: String, user: String, onError: (String) -> Unit) {
        if (isLoggingIn) return
        isLoggingIn = true
        notifyChanged()
        thread {
            runCatching { DemoApi(server).demoLogin(user) }
                .onSuccess { result ->
                    main.post {
                        isLoggingIn = false
                        onLoggedIn(server, user, result.token)
                    }
                }
                .onFailure { error ->
                    main.post {
                        isLoggingIn = false
                        connectionText = "登录失败"
                        // **原因就贴在按钮上方显示**，不再只写进拨号页底部那个要滚动才看得见的
                        // label——「登录失败」四个字分不出是地址不通、服务端没起、还是账号不对，
                        // 而这三种要查的地方完全不同。
                        lastLoginError = LoginHint.explain(server, error)
                        notifyChanged()
                        // **给 logcat 的是短的那句。** 身份卡上那段带着隧道命令、有换行，
                        // 整段灌进 logcat 只会把一条日志撑成四行。
                        onError("登录失败：${error.message}")
                    }
                }
        }
    }

    /** 票过期后的静默重登：不回登录页，直接用记住的账号再换一枚票。 */
    private fun relogin() {
        val currentServer = server
        val currentUser = username
        if (currentServer.isEmpty() || currentUser.isEmpty()) {
            logout()
            return
        }
        engine?.logout()
        login(currentServer, currentUser) {
            IMRTCLog.w("demo", "静默重登失败：$it")
            main.post { logout() }
        }
    }

    private fun onLoggedIn(server: String, user: String, newToken: String) {
        lastLoginError = null
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

        // **旧 Engine 必须先拆。** 留着它就是留着第二条握手连接，服务端会把其中一条踢掉。
        teardownEngine()

        // 日志回传（仅开发）：登录之后才知道往哪台服务器发、以谁的身份发。
        // **与 logcat 并联**，不替换——现场排查靠的还是 logcat（见 DemoLogSink）。
        // `android-` 前缀是跨端约定：timeline.py 按它把三端的日志染成不同颜色。
        DemoLogSink.attachRemote(RemoteLogSink(server, "android-$user"))

        val wsUrl = server.replaceFirst("http", "ws").trimEnd('/') + "/v1/ws"
        val instance = IMCallEngine(
            IMCallEngine.Config(url = wsUrl, deviceId = deviceId),
            // **Kit 包一层**：宿主自己的 listener 照常收到全部回调，Kit 只是搭个便车。
            IMCallKit.wrap(HostListener(loginGeneration)),
            IMWebRTCAdapter(applicationContext, videoProfile),
        )
        engine = instance
        // 「添加成员」的候选名单是宿主给的：Demo 用与选人页同一份写死的联系人，自己不放进去。
        kitConfig.inviteCandidates = ContactPicker.all().filter { it != user }.map { IMInviteCandidate(it) }
        IMCallKit.start(applicationContext, instance, kitConfig)
        instance.login(newToken)
        connectionText = "连接中…"
        notifyChanged()
    }

    fun logout() {
        // 主动退出就别再自动重登了——那是用户的明确意思。
        prefs.edit().putBoolean(KEY_AUTO, false).apply()
        teardownEngine()
        lastLoginError = null
        connectionText = "未登录"
        notifyChanged()
    }

    /**
     * 拆掉当前 Engine + Kit。
     *
     * **先让这一代作废再拆**：`destroy()` 会顺手吐出 `onDisconnected`，
     * 世代号先加上去，那条回调落地时就已经是旧世代、不会再改动登录态。
     */
    private fun teardownEngine() {
        loginGeneration++
        engine?.destroy()
        engine = null
        IMCallKit.stop()
        // 摘掉日志回传并把手里剩下的发出去。**放在这里而不是只在 logout**：
        // 换服务器重登也要走这条路，否则旧 sink 会继续往上一台服务器发。
        DemoLogSink.detachRemote()
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
        if (engine == null) return
        pending = Meta(
            peer = if (isGroup) "群通话 · ${peers.size + 1} 人" else peers.joinToString("、"),
            mediaType = mediaType,
            isGroup = isGroup,
            role = "caller",
        )
        // 经 Kit 拨出：它先过权限门（说明卡 → 系统框 → 被拒分支）再发 invite，
        // 拿不到麦克风就不去响别人的铃（交互稿 §01）。拨出侧的界面也由它拉起来。
        IMCallKit.placeCall(peers, mediaType, isGroup)
    }

    fun joinMeeting(roomId: String, roomToken: String) {
        if (engine == null) return
        // 会议房不产生 call，也就不会有 onCallEnd——记录页看不到它是对的。
        pending = null
        IMCallKit.joinMeeting(roomId, roomToken)
    }

    fun clearRecords() {
        records = emptyList()
        saveRecords()
        notifyChanged()
    }

    // ── 回调 → 连接态 + 通话记录 ──────────────────────────────────────

    /**
     * 宿主的回调落点。**按登录世代造，一个 Engine 一个**。
     *
     * 它不是 `object` 是有原因的：回调签名里**没有「是哪个 Engine 发的」**，
     * 一个全局实例分不清自己伺候的 Engine 是不是还在岗。旧 Engine 断线、被踢时照样会喊，
     * 而处理函数动的是 `DemoSession` 的全局登录态——就把新 Engine 误伤了。
     * 出生时记下世代号，每条回调先跟当下的对一下，这个歧义就没了。
     */
    private class HostListener(private val generation: Long) : IMCallEngineListener {

        /** 这条回调来自已经被换掉的 Engine——它说什么都不算数了。 */
        private val stale: Boolean get() = generation != loginGeneration

        override fun onConnected(sessionId: String, resumed: Boolean) {
            if (stale) return
            connectionText = "已连接 · " + server.removePrefix("http://").removePrefix("https://")
            notifyChanged()
        }

        override fun onDisconnected(code: Int, reason: String) {
            if (stale) return
            connectionText = "已断开（$code $reason）"
            notifyChanged()
        }

        /**
         * **三种原因，三种处置。** 真实宿主照这个分岔写。
         */
        override fun onKickedOut(reason: IMKickedOutReason) {
            if (stale) return
            when (reason) {
                // 账号在别处登录，或被宿主后台吊销（封号 / 注销设备）。换票救不了。
                IMKickedOutReason.TAKEN_OVER -> {
                    connectionText = "账号在其它设备登录"
                    // 被踢之后别再自动重登，否则重启就撞回同一个死胡同。
                    main.post { logout() }
                }
                // 票不好使且三次没换上：取一枚新票重登即可，不必打扰用户。
                IMKickedOutReason.AUTH_EXPIRED -> {
                    connectionText = "登录态过期，正在重新获取…"
                    main.post { relogin() }
                }
                // 参数被服务端拒了（device_id 不合规、协议版本不支持、应用被停用）。
                // **换票和重试都没用**——参数不会因为再来一次而变对，所以既不 relogin
                // 也不自动重连，只把话说清楚，等人去改配置。
                IMKickedOutReason.CONFIG_REJECTED -> {
                    connectionText = "接入参数被拒，请看日志"
                    main.post { logout() }
                }
            }
            notifyChanged()
        }

        /**
         * 票快到期了：**去自己的后台换一枚新的塞回来**，用户全程无感。
         *
         * 这就是宿主要做的全部事情。Engine 不会替你去要票——票从你的账号体系来，
         * 它不认识那套东西（协议 §1.5 的 push 不 pull）。
         */
        override fun onTokenWillExpire(expiresAtMs: Long) {
            if (stale) return
            val instance = engine ?: return
            val currentServer = server
            val currentUser = username
            if (currentServer.isEmpty() || currentUser.isEmpty()) return
            thread {
                runCatching { DemoApi(currentServer).demoLogin(currentUser) }
                    .onSuccess { result ->
                        main.post {
                            // 下一次重连生效，不打断当前通话。
                            instance.updateToken(result.token, result.expiresAtMs)
                            token = result.token
                            IMRTCLog.i("demo", "票已续期")
                        }
                    }
                    .onFailure { error ->
                        // 换票失败不是致命的：当前连接照旧活着，下次重连时再按 4401 那条路走。
                        IMRTCLog.w("demo", "续期失败：${error.message}")
                    }
            }
        }

        override fun onError(code: Int, message: String) {
            if (stale) return
            IMRTCLog.w("demo", "错误 $code $message")
        }

        override fun onCallReceived(
            callId: String,
            caller: String,
            calleeIds: List<String>,
            mediaType: String,
            isGroup: Boolean,
        ) {
            if (stale) return
            pending = Meta(caller, mediaType, isGroup, "callee")
        }

        override fun onCallBegin(callId: String, roomId: String, mediaType: String, role: String) {
            if (stale) return
            // 主叫这边 onCallReceived 不会来；正常路径上 placeCall 已经填好了 pending，
            // 但多端登录时这通电话可能是**在别的设备上发起、这台设备接进来的**（joinCall）。
            if (pending == null) pending = Meta("", mediaType, false, role)
        }

        override fun onCallEnd(callId: String, reason: String, durationSec: Long, endedBy: String) {
            if (stale) return
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
    private const val KEY_BANNER = "banner_first"
    private const val KEY_FLOATING = "floating_window"
    private const val KEY_RECORDS = "records"
}
