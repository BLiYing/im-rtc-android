package com.imrtc.engine.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 摄像头异步失败要有回音，且下一次打开要重起而不是照用死 source（静默失败审计 android #1）。 */
class IMCameraEventsTest {

    @Test
    fun `打不开：回报一次，下一次打开认得出要重起`() {
        val lost = mutableListOf<String>()
        val events = IMCameraEvents { lost += it }
        events.onCameraError("Camera in use")
        assertEquals(listOf("error: Camera in use"), lost)
        assertTrue(events.consumeFailure())
        assertFalse("失败只认一次，重起之后不再重起", events.consumeFailure())
    }

    @Test
    fun `中途被抢走也算失败`() {
        val lost = mutableListOf<String>()
        val events = IMCameraEvents { lost += it }
        events.onCameraDisconnected()
        assertEquals(listOf("disconnected"), lost)
        assertTrue(events.consumeFailure())
    }

    @Test
    fun `卡帧不算失败：看门狗在恢复前会反复报`() {
        val lost = mutableListOf<String>()
        val events = IMCameraEvents { lost += it }
        events.onCameraFreezed("Camera failure. Client must return video buffers.")
        assertTrue(lost.isEmpty())
        assertFalse(events.consumeFailure())
    }

    @Test
    fun `停采集之后迟到的事件一律不算`() {
        val lost = mutableListOf<String>()
        val events = IMCameraEvents { lost += it }
        events.retire()
        events.onCameraError("late")
        events.onCameraDisconnected()
        assertTrue(lost.isEmpty())
        assertFalse(events.consumeFailure())
    }
}
