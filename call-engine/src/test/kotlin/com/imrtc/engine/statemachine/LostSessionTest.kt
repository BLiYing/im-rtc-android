package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMEnvelope
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「服务端那侧的会话没了」之后，**宿主必须拿到一个收场信号**。
 *
 * 有 call 的场合一直有 `onCallEnd(network)` 兜着（不变量 I8），可**会议是直接 joinRoom 的、
 * 压根没有 call**：[IMRoomMachine.resume] 传 `resumed=false` 只是把房间清成 IDLE，
 * 一个事件都不抛。于是房间机悄悄回了 IDLE，而界面还显示着「会议中」、计时器还在走，
 * 用户完全不知道自己已经掉出去了；更糟的是一个结束类回调都没抛，门面的 leave 那组回调
 * 不命中 → `media.stop()` 永远不调用（**摄像头与前台服务一直开着**），
 * 上一轮的 PeerConnection 还会被带进下一次进房。
 *
 * **这一条三端同源**：Web 与 iOS 同日补上同一段（`dropLostSession`）。
 */
class LostSessionTest {

    /** 会议里重连回来发现会话没了：房间回 IDLE，**并且要抛 onRoomLeft**。 */
    @Test
    fun `会议里 resumed=false，房间回 idle 并抛 onRoomLeft`() {
        val result = IMEngineMachine.reduce(inMeeting(), helloOk(resumed = false), NOW)

        assertEquals(IMRoomState.IDLE, result.state.room.state)
        assertEquals(
            "会议没有 callEnd，onRoomLeft 是它唯一的收场信号",
            listOf("onConnected", "onRoomLeft"),
            result.emit.map { it.callback },
        )
        assertEquals(IMJson.Str("r-9"), result.emit.last().args["room_id"])
    }

    /** 断太久（`session_unrecoverable`）走同一条收场路径——差别只在不必等重连成功。 */
    @Test
    fun `会议里断太久，走同一条收场路径`() {
        val result = IMEngineMachine.reduce(
            inMeeting(),
            IMMachineInput.Internal("session_unrecoverable"),
            NOW,
        )

        assertEquals(IMRoomState.IDLE, result.state.room.state)
        assertEquals(listOf("onRoomLeft"), result.emit.map { it.callback })
    }

    /**
     * **有通话时不能补 onRoomLeft**：`onCallEnd` 是所有结束分支的唯一出口（设计 §7.5），
     * 为同一件事抛两个回调会让宿主的记账重复一次。
     * 这条也是一致性向量 `reconnect_not_resumed_synthesizes_call_end` 钉住的行为。
     */
    @Test
    fun `有通话时只抛 onCallEnd，不重复抛 onRoomLeft`() {
        val ctx = inMeeting().let {
            it.copy(call = it.call.copy(state = IMCallState.CONNECTED, callId = "call-1", connectedAtMs = NOW))
        }

        val result = IMEngineMachine.reduce(ctx, helloOk(resumed = false), NOW + 60_000)

        assertEquals(listOf("onConnected", "onCallEnd"), result.emit.map { it.callback })
        assertEquals(IMCallState.IDLE, result.state.call.state)
        assertEquals(IMRoomState.IDLE, result.state.room.state)
    }

    /** 本来就在 IDLE：只报连接，不凭空抛一条离房。 */
    @Test
    fun `本来就不在房里，不凭空抛离房`() {
        val result = IMEngineMachine.reduce(IMEngineContext(), helloOk(resumed = false), NOW)
        assertEquals(listOf("onConnected"), result.emit.map { it.callback })
    }

    /** `resumed=true` 这条路一个字都不该变：房间留着，不抛离房。 */
    @Test
    fun `resumed=true 时房间留着`() {
        val ctx = inMeeting().let { it.copy(room = it.room.copy(state = IMRoomState.RECONNECTING)) }

        val result = IMEngineMachine.reduce(ctx, helloOk(resumed = true), NOW)

        assertEquals(IMRoomState.JOINED, result.state.room.state)
        assertEquals(listOf("onConnected"), result.emit.map { it.callback })
    }

    private fun inMeeting() = IMEngineContext(
        room = IMRoomContext(
            state = IMRoomState.JOINED,
            didJoin = true,
            roomId = "r-9",
            roomToken = "rt-9",
        ),
    )

    private fun helloOk(resumed: Boolean) = IMMachineInput.Recv(
        IMEnvelope.okType(IMFrameType.HELLO),
        mapOf("session_id" to IMJson.Str("s-2"), "resumed" to IMJson.Bool(resumed)),
    )

    private companion object {
        const val NOW = 1_757_000_000_000L
    }
}
