package com.imrtc.engine.signaling

import com.imrtc.engine.log.IMRTCLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * [IMTransport] 的 OkHttp 实现。
 *
 * 两个刻意的设置：
 * - **`pingInterval` 设为 0**：协议自己有 `sys.ping`（§1.3），再叠一层 WS 层 ping
 *   只是白费电，而且两套心跳的超时判定会打架。
 * - **`readTimeout` 设为 0**：WebSocket 是长连接，读超时会把闲着的连接踢掉。
 *   连接是否还活着由我们自己的心跳判定。
 */
internal class IMOkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : IMTransport {

    /** `@Volatile`：[send] 可能不在 engine 线程上调（`IMSignalConnection.fire`）。OkHttp 的 send 本身线程安全。 */
    @Volatile
    private var socket: WebSocket? = null

    override fun connect(url: String, listener: IMTransport.Listener) {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

                override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // 对端要关：回一个同码的关闭帧，然后等 onClosed。
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    listener.onClosed(code, reason)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // 握手阶段失败时 response 里才有 HTTP 状态码；连接建立后就只有异常。
                    val code = response?.code
                    IMRTCLog.w("transport", "连接失败：${t.javaClass.simpleName} http=$code")
                    listener.onFailure(t)
                }
            },
        )
    }

    override fun send(text: String) {
        socket?.send(text)
    }

    override fun close(code: Int, reason: String) {
        socket?.close(code, reason)
        socket = null
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
