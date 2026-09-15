package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMEnvelope
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson

/**
 * Engine 的总状态：把通话机与房间机合起来，并处理**只有「合起来」才说得清**的事。
 *
 * 四件事：
 * 1. **连接级事件**（onConnected / onDisconnected / onKickedOut）由这里抛——
 *    它们既不属于某次通话，也不属于某个房间。
 * 2. **重连恢复失败**时，房间回 idle **且**通话要本地合成 onCallEnd(network)（不变量 I8）——
 *    服务端那条 ended 帧送不到我们手里了。
 * 3. **通话机产出的 `room.join` 要转成房间机的 join 动作**，否则房间机不知道自己正在进房，
 *    之后的 `room.join.ok` 就没人接。
 * 4. **通话结束时房间要回 idle**。这条是 3 的反向，漏了它的后果比漏 3 还隐蔽：
 *    `call.ended` 之后服务端就把房间销毁了，而房间机还停在 joined，于是之后每一帧都发向
 *    一个已经不存在的房间（服务端回 1201），下一次 join 还会因为「不在 idle」被本地拒掉——
 *    界面永远停在「接通中」。（这一条是 Web 端浏览器双开时抓到的真 bug，五端都要有。）
 */
internal data class IMEngineContext(
    val room: IMRoomContext = IMRoomContext(),
    val call: IMCallContext = IMCallContext(),
    /**
     * **本端**这一场从哪一刻开始（本机时钟，抛 `onCallBegin` 那一刻），给强制收场算时长用。0 = 不在通话里。
     *
     * `call.connectedAtMs` 是整通电话第一次接通的时刻——群通话里中途被拉进来的人拿它算，
     * 会把他进来之前的那段也算进去（真机 2026-09-15 10:05 iOS frank：待了约 6 秒，本地收场写成 124 秒）。
     * 由 [IMEngineMachine.reduce] 统一维护，见 `stampCallStart`。
     */
    val callStartedAtMs: Long = 0L,
)

internal object IMEngineMachine {

    private val CALL_ACTS = setOf(
        "call", "accept", "reject", "cancel", "hangup", "invite_more", "join_call",
    )
    private val ROOM_ACTS = setOf(
        "join", "leave", "publish", "unpublish", "mute", "subscribe", "unsubscribe", "update_layer",
        "restart_pub_ice",
    )

    /** engine 状态的唯一入口。 */
    fun reduce(
        ctx: IMEngineContext,
        input: IMMachineInput,
        nowMs: Long = System.currentTimeMillis(),
    ): IMMachineOutput<IMEngineContext> = stampCallStart(ctx, route(ctx, input, nowMs), nowMs)

    /**
     * 维护 [IMEngineContext.callStartedAtMs]：本次抛了 `onCallBegin` 就记成 [nowMs]，通话机回 idle 就清零，
     * 其余沿用输入的值。**放在入口统一做**：好几个分支会新建 context（`liftCall`、被踢），逐个带过去迟早漏一个。
     */
    private fun stampCallStart(
        ctx: IMEngineContext,
        out: IMMachineOutput<IMEngineContext>,
        nowMs: Long,
    ): IMMachineOutput<IMEngineContext> {
        val startedAt = when {
            out.state.call.state == IMCallState.IDLE -> 0L
            out.emit.any { it.callback == "onCallBegin" } -> nowMs
            else -> ctx.callStartedAtMs
        }
        if (startedAt == out.state.callStartedAtMs) return out
        return out.copy(state = out.state.copy(callStartedAtMs = startedAt))
    }

    private fun route(
        ctx: IMEngineContext,
        input: IMMachineInput,
        nowMs: Long,
    ): IMMachineOutput<IMEngineContext> = when (input) {
        is IMMachineInput.Recv ->
            if (input.type == IMEnvelope.okType(IMFrameType.HELLO)) {
                handleHelloOk(ctx, input.data, nowMs)
            } else {
                routeFrame(ctx, input.type, input.data)
            }
        is IMMachineInput.Internal -> handleInternal(ctx, input.name, nowMs)
        is IMMachineInput.Act -> routeAct(ctx, input.op, input.args)
    }

    /**
     * 握手成功。
     *
     * `resumed == false` 时**房间与通话都要归零**——服务端那边的会话已经过期，
     * 装作还在只会让 UI 撒谎。
     */
    private fun handleHelloOk(
        ctx: IMEngineContext,
        data: Map<String, IMJson>,
        nowMs: Long,
    ): IMMachineOutput<IMEngineContext> {
        val resumed = Wire.flag(data, "resumed")
        val connected = IMEmittedEvent(
            "onConnected",
            mapOf("session_id" to s(Wire.str(data, "session_id")), "resumed" to b(resumed)),
        )

        if (!resumed) {
            val dropped = dropLostSession(ctx, nowMs)
            return dropped.copy(emit = listOf(connected) + dropped.emit)
        }

        val room = IMRoomMachine.resume(ctx.room, resumed = true)
        return IMMachineOutput(
            ctx.copy(room = room.state),
            send = room.send,
            emit = listOf(connected) + room.emit,
        )
    }

    /**
     * 收拾「服务端那侧的会话已经没了」这一件事：房间与通话都要收场。
     *
     * 「重连上了但 `resumed=false`」与「断得太久 `session_unrecoverable`」是同一件事的
     * 两个到达时机，所以共用这一段。
     *
     * # 必须给宿主一个收场信号
     *
     * `IMRoomMachine.resume(ctx, false)` 只是把房间清成 idle，**一个事件都不抛**。
     * 有 call 的场合还有 `onCallEnd(network)` 兜着，可**会议是直接 joinRoom 的、
     * 压根没有 call**——于是房间机悄悄回了 idle，而界面还显示着「会议中」、计时器还在走，
     * 用户完全不知道自己已经掉出去了；更糟的是一个结束类回调都没抛，
     * 门面的 leave 那组回调不命中，`media.stop()` 永远不调用（**摄像头与前台服务一直开着**），
     * 上一轮的 PeerConnection 还会被带进下一次进房。
     *
     * 所以：有通话就抛 `onCallEnd`（唯一出口，不再补 `onRoomLeft`，否则宿主记两遍账），
     * 没通话但在房里就补一条 `onRoomLeft`——房间的收场信号就是它。
     * **三端同源**：Web 的 `engineMachine.dropLostSession`、iOS 的
     * `IMEngineMachine.dropLostSession` 是同一段。
     */
    private fun dropLostSession(ctx: IMEngineContext, nowMs: Long): IMMachineOutput<IMEngineContext> {
        val room = IMRoomMachine.resume(ctx.room, resumed = false)
        val emit = mutableListOf<IMEmittedEvent>()
        emit += room.emit
        var call = ctx.call

        if (ctx.call.state != IMCallState.IDLE) {
            // 不变量 I8 的那个唯一例外：服务端的 call.ended 送不到，本地合成一条。
            val synthesized = IMCallMachine.synthesizeNetworkEnd(ctx.call, nowMs)
            call = synthesized.state
            emit += synthesized.emit
        } else if (ctx.room.state != IMRoomState.IDLE) {
            emit += IMEmittedEvent("onRoomLeft", mapOf("room_id" to s(ctx.room.roomId)))
        }

        return IMMachineOutput(ctx.copy(room = room.state, call = call), send = room.send, emit = emit)
    }

    private fun handleInternal(
        ctx: IMEngineContext,
        name: String,
        nowMs: Long,
    ): IMMachineOutput<IMEngineContext> {
        if (name == "ws_closed_4403") {
            // 被踢：什么都不留。重连没有意义——那等于跟另一台设备打架。
            //
            // **不带关闭码**：这个内部事件也被「鉴权连续失败」复用（那时真实关闭码是 4401），
            // 写死 4403 就是在撒谎。关闭码由连接层原样上报——状态机这一份 onDisconnected
            // 只用来驱动状态迁移，门面不会外发它。
            return IMMachineOutput(
                IMEngineContext(),
                emit = listOf(IMEmittedEvent("onKickedOut"), IMEmittedEvent("onDisconnected")),
            )
        }
        if (name == "join_failed" || name == "leave_failed") {
            val room = IMRoomMachine.reduce(ctx.room, IMMachineInput.Internal(name))
            return IMMachineOutput(ctx.copy(room = room.state), send = room.send, emit = room.emit)
        }
        if (name == "call_failed") {
            // 交给通话机回 idle；它抛的 onCallEnd 会顺带把房间也清掉（见 liftCall）。
            return liftCall(ctx, IMCallMachine.reduce(ctx.call, IMMachineInput.Internal(name)))
        }
        /*
         **服务端那一侧已经不可能再恢复这条会话了**（§1.4 的恢复窗口过了）。

         语义与「重连上了但 `resumed=false`」完全一样，所以走同一段代码：房间归零、
         通话本地合成一条 `ended{network}`。差别只在**不必等重连成功** ——
         网络一直不回来的话那一刻永远不会到，界面就永远停在「正在重连」、
         连挂断都点不动（真机 2026-09-08，iOS carol 那一幕）。

         「什么时候算过了窗口」由连接层算（只有它知道心跳周期），见
         `IMSignalConnection` 的 giveUpDelayMs。
        */
        if (name == "session_unrecoverable") return dropLostSession(ctx, nowMs)
        if (name == "disconnected") {
            val room = IMRoomMachine.reduce(ctx.room, IMMachineInput.Internal(name))
            return IMMachineOutput(
                ctx.copy(room = room.state),
                emit = listOf(IMEmittedEvent("onDisconnected")) + room.emit,
            )
        }
        // 其余内部事件（media_ready）交给通话机。
        val call = IMCallMachine.reduce(ctx.call, IMMachineInput.Internal(name))
        return IMMachineOutput(ctx.copy(call = call.state), send = call.send, emit = call.emit)
    }

    private fun routeFrame(
        ctx: IMEngineContext,
        type: String,
        data: Map<String, IMJson>,
    ): IMMachineOutput<IMEngineContext> {
        if (type.startsWith("call.")) {
            return liftCall(ctx, IMCallMachine.reduce(ctx.call, IMMachineInput.Recv(type, data)))
        }
        if (type.startsWith("room.")) {
            val room = IMRoomMachine.reduce(ctx.room, IMMachineInput.Recv(type, data))
            return IMMachineOutput(ctx.copy(room = room.state), send = room.send, emit = room.emit)
        }
        return IMMachineOutput(ctx)
    }

    private fun routeAct(
        ctx: IMEngineContext,
        op: String,
        args: Map<String, IMJson>,
    ): IMMachineOutput<IMEngineContext> {
        if (op in CALL_ACTS) {
            return liftCall(ctx, IMCallMachine.reduce(ctx.call, IMMachineInput.Act(op, args)))
        }
        if (op in ROOM_ACTS) {
            val room = IMRoomMachine.reduce(ctx.room, IMMachineInput.Act(op, args))
            return IMMachineOutput(ctx.copy(room = room.state), send = room.send, emit = room.emit)
        }
        return IMMachineOutput(ctx)
    }

    /**
     * 把通话机的输出抬到 engine 层，并**把 `room.join` 转交给房间机**。
     *
     * 不做这一步的话，房间机不知道自己正在进房，随后的 `room.join.ok` 就没人接，
     * UI 会停在「接通中」不动。
     */
    private fun liftCall(
        ctx: IMEngineContext,
        result: IMMachineOutput<IMCallContext>,
    ): IMMachineOutput<IMEngineContext> {
        val send = mutableListOf<IMOutgoingFrame>()
        val emit = result.emit.toMutableList()
        var room = ctx.room

        for (frame in result.send) {
            if (frame.type != IMFrameType.ROOM_JOIN) {
                send += frame
                continue
            }
            val joined = IMRoomMachine.reduce(
                room,
                IMMachineInput.Act(
                    "join",
                    mapOf(
                        "room_id" to (frame.data["room_id"] ?: s("")),
                        "room_token" to (frame.data["room_token"] ?: s("")),
                    ),
                ),
            )
            room = joined.state
            send += joined.send
            emit += joined.emit
        }

        // 通话结束 = 房间没了。服务端在发出 call.ended 的同时就销毁了房间（§4.4），
        // 所以这里只是**本地归零**，不发 room.leave——那一帧只会换回一个 1201。
        // 也不补抛 onRoomLeft：onCallEnd 是所有结束分支的唯一出口（§7.5），
        // 为同一件事抛两个回调会让宿主的记账重复。
        if (emit.any { it.callback == "onCallEnd" }) {
            room = IMRoomMachine.cleared(IMRoomState.IDLE)
        }

        return IMMachineOutput(IMEngineContext(room = room, call = result.state), send = send, emit = emit)
    }
}
