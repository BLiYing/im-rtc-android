package com.imrtc.uikit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 「还有 N 人未显示」胶囊（MEETING_ROOM_DESIGN §4.5，会议房 M1 止血）：舞台右下角，**不可点**——
 * 成员列表是 M2 的内容，M1 不能让它看起来可以点。文案见 [IMGrid.hiddenCountText]。
 *
 * 单独一个文件是因为 `IMCallView` 贴着 600 行红线。
 */
internal class IMHiddenCountPill(context: Context) : TextView(context) {

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(IMKitTheme.primaryText)
        val padH = dp(10)
        setPadding(padH, dp(3), padH, dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(0x99000000.toInt())
        }
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    /** 被截掉几个人；0 就藏起来。 */
    fun show(hidden: Int) {
        text = IMGrid.hiddenCountText(hidden)
        visibility = if (hidden > 0) VISIBLE else GONE
    }

    companion object {
        fun layoutParams(pill: IMHiddenCountPill): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
        ).apply { val m = pill.dp(20); setMargins(m, m, m, m) }
    }
}
