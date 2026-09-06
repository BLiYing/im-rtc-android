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
    private val selfTile = IMVideoTile(context)
    private val addTile = ImageButton(context)
    /** uid → 这个人的格子。**不每次重建**：重建会让媒体层挂着的渲染器重来，画面会闪。 */
    private val tiles = LinkedHashMap<String, IMVideoTile>()
    private var fullTile: IMVideoTile? = null

    private val micButton = IMControlButton(context, IMKitIcon.MIC, "静音", IMKitIcon.MIC_SLASH, "已静音")
    private val cameraButton = IMControlButton(context, IMKitIcon.VIDEO_SLASH, "开摄像头", IMKitIcon.VIDEO, "关摄像头")
    private val speakerButton = IMControlButton(context, IMKitIcon.SPEAKER, "扬声器")
    private val minimizeControl = IMControlButton(context, IMKitIcon.MINIMIZE, "小窗")
    private val hangupButton = IMControlButton(context, IMKitIcon.PHONE_DOWN, "挂断", role = IMControlButton.Role.DANGER)
    private val answerButton = IMControlButton(context, IMKitIcon.PHONE, "接听", role = IMControlButton.Role.ACCEPT)
    private val rejectButton = IMControlButton(context, IMKitIcon.XMARK, "拒绝", role = IMControlButton.Role.DANGER)

    private val main = Handler(Looper.getMainLooper())
    private val hideChrome = Runnable { setChrome(visible = false) }
    private var chromeVisible = true
    private var layout = IMCallViewState.Layout.AUDIO
    private var state = IMCallViewState()
    /** 画中画模式：只留画面，壳全藏（Android 差异 2）。 */
    var pipMode = false
        set(value) { field = value; render(state) }

    init {
        setBackgroundColor(IMKitTheme.background)
        column.orientation = LinearLayout.VERTICAL
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(IMKitTheme.HEADER_HEIGHT_DP)).apply { topMargin = dp(8) })
        column.addView(stage, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(controls, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(IMKitTheme.CONTROLS_HEIGHT_DP)).apply { bottomMargin = dp(26) })

        stage.addView(audioStage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
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
        // 控制条放在最上层（它在 column 里会被 scrim 盖住），所以从 column 摘出来重挂。
        column.removeView(controls)
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, dp(IMKitTheme.CONTROLS_HEIGHT_DP), Gravity.BOTTOM).apply { bottomMargin = dp(26) })
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER or Gravity.TOP
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
        minimizeControl.setOnClickListener { actions?.onMinimize() }
        hangupButton.setOnClickListener { actions?.onHangup() }
        answerButton.setOnClickListener { actions?.onAnswer() }
        rejectButton.setOnClickListener { actions?.onHangup() }
        pip.onTap = { if (layout == IMCallViewState.Layout.VIDEO) actions?.onSwap() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) pip.layoutInContainer()
    }

    fun render(state: IMCallViewState) {
        this.state = state
        val hasLocalVideo = actions?.hasLocalVideo() ?: false
        layout = state.layout(hasLocalVideo)
        val isEnded = state.phase == IMCallViewState.Phase.ENDED
        val peerLevel = if (state.isGroup) 0 else (state.members.values.firstOrNull()?.networkLevel ?: 0)
        header.apply(
            state.titleText, state.statusText,
            if (state.phase == IMCallViewState.Phase.CONNECTED) peerLevel else 0,
            showsMinimize = state.canMinimize && IMCallKit.config.floatingWindow,
            showsInvite = state.canShowInvite,
        )
        header.visibility = if (pipMode) GONE else VISIBLE
        background = if (layout == IMCallViewState.Layout.VIDEO && !isEnded) null else IMKitTheme.callBackground()
        if (background == null) setBackgroundColor(IMKitTheme.background)
        endedLabel.visibility = if (isEnded) VISIBLE else GONE
        endedLabel.text = state.statusText
        audioStage.visibility = if (layout == IMCallViewState.Layout.AUDIO && !isEnded) VISIBLE else GONE
        grid.visibility = if (layout == IMCallViewState.Layout.GRID && !isEnded) VISIBLE else GONE
        controlsScrim.visibility = if (layout == IMCallViewState.Layout.VIDEO && !isEnded && !pipMode) VISIBLE else GONE
        renderBanner(state)
        renderControls(state, isEnded)
        if (isEnded) { pip.visibility = GONE; unpinFull(); return }
        when (layout) {
            IMCallViewState.Layout.AUDIO -> renderAudio(state, hasLocalVideo)
            IMCallViewState.Layout.VIDEO -> renderVideo(state)
            IMCallViewState.Layout.GRID -> renderGrid(state)
        }
        if (layout != IMCallViewState.Layout.VIDEO || pipMode) setChrome(visible = !pipMode, arm = false) else if (chromeVisible) armAutoHide()
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
        val wanted: List<View> = when {
            isEnded || pipMode -> emptyList()
            state.phase == IMCallViewState.Phase.INCOMING ->
                // 视频来电多一个摄像头开关，而不是「以语音接听」按钮（拍板 §11-10）。
                if (state.showsCameraButton) listOf(cameraButton, rejectButton, answerButton) else listOf(rejectButton, answerButton)
            state.showsCameraButton -> listOf(micButton, cameraButton, speakerButton, minimizeControl, hangupButton)
            else -> listOf(micButton, speakerButton, minimizeControl, hangupButton)
        }
        if ((0 until controls.childCount).map { controls.getChildAt(it) } != wanted) {
            controls.removeAllViews()
            wanted.forEach { controls.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        micButton.isOn = !state.micOn
        cameraButton.isOn = state.cameraOn
        cameraButton.isDisabledLook = state.cameraBlocked
        cameraButton.caption = if (state.cameraBlocked) "无权限" else "开摄像头"
        speakerButton.isOn = state.speakerOn
        minimizeControl.visibility = if (IMCallKit.config.floatingWindow) VISIBLE else GONE
        // 红按钮的语义按房间类型分叉（规范 §05）：群 / 会议写「离开」，拨出中写「取消」。
        hangupButton.caption = when {
            state.isGroup || state.isMeeting -> "离开"
            state.phase == IMCallViewState.Phase.OUTGOING -> "取消"
            else -> "挂断"
        }
    }

    // ── 三种版式 ──────────────────────────────────────────────────────

    private fun renderAudio(state: IMCallViewState, hasLocalVideo: Boolean) {
        val peer = state.members.values.firstOrNull()
        audioStage.apply(state.peer, state.peer.ifEmpty { peer?.uid ?: "通话中" }, state.statusText,
            isRinging = state.phase == IMCallViewState.Phase.OUTGOING, networkLevel = peer?.networkLevel ?: 0)
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
        remote.apply(peer.uid, peer.uid, peer.video, peer.audio, state.speakingUid == peer.uid,
            networkLevel = peer.networkLevel, avatarSizeDp = if (state.isSwapped) 44 else IMKitTheme.AVATAR_LARGE_DP)
        applySelf(state, true, if (state.isSwapped) IMKitTheme.AVATAR_LARGE_DP else 44)
        // 默认远端全屏、本端小窗；互换后反过来。层上界由 Kit 按 isSwapped 报。
        val (full, small) = if (state.isSwapped) selfTile to remote else remote to selfTile
        pinFull(full)
        mountInPip(small)
        pip.visibility = if (pipMode) GONE else VISIBLE
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
        for (m in members) {
            val tile = tiles.getOrPut(m.uid) { IMVideoTile(context) }
            tile.setVideoView(if (m.video) actions?.videoViewFor(m.uid) else null)
            tile.apply(m.uid, m.uid, m.video, m.audio, state.speakingUid == m.uid,
                isRinging = !m.accepted, settled = m.settled, networkLevel = m.networkLevel)
            ordered += tile
        }
        // 加人入口放在网格里（交互稿 §05）：它天然占着「下一个人的位置」。只有主叫、没满员时才有。
        if (state.canShowInvite) ordered += addTile
        layoutGrid(ordered)
    }

    /** 格子恒为正方形，行列跟着容器形状走（与 iOS / Web 同一个算法）。 */
    private fun layoutGrid(ordered: List<View>) {
        val width = stage.width - dp(24)
        val height = stage.height - dp(8)
        val aspect = if (height > 0) width.toDouble() / height else 0.7
        val (columns, rows) = IMGrid.dimensions(ordered.size, aspect)
        val gap = dp(IMKitTheme.TILE_GAP_DP)
        val side = if (ordered.size > 1 && width > 0 && height > 0) IMGrid.cellSide(columns, rows, width, height, gap) else 0
        grid.removeAllViews()
        grid.columnCount = columns
        grid.rowCount = rows
        for (view in ordered) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            val params = GridLayout.LayoutParams().apply {
                if (side > 0) { this.width = side; this.height = side } else { this.width = 0; this.height = 0 }
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
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
        if (pip.childCount == 1 && pip.getChildAt(0) === tile) return
        pip.removeAllViews()
        (tile.parent as? android.view.ViewGroup)?.removeView(tile)
        pip.addView(tile, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun pinFull(tile: IMVideoTile) {
        if (fullTile === tile) return
        unpinFull()
        (tile.parent as? android.view.ViewGroup)?.removeView(tile)
        stage.addView(tile, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tile.background = null
        fullTile = tile
    }

    private fun unpinFull() {
        fullTile?.let {
            stage.removeView(it)
            it.background = IMKitTheme.roundedDrawable(IMKitTheme.tileBackground, dp(IMKitTheme.TILE_RADIUS_DP))
        }
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
}
