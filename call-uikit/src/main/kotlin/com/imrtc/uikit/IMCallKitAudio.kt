package com.imrtc.uikit

import com.imrtc.engine.IMAudioRoute
import com.imrtc.engine.log.IMRTCLog

/*
 [IMCallKit] 的音频路由那一角（2026-09-22 四选一落地时从主文件挪出来，只为体量）。
 扬声器键的两种形态在 [IMCallView.renderSpeakerButton]，面板在 [IMAudioRoutePanel]。
 */

/** 二态开关形态下点扬声器键：翻布尔、真的去切路由（关 = 跟随系统，见 Engine `setSpeakerOn`）。 */
internal fun IMCallKit.toggleSpeaker() {
    val next = !state.speakerOn
    engine?.setSpeakerOn(next)
    update(IMCallViewReducer.toggleSpeaker(state))
}

/** 面板里选了一条路由。不改视图状态：在用项从 Engine 的 `onAudioRoutesChanged` 回来，按钮跟着它变。 */
internal fun IMCallKit.selectAudioRoute(route: IMAudioRoute) {
    IMRTCLog.i("kit", "选择音频路由 ${route.kind} ${route.name}")
    engine?.setAudioRoute(route)
}
