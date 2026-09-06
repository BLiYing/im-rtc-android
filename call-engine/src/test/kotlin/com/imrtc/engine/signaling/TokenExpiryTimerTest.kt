package com.imrtc.engine.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接入票到期提醒（[IMTokenExpiryTimer]）。
 *
 * 用假调度器把 12 小时的行为在几毫秒内验完——这类东西用真定时器测就只能 sleep，
 * 没人会去跑第二次。
 */
class TokenExpiryTimerTest {

    private fun harness(leadMs: Long = IMTokenExpiryTimer.DEFAULT_LEAD_MS): Triple<
        FakeScheduler, MutableList<Long>, IMTokenExpiryTimer,
        > {
        val scheduler = FakeScheduler()
        val fired = mutableListOf<Long>()
        val timer = IMTokenExpiryTimer(scheduler, leadMs) { fired += it }
        return Triple(scheduler, fired, timer)
    }

    @Test
    fun `到期前 60 秒触发，带上到期时刻`() {
        val (scheduler, fired, timer) = harness()
        val expiresAt = scheduler.nowMs() + 3_600_000L
        timer.arm(expiresAt)

        scheduler.advance(3_600_000L - 60_000L - 1)
        assertTrue("还没到提前量窗口就催了", fired.isEmpty())

        scheduler.advance(2)
        assertEquals(listOf(expiresAt), fired)
    }

    @Test
    fun `同一张票只触发一次，触发后自己卸掉`() {
        val (scheduler, fired, timer) = harness()
        timer.arm(scheduler.nowMs() + 120_000L)

        scheduler.advance(600_000L)
        assertEquals(1, fired.size)
        assertFalse(timer.isArmed)
    }

    /*
      服务端说「未知」时不能报错、也不能瞎猜一个时刻——猜错会在票其实还早的时候催换票。
      正确行为是解除武装，退化成被动行为。
    */
    @Test
    fun `到期时刻为 0 或负数当未知处理，不排定也不报错`() {
        val (scheduler, fired, timer) = harness()
        timer.arm(0L)
        assertFalse(timer.isArmed)
        timer.arm(-1L)
        assertFalse(timer.isArmed)

        scheduler.advance(24 * 3_600_000L)
        assertTrue(fired.isEmpty())
    }

    /*
      票只剩 10 秒时**更**需要提醒宿主，不是更不需要。静默跳过会让
      「登录时票就快过期了」这种场景完全失去提前量。
    */
    @Test
    fun `已经进入提前量窗口时立刻触发`() {
        val (scheduler, fired, timer) = harness()
        timer.arm(scheduler.nowMs() + 10_000L)

        scheduler.advance(1)
        assertEquals("只剩 10 秒的票没有立刻提醒", 1, fired.size)
    }

    @Test
    fun `票已经过期也触发一次`() {
        val (scheduler, fired, timer) = harness()
        timer.arm(scheduler.nowMs() - 60_000L)

        scheduler.advance(1)
        assertEquals(1, fired.size)
    }

    @Test
    fun `重新 arm 会取消上一个，不会两张票各响一次`() {
        val (scheduler, fired, timer) = harness()
        val first = scheduler.nowMs() + 3_600_000L
        val second = scheduler.nowMs() + 7_200_000L
        timer.arm(first)
        timer.arm(second)

        scheduler.advance(8 * 3_600_000L)
        assertEquals("旧定时器没被取消", listOf(second), fired)
    }

    @Test
    fun `disarm 之后不再触发，且可重复调用`() {
        val (scheduler, fired, timer) = harness()
        timer.arm(scheduler.nowMs() + 120_000L)
        timer.disarm()
        timer.disarm()

        scheduler.advance(600_000L)
        assertTrue(fired.isEmpty())
        assertFalse(timer.isArmed)
    }

    @Test
    fun `提前量可配`() {
        val (scheduler, fired, timer) = harness(leadMs = 5_000L)
        timer.arm(scheduler.nowMs() + 60_000L)

        scheduler.advance(54_999L)
        assertTrue(fired.isEmpty())
        scheduler.advance(2)
        assertEquals(1, fired.size)
    }

    /*
      arm 在握手成功的路径上被调用。同步回调会让宿主在 onTokenWillExpire 里调的
      updateToken 重入到还没走完的连接流程里——所以哪怕延迟是 0 也必须经过调度器。
    */
    @Test
    fun `延迟为 0 时也不同步回调`() {
        val (scheduler, fired, timer) = harness()
        timer.arm(scheduler.nowMs()) // 正好到期，延迟钳成 0

        assertTrue("同步就烧掉了，没经过调度器", fired.isEmpty())
        scheduler.advance(1)
        assertEquals(1, fired.size)
    }
}
