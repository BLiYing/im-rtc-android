package com.imrtc.uikit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.log.IMRTCLog

/**
 * **整套通话 UI 的入口。宿主一行接管：`IMCallKit.start(context, engine)`。**
 *
 * Kit 只消费公开回调表（[IMCallEngineListener]），**没有任何私有通道**——
 * 宿主自画 UI 能拿到的信息与它完全一致。这是「两种集成方式能力对等」的唯一保证。
 *
 * 它做四件事：
 * 1. 把 Engine 的回调折成 [IMCallViewState]（纯值，可单测；接线在 [IMKitListener]）；
 * 2. 按当前状态**决定用哪种呈现形态**——全屏页 / 来电横幅 / 悬浮球 / 系统画中画，见 [desiredMode]；
 * 3. 把界面上的点击翻译回 Engine 的方法调用；
 * 4. 拨出 / 接听之前先过权限门（交互稿 §01–§02）：拿不到麦克风就不该去响别人的铃。
 */
object IMCallKit {

    internal val main = Handler(Looper.getMainLooper())

    @Volatile
    internal var engine: IMCallEngine? = null
    private var appContext: Context? = null

    /** Kit 的可配项。宿主可以随时改，下一次形态切换就读到新值。 */
    @JvmStatic
    var config: IMCallKitConfig = IMCallKitConfig()
        private set

    /** 横幅 / 悬浮球都挂在这上面（应用内浮层，不申请 SYSTEM_ALERT_WINDOW）。 */
    private val overlay = IMCallOverlay()

    /** 横幅已经被用户点开过（或 5s 到点自动升级）。**它必须独立于 [IMCallViewState]**，否则界面来回跳。 */
    private var bannerExpanded = false
    private var mode = Mode.HIDDEN

    private enum class Mode { HIDDEN, BANNER, BUBBLE, FULLSCREEN }

    @Volatile
    internal var state: IMCallViewState = IMCallViewState()
        private set

    private var timer: Runnable? = null
    private var hintExpiry: Runnable? = null
    /** 最后一批邀请出去的 uid。加人被服务端拒时用它把占位格收回来。 */
    private var lastInvited: List<String> = emptyList()
    private val settleTimers = HashMap<String, Runnable>()
    private val observers = mutableListOf<(IMCallViewState) -> Unit>()

    /** 权限门的系统探针。默认拉透明 Activity 去问；测试可换。 */
    internal var asker: IMPermissionGate.Asker? = null

    /** 本通电话里每个 uid 的渲染器。**同一个 uid 反复要拿到的是同一个 View**，否则每次刷新都重建、画面闪。 */
    private val remoteViews = HashMap<String, View>()
    private var localPreview: View? = null
    private var localPreviewStarted = false

    /**
     * 接管通话 UI。**在 login 之前调**——来电随时可能到。
     * 传进来的 `engine` 的 listener 要先经 [wrap] 包一层：宿主自己的 listener 照常收到全部回调，Kit 只是搭个便车。
     */
    @JvmOverloads
    @JvmStatic
    fun start(context: Context, engine: IMCallEngine, config: IMCallKitConfig = IMCallKitConfig()) {
        this.appContext = context.applicationContext
        this.engine = engine
        this.config = config
        if (asker == null) asker = IMPermissionActivity.asker(context.applicationContext)
        (context.applicationContext as? Application)?.let { IMActivityTracker.install(it) }
        IMActivityTracker.onForegroundChanged = { foreground -> onForegroundChanged(foreground) }
    }

    @JvmStatic
    fun stop() {
        engine = null
        stopTimer()
        clearSettleTimers()
        hintExpiry?.let { main.removeCallbacks(it) }
        hintExpiry = null
        lastInvited = emptyList()
        state = IMCallViewReducer.reset()
        main.post { overlay.detach() }
        mode = Mode.HIDDEN
        bannerExpanded = false
        remoteViews.clear()
        localPreview = null
        localPreviewStarted = false
    }

    /** 宿主把自己的 listener 交给它包一层，Kit 借此拿到全部事件。**不给 Kit 开第二个 listener 口子**——那等于私有通道。 */
    @JvmStatic
    fun wrap(host: IMCallEngineListener): IMCallEngineListener = IMKitListener(host)

    // ── 宿主能调的三个动作 ────────────────────────────────────────────

    /**
     * 拨出。**先过权限门再发 invite**（交互稿 §01）：拿不到麦克风就不该去响别人的铃；
     * 摄像头拿不到就降级为语音继续（`cameraBlocked`）。界面先切到「正在呼叫…」，权限卡叠在它上面。
     */
    @JvmOverloads
    @JvmStatic
    fun placeCall(peers: List<String>, mediaType: String, isGroup: Boolean = false) {
        val instance = engine ?: return
        update(IMCallViewReducer.outgoing(state, peers, mediaType, isGroup))
        ensurePermissions(IMPermissionGate.devicesFor(mediaType, withCamera = true)) { outcome ->
            when (outcome) {
                IMPermissionGate.Outcome.OK -> {
                    // 摄像头到手了才接采集——**拨出中就该看见自己**（草图 §03-E）。
                    onLocalMediaStarted()
                    instance.call(peers, mediaType, isGroup)
                }
                IMPermissionGate.Outcome.CAMERA_BLOCKED -> {
                    update(IMCallViewReducer.cameraBlocked(state))
                    instance.call(peers, mediaType, isGroup)
                }
                IMPermissionGate.Outcome.MIC_BLOCKED, IMPermissionGate.Outcome.CANCELLED -> update(IMCallViewReducer.reset())
            }
        }
    }

    /** 进会议房（不走振铃）。同样先过权限门。 */
    @JvmStatic
    fun joinMeeting(roomId: String, roomToken: String) {
        val instance = engine ?: return
        ensurePermissions(IMPermissionGate.devicesFor("video", withCamera = true)) { outcome ->
            if (outcome == IMPermissionGate.Outcome.MIC_BLOCKED || outcome == IMPermissionGate.Outcome.CANCELLED) return@ensurePermissions
            update(IMCallViewReducer.meeting(state, roomId))
            if (outcome == IMPermissionGate.Outcome.CAMERA_BLOCKED) {
                update(IMCallViewReducer.cameraBlocked(state))
            } else {
                onLocalMediaStarted()
            }
            instance.joinRoom(roomId, roomToken)
        }
    }

    /** 宿主自己调了 `engine.call` 的话，用这一条把拨出界面拉起来（回调里只有被叫侧的信息）。 */
    @JvmStatic
    fun notifyOutgoing(peers: List<String>, mediaType: String, isGroup: Boolean) {
        update(IMCallViewReducer.outgoing(state, peers, mediaType, isGroup))
    }

    /** 宿主自己调了 `engine.joinRoom` 时同理。 */
    @JvmStatic
    fun notifyMeeting(roomId: String) {
        update(IMCallViewReducer.meeting(state, roomId))
    }

    private fun ensurePermissions(devices: List<IMPermissionGate.Device>, done: (IMPermissionGate.Outcome) -> Unit) {
        val asker = asker ?: IMPermissionGate.Asker { _, cb -> cb(IMPermissionGate.Result.GRANTED) }
        IMPermissionGate.ensure(devices, asker) { outcome -> main.post { done(outcome) } }
    }

    internal fun observe(observer: (IMCallViewState) -> Unit) {
        observers += observer
        observer(state)
    }

    internal fun forget(observer: (IMCallViewState) -> Unit) {
        observers -= observer
    }

    // ── 界面上的动作 ──────────────────────────────────────────────────

    /** 远端渲染器：一个 uid 一个，整通复用。 */
    internal fun videoViewFor(context: Context, uid: String): View? {
        remoteViews[uid]?.let { return it }
        val view = engine?.createVideoView(context.applicationContext) ?: return null
        engine?.attachView(uid, view)
        remoteViews[uid] = view
        return view
    }

    /**
     * 本端预览的渲染器。**造出来就当场接上采集**（`startLocalPreview` 自己会把摄像头开起来）。
     *
     * 原先是「造一个空视图，等 onRoomJoined 再接」，而那一步要拿前台 Activity——
     * 可通话页 [IMCallActivity] 一起来，宿主的 Activity 就 pause 了，
     * `IMActivityTracker.foreground()` 返回 null（它刻意不认自己家的通话页），
     * 于是 `startLocalPreview` **一次都没被调用过**：真机上「别人看得见我，我自己看不见我」。
     */
    internal fun localPreviewView(context: Context): View? {
        val instance = engine ?: return null
        val view = localPreview
            ?: instance.createVideoView(appContext ?: context.applicationContext)?.also { localPreview = it }
            ?: return null
        /*
         **摄像头权限没到手之前不许接采集。** 预览自己会开摄像头，而拨出时界面先切到
         「正在呼叫…」、权限卡叠在它上面——这一步比权限门先跑。抢在授权之前开摄像头，
         轻则拿不到设备被记成 `2002 device_not_found`，重则把权限门的三段式整个绕过去。
         授权通过后 `onLocalMediaStarted()` 会再来一次，那时才真的接上。
        */
        if (!localPreviewStarted && cameraGranted()) {
            instance.startLocalPreview(view)
            localPreviewStarted = true
        }
        return view
    }

    /** 摄像头权限到手没有。没有 Context 时保守当作没有。 */
    private fun cameraGranted(): Boolean {
        val context = appContext ?: return false
        return IMPermissionActivity.isGranted(context, IMPermissionGate.Device.CAMERA)
    }

    /**
     * 本端此刻有没有画面可显示。
     *
     * **判据只有「摄像头开着且没被拒」**——不再等「进房发布之后」：预览自己会起采集
     * （`IMWebRTCAdapter.startLocalPreview`），所以拨出中就该看见自己（草图 §03-E）。
     */
    internal fun hasLocalVideo(): Boolean = state.cameraOn && !state.cameraBlocked

    /**
     * 接听。**先过权限门再发 accept**——先 accept 再发现没权限，对方那边已经接通了却听不到人。
     * 来电页上关掉了摄像头就只问麦克风（= 以语音接听）。接不了就拒掉，别让对方一直等。
     */
    internal fun answer() {
        val instance = engine ?: return
        ensurePermissions(IMPermissionGate.devicesFor(state.mediaType, withCamera = state.cameraOn)) { outcome ->
            when (outcome) {
                IMPermissionGate.Outcome.OK -> { onLocalMediaStarted(); instance.accept() }
                IMPermissionGate.Outcome.CAMERA_BLOCKED -> { update(IMCallViewReducer.cameraBlocked(state)); instance.accept() }
                IMPermissionGate.Outcome.MIC_BLOCKED, IMPermissionGate.Outcome.CANCELLED -> instance.reject()
            }
        }
    }

    internal fun hangup() {
        when (state.hangupAction) {
            // **会议房里没有 call，结束动作是 leaveRoom**。红按钮无条件走 hangup 的话，通话机会把它本地拒成 2005。
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

    /** 禁用态点了要出提示，不能静默（规范 §06）。 */
    internal fun toggleCamera() {
        if (state.cameraBlocked) { hint("没有摄像头权限"); return }
        val next = !state.cameraOn
        if (next) engine?.openCamera() else engine?.closeCamera()
        update(IMCallViewReducer.toggleCamera(state))
    }

    internal fun toggleSpeaker() {
        val next = !state.speakerOn
        engine?.setSpeakerOn(next)
        update(IMCallViewReducer.toggleSpeaker(state))
    }

    /** 互换 1v1 的两块画面（交互稿 §04）。纯本端行为，不发帧。 */
    internal fun swap() = update(IMCallViewReducer.setSwapped(state, !state.isSwapped))

    /** 前后摄像头翻转。纯媒体动作，不改视图状态。 */
    internal fun switchCamera() {
        engine?.switchCamera()
    }

    /**
     * 往群通话里加人：占位格**立刻**出现，帧随后才发（交互稿 §05 G3）。
     *
     * 记下这一批是谁：服务端拒掉（1407 非主叫 / 1202 满员）时不会有 `onUserReject`——
     * 那条是给「真的响了铃的人」的。不收回占位格的话它们会一直挂着「呼叫中…」，还占着人数，
     * 让「还能加 N 人」和九宫格的行列都算错。
     */
    internal fun inviteMore(uids: List<String>) {
        if (uids.isEmpty()) return
        lastInvited = uids
        update(IMCallViewReducer.invited(state, uids))
        engine?.inviteMore(uids)
    }

    /** 把最后一批邀请的占位格收回来（加人被服务端拒时）。 */
    internal fun revokeLastInvite() {
        val uids = lastInvited
        lastInvited = emptyList()
        var next = state
        for (uid in uids) {
            if (next.members[uid]?.accepted == true) continue
            next = IMCallViewReducer.userRemove(next, uid)
        }
        if (next !== state) update(next)
    }

    /**
     * 提示（「通话已满员」「对方已拒接」）**停几秒就撤**。
     *
     * `statusText` 里 hint 优先于时长，不撤的话「通话已满员」会顶着标题栏直到通话结束，
     * 计时器再也不出现（规范 §08：这些是 toast，不是常驻状态）。
     */
    internal fun hint(text: String) {
        update(IMCallViewReducer.hint(state, text))
        hintExpiry?.let { main.removeCallbacks(it) }
        hintExpiry = null
        if (text.isEmpty()) return
        val expire = Runnable {
            // 只清掉自己那条：中途又来一条新提示时，不该被上一条的计时器抹掉。
            if (state.hint == text) update(IMCallViewReducer.hint(state, ""))
        }
        hintExpiry = expire
        main.postDelayed(expire, IMKitTheme.HINT_HOLD_MS)
    }

    internal fun showInvitePicker(activity: Activity) {
        IMInvitePicker(activity, config.inviteCandidates, state.members.keys, state.inviteSlotsLeft) { inviteMore(it) }.show()
    }

    /** 收进小窗。接通之前不许收，见 [IMCallViewState.canMinimize]。 */
    internal fun minimize() = update(IMCallViewReducer.minimize(state))

    /**
     * App 切到后台 / 回到前台（交互稿 §03）。
     *
     * **后台不允许继续采集摄像头**（Android 从 9 开始就是这条规矩，各家 ROM 更严），
     * 对端看到的就是一片黑——比看到头像糟糕得多。所以进后台把摄像头轨道 mute 掉，
     * 对端收到「摄像头已关闭」、看到头像；回前台**恢复到用户原来的选择**：
     * 他进后台前本来就关着摄像头，回前台不要替他打开。与 iOS 的 `IMCallController` 同一条规则。
     *
     * 进系统画中画不算切后台：那时候采集照跑，画面就在那一小块窗口里。
     */
    private fun onForegroundChanged(foreground: Boolean) {
        val instance = engine ?: return
        if (!foreground) {
            if (state.phase == IMCallViewState.Phase.IDLE || !state.cameraOn) return
            cameraPausedByBackground = true
            instance.closeCamera()
            return
        }
        if (!cameraPausedByBackground) return
        cameraPausedByBackground = false
        if (state.cameraOn) instance.openCamera()
    }

    /** 摄像头是**因为切后台**才关的——只有这种情况回前台才自动打开。 */
    private var cameraPausedByBackground = false

    /** 从小窗 / 横幅展开回全屏。 */
    internal fun expand() {
        bannerExpanded = true
        update(IMCallViewReducer.expand(state))
    }

/**
     * 本端采集起来了。**必须无条件通知界面**：cid 不在 state 里，状态相等时界面不会自己刷。
     *
     * 不再需要「前台 Activity」——渲染器用 applicationContext 造得出来，
     * 而拿前台 Activity 恰恰是拿不到的（通话页一起来宿主那个就 pause 了）。
     */
    internal fun onLocalMediaStarted() {
        appContext?.let { localPreviewView(it) }
        update(state)
    }

    internal fun update(next: IMCallViewState) {
        state = next
        main.post {
            observers.toList().forEach { it(next) }
            applyPresentation(next)
            scheduleSettledRemovals(next)
        }
    }

    // ── 呈现形态：全屏 / 横幅 / 悬浮球 ────────────────────────────────

    /** 按当前状态决定用哪种形态，并把上一种收掉。**每次状态更新都会走一遍**，所以它必须便宜且幂等。 */
    private fun applyPresentation(current: IMCallViewState) {
        if (current.phase == IMCallViewState.Phase.IDLE) { bannerExpanded = false; clearCallViews() }
        val host = IMActivityTracker.foreground()
        val wanted = desiredMode(current, host)
        val changed = wanted != mode
        mode = wanted
        when (wanted) {
            Mode.HIDDEN -> if (changed) overlay.detach()
            Mode.FULLSCREEN -> { overlay.detach(); if (changed) present() }
            Mode.BANNER -> mountBanner(host, current)
            Mode.BUBBLE -> mountBubble(host, current)
        }
        if (changed) IMRTCLog.i("kit", "通话界面形态：${wanted.name.lowercase()}")
    }

    /**
     * 形态判定。顺序有讲究，**小窗优先于横幅**：来电时不可能是小窗（还没接通），反过来接通后也不该再出横幅。
     * 在系统画中画里就是 FULLSCREEN（那个 Activity 还活着），别往宿主界面上再挂一个悬浮球。
     */
    private fun desiredMode(current: IMCallViewState, host: Activity?): Mode = when {
        current.phase == IMCallViewState.Phase.IDLE -> Mode.HIDDEN
        current.isMinimized && config.floatingWindow -> if (host != null) Mode.BUBBLE else Mode.HIDDEN
        current.phase == IMCallViewState.Phase.INCOMING && config.bannerFirst && !bannerExpanded && host != null -> Mode.BANNER
        else -> Mode.FULLSCREEN
    }

    private fun mountBanner(host: Activity?, current: IMCallViewState) {
        val banner = overlay.mount(
            host, IMIncomingBanner::class.java,
            { activity ->
                IMIncomingBanner(activity).apply {
                    onAccept = { answer() }
                    onReject = { hangup() }
                    onExpand = { expand() }
                    onToggleCamera = { toggleCamera() }
                }
            },
            { activity -> IMCallOverlay.bannerParams(activity) },
        )
        banner?.render(current)
    }

    private fun mountBubble(host: Activity?, current: IMCallViewState) {
        val bubble = overlay.mount(
            host, IMFloatingBubble::class.java,
            { activity -> IMFloatingBubble(activity).apply { onExpand = { expand() }; onHangup = { hangup() } } },
            { activity -> IMFloatingBubble.initialParams(activity) },
        )
        bubble?.render(current)
        // 视频通话的悬浮球放主讲人的缩略画面（规范 §06）。
        if (bubble != null && host != null && current.mediaType == "video") {
            val speaker = current.speakingUid.ifEmpty { current.members.keys.firstOrNull().orEmpty() }
            bubble.setVideoView(if (speaker.isEmpty()) null else videoViewFor(host, speaker))
        }
    }

    private fun present() {
        val context = appContext ?: return
        context.startActivity(Intent(context, IMCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 邀请中的格子拿到终局（已拒绝 / 未接听）后停 2s 再收（交互稿 §05 G3）。 */
    private fun scheduleSettledRemovals(current: IMCallViewState) {
        current.members.values.filter { it.settled != IMCallViewState.Settled.NONE && it.uid !in settleTimers }.forEach { member ->
            val remove = Runnable {
                settleTimers.remove(member.uid)
                update(IMCallViewReducer.userRemove(state, member.uid))
            }
            settleTimers[member.uid] = remove
            main.postDelayed(remove, IMKitTheme.SETTLED_HOLD_MS)
        }
    }

    private fun clearSettleTimers() {
        settleTimers.values.forEach { main.removeCallbacks(it) }
        settleTimers.clear()
    }

    private fun clearCallViews() {
        remoteViews.keys.forEach { engine?.attachView(it, null) }
        remoteViews.clear()
        localPreview = null
        localPreviewStarted = false
        clearSettleTimers()
    }

    internal fun startTimer() {
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

    internal fun stopTimer() {
        timer?.let { main.removeCallbacks(it) }
        timer = null
    }
}
