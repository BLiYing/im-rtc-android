package com.imrtc.uikit

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * 设计令牌 —— 设计稿《通话界面规范》§02–§04、§07 的落点。
 *
 * **所有色值、尺寸、时长集中在这里，组件里禁止再出现字面量**。字段名与 iOS 的 `IMKitTheme` /
 * Web 的 `theme.ts` 逐条对应，五端同值。
 *
 * **通话页固定深色、不随宿主主题**（对齐草图 §01；FaceTime / Telegram 同做法）——
 * 通话是个全屏沉浸场景，跟着宿主的浅色主题走会让视频画面周围一片刺眼的白。
 */
internal object IMKitTheme {

    // ── 颜色（规范 §02 的 11 个语义色）──────────────────────────────
    /** 全屏遮罩底 #121418。 */
    val background = Color.parseColor("#121418")
    /** 语音通话页的径向渐变：#2A3350 → #0F1117。 */
    val callGradientTop = Color.parseColor("#2A3350")
    val callGradientBottom = Color.parseColor("#0F1117")
    /** 格子底：没有画面时露出来的那层。 */
    val tileBackground = Color.parseColor("#000000")
    /** 头像兜底底色。 */
    val avatarBackground = Color.parseColor("#2B3038")
    val primaryText = Color.parseColor("#FFFFFF")
    val secondaryText = Color.parseColor("#B3FFFFFF") // 白 70%
    /** 控制按钮常态（白 14%）与开启态（**反白，不是变蓝**）。 */
    val controlOff = Color.parseColor("#24FFFFFF")
    val controlOffIcon = Color.parseColor("#FFFFFF")
    val controlOn = Color.parseColor("#FFFFFF")
    val controlOnIcon = Color.parseColor("#121418")
    /** 挂断恒红、接听恒绿——**这两个颜色不许换**，肌肉记忆比品牌色重要。 */
    val hangup = Color.parseColor("#E5484D")
    val answer = Color.parseColor("#3DDC84")
    val answerIcon = Color.parseColor("#08210F")
    /** 正在说话的描边。**绿色在通话页只有接听与发言两个含义**。 */
    val speaking = Color.parseColor("#3DDC84")
    /** 「网络不佳」「正在重连…」的横幅与角标。 */
    val warning = Color.parseColor("#F5A623")
    /** 格子上「对方已静音」角标的图标色。 */
    val mutedBadge = Color.parseColor("#FFB4AE")
    /** 横幅 / 提示卡 / 选人页 / 悬浮球的底。 */
    val bannerBackground = Color.parseColor("#1E2330")
    /** 名字标签、角标的黑底 55%。 */
    val scrim = Color.parseColor("#8C000000")

    // ── 尺寸（规范 §04，单位 dp）─────────────────────────────────────
    const val CONTROL_SIZE_DP = 56
    const val CONTROL_SIZE_BIG_DP = 64
    const val CONTROL_SIZE_SMALL_DP = 44
    const val ICON_DP = 26
    const val ICON_BIG_DP = 30
    const val ICON_SMALL_DP = 22
    const val AVATAR_LARGE_DP = 96
    const val TILE_GAP_DP = 8
    const val TILE_RADIUS_DP = 10
    const val PIP_RADIUS_DP = 12
    const val HEADER_HEIGHT_DP = 64
    const val CONTROLS_HEIGHT_DP = 96
    /** 悬浮球：语音 56 圆；视频 90×120 带缩略画面。 */
    const val BUBBLE_SIZE_DP = 56
    const val BUBBLE_VIDEO_W_DP = 90
    const val BUBBLE_VIDEO_H_DP = 120

    // ── 动效（规范 §07）───────────────────────────────────────────────
    const val PRESS_MS = 120L
    const val SNAP_MS = 250L
    const val FADE_MS = 200L
    const val AUTO_HIDE_MS = 3_000L
    const val LONG_PRESS_MS = 350L
    const val SETTLED_HOLD_MS = 2_000L
    /** 一次性提示（「通话已满员」「对方已拒接」）停多久自己撤掉。 */
    const val HINT_HOLD_MS = 3_000L
    const val NETWORK_BANNER_MS = 2_000L
    /** 横幅 5s 不处理升级为全屏来电页（交互稿 §06）。 */
    const val BANNER_ESCALATE_MS = 5_000L

    /**
     * 九个头像渐变（规范 §02），取哪一个见 [IMAvatar.index]：**同一个 uid 五端同色**。
     */
    val avatarGradients: List<IntArray> = listOf(
        intArrayOf(0xFF9E7BF0.toInt(), 0xFF6E52D6.toInt()),
        intArrayOf(0xFF3AA0FF.toInt(), 0xFF0A6BE0.toInt()),
        intArrayOf(0xFF4CD268.toInt(), 0xFF28B14A.toInt()),
        intArrayOf(0xFFFBB040.toInt(), 0xFFF5872B.toInt()),
        intArrayOf(0xFFFF7AA8.toInt(), 0xFFE0559E.toInt()),
        intArrayOf(0xFF5ED3D0.toInt(), 0xFF2AA6A3.toInt()),
        intArrayOf(0xFFB0B8C8.toInt(), 0xFF7E8797.toInt()),
        intArrayOf(0xFFF08A5D.toInt(), 0xFFC94F3B.toInt()),
        intArrayOf(0xFF7C9CF0.toInt(), 0xFF4C6BD6.toInt()),
    )

    fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun roundedDrawable(color: Int, radiusPx: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(color)
    }

    /** 头像盘：160° 方向的渐变圆。 */
    fun avatarDrawable(uid: String): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        avatarGradients[IMAvatar.index(uid)],
    ).apply { shape = GradientDrawable.OVAL }

    /** 语音页的径向渐变底。 */
    fun callBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(callGradientTop, callGradientBottom),
    ).apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        setGradientCenter(0.5f, 0f)
        gradientRadius = 1_400f
    }

    /** 视频页控制条底下那层透明 → 黑 55% 的渐变，否则浅色画面上白图标看不见（规范 §04）。 */
    fun controlsScrim(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.TRANSPARENT, scrim),
    )
}

/** 图标 —— 与设计稿 §05 的对照表一致，五端同一份路径（`res/drawable/ic_im_*.xml`）。 */
internal enum class IMKitIcon(val resId: Int) {
    MIC(R.drawable.ic_im_mic),
    MIC_SLASH(R.drawable.ic_im_mic_slash),
    VIDEO(R.drawable.ic_im_video),
    VIDEO_SLASH(R.drawable.ic_im_video_slash),
    PHONE(R.drawable.ic_im_phone),
    PHONE_DOWN(R.drawable.ic_im_phone_down),
    XMARK(R.drawable.ic_im_xmark),
    MINIMIZE(R.drawable.ic_im_minimize),
    EXPAND(R.drawable.ic_im_expand),
    SPEAKER(R.drawable.ic_im_speaker),
    SPEAKER_SLASH(R.drawable.ic_im_speaker_slash),
    CAMERA_FLIP(R.drawable.ic_im_camera_flip),
    PERSON_ADD(R.drawable.ic_im_person_add),
    PLUS(R.drawable.ic_im_plus),
    CHEVRON_DOWN(R.drawable.ic_im_chevron_down),
    MORE(R.drawable.ic_im_more),
    SCREEN_SHARE(R.drawable.ic_im_screen_share),
    GRID(R.drawable.ic_im_grid),
    SETTINGS(R.drawable.ic_im_settings),
}
