package com.imrtc.engine.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 归属表是整表替换：上一份有、这一份没有的 track_id 要摘掉（见 [retireRemoteTracks]）。
 */
class RetiredTrackIdsTest {

    @Test
    fun `同一个人换了 track_id，旧的那条要下线`() {
        val before = mapOf("t1" to "carol", "t9" to "bob")
        val after = mapOf("t2" to "carol", "t9" to "bob")
        assertEquals(setOf("t1"), retiredTrackIds(before, after))
    }

    @Test
    fun `归属没变就什么都不摘`() {
        val owners = mapOf("t1" to "carol")
        assertEquals(emptySet<String>(), retiredTrackIds(owners, owners))
    }

    @Test
    fun `新来的轨道不算下线`() {
        assertEquals(emptySet<String>(), retiredTrackIds(emptyMap(), mapOf("t1" to "carol")))
    }

    @Test
    fun `人全走了就全部下线`() {
        assertEquals(setOf("t1", "t2"), retiredTrackIds(mapOf("t1" to "a", "t2" to "b"), emptyMap()))
    }
}
