package com.imrtc.engine.conformance

import com.imrtc.engine.protocol.IMCallEndReason
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.optObjArr
import com.imrtc.engine.protocol.optString
import com.imrtc.engine.protocol.optStringArr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `reasons.json` 逐条跑：枚举全表、未知值兜底、群通话主导 reason、时长算法。
 *
 * 为什么主导 reason 要五端各算一遍而不是只信服务端：**群通话里每个成员的裁决是分别到达的**，
 * 端上要在收齐之前就能显示中间状态。算法不一致的话，同一通电话在不同端上会显示成不同结局。
 */
class ReasonVectorsTest {

    private val root = ConformanceVectors.loadChecked("reasons")

    @Test
    fun `reason 全表逐条相等`() {
        val reasons = root.optObjArr("reasons") ?: error("reasons 不是对象数组")
        for (entry in reasons) {
            val value = entry.optString("value") ?: error("条目缺 value")
            val canBeConnected = (entry.fields["can_be_connected"] as? IMJson.Bool)?.value
                ?: error("$value 缺 can_be_connected")
            val durationPositive = (entry.fields["duration_positive"] as? IMJson.Bool)?.value
                ?: error("$value 缺 duration_positive")

            val actual = IMCallEndReason.entries.firstOrNull { it.wire == value }
                ?: error("本地表里没有 reason：$value")
            assertEquals("$value 的 can_be_connected 不一致", canBeConnected, actual.canBeConnected)
            assertEquals("$value 的 duration_positive 不一致", durationPositive, actual.durationPositive)
        }
        assertEquals("本地 reason 数量与向量不一致", reasons.size, IMCallEndReason.entries.size)
    }

    @Test
    fun `表外的值折成兜底而不是崩掉`() {
        val fallback = root.optString("unknown_fallback") ?: error("缺 unknown_fallback")
        assertEquals("兜底值与向量不一致", fallback, IMCallEndReason.FALLBACK.wire)
        // §2.4 规则 6：收到集合外的值必须折成兜底，**禁止崩溃、禁止透传给 UI**。
        assertEquals(IMCallEndReason.FALLBACK, IMCallEndReason.from("supernova"))
        assertEquals(IMCallEndReason.FALLBACK, IMCallEndReason.from(""))
        assertEquals(IMCallEndReason.HANGUP, IMCallEndReason.from("hangup"))
    }

    @Test
    fun `群通话主导 reason 按优先级取而不是按先后`() {
        val priority = root.optStringArr("group_dominant_priority") ?: error("缺 group_dominant_priority")
        assertEquals(
            "主导优先级与向量不一致",
            priority,
            IMCallEndReason.GROUP_DOMINANT_PRIORITY.map { it.wire },
        )

        val cases = root.optObjArr("group_dominant_cases") ?: error("缺 group_dominant_cases")
        for (case in cases) {
            val name = case.optString("name") ?: error("case 缺 name")
            val outcomes = case.optStringArr("member_outcomes") ?: error("$name 缺 member_outcomes")
            val expect = case.optString("expect") ?: error("$name 缺 expect")
            val actual = IMCallEndReason.dominant(outcomes.map { IMCallEndReason.from(it) })
            assertEquals("$name：主导 reason 不对", expect, actual.wire)
        }
    }

    @Test
    fun `时长向下取整且未接通恒为零`() {
        val cases = root.optObjArr("duration_cases") ?: error("缺 duration_cases")
        for (case in cases) {
            val name = case.optString("name") ?: error("case 缺 name")
            val connectedAt = (case.fields["connected_at_ms"] as? IMJson.Num)?.value
                ?: error("$name 缺 connected_at_ms")
            val endedAt = (case.fields["ended_at_ms"] as? IMJson.Num)?.value
                ?: error("$name 缺 ended_at_ms")
            val expect = (case.fields["expect_duration_sec"] as? IMJson.Num)?.value
                ?: error("$name 缺 expect_duration_sec")
            assertEquals("$name：时长不对", expect, IMCallEndReason.durationSec(connectedAt, endedAt))
        }
        // 1999ms 要算 1 秒不是 2 秒——**向下取整不是四舍五入**。
        assertTrue(IMCallEndReason.durationSec(0L, 1_999L) == 0L)
    }
}
