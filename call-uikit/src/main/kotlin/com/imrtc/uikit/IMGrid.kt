package com.imrtc.uikit

/**
 * 九宫格的布局算术。**纯函数，不碰 View**——所以它能在纯 JVM 单测里验，不需要设备。
 *
 * 群通话上限 9 = 3×3（拍板 §11 #3）。不做「9 格显示 N 人」的分页轮换：
 * 分页画廊是会议期的事，MVP 里超过 9 人根本进不来。
 *
 * 行列**跟着容器形状走**（`aspect` = 宽/高），与 iOS 的 `imGridDimensions` / Web 的 `gridDimensions`
 * 是同一个算法：3~4 格在竖屏恒为两列，其余让正方形格子尽量大、平手时取行数少的。
 * 竖屏手机上 2 个人是上下摞，横屏才是左右排。
 */
internal object IMGrid {

    /** 一屏最多几个人。 */
    const val MAX_TILES = 9

    /**
     * 远端最多几个格子。
     *
     * **本端恒占一格**（九宫格里自己也是一格），所以远端只剩 8 个位置。
     * 按 [MAX_TILES] 截远端的话，会议房（服务端不设人数上限）进到第 10 个人时
     * 算出来是「9 个远端 + 自己 = 10 格」，而行列只有 9 个坑：GridLayout 会越过
     * `rowCount` 往下多排一行、跑出居中块，iOS 悄悄丢掉多的，Web 的 CSS grid 溢出——
     * **同一个房间三端长得不一样**。
     */
    const val MAX_REMOTE_TILES = MAX_TILES - 1

    /** 间距占容器短边的比例，只影响边界情况。五端同一个数。 */
    private const val GAP_RATIO = 0.02

    /**
     * 给定人数与容器宽高比，返回 (列数, 行数)。默认 `aspect = 0.7`——竖屏手机上
     * 头部与控制条之间那块区域的形状（与 iOS 测试里的 `phone` 同值）。
     *
     * **3~4 格在竖屏容器里恒为两列**：这一条是产品决定，不是尺寸最优解——
     * 竖屏上 3 个人排成一竖条，格子其实比 2×2 还大一点（0.325 vs 0.294），
     * 但没人管那叫「九宫格」，交互稿 §05 画的就是「第一行两个」。
     * 而按「格子最大」去挑的话，翻转恰好压在手机的常见比例上（3 格 `aspect ≈ 0.662`、
     * 4 格 `≈ 0.495`），同一通电话换台设备就是另一种版式。
     * 横屏（`aspect >= 1`）不受这条约束：宽容器上 3 个人一行排开本来就更好。
     */
    @JvmStatic
    @JvmOverloads
    fun dimensions(count: Int, aspect: Double = 0.7): Pair<Int, Int> {
        val n = count.coerceIn(1, MAX_TILES)
        val width = maxOf(aspect, 0.01)
        val gap = minOf(width, 1.0) * GAP_RATIO
        // 竖屏容器下 3~4 格恒为两列（见上）。
        if (width < 1.0 && (n == 3 || n == 4)) return 2 to (n + 1) / 2
        var best = n to 1
        var bestSide = -1.0
        for (columns in 1..n) {
            val rows = (n + columns - 1) / columns
            val side = minOf((width - (columns - 1) * gap) / columns, (1.0 - (rows - 1) * gap) / rows)
            // 严格大于才换；平手时取**行数更少**的那个。
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

    /**
     * 「还有 N 人未显示」胶囊的文案（MEETING_ROOM_DESIGN §4.5，会议房 M1 止血）；没人被截掉时是空串。
     * 会议房原先超过 9 人时多出来的人**无声消失**（2026-09-09 真机）。与 Web `hiddenCountText`、iOS 同一句。
     */
    fun hiddenCountText(hidden: Int): String = if (hidden > 0) "还有 $hidden 人未显示" else ""

    /** 通话时长格式化：一小时以内 `mm:ss`，超过就 `h:mm:ss`。 */
    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "00:00"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
