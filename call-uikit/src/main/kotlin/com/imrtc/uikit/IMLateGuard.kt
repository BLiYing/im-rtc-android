package com.imrtc.uikit

/**
 * 「**异步回来之后，这件事还算不算数**」的那几条判据。
 *
 * Kit 里有好几处是「先切界面 → 等一个可能很久才回来的东西 → 再发帧」：
 * 权限门可能停在系统框 / 说明卡上好几秒，媒体启动也不是同步的。
 * 这期间用户完全可能已经按了红键、对方可能已经挂了、这一屏可能已经被
 * [IMRedButtonWatchdog] 本地收场——**回调回来时的世界和发起时不是同一个**。
 *
 * 不看一眼就往下走的代价是具体的：
 * - 拨出侧：屏幕早收了，`call.invite` 却在用户点「允许」的那一刻才发出去——
 *   **对方响起铃来，主叫这边一个界面都没有**，而随后的 `call.begin`
 *   又会把一通他已经取消掉的电话重新拉起来；
 * - 被叫侧：接一通**已经不存在的电话**，服务端回 `1401 call_not_found`。
 *
 * 单独成类：一是这几条是纯函数，放这儿才能在 JVM 单测里咬住；
 * 二是 `IMCallKit` 顶着 600 行的体量红线（CONVENTIONS §2）。
 * iOS 那边对应的是 `IMCallController` 里过完 gate 之后的那两处 `guard`。
 */
internal object IMLateGuard {

    /** 拨出那一屏还在不在。过完权限门要拿它挡一下再发 `call.invite`。 */
    fun stillPlacing(state: IMCallViewState): Boolean =
        state.phase == IMCallViewState.Phase.OUTGOING

    /** 来电那一屏还在不在。过完权限门要拿它挡一下再发 `call.accept`。 */
    fun stillIncoming(state: IMCallViewState): Boolean =
        state.phase == IMCallViewState.Phase.INCOMING

    /**
     * 这一屏还停在通话里没有。[IMRedButtonWatchdog] 到点时拿它判断该不该本地收场。
     *
     * `ENDED` 也算「已经走了」：结束画面本来就会自己收，再收一次会把 `endReason` 抹掉。
     */
    fun stillInCall(state: IMCallViewState): Boolean =
        state.phase != IMCallViewState.Phase.IDLE && state.phase != IMCallViewState.Phase.ENDED
}
