package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson

/**
 * 房间状态机：`RTC_PROTOCOL.md` §5.3。一致性向量 `room_fsm.json`，五端跑同一份。
 *
 * ## 三条不变量（§5.3 的 R1~R3）
 *
 * - **R1** 只有 joined 才允许 publish / subscribe / mute；其余状态**本地拒绝**，
 *   不发上去让服务端报错。
 * - **R2** joining 与 reconnecting 期间**禁止发任何房间帧**，但要把用户意图缓存下来，
 *   进房/恢复后一次性重放。这两个状态的共同点是**宿主观察不到**——它拿到 onCallBegin
 *   就推流是最自然的写法，不该因为一个内部中间态而失败。
 * - **R3** 订阅与换层是**幂等**的：重复 subscribe 同一条 track 等价于换层。
 */

/** 房间连接状态。 */
internal enum class IMRoomState(val wire: String) {
    IDLE("idle"),
    JOINING("joining"),
    JOINED("joined"),
    LEAVING("leaving"),
    RECONNECTING("reconnecting"),
    ;

    companion object {
        fun from(wire: String): IMRoomState =
            entries.firstOrNull { it.wire == wire } ?: error("未知房间状态：$wire")
    }
}

/**
 * 一条本端 Track 的发布状态。
 *
 * **没有 idle**：向量里的 `idle` 就是「这个 cid 不在表里」。用「不存在」表达空态，
 * 省掉「表里有一个 idle 条目」与「表里没有」两种等价写法。订阅侧的 `none` 同理。
 */
internal enum class IMPublishState(val wire: String) {
    PUBLISHING("publishing"),
    PUBLISHED("published"),
    UNPUBLISHING("unpublishing"),
}

/** 一条远端 Track 的订阅状态。 */
internal enum class IMSubscribeState(val wire: String) {
    SUBSCRIBING("subscribing"),
    SUBSCRIBED("subscribed"),
    UNSUBSCRIBING("unsubscribing"),
}

/** 远端 Track 的本地记账。 */
internal data class IMRemoteTrack(
    val uid: String,
    /** "audio" 或 "video"。 */
    val kind: String,
    val participantId: String,
)

/**
 * 攒下来的一次调用，**存的是意图不是帧**。
 *
 * 存帧的话重放时只能原样发出去，状态（比如 `publish[cid] = PUBLISHING`）就漏掉了；
 * 存意图则可以在 joined 态重新走一遍正常路径，跟没缓存过一模一样。
 */
internal data class IMBufferedIntent(val op: String, val args: Map<String, IMJson>)

/** 房间状态机持有的全部数据。 */
internal data class IMRoomContext(
    val state: IMRoomState = IMRoomState.IDLE,
    val roomId: String = "",
    val roomToken: String = "",
    val participantId: String = "",
    val autoSubscribe: Boolean = true,
    /** cid → 发布状态。用 cid 而不是 track_id：发布请求发出时还没有 track_id。 */
    val publish: Map<String, IMPublishState> = emptyMap(),
    /** cid → 服务端分配的 track_id。 */
    val publishTrackIds: Map<String, String> = emptyMap(),
    /** track_id → 订阅状态。 */
    val subscribe: Map<String, IMSubscribeState> = emptyMap(),
    /** track_id → 远端 Track 记账。`track_unpublished` 帧不带 kind，只能靠它。 */
    val remoteTracks: Map<String, IMRemoteTrack> = emptyMap(),
    /** 期望的最高层。track_id → layer。 */
    val layers: Map<String, String> = emptyMap(),
    /** joining / reconnecting 期间缓存的用户意图（不变量 R2）。 */
    val buffered: List<IMBufferedIntent> = emptyList(),
)

internal object IMRoomMachine {

    /** 值得攒下来重放的操作——正好是 R1 管的那一组。 */
    private val BUFFERABLE_OPS = setOf(
        "publish", "unpublish", "mute", "subscribe", "unsubscribe", "update_layer",
    )

    /** 房间状态机的唯一入口。 */
    fun reduce(ctx: IMRoomContext, input: IMMachineInput): IMMachineOutput<IMRoomContext> =
        when (input) {
            is IMMachineInput.Act -> reduceAct(ctx, input.op, input.args)
            is IMMachineInput.Recv -> reduceRoomRecv(ctx, input.type, input.data)
            is IMMachineInput.Internal -> reduceInternal(ctx, input.name)
        }

    internal fun out(
        ctx: IMRoomContext,
        send: List<IMOutgoingFrame> = emptyList(),
        emit: List<IMEmittedEvent> = emptyList(),
    ) = IMMachineOutput(ctx, send, emit)

    /** 把房间相关的记账全部清空，state 由调用方决定。 */
    internal fun cleared(state: IMRoomState) = IMRoomContext(state = state)

    private fun reduceInternal(ctx: IMRoomContext, name: String): IMMachineOutput<IMRoomContext> =
        when (name) {
            // 断线**不等于**离房：协议给了 30 秒恢复窗口，房内其他人这时还看得见我们。
            "disconnected" ->
                if (ctx.state == IMRoomState.IDLE) out(ctx)
                else out(ctx.copy(state = IMRoomState.RECONNECTING))

            "ws_closed_4403", "reset" -> out(cleared(IMRoomState.IDLE))

            /*
             进房被拒（房间没了、票过期、已在房里…）。**退回 idle**，否则状态机永远停在
             joining，之后每次 publish 都被 R1 本地拒成 2005。

             **还要抛 onRoomLeft**：只清状态的话宿主什么都不知道，会议界面会一直停在
             「正在进入会议…」——和「呼叫被拒却不回 idle」是同一类毛病，界面需要一个
             明确的收场信号，房间的收场信号就是这一条。
             （iOS 上这个分支原先整个没有：FrameLoop 发了 join_failed 但没人接，
             于是进房失败之后那台 Engine 再也进不了任何房间。）
            */
            "join_failed" ->
                if (ctx.state != IMRoomState.JOINING) {
                    out(ctx)
                } else {
                    out(
                        cleared(IMRoomState.IDLE),
                        emit = listOf(IMEmittedEvent("onRoomLeft", mapOf("room_id" to s(ctx.roomId)))),
                    )
                }

            else -> out(ctx)
        }

    /**
     * 重连成功后恢复房间：重放缓存的用户意图。
     *
     * `resumed == false` 时**必须回到 idle 并重新 join**（§1.4）——
     * 服务端那边的成员关系已经过期了，装作还在只会让 UI 撒谎。
     */
    fun resume(ctx: IMRoomContext, resumed: Boolean): IMMachineOutput<IMRoomContext> {
        if (!resumed) return out(cleared(IMRoomState.IDLE))
        if (ctx.state != IMRoomState.RECONNECTING) return out(ctx)
        return replayBuffered(ctx.copy(state = IMRoomState.JOINED))
    }

    /**
     * 在 joined 态把攒下的意图重新走一遍。
     *
     * **重放走的是正常路径**（[reduceAct]），不是把缓存的帧直接吐出去——
     * 这样状态更新与帧生成永远一致，不会出现「帧发了但本地记账没跟上」。
     */
    internal fun replayBuffered(ctx: IMRoomContext): IMMachineOutput<IMRoomContext> {
        if (ctx.buffered.isEmpty()) return out(ctx)

        var state = ctx.copy(buffered = emptyList())
        val send = mutableListOf<IMOutgoingFrame>()
        val emit = mutableListOf<IMEmittedEvent>()
        for (intent in ctx.buffered) {
            val result = reduceAct(state, intent.op, intent.args)
            state = result.state
            send += result.send
            emit += result.emit
        }
        return out(state, send, emit)
    }

    private fun reduceAct(
        ctx: IMRoomContext,
        op: String,
        args: Map<String, IMJson>,
    ): IMMachineOutput<IMRoomContext> {
        if (op == "join") return joinRoom(ctx, args)
        if (op == "leave") {
            if (ctx.state != IMRoomState.JOINED) return localReject(ctx)
            return out(
                ctx.copy(state = IMRoomState.LEAVING),
                send = listOf(IMOutgoingFrame(IMFrameType.ROOM_LEAVE, mapOf("room_id" to s(ctx.roomId)))),
            )
        }

        // R1：只有 joined 才允许发布/订阅类操作。
        // R2：**joining 与 reconnecting** 期间把意图缓存下来，之后重放——不是丢掉，也不是发上去。
        //     这两个状态宿主都观察不到，在它们上面报「状态非法」等于让宿主为一个内部细节买单。
        if (ctx.state == IMRoomState.JOINING || ctx.state == IMRoomState.RECONNECTING) {
            return bufferIntent(ctx, op, args)
        }
        if (ctx.state != IMRoomState.JOINED) return localReject(ctx)

        return when (op) {
            "publish" -> publishTrack(ctx, args)
            "unpublish" -> unpublishTrack(ctx, args)
            "mute" -> out(ctx, send = listOf(IMOutgoingFrame(IMFrameType.ROOM_MUTE, muteData(args))))
            "subscribe" -> subscribeTrack(ctx, args)
            "unsubscribe" -> unsubscribeTrack(ctx, args)
            "update_layer" -> updateLayer(ctx, args)
            else -> localReject(ctx)
        }
    }

    private fun joinRoom(ctx: IMRoomContext, args: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
        if (ctx.state != IMRoomState.IDLE) return localReject(ctx)
        // auto_subscribe 默认 true——直接读 args 会把「没写」当成 false，
        // 那正是 §2.4 点名的发送侧陷阱。
        val autoSubscribe = (args["auto_subscribe"] as? IMJson.Bool)?.value ?: true
        val roomId = Wire.str(args, "room_id")
        val roomToken = Wire.str(args, "room_token")

        return out(
            ctx.copy(
                state = IMRoomState.JOINING,
                roomId = roomId,
                roomToken = roomToken,
                autoSubscribe = autoSubscribe,
            ),
            send = listOf(
                IMOutgoingFrame(
                    IMFrameType.ROOM_JOIN,
                    mapOf(
                        "room_id" to s(roomId),
                        "room_token" to s(roomToken),
                        "auto_subscribe" to b(autoSubscribe),
                    ),
                ),
            ),
        )
    }

    private fun publishTrack(ctx: IMRoomContext, args: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
        val cid = Wire.str(args, "cid")
        return out(
            ctx.copy(publish = ctx.publish + (cid to IMPublishState.PUBLISHING)),
            send = listOf(
                IMOutgoingFrame(
                    IMFrameType.ROOM_PUBLISH,
                    mapOf(
                        "cid" to s(cid),
                        "kind" to s(Wire.str(args, "kind")),
                        "source" to s(Wire.str(args, "source")),
                        "simulcast" to b(Wire.flag(args, "simulcast")),
                    ),
                ),
            ),
        )
    }

    private fun unpublishTrack(ctx: IMRoomContext, args: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
        val trackId = Wire.str(args, "track_id")
        val cid = ctx.publishTrackIds.entries.firstOrNull { it.value == trackId }?.key
        val publish = if (cid != null) ctx.publish + (cid to IMPublishState.UNPUBLISHING) else ctx.publish
        return out(
            ctx.copy(publish = publish),
            send = listOf(IMOutgoingFrame(IMFrameType.ROOM_UNPUBLISH, mapOf("track_id" to s(trackId)))),
        )
    }

    /**
     * **重复订阅等价于换层**（不变量 R3）。
     *
     * 客户端的订阅与服务端的 `track_unpublished` 天然会赛跑，所以这条路径必须幂等。
     */
    private fun subscribeTrack(ctx: IMRoomContext, args: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
        val trackId = Wire.str(args, "track_id")
        val layer = Wire.str(args, "max_layer").ifEmpty { "m" }
        val withLayer = ctx.copy(layers = ctx.layers + (trackId to layer))

        if (ctx.subscribe.containsKey(trackId)) {
            return out(
                withLayer,
                send = listOf(
                    IMOutgoingFrame(
                        IMFrameType.ROOM_UPDATE_LAYER,
                        mapOf("track_id" to s(trackId), "max_layer" to s(layer)),
                    ),
                ),
            )
        }
        return out(
            withLayer.copy(subscribe = ctx.subscribe + (trackId to IMSubscribeState.SUBSCRIBING)),
            send = listOf(
                IMOutgoingFrame(
                    IMFrameType.ROOM_SUBSCRIBE,
                    mapOf("track_id" to s(trackId), "max_layer" to s(layer)),
                ),
            ),
        )
    }

    private fun unsubscribeTrack(ctx: IMRoomContext, args: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
        val trackId = Wire.str(args, "track_id")
        return out(
            ctx.copy(subscribe = ctx.subscribe + (trackId to IMSubscribeState.UNSUBSCRIBING)),
            send = listOf(IMOutgoingFrame(IMFrameType.ROOM_UNSUBSCRIBE, mapOf("track_id" to s(trackId)))),
        )
    }

    private fun updateLayer(ctx: IMRoomContext, args: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
        val trackId = Wire.str(args, "track_id")
        val layer = Wire.str(args, "max_layer").ifEmpty { "m" }
        return out(
            ctx.copy(layers = ctx.layers + (trackId to layer)),
            send = listOf(
                IMOutgoingFrame(
                    IMFrameType.ROOM_UPDATE_LAYER,
                    mapOf("track_id" to s(trackId), "max_layer" to s(layer)),
                ),
            ),
        )
    }

    /** 把中间态期间的用户意图缓存起来（不变量 R2）。 */
    private fun bufferIntent(
        ctx: IMRoomContext,
        op: String,
        args: Map<String, IMJson>,
    ): IMMachineOutput<IMRoomContext> {
        // 不认识的 op 照旧本地拒绝：缓存的是**合法但来早了**的调用，不是笔误。
        if (op !in BUFFERABLE_OPS) return localReject(ctx)
        return out(ctx.copy(buffered = ctx.buffered + IMBufferedIntent(op, args)))
    }

    private fun muteData(args: Map<String, IMJson>): Map<String, IMJson> = mapOf(
        "track_id" to s(Wire.str(args, "track_id")),
        "muted" to b(Wire.flag(args, "muted")),
    )

    /** 不变量 R1 的落点：错误状态下的调用**本地拒绝**，不发上去。 */
    internal fun localReject(ctx: IMRoomContext) = out(
        ctx,
        emit = listOf(
            IMEmittedEvent(
                "onError",
                mapOf(
                    "code" to n(IMErrorCode.INVALID_STATE.code.toLong()),
                    "name" to s(IMErrorCode.INVALID_STATE.wireName),
                ),
            ),
        ),
    )
}
