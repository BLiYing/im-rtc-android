package com.imrtc.engine.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * 远端画面诊断里的两段纯逻辑：断流 / 恢复检测（[IMFrameGapTracker]）与统计字段拼行（[IMStatsText]）。
 * 挂 sink、post 主线程、取 getStats 那部分走真机。
 */
class RemoteVideoDiagnosticsTest {

    @Test
    fun `第一帧算一次恢复，断流时长记 -1`() {
        val tracker = IMFrameGapTracker(gapMs = 1_000, windowMs = 3_000)
        val event = tracker.onFrame(0, 720, 1280)
        assertNotNull(event)
        assertTrue(event!!.resumed)
        assertEquals(-1L, event.gapBeforeMs)
        assertNull(event.closed)
    }

    @Test
    fun `正常帧率下窗口没满就不出事件`() {
        val tracker = IMFrameGapTracker(gapMs = 1_000, windowMs = 3_000)
        tracker.onFrame(0, 720, 1280)
        for (t in 33L..2_970L step 33L) {
            assertNull("t=$t 不该有事件", tracker.onFrame(t, 720, 1280))
        }
    }

    @Test
    fun `窗口满了由下一帧收尾，记帧数、最长帧间隔、尺寸变化`() {
        val tracker = IMFrameGapTracker(gapMs = 1_000, windowMs = 3_000)
        tracker.onFrame(0, 360, 640)
        tracker.onFrame(100, 360, 640)
        tracker.onFrame(700, 720, 1280) // 隔了 600ms，没到断流阈值，但尺寸变了
        tracker.onFrame(800, 720, 1280)
        val event = tracker.onFrame(3_000, 720, 1280) // 离窗口起点满 3 秒；这一帧离上一帧 2200ms，算断流
        assertNotNull(event)
        val window = event!!.closed
        assertNotNull(window)
        assertEquals(4, window!!.frames)
        assertEquals(600L, window.maxFrameGapMs)
        assertEquals(1, window.sizeChanges)
        assertEquals(360, window.firstWidth)
        assertEquals(1280, window.lastHeight)
        assertEquals(800L, window.spanMs)
        assertEquals(-1L, window.gapBeforeMs)
        assertTrue("2200ms 没有帧，这一帧同时是新的一段恢复", event.resumed)
        assertEquals(2_200L, event.gapBeforeMs)
    }

    @Test
    fun `只是窗口到期、没有断流时，收尾但不算恢复`() {
        val tracker = IMFrameGapTracker(gapMs = 1_000, windowMs = 3_000)
        tracker.onFrame(0, 720, 1280)
        for (t in 100L..2_900L step 100L) assertNull(tracker.onFrame(t, 720, 1280))
        val event = tracker.onFrame(3_000, 720, 1280)
        assertNotNull(event)
        assertFalse(event!!.resumed)
        val window = event.closed!!
        assertEquals(30, window.frames)
        assertEquals(100L, window.maxFrameGapMs)
        // 窗口收掉之后正常出帧，不再有事件。
        assertNull(tracker.onFrame(3_100, 720, 1280))
    }

    @Test
    fun `摄像头关了几秒再开：报一次恢复，断流时长就是关着的那段`() {
        val tracker = IMFrameGapTracker(gapMs = 1_000, windowMs = 3_000)
        tracker.onFrame(0, 720, 1280)
        for (t in 33L..3_300L step 33L) tracker.onFrame(t, 720, 1280)
        val event = tracker.onFrame(8_267, 720, 1280)
        assertNotNull(event)
        assertTrue(event!!.resumed)
        assertEquals(4_967L, event.gapBeforeMs)
    }

    @Test
    fun `统计拼行：缺席不写、整数原样、浮点去尾零`() {
        val members = mapOf<String, Any?>(
            "framesDecoded" to 1234L,
            "bytesReceived" to BigInteger.valueOf(98_765),
            "totalFreezesDuration" to 0.1 + 0.2,
            "framesPerSecond" to 30.0,
            "decoderImplementation" to "MediaCodecVideoDecoder",
        )
        val line = IMStatsText.line(
            members,
            listOf("framesDecoded", "keyFramesDecoded", "bytesReceived", "totalFreezesDuration", "framesPerSecond", "decoderImplementation"),
        )
        assertEquals(
            "framesDecoded=1234 bytesReceived=98765 totalFreezesDuration=0.3 framesPerSecond=30 decoderImplementation=MediaCodecVideoDecoder",
            line,
        )
    }
}
