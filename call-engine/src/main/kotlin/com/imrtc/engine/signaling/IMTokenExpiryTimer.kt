package com.imrtc.engine.signaling

import com.imrtc.engine.log.IMRTCLog

/**
 * 接入票到期前的主动换票定时器。
 *
 * # 它挡的是什么
 *
 * 服务端只在 `sys.hello` 握手时验一次票，之后永不复查。所以票过期**不会**断开已建立的
 * 连接——真正出问题的是**过期之后的第一次重连**：那一次握手撞 `4401`，退避重试三次，
 * 然后 `onKickedOut`，用户被踢回登录页。而重连（切基站、NAT 超时、切后台回来）
 * 是必然会发生的，只是不知道什么时候。
 *
 * 有了 `sys.hello.ok` 下发的 `token_expires_at_ms`，可以在到期**前**提醒宿主换票，
 * 把那次「必然发生但时间不定」的掉线消灭在发生之前。
 *
 * 见 im-rtc-server/docs/design/TOKEN_LIFECYCLE_DESIGN.md §6-①②。
 *
 * # 为什么单独一个类
 *
 * 它是纯逻辑（时刻计算 + 一个定时器），用假调度器就能把 12 小时的行为在几毫秒内验完，
 * 不需要真的等。放进 IMSignalConnection 会让那个类既管连接又管票期，两件事纠缠在一起。
 */
internal class IMTokenExpiryTimer(
    private val scheduler: IMScheduler,
    /** 到期前多久提醒。默认 [DEFAULT_LEAD_MS]。 */
    private val leadMs: Long = DEFAULT_LEAD_MS,
    /** 触发时调它。**同一张票只会触发一次**。 */
    private val onWillExpire: (expiresAtMs: Long) -> Unit,
) {

    internal companion object {
        /**
         * 到期前多久提醒宿主换票。
         *
         * 60 秒的依据是「够宿主打一次自家后台的换票接口，又不至于早到让人莫名其妙」。
         * 再短的话，一次慢请求就跨过了到期时刻。**五端同一个数。**
         */
        const val DEFAULT_LEAD_MS = 60_000L
    }

    private var timer: IMScheduler.Cancellable? = null

    val isArmed: Boolean get() = timer != null

    /**
     * 按新的到期时刻重新武装。
     *
     * - `expiresAtMs <= 0` = 服务端说「未知」（协议 §1.2）→ **解除武装，不报错**。
     *   这条路径退化成没有这个定时器时的被动行为，是刻意降级而不是故障。
     * - 已经进入提前量窗口（含已过期）→ **立刻触发一次**，而不是静默跳过。
     *   票只剩 10 秒时更需要提醒宿主，不是更不需要。
     */
    fun arm(expiresAtMs: Long) {
        disarm()
        if (expiresAtMs <= 0L) return

        val delayMs = (expiresAtMs - leadMs - scheduler.nowMs()).coerceAtLeast(0L)
        IMRTCLog.d("signal", "票到期提醒已排定 delayMs=$delayMs expiresAtMs=$expiresAtMs")
        // 即使延迟是 0 也走调度器，不同步回调：arm 是在握手成功的路径上调的，
        // 同步回调会让宿主的 updateToken 重入到还没走完的连接流程里。
        timer = scheduler.postDelayed(delayMs) {
            timer = null
            onWillExpire(expiresAtMs)
        }
    }

    /** 解除武装。重复调用安全。 */
    fun disarm() {
        timer?.cancel()
        timer = null
    }
}
