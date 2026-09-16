package com.imrtc.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

/*
纯值判据（CONVENTIONS §10：本仓禁用 Robolectric，`MediaPlayer` / `AudioManager` 一进单测就
`Method not mocked`）。播放层 [IMRingPlayer] 走真机验收，这里只钉死「该不该响、响哪种」。

判据顺序（见 [IMRingRules.ringtoneFor] 类注释）：muted 与会议房都是无条件不响，优先于阶段；
INCOMING → 来电铃声，OUTGOING → 回铃音，其余阶段不响。
*/
class IMRingRulesTest {

    private fun state(
        phase: IMCallViewState.Phase,
        isMeeting: Boolean = false,
    ) = IMCallViewState(phase = phase, isMeeting = isMeeting)

    @Test
    fun `来电阶段响来电铃声`() {
        assertEquals(
            IMRingtoneKind.INCOMING,
            IMRingRules.ringtoneFor(state(IMCallViewState.Phase.INCOMING), muted = false),
        )
    }

    @Test
    fun `拨出阶段响回铃音`() {
        assertEquals(
            IMRingtoneKind.RINGBACK,
            IMRingRules.ringtoneFor(state(IMCallViewState.Phase.OUTGOING), muted = false),
        )
    }

    /** 会议房没有振铃语义——直接进 CONNECTING，这里按阶段判天然就不会响，不需要看 isMeeting 单独特判。 */
    @Test
    fun `会议房不响`() {
        assertEquals(
            IMRingtoneKind.NONE,
            IMRingRules.ringtoneFor(state(IMCallViewState.Phase.CONNECTING, isMeeting = true), muted = false),
        )
    }

    /** isMeeting 优先于阶段：哪怕（理论上）落在 INCOMING/OUTGOING，会议房也不响。 */
    @Test
    fun `isMeeting 时即便阶段是来电也不响`() {
        assertEquals(
            IMRingtoneKind.NONE,
            IMRingRules.ringtoneFor(state(IMCallViewState.Phase.INCOMING, isMeeting = true), muted = false),
        )
    }

    @Test
    fun `静音时来电也不响`() {
        assertEquals(
            IMRingtoneKind.NONE,
            IMRingRules.ringtoneFor(state(IMCallViewState.Phase.INCOMING), muted = true),
        )
    }

    @Test
    fun `静音时拨出也不响`() {
        assertEquals(
            IMRingtoneKind.NONE,
            IMRingRules.ringtoneFor(state(IMCallViewState.Phase.OUTGOING), muted = true),
        )
    }

    @Test
    fun `其余阶段一律不响`() {
        for (phase in listOf(
            IMCallViewState.Phase.IDLE,
            IMCallViewState.Phase.CONNECTING,
            IMCallViewState.Phase.CONNECTED,
            IMCallViewState.Phase.ENDED,
        )) {
            assertEquals(
                "phase=$phase 不该响",
                IMRingtoneKind.NONE,
                IMRingRules.ringtoneFor(state(phase), muted = false),
            )
        }
    }
}
