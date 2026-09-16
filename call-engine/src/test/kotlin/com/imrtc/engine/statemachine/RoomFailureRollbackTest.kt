package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房间帧被拒的回滚：`publish_failed` / `subscribe_failed`（静默失败审计 §A）。**纯函数，纯 JVM。**
 *
 * 原先这张回滚表只认 `join_failed` / `leave_failed`，`room.publish` / `room.subscribe`
 * 被拒之后不摘记账：
 * - `publish[cid]` 永远停在 `publishing`——`publish.ok` 不会来，pub offer 永远产不出。
 * - `subscribe[trackId]` 永远停在 `subscribing`——不变量 R3 会把之后每次重订都当成
 *   「已经订过、只是换层」，只发 `room.update_layer`，**再也发不出 `room.subscribe`**。
 *   最常见的来路是 1301：订阅与对方的 `track_unpublished` 赛跑输了。
 *
 * 与 Web 的 `roomMachine.test.ts`「房间帧被拒的回滚」同名同义。
 * 通话中 `room.publish` 被拒要整通收场（reason=error）的那条路径不经过这里——
 * 见 `IMCallEngine` 门面的 `IMRequestFailures`，只有没有通话的会议房才落到这张回滚表。
 */
class RoomFailureRollbackTest {

    private val joined = IMRoomContext(state = IMRoomState.JOINED, didJoin = true, roomId = "r-1")

    @Test
    fun `subscribe_failed 摘掉 subscribing 与层记账，重订重新发 room subscribe`() {
        val subscribing = IMRoomMachine.reduce(
            joined,
            IMMachineInput.Act("subscribe", mapOf("track_id" to IMJson.Str("t-9"), "max_layer" to IMJson.Str("h"))),
        ).state
        assertEquals(IMSubscribeState.SUBSCRIBING, subscribing.subscribe["t-9"])
        assertEquals("h", subscribing.layers["t-9"])

        val rolled = IMRoomMachine.reduce(
            subscribing,
            IMMachineInput.Internal("subscribe_failed", mapOf("track_id" to IMJson.Str("t-9"))),
        )
        assertTrue("subscribing 那条要摘掉", rolled.state.subscribe["t-9"] == null)
        assertTrue("层记账也要跟着摘，不然幽灵层记账", rolled.state.layers["t-9"] == null)
        assertTrue("不额外抛回调", rolled.emit.isEmpty())

        // 摘掉之后重订必须重新发 room.subscribe，而不是被 R3 当成换层只发 update_layer。
        val again = IMRoomMachine.reduce(
            rolled.state,
            IMMachineInput.Act("subscribe", mapOf("track_id" to IMJson.Str("t-9"), "max_layer" to IMJson.Str("h"))),
        )
        assertEquals(listOf(IMFrameType.ROOM_SUBSCRIBE), again.send.map { it.type })
    }

    @Test
    fun `publish_failed 摘掉 publishing，重新发布同一 cid 能再发出 room publish`() {
        val publishing = IMRoomMachine.reduce(
            joined,
            IMMachineInput.Act(
                "publish",
                mapOf("cid" to IMJson.Str("cam-1"), "kind" to IMJson.Str("video"), "source" to IMJson.Str("camera")),
            ),
        ).state
        assertEquals(IMPublishState.PUBLISHING, publishing.publish["cam-1"])

        val rolled = IMRoomMachine.reduce(
            publishing,
            IMMachineInput.Internal("publish_failed", mapOf("cid" to IMJson.Str("cam-1"))),
        )
        assertTrue("不能永远停在 publishing", rolled.state.publish["cam-1"] == null)
        assertTrue("不额外抛回调，门面已经先抛过一次 onError", rolled.emit.isEmpty())

        val again = IMRoomMachine.reduce(
            rolled.state,
            IMMachineInput.Act(
                "publish",
                mapOf("cid" to IMJson.Str("cam-1"), "kind" to IMJson.Str("video"), "source" to IMJson.Str("camera")),
            ),
        )
        assertEquals(listOf(IMFrameType.ROOM_PUBLISH), again.send.map { it.type })
        assertEquals(IMPublishState.PUBLISHING, again.state.publish["cam-1"])
    }

    @Test
    fun `只动 publishing 或 subscribing 的那一条，已发布已订阅的不受影响`() {
        val ctx = joined.copy(
            publish = mapOf("c-1" to IMPublishState.PUBLISHED),
            subscribe = mapOf("t-1" to IMSubscribeState.SUBSCRIBED),
            layers = mapOf("t-1" to "h"),
        )

        val afterPublishFailed = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Internal("publish_failed", mapOf("cid" to IMJson.Str("c-1"))),
        ).state
        assertEquals("已发布的不是 publishing，不该被摘", mapOf("c-1" to IMPublishState.PUBLISHED), afterPublishFailed.publish)

        val afterSubscribeFailed = IMRoomMachine.reduce(
            ctx,
            IMMachineInput.Internal("subscribe_failed", mapOf("track_id" to IMJson.Str("t-1"))),
        ).state
        assertEquals(
            "已订阅的不是 subscribing，不该被摘",
            mapOf("t-1" to IMSubscribeState.SUBSCRIBED),
            afterSubscribeFailed.subscribe,
        )
        assertEquals("层记账也不该被误删", mapOf("t-1" to "h"), afterSubscribeFailed.layers)
    }

    @Test
    fun `不在 joined 时 publish_failed 或 subscribe_failed 原样返回`() {
        val idle = IMRoomContext()
        val out = IMRoomMachine.reduce(idle, IMMachineInput.Internal("publish_failed", mapOf("cid" to IMJson.Str("c-1"))))
        assertEquals(idle, out.state)
        assertTrue(out.emit.isEmpty())
    }
}
