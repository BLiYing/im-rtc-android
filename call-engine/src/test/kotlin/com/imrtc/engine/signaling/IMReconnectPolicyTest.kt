package com.imrtc.engine.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IMReconnectPolicy] 是纯函数：不碰 IO、不碰时钟、不碰 [IMSignalConnection] 的任何状态，
 * 直接构造 `wasConnected` / `aliveMs` / `foreground` 三个入参就能把四条规则验完，
 * 不需要假传输假调度器（那些留给 `SignalConnectionTest` 验「接对了没有」）。
 */
class IMReconnectPolicyTest {

    private fun plan(wasConnected: Boolean, aliveMs: Long?, foreground: Boolean): Pair<IMReconnectPolicy.Plan, Boolean> {
        var reset = false
        val result = IMReconnectPolicy.plan(wasConnected, aliveMs, foreground) { reset = true }
        return result to reset
    }

    @Test
    fun `规则①前台：不管活了多久都归零，不封顶`() {
        val (short, resetShort) = plan(wasConnected = true, aliveMs = 100, foreground = true)
        assertTrue(resetShort)
        assertNull(short.capMs)

        val (long, resetLong) = plan(wasConnected = true, aliveMs = 60_000, foreground = true)
        assertTrue(resetLong)
        assertNull(long.capMs)
    }

    @Test
    fun `规则②后台+活不到10秒：不归零，封顶3秒`() {
        val (plan, reset) = plan(wasConnected = true, aliveMs = 2_000, foreground = false)
        assertFalse("不该归零", reset)
        assertEquals(IMReconnectPolicy.BACKGROUND_SHORT_LIVED_CAP_MS, plan.capMs)
    }

    @Test
    fun `规则②边界：恰好10秒不算「不到10秒」，走规则③归零`() {
        val (plan, reset) = plan(wasConnected = true, aliveMs = IMReconnectPolicy.BACKGROUND_ALIVE_RESET_MS, foreground = false)
        assertTrue("恰好10秒该按规则③处理——归零", reset)
        assertNull(plan.capMs)
    }

    @Test
    fun `规则③后台+活过10秒：跟前台一样归零，不封顶`() {
        val (plan, reset) = plan(wasConnected = true, aliveMs = 10_001, foreground = false)
        assertTrue(reset)
        assertNull(plan.capMs)
    }

    @Test
    fun `规则④从未连上成功：不管前后台都不归零也不封顶`() {
        val (fg, resetFg) = plan(wasConnected = false, aliveMs = null, foreground = true)
        assertFalse("从未连上不该因为前台就归零——一直没连上谈不上归零", resetFg)
        assertNull(fg.capMs)

        val (bg, resetBg) = plan(wasConnected = false, aliveMs = null, foreground = false)
        assertFalse("从未连上不许被误判成后台短命连接", resetBg)
        assertNull("从未连上不许被封顶", bg.capMs)
    }
}
