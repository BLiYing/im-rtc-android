package com.imrtc.uikit

/**
 * 九宫格的布局算术。**纯函数，不碰 View**——所以它能在纯 JVM 单测里验，不需要设备。
 *
 * 群通话上限 9 = 3×3（拍板 §11 #3）。不做「9 格显示 N 人」的分页轮换：
 * 分页画廊是会议期的事，MVP 里超过 9 人根本进不来。
 *
 * 行列**跟着容器形状走**（`aspect` = 宽/高），与 iOS 的 `imGridDimensions` / Web 的 `gridDimensions`
 * 是同一个算法：让正方形格子尽量大，平手时取行数少的。竖屏手机上 2 个人是上下摞，横屏才是左右排。
 */
internal object IMGrid {

    /** 一屏最多几个人。 */
    const val MAX_TILES = 9

    /** 间距占容器短边的比例，只影响边界情况。五端同一个数。 */
    private const val GAP_RATIO = 0.02

    /**
     * 给定人数与容器宽高比，返回 (列数, 行数)。默认 `aspect = 0.7`——竖屏手机上
     * 头部与控制条之间那块区域的形状（与 iOS 测试里的 `phone` 同值）。
     */
    @JvmStatic
    @JvmOverloads
    fun dimensions(count: Int, aspect: Double = 0.7): Pair<Int, Int> {
        val n = count.coerceIn(1, MAX_TILES)
        val width = maxOf(aspect, 0.01)
        val gap = minOf(width, 1.0) * GAP_RATIO
        var best = n to 1
        var bestSide = -1.0
        for (columns in 1..n) {
            val rows = (n + columns - 1) / columns
            val side = minOf((width - (columns - 1) * gap) / columns, (1.0 - (rows - 1) * gap) / rows)
            if (side > bestSide || (side == bestSide && rows < best.second)) {
                best = columns to rows
                bestSide = side
            }
        }
        return best
    }

    /** 正方形格子的边长（像素）。**格子必须是正方形**：吃满整块区域的话，竖屏两个人就是两条细长条。 */
    fun cellSide(columns: Int, rows: Int, width: Int, height: Int, gap: Int): Int {
        val byWidth = (width - (columns - 1) * gap) / columns
        val byHeight = (height - (rows - 1) * gap) / rows
        return maxOf(minOf(byWidth, byHeight), 0)
    }

    /**
     * 每个格子该订阅到哪一层。**格子越小越该要小图**：九宫格里八个小格子每个都收大图，既费带宽又费解码。
     * 这个上界会随订阅一起发给服务端（`max_layer`），**漏发的话服务端记 m、实际发 h**。
     */
    fun layerFor(count: Int, focused: Boolean): String = when {
        focused -> "h"
        count <= 1 -> "h"
        count <= 4 -> "m"
        else -> "l"
    }

    /** 通话时长格式化：一小时以内 `mm:ss`，超过就 `h:mm:ss`。 */
    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "00:00"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
