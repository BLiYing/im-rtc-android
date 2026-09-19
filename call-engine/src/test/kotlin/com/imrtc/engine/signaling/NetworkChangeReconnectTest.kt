package com.imrtc.engine.signaling

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网络变化：清零退避、立刻重连（2026-09-18 20:45，真机 OPPO/ColorOS）。
 *
 * 现场：Wi-Fi 自己断开重连换了 IP，信令在退避 30 秒那一档空等，服务端 30 秒恢复窗口先到期，
 * 通话被结束。这里钉住 [IMSignalConnection.onNetworkChanged] 的三种处境与防风暴间隔。
 */
class NetworkChangeReconnectTest {

    private val scheduler = FakeScheduler()
    private val transport = FakeTransport()
    private val events = Recorder()
    private val connection = IMSignalConnection(transport, scheduler, events)

    private val config = IMSignalConnection.Config(url = "ws://test/rtc", deviceId = "d-1")

    private fun connect() {
        connection.start(config, "tk-1")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
    }

    @Test
    fun `退避已经走到30秒那一档：网络一变立刻重连并归零`() {
        connection.start(config, "tk-1")
        // 连着 5 次连不上，每次都等满这一档（含 +20% 抖动），第 6 次失败后排的是 30 秒。
        for (waitMs in longArrayOf(1_200, 2_400, 4_800, 9_600, 18_000)) {
            transport.failure(RuntimeException("no route to host"))
            scheduler.advance(waitMs)
        }
        transport.failure(RuntimeException("no route to host"))
        assertEquals("前提：退避该已推到第 6 档", 6, connection.debugBackoffAttempts)
        val before = transport.connectCount

        connection.onNetworkChanged()

        assertEquals("网络变了不该再等 30 秒", before + 1, transport.connectCount)
        assertEquals("退避该归零", 0, connection.debugBackoffAttempts)
    }

    @Test
    fun `连着时网络变了：探一下，旧连接还活着就什么都不动`() {
        connect()
        val pings = transport.countOf(IMFrameType.PING)
        val connects = transport.connectCount

        connection.onNetworkChanged()
        assertEquals("该立刻发一个探测 ping", pings + 1, transport.countOf(IMFrameType.PING))
        transport.deliver(IMFrameType.PONG, "")
        scheduler.advance(IMNetworkProbe.PROBE_MS)

        assertEquals("活连接不许被误断", 0, events.disconnects)
        assertEquals(connects, transport.connectCount)
    }

    @Test
    fun `连着时网络变了：3秒没有下行就判死，立刻重连不走退避`() {
        connect()
        val connects = transport.connectCount

        connection.onNetworkChanged()
        scheduler.advance(IMNetworkProbe.PROBE_MS - 1)
        assertEquals("差 1ms 不该判死", 0, events.disconnects)

        scheduler.advance(1)
        assertEquals(1, events.disconnects)
        assertEquals("判死后该当场重连，不等退避那 1 秒", connects + 1, transport.connectCount)
    }

    @Test
    fun `网络变化时正在连：这次失败后立刻再连，不走退避`() {
        connection.start(config, "tk-1")
        val connects = transport.connectCount

        connection.onNetworkChanged()
        assertEquals("正在连的那次让它跑完，不另开", connects, transport.connectCount)

        transport.failure(RuntimeException("no route to host"))
        assertEquals("失败后该立刻再连", connects + 1, transport.connectCount)
    }

    @Test
    fun `网络来回跳：两次立刻重连之间至少隔2秒`() {
        connection.start(config, "tk-1")
        transport.failure(RuntimeException("no network"))
        connection.onNetworkChanged()
        val afterFirst = transport.connectCount

        transport.failure(RuntimeException("no network"))
        connection.onNetworkChanged()
        assertEquals("第二次紧跟着来，不许当场再连", afterFirst, transport.connectCount)

        scheduler.advance(IMReconnectTimer.IMMEDIATE_RECONNECT_MIN_GAP_MS - 1)
        assertEquals(afterFirst, transport.connectCount)
        scheduler.advance(1)
        assertEquals("满 2 秒该连", afterFirst + 1, transport.connectCount)
    }

    @Test
    fun `探测期间连接自己断了：探测作废，不许误伤下一条连接`() {
        connect()
        connection.onNetworkChanged()
        transport.closed(1006, "network")
        scheduler.advance(1_200) // 前台归零后第一档 1 秒（含抖动）
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
        val connects = transport.connectCount

        scheduler.advance(IMNetworkProbe.PROBE_MS)

        assertEquals("只该有那一次真断开", 1, events.disconnects)
        assertEquals(connects, transport.connectCount)
    }

    @Test
    fun `没登录或已登出：网络变化什么都不做`() {
        connection.onNetworkChanged()
        assertEquals(0, transport.connectCount)

        connect()
        connection.stop()
        val pings = transport.countOf(IMFrameType.PING)
        connection.onNetworkChanged()
        scheduler.advance(10_000)
        assertEquals(1, transport.connectCount)
        assertEquals(pings, transport.countOf(IMFrameType.PING))
    }

    private class Recorder : IMSignalConnectionEvents {
        var disconnects = 0

        override fun onConnected(sessionId: String, resumed: Boolean) = Unit
        override fun onDisconnected(code: Int, willReconnect: Boolean) { disconnects++ }
        override fun onFrame(type: String, data: Map<String, IMJson>) = Unit
        override fun onKickedOut(reason: IMKickedOutReason) = Unit
        override fun onTokenWillExpire(expiresAtMs: Long) = Unit
        override fun onSessionUnrecoverable() = Unit
        override fun onError(code: IMErrorCode, message: String) = Unit
    }
}
