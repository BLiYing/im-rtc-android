package com.imrtc.uikit

import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.content.Context
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 来电横幅（规范 §06「来电横幅」）：左右各留 8、高 62、圆角 16；头像 38（渐变底 + 首字母）+ 两行字 +
 * 拒绝 / 接听两个 38 圆。**来电先出横幅，不直接全屏**——用户正在打字时被一整屏盖住很粗暴。
 * 点横幅本体展开成全屏来电页。**没有「5s 自动升级为全屏」**（v3.2 撤掉）：
 * 要么横幅要么全屏，由 `IMCallKitConfig.bannerFirst` 一个开关决定，
 * 中途自己变身既没必要、也让「现在到底该显示哪一个」多出一条时间维度的分支。
 *
 * 视频来电多一颗**关摄像头**：接起来之前就能决定要不要出镜（Web 端一直有，两端补齐）。
 * 图标一律矢量，不用 emoji。
 *
 * **两个按钮之间要留够间距**：接听与拒绝挨太近，误触的代价是「本来想接却挂了对方」。
 */
internal class IMIncomingBanner(context: Context) : LinearLayout(context) {

    var onExpand: (() -> Unit)? = null
    var onAccept: (() -> Unit)? = null
    var onReject: (() -> Unit)? = null
    /** 视频来电上的「关摄像头」：接起来之前先决定要不要出镜。 */
    var onToggleCamera: (() -> Unit)? = null

    private val avatar = TextView(context)
    /** 宿主给的头像图。独立 ImageView + 居中裁切 + 圆形裁剪，理由见 IMVideoTile 同名字段。 */
    private val avatarPhoto = ImageView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val cameraButton = roundButton(IMKitTheme.controlOff, IMKitTheme.primaryText, IMKitIcon.VIDEO, "关摄像头")
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
        // 头像与图**必须叠在同一格里**：直接加进水平排布会变成左右并排两个头像。
        val avatarBox = FrameLayout(context)
        avatarBox.addView(avatar, FrameLayout.LayoutParams(dp(38), dp(38)))

        avatarPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
        avatarPhoto.visibility = GONE
        avatarPhoto.clipToOutline = true
        avatarPhoto.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val side = minOf(view.width, view.height)
                outline.setRoundRect(0, 0, side, side, side / 2f)
            }
        }
        // 加在 avatar 之后，层级才在它上面。
        avatarBox.addView(avatarPhoto, FrameLayout.LayoutParams(dp(38), dp(38)))
        addView(avatarBox, LayoutParams(dp(38), dp(38)))

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
        cameraButton.setOnClickListener { onToggleCamera?.invoke() }
        addView(cameraButton, LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
        rejectButton.setOnClickListener { onReject?.invoke() }
        addView(rejectButton, LayoutParams(dp(38), dp(38)))
        addView(acceptButton, LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(10) })
        acceptButton.setOnClickListener { onAccept?.invoke() }

        // 点两个按钮之外的地方 = 展开全屏。
        setOnClickListener { onExpand?.invoke() }
        contentDescription = "来电，点击展开"
    }

    fun render(state: IMCallViewState) {
        // 显示「谁邀请的你」：群通话中途加你进来的人不一定是发起人（见 incomingFromUid）。
        val caller = state.incomingFromUid
        /*
          来电屏是**最不能显示成一串 uid** 的一屏，也是最可能解析不出来的一屏
          （陌生人来电时宿主本机没有对方名片）。解析不到就退化成 uid，
          宿主的解析器拉回来后调 IMCallKit.reloadProfiles 重画。
        */
        val resolver = IMCallKit.config.profileResolver
        val callerName = resolvedName(resolver, caller, caller)
        val photo = resolvedAvatar(resolver, caller)
        if (photo == null) {
            // 底色按 uid、首字母按显示名——同 IMVideoTile，理由见那边的注释。
            avatar.text = IMAvatar.initial(callerName)
            avatar.background = IMKitTheme.avatarDrawable(caller)
            avatarPhoto.setImageDrawable(null)
            avatarPhoto.visibility = GONE
        } else {
            // 色块留在底下，图盖上去——切回无图时不用重建背景。
            avatar.text = IMAvatar.initial(callerName)
            avatar.background = IMKitTheme.avatarDrawable(caller)
            avatarPhoto.setImageDrawable(photo)
            avatarPhoto.visibility = VISIBLE
        }
        title.text = callerName
        subtitle.text = state.statusText
        // 接听键恒为听筒（构造时已设），与来电页那颗、与 Web 一致（UI_SPEC「phone · 来电页、来电横幅」）。
        // 原先视频来电换成摄像机图标：群通话默认关着摄像头也显示摄像机，像是「以视频接听」。
        // 出不出镜只由最左那颗摄像头开关表达（§11-10）。
        // 语音来电没有摄像头可关。
        cameraButton.visibility = if (state.showsCameraButton) VISIBLE else GONE
        cameraButton.setImageResource((if (state.cameraOn) IMKitIcon.VIDEO else IMKitIcon.VIDEO_SLASH).resId)
        cameraButton.contentDescription = if (state.cameraOn) "关摄像头" else "开摄像头"
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
}
