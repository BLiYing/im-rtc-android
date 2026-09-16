package com.imrtc.engine.webrtc

import android.media.AudioDeviceInfo

/**
 * 通话声音该从哪出：**纯规则，不碰 AudioManager**，好在 JVM 单测里钉住。
 *
 * 扬声器键只表示「强制外放」。**关着的时候跟随系统**：接着耳机 / 蓝牙就走它们，都没有才走听筒。
 * 原先关着时显式把通信设备钉在听筒上——`setCommunicationDevice()` 是强制覆盖，
 * 恰好打掉了系统本来会切到耳机的默认行为：插着耳机，音乐能从耳机听到、通话却从听筒出来
 * （CLIENT_PARITY `[^audioroute]`，2026-09-09 真机）。
 *
 * 外接设备之间的先后：有线 / USB 排在蓝牙前面——两样都接着时，插线是更晚、更有意的动作。
 */
internal object IMAudioRoutePolicy {

    /** 跟随系统时的先后，越靠前越优先。听筒垫底，扬声器不在表里（只有强制外放才去）。 */
    val FOLLOW_SYSTEM_ORDER = listOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    )

    /** 外接的通话设备（耳机、蓝牙）——插拔这些才需要重新选路。 */
    val EXTERNAL = FOLLOW_SYSTEM_ORDER - AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

    /**
     * 从此刻可用的通信设备类型里挑一个。强制外放挑扬声器；否则按 [FOLLOW_SYSTEM_ORDER]。
     * 一个都挑不出来返回 null（调用方交还系统默认，不硬选）。
     */
    fun pick(speakerForced: Boolean, available: Collection<Int>): Int? {
        if (speakerForced) return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER.takeIf { it in available }
        return FOLLOW_SYSTEM_ORDER.firstOrNull { it in available }
    }

    /** 这批插拔里有没有外接的通话设备。手机自己的扬声器、听筒、HDMI 之类的变化不值得重新选路。 */
    fun touchesExternal(types: Collection<Int>): Boolean = types.any { it in EXTERNAL }
}
