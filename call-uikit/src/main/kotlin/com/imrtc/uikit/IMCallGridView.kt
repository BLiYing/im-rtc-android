package com.imrtc.uikit

import android.content.Context
import android.view.View
import android.widget.GridLayout

/**
 * 九宫格容器。与 iOS 的 `IMCallGridView`、Web 的 `GridStage` 是同一层（三端同名同职责）。
 *
 * # 格子恒为正方形、整块居中
 *
 * **不给 spec 带权重**：带权重的话 GridLayout 会把剩余空间摊到每一格上，
 * 算出来的正方形边长当场被撑没——竖屏两个人就变成两条又高又窄的长条，
 * 与 iOS 完全不是一个样子。整块的居中交给容器自己的 `Gravity.CENTER`。
 *
 * # 没变就一格都不许重挂
 *
 * [apply] 每次 `render` 都会被调到，而 `render` 是**每秒好几次**（计时器 1s 一跳、
 * 网络质量与主讲人都是周期帧）。原先它无条件 `removeAllViews()` 再逐个 `addView`——
 * 而格子里装的是 `SurfaceViewRenderer`：**SurfaceView 一从 window 上摘下来，
 * Surface 就被销毁、重挂时重建**，中间必然黑一帧，还要再等一个关键帧。
 * 真机上看到的「视频一直在闪」就是这么来的。
 *
 * 所以分三档：什么都没变 → 直接返回；只有格子大小变了（转屏、控制条高度变化）→
 * 就地改 LayoutParams；只有格子集合变了才重挂。
 * iOS 的 `IMCallGridView.layout` 是同一条判据（`guard wanted != tiles`）。
 */
internal class IMCallGridView(context: Context) : GridLayout(context) {

    init {
        alignmentMode = ALIGN_BOUNDS
    }

    /** 当前摆着的那批格子，按加入顺序。 */
    var tiles: List<View> = emptyList()
        private set

    private var cellWidth = -1
    private var cellHeight = -1

    /**
     * 摆一批格子。
     *
     * @param wanted 要摆的格子，第一个是本端。
     * @param boxWidth 可用区宽（**已扣掉外边距与一整个 gap**）。量不出来时传 <= 0。
     * @param boxHeight 可用区高，同上。
     * @param gap 格子之间的间距（像素）。
     * @param fallbackCell 容器还没量出来时先用的边长——第一轮 render 早于第一次 layout，
     *   那时只能按竖屏手机的形状估一版，`onLayout` 量到真尺寸会再来一次。
     */
    fun apply(wanted: List<View>, boxWidth: Int, boxHeight: Int, gap: Int, fallbackCell: Int) {
        val measured = boxWidth > 0 && boxHeight > 0
        val aspect = if (measured) boxWidth.toDouble() / boxHeight else DEFAULT_ASPECT
        val (columns, rows) = IMGrid.dimensions(wanted.size, aspect)
        val wantedWidth: Int
        val wantedHeight: Int
        when {
            !measured -> { wantedWidth = fallbackCell; wantedHeight = fallbackCell }
            // 只有一格时铺满：正方形是为了「多格之间不互相拉伸」，一格时没有别人可比。
            wanted.size <= 1 -> { wantedWidth = boxWidth; wantedHeight = boxHeight }
            else -> {
                val side = IMGrid.cellSide(columns, rows, boxWidth, boxHeight, gap)
                wantedWidth = side
                wantedHeight = side
            }
        }

        val sameTiles = wanted == tiles
        val sameCells = wantedWidth == cellWidth && wantedHeight == cellHeight &&
            columns == columnCount && rows == rowCount
        if (sameTiles && sameCells) return
        tiles = ArrayList(wanted)
        cellWidth = wantedWidth
        cellHeight = wantedHeight

        if (sameTiles) {
            // 只是尺寸变了：**就地改**，一个 SurfaceView 都不摘。
            columnCount = columns
            rowCount = rows
            for (view in wanted) {
                val params = view.layoutParams as? LayoutParams ?: continue
                params.width = wantedWidth
                params.height = wantedHeight
                view.layoutParams = params
            }
            return
        }

        // **先摘光再改行列数**：GridLayout 的行列数与在场子视图的 spec 是一起校验的，
        // 先缩小再摘会在下一次 measure 上抛 IllegalArgumentException。
        removeAllViews()
        columnCount = columns
        rowCount = rows
        for (view in wanted) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            addView(
                view,
                LayoutParams().apply {
                    width = wantedWidth
                    height = wantedHeight
                    columnSpec = spec(UNDEFINED)
                    rowSpec = spec(UNDEFINED)
                    setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                },
            )
        }
    }

    private companion object {
        /** 量不出来时按竖屏手机的舞台区形状估（与 `IMGrid.dimensions` 的默认值同源）。 */
        const val DEFAULT_ASPECT = 0.7
    }
}
