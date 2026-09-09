package com.imrtc.uikit

/**
 * 「这一下该不该（重新）排定自动隐藏」——[IMChromeGate] 里唯一一段纯判定。
 *
 * # 为什么值得单独摘出来
 *
 * 与 `IMIceGiveUp` / `IMNegotiationGate` 同一个理由：[IMChromeGate] 要碰 `Handler`
 * 与一堆 `View`，JVM 单测里根本构造不出来，**而这段判定恰恰是最容易写错的**。
 *
 * 写错的后果已经发生过一次，而且藏了很久：原先 `armAutoHide` 无条件
 * `removeCallbacks` + `postDelayed`，而 `IMCallKit.startTimer()` **每秒**推一次状态
 * （通话时长 +1）一路走到 `IMCallView.render`，render 末尾又会 arm 一次——
 * **3 秒的计时每 1 秒被重置，永远走不完，控制条从来没自动隐藏过。**
 * 当时看不出来，是因为「点一下画面」那条路能手动收起，像是功能在工作。
 *
 * iOS 侧没踩到，是因为它每秒的 tick **只刷标题栏**（`startTicking` 调的是
 * `renderHeader` 不是 `render`）。**两端的 tick 粒度不同**，所以这道「别重置」
 * 是 Android 特有的，不是照搬 iOS 能得到的。
 */
internal class IMAutoHideCountdown {

    /** 有没有一个待触发的隐藏。 */
    var pending: Boolean = false
        private set

    /**
     * 要不要把定时器（重新）排上。
     *
     * @param canAutoHide 此刻允不允许自动隐藏（版式是视频、且已接通）。
     * @param restart `true` = 控制条**刚显示出来**，用户刚看到它，理应从头数满 3 秒；
     *   `false` = 只是又走了一次 render，**已经在数就别打扰**。
     * @return `true` 表示调用方该去 `postDelayed`；`false` 表示什么都别做。
     */
    fun arm(canAutoHide: Boolean, restart: Boolean): Boolean {
        if (!restart && pending) return false
        pending = canAutoHide
        return pending
    }

    /** 定时器触发了，或者被撤了。 */
    fun clear() {
        pending = false
    }
}
