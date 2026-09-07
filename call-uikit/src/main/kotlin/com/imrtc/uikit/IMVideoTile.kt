package com.imrtc.uikit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewOutlineProvider
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    /**
     * 宿主给的头像图。**独立一个 ImageView，不是把 Drawable 塞进 avatar 的背景**。
     *
     * 塞背景的话有两个问题，真机上一眼就能看出来：
     *  · TextView 的背景**不受任何形状裁剪**——圆形头像是靠背景 Drawable 自己是
     *    OVAL 画出来的，宿主给一张方图就实实在在显示成方的（iOS / Web 都会裁成圆）。
     *  · 背景会被拉伸填满，非 1:1 的图会变形；而 iOS 是 scaleAspectFill、
     *    Web 是 object-fit:cover，都是**居中裁切**。
     * ImageView + CENTER_CROP + clipToOutline 才和另外两端一致。
     */
    private val avatarPhoto = ImageView(context)
    private val namePlate = TextView(context)
    private val mutedPlate = FrameLayout(context)
    private val mutedIcon = ImageView(context)
    private val bottomRow = LinearLayout(context)
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

        // 居中裁切 + 圆形裁剪，与 iOS 的 scaleAspectFill、Web 的 object-fit:cover 对齐。
        // **加在 avatar 之后**，层级才在它上面：有图时不该看见底下的色块与首字母。
        avatarPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
        avatarPhoto.visibility = GONE
        avatarPhoto.clipToOutline = true
        avatarPhoto.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // 用 setRoundRect(半径 = 边长/2) 而不是 setOval：Outline 只有在是
                // 圆角矩形时才 canClip()，setOval 在部分实现上会被判为不可裁剪而静默失效。
                val side = minOf(view.width, view.height)
                outline.setRoundRect(0, 0, side, side, side / 2f)
            }
        }
        addView(avatarPhoto, LayoutParams(dp(44), dp(44), Gravity.CENTER))

        /*
         名字牌 + 静音角标是**左下角同一行**（v3.2 改）。

         静音角标原先在右上角，而全屏画面是铺满整屏的——那个位置正好压在状态栏的
         时间与电量上。挪到名字右边之后两者一起排，也不会再和系统栏打架。

         离左边与下边都留 `PLATE_INSET_DP`（12，比原来的 8 大）：格子有圆角，
         贴到 8 的话名字在圆角上会被切掉一截，有的机型上直接看不全。
        */
        namePlate.textSize = 12f
        namePlate.setTextColor(IMKitTheme.primaryText)
        namePlate.maxLines = 1
        namePlate.ellipsize = android.text.TextUtils.TruncateAt.END
        namePlate.setPadding(dp(8), 0, dp(8), 0)
        namePlate.gravity = Gravity.CENTER_VERTICAL
        namePlate.background = IMKitTheme.roundedDrawable(IMKitTheme.scrim, dp(6))

        mutedIcon.setImageResource(IMKitIcon.MIC_SLASH.resId)
        mutedIcon.setColorFilter(IMKitTheme.mutedBadge)
        mutedPlate.background = IMKitTheme.circleDrawable(IMKitTheme.scrim)
        mutedPlate.addView(mutedIcon, LayoutParams(dp(14), dp(14), Gravity.CENTER))
        mutedPlate.contentDescription = "已静音"

        bottomRow.orientation = LinearLayout.HORIZONTAL
        bottomRow.gravity = Gravity.CENTER_VERTICAL
        // **名字牌只包住文字**：给它权重的话，「我」两个像素宽的名字会拖着一条
        // 横贯整格的深色底板（与 iOS 的名字牌完全不是一个样子）。太长时靠
        // onSizeChanged 里算出来的 maxWidth 截断，不会把静音角标顶出格子。
        bottomRow.addView(namePlate, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(20)))
        bottomRow.addView(
            mutedPlate,
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = dp(4) },
        )
        addView(
            bottomRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(PLATE_INSET_DP), 0, dp(PLATE_INSET_DP), dp(PLATE_INSET_DP))
            },
        )

        netPlate.background = IMKitTheme.circleDrawable(IMKitTheme.scrim)
        netPlate.addView(netBars, LayoutParams(dp(14), dp(14), Gravity.CENTER))
        netPlate.contentDescription = "网络不佳"
        addView(
            netPlate,
            LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(PLATE_INSET_DP), dp(PLATE_INSET_DP), 0)
            },
        )

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

    /** 当前挂着的渲染器是不是「压在别人上面」那一层。换角色时要重挂一次，见 [setVideoView]。 */
    private var overlayApplied = false

    /**
     * 挂渲染器。传 null 卸载。`overlay` 为真时让 SurfaceView 浮在别的 SurfaceView 之上
     * （小窗压在全屏画面上——两个 SurfaceView 叠放时谁在上面是不定的）。
     *
     * **角色变了必须摘下来重挂。** `setZOrderMediaOverlay` 只在 Surface 创建之前有效，
     * 对着已经挂好的 View 再调一次是没有作用的。A/B 互换恰恰会让两个渲染器交换角色：
     * 原先「已经挂着同一个 view 就直接返回」，于是互换之后进小窗的那一路还是压在下面，
     * 被全屏那一路整个盖住——真机上的症状是「点了互换，小窗里什么都没有」。
     * 摘下来再加回去会重建 Surface，新的层次才生效（只有互换时才发生，画面闪一下可以接受）。
     */
    fun setVideoView(view: View?, overlay: Boolean = false) {
        val current = videoHost.getChildAt(0)
        if (current === view && overlayApplied == overlay) return
        videoHost.removeAllViews()
        if (view == null) {
            overlayApplied = false
            return
        }
        (view.parent as? FrameLayout)?.removeView(view)
        (view as? SurfaceView)?.setZOrderMediaOverlay(overlay)
        overlayApplied = overlay
        videoHost.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 圆角 + 底色**只有九宫格里的格子要**。
     *
     * 全屏与小窗的外形由容器负责：**`clipToOutline` 对 SurfaceView 不起作用**
     * （它是独立的 Surface，由窗口管理器合成，应用画不到它上面），
     * 格子自己再画一层 10dp 圆角底，就会在小窗那 12dp 的圆角框里露出一圈方角，
     * 看着像小窗上多了个透明方块。
     */
    fun setRounded(rounded: Boolean) {
        clipToOutline = rounded
        background = if (rounded) {
            IMKitTheme.roundedDrawable(IMKitTheme.tileBackground, dp(IMKitTheme.TILE_RADIUS_DP))
        } else {
            null
        }
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
            avatarPhoto.layoutParams = LayoutParams(dp(avatarSizeDp), dp(avatarSizeDp), Gravity.CENTER)
            // 尺寸变了要重算裁剪轮廓，否则还按旧边长裁，圆会偏。
            avatarPhoto.invalidateOutline()
        }
        /*
          显示名与头像交给宿主解析（见 IMProfileResolver）。没配 resolver 时
          resolvedName 原样返回 label，行为与加这个钩子之前完全一致。
        */
        val resolver = IMCallKit.config.profileResolver
        val shown = resolvedName(resolver, uid, label)
        val photo = resolvedAvatar(resolver, uid)
        avatar.textSize = (avatarSizeDp / 3f)
        /*
          **底色按 uid 取，首字母按显示名取。**
          底色跟 uid 走才能五端稳定（规范 §02）——同一个人在谁的屏幕上都是同一个颜色；
          而显示名是每台设备各算各的（备注！），拿它取色会让同一个人换台设备就变个颜色。
        */
        avatar.text = IMAvatar.initial(shown)
        avatar.background = IMKitTheme.avatarDrawable(uid.ifEmpty { label })
        // 有图就盖上去（居中裁切 + 圆形裁剪都由 avatarPhoto 负责）；没有就露出下面的色块。
        avatarPhoto.setImageDrawable(photo)
        avatarPhoto.visibility = if (photo == null) GONE else VISIBLE
        // 没画面时露出头像。**用 visibility 不改层级**：层级一动，媒体层挂着的渲染器会跟着重来。
        avatar.visibility = if (hasVideo) GONE else VISIBLE
        videoHost.visibility = if (hasVideo) VISIBLE else INVISIBLE
        namePlate.text = shown
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

    /**
     * 名字最多占多宽。
     *
     * 格子多宽只有量出来才知道，所以在这里算：整格宽减掉两侧留白、静音角标与那 4dp 间隙。
     * 不设上限的话，长名字会把静音角标一路顶出格子外。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        namePlate.maxWidth = (w - dp(PLATE_INSET_DP) * 2 - dp(20) - dp(4)).coerceAtLeast(dp(24))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /**
         * 角标离格子边缘的距离。
         *
         * 12 而不是 8：格子有 10dp 圆角，名字牌贴到 8 会被圆角切掉一截——
         * 有的机型上直接看不全（真机反馈）。
         */
        const val PLATE_INSET_DP = 12
    }
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
