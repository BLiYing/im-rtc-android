package com.imrtc.engine.signaling

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.protocol.IMCloseCode
import com.imrtc.engine.protocol.IMEnvelope
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMRtcException

/**
 * 信令连接：握手、心跳、按 req_id 配对、退避重连。协议 §1。
 *
 * ## 四条已经在别的端上流过血的规矩
 *
 * 1. **4401 必须有重试上限（3 次）**：重连带的是**同一枚 token**，没有上限就是拿同一把
 *    坏钥匙永远敲同一扇门。Web 端实测过：服务端重启换了签名密钥，一个没关的标签页重试到
 *    第 19 次还在敲，日志里全是 token_invalid，把真正的问题淹掉了。到顶抛 onKickedOut。
 * 2. **放弃必须用闩**（[stopped]）：只取消定时器不行——`connect()` 失败那条回调排在后面，
 *    它会把重连又排回来。
 * 3. **一次断线只排一次重连**：失败会从「关闭」与「连接失败」两条路走到 [scheduleReconnect]，
 *    不去重的话退避档一次涨两级（日志里会看到 attempt=14 紧跟 attempt=15）。
 * 4. **关闭码由这一层独占上报**：状态机那份 onDisconnected 不带码。混着报会出现
 *    「假的 4403」，宿主想数重连次数就数不对。
 */
internal class IMSignalConnection(
    private val transport: IMTransport,
    private val scheduler: IMScheduler,
    private val events: Events,
) {

    /** 连接层向上的出口。全部在 engine 线程上回调。 */
    interface Events {
        /** 握手成功。`resumed` 决定房间要不要归零（§1.4）。 */
        fun onConnected(sessionId: String, resumed: Boolean)

        /** 连接断开。`code` 是**真实关闭码**，没有就给 0。 */
        fun onDisconnected(code: Int, reason: String)

        /** 一条下行帧（事件或双向帧；应答已经在本层配对掉了）。 */
        fun onFrame(type: String, data: Map<String, IMJson>)

        /**
         * 被踢下线，**不会自动重连**。
         *
         * `reason` 决定宿主该做什么，两者处置相反——合并成一个「被踢」的话，
         * 宿主只能都当登录失效处理，把本可静默恢复的场景也变成「请重新登录」。
         */
        fun onKickedOut(reason: IMKickedOutReason)

        /** 票快到期了，宿主该去取新票并 updateToken。见 [IMTokenExpiryTimer]。 */
        fun onTokenWillExpire(expiresAtMs: Long)

        /** 连接层自己的错误（解析失败等）。 */
        fun onError(code: IMErrorCode, message: String)
    }

    data class Config(
        val url: String,
        val deviceId: String,
        val sdk: String = "android",
        val protocolVersion: Long = 1,
    )

    private var config: Config? = null
    private var token: String = ""
    private var sessionId: String = ""

    /**
     * 本端 uid，由 `sys.hello.ok` 带回来（协议 §1.3）。
     *
     * `@Volatile` 是因为它**在主线程上被读**（界面要拿它把自己从 callee_ids 里剔掉），
     * 而写在 engine 线程上。
     */
    @Volatile
    var uid: String = ""
        private set

    private val pending = IMPendingRequests(scheduler)
    private val backoff = IMBackoff()

    /** 闩：`logout()` 之后一切重连都不许再排（见类注释第 2 条）。 */
    private var stopped = true
    private var connecting = false
    private var connected = false
    private var reconnectTimer: IMScheduler.Cancellable? = null
    private var heartbeatTimer: IMScheduler.Cancellable? = null
    private var authFailures = 0
    private var lastInboundMs = 0L
    private val tokenExpiry = IMTokenExpiryTimer(scheduler) { expiresAtMs ->
        events.onTokenWillExpire(expiresAtMs)
    }

    val isConnected: Boolean get() = connected

    /** 开始连接。`token` 是宿主给的票，换票走 [updateToken]。 */
    fun start(config: Config, token: String) {
        this.config = config
        this.token = token
        stopped = false
        authFailures = 0
        backoff.reset()
        openSocket()
    }

    /**
     * 换票：**push 不 pull**（协议 §1.5）。
     *
     * 语义四端一致：**下一次重连生效，不打断当前连接**。不做「token provider 回调」
     * 那种让 Engine 自己去宿主账号体系要票的设计——票是宿主的东西，Engine 不认识那套。
     */
    @JvmOverloads
    fun updateToken(token: String, expiresAtMs: Long = 0L) {
        this.token = token
        // 换了新票，鉴权失败计数归零：这是一把新钥匙，不是同一把坏钥匙又敲一次。
        authFailures = 0
        // 宿主刚从自家后台拿到票，必然知道它的 expires_in。传了就按新票重新武装；
        // 不传就让旧定时器继续跑到下一次握手——那时 sys.hello.ok 会给出权威值。
        if (expiresAtMs > 0L) tokenExpiry.arm(expiresAtMs)
    }

    fun stop(code: Int = IMCloseCode.NORMAL.code, reason: String = "logout") {
        stopped = true
        connecting = false
        connected = false
        cancelTimers()
        tokenExpiry.disarm()
        pending.failAll(IMErrorCode.NOT_LOGGED_IN, "连接已关闭")
        transport.close(code, reason)
        sessionId = ""
    }

    /** 发一条请求帧，等它的应答。 */
    fun request(type: String, data: Map<String, IMJson>, callback: IMPendingRequests.Callback) {
        if (!connected) {
            callback.onResult(false, emptyMap(), IMErrorCode.NOT_LOGGED_IN, "信令未连接")
            return
        }
        val reqId = pending.nextReqId()
        pending.register(reqId, type, callback)
        sendFrame(type, reqId, data)
    }

    /** 发一条不需要应答的帧（下行双向帧的本端方向，如 room.answer 的 ICE 候选）。 */
    fun send(type: String, data: Map<String, IMJson>) {
        if (!connected) return
        sendFrame(type, "", data)
    }

    // ── 内部：连接生命周期 ──────────────────────────────────────────────

    private fun openSocket() {
        val cfg = config ?: return
        if (stopped || connecting || connected) return
        connecting = true
        IMRTCLog.i("signal", "连接 ${cfg.url}（第 ${backoff.attempts} 次尝试）")
        transport.connect(cfg.url, TransportListener())
    }

    private fun sendHello() {
        val cfg = config ?: return
        val data = mapOf(
            "protocol_version" to IMJson.Num(cfg.protocolVersion),
            "token" to IMJson.Str(token),
            "device_id" to IMJson.Str(cfg.deviceId),
            "session_id" to IMJson.Str(sessionId),
            "sdk" to IMJson.Str(cfg.sdk),
        )
        IMRTCLog.i("signal", "握手 device=${cfg.deviceId} token=${IMRTCLog.redact(token)}")
        val reqId = pending.nextReqId()
        pending.register(reqId, IMFrameType.HELLO) { ok, payload, code, message ->
            if (ok) onHelloOk(payload) else {
                IMRTCLog.w("signal", "握手失败：${code?.wireName} $message")
                events.onError(code ?: IMErrorCode.INTERNAL, message)
                closeAndReconnect(0, "hello failed")
            }
        }
        sendFrame(IMFrameType.HELLO, reqId, data)
    }

    private fun onHelloOk(data: Map<String, IMJson>) {
        connected = true
        connecting = false
        backoff.reset()
        authFailures = 0
        sessionId = (data["session_id"] as? IMJson.Str)?.value ?: ""
        uid = (data["uid"] as? IMJson.Str)?.value ?: uid
        val resumed = (data["resumed"] as? IMJson.Bool)?.value ?: false
        val pingSec = (data["ping_interval_sec"] as? IMJson.Num)?.value ?: DEFAULT_PING_SEC
        IMRTCLog.i("signal", "已连接 session=$sessionId resumed=$resumed ping=${pingSec}s")
        startHeartbeat(pingSec.coerceIn(MIN_PING_SEC, MAX_PING_SEC))
        tokenExpiry.arm((data["token_expires_at_ms"] as? IMJson.Num)?.value ?: 0L)
        events.onConnected(sessionId, resumed)
    }

    private fun startHeartbeat(intervalSec: Long) {
        heartbeatTimer?.cancel()
        lastInboundMs = scheduler.nowMs()
        val intervalMs = intervalSec * 1000
        fun tick() {
            heartbeatTimer = scheduler.postDelayed(intervalMs) {
                if (!connected) return@postDelayed
                // 连着两个周期没收到任何东西 = 这条连接已经死了，只是 TCP 还没告诉我们。
                // 移动网络下这种「假活」很常见（切基站、NAT 超时），干等 TCP 超时要好几分钟。
                if (scheduler.nowMs() - lastInboundMs > intervalMs * 2) {
                    IMRTCLog.w("signal", "心跳超时，主动断开重连")
                    closeAndReconnect(0, "heartbeat timeout")
                    return@postDelayed
                }
                send(IMFrameType.PING, emptyMap())
                tick()
            }
        }
        tick()
    }

    private fun cancelTimers() {
        reconnectTimer?.cancel()
        reconnectTimer = null
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    private fun closeAndReconnect(code: Int, reason: String) {
        transport.close(IMCloseCode.NORMAL.code, reason)
        handleClosed(code, reason)
    }

    private fun handleClosed(code: Int, reason: String) {
        val wasConnected = connected
        connected = false
        connecting = false
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        pending.failAll(IMErrorCode.NETWORK_UNREACHABLE, "连接断开：$reason")

        // 关闭码由这一层独占上报（类注释第 4 条）。
        if (wasConnected || code != 0) events.onDisconnected(code, reason)

        if (stopped) return

        when (code) {
            IMCloseCode.KICKED.code -> {
                // 被踢：重连没有意义——那等于跟另一台设备打架。
                IMRTCLog.w("signal", "被踢下线（4403），不再重连")
                stopped = true
                events.onKickedOut(IMKickedOutReason.TAKEN_OVER)
                return
            }
            IMCloseCode.UNAUTHORIZED.code -> {
                authFailures++
                IMRTCLog.w("signal", "鉴权失败（4401），第 $authFailures 次")
                if (authFailures >= MAX_AUTH_FAILURES) {
                    // 四端同一个数：3。到顶就别再敲了，让宿主回登录页换票。
                    IMRTCLog.e("signal", "连续 $MAX_AUTH_FAILURES 次鉴权失败，放弃")
                    stopped = true
                    events.onKickedOut(IMKickedOutReason.AUTH_EXPIRED)
                    return
                }
            }
            IMCloseCode.NORMAL.code -> {
                // 服务端正常关闭且我们没主动 stop：还是要重连（可能是它在滚动重启）。
            }
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (stopped) return
        // 一次断线只排一次（类注释第 3 条）。
        if (reconnectTimer != null) return
        val delay = backoff.nextDelayMs()
        IMRTCLog.i("signal", "${delay}ms 后重连（attempt=${backoff.attempts}）")
        reconnectTimer = scheduler.postDelayed(delay) {
            reconnectTimer = null
            openSocket()
        }
    }

    // ── 内部：收发 ─────────────────────────────────────────────────────

    private fun sendFrame(type: String, reqId: String, data: Map<String, IMJson>) {
        val envelope = IMEnvelope(type, reqId, scheduler.nowMs(), data)
        try {
            transport.send(envelope.encode())
        } catch (e: IMRtcException) {
            IMRTCLog.e("signal", "发送失败 $type：${e.detail}")
            events.onError(e.errorCode, e.detail)
        }
    }

    private fun handleText(text: String) {
        lastInboundMs = scheduler.nowMs()
        val envelope = try {
            IMEnvelope.decode(text)
        } catch (e: IMRtcException) {
            // 服务端发来的东西解不动：记一条就算了，**不要断开连接**——
            // 很可能只是一个我们还不认识的新帧，前向兼容要求静默忍受。
            IMRTCLog.w("signal", "收到解不动的帧：${e.detail}")
            return
        }

        // sys.error 也是应答：它带着 req_id 回到发起方（§7）。
        if (envelope.type == IMFrameType.ERROR && envelope.reqId.isNotEmpty()) {
            val data = envelope.decodedDataOrEmpty()
            val code = IMErrorCode.fromCode(((data["code"] as? IMJson.Num)?.value ?: 0L).toInt())
            val message = (data["msg"] as? IMJson.Str)?.value ?: ""
            if (!pending.reject(envelope.reqId, code ?: IMErrorCode.INTERNAL, message)) {
                IMRTCLog.d("signal", "迟到的错误应答，丢弃：${envelope.reqId}")
            }
            return
        }

        if (envelope.reqId.isNotEmpty() && envelope.type.endsWith(IMEnvelope.OK_SUFFIX)) {
            if (!pending.resolve(envelope.reqId, envelope.decodedDataOrEmpty())) {
                // 迟到的应答：丢掉即可，**不得崩溃**（§2.2）。
                IMRTCLog.d("signal", "迟到的应答，丢弃：${envelope.type}")
            }
            return
        }

        // 未知帧类型：客户端**必须静默忽略**（§2.3 的前向兼容）。
        val data = try {
            envelope.decodedData()
        } catch (e: IMRtcException) {
            if (e.errorCode == IMErrorCode.UNKNOWN_TYPE || e.errorCode == IMErrorCode.NOT_IMPLEMENTED) {
                IMRTCLog.d("signal", "忽略未知帧 ${envelope.type}")
            } else {
                IMRTCLog.w("signal", "帧 ${envelope.type} 解码失败：${e.detail}")
                events.onError(e.errorCode, e.detail)
            }
            return
        }
        events.onFrame(envelope.type, data)
    }

    private fun IMEnvelope.decodedDataOrEmpty(): Map<String, IMJson> = try {
        decodedData()
    } catch (e: IMRtcException) {
        data
    }

    /** transport 的回调可能在任意线程，这里统一 post 回 engine 线程。 */
    private inner class TransportListener : IMTransport.Listener {
        override fun onOpen() = scheduler.post {
            if (stopped) return@post
            sendHello()
        }

        override fun onText(text: String) = scheduler.post { handleText(text) }

        override fun onClosed(code: Int, reason: String) = scheduler.post { handleClosed(code, reason) }

        override fun onFailure(error: Throwable) = scheduler.post {
            handleClosed(0, error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        /** 四端同一个数。见类注释第 1 条。 */
        const val MAX_AUTH_FAILURES = 3
        private const val DEFAULT_PING_SEC = 15L
        private const val MIN_PING_SEC = 5L
        private const val MAX_PING_SEC = 60L
    }
}
