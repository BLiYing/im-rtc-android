package com.imrtc.engine.signaling

/**
 * 系统默认网络换了之后，**连着的那条信令还活不活**——发一个 `sys.ping`，[PROBE_MS] 内
 * 收到任何下行就算活，否则判死。从 [IMSignalConnection] 拆出来是体量红线（CONVENTIONS §2）。
 *
 * ## 为什么要有它（2026-09-18 20:45，真机 OPPO/ColorOS，alice）
 *
 * 手机 Wi-Fi 自己断开重连了 1.5 秒，回来换了随机 MAC、IP 从 .11 变成 .22。
 * 旧 socket 绑在旧 IP 上，已经死了，但 TCP 不会告诉我们；心跳要连着两个周期（约 30 秒）
 * 收不到东西才判死，再走一档退避——**服务端给的恢复窗口也只有 30 秒**，等不起。
 *
 * ## 为什么是探一下，而不是见变化就断
 *
 * 默认网络变化不一定伤到旧连接：蜂窝 → Wi-Fi 时蜂窝还会挂一阵子，VPN 开关也会报一次变化。
 * 见变化就断，每次都白白掐掉在飞请求（发布会被推迟、挂断帧要补发）。探一下只多等
 * [PROBE_MS]，比心跳判死的 30 秒快一个数量级，又不误伤活连接。
 */
internal class IMNetworkProbe(private val scheduler: IMScheduler) {

    private var timer: IMScheduler.Cancellable? = null
    private var answered = false

    /** 正在探。已经在探就不再发第二个 ping——网络来回跳时一次只探一个。 */
    val armed: Boolean get() = timer != null

    /**
     * 发探测 ping，[PROBE_MS] 后没收到下行就回调 [onDead]。
     *
     * 调用方在连接关闭时**必须** [cancel]：否则这一代的判决会落到下一代连接头上。
     */
    fun arm(sendPing: () -> Unit, onDead: () -> Unit) {
        if (timer != null) return
        answered = false
        sendPing()
        timer = scheduler.postDelayed(PROBE_MS) {
            timer = null
            if (!answered) onDead()
        }
    }

    /** 收到任何下行帧都算活，不必是那条 pong。 */
    fun onInbound() {
        answered = true
    }

    fun cancel() {
        timer?.cancel()
        timer = null
    }

    companion object {
        /** 局域网与 4G 下 pong 都在几百毫秒内回来；3 秒没回就不是慢，是断了。 */
        const val PROBE_MS = 3_000L
    }
}
