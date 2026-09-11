package com.imrtc.engine.signaling

/**
 * 「后台重连节奏：不清零，最长 3 秒」的判定逻辑（2026-09-11，真机 OPPO/ColorOS）。
 *
 * ColorOS 的 `OAppNetControlService` 在 App 切后台后约每 3 秒打一条
 * `Close socket:[...] cause:App bg(IMMEDIATELY)`，把后台 App 的 socket 强制掐断——
 * 服务端看到的是 `connection reset by peer`。默认的 1/2/4/8/15/30s 退避在这种场景下
 * 会越退越慢，而服务端「被叫刚断线在 30s 恢复窗口内」时只等 **5 秒**就判离线转振铃，
 * 退避一旦超过 5 秒，后台就接不到来电了。
 *
 * ## 四条规则
 *
 * 1. **前台**：不管连了多久，断开就把退避归零——**行为不变**。
 * 2. **后台 + 连上不到 10 秒就断**（ColorOS 掐后台 socket 正是这个形态）：**不归零**，
 *    退避档照常往上走，但**这一次等待封顶 3 秒**（含抖动，[BACKGROUND_SHORT_LIVED_CAP_MS]），
 *    于是节奏是 1,2,3,3,3…秒，每分钟约 10 次，间隔落进服务端 5 秒的等待窗口。
 * 3. **后台 + 连上超过 10 秒才断**（[BACKGROUND_ALIVE_RESET_MS]）：这不是被 ColorOS
 *    掐的那种，跟前台一样归零。
 * 4. **从未连上成功**（网络不通、握手失败）：不管前后台，都按原退避走到 30s——
 *    否则断网时会被误判成「后台短命连接」，变成每 3 秒空连一次。
 *
 * 「回到前台立刻重连、退避归零」不在这里——那条不是「算下一次等多久」，是
 * 「取消正在等的那次，另起一次」，逻辑上属于 [IMSignalConnection.setForeground]。
 *
 * **纯函数，不碰 IO、不碰时钟、不碰调用方的任何私有状态**：单测直接构造
 * `wasConnected` / `aliveMs` / `foreground` 三个入参验证，不需要假传输假调度器。
 */
internal object IMReconnectPolicy {

    /** 一次重连该按哪条规则走。[capMs] 为 null 表示不封顶，直接用退避算出来的值。 */
    data class Plan(val capMs: Long?, val rule: String)

    /** 后台连接活过这个时长才算「不是被 ColorOS 掐的那种」，归零退避（规则③）。 */
    const val BACKGROUND_ALIVE_RESET_MS = 10_000L

    /** 后台短命连接的重连等待封顶（含抖动，规则②）：落进服务端来电等待恢复的 5 秒窗口内。 */
    const val BACKGROUND_SHORT_LIVED_CAP_MS = 3_000L

    /**
     * @param wasConnected 这条连接有没有握手成功过（[IMSignalConnection.onHelloOk]）。
     *   规则④的判据——**必须排在最前面判断**，不然网络彻底不通时会被误判成
     *   「后台短命连接」，变成每 3 秒空连一次。
     * @param aliveMs 握手成功到断开经过了多少毫秒；从未连上传 `null`。
     * @param foreground 断开这一刻 App 在不在前台。
     * @param resetBackoff 判定该归零时回调它一次——[IMBackoff] 由调用方持有，
     *   这里不持有、也不读写任何退避状态，只决定「要不要归零」与「要不要封顶」。
     */
    fun plan(wasConnected: Boolean, aliveMs: Long?, foreground: Boolean, resetBackoff: () -> Unit): Plan {
        // 规则④：从未连上成功——不管前后台都按原退避走满。
        if (!wasConnected) return Plan(null, "没连上常规退避")
        // 规则①：前台，行为不变——断开就归零。
        if (foreground) {
            resetBackoff()
            return Plan(null, "前台常规")
        }
        // 规则③：后台但这条连接活过了 10 秒——跟前台一样归零。
        if (aliveMs != null && aliveMs >= BACKGROUND_ALIVE_RESET_MS) {
            resetBackoff()
            return Plan(null, "后台活过10s归零")
        }
        // 规则②：后台 + 连上不到 10 秒就断——不归零，但这一次封顶 3 秒（含抖动）。
        return Plan(BACKGROUND_SHORT_LIVED_CAP_MS, "后台短命连接封顶3s")
    }
}
