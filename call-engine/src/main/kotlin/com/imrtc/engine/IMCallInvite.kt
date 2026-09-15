package com.imrtc.engine

import com.imrtc.engine.protocol.IMCallOptionsGuard
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.statemachine.IMEmittedEvent
import com.imrtc.engine.statemachine.IMMachineInput

/**
 * `IMCallEngine.call(userIds, mediaType, options)` 的校验与参数拼装。
 *
 * 拆成单独文件是体量红线（CONVENTIONS §2）——`IMCallEngine.kt` 已经踩线，
 * 这段逻辑本来也和门面的「核心循环」是两个关注点。
 */
internal object IMCallInvite {

    /** 0 = 用协议默认值。 */
    private const val DEFAULT_TIMEOUT_SEC = 30L

    /**
     * 校验通过就返回喂给状态机的 [IMMachineInput.Act]；校验不过直接经 [dispatcher] 抛
     * `onError(1004)` + `onCallEnd(reason="error")`（**不上线路**，与「callee_ids 里有自己」
     * 服务端拒绝走同一个出口）并返回 null——调用方看到 null 就什么都不用再做。
     */
    fun act(
        dispatcher: IMEventDispatcher,
        calleeIds: List<String>,
        mediaType: String,
        options: IMCallOptions,
    ): IMMachineInput.Act? {
        val violation = IMCallOptionsGuard.validate(options.chatGroupId, options.userData)
        if (violation != null) {
            dispatcher.error(IMErrorCode.BAD_PARAMS.code, violation)
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
            return null
        }
        return IMMachineInput.Act("call", args(calleeIds, mediaType, options))
    }

    /** `call` 动作的完整参数表——**总是带上 chat_group_id / user_data / timeout_sec**。 */
    private fun args(calleeIds: List<String>, mediaType: String, options: IMCallOptions): Map<String, IMJson> {
        val resolvedTimeoutSec = if (options.timeoutSec > 0) options.timeoutSec.toLong() else DEFAULT_TIMEOUT_SEC
        return mapOf(
            "callee_ids" to IMJson.Arr(calleeIds.map { IMJson.Str(it) }),
            "media_type" to IMJson.Str(mediaType),
            "is_group" to IMJson.Bool(options.isGroup),
            "chat_group_id" to IMJson.Str(options.chatGroupId),
            "user_data" to IMJson.Str(options.userData),
            "timeout_sec" to IMJson.Num(resolvedTimeoutSec),
        )
    }
}
