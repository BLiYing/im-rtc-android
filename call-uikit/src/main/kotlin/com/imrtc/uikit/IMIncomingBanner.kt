package com.imrtc.uikit

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 来电横幅（草图 §04）。**来电先出横幅，不直接全屏**——用户正在打字时被一整屏盖住很粗暴。
 *
 * 横幅停在顶部，点横幅本体展开成全屏来电页，横幅上就能直接接 / 拒。
 * 它跟全屏页共用同一份 [IMCallViewState]，只是另一种呈现；开关在 [IMCallKitConfig.bannerFirst]。
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
    private val acceptButton = roundButton(IMKitTheme.answer)
    private val rejectButton = roundButton(IMKitTheme.hangup)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = IMKitTheme.roundedDrawable(IMKitTheme.bannerBackground, dp(16))
        elevation = dp(12).toFloat()
        setPadding(dp(12), dp(12), dp(12), dp(12))

        avatar.apply {
            textSize = 18f
            setTextColor(IMKitTheme.primaryText)
            gravity = Gravity.CENTER
            background = IMKitTheme.circleDrawable(IMKitTheme.avatarBackground)
        }
        addView(avatar, LayoutParams(dp(44), dp(44)))

        title.apply {
            textSize = 15f
            setTextColor(IMKitTheme.primaryText)
        }
        subtitle.apply {
            textSize = 12f
            setTextColor(IMKitTheme.secondaryText)
        }
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title)
                addView(subtitle)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
                rightMargin = dp(8)
            },
        )

        rejectButton.text = "✕"
        rejectButton.setOnClickListener { onReject?.invoke() }
        addView(rejectButton, LayoutParams(dp(40), dp(40)))
        addView(
            acceptButton,
            LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(12) },
        )
        acceptButton.setOnClickListener { onAccept?.invoke() }

        // 点两个按钮之外的地方 = 展开全屏。
        setOnClickListener { onExpand?.invoke() }
        contentDescription = "来电，点击展开"
    }

    fun render(state: IMCallViewState) {
        val caller = state.titleText
        avatar.text = caller.take(1).uppercase()
        title.text = caller
        subtitle.text = state.statusText
        acceptButton.text = if (state.mediaType == "video") "📹" else "📞"
    }

    private fun roundButton(color: Int) = Button(context).apply {
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = IMKitTheme.circleDrawable(color)
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
