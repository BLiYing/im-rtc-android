package com.imrtc.engine.conformance

import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.optArr
import com.imrtc.engine.protocol.optObjArr
import com.imrtc.engine.protocol.optString
import com.imrtc.engine.protocol.optStringArr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务一的验收物：**五份一致性向量能被本仓读到、并且结构完整**。
 *
 * 这里**不断言具体条数**（16 条 case、38 个错误码之类）——那种数字一加向量就得跟着改测试，
 * 而「向量增删」本来就是协议演进的正常动作。条数由服务端的 `check-protocol-consistency.py`
 * 与各端「逐条跑一遍」的测试去守；本文件守的是另一件事：
 * **文件在、能解析、头字段对、该有的清单不为空**。
 *
 * 逐条喂给协议层与状态机是任务二的事（`envelope` → 信封解析，`call_fsm`/`room_fsm` → 状态机，
 * `error_codes`/`reasons` → 枚举表）。在那之前，这份测试保证的是「路是通的」。
 */
class ConformanceVectorsTest {

    @Test
    fun `五份向量都能找到并解析`() {
        for (name in ConformanceVectors.NAMES) {
            val root = ConformanceVectors.loadChecked(name)
            assertTrue("$name.json 解析出来是空对象", root.fields.isNotEmpty())
        }
    }

    @Test
    fun `envelope 向量结构完整`() {
        val root = ConformanceVectors.loadChecked("envelope")
        val cases = root.optObjArr("cases") ?: error("envelope.cases 不是对象数组")
        assertTrue("envelope.cases 不该为空", cases.isNotEmpty())
        for (case in cases) {
            val name = case.optString("name") ?: error("case 缺 name")
            assertTrue("$name 缺 input", case.optString("input") != null)
            assertTrue("$name 缺 expect", case.fields["expect"] is IMJson.Obj)
        }
        val defaults = root.optObjArr("default_cases") ?: error("envelope.default_cases 不是对象数组")
        assertTrue("envelope.default_cases 不该为空", defaults.isNotEmpty())
        for (case in defaults) {
            val name = case.optString("name") ?: error("default_case 缺 name")
            assertTrue("$name 缺 type", case.optString("type") != null)
            // 注意这里**两边形状不一样**：input_data 是原始 JSON 文本（发送侧要按文本原样喂），
            // expect_data 是对象（填完默认值之后的期望结果）。任务二接默认值填充时别搞反。
            assertTrue("$name 的 input_data 应是原始 JSON 文本", case.optString("input_data") != null)
            assertTrue("$name 的 expect_data 应是对象", case.fields["expect_data"] is IMJson.Obj)
        }
    }

    @Test
    fun `两份状态机向量结构完整`() {
        val call = ConformanceVectors.loadChecked("call_fsm")
        assertTrue("call_fsm.states 不该为空", !call.optStringArr("states").isNullOrEmpty())
        assertCasesWithSteps(call, "call_fsm")

        val room = ConformanceVectors.loadChecked("room_fsm")
        for (key in listOf("room_states", "publish_states", "subscribe_states")) {
            assertTrue("room_fsm.$key 不该为空", !room.optStringArr(key).isNullOrEmpty())
        }
        assertCasesWithSteps(room, "room_fsm")
    }

    @Test
    fun `错误码向量结构完整`() {
        val root = ConformanceVectors.loadChecked("error_codes")
        val wire = root.optObjArr("wire") ?: error("error_codes.wire 不是对象数组")
        val local = root.optObjArr("local") ?: error("error_codes.local 不是对象数组")
        assertTrue("error_codes.wire 不该为空", wire.isNotEmpty())
        assertTrue("error_codes.local 不该为空", local.isNotEmpty())

        val seenCodes = HashSet<Long>()
        val seenNames = HashSet<String>()
        for (entry in wire + local) {
            val code = (entry.fields["code"] as? IMJson.Num)?.value ?: error("错误码条目缺 code")
            val name = entry.optString("name") ?: error("错误码 $code 缺 name")
            assertTrue("错误码 $code 缺 msg", entry.optString("msg") != null)
            assertTrue("错误码重复：$code", seenCodes.add(code))
            assertTrue("错误码名字重复：$name", seenNames.add(name))
        }
        assertTrue("error_codes.close_codes 不该为空", !root.optArr("close_codes").isNullOrEmpty())
    }

    @Test
    fun `reason 向量结构完整且兜底值在表内`() {
        val root = ConformanceVectors.loadChecked("reasons")
        val reasons = root.optObjArr("reasons") ?: error("reasons.reasons 不是对象数组")
        assertTrue("reasons.reasons 不该为空", reasons.isNotEmpty())

        val values = reasons.map { it.optString("value") ?: error("reason 条目缺 value") }
        assertEquals("reason 取值有重复", values.size, values.toSet().size)

        // §2.4 规则 6：收到表外的 reason 必须按兜底值处理。兜底值本身当然得在表内。
        val fallback = root.optString("unknown_fallback") ?: error("reasons 缺 unknown_fallback")
        assertTrue("兜底值 $fallback 不在 reason 表里", fallback in values)

        for (key in listOf("group_dominant_priority", "group_dominant_cases", "duration_cases")) {
            assertTrue("reasons.$key 不该为空", !root.optArr(key).isNullOrEmpty())
        }
    }

    private fun assertCasesWithSteps(root: IMJson.Obj, name: String) {
        val cases = root.optObjArr("cases") ?: error("$name.cases 不是对象数组")
        assertTrue("$name.cases 不该为空", cases.isNotEmpty())
        val seen = HashSet<String>()
        for (case in cases) {
            val caseName = case.optString("name") ?: error("$name 里有 case 缺 name")
            assertTrue("$name 用例重名：$caseName", seen.add(caseName))
            assertTrue("$name/$caseName 缺 initial_state", case.fields["initial_state"] != null)
            assertTrue("$name/$caseName 的 steps 不该为空", !case.optArr("steps").isNullOrEmpty())
        }
    }
}
