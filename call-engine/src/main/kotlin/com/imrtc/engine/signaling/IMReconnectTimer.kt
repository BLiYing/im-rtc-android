package com.imrtc.engine.signaling

import com.imrtc.engine.log.IMRTCLog

/**
 * 「下一次什么时候开 socket」：持有退避档与重连定时器。从 [IMSignalConnection] 拆出来是
 * 体量红线（CONVENTIONS §2）；**判定规则不在这里**——等多久按 [IMReconnectPolicy]，
 * 该不该重连由 [IMSignalConnection.handleClosed] 裁决，这里只管排、取消、立刻开。
 *
 * ## 三种起法
 *
 * - [schedule]：断开后按退避（与 [IMReconnectPolicy.Plan] 的封顶）排一次。
 * - [reconnectNow]：回前台，正等着的那次不等了，退避归零立刻开。
 * - [reconnectForNetwork]：系统默认网络换了（2026-09-18 20:45 真机 OPPO：Wi-Fi 重连换 IP，
 *   退避正在 30 秒那一档空等，服务端的 30 秒恢复窗口先到期，通话被结束）。
 *   退避归零立刻开，但两次之间至少隔 [NETWORK_RECONNECT_MIN_GAP_MS]——网络来回跳时
 *   不刷出重连风暴。
 *
 * [networkChangePending]：网络变化那一刻正在连、或探测判定旧连接已死要断，
 * 这一次的失败**不走退避**，由 [schedule] 转成 [reconnectForNetwork]。
 */
internal class IMReconnectTimer(
    private val scheduler: IMScheduler,
    private val open: () -> Unit,
) {

    private val backoff = IMBackoff()
    private var timer: IMScheduler.Cancellable? = null
    private var lastNetworkReconnectMs = Long.MIN_VALUE / 2

    /** 见类注释。连上（握手成功）、`start`、`stop` 时由调用方清掉。 */
    var networkChangePending = false

    /** 已经尝试过几次，打日志与单测用。 */
    val attempts: Int get() = backoff.attempts

    /** 正等着下一次重连。 */
    val waiting: Boolean get() = timer != null

    fun resetBackoff() = backoff.reset()

    fun cancel() {
        timer?.cancel()
        timer = null
    }

    /** 断开后排一次。**一次断线只排一次**（[IMSignalConnection] 类注释第 3 条）。 */
    fun schedule(plan: IMReconnectPolicy.Plan) {
        if (timer != null) return
        if (networkChangePending) {
            reconnectForNetwork()
            return
        }
        val raw = backoff.nextDelayMs()
        val delay = plan.capMs?.let { minOf(raw, it) } ?: raw
        IMRTCLog.i("signal", "${delay}ms 后重连（attempt=${backoff.attempts}，规则=${plan.rule}）")
        arm(delay) { open() }
    }

    /** 回前台：正等着的那次不等了。没在等就什么都不做。 */
    fun reconnectNow(rule: String) {
        if (timer == null) return
        cancel()
        backoff.reset()
        IMRTCLog.i("signal", "0ms 后重连（attempt=${backoff.attempts}，规则=$rule）")
        open()
    }

    /** 网络变化：清零退避、立刻开；离上一次不足 [NETWORK_RECONNECT_MIN_GAP_MS] 就补足间隔。 */
    fun reconnectForNetwork() {
        networkChangePending = false
        cancel()
        backoff.reset()
        val wait = (lastNetworkReconnectMs + NETWORK_RECONNECT_MIN_GAP_MS - scheduler.nowMs()).coerceAtLeast(0L)
        IMRTCLog.i("signal", "${wait}ms 后重连（attempt=${backoff.attempts}，规则=网络变化立即重连）")
        val go = {
            lastNetworkReconnectMs = scheduler.nowMs()
            open()
        }
        if (wait == 0L) go() else arm(wait, go)
    }

    private fun arm(delayMs: Long, block: () -> Unit) {
        timer = scheduler.postDelayed(delayMs) {
            timer = null
            block()
        }
    }

    companion object {
        /** 因网络变化立刻重连，两次之间至少隔这么久。 */
        const val NETWORK_RECONNECT_MIN_GAP_MS = 2_000L
    }
}
