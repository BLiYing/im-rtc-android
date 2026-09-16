package com.imrtc.demo

import com.imrtc.engine.IMCallEndReason
import com.imrtc.engine.IMKickedOutReason
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.IMCallOptions
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMVideoProfile
import com.imrtc.engine.webrtc.IMWebRTCAdapter
import com.imrtc.uikit.IMCallKit
import com.imrtc.uikit.IMCallKitConfig
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

    /** 登录表单「上次填的东西」，见 [DemoFormPrefs]。`install` 之后才可用。 */
    lateinit var form: DemoFormPrefs
        private set

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
    // 算一次就够：`Build.MODEL` 不会变，而**房票与握手必须拿到同一个值**。
    // `Build.MODEL` 是平台类型（`String!`），个别刷机 ROM 上 `ro.product.model` 是空的。
    val deviceId: String by lazy { "android-" + sanitizeDeviceId(Build.MODEL.orEmpty()) }

    var records: List<DemoRecord> = emptyList()
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
     * 登录成功、以及用户**自己点的**那次退出会清掉它。被动退出（被踢、参数被拒、
     * 静默重登失败）**不清**——那几种情况下这句原因正是唯一说得清发生了什么的东西。
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

    /**
     * 视频走**硬件 H.264** 还是原来的 VP8 软编。**换了要重登才生效**（同 [videoProfile]）。
     *
     * # 为什么做成开关而不是写死
     *
     * 硬编治的是「CPU 跟不上 → 帧率掉 → 顶层饿死」那一半：真机实测 OPPO 推三层 VP8 时
     * 约 30 秒后必出 `受限=cpu`、帧率钉在 19。但 **Android 硬编做 simulcast 的成败
     * 取决于这台机器的 MediaCodec 实现**，厂商之间差别很大——有的起不了三个并发实例，
     * 有的低码率下码率控制很烂。
     *
     * 所以它必须能**不重新打包就退回去**，否则换一台机器出问题就只能等发版。
     * 关掉之后行为与 2026-09-10 之前完全一致（VP8 + libvpx 原生 simulcast）。
     */
    var preferHardwareH264: Boolean = true
        set(value) {
            field = value
            prefs.edit().putBoolean(KEY_H264, value).apply()
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
        form = DemoFormPrefs(prefs)
        records = DemoRecordStore.load(prefs)
        groupPick = prefs.getString(KEY_GROUP, "alice,carol").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        videoProfile = IMVideoProfile.PRESETS
            .firstOrNull { it.name == prefs.getString(KEY_PROFILE, "") } ?: IMVideoProfile.DEFAULT
        preferHardwareH264 = prefs.getBoolean(KEY_H264, true)
        verboseLog = prefs.getBoolean(KEY_VERBOSE, true)
        bannerFirst = prefs.getBoolean(KEY_BANNER, true)
        floatingWindow = prefs.getBoolean(KEY_FLOATING, true)
        DemoLogSink.install()
    }

    /**
     * 上次登录过就自动重登。**杀掉 app 再打开不该回到登录页。**
     *
     * 记的是「用什么去换 token」而不是 token 本身：token 会过期，重启后本来就该走一次
     * 正常的换票流程（真实宿主也一样——用自己的会话换新票）。
     */
    fun autoLogin() {
        if (isLoggedIn || !prefs.getBoolean(KEY_AUTO, false)) return
        val server = form.defaultServer
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
            // **带上说明**：不带的话 logout() 会把 lastLoginError 一并清掉，
            // 于是「票过期 → 换票也失败」最后落在一个什么都不说的登录页上——
            // 正是 lastLoginError 这个字段存在的理由被它自己抹掉了。
            main.post { logout("登录态过期，重登也失败了") }
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
            IMWebRTCAdapter(applicationContext, videoProfile, preferHardwareH264),
        )
        engine = instance
        // 「添加成员」按通话向宿主要候选人（HOST_INTEGRATION_DESIGN §3.4）：真实的 Demo 联系人
        // 排前面，后面凑几十个假成员分页；搜索词 fail/slow 演示失败/超时三态。见 DemoInviteProvider。
        kitConfig.inviteMemberProvider = DemoInviteProvider()
        kitConfig.allowsManualUidInput = true
        IMCallKit.start(applicationContext, instance, kitConfig)
        instance.login(newToken)
        connectionText = "连接中…"
        notifyChanged()
    }

    /**
     * 退出登录。
     *
     * [note] 是留在身份卡上的说明。**传了就说明这次退出是被动的**（被踢、参数被拒、
     * 静默重登失败），话要说清楚、上一次的失败原因也要留着给人看；不传才是用户自己
     * 点的「退出」，那才该清干净回到「未登录」。
     *
     * 没有这个参数时的症状：被动退出的那几处都是先写 `connectionText` 再
     * `main.post { logout() }`，而 `notifyChanged()` 自己也是 post 的、排在 `logout()`
     * **后面**——于是界面读到的永远是 `logout()` 写的「未登录」，那句解释一次都没露过面。
     */
    fun logout(note: String? = null) {
        // 主动退出就别再自动重登了——那是用户的明确意思。被动退出同理：
        // 参数不对/被踢的情况下自动重登只会每次启动都撞回同一个死胡同。
        prefs.edit().putBoolean(KEY_AUTO, false).apply()
        teardownEngine()
        if (note == null) lastLoginError = null
        connectionText = note ?: "未登录"
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
        // 群通话带上一个演示用的 chatGroupId——`DemoInviteProvider` 靠它决定「添加成员」列谁
        // （宿主真实场景里这是 IM 侧的群 id，HOST_INTEGRATION_DESIGN §3.2）。
        if (isGroup) {
            IMCallKit.placeCall(peers, mediaType, IMCallOptions(isGroup = true, chatGroupId = DEMO_CHAT_GROUP_ID))
        } else {
            IMCallKit.placeCall(peers, mediaType, isGroup = false)
        }
    }

    fun joinMeeting(roomId: String, roomToken: String) {
        if (engine == null) return
        // 会议房不产生 call，也就不会有 onCallEnd——记录页看不到它是对的。
        pending = null
        IMCallKit.joinMeeting(roomId, roomToken)
    }

    /**
     * 主动加入一通进行中的群通话（M8：`call.join`）。
     *
     * **「怎么知道有通话在进行中」是宿主的事**——真实宿主拿 webhook `call.started` 或
     * `GET /v1/calls?chat_group_id=&active=1` 自己摆「进行中」横幅；Demo 图简单，
     * 直接给一个「按 call_id 加入」的输入框（`DialerScreen`），call_id 靠人从另一台设备的
     * 日志里抄过来。
     */
    fun joinCall(callId: String) {
        if (engine == null || callId.isEmpty()) return
        pending = null
        IMCallKit.joinCall(callId)
    }

    fun clearRecords() {
        records = emptyList()
        DemoRecordStore.save(prefs, records)
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

        override fun onDisconnected(code: Int, willReconnect: Boolean) {
            if (stale) return
            connectionText = "已断开（$code，${if (willReconnect) "重连中…" else "不再重连"}）"
            notifyChanged()
        }

        /**
         * **三种原因，三种处置。** 真实宿主照这个分岔写。
         */
        override fun onKickedOut(reason: IMKickedOutReason) {
            if (stale) return
            when (reason) {
                // 账号在别处登录，或被宿主后台吊销（封号 / 注销设备）。换票救不了。
                // **说明交给 logout(note)**：它是最后落地的那一步，写在这里会被它盖掉。
                IMKickedOutReason.TAKEN_OVER -> main.post { logout("账号在其它设备登录") }
                // 票不好使：取一枚新票重登即可，不必打扰用户。
                IMKickedOutReason.AUTH_EXPIRED -> {
                    connectionText = "登录态过期，正在重新获取…"
                    main.post { relogin() }
                }
                // 参数被服务端拒了（device_id 不合规、协议版本不支持、应用被停用）。
                // **换票和重试都没用**——参数不会因为再来一次而变对，所以既不 relogin
                // 也不自动重连，只把话说清楚，等人去改配置。
                IMKickedOutReason.CONFIG_REJECTED -> main.post { logout("接入参数被拒，请看日志") }
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

        override fun onError(code: Int, name: String, message: String) {
            if (stale) return
            IMRTCLog.w("demo", "错误 $code $name $message")
        }

        override fun onCallReceived(
            callId: String,
            caller: String,
            inviter: String,
            calleeIds: List<String>,
            mediaType: String,
            isGroup: Boolean,
            chatGroupId: String,
            userData: String,
        ) {
            if (stale) return
            pending = Meta(caller, mediaType, isGroup, "callee")
        }

        override fun onCallBegin(
            callId: String,
            roomId: String,
            mediaType: String,
            isGroup: Boolean,
            role: String,
            caller: String,
            chatGroupId: String,
            userData: String,
        ) {
            if (stale) return
            // 主叫这边 onCallReceived 不会来；正常路径上 placeCall 已经填好了 pending，
            // 但多端登录时这通电话可能是**在别的设备上发起、这台设备接进来的**（joinCall）。
            if (pending == null) pending = Meta(caller, mediaType, isGroup, role)
        }

        override fun onCallEnd(callId: String, reason: IMCallEndReason, durationSec: Long, endedBy: String) {
            if (stale) return
            val meta = pending ?: Meta("", "audio", false, "caller")
            records = listOf(
                DemoRecord(
                    callId = callId,
                    peer = meta.peer,
                    mediaType = meta.mediaType,
                    isGroup = meta.isGroup,
                    role = meta.role,
                    reason = reason.wire,
                    durationSec = durationSec,
                    endedAtMs = System.currentTimeMillis(),
                ),
            ) + records
            pending = null
            DemoRecordStore.save(prefs, records)
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        main.post { onChange?.invoke() }
    }

    private lateinit var applicationContext: Context

    /** Demo 群呼演示用的固定群号，配合 [DemoInviteProvider]。真实宿主这里传自己 IM 的群 id。 */
    private const val DEMO_CHAT_GROUP_ID = "demo-group"

}
