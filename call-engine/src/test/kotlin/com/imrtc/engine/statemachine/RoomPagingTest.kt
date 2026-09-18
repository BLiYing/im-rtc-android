package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会议房按页订阅（`MEETING_ROOM_DESIGN.md` §4.3）。
 *
 * 一致性向量（`room_fsm.json` 的 `meeting_audio_auto_video_by_page` 与
 * `call_room_auto_subscribe_all_layer_only`）钉的是**正常翻页那一条线**，
 * 这里补的是向量表达不了的两件事：**16 路上限**与**翻回来撤掉迟滞**。
 */
class RoomPagingTest {

    private fun meeting(videoCount: Int): IMRoomContext = IMRoomContext(
        state = IMRoomState.JOINED,
        roomId = "r-m",
        didJoin = true,
        autoSubscribe = "audio",
        remoteTracks = (1..videoCount).associate { i ->
            "t-$i" to IMRemoteTrack("u$i", "video", "p-$i")
        },
    )

    private fun layer(ctx: IMRoomContext, trackId: String, maxLayer: String) = IMRoomMachine.reduce(
        ctx,
        IMMachineInput.Act(
            "update_layer",
            mapOf("track_id" to IMJson.Str(trackId), "max_layer" to IMJson.Str(maxLayer)),
        ),
    )

    /** 把前 count 条视频都订上并坐实（模拟一页一页翻过来）。 */
    private fun subscribeAll(start: IMRoomContext, count: Int): IMRoomContext {
        var ctx = start
        for (i in 1..count) ctx = layer(ctx, "t-$i", "l").state
        return ctx.copy(subscribe = ctx.subscribe.mapValues { IMSubscribeState.SUBSCRIBED })
    }

    @Test
    fun `翻走先报 none 停包，五秒后才退订`() {
        val ctx = subscribeAll(meeting(3), 1)

        val out = layer(ctx, "t-1", "none")
        assertEquals("这一步只停包，不退订", listOf(IMFrameType.ROOM_UPDATE_LAYER), out.send.map { it.type })
        assertEquals(listOf("t-1"), out.state.pendingUnsubscribe)
        assertEquals("订阅关系还在", IMSubscribeState.SUBSCRIBED, out.state.subscribe["t-1"])

        val elapsed = IMRoomMachine.reduce(
            out.state,
            IMMachineInput.Internal("unsubscribe_hysteresis_elapsed", mapOf("track_id" to IMJson.Str("t-1"))),
        )
        assertEquals(listOf(IMFrameType.ROOM_UNSUBSCRIBE), elapsed.send.map { it.type })
        assertEquals(IMSubscribeState.UNSUBSCRIBING, elapsed.state.subscribe["t-1"])
        assertTrue(elapsed.state.pendingUnsubscribe.isEmpty())
    }

    @Test
    fun `五秒内翻回来只换层，不重协商，计时也撤掉`() {
        val ctx = subscribeAll(meeting(3), 1)
        val out = layer(layer(ctx, "t-1", "none").state, "t-1", "l")

        assertEquals(
            "翻回来不该再订一次——那就是一次白白的重协商",
            listOf(IMFrameType.ROOM_UPDATE_LAYER),
            out.send.map { it.type },
        )
        assertTrue("计时要撤掉", out.state.pendingUnsubscribe.isEmpty())

        // 计时撤掉之后，那条内部事件迟到了也不许退订。
        val late = IMRoomMachine.reduce(out.state, IMMachineInput.Internal("unsubscribe_hysteresis_elapsed"))
        assertTrue(late.send.isEmpty())
        assertEquals(IMSubscribeState.SUBSCRIBED, late.state.subscribe["t-1"])
    }

    @Test
    fun `重复报 none 不会再发一遍，也不会把五秒重新拉长`() {
        val ctx = subscribeAll(meeting(3), 1)
        val twice = layer(layer(ctx, "t-1", "none").state, "t-1", "none")
        assertTrue(twice.send.isEmpty())
        assertEquals(listOf("t-1"), twice.state.pendingUnsubscribe)
    }

    @Test
    fun `没订过的人报 none 不发任何帧`() {
        val out = layer(meeting(3), "t-2", "none")
        assertTrue(out.send.isEmpty())
        assertTrue(out.state.pendingUnsubscribe.isEmpty())
    }

    @Test
    fun `订满 16 路时提前退掉最早翻走的那一条`() {
        var ctx = subscribeAll(meeting(MAX_SUBSCRIBED_VIDEO + 1), MAX_SUBSCRIBED_VIDEO)
        // 翻走两条（t-1 比 t-2 早），它们都还在五秒迟滞里占着 m-line。
        ctx = layer(ctx, "t-1", "none").state
        ctx = layer(ctx, "t-2", "none").state
        assertEquals(listOf("t-1", "t-2"), ctx.pendingUnsubscribe)

        val out = layer(ctx, "t-${MAX_SUBSCRIBED_VIDEO + 1}", "l")
        assertEquals(
            "先退最早翻走的那一条，再订新的",
            listOf(IMFrameType.ROOM_UNSUBSCRIBE, IMFrameType.ROOM_SUBSCRIBE),
            out.send.map { it.type },
        )
        assertEquals(IMJson.Str("t-1"), out.send.first().data["track_id"])
        assertEquals("t-2 还在迟滞里，没被牵连", listOf("t-2"), out.state.pendingUnsubscribe)
        assertNull(out.reject)
    }

    @Test
    fun `一条都腾不出来时本地拒绝，不排队`() {
        // 16 路全订着且一条都没翻走：这只可能是界面一次要看超过 16 路。
        val ctx = subscribeAll(meeting(MAX_SUBSCRIBED_VIDEO + 1), MAX_SUBSCRIBED_VIDEO)
        val out = layer(ctx, "t-${MAX_SUBSCRIBED_VIDEO + 1}", "l")

        assertTrue("本地拒绝不许带帧", out.send.isEmpty())
        assertEquals(IMErrorCode.INVALID_STATE, out.reject)
        assertNull(out.state.subscribe["t-${MAX_SUBSCRIBED_VIDEO + 1}"])
    }

    @Test
    fun `人走了，排着的退订跟着摘掉`() {
        val ctx = layer(subscribeAll(meeting(3), 1), "t-1", "none").state
        val left = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Recv(
                IMFrameType.ROOM_PARTICIPANT_LEFT,
                mapOf(
                    "room_id" to IMJson.Str("r-m"),
                    "participant_id" to IMJson.Str("p-1"),
                    "uid" to IMJson.Str("u1"),
                    "device_id" to IMJson.Str("d1"),
                ),
            ),
        )
        assertTrue(left.state.pendingUnsubscribe.isEmpty())
        assertNull(left.state.subscribe["t-1"])
    }

    // ── 通话房的护栏：分页那套逻辑一点都不许漏进来 ────────────────────

    private fun callRoom(): IMRoomContext = IMRoomContext(
        state = IMRoomState.JOINED,
        roomId = "r-c",
        didJoin = true,
        autoSubscribe = "all",
        remoteTracks = mapOf("t-1" to IMRemoteTrack("bob", "video", "p-1")),
        subscribe = mapOf("t-1" to IMSubscribeState.SUBSCRIBED),
    )

    @Test
    fun `通话房报 none 只换层，绝不退订`() {
        val out = layer(callRoom(), "t-1", "none")
        assertEquals(listOf(IMFrameType.ROOM_UPDATE_LAYER), out.send.map { it.type })
        assertTrue(out.state.pendingUnsubscribe.isEmpty())
        assertEquals(
            "通话房退订会让那个人的画面再也回不来",
            IMSubscribeState.SUBSCRIBED,
            out.state.subscribe["t-1"],
        )
    }

    @Test
    fun `通话房收到迟滞事件什么都不做`() {
        val out = IMRoomMachine.reduce(callRoom(), IMMachineInput.Internal("unsubscribe_hysteresis_elapsed"))
        assertTrue(out.send.isEmpty())
        assertEquals(IMSubscribeState.SUBSCRIBED, out.state.subscribe["t-1"])
    }

    @Test
    fun `认不出的 auto_subscribe 兜底成 all 而不是 none`() {
        val out = IMRoomMachine.reduce(
            IMRoomContext(),
            IMMachineInput.Act(
                "join",
                mapOf(
                    "room_id" to IMJson.Str("r-1"),
                    "room_token" to IMJson.Str("tk"),
                    "auto_subscribe" to IMJson.Str("video"),
                ),
            ),
        )
        assertEquals("all", out.state.autoSubscribe)
        assertEquals(IMJson.Str("all"), out.send.first().data["auto_subscribe"])
    }

    @Test
    fun `audio 档只自动订音频`() {
        val ctx = IMRoomContext(state = IMRoomState.JOINING, autoSubscribe = "audio")
        val joined = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Recv(
                "room.join.ok",
                mapOf(
                    "room_id" to IMJson.Str("r-m"),
                    "room_kind" to IMJson.Str("meeting"),
                    "participant_id" to IMJson.Str("p-9"),
                    "participants" to IMJson.Arr(emptyList()),
                    "tracks" to IMJson.Arr(
                        listOf(
                            track("t-a1", "audio"),
                            track("t-v1", "video"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("音频由服务端自动订上", IMSubscribeState.SUBSCRIBING, joined.state.subscribe["t-a1"])
        assertNull("视频要等界面报「看得见」才订", joined.state.subscribe["t-v1"])
    }

    private fun track(trackId: String, kind: String) = IMJson.Obj(
        linkedMapOf(
            "track_id" to IMJson.Str(trackId),
            "participant_id" to IMJson.Str("p-1"),
            "uid" to IMJson.Str("bob"),
            "kind" to IMJson.Str(kind),
            "source" to IMJson.Str(if (kind == "audio") "microphone" else "camera"),
            "codec" to IMJson.Str(if (kind == "audio") "opus" else "vp8"),
            "simulcast_layers" to IMJson.Arr(emptyList()),
            "muted" to IMJson.Bool(false),
        ),
    )

    // ── 评审补的三条边界 ────────────────────────────────────────

    @Test
    fun `断网重连期间迟滞到点也不发退订，清单留着等回来`() {
        val paged = layer(subscribeAll(meeting(3), 2), "t-1", "none").state
        assertEquals(listOf("t-1"), paged.pendingUnsubscribe)

        val cut = IMRoomMachine.reduce(paged, IMMachineInput.Internal("disconnected", emptyMap()))
        assertEquals(IMRoomState.RECONNECTING, cut.state.state)

        val fired = IMRoomMachine.reduce(
            cut.state,
            IMMachineInput.Internal(
                "unsubscribe_hysteresis_elapsed",
                mapOf("track_id" to IMJson.Str("t-1")),
            ),
        )
        // 一帧都不许发：退订帧没有回滚路径，扔进死连接会让这条 track 永远卡在 UNSUBSCRIBING。
        assertTrue(fired.send.isEmpty())
        assertEquals(IMSubscribeState.SUBSCRIBED, fired.state.subscribe["t-1"])
        // 还在清单上，定时器会重新排一只，等回到 joined 再退。
        assertEquals(listOf("t-1"), fired.state.pendingUnsubscribe)
    }

    @Test
    fun `订阅被拒时连带把待退订摘掉`() {
        // 订上 → 翻走排退订 → 这时订阅的 reject 才回来（两件事各走各的，顺序能排到）。
        val ctx = layer(meeting(2), "t-1", "l").state.copy(pendingUnsubscribe = listOf("t-1"))
        val out = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Internal("subscribe_failed", mapOf("track_id" to IMJson.Str("t-1"))),
        )
        assertNull(out.state.subscribe["t-1"])
        // 不摘的话五秒后那条 unsubscribe 会打在空处——人要是翻回来了，退掉的是刚订上的那一路。
        assertTrue(out.state.pendingUnsubscribe.isEmpty())
    }

    @Test
    fun `已经在退订中的不再排一次迟滞`() {
        val ctx = subscribeAll(meeting(2), 1).let {
            it.copy(subscribe = it.subscribe + ("t-1" to IMSubscribeState.UNSUBSCRIBING))
        }
        val out = layer(ctx, "t-1", "none")
        assertTrue(out.send.isEmpty())
        assertTrue(out.state.pendingUnsubscribe.isEmpty())
    }
}
