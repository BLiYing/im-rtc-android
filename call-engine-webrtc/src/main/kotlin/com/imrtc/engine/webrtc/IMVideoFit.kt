package com.imrtc.engine.webrtc

/**
 * 「这一格该裁切填满，还是等比留黑边」——纯几何判据。
 *
 * 规则与真机依据见 `im-rtc-server/docs/mechanism/VIDEO_RENDERING.md`（五仓统一）：
 *
 * ```
 * 裁切填充后画面还剩 ≥ 56.25% 可见  →  FILL（裁一点，换来没有黑边）
 * 剩 < 56.25%                      →  FIT （宁可留黑边，也不要放大 + 砍掉大半）
 * ```
 *
 * # 为什么是几何判据，不是「看版式」
 *
 * `call-uikit` 按分层约定**不许 import org.webrtc**，传不了「此刻是九宫格还是全屏」
 * 这种 UI 概念。而只要「格子宽高」与「源宽高」两个数就够算——以后加画中画、
 * 共享屏幕、横屏，都不用回来改这里。
 *
 * # 摘成纯函数单测的理由
 *
 * 与 `IMNegotiationGate` / `IMIceGiveUp` 同一条：判据本身最容易写错
 * （宽高比取反、旋转没算、阈值比较方向），而它一旦错了症状是「画面糊」或
 * 「莫名黑边」，**没有任何报错**。渲染器要真设备才起得来，判据不能跟着一起没法测。
 */
internal object IMVideoFit {

    /**
     * 可见比例的下限，**9/16 = 0.5625**。
     *
     * 不是凑的：它正好让**竖屏源（9:16）填满正方形格子**落在边界上——
     * 那一格裁掉 44% 的高、而且是缩小，不会糊，所以该填满；
     * 再严一点就会让九宫格白留两条宽黑边（用户第一次看到效果就指出了这点）。
     *
     * 与 libwebrtc `RendererCommon.BALANCED_VISIBLE_FRACTION` 同值——
     * 同一个取舍被独立做过一次，算个旁证。
     */
    const val MIN_VISIBLE_FRACTION = 0.5625f

    /**
     * 判据的容差：**贴着阈值的那一格不能因为一两个像素翻成 FIT**。
     *
     * 9:16 源在正方形格子里正好压在阈值上，任何微小偏差都会把它推到另一边：
     * 格子宽高差 1px（iOS 2026-09-11 真机：UIStackView 把小数边长取整，左右露出两条黑边），
     * 或发送端缩放后源不再是精确的 9:16（对齐到偶数 / 16 的倍数）。
     * 本端的格子边长是整数像素、一直没踩到，但判据五端必须同一个数，所以一起加。
     * 0.01 盖得住这两种，又远够不到真该留黑边的组合（竖屏全屏 + 横屏源是 0.276）。
     */
    const val FILL_TOLERANCE = 0.01f

    /**
     * 裁切填满之后，源画面还剩多少比例可见。返回 `0f` 表示算不出（尺寸还没量出来）。
     *
     * `frameWidth` / `frameHeight` 是**未旋转**的缓冲区尺寸——libwebrtc 的
     * `RendererEvents.onFrameResolutionChanged` 给的就是这个，旋转角单独一个参数
     * （`SurfaceEglRenderer` 传的是 `frame.buffer.width/height` 加 `frame.rotation`）。
     * **90° / 270° 必须自己换过来**，不换的话竖屏源会被当成横屏，判据正好反。
     */
    fun visibleFraction(
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Float {
        val rotated = rotationDegrees % 180 != 0
        val sourceWidth = if (rotated) frameHeight else frameWidth
        val sourceHeight = if (rotated) frameWidth else frameHeight
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 0f
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val viewAspect = viewWidth.toFloat() / viewHeight
        // 裁切时按较"紧"的那一边缩放，另一边溢出被裁掉；剩下的比例就是两个宽高比之商。
        return minOf(sourceAspect, viewAspect) / maxOf(sourceAspect, viewAspect)
    }

    /**
     * 该不该裁切填满。**尺寸还没量出来时返回 `true`**——先填满总比先露一圈黑边好看，
     * 等 layout 稳定后会再算一次（见 `IMWebRTCAdapter` 里的 layout 监听）。
     */
    fun shouldFill(
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Boolean {
        val fraction = visibleFraction(frameWidth, frameHeight, rotationDegrees, viewWidth, viewHeight)
        return fraction == 0f || fraction >= MIN_VISIBLE_FRACTION - FILL_TOLERANCE
    }
}
