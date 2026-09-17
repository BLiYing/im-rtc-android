package com.imrtc.engine.signaling

import com.imrtc.engine.IMCallEngineVersion
import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.protocol.IMCloseCode
import com.imrtc.engine.protocol.IMEnvelope
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMRtcException
import java.util.concurrent.atomic.AtomicLong

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
 *
 * ## 后台重连节奏：「不清零，最长 3 秒」（2026-09-11，真机 OPPO/ColorOS）
 *
 * ColorOS 的 `OAppNetControlService` 在 App 切后台后约每 3 秒打一条
 * `Close socket:[...] cause:App bg(IMMEDIATELY)`，把后台 App 的 socket 强制掐断，
 * 而服务端「被叫刚断线在 30s 恢复窗口内」时只等 **5 秒**就判离线转振铃——默认的
 * 1/2/4/8/15/30s 退避一旦超过 5 秒，后台就接不到来电了。四条判定规则（纯函数，
 * 不碰这个类的任何状态，单测直接构造入参）在 [IMReconnectPolicy]，由 [setForeground]
 * 喂前后台状态。回到前台时如果正等着下一次重连，不必等了：立刻重连、退避归零。
 */
internal class IMSignalConnection(
    private val transport: IMTransport,
    private val scheduler: IMScheduler,
    private val events: IMSignalConnectionEvents,
) {

    data class Config(
        val url: String,
        val deviceId: String,
        val sdk: String = IMCallEngineVersion.SDK,
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

    /**
     * 握手应答里当场判定的「放弃」原因，由 [handleClosed] 消费。
     *
     * 判定在 [sendHello]（只有那儿看得见错误码），收拾现场与上报在 [handleClosed]
     * （只有那儿是关闭码的唯一出口）——这个字段就是两者之间的交接。
     */
    private var pendingGiveUp: IMKickedOutReason? = null
    private var connecting = false

    /** `@Volatile`：[fire] 与 `IMCallEngine.forceEnd` 在调用方线程上读它，写只在 engine 线程。 */
    @Volatile
    private var connected = false

    /**
     * 当前这条 socket 的代际。**每开一条 +1**，[handleClosed] 拿它认「这条关闭事件是谁的」。
     *
     * 少了它就会出这一幕：[closeAndReconnect] 自己先调一次 [handleClosed]，
     * 而 transport 的 `onClosed` / `onFailure` 随后**还会再调一次**（OkHttp 一定会回调，
     * `transport.close()` 只是发个关闭帧）。网络假活时——也就是心跳超时那条路——
     * 第二次回调可能晚到好几分钟，那时新连接早已 `connected=true`：
     * 于是多抛一条假的 onDisconnected（界面写「正在重连」而连接好好的）、
     * `failAll` 把在飞请求全掐掉、`connected/connecting` 被清零，
     * 接着 `scheduleReconnect → openSocket` 开出**第二条 socket**——
     * 同 uid 同 device_id，服务端按顶号踢掉一条，宿主收到一个**假的
     * `onKickedOut(TAKEN_OVER)`**，用户被踹回登录页。
     *
     * （iOS 不会：`IMURLSessionWebSocket` 有个 `closed` 标志，
     * 保证每条 socket 只回一次 onClose。这里等价的做法就是认代际。）
     */
    private var generation = 0

    /** 已经为哪一代收过场了。同一代的第二条关闭事件一律丢掉。 */
    private var closedGeneration = -1
    private var reconnectTimer: IMScheduler.Cancellable? = null
    private var heartbeatTimer: IMScheduler.Cancellable? = null

    /** 服务端最近一次告知的心跳周期。[IMSessionRecoveryTimer] 要用它推算服务端何时判死。 */
    private var pingSec = DEFAULT_PING_SEC

    private val sessionRecovery = IMSessionRecoveryTimer(scheduler) {
        // 服务端已经丢掉这个会话，再拿它去要 resume 只会白跑一趟。
        sessionId = ""
        events.onSessionUnrecoverable()
    }
    private var authFailures = 0
    private var lastInboundMs = 0L

    /**
     * App 前后台状态，由宿主经 [setForeground] 喂（`IMCallEngine.setAppForeground`）。
     * **默认 true**：宿主没接这条通道之前，一切按「前台」的老规矩走，不引入回归。
     */
    private var foreground = true

    /** 最近一次握手成功（[onHelloOk]）的时刻。断开时算「这条连接活了多久」要用它。 */
    private var connectedAtMs = 0L
    private val tokenExpiry = IMTokenExpiryTimer(scheduler) { expiresAtMs ->
        events.onTokenWillExpire(expiresAtMs)
    }

    val isConnected: Boolean get() = connected

    /** 仅供单测观察退避档位有没有被清零（规则①③⑤该清零、规则②④不该）；生产代码不读它。 */
    internal val debugBackoffAttempts: Int get() = backoff.attempts

    /** 开始连接。`token` 是宿主给的票，换票走 [updateToken]。 */
    fun start(config: Config, token: String) {
        this.config = config
        this.token = token
        stopped = false
        pendingGiveUp = null
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

    /**
     * App 前后台切换，由 `IMCallEngine.setAppForeground` 喂（类注释「后台重连节奏」）。
     *
     * 回到前台时如果正等着下一次重连——没必要再等专为「后台被系统掐掉的连接」算出来的
     * 那一档，前台大概率马上要用信令（来电、正常操作）：取消定时器，立刻重连，退避归零。
     */
    fun setForeground(value: Boolean) {
        if (value == foreground) return
        foreground = value
        IMRTCLog.i("signal", "App 切到${if (value) "前台" else "后台"}")
        if (!value) return
        val timer = reconnectTimer ?: return
        timer.cancel()
        reconnectTimer = null
        backoff.reset()
        IMRTCLog.i("signal", "0ms 后重连（attempt=${backoff.attempts}，规则=回前台立即重连）")
        openSocket()
    }

    fun stop(code: Int = IMCloseCode.NORMAL.code, reason: String = "logout") {
        stopped = true
        // 当前这一代就此收场：logout 之后 transport 还会回一次 onClosed，
        // 那条不该再走一遍 failAll 与 onDisconnected。
        closedGeneration = generation
        pendingGiveUp = null
        connecting = false
        connected = false
        cancelTimers()
        sessionRecovery.cancel()
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

    /** [fire] 的 req_id 序号。**原子的**：[fire] 不在 engine 线程上调。 */
    private val fireSequence = AtomicLong()

    /**
     * 发一条请求但**不等应答**，**任何线程都能调**（不经过 engine 线程）。
     *
     * 只给 `IMCallEngine.forceEnd()` 用：红键等不到结束事件时，结束帧不能再排在一条
     * 可能已经卡住的队列后面（2026-09-13 iOS frank：join 晚了 28.6 秒、hangup 一帧没到服务端）。
     * req_id 不登记进 [pending]：应答回来对不上号，[handleText] 当迟到的应答丢掉，不会漏进事件流。
     *
     * @return 没连上时不发，返回 false。
     */
    fun fire(type: String, data: Map<String, IMJson>): Boolean {
        if (!connected) {
            IMRTCLog.w("signal", "帧没发出去：连接不可用 type=$type")
            return false
        }
        return try {
            // 从全默认值起手再覆盖（发送侧默认值陷阱，见 [IMEnvelope.request]）。
            val reqId = "f-${fireSequence.incrementAndGet()}"
            transport.send(IMEnvelope.request(type, reqId, scheduler.nowMs()) { it.putAll(data) }.encode())
            // 同 [sendFrame]：先问一句有没有人要，别不问就把 String 拼好。
            if (IMRTCLog.isLoggable(IMRTCLog.Level.DEBUG)) {
                IMRTCLog.d("signal", "↑ $type${reqSuffix(reqId)}${idSuffix(data)}（不等应答）")
            }
            true
        } catch (e: IMRtcException) {
            IMRTCLog.e("signal", "发送失败 $type：${e.detail}")
            false
        }
    }

    // ── 内部：连接生命周期 ──────────────────────────────────────────────

    private fun openSocket() {
        val cfg = config ?: return
        if (stopped || connecting || connected) return
        connecting = true
        generation += 1
        IMRTCLog.i("signal", "连接 ${cfg.url}（第 ${backoff.attempts} 次尝试，gen=$generation）")
        transport.connect(cfg.url, TransportListener(generation))
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
                // 判定放在这里，**收拾现场与上报交给 handleClosed**：那边才是本类唯一
                // 一处「关连接 → 清定时器 → 结掉在飞请求 → 报关闭码」的地方（类注释第 4 条）。
                // 自己在这儿闩上再抛，会留下一个 connecting=true 的半开连接，
                // 宿主照着 onKickedOut 的建议改完配置再 login() 就会被 openSocket 静默挡掉。
                // 判定规则见 [handshakeGiveUpReason]（不可重试 ≠ 参数不对，三类处置不同）。
                pendingGiveUp = handshakeGiveUpReason(code, payload)
                closeAndReconnect(0, "hello failed")
            }
        }
        sendFrame(IMFrameType.HELLO, reqId, data)
    }

    private fun onHelloOk(data: Map<String, IMJson>) {
        connected = true
        connecting = false
        // 退避归零与否要看这条连接能活多久、断开时前后台是什么状态——
        // 那要等断开才知道，见 [IMReconnectPolicy]，这里只记下起点。
        connectedAtMs = scheduler.nowMs()
        authFailures = 0
        sessionId = (data["session_id"] as? IMJson.Str)?.value ?: ""
        uid = (data["uid"] as? IMJson.Str)?.value ?: uid
        val resumed = (data["resumed"] as? IMJson.Bool)?.value ?: false
        pingSec = ((data["ping_interval_sec"] as? IMJson.Num)?.value ?: DEFAULT_PING_SEC)
            .coerceIn(MIN_PING_SEC, MAX_PING_SEC)
        // 连上了就别再倒计时了——不管 resumed 是真是假，服务端都已经给出裁决。
        sessionRecovery.cancel()
        IMRTCLog.i("signal", "已连接 session=$sessionId resumed=$resumed ping=${pingSec}s")
        startHeartbeat(pingSec)
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
        // 就地收场，并把这一代闩上——transport 随后一定还会回一次 onClosed/onFailure，
        // 那一条必须被 [handleClosed] 的代际判断挡掉（见 [generation]）。
        handleClosed(code, reason, generation)
    }

    /**
     * 收场。`from` 是这条关闭事件属于哪一代 socket。
     *
     * **同一代只收一次场，旧代的一律丢掉**——理由见 [generation]。
     */
    private fun handleClosed(code: Int, reason: String, from: Int) {
        if (from <= closedGeneration) {
            IMRTCLog.d("signal", "重复/过期的关闭事件（gen=$from，已收到 $closedGeneration），丢弃")
            return
        }
        closedGeneration = from
        val wasConnected = connected
        // 算「这条连接活了多久」要趁 connected 还没清掉之前——只有真握手成功过才有意义。
        val aliveMs = if (wasConnected) scheduler.nowMs() - connectedAtMs else null
        connected = false
        connecting = false
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        pending.failAll(IMErrorCode.NETWORK_UNREACHABLE, "连接断开：$reason")

        IMRTCLog.i(
            "signal",
            "断开 code=$code reason=$reason aliveMs=${aliveMs ?: -1} foreground=$foreground",
        )

        // 抛 onDisconnected 前先当场判「会不会重连」，判据要跟下面真实走的分支同步（类注释第 4 条）。
        val willReconnect = IMReconnectPolicy.willReconnect(stopped, pendingGiveUp != null, code, authFailures + 1, MAX_AUTH_FAILURES)
        if (wasConnected || code != 0) events.onDisconnected(code, willReconnect)

        if (stopped) return

        // 见 [IMSessionRecoveryTimer]：只在第一次断开时起，往后每次重连失败都会再走到
        // 这一行，靠它自己挡住重排。到点了先把 sessionId 清掉——服务端已经丢掉这个会话，
        // 再拿它去要 resume 只会白跑一趟。
        sessionRecovery.armIfNeeded(pingSec)

        // 握手应答里已经判过「这个错重连一万次也还是这个错」（见 [giveUpReason]）。
        // 排在关闭码之前：那一帧比关闭码具体得多——4401 只说「鉴权没过」，
        // 而 1101/1102 分得出「票不合法」与「票刚过期」。
        pendingGiveUp?.let { reason ->
            pendingGiveUp = null
            IMRTCLog.e("signal", "握手被拒（$reason），不再重连")
            giveUp(reason)
            return
        }

        when (code) {
            IMCloseCode.KICKED.code -> {
                // 被踢：重连没有意义——那等于跟另一台设备打架。
                IMRTCLog.w("signal", "被踢下线（4403），不再重连")
                giveUp(IMKickedOutReason.TAKEN_OVER)
                return
            }
            IMCloseCode.UNAUTHORIZED.code -> {
                authFailures++
                IMRTCLog.w("signal", "鉴权失败（4401），第 $authFailures 次")
                if (!willReconnect) {
                    // 四端同一个数：3。到顶就别再敲了，让宿主回登录页换票。
                    IMRTCLog.e("signal", "连续 $MAX_AUTH_FAILURES 次鉴权失败，放弃")
                    giveUp(IMKickedOutReason.AUTH_EXPIRED)
                    return
                }
            }
        }
        if (!willReconnect) {
            // 其余不重连的码（当前只有 4400）：对齐 iOS/Web 只报 onDisconnected，不算被踢；
            // 闩上 stopped 防内部路径误排重连，宿主重新 login() 会解开（见 [start]）。
            IMRTCLog.e("signal", "关闭码 $code 不重连（shouldReconnect=false）")
            stopped = true
            tokenExpiry.disarm()
            return
        }
        scheduleReconnect(IMReconnectPolicy.plan(wasConnected, aliveMs, foreground, backoff::reset))
    }

    /**
     * 放弃这条连接：**闩上、把票期定时器也停掉，再把原因抛给宿主**。
     *
     * 定时器不停的症状：几分钟后它照样喊 `onTokenWillExpire`，宿主老老实实去后台换一枚
     * 新票、`updateToken` 塞回来——而 [stopped] 已经闩上了，这条连接不会因此重连一次。
     * 宿主以为自己救回来了，实际上 Engine 已经哑了，而且一声不吭。
     */
    private fun giveUp(reason: IMKickedOutReason) {
        stopped = true
        tokenExpiry.disarm()
        events.onKickedOut(reason)
    }

    private fun scheduleReconnect(plan: IMReconnectPolicy.Plan) {
        if (stopped) return
        // 一次断线只排一次（类注释第 3 条）。
        if (reconnectTimer != null) return
        val raw = backoff.nextDelayMs()
        val delay = plan.capMs?.let { minOf(raw, it) } ?: raw
        IMRTCLog.i("signal", "${delay}ms 后重连（attempt=${backoff.attempts}，规则=${plan.rule}）")
        reconnectTimer = scheduler.postDelayed(delay) {
            reconnectTimer = null
            openSocket()
        }
    }

    // ── 内部：收发 ─────────────────────────────────────────────────────

    private fun sendFrame(type: String, reqId: String, data: Map<String, IMJson>) {
        val envelope = IMEnvelope(type, reqId, scheduler.nowMs(), data)
        try {
            // **上下行各留一条**。没有它们时，「按了挂断却没挂掉」这类问题在日志里
            // 是一段空白：分不出是 Engine 压根没发、发了服务端没收到、还是收到了
            // 应答没回来——三种情况要查的地方完全不同。
            //
            // 信令帧一秒最多几帧，不是媒体那种每帧每包的热路径（CONVENTIONS §6
            // 禁的是后者）。级别用 debug，并且**先问一句有没有人要**——
            // `d()` 收的是拼好的 String，不问就等于「装没装 sink 都照拼」。
            //
            // **写在 send 之后**：`encode()` 会为超过 64 KiB 的帧抛 FRAME_TOO_LARGE，
            // 写在前面就会记下一条根本没发出去的 `↑`——而时间轴上「客户端有 ↑、
            // 服务端没有」恰恰是「发了服务端没收到」的判据，正好把结论指反。
            transport.send(envelope.encode())
            if (IMRTCLog.isLoggable(IMRTCLog.Level.DEBUG)) {
                IMRTCLog.d("signal", "↑ $type${reqSuffix(reqId)}${idSuffix(data)}")
            }
        } catch (e: IMRtcException) {
            IMRTCLog.e("signal", "发送失败 $type：${e.detail}")
            events.onError(e.errorCode, e.detail)
        }
    }

    /** 有 req_id 就带上——按它能把一次请求与它的应答在时间轴上串起来。 */
    private fun reqSuffix(reqId: String): String = if (reqId.isEmpty()) "" else " req=$reqId"

    /**
     * 必带字段：call_id / room_id（有哪个带哪个，CONVENTIONS §6）。
     *
     * **只取这两个，不打整个 data**：帧里可能有 SDP 与 token，
     * 整条打出来既会把前后文冲掉，也违反脱敏那条。
     */
    private fun idSuffix(data: Map<String, IMJson>): String = buildString {
        (data["call_id"] as? IMJson.Str)?.let { append(" call_id=").append(it.value) }
        (data["room_id"] as? IMJson.Str)?.let { append(" room_id=").append(it.value) }
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

        // **整帧只解一次。** `decodedData()` 什么都不缓存——每调一次就重跑一遍注册表查找
        // 与字段解码，未知/留位帧还要**构造并抛一个异常**（异常带栈回填）。
        // 原先日志一次、下面的分支再一次，等于每条下行帧的解码都做了两遍，
        // room.offer / room.answer 那种带 SDP 的也不例外。
        var decodeError: IMRtcException? = null
        val data = try {
            envelope.decodedData()
        } catch (e: IMRtcException) {
            decodeError = e
            envelope.data
        }

        if (IMRTCLog.isLoggable(IMRTCLog.Level.DEBUG)) {
            IMRTCLog.d("signal", "↓ ${envelope.type}${reqSuffix(envelope.reqId)}${idSuffix(data)}")
        }

        // sys.error 也是应答：它带着 req_id 回到发起方（§7）。
        if (envelope.type == IMFrameType.ERROR && envelope.reqId.isNotEmpty()) {
            val code = IMErrorCode.fromCode(((data["code"] as? IMJson.Num)?.value ?: 0L).toInt())
            val message = (data["msg"] as? IMJson.Str)?.value ?: ""
            // **code 与 data 都原样带过去**：本端不认识这个码时 code 是 null，
            // 而只有帧上的 retryable 说得准。在这儿兜底成 INTERNAL 会把两者一起弄丢。
            if (!pending.reject(envelope.reqId, code, message, data)) {
                IMRTCLog.d("signal", "迟到的错误应答，丢弃：${envelope.reqId}")
            }
            return
        }

        if (envelope.reqId.isNotEmpty() && envelope.type.endsWith(IMEnvelope.OK_SUFFIX)) {
            if (!pending.resolve(envelope.reqId, data)) {
                // 迟到的应答：丢掉即可，**不得崩溃**（§2.2）。
                IMRTCLog.d("signal", "迟到的应答，丢弃：${envelope.type}")
            }
            return
        }

        // 未知帧类型：客户端**必须静默忽略**（§2.3 的前向兼容）。
        decodeError?.let { e ->
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

    /**
     * transport 的回调可能在任意线程，这里统一 post 回 engine 线程。
     *
     * **每条 socket 一个 listener，带着自己那一代**：迟到的回调靠它认出来
     * （见 [generation]），不然它会把一条好端端的新连接拆掉。
     */
    private inner class TransportListener(private val gen: Int) : IMTransport.Listener {
        override fun onOpen() = scheduler.post {
            // 这条 socket 已经被换掉了（重连排在它前面开出了新的一条）：它开出来也没用。
            if (stopped || gen != generation) return@post
            sendHello()
        }

        override fun onText(text: String) = scheduler.post {
            // 旧 socket 上迟到的帧不能喂进状态机——那是上一条会话的东西。
            if (gen != generation) return@post
            handleText(text)
        }

        override fun onClosed(code: Int, reason: String) = scheduler.post { handleClosed(code, reason, gen) }

        override fun onFailure(error: Throwable) = scheduler.post {
            handleClosed(0, error.message ?: error.javaClass.simpleName, gen)
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
