package com.imrtc.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已在通话中又发起另一场」的守门判据（[IMBusyGuard]）。**纯 JVM**。
 *
 * 真机事故（2026-09-19 17:03，1v1 收成小窗后去发起群通话）：判据缺失时界面被换成另一通、没有任何提示。
 * 这里钉的是**每个 phase 的放行结论**——新增 phase 时枚举遍历会逼着回答「它算不算已在通话」。
 */
class BusyGuardTest {

    @Test
    fun `只有空闲或停在结束画面才允许开始新的一场`() {
        val free = setOf(IMCallViewState.Phase.IDLE, IMCallViewState.Phase.ENDED)
        for (phase in IMCallViewState.Phase.values()) {
            assertEquals("$phase", phase in free, IMBusyGuard.allows(phase))
        }
    }

    @Test
    fun `响铃拨出接通中通话中都算已在通话`() {
        for (phase in listOf(
            IMCallViewState.Phase.INCOMING,
            IMCallViewState.Phase.OUTGOING,
            IMCallViewState.Phase.CONNECTING,
            IMCallViewState.Phase.CONNECTED,
        )) {
            assertFalse("$phase", IMBusyGuard.allows(phase))
        }
    }

    @Test
    fun `加入通话与发起通话用同一条判据`() {
        for (phase in IMCallViewState.Phase.values()) {
            assertEquals("$phase", IMBusyGuard.allows(phase), IMJoinCallState.allowedFrom(phase))
        }
        assertTrue(IMBusyGuard.MESSAGE.isNotBlank())
    }
}
