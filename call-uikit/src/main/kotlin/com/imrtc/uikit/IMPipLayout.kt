package com.imrtc.uikit

/**
 * 小窗（PiP）的位置算术：四角吸附、控制条避让、按容器形状选尺寸（交互稿 §04）。
 *
 * 纯函数，不碰 View，与 iOS 的 `IMPipLayout.swift` / Web 的 `layout/pip.ts` 是同一份算法——
 * 同样的容器、同样的松手点，三端必须吸到同一个角。手势那一层在 [IMPipView]。
 * 单位 dp（View 层乘密度）。
 */
internal object IMPipLayout {

    enum class Corner(val isRight: Boolean, val isBottom: Boolean) {
        TOP_LEFT(false, false), TOP_RIGHT(true, false), BOTTOM_LEFT(false, true), BOTTOM_RIGHT(true, true);

        companion object {
            /** 默认停右上角：本端画面惯例在这里。 */
            val DEFAULT = TOP_RIGHT
        }
    }

    data class Size(val width: Double, val height: Double)
    data class Point(val x: Double, val y: Double)

    /** 小窗离容器边缘的距离（规范 §04）。 */
    const val INSET = 12.0
    /** 控制条显示时，停在下面两个角的小窗要上移这么多。 */
    const val LIFT = 88.0
    /** 竖屏容器 3:4；横屏 16:9。 */
    val PORTRAIT = Size(96.0, 128.0)
    val LANDSCAPE = Size(160.0, 90.0)

    /** 按**容器形状**选尺寸。判据是容器不是设备：平板分屏成窄条也该按竖屏那套走。 */
    fun sizeFor(containerWidth: Double, containerHeight: Double): Size {
        if (containerHeight <= 0) return LANDSCAPE
        return if (containerWidth / containerHeight < 1) PORTRAIT else LANDSCAPE
    }

    /** 松手时吸附到**最近的角**（按小窗中心算），不是最近的边。 */
    fun nearestCorner(point: Point, containerWidth: Double, containerHeight: Double): Corner {
        val right = point.x > containerWidth / 2
        val bottom = point.y > containerHeight / 2
        return when {
            right && bottom -> Corner.BOTTOM_RIGHT
            right -> Corner.TOP_RIGHT
            bottom -> Corner.BOTTOM_LEFT
            else -> Corner.TOP_LEFT
        }
    }

    /** 某个角的小窗左上角。`lift` 是控制条显示时下面两个角的上移量。 */
    fun origin(corner: Corner, size: Size, containerWidth: Double, containerHeight: Double, lift: Double = 0.0): Point {
        val x = if (corner.isRight) containerWidth - size.width - INSET else INSET
        val y = if (corner.isBottom) containerHeight - size.height - INSET - lift else INSET
        return Point(maxOf(x, 0.0), maxOf(y, 0.0))
    }

    /** 拖动中的左上角夹在容器里，不让小窗被拖出边界。 */
    fun clamp(origin: Point, size: Size, containerWidth: Double, containerHeight: Double): Point {
        val maxX = maxOf(containerWidth - size.width, 0.0)
        val maxY = maxOf(containerHeight - size.height, 0.0)
        return Point(origin.x.coerceIn(0.0, maxX), origin.y.coerceIn(0.0, maxY))
    }
}
