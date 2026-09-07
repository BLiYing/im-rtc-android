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
    ): IMMachineOutput<IMEngineContext> = when (input) {
        is IMMachineInput.Recv ->
            if (input.type == IMEnvelope.okType(IMFrameType.HELLO)) {
                handleHelloOk(ctx, input.data, nowMs)
            } else {
                routeFrame(ctx, input.type, input.data)
            }
        is IMMachineInput.Internal -> handleInternal(ctx, input.name)
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
        val emit = mutableListOf(
            IMEmittedEvent(
                "onConnected",
                mapOf("session_id" to s(Wire.str(data, "session_id")), "resumed" to b(resumed)),
            ),
        )

        val room = IMRoomMachine.resume(ctx.room, resumed)
        emit += room.emit

        var call = ctx.call
        if (!resumed && ctx.call.state != IMCallState.IDLE) {
            // 不变量 I8 的那个唯一例外：服务端的 call.ended 送不到，本地合成一条。
            val synthesized = IMCallMachine.synthesizeNetworkEnd(ctx.call, nowMs)
            call = synthesized.state
            emit += synthesized.emit
        }

        return IMMachineOutput(ctx.copy(room = room.state, call = call), send = room.send, emit = emit)
    }

    private fun handleInternal(ctx: IMEngineContext, name: String): IMMachineOutput<IMEngineContext> {
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
