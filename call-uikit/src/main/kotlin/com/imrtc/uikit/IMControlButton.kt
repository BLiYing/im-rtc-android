package com.imrtc.uikit

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 通话页的圆形控制按钮（规范 §06「控制按钮的五个态」）：**圆里一个图标、圆下一行文字**。
 *
 * 五个态：常态（白 14%）、开启（反白 + 换成 slash 图标）、危险（恒红、64 大）、接听（恒绿、64 大）、
 * 禁用（35% 不透明，**点了要出提示不能静默**——由调用方决定提示什么）。按下缩放 0.92，120ms。
 * 与 iOS 的 `IMControlButton` / Web 的 `ControlButton` 同一套视觉，文案逐字对齐。
 *
 * **图标一律矢量**（`res/drawable/ic_im_*`），不用 emoji——iOS 上踩过：设备上会变成方框问号。
 * **开启态除了变色还要换图标**——不用颜色作为唯一信息载体（CONVENTIONS §9 无障碍）。
 */
internal class IMControlButton(
    context: Context,
    private val icon: IMKitIcon,
    caption: String,
    private val onIcon: IMKitIcon = icon,
    private val onCaption: String = caption,
    private val role: Role = Role.NORMAL,
    size: Size? = null,
) : LinearLayout(context) {

    enum class Role { NORMAL, DANGER, ACCEPT }
    enum class Size { NORMAL, BIG, SMALL }

    private val size = size ?: if (role == Role.NORMAL) Size.NORMAL else Size.BIG
    private val circle = FrameLayout(context)
    private val iconView = ImageView(context)
    private val captionView = TextView(context)
    /** 右下角 8×8 的路由角标（chevron-up），只在扬声器键处于「路由选择」形态时出现（设计稿 §04 v3.5）。 */
    private val chevron = ImageView(context)
    private var offCaption = caption

    /** 换掉常态 / 开启态的图标（路由选择形态下显示在用路由的字形）；null = 用构造时给的那两枚。 */
    var overrideIcon: IMKitIcon? = null
        set(value) {
            if (field == value) return
            field = value
            paint()
        }

    var showsChevron = false
        set(value) {
            if (field == value) return
            field = value
            chevron.visibility = if (value) VISIBLE else GONE
            // 路由选择形态下文案是设备名：最多 6 字，超长中间省略（设计稿 §04）。**只在这个形态下限**，
            // 别的按钮（「无权限」「已静音」……）不该被这条规则截断。
            captionView.maxWidth = if (value) dp(6 * 11) else Int.MAX_VALUE
            captionView.ellipsize = if (value) TextUtils.TruncateAt.MIDDLE else null
        }

    var isOn = false
        set(value) {
            if (field == value) return
            field = value
            paint()
        }

    /** 禁用态：**仍然可点**——调用方要借这一下出提示。 */
    var isDisabledLook = false
        set(value) {
            if (field == value) return
            field = value
            paint()
        }

    var caption: String
        get() = offCaption
        set(value) {
            if (offCaption == value) return
            offCaption = value
            paint()
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        val diameter = dp(
            when (this.size) {
                Size.NORMAL -> IMKitTheme.CONTROL_SIZE_DP
                Size.BIG -> IMKitTheme.CONTROL_SIZE_BIG_DP
                Size.SMALL -> IMKitTheme.CONTROL_SIZE_SMALL_DP
            },
        )
        val iconDp = when (this.size) {
            Size.NORMAL -> IMKitTheme.ICON_DP
            Size.BIG -> IMKitTheme.ICON_BIG_DP
            Size.SMALL -> IMKitTheme.ICON_SMALL_DP
        }
        circle.addView(iconView, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        chevron.setImageResource(IMKitIcon.CHEVRON_UP.resId)
        chevron.visibility = GONE
        circle.addView(
            chevron,
            // 离外接矩形 11：圆在 45° 角上比矩形往里缩了 8.2（56 × (1 − 1/√2) / 2），
            // 用 8 会让角标外角正好压在圆边上（真机 09-22 用户反馈「太挨着边缘」），11 才整个落在圆里。iOS 同值。
            FrameLayout.LayoutParams(dp(8), dp(8), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(11)
                marginEnd = dp(11)
            },
        )
        addView(circle, LayoutParams(diameter, diameter))
        captionView.textSize = 11f
        captionView.gravity = Gravity.CENTER
        captionView.setTextColor(IMKitTheme.secondaryText)
        captionView.maxLines = 1
        addView(captionView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        paint()
    }

    private fun paint() {
        val (bg, fg) = when (role) {
            Role.DANGER -> IMKitTheme.hangup to IMKitTheme.primaryText
            Role.ACCEPT -> IMKitTheme.answer to IMKitTheme.answerIcon
            Role.NORMAL -> if (isOn) IMKitTheme.controlOn to IMKitTheme.controlOnIcon else IMKitTheme.controlOff to IMKitTheme.controlOffIcon
        }
        circle.background = IMKitTheme.circleDrawable(bg)
        iconView.setImageResource((overrideIcon ?: if (isOn) onIcon else icon).resId)
        iconView.setColorFilter(fg)
        chevron.setColorFilter(fg)
        captionView.text = if (isOn) onCaption else offCaption
        alpha = if (isDisabledLook) 0.35f else 1f
        contentDescription = captionView.text
    }

    /** 按下缩放 0.92 + 松手弹回（规范 §07）。返回 false 让点击照常派发。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> circle.animate().scaleX(0.92f).scaleY(0.92f).setDuration(IMKitTheme.PRESS_MS).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                circle.animate().scaleX(1f).scaleY(1f).setDuration(IMKitTheme.PRESS_MS + 40).start()
        }
        return super.onTouchEvent(event)
    }
}
