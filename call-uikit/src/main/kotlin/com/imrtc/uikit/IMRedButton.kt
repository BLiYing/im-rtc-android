package com.imrtc.uikit

import android.os.Handler
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.log.IMRTCLog

/**
 * 红键：按下去该发哪个结束动作、发出去没人应时怎么收场。
 *
 * 从 [IMCallKit] 拆出来是体量红线（CONVENTIONS §2）；这一整块本来也是一件事——
 * 「用户要出去，这条路必须走得完」。状态与 Engine 由 IMCallKit 注入，本类不持有它们。
 */
internal class IMRedButton(
    private val main: Handler,
    private val state: () -> IMCallViewState,
    private val update: (IMCallViewState) -> Unit,
    private val engine: () -> IMCallEngine?,
) {

    /** 看门狗（为什么要有它、判据为什么是「走没走」，见 [IMRedButtonWatchdog]）。 */
    private val watchdog = IMRedButtonWatchdog(
        schedule = { delayMs, task -> main.postDelayed(task, delayMs) },
        unschedule = { task -> main.removeCallbacks(task) },
    )

    /** 这一屏已经走了，看门狗撤掉。重复调用无害。 */
    fun disarm() = watchdog.disarm()

    fun press() {
        // 认得出动作的那四条也要盯着——**帧发不出去与认不出动作是两回事**。
        val current = state()
        val action = current.hangupAction
        val reason = IMCallViewState.watchdogReason(action)
        // **按下红键要留一条**：2026-09-13 iOS frank 那次到底按没按、按的时候在哪个阶段，事后只能靠猜。
        IMRTCLog.i("kit", "按下红键 action=$action phase=${current.phase}")
        if (action != IMCallViewState.Action.NONE) {
            watchdog.arm { armedEnd(reason) }
        }
        val instance = engine()
        when (action) {
            // **会议房里没有 call，结束动作是 leaveRoom**。红按钮无条件走 hangup 的话，通话机会把它本地拒成 2005。
            IMCallViewState.Action.LEAVE_ROOM -> instance?.leaveRoom()
            IMCallViewState.Action.REJECT -> instance?.reject()
            IMCallViewState.Action.CANCEL -> instance?.cancel()
            IMCallViewState.Action.HANGUP -> instance?.hangup()
            /*
             **红按钮永远不许是静默空转。**

             用户按挂断时的意图是没有歧义的：把我弄出去。如果这一刻状态机认不出
             该发哪一种结束帧（phase 已经不是 incoming/outgoing/connecting/connected），
             那说明本地记账已经和服务端对不上了——继续挂在这一屏只会让用户**困在
             一个不存在的通话里**：真机 2026-09-07 就是这样，通话早在 19 秒前结束、
             服务端只回 1203，而界面还在，点什么都没反应。
             这时唯一正确的动作是**本地收场**，而不是什么都不做。
            */
            IMCallViewState.Action.NONE -> endLocally("认不出该发哪种结束帧", reason)
        }
    }

    /** 看门狗到点了：这一屏还在就本地收场，**并让 Engine 也收场**。 */
    private fun armedEnd(reason: String) {
        if (!IMLateGuard.stillInCall(state())) return
        endLocally("${watchdog.timeoutMs}ms 没等到结束事件", reason)
        /*
         **界面收了，Engine 也要收。** 只收界面的话，结束帧没发出去时 Engine 还留在通话与房间里：
         服务端照样当他在场，别人一直看得见他，摄像头麦克风也还开着
         （2026-09-13 14:54 iOS frank，直到 14:58 整通结束才被带走；本端原先是同一个缺口）。
         `forceEnd` 不经 engine 线程，直接把结束帧交给信令连接，并在本地收场。
        */
        engine()?.forceEnd()
    }

    /** 结束这一屏，**不依赖服务端应答**（为什么必须能本地走完，见 [IMRedButtonWatchdog]）。 */
    private fun endLocally(why: String, reason: String) {
        val current = state()
        IMRTCLog.w("kit", "红按钮本地收场：$why（phase=${current.phase} reason=$reason）")
        update(IMCallViewReducer.ended(current, reason))
        main.postDelayed({ if (state().phase == IMCallViewState.Phase.ENDED) update(IMCallViewReducer.reset()) }, 1_500)
    }
}
