package com.imrtc.uikit

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IMCallKit.joinCall` 的守门规则（2026-09-15 代码审查）。**纯 JVM**，与 iOS `imJoinCallAllowed`、
 * Web `useCallActions.joinCall` 同一条判据。
 */
class JoinCallRulesTest {

    @After
    fun tearDown() {
        IMJoinCallState.joining = false
    }

    @Test
    fun `只有空闲或停在结束画面时才能主动加入`() {
        assertTrue(IMJoinCallState.allowedFrom(IMCallViewState.Phase.IDLE))
        assertTrue(IMJoinCallState.allowedFrom(IMCallViewState.Phase.ENDED))
        for (phase in listOf(
            IMCallViewState.Phase.INCOMING,
            IMCallViewState.Phase.OUTGOING,
            IMCallViewState.Phase.CONNECTING,
            IMCallViewState.Phase.CONNECTED,
        )) {
            assertFalse("$phase 时已经在一场里，不该放行加入", IMJoinCallState.allowedFrom(phase))
        }
    }

    @Test
    fun `不在加入中时本地错误码不归加入处理`() {
        IMJoinCallState.joining = false
        assertFalse(IMJoinCallState.onLocalRejection(2005))
        assertFalse(IMJoinCallState.onLocalRejection(2007))
    }

    @Test
    fun `加入中遇到服务端拒绝码不走本地收场，等随后的 onCallEnd`() {
        IMJoinCallState.joining = true
        assertFalse(IMJoinCallState.onLocalRejection(1409))
        assertFalse(IMJoinCallState.onLocalRejection(1202))
        assertTrue("服务端拒绝时标记要留到 onCallEnd 再清", IMJoinCallState.joining)
    }
}
