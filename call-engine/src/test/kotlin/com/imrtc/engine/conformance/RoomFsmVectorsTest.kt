package com.imrtc.engine.conformance

import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.optObj
import com.imrtc.engine.protocol.optObjArr
import com.imrtc.engine.protocol.optString
import com.imrtc.engine.protocol.optStringArr
import com.imrtc.engine.statemachine.IMCallContext
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMEngineContext
import com.imrtc.engine.statemachine.IMEngineMachine
import com.imrtc.engine.statemachine.IMMachineInput
import com.imrtc.engine.statemachine.IMPublishState
import com.imrtc.engine.statemachine.IMRoomContext
import com.imrtc.engine.statemachine.IMRoomState
import com.imrtc.engine.statemachine.IMSubscribeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `room_fsm.json` 逐条驱动，10 个用例、61 步。**与另外四端同一份文件。**
 *
 * 注意驱动的是 **engine 总状态机**而不是房间机本身：向量里有
 * `onDisconnected` / `onConnected` / `onKickedOut` / `onCallEnd` 这些连接级与跨机事件，
 * 它们只有把通话机与房间机合起来才说得清（见 `EngineStateMachine`）。
 */
class RoomFsmVectorsTest {

    private val root = ConformanceVectors.loadChecked("room_fsm")

    @Test
    fun `三组状态集合与本地枚举一致`() {
        assertEquals(
            root.optStringArr("room_states"),
            IMRoomState.entries.map { it.wire },
        )
        // publish 的 idle 与 subscribe 的 none 在本地是「不在表里」，不是一个枚举值——
        // 用「不存在」表达空态，省掉两种等价写法。
        assertEquals(
            root.optStringArr("publish_states"),
            listOf("idle") + IMPublishState.entries.map { it.wire },
        )
        assertEquals(
            root.optStringArr("subscribe_states"),
            listOf("none") + IMSubscribeState.entries.map { it.wire },
        )
    }

    @Test
    fun `每个用例逐步跑过`() {
        val cases = root.optObjArr("cases") ?: error("cases 不是对象数组")
        var steps = 0
        for (case in cases) {
            val name = case.optString("name") ?: error("case 缺 name")
            var ctx = seed(case.optObj("initial_state") ?: error("$name 缺 initial_state"))
            val stepList = case.optObjArr("steps") ?: error("$name 的 steps 不是对象数组")

            stepList.forEachIndexed { index, step ->
                val where = "$name#$index"
                // 时间戳固定，免得 synthesizeNetworkEnd 的时长随时钟变。
                val output = IMEngineMachine.reduce(ctx, inputOf(step, where), nowMs = FIXED_NOW_MS)

                val expectedSend = step.optObjArr("send") ?: emptyList()
                assertEquals(
                    "$where：发出的帧数不对（${output.send.map { it.type }}）",
                    expectedSend.size,
                    output.send.size,
                )
                expectedSend.forEachIndexed { i, expected ->
                    val actual = output.send[i]
                    assertEquals("$where：第 $i 帧的 type 不对", expected.optString("type"), actual.type)
                    expected.optObj("data")?.let {
                        VectorMatch.subsetFields("$where 第 $i 帧 data", it.fields, actual.data)
                    }
                }

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
                step.optObj("state")?.let { assertState(where, it, output.state) }
                ctx = output.state
                steps++
            }
        }
        assertTrue("一步都没跑，向量八成没读到", steps > 0)
    }

    /** state 是**子集比对的对象**：向量列了哪几项就比哪几项。 */
    private fun assertState(where: String, expected: IMJson.Obj, actual: IMEngineContext) {
        expected.optString("room")?.let {
            assertEquals("$where：房间状态不对", it, actual.room.state.wire)
        }
        expected.optString("call")?.let {
            assertEquals("$where：通话状态不对", it, actual.call.state.wire)
        }
        expected.optObj("publish")?.let { publish ->
            assertEquals(
                "$where：发布表不对",
                publish.fields.mapValues { (_, v) -> (v as IMJson.Str).value },
                actual.room.publish.mapValues { (_, v) -> v.wire },
            )
        }
        expected.optObj("subscribe")?.let { subscribe ->
            assertEquals(
                "$where：订阅表不对",
                subscribe.fields.mapValues { (_, v) -> (v as IMJson.Str).value },
                actual.room.subscribe.mapValues { (_, v) -> v.wire },
            )
        }
    }

    private fun seed(initial: IMJson.Obj): IMEngineContext {
        val publish = initial.optObj("publish")?.fields.orEmpty().mapValues { (_, v) ->
            val wire = (v as IMJson.Str).value
            IMPublishState.entries.firstOrNull { it.wire == wire } ?: error("未知发布状态：$wire")
        }
        val subscribe = initial.optObj("subscribe")?.fields.orEmpty().mapValues { (_, v) ->
            val wire = (v as IMJson.Str).value
            IMSubscribeState.entries.firstOrNull { it.wire == wire } ?: error("未知订阅状态：$wire")
        }
        val roomState = IMRoomState.from(initial.optString("room") ?: "idle")
        return IMEngineContext(
            room = IMRoomContext(
                state = roomState,
                publish = publish,
                subscribe = subscribe,
                /*
                 **向量里说「初始就在房里」的，didJoin 也要跟着置上。**

                 向量断言的是 room / publish / subscribe 那几个键，didJoin 是本端为了分辨
                 「RECONNECTING 是从 JOINED 断的还是从 JOINING 断的」自己记的账
                 （见 IMRoomMachine.resume）。种子里漏掉它，
                 reconnect_resumed_replays_buffered_intent 就会被当成「那次进房从未落地」
                 而去重发 room.join——**是种子不完整，不是实现错了**。
                */
                didJoin = roomState != IMRoomState.IDLE && roomState != IMRoomState.JOINING,
            ),
            call = IMCallContext(state = IMCallState.from(initial.optString("call") ?: "idle")),
        )
    }

    private fun inputOf(step: IMJson.Obj, where: String): IMMachineInput {
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

    private companion object {
        const val FIXED_NOW_MS = 1_756_876_812_000L
    }
}
