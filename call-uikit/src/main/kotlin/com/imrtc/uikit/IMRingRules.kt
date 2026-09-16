package com.imrtc.uikit

/**
 * 这一刻该响哪种声音。**纯值**，播放层（[IMRingPlayer]）只认它，不自己猜阶段。
 *
 * `NONE` 是「什么都不响」，不是「静音状态」——静音本身是 [IMCallKitConfig.ringtoneMuted]，
 * 判据里已经吃进去了，播放层不需要再关心。
 */
internal enum class IMRingtoneKind { NONE, INCOMING, RINGBACK }

/**
 * 该不该响、响哪种——**纯函数判据**，不碰 `MediaPlayer` / `AudioManager`。
 *
 * 与 [IMBannerRules] 同样的理由：本仓禁用 Robolectric（CONVENTIONS §10），
 * `android.*` 一进单测就抛 `Method not mocked`，所以决策必须先在纯值层做完，
 * 播放层只负责「照这个值起停」，真机验收。
 *
 * 规则很直白，但**顺序**要按这个来——`muted` 与会议房都是「无条件不响」，
 * 优先于阶段判断：
 * 1. 宿主整体静音 → 不响。
 * 2. 会议房（[IMCallViewState.isMeeting]）→ 不响：会议直接进 CONNECTING，
 *    没有振铃语义，`onCallMissed` 这类通话中的提示也一样——它们从不落在
 *    INCOMING / OUTGOING 阶段，按阶段判天然就不会响，不需要按事件特判。
 * 3. 来电（[IMCallViewState.Phase.INCOMING]）→ 来电铃声。
 * 4. 拨出（[IMCallViewState.Phase.OUTGOING]）→ 回铃音。
 * 5. 其余阶段（CONNECTING / CONNECTED / ENDED / IDLE）→ 不响。
 */
internal object IMRingRules {
    fun ringtoneFor(state: IMCallViewState, muted: Boolean): IMRingtoneKind = when {
        muted -> IMRingtoneKind.NONE
        state.isMeeting -> IMRingtoneKind.NONE
        state.phase == IMCallViewState.Phase.INCOMING -> IMRingtoneKind.INCOMING
        state.phase == IMCallViewState.Phase.OUTGOING -> IMRingtoneKind.RINGBACK
        else -> IMRingtoneKind.NONE
    }
}
