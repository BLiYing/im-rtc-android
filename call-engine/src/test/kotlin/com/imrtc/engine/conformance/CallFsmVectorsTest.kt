package com.imrtc.engine.conformance

import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.optObj
import com.imrtc.engine.protocol.optObjArr
import com.imrtc.engine.protocol.optString
import com.imrtc.engine.protocol.optStringArr
import com.imrtc.engine.statemachine.IMCallContext
import com.imrtc.engine.statemachine.IMCallMachine
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMMachineInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `call_fsm.json` 逐条驱动通话状态机，16 个用例、73 步。**与另外四端同一份文件。**
 *
 * 向量的约定：**省略 `send` / `emit` / `result` 即断言为空**——「这一步不该发帧、不该抛回调、
 * 没有被本地拒绝」与「这一步发了什么」同样重要。漏抛不会报错，只会表现成界面不动，全靠这里守。
 */
class CallFsmVectorsTest {

    private val root = ConformanceVectors.loadChecked("call_fsm")

    @Test
    fun `状态集合与本地枚举一致`() {
        val states = root.optStringArr("states") ?: error("缺 states")
        assertEquals("状态集合与向量不一致", states, IMCallState.entries.map { it.wire })
    }

    @Test
    fun `十六个用例逐步跑过`() {
        val cases = root.optObjArr("cases") ?: error("cases 不是对象数组")
        var steps = 0
        for (case in cases) {
            val name = case.optString("name") ?: error("case 缺 name")
            // 少数用例带 `context`：从半途开始（例如群通话中途加邀，一上来就是 connected
            // 且已经有 call_id）。忽略它的话第一步就会发出 call_id="" 的帧。
            val seed = case.optObj("context")
            var ctx = IMCallContext(
                state = IMCallState.from(case.optString("initial_state") ?: "idle"),
                callId = seed?.optString("call_id") ?: "",
                roomId = seed?.optString("room_id") ?: "",
                isGroup = (seed?.fields?.get("is_group") as? IMJson.Bool)?.value ?: false,
            )
            val stepList = case.optObjArr("steps") ?: error("$name 的 steps 不是对象数组")

            stepList.forEachIndexed { index, step ->
                val where = "$name#$index"
                val output = IMCallMachine.reduce(ctx, inputOf(step, where))

                // send：向量里省略就是「一帧都不该发」
                val expectedSend = step.optObjArr("send") ?: emptyList()
                assertEquals("$where：发出的帧数不对（${output.send.map { it.type }}）", expectedSend.size, output.send.size)
                expectedSend.forEachIndexed { i, expected ->
                    val actual = output.send[i]
                    assertEquals("$where：第 $i 帧的 type 不对", expected.optString("type"), actual.type)
                    // data 是子集断言，与 envelope 向量一致
                    expected.optObj("data")?.let {
                        VectorMatch.subsetFields("$where 第 $i 帧 data", it.fields, actual.data)
                    }
                }

                // emit：向量里省略就是「一个回调都不该抛」
                val expectedEmit = step.optObjArr("emit") ?: emptyList()
                assertEquals(
                    "$where：抛出的回调数不对（${output.emit.map { it.callback }}）",
                    expectedEmit.size,
                    output.emit.size,
                )
                expectedEmit.forEachIndexed { i, expected ->
                    val actual = output.emit[i]
                    assertEquals("$where：第 $i 个回调名不对", expected.optString("cb"), actual.callback)
                    expected.optObj("args")?.let {
                        VectorMatch.subsetFields("$where 回调 ${actual.callback}", it.fields, actual.args)
                    }
                }

                assertResult(where, step, output.reject)

                step.optString("state")?.let {
                    assertEquals("$where：状态不对", it, output.state.state.wire)
                }
                ctx = output.state
                steps++
            }
        }
        assertTrue("一步都没跑，向量八成没读到", steps > 0)
    }

    private fun inputOf(step: IMJson.Obj, where: String): IMMachineInput = vectorInput(step, where)
}

/**
 * `result`：`act` 被状态机就地拒绝时回给调用方的结果；**省略 = 断言没有本地拒绝**。
 * 本地拒绝只从结果出口报，所以同一步的 `emit` 必然为空（上面已经按省略即空断言过）。
 */
internal fun assertResult(where: String, step: IMJson.Obj, reject: IMErrorCode?) {
    val expected = step.optObj("result")
    if (expected == null) {
        assertEquals("$where：不该有本地拒绝", null, reject?.wireName)
        return
    }
    assertEquals("$where：本地拒绝的码不对", (expected.fields["code"] as? IMJson.Num)?.value?.toInt(), reject?.code)
    assertEquals("$where：本地拒绝的 name 不对", expected.optString("name"), reject?.wireName)
}

private fun vectorInput(step: IMJson.Obj, where: String): IMMachineInput {
    step.optObj("act")?.let { act ->
        val op = act.optString("op") ?: error("$where 的 act 缺 op")
        return IMMachineInput.Act(op, act.optObj("args")?.fields ?: emptyMap())
    }
    step.optObj("recv")?.let { recv ->
        val type = recv.optString("type") ?: error("$where 的 recv 缺 type")
        return IMMachineInput.Recv(type, recv.optObj("data")?.fields ?: emptyMap())
    }
    step.optString("internal")?.let { return IMMachineInput.Internal(it) }
    error("$where：这一步既没有 act 也没有 recv 或 internal")
}
