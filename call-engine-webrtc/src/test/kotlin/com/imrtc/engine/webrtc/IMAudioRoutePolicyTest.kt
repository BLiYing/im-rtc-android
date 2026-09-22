package com.imrtc.engine.webrtc

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_HDMI
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import com.imrtc.engine.IMAudioRoute
import com.imrtc.engine.IMAudioRouteKind
import com.imrtc.engine.webrtc.IMAudioRoutePolicy.Choice
import com.imrtc.engine.webrtc.IMAudioRoutePolicy.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通话声音从哪出：跟随系统（CLIENT_PARITY `[^audioroute]`）+ 四选一的两层意图（交互稿差异 7，2026-09-22）。
 * 与 iOS `AudioRouteListTests` 是同一组约定：内置两条恒在前、固定 uid、外接设备真名。
 */
class IMAudioRoutePolicyTest {

    private val earpiece = Device(TYPE_BUILTIN_EARPIECE, "", 1)
    private val speaker = Device(TYPE_BUILTIN_SPEAKER, "", 2)
    private val wired = Device(TYPE_WIRED_HEADSET, "USB-C 耳机", 7)
    private val airpods = Device(TYPE_BLUETOOTH_SCO, "AirPods Pro", 9)
    private val phoneOnly = listOf(earpiece, speaker)

    private fun pick(choice: Choice, devices: List<Device>) = IMAudioRoutePolicy.effective(choice, devices)?.type

    @Test
    fun `什么都没接：听筒`() {
        assertEquals(TYPE_BUILTIN_EARPIECE, pick(Choice(), phoneOnly))
    }

    @Test
    fun `插着有线耳机：走耳机，不是听筒`() {
        assertEquals(TYPE_WIRED_HEADSET, pick(Choice(), phoneOnly + wired))
    }

    @Test
    fun `连着蓝牙：走蓝牙；蓝牙和有线都在：有线优先`() {
        assertEquals(TYPE_BLUETOOTH_SCO, pick(Choice(), phoneOnly + airpods))
        assertEquals(TYPE_WIRED_HEADSET, pick(Choice(), phoneOnly + airpods + wired))
    }

    @Test
    fun `手选扬声器：接着耳机也走扬声器；扬声器没了就跟随系统，不硬选`() {
        val forced = IMAudioRoutePolicy.manual(Choice(), IMAudioRoute.SPEAKER_UID)
        assertEquals(TYPE_BUILTIN_SPEAKER, pick(forced, phoneOnly + wired))
        assertEquals(TYPE_BUILTIN_EARPIECE, pick(forced, listOf(earpiece)))
        assertNull(pick(forced, emptyList()))
    }

    @Test
    fun `插拔盖过手选：手选扬声器后插耳机走耳机，拔掉回到扬声器`() {
        var choice = IMAudioRoutePolicy.manual(Choice(), IMAudioRoute.SPEAKER_UID)
        choice = IMAudioRoutePolicy.externalAdded(choice, listOf(wired))
        assertEquals(TYPE_WIRED_HEADSET, pick(choice, phoneOnly + wired))
        choice = IMAudioRoutePolicy.removed(choice, listOf(IMAudioRoutePolicy.uidOf(wired)))
        assertEquals(TYPE_BUILTIN_SPEAKER, pick(choice, phoneOnly))
    }

    @Test
    fun `手选蓝牙后蓝牙断开：回到跟随系统（听筒），绝不落到静音`() {
        var choice = IMAudioRoutePolicy.manual(Choice(), IMAudioRoutePolicy.uidOf(airpods))
        assertEquals(TYPE_BLUETOOTH_SCO, pick(choice, phoneOnly + airpods))
        choice = IMAudioRoutePolicy.removed(choice, listOf(IMAudioRoutePolicy.uidOf(airpods)))
        assertEquals(Choice(), choice)
        assertEquals(TYPE_BUILTIN_EARPIECE, pick(choice, phoneOnly))
    }

    @Test
    fun `手选盖掉此前的自动切；内置设备的插拔不动意图`() {
        var choice = IMAudioRoutePolicy.externalAdded(Choice(), listOf(airpods))
        choice = IMAudioRoutePolicy.manual(choice, IMAudioRoute.EARPIECE_UID)
        assertEquals(Choice(manual = IMAudioRoute.EARPIECE_UID), choice)
        assertEquals(choice, IMAudioRoutePolicy.externalAdded(choice, listOf(Device(TYPE_HDMI, "TV", 30))))
    }

    @Test
    fun `清单：听筒、扬声器恒在前两位且用固定 uid，外接设备带真名，HDMI 不进清单，BLE 与 SCO 同一只（id 不同）按同类同名去重`() {
        val routes = IMAudioRoutePolicy.routes(listOf(Device(TYPE_HDMI, "TV", 30), airpods, speaker, wired, earpiece, Device(TYPE_BLE_HEADSET, "AirPods Pro", 10)))
            .mapNotNull(IMAudioRoutePolicy::toRoute)
        assertEquals(
            listOf(
                IMAudioRoute(IMAudioRouteKind.EARPIECE, "", IMAudioRoute.EARPIECE_UID),
                IMAudioRoute(IMAudioRouteKind.SPEAKER, "", IMAudioRoute.SPEAKER_UID),
                IMAudioRoute(IMAudioRouteKind.WIRED_HEADSET, "USB-C 耳机", "7"),
                // 同一只耳机的 BLE（id 10）与 SCO（id 9）两个端口并成一行；留下的是 FOLLOW_SYSTEM_ORDER 里靠前的 BLE 那个。
                IMAudioRoute(IMAudioRouteKind.BLUETOOTH, "AirPods Pro", "10"),
            ),
            routes,
        )
    }

    @Test
    fun `只有插拔外接设备才值得重新选路`() {
        assertTrue(IMAudioRoutePolicy.touchesExternal(listOf(TYPE_WIRED_HEADSET)))
        assertTrue(IMAudioRoutePolicy.touchesExternal(listOf(TYPE_HDMI, TYPE_BLUETOOTH_SCO)))
        assertFalse(IMAudioRoutePolicy.touchesExternal(listOf(TYPE_HDMI, TYPE_BUILTIN_SPEAKER)))
    }
}
