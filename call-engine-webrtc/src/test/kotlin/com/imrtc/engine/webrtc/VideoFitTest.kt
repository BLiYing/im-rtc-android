package com.imrtc.engine.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「裁切填满还是留黑边」的判据（[IMVideoFit]）。
 *
 * 三条主用例就是 `im-rtc-server/docs/mechanism/VIDEO_RENDERING.md` 里那张表，
 * **数字一一对应**——那张表是规范，这里是它的可执行版本。
 */
class VideoFitTest {

    /** 竖屏源 720×1280：摄像头给的是未旋转的 1280×720 + 旋转 90°。 */
    private val portraitW = 1280
    private val portraitH = 720
    private val portraitRot = 90

    @Test
    fun `九宫格正方形格子 + 竖屏源：正好落在阈值上，该填满`() {
        val fraction = IMVideoFit.visibleFraction(portraitW, portraitH, portraitRot, 350, 350)
        assertEquals(0.5625f, fraction, 0.001f)
        assertTrue("正好等于阈值要算填满，否则九宫格白留两条宽黑边", fraction >= IMVideoFit.MIN_VISIBLE_FRACTION)
        assertTrue(IMVideoFit.shouldFill(portraitW, portraitH, portraitRot, 350, 350))
    }

    @Test
    fun `竖屏全屏 + 竖屏源：方向一致，该填满`() {
        val fraction = IMVideoFit.visibleFraction(portraitW, portraitH, portraitRot, 1080, 2200)
        assertEquals(0.873f, fraction, 0.01f)
        assertTrue(IMVideoFit.shouldFill(portraitW, portraitH, portraitRot, 1080, 2200))
    }

    @Test
    fun `竖屏全屏 + 横屏源：只剩四分之一可见，必须留黑边`() {
        // 这一条是整条规则的理由：填满会是 3 倍放大 + 砍掉 72% 的宽。
        val fraction = IMVideoFit.visibleFraction(1280, 720, 0, 1080, 2200)
        assertEquals(0.276f, fraction, 0.01f)
        assertFalse(IMVideoFit.shouldFill(1280, 720, 0, 1080, 2200))
    }

    @Test
    fun `旋转必须算进去，否则竖屏源会被当成横屏、判据正好反`() {
        // 同一帧缓冲（1280×720），旋转 90° 是竖屏、0° 是横屏，在竖屏全屏里结论相反。
        assertTrue("旋转 90 = 竖屏源", IMVideoFit.shouldFill(1280, 720, 90, 1080, 2200))
        assertFalse("旋转 0 = 横屏源", IMVideoFit.shouldFill(1280, 720, 0, 1080, 2200))
        // 270° 与 90° 等价；180° 与 0° 等价。
        assertTrue(IMVideoFit.shouldFill(1280, 720, 270, 1080, 2200))
        assertFalse(IMVideoFit.shouldFill(1280, 720, 180, 1080, 2200))
    }

    @Test
    fun `尺寸还没量出来时先填满，别先露一圈黑边`() {
        assertEquals(0f, IMVideoFit.visibleFraction(1280, 720, 0, 0, 0), 0f)
        assertTrue("父容器还没 layout，这一拍该先填满", IMVideoFit.shouldFill(1280, 720, 0, 0, 0))
        assertTrue("帧尺寸还没来也一样", IMVideoFit.shouldFill(0, 0, 0, 350, 350))
    }

    @Test
    fun `格子差一个像素、源不是精确 9比16：贴着阈值的照样填满`() {
        // iOS 2026-09-11 真机：格子取整后宽高差 1px，不加容差就翻成 FIT、左右两条黑边。
        val offByOne = IMVideoFit.visibleFraction(portraitW, portraitH, portraitRot, 527, 526)
        assertTrue("不加容差这一格就是 FIT", offByOne < IMVideoFit.MIN_VISIBLE_FRACTION)
        assertTrue(IMVideoFit.shouldFill(portraitW, portraitH, portraitRot, 527, 526))
        // 源缩放后 358×640（0.559）。
        assertTrue(IMVideoFit.shouldFill(640, 358, 90, 350, 350))
    }

    @Test
    fun `容差不吞掉真该留黑边的`() {
        assertEquals(0.01f, IMVideoFit.FILL_TOLERANCE, 0.0001f)
        // 0.54：明显低于阈值，照样留黑边。
        assertFalse(IMVideoFit.shouldFill(1000, 540, 90, 350, 350))
    }

    @Test
    fun `方向完全一致时整帧都可见`() {
        assertEquals(1f, IMVideoFit.visibleFraction(1280, 720, 0, 640, 360), 0.001f)
        assertTrue(IMVideoFit.shouldFill(1280, 720, 0, 640, 360))
    }
}
