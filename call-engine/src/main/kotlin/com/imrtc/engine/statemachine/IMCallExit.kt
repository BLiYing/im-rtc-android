package com.imrtc.engine.statemachine

import com.imrtc.engine.IMCallEndReason
import com.imrtc.engine.protocol.IMFrameType

/*
 「在这个状态下结束这通电话」的**唯一一张表**（§5.1）。

 原先四处各写一份：宿主调 reject / cancel / hangup（`IMCallMachine.reduceAct`）、强制收场挑结束帧（[forceEndFrames]）、
 本地已经 idle 时迟到帧补发（`CallStateMachineRecv` 的 `handleLateFrame` 与 `handleInviteOk` 补发挂着的 cancel）、
 请求失败时哪些帧也要本地收场（`IMRequestFailures`）。哪份漏改一处，挂断键和红键看门狗发的就不是同一帧。
 现在都查这里；与 `call_fsm.json` 的逐条对照见 `CallExitTableTest`。iOS `CallStateMachine+Exit.swift` 同一张表。

 **为什么不放进请求关联层**（`/simplify` 当初的建议）：迟到帧里的 `call.connected` 是服务端推送、不是哪个请求的应答，
 关联层根本看不见它；而 invite.ok 迟到时那条请求早已超时出表。补发与否只取决于「服务端那边这通电话停在哪」，
 那是状态机的知识。
 */
internal data class IMCallExit(
    /** 宿主在这个状态下该调的退出方法（`reduceAct` 的 op）；换了状态调就是本地 2005。`accepting` 没有，只有强制收场能收。 */
    val op: String?,
    /** 按顺序发的结束帧，都只带 `call_id`。 */
    val frameTypes: List<String>,
    /** 本地收场时写的结束原因（强制收场按状态挑原因时用）。 */
    val reason: IMCallEndReason,
) {
    /** 把结束帧填上 `call_id`。 */
    fun frames(callId: String): List<IMOutgoingFrame> =
        frameTypes.map { IMOutgoingFrame(it, mapOf("call_id" to s(callId))) }

    companion object {
        /**
         * 查表。`idle` 没有可结束的，返回 null。
         *
         * `accepting` 发 **reject + hangup 两帧**：accept 有没有在服务端落地，本端不知道。
         * 还在响铃就是 reject 生效（随后那条 hangup 被拒，无害）；已经接起来就是 hangup 生效。
         */
        fun of(state: IMCallState): IMCallExit? = when (state) {
            IMCallState.IDLE -> null
            IMCallState.INVITING -> IMCallExit("cancel", listOf(IMFrameType.CALL_CANCEL), IMCallEndReason.CANCEL)
            IMCallState.RINGING -> IMCallExit("reject", listOf(IMFrameType.CALL_REJECT), IMCallEndReason.REJECT)
            IMCallState.ACCEPTING ->
                IMCallExit(null, listOf(IMFrameType.CALL_REJECT, IMFrameType.CALL_HANGUP), IMCallEndReason.HANGUP)
            IMCallState.CONNECTING, IMCallState.CONNECTED ->
                IMCallExit("hangup", listOf(IMFrameType.CALL_HANGUP), IMCallEndReason.HANGUP)
        }

        /**
         * idle 下迟到的这一帧说明的「服务端那边这通电话停在哪」，没有就是 null（照旧丢弃）。
         *
         * - `call.invite.ok`：邀请在服务端落地了，被叫正在响铃——按 `inviting` 收（cancel），被叫才不会一直响到超时。
         * - `call.connected`：cancel 来不及、有人已经接起来了——按 `connected` 收（hangup）。
         */
        fun serverState(afterLate: String): IMCallState? = when (afterLate) {
            IMCallMachine.okType(IMFrameType.CALL_INVITE) -> IMCallState.INVITING
            IMFrameType.CALL_CONNECTED -> IMCallState.CONNECTED
            else -> null
        }

        /** 表里出现过的所有结束帧：这些帧失败了也要本地收场（ACTION_RESULT_DESIGN D2）。 */
        val allFrameTypes: Set<String> = IMCallState.entries.mapNotNull { of(it) }.flatMap { it.frameTypes }.toSet()
    }
}
