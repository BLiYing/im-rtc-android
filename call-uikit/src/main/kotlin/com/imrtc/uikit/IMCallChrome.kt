package com.imrtc.uikit

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 通话页的「壳」：顶部那条、顶部橙条、语音页的中间区块。三个小视图放一个文件——它们都不含业务逻辑。
 */

/**
 * 顶部那条（规范 §04）：左 32 圆「小窗」、中间标题 + 副标题 + 网络条、右 32 圆「加人」。固定高 64。
 *
 * 左上角那颗就是**收进小窗的唯一入口**（控制条里不再重复放一颗）：那个位置在三端都是
 * 「离开这一屏」的手势位，用户第一反应就是往那儿点。
 */
internal class IMCallHeader(context: Context) : FrameLayout(context) {
    val minimizeButton = roundButton(IMKitIcon.PIP, "收进小窗")
    val inviteButton = roundButton(IMKitIcon.PERSON_ADD, "添加成员")
    /**
     * 成员列表入口（MEETING_ROOM_DESIGN §4.6 的「👥 N」）。**只有会议房给**。
     *
     * 它与「添加成员」共用右上角那一个位置：会议房没有加人这回事
     * （`canShowInvite` 明确排除了会议），两颗按钮不会同时出现。
     */
    val membersButton = TextView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val bars = IMNetworkBarsView(context)

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(IMKitTheme.HEADER_HEIGHT_DP))
        title.textSize = 16f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(IMKitTheme.primaryText)
        title.gravity = Gravity.CENTER
        title.maxLines = 1
        subtitle.textSize = 13f
        subtitle.setTextColor(IMKitTheme.secondaryText)
        subtitle.gravity = Gravity.CENTER
        val subtitleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(subtitle)
            addView(bars, LinearLayout.LayoutParams(dp(13), dp(13)).apply { leftMargin = dp(6); gravity = Gravity.CENTER_VERTICAL })
        }
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title)
            addView(subtitleRow)
        }
        addView(center, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(56); rightMargin = dp(56)
        })
        addView(minimizeButton, LayoutParams(dp(32), dp(32), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(16) })
        addView(inviteButton, LayoutParams(dp(32), dp(32), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(16) })

        membersButton.textSize = 12f
        membersButton.setTextColor(IMKitTheme.primaryText)
        membersButton.gravity = Gravity.CENTER
        membersButton.background = IMKitTheme.circleDrawable(IMKitTheme.controlOff)
        membersButton.contentDescription = "成员列表"
        membersButton.visibility = View.GONE
        addView(
            membersButton,
            LayoutParams(dp(44), dp(32), Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { rightMargin = dp(16) },
        )
    }

    fun apply(
        titleText: String,
        subtitleText: String,
        networkLevel: Int,
        showsMinimize: Boolean,
        showsInvite: Boolean,
        memberCount: Int = 0,
    ) {
        title.text = titleText
        subtitle.text = subtitleText
        bars.level = networkLevel
        bars.visibility = if (networkLevel > 0) View.VISIBLE else View.GONE
        minimizeButton.visibility = if (showsMinimize) View.VISIBLE else View.INVISIBLE
        inviteButton.visibility = if (showsInvite) View.VISIBLE else View.INVISIBLE
        // 两颗按钮同一个位置，互斥：加人按钮出现时就没有成员列表这一说（会议房才有它）。
        membersButton.visibility =
            if (!showsInvite && memberCount > 0) View.VISIBLE else View.GONE
        membersButton.text = "👥$memberCount"
    }

    private fun roundButton(icon: IMKitIcon, label: String) = ImageButton(context).apply {
        setImageResource(icon.resId)
        setColorFilter(IMKitTheme.primaryText)
        background = IMKitTheme.circleDrawable(IMKitTheme.controlOff)
        contentDescription = label
        val pad = dp(7)
        setPadding(pad, pad, pad, pad)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    }
}

/** 顶部橙条（规范 §08）：「正在重连…」「连接已断开」「对方网络不佳」。橙底深字，圆角胶囊。 */
internal class IMTopBanner(context: Context) : TextView(context) {
    init {
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(IMKitTheme.background)
        background = IMKitTheme.roundedDrawable(IMKitTheme.warning, dp(12))
        setPadding(dp(12), dp(4), dp(12), dp(4))
        visibility = View.GONE
    }

    /** 空串 = 隐藏。 */
    fun apply(text: String) {
        this.text = text
        visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }
}

/**
 * 语音通话页与拨出中页的中间区块（规范 §03 · §04 红线）：96 头像 + 22 名字 + 13 状态 + 网络胶囊。
 * 拨出中头像外面多一圈**呼吸光环**（1.6s 循环，接通立刻停）。
 */
internal class IMAudioStage(context: Context) : FrameLayout(context) {
    private val ring = View(context)
    private val avatar = TextView(context)
    private val name = TextView(context)
    private val status = TextView(context)
    private val netChip = LinearLayout(context)
    private val netBars = IMNetworkBarsView(context)
    private val netText = TextView(context)

    /** 底色只跟这个键走；没变就不重建 GradientDrawable（同 [IMVideoTile] 的 avatarKey）。 */
    private var avatarKey: String? = null

    init {
        val size = dp(IMKitTheme.AVATAR_LARGE_DP)
        ring.background = IMKitTheme.roundedDrawable(android.graphics.Color.TRANSPARENT, (size + dp(22)) / 2).apply {
            setStroke(dp(3), 0x40FFFFFF)
        }
        avatar.gravity = Gravity.CENTER
        avatar.textSize = 32f
        avatar.setTypeface(null, android.graphics.Typeface.BOLD)
        avatar.setTextColor(IMKitTheme.primaryText)
        avatar.elevation = dp(12).toFloat()
        name.textSize = 22f
        name.setTypeface(null, android.graphics.Typeface.BOLD)
        name.setTextColor(IMKitTheme.primaryText)
        name.gravity = Gravity.CENTER
        status.textSize = 13f
        status.setTextColor(IMKitTheme.secondaryText)
        status.gravity = Gravity.CENTER
        netText.textSize = 11f
        netText.setTextColor(IMKitTheme.secondaryText)
        netChip.orientation = LinearLayout.HORIZONTAL
        netChip.gravity = Gravity.CENTER
        netChip.background = IMKitTheme.roundedDrawable(IMKitTheme.controlOff, dp(10))
        netChip.setPadding(dp(9), dp(2), dp(9), dp(2))
        netChip.addView(netBars, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(4); gravity = Gravity.CENTER_VERTICAL })
        netChip.addView(netText)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                FrameLayout(context).apply {
                    addView(ring, LayoutParams(size + dp(22), size + dp(22), Gravity.CENTER))
                    addView(avatar, LayoutParams(size, size, Gravity.CENTER))
                },
                LinearLayout.LayoutParams(size + dp(22), size + dp(22)),
            )
            addView(name, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(status, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(netChip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(20)).apply { topMargin = dp(8) })
        }
        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /**
     * @param showsCaption 名字与状态这一行要不要显示。
     *
     * **接通之后不显示**：那时候标题栏里已经是「对方名字 + 计时器」，中间再写一遍
     * 就是同一句话在一屏里出现两次，还各走各的计时。呼叫中 / 来电页的标题栏是空的，
     * 名字与状态只在那两屏出现。
     */
    fun apply(
        uid: String,
        nameText: String,
        statusText: String,
        isRinging: Boolean,
        networkLevel: Int,
        showsCaption: Boolean = true,
    ) {
        avatar.text = IMAvatar.initial(nameText)
        val avatarKey = uid.ifEmpty { nameText }
        if (this.avatarKey != avatarKey) {
            this.avatarKey = avatarKey
            avatar.background = IMKitTheme.avatarDrawable(avatarKey)
        }
        name.text = nameText
        status.text = statusText
        name.visibility = if (showsCaption) View.VISIBLE else View.GONE
        status.visibility = if (showsCaption) View.VISIBLE else View.GONE
        netChip.visibility = if (networkLevel > 0) View.VISIBLE else View.GONE
        netBars.level = networkLevel
        netText.text = IMCallViewState.networkText(networkLevel)
        netText.setTextColor(if (networkLevel >= 5) IMKitTheme.warning else IMKitTheme.secondaryText)
        setRinging(isRinging)
    }

    private fun setRinging(ringing: Boolean) {
        ring.visibility = if (ringing) View.VISIBLE else View.INVISIBLE
        ring.animate().cancel()
        if (!ringing) { ring.alpha = 1f; return }
        breathe()
    }

    /** 3dp 外环在 0.25 ↔ 0.05 不透明度之间呼吸（规范 §07）。 */
    private fun breathe() {
        ring.animate().alpha(0.2f).setDuration(800).withEndAction {
            if (ring.visibility != View.VISIBLE) return@withEndAction
            ring.animate().alpha(1f).setDuration(800).withEndAction { if (ring.visibility == View.VISIBLE) breathe() }.start()
        }.start()
    }
}
