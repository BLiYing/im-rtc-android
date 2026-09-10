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

    @Test
    fun `通话时长秒表：重开先停上一只`() {
        // 不停的话重连一次就多一只表，界面上的秒数开始跳着走。
        val clock = FakeClock()
        val ticker = IMDurationTicker(clock::schedule, clock::cancel)
        var ticks = 0

        ticker.start { ticks += 1 }
        ticker.start { ticks += 1 }
        assertEquals("重开只该剩一只表", 1, clock.queued.size)

        clock.fire()
        assertEquals(1, ticks)
        assertEquals("走一拍要排下一拍", 1, clock.queued.size)

        ticker.stop()
        clock.fire()
        assertEquals("停了就不该再走", 1, ticks)
        assertFalse(ticker.running)

        ticker.stop() // 重复调用无害
    }

    @Test
    fun `提示过期：只清掉自己那一条`() {
        /*
         `statusText` 里 hint 优先于时长，不撤的话「通话已满员」会顶着标题栏直到通话结束。
         但**中途来了新提示时，旧提示的计时器不该把新的抹掉**——那会让新提示只闪一下，
         用户根本没看清写的是什么。
        */
        val clock = FakeClock()
        val expiry = IMHintExpiry(clock::schedule, clock::cancel)
        val expired = ArrayList<String>()

        expiry.arm("通话已满员") { expired += it }
        assertTrue(expiry.armed)

        // 中途换了一条：上一条的表要作废，只剩新的那一只。
        expiry.arm("对方已拒接") { expired += it }
        assertEquals(1, clock.queued.size)
        clock.fire()
        assertEquals(listOf("对方已拒接"), expired)
        assertFalse(expiry.armed)

        // 空串 = 只撤上一条，不排新的。
        expiry.arm("还在排队") { expired += it }
        expiry.arm("") { expired += it }
        clock.fire()
        assertEquals("空串不排新表，也不该让旧表跑掉", listOf("对方已拒接"), expired)
        assertFalse(expiry.armed)

        expiry.clear() // 重复调用无害
    }

    @Test
    fun `异步回来之后还算不算数`() {
        /*
         权限门可能停在系统框上好几秒，回调回来时的世界和发起时不是同一个。
         不看一眼就往下走：拨出侧屏幕早收了 invite 却照发，**对方响起铃来而主叫这边一个界面都没有**；
         被叫侧则是去接一通已经不存在的电话（服务端回 1401）。
        */
        val placing = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false)
        assertTrue(IMLateGuard.stillPlacing(placing))
        assertFalse(IMLateGuard.stillIncoming(placing))
        assertTrue(IMLateGuard.stillInCall(placing))

        val incoming = IMCallViewReducer.incoming(IMCallViewState(), "c-1", "alice", emptyList(), "audio", false)
        assertTrue(IMLateGuard.stillIncoming(incoming))
        assertFalse(IMLateGuard.stillPlacing(incoming))

        // 红键按过、界面已经收场：三条都不该再放行。
        val ended = IMCallViewReducer.ended(placing, "cancel")
        assertFalse("结束画面上不该再发 invite", IMLateGuard.stillPlacing(ended))
        assertFalse("结束画面自己会收，别再收一次把 endReason 抹掉", IMLateGuard.stillInCall(ended))

        val idle = IMCallViewReducer.reset()
        assertFalse(IMLateGuard.stillPlacing(idle))
        assertFalse(IMLateGuard.stillIncoming(idle))
        assertFalse(IMLateGuard.stillInCall(idle))
    }

    @Test
    fun `本地收场的原因照实际发出去的动作写，不冤枉网络`() {
        // 复现出来那一次网络是好的——是权限门没落定、帧压根没发。
        // 写 "network" 的话屏幕上是「网络中断」，用户会去检查 WiFi。
        assertEquals("cancel", IMCallViewState.watchdogReason(IMCallViewState.Action.CANCEL))
        assertEquals("reject", IMCallViewState.watchdogReason(IMCallViewState.Action.REJECT))
        assertEquals("hangup", IMCallViewState.watchdogReason(IMCallViewState.Action.HANGUP))
        assertEquals("hangup", IMCallViewState.watchdogReason(IMCallViewState.Action.LEAVE_ROOM))

        // 拨出中按红键 = 「已取消」，不是「网络中断」。
        val text = IMCallViewState.endReasonText("cancel", role = "caller", durationSec = 0L)
        assertEquals("已取消", text)
    }
}
