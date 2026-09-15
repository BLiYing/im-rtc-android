package com.imrtc.engine.statemachine

import com.imrtc.engine.IMCallEndReason
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson

/*
 通话状态机的**下行帧**分支（§5.1 转移表的右半边）。

 与 CallStateMachine.kt 拆开是体量红线（CONVENTIONS §2）——
 「上行动作」与「下行帧」本来也是两组独立的关注点。
 */

/**
 * idle 下迟到的通话帧：**一律丢弃，只有两个例外**（不改状态、不抛回调）。
 *
 * 本地已经收场（强制收场、请求被拒回滚），服务端那边这通电话却还在往前走——
 * 不补一帧的话，服务端一直把本端当成在通话里：
 * - `call.invite.ok`：拨出时 invite 还在路上就强制收场，此刻才拿到 call_id → 补发 `call.cancel`，
 *   被叫才不会一直响到超时；
 * - `call.connected`：cancel 来不及、被叫已经接起来了 → 补发 `call.hangup`。
 *
 * 其余照旧丢弃（向量 `late_frames_in_idle_are_dropped`）。iOS `IMCallMachine.handleLateFrame` 同一张表。
 */
private fun handleLateFrame(
    ctx: IMCallContext,
    type: String,
    data: Map<String, IMJson>,
): IMMachineOutput<IMCallContext> {
    val callId = Wire.str(data, "call_id")
    val reply = when (type) {
        IMCallMachine.okType(IMFrameType.CALL_INVITE) -> IMFrameType.CALL_CANCEL
        IMFrameType.CALL_CONNECTED -> IMFrameType.CALL_HANGUP
        else -> null
    }
    if (reply == null || callId.isEmpty()) return IMCallMachine.out(ctx)
    return IMCallMachine.out(ctx, send = listOf(IMOutgoingFrame(reply, mapOf("call_id" to s(callId)))))
}

/**
 * 邀请落地：记下 call_id / room_id。
 *
 * invite.ok 回来之前按过取消的（[IMCallContext.cancelPending]），**这一刻补发 `call.cancel`**——
 * 不必等红键看门狗 3 秒到点，也不会发出一条没有 call_id、只换回 1401 的 cancel。
 */
private fun handleInviteOk(ctx: IMCallContext, data: Map<String, IMJson>): IMMachineOutput<IMCallContext> {
    val callId = Wire.str(data, "call_id")
    val next = ctx.copy(
        callId = callId,
        roomId = Wire.str(data, "room_id"),
        cancelPending = ctx.cancelPending && callId.isEmpty(),
    )
    if (!ctx.cancelPending || callId.isEmpty()) return IMCallMachine.out(next)
    return IMCallMachine.out(next, send = listOf(IMCallMachine.callIdFrame(IMFrameType.CALL_CANCEL, next)))
}

/**
 * 处理一条下行帧。
 *
 * 两条优先级规则写在最前面，**别挪**：
 * 1. **终态帧优先**——任何非 idle 状态收到 `call.ended` 都直达 idle（§5.1）。
 * 2. **idle 下的迟到帧静默丢弃**：不抛回调、不报错。本地状态与服务端赛跑是正常的，客户端得容忍。
 *    例外只有补一帧善后的那两种，见 [handleLateFrame]。
 */
internal fun reduceCallRecv(
    ctx: IMCallContext,
    type: String,
    data: Map<String, IMJson>,
): IMMachineOutput<IMCallContext> {
    if (isForAnotherCall(ctx, data)) return handleForeignCall(ctx, type, data)
    if (type == IMFrameType.CALL_ENDED) return handleEnded(ctx, data)
    if (ctx.state == IMCallState.IDLE && type != IMFrameType.CALL_INCOMING) return handleLateFrame(ctx, type, data)

    return when (type) {
        IMFrameType.CALL_INCOMING -> handleIncoming(ctx, data)

        IMCallMachine.okType(IMFrameType.CALL_INVITE) -> handleInviteOk(ctx, data)

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

/**
 * 这一帧说的是不是**别的一通电话**。
 *
 * 通话中被第三个人呼叫时，服务端会判他忙线并给我们发一条 `call.ended{busy}`——
 * 那条帧的 `call_id` 是**新来那通**的。原先这里不看 call_id，于是这条帧被当成
 * 「当前通话结束了」：媒体面直接关掉、通话页收起，而对面还好好地显示着通话中。
 * 真机日志里就是 08:30:39 那一串 `PC 状态 closed` 紧跟一条别的 call_id 的 callEnd。
 */
private fun isForAnotherCall(ctx: IMCallContext, data: Map<String, IMJson>): Boolean {
    val frameCallId = Wire.str(data, "call_id")
    return ctx.callId.isNotEmpty() && frameCallId.isNotEmpty() && frameCallId != ctx.callId
}

/**
 * 别的一通电话的帧：**一律不碰当前状态**。
 *
 * 只有终态帧要露个头——那说明「有人打进来，已经被自动回了忙线」，
 * 界面据此提示一句谁来过电话（交互规则见 UX_FLOWS §06）。
 */
private fun handleForeignCall(
    ctx: IMCallContext,
    type: String,
    data: Map<String, IMJson>,
): IMMachineOutput<IMCallContext> {
    if (type != IMFrameType.CALL_ENDED) return IMCallMachine.out(ctx)
    return IMCallMachine.out(
        ctx,
        emit = listOf(
            IMEmittedEvent(
                "onCallMissed",
                mapOf(
                    "call_id" to s(Wire.str(data, "call_id")),
                    "caller" to s(Wire.str(data, "caller")),
                    "reason" to s(Wire.str(data, "reason")),
                ),
            ),
        ),
    )
}

private fun handleIncoming(ctx: IMCallContext, data: Map<String, IMJson>): IMMachineOutput<IMCallContext> {
    if (ctx.state != IMCallState.IDLE) return IMCallMachine.out(ctx)
    val mediaType = if (Wire.str(data, "media_type") == "video") "video" else "audio"
    val caller = Wire.str(data, "caller")
    val chatGroupId = Wire.str(data, "chat_group_id")
    val userData = Wire.str(data, "user_data")
    val next = ctx.copy(
        state = IMCallState.RINGING,
        role = IMCallRole.CALLEE,
        callId = Wire.str(data, "call_id"),
        roomId = Wire.str(data, "room_id"),
        mediaType = mediaType,
        isGroup = Wire.flag(data, "is_group"),
        caller = caller,
        chatGroupId = chatGroupId,
        userData = userData,
    )
    return IMCallMachine.out(
        next,
        emit = listOf(
            IMEmittedEvent(
                "onCallReceived",
                mapOf(
                    "call_id" to s(next.callId),
                    "caller" to s(caller),
                    // **原样带上**：群通话里被叫要靠它把还没接的人摆成占位格，
                    // 不然主叫那边是四格、被叫这边只有两格，同一通电话两种样子。
                    "callee_ids" to arr(Wire.strList(data, "callee_ids")),
                    "media_type" to s(mediaType),
                    "is_group" to b(next.isGroup),
                    // 宿主自己的群号 / opaque 数据，原样透传（HOST_INTEGRATION_DESIGN §3.2）。
                    "chat_group_id" to s(chatGroupId),
                    "user_data" to s(userData),
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
    // **取 call.connected 的值，为空时回落到本通 call.incoming / call() 选项记下的值**
    // （HOST_INTEGRATION_DESIGN §3.3，兼容还没升级的服务端）。
    val caller = Wire.str(data, "caller").ifEmpty { ctx.caller }
    val chatGroupId = Wire.str(data, "chat_group_id").ifEmpty { ctx.chatGroupId }
    val userData = Wire.str(data, "user_data").ifEmpty { ctx.userData }

    val next = ctx.copy(
        state = IMCallState.CONNECTING,
        callId = callId.ifEmpty { ctx.callId },
        roomId = roomId,
        roomToken = roomToken,
        mediaType = mediaType,
        isGroup = Wire.flag(data, "is_group") || ctx.isGroup,
        connectedAtMs = Wire.num(data, "connected_at_ms"),
        caller = caller,
        chatGroupId = chatGroupId,
        userData = userData,
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
                    "caller" to s(caller),
                    "chat_group_id" to s(chatGroupId),
                    "user_data" to s(userData),
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
