package com.imrtc.uikit

/**
 * 九宫格的布局算术。**纯函数，不碰 View**——所以它能在纯 JVM 单测里验，不需要设备。
 *
 * 群通话上限 9 = 3×3（拍板 §11 #3）。不做「9 格显示 N 人」的分页轮换：
 * 分页画廊是会议期的事，MVP 里超过 9 人根本进不来。
 */
internal object IMGrid {

    /** 一屏最多几个人。 */
    const val MAX_TILES = 9

    /** 给定人数，返回 (列数, 行数)。 */
    fun dimensions(count: Int): Pair<Int, Int> = when {
        count <= 1 -> 1 to 1
        count == 2 -> 1 to 2
        count <= 4 -> 2 to 2
        count <= 6 -> 2 to 3
        else -> 3 to 3
    }

    /**
     * 每个格子该订阅到哪一层。
     *
     * **格子越小越该要小图**：九宫格里八个小格子每个都收大图，既费带宽又费解码。
     * 这个上界会随订阅一起发给服务端（`max_layer`），**漏发的话服务端记 m、实际发 h**，
     * 那是 iOS/Web 两端都写进已知坑的一条。
     */
    fun layerFor(count: Int, focused: Boolean): String = when {
        focused -> "h"
        count <= 2 -> "h"
        count <= 4 -> "m"
        else -> "l"
    }

    /** 通话时长格式化：一小时以内 `mm:ss`，超过就 `h:mm:ss`。 */
    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "00:00"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }
}
