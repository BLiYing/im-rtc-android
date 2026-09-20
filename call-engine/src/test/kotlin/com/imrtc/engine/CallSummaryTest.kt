package com.imrtc.engine

import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.FakeScheduler
import com.imrtc.engine.signaling.FakeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `onCallSummary`（通话记录设计 §4）：紧跟 `onCallEnd`、每通拿到 call_id 的电话恰好一次，
 * 字段取自**结束前**的通话上下文（结束后对端 / 群号 / 角色就清零了）。
 */
class CallSummaryTest {

    private val transport = FakeTransport()
    private val log = mutableListOf<String>()
    private val summaries = mutableListOf<IMCallSummary>()

    private val listener = object : IMCallEngineListener {
        override fun onCallEnd(callId: String, reason: IMCallEndReason, durationSec: Long, endedBy: String) {
            log += "end"
        }

        override fun onCallSummary(summary: IMCallSummary) {
            log += "summary"
            summaries += summary
        }
    }

    private val engine = IMCallEngine.forTest(
        IMCallEngine.Config(url = "ws://test/rtc", deviceId = "d-1"),
        listener,
        null,
        FakeScheduler(),
        transport,
    )

    private fun loginAs(uid: String) {
        engine.login("tk-1")
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf("session_id" to IMJson.Str("s-1"), "uid" to IMJson.Str(uid)),
        )
    }

    private fun ended(reason: String, duration: Long, by: String) = transport.deliver(
        IMFrameType.CALL_ENDED,
        "",
        mapOf(
            "call_id" to IMJson.Str("call-1"),
            "reason" to IMJson.Str(reason),
            "duration_sec" to IMJson.Num(duration),
            "ended_by" to IMJson.Str(by),
        ),
    )

    @Test
    fun `主叫 1v1 未接通：peer 是被叫，caller 回落自己，且只来一条`() {
        loginAs("alice")
        engine.call(listOf("bob"), "video")
        transport.replyOk(
            IMFrameType.CALL_INVITE,
            mapOf("call_id" to IMJson.Str("call-1"), "room_id" to IMJson.Str("r-1")),
        )
        ended("no_answer", 0, "")
        ended("no_answer", 0, "") // 迟到的重复帧：状态机已 idle，不能再来第二条

        assertEquals(listOf("end", "summary"), log)
        val s = summaries.single()
        assertEquals("call-1", s.callId)
        assertEquals(IMCallEndReason.NO_ANSWER, s.reason)
        assertEquals("video", s.mediaType)
        assertEquals("caller", s.role)
        assertEquals("bob", s.peer)
        assertEquals("alice", s.caller)
        assertTrue(!s.isGroup)
    }

    @Test
    fun `被叫群通话：role 是 callee，peer 为空，时长取服务端值`() {
        loginAs("alice")
        transport.deliver(
            IMFrameType.CALL_INCOMING,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "room_id" to IMJson.Str("r-1"),
                "caller" to IMJson.Str("bob"),
                "media_type" to IMJson.Str("audio"),
                "is_group" to IMJson.Bool(true),
                "chat_group_id" to IMJson.Str("g-1"),
                "user_data" to IMJson.Str("u"),
            ),
        )
        ended("hangup", 42, "bob")

        val s = summaries.single()
        assertEquals("callee", s.role)
        assertEquals("bob", s.caller)
        assertEquals("", s.peer)
        assertTrue(s.isGroup)
        assertEquals("g-1", s.chatGroupId)
        assertEquals("u", s.userData)
        assertEquals(42L, s.durationSec)
        assertEquals("bob", s.endedBy)
    }

    @Test
    fun `invite 还没拿到 call_id 就收场：不产生 summary`() {
        loginAs("alice")
        engine.call(listOf("bob"), "audio")
        transport.replyError(IMFrameType.CALL_INVITE, 1004, "bad_params")
        assertTrue(summaries.isEmpty())
    }
}
