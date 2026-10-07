package com.imrtc.uikit

import com.imrtc.engine.IMCallEngine

/**
 * 两个 `IMCallKit.placeCall` 重载共用的流程：守门 → 进「正在呼叫…」→ 权限门 → 确保已登录 → 发 invite。
 * `dispatch` 只是最后真正发帧的那一下不同。
 *
 * 拆成单独文件是不想让 `IMCallKit.kt` 再长（体量红线，CONVENTIONS §2）。
 */
internal object IMPlaceCall {

    fun place(
        calleeIds: List<String>,
        mediaType: String,
        isGroup: Boolean,
        chatGroupId: String,
        userData: String,
        dispatch: (IMCallEngine) -> Unit,
    ) {
        val instance = IMBusyGuard.freeEngine() ?: return
        IMCallKit.update(IMCallViewReducer.outgoing(IMCallKit.state, calleeIds, mediaType, isGroup, chatGroupId, userData))
        IMCallKit.ensurePermissions(IMPermissionGate.devicesForPlacing(mediaType, isGroup)) { outcome ->
            when (outcome) {
                IMPermissionGate.Outcome.OK, IMPermissionGate.Outcome.CAMERA_BLOCKED -> {
                    if (!IMCallKit.stillPlacing()) return@ensurePermissions
                    // 没登上先补一次（KIT_TOKEN_PROVIDER_DESIGN §6）；等的这段时间界面照常是「正在呼叫…」。
                    IMKitLogin.thenReady({ IMLateGuard.stillPlacing(IMCallKit.state) }) {
                        // 等登录期间用户可能已经按了红键：这一屏不在了就不发 invite。
                        if (IMCallKit.stillPlacing()) send(instance, outcome, dispatch)
                    }
                }
                // 同上先看一眼：这一屏可能已经不在了，reset() 会把无关的当前状态整个抹掉。
                IMPermissionGate.Outcome.MIC_BLOCKED, IMPermissionGate.Outcome.CANCELLED ->
                    if (IMCallKit.stillPlacing()) IMCallKit.update(IMCallViewReducer.reset())
            }
        }
    }

    private fun send(instance: IMCallEngine, outcome: IMPermissionGate.Outcome, dispatch: (IMCallEngine) -> Unit) {
        if (outcome == IMPermissionGate.Outcome.CAMERA_BLOCKED) {
            IMCallKit.update(IMCallViewReducer.cameraBlocked(IMCallKit.state))
        } else {
            // 摄像头到手、而且开着才接采集——**拨出中就该看见自己**（草图 §03-E）。
            IMCallKit.onLocalMediaStarted()
        }
        IMCallKit.syncCameraIntent(instance)
        dispatch(instance)
    }
}
