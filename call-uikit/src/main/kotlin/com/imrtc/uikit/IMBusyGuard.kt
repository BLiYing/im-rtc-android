package com.imrtc.uikit

import android.widget.Toast

/**
 * 「已经在一场通话 / 会议里，又想发起另一场」的守门。
 *
 * **为什么要有**（2026-09-19 真机，Android 日志 17:03:13）：1v1 通话中收成小窗，回宿主去发起群通话——
 * `placeCall` 一进来就把界面状态整个换成「拨出中」（小窗消失、出现群通话呼叫页），Engine 随后把这次调用拒成
 * `2005`（`call_state=connected`），Kit 对 `2005` 一声不吭；结果是**通话还连着、界面却被换成了另一通**，
 * 没有任何提示。`joinCall` 早在 2026-09-15 就有同样的守门（[IMJoinCallState.allowedFrom]），`placeCall` 与
 * `joinMeeting` 漏了。现在三个入口共用这一条判据。
 *
 * **提示用系统 Toast，不用 [IMCallKit.hint]**：hint 画在通话界面里，通话收成小窗、人在宿主界面上时根本看不见。
 */
internal object IMBusyGuard {

    const val MESSAGE = "你正在通话中，请先结束当前通话"

    /** 只有界面空闲、或停在上一通的结束画面时，才能开始新的一场。 */
    fun allows(phase: IMCallViewState.Phase): Boolean =
        phase == IMCallViewState.Phase.IDLE || phase == IMCallViewState.Phase.ENDED

    /** 已在一场里：弹提示并返回 true，调用方直接 return（**不改界面、不发帧**）。 */
    fun blocks(message: String = MESSAGE): Boolean {
        if (allows(IMCallKit.state.phase)) return false
        toast(message)
        return true
    }

    /** 拿引擎去开始新的一场：没有引擎（Kit 未 start）或已在一场里（已弹提示）都返回 null。 */
    fun freeEngine() = IMCallKit.engine?.takeUnless { blocks() }

    /** 主线程弹 Toast。Kit 还没 start（没有 context）就什么都不做。 */
    fun toast(message: String) {
        val context = IMCallKit.appContext ?: return
        IMCallKit.main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}
