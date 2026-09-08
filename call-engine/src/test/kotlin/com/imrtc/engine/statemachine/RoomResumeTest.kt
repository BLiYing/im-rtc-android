package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「恢复之后到底算不算在房里」——2026-09-08 那轮 code review 的回归。
 *
 * `disconnected` 会把**任何**非 IDLE 状态推进 RECONNECTING，JOINING 也在内。
 * 而从 JOINING 断的那一种，`room.join` 当时还在飞：服务端从没受理过我们。
 * 原先 [IMRoomMachine.resume] 无条件宣布 JOINED，于是本端以为自己在房里，
 * 之后每一帧都换回 1201/1203，而重新 join 又因为「不在 idle」被本地拒成 2005——
 * 一个哑掉的死局，日志里一条报错都没有。
 */
class RoomResumeTest {

    /** 从 JOINED 断的：服务端那边成员关系还在，恢复后直接回 JOINED，攒下的意图照旧重放。 */
    @Test
    fun `从 joined 断的，恢复后回 joined 并重放攒下的意图`() {
        var ctx = joined()
        ctx = IMRoomMachine.reduce(ctx, IMMachineInput.Internal("disconnected")).state
        assertEquals(IMRoomState.RECONNECTING, ctx.state)

        ctx = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Act("mute", mapOf("track_id" to IMJson.Str("t-7"), "muted" to IMJson.Bool(true))),
        ).state
        assertEquals("reconnecting 期间只攒不发（不变量 R2）", 1, ctx.buffered.size)

        val result = IMRoomMachine.resume(ctx, resumed = true)

        assertEquals(IMRoomState.JOINED, result.state.state)
        assertEquals(listOf(IMFrameType.ROOM_MUTE), result.send.map { it.type })
        assertTrue("重放完就该清空", result.state.buffered.isEmpty())
    }

    /**
     * **从 JOINING 断的：恢复后不许宣布 JOINED，要把那次没落地的进房重发一遍。**
     *
     * 这就是那个死局的现场。
     */
    @Test
    fun `从 joining 断的，恢复后重发 room join 而不是假装已经在房里`() {
        var ctx = IMRoomContext()
        ctx = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Act(
                "join",
                mapOf("room_id" to IMJson.Str("r-9"), "room_token" to IMJson.Str("rt-9")),
            ),
        ).state
        assertEquals(IMRoomState.JOINING, ctx.state)

        // 断线。join_failed 那条兜底指望不上——它 guard 在 JOINING 上，
        // 而 disconnected 已经把状态推走了（iOS 上就是这么翻车的）。
        ctx = IMRoomMachine.reduce(ctx, IMMachineInput.Internal("disconnected")).state
        ctx = IMRoomMachine.reduce(ctx, IMMachineInput.Internal("join_failed")).state
        assertEquals(IMRoomState.RECONNECTING, ctx.state)

        val result = IMRoomMachine.resume(ctx, resumed = true)

        assertEquals("那次进房从未落地，不能宣布 joined", IMRoomState.JOINING, result.state.state)
        assertEquals(listOf(IMFrameType.ROOM_JOIN), result.send.map { it.type })
        val join = result.send.single().data
        assertEquals("r-9", (join["room_id"] as IMJson.Str).value)
        assertEquals("房票要原样带上，不然重发也进不去", "rt-9", (join["room_token"] as IMJson.Str).value)
    }

    /** 重发那一轮，攒下的意图要留着等进房后重放——别顺手清掉。 */
    @Test
    fun `重发 room join 时攒下的意图原样留着`() {
        var ctx = IMRoomContext()
        ctx = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Act(
                "join",
                mapOf("room_id" to IMJson.Str("r-9"), "room_token" to IMJson.Str("rt-9")),
            ),
        ).state
        ctx = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Act(
                "publish",
                mapOf("cid" to IMJson.Str("cam-1"), "kind" to IMJson.Str("video")),
            ),
        ).state
        ctx = IMRoomMachine.reduce(ctx, IMMachineInput.Internal("disconnected")).state

        val result = IMRoomMachine.resume(ctx, resumed = true)

        assertEquals(1, result.state.buffered.size)
        assertEquals("publish", result.state.buffered.single().op)
    }

    /** 连房号都没有（join 的帧还没产出就断了）：没得重发，干净地回 IDLE。 */
    @Test
    fun `没有房号可重发时干净地回 idle`() {
        val ctx = IMRoomContext(state = IMRoomState.RECONNECTING, didJoin = false)
        val result = IMRoomMachine.resume(ctx, resumed = true)
        assertEquals(IMRoomState.IDLE, result.state.state)
        assertTrue(result.send.isEmpty())
    }

    /** `resumed=false` 照旧无条件归零（协议 §1.4），别被新分支挡掉。 */
    @Test
    fun `resumed 为 false 时照旧归零`() {
        val ctx = joined().copy(state = IMRoomState.RECONNECTING)
        val result = IMRoomMachine.resume(ctx, resumed = false)
        assertEquals(IMRoomState.IDLE, result.state.state)
        assertTrue(result.send.isEmpty())
    }

    /** `room.join.ok` 是记下 didJoin 的唯一地方。 */
    @Test
    fun `join ok 会记下 didJoin`() {
        var ctx = IMRoomContext()
        ctx = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Act(
                "join",
                mapOf("room_id" to IMJson.Str("r-9"), "room_token" to IMJson.Str("rt-9")),
            ),
        ).state
        assertTrue("还没进房，didJoin 应该是 false", !ctx.didJoin)

        ctx = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Recv(
                IMFrameType.ROOM_JOIN + ".ok",
                mapOf("room_id" to IMJson.Str("r-9"), "participant_id" to IMJson.Str("p-1")),
            ),
        ).state

        assertTrue("收到 join.ok 之后才算真的进过房", ctx.didJoin)
    }

    private fun joined() = IMRoomContext(
        state = IMRoomState.JOINED,
        didJoin = true,
        roomId = "r-1",
        roomToken = "rt-1",
    )
}
