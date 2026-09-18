package com.imrtc.uikit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [IMDefaultNetworkTracker]：只有「换成另一个网络」才通知 Engine，注册时那一下不算。 */
class DefaultNetworkTrackerTest {

    private val tracker = IMDefaultNetworkTracker<String>()

    @Test
    fun `注册时回调的当前网络不算变化`() {
        assertFalse(tracker.onAvailable("wifi-1"))
    }

    @Test
    fun `同一个网络重复回调不算变化`() {
        tracker.onAvailable("wifi-1")
        assertFalse(tracker.onAvailable("wifi-1"))
    }

    @Test
    fun `换成另一个网络算变化`() {
        tracker.onAvailable("wifi-1")
        assertTrue("Wi-Fi 重连换了 handle / 切到蜂窝", tracker.onAvailable("wifi-2"))
    }

    @Test
    fun `丢了再回来算变化，哪怕是同一个`() {
        tracker.onAvailable("wifi-1")
        tracker.onLost("wifi-1")
        assertTrue(tracker.onAvailable("wifi-1"))
    }

    @Test
    fun `丢的不是当前网络不影响判定`() {
        tracker.onAvailable("wifi-1")
        tracker.onAvailable("cell-1")
        tracker.onLost("wifi-1")
        assertFalse(tracker.onAvailable("cell-1"))
    }

    @Test
    fun `reset 之后的第一个又当注册时那一下`() {
        tracker.onAvailable("wifi-1")
        tracker.reset()
        assertFalse(tracker.onAvailable("wifi-2"))
    }
}
