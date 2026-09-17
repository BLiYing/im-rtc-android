package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.protocol.IMCallOptionsGuard
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMProtocolEnums
import com.imrtc.engine.statemachine.IMEmittedEvent

/**
 * `IMCallEngine.call` / `inviteMore` 上线路**之前**的本地关卡，以及 `call` 动作的参数拼装。
 *
 * 拆成单独文件是体量红线（CONVENTIONS §2）——门面已经踩线，这段逻辑本来也和门面的「核心循环」是两个关注点。
 * 与 Web 的 `callGuards.ts` 同一套规则：拒绝本身**交给调用方**（结果回调），不发 `onError`（R3）。
 */
internal object IMCallInvite {

    /**
     * 名单里有自己：服务端会以 `1004` 拒掉，但那条链路上界面已经乐观地进了「正在呼叫…」，
     * 错误只是一条没头没尾的 1004——本地先拦。
     */
    fun selfViolation(selfUid: String, calleeIds: List<String>, forType: String): IMRTCError? {
        if (selfUid.isEmpty() || selfUid !in calleeIds) return null
        IMRTCLog.w("engine", "名单里含自己，已就地拒掉 type=$forType")
        return IMRTCError.of(IMErrorCode.BAD_PARAMS, "callee_ids 不能含自己", forType)
    }

    /** 群号 / user_data 超限（`HOST_INTEGRATION_DESIGN.md` §3.3）。 */
    fun optionsViolation(options: IMCallOptions): IMRTCError? {
        val violation = IMCallOptionsGuard.validate(options.chatGroupId, options.userData) ?: return null
        IMRTCLog.w("engine", "call 选项超限，已就地拒掉：$violation")
        return IMRTCError.of(IMErrorCode.BAD_PARAMS, violation, IMFrameType.CALL_INVITE)
    }

    /**
     * `call()` 被本地拒掉：先抛一条 `onCallEnd(reason="error", durationSec=0)`，再把错误交给调用方。
     *
     * 界面（Kit / 宿主）在调 `call()` 之前就切到了「正在呼叫…」，收起它靠这个事件——
     * 与「服务端拒了 invite」（`call_failed`）走同一个出口。**不上线路。**
     */
    fun rejectLocally(dispatcher: IMEventDispatcher, result: IMCallResult<String>, error: IMRTCError) {
        dispatcher.dispatch(
            IMEmittedEvent(
                "onCallEnd",
                mapOf(
                    "call_id" to IMJson.Str(""),
                    "reason" to IMJson.Str(IMCallEndReason.ERROR.wire),
                    "duration_sec" to IMJson.Num(0),
                    "ended_by" to IMJson.Str(""),
                ),
            ),
        )
        result.finish(error)
    }

    /** 不带选项的 `call` 动作参数（三参数重载）。 */
    fun plainArgs(calleeIds: List<String>, mediaType: String, isGroup: Boolean): Map<String, IMJson> = mapOf(
        "callee_ids" to IMJson.Arr(calleeIds.map { IMJson.Str(it) }),
        "media_type" to IMJson.Str(mediaType),
        "is_group" to IMJson.Bool(isGroup),
    )

    /** 带选项的 `call` 动作参数——**总是带上 chat_group_id / user_data / timeout_sec**。 */
    fun args(calleeIds: List<String>, mediaType: String, options: IMCallOptions): Map<String, IMJson> {
        val resolvedTimeoutSec = if (options.timeoutSec > 0) options.timeoutSec.toLong() else IMProtocolEnums.DEFAULT_TIMEOUT_SEC
        return plainArgs(calleeIds, mediaType, options.isGroup) + mapOf(
            "chat_group_id" to IMJson.Str(options.chatGroupId),
            "user_data" to IMJson.Str(options.userData),
            "timeout_sec" to IMJson.Num(resolvedTimeoutSec),
        )
    }
}
