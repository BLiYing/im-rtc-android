package com.imrtc.uikit

/**
 * 通话时长的秒表：接通后每秒推一次界面。
 *
 * 定时器经 [schedule] / [unschedule] 注入，JVM 单测拿假调度器就能验
 * 「[start] 会先把上一只停掉」——不停的话重连一次就多一只表，界面上的秒数开始跳着走。
 *
 * @param schedule 延时执行。真身是 `Handler.postDelayed`。
 * @param unschedule 撤掉还没跑的那一次。真身是 `Handler.removeCallbacks`。
 * @param periodMs 每隔多久推一次。
 */
internal class IMDurationTicker(
    private val schedule: (Long, Runnable) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val periodMs: Long = 1_000,
) {

    private var ticking: Runnable? = null

    /** 此刻在不在走。给测试看。 */
    val running: Boolean get() = ticking != null

    /** 开始走，每 [periodMs] 回调一次 [onTick]。**先把上一只停掉**。 */
    fun start(onTick: () -> Unit) {
        stop()
        val tick = object : Runnable {
            override fun run() {
                onTick()
                schedule(periodMs, this)
            }
        }
        ticking = tick
        schedule(periodMs, tick)
    }

    /** 停表。**重复调用无害**。 */
    fun stop() {
        ticking?.let { unschedule(it) }
        ticking = null
    }
}

/**
 * 一次性提示（「通话已满员」「对方已拒接」）**停几秒就撤**。
 *
 * `statusText` 里 hint 优先于时长，不撤的话「通话已满员」会顶着标题栏直到通话结束，
 * 计时器再也不出现（规范 §08：这些是 toast，不是常驻状态）。
 *
 * **只清掉自己那一条**：中途又来一条新提示时，不该被上一条的计时器抹掉——
 * 那会让新提示只闪一下就没了，而用户根本没看清写的是什么。
 *
 * @param schedule 延时执行。真身是 `Handler.postDelayed`。
 * @param unschedule 撤掉还没跑的那一次。真身是 `Handler.removeCallbacks`。
 * @param holdMs 提示停多久。默认 [IMKitTheme.HINT_HOLD_MS]。
 */
internal class IMHintExpiry(
    private val schedule: (Long, Runnable) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val holdMs: Long = IMKitTheme.HINT_HOLD_MS,
) {

    private var pending: Runnable? = null

    /** 此刻有没有一条在计时。给测试看。 */
    val armed: Boolean get() = pending != null

    /**
     * 给 [text] 这条提示排上过期。空串 = 只撤掉上一条，不排新的。
     *
     * [expire] 到点时执行；它自己再确认「此刻挂着的还是不是我这条」——
     * 那份状态归 `IMCallKit` 管，这里不认识 hint。
     */
    fun arm(text: String, expire: (String) -> Unit) {
        pending?.let { unschedule(it) }
        pending = null
        if (text.isEmpty()) return
        val task = Runnable {
            pending = null
            expire(text)
        }
        pending = task
        schedule(holdMs, task)
    }

    /** 撤掉还没到点的那一条。**重复调用无害**。 */
    fun clear() {
        pending?.let { unschedule(it) }
        pending = null
    }
}
