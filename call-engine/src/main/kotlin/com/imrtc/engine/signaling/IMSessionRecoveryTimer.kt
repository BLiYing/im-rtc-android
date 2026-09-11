package com.imrtc.engine.signaling

import com.imrtc.engine.log.IMRTCLog

/**
 * 「服务端那一侧的会话已经不可能再恢复」的倒计时（协议 §1.4 的恢复窗口过了）。
 *
 * 与「重连上了但 `resumed=false`」是同一件事，只是**不必等重连成功**——网络一直不回来
 * 的话那一刻永远不会到。少了它，界面就永远停在「正在重连」、连挂断都点不动（真机 2026-09-08）。
 *
 * # 倒计时该取多长：不是恢复窗口那 30 秒
 *
 * 服务端的 30 秒**不是从我们断开的那一刻算起的**，是从**它自己察觉**的那一刻算起。
 * 而它靠读超时察觉：连续 3 个心跳周期收不到任何东西才判死（§1.3）。
 * 我们断开时距离上一帧最多一个心跳周期，所以：
 *
 *     服务端察觉    = 断开后 (3 × ping − 上一帧到断开的间隔) ∈ [2×ping, 3×ping]
 *     它的窗口到期  = 察觉 + 30s，最晚 = 断开后 3×ping + 30s
 *
 * 按默认 15 秒心跳就是 **45 + 30 = 75 秒**，再加一点余量避开「同一秒」的竞争。
 *
 * **必须取上界，不能取更短**：取短了就会撒谎——真机 2026-09-08 那通，断开 14 秒后重连
 * **成功恢复**，通话好端端地继续。在那之前宣布「通话已结束」是把一通还能救回来的电话杀掉，
 * 而且服务端还认为我们在房里，房间里会挂着一个幽灵成员。**宁可让用户多看几十秒
 * 「正在重连」，也不能提前下结论。**
 *
 * # 只在第一次断开时起
 *
 * [armIfNeeded] 每一次重连失败都会被调，重排的话截止时刻就一直往后挪、永远不会到
 * （而那正是它要治的病）——[armIfNeeded] 靠「已经在计时就不再起一次」自己挡住了这个坑，
 * 调用方不用关心这件事。起点是第一次断开的那一刻，与服务端算的是同一笔账。
 */
internal class IMSessionRecoveryTimer(
    private val scheduler: IMScheduler,
    private val onUnrecoverable: () -> Unit,
) {
    private var timer: IMScheduler.Cancellable? = null

    /** 已经在倒计时了：`handleClosed` 每次重连失败都会调用本方法，只有第一次真正起表。 */
    fun armIfNeeded(pingSec: Long) {
        if (timer != null) return
        val delay = giveUpDelayMs(pingSec)
        IMRTCLog.i("signal", "${delay}ms 内若还连不上，服务端那一侧的会话就没了")
        timer = scheduler.postDelayed(delay) {
            timer = null
            IMRTCLog.w("signal", "断开已超过恢复窗口，会话不可恢复")
            onUnrecoverable()
        }
    }

    /** 连上了，或者宿主自己 stop 了：不用再倒计时。 */
    fun cancel() {
        timer?.cancel()
        timer = null
    }

    private fun giveUpDelayMs(pingSec: Long): Long =
        (SERVER_DEATH_PINGS * pingSec + RESUME_WINDOW_SEC + GIVE_UP_GRACE_SEC) * 1000

    private companion object {
        /** 协议 §1.4 的恢复窗口：30 秒。**四端同一个值**，服务端的 `ResumeWindow` 也是它。 */
        const val RESUME_WINDOW_SEC = 30L

        /** 服务端判一条连接死掉要连续几个心跳周期收不到东西（§1.3）。 */
        const val SERVER_DEATH_PINGS = 3L

        /** 余量：跨过服务端窗口到期那一刻再收场，别跟它抢同一秒。 */
        const val GIVE_UP_GRACE_SEC = 5L
    }
}
