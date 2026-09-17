package com.imrtc.engine.signaling

import com.imrtc.engine.protocol.IMCloseCode
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

    /**
     * [IMReconnectPolicy.willReconnect] 判据要跟 [IMCloseCode.shouldReconnect] 这张表同步——
     * 这几条钉住「不许两份表再分叉」（曾经 4400 掉进默认分支变成一直重连）。
     */
    @Test
    fun `willReconnect：4400 协议错误不重连`() {
        assertFalse(willReconnect(code = IMCloseCode.BAD_PROTOCOL.code, authFailuresAfterIncrement = 1))
    }

    @Test
    fun `willReconnect：4403 被踢不重连`() {
        assertFalse(willReconnect(code = IMCloseCode.KICKED.code, authFailuresAfterIncrement = 1))
    }

    @Test
    fun `willReconnect：4401 前两次重连，第三次放弃`() {
        assertTrue(willReconnect(code = IMCloseCode.UNAUTHORIZED.code, authFailuresAfterIncrement = 1))
        assertTrue(willReconnect(code = IMCloseCode.UNAUTHORIZED.code, authFailuresAfterIncrement = 2))
        assertFalse(willReconnect(code = IMCloseCode.UNAUTHORIZED.code, authFailuresAfterIncrement = 3))
    }

    @Test
    fun `willReconnect：1000 与 1006 都重连`() {
        assertTrue(willReconnect(code = IMCloseCode.NORMAL.code, authFailuresAfterIncrement = 1))
        assertTrue("1006 不在表里，未知码按「要重连」处理", willReconnect(code = 1006, authFailuresAfterIncrement = 1))
    }

    @Test
    fun `willReconnect：已经 stopped 或握手判过要放弃，一律不重连`() {
        assertFalse(IMReconnectPolicy.willReconnect(stopped = true, hasPendingGiveUp = false, code = 1006, authFailuresAfterIncrement = 1, maxAuthFailures = 3))
        assertFalse(IMReconnectPolicy.willReconnect(stopped = false, hasPendingGiveUp = true, code = 1006, authFailuresAfterIncrement = 1, maxAuthFailures = 3))
    }

    private fun willReconnect(code: Int, authFailuresAfterIncrement: Int, maxAuthFailures: Int = 3): Boolean =
        IMReconnectPolicy.willReconnect(stopped = false, hasPendingGiveUp = false, code = code, authFailuresAfterIncrement = authFailuresAfterIncrement, maxAuthFailures = maxAuthFailures)
}
