package com.imrtc.engine.protocol

/**
 * 帧类型名，对应 `RTC_PROTOCOL.md` 附录 A 的帧索引。
 *
 * 「加一个帧」的完整动作是：改协议文档 → 改 `docs/conformance` 向量 → 在这里注册 → 五仓跟进。
 * **顺序不许颠倒。**
 */
internal object IMFrameType {
    // sys 域
    const val HELLO = "sys.hello"
    const val PING = "sys.ping"
    const val PONG = "sys.pong"
    const val ERROR = "sys.error"

    // room 域：上行请求
    const val ROOM_JOIN = "room.join"
    const val ROOM_LEAVE = "room.leave"
    const val ROOM_PUBLISH = "room.publish"
    const val ROOM_UNPUBLISH = "room.unpublish"
    const val ROOM_MUTE = "room.mute"
    const val ROOM_SUBSCRIBE = "room.subscribe"
    const val ROOM_UNSUBSCRIBE = "room.unsubscribe"
    const val ROOM_UPDATE_LAYER = "room.update_layer"

    // 下面三个是**双向**的：谁是请求方由 pc 字段决定（§3.3），不由 type 决定。
    const val ROOM_OFFER = "room.offer"
    const val ROOM_ANSWER = "room.answer"
    const val ROOM_ICE_CANDIDATE = "room.ice_candidate"

    // room 域：下行事件
    const val ROOM_PARTICIPANT_JOINED = "room.participant_joined"
    const val ROOM_PARTICIPANT_LEFT = "room.participant_left"
    const val ROOM_TRACK_PUBLISHED = "room.track_published"
    const val ROOM_TRACK_UNPUBLISHED = "room.track_unpublished"
    const val ROOM_TRACK_MUTED = "room.track_muted"
    const val ROOM_ACTIVE_SPEAKERS = "room.active_speakers"
    const val ROOM_QUALITY = "room.quality"
    const val ROOM_CLOSED = "room.closed"

    // call 域：上行请求
    const val CALL_INVITE = "call.invite"
    const val CALL_ACCEPT = "call.accept"
    const val CALL_REJECT = "call.reject"
    const val CALL_CANCEL = "call.cancel"
    const val CALL_HANGUP = "call.hangup"
    const val CALL_INVITE_MORE = "call.invite_more"
    const val CALL_JOIN = "call.join"

    // call 域：下行事件
    const val CALL_INCOMING = "call.incoming"
    const val CALL_RINGING = "call.ringing"
    const val CALL_ACCEPTED = "call.accepted"
    const val CALL_REJECTED = "call.rejected"
    const val CALL_BUSY = "call.busy"
    const val CALL_NO_ANSWER = "call.no_answer"
    const val CALL_CANCELLED = "call.cancelled"
    const val CALL_CONNECTED = "call.connected"
    const val CALL_HANDLED_ELSEWHERE = "call.handled_elsewhere"
    const val CALL_ENDED = "call.ended"
}

internal object IMFrameRegistry {

    /**
     * 全部上行请求帧。请求必须带非空 req_id，且**恰好**有一条应答。
     *
     * `room.offer` / `room.answer` / `room.ice_candidate` **不在这张表里**——它们是双向的。
     */
    val REQUEST_TYPES: Set<String> = setOf(
        IMFrameType.HELLO, IMFrameType.PING,
        IMFrameType.ROOM_JOIN, IMFrameType.ROOM_LEAVE, IMFrameType.ROOM_PUBLISH,
        IMFrameType.ROOM_UNPUBLISH, IMFrameType.ROOM_MUTE, IMFrameType.ROOM_SUBSCRIBE,
        IMFrameType.ROOM_UNSUBSCRIBE, IMFrameType.ROOM_UPDATE_LAYER,
        IMFrameType.CALL_INVITE, IMFrameType.CALL_ACCEPT, IMFrameType.CALL_REJECT,
        IMFrameType.CALL_CANCEL, IMFrameType.CALL_HANGUP, IMFrameType.CALL_INVITE_MORE,
        IMFrameType.CALL_JOIN,
    )

    /**
     * §3.6 的会议层留位帧：**已占名但 v1 不实现**。
     *
     * 客户端不该发它们；收到服务端的 1003 时要能分清「将来会有」与「压根没有」。
     */
    val RESERVED_TYPES: Set<String> = setOf(
        "room.mute_participant", "room.kick", "room.lock", "room.raise_hand",
        "room.participant_muted", "room.participant_kicked", "room.hand_raised", "room.locked",
    )

    fun isRequest(type: String): Boolean = type in REQUEST_TYPES

    fun isReserved(type: String): Boolean = type in RESERVED_TYPES

    /**
     * 某帧类型的字段声明；未知帧返回 null。
     *
     * 请求帧的 `.ok` 如果没单独登记，一律给空对象——纯 ack 是常态，
     * 不必为每个 `room.mute.ok` 写一份声明。
     */
    fun fields(type: String): IMFrameFields? {
        registry[type]?.let { return it }
        if (type.endsWith(IMEnvelope.OK_SUFFIX)) {
            val base = type.dropLast(IMEnvelope.OK_SUFFIX.length)
            if (isRequest(base)) return SysFrames.EMPTY
        }
        return null
    }

    /** 全部显式登记的帧类型（不含自动派生的纯 ack `.ok`），供测试核对。 */
    val knownTypes: Set<String> get() = registry.keys

    private val registry: Map<String, IMFrameFields> = buildMap {
        put(IMFrameType.HELLO, SysFrames.HELLO)
        put(IMEnvelope.okType(IMFrameType.HELLO), SysFrames.HELLO_OK)
        put(IMFrameType.PING, SysFrames.EMPTY)
        put(IMFrameType.PONG, SysFrames.EMPTY)
        put(IMFrameType.ERROR, SysFrames.ERROR)

        put(IMFrameType.ROOM_JOIN, RoomFrames.JOIN)
        put(IMEnvelope.okType(IMFrameType.ROOM_JOIN), RoomFrames.JOIN_OK)
        put(IMFrameType.ROOM_LEAVE, RoomFrames.LEAVE)
        put(IMFrameType.ROOM_PUBLISH, RoomFrames.PUBLISH)
        put(IMEnvelope.okType(IMFrameType.ROOM_PUBLISH), RoomFrames.PUBLISH_OK)
        put(IMFrameType.ROOM_UNPUBLISH, RoomFrames.TRACK_ID)
        put(IMFrameType.ROOM_MUTE, RoomFrames.MUTE)
        put(IMFrameType.ROOM_SUBSCRIBE, RoomFrames.LAYER)
        put(IMFrameType.ROOM_UNSUBSCRIBE, RoomFrames.TRACK_ID)
        put(IMFrameType.ROOM_UPDATE_LAYER, RoomFrames.LAYER)
        put(IMFrameType.ROOM_OFFER, RoomFrames.SDP)
        put(IMFrameType.ROOM_ANSWER, RoomFrames.SDP)
        // room.offer 没有 .ok —— pub 侧的 offer 由 room.answer 直接作为应答回来（§3.3）。
        put(IMEnvelope.okType(IMFrameType.ROOM_ANSWER), SysFrames.EMPTY)
        put(IMFrameType.ROOM_ICE_CANDIDATE, RoomFrames.ICE_CANDIDATE)
        put(IMEnvelope.okType(IMFrameType.ROOM_ICE_CANDIDATE), SysFrames.EMPTY)

        put(IMFrameType.ROOM_PARTICIPANT_JOINED, RoomFrames.PARTICIPANT_JOINED)
        put(IMFrameType.ROOM_PARTICIPANT_LEFT, RoomFrames.PARTICIPANT_LEFT)
        put(IMFrameType.ROOM_TRACK_PUBLISHED, RoomFrames.TRACK_PUBLISHED)
        put(IMFrameType.ROOM_TRACK_UNPUBLISHED, RoomFrames.TRACK_UNPUBLISHED)
        put(IMFrameType.ROOM_TRACK_MUTED, RoomFrames.TRACK_MUTED)
        put(IMFrameType.ROOM_ACTIVE_SPEAKERS, RoomFrames.ACTIVE_SPEAKERS)
        put(IMFrameType.ROOM_QUALITY, RoomFrames.QUALITY)
        put(IMFrameType.ROOM_CLOSED, RoomFrames.CLOSED)

        put(IMFrameType.CALL_INVITE, CallFrames.INVITE)
        put(IMEnvelope.okType(IMFrameType.CALL_INVITE), CallFrames.INVITE_OK)
        put(IMFrameType.CALL_ACCEPT, CallFrames.CALL_ID)
        put(IMEnvelope.okType(IMFrameType.CALL_ACCEPT), SysFrames.EMPTY)
        put(IMFrameType.CALL_REJECT, CallFrames.CALL_ID)
        put(IMFrameType.CALL_CANCEL, CallFrames.CALL_ID)
        put(IMFrameType.CALL_HANGUP, CallFrames.CALL_ID)
        put(IMFrameType.CALL_INVITE_MORE, CallFrames.INVITE_MORE)
        put(IMEnvelope.okType(IMFrameType.CALL_INVITE_MORE), SysFrames.EMPTY)
        put(IMFrameType.CALL_JOIN, CallFrames.CALL_ID)
        put(IMEnvelope.okType(IMFrameType.CALL_JOIN), SysFrames.EMPTY)

        put(IMFrameType.CALL_INCOMING, CallFrames.INCOMING)
        put(IMFrameType.CALL_RINGING, CallFrames.RINGING)
        put(IMFrameType.CALL_ACCEPTED, CallFrames.MEMBER_OUTCOME)
        put(IMFrameType.CALL_REJECTED, CallFrames.MEMBER_OUTCOME)
        put(IMFrameType.CALL_BUSY, CallFrames.MEMBER_OUTCOME)
        put(IMFrameType.CALL_NO_ANSWER, CallFrames.MEMBER_OUTCOME)
        put(IMFrameType.CALL_CANCELLED, CallFrames.CANCELLED)
        put(IMFrameType.CALL_CONNECTED, CallFrames.CONNECTED)
        put(IMFrameType.CALL_HANDLED_ELSEWHERE, CallFrames.HANDLED_ELSEWHERE)
        put(IMFrameType.CALL_ENDED, CallFrames.ENDED)
    }
}
