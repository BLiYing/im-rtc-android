package com.imrtc.engine.signaling

import kotlin.random.Random

/**
 * 重连退避档：`1s, 2s, 4s, 8s, 15s, 30s`，之后固定 30s，每档 ±20% 抖动（协议 §1.4）。
 * **四端同一份。**
 *
 * 抖动不是装饰：服务端重启时几百个客户端会在同一秒醒来，没有抖动就是一次自制的 DDoS。
 */
internal class IMBackoff(private val random: Random = Random.Default) {

    private var attempt = 0

    /** 已经尝试过几次。日志里带上它，「一次断线排了两次重连」这种 bug 一眼可见。 */
    val attempts: Int get() = attempt

    fun reset() {
        attempt = 0
    }

    /** 下一档等待时长（毫秒），并把档位推进一格。 */
    fun nextDelayMs(): Long {
        val base = STEPS_MS[minOf(attempt, STEPS_MS.lastIndex)]
        attempt++
        val jitter = (base * JITTER_RATIO).toLong()
        // ±20%
        return base + random.nextLong(-jitter, jitter + 1)
    }

    private companion object {
        val STEPS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
        const val JITTER_RATIO = 0.2
    }
}
