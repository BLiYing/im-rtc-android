package com.imrtc.uikit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.IMNetworkQuality
import com.imrtc.engine.IMSpeaker

/**
 * **整套通话 UI 的入口。宿主一行接管：`IMCallKit.start(context, engine)`。**
 *
 * Kit 只消费公开回调表（[IMCallEngineListener]），**没有任何私有通道**——
 * 宿主自画 UI 能拿到的信息与它完全一致。这是「两种集成方式能力对等」的唯一保证。
 *
 * 它做三件事：
 * 1. 把 Engine 的回调折成 [IMCallViewState]（纯值，可单测）；
 * 2. 按当前状态**决定用哪种呈现形态**——全屏页 / 来电横幅 / 悬浮球，见 [desiredMode]；
 * 3. 把界面上的点击翻译回 Engine 的方法调用。
 */
object IMCallKit {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: IMCallEngine? = null
    private var appContext: Context? = null

    /** Kit 的可配项。宿主可以随时改，下一次形态切换就读到新值。 */
    @JvmStatic
    var config: IMCallKitConfig = IMCallKitConfig()
        private set

    /** 横幅 / 悬浮球都挂在这上面（应用内浮层，不申请 SYSTEM_ALERT_WINDOW）。 */
    private val overlay = IMCallOverlay()

    /**
     * 横幅已经被用户点开过。
     *
     * **它必须独立于 [IMCallViewState]**：状态里没有「用户看过横幅了」这回事，
     * 而少了它的话，展开成全屏之后下一次刷新又会被判回横幅——界面来回跳。
     */
    private var bannerExpanded = false

    private var mode = Mode.HIDDEN

    private enum class Mode { HIDDEN, BANNER, BUBBLE, FULLSCREEN }

    @Volatile
    internal var state: IMCallViewState = IMCallViewState()
        private set

    private var timer: Runnable? = null
    private val observers = mutableListOf<(IMCallViewState) -> Unit>()

    /**
     * 接管通话 UI。**在 login 之前调**——来电随时可能到。
     *
     * 传进来的 `engine` 的 listener 会被 Kit 包一层：宿主自己的 listener 照常收到全部回调，
     * Kit 只是搭个便车。
     */
    @JvmOverloads
    @JvmStatic
    fun start(context: Context, engine: IMCallEngine, config: IMCallKitConfig = IMCallKitConfig()) {
        this.appContext = context.applicationContext
        this.engine = engine
        this.config = config
        // 横幅与悬浮球要知道挂到哪个 Activity 上；宿主传进来的可能是 Application，也可能是 Activity。
        (context.applicationContext as? Application)?.let { IMActivityTracker.install(it) }
    }

    @JvmStatic
    fun stop() {
        engine = null
        stopTimer()
        state = IMCallViewReducer.reset()
        main.post { overlay.detach() }
        mode = Mode.HIDDEN
        bannerExpanded = false
    }

    /**
     * 宿主把自己的 listener 交给它包一层，Kit 借此拿到全部事件。
     *
     * 为什么不让 Kit 自己注册一个 listener：Engine 只有一个 listener 位——
     * **给 Kit 开第二个口子就等于开了私有通道**，那正是我们不做的事。包一层最诚实。
     */
    @JvmStatic
    fun wrap(host: IMCallEngineListener): IMCallEngineListener = KitListener(host)

    internal fun observe(observer: (IMCallViewState) -> Unit) {
        observers += observer
        observer(state)
    }

    internal fun forget(observer: (IMCallViewState) -> Unit) {
        observers -= observer
    }

    internal fun videoViewFor(context: Context, uid: String): View? {
        val view = engine?.createVideoView(context) ?: return null
        engine?.attachView(uid, view)
        return view
    }

    internal fun answer() = engine?.accept()

    internal fun hangup() {
        when (state.hangupAction) {
            // **会议房里没有 call，结束动作是 leaveRoom**。红按钮无条件走 hangup 的话，
            // 通话机会把它本地拒成 2005，用户看到的是「点了没反应」。
            IMCallViewState.Action.LEAVE_ROOM -> engine?.leaveRoom()
            IMCallViewState.Action.REJECT -> engine?.reject()
            IMCallViewState.Action.CANCEL -> engine?.cancel()
            IMCallViewState.Action.HANGUP -> engine?.hangup()
            IMCallViewState.Action.NONE -> Unit
        }
    }

    internal fun toggleMic() {
        val next = !state.micOn
        if (next) engine?.openMic() else engine?.closeMic()
        update(IMCallViewReducer.toggleMic(state))
    }

    internal fun toggleCamera() {
        val next = !state.cameraOn
        if (next) engine?.openCamera() else engine?.closeCamera()
        update(IMCallViewReducer.toggleCamera(state))
    }

    internal fun toggleSpeaker() {
        val next = !state.speakerOn
        engine?.setSpeakerOn(next)
        update(IMCallViewReducer.toggleSpeaker(state))
    }

    internal fun switchCamera() = engine?.switchCamera()

    /** 收进悬浮球。接通之前不许收，见 [IMCallViewState.canMinimize]。 */
    internal fun minimize() = update(IMCallViewReducer.minimize(state))

    /** 从悬浮球 / 横幅展开回全屏。 */
    internal fun expand() {
        bannerExpanded = true
        update(IMCallViewReducer.expand(state))
    }

    /** 宿主主动拨出时告诉 Kit 一声，好把界面拉起来（回调里只有被叫侧的信息）。 */
    @JvmStatic
    fun notifyOutgoing(peers: List<String>, mediaType: String, isGroup: Boolean) {
        update(IMCallViewReducer.outgoing(state, peers, mediaType, isGroup))
    }

    /** 宿主进会议房时同理。 */
    @JvmStatic
    fun notifyMeeting(roomId: String) {
        update(IMCallViewReducer.meeting(state, roomId))
    }

    private fun update(next: IMCallViewState) {
        state = next
        main.post {
            observers.toList().forEach { it(next) }
            applyPresentation(next)
        }
    }

    // ── 呈现形态：全屏 / 横幅 / 悬浮球 ────────────────────────────────

    /**
     * 按当前状态决定用哪种形态，并把上一种收掉。**每次状态更新都会走一遍**，
     * 所以它必须便宜且幂等——一秒一次的计时也会走到这里。
     */
    private fun applyPresentation(current: IMCallViewState) {
        if (current.phase == IMCallViewState.Phase.IDLE) bannerExpanded = false
        val host = IMActivityTracker.foreground()
        val wanted = desiredMode(current, host)
        val changed = wanted != mode
        mode = wanted
        when (wanted) {
            Mode.HIDDEN -> if (changed) overlay.detach()
            Mode.FULLSCREEN -> {
                overlay.detach()
                if (changed) present()
            }
            Mode.BANNER -> mountBanner(host, current)
            Mode.BUBBLE -> mountBubble(host, current)
        }
        if (changed) IMRTCLog.i("kit", "通话界面形态：${wanted.name.lowercase()}")
    }

    /**
     * 形态判定。顺序有讲究，**小窗优先于横幅**：来电时不可能是小窗（还没接通），
     * 反过来接通后也不该再出横幅。
     *
     * 横幅与悬浮球都是**应用内浮层**，没有前台 Activity 就挂不上去：
     * - 来电时退回全屏 Activity（App 在后台，这本来就是系统来电的做法）；
     * - 已经收成小窗时**什么都不显示**（HIDDEN）——通话照常，前台服务的通知还在，
     *   把用户硬拽回 App 才是错的。
     */
    private fun desiredMode(current: IMCallViewState, host: Activity?): Mode = when {
        current.phase == IMCallViewState.Phase.IDLE -> Mode.HIDDEN
        current.isMinimized && config.floatingWindow ->
            if (host != null) Mode.BUBBLE else Mode.HIDDEN
        current.phase == IMCallViewState.Phase.INCOMING && config.bannerFirst &&
            !bannerExpanded && host != null -> Mode.BANNER
        else -> Mode.FULLSCREEN
    }

    private fun mountBanner(host: Activity?, current: IMCallViewState) {
        val banner = overlay.mount(
            host,
            IMIncomingBanner::class.java,
            { activity ->
                IMIncomingBanner(activity).apply {
                    onAccept = { answer() }
                    onReject = { hangup() }
                    onExpand = { expand() }
                }
            },
            { activity -> IMCallOverlay.bannerParams(activity) },
        )
        banner?.render(current)
    }

    private fun mountBubble(host: Activity?, current: IMCallViewState) {
        val bubble = overlay.mount(
            host,
            IMFloatingBubble::class.java,
            { activity -> IMFloatingBubble(activity).apply { onExpand = { expand() } } },
            { activity -> IMFloatingBubble.initialParams(activity) },
        )
        bubble?.render(current)
    }

    private fun present() {
        val context = appContext ?: return
        val intent = Intent(context, IMCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun startTimer() {
        stopTimer()
        val tick = object : Runnable {
            override fun run() {
                update(IMCallViewReducer.tick(state))
                main.postDelayed(this, 1_000)
            }
        }
        timer = tick
        main.postDelayed(tick, 1_000)
    }

    private fun stopTimer() {
        timer?.let { main.removeCallbacks(it) }
        timer = null
    }

    /** 包在宿主 listener 外面的一层：先喂 Kit，再原样转给宿主。 */
    private class KitListener(private val host: IMCallEngineListener) : IMCallEngineListener {

        override fun onConnected(sessionId: String, resumed: Boolean) = host.onConnected(sessionId, resumed)

        override fun onDisconnected(code: Int, reason: String) = host.onDisconnected(code, reason)

        override fun onKickedOut() = host.onKickedOut()

        override fun onError(code: Int, message: String) = host.onError(code, message)

        override fun onCallReceived(callId: String, caller: String, mediaType: String, isGroup: Boolean) {
            update(IMCallViewReducer.incoming(state, callId, caller, mediaType, isGroup))
            host.onCallReceived(callId, caller, mediaType, isGroup)
        }

        override fun onCallBegin(callId: String, roomId: String, mediaType: String, role: String) {
            update(IMCallViewReducer.begin(state, callId, roomId, mediaType, role))
            host.onCallBegin(callId, roomId, mediaType, role)
        }

        override fun onCallEnd(callId: String, reason: String, durationSec: Long, endedBy: String) {
            stopTimer()
            update(IMCallViewReducer.ended(state, reason))
            // 停 1.5 秒让用户看清结束原因再收场。**这是界面的展示状态**——
            // 通话状态机里没有 ended，那是个事件。
            main.postDelayed({ update(IMCallViewReducer.reset()) }, 1_500)
            host.onCallEnd(callId, reason, durationSec, endedBy)
        }

        override fun onCallCancelled(uid: String) = host.onCallCancelled(uid)
        override fun onCallRejected(uid: String) = host.onCallRejected(uid)
        override fun onCallBusy(uid: String) = host.onCallBusy(uid)
        override fun onCallNoAnswer(uid: String) = host.onCallNoAnswer(uid)
        override fun onHandledOnOtherDevice(callId: String, action: String) =
            host.onHandledOnOtherDevice(callId, action)

        override fun onUserEnter(uid: String) {
            update(IMCallViewReducer.userEnter(state, uid))
            host.onUserEnter(uid)
        }

        override fun onUserLeave(uid: String) {
            update(IMCallViewReducer.userLeave(state, uid))
            host.onUserLeave(uid)
        }

        override fun onUserAccept(uid: String) = host.onUserAccept(uid)
        override fun onUserReject(uid: String) = host.onUserReject(uid)
        override fun onUserNoResponse(uid: String) = host.onUserNoResponse(uid)

        override fun onUserAudioAvailable(uid: String, available: Boolean) {
            update(IMCallViewReducer.availability(state, uid, "audio", available))
            host.onUserAudioAvailable(uid, available)
        }

        override fun onUserVideoAvailable(uid: String, available: Boolean) {
            update(IMCallViewReducer.availability(state, uid, "video", available))
            host.onUserVideoAvailable(uid, available)
        }

        override fun onActiveSpeakers(speakers: List<IMSpeaker>) {
            update(IMCallViewReducer.speaking(state, speakers.maxByOrNull { it.volume }?.uid.orEmpty()))
            host.onActiveSpeakers(speakers)
        }

        override fun onNetworkQuality(entries: List<IMNetworkQuality>) = host.onNetworkQuality(entries)

        override fun onCallMediaTypeChanged(callId: String, from: String, to: String) =
            host.onCallMediaTypeChanged(callId, from, to)

        override fun onFirstVideoFrame(uid: String) {
            // 第一帧到了，界面撤 loading：让格子重画一次就够。
            update(state)
            host.onFirstVideoFrame(uid)
        }

        override fun onRoomJoined(roomId: String) {
            update(IMCallViewReducer.connected(state))
            startTimer()
            host.onRoomJoined(roomId)
        }

        override fun onRoomLeft(roomId: String) {
            stopTimer()
            update(IMCallViewReducer.reset())
            host.onRoomLeft(roomId)
        }

        override fun onRoomClosed(roomId: String, reason: String) {
            stopTimer()
            update(IMCallViewReducer.ended(state, reason))
            main.postDelayed({ update(IMCallViewReducer.reset()) }, 1_500)
            host.onRoomClosed(roomId, reason)
        }
    }
}

/** 通话全屏页。**独立 Activity，不入宿主导航栈**——任何界面都能被来电覆盖。 */
class IMCallActivity : Activity() {

    private lateinit var view: IMCallView
    private val observer: (IMCallViewState) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        view = IMCallView(this)
        view.actions = object : IMCallView.Actions {
            override fun onAnswer() { IMCallKit.answer() }
            override fun onHangup() { IMCallKit.hangup() }
            override fun onToggleMic() { IMCallKit.toggleMic() }
            override fun onToggleCamera() { IMCallKit.toggleCamera() }
            override fun onToggleSpeaker() { IMCallKit.toggleSpeaker() }
            override fun onSwitchCamera() { IMCallKit.switchCamera() }
            override fun onMinimize() { IMCallKit.minimize() }
        }
        setContentView(view)
        IMCallKit.observe(observer)
    }

    override fun onDestroy() {
        IMCallKit.forget(observer)
        super.onDestroy()
    }

    /** 通话中禁用返回键：误触退出通话是最容易被骂的一件事。挂断请点红键。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (IMCallKit.state.phase == IMCallViewState.Phase.IDLE) super.onBackPressed()
    }

    private fun render(state: IMCallViewState) {
        view.render(state) { uid -> IMCallKit.videoViewFor(this, uid) }
        // 收进小窗 = 关掉全屏页（通话照常）。**不能只是隐藏**：留着它，宿主的界面
        // 还是被盖着的，悬浮球也就无从谈起。
        if ((state.phase == IMCallViewState.Phase.IDLE || state.isMinimized) && !isFinishing) finish()
    }
}
