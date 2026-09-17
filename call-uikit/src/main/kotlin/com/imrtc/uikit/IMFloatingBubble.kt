package com.imrtc.uikit

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * 悬浮球（交互稿 §03 M1）：通话页收起后缩成一个可拖的小窗贴在屏幕边上，点一下展开回全屏。
 * **通话本身不受影响**——它只是换了个呈现。
 *
 * 两种形态：语音通话是 **56 圆角球**（图标 + 等宽时长）；视频通话是 **90×120 缩略画面**，
 * 右下角叠时长——只放主讲人，层上界报 `l`。图标一律矢量，不用 emoji。
 *
 * Android 上它是**应用内浮层**（不申请 `SYSTEM_ALERT_WINDOW`，CONVENTIONS §8）；
 * 视频通话优先走系统画中画（[IMCallActivity]），这里是画中画不可用时的兜底。
 *
 * 拖完吸附到最近的左右边缘；位移超过 `touchSlop` 才算拖，否则松手当点击。
 *
 * **球体下面挂一颗 28 的红色挂断**：收进小窗之后没有它就只能先展开回全屏才能挂断，
 * 而「随手挂掉」正是小窗最常用的一件事。红色是危险动作的唯一颜色（规范 §01 danger）。
 *
 * # 为什么本体是「容器 + 球 + 挂断」三层
 *
 * 球自己要 `clipToOutline` 才能把视频裁成圆角，而**被裁掉的正好是挂断那一颗**——
 * 放在球内底部会被圆形轮廓切掉大半，放在球外又超出父视图的边界（Android 不给
 * 边界外的子视图派发触摸）。所以本类是一个**透明容器**：球在上、挂断在下，
 * 两个都在容器边界内，拖动时整块一起走。
 */
internal class IMFloatingBubble(context: Context) : FrameLayout(context) {

    var onExpand: (() -> Unit)? = null
    /** 小窗上的挂断。走与红按钮同一条路（会议房里是离房，不是 hangup）。 */
    var onHangup: (() -> Unit)? = null
    /** 视频形态下远端缩略画面放这里。 */
    val videoHost = FrameLayout(context)

    /** 球体本身（圆形 / 圆角矩形）。裁剪、底色、视频都在它身上，挂断在它外面。 */
    private val body = FrameLayout(context)
    private val icon = ImageView(context)
    private val hangup = android.widget.ImageButton(context)
    private val duration = TextView(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var isVideo = false
    /** 这一串手势是不是从挂断按钮上按下的，见 [onInterceptTouchEvent]。 */
    private var downOnHangup = false

    init {
        body.background = IMKitTheme.circleDrawable(IMKitTheme.bannerBackground)
        body.elevation = dp(10).toFloat()
        body.clipToOutline = true
        addView(
            body,
            LayoutParams(dp(IMKitTheme.BUBBLE_SIZE_DP), dp(IMKitTheme.BUBBLE_SIZE_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )
        body.addView(videoHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        videoHost.visibility = GONE
        icon.setColorFilter(IMKitTheme.primaryText)
        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(icon, android.widget.LinearLayout.LayoutParams(dp(18), dp(18)))
            duration.textSize = 11f
            duration.gravity = Gravity.CENTER
            duration.setTextColor(IMKitTheme.primaryText)
            addView(duration)
        }
        body.addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        hangup.setImageResource(IMKitIcon.PHONE_DOWN.resId)
        hangup.setColorFilter(IMKitTheme.primaryText)
        hangup.background = IMKitTheme.circleDrawable(IMKitTheme.hangup)
        hangup.contentDescription = "挂断"
        hangup.setPadding(dp(6), dp(6), dp(6), dp(6))
        hangup.scaleType = ImageView.ScaleType.FIT_CENTER
        hangup.elevation = dp(10).toFloat()
        hangup.setOnClickListener { onHangup?.invoke() }
        addView(hangup, LayoutParams(dp(HANGUP_DP), dp(HANGUP_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        contentDescription = "通话中，点击展开"
    }

    fun render(state: IMCallViewState) {
        icon.setImageResource((if (state.mediaType == "video") IMKitIcon.VIDEO else IMKitIcon.PHONE).resId)
        duration.text = IMGrid.bubbleText(state)
    }

    /**
     * 挂远端缩略画面：切成 90×120 的视频形态。
     *
     * **同一个 view 再来一次就直接返回。** `mountBubble` 挂在 `applyPresentation` 上，
     * 每次状态更新都会调到这里，而时长每秒走一格——不判重的话这块渲染器一秒摘挂一回，
     * `SurfaceView` 的 surface 跟着销毁重建，小窗里只剩闪烁。
     */
    fun setVideoView(view: View?) {
        if (videoHost.getChildAt(0) === view && (view != null) == isVideo) return
        videoHost.removeAllViews()
        val video = view != null
        if (video != isVideo) {
            isVideo = video
            body.background = if (video) IMKitTheme.roundedDrawable(IMKitTheme.bannerBackground, dp(14)) else IMKitTheme.circleDrawable(IMKitTheme.bannerBackground)
            icon.visibility = if (video) GONE else VISIBLE
            val bodyW = dp(if (video) IMKitTheme.BUBBLE_VIDEO_W_DP else IMKitTheme.BUBBLE_SIZE_DP)
            val bodyH = dp(if (video) IMKitTheme.BUBBLE_VIDEO_H_DP else IMKitTheme.BUBBLE_SIZE_DP)
            body.layoutParams = LayoutParams(bodyW, bodyH, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            // 容器 = 球 + 间隙 + 挂断，整块一起拖。
            val size = LayoutParams(bodyW, bodyH + dp(HANGUP_GAP_DP) + dp(HANGUP_DP))
            (layoutParams as? LayoutParams)?.let { size.gravity = it.gravity; size.setMargins(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
            layoutParams = size
            // 视频形态：时长挪到右下角的小标签里。
            (duration.parent as View).layoutParams = if (video) LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(5), dp(5)) }
            else LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            duration.background = if (video) IMKitTheme.roundedDrawable(IMKitTheme.scrim, dp(5)) else null
            duration.setPadding(if (video) dp(5) else 0, 0, if (video) dp(5) else 0, 0)
            duration.textSize = if (video) 10f else 11f
        }
        videoHost.visibility = if (video) VISIBLE else GONE
        if (view != null) {
            /*
             **先从原父容器上摘下来。** 渲染器是一个 uid 一份、整通复用的
             （`IMCallKit.videoViewFor`），点小窗这一刻它还挂在全屏页的格子上——
             不摘就是 `IllegalStateException: The specified child already has a parent`，
             当场崩在主线程。收起小窗回全屏是同一件事，那边由
             `IMVideoTile.setVideoView` 摘（同一套写法，那边先踩到）。
            */
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            videoHost.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * 挂断按钮自己处理触摸：不拦的话 onTouchEvent 会把它吞成「点球 = 展开」。
     *
     * **按下落在挂断上，这一整串手势都不拦。** 原先只放过 DOWN、MOVE / UP 照拦：按钮拿到 DOWN 之后
     * UP 被父容器截走，按钮只收到 CANCEL——点了没反应，也不展开（2026-09-17 PKD130 真机）。
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) downOnHangup = hitsHangup(event)
        return !downOnHangup
    }

    private fun hitsHangup(event: MotionEvent): Boolean {
        val x = event.x - hangup.left
        val y = event.y - hangup.top
        return x >= 0 && y >= 0 && x <= hangup.width && y <= hangup.height
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? View ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY; downX = x; downY = y; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && kotlin.math.hypot(dx, dy) > slop) dragging = true
                if (dragging) {
                    // 夹在父容器里：拖出屏幕就再也点不回来了。
                    x = (downX + dx).coerceIn(0f, (parentView.width - width).toFloat())
                    y = (downY + dy).coerceIn(0f, (parentView.height - height).toFloat())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) snapToEdge(parentView) else performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onExpand?.invoke()
        return true
    }

    /** 吸附到最近的左右边缘（离边 8），竖直方向夹在上下各留 60 之内。 */
    private fun snapToEdge(parentView: View) {
        val margin = dp(8).toFloat()
        val toLeft = x + width / 2f < parentView.width / 2f
        val minY = dp(60).toFloat()
        val maxY = (parentView.height - height - dp(60)).toFloat()
        animate()
            .x(if (toLeft) margin else parentView.width - width - margin)
            .y(y.coerceIn(minY, maxOf(minY, maxY)))
            .setDuration(IMKitTheme.SNAP_MS)
            .start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 挂断按钮的直径。28 是「拇指够得着」的下限（规范 §04 的小控件尺寸）。 */
        private const val HANGUP_DP = 28

        /** 球体与挂断之间的间隙。 */
        private const val HANGUP_GAP_DP = 4

        /** 初始位置：右上角靠下一点，避开状态栏与常见的顶部导航。 */
        fun initialParams(context: Context): FrameLayout.LayoutParams {
            val density = context.resources.displayMetrics.density
            val size = (IMKitTheme.BUBBLE_SIZE_DP * density).toInt()
            val height = size + ((HANGUP_GAP_DP + HANGUP_DP) * density).toInt()
            return FrameLayout.LayoutParams(size, height).apply {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = (8 * density).toInt()
                topMargin = (120 * density).toInt()
            }
        }
    }
}
