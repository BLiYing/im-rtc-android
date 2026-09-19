package com.imrtc.engine.signaling

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.protocol.IMCloseCode
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上一条 socket 的关闭事件迟到了」——2026-09-08 那轮 code review 的回归。
 *
 * ## 现场
 *
 * [IMSignalConnection.closeAndReconnect] 会**自己先调一次** `handleClosed`，
 * 而 transport 的 `onClosed` / `onFailure` 随后**还会再调一次**
 * （`transport.close()` 只是发个关闭帧，OkHttp 一定还会回调）。
 * 网络假活时——也就是心跳超时那条路——第二次回调可能晚到好几分钟，
 * 那时新连接早已连上：于是那条迟到的关闭事件会把一条**好端端的连接**拆掉，
 * 还顺手开出第二条 socket，同 uid 同 device_id 被服务端顶掉，
 * 宿主收到一个**假的 onKickedOut**，用户被踹回登录页。
 *
 * 这一幕在真连接上几乎没法复现（要制造一条半死不活的网络再等几分钟），
 * 所以只能这么测。
 */
class StaleSocketCloseTest {

    private val scheduler = FakeScheduler()
    private val transport = FakeTransport()
    private val events = Recorder()
    private val connection = IMSignalConnection(transport, scheduler, events)

    private val config = IMSignalConnection.Config(url = "ws://test/rtc", deviceId = "d-1")

    /**
     * 把时间往前推 [seconds] 秒，**沿途保持连接活着**。
     *
     * 不喂东西的话，新连接自己的心跳会在 45 秒后判死（3 个周期没有任何入站），
     * 于是「几分钟后旧 socket 才回调」这一幕根本走不到——挂掉的是新连接自己，
     * 而不是我们要验的那件事。真机上这期间是有心跳往返的。
     */
    private fun keepAliveFor(seconds: Long) {
        var left = seconds
        while (left > 0) {
            val step = minOf(left, 10L)
            scheduler.advance(step * 1000)
            transport.deliver(IMFrameType.PONG, "")
            left -= step
        }
    }

    /** 走一遍握手，让当前这条 socket 进入 connected。 */
    private fun handshake(session: String) {
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf(
                "session_id" to IMJson.Str(session),
                "resumed" to IMJson.Bool(true),
                "ping_interval_sec" to IMJson.Num(15),
            ),
        )
    }

    /**
     * **核心那一条**：旧 socket 的关闭事件迟到，不许动新连接。
     *
     * 时间线与真机一致：心跳超时 → 就地收场 + 排重连 → 重连成功 →
     * 几分钟后旧 socket 才终于回一条 onFailure。
     */
    @Test
    fun `旧 socket 迟到的关闭事件不许拆掉已经连上的新连接`() {
        connection.start(config, "tk-1")
        handshake("s-1")
        val stale = transport.listeners.last()

        // 心跳超时：连着两个周期没收到任何东西，就地断开重连。
        scheduler.advance(15_000L * 3)
        assertTrue("心跳超时应该断开", !connection.isConnected)

        // 退避第一档 1 秒后重连，握手成功。
        scheduler.advance(2_000)
        assertEquals("应该开出第二条 socket", 2, transport.connectCount)
        handshake("s-1")
        assertTrue("重连之后应该是连上的", connection.isConnected)

        val disconnectsBefore = events.disconnects
        val connectsBefore = transport.connectCount

        // 几分钟后，旧 socket 那条 onFailure 才终于冒出来。
        keepAliveFor(300)
        assertTrue("这几分钟里新连接本来就该好好的", connection.isConnected)
        stale.onFailure(RuntimeException("stale socket finally gave up"))

        assertTrue("迟到的关闭事件不该把新连接拆掉", connection.isConnected)
        assertEquals("不该多抛一条 onDisconnected", disconnectsBefore, events.disconnects)
        assertEquals("不该再开一条 socket", connectsBefore, transport.connectCount)
        assertEquals("更不该抛出假的『被踢』", 0, events.kicks.size)
    }

    /** 迟到的关闭事件也不许把新连接上在飞的请求掐掉。 */
    @Test
    fun `旧 socket 迟到的关闭事件不许结掉新连接上在飞的请求`() {
        connection.start(config, "tk-1")
        handshake("s-1")
        val stale = transport.listeners.last()

        scheduler.advance(15_000L * 3)
        scheduler.advance(2_000)
        handshake("s-1")

        var failure: IMErrorCode? = null
        connection.request(IMFrameType.ROOM_JOIN, emptyMap()) { ok, _, code, _ ->
            if (!ok) failure = code
        }

        stale.onClosed(IMCloseCode.NORMAL.code, "stale")

        assertEquals("在飞的 room.join 不该被上一条 socket 的关闭事件掐掉", null, failure)
    }

    /** `closeAndReconnect` 自己那次收场**照旧要生效**——别把补丁打成「两次都不认」。 */
    @Test
    fun `心跳超时那次收场照旧生效并排上重连`() {
        connection.start(config, "tk-1")
        handshake("s-1")

        scheduler.advance(15_000L * 3)

        assertTrue("心跳超时应该断开", !connection.isConnected)
        assertEquals("应该抛一条 onDisconnected", 1, events.disconnects)
        scheduler.advance(2_000)
        assertEquals("应该排上重连", 2, transport.connectCount)
    }

    /**
     * 判死之后自己关掉的那条 socket **不能带 1000**：协议里 1000 是 logout，服务端收到就当场结束会话、
     * 移出房间，随后的重连只能「恢复失败，开新会话」，通话必死（2026-09-19 真机，服务端日志
     * 「客户端主动关闭，会话已结束」）。iOS / Web / 桌面一直用 1001。
     */
    @Test
    fun `心跳超时断开用 1001，不许用 1000`() {
        connection.start(config, "tk-1")
        handshake("s-1")

        scheduler.advance(15_000L * 3)

        assertEquals(listOf(IMCloseCode.GOING_AWAY.code), transport.closeCodes)
    }

    /** logout 之后迟到的关闭事件同样不许再抛回调、更不许重连。 */
    @Test
    fun `logout 之后迟到的关闭事件不许再抛回调`() {
        connection.start(config, "tk-1")
        handshake("s-1")
        val stale = transport.listeners.last()

        connection.stop()
        val disconnectsBefore = events.disconnects

        stale.onClosed(IMCloseCode.NORMAL.code, "late")
        scheduler.advance(60_000)

        assertEquals("logout 之后不该再抛 onDisconnected", disconnectsBefore, events.disconnects)
        assertEquals("logout 之后不该重连", 1, transport.connectCount)
    }

    private class Recorder : IMSignalConnectionEvents {
        var disconnects = 0
        val kicks = mutableListOf<IMKickedOutReason>()

        override fun onConnected(sessionId: String, resumed: Boolean) = Unit
        override fun onDisconnected(code: Int, willReconnect: Boolean) { disconnects++ }
        override fun onFrame(type: String, data: Map<String, IMJson>) = Unit
        override fun onKickedOut(reason: IMKickedOutReason) { kicks += reason }
        override fun onTokenWillExpire(expiresAtMs: Long) = Unit
        override fun onSessionUnrecoverable() = Unit
        override fun onError(code: IMErrorCode, message: String) = Unit
    }
}
