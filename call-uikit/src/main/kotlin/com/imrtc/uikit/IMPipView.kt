package com.imrtc.uikit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 1v1 视频里浮在角上的那块小画面（交互稿 §04）：单击回调（互换由调用方做）、**长按 350ms 进入拖动态**、
 * 松手吸附到最近的角、控制条显示时下面两个角上移 88。
 *
 * # 为什么拖动要先长按
 *
 * 小窗只有 96dp 宽，手指本身就有十来 dp 的抖动。不加长按的话，用户想「点一下互换」十次里有三次
 * 会被判成拖动。长按是给「移动」这个低频动作加的门槛，换来「互换」这个高频动作永远准。
 *
 * 位置算术在 [IMPipLayout]（纯函数，有单测，与 iOS / Web 同一份）；这里只负责手势与动画。
 */
internal class IMPipView(context: Context) : FrameLayout(context) {

    var onTap: (() -> Unit)? = null
    var corner: IMPipLayout.Corner = IMPipLayout.Corner.DEFAULT
        private set

    /** 控制条此刻是否显示——显示时下面两个角要上移。 */
    var liftsForControls = false
        set(value) { field = value; if (!dragging) snap(animated = true) }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val main = Handler(Looper.getMainLooper())
    private var dragging = false
    private var moved = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var ghosts: GhostsView? = null
    private val enterDrag = Runnable { beginDrag() }

    init {
        background = IMKitTheme.roundedDrawable(IMKitTheme.tileBackground, dp(IMKitTheme.PIP_RADIUS_DP))
        foreground = IMKitTheme.roundedDrawable(android.graphics.Color.TRANSPARENT, dp(IMKitTheme.PIP_RADIUS_DP)).apply {
            setStroke((1.5f * density).toInt(), 0x8CFFFFFF.toInt())
        }
        elevation = dp(10).toFloat()
        clipToOutline = true
        contentDescription = "本端画面。轻点互换，长按可移动"
    }

    /** 容器尺寸变了（转屏、首次布局）就按当前角重摆。 */
    fun layoutInContainer() {
        if (!dragging) snap(animated = false)
    }

    private val container: ViewGroup? get() = parent as? ViewGroup

    private fun sizeDp(): IMPipLayout.Size {
        val c = container ?: return IMPipLayout.LANDSCAPE
        return IMPipLayout.sizeFor(c.width / density.toDouble(), c.height / density.toDouble())
    }

    private fun restOrigin(corner: IMPipLayout.Corner): IMPipLayout.Point {
        val c = container ?: return IMPipLayout.Point(0.0, 0.0)
        return IMPipLayout.origin(
            corner, sizeDp(), c.width / density.toDouble(), c.height / density.toDouble(),
            if (liftsForControls) IMPipLayout.LIFT else 0.0,
        )
    }

    private fun snap(animated: Boolean) {
        val size = sizeDp()
        val wanted = LayoutParams((size.width * density).toInt(), (size.height * density).toInt())
        if (layoutParams?.width != wanted.width || layoutParams?.height != wanted.height) layoutParams = wanted
        val origin = restOrigin(corner)
        val tx = (origin.x * density).toFloat()
        val ty = (origin.y * density).toFloat()
        if (!animated) { x = tx; y = ty; return }
        animate().x(tx).y(ty).scaleX(1f).scaleY(1f).setDuration(IMKitTheme.SNAP_MS).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = container ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = x
                startY = y
                moved = false
                dragging = false
                main.postDelayed(enterDrag, IMKitTheme.LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging) {
                    // 长按还没到就动了：不是长按，也不是拖——什么都不做，松手时也不算单击。
                    if (kotlin.math.hypot(dx, dy) > slop) { moved = true; main.removeCallbacks(enterDrag) }
                    return true
                }
                val size = sizeDp()
                val origin = IMPipLayout.clamp(
                    IMPipLayout.Point(((startX + dx) / density).toDouble(), ((startY + dy) / density).toDouble()),
                    size, c.width / density.toDouble(), c.height / density.toDouble(),
                )
                x = (origin.x * density).toFloat()
                y = (origin.y * density).toFloat()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(enterDrag)
                if (dragging) {
                    // 松手吸附到**最近的角**（按小窗中心算），不是最近的边。
                    corner = IMPipLayout.nearestCorner(
                        IMPipLayout.Point(((x + width / 2f) / density).toDouble(), ((y + height / 2f) / density).toDouble()),
                        c.width / density.toDouble(), c.height / density.toDouble(),
                    )
                    dragging = false
                    hideGhosts()
                    snap(animated = true)
                } else if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    /** 进入拖动态：放大 1.04、轻触觉反馈，四角浮现虚线框（交互稿 §04 S3）。 */
    private fun beginDrag() {
        dragging = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        animate().scaleX(1.04f).scaleY(1.04f).setDuration(IMKitTheme.PRESS_MS).start()
        showGhosts()
    }

    private fun showGhosts() {
        val c = container ?: return
        hideGhosts()
        val size = sizeDp()
        val rects = IMPipLayout.Corner.values().map { corner ->
            val o = restOrigin(corner)
            RectF(
                (o.x * density).toFloat(), (o.y * density).toFloat(),
                ((o.x + size.width) * density).toFloat(), ((o.y + size.height) * density).toFloat(),
            )
        }
        val view = GhostsView(context, rects, dp(IMPipLayout_RADIUS).toFloat())
        c.addView(view, c.indexOfChild(this), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ghosts = view
    }

    private fun hideGhosts() {
        ghosts?.let { (it.parent as? ViewGroup)?.removeView(it) }
        ghosts = null
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    /** 拖动中四个角的虚线框。 */
    private class GhostsView(context: Context, private val rects: List<RectF>, private val radius: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * context.resources.displayMetrics.density
            color = 0x59FFFFFF
            pathEffect = DashPathEffect(floatArrayOf(6f * context.resources.displayMetrics.density, 4f * context.resources.displayMetrics.density), 0f)
        }

        override fun onDraw(canvas: Canvas) {
            rects.forEach { canvas.drawRoundRect(it, radius, radius, paint) }
        }
    }

    private companion object {
        const val IMPipLayout_RADIUS = IMKitTheme.PIP_RADIUS_DP
    }
}
