package com.imrtc.uikit

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮球（草图 §04）：通话页收起后缩成一个可拖的小球贴在屏幕边上，显示时长，点一下展开回全屏。
 * **通话本身不受影响**——它只是换了个呈现。
 *
 * 两条做法上的讲究：
 * 1. **拖完吸附到最近的左右边缘**。停在屏幕中间会挡住宿主的内容，而用户拖它的本意
 *    恰恰是「别挡着我看东西」。
 * 2. **拖动与点击要分得开**：位移超过 `touchSlop` 才算拖，否则松手当点击。
 *    不分的话，手指抖一下就展开全屏，或者拖完还顺带展开一次。
 */
internal class IMFloatingBubble(context: Context) : LinearLayout(context) {

    var onExpand: (() -> Unit)? = null

    private val icon = TextView(context)
    private val duration = TextView(context)

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = IMKitTheme.circleDrawable(IMKitTheme.bannerBackground)
        elevation = dp(10).toFloat()
        icon.apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(IMKitTheme.primaryText)
        }
        duration.apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(IMKitTheme.primaryText)
        }
        addView(icon)
        addView(duration)
        contentDescription = "通话中，点击展开"
    }

    fun render(state: IMCallViewState) {
        icon.text = if (state.mediaType == "video") "📹" else "📞"
        duration.text = IMGrid.formatDuration(state.durationSec)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? View ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = x
                downY = y
                dragging = false
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
                if (dragging) {
                    snapToEdge(parentView)
                } else {
                    performClick()
                }
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

    /** 吸附到最近的左右边缘。**只吸左右不吸上下**：上下贴边会撞到状态栏与导航条。 */
    private fun snapToEdge(parentView: View) {
        val margin = dp(12).toFloat()
        val toLeft = x + width / 2f < parentView.width / 2f
        animate()
            .x(if (toLeft) margin else parentView.width - width - margin)
            .setDuration(160)
            .start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val SIZE_DP = 60

        /** 初始位置：右上角靠下一点，避开状态栏与常见的顶部导航。 */
        fun initialParams(context: Context): FrameLayout.LayoutParams {
            val density = context.resources.displayMetrics.density
            val size = (SIZE_DP * density).toInt()
            return FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = (12 * density).toInt()
                topMargin = (96 * density).toInt()
            }
        }
    }
}
