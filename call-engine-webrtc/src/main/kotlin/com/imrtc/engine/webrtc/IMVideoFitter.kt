package com.imrtc.engine.webrtc

import android.os.Handler
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 每块渲染器按 [IMVideoFit] 的判据决定是裁切填满还是等比留边；帧尺寸或父容器一变就重算。
 *
 * 从 `IMWebRTCAdapter` 拆出来的（那边贴着 600 行的闸），行为没变。
 *
 * # 为什么量的是**父容器**而不是渲染器自己
 *
 * 渲染器给的是 `WRAP_CONTENT`（见 `IMVideoTile`），它的尺寸**由 scalingType 反推出来**——
 * 拿它去决定 scalingType 就成了循环。父容器（`videoHost`）是 `MATCH_PARENT`、
 * 尺寸稳定，才是「这一格有多大」的正确来源。
 *
 * # 为什么不能给 `MATCH_PARENT`
 *
 * libwebrtc 的 `RendererCommon.VideoLayoutMeasure.measure()` 里有一句
 * 「If the measure specification is forcing a specific size, yield」——
 * 测量规格是 `EXACTLY`（`MATCH_PARENT` 就是）时它**直接忽略 scalingType**、
 * 占满给定尺寸再裁切填充。2026-09-10 第一版改动就栽在这里：只设了
 * `setScalingType`、而 `IMVideoTile` 给的是 `MATCH_PARENT`，**是个空操作**，
 * 真机上仍然满格裁切。
 *
 * **除 [onFrameSize] 之外都必须在主线程调**（`setScalingType` 头一行就查线程，见 `IMWebRTCAdapter` 类注释）。
 */
internal class IMVideoFitter(
    private val main: Handler,
    /** 钥匙（uid 或本端预览那把）→ 当前挂着的渲染器。只在主线程上查。 */
    private val rendererFor: (String) -> SurfaceViewRenderer?,
) {

    /** 钥匙 → 最近一帧的 `[未旋转宽, 未旋转高, 旋转角]`。布局变化时要拿它重算。 */
    private val lastFrameSize = LinkedHashMap<String, IntArray>()

    /** 渲染器刚 `init` 完：按上次记下的尺寸先算一次，并盯住父容器的布局变化。 */
    fun mount(key: String, renderer: SurfaceViewRenderer) {
        apply(renderer, lastFrameSize[key])
        // 父容器尺寸一变就重算（转屏、进出全屏、九宫格行列变化都会走到）。
        renderer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            apply(renderer, lastFrameSize[key])
        }
    }

    /**
     * 记下这一路的帧尺寸并重算缩放。**在渲染线程上被调用**，所以先回主线程。
     *
     * 布局也要监听（[mount]）：第一帧到达时父容器可能还没量出来（宽高是 0），
     * 那时 [IMVideoFit.shouldFill] 会先给 FILL，等 layout 稳定后再算一次。
     */
    fun onFrameSize(key: String, width: Int, height: Int, rotation: Int) {
        val frame = intArrayOf(width, height, rotation)
        main.post {
            lastFrameSize[key] = frame
            rendererFor(key)?.let { apply(it, frame) }
        }
    }

    /** **两个参数都要传**：单参版只设「方向一致」那一种，而方向不一致恰恰是要处理的那一种。 */
    private fun apply(renderer: SurfaceViewRenderer, frame: IntArray?) {
        val host = renderer.parent as? android.view.View
        val fill = frame == null ||
            IMVideoFit.shouldFill(frame[0], frame[1], frame[2], host?.width ?: 0, host?.height ?: 0)
        val type = if (fill) {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        }
        renderer.setScalingType(type, type)
        renderer.requestLayout()
    }
}
