package com.imrtc.engine.conformance

import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.optObj
import com.imrtc.engine.protocol.optObjArr
import com.imrtc.engine.protocol.optString
import com.imrtc.engine.statemachine.IMCallContext
import com.imrtc.engine.statemachine.IMCallExit
import com.imrtc.engine.statemachine.IMCallMachine
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMMachineInput
import com.imrtc.engine.statemachine.forceEndFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IMCallExit] 那张表与 `call_fsm.json` 逐条对照（iOS `CallExitTableTests` 同一份判据）。
 *
 * 表是「这个状态怎么结束」的唯一出处（挂断键、红键强制收场、迟到帧补发、请求失败收场四处都查它），
 * 向量是五端共用的契约。两边任何一处改了而另一处没跟上，这里就会红。
 */
class CallExitTableTest {

    private val root = ConformanceVectors.loadChecked("call_fsm")
    private val exitOps = listOf("reject", "cancel", "hangup")

    @Test
    fun `向量里每一步退出都与表一致`() {
        var checked = 0
        for (case in root.optObjArr("cases") ?: error("cases 不是对象数组")) {
            val name = case.optString("name") ?: "?"
            var ctx = IMCallContext(state = IMCallState.from(case.optString("initial_state") ?: "idle"))
            (case.optObjArr("steps") ?: emptyList()).forEachIndexed { index, step ->
                val where = "$name#$index"
                val input = vectorInput(step, where)
                val wantSend = (step.optObjArr("send") ?: emptyList()).mapNotNull { it.optString("type") }
                if (input is IMMachineInput.Act && input.op in exitOps) {
                    val exit = IMCallExit.of(ctx.state)
                    if (step.optObj("result") != null) {
                        assertNotEquals("$where：向量在 ${ctx.state} 下本地拒掉 ${input.op}，表却放行", input.op, exit?.op)
                    } else {
                        assertEquals("$where：向量在 ${ctx.state} 下放行 ${input.op}，表不认", input.op, exit?.op)
                        assertEquals("$where：${ctx.state} 下 ${input.op} 发的帧", wantSend, exit?.frameTypes ?: emptyList<String>())
                    }
                    checked++
                }
                if (input is IMMachineInput.Recv && ctx.state == IMCallState.IDLE && input.type != IMFrameType.CALL_INCOMING) {
                    val tableSend = IMCallExit.serverState(input.type)?.let { IMCallExit.of(it) }?.frameTypes ?: emptyList()
                    assertEquals("$where：idle 下迟到的 ${input.type} 补发的帧", wantSend, tableSend)
                }
                ctx = IMCallMachine.reduce(ctx, input).state
            }
        }
        assertTrue("向量里的退出步骤明显变少了", checked >= 3)
    }

    @Test
    fun `每个状态乘三个退出方法都按表走`() {
        for (state in IMCallState.entries) {
            val ctx = IMCallContext(state = state, callId = "c-1")
            for (op in exitOps) {
                val out = IMCallMachine.reduce(ctx, IMMachineInput.Act(op, emptyMap()))
                val exit = IMCallExit.of(state)
                if (exit != null && exit.op == op) {
                    assertNull("$state 下 $op 应放行", out.reject)
                    assertEquals("$state 下 $op 的帧", exit.frameTypes, out.send.map { it.type })
                    assertTrue(out.send.all { it.data["call_id"] == IMJson.Str("c-1") })
                } else {
                    assertEquals("$state 下 $op 应本地拒成 2005", IMErrorCode.INVALID_STATE, out.reject)
                    assertTrue(out.send.isEmpty())
                }
            }
        }
    }

    @Test
    fun `强制收场按状态挑的帧与原因就是表里那一行`() {
        for (state in IMCallState.entries.filter { it != IMCallState.IDLE }) {
            val (frames, reason) = forceEndFrames(IMCallContext(state = state, callId = "c-1"))
            assertEquals("$state", IMCallExit.of(state)?.frameTypes, frames.map { it.type })
            assertEquals("$state", IMCallExit.of(state)?.reason, reason)
        }
    }

    @Test
    fun `失败也要本地收场的集合就是三种结束帧`() {
        assertEquals(
            setOf(IMFrameType.CALL_CANCEL, IMFrameType.CALL_REJECT, IMFrameType.CALL_HANGUP),
            IMCallExit.allFrameTypes,
        )
    }
}
