package com.imrtc.uikit

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 通话界面本体：**三段式**——标题区、九宫格、控制条。
 *
 * 用代码搭而不是 XML：Kit 是要塞进别人 App 的库，少一批 layout 资源就少一批与宿主重名的风险，
 * 也省掉 `merge`/主题继承那些麻烦。
 *
 * 布局的坑（iOS 端在等价的地方错了三轮）：**三段式要把两头钉死高度**，
 * 中间那段的高度才被完全确定。这里用 `LinearLayout` + `weight`：
 * 标题区与控制条 `wrap_content`，中间格子 `weight=1` 吃掉剩下的全部——
 * 一个未知高度，不会欠定。
 */
internal class IMCallView(context: Context) : LinearLayout(context) {

    /** 界面上的动作，交给 [IMCallKit] 去调 Engine。UI 自己不认识 Engine。 */
    interface Actions {
        fun onAnswer()
        fun onHangup()
        fun onToggleMic()
        fun onToggleCamera()
        fun onToggleSpeaker()
        fun onSwitchCamera()
    }

    var actions: Actions? = null

    private val statusTitle = TextView(context)
    private val statusSubtitle = TextView(context)
    private val grid = GridLayout(context)
    private val controls = LinearLayout(context)

    private val micButton = circleButton("麦克风")
    private val cameraButton = circleButton("摄像头")
    private val speakerButton = circleButton("扬声器")
    private val switchButton = circleButton("翻转")
    private val hangupButton = circleButton("挂断")
    private val answerButton = circleButton("接听")

    /** uid → 这个人的格子。格子里可能是视频视图，也可能只是头像占位。 */
    private val tiles = LinkedHashMap<String, FrameLayout>()

    init {
        orientation = VERTICAL
        setBackgroundColor(IMKitTheme.background)
        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(buildControls(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(state: IMCallViewState, videoViewFor: (String) -> View?) {
        statusTitle.text = state.titleText
        statusSubtitle.text = state.statusText

        answerButton.visibility = if (state.showAnswerButton) VISIBLE else GONE
        // 来电时不该显示「翻转摄像头」——还没开摄像头呢。
        switchButton.visibility =
            if (state.phase == IMCallViewState.Phase.CONNECTED && state.cameraOn) VISIBLE else GONE
        paint(switchButton, false)
        paint(micButton, state.micOn)
        paint(cameraButton, state.cameraOn)
        paint(speakerButton, state.speakerOn)

        renderTiles(state, videoViewFor)
    }

    private fun renderTiles(state: IMCallViewState, videoViewFor: (String) -> View?) {
        val members = state.tiles
        val (columns, rows) = IMGrid.dimensions(members.size)
        grid.columnCount = columns
        grid.rowCount = rows

        val wanted = members.map { it.uid }.toSet()
        // 走了的人：格子连同挂在上面的视频视图一起摘掉，**不摘会留着一块黑**。
        tiles.keys.filter { it !in wanted }.forEach { uid ->
            grid.removeView(tiles.remove(uid))
        }

        for (member in members) {
            val tile = tiles.getOrPut(member.uid) {
                FrameLayout(context).also { frame ->
                    frame.setBackgroundColor(IMKitTheme.tileBackground)
                    val label = TextView(context).apply {
                        text = member.uid
                        setTextColor(IMKitTheme.secondaryText)
                        gravity = Gravity.CENTER
                    }
                    frame.addView(label)
                    grid.addView(frame)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                val gap = dp(IMKitTheme.TILE_GAP_DP)
                setMargins(gap, gap, gap, gap)
            }
            tile.layoutParams = params

            // 正在说话的人加一圈高亮边框。**不能只靠颜色**——静音角标另有图标，见下。
            tile.foreground = if (state.speakingUid == member.uid) speakingBorder() else null

            // 有视频就把视频视图挂上；没有就保持头像占位。
            if (member.video && tile.childCount == 1) {
                videoViewFor(member.uid)?.let { video ->
                    tile.addView(
                        video,
                        0,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            }
            (tile.getChildAt(tile.childCount - 1) as? TextView)?.text =
                if (member.audio) member.uid else "${member.uid} （已静音）"
        }
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(24), dp(48), dp(24), dp(16))
        statusTitle.setTextColor(IMKitTheme.primaryText)
        statusTitle.textSize = 22f
        statusSubtitle.setTextColor(IMKitTheme.secondaryText)
        statusSubtitle.textSize = 14f
        addView(statusTitle)
        addView(statusSubtitle)
    }

    private fun buildControls(): View = controls.apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(16), dp(16), dp(40))
        addView(micButton, controlParams())
        addView(cameraButton, controlParams())
        addView(speakerButton, controlParams())
        addView(switchButton, controlParams())
        addView(hangupButton, controlParams())
        addView(answerButton, controlParams())

        micButton.setOnClickListener { actions?.onToggleMic() }
        cameraButton.setOnClickListener { actions?.onToggleCamera() }
        speakerButton.setOnClickListener { actions?.onToggleSpeaker() }
        switchButton.setOnClickListener { actions?.onSwitchCamera() }
        hangupButton.setOnClickListener { actions?.onHangup() }
        answerButton.setOnClickListener { actions?.onAnswer() }

        paintFixed(hangupButton, IMKitTheme.hangup)
        paintFixed(answerButton, IMKitTheme.answer)
    }

    private fun circleButton(text: String) = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 11f
    }

    private fun controlParams() = LayoutParams(dp(IMKitTheme.CONTROL_SIZE_DP), dp(IMKitTheme.CONTROL_SIZE_DP))
        .apply { setMargins(dp(6), 0, dp(6), 0) }

    /** 开启态白底黑字、关闭态深底白字（草图 §03）。 */
    private fun paint(button: Button, on: Boolean) {
        button.background = circle(if (on) IMKitTheme.controlOn else IMKitTheme.controlOff)
        button.setTextColor(if (on) IMKitTheme.controlOnIcon else IMKitTheme.controlOffIcon)
    }

    private fun paintFixed(button: Button, color: Int) {
        button.background = circle(color)
        button.setTextColor(Color.WHITE)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun speakingBorder() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setStroke(dp(2), IMKitTheme.speaking)
        setColor(Color.TRANSPARENT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
