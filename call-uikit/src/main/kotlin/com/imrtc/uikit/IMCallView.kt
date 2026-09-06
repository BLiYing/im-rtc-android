package com.imrtc.uikit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 通话界面本体，三种版式（规范 §03 / §04）：
 * · **AUDIO**：语音通话、拨出中 —— 96 头像 + 名字 + 状态（拨出视频时右上叠本端预览）；
 * · **VIDEO**：1v1 视频通话中 —— 远端全屏 + 本端小窗，单击小窗互换，控制条 3s 自动隐藏；
 * · **GRID**：群通话 / 会议 —— 九宫格 + 加号格（只有主叫可见）。
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
        /** 要本端预览的渲染器。 */
        fun localPreviewView(): View?
        fun hasLocalVideo(): Boolean
    }

    var actions: Actions? = null

    private val column = LinearLayout(context)
    private val header = IMCallHeader(context)
    private val banner = IMTopBanner(context)
    private val stage = FrameLayout(context)
    private val audioStage = IMAudioStage(context)
    private val grid = GridLayout(context)
    private val pip = IMPipView(context)
    private val endedLabel = TextView(context)
    private val controlsScrim = View(context)
    private val controls = LinearLayout(context)
    /** 控制条上排：三个开关。 */
    private val controlsTop = LinearLayout(context)
    /** 控制条下排：挂断居中 + 翻转摄像头。 */
    private val controlsBottom = LinearLayout(context)
    private val selfTile = IMVideoTile(context)
    private val addTile = ImageButton(context)
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
    private val hideChrome = Runnable { setChrome(visible = false) }
    private var chromeVisible = true
    private var layout = IMCallViewState.Layout.AUDIO
    private var state = IMCallViewState()

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
        column.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(IMKitTheme.HEADER_HEIGHT_DP)).apply { topMargin = dp(8) })
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
        // 单击画面空白处：显示 / 隐藏控制条（视频版式才生效）。
        stage.setOnClickListener { if (layout == IMCallViewState.Layout.VIDEO) setChrome(!chromeVisible) }

        controlsScrim.background = IMKitTheme.controlsScrim()
        addView(controlsScrim, LayoutParams(LayoutParams.MATCH_PARENT, dp(160), Gravity.BOTTOM))
        controlsScrim.visibility = GONE
        addView(banner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
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
        grid.alignmentMode = GridLayout.ALIGN_BOUNDS

        addTile.setImageResource(IMKitIcon.PLUS.resId)
        addTile.setColorFilter(IMKitTheme.primaryText)
        addTile.alpha = 0.8f
        addTile.background = IMKitTheme.roundedDrawable(android.graphics.Color.TRANSPARENT, dp(IMKitTheme.TILE_RADIUS_DP)).apply {
            setStroke((1.5f * resources.displayMetrics.density).toInt(), 0x4DFFFFFF)
        }
        addTile.contentDescription = "添加成员"
        addTile.setOnClickListener { actions?.onInvite() }

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

    /** 当前摆在九宫格里的那批格子。容器尺寸变了要按真尺寸重摆一次（见 layoutGrid）。 */
    private var gridOrdered: List<View> = emptyList()

    /**
     * 让开状态栏与导航栏。
     *
     * Activity 是边到边的（[IMCallActivity.goFullScreen]），不让的话「对方名字 + 时长」那一行
     * 直接压在状态栏的时间和电量上，而底部的挂断键会被手势条盖掉一半。
     * **只给根容器加 padding**，版式代码一行不用改。
     */
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
        (controls.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = dp(CONTROLS_BOTTOM_DP) + padBottom
            controls.layoutParams = it
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!changed) return
        pip.layoutInContainer()
        // 第一轮 render 时 stage 还没量出来，格子边长只能按默认形状估。这里补摆一次。
        if (layout == IMCallViewState.Layout.GRID && gridOrdered.isNotEmpty()) {
            post { layoutGrid(gridOrdered) }
        }
    }

    fun render(state: IMCallViewState) {
        this.state = state
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
        controlsScrim.visibility = if (layout == IMCallViewState.Layout.VIDEO && !isEnded) VISIBLE else GONE
        renderBanner(state)
        renderControls(state, isEnded)
        if (isEnded) { pip.visibility = GONE; unpinFull(); return }
        when (layout) {
            IMCallViewState.Layout.AUDIO -> renderAudio(state, hasLocalVideo)
            IMCallViewState.Layout.VIDEO -> renderVideo(state)
            IMCallViewState.Layout.GRID -> renderGrid(state)
        }
        if (layout != IMCallViewState.Layout.VIDEO) setChrome(visible = true, arm = false) else if (chromeVisible) armAutoHide()
    }

    private fun renderBanner(state: IMCallViewState) {
        val text = when (state.connection) {
            IMCallViewState.Connection.RECONNECTING -> "正在重连…"
            IMCallViewState.Connection.LOST -> "连接已断开"
            IMCallViewState.Connection.OK -> if (state.members.values.any { IMCallViewState.isNetworkPoor(it.networkLevel) } && !poorShown) {
                poorShown = true
                main.postDelayed({ banner.apply("") }, IMKitTheme.NETWORK_BANNER_MS)
                "对方网络不佳"
            } else ""
        }
        if (state.members.values.none { IMCallViewState.isNetworkPoor(it.networkLevel) }) poorShown = false
        if (text.isNotEmpty() || state.connection != IMCallViewState.Connection.OK) banner.apply(text)
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
            val height = if (it === spacer) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            row.addView(it, LinearLayout.LayoutParams(0, height, 1f))
        }
        row.visibility = if (wanted.isEmpty()) GONE else VISIBLE
    }

    // ── 三种版式 ──────────────────────────────────────────────────────

    private fun renderAudio(state: IMCallViewState, hasLocalVideo: Boolean) {
        val peer = state.members.values.firstOrNull()
        audioStage.apply(
            state.peer, state.peer.ifEmpty { peer?.uid ?: "通话中" }, state.statusText,
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
        remote.apply(peer.uid, peer.uid, peer.video, peer.audio, isSpeaking = false,
            networkLevel = peer.networkLevel, avatarSizeDp = if (state.isSwapped) 44 else IMKitTheme.AVATAR_LARGE_DP)
        applySelf(state, actions?.hasLocalVideo() ?: false, if (state.isSwapped) IMKitTheme.AVATAR_LARGE_DP else 44)
        // 默认远端全屏、本端小窗；互换后反过来。层上界由 Kit 按 isSwapped 报。
        val (full, small) = if (state.isSwapped) selfTile to remote else remote to selfTile
        pinFull(full)
        mountInPip(small)
        pip.visibility = VISIBLE
        pip.liftsForControls = chromeVisible
        pip.contentDescription = if (state.isSwapped) "对方画面。轻点互换，长按可移动" else "本端画面。轻点互换，长按可移动"
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
        for (m in members) {
            val tile = tiles.getOrPut(m.uid) { IMVideoTile(context) }
            tile.setRounded(true)
            tile.setVideoView(if (m.video) actions?.videoViewFor(m.uid) else null)
            tile.apply(m.uid, m.uid, m.video, m.audio, state.speakingUid == m.uid,
                isRinging = !m.accepted, settled = m.settled, networkLevel = m.networkLevel)
            ordered += tile
        }
        // 加人入口放在网格里（交互稿 §05）：它天然占着「下一个人的位置」。只有主叫、没满员时才有。
        if (state.canShowInvite) ordered += addTile
        layoutGrid(ordered)
    }

    /**
     * 格子恒为正方形、整块居中，行列跟着容器形状走（与 iOS / Web 同一个算法）。
     *
     * **不给 spec 带权重**：带权重的话 GridLayout 会把剩余空间摊到每一格上，
     * 算出来的正方形边长当场被撑没——竖屏两个人就变成两条又高又窄的长条，
     * 与 iOS 完全不是一个样子。整块的居中交给 grid 自己的 `Gravity.CENTER`。
     */
    private fun layoutGrid(ordered: List<View>) {
        gridOrdered = ordered
        val gap = dp(IMKitTheme.TILE_GAP_DP)
        // 每格四周各留 gap/2 的外边距，所以可用区要先扣掉一整个 gap，算出来的边长才放得下。
        val width = stage.width - dp(24) - gap
        val height = stage.height - dp(8) - gap
        val measured = width > 0 && height > 0
        // 容器还没量出来（第一轮 render 早于 layout）：先按竖屏手机的形状排一版，
        // onLayout 量到真尺寸会再摆一次。
        val aspect = if (measured) width.toDouble() / height else 0.7
        val (columns, rows) = IMGrid.dimensions(ordered.size, aspect)
        val cellWidth: Int
        val cellHeight: Int
        when {
            !measured -> { cellWidth = dp(120); cellHeight = dp(120) }
            // 只有一格时铺满：正方形是为了「多格之间不互相拉伸」，一格时没有别人可比。
            ordered.size <= 1 -> { cellWidth = width; cellHeight = height }
            else -> {
                val side = IMGrid.cellSide(columns, rows, width, height, gap)
                cellWidth = side
                cellHeight = side
            }
        }
        grid.removeAllViews()
        grid.columnCount = columns
        grid.rowCount = rows
        for (view in ordered) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            val params = GridLayout.LayoutParams().apply {
                this.width = cellWidth
                this.height = cellHeight
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            }
            grid.addView(view, params)
        }
    }

    private fun applySelf(state: IMCallViewState, hasLocalVideo: Boolean, avatarDp: Int) {
        val showVideo = state.cameraOn && hasLocalVideo
        selfTile.setVideoView(if (showVideo) actions?.localPreviewView() else null, overlay = !state.isSwapped)
        selfTile.apply("", "我", showVideo, state.micOn, false, avatarSizeDp = avatarDp)
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
        }
    }

    // ── 控制条自动隐藏（规范 §07：3s 后淡出，任意触摸恢复）───────────

    private fun setChrome(visible: Boolean, arm: Boolean = true) {
        chromeVisible = visible
        for (view in listOf(header, controls, controlsScrim)) {
            view.animate().alpha(if (visible) 1f else 0f).setDuration(IMKitTheme.FADE_MS).start()
        }
        controls.isEnabled = visible
        pip.liftsForControls = visible && layout == IMCallViewState.Layout.VIDEO
        main.removeCallbacks(hideChrome)
        if (visible && arm) armAutoHide()
    }

    private fun armAutoHide() {
        main.removeCallbacks(hideChrome)
        if (state.phase == IMCallViewState.Phase.CONNECTED) main.postDelayed(hideChrome, IMKitTheme.AUTO_HIDE_MS)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(hideChrome)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 控制条离屏幕底边的距离（还要再加上手势条的 inset）。 */
        const val CONTROLS_BOTTOM_DP = 26
    }
}
