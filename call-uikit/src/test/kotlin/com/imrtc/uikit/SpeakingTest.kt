package com.imrtc.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「谁在说话」——2026-09-09 改版的回归。
 *
 * 原先这一层只存**一个** `speakingUid`（音量最大的那个）：三个人同时说话只亮一个格子，
 * 而那人若恰好是本端，远端一个都不亮。真机上「说话高亮没实现」就是这么来的——
 * 功能是有的，只是永远只轮得到一个人。
 *
 * 改版之后说话状态记在每个 [IMCallViewState.Member] 身上，格子各看各的；
 * `speakingUid` 保留，但**只给悬浮球挑缩略画面**（它一次只放得下一路）。
 */
class SpeakingTest {

    private fun roomOf(vararg uids: String): IMCallViewState =
        IMCallViewState(members = uids.associateWith { IMCallViewState.Member(it) })

    private fun speak(
        state: IMCallViewState,
        volumes: Map<String, Int>,
        selfUid: String = "alice",
    ) = IMCallViewReducer.speaking(
        state,
        volumes,
        volumes.maxByOrNull { it.value }?.key.orEmpty(),
        selfUid,
    )

    /** 核心那一条：三个人同时说话，三个格子都要亮。 */
    @Test
    fun `多人同时说话，每个格子各亮各的`() {
        val state = speak(roomOf("bob", "carol", "dave"), mapOf("bob" to 60, "carol" to 45, "dave" to 30))

        assertTrue("bob 在说话", state.members["bob"]!!.speaking)
        assertTrue("carol 也在说话——不能因为 bob 更响就把她灭掉", state.members["carol"]!!.speaking)
        assertTrue("dave 也在说话", state.members["dave"]!!.speaking)
    }

    /** 音量要跟着落到每个人身上，图标的条高靠它。 */
    @Test
    fun `音量按人记账`() {
        val state = speak(roomOf("bob", "carol"), mapOf("bob" to 60, "carol" to 45))
        assertEquals(60, state.members["bob"]!!.volume)
        assertEquals(45, state.members["carol"]!!.volume)
    }

    /** **全量快照**：不在名单里的人一律清成没说话，不能只做加法。 */
    @Test
    fun `不在名单里的人要被清掉`() {
        var state = speak(roomOf("bob", "carol"), mapOf("bob" to 60, "carol" to 45))
        state = speak(state, mapOf("carol" to 50))

        assertFalse("bob 已经不在名单里了", state.members["bob"]!!.speaking)
        assertEquals(0, state.members["bob"]!!.volume)
        assertTrue(state.members["carol"]!!.speaking)
    }

    /** 本端那格没有 Member，说话状态单独记——否则「我在说话」永远不亮。 */
    @Test
    fun `本端说话单独记账`() {
        val state = speak(roomOf("bob"), mapOf("alice" to 70, "bob" to 20), selfUid = "alice")

        assertTrue("本端在说话", state.selfSpeaking)
        assertEquals(70, state.selfVolume)
        assertTrue("远端也照常亮", state.members["bob"]!!.speaking)
    }

    /** 本端没在说话时不能误亮。 */
    @Test
    fun `本端不在名单里就不亮`() {
        val state = speak(roomOf("bob"), mapOf("bob" to 40), selfUid = "alice")
        assertFalse(state.selfSpeaking)
        assertEquals(0, state.selfVolume)
    }

    /**
     * `speakingUid` 仍然是「最响的那一个」——悬浮球靠它挑画面。
     *
     * 它**不再**被格子使用，但也不能顺手删掉：删了悬浮球就没得挑了。
     */
    @Test
    fun `speakingUid 仍然是最响的那个，留给悬浮球`() {
        val state = speak(roomOf("bob", "carol"), mapOf("bob" to 30, "carol" to 80))
        assertEquals("carol", state.speakingUid)
    }

    /** 没人说话时全清干净。 */
    @Test
    fun `名单空了就全灭`() {
        var state = speak(roomOf("bob", "carol"), mapOf("bob" to 60, "carol" to 45))
        state = speak(state, emptyMap())

        assertTrue(state.members.values.none { it.speaking })
        assertFalse(state.selfSpeaking)
        assertEquals("", state.speakingUid)
    }
}
