package com.imrtc.uikit

import android.graphics.Color

/**
 * 通话界面的视觉常量。
 *
 * **通话页固定深色、不随宿主主题**（对齐草图 §01；FaceTime / Telegram 同做法）——
 * 通话是个全屏沉浸场景，跟着宿主的浅色主题走会让视频画面周围一片刺眼的白。
 *
 * 颜色集中在这里，**禁止在组件里硬编码色值**：宿主要换皮时只改这一个文件。
 */
internal object IMKitTheme {

    val background = Color.parseColor("#111214")
    val tileBackground = Color.parseColor("#1E2024")
    val primaryText = Color.parseColor("#FFFFFF")
    val secondaryText = Color.parseColor("#A8ACB3")

    /** 控制按钮：**开启态白底黑字**，关闭态深底白字。 */
    val controlOn = Color.parseColor("#FFFFFF")
    val controlOnIcon = Color.parseColor("#111214")
    val controlOff = Color.parseColor("#2C2F36")
    val controlOffIcon = Color.parseColor("#FFFFFF")

    /** 挂断恒红、接听恒绿——**这两个颜色不许换**，肌肉记忆比品牌色重要。 */
    val hangup = Color.parseColor("#E5484D")
    val answer = Color.parseColor("#30A46C")

    /** 正在说话的高亮边框。 */
    val speaking = Color.parseColor("#30A46C")

    /** 控制按钮直径（dp）。 */
    const val CONTROL_SIZE_DP = 56

    const val TILE_GAP_DP = 4
}
