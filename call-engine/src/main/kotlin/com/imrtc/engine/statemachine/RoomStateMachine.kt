package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMProtocolEnums

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
    /** 进房时声明的自动订阅档位（协议 §3.1）。会议房是 `audio`，通话房是 `all`。 */
    val autoSubscribe: String = "all",
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
    /**
     * 翻页翻走、等五秒迟滞到点才退订的 track_id，**最早翻走的排在前面**
     * （见 `RoomStateMachinePaging.kt`）。
     *
     * 顺序有用：订满 16 路要提前腾位置时，退的就是最早翻走的那一个。
     * 不进一致性向量——向量只断言 `room` / `publish` / `subscribe` 三个键。
     */
    val pendingUnsubscribe: List<String> = emptyList(),
    /**
     * 这个房间**真的收到过 `room.join.ok`** 吗。
     *
     * 只有它能区分 RECONNECTING 的两种来路：从 JOINED 断的（服务端那边成员关系还在，
     * 恢复后直接回 JOINED），还是从 JOINING 断的（`room.join` 还在飞，服务端从没受理过）。
     * 少了它，[IMRoomMachine.resume] 会把后者也宣布成 JOINED。
     *
     * 不进一致性向量：向量只断言 `room` / `publish` / `subscribe` 那几个键，
     * 这是本端为了分辨来路自己记的账。
     */
    val didJoin: Boolean = false,
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
            is IMMachineInput.Internal -> reduceInternal(ctx, input.name, input.args)
        }

    internal fun out(
        ctx: IMRoomContext,
        send: List<IMOutgoingFrame> = emptyList(),
        emit: List<IMEmittedEvent> = emptyList(),
    ) = IMMachineOutput(ctx, send, emit)

    /** 把房间相关的记账全部清空，state 由调用方决定。 */
    internal fun cleared(state: IMRoomState) = IMRoomContext(state = state)

    private fun reduceInternal(
        ctx: IMRoomContext,
        name: String,
        args: Map<String, IMJson>,
    ): IMMachineOutput<IMRoomContext> =
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

            /*
             离房被拒。**照样退回 idle**——这是 join_failed 的镜像，漏掉它的代价更大。

             `room.leave` 会被拒是真事：服务端在「会话已不在房间里」时回 1203
             （两人同时离房、或房间刚被「已空，已关闭」销毁掉，都撞得上）。
             而被拒的语义恰恰是**我们已经不在房里了**，本地却还停在 leaving：
             媒体停不掉（摄像头、麦克风、前台服务一直开着），再 leave 被 R1 拒成 2005，
             再 join 因为「不在 idle」也被拒——除非 logout，这台 Engine 永远进不了房。

             所以「被拒」与「leave.ok」在本地是同一个收场：归零 + onRoomLeft。
            */
            "leave_failed" ->
                if (ctx.state != IMRoomState.LEAVING) {
                    out(ctx)
                } else {
                    out(
                        cleared(IMRoomState.IDLE),
                        emit = listOf(IMEmittedEvent("onRoomLeft", mapOf("room_id" to s(ctx.roomId)))),
                    )
                }

            /*
             `room.publish` 被拒（或没送到）：把那条 `publishing` 摘掉（静默失败审计 §A）。

             不摘的话它永远停在 `publishing`：`publish.ok` 不会来，pub offer 永远产不出，
             对方全程听不见看不见。**通话里走不到这里**——`IMCallEngine.onRequestFailed`
             撞见「通话不在 idle」会直接走 forceEnd 把整通收场（reason=error），这里只管
             没有通话的会议房。错误本身在 `onRequestFailed` 里已经抛过一次，这里不重复抛。
            */
            "publish_failed" -> dropFailedPublish(ctx, Wire.str(args, "cid"))

            /*
             `room.subscribe` 被拒：把那条 `subscribing` 连同层记账一起摘掉。

             不摘的话不变量 R3 会把之后每一次重订都当成「已经订过、只是换层」，
             只发 `room.update_layer`，**再也发不出 `room.subscribe`**。最常见的来路是
             1301（`track_not_found`）：订阅与对方的 `track_unpublished` 赛跑输了，
             此时摘掉正是实情。不收场、不额外抛回调——通话本身没事。
            */
            "subscribe_failed" -> dropFailedSubscribe(ctx, Wire.str(args, "track_id"))

            /*
             翻页退订的五秒到了（`RoomStateMachinePaging.kt`）。带 track_id 就只退那一条
             （帧循环按 track 排定时器），不带就把排着的一次清掉（一致性向量用的是这一种）。
            */
            "unsubscribe_hysteresis_elapsed" ->
                flushHysteresis(ctx, Wire.str(args, "track_id").ifEmpty { null })

            else -> out(ctx)
        }

    /** 只摘 `publishing` 那一条；已经 `published` / `unpublishing` 的不碰。 */
    private fun dropFailedPublish(ctx: IMRoomContext, cid: String): IMMachineOutput<IMRoomContext> {
        if (ctx.publish[cid] != IMPublishState.PUBLISHING) return out(ctx)
        return out(ctx.copy(publish = ctx.publish - cid))
    }

    /**
     * 只摘 `subscribing` 那一条；已经 `subscribed` / `unsubscribing` 的不碰。
     *
     * **待退订队列要一起摘**：会议房里「订上 → 翻走排退订 → 订阅这时才被拒」是能排到的顺序
     * （订阅与翻页各走各的），队列里留着一个已经没有订阅记账的 track，
     * 五秒后会发一条打在空处的 `room.unsubscribe`；要是这中间那个人又翻回来了，
     * 那一条会把**刚重新订上的**那一路退掉，表现成「翻回来看了五秒，画面自己没了」。
     */
    private fun dropFailedSubscribe(ctx: IMRoomContext, trackId: String): IMMachineOutput<IMRoomContext> {
        if (ctx.subscribe[trackId] != IMSubscribeState.SUBSCRIBING) return out(ctx)
        return out(
            ctx.copy(
                subscribe = ctx.subscribe - trackId,
                layers = ctx.layers - trackId,
                pendingUnsubscribe = ctx.pendingUnsubscribe - trackId,
            ),
        )
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
        if (!ctx.didJoin) return rejoin(ctx)
        return replayBuffered(ctx.copy(state = IMRoomState.JOINED))
    }

    /**
     * 「进房还没落地就断了」的那一轮，恢复后**重发一次 `room.join`**。
     *
     * `disconnected` 会把任何非 IDLE 状态推进 RECONNECTING，JOINING 也在内。
     * 而从 JOINING 断的那一种，`room.join` 当时还在飞：服务端从没受理过我们，
     * 恢复的只是那条 WS 会话，**不是房间成员关系**。无条件宣布 JOINED 的话，
     * 本端以为自己在房里，之后每一帧都换回 1201/1203，
     * 而重新 join 又因为「不在 idle」被本地拒成 2005——一个哑掉的死局。
     *
     * 本端目前靠 `onRequestFailed` 的 `join_failed` 也能兜住（[IMPendingRequests.failAll]
     * 是同步回调，排在 `onDisconnected` 前面），但那是**时序凑巧**：
     * iOS 那边同一段代码就因为多两跳 actor 而翻车过。所以这里改成认 [didJoin] 这笔账，
     * 三端同一份，不依赖谁先谁后。
     *
     * 房号与房票都还在手上，攒下的意图也照旧留着等进房后重放。
     */
    private fun rejoin(ctx: IMRoomContext): IMMachineOutput<IMRoomContext> {
        // 连房号都没有（`join` 的帧还没产出就断了）：没得重发，干净地回 IDLE。
        if (ctx.roomId.isEmpty()) return out(cleared(IMRoomState.IDLE))
        return out(
            ctx.copy(state = IMRoomState.JOINING),
            send = listOf(
                IMOutgoingFrame(
                    IMFrameType.ROOM_JOIN,
                    mapOf(
                        "room_id" to s(ctx.roomId),
                        "room_token" to s(ctx.roomToken),
                        "auto_subscribe" to s(ctx.autoSubscribe),
                    ),
                ),
            ),
        )
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
            /*
             上行那条 PC 断了，重新 offer 一次把 ICE 打回来（媒体层已经把 restart 位置好了）。
             **不进 bufferedOps**：这是「此刻网断了」的即时反应，等到重放的时候
             那条 PC 早就换过一轮了，补发一个过期的重启只会白折腾一次协商。
             真正的触发点在会话恢复之后（§1.4），见 IMCallEngine.onConnected。
            */
            "restart_pub_ice" -> out(
                ctx,
                send = listOf(
                    IMOutgoingFrame(
                        IMFrameType.ROOM_OFFER,
                        mapOf("pc" to IMJson.Str("pub"), "sdp" to IMJson.Str("")),
                    ),
                ),
            )
            else -> localReject(ctx)
        }
    }

    private fun joinRoom(ctx: IMRoomContext, args: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
        if (ctx.state != IMRoomState.IDLE) return localReject(ctx)
        // auto_subscribe 默认 `all`——直接读 args 会把「没写」当成空串，
        // 那正是 §2.4 点名的发送侧陷阱。集合外的值按 §2.4 规则 6 兜底成 `all`。
        val autoSubscribe = coerceAutoSubscribe((args["auto_subscribe"] as? IMJson.Str)?.value)
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
                        "auto_subscribe" to s(autoSubscribe),
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
            ctx.copy(
                subscribe = ctx.subscribe + (trackId to IMSubscribeState.UNSUBSCRIBING),
                // 已经手动退了，排着的那次迟滞退订就不必再来一遍。
                pendingUnsubscribe = ctx.pendingUnsubscribe - trackId,
            ),
            send = listOf(IMOutgoingFrame(IMFrameType.ROOM_UNSUBSCRIBE, mapOf("track_id" to s(trackId)))),
        )
    }

    /**
     * 报某条流的层上界。
     *
     * **会议房里它同时是订阅意图**：视频不由服务端自动订，所以「看得见」= 订阅、
     * 「看不见」= 五秒后退订（见 `RoomStateMachinePaging.kt`）。通话房照旧只换层。
     */
    private fun updateLayer(ctx: IMRoomContext, args: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
        val trackId = Wire.str(args, "track_id")
        val layer = Wire.str(args, "max_layer").ifEmpty { "m" }
        if (usesPagedVideo(ctx) && ctx.remoteTracks[trackId]?.kind == "video") {
            return pagedUpdateLayer(ctx, trackId, layer)
        }
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

    /** 把线路上的档位归一化，认不出的一律按 `all`（§2.4 规则 6）。 */
    internal fun coerceAutoSubscribe(value: String?): String =
        if (value != null && value in IMProtocolEnums.AUTO_SUBSCRIBE_MODES) value else "all"

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
    internal fun localReject(ctx: IMRoomContext) = invalidStateOutput(ctx)
}
