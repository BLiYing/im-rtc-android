package com.imrtc.uikit

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
        val caller = state.members.keys.firstOrNull() ?: state.peer
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
        } else {
            avatar.text = ""
            avatar.background = photo
        }
        title.text = callerName
        subtitle.text = state.statusText
        acceptButton.setImageResource((if (state.mediaType == "video") IMKitIcon.VIDEO else IMKitIcon.PHONE).resId)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
