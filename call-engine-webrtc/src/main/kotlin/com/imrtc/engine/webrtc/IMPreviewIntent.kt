package com.imrtc.engine.webrtc

import java.util.concurrent.atomic.AtomicLong

/**
 * 本端预览「开 / 关」的最新意图：**只认最后一次**（设计 v3.7 第 6 步）。
 *
 * 为什么要它：`startLocalPreview` 真正起采集是在主线程上（渲染器只能在主线程 init），
 * `stopLocalPreview` 却跑在 Engine 线程上。来电页上刚起预览就点「关」——停止先跑完
 * （那时还没东西可停），排在主线程上的那次开启随后才到，摄像头就在按钮关着时亮起来了。
 *
 * 开启领一个号、动手前对一下；关闭作废此前领过的所有号。与 Web 的 `previewIntent` 同一个思路。
 */
internal class IMPreviewIntent {
    private val latest = AtomicLong()

    /** 开：领号。真正起采集之前拿它问 [isCurrent]。 */
    fun begin(): Long = latest.incrementAndGet()

    /** 关：此前领过的号全部作废。 */
    fun cancel() {
        latest.incrementAndGet()
    }

    fun isCurrent(token: Long): Boolean = latest.get() == token
}
