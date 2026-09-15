package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强制收场（`IMEngineMachine.forceEnd`）与 idle 下迟到的房间帧、通话帧。**纯函数，纯 JVM。**
 *
 * 与 iOS `ForceEndTests` 同名同义。守的是 2026-09-13 14:53~14:58 iOS frank 那一场暴露出来的事：
 * - 红键的结束帧没发出去时，界面收了而 Engine 还留在通话与房间里，别人一直看得见他；
 * - 收场之后才回来的 `room.join.ok` / `call.invite.ok` 会让服务端一直挂着这个人。
 */
class ForceEndTest {

    private fun inCall(state: IMCallState, callId: String = "c-1", room: IMRoomState = IMRoomState.IDLE) =
        IMEngineContext(
            call = IMCallContext(state = state, callId = callId, connectedAtMs = 1_000),
            room = IMRoomContext(state = room, roomId = if (room == IMRoomState.IDLE) "" else "r-1"),
        )

    private fun str(data: Map<String, IMJson>, key: String) = (data[key] as? IMJson.Str)?.value

    // ── 通话 ──────────────────────────────────────────────────────────

    @Test
    fun `接通中强制收场：发 hangup，本地归零并抛一次 onCallEnd`() {
        val out = IMEngineMachine.forceEnd(inCall(IMCallState.CONNECTED, room = IMRoomState.JOINED), nowMs = 6_500)

        assertEquals(listOf(IMFrameType.CALL_HANGUP), out.send.map { it.type })
        assertEquals("c-1", str(out.send.first().data, "call_id"))
        assertEquals(IMCallState.IDLE, out.state.call.state)
        assertEquals("通话收了，房间也要一起归零", IMRoomState.IDLE, out.state.room.state)
        assertEquals(listOf("onCallEnd"), out.emit.map { it.callback })
        assertEquals("hangup", str(out.emit.first().args, "reason"))
        assertEquals(IMJson.Num(5), out.emit.first().args["duration_sec"])
    }

    /** frank 那一刻的形状：call.connected 到了、room.join 还在路上。 */
    @Test
    fun `join 在飞时强制收场照样挂断，攒着的意图不留到下一通`() {
        val out = IMEngineMachine.forceEnd(inCall(IMCallState.CONNECTING, room = IMRoomState.JOINING), nowMs = 2_000)

        assertEquals(listOf(IMFrameType.CALL_HANGUP), out.send.map { it.type })
        assertEquals(IMRoomState.IDLE, out.state.room.state)
        assertTrue(out.state.room.buffered.isEmpty())
    }

    @Test
    fun `响铃中强制收场发 reject`() {
        val out = IMEngineMachine.forceEnd(inCall(IMCallState.RINGING), nowMs = 2_000)
        assertEquals(listOf(IMFrameType.CALL_REJECT), out.send.map { it.type })
        assertEquals("reject", str(out.emit.first().args, "reason"))
    }

    /** accept 有没有落地本端不知道：两帧都发，服务端那边总有一帧生效。 */
    @Test
    fun `接听中强制收场发 reject 再发 hangup`() {
        val out = IMEngineMachine.forceEnd(inCall(IMCallState.ACCEPTING), nowMs = 2_000)
        assertEquals(listOf(IMFrameType.CALL_REJECT, IMFrameType.CALL_HANGUP), out.send.map { it.type })
        assertEquals(IMCallState.IDLE, out.state.call.state)
    }

    /** invite.ok 还没回来就没有 call_id：此刻发不了 cancel，但本地照样得收得掉。 */
    @Test
    fun `拨出中还没拿到 call_id：不发帧，本地照样收场`() {
        // 拨出中还没接通：没有 connected_at_ms。
        val inviting = inCall(IMCallState.INVITING, callId = "").let { it.copy(call = it.call.copy(connectedAtMs = 0)) }
        val out = IMEngineMachine.forceEnd(inviting, nowMs = 2_000)
        assertTrue(out.send.isEmpty())
        assertEquals(IMCallState.IDLE, out.state.call.state)
        assertEquals("cancel", str(out.emit.first().args, "reason"))
        assertEquals("未接通时长恒为 0", IMJson.Num(0), out.emit.first().args["duration_sec"])
    }

    // ── 会议与空闲 ────────────────────────────────────────────────────

    @Test
    fun `会议里强制收场发 room leave 并抛 onRoomLeft`() {
        val out = IMEngineMachine.forceEnd(
            IMEngineContext(room = IMRoomContext(state = IMRoomState.JOINED, roomId = "r-9")),
            nowMs = 2_000,
        )
        assertEquals(listOf(IMFrameType.ROOM_LEAVE), out.send.map { it.type })
        assertEquals("r-9", str(out.send.first().data, "room_id"))
        assertEquals(IMRoomState.IDLE, out.state.room.state)
        assertEquals("会议没有 onCallEnd，收尾只能靠 onRoomLeft", listOf("onRoomLeft"), out.emit.map { it.callback })
    }

    @Test
    fun `没有进行中的通话或房间：原样返回`() {
        val out = IMEngineMachine.forceEnd(IMEngineContext(), nowMs = 2_000)
        assertEquals(IMEngineContext(), out.state)
        assertTrue(out.send.isEmpty())
        assertTrue(out.emit.isEmpty())
    }

    // ── idle 下迟到的房间帧 ───────────────────────────────────────────

    /** 收场之后才回来的 join.ok：**不认领，补发 room.leave**。 */
    @Test
    fun `idle 下迟到的 join ok：补发 room leave，状态不动`() {
        val out = IMRoomMachine.reduce(
            IMRoomContext(),
            IMMachineInput.Recv(
                "room.join.ok",
                mapOf("room_id" to IMJson.Str("r-1"), "participant_id" to IMJson.Str("p-6")),
            ),
        )
        assertEquals("认领的话会把一个没人要的房间捡回来", IMRoomState.IDLE, out.state.state)
        assertEquals(listOf(IMFrameType.ROOM_LEAVE), out.send.map { it.type })
        assertEquals("r-1", str(out.send.first().data, "room_id"))
        assertTrue("宿主不该收到一个它早就离开的房间的 onRoomJoined", out.emit.isEmpty())
    }

    @Test
    fun `idle 下其余迟到的房间帧一律丢弃`() {
        val idle = IMRoomContext()
        val offer = IMRoomMachine.reduce(
            idle,
            IMMachineInput.Recv(IMFrameType.ROOM_OFFER, mapOf("pc" to IMJson.Str("sub"), "sdp" to IMJson.Str("v=0"))),
        )
        assertTrue("应答的话会把下行协商重新拉起来", offer.send.isEmpty())

        val joined = IMRoomMachine.reduce(
            idle,
            IMMachineInput.Recv(IMFrameType.ROOM_PARTICIPANT_JOINED, mapOf("uid" to IMJson.Str("bob"))),
        )
        assertTrue(joined.emit.isEmpty())

        // 补发的那条 room.leave 的 .ok 回来时也落在这里：不能多抛一次 onRoomLeft。
        val leaveOk = IMRoomMachine.reduce(idle, IMMachineInput.Recv("room.leave.ok", emptyMap()))
        assertTrue(leaveOk.emit.isEmpty())
        assertEquals(idle, leaveOk.state)
    }

    // ── idle 下迟到的通话帧 ───────────────────────────────────────────

    /** 强制收场时 invite 还在路上：它回来了就补发 cancel，被叫才不会一直响到超时。 */
    @Test
    fun `idle 下迟到的 invite ok：补发 call cancel`() {
        val out = IMCallMachine.reduce(
            IMCallContext(),
            IMMachineInput.Recv("call.invite.ok", mapOf("call_id" to IMJson.Str("c-9"), "room_id" to IMJson.Str("r-9"))),
        )
        assertEquals(listOf(IMFrameType.CALL_CANCEL), out.send.map { it.type })
        assertEquals("c-9", str(out.send.first().data, "call_id"))
        assertEquals("本地已经收场了，不能把这通捡回来", IMCallContext(), out.state)
        assertTrue(out.emit.isEmpty())
    }

    /** cancel 来不及、对方已经接起来了：补发 hangup。 */
    @Test
    fun `idle 下迟到的 call connected：补发 call hangup`() {
        val out = IMCallMachine.reduce(
            IMCallContext(),
            IMMachineInput.Recv(
                IMFrameType.CALL_CONNECTED,
                mapOf("call_id" to IMJson.Str("c-9"), "room_id" to IMJson.Str("r-9"), "room_token" to IMJson.Str("rt")),
            ),
        )
        assertEquals(listOf(IMFrameType.CALL_HANGUP), out.send.map { it.type })
        assertEquals("c-9", str(out.send.first().data, "call_id"))
        assertEquals(IMCallContext(), out.state)
        assertTrue("不许发 room.join、也不许抛 onCallBegin", out.emit.isEmpty())
    }

    /** 其余迟到帧照旧丢弃（向量 late_frames_in_idle_are_dropped）。 */
    @Test
    fun `idle 下其余迟到的通话帧照旧丢弃`() {
        val out = IMCallMachine.reduce(
            IMCallContext(),
            IMMachineInput.Recv(IMFrameType.CALL_ACCEPTED, mapOf("call_id" to IMJson.Str("call-old"), "uid" to IMJson.Str("bob"))),
        )
        assertTrue(out.send.isEmpty())
        assertTrue(out.emit.isEmpty())
    }

    // ── 时长从本端进来算 ──────────────────────────────────────────────

    /** 中途被拉进群通话的人：时长从**他自己**进来算（2026-09-15 10:05 iOS frank 待了约 6 秒，写成 124 秒）。 */
    @Test
    fun `强制收场时长从本端开始时刻算，不从整通接通算`() {
        val base = inCall(IMCallState.CONNECTED, room = IMRoomState.JOINED)
        val ctx = base.copy(call = base.call.copy(connectedAtMs = 1_000), callStartedAtMs = 119_000)

        val out = IMEngineMachine.forceEnd(ctx, nowMs = 125_500)
        assertEquals(IMJson.Num(6), out.emit.first().args["duration_sec"])
    }

    @Test
    fun `没记到本端开始时刻：退回整通的 connected_at_ms`() {
        val base = inCall(IMCallState.CONNECTED, room = IMRoomState.JOINED)
        val ctx = base.copy(call = base.call.copy(connectedAtMs = 1_000))

        val out = IMEngineMachine.forceEnd(ctx, nowMs = 125_500)
        assertEquals(IMJson.Num(124), out.emit.first().args["duration_sec"])
    }

    /** 抛 onCallBegin 那一刻记下本端开始时刻，中间的推进不冲掉，通话结束清零。 */
    @Test
    fun `engine 在 onCallBegin 那一刻记下本端开始时刻，结束清零`() {
        val accepting = IMEngineContext(call = IMCallContext(state = IMCallState.ACCEPTING, callId = "c-1"))

        val began = IMEngineMachine.reduce(
            accepting,
            IMMachineInput.Recv(
                IMFrameType.CALL_CONNECTED,
                mapOf(
                    "call_id" to IMJson.Str("c-1"),
                    "room_id" to IMJson.Str("r-1"),
                    "room_token" to IMJson.Str("rt"),
                    "connected_at_ms" to IMJson.Num(1_000),
                ),
            ),
            nowMs = 119_000,
        )
        assertEquals(listOf("onCallBegin"), began.emit.map { it.callback })
        assertEquals(119_000L, began.state.callStartedAtMs)

        val later = IMEngineMachine.reduce(began.state, IMMachineInput.Internal("media_ready"), nowMs = 120_000)
        assertEquals("中间的推进不能冲掉开始时刻", 119_000L, later.state.callStartedAtMs)

        val ended = IMEngineMachine.reduce(
            later.state,
            IMMachineInput.Recv(
                IMFrameType.CALL_ENDED,
                mapOf("call_id" to IMJson.Str("c-1"), "reason" to IMJson.Str("hangup"), "duration_sec" to IMJson.Num(5)),
            ),
            nowMs = 125_000,
        )
        assertEquals(IMCallState.IDLE, ended.state.call.state)
        assertEquals(0L, ended.state.callStartedAtMs)
    }

    // ── 拨出中没 call_id 时取消 ───────────────────────────────────────

    /** 没有 call_id 的 cancel 只会换回 1401（Web 真机 2026-09-15 10:09）：先挂起，invite.ok 回来立刻补发。 */
    @Test
    fun `拨出中没 call_id 时取消：不发帧不报错，invite ok 回来立刻补发`() {
        val inviting = IMCallContext(state = IMCallState.INVITING, role = IMCallRole.CALLER)

        val pressed = IMCallMachine.reduce(inviting, IMMachineInput.Act("cancel", emptyMap()))
        assertTrue(pressed.send.isEmpty())
        assertTrue("不许本地拒成 2005", pressed.emit.isEmpty())
        assertTrue(pressed.state.cancelPending)

        val landed = IMCallMachine.reduce(
            pressed.state,
            IMMachineInput.Recv("call.invite.ok", mapOf("call_id" to IMJson.Str("c-8"), "room_id" to IMJson.Str("r-8"))),
        )
        assertEquals(listOf(IMFrameType.CALL_CANCEL), landed.send.map { it.type })
        assertEquals("c-8", str(landed.send.first().data, "call_id"))
        assertEquals(IMCallState.INVITING, landed.state.state)
        assertEquals("c-8", landed.state.callId)
        assertTrue("补发过就清掉", !landed.state.cancelPending)
    }

    @Test
    fun `已有 call_id 时取消照旧立刻发 cancel`() {
        val out = IMCallMachine.reduce(
            IMCallContext(state = IMCallState.INVITING, callId = "c-1"),
            IMMachineInput.Act("cancel", emptyMap()),
        )
        assertEquals(listOf(IMFrameType.CALL_CANCEL), out.send.map { it.type })
        assertEquals("c-1", str(out.send.first().data, "call_id"))
        assertTrue(!out.state.cancelPending)
    }

    @Test
    fun `没按取消时 invite ok 照常只记 call_id`() {
        val out = IMCallMachine.reduce(
            IMCallContext(state = IMCallState.INVITING),
            IMMachineInput.Recv("call.invite.ok", mapOf("call_id" to IMJson.Str("c-2"), "room_id" to IMJson.Str("r-2"))),
        )
        assertTrue(out.send.isEmpty())
        assertEquals("c-2", out.state.callId)
    }
}
