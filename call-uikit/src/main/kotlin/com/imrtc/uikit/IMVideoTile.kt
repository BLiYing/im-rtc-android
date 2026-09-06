package com.imrtc.uikit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * 一个成员格子（规范 §06「格子」）：有画面时显示画面，没有时显示**渐变底 + 首字母**的头像盘。
 * 左下名字标签（正在说话时底变绿）、右上静音角标、右下网络角标，邀请中的格子整格 55% 不透明 + 顶部一行终局。
 *
 * **画面只经 Engine 造的 View 挂载**（CONVENTIONS §1）：UIKit 不 import org.webrtc，
 * 渲染器由媒体层造好交过来，这里只管放进 [videoHost]。
 */
internal class IMVideoTile(context: Context) : FrameLayout(context) {

    /** 渲染器的容器。媒体层造的 View 放这里；互换 / 换版式时**只挪格子不重建渲染器**。 */
    val videoHost = FrameLayout(context)
    private val avatar = TextView(context)
    private val namePlate = TextView(context)
    private val mutedPlate = FrameLayout(context)
    private val mutedIcon = ImageView(context)
    private val netPlate = FrameLayout(context)
    private val netBars = IMNetworkBarsView(context)
    private val ringingLabel = TextView(context)
    private val border = GradientDrawable()
    private var avatarSizeDp = 44

    var uid = ""
        private set

    init {
        clipToOutline = true
        background = IMKitTheme.roundedDrawable(IMKitTheme.tileBackground, dp(IMKitTheme.TILE_RADIUS_DP))
        addView(videoHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        avatar.gravity = Gravity.CENTER
        avatar.setTextColor(IMKitTheme.primaryText)
        avatar.setTypeface(null, android.graphics.Typeface.BOLD)
        addView(avatar, LayoutParams(dp(44), dp(44), Gravity.CENTER))

        namePlate.textSize = 12f
        namePlate.setTextColor(IMKitTheme.primaryText)
        namePlate.maxLines = 1
        namePlate.setPadding(dp(8), 0, dp(8), 0)
        namePlate.gravity = Gravity.CENTER_VERTICAL
        namePlate.background = IMKitTheme.roundedDrawable(IMKitTheme.scrim, dp(6))
        addView(namePlate, LayoutParams(LayoutParams.WRAP_CONTENT, dp(18), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), 0, dp(8), dp(8))
        })

        // 静音角标放右上，与左下的名字牌分开：名字可能很长，挤在一起时角标会被顶出格子。
        mutedIcon.setImageResource(IMKitIcon.MIC_SLASH.resId)
        mutedIcon.setColorFilter(IMKitTheme.mutedBadge)
        mutedPlate.background = IMKitTheme.circleDrawable(IMKitTheme.scrim)
        mutedPlate.addView(mutedIcon, LayoutParams(dp(14), dp(14), Gravity.CENTER))
        mutedPlate.contentDescription = "已静音"
        addView(mutedPlate, LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })

        netPlate.background = IMKitTheme.circleDrawable(IMKitTheme.scrim)
        netPlate.addView(netBars, LayoutParams(dp(14), dp(14), Gravity.CENTER))
        netPlate.contentDescription = "网络不佳"
        addView(netPlate, LayoutParams(dp(24), dp(24), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) })

        ringingLabel.textSize = 11f
        ringingLabel.setTextColor(IMKitTheme.primaryText)
        ringingLabel.gravity = Gravity.CENTER_HORIZONTAL
        addView(ringingLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(10) })

        // 发言描边是**内描边**（规范 §06：2.5 内缩，不撑大格子）。
        border.shape = GradientDrawable.RECTANGLE
        border.cornerRadius = dp(IMKitTheme.TILE_RADIUS_DP).toFloat()
        border.setStroke(dp(3), IMKitTheme.speaking)
        border.setColor(android.graphics.Color.TRANSPARENT)
    }

    /** 挂渲染器。传 null 卸载。`overlay` 为真时让 SurfaceView 浮在别的 SurfaceView 之上（小窗压在全屏画面上）。 */
    fun setVideoView(view: View?, overlay: Boolean = false) {
        if (videoHost.childCount == 1 && videoHost.getChildAt(0) === view) return
        videoHost.removeAllViews()
        if (view == null) return
        (view.parent as? FrameLayout)?.removeView(view)
        (view as? SurfaceView)?.setZOrderMediaOverlay(overlay)
        videoHost.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun apply(
        uid: String,
        label: String,
        hasVideo: Boolean,
        hasAudio: Boolean,
        isSpeaking: Boolean,
        isRinging: Boolean = false,
        settled: IMCallViewState.Settled = IMCallViewState.Settled.NONE,
        networkLevel: Int = 0,
        avatarSizeDp: Int = 44,
    ) {
        this.uid = uid
        if (this.avatarSizeDp != avatarSizeDp) {
            this.avatarSizeDp = avatarSizeDp
            avatar.layoutParams = LayoutParams(dp(avatarSizeDp), dp(avatarSizeDp), Gravity.CENTER)
        }
        avatar.text = IMAvatar.initial(label)
        avatar.textSize = (avatarSizeDp / 3f)
        avatar.background = IMKitTheme.avatarDrawable(uid.ifEmpty { label })
        // 没画面时露出头像。**用 visibility 不改层级**：层级一动，媒体层挂着的渲染器会跟着重来。
        avatar.visibility = if (hasVideo) GONE else VISIBLE
        videoHost.visibility = if (hasVideo) VISIBLE else INVISIBLE
        namePlate.text = label
        // 正在说话：名字标签底变绿、字变深（规范 §06）。
        namePlate.background = IMKitTheme.roundedDrawable(if (isSpeaking) IMKitTheme.speaking else IMKitTheme.scrim, dp(6))
        namePlate.setTextColor(if (isSpeaking) IMKitTheme.answerIcon else IMKitTheme.primaryText)
        foreground = if (isSpeaking) border else null
        mutedPlate.visibility = if (hasAudio) GONE else VISIBLE
        netPlate.visibility = if (IMCallViewState.isNetworkPoor(networkLevel)) VISIBLE else GONE
        netBars.level = networkLevel
        // 邀请中的占位格：整格 55% 不透明 + 顶部一行终局（规范 §06）。
        alpha = if (isRinging) 0.55f else 1f
        ringingLabel.visibility = if (isRinging) VISIBLE else GONE
        ringingLabel.text = if (settled == IMCallViewState.Settled.NONE) "呼叫中…" else IMCallViewState.settledText(settled)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * 三根柱子的网络质量图标（规范 §05 `net-bars`）：1~2 三根亮、3~4 两根、5~6 一根；0 不画。
 * 5 以上柱子变橙（规范 §02 warn）。分档在 [IMCallViewState.networkBarsLit]（纯函数，有单测）。
 */
internal class IMNetworkBarsView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var level = 0
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (level <= 0) return
        val lit = IMCallViewState.networkBarsLit(level)
        val unit = width / 24f
        val color = if (level >= 5) IMKitTheme.warning else IMKitTheme.primaryText
        val bars = listOf(Triple(3f, 14f, 6f), Triple(10.2f, 9f, 11f), Triple(17.4f, 4f, 16f))
        bars.forEachIndexed { index, (x, y, h) ->
            paint.color = color
            paint.alpha = if (index < lit) 255 else 90
            canvas.drawRoundRect(x * unit, y * unit, (x + 3.6f) * unit, (y + h) * unit, unit, unit, paint)
        }
    }
}
