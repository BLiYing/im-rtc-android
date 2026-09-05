package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMCallEndReason
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson

/*
 通话状态机的**下行帧**分支（§5.1 转移表的右半边）。

 与 CallStateMachine.kt 拆开是体量红线（CONVENTIONS §2）——
 「上行动作」与「下行帧」本来也是两组独立的关注点。
 */

/**
 * 处理一条下行帧。
 *
 * 两条优先级规则写在最前面，**别挪**：
 * 1. **终态帧优先**——任何非 idle 状态收到 `call.ended` 都直达 idle（§5.1）。
 * 2. **idle 下的迟到帧一律静默丢弃**：不抛回调、不发帧、不报错。
 *    本地状态与服务端赛跑是正常的，客户端得容忍。
 */
internal fun reduceCallRecv(
    ctx: IMCallContext,
    type: String,
    data: Map<String, IMJson>,
): IMMachineOutput<IMCallContext> {
    if (type == IMFrameType.CALL_ENDED) return handleEnded(ctx, data)
    if (ctx.state == IMCallState.IDLE && type != IMFrameType.CALL_INCOMING) return IMCallMachine.out(ctx)

    return when (type) {
        IMFrameType.CALL_INCOMING -> handleIncoming(ctx, data)

        IMCallMachine.okType(IMFrameType.CALL_INVITE) -> IMCallMachine.out(
            ctx.copy(
                callId = Wire.str(data, "call_id"),
                roomId = Wire.str(data, "room_id"),
            ),
        )

        IMFrameType.CALL_CONNECTED -> handleConnected(ctx, data)

        IMFrameType.CALL_ACCEPTED -> IMCallMachine.out(
            ctx,
            emit = listOf(IMEmittedEvent("onUserAccept", mapOf("uid" to s(Wire.str(data, "uid"))))),
        )

        IMFrameType.CALL_REJECTED ->
            handleOutcome(ctx, data, userCb = "onUserReject", convenienceCb = "onCallRejected")

        IMFrameType.CALL_NO_ANSWER ->
            handleOutcome(ctx, data, userCb = "onUserNoResponse", convenienceCb = "onCallNoAnswer")

        // 忙线没有对应的 onUser*——被叫压根没振铃（§4.3）。
        IMFrameType.CALL_BUSY -> if (ctx.isGroup) {
            IMCallMachine.out(ctx)
        } else {
            IMCallMachine.out(
                ctx,
                emit = listOf(IMEmittedEvent("onCallBusy", mapOf("uid" to s(Wire.str(data, "uid"))))),
            )
        }

        IMFrameType.CALL_CANCELLED -> IMCallMachine.out(
            ctx,
            emit = listOf(IMEmittedEvent("onCallCancelled", mapOf("by" to s(Wire.str(data, "by"))))),
        )

        IMFrameType.CALL_HANDLED_ELSEWHERE -> IMCallMachine.out(
            ctx,
            emit = listOf(
                IMEmittedEvent(
                    "onHandledOnOtherDevice",
                    mapOf(
                        "call_id" to s(Wire.str(data, "call_id")),
                        "action" to s(Wire.str(data, "action")),
                    ),
                ),
            ),
        )

        // 其余（call.ringing、各种 .ok）不改状态也不抛回调。
        else -> IMCallMachine.out(ctx)
    }
}

private fun handleIncoming(ctx: IMCallContext, data: Map<String, IMJson>): IMMachineOutput<IMCallContext> {
    if (ctx.state != IMCallState.IDLE) return IMCallMachine.out(ctx)
    val mediaType = if (Wire.str(data, "media_type") == "video") "video" else "audio"
    val next = ctx.copy(
        state = IMCallState.RINGING,
        role = IMCallRole.CALLEE,
        callId = Wire.str(data, "call_id"),
        roomId = Wire.str(data, "room_id"),
        mediaType = mediaType,
        isGroup = Wire.flag(data, "is_group"),
    )
    return IMCallMachine.out(
        next,
        emit = listOf(
            IMEmittedEvent(
                "onCallReceived",
                mapOf(
                    "call_id" to s(next.callId),
                    "caller" to s(Wire.str(data, "caller")),
                    "media_type" to s(mediaType),
                    "is_group" to b(next.isGroup),
                ),
            ),
        ),
    )
}

/**
 * 拿到 room_token，抛 onCallBegin，并**立刻发 room.join**。
 *
 * onCallBegin 抛在进入 connecting 时（不是 connected）——草图 §09 的时序就是这样：
 * 双方在 `call.connected` 那一刻同时开始计时。
 */
private fun handleConnected(ctx: IMCallContext, data: Map<String, IMJson>): IMMachineOutput<IMCallContext> {
    val allowed = ctx.state == IMCallState.INVITING ||
        ctx.state == IMCallState.RINGING ||
        ctx.state == IMCallState.ACCEPTING
    if (!allowed) return IMCallMachine.out(ctx)

    val roomId = Wire.str(data, "room_id")
    val roomToken = Wire.str(data, "room_token")
    val mediaType = if (Wire.str(data, "media_type") == "video") "video" else ctx.mediaType
    val callId = Wire.str(data, "call_id")

    val next = ctx.copy(
        state = IMCallState.CONNECTING,
        callId = callId.ifEmpty { ctx.callId },
        roomId = roomId,
        roomToken = roomToken,
        mediaType = mediaType,
        isGroup = Wire.flag(data, "is_group") || ctx.isGroup,
        connectedAtMs = Wire.num(data, "connected_at_ms"),
    )
    return IMCallMachine.out(
        next,
        send = listOf(
            IMOutgoingFrame(
                IMFrameType.ROOM_JOIN,
                mapOf("room_id" to s(roomId), "room_token" to s(roomToken)),
            ),
        ),
        emit = listOf(
            IMEmittedEvent(
                "onCallBegin",
                mapOf(
                    "call_id" to s(next.callId),
                    "room_id" to s(roomId),
                    "media_type" to s(mediaType),
                    "is_group" to b(next.isGroup),
                    "role" to s(next.role.wire),
                ),
            ),
        ),
    )
}

/**
 * 某成员的裁决。
 *
 * **便利回调只在 1v1 抛**（不变量 I7）：群里一个人拒接，通话还在继续，
 * 后面并不会紧跟 onCallEnd，抛便利回调就自相矛盾了。
 */
private fun handleOutcome(
    ctx: IMCallContext,
    data: Map<String, IMJson>,
    userCb: String,
    convenienceCb: String,
): IMMachineOutput<IMCallContext> {
    val uid = Wire.str(data, "uid")
    val emit = mutableListOf(IMEmittedEvent(userCb, mapOf("uid" to s(uid))))
    if (!ctx.isGroup) emit.add(IMEmittedEvent(convenienceCb, mapOf("uid" to s(uid))))
    return IMCallMachine.out(ctx, emit = emit)
}

/**
 * 唯一的终态处理。
 *
 * **收到 `call.ended` 后禁止再发 `room.leave`**（不变量 I6）——服务端在结束通话时
 * 已经清掉了房间成员，再发只会换回 1201/1203。
 */
private fun handleEnded(ctx: IMCallContext, data: Map<String, IMJson>): IMMachineOutput<IMCallContext> {
    if (ctx.state == IMCallState.IDLE) return IMCallMachine.out(ctx)
    val reason = IMCallEndReason.from(Wire.str(data, "reason"))
    return IMCallMachine.out(
        IMCallContext(),
        emit = listOf(
            IMEmittedEvent(
                "onCallEnd",
                mapOf(
                    "call_id" to s(Wire.str(data, "call_id")),
                    "reason" to s(reason.wire),
                    "duration_sec" to n(Wire.num(data, "duration_sec")),
                    "ended_by" to s(Wire.str(data, "ended_by")),
                ),
            ),
        ),
    )
}
