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
 *
 * # 改行列数之前，先把 spec 退回 UNDEFINED
 *
 * 格子是不写行列的（`spec(UNDEFINED)`），**但 GridLayout 每次 measure 都会在
 * `validateLayoutParams()` 里把它们改写成具体的行列下标**（`columnSpec` 变成 `[2,3)` 这种）。
 * 于是「在场子视图算出来的最大下标」= 上一版的列数；下一次把 `columnCount` 调**小**，
 * `Axis.setCount` 拿它一比就当场抛 `IllegalArgumentException`。
 *
 * 而列数**同一批人也会变**：第一轮 `render` 早于第一次 layout，只能按默认 `aspect = 0.7`
 * 估（9 个人 → 3×3）；量到真尺寸那一轮 `aspect` 是 0.48（控制条的下 padding 还没生效），
 * 9 个人变成 2×5——**发起群通话就是这么闪退的**，一次转屏也是同一条路。
 */
internal class IMCallGridView(context: Context) : GridLayout(context) {

    init {
        alignmentMode = ALIGN_BOUNDS
    }

    /**
     * 分页时把整块撑到「满一页」那么大。
     *
     * GridLayout 只按在场的行列算测量结果，末页不满就会缩水，再被 `Gravity.CENTER`
     * 摆到屏幕正中——而设计要求的是**格子位置固定、从左上往下排**（§4.1）：
     * 左滑一页格子还在原地，只是换了人。撑满之后前几格自然落在第一行。
     */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        if (reservedRows <= 0 || cellWidth <= 0 || cellHeight <= 0) return
        // 每格四周各留 gap/2 的外边距，所以一格实际占 cell + gap。
        val wantedWidth = reservedColumns * (cellWidth + reservedGap)
        val wantedHeight = reservedRows * (cellHeight + reservedGap)
        setMeasuredDimension(
            maxOf(measuredWidth, wantedWidth),
            maxOf(measuredHeight, wantedHeight),
        )
    }

    /** 当前摆着的那批格子，按加入顺序。 */
    var tiles: List<View> = emptyList()
        private set

    private var cellWidth = -1
    private var cellHeight = -1

    /**
     * 分页时**整块要占满一页的大小**，哪怕这一页没坐满（[onMeasure]）。
     *
     * 本视图是 `WRAP_CONTENT` + `Gravity.CENTER` 挂在舞台上的，而 GridLayout 的
     * 测量高度只按**在场的行**算：最后一页只有 3 个人时它缩成一行，然后被整块居中——
     * 看起来就是「三个人浮在屏幕中间」，而不是设计要求的「从左上往下排」（§4.1）。
     * 0 = 不预留（群通话，本来就坐满）。
     */
    private var reservedColumns = 0
    private var reservedRows = 0
    private var reservedGap = 0

    /**
     * 摆一批格子。
     *
     * @param wanted 要摆的格子，第一个是本端。
     * @param boxWidth 可用区宽（**已扣掉外边距与一整个 gap**）。量不出来时传 <= 0。
     * @param boxHeight 可用区高，同上。
     * @param gap 格子之间的间距（像素）。
     * @param fallbackCell 容器还没量出来时先用的边长——第一轮 render 早于第一次 layout，
     *   那时只能按竖屏手机的形状估一版，`onLayout` 量到真尺寸会再来一次。
     * @param fixedTileCount 恒按这么多格算行列；0 = 按实际格数（群通话）。
     *   会议分页固定 3×3：**最后一页不满时格子和满页一样大**，不放大
     *   （MEETING_ROOM_DESIGN §4.1）——放大的话层会从 l 跳到 m、还要多等一次关键帧。
     */
    fun apply(
        wanted: List<View>,
        boxWidth: Int,
        boxHeight: Int,
        gap: Int,
        fallbackCell: Int,
        fixedTileCount: Int = 0,
    ) {
        val measured = boxWidth > 0 && boxHeight > 0
        val aspect = if (measured) boxWidth.toDouble() / boxHeight else DEFAULT_ASPECT
        // 分页时**不跟着容器形状变**：格子位置固定，左滑才只是换人而不是整屏重排（§4.1）。
        val (columns, rows) =
            if (fixedTileCount > 0) IMGrid.fixedDimensions(fixedTileCount)
            else IMGrid.dimensions(wanted.size, aspect)
        // 分页时整块恒占满一页，末页不满也从左上排起（见 reservedRows）。
        reservedColumns = if (fixedTileCount > 0) columns else 0
        reservedRows = if (fixedTileCount > 0) rows else 0
        reservedGap = gap
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

        /*
         判据看的是**在场的子视图**，不是上一次记下的 `tiles`：格子会被
         `IMCallView.pinFull` / `mountInPip` 从这里摘走挂到别处（全屏画面、小窗），
         那之后 `tiles` 与实际在场的就对不上了。只比 `tiles` 的话，
         视频版式切回九宫格时会认成「什么都没变」，格子再也回不来——**一屏空的九宫格**。
        */
        val sameTiles = childrenAre(wanted)
        val sameCells = wantedWidth == cellWidth && wantedHeight == cellHeight &&
            columns == columnCount && rows == rowCount
        if (sameTiles && sameCells) return
        tiles = ArrayList(wanted)
        cellWidth = wantedWidth
        cellHeight = wantedHeight

        if (sameTiles) {
            // 只是尺寸 / 行列变了：**就地改**，一个 SurfaceView 都不摘。
            // **必须先退 spec 再改行列数**（见类注释）：退完 `setLayoutParams` 会让
            // GridLayout 重算最大下标，这时列数往小改才不会抛。
            for (view in wanted) {
                val params = view.layoutParams as? LayoutParams ?: continue
                params.width = wantedWidth
                params.height = wantedHeight
                params.columnSpec = spec(UNDEFINED)
                params.rowSpec = spec(UNDEFINED)
                view.layoutParams = params
            }
            columnCount = columns
            rowCount = rows
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

    /** 在场的子视图是不是**正好**是这一批、且顺序一致。 */
    private fun childrenAre(wanted: List<View>): Boolean {
        if (childCount != wanted.size) return false
        for (i in wanted.indices) if (getChildAt(i) !== wanted[i]) return false
        return true
    }

    private companion object {
        /** 量不出来时按竖屏手机的舞台区形状估（与 `IMGrid.dimensions` 的默认值同源）。 */
        const val DEFAULT_ASPECT = 0.7
    }
}
