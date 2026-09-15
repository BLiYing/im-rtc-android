package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMCallEndReason
import com.imrtc.engine.protocol.IMFrameType

/*
 强制收场：红键按下去**等不到结束事件**时的出口（门面见 `IMCallEngine.forceEnd()`）。

 # 为什么 hangup 不够

 hangup 只发帧，状态由随后的 `call.ended` 推进——服务端才是裁决方（§5.1）。帧要是根本没发出去，
 这一场就永远收不掉：2026-09-13 14:54 iOS frank 按了挂断，`call.hangup` 一帧没到服务端，
 Kit 的看门狗把界面收了，Engine 却还留在通话与房间里，别人一直看得见他，直到 14:58 整通结束。
 Android 的看门狗原先也只收界面，同一个缺口。

 # 这里只算「该发什么、收成什么样」

 纯函数，与 `dropLostSession` 同一个形状：通话机、房间机一起归零，抛唯一的结束出口。
 **发帧由门面直接交给信令连接**（不经过 engine 线程），关媒体跟着状态走（`IMMediaDriver`）。
 五端同一张表：iOS `EngineStateMachine+ForceEnd.swift`、Web `engineMachine` 的 forceEnd。
 */

/**
 * 算出强制收场的结果。没有进行中的通话也不在房里时原样返回（`emit` 为空）。
 *
 * 时长按服务端给的 `connected_at_ms` 估算，与恢复失败时 I8 的那条例外同一个算法：
 * 本地已经收场，服务端那条带真值的 `call.ended` 随后会因为 idle 被丢掉，没有更准的值可用。
 */
internal fun IMEngineMachine.forceEnd(ctx: IMEngineContext, nowMs: Long): IMMachineOutput<IMEngineContext> {
    if (ctx.call.state != IMCallState.IDLE) {
        val call = ctx.call
        val (frames, reason) = forceEndFrames(call)
        return IMMachineOutput(
            IMEngineContext(room = IMRoomMachine.cleared(IMRoomState.IDLE), call = IMCallContext()),
            send = frames,
            emit = listOf(
                IMEmittedEvent(
                    "onCallEnd",
                    mapOf(
                        "call_id" to s(call.callId),
                        "reason" to s(reason.wire),
                        "duration_sec" to n(IMCallEndReason.durationSec(call.connectedAtMs, nowMs)),
                        "ended_by" to s(""),
                    ),
                ),
            ),
        )
    }
    if (ctx.room.state == IMRoomState.IDLE) return IMMachineOutput(ctx)

    // 没有通话却在房里：会议。结束动作是离房（UIKit 的红键在会议里就是 leaveRoom）。
    val roomId = ctx.room.roomId
    val send = if (roomId.isEmpty()) emptyList() else listOf(IMOutgoingFrame(IMFrameType.ROOM_LEAVE, mapOf("room_id" to s(roomId))))
    return IMMachineOutput(
        ctx.copy(room = IMRoomMachine.cleared(IMRoomState.IDLE)),
        send = send,
        emit = listOf(IMEmittedEvent("onRoomLeft", mapOf("room_id" to s(roomId)))),
    )
}

/**
 * 按通话此刻的状态挑结束帧，以及本地收场写哪个结束原因。
 *
 * - `accepting` 发 **reject + hangup 两帧**：accept 有没有在服务端落地，本端不知道。
 *   还在响铃就是 reject 生效（随后那条 hangup 被拒，无害）；已经接起来就是 hangup 生效。
 * - `inviting` 还没拿到 call_id（`call.invite.ok` 没回来）时**此刻发不了 cancel**，
 *   由那条 invite.ok 迟到时补发（门面的落地比对与 `CallStateMachineRecv` 的 idle 分支）。
 */
internal fun forceEndFrames(call: IMCallContext): Pair<List<IMOutgoingFrame>, IMCallEndReason> {
    val (types, reason) = when (call.state) {
        IMCallState.IDLE -> return emptyList<IMOutgoingFrame>() to IMCallEndReason.HANGUP
        IMCallState.RINGING -> listOf(IMFrameType.CALL_REJECT) to IMCallEndReason.REJECT
        IMCallState.INVITING -> listOf(IMFrameType.CALL_CANCEL) to IMCallEndReason.CANCEL
        IMCallState.ACCEPTING -> listOf(IMFrameType.CALL_REJECT, IMFrameType.CALL_HANGUP) to IMCallEndReason.HANGUP
        IMCallState.CONNECTING, IMCallState.CONNECTED -> listOf(IMFrameType.CALL_HANGUP) to IMCallEndReason.HANGUP
    }
    if (call.callId.isEmpty()) return emptyList<IMOutgoingFrame>() to reason
    return types.map { IMCallMachine.callIdFrame(it, call) } to reason
}
