package com.imrtc.engine

import com.imrtc.engine.signaling.IMScheduler
import com.imrtc.engine.statemachine.UNSUBSCRIBE_HYSTERESIS_MS

/**
 * 翻页退订那五秒迟滞的**定时器那一半**。
 *
 * ## 为什么定时器不在状态机里
 *
 * 状态机是纯函数，所以它只记下「这几条等着退订」（`IMRoomContext.pendingUnsubscribe`），
 * 到点该做什么由它自己在收到内部事件时决定。这里负责把那份清单**变成真的定时器**。
 *
 * ## 为什么是「对账」而不是「排一次」
 *
 * 排队的来路不止一条：翻页报 `none` 会加一条，翻回来会撤一条，人走了 / 对方关摄像头
 * 会让它凭空消失，订满 16 路时还会被提前强制退掉。让每一条来路各自记得排 / 撤定时器，
 * 总有一条会漏——漏掉撤销的表现是**翻回来看着的人五秒后突然没了画面**。
 * 所以这里每次状态推进后按清单**整体对账**：清单里有而没定时器的排上，
 * 有定时器而清单里没有的撤掉。来路再多也不用改这里。
 *
 * **只在 engine 线程上用**（[IMFrameLoop.applyOutput] 调），所以自己不加锁。
 */
internal class IMUnsubscribeTimers(
    private val scheduler: IMScheduler,
    private val onElapsed: (String) -> Unit,
) {
    private val timers = mutableMapOf<String, IMScheduler.Cancellable>()

    /** 让定时器与待退订清单一致。每次状态推进后调一次。 */
    fun sync(pending: List<String>) {
        val wanted = pending.toSet()
        val iterator = timers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in wanted) continue
            entry.value.cancel()
            iterator.remove()
        }
        for (trackId in wanted) {
            if (trackId in timers) continue
            timers[trackId] = scheduler.postDelayed(UNSUBSCRIBE_HYSTERESIS_MS) {
                // 先摘掉再回调：回调会推状态机，而那一轮的 sync 又会走到这里。
                timers.remove(trackId)
                onElapsed(trackId)
            }
        }
    }

    /** 撤掉全部定时器（离房、logout）。 */
    fun clear() {
        for ((_, timer) in timers) timer.cancel()
        timers.clear()
    }

    /** 此刻挂着几只，供测试断言「撤销真的做了」。 */
    val armed: Int get() = timers.size
}
