package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMEnvelope
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson

/*
 房间状态机的**下行帧**分支。

 与 RoomStateMachine.kt 拆开是体量红线（CONVENTIONS §2）；
 「上行动作」与「下行帧」本来也是两组独立的关注点。
 */

/**
 * **idle 下迟到的房间帧一律丢弃**，与通话机的第 2 条规则同一个道理——
 * 本地已经不在房里，这些帧说的是一个本端不再关心的房间。
 *
 * 唯一例外是 `room.join.ok`：它说明**服务端已经把我们放进房了**，而本地早就收场了
 * （强制收场时还卡在路上的那条 join）。认领它会把一个没人要的房间捡回来；不理它，
 * 服务端就一直挂着这个人——服务端只验房票、不查通话成员。所以补发一条 `room.leave`，
 * 本地状态不动。那条 leave 的 `.ok` 回来时同样落在这里被丢掉，不会多抛一次 onRoomLeft。
 */
private fun handleLateRoomFrame(
    ctx: IMRoomContext,
    type: String,
    data: Map<String, IMJson>,
): IMMachineOutput<IMRoomContext> {
    val roomId = Wire.str(data, "room_id")
    if (type != IMEnvelope.okType(IMFrameType.ROOM_JOIN) || roomId.isEmpty()) return IMRoomMachine.out(ctx)
    return IMRoomMachine.out(ctx, send = listOf(IMOutgoingFrame(IMFrameType.ROOM_LEAVE, mapOf("room_id" to s(roomId)))))
}

internal fun reduceRoomRecv(
    ctx: IMRoomContext,
    type: String,
    data: Map<String, IMJson>,
): IMMachineOutput<IMRoomContext> = if (ctx.state == IMRoomState.IDLE) handleLateRoomFrame(ctx, type, data) else when (type) {

    IMEnvelope.okType(IMFrameType.ROOM_JOIN) -> handleJoinOk(ctx, data)

    IMEnvelope.okType(IMFrameType.ROOM_LEAVE) -> IMRoomMachine.out(
        IMRoomMachine.cleared(IMRoomState.IDLE),
        emit = listOf(IMEmittedEvent("onRoomLeft", mapOf("room_id" to s(ctx.roomId)))),
    )

    IMEnvelope.okType(IMFrameType.ROOM_PUBLISH) -> handlePublishOk(ctx, data)

    // 服务端对 pub offer 的应答：本端那条上行协商完成了。
    IMFrameType.ROOM_ANSWER -> IMRoomMachine.out(
        ctx.copy(publish = ctx.publish.mapValues { (_, v) ->
            if (v == IMPublishState.PUBLISHING) IMPublishState.PUBLISHED else v
        }),
    )

    IMFrameType.ROOM_OFFER -> handleSubOffer(ctx, data)

    IMEnvelope.okType(IMFrameType.ROOM_UNPUBLISH) -> IMRoomMachine.out(
        ctx.copy(publish = ctx.publish.filterValues { it != IMPublishState.UNPUBLISHING }),
    )

    IMEnvelope.okType(IMFrameType.ROOM_UNSUBSCRIBE) -> IMRoomMachine.out(
        ctx.copy(subscribe = ctx.subscribe.filterValues { it != IMSubscribeState.UNSUBSCRIBING }),
    )

    IMFrameType.ROOM_PARTICIPANT_JOINED -> IMRoomMachine.out(
        ctx,
        emit = listOf(IMEmittedEvent("onUserEnter", mapOf("uid" to s(Wire.str(data, "uid"))))),
    )

    IMFrameType.ROOM_PARTICIPANT_LEFT -> handleParticipantLeft(ctx, data)

    IMFrameType.ROOM_TRACK_PUBLISHED -> handleTrackPublished(ctx, data)

    IMFrameType.ROOM_TRACK_UNPUBLISHED -> handleTrackUnpublished(ctx, data)

    IMFrameType.ROOM_TRACK_MUTED -> IMRoomMachine.out(
        ctx,
        emit = listOf(
            availability(
                kind = if (Wire.str(data, "kind") == "video") "video" else "audio",
                uid = Wire.str(data, "uid"),
                available = !Wire.flag(data, "muted"),
            ),
        ),
    )

    IMFrameType.ROOM_ACTIVE_SPEAKERS -> IMRoomMachine.out(
        ctx,
        emit = listOf(
            IMEmittedEvent("onActiveSpeakers", mapOf("speakers" to (data["speakers"] ?: IMJson.Arr(emptyList())))),
        ),
    )

    IMFrameType.ROOM_QUALITY -> IMRoomMachine.out(
        ctx,
        emit = listOf(
            IMEmittedEvent("onNetworkQuality", mapOf("entries" to (data["entries"] ?: IMJson.Arr(emptyList())))),
        ),
    )

    IMFrameType.ROOM_CLOSED -> IMRoomMachine.out(
        IMRoomMachine.cleared(IMRoomState.IDLE),
        emit = listOf(
            IMEmittedEvent(
                "onRoomClosed",
                mapOf(
                    "room_id" to s(Wire.str(data, "room_id")),
                    "reason" to s(Wire.str(data, "reason")),
                ),
            ),
        ),
    )

    // 其余的 .ok（subscribe / update_layer / mute）不改状态也不抛回调。
    else -> IMRoomMachine.out(ctx)
}

/** 用快照把房间一次性搭起来：先成员，再他们的 Track。 */
private fun handleJoinOk(ctx: IMRoomContext, data: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
    val emit = mutableListOf(IMEmittedEvent("onRoomJoined", mapOf("room_id" to s(Wire.str(data, "room_id")))))
    var next = ctx.copy(
        state = IMRoomState.JOINED,
        // **这一笔账只在这里记**：它是「服务端真的受理了我们」的唯一证据，
        // resume 靠它分辨 RECONNECTING 的两种来路（见 IMRoomMachine.resume）。
        didJoin = true,
        roomId = Wire.str(data, "room_id"),
        participantId = Wire.str(data, "participant_id"),
    )

    for (participant in Wire.objects(data, "participants")) {
        emit += IMEmittedEvent("onUserEnter", mapOf("uid" to s(Wire.str(participant, "uid"))))
    }
    for (track in Wire.objects(data, "tracks")) {
        val trackId = Wire.str(track, "track_id")
        val kind = if (Wire.str(track, "kind") == "video") "video" else "audio"
        val uid = Wire.str(track, "uid")
        next = next.copy(
            remoteTracks = next.remoteTracks +
                (trackId to IMRemoteTrack(uid, kind, Wire.str(track, "participant_id"))),
        )
        emit += availability(kind = kind, uid = uid, available = !Wire.flag(track, "muted"))
        // 自动订阅是**服务端**做的，客户端这边只记账，等 sub offer 来把它们坐实。
        if (ctx.autoSubscribe) {
            next = next.copy(subscribe = next.subscribe + (trackId to IMSubscribeState.SUBSCRIBING))
        }
    }
    // 进房成功之后**立刻重放 joining 期间攒下的意图**（不变量 R2）：
    // 宿主在 onCallBegin 里就发起的 publish 走的正是这条路。
    val replayed = IMRoomMachine.replayBuffered(next)
    return IMRoomMachine.out(replayed.state, send = replayed.send, emit = emit + replayed.emit)
}

private fun handlePublishOk(ctx: IMRoomContext, data: Map<String, IMJson>): IMMachineOutput<IMRoomContext> =
    IMRoomMachine.out(
        ctx.copy(
            publishTrackIds = ctx.publishTrackIds + (Wire.str(data, "cid") to Wire.str(data, "track_id")),
        ),
        // 拿到 track_id 之后才发 pub offer：服务端要靠 msid 里的 cid 认领 m-line（§3.2）。
        send = listOf(IMOutgoingFrame(IMFrameType.ROOM_OFFER, mapOf("pc" to s("pub"), "sdp" to s("")))),
    )

/**
 * **sub PC 的 offerer 恒为服务端**（§3.3），我们只负责应答。
 * 应答的同时把「订阅中」坐实为「已订阅」——那条流这时才真的挂上来。
 */
private fun handleSubOffer(ctx: IMRoomContext, data: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
    if (Wire.str(data, "pc") != "sub") return IMRoomMachine.out(ctx)
    return IMRoomMachine.out(
        ctx.copy(subscribe = ctx.subscribe.mapValues { (_, v) ->
            if (v == IMSubscribeState.SUBSCRIBING) IMSubscribeState.SUBSCRIBED else v
        }),
        send = listOf(IMOutgoingFrame(IMFrameType.ROOM_ANSWER, mapOf("pc" to s("sub"), "sdp" to s("")))),
    )
}

private fun handleParticipantLeft(ctx: IMRoomContext, data: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
    val participantId = Wire.str(data, "participant_id")
    val goneTracks = ctx.remoteTracks.filterValues { it.participantId == participantId }.keys
    // 人走了，他的 Track 与我们对它的订阅一起清掉——不清的话重连时会重放一个死订阅。
    return IMRoomMachine.out(
        ctx.copy(
            remoteTracks = ctx.remoteTracks - goneTracks,
            subscribe = ctx.subscribe - goneTracks,
        ),
        emit = listOf(IMEmittedEvent("onUserLeave", mapOf("uid" to s(Wire.str(data, "uid"))))),
    )
}

private fun handleTrackPublished(ctx: IMRoomContext, data: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
    val trackId = Wire.str(data, "track_id")
    val kind = if (Wire.str(data, "kind") == "video") "video" else "audio"
    val uid = Wire.str(data, "uid")

    var next = ctx.copy(
        remoteTracks = ctx.remoteTracks +
            (trackId to IMRemoteTrack(uid, kind, Wire.str(data, "participant_id"))),
    )
    if (ctx.autoSubscribe) {
        next = next.copy(subscribe = next.subscribe + (trackId to IMSubscribeState.SUBSCRIBING))
    }
    return IMRoomMachine.out(
        next,
        emit = listOf(availability(kind = kind, uid = uid, available = !Wire.flag(data, "muted"))),
    )
}

/** 帧里**不带 kind**，只能靠本地记账知道该抛音频还是视频事件。 */
private fun handleTrackUnpublished(ctx: IMRoomContext, data: Map<String, IMJson>): IMMachineOutput<IMRoomContext> {
    val trackId = Wire.str(data, "track_id")
    val known = ctx.remoteTracks[trackId]
    val next = ctx.copy(
        remoteTracks = ctx.remoteTracks - trackId,
        subscribe = ctx.subscribe - trackId,
    )
    if (known == null) return IMRoomMachine.out(next)
    return IMRoomMachine.out(
        next,
        emit = listOf(availability(kind = known.kind, uid = known.uid, available = false)),
    )
}

/** 把「Track 有没有」翻译成 §7.5 的两个回调之一。 */
private fun availability(kind: String, uid: String, available: Boolean) = IMEmittedEvent(
    if (kind == "video") "onUserVideoAvailable" else "onUserAudioAvailable",
    mapOf("uid" to s(uid), "available" to b(available)),
)

