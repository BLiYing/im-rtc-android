package com.imrtc.uikit

import com.imrtc.engine.IMResultCallback
import com.imrtc.engine.log.IMRTCLog

/**
 * Kit 调 Engine 发起类方法时交过去的结果回调（2.0.0，server `docs/design/ACTION_RESULT_DESIGN.md`）。
 *
 * **失败的码直接从这次调用的结果里取**，不再在 `onError` 里靠 `IMJoinCallState.joining` 之类的标记猜归属——
 * 并发时猜不准（加入中有人加人被 1409 拒，两句文案就串了）。**界面收起仍靠 `onCallEnd` / `onRoomLeft`**：
 * Engine 在交付结果之前就把那条状态事件抛完了，这里只挑文案、收占位格。文案不变。
 *
 * 全部回调都在主线程上来。
 */
internal object IMKitResults {

    /** 失败只留日志：界面收起靠事件，没有要提示的（接听、拒接、挂断、开关麦克风 / 摄像头、进离会议）。 */
    fun <T> logOnly(what: String) = IMResultCallback<T> { _, error ->
        if (error != null) IMRTCLog.w("kit", "$what 失败 code=${error.code} ${error.name} for=${error.forType}")
    }

    /** 拨号：宿主邀请鉴权回调拒绝（1409）与已在别处通话（1408）有专属提示，其余码由 `onCallEnd(error)` 那条路负责。 */
    fun placeCall() = IMResultCallback<String> { _, error ->
        if (error == null) return@IMResultCallback
        IMRTCLog.w("kit", "拨号被拒 code=${error.code} ${error.name}")
        when (error.code) {
            INVITE_DENIED -> IMCallKit.hint(IMText.t("hint.inviteRejected"))
            // 本端界面看着空闲、但同一账号在别的设备上通话：入口守门拦不到，只能靠服务端回 1408。
            ALREADY_IN_CALL -> IMBusyGuard.toast(IMBusyGuard.MESSAGE)
        }
    }

    /**
     * 加人（交互稿 §05、HOST_INTEGRATION_DESIGN §3.4）：满员出提示；本端已不在通话里（1407）把入口藏掉；
     * 宿主拒绝（1409）出另一句。**不管哪种失败都把这一批占位格收回来**——服务端拒掉时不会有
     * `onUserReject` / `onUserNoResponse`，不收的话占位格会一直挂着「呼叫中…」。通话本身不受影响。
     */
    fun inviteMore() = IMResultCallback<Unit> { _, error ->
        if (error == null) return@IMResultCallback
        IMRTCLog.w("kit", "加人被拒 code=${error.code} ${error.name}")
        IMCallKit.revokeLastInvite()
        when (error.code) {
            ROOM_FULL -> IMCallKit.hint(IMText.t("hint.roomFull"))
            NOT_CALL_OWNER -> IMCallKit.update(IMCallViewReducer.inviteDenied(IMCallKit.state))
            INVITE_DENIED -> IMCallKit.hint(IMText.t("hint.inviteRejected"))
        }
    }

    /**
     * 主动加入：任何失败都是「无法加入该通话」。服务端拒绝时 Engine 已经抛过 `onCallEnd(error)`（界面到了结束画面）；
     * 本地就拒掉的（`2005` 状态不对 / 已销毁）没有那条事件，这一屏还停在「接通中…」，要自己收回来。
     */
    fun joinCall(callId: String) = IMResultCallback<Unit> { _, error ->
        if (error == null) return@IMResultCallback
        IMRTCLog.w("kit", "加入被拒 call_id=$callId code=${error.code} ${error.name}")
        val state = IMCallKit.state
        if (state.phase == IMCallViewState.Phase.CONNECTING && state.callId == callId) {
            IMCallKit.update(IMCallViewReducer.reset())
        }
        IMCallKit.hint(IMText.t("hint.joinDenied"))
    }

    private const val ROOM_FULL = 1202
    private const val NOT_CALL_OWNER = 1407
    private const val INVITE_DENIED = 1409
    private const val ALREADY_IN_CALL = 1408
}
