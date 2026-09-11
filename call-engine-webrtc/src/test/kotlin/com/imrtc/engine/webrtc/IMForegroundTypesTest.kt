package com.imrtc.engine.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台服务类型按实际授权给（[IMForegroundTypes]）。
 *
 * 2026-09-11 真机崩溃循环：麦克风在系统设置里被收回，来电页点开摄像头 →
 * `ensureCapture` 起前台服务 → 恒带 `microphone` 类型 → Android 14+ 抛 SecurityException。
 */
class IMForegroundTypesTest {

    @Test
    fun `麦克风被收回、摄像头有：只带 camera，不能再带 microphone`() {
        val types = IMForegroundTypes.of(withCamera = true, micGranted = false, cameraGranted = true)
        assertFalse("没有 RECORD_AUDIO 还带 microphone 类型就是那次崩溃", types.microphone)
        assertTrue(types.camera)
        assertFalse(types.isEmpty)
    }

    @Test
    fun `两样权限都在：视频带两种，语音只带 microphone`() {
        assertEquals(IMForegroundTypes(microphone = true, camera = true),
            IMForegroundTypes.of(withCamera = true, micGranted = true, cameraGranted = true))
        assertEquals(IMForegroundTypes(microphone = true, camera = false),
            IMForegroundTypes.of(withCamera = false, micGranted = true, cameraGranted = true))
    }

    @Test
    fun `摄像头没授权时不带 camera，哪怕调用方说要`() {
        val types = IMForegroundTypes.of(withCamera = true, micGranted = true, cameraGranted = false)
        assertEquals(IMForegroundTypes(microphone = true, camera = false), types)
    }

    @Test
    fun `一样都给不出：isEmpty，start 据此不去调 startForegroundService`() {
        assertTrue(IMForegroundTypes.of(withCamera = true, micGranted = false, cameraGranted = false).isEmpty)
        assertTrue(IMForegroundTypes.of(withCamera = false, micGranted = false, cameraGranted = true).isEmpty)
    }
}
