package com.imrtc.engine.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预览「开 / 关」只认最后一次：排在主线程上的旧开启不许在关掉之后把摄像头打开。 */
class IMPreviewIntentTest {

    @Test
    fun `开了没关，号一直有效`() {
        val intent = IMPreviewIntent()
        val token = intent.begin()
        assertTrue(intent.isCurrent(token))
    }

    @Test
    fun `开启还在排队时关掉：那次开启作废`() {
        val intent = IMPreviewIntent()
        val token = intent.begin()
        intent.cancel()
        assertFalse(intent.isCurrent(token))
    }

    @Test
    fun `开、关、再开：只有最后那次开启算数`() {
        val intent = IMPreviewIntent()
        val first = intent.begin()
        intent.cancel()
        val second = intent.begin()
        assertFalse(intent.isCurrent(first))
        assertTrue(intent.isCurrent(second))
    }

    @Test
    fun `连开两次：前一次让位给后一次`() {
        val intent = IMPreviewIntent()
        val first = intent.begin()
        val second = intent.begin()
        assertFalse(intent.isCurrent(first))
        assertTrue(intent.isCurrent(second))
    }
}
