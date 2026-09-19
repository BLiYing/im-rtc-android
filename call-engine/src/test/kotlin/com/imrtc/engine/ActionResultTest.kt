package com.imrtc.engine

import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMEnvelope
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.FakeScheduler
import com.imrtc.engine.signaling.FakeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **调用结果回给调用方**（server `docs/design/ACTION_RESULT_DESIGN.md`，2.0.0）。与 Web `test/actionResult.test.ts` 同一张表。
 *
 * 每个发起类方法四格：成功 / 本地拒绝 2005 / 服务端拒绝 / 等应答时断线 2003——
 * 每格断言**结果恰好一次**、失败时**没有**多发带同一 forType 的 `onError`（R3）。
 * 另有：不传回调退回 `onError`（R7）、连锁帧走 `onError`（R2）、退出类本地收场（D2）、状态事件先于结果（R4）。
 */
class ActionResultTest {

    private val scheduler = FakeScheduler()
    private val transport = FakeTransport()
    private val listener = Recorder()
    private val engine = IMCallEngine.forTest(
        IMCallEngine.Config(url = "ws://test/rtc", deviceId = "d-1"),
        listener,
        QuietMedia(),
        scheduler,
        transport,
    )

    /** 一次调用收到的全部结果。恰好一次的断言就看它的长度。 */
    private class Results<T> : IMResultCallback<T> {
        val got = mutableListOf<Pair<T?, IMRTCError?>>()
        var log: MutableList<String>? = null

        override fun onResult(value: T?, error: IMRTCError?) {
            got += value to error
            log?.add("result:${error?.code ?: "ok"}")
        }

        fun single(): Pair<T?, IMRTCError?> {
            assertEquals("结果必须恰好一次", 1, got.size)
            return got[0]
        }
    }

    // ── 把 engine 推到某个状态 ─────────────────────────────────────

    private fun login() {
        engine.login("tk")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to s("s-1"), "uid" to s("alice")))
    }

    private fun ringing() = transport.deliver(
        IMFrameType.CALL_INCOMING,
        "",
        mapOf("call_id" to s("c-1"), "room_id" to s("r-1"), "caller" to s("bob"), "media_type" to s("audio"), "is_group" to IMJson.Bool(true)),
    )

    private fun inviting() {
        engine.call(listOf("bob"), "audio")
        transport.replyOk(IMFrameType.CALL_INVITE, mapOf("call_id" to s("c-1"), "room_id" to s("r-1")))
    }

    private fun inCall() {
        ringing()
        engine.accept()
        transport.replyOk(IMFrameType.CALL_ACCEPT)
        connectedAndJoined()
    }

    private fun connectedAndJoined() {
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to s("c-1"), "room_id" to s("r-1"), "room_token" to s("tk-room"),
                "media_type" to s("audio"), "is_group" to IMJson.Bool(true), "accepted_by" to s("alice"),
            ),
        )
    }

    private fun inMeeting() {
        engine.joinRoom("m-1", "tk")
        transport.replyOk(IMFrameType.ROOM_JOIN, mapOf("room_id" to s("m-1"), "participant_id" to s("p-1")))
    }

    /** 会议里把自动发布的那条音频回成功，让麦克风有 track_id。 */
    private fun inMeetingWithAudio() {
        inMeeting()
        val audio = transport.sent.last { it.type == IMFrameType.ROOM_PUBLISH && it.data.text("kind") == "audio" }
        reply(audio, mapOf("cid" to s(audio.data.text("cid")), "track_id" to s("t-mic")))
    }

    private fun reply(request: IMEnvelope, data: Map<String, IMJson>) =
        transport.deliver(request.type + IMEnvelope.OK_SUFFIX, request.reqId, data)

    // ── 四格表 ─────────────────────────────────────────────────

    private class Case(
        val name: String,
        val ready: ActionResultTest.() -> Unit,
        /** 状态机会就地拒掉的前置状态；null = 这个方法没有本地拒绝的状态。 */
        val wrongState: (ActionResultTest.() -> Unit)?,
        val frame: String,
        val invoke: IMCallEngine.(Results<Any>) -> Unit,
        val okData: Map<String, IMJson> = emptyMap(),
        val value: Any? = Unit,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> Results<Any>.cast() = this as IMResultCallback<T>

    private val cases = listOf(
        Case("call", {}, { ringing() }, IMFrameType.CALL_INVITE, { call(listOf("bob"), "audio", onResult = it.cast()) },
            mapOf("call_id" to s("c-9"), "room_id" to s("r-9")), "c-9"),
        Case("joinCall", {}, { ringing() }, IMFrameType.CALL_JOIN, { joinCall("c-9", it.cast()) }),
        Case("accept", { ringing() }, {}, IMFrameType.CALL_ACCEPT, { accept(it.cast()) }),
        Case("reject", { ringing() }, {}, IMFrameType.CALL_REJECT, { reject(it.cast()) }),
        Case("cancel", { inviting() }, {}, IMFrameType.CALL_CANCEL, { cancel(it.cast()) }),
        Case("hangup", { inCall() }, {}, IMFrameType.CALL_HANGUP, { hangup(it.cast()) }),
        Case("inviteMore", { inCall() }, {}, IMFrameType.CALL_INVITE_MORE, { inviteMore(listOf("carol"), it.cast()) }),
        Case("joinRoom", {}, { inMeeting() }, IMFrameType.ROOM_JOIN, { joinRoom("m-2", "tk", onResult = it.cast()) },
            mapOf("room_id" to s("m-2"), "participant_id" to s("p-2"))),
        Case("leaveRoom", { inMeeting() }, {}, IMFrameType.ROOM_LEAVE, { leaveRoom(it.cast()) }),
        Case("openMicrophone", { inMeetingWithAudio() }, null, IMFrameType.ROOM_MUTE, { openMicrophone(it.cast()) }),
    )

    private fun eachCase(block: ActionResultTest.(Case) -> Unit) {
        for (case in cases) {
            val fresh = ActionResultTest()
            fresh.login()
            try {
                fresh.block(case)
            } catch (e: AssertionError) {
                throw AssertionError("${case.name}：${e.message}", e)
            }
        }
    }

    @Test
    fun `成功：直接那一帧收到 ok 就回结果`() = eachCase { case ->
        case.ready(this)
        val results = Results<Any>()
        case.invoke(engine, results)
        val before = transport.countOf(case.frame)
        assertTrue("应当发出 ${case.frame}", before > 0)
        assertTrue("应答回来之前不该有结果", results.got.isEmpty())
        transport.replyOk(case.frame, case.okData)
        val (value, error) = results.single()
        assertNull(error)
        assertEquals(case.value, value)
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun `本地拒绝：回 2005，不发帧、不发 onError`() = eachCase { case ->
        val wrong = case.wrongState ?: return@eachCase
        wrong(this)
        val before = transport.countOf(case.frame)
        val results = Results<Any>()
        case.invoke(engine, results)
        assertEquals(2005, results.single().second?.code)
        assertEquals(before, transport.countOf(case.frame))
        assertTrue("本地拒绝不该多发 onError：${listener.errors}", listener.errors.isEmpty())
    }

    @Test
    fun `服务端拒绝：回那个码并带 forType，不发 onError`() = eachCase { case ->
        case.ready(this)
        val results = Results<Any>()
        case.invoke(engine, results)
        transport.replyError(case.frame, 1501, "internal")
        val error = results.single().second
        assertEquals(1501, error?.code)
        assertEquals(case.frame, error?.forType)
        assertTrue("已经交给调用方的错误不再发 onError", listener.errors.isEmpty())
    }

    @Test
    fun `等应答时断线：回 2003 并带 forType，不发同一 forType 的 onError`() = eachCase { case ->
        case.ready(this)
        val results = Results<Any>()
        case.invoke(engine, results)
        transport.closed(1006)
        val error = results.single().second
        assertEquals(2003, error?.code)
        assertEquals(case.frame, error?.forType)
        assertTrue(listener.errors.none { it.endsWith(":${case.frame}") })
    }

    // ── R7 / R2 / D2 / R4 ─────────────────────────────────────────

    @Test
    fun `不传回调：失败退回 onError 并带 forType`() {
        login()
        ringing()
        engine.accept()
        transport.replyError(IMFrameType.CALL_ACCEPT, 1402, "call_ended")
        assertEquals(listOf("1402:call.accept"), listener.errors)
    }

    @Test
    fun `连锁帧失败找不到调用方：accept 已成功，随后的 room join 被拒走 onError`() {
        login()
        ringing()
        val results = Results<Unit>()
        engine.accept(results)
        transport.replyOk(IMFrameType.CALL_ACCEPT)
        assertNull(results.single().second)
        connectedAndJoined()
        transport.replyError(IMFrameType.ROOM_JOIN, 1201, "room_not_found")
        assertEquals(listOf("1201:room.join"), listener.errors)
        assertEquals(1, results.got.size)
    }

    @Test
    fun `退出类被拒：本地照样收场，onCallEnd 先于结果到`() {
        login()
        inCall()
        val results = Results<Unit>().also { it.log = listener.log }
        engine.hangup(results)
        transport.replyError(IMFrameType.CALL_HANGUP, 1402, "call_ended")
        assertEquals(1402, results.single().second?.code)
        assertEquals(listOf("callEnd:hangup", "result:1402"), listener.log.filter { it.startsWith("callEnd") || it.startsWith("result") })
        assertTrue(listener.errors.isEmpty())
        // 服务端随后的 call.ended 不再抛第二次。
        transport.deliver(IMFrameType.CALL_ENDED, "", mapOf("call_id" to s("c-1"), "reason" to s("hangup")))
        assertEquals(1, listener.log.count { it.startsWith("callEnd") })
    }

    @Test
    fun `离房等应答时断线：回 2003，onRoomLeft 照发`() {
        login()
        inMeeting()
        val results = Results<Unit>()
        engine.leaveRoom(results)
        transport.closed(1006)
        assertEquals(2003, results.single().second?.code)
        assertTrue(listener.log.contains("roomLeft:m-1"))
    }

    @Test
    fun `名单里有自己：先 onCallEnd(error)，再回 1004，不上线路`() {
        login()
        val results = Results<String>().also { it.log = listener.log }
        engine.call(listOf("alice"), "audio", onResult = results)
        val error = results.single().second
        assertEquals(1004, error?.code)
        assertEquals(IMFrameType.CALL_INVITE, error?.forType)
        assertEquals(0, transport.countOf(IMFrameType.CALL_INVITE))
        assertEquals(listOf("callEnd:error", "result:1004"), listener.log.filter { it.startsWith("callEnd") || it.startsWith("result") })
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun `加人名单里有自己：回 1004，不发 callEnd`() {
        login()
        inCall()
        val results = Results<Unit>()
        engine.inviteMore(listOf("alice", "dave"), results)
        assertEquals(1004, results.single().second?.code)
        assertTrue(listener.log.none { it.startsWith("callEnd") })
    }

    @Test
    fun `进房中打开麦克风：没有直接帧，立即成功`() {
        login()
        engine.joinRoom("m-1", "tk")
        val results = Results<Unit>()
        engine.openMicrophone(results)
        assertNull(results.single().second)
    }

    // ── login ────────────────────────────────────────────────────

    @Test
    fun `login：握手成功回成功；连着再 login 回 2005`() {
        val first = Results<Unit>()
        engine.login("tk", first)
        transport.open()
        assertTrue(first.got.isEmpty())
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to s("s-1")))
        assertNull(first.single().second)

        val again = Results<Unit>()
        engine.login("tk", again)
        assertEquals(2005, again.single().second?.code)
    }

    @Test
    fun `login：握手被拒回那个码，不发 onError；连不上回 2003`() {
        val rejected = Results<Unit>()
        engine.login("tk", rejected)
        transport.open()
        transport.replyError(IMFrameType.HELLO, 1004, "bad_params")
        transport.closed(0)
        assertEquals(1004, rejected.single().second?.code)
        assertTrue(listener.errors.isEmpty())

        val other = ActionResultTest()
        val unreachable = Results<Unit>()
        other.engine.login("tk", unreachable)
        other.transport.failure()
        assertEquals(2003, unreachable.single().second?.code)
    }

    @Test
    fun `login：结论出来之前 logout 回 2007`() {
        val results = Results<Unit>()
        engine.login("tk", results)
        engine.logout()
        assertEquals(2007, results.single().second?.code)
    }

    // ── destroy（R6）───────────────────────────────────────────────

    @Test
    fun `destroy 之后：发起类与本地设备类一律 2005，清理与提示类空操作`() {
        login()
        inCall()
        engine.destroy()
        val sentBefore = transport.sent.size
        val errorsBefore = listener.errors.size

        val initiating: Map<String, (Results<Any>) -> Unit> = mapOf(
            "login" to { engine.login("tk", it.cast()) },
            "call" to { engine.call(listOf("bob"), "audio", onResult = it.cast()) },
            "callWithOptions" to { engine.call(listOf("bob"), "audio", IMCallOptions(), it.cast()) },
            "joinCall" to { engine.joinCall("c-1", it.cast()) },
            "accept" to { engine.accept(it.cast()) },
            "reject" to { engine.reject(it.cast()) },
            "cancel" to { engine.cancel(it.cast()) },
            "hangup" to { engine.hangup(it.cast()) },
            "inviteMore" to { engine.inviteMore(listOf("dave"), it.cast()) },
            "joinRoom" to { engine.joinRoom("m", "tk", onResult = it.cast()) },
            "leaveRoom" to { engine.leaveRoom(it.cast()) },
            "openMicrophone" to { engine.openMicrophone(it.cast()) },
            "openCamera" to { engine.openCamera(it.cast()) },
            "switchCamera" to { engine.switchCamera(it.cast()) },
            "startLocalPreview" to { assertEquals("", engine.startLocalPreview(it.cast())) },
        )
        for ((name, call) in initiating) {
            val results = Results<Any>()
            call(results)
            assertEquals("$name 应当回 2005", 2005, results.single().second?.code)
        }

        engine.logout()
        engine.forceEnd()
        engine.closeMicrophone()
        engine.closeCamera()
        engine.stopLocalPreview()
        engine.attachView("bob", null)
        engine.attachLocalView("cam", null)
        engine.setRemoteLayer("bob", "l")
        engine.setSpeakerOn(true)
        engine.setAppForeground(false)
        engine.notifyNetworkChanged()
        engine.updateToken("tk2")
        engine.destroy()
        assertEquals("销毁后清理类不许再发帧（forceEnd 读到旧状态直发结束帧的竞态）", sentBefore, transport.sent.size)
        assertEquals("清理类不报错", errorsBefore, listener.errors.size)
    }

    @Test
    fun `调度器没收下这次调用：当场回 2005`() {
        login()
        scheduler.rejectPosts = true
        val results = Results<Unit>()
        engine.hangup(results)
        assertEquals(2005, results.single().second?.code)
    }

    // ── 通话记录：没登录 / 销毁后走结果回调，不发请求 ──────────────────

    @Test
    fun `fetchCallHistory 没登录以 2007 结束`() {
        var error: IMRTCError? = null
        engine.fetchCallHistory { _, e -> error = e }
        assertEquals(2007, error?.code)
    }

    @Test
    fun `fetchCallHistory 销毁后以 2005 结束`() {
        engine.destroy()
        var error: IMRTCError? = null
        engine.fetchCallHistory { _, e -> error = e }
        assertEquals(2005, error?.code)
    }

    // ── 替身 ─────────────────────────────────────────────────────

    private class Recorder : IMCallEngineListener {
        /** `code:forType`。 */
        val errors = mutableListOf<String>()
        val log = mutableListOf<String>()

        override fun onError(code: Int, name: String, message: String, forType: String) {
            errors += "$code:$forType"
            log += "error:$code"
        }

        override fun onCallEnd(callId: String, reason: IMCallEndReason, durationSec: Long, endedBy: String) {
            log += "callEnd:${reason.wire}"
        }

        override fun onRoomLeft(roomId: String) {
            log += "roomLeft:$roomId"
        }
    }

    private class QuietMedia : IMMediaAdapter {
        override fun attachEvents(events: IMMediaAdapter.Events) = Unit
        override fun start(iceServers: List<String>) = Unit
        override fun stop() = Unit
        override fun publish(cid: String, kind: String, simulcast: Boolean) = Unit
        override fun unpublish(cid: String) = Unit
        override fun setMuted(kind: String, muted: Boolean) = Unit
        override fun createOffer(pc: String) = Unit
        override fun restartPubICE() = Unit
        override fun applyRemoteSdp(pc: String, type: String, sdp: String) = Unit
        override fun applyRemoteCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) = Unit
        override fun createVideoView(context: android.content.Context): android.view.View? = null
        override fun attachView(uid: String, view: Any?) = Unit
        override fun claimRemoteTracks(owners: Map<String, String>) = Unit
        override fun startLocalPreview(cid: String) = Unit
        override fun switchCamera() = Unit
        override fun setSpeakerOn(on: Boolean) = Unit
    }

    private fun s(value: String) = IMJson.Str(value)
}
