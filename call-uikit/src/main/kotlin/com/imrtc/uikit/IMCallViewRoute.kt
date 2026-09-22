package com.imrtc.uikit

/*
 扬声器键的两种形态（设计稿 §04 v3.5，交互稿差异 7；与 iOS `renderSpeakerButton` 同形）：
 只有内置两条 = 二态开关（亮 = 外放，点一下就切）；出现第三条（有线 / 蓝牙）= 路由选择——
 图标换成在用那条的字形、文案换成设备名、右下角叠一枚 8×8 的 chevron-up，点它弹 [IMAudioRoutePanel]。
 判据是 [IMCallViewState.showsRoutePicker]。从 [IMCallView] 挪出来只为体量。
 */

internal fun IMCallView.renderSpeakerButton(state: IMCallViewState) {
    if (!state.showsRoutePicker) {
        speakerButton.showsChevron = false
        speakerButton.overrideIcon = null
        speakerButton.caption = IMText.t("ctl.speaker")
        speakerButton.isOn = state.speakerOn
        return
    }
    // 路由选择形态：亮不亮已经没有意义（四选一不是开关），恒暗，靠图标与文案表达。
    val current = state.currentAudioRoute
    speakerButton.isOn = false
    speakerButton.showsChevron = true
    speakerButton.overrideIcon = current?.let { routeIcon(it.kind) } ?: IMKitIcon.SPEAKER
    speakerButton.caption = current?.let(::routeDisplayName) ?: IMText.t("ctl.speaker")
}

/** 点扬声器键：有第三条路由时弹面板、点一行才切；否则就是老的二态开关。 */
internal fun IMCallView.onSpeakerTapped() {
    if (state.showsRoutePicker) {
        IMAudioRoutePanel.show(context, state) { actions?.onPickAudioRoute(it) }
    } else {
        actions?.onToggleSpeaker()
    }
}
