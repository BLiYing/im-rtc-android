package com.imrtc.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两只定时器的记账。**纯 JVM**——定时器是注入的，不需要 Looper。
 *
 * 守的都是「不报错但用户会骂」的那类：红键按下去没反应、占位格永远收不掉。
 */
class TimersTest {

    /** 假调度器：不真的等，把待跑的任务攒着，由测试决定什么时候「到点」。 */
    private class FakeClock {
        val queued = ArrayList<Pair<Long, Runnable>>()

        fun schedule(delayMs: Long, task: Runnable) {
            queued += delayMs to task
        }

        fun cancel(task: Runnable) {
            queued.removeAll { it.second === task }
        }

        /** 让所有排着的任务到点。 */
        fun fire() {
            val due = ArrayList(queued)
            queued.clear()
            due.forEach { it.second.run() }
        }
    }

    @Test
    fun `红键看门狗：没人应就到点收场，收到终态就撤`() {
        /*
         2026-09-09 真机：摄像头设成「每次询问」后发起视频呼叫，进了呼叫界面挂不掉。
         `call.invite` 一帧没发，红键映射到 cancel，引擎的通话状态机还在 Idle——
         本地拒成 2005、一帧不发、也没有任何结束事件回来，界面永远停在「正在呼叫…」。
        */
        val clock = FakeClock()
        val dog = IMRedButtonWatchdog(clock::schedule, clock::cancel)
        var gaveUp = 0

        // ① 按下红键，没人应 → 到点本地收场。
        dog.arm { gaveUp += 1 }
        assertTrue(dog.armed)
        assertEquals(IMRedButtonWatchdog.DEFAULT_TIMEOUT_MS, clock.queued.single().first)
        clock.fire()
        assertEquals(1, gaveUp)
        assertFalse("跑完就该自己松手", dog.armed)

        // ② 按下红键，终态回来了 → 撤掉，不该再收场。
        dog.arm { gaveUp += 1 }
        dog.disarm()
        clock.fire()
        assertEquals("收到终态之后不许再本地收场", 1, gaveUp)
        assertTrue("撤了之后队列要干净", clock.queued.isEmpty())

        // ③ 连按两下红键只排一次——不然会收场两回。
        dog.arm { gaveUp += 1 }
        dog.arm { gaveUp += 1 }
        assertEquals(1, clock.queued.size)
        clock.fire()
        assertEquals(2, gaveUp)

        // ④ 没武装过就撤，必须无害。
        dog.disarm()
        dog.disarm()
    }

    @Test
    fun `占位格终局：一个 uid 一只表，已经在排队的不重排`() {
        /*
         `room.active_speakers` 一秒来好几帧，而调用方每次渲染都会调 scheduleAll。
         每来一帧就重排的话，那一格的倒计时被永远推回 2 秒，**再也收不掉**。
        */
        val clock = FakeClock()
        val timers = IMSettleTimers(clock::schedule, clock::cancel)
        val removed = ArrayList<String>()

        timers.scheduleAll(listOf("dave", "erin")) { removed += it }
        assertEquals(2, timers.pending)

        // 又渲染了三轮，同样两个人——不该多排出任何一只表。
        repeat(3) { timers.scheduleAll(listOf("dave", "erin")) { removed += it } }
        assertEquals("重复渲染不许重排", 2, timers.pending)
        assertEquals(2, clock.queued.size)

        clock.fire()
        assertEquals(listOf("dave", "erin"), removed)
        assertEquals("跑完自己出表", 0, timers.pending)

        // 通话结束：还没到点的全撤掉，不许在下一通电话里跳出来。
        timers.scheduleAll(listOf("frank")) { removed += it }
        timers.clear()
        clock.fire()
        assertEquals(listOf("dave", "erin"), removed)
        assertEquals(0, timers.pending)
    }
}
