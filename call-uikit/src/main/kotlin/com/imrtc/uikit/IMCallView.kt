package com.imrtc.uikit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 通话界面本体，三种版式（规范 §03 / §04）：
 * · **AUDIO**：语音通话、拨出中 —— 96 头像 + 名字 + 状态（拨出视频时右上叠本端预览）；
 * · **VIDEO**：1v1 视频通话中 —— 远端全屏 + 本端小窗，单击小窗互换，控制条 3s 自动隐藏；
 * · **GRID**：群通话 / 会议 —— 九宫格（加人入口只在标题栏右上角那一颗）。
 *
 * **三段式**：头部与控制条钉死高度（64 / 96），中间那段 `weight=1` 吃掉剩下的全部——一个未知高度，不会欠定。
 * 用代码搭而不是 XML：Kit 是要塞进别人 App 的库，少一批 layout 资源就少一批与宿主重名的风险。
 */
internal class IMCallView(context: Context) : FrameLayout(context) {

    /** 界面上的动作，交给 [IMCallKit] 去调 Engine。UI 自己不认识 Engine。 */
    interface Actions {
        fun onAnswer()
        fun onHangup()
        fun onToggleMic()
        fun onToggleCamera()
        fun onToggleSpeaker()
        fun onSwitchCamera()
        fun onMinimize()
        fun onSwap()
        fun onInvite()
        /** 要一个远端渲染器。同一个 uid 反复要拿到的是同一个 View。 */
        fun videoViewFor(uid: String): View?

        /**
         * 这个人的格子不要了：**在 Engine 那一侧也解绑**。
         *
         * 只把 View 从格子上摘掉是不够的——渲染器还挂在 `engine.attachView(uid, …)` 上，
         * 解码器跟着一直占着，直到整通电话结束才随 `clearCallViews` 一起清。
         * iOS 的 `retireTiles` 是调 `controller.attachView(uid, to: nil)` 的，这里补齐。
         */
        fun releaseVideoView(uid: String)
        /** 要本端预览的渲染器。 */
        fun localPreviewView(): View?
        fun hasLocalVideo(): Boolean

        /**
         * 报某个远端画面的**层上界**（协议 §3.5）。格子越小报得越低，直接省带宽。
         *
         * **漏报的代价是隐形的**：服务端按默认的 `m` 给每一路下发，九宫格里八个小格子
         * 每格都收半高清，带宽与解码器一起翻几倍，症状是「画面卡、掉帧」而不是任何一条报错。
         */
        fun reportLayer(uid: String, layer: String)
    }

    var actions: Actions? = null

    private val column = LinearLayout(context)
    private val header = IMCallHeader(context)
    private val banner = IMTopBanner(context)
    private val stage = FrameLayout(context)
    private val audioStage = IMAudioStage(context)
    private val grid = IMCallGridView(context)
    private val hiddenPill = IMHiddenCountPill(context)
    private val pip = IMPipView(context)
    private val endedLabel = TextView(context)
    private val controlsScrim = View(context)
    private val controls = LinearLayout(context)
    /** 控制条上排：三个开关。 */
    private val controlsTop = LinearLayout(context)
    /** 控制条下排：挂断居中 + 翻转摄像头。 */
    private val controlsBottom = LinearLayout(context)
    private val selfTile = IMVideoTile(context)
    /** uid → 这个人的格子。**不每次重建**：重建会让媒体层挂着的渲染器重来，画面会闪。 */
    private val tiles = LinkedHashMap<String, IMVideoTile>()
    private var fullTile: IMVideoTile? = null

    private val micButton = IMControlButton(context, IMKitIcon.MIC, "静音", IMKitIcon.MIC_SLASH, "已静音")
    private val cameraButton = IMControlButton(context, IMKitIcon.VIDEO_SLASH, "开摄像头", IMKitIcon.VIDEO, "关摄像头")
    private val speakerButton = IMControlButton(context, IMKitIcon.SPEAKER, "扬声器")
    private val switchCameraButton = IMControlButton(context, IMKitIcon.CAMERA_FLIP, "翻转")
    /** 下排左边那个空位：有它挂断才真的在屏幕正中。 */
    private val spacer = View(context)
    private val hangupButton = IMControlButton(context, IMKitIcon.PHONE_DOWN, "挂断", role = IMControlButton.Role.DANGER)
    private val answerButton = IMControlButton(context, IMKitIcon.PHONE, "接听", role = IMControlButton.Role.ACCEPT)
    private val rejectButton = IMControlButton(context, IMKitIcon.XMARK, "拒绝", role = IMControlButton.Role.DANGER)

    private val main = Handler(Looper.getMainLooper())
    private var layout = IMCallViewState.Layout.AUDIO
    private var state = IMCallViewState()

    /** 控制条的「3s 后淡出、任意触摸恢复」。判据与 iOS 对齐，细节全在 [IMChromeGate]。 */
    private val chrome = IMChromeGate(
        main = main,
        faded = listOf(header, controls, controlsScrim),
        gated = listOf(header, controls),
        canAutoHide = {
            layout == IMCallViewState.Layout.VIDEO && state.phase == IMCallViewState.Phase.CONNECTED
        },
        onChanged = { visible -> pip.liftsForControls = visible && layout == IMCallViewState.Layout.VIDEO },
    )

    /**
     * 全屏画面的宿主：**根布局最底下一层，铺满整屏**（含状态栏与手势条那两条）。
     *
     * 原先全屏画面钉在 `stage` 里，而 stage 是「头部下方、控制条上方」那一块——
     * 于是视频顶上顶着一条黑边、底下再一条，真机上看着就是「没有全屏」。
     * 现在头部与控制条浮在画面上（它们自带 scrim），画面自己铺满。
     */
    private val videoFull = FrameLayout(context)

    init {
        setBackgroundColor(IMKitTheme.background)
        addView(videoFull, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.orientation = LinearLayout.VERTICAL
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(
            header,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(IMKitTheme.HEADER_HEIGHT_DP))
                .apply { topMargin = dp(HEADER_TOP_MARGIN_DP) },
        )
        column.addView(stage, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        stage.addView(audioStage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 九宫格整块居中（与 iOS 一样）：格子是正方形，剩下的空间摊在四周，不摊进格子里。
        stage.addView(
            grid,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                .apply { setMargins(dp(12), dp(4), dp(12), dp(4)) },
        )
        endedLabel.textSize = 17f
        endedLabel.setTextColor(IMKitTheme.primaryText)
        endedLabel.gravity = Gravity.CENTER
        stage.addView(endedLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) })
        stage.addView(pip, LayoutParams(dp(96), dp(128)))
        stage.addView(hiddenPill, IMHiddenCountPill.layoutParams(hiddenPill))
        // 单击画面空白处：显示 / 隐藏控制条（视频版式才生效）。
        stage.setOnClickListener { if (layout == IMCallViewState.Layout.VIDEO) chrome.set(!chrome.visible) }

        controlsScrim.background = IMKitTheme.controlsScrim()
        addView(controlsScrim, LayoutParams(LayoutParams.MATCH_PARENT, dp(160), Gravity.BOTTOM))
        controlsScrim.visibility = GONE
        /*
         橙条挂在根布局而不是 column 里：它要浮在画面之上，进了 column 会把 stage 顶下去。
         代价是**系统栏留白得自己算**——[onApplyWindowInsets] 只给 column 与 controls 打了
         padding，漏了这里，全面屏上橙条就钻到状态栏/刘海底下去了（真机 2026-09-09）。
         位置在 [bannerTopMargin] 里统一算：标题栏下方，而不是和标题抢同一条。
        */
        addView(
            banner,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = bannerTopMargin(0) },
        )
        // 控制条放在最上层（它在 column 里会被 scrim 盖住），所以直接挂在根布局上。
        /*
         控制条**两排**（v3.2）：上排是三个开关（静音 / 摄像头 / 扬声器），
         下排是「挂断居中 + 翻转摄像头在它右边」。

         下排用三格等宽：左边一格空着，挂断占中间那格所以**真的在屏幕正中**，
         翻转占右边那格。少了左边那个占位，挂断就会偏左——红键偏了最容易点错。
        */
        controls.orientation = LinearLayout.VERTICAL
        controls.gravity = Gravity.CENTER_HORIZONTAL
        controlsTop.orientation = LinearLayout.HORIZONTAL
        controlsTop.gravity = Gravity.CENTER or Gravity.TOP
        controlsBottom.orientation = LinearLayout.HORIZONTAL
        controlsBottom.gravity = Gravity.CENTER or Gravity.TOP
        controls.addView(controlsTop, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        controls.addView(
            controlsBottom,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) },
        )
        addView(
            controls,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { bottomMargin = dp(CONTROLS_BOTTOM_DP) },
        )

        header.minimizeButton.setOnClickListener { actions?.onMinimize() }
        header.inviteButton.setOnClickListener { actions?.onInvite() }
        micButton.setOnClickListener { actions?.onToggleMic() }
        cameraButton.setOnClickListener { actions?.onToggleCamera() }
        speakerButton.setOnClickListener { actions?.onToggleSpeaker() }
        switchCameraButton.setOnClickListener { actions?.onSwitchCamera() }
        hangupButton.setOnClickListener { actions?.onHangup() }
        answerButton.setOnClickListener { actions?.onAnswer() }
        rejectButton.setOnClickListener { actions?.onHangup() }
        pip.onTap = { if (layout == IMCallViewState.Layout.VIDEO) actions?.onSwap() }
    }

    /**
     * 让开状态栏与导航栏。
     *
     * Activity 是边到边的（[IMCallActivity.goFullScreen]），不让的话「对方名字 + 时长」那一行
     * 直接压在状态栏的时间和电量上，而底部的挂断键会被手势条盖掉一半。
     * **只给根容器加 padding**，版式代码一行不用改。
     */
    /**
     * 橙条的上边距：**标题栏下方**，不与标题和通话时长抢同一条。
     *
     * `padTop` 是系统栏留白（全面屏上就是状态栏/刘海的高度）；
     * 再往下让过 header 的上边距与它自身的高度，最后留一个 [BANNER_TOP_GAP_DP] 的间隙。
     */
    private fun bannerTopMargin(padTop: Int): Int =
        padTop + dp(HEADER_TOP_MARGIN_DP + IMKitTheme.HEADER_HEIGHT_DP + BANNER_TOP_GAP_DP)

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        val top: Int
        val bottom: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            top = bars.top
            bottom = bars.bottom
        } else {
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
        }
        val padTop = top
        val padBottom = bottom
        // **只让开「壳」，不让开画面**：留白打在根布局上的话，全屏画面也会被一起顶下去，
        // 顶上顶着一条黑边——那正是「没有全屏」的样子。
        (column.layoutParams as? LayoutParams)?.let { it.topMargin = padTop; column.layoutParams = it }
        // 橙条不在 column 里，系统栏留白得单独打给它——漏了这一条它就钻进状态栏。
        (banner.layoutParams as? LayoutParams)?.let {
            it.topMargin = bannerTopMargin(padTop)
            banner.layoutParams = it
        }
        (controls.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = dp(CONTROLS_BOTTOM_DP) + padBottom
            controls.layoutParams = it
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 控制条的高度会随按钮组变（来电两颗 / 通话中两排），所以每轮都要对一次，不只是 changed。
        applyStageInsets()
        if (!changed) return
        pip.layoutInContainer()
        // 第一轮 render 时 stage 还没量出来，格子边长只能按默认形状估。这里补摆一次。
        if (layout == IMCallViewState.Layout.GRID && grid.tiles.isNotEmpty()) {
            post { layoutGrid(grid.tiles) }
        }
    }

    /**
     * 把控制条占的那一条从舞台区里让出来。
     *
     * **`stage` 的下边界就是屏幕下边界**：控制条为了浮在全屏画面上，是直接挂在根布局上的
     * （见 init 里那段注释），不在 `column` 里。于是九宫格「在 stage 里居中」= 在整块屏幕里居中，
     * 最后一行正好被按钮压住——而 iOS 的 stage 钉的是 `controlsStack.topAnchor`、
     * Web 的 ControlBar 是 flex 的兄弟节点，两端的格子都在按钮上方。
     * 舞台区形状差这一条，`IMGrid.dimensions` 拿到的 aspect 就从 0.68 掉到 0.48，
     * 连行列都跟着算错（三个人排成一竖条）。
     *
     * 只有语音页与九宫格要让：视频版式的画面挂在 `videoFull` 上、本来就该铺满，
     * 控制条浮在它上面还会 3s 自动隐藏。
     */
    private fun applyStageInsets() {
        val reserved = if (layout == IMCallViewState.Layout.VIDEO || controls.visibility != VISIBLE) {
            0
        } else {
            // stage 的底边与根布局的底边重合（column 是 MATCH_PARENT、stage 是最后一个 weight=1 的孩子），
            // 所以「控制条上沿到屏幕底边」就是要让开的高度，已含它自己的下边距与手势条 inset。
            (height - controls.top).coerceAtLeast(0)
        }
        if (stage.paddingBottom == reserved) return
        // **改 padding 会再触发一轮布局**，而这里正在布局里。挪到下一帧做，
        // 顺带避开「requestLayout() improperly called during layout」那条告警。
        post { if (stage.paddingBottom != reserved) stage.setPadding(0, 0, 0, reserved) }
    }

    fun render(state: IMCallViewState) {
        this.state = state
        /*
         **通话已经收场了就不要再画一遍。**

         复位后的状态是一个全默认的 `IMCallViewState`（`isGroup=false`、`mediaType="audio"`、
         `phase=IDLE`），而 `isEnded` 只认 ENDED——照常走下去就会按**语音通话中**渲染一屏：
         大头像 + 标题「通话」+ 静音/扬声器/挂断三颗按钮。而 `IMCallActivity` 是先 render
         再 finish，退出动画那两三百毫秒里这一屏是完整可见的：九宫格通话结束时版式还会从
         GRID 整个跳成 AUDIO，就是用户报的「多了一个画面，闪一下看不清」。
         Web 的 `CallOverlay` 第一句就是 `if (phase === 'idle') return null`，
         iOS 的 `IMCallWindow` 在 idle 时同步把整个 window 置 nil——两端都不给这一帧机会。
        */
        if (state.phase == IMCallViewState.Phase.IDLE) return
        val hasLocalVideo = actions?.hasLocalVideo() ?: false
        layout = state.layout
        val isEnded = state.phase == IMCallViewState.Phase.ENDED
        val peerLevel = if (state.isGroup) 0 else (state.members.values.firstOrNull()?.networkLevel ?: 0)
        /*
         **呼叫中与来电页的标题栏留空。** 那两屏的正中间已经是「大头像 + 名字 + 状态」，
         顶部再写一遍同样的名字和同一行状态，同一句话在一屏里出现两次。
         接通之后才有真正只属于顶栏的信息（对方名字 + 计时器 + 网络条）。
        */
        val bare = state.phase == IMCallViewState.Phase.INCOMING || state.phase == IMCallViewState.Phase.OUTGOING
        header.apply(
            if (bare) "" else state.titleText, if (bare) "" else state.statusText,
            if (state.phase == IMCallViewState.Phase.CONNECTED) peerLevel else 0,
            showsMinimize = state.canMinimize && IMCallKit.config.floatingWindow,
            showsInvite = state.canShowInvite,
        )
        background = if (layout == IMCallViewState.Layout.VIDEO && !isEnded) null else IMKitTheme.callBackground()
        if (background == null) setBackgroundColor(IMKitTheme.background)
        endedLabel.visibility = if (isEnded) VISIBLE else GONE
        endedLabel.text = state.statusText
        audioStage.visibility = if (layout == IMCallViewState.Layout.AUDIO && !isEnded) VISIBLE else GONE
        grid.visibility = if (layout == IMCallViewState.Layout.GRID && !isEnded) VISIBLE else GONE
        if (grid.visibility == GONE) hiddenPill.show(0)
        controlsScrim.visibility = if (layout == IMCallViewState.Layout.VIDEO && !isEnded) VISIBLE else GONE
        renderBanner(state)
        renderControls(state, isEnded)
        if (isEnded) {
            pip.visibility = GONE
            unpinFull()
            // 结束画面要停 1.5~3s，**这期间远端渲染器没有任何用处**，占着解码器不放。
            retireTiles(emptySet())
            return
        }
        when (layout) {
            IMCallViewState.Layout.AUDIO -> renderAudio(state, hasLocalVideo)
            IMCallViewState.Layout.VIDEO -> renderVideo(state)
            IMCallViewState.Layout.GRID -> renderGrid(state)
        }
        if (layout != IMCallViewState.Layout.VIDEO) chrome.set(visible = true, arm = false) else if (chrome.visible) chrome.armAutoHide()
    }

    /** 橙条：文案怎么定见 [IMBannerRules]，这里只管把它写上去、以及给「网络不佳」那条排定时器。 */
    private fun renderBanner(state: IMCallViewState) {
        val poor = !state.isGroup && state.members.values.any { IMCallViewState.isNetworkPoor(it.networkLevel) } // 只做 1v1
        if (!poor) poorShown = false
        val next = IMBannerRules.next(state.connection, poor, poorShown, bannerText) ?: return
        if (next == IMBannerRules.POOR) {
            poorShown = true
            // 定时器**只撤自己那条**：这 2s 里连接可能已经断了，那时橙条上写的是
            // 「正在重连…」，不认一下就会把它一起抹掉。
            main.postDelayed(
                { if (bannerText == IMBannerRules.POOR) applyBanner("") },
                IMKitTheme.NETWORK_BANNER_MS,
            )
        }
        applyBanner(next)
    }

    /** 橙条上此刻真正写着什么。空串 = 没有横幅。[IMBannerRules.next] 靠它判断该不该动。 */
    private var bannerText = ""

    private fun applyBanner(text: String) {
        if (text == bannerText) return
        bannerText = text
        banner.apply(text)
    }

    /** 「对方网络不佳」只出一次、2s 后收成角标，**不一直霸占顶部**。 */
    private var poorShown = false

    private fun renderControls(state: IMCallViewState, isEnded: Boolean) {
        val top: List<View>
        val bottom: List<View>
        when {
            isEnded -> {
                top = emptyList()
                bottom = emptyList()
            }
            state.phase == IMCallViewState.Phase.INCOMING -> {
                // 视频来电多一个摄像头开关，而不是「以语音接听」按钮（拍板 §11-10）。
                top = emptyList()
                bottom = if (state.showsCameraButton) {
                    listOf(cameraButton, rejectButton, answerButton)
                } else {
                    listOf(rejectButton, answerButton)
                }
            }
            // 「小窗」不在控制条里——它在标题栏左上角那一颗（IMCallHeader 的注释）。
            state.showsCameraButton -> {
                top = listOf(micButton, cameraButton, speakerButton)
                // 左边留一个空位，挂断才真的在正中；翻转在它右边。
                bottom = listOf(spacer, hangupButton, switchCameraButton)
            }
            else -> {
                top = listOf(micButton, speakerButton)
                bottom = listOf(hangupButton)
            }
        }
        fillRow(controlsTop, top)
        fillRow(controlsBottom, bottom)
        // 摄像头关着的时候翻转没有意义（也没有画面可翻）。
        switchCameraButton.isEnabled = state.cameraOn && !state.cameraBlocked
        switchCameraButton.alpha = if (switchCameraButton.isEnabled) 1f else 0.4f
        micButton.isOn = !state.micOn
        cameraButton.isOn = state.cameraOn
        cameraButton.isDisabledLook = state.cameraBlocked
        cameraButton.caption = if (state.cameraBlocked) "无权限" else "开摄像头"
        speakerButton.isOn = state.speakerOn
        // 红按钮的语义按房间类型分叉（规范 §05）：群 / 会议写「离开」，拨出中写「取消」。
        hangupButton.caption = when {
            state.isGroup || state.isMeeting -> "离开"
            state.phase == IMCallViewState.Phase.OUTGOING -> "取消"
            else -> "挂断"
        }
    }

    /**
     * 摆一排按钮：等宽平分，同一排的按钮无论几个都对齐。内容没变就不重建（重建会打断按下动效）。
     *
     * **占位格的高度必须写死 0**：裸 `View` 用 `wrap_content` 量出来的不是 0——
     * `View.getDefaultSize` 对 `AT_MOST` 直接返回 specSize，也就是**整块可用高度**。
     * 它一撑，下排跟着高到整屏，`controls` 这个 `wrap_content` 的容器再一撑，
     * 贴底的重力就没有意义了：两排按钮整体被顶到屏幕最上面，压在标题栏和状态栏上。
     * 真机上就是这个样子（v3.3 修）。
     */
    private fun fillRow(row: LinearLayout, wanted: List<View>) {
        if ((0 until row.childCount).map { row.getChildAt(it) } == wanted) return
        row.removeAllViews()
        wanted.forEach {
            // **先脱离原来那一排**：摄像头开关来电时在下排、接通后在上排，
            // 而 addView 遇到「已经有父容器」的 View 会直接抛 IllegalStateException——
            // 真机上就是「点接听，通话页当场闪退」（v3.3 修）。
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            val height = if (it === spacer) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            row.addView(it, LinearLayout.LayoutParams(0, height, 1f))
        }
        row.visibility = if (wanted.isEmpty()) GONE else VISIBLE
    }

    // ── 三种版式 ──────────────────────────────────────────────────────

    private fun renderAudio(state: IMCallViewState, hasLocalVideo: Boolean) {
        val peer = state.members.values.firstOrNull()
        // 来电这一屏显示「谁邀请的你」；其余阶段照旧（1v1 两者本来就是同一个人）。
        val shown = if (state.phase == IMCallViewState.Phase.INCOMING) state.incomingFromUid else state.peer
        audioStage.apply(
            shown, shown.ifEmpty { peer?.uid ?: "通话中" }, state.statusText,
            isRinging = state.phase == IMCallViewState.Phase.OUTGOING,
            networkLevel = peer?.networkLevel ?: 0,
            // 接通之后名字与时长归标题栏，中间只留头像——两处各走各的计时是重复也是打架。
            showsCaption = state.phase != IMCallViewState.Phase.CONNECTED,
        )
        unpinFull()
        retireTiles(emptySet())
        // 拨出视频时右上角叠本端预览（草图 §03-E：拨出时看得见自己）。
        val showPreview = state.mediaType == "video" && state.cameraOn && hasLocalVideo
        pip.visibility = if (showPreview) VISIBLE else GONE
        pip.liftsForControls = false
        if (showPreview) {
            applySelf(state, hasLocalVideo, 44)
            mountInPip(selfTile)
        }
    }

    private fun renderVideo(state: IMCallViewState) {
        val peer = state.members.values.firstOrNull() ?: return
        val remote = tiles.getOrPut(peer.uid) { IMVideoTile(context) }
        retireTiles(setOf(peer.uid))
        remote.setVideoView(actions?.videoViewFor(peer.uid), overlay = state.isSwapped)
        // **1v1 不做发言高亮**（绿描边 + 绿名牌）：只有两个人，谁在说话本来就一目了然，
        // 而那圈绿边压在全屏画面上只会显得像出了什么问题。九宫格里才需要它。
        remote.apply(peer.uid, peer.uid, peer.showsVideo, peer.audio, isSpeaking = false,
            networkLevel = peer.networkLevel, avatarSizeDp = if (state.isSwapped) 44 else IMKitTheme.AVATAR_LARGE_DP)
        applySelf(
            state, actions?.hasLocalVideo() ?: false,
            if (state.isSwapped) IMKitTheme.AVATAR_LARGE_DP else 44,
        )
        // 默认远端全屏、本端小窗；互换后反过来。层上界由 Kit 按 isSwapped 报。
        val (full, small) = if (state.isSwapped) selfTile to remote else remote to selfTile
        pinFull(full)
        mountInPip(small)
        pip.visibility = VISIBLE
        pip.liftsForControls = chrome.visible
        pip.contentDescription = if (state.isSwapped) "对方画面。轻点互换，长按可移动" else "本端画面。轻点互换，长按可移动"
        // 进小窗的报 l、上全屏的报 h（协议 §3.5）。
        actions?.reportLayer(peer.uid, if (state.isSwapped) "l" else "h")
    }

    private fun renderGrid(state: IMCallViewState) {
        unpinFull()
        pip.visibility = GONE
        val members = state.tiles
        retireTiles(members.map { it.uid }.toSet())
        applySelf(state, actions?.hasLocalVideo() ?: false, 44)
        val ordered = ArrayList<View>()
        ordered += selfTile
        selfTile.setRounded(true)
        // 层上界按格子数算：**加号格已经没有了**，格数就是真人数（本端 + 远端）。
        val layer = IMGrid.layerFor(members.size + 1, focused = false)
        for (m in members) {
            val tile = tiles.getOrPut(m.uid) { IMVideoTile(context) }
            tile.setRounded(true)
            /*
             **渲染器一直挂着，有没有画面交给 `apply` 用 visibility 切**（与 iOS 一致）。
             原先「没画面就 setVideoView(null)」会把 SurfaceView 摘下来，Surface 当场销毁；
             对端一开摄像头就得重建 Surface 再等一个关键帧——白等半秒还闪一下。
            */
            tile.setVideoView(actions?.videoViewFor(m.uid))
            tile.apply(m.uid, m.uid, m.showsVideo, m.audio, m.speaking, m.volume,
                isRinging = !m.accepted, settled = m.settled, networkLevel = m.networkLevel)
            actions?.reportLayer(m.uid, layer)
            ordered += tile
        }
        /*
         **九宫格里没有加号格**（v3.3 撤掉）。加人入口只有标题栏右上角那一颗
         （`canShowInvite` 同一条判据）：网格里再放一个是同一个动作的第二个入口，
         而它还会占掉一个格位——三个人的通话看起来像四个人，行列也跟着多排一格。
        */
        layoutGrid(ordered)
        // 没格子的人视频报 none，并说一句「还有 N 人未显示」（会议房 M1 止血，MEETING_ROOM_DESIGN §4.3 / §4.5）。
        state.hiddenMembers.forEach { actions?.reportLayer(it.uid, "none") }
        hiddenPill.show(state.hiddenMembers.size)
    }

    /**
     * 把可用区算出来交给 [IMCallGridView]——**摆放本身在那边**（含「没变就不重挂」那条闸）。
     *
     * 可用区要连**给控制条让出来的那条 padding** 一起扣掉（见 [applyStageInsets]），
     * 否则九宫格是在整块屏幕里居中，最后一行被按钮压着。
     */
    private fun layoutGrid(ordered: List<View>) {
        val gap = dp(IMKitTheme.TILE_GAP_DP)
        // 每格四周各留 gap/2 的外边距，所以可用区要先扣掉一整个 gap，算出来的边长才放得下。
        val width = stage.width - stage.paddingLeft - stage.paddingRight - dp(24) - gap
        val height = stage.height - stage.paddingTop - stage.paddingBottom - dp(8) - gap
        grid.apply(ordered, width, height, gap, fallbackCell = dp(120))
    }

    /**
     * 本端那格。**只表达麦克风开 / 关两态**（2026-09-09 拍板）——
     * 自己在不在说话自己知道，所以这里不再需要「哪种版式才显示说话」那个参数：
     * 三种版式一视同仁。
     */
    private fun applySelf(state: IMCallViewState, hasLocalVideo: Boolean, avatarDp: Int) {
        val showVideo = state.cameraOn && hasLocalVideo
        selfTile.setVideoView(if (showVideo) actions?.localPreviewView() else null, overlay = !state.isSwapped)
        // 本端那格也显示（2026-09-09 拍板）：uid 为空串，说话状态按本端音量判。
        /*
          **本端那格只表达麦克风开关**（2026-09-09 拍板）：自己在不在说话自己知道，
          那一格跳来跳去纯属多余。showsSpeaking = false 之后它只有开 / 关两态。
        */
        selfTile.apply("", "我", showVideo, state.micOn, isSpeaking = false, volume = 0,
            showsSpeaking = false,
            avatarSizeDp = avatarDp)
    }

    private fun mountInPip(tile: IMVideoTile) {
        // 外形由小窗容器负责——格子自己再画一层圆角底，会在小窗的框里露出一圈方角。
        tile.setRounded(false)
        if (pip.childCount == 1 && pip.getChildAt(0) === tile) return
        pip.removeAllViews()
        (tile.parent as? android.view.ViewGroup)?.removeView(tile)
        pip.addView(tile, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun pinFull(tile: IMVideoTile) {
        if (fullTile === tile) return
        unpinFull()
        (tile.parent as? android.view.ViewGroup)?.removeView(tile)
        videoFull.addView(tile, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tile.setRounded(false)
        fullTile = tile
    }

    private fun unpinFull() {
        fullTile?.let { videoFull.removeView(it) }
        fullTile = null
    }

    /** 收掉不再需要的远端格子。**卸载要成对**：不摘的话渲染器还占着解码器。 */
    private fun retireTiles(wanted: Set<String>) {
        tiles.keys.filter { it !in wanted }.forEach { uid ->
            val tile = tiles.remove(uid) ?: return@forEach
            tile.setVideoView(null)
            (tile.parent as? android.view.ViewGroup)?.removeView(tile)
            if (fullTile === tile) fullTile = null
            // 视图摘了还不算完，Engine 那一侧也要解绑（见 Actions.releaseVideoView）。
            actions?.releaseVideoView(uid)
        }
    }

    override fun onDetachedFromWindow() {
        chrome.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 控制条离屏幕底边的距离（还要再加上手势条的 inset）。 */
        const val CONTROLS_BOTTOM_DP = 26

        /** 标题栏离系统栏留白的距离。橙条要算到它下面去，所以具名而不是散落的字面量。 */
        const val HEADER_TOP_MARGIN_DP = 8

        /** 橙条与标题栏之间的间隙。 */
        const val BANNER_TOP_GAP_DP = 8
    }
}
