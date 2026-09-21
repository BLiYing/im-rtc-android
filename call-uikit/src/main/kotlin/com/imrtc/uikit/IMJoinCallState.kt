package com.imrtc.uikit

/**
 * `IMCallKit.joinCall` 的状态与流程：**主动加入进行中的群通话**（`HOST_INTEGRATION_DESIGN.md` §3.4）。
 *
 * 拆成单独文件是不想让 `IMCallKit.kt` 再长（体量红线，CONVENTIONS §2）。
 */
internal object IMJoinCallState {

    /**
     * 此刻能不能开始加入：**只有界面空闲、或停在上一通的结束画面时才行**。
     *
     * 已经在一场里时 Engine 只会本地回一个 2005、不会有 `onCallEnd`；而加入流程第一步就把界面切成
     * 「接通中…」——放行的话，正在进行的那通电话的界面被盖掉、再也收不回来（2026-09-15 代码审查，三端同一个坑）。
     */
    fun allowedFrom(phase: IMCallViewState.Phase): Boolean = IMBusyGuard.allows(phase)

    /** [IMCallKit.joinCall] 的实现：守门 → 进「接通中…」→ 麦克风权限门 → 发 `call.join`。 */
    fun start(callId: String) {
        val instance = IMCallKit.engine ?: return
        // Toast 而不是 hint：通话收成小窗时 hint 看不见（见 [IMBusyGuard]）。
        if (IMBusyGuard.blocks(IMText.t("busy.joinBlocked"))) return
        IMCallKit.update(IMCallViewReducer.joining(IMCallKit.state, callId))
        // 与接听同一道权限门，但只要麦克风：加入之前不知道这通是不是视频，摄像头等用户在通话里再开。
        IMCallKit.ensurePermissions(IMPermissionGate.devicesFor("audio", withCamera = false)) { outcome ->
            // 权限门可能停在系统框上好几秒，这期间用户可能已经收起了这一屏。
            val state = IMCallKit.state
            val stillJoining = state.phase == IMCallViewState.Phase.CONNECTING && state.callId == callId
            val blocked = outcome == IMPermissionGate.Outcome.MIC_BLOCKED || outcome == IMPermissionGate.Outcome.CANCELLED
            when {
                !stillJoining -> Unit
                blocked -> IMCallKit.update(IMCallViewReducer.reset())
                // 被拒的码从这次调用的结果里取（2.0.0），见 [IMKitResults.joinCall]。
                else -> instance.joinCall(callId, IMKitResults.joinCall(callId))
            }
        }
    }
}
