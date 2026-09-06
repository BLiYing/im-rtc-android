package com.imrtc.engine.signaling

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.protocol.IMCloseCode
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信令连接的时序，全部用假传输 + 假时钟跑——**不连真服务端**。
 *
 * 这些行为在真连接上极难复现（要制造 4401、要等 30 秒退避、要让服务端半死不活），
 * 而它们恰恰是 Web 与 iOS 两端**实际出过 bug** 的地方。一条都不能靠肉眼验。
 */
class SignalConnectionTest {

    private val scheduler = FakeScheduler()
    private val transport = FakeTransport()
    private val events = RecordingEvents()
    private val connection = IMSignalConnection(transport, scheduler, events)

    private val config = IMSignalConnection.Config(url = "ws://test/rtc", deviceId = "d-1")

    private fun connect(token: String = "tk-1", resumed: Boolean = false) {
        connection.start(config, token)
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf(
                "session_id" to IMJson.Str("s-1"),
                "resumed" to IMJson.Bool(resumed),
                "ping_interval_sec" to IMJson.Num(15),
            ),
        )
    }

    @Test
    fun `握手：连上就发 sys hello，收到 ok 才算连接成功`() {
        connection.start(config, "tk-1")
        assertEquals(1, transport.connectCount)
        // 还没 open，不该发任何东西
        assertTrue(transport.sent.isEmpty())

        transport.open()
        val hello = transport.lastOf(IMFrameType.HELLO) ?: error("没发 sys.hello")
        assertEquals("tk-1", (hello.data["token"] as IMJson.Str).value)
        assertEquals("d-1", (hello.data["device_id"] as IMJson.Str).value)
        // 首次连接 session_id 为空串——**空串不是缺失**，缺了就是非法信封
        assertEquals("", (hello.data["session_id"] as IMJson.Str).value)
        assertTrue("握手没回来之前不该算已连接", !connection.isConnected)

        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
        assertTrue(connection.isConnected)
        assertEquals(listOf("s-1" to false), events.connected)
    }

    @Test
    fun `请求按 req_id 配对，迟到的应答丢掉而不是崩掉`() {
        connect()
        var result: String? = null
        connection.request(IMFrameType.ROOM_LEAVE, mapOf("room_id" to IMJson.Str("r-1"))) { ok, _, _, _ ->
            result = if (ok) "ok" else "fail"
        }
        transport.replyOk(IMFrameType.ROOM_LEAVE)
        assertEquals("ok", result)

        // 同一个 req_id 再来一次：这是迟到的重复应答，丢掉即可
        transport.replyOk(IMFrameType.ROOM_LEAVE)
        assertEquals("ok", result)
    }

    @Test
    fun `请求十秒无应答报 2004，之后应答回来也不崩`() {
        connect()
        var code: IMErrorCode? = null
        connection.request(IMFrameType.ROOM_JOIN, emptyMap()) { _, _, c, _ -> code = c }

        scheduler.advance(9_000)
        assertNull("9 秒还不该超时", code)
        scheduler.advance(2_000)
        assertEquals(IMErrorCode.SIGNALING_TIMEOUT, code)

        transport.replyOk(IMFrameType.ROOM_JOIN) // 迟到的应答
    }

    @Test
    fun `心跳按周期发 ping；两个周期收不到东西就自己断开重连`() {
        connect()
        scheduler.advance(15_000)
        assertEquals(1, transport.countOf(IMFrameType.PING))

        // 服务端有回应：连接是活的
        transport.deliver(IMFrameType.PONG, "")
        scheduler.advance(15_000)
        assertEquals(2, transport.countOf(IMFrameType.PING))

        // 之后一直没有任何下行：超过两个周期就主动断
        scheduler.advance(45_000)
        assertTrue("心跳超时后应当断开", !connection.isConnected)
        assertTrue("断开后应当排重连", transport.connectCount > 1)
    }

    @Test
    fun `一次断线只排一次重连`() {
        connect()
        // 「关闭」与「连接失败」两条路都会走到排重连，不去重的话退避档一次涨两级
        transport.closed(1006, "network")
        transport.failure()
        scheduler.advance(60_000)
        assertEquals("只该重连一次", 2, transport.connectCount)
    }

    @Test
    fun `4401 连续三次就放弃并抛 onKickedOut`() {
        connection.start(config, "过期的票")
        repeat(3) {
            transport.open()
            transport.closed(IMCloseCode.UNAUTHORIZED.code, "token_invalid")
            scheduler.advance(60_000)
        }
        assertEquals(1, events.kickedOut)
        val connectsSoFar = transport.connectCount
        scheduler.advance(120_000)
        assertEquals("放弃之后不许再敲", connectsSoFar, transport.connectCount)
    }

    @Test
    fun `换票之后鉴权计数归零——那是一把新钥匙`() {
        connection.start(config, "过期的票")
        repeat(2) {
            transport.open()
            transport.closed(IMCloseCode.UNAUTHORIZED.code, "token_invalid")
            scheduler.advance(60_000)
        }
        assertEquals(0, events.kickedOut)

        connection.updateToken("新票")
        repeat(2) {
            transport.open()
            transport.closed(IMCloseCode.UNAUTHORIZED.code, "token_invalid")
            scheduler.advance(60_000)
        }
        assertEquals("换票后重新计数，两次还不该放弃", 0, events.kickedOut)
    }

    @Test
    fun `4403 被踢：抛 onKickedOut 且不再重连`() {
        connect()
        transport.closed(IMCloseCode.KICKED.code, "kicked")
        val connectsSoFar = transport.connectCount
        scheduler.advance(120_000)
        assertEquals(1, events.kickedOut)
        assertEquals("被踢之后重连等于跟另一台设备打架", connectsSoFar, transport.connectCount)
    }

    @Test
    fun `stop 之后一律不再重连——放弃必须用闩`() {
        connect()
        connection.stop()
        // 闩没上的话，这条排在后面的失败回调会把重连又排回来
        transport.closed(1006, "late close")
        transport.failure()
        val connectsSoFar = transport.connectCount
        scheduler.advance(120_000)
        assertEquals(connectsSoFar, transport.connectCount)
    }

    @Test
    fun `重连成功后 resumed 原样带上来`() {
        connect()
        transport.closed(1006, "network")
        scheduler.advance(5_000)
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf("session_id" to IMJson.Str("s-1"), "resumed" to IMJson.Bool(true)),
        )
        assertEquals(listOf("s-1" to false, "s-1" to true), events.connected)
    }

    @Test
    fun `未知帧静默忽略，不断连接也不上抛`() {
        connect()
        transport.deliver("room.something_new", "")
        assertTrue(connection.isConnected)
        assertTrue(events.frames.none { it.first == "room.something_new" })
        assertTrue(events.errors.isEmpty())
    }

    /*
      被顶号与「票不好使」是两种相反的处置：一个回登录页，一个悄悄换票重来。
      分不开的话宿主只能都当登录失效，把本可静默恢复的场景也变成「请重新登录」。
    */
    @Test
    fun `被踢的两种原因分得开`() {
        connect()
        transport.closed(IMCloseCode.KICKED.code, "elsewhere")
        assertEquals(listOf(IMKickedOutReason.TAKEN_OVER), events.kickReasons)
    }

    @Test
    fun `4401 用尽报的是 AUTH_EXPIRED 而不是被顶号`() {
        connect()
        repeat(3) {
            transport.closed(IMCloseCode.UNAUTHORIZED.code, "bad token")
            scheduler.advance(60_000)
        }
        assertEquals(listOf(IMKickedOutReason.AUTH_EXPIRED), events.kickReasons)
    }

    private class RecordingEvents : IMSignalConnection.Events {
        val connected = mutableListOf<Pair<String, Boolean>>()
        val frames = mutableListOf<Pair<String, Map<String, IMJson>>>()
        val errors = mutableListOf<Pair<IMErrorCode, String>>()
        var kickedOut = 0
        val kickReasons = mutableListOf<IMKickedOutReason>()
        val tokenWarnings = mutableListOf<Long>()
        var disconnects = 0

        override fun onConnected(sessionId: String, resumed: Boolean) {
            connected += sessionId to resumed
        }

        override fun onDisconnected(code: Int, reason: String) {
            disconnects++
        }

        override fun onFrame(type: String, data: Map<String, IMJson>) {
            frames += type to data
        }

        override fun onKickedOut(reason: IMKickedOutReason) {
            kickedOut++
            kickReasons += reason
        }

        override fun onTokenWillExpire(expiresAtMs: Long) {
            tokenWarnings += expiresAtMs
        }

        override fun onError(code: IMErrorCode, message: String) {
            errors += code to message
        }
    }
}
