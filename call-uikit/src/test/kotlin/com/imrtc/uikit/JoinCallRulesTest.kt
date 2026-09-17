package com.imrtc.uikit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IMCallKit.joinCall` 的守门规则（2026-09-15 代码审查）。**纯 JVM**，与 iOS `imJoinCallAllowed`、
 * Web `useCallActions.joinCall` 同一条判据。
 *
 * 被拒之后的文案与收场从 `joinCall` 的结果回调里取（2.0.0，[IMKitResults.joinCall]），
 * 不再有「正在加入」标记去 `onError` 里猜归属——那一段真机验。
 */
class JoinCallRulesTest {

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
}
