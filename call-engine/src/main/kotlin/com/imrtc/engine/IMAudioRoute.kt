package com.imrtc.engine

/**
 * 音频路由的四种类型（设计文档 §7.5 `setAudioRoute`，2026-09-22 落地，与 iOS `IMAudioRouteKind` 同名同序）。
 *
 * **不是 Android 的 `AudioDeviceInfo.type`**：那张表有几十种（HDMI、USB 声卡、助听器……），
 * 通话界面只关心「从哪出声」这四类；平台类型到这四类的折算在媒体层（`IMAudioRoutePolicy.kindOf`）。
 */
enum class IMAudioRouteKind { EARPIECE, SPEAKER, WIRED_HEADSET, BLUETOOTH }

/**
 * 一条可选的音频路由（**设备清单式**，用户 2026-09-22 拍板）：
 * 界面要显示真实设备名（「AirPods Pro」），光一个枚举不够。
 *
 * - [name]：外接设备给系统报的名字；**内置两条为空串**，由 Kit 按语言填「听筒」「扬声器」
 *   （Engine 没有界面、不做本地化）。
 * - [uid]：内置两条恒为 [EARPIECE_UID] / [SPEAKER_UID]（与 iOS 同一对字符串）；外接设备用系统的设备 id。
 *   两只蓝牙同时连着时靠它分辨，别按 [kind] 认。
 *
 * 清单从 [IMCallEngine.availableAudioRoutes] 拿，变化从 [IMCallEngineListener.onAudioRoutesChanged] 来；
 * 选中一条调 [IMCallEngine.setAudioRoute]。
 */
data class IMAudioRoute(
    val kind: IMAudioRouteKind,
    val name: String,
    val uid: String,
) {
    companion object {
        @JvmField val EARPIECE_UID = "builtin.earpiece"
        @JvmField val SPEAKER_UID = "builtin.speaker"
    }
}
