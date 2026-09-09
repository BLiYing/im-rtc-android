package com.imrtc.uikit

/**
 * 红键按下之后**盯着这一屏到底走没走**；到点还在原地，就让调用方本地收场。
 *
 * # 为什么需要它
 *
 * 发出去的那一帧未必真的发得出去。2026-09-09 真机上，摄像头权限设成「每次询问」后
 * 发起视频呼叫：权限门在拨出中途没落定，`call.invite` **一帧没发**，而界面早已切成
 * outgoing。红键在 outgoing 映射到 `cancel`，引擎的通话状态机却还在 Idle，于是
 * **本地拒成 2005、一帧不发、也没有任何结束事件回来**——界面永远停在「正在呼叫…」，
 * 点什么都没反应。
 *
 * `IMCallViewState.hangupAction` 里那条「红按钮永远不许是静默空转」的兜底
 * （2026-09-07 为另一个同类问题写的）**只覆盖 `Action.NONE`**，覆不到这一格：
 * 这里动作是认得出的，只是**发出去没人应**。
 *
 * 所以判据不能是「认不认得出该发哪种结束帧」，只能是**「按下之后这一屏到底走没走」**。
 * 用户按红键时的意图没有歧义：把我弄出去。这条路必须在本地就能走完，
 * 不许依赖服务端应答。
 *
 * # 为什么单独成类
 *
 * 一是 `IMCallKit` 顶着 600 行的体量红线（CONVENTIONS §2）；二是**这样它才咬得住**——
 * 定时器经 [schedule] / [unschedule] 注入，JVM 单测拿一个假调度器就能把
 * 「按下 → 没人应 → 本地收场」和「按下 → 收到终态 → 不该收场」两条路都走一遍。
 *
 * @param schedule 延时执行。真身是 `Handler.postDelayed`。
 * @param unschedule 撤掉还没跑的那一次。真身是 `Handler.removeCallbacks`。
 * @param timeoutMs 等多久。默认见 [DEFAULT_TIMEOUT_MS]。
 */
internal class IMRedButtonWatchdog(
    private val schedule: (Long, Runnable) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** 正在跑的那一只。红键一次只按得下一个动作，一个槽就够。 */
    private var pending: Runnable? = null

    /** 此刻盯着没有。给测试与日志看。 */
    val armed: Boolean get() = pending != null

    /**
     * 开始盯着。重复调用只保留最后一次——按两下红键不该排两次收场。
     *
     * [onExpire] 在到点时执行；它自己再判断「这一屏是不是真的还没走」——
     * 状态归 `IMCallKit` 管，这里不认识 phase。
     */
    fun arm(onExpire: () -> Unit) {
        disarm()
        val watchdog = Runnable {
            pending = null
            onExpire()
        }
        pending = watchdog
        schedule(timeoutMs, watchdog)
    }

    /** 这一屏已经走了（或从头就没武装过），把它撤掉。**重复调用无害**。 */
    fun disarm() {
        pending?.let { unschedule(it) }
        pending = null
    }

    companion object {
        /**
         * 等服务端把这一屏收掉的最长时间。
         *
         * 3 秒是「慢网也该回来了」与「用户还没开始怀疑手机坏了」之间的那个数：
         * 正常路径上 `call.hangup.ok` 与 `call.ended` 都在百毫秒级
         * （真机日志里最慢一次 100ms）。
         */
        const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}
