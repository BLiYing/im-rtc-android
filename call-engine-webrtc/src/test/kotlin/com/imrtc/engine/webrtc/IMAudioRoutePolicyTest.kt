package com.imrtc.engine.webrtc

import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_HDMI
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扬声器键关着时跟随系统：接着耳机就走耳机，不再钉死听筒（CLIENT_PARITY `[^audioroute]`）。 */
class IMAudioRoutePolicyTest {

    private val phoneOnly = listOf(TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER)

    @Test
    fun `什么都没接：听筒`() {
        assertEquals(TYPE_BUILTIN_EARPIECE, IMAudioRoutePolicy.pick(speakerForced = false, available = phoneOnly))
    }

    @Test
    fun `插着有线耳机：走耳机，不是听筒`() {
        assertEquals(TYPE_WIRED_HEADSET, IMAudioRoutePolicy.pick(false, phoneOnly + TYPE_WIRED_HEADSET))
    }

    @Test
    fun `连着蓝牙：走蓝牙；蓝牙和有线都在：有线优先`() {
        assertEquals(TYPE_BLUETOOTH_SCO, IMAudioRoutePolicy.pick(false, phoneOnly + TYPE_BLUETOOTH_SCO))
        assertEquals(TYPE_WIRED_HEADSET, IMAudioRoutePolicy.pick(false, phoneOnly + TYPE_BLUETOOTH_SCO + TYPE_WIRED_HEADSET))
    }

    @Test
    fun `强制外放：接着耳机也走扬声器；没有扬声器就不硬选`() {
        assertEquals(TYPE_BUILTIN_SPEAKER, IMAudioRoutePolicy.pick(true, phoneOnly + TYPE_WIRED_HEADSET))
        assertNull(IMAudioRoutePolicy.pick(true, listOf(TYPE_BUILTIN_EARPIECE)))
    }

    @Test
    fun `只有外接通话设备的插拔才重新选路`() {
        assertTrue(IMAudioRoutePolicy.touchesExternal(listOf(TYPE_BLUETOOTH_SCO)))
        assertFalse(IMAudioRoutePolicy.touchesExternal(listOf(TYPE_HDMI, TYPE_BUILTIN_SPEAKER)))
    }
}
