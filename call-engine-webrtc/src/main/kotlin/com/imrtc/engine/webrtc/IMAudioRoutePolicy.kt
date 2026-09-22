package com.imrtc.engine.webrtc

import android.media.AudioDeviceInfo
import com.imrtc.engine.IMAudioRoute
import com.imrtc.engine.IMAudioRouteKind

/**
 * 通话声音该从哪出：**纯规则，不碰 AudioManager**，好在 JVM 单测里钉住。
 *
 * # 两层意图（交互稿差异 7，2026-09-22 落地四选一）
 *
 * - **手选**（[Choice.manual]，用户在面板里点的那条 / 老的扬声器键）：盖过默认值。
 * - **插拔自动切**（[Choice.autoExternal]，刚插上的耳机 / 刚连上的蓝牙）：**盖过手选**——
 *   用户插耳机的意图没有歧义，不该再问他一次；拔掉时回到他手选的那条，那条也没了才跟随系统。
 * - 两者都没有 = **跟随系统**：接着耳机 / 蓝牙就走它们，都没有才走听筒。
 *   原先关着扬声器时显式把通信设备钉在听筒上——`setCommunicationDevice()` 是强制覆盖，
 *   恰好打掉了系统本来会切到耳机的默认行为：插着耳机，音乐能从耳机听到、通话却从听筒出来
 *   （CLIENT_PARITY `[^audioroute]`，2026-09-09 真机）。
 *
 * 外接设备之间的先后：有线 / USB 排在蓝牙前面——两样都接着时，插线是更晚、更有意的动作。
 * **绝不落到静音**：挑不出来时调用方交还系统默认，不硬选。
 */
internal object IMAudioRoutePolicy {

    /** 跟随系统时的先后，越靠前越优先。听筒垫底，扬声器不在表里（只有手选才去）。 */
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

    /** 一台候选设备：从 `AudioDeviceInfo` 抠出来的三个字段，好在 JVM 里造。`id` 是系统给的设备号。 */
    data class Device(val type: Int, val name: String, val id: Int)

    /**
     * 两层意图，都记 [IMAudioRoute.uid]（设备号，不是类型——两只蓝牙同时连着时类型分不出是哪只）。
     * `null` = 没有这一层。
     */
    data class Choice(val manual: String? = null, val autoExternal: String? = null)

    /** 平台的几十种设备类型折成通话界面关心的四类；不是这四类（HDMI、USB 声卡……）的返回 null，不进清单。 */
    fun kindOf(type: Int): IMAudioRouteKind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> IMAudioRouteKind.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> IMAudioRouteKind.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET ->
            IMAudioRouteKind.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID ->
            IMAudioRouteKind.BLUETOOTH
        else -> null
    }

    /** 内置两条用与 iOS 相同的固定 uid，外接设备用系统设备号。 */
    fun uidOf(device: Device): String = when (kindOf(device.type)) {
        IMAudioRouteKind.EARPIECE -> IMAudioRoute.EARPIECE_UID
        IMAudioRouteKind.SPEAKER -> IMAudioRoute.SPEAKER_UID
        else -> device.id.toString()
    }

    /**
     * 可选清单：**听筒、扬声器恒在前两位**（内置两条名字留空，Kit 按语言填），外接的按 [FOLLOW_SYSTEM_ORDER] 排在后面。
     * 同一台设备可能以两种类型出现（BLE + SCO），按 uid 去重。
     */
    fun routes(devices: List<Device>): List<Device> {
        val byKind = devices.filter { kindOf(it.type) != null }
        val builtIn = listOf(IMAudioRouteKind.EARPIECE, IMAudioRouteKind.SPEAKER)
            .mapNotNull { kind -> byKind.firstOrNull { kindOf(it.type) == kind } }
        val external = byKind.filter { it.type in EXTERNAL && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            .sortedBy { FOLLOW_SYSTEM_ORDER.indexOf(it.type) }
        return (builtIn + external).distinctBy { uidOf(it) }
    }

    fun toRoute(device: Device): IMAudioRoute? {
        val kind = kindOf(device.type) ?: return null
        val name = if (kind == IMAudioRouteKind.EARPIECE || kind == IMAudioRouteKind.SPEAKER) "" else device.name
        return IMAudioRoute(kind, name, uidOf(device))
    }

    /**
     * 此刻该用哪台：插拔自动切的那条 > 手选的那条 > 跟随系统。
     * 前两层的设备已经不在了就当没有这一层（拔掉的耳机不能再选）。一台都挑不出返回 null（调用方交还系统默认）。
     */
    fun effective(choice: Choice, devices: List<Device>): Device? {
        val candidates = routes(devices)
        fun byUid(uid: String?) = uid?.let { u -> candidates.firstOrNull { uidOf(it) == u } }
        byUid(choice.autoExternal)?.let { return it }
        byUid(choice.manual)?.let { return it }
        return FOLLOW_SYSTEM_ORDER.firstNotNullOfOrNull { type -> candidates.firstOrNull { it.type == type } }
    }

    /** 用户手选：盖掉此前的自动切（他此刻的意图更新）。 */
    fun manual(choice: Choice, uid: String?) = Choice(manual = uid, autoExternal = null)

    /** 插上 / 连上外接设备：自动切过去（多台同时出现按 [FOLLOW_SYSTEM_ORDER] 挑）；没有外接的这批变化不动意图。 */
    fun externalAdded(choice: Choice, added: List<Device>): Choice {
        val target = added.filter { it.type in EXTERNAL }.minByOrNull { FOLLOW_SYSTEM_ORDER.indexOf(it.type) }
            ?: return choice
        return choice.copy(autoExternal = uidOf(target))
    }

    /** 拔掉 / 断开：正指着它的那一层作废——回到手选的那条，再没有就跟随系统（**绝不落到静音**）。 */
    fun removed(choice: Choice, removedUids: Collection<String>): Choice = Choice(
        manual = choice.manual?.takeUnless { it in removedUids },
        autoExternal = choice.autoExternal?.takeUnless { it in removedUids },
    )

    /** 这批插拔里有没有外接的通话设备。手机自己的扬声器、听筒、HDMI 之类的变化不值得重新选路。 */
    fun touchesExternal(types: Collection<Int>): Boolean = types.any { it in EXTERNAL }
}
