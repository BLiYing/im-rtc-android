package com.imrtc.engine.statemachine

import com.imrtc.engine.IMCallEndReason
import com.imrtc.engine.protocol.IMEnvelope
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson

/**
 * 通话状态机：`RTC_PROTOCOL.md` §5.1。一致性向量 `call_fsm.json`，五端跑同一份。
 *
 * ## 三条容易写错的地方
 *
 * 1. **没有 `ended` 状态**——ended 是事件不是状态。草图 §09 里那个「停 1.5 秒」的方框
 *    是 UIKit 的展示状态，由 UIKit 自己持有（不变量 I5）。
 * 2. **便利回调只在 1v1 抛**（onCallCancelled/Rejected/Busy/NoAnswer）。群通话里某人拒接
 *    只抛 onUserReject——否则会违反「便利回调之后必定跟 onCallEnd」（I7）。
 * 3. **状态只由信令帧与宿主调用驱动，禁止由定时器改状态**（I4）。
 *    本地振铃倒计时只改 UI，超时由服务端裁决。
 */

/** 通话状态。**没有 ended**，见类注释。 */
internal enum class IMCallState(val wire: String) {
    IDLE("idle"),
    INVITING("inviting"),
    RINGING("ringing"),
    ACCEPTING("accepting"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    ;

    companion object {
        fun from(wire: String): IMCallState =
            entries.firstOrNull { it.wire == wire } ?: error("未知通话状态：$wire")
    }
}

/** 本端在这通电话里的角色。 */
internal enum class IMCallRole(val wire: String) {
    NONE(""),
    CALLER("caller"),
    CALLEE("callee"),
}

/** 通话状态机持有的全部数据。 */
internal data class IMCallContext(
    val state: IMCallState = IMCallState.IDLE,
    val callId: String = "",
    val roomId: String = "",
    val roomToken: String = "",
    val mediaType: String = "audio",
    val isGroup: Boolean = false,
    val role: IMCallRole = IMCallRole.NONE,
    /** 通话时长的起点，来自服务端。**客户端不自己算时长**（I8）。 */
    val connectedAtMs: Long = 0L,
    /**
     * 这通电话的发起人 uid。主叫发起时不记（自己就是），被叫从 `call.incoming.caller` 记下。
     *
     * 只作为 `call.connected.caller` 缺席时的回落值（HOST_INTEGRATION_DESIGN §3.3）：
     * `call.join` 进来的人没收过 `call.incoming`，这个字段天然是空的，此时只能靠
     * `call.connected` 本身带出来的值——服务端已经保证了这一点。
     */
    val caller: String = "",
    /** `call()` 选项 / `call.incoming` 里记下的群号，onCallBegin 回落用（同 [caller]）。 */
    val chatGroupId: String = "",
    /** 同 [chatGroupId]，记的是 `user_data`。 */
    val userData: String = "",
    /**
     * 拨出中、`call.invite.ok` 还没回来（手里没有 call_id）时按了取消。
     *
     * 那一刻发不出一条像样的 `call.cancel`——没有 call_id 只会换回 1401、宿主多收一条 error
     * （Web 真机 2026-09-15 10:09）。先记下，invite.ok 一回来就补发（见 `reduceCallRecv`）。
     */
    val cancelPending: Boolean = false,
)

internal object IMCallMachine {

    /** 通话状态机的唯一入口。 */
    fun reduce(ctx: IMCallContext, input: IMMachineInput): IMMachineOutput<IMCallContext> =
        when (input) {
            is IMMachineInput.Act -> reduceAct(ctx, input.op, input.args)
            is IMMachineInput.Recv -> reduceCallRecv(ctx, input.type, input.data)
            is IMMachineInput.Internal -> reduceInternal(ctx, input.name)
        }

    internal fun out(
        ctx: IMCallContext,
        send: List<IMOutgoingFrame> = emptyList(),
        emit: List<IMEmittedEvent> = emptyList(),
    ) = IMMachineOutput(ctx, send, emit)

    private fun reduceInternal(ctx: IMCallContext, name: String): IMMachineOutput<IMCallContext> {
        /*
         **`call.invite` 被服务端拒了要回 idle**，与 join_failed 同一个道理。

         不退的话通话机永远停在 inviting：界面上「正在呼叫…」转个不停，而服务端根本没建
         这通电话；随后每次挂断都发向一个不存在的 call，换回 1401 call_not_found，
         **永远退不出去**。（iOS 侧实测过：群呼把主叫自己也放进了 callee_ids，
         服务端回 1004，接着连点五次挂断全是 1401。）

         抛 onCallEnd 而不是只清状态：它是所有结束分支的唯一出口。reason 用 error——
         这通电话从未建立，hangup / cancel / reject 哪个都不是实情。
        */
        if (name == "call_failed" && ctx.state != IMCallState.IDLE) {
            return out(
                IMCallContext(),
                emit = listOf(
                    IMEmittedEvent(
                        "onCallEnd",
                        mapOf(
                            "call_id" to s(ctx.callId),
                            "reason" to s(IMCallEndReason.ERROR.wire),
                            "duration_sec" to n(0),
                            "ended_by" to s(""),
                        ),
                    ),
                ),
            )
        }
        // 媒体就绪 = room.join.ok 到手 + sub PC 的 ICE 连通（§5.1）。
        if (name != "media_ready" || ctx.state != IMCallState.CONNECTING) return out(ctx)
        return out(ctx.copy(state = IMCallState.CONNECTED))
    }

    private fun reduceAct(
        ctx: IMCallContext,
        op: String,
        args: Map<String, IMJson>,
    ): IMMachineOutput<IMCallContext> = when (op) {
        "call" -> startCall(ctx, args)
        "accept" -> acceptCall(ctx)
        "reject", "cancel", "hangup" -> exitCall(ctx, op)
        "invite_more" -> inviteMore(ctx, args)
        "join_call" -> joinOngoingCall(ctx, args)
        else -> invalidState(ctx)
    }

    /**
     * `chat_group_id` / `user_data` / `timeout_sec` 是**可选**的（`IMCallEngine.call` 的老重载不传）：
     * 只在门面真的给了这些键时才发上线路，**不能无条件带上默认值**——`timeout_sec` 缺省是 0，
     * 而协议默认是 30；无条件发 0 会把老重载的通话振铃时间从 30s 砍到服务端的最小钳值 5s。
     */
    private fun startCall(ctx: IMCallContext, args: Map<String, IMJson>): IMMachineOutput<IMCallContext> {
        if (ctx.state != IMCallState.IDLE) return invalidState(ctx)

        val calleeIds = Wire.strList(args, "callee_ids")
        val mediaType = if (Wire.str(args, "media_type") == "video") "video" else "audio"
        val isGroup = Wire.flag(args, "is_group")
        val chatGroupId = Wire.str(args, "chat_group_id")
        val userData = Wire.str(args, "user_data")

        val data = LinkedHashMap<String, IMJson>().apply {
            put("callee_ids", arr(calleeIds))
            put("media_type", s(mediaType))
            put("is_group", b(isGroup))
            if (args.containsKey("chat_group_id")) put("chat_group_id", s(chatGroupId))
            if (args.containsKey("user_data")) put("user_data", s(userData))
            if (args.containsKey("timeout_sec")) put("timeout_sec", n(Wire.num(args, "timeout_sec")))
        }

        return out(
            ctx.copy(
                state = IMCallState.INVITING,
                role = IMCallRole.CALLER,
                mediaType = mediaType,
                isGroup = isGroup,
                chatGroupId = chatGroupId,
                userData = userData,
            ),
            send = listOf(IMOutgoingFrame(IMFrameType.CALL_INVITE, data)),
        )
    }

    private fun acceptCall(ctx: IMCallContext): IMMachineOutput<IMCallContext> {
        // 第二次 accept 必须**本地**拦下，不能发上去让服务端回 1405。
        if (ctx.state != IMCallState.RINGING) return invalidState(ctx)
        return out(
            ctx.copy(state = IMCallState.ACCEPTING),
            send = listOf(callIdFrame(IMFrameType.CALL_ACCEPT, ctx)),
        )
    }

    private fun inviteMore(ctx: IMCallContext, args: Map<String, IMJson>): IMMachineOutput<IMCallContext> {
        if (ctx.state != IMCallState.CONNECTED && ctx.state != IMCallState.CONNECTING) {
            return invalidState(ctx)
        }
        return out(
            ctx,
            send = listOf(
                IMOutgoingFrame(
                    IMFrameType.CALL_INVITE_MORE,
                    mapOf(
                        "call_id" to s(ctx.callId),
                        "callee_ids" to arr(Wire.strList(args, "callee_ids")),
                    ),
                ),
            ),
        )
    }

    /**
     * 「群成员看到『进行中』主动加入」（§4.1）。
     *
     * **「怎么知道有通话在进行中」不在本协议里**——那是宿主拿 webhook `call.started`
     * 自己发广播的事。Engine 只负责把 call_id 送上去。
     */
    private fun joinOngoingCall(ctx: IMCallContext, args: Map<String, IMJson>): IMMachineOutput<IMCallContext> {
        if (ctx.state != IMCallState.IDLE) return invalidState(ctx)
        val callId = Wire.str(args, "call_id")
        return out(
            ctx.copy(
                state = IMCallState.ACCEPTING,
                role = IMCallRole.CALLEE,
                callId = callId,
                isGroup = true,
            ),
            send = listOf(IMOutgoingFrame(IMFrameType.CALL_JOIN, mapOf("call_id" to s(callId)))),
        )
    }

    /**
     * 宿主调 reject / cancel / hangup：此刻的状态该用哪个方法、发哪帧，查 [IMCallExit]。
     *
     * 只发帧，状态由随后的 call.ended 推进——**服务端才是裁决方**。
     * 拨出中还没拿到 call_id：不发一条注定被拒的 cancel，挂起到 invite.ok 回来（见 [IMCallContext.cancelPending]）。
     */
    private fun exitCall(ctx: IMCallContext, op: String): IMMachineOutput<IMCallContext> {
        val exit = IMCallExit.of(ctx.state)
        if (exit == null || exit.op != op) return invalidState(ctx)
        if (ctx.state == IMCallState.INVITING && ctx.callId.isEmpty()) return out(ctx.copy(cancelPending = true))
        return out(ctx, send = exit.frames(ctx.callId))
    }

    internal fun callIdFrame(type: String, ctx: IMCallContext) =
        IMOutgoingFrame(type, mapOf("call_id" to s(ctx.callId)))

    internal fun invalidState(ctx: IMCallContext) = invalidStateOutput(ctx)

    /**
     * 不变量 I8 的那个**唯一例外**。
     *
     * 重连恢复失败时服务端那条 `call.ended` 已经送不到我们手里了，只能本地合成一条——
     * 否则宿主会永远等不到 onCallEnd，界面卡在通话中。
     */
    fun synthesizeNetworkEnd(ctx: IMCallContext, nowMs: Long): IMMachineOutput<IMCallContext> {
        if (ctx.state == IMCallState.IDLE) return out(ctx)
        val duration = IMCallEndReason.durationSec(ctx.connectedAtMs, nowMs)
        return out(
            IMCallContext(),
            emit = listOf(
                IMEmittedEvent(
                    "onCallEnd",
                    mapOf(
                        "call_id" to s(ctx.callId),
                        "reason" to s(IMCallEndReason.NETWORK.wire),
                        "duration_sec" to n(duration),
                        "ended_by" to s(""),
                    ),
                ),
            ),
        )
    }

    /** `call.invite.ok` 之类的应答类型，写在这里省得各处拼字符串。 */
    internal fun okType(type: String): String = IMEnvelope.okType(type)
}
