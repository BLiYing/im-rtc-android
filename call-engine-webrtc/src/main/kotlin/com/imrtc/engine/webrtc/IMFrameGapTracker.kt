package com.imrtc.engine.webrtc

/**
 * 一路远端视频的**断流 → 恢复**检测。纯逻辑：不认 `org.webrtc`、不自己读时钟，单测直接跑在 JVM 上。
 *
 * # 它要抓的现象
 *
 * iOS 通话中关摄像头再打开，Android 这边「画面已经出来了，又刷新一下」（2026-09-11 真机）。
 * 那一下是解码尺寸跳了档、还是恢复后又卡了一下、还是只是画质跳变，光看界面分不出来。
 * 这里记下**恢复之后头 [windowMs] 毫秒**里的帧数、最长帧间隔、尺寸变了几次。
 * 谁来打日志、打多少见 [IMRemoteVideoDiagnostics]。
 *
 * # 热路径纪律（CONVENTIONS §5）
 *
 * [onFrame] 每一帧都走：平时只做几次整数比较和赋值，**不分配、不加锁**。
 * 只有「恢复」和「窗口收尾」这两个事件才造一个小对象，一段断流各发生一次。
 * **单线程**：同一条轨道的帧都在同一条解码线程上回调，字段不需要同步。
 */
internal class IMFrameGapTracker(
    private val gapMs: Long = DEFAULT_GAP_MS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    /** 恢复之后那一段窗口的样子。 */
    data class Window(
        /** 恢复前断了多久；`-1` 表示这是这条轨道的第一帧。 */
        val gapBeforeMs: Long,
        val frames: Int,
        /** 窗口里相邻两帧最长隔了多久。远大于正常帧间隔，说明恢复之后又卡了一下。 */
        val maxFrameGapMs: Long,
        val sizeChanges: Int,
        val firstWidth: Int,
        val firstHeight: Int,
        val lastWidth: Int,
        val lastHeight: Int,
        /** 窗口第一帧到最后一帧隔了多久。 */
        val spanMs: Long,
    )

    /** 一帧带来的事件。两件事可能同时发生：窗口没满又断了一次——上一个窗口收尾、新的一段恢复。 */
    class Event(val closed: Window?, val resumed: Boolean, val gapBeforeMs: Long)

    private var lastFrameMs = -1L
    private var windowStartMs = -1L
    private var windowGapBeforeMs = 0L
    private var frames = 0
    private var maxFrameGapMs = 0L
    private var sizeChanges = 0
    private var firstWidth = 0
    private var firstHeight = 0
    private var lastWidth = 0
    private var lastHeight = 0

    /** 喂一帧。`nowMs` 是单调时钟（真机上是 `SystemClock.elapsedRealtime()`）。没有事件时返回 null。 */
    fun onFrame(nowMs: Long, width: Int, height: Int): Event? {
        val previous = lastFrameMs
        lastFrameMs = nowMs
        val gap = if (previous < 0) -1L else nowMs - previous
        val resumed = previous < 0 || gap >= gapMs
        var closed: Window? = null
        if (windowStartMs >= 0) {
            if (!resumed && nowMs - windowStartMs < windowMs) {
                frames++
                if (gap > maxFrameGapMs) maxFrameGapMs = gap
                if (width != lastWidth || height != lastHeight) {
                    sizeChanges++
                    lastWidth = width
                    lastHeight = height
                }
                return null
            }
            closed = Window(
                windowGapBeforeMs, frames, maxFrameGapMs, sizeChanges,
                firstWidth, firstHeight, lastWidth, lastHeight, previous - windowStartMs,
            )
            windowStartMs = -1L
        }
        if (resumed) {
            windowStartMs = nowMs
            windowGapBeforeMs = gap
            frames = 1
            maxFrameGapMs = 0L
            sizeChanges = 0
            firstWidth = width
            firstHeight = height
            lastWidth = width
            lastHeight = height
        }
        return if (closed == null && !resumed) null else Event(closed, resumed, gap)
    }

    internal companion object {
        /** 隔这么久才来下一帧就算「断过」。1 秒：正常帧率下不会误报，开关一次摄像头是好几秒。 */
        const val DEFAULT_GAP_MS = 1_000L

        /** 恢复之后看多久。刷新感都在头一两秒，3 秒够把「刷新之后稳住」也收进来。 */
        const val DEFAULT_WINDOW_MS = 3_000L
    }
}
