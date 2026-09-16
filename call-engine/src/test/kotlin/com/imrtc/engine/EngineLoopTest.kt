package com.imrtc.engine

import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.FakeScheduler
import com.imrtc.engine.signaling.FakeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门面这条核心循环：**宿主调方法 → 状态机 → 发帧 → 应答回喂 → 回调抛给宿主**。
 *
 * 用假传输 + 假时钟 + 假媒体跑完整通电话，纯 JVM。这里验的是**接线**——
 * 状态机本身由一致性向量守着，这份测试守的是「线有没有接错」：
 * 应答回没回喂给状态机、被拒了有没有退回 idle、媒体有没有在该起的时候起。
 */
class EngineLoopTest {

    private val scheduler = FakeScheduler()
    private val transport = FakeTransport()
    private val listener = RecordingListener()
    private val media = FakeMedia()

    private val engine = IMCallEngine.forTest(
        IMCallEngine.Config(url = "ws://test/rtc", deviceId = "d-1"),
        listener,
        media,
        scheduler,
        transport,
    )

    private fun loginAndConnect() {
        engine.login("tk-1")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
    }

    /** 会议房：**直接 joinRoom，压根不经过 call**。真机上出问题的就是这条路。 */
    private fun joinConferenceRoom() {
        engine.joinRoom("r-1", "tk-room")
        transport.replyOk(
            IMFrameType.ROOM_JOIN,
            mapOf("room_id" to IMJson.Str("r-1"), "participant_id" to IMJson.Str("p-1")),
        )
    }

    @Test
    fun `主叫全程：拨出到接通再挂断`() {
        loginAndConnect()
        assertEquals(listOf("s-1"), listener.connected)

        engine.call(listOf("bob"), "video")
        val invite = transport.lastOf(IMFrameType.CALL_INVITE) ?: error("没发 call.invite")
        assertEquals("video", (invite.data["media_type"] as IMJson.Str).value)

        transport.replyOk(
            IMFrameType.CALL_INVITE,
            mapOf("call_id" to IMJson.Str("call-1"), "room_id" to IMJson.Str("r-1")),
        )
        transport.deliver(IMFrameType.CALL_RINGING, "", mapOf("uid" to IMJson.Str("bob")))
        transport.deliver(IMFrameType.CALL_ACCEPTED, "", mapOf("uid" to IMJson.Str("bob")))
        assertEquals(listOf("bob"), listener.userAccepts)

        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "room_id" to IMJson.Str("r-1"),
                "room_token" to IMJson.Str("tk-room"),
                "media_type" to IMJson.Str("video"),
                "connected_at_ms" to IMJson.Num(1_000),
                "accepted_by" to IMJson.Str("bob"),
            ),
        )
        // onCallBegin 抛在进入 connecting 那一刻，同时房间机被驱动去发 room.join
        assertEquals(listOf("call-1"), listener.callBegins)
        assertTrue("拿到 room_token 就该把媒体拉起来", media.started)
        val join = transport.lastOf(IMFrameType.ROOM_JOIN) ?: error("没发 room.join")
        assertEquals("r-1", (join.data["room_id"] as IMJson.Str).value)
        // 发送侧的默认值陷阱：auto_subscribe 必须是 true，不能因为「没写」变成 false
        assertEquals(IMJson.Bool(true), join.data["auto_subscribe"])

        transport.replyOk(
            IMFrameType.ROOM_JOIN,
            mapOf("room_id" to IMJson.Str("r-1"), "participant_id" to IMJson.Str("p-1")),
        )
        assertEquals(listOf("r-1"), listener.roomJoins)
        // 进房就自动发布：宿主什么都不做也该能通话，「进了房没人推流」不是合理默认
        assertEquals(listOf("audio", "video"), media.published)
        assertEquals(2, transport.countOf(IMFrameType.ROOM_PUBLISH))

        engine.hangup()
        assertTrue(transport.lastOf(IMFrameType.CALL_HANGUP) != null)
        transport.deliver(
            IMFrameType.CALL_ENDED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "reason" to IMJson.Str("hangup"),
                "duration_sec" to IMJson.Num(42),
                "ended_by" to IMJson.Str("alice"),
            ),
        )
        assertEquals(listOf("hangup:42"), listener.callEnds)
        assertTrue("通话结束要停媒体", media.stopped)
    }

    @Test
    fun `被叫：来电、接听、结束`() {
        loginAndConnect()
        transport.deliver(
            IMFrameType.CALL_INCOMING,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-9"),
                "room_id" to IMJson.Str("r-9"),
                "caller" to IMJson.Str("alice"),
                "media_type" to IMJson.Str("audio"),
            ),
        )
        assertEquals(listOf("call-9 from alice"), listener.incoming)

        engine.accept()
        assertTrue(transport.lastOf(IMFrameType.CALL_ACCEPT) != null)
        // 第二次 accept 必须**本地**拦下，不能发上去让服务端回 1405
        val acceptsSoFar = transport.countOf(IMFrameType.CALL_ACCEPT)
        engine.accept()
        assertEquals(acceptsSoFar, transport.countOf(IMFrameType.CALL_ACCEPT))
        assertTrue("本地拒绝要抛 2005", listener.errors.any { it == 2005 })
    }

    @Test
    fun `带选项拨群通话：chat_group_id、user_data、timeout_sec 原样上线路`() {
        loginAndConnect()
        engine.call(
            listOf("bob", "carol"),
            "audio",
            IMCallOptions(isGroup = true, chatGroupId = "g-42", userData = "{\"n\":1}", timeoutSec = 45),
        )
        val invite = transport.lastOf(IMFrameType.CALL_INVITE) ?: error("没发 call.invite")
        assertEquals(IMJson.Str("g-42"), invite.data["chat_group_id"])
        assertEquals(IMJson.Str("{\"n\":1}"), invite.data["user_data"])
        assertEquals(IMJson.Num(45), invite.data["timeout_sec"])
        assertEquals(IMJson.Bool(true), invite.data["is_group"])

        transport.replyOk(
            IMFrameType.CALL_INVITE,
            mapOf("call_id" to IMJson.Str("call-1"), "room_id" to IMJson.Str("r-1")),
        )
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "room_id" to IMJson.Str("r-1"),
                "room_token" to IMJson.Str("tk"),
                "media_type" to IMJson.Str("audio"),
                "is_group" to IMJson.Bool(true),
                "connected_at_ms" to IMJson.Num(1_000),
                "accepted_by" to IMJson.Str("bob"),
                "caller" to IMJson.Str("alice"),
                "chat_group_id" to IMJson.Str("g-42"),
                "user_data" to IMJson.Str("{\"n\":1}"),
            ),
        )
        assertEquals(listOf("alice", "g-42", "{\"n\":1}", true), listener.lastBeginGroupData)
    }

    @Test
    fun `带选项拨的老服务端不回群号时，onCallBegin 回落到 call() 选项记下的值`() {
        loginAndConnect()
        engine.call(listOf("bob"), "audio", IMCallOptions(chatGroupId = "g-9", userData = "u-9"))
        transport.replyOk(
            IMFrameType.CALL_INVITE,
            mapOf("call_id" to IMJson.Str("call-1"), "room_id" to IMJson.Str("r-1")),
        )
        // 老服务端：call.connected 压根没有 caller / chat_group_id / user_data 三个键。
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "room_id" to IMJson.Str("r-1"),
                "room_token" to IMJson.Str("tk"),
                "media_type" to IMJson.Str("audio"),
                "connected_at_ms" to IMJson.Num(1_000),
                "accepted_by" to IMJson.Str("bob"),
            ),
        )
        assertEquals(listOf("", "g-9", "u-9", false), listener.lastBeginGroupData)
    }

    @Test
    fun `被叫的 onCallReceived 带群号，接通后回显同一个值`() {
        loginAndConnect()
        transport.deliver(
            IMFrameType.CALL_INCOMING,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-9"),
                "room_id" to IMJson.Str("r-9"),
                "caller" to IMJson.Str("alice"),
                "media_type" to IMJson.Str("audio"),
                "is_group" to IMJson.Bool(true),
                "chat_group_id" to IMJson.Str("g-5"),
                "user_data" to IMJson.Str("u-5"),
            ),
        )
        assertEquals("g-5" to "u-5", listener.lastIncomingGroupData)

        engine.accept()
        transport.replyOk(IMFrameType.CALL_ACCEPT, emptyMap())
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-9"),
                "room_id" to IMJson.Str("r-9"),
                "room_token" to IMJson.Str("tk"),
                "media_type" to IMJson.Str("audio"),
                "connected_at_ms" to IMJson.Num(1_000),
                "accepted_by" to IMJson.Str("bob"),
            ),
        )
        // call.connected 没带这三个字段（老服务端）：回落到 call.incoming 记下的值。
        assertEquals(listOf("alice", "g-5", "u-5", true), listener.lastBeginGroupData)
    }

    @Test
    fun `onCallReceived 带 inviter：群通话中途加你进来的人不是发起人`() {
        loginAndConnect()
        transport.deliver(
            IMFrameType.CALL_INCOMING,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-10"),
                "room_id" to IMJson.Str("r-10"),
                "caller" to IMJson.Str("alice"),
                // 群通话中途被 bob 加进来：发起人仍是 alice，邀请你的是 bob。
                "inviter" to IMJson.Str("bob"),
                "media_type" to IMJson.Str("audio"),
                "is_group" to IMJson.Bool(true),
            ),
        )
        assertEquals("bob", listener.lastIncomingInviter)
    }

    @Test
    fun `旧服务端不带 inviter 时回落到 caller`() {
        loginAndConnect()
        transport.deliver(
            IMFrameType.CALL_INCOMING,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-11"),
                "room_id" to IMJson.Str("r-11"),
                "caller" to IMJson.Str("alice"),
                "media_type" to IMJson.Str("audio"),
                "is_group" to IMJson.Bool(true),
            ),
        )
        assertEquals("alice", listener.lastIncomingInviter)
    }

    @Test
    fun `call() 选项本地校验不过：onError(1004) + onCallEnd(error)，不上线路`() {
        loginAndConnect()
        val before = transport.countOf(IMFrameType.CALL_INVITE)
        engine.call(listOf("bob"), "audio", IMCallOptions(chatGroupId = "has space"))
        assertEquals("不该发出 call.invite", before, transport.countOf(IMFrameType.CALL_INVITE))
        assertTrue("本地校验不过要抛 1004", listener.errors.any { it == 1004 })
        assertEquals(listOf("error:0"), listener.callEnds)

        // 校验失败之后状态机仍是 idle，能正常再拨一次。
        engine.call(listOf("bob"), "audio")
        assertEquals(before + 1, transport.countOf(IMFrameType.CALL_INVITE))
    }

    @Test
    fun `呼叫被服务端拒了要退回 idle，而不是卡在 inviting`() {
        loginAndConnect()
        engine.call(listOf("self"), "audio")
        transport.replyError(IMFrameType.CALL_INVITE, 1004, "bad_params", "callee 里有自己")

        // 抛 onCallEnd 收场（界面需要一个明确的结束信号），并且状态回 idle：
        // 不回 idle 的话之后每次挂断都发向一个不存在的 call，永远退不出去。
        assertEquals(listOf("error:0"), listener.callEnds)
        engine.call(listOf("bob"), "audio")
        assertEquals("退回 idle 之后应该能再次拨出", 2, transport.countOf(IMFrameType.CALL_INVITE))
    }

    @Test
    fun `进房被拒要退回 idle 并抛 onRoomLeft`() {
        loginAndConnect()
        engine.joinRoom("r-1", "tk-room")
        transport.replyError(IMFrameType.ROOM_JOIN, 1201, "room_not_found", "房间没了")

        assertEquals(listOf("r-1"), listener.roomLeaves)
        engine.joinRoom("r-2", "tk-room")
        assertEquals("退回 idle 之后应该能再进别的房间", 2, transport.countOf(IMFrameType.ROOM_JOIN))
    }

    @Test
    fun `重连没恢复：房间归零并本地合成 onCallEnd`() {
        loginAndConnect()
        engine.call(listOf("bob"), "audio")
        transport.replyOk(
            IMFrameType.CALL_INVITE,
            mapOf("call_id" to IMJson.Str("c-1"), "room_id" to IMJson.Str("r-1")),
        )
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("c-1"),
                "room_id" to IMJson.Str("r-1"),
                "room_token" to IMJson.Str("tk"),
                "connected_at_ms" to IMJson.Num(scheduler.nowMs()),
            ),
        )
        listener.callEnds.clear()

        transport.closed(1006, "network")
        scheduler.advance(5_000)
        transport.open()
        // resumed=false：服务端那边的会话已经过期，那条 call.ended 送不到我们手里了
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf("session_id" to IMJson.Str("s-2"), "resumed" to IMJson.Bool(false)),
        )
        assertTrue("必须本地合成一条 onCallEnd(network)", listener.callEnds.any { it.startsWith("network:") })
    }

    /*
      **网络一直不回来时也要收场。**

      上面那条走的是「重连上了但 resumed=false」——它要求先连回来。
      真机 2026-09-08：iOS carol 断网后不接网，那一刻永远不会到，
      于是界面永远停在「正在重连」，**连挂断都点不动**（挂断只产出一帧发不出去的
      call.hangup，本地状态一动不动，这是 §4.2 铁律 1 的直接后果）。

      所以断开超过恢复窗口的上界之后，客户端自己收场，判据与服务端算的是同一笔账。
    */
    @Test
    fun `断开一直连不上：超过恢复窗口也要本地收场`() {
        loginAndConnect()
        engine.call(listOf("bob"), "audio")
        transport.replyOk(
            IMFrameType.CALL_INVITE,
            mapOf("call_id" to IMJson.Str("c-1"), "room_id" to IMJson.Str("r-1")),
        )
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("c-1"),
                "room_id" to IMJson.Str("r-1"),
                "room_token" to IMJson.Str("tk"),
                "connected_at_ms" to IMJson.Num(scheduler.nowMs()),
            ),
        )
        listener.callEnds.clear()

        transport.closed(1006, "network")
        // 网络一直不回来：每次重连都失败，一次 hello.ok 都没有。
        repeat(20) {
            scheduler.advance(5_000)
            transport.failure(RuntimeException("连不上"))
        }

        assertTrue(
            "网络不回来就永远不收场，界面停在「正在重连」、挂断也点不动",
            listener.callEnds.any { it.startsWith("network:") },
        )
    }

    /**
     * 会议房离房**走的是两步**：`joined →(leave)→ leaving →(leave.ok)→ idle`。
     *
     * 第一版的判据是「before=joined 且 after=idle」，这条两步路一步都不满足，
     * 于是 `stop()` 一次都没调过——PeerConnection 活着继续重采候选，
     * 服务端每 5 分钟回两条 `1203 not_in_room`（真机日志刷了 50 分钟）。
     */
    @Test
    fun `会议房离房要停媒体，哪怕它是分两步走完的`() {
        loginAndConnect()
        joinConferenceRoom()
        assertTrue("进房要把媒体拉起来", media.started)

        engine.leaveRoom()
        assertFalse("leave.ok 还没回来，媒体不该停", media.stopped)

        transport.replyOk(IMFrameType.ROOM_LEAVE)
        assertEquals(listOf("r-1"), listener.roomLeaves)
        assertTrue("离房走完必须停媒体", media.stopped)
    }

    @Test
    fun `离房之后再来的本端候选不产生任何上行帧`() {
        loginAndConnect()
        joinConferenceRoom()

        // 在房里：候选照发，不然媒体根本连不上。
        media.fireLocalCandidate("pub")
        assertEquals(1, transport.countOf(IMFrameType.ROOM_ICE_CANDIDATE))

        engine.leaveRoom()
        transport.replyOk(IMFrameType.ROOM_LEAVE)
        val framesAfterLeave = transport.sent.size

        // 离房之后 native 侧还会冒（GATHER_CONTINUALLY，每 5 分钟一轮）：一条都不许上行。
        media.fireLocalCandidate("pub")
        media.fireLocalCandidate("sub")
        assertEquals(
            "离房之后的候选必须在出口被丢掉，发上去只会换回 1203 not_in_room",
            1,
            transport.countOf(IMFrameType.ROOM_ICE_CANDIDATE),
        )
        // 更强的一条：这两次候选**一帧上行都不该产生**，不只是「不产生候选帧」。
        assertEquals("离房之后不该再有任何上行帧", framesAfterLeave, transport.sent.size)
    }

    /**
     * `room.leave` 被拒是真事：服务端在「会话已不在房间里」时回 1203
     * （两人同时离房、或房间刚被「已空，已关闭」销毁，都撞得上）。
     *
     * 被拒的语义恰恰是**我们已经不在房里了**，所以本地必须照样收场。
     * 不接这一条的话房间永久停在 leaving：媒体停不掉（摄像头与前台服务一直开着），
     * 之后 join 还会因为「不在 idle」被本地拒——这台 Engine 再也进不了房。
     */
    @Test
    fun `离房被服务端拒了也要退回 idle，而不是卡在 leaving`() {
        loginAndConnect()
        joinConferenceRoom()

        engine.leaveRoom()
        transport.replyError(IMFrameType.ROOM_LEAVE, 1203, "not_in_room", "会话不在任何房间里")

        assertEquals("界面需要一个明确的收场信号", listOf("r-1"), listener.roomLeaves)
        assertTrue("离房被拒同样要停媒体，否则摄像头一直开着", media.stopped)

        // 退回 idle 之后才进得了下一个房间。
        engine.joinRoom("r-2", "tk-room")
        assertEquals("退回 idle 之后应该能再进别的房间", 2, transport.countOf(IMFrameType.ROOM_JOIN))

        // 而且照样不许再冒候选上去。
        val framesAfterLeave = transport.sent.size
        media.fireLocalCandidate("pub")
        assertEquals(framesAfterLeave, transport.sent.size)
    }

    /**
     * **重连恢复不是新进房**。`resumed=true` 时房间机把 reconnecting 推回 joined，
     * 若把它也当成刚进房，每恢复一次就重复发一整套 audio+video——
     * 多两条 `room.publish`，真机上还会在旧 capturer 没停的情况下再开一个摄像头采集。
     */
    @Test
    fun `重连恢复不该重复发布本端 Track`() {
        loginAndConnect()
        joinConferenceRoom()
        assertEquals(listOf("audio", "video"), media.published)
        val publishesAfterJoin = transport.countOf(IMFrameType.ROOM_PUBLISH)

        transport.closed(1006, "network")
        scheduler.advance(5_000)
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf("session_id" to IMJson.Str("s-1"), "resumed" to IMJson.Bool(true)),
        )

        assertEquals("恢复不是新进房，不该再发一套", listOf("audio", "video"), media.published)
        assertEquals(
            "恢复的前提就是服务端那边的发布关系还在，一条 room.publish 都不该补",
            publishesAfterJoin,
            transport.countOf(IMFrameType.ROOM_PUBLISH),
        )
        // 恢复之后房间还是 joined，候选照发——别把这一道守卫连带堵死了。
        media.fireLocalCandidate("pub")
        assertEquals(1, transport.countOf(IMFrameType.ROOM_ICE_CANDIDATE))
    }

    /**
     * **会话恢复之后必须重新协商上行**（协议 §1.4：客户端的 pub PC 若已失效则重发
     * `room.offer{pc:"pub"}`）。
     *
     * 这条不能只挂在「PC 判 FAILED 的那一刻」：网一断信令也跟着断，房间立刻变成
     * reconnecting，而 PC 要等约 30 秒才判 FAILED——那时 `restart_pub_ice` 会被房间机
     * 本地拒掉，且它不进 BUFFERABLE_OPS，于是永远丢失。iOS 真机 2026-09-07 抓到过
     * `动作被状态机本地拒绝 op=restart_pub_ice room_state=reconnecting`，
     * ICE 自愈在它唯一该生效的场景里等于不存在。
     */
    @Test
    fun `会话恢复之后要重新协商上行`() {
        loginAndConnect()
        joinConferenceRoom()
        val offersAfterJoin = media.offersAsked.size
        val restartsAfterJoin = media.pubIceRestarts

        transport.closed(1006, "network")
        scheduler.advance(5_000)
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf("session_id" to IMJson.Str("s-1"), "resumed" to IMJson.Bool(true)),
        )

        assertEquals(
            "恢复后要让下一个上行 offer 带上 ICE restart",
            restartsAfterJoin + 1,
            media.pubIceRestarts,
        )
        // 光置位不发帧等于没做。Android 的 room.offer 是「先向媒体层现取 SDP、
        // 异步回来才真发」，所以这里断言的是**引擎确实去要了一个 pub offer**。
        assertEquals(offersAfterJoin + 1, media.offersAsked.size)
        assertEquals("pub", media.offersAsked.last())
    }

    /** 没恢复成功就不该重协商：那时房间已归零，发上去只会换回 1203。 */
    @Test
    fun `恢复失败不重新协商上行`() {
        loginAndConnect()
        joinConferenceRoom()
        val offersAfterJoin = media.offersAsked.size

        transport.closed(1006, "network")
        scheduler.advance(5_000)
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf("session_id" to IMJson.Str("s-9"), "resumed" to IMJson.Bool(false)),
        )

        assertEquals(0, media.pubIceRestarts)
        assertEquals(offersAfterJoin, media.offersAsked.size)
    }

    /**
     * 对端开摄像头之后，要让媒体层**等新画面真正上屏**再报首帧（`awaitFirstVideoFrame`）。
     *
     * 渲染器整通复用，`init` 后的首帧只来一次；少了这一步，界面只能按 `room.track_muted` 揭示格子，
     * 而信令比新画面早几百毫秒——露出来的是关摄像头之前的最后一帧（Android 真机 2026-09-11 19:17）。
     */
    @Test
    fun `对端开摄像头要等新画面上屏，关摄像头和开关麦克风不等`() {
        loginAndConnect()
        joinConferenceRoom()
        fun trackMuted(kind: String, muted: Boolean) = transport.deliver(
            IMFrameType.ROOM_TRACK_MUTED,
            "",
            mapOf(
                "room_id" to IMJson.Str("r-1"),
                "track_id" to IMJson.Str("t-$kind"),
                "participant_id" to IMJson.Str("p-2"),
                "uid" to IMJson.Str("carol"),
                "kind" to IMJson.Str(kind),
                "muted" to IMJson.Bool(muted),
            ),
        )

        trackMuted("video", true)
        trackMuted("audio", false)
        assertEquals(emptyList<String>(), media.awaitedFirstFrames)

        trackMuted("video", false)
        assertEquals(listOf("carol"), media.awaitedFirstFrames)
    }

    @Test
    fun `没有媒体适配器时，推流失败但信令一切正常`() {
        val bare = IMCallEngine.forTest(
            IMCallEngine.Config("ws://test/rtc", "d-2"),
            listener,
            null,
            scheduler,
            transport,
        )
        bare.login("tk")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
        bare.attachView("bob", null)
        assertTrue("没有媒体时挂画面应当报 2005", listener.errors.contains(2005))
        // 但信令还是通的
        bare.call(listOf("bob"), "audio")
        assertTrue(transport.lastOf(IMFrameType.CALL_INVITE) != null)
    }

    /**
     * 红键看门狗到点 → `forceEnd()`。复现 2026-09-13 14:53 iOS frank 那一刻的形状：
     * `call.connected` 到了、`room.join` 发出去还没回，这时强制收场。
     *
     * 结束帧不等 join 回来就发出去；本地收场只抛一次 onCallEnd、媒体停掉；
     * 迟到的 join.ok 不认领、补发 room.leave；迟到的候选不进媒体层；服务端随后的 call.ended 不再抛。
     */
    @Test
    fun `join 在飞时强制收场：挂断立刻发出，迟到的进房被退回去`() {
        loginAndConnect()
        transport.deliver(
            IMFrameType.CALL_INCOMING,
            "",
            mapOf(
                "call_id" to IMJson.Str("c-1"),
                "room_id" to IMJson.Str("r-1"),
                "caller" to IMJson.Str("bob"),
                "media_type" to IMJson.Str("video"),
                "is_group" to IMJson.Bool(true),
            ),
        )
        engine.accept()
        transport.replyOk(IMFrameType.CALL_ACCEPT)
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("c-1"),
                "room_id" to IMJson.Str("r-1"),
                "room_token" to IMJson.Str("tk-room"),
                "media_type" to IMJson.Str("video"),
                "connected_at_ms" to IMJson.Num(scheduler.nowMs()),
            ),
        )
        val join = transport.lastOf(IMFrameType.ROOM_JOIN) ?: error("没发 room.join")

        engine.forceEnd()

        val hangup = transport.lastOf(IMFrameType.CALL_HANGUP) ?: error("强制收场没发 call.hangup")
        assertEquals("c-1", (hangup.data["call_id"] as IMJson.Str).value)
        assertEquals(listOf("hangup:0"), listener.callEnds)
        assertTrue("摄像头、麦克风要跟着停", media.stopped)

        // 迟到的 join.ok：服务端已经放他进房了，得退出来，而且不许抛 onRoomJoined。
        transport.deliver(
            IMFrameType.ROOM_JOIN + ".ok",
            join.reqId,
            mapOf("room_id" to IMJson.Str("r-1"), "participant_id" to IMJson.Str("p-6")),
        )
        val leave = transport.lastOf(IMFrameType.ROOM_LEAVE) ?: error("迟到的 join.ok 没补发 room.leave")
        assertEquals("r-1", (leave.data["room_id"] as IMJson.Str).value)
        transport.replyOk(IMFrameType.ROOM_LEAVE)
        assertTrue(listener.roomJoins.isEmpty())
        assertTrue("补发的 leave 是善后，不是宿主要知道的离房", listener.roomLeaves.isEmpty())

        transport.deliver(
            IMFrameType.ROOM_ICE_CANDIDATE,
            "",
            mapOf("pc" to IMJson.Str("sub"), "candidate" to IMJson.Str("candidate:1 1 udp 1 10.0.0.9 7881 typ host")),
        )
        assertEquals("迟到的候选不许交给媒体层", 0, media.remoteCandidates)
        transport.deliver(
            IMFrameType.CALL_ENDED,
            "",
            mapOf("call_id" to IMJson.Str("c-1"), "reason" to IMJson.Str("hangup"), "duration_sec" to IMJson.Num(3)),
        )
        assertEquals("服务端那条 call.ended 不能再抛一次", listOf("hangup:0"), listener.callEnds)
    }

    /** 拨出时 invite 还在路上就强制收场：本地立刻收场，invite.ok 回来后补发 cancel。 */
    @Test
    fun `invite 在飞时强制收场：本地先收，call id 回来后补发 cancel`() {
        loginAndConnect()
        engine.call(listOf("bob"), "video", isGroup = true)

        engine.forceEnd()
        assertEquals("此刻没有 call_id，发不了 cancel", 0, transport.countOf(IMFrameType.CALL_CANCEL))
        assertEquals(listOf("cancel:0"), listener.callEnds)

        transport.replyOk(
            IMFrameType.CALL_INVITE,
            mapOf("call_id" to IMJson.Str("c-7"), "room_id" to IMJson.Str("r-7")),
        )
        val cancel = transport.lastOf(IMFrameType.CALL_CANCEL) ?: error("invite.ok 回来了却没补发 cancel")
        assertEquals("c-7", (cancel.data["call_id"] as IMJson.Str).value)
        assertEquals("本地早就收过场了，不能再抛一次", listOf("cancel:0"), listener.callEnds)
    }

    /** 拨出中还没拿到 call_id 就按取消：此刻不发帧、不报错；invite.ok 回来立刻补发带 call_id 的 cancel。 */
    @Test
    fun `拨出中没 call id 时取消：先挂起，invite ok 回来立刻补发`() {
        loginAndConnect()
        engine.call(listOf("bob"), "video", isGroup = true)

        engine.cancel()
        assertEquals("没有 call_id 的 cancel 只会换回 1401", 0, transport.countOf(IMFrameType.CALL_CANCEL))
        assertTrue("不许本地拒成 2005", listener.errors.isEmpty())

        transport.replyOk(
            IMFrameType.CALL_INVITE,
            mapOf("call_id" to IMJson.Str("c-8"), "room_id" to IMJson.Str("r-8")),
        )
        val cancel = transport.lastOf(IMFrameType.CALL_CANCEL) ?: error("invite.ok 回来了却没补发 cancel")
        assertEquals("c-8", (cancel.data["call_id"] as IMJson.Str).value)
        assertTrue(listener.errors.isEmpty())
    }

    /*
      静默失败审计 §A：`onRequestFailed` 原先只认 call.invite / call.accept / call.join /
      room.join / room.leave 这张表，`room.publish` 被拒之后不回滚——那条轨道永远停在
      publishing，publish.ok 不来，pub offer 永不产出，对方全程听不见看不见、零提示。
      2026-09-16 拍板：**通话里被拒直接结束本端通话**（reason=error）；没有通话的会议房
      只摘掉那一条。参考实现：Web `frameLoop.ts` 的 `rollback` 表。
    */

    @Test
    fun `通话中 room publish 被拒：原错误码照报，发 hangup，onCallEnd 只抛一次且 reason 为 error`() {
        loginAndConnect()
        engine.call(listOf("bob"), "video")
        transport.replyOk(
            IMFrameType.CALL_INVITE,
            mapOf("call_id" to IMJson.Str("call-1"), "room_id" to IMJson.Str("r-1")),
        )
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "room_id" to IMJson.Str("r-1"),
                "room_token" to IMJson.Str("tk-room"),
                "media_type" to IMJson.Str("video"),
                "connected_at_ms" to IMJson.Num(1_000),
                "accepted_by" to IMJson.Str("bob"),
            ),
        )
        transport.replyOk(
            IMFrameType.ROOM_JOIN,
            mapOf("room_id" to IMJson.Str("r-1"), "participant_id" to IMJson.Str("p-1")),
        )
        assertEquals("进房自动发布 audio+video", 2, transport.countOf(IMFrameType.ROOM_PUBLISH))

        transport.replyError(IMFrameType.ROOM_PUBLISH, 1302, "publish_denied", "同一路重复发布")

        assertTrue("原错误码要照报，不是被吞掉", listener.errors.contains(1302))
        val hangup = transport.lastOf(IMFrameType.CALL_HANGUP) ?: error("发布被拒没有发 call.hangup")
        assertEquals("对端还在等，要告诉服务端我走了", "call-1", (hangup.data["call_id"] as IMJson.Str).value)
        assertEquals("不能留在一通对方听不见的通话里，只抛一次", listOf("error:0"), listener.callEnds)
        assertTrue("通话收了要停媒体", media.stopped)

        // 服务端随后那条 call.ended 不能再抛一次。
        transport.deliver(
            IMFrameType.CALL_ENDED,
            "",
            mapOf("call_id" to IMJson.Str("call-1"), "reason" to IMJson.Str("hangup"), "duration_sec" to IMJson.Num(3)),
        )
        assertEquals(listOf("error:0"), listener.callEnds)

        // 退回 idle 之后应该能再拨一次。
        engine.call(listOf("carol"), "audio")
        assertEquals(2, transport.countOf(IMFrameType.CALL_INVITE))
    }

    @Test
    fun `会议房 room publish 被拒：人还在房里，不抛 callEnd 也不抛 roomLeft`() {
        loginAndConnect()
        joinConferenceRoom()
        assertEquals(listOf("audio", "video"), media.published)
        assertEquals(2, transport.countOf(IMFrameType.ROOM_PUBLISH))

        transport.replyError(IMFrameType.ROOM_PUBLISH, 1302, "publish_denied", "同一路重复发布")

        assertTrue("原错误码要照报", listener.errors.contains(1302))
        assertEquals("不能收场", emptyList<String>(), listener.callEnds)
        assertEquals("不能离房", emptyList<String>(), listener.roomLeaves)
        assertFalse("媒体不该被停", media.stopped)

        // 还在 joined：leaveRoom 能正常发出 room.leave（不是被 R1 本地拒成 2005）。
        val errorsBefore = listener.errors.size
        engine.leaveRoom()
        assertTrue("还在房里，leave 应该正常发出", transport.lastOf(IMFrameType.ROOM_LEAVE) != null)
        assertEquals("不该多出本地拒绝的 2005", errorsBefore, listener.errors.size)
    }

    // ── 记录用的假实现 ────────────────────────────────────────────────

    /** internal 而不是 private：`MuteBeforePublishTest` 也要一个只收不看的 listener，
     *  28 个空方法抄第二遍纯属噪声。 */
    internal class RecordingListener : IMCallEngineListener {
        val connected = mutableListOf<String>()
        val incoming = mutableListOf<String>()
        val callBegins = mutableListOf<String>()
        val callEnds = mutableListOf<String>()
        val userAccepts = mutableListOf<String>()
        val roomJoins = mutableListOf<String>()
        val roomLeaves = mutableListOf<String>()
        val errors = mutableListOf<Int>()
        /** 最近一次 onCallReceived 带的 (chat_group_id, user_data)。 */
        var lastIncomingGroupData: Pair<String, String>? = null
        /** 最近一次 onCallReceived 带的 inviter。 */
        var lastIncomingInviter: String? = null
        /** 最近一次 onCallBegin 带的 (caller, chat_group_id, user_data, is_group)。 */
        var lastBeginGroupData: List<Any>? = null

        override fun onConnected(sessionId: String, resumed: Boolean) { connected += sessionId }
        override fun onCallReceived(
            callId: String,
            caller: String,
            inviter: String,
            calleeIds: List<String>,
            mediaType: String,
            isGroup: Boolean,
            chatGroupId: String,
            userData: String,
        ) {
            incoming += "$callId from $caller"
            lastIncomingGroupData = chatGroupId to userData
            lastIncomingInviter = inviter
        }
        override fun onCallBegin(
            callId: String,
            roomId: String,
            mediaType: String,
            isGroup: Boolean,
            role: String,
            caller: String,
            chatGroupId: String,
            userData: String,
        ) {
            callBegins += callId
            lastBeginGroupData = listOf(caller, chatGroupId, userData, isGroup)
        }
        override fun onCallEnd(callId: String, reason: IMCallEndReason, durationSec: Long, endedBy: String) {
            callEnds += "${reason.wire}:$durationSec"
        }
        override fun onUserAccept(uid: String) { userAccepts += uid }
        override fun onRoomJoined(roomId: String) { roomJoins += roomId }
        override fun onRoomLeft(roomId: String) { roomLeaves += roomId }
        override fun onError(code: Int, name: String, message: String) { errors += code }
    }

    private class FakeMedia : IMMediaAdapter {
        var started = false
        var stopped = false
        val published = mutableListOf<String>()

        /** 门面挂上来的媒体事件出口。测试拿它模拟 native 侧冒上来的候选。 */
        private var events: IMMediaAdapter.Events? = null

        /**
         * 模拟 libwebrtc 冒一个本端候选。
         *
         * 真机上这是 `PeerConnection.Observer.onIceCandidate`，跑在 native 的 signaling
         * 线程上、什么时候来不归我们管——**离房之后照样会来**（GATHER_CONTINUALLY）。
         */
        fun fireLocalCandidate(pc: String) =
            events?.onLocalCandidate(pc, "candidate:1 1 udp 2130706431 10.0.0.2 5000 typ host", "0", 0)

        override fun attachEvents(events: IMMediaAdapter.Events) {
            this.events = events
        }

        override fun start(iceServers: List<String>) { started = true }
        override fun stop() { stopped = true }
        override fun publish(cid: String, kind: String, simulcast: Boolean) { published += kind }
        override fun unpublish(cid: String) = Unit
        /** 记下每一次本端开关：`kind to muted`。**验静音必须看这个**——
         *  只看 `room.mute` 帧的话，「帧发了但本端轨道其实没关」正好被漏掉，
         *  而那恰恰是对端还听得见你的原因。 */
        val mutes = mutableListOf<Pair<String, Boolean>>()

        override fun setMuted(kind: String, muted: Boolean) {
            mutes += kind to muted
        }
        /** 状态机每决定「该协商了」，引擎就向这里现取一次 SDP。 */
        var offersAsked = mutableListOf<String>()
        override fun createOffer(pc: String) { offersAsked += pc }
        /** 被要求重启上行 ICE 的次数。 */
        var pubIceRestarts = 0
        override fun restartPubICE() { pubIceRestarts += 1 }
        override fun applyRemoteSdp(pc: String, type: String, sdp: String) = Unit
        /** 交到媒体层的远端候选条数。房间已经不在时一条都不该进来。 */
        var remoteCandidates = 0
        override fun applyRemoteCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
            remoteCandidates += 1
        }
        override fun createVideoView(context: android.content.Context): android.view.View? = null
        override fun attachView(uid: String, view: Any?) = Unit

        /** 最后一次收到的归属表：`track_id → uid`。 */
        var claimed: Map<String, String> = emptyMap()
        override fun claimRemoteTracks(owners: Map<String, String>) { claimed = owners }

        /** 被要求「等新画面上屏再报首帧」的 uid，按先后。 */
        val awaitedFirstFrames = mutableListOf<String>()
        override fun awaitFirstVideoFrame(uid: String) { awaitedFirstFrames += uid }
        override fun startLocalPreview(view: Any?) = Unit
        override fun switchCamera() = Unit
        override fun setSpeakerOn(on: Boolean) = Unit
    }
}
