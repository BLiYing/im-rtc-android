package com.imrtc.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会议分页画廊与第一页的发言人优先（MEETING_ROOM_DESIGN §4.1 / §4.2）。
 *
 * §4.2 的三条规则全是**时间闸**，而时间闸最容易写成「差不多能用」：
 * 少一条就是格子每 300ms 跳一次，多一条就是说了半天也换不上去。
 * 所以每一条各有一条用例，**三端跑同一组场景**（Web 的 `firstPage.test.ts`、
 * iOS 的 `MeetingPagerTests`）。
 */
class MeetingPagerTest {

    private val firstPageSize = IMMeetingPager.REMOTES_PER_PAGE

    private fun names(count: Int) = (1..count).map { "u$it" }

    private fun input(
        uids: List<String> = names(10),
        speaking: Set<String> = emptySet(),
        withVideo: Set<String>? = null,
        pinned: String = "",
        nowMs: Long,
    ) = IMMeetingPager.FirstPageInput(
        uids = uids,
        speaking = speaking,
        withVideo = withVideo ?: uids.toSet(),
        pinned = pinned,
        nowMs = nowMs,
        firstPageSize = firstPageSize,
    )

    /** settle 把所有人在第一页的驻留时间熬过 10 s，好让后面的用例能真的换人。 */
    private fun settle(uids: List<String> = names(10)): Pair<IMMeetingPager.FirstPageState, Long> {
        var state = IMMeetingPager.reorderFirstPage(
            IMMeetingPager.FirstPageState(), input(uids = uids, nowMs = 0),
        )
        val nowMs = IMMeetingPager.MIN_STAY_MS + 1
        state = IMMeetingPager.reorderFirstPage(state, input(uids = uids, nowMs = nowMs))
        return state to nowMs
    }

    private fun firstPage(state: IMMeetingPager.FirstPageState) = state.order.take(firstPageSize)

    // ── 发言人优先 ──────────────────────────────────────────────

    @Test
    fun `第一次排就是进房顺序`() {
        val state = IMMeetingPager.reorderFirstPage(
            IMMeetingPager.FirstPageState(), input(nowMs = 1_000),
        )
        assertEquals(names(10), state.order)
    }

    @Test
    fun `说够 1_5 秒才换进第一页`() {
        val (base, now) = settle()
        val speaking = setOf("u10")

        // 刚开口：记下起点，但不换。
        var state = IMMeetingPager.reorderFirstPage(base, input(speaking = speaking, nowMs = now))
        assertFalse(firstPage(state).contains("u10"))

        // 差一点点也不换——1.4 s 的咳嗽不该把人顶上来。
        state = IMMeetingPager.reorderFirstPage(
            state, input(speaking = speaking, nowMs = now + IMMeetingPager.PROMOTE_AFTER_MS - 100),
        )
        assertFalse(firstPage(state).contains("u10"))

        state = IMMeetingPager.reorderFirstPage(
            state, input(speaking = speaking, nowMs = now + IMMeetingPager.PROMOTE_AFTER_MS),
        )
        assertTrue("说够 1.5 s 就该换进第一页", firstPage(state).contains("u10"))
    }

    @Test
    fun `换走的是第一页里最久没发言的那一个`() {
        val (base, now) = settle()
        // u3 最近说过话，u1 从没说过：该走的是 u1。
        var state = IMMeetingPager.reorderFirstPage(base, input(speaking = setOf("u3"), nowMs = now))
        val later = now + IMMeetingPager.SWAP_COOLDOWN_MS + IMMeetingPager.PROMOTE_AFTER_MS
        state = IMMeetingPager.reorderFirstPage(
            state, input(speaking = setOf("u10"), nowMs = later - IMMeetingPager.PROMOTE_AFTER_MS),
        )
        state = IMMeetingPager.reorderFirstPage(state, input(speaking = setOf("u10"), nowMs = later))

        assertTrue(firstPage(state).contains("u10"))
        assertTrue(firstPage(state).contains("u3"))
        assertFalse(firstPage(state).contains("u1"))
    }

    @Test
    fun `同样久没说话时先换走没开摄像头的`() {
        val (base, now) = settle()
        val withVideo = names(10).filter { it != "u2" }.toSet()
        var state = IMMeetingPager.reorderFirstPage(
            base, input(speaking = setOf("u10"), withVideo = withVideo, nowMs = now),
        )
        state = IMMeetingPager.reorderFirstPage(
            state,
            input(
                speaking = setOf("u10"), withVideo = withVideo,
                nowMs = now + IMMeetingPager.PROMOTE_AFTER_MS,
            ),
        )
        assertFalse(firstPage(state).contains("u2"))
    }

    @Test
    fun `刚上第一页的人 10 秒内不会被顶掉`() {
        val (base, now) = settle()
        var state = IMMeetingPager.reorderFirstPage(base, input(speaking = setOf("u10"), nowMs = now))
        val promotedAt = now + IMMeetingPager.PROMOTE_AFTER_MS
        state = IMMeetingPager.reorderFirstPage(state, input(speaking = setOf("u10"), nowMs = promotedAt))
        assertTrue(firstPage(state).contains("u10"))

        // 紧接着 u9 也说够了：这时第一页里只有 u10 是「新来的」，别人都熬过 10 s，
        // 所以该被换走的是别人，u10 必须还在。
        val later = promotedAt + IMMeetingPager.SWAP_COOLDOWN_MS + IMMeetingPager.PROMOTE_AFTER_MS
        state = IMMeetingPager.reorderFirstPage(
            state, input(speaking = setOf("u9"), nowMs = later - IMMeetingPager.PROMOTE_AFTER_MS),
        )
        state = IMMeetingPager.reorderFirstPage(state, input(speaking = setOf("u9"), nowMs = later))
        assertTrue(firstPage(state).contains("u10"))
        assertTrue(firstPage(state).contains("u9"))
    }

    @Test
    fun `每 2 秒最多换一个人`() {
        val (base, now) = settle()
        val both = setOf("u9", "u10")
        var state = IMMeetingPager.reorderFirstPage(base, input(speaking = both, nowMs = now))
        state = IMMeetingPager.reorderFirstPage(
            state, input(speaking = both, nowMs = now + IMMeetingPager.PROMOTE_AFTER_MS),
        )
        val promoted = listOf("u9", "u10").filter { firstPage(state).contains(it) }
        assertEquals("一轮只许换一个", 1, promoted.size)
    }

    @Test
    fun `钉住的人不许被换走`() {
        val (base, now) = settle()
        // u1 是最久没发言的那个，不钉的话第一个被换。
        var state = IMMeetingPager.reorderFirstPage(
            base, input(speaking = setOf("u10"), pinned = "u1", nowMs = now),
        )
        state = IMMeetingPager.reorderFirstPage(
            state,
            input(speaking = setOf("u10"), pinned = "u1", nowMs = now + IMMeetingPager.PROMOTE_AFTER_MS),
        )
        assertTrue(firstPage(state).contains("u1"))
    }

    // ── 成员进出 ────────────────────────────────────────────────

    @Test
    fun `有人离开后面的人依次前补`() {
        val (base, now) = settle()
        val left = names(10).filter { it != "u5" }
        val state = IMMeetingPager.reorderFirstPage(base, input(uids = left, nowMs = now + 1))
        assertFalse(state.order.contains("u5"))
        assertEquals(9, state.order.size)
    }

    @Test
    fun `新人追加到末尾`() {
        val (base, now) = settle()
        val state = IMMeetingPager.reorderFirstPage(
            base, input(uids = names(10) + "zed", nowMs = now + 1),
        )
        assertEquals("zed", state.order.last())
    }

    @Test
    fun `走掉的人不留在记账里`() {
        val (base, now) = settle()
        var state = IMMeetingPager.reorderFirstPage(base, input(speaking = setOf("u3"), nowMs = now))
        state = IMMeetingPager.reorderFirstPage(
            state, input(uids = names(10).filter { it != "u3" }, nowMs = now + 1),
        )
        assertNull(state.lastSpokeAt["u3"])
        assertNull(state.enteredAt["u3"])
    }

    // ── 分页算术 ────────────────────────────────────────────────

    @Test
    fun `每页都留一格给自己`() {
        assertEquals(7, IMMeetingPager.pageCount(49))
        assertEquals(1, IMMeetingPager.pageCount(8))
        assertEquals("一个远端都没有也有第 1 页", 1, IMMeetingPager.pageCount(0))
    }

    @Test
    fun `9 人以内不分页`() {
        assertFalse(IMMeetingPager.paged(8))
        assertTrue(IMMeetingPager.paged(9))
    }

    @Test
    fun `最后一页不满就是不满`() {
        assertEquals(listOf("u9", "u10"), IMMeetingPager.pageSlice(names(10), 1))
        assertEquals(emptyList<String>(), IMMeetingPager.pageSlice(names(10), 5))
    }

    @Test
    fun `页码夹回范围内`() {
        assertEquals(1, IMMeetingPager.clampPage(6, 2))
        assertEquals(0, IMMeetingPager.clampPage(-1, 3))
    }

    @Test
    fun `页码文案`() {
        assertEquals("1 / 7", IMMeetingPager.pageLabel(0, 7))
        assertEquals("7 / 7", IMMeetingPager.pageLabel(6, 7))
    }

    @Test
    fun `被换下去的人回到第一页时重新起算 10 秒`() {
        val (base, now) = settle()
        val speaking = setOf("u10")
        // u1 最久没说话，被 u10 顶掉。
        var state = IMMeetingPager.reorderFirstPage(base, input(speaking = speaking, nowMs = now))
        state = IMMeetingPager.reorderFirstPage(
            state, input(speaking = speaking, nowMs = now + IMMeetingPager.PROMOTE_AFTER_MS),
        )
        assertFalse(firstPage(state).contains("u1"))
        assertNull(state.enteredAt["u1"])

        // u1 因为有人离开补位回第一页：驻留时刻要从此刻重新起算，
        // 留着旧的那一条的话他会被下一个说话的人立刻再顶掉，位置一闪就没。
        // 走两个人 u1 才从第 10 位补回来（换位是跟第 10 位对调，不是挪一格）。
        val back = now + IMMeetingPager.PROMOTE_AFTER_MS + 1
        val fewer = names(10).filter { it != "u2" && it != "u3" }
        state = IMMeetingPager.reorderFirstPage(state, input(uids = fewer, nowMs = back))
        assertTrue(firstPage(state).contains("u1"))
        assertEquals(back, state.enteredAt["u1"])
    }

    @Test
    fun `分页恒为方阵，不跟着容器形状变`() {
        // 9 格按「格子最大」算在横屏上是 5×2、竖屏上是 2×5；
        // 分页要的是格子位置固定，左滑只换人。
        assertEquals(3 to 3, IMGrid.fixedDimensions(9))
        assertTrue(IMGrid.dimensions(9, 2.2) != 3 to 3)
        // 最后一页不满也按同样的方阵排，格子不放大（§4.1）。
        assertEquals(3, IMGrid.fixedDimensions(9).first)
    }
}
