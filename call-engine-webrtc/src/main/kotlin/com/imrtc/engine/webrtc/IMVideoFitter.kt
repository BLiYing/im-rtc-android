package com.imrtc.engine.webrtc

import android.os.Handler
import com.imrtc.engine.log.IMRTCLog
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

    /** 钥匙 → 最近一次**打过日志**的「结论 + 容器尺寸」。见 [logFit]。 */
    private val loggedFit = LinkedHashMap<String, String>()

    /** 渲染器刚 `init` 完：按上次记下的尺寸先算一次，并盯住父容器的布局变化。 */
    fun mount(key: String, renderer: SurfaceViewRenderer) {
        apply(key, renderer, lastFrameSize[key])
        // 父容器尺寸一变就重算（转屏、进出全屏、九宫格行列变化都会走到）。
        renderer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            apply(key, renderer, lastFrameSize[key])
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
            rendererFor(key)?.let { apply(key, it, frame) }
        }
    }

    /** **两个参数都要传**：单参版只设「方向一致」那一种，而方向不一致恰恰是要处理的那一种。 */
    private fun apply(key: String, renderer: SurfaceViewRenderer, frame: IntArray?) {
        val host = renderer.parent as? android.view.View
        val hostWidth = host?.width ?: 0
        val hostHeight = host?.height ?: 0
        val fill = frame == null ||
            IMVideoFit.shouldFill(frame[0], frame[1], frame[2], hostWidth, hostHeight)
        val type = if (fill) {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        }
        logFit(key, frame, hostWidth, hostHeight, fill)
        renderer.setScalingType(type, type)
        renderer.requestLayout()
    }

    /**
     * 打一行判据实况：**这一格此刻量到的两个尺寸，以及算出来的结论**。
     *
     * 判据算错的症状是「画面糊」或「莫名黑边」，**一条错都不报**——2026-09-18 会议房
     * 真机就卡在这儿：Android 给横屏源留了黑边（`VIDEO_RENDERING.md` 表格第 4 行，
     * 当日已拍板「不改」），iOS 同一个槽位（钉住的主画面）却铺满了，
     * 而两端日志里都看不出它们各自量到的是多大的容器、多大的源。
     *
     * # 判重的键为什么不含源尺寸
     *
     * 远端每换一次层它就变一次（320×180 ↔ 1280×720），进键里就是刷屏；
     * 而要查的是「**容器**多大、结论是什么」。源尺寸照样打在行里，只是那一行是
     * **做判断那一刻**的快照。容器尺寸变化（挂上、钉住 / 取消钉住、翻页、转屏）
     * 才是真正该留痕的事件，那些一通电话里只有个位数次。
     *
     * 与 iOS `IMAspectVideoView.logFit` 打的是同一行、同名字段，两端可以直接对着看。
     * **`video` 那一格是已经按旋转角换算过的显示尺寸**——iOS 的回调给的就是显示尺寸，
     * 这边给的是未旋转缓冲区 + 旋转角，不换算的话两端对不上。
     */
    private fun logFit(key: String, frame: IntArray?, hostWidth: Int, hostHeight: Int, fill: Boolean) {
        val mode = if (fill) "FILL" else "FIT"
        val marker = "$mode|${hostWidth}x$hostHeight"
        if (loggedFit[key] == marker) return
        loggedFit[key] = marker
        val rotated = frame != null && frame[2] % 180 != 0
        val videoWidth = frame?.let { if (rotated) it[1] else it[0] } ?: 0
        val videoHeight = frame?.let { if (rotated) it[0] else it[1] } ?: 0
        val fraction = IMVideoFit.visibleFraction(
            frame?.get(0) ?: 0, frame?.get(1) ?: 0, frame?.get(2) ?: 0, hostWidth, hostHeight,
        )
        IMRTCLog.i(
            "media",
            "画面缩放判据 owner=$key view=${hostWidth}x$hostHeight video=${videoWidth}x$videoHeight " +
                "fraction=${"%.3f".format(fraction)} mode=$mode",
        )
    }
}
