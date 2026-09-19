package com.imrtc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通话记录：地址推导、请求拼装、分页「到底」判据、错误映射。 */
class CallHistoryTest {

    @Test
    fun `信令地址推出 REST 根`() {
        val cases = mapOf(
            "ws://127.0.0.1:8787/v1/ws" to "http://127.0.0.1:8787/",
            "wss://rtc.example.com/v1/ws" to "https://rtc.example.com/",
            "wss://rtc.example.com/gw/v1/ws?x=1" to "https://rtc.example.com/gw",
            "https://rtc.example.com/v1/ws" to "https://rtc.example.com/",
        )
        for ((input, want) in cases) assertEquals(input, want, IMCallHistory.restBase(input).toString())
        assertNull(IMCallHistory.restBase("ftp://h/v1/ws"))
    }

    @Test
    fun `请求带 limit 与 cursor`() {
        assertEquals("http://h:8787/v1/calls?limit=20", IMCallHistory.buildUrl("ws://h:8787/v1/ws", 20, null).toString())
        assertEquals(
            "http://h:8787/v1/calls?limit=20&cursor=1700000000000",
            IMCallHistory.buildUrl("ws://h:8787/v1/ws", 20, 1700000000000).toString(),
        )
        assertEquals("https://h/gw/v1/calls?limit=5", IMCallHistory.buildUrl("wss://h/gw/v1/ws", 5, null).toString())
    }

    private fun body(count: Int, next: Long?): String {
        val calls = (0 until count).joinToString(",") {
            """{"call_id":"c$it","caller":"alice","media_type":"video","is_group":false,"reason":"hangup","duration_sec":12,"started_at_ms":${1000 - it},"members":[{"uid":"bob","state":"joined"}]}"""
        }
        val cursor = next?.let { ""","next_cursor":$it""" } ?: ""
        return """{"calls":[$calls]$cursor}"""
    }

    @Test
    fun `满页交出游标 不满页就是到底`() {
        val full = IMCallHistory.parse(200, body(2, 999), 2).getOrThrow()
        assertEquals(2, full.records.size)
        assertEquals(999L, full.nextCursor)
        assertEquals("c0", full.records[0].callId)
        assertEquals(12, full.records[0].durationSec)
        assertEquals(listOf(IMCallHistoryMember("bob", "joined")), full.records[0].members)

        assertNull("不满一页不能再给游标", IMCallHistory.parse(200, body(1, 999), 2).getOrThrow().nextCursor)
        val empty = IMCallHistory.parse(200, """{"calls":[]}""", 2).getOrThrow()
        assertTrue(empty.records.isEmpty())
        assertNull(empty.nextCursor)
    }

    @Test
    fun `缺字段解成零值`() {
        val page = IMCallHistory.parse(200, """{"calls":[{"call_id":"c1"}]}""", 20).getOrThrow()
        assertEquals("c1", page.records[0].callId)
        assertEquals("", page.records[0].reason)
        assertEquals(0, page.records[0].durationSec)
        assertTrue(page.records[0].members.isEmpty())
    }

    private fun errorCode(status: Int, body: String) =
        (IMCallHistory.parse(status, body, 20).exceptionOrNull() as IMCallHistoryException).error.code

    @Test
    fun `状态码映射到错误码`() {
        assertEquals(1101, errorCode(401, ""))
        assertEquals(1501, errorCode(500, ""))
        assertEquals(1501, errorCode(200, "nope"))
    }
}
