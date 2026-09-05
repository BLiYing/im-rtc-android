package com.imrtc.demo

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 占位首页。第五刀换成草图 §02 的三屏（拨号 / 通话记录 / 设置）。
 *
 * 现在它存在的唯一理由：让 `assembleDebug` 真的产出一个能装的 APK，
 * 骨架阶段就把「四个模块能一起编成 App」这条路走通，别等到第五刀才发现构建配错了。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "im-rtc Demo —— 骨架阶段，尚无功能"
            textSize = 16f
            setPadding(48, 96, 48, 48)
        })
    }
}
