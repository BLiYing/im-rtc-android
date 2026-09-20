package com.imrtc.engine

import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.statemachine.IMCallContext
import com.imrtc.engine.statemachine.IMCallRole
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMEmittedEvent

/**
 * 一通电话结束时的事实汇总（通话记录设计 §4）。字段全部来自 Engine 自己的通话状态。
 *
 * [isGroup] 为 false 时 [peer] 是对端 uid（主叫 = 被叫，被叫 = 主叫）；群通话恒空。
 * [durationSec] 由服务端给（不变量 I8），未接通恒 0。[role] 是 `"caller"` 或 `"callee"`。
 * [userData] 是主叫拨号时透传的宿主私有字符串，原样返回。
 */
data class IMCallSummary(
    val callId: String,
    val reason: IMCallEndReason,
    val durationSec: Long,
    val endedBy: String,
    val mediaType: String,
    val isGroup: Boolean,
    val chatGroupId: String,
    val caller: String,
    val role: String,
    val peer: String,
    val userData: String,
)

/** 在每个 `onCallEnd` 后面紧跟一条 `onCallSummary`。 */
internal object IMCallSummaries {

    /**
     * [before] 是这一步之前的通话上下文（结束后对端 / 群号 / 角色就清零了）。
     * 结束前没有通话，或这通电话还没拿到 call_id（发不出去 / 被本地拒），不追加：
     * 服务端没有这通电话，宿主也没有 cid 可写进记录。
     */
    fun append(before: IMCallContext, emit: List<IMEmittedEvent>, selfUid: () -> String): List<IMEmittedEvent> {
        if (before.state == IMCallState.IDLE || emit.none { it.callback == "onCallEnd" }) return emit
        val out = ArrayList<IMEmittedEvent>(emit.size + 1)
        for (event in emit) {
            out += event
            if (event.callback == "onCallEnd") summary(before, event, selfUid)?.let { out += it }
        }
        return out
    }

    private fun str(args: Map<String, IMJson>, key: String): String = (args[key] as? IMJson.Str)?.value.orEmpty()

    private fun summary(before: IMCallContext, end: IMEmittedEvent, selfUid: () -> String): IMEmittedEvent? {
        val callId = str(end.args, "call_id").ifEmpty { before.callId }
        if (callId.isEmpty()) return null
        val caller = before.caller.ifEmpty { if (before.role == IMCallRole.CALLER) selfUid() else "" }
        return IMEmittedEvent(
            "onCallSummary",
            mapOf(
                "call_id" to IMJson.Str(callId),
                "reason" to IMJson.Str(str(end.args, "reason")),
                "duration_sec" to (end.args["duration_sec"] ?: IMJson.Num(0)),
                "ended_by" to IMJson.Str(str(end.args, "ended_by")),
                "media_type" to IMJson.Str(before.mediaType),
                "is_group" to IMJson.Bool(before.isGroup),
                "chat_group_id" to IMJson.Str(before.chatGroupId),
                "caller" to IMJson.Str(caller),
                "role" to IMJson.Str(before.role.wire),
                "peer" to IMJson.Str(before.peerId),
                "user_data" to IMJson.Str(before.userData),
            ),
        )
    }
}
