package com.imrtc.uikit

import android.content.Context
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 来电横幅（规范 §06「来电横幅」）：左右各留 8、高 62、圆角 16；头像 38（渐变底 + 首字母）+ 两行字 +
 * 拒绝 / 接听两个 38 圆。**来电先出横幅，不直接全屏**——用户正在打字时被一整屏盖住很粗暴。
 * 点横幅本体展开成全屏来电页；5s 不处理由 [IMCallKit] 升级为全屏。图标一律矢量，不用 emoji。
 *
 * **两个按钮之间要留够间距**：接听与拒绝挨太近，误触的代价是「本来想接却挂了对方」。
 */
internal class IMIncomingBanner(context: Context) : LinearLayout(context) {

    var onExpand: (() -> Unit)? = null
    var onAccept: (() -> Unit)? = null
    var onReject: (() -> Unit)? = null

    private val avatar = TextView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val acceptButton = roundButton(IMKitTheme.answer, IMKitTheme.answerIcon, IMKitIcon.PHONE, "接听")
    private val rejectButton = roundButton(IMKitTheme.hangup, IMKitTheme.primaryText, IMKitIcon.XMARK, "拒绝")

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = IMKitTheme.roundedDrawable(IMKitTheme.bannerBackground, dp(16))
        elevation = dp(14).toFloat()
        setPadding(dp(12), dp(10), dp(10), dp(10))
        minimumHeight = dp(62)

        avatar.textSize = 13f
        avatar.setTypeface(null, android.graphics.Typeface.BOLD)
        avatar.setTextColor(IMKitTheme.primaryText)
        avatar.gravity = Gravity.CENTER
        addView(avatar, LayoutParams(dp(38), dp(38)))

        title.textSize = 16f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(IMKitTheme.primaryText)
        subtitle.textSize = 13f
        subtitle.setTextColor(IMKitTheme.secondaryText)
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title)
                addView(subtitle)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10); rightMargin = dp(8) },
        )
        rejectButton.setOnClickListener { onReject?.invoke() }
        addView(rejectButton, LayoutParams(dp(38), dp(38)))
        addView(acceptButton, LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(10) })
        acceptButton.setOnClickListener { onAccept?.invoke() }

        // 点两个按钮之外的地方 = 展开全屏。
        setOnClickListener { onExpand?.invoke() }
        contentDescription = "来电，点击展开"
    }

    fun render(state: IMCallViewState) {
        val caller = state.members.keys.firstOrNull() ?: state.peer
        avatar.text = IMAvatar.initial(caller)
        avatar.background = IMKitTheme.avatarDrawable(caller)
        title.text = caller
        subtitle.text = state.statusText
        acceptButton.setImageResource((if (state.mediaType == "video") IMKitIcon.VIDEO else IMKitIcon.PHONE).resId)
    }

    private fun roundButton(color: Int, tint: Int, icon: IMKitIcon, label: String) = ImageButton(context).apply {
        setImageResource(icon.resId)
        setColorFilter(tint)
        background = IMKitTheme.circleDrawable(color)
        contentDescription = label
        val pad = dp(9)
        setPadding(pad, pad, pad, pad)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
