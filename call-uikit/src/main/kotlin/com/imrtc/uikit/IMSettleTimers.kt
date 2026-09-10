package com.imrtc.uikit

/**
 * 邀请中的格子拿到终局（已拒绝 / 未接听）之后，**停 2s 再收**（交互稿 §05 G3）。
 *
 * 立刻收掉的话，那一格是「闪一下就没了」——用户根本来不及看清是拒绝还是没接。
 *
 * # 记账规则
 *
 * **一个 uid 一只表，已经在排队的不重排**。`room.active_speakers` 一秒来好几帧，
 * 每次渲染都重排的话那一格永远收不掉：每来一帧就把倒计时推回 2 秒。
 *
 * 单独成类有两个理由：`IMCallKit` 顶着 600 行的体量红线（CONVENTIONS §2）；
 * 以及定时器经 [schedule] / [unschedule] 注入之后，**这套记账在 JVM 单测里咬得住**。
 *
 * @param schedule 延时执行。真身是 `Handler.postDelayed`。
 * @param unschedule 撤掉还没跑的那一次。真身是 `Handler.removeCallbacks`。
 * @param holdMs 终局停多久再收。默认 [IMKitTheme.SETTLED_HOLD_MS]。
 */
internal class IMSettleTimers(
    private val schedule: (Long, Runnable) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val holdMs: Long = IMKitTheme.SETTLED_HOLD_MS,
) {

    private val timers = HashMap<String, Runnable>()

    /** 此刻有几只表在排队。给测试看。 */
    val pending: Int get() = timers.size

    /**
     * 给 [uids] 里**还没排过队**的那些排上；到点回调 [onExpire]。
     *
     * 调用方每次渲染都会调它，所以「已经在排队的不重排」这条是必须的，不是优化。
     */
    fun scheduleAll(uids: Collection<String>, onExpire: (String) -> Unit) {
        uids.filter { it !in timers }.forEach { uid ->
            val remove = Runnable {
                timers.remove(uid)
                onExpire(uid)
            }
            timers[uid] = remove
            schedule(holdMs, remove)
        }
    }

    /** 全撤掉（通话结束 / Kit stop）。**重复调用无害**。 */
    fun clear() {
        timers.values.forEach { unschedule(it) }
        timers.clear()
    }
}
