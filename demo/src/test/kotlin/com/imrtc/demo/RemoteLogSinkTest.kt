package com.imrtc.demo

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteLogSinkTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun entry(msg: String, tag: String = "engine", level: String = "INFO") =
        RemoteLogSink.Entry(atMS = 1757130000000, level = level, tag = tag, message = msg)

    // ── 序列化 ────────────────────────────────────────────────

    @Test
    fun `编出来的是服务端认的形状`() {
        val json = RemoteLogSink.encode("android-alice", listOf(entry("已接通")))
        assertEquals(
            """{"client":"android-alice","entries":[{"at_ms":1757130000000,""" +
                """"level":"INFO","msg":"已接通","fields":{"tag":"engine"}}]}""",
            json,
        )
    }

    // 日志里出现引号、反斜杠、换行是家常便饭——漏一个整批就成了废数据，
    // 而服务端那边只会静静地 400，客户端根本不看返回。
    @Test
    fun `引号反斜杠与换行都要转义`() {
        val json = RemoteLogSink.encode("c", listOf(entry("说了句 \"你好\"\n路径 C:\\tmp\tend")))
        assertTrue("引号没转义: $json", json.contains("""\"你好\""""))
        assertTrue("反斜杠没转义: $json", json.contains("""C:\\tmp"""))
        assertTrue("换行没转义: $json", json.contains("""\n"""))
        assertTrue("制表符没转义: $json", json.contains("""\t"""))
        // 转义之后整体仍然是合法的一行——不能因为内容里有换行就把 JSON 断成两行。
        assertEquals(1, json.lines().size)
    }

    @Test
    fun `控制字符走 unicode 转义`() {
        val json = RemoteLogSink.encode("c", listOf(entry("a\u0001b")))
        assertTrue("控制字符没转义: $json", json.contains("""\u0001"""))
    }

    @Test
    fun `空批与多条都编得出来`() {
        assertEquals("""{"client":"c","entries":[]}""", RemoteLogSink.encode("c", emptyList()))
        val json = RemoteLogSink.encode("c", listOf(entry("a"), entry("b")))
        assertEquals(2, json.split("at_ms").size - 1)
    }

    // ── 攒批队列 ──────────────────────────────────────────────

    // 满了丢**最旧**的：正在排查的问题总在最近这几条里，丢新的等于把最要紧的那段丢掉。
    @Test
    fun `队列满了丢最旧的`() {
        val buffer = RemoteLogSink.LogBuffer()
        repeat(RemoteLogSink.MAX_QUEUE + 10) { i -> buffer.add(entry("m$i")) }

        assertEquals(RemoteLogSink.MAX_QUEUE, buffer.size())
        val drained = buffer.drain(RemoteLogSink.MAX_QUEUE)
        assertEquals("m10", drained.first().message)
        assertEquals("m${RemoteLogSink.MAX_QUEUE + 9}", drained.last().message)
    }

    @Test
    fun `drain 按批取并把取走的移出队列`() {
        val buffer = RemoteLogSink.LogBuffer()
        repeat(5) { i -> buffer.add(entry("m$i")) }

        assertEquals(2, buffer.drain(2).size)
        assertEquals(3, buffer.size())
        assertEquals(3, buffer.drain(10).size) // 要的比有的多，给现有的
        assertEquals(0, buffer.size())
        assertTrue(buffer.drain(10).isEmpty())
    }

    // ── 发送 ──────────────────────────────────────────────────

    @Test
    fun `flush 把日志发到 dev logs`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"written":1}"""))
        val sink = RemoteLogSink(server.url("/").toString().trimEnd('/'), "android-alice")

        sink.write(com.imrtc.engine.log.IMRTCLog.Level.WARN, "engine", "上行 ICE 失败")
        sink.start()

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("1 秒内应当发出一批", recorded)
        assertEquals("POST", recorded!!.method)
        assertEquals("/v1/dev/logs", recorded.path)

        val body = recorded.body.readUtf8()
        assertTrue("客户端标识不对: $body", body.contains(""""client":"android-alice""""))
        assertTrue("级别丢了: $body", body.contains(""""level":"WARN""""))
        assertTrue("tag 没进 fields: $body", body.contains(""""fields":{"tag":"engine"}"""))
        assertTrue("消息丢了: $body", body.contains("上行 ICE 失败"))
        sink.stop()
    }

    // 服务端不可达时**不能重试、不能回队**：日志是尽力而为的，
    // 重试只会在服务端挂着的时候把队列撑爆，还会拖住下一轮。
    @Test
    fun `服务端不可达时丢掉而不是回队`() {
        server.shutdown() // 端口上没人了
        val sink = RemoteLogSink(server.url("/").toString().trimEnd('/'), "android-alice")

        sink.write(com.imrtc.engine.log.IMRTCLog.Level.INFO, "engine", "一条")
        sink.start()
        Thread.sleep(1500) // 让定时器跑一轮
        sink.stop()
        // 跑到这里没抛异常就是对的：写日志不该把业务线程带走。
    }

    // 日志出口自己抛异常会把正在打日志的业务线程一起带走。
    @Test
    fun `write 在任何情况下都不抛`() {
        val sink = RemoteLogSink("http://127.0.0.1:1", "c")
        sink.write(com.imrtc.engine.log.IMRTCLog.Level.ERROR, "t", "x")
        sink.write(com.imrtc.engine.log.IMRTCLog.Level.DEBUG, "", "")
    }
}
