package com.imrtc.engine.protocol

/**
 * room 域：房间与媒体。见 §3。
 * **Room 层是媒体的全部**——1v1、群通话、会议都用同一套帧。
 */
internal object RoomFrames {

    private val E = IMProtocolEnums

    /** 房间成员快照。 */
    val PARTICIPANT: IMFrameFields = mapOf(
        "participant_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
        "device_id" to IMFieldKind.Str(),
        "joined_at_ms" to IMFieldKind.Num(),
    )

    /** 一条已发布 Track 的快照。 */
    val TRACK: IMFrameFields = mapOf(
        "track_id" to IMFieldKind.Str(),
        "participant_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
        "kind" to IMFieldKind.Enumeration(E.TRACK_KINDS, fallback = "audio"),
        "source" to IMFieldKind.Enumeration(E.TRACK_SOURCES, fallback = "microphone"),
        "codec" to IMFieldKind.Str(),
        // 空数组 = 单层。**不能是 null**。
        "simulcast_layers" to IMFieldKind.EnumArray(E.LAYERS, fallback = "l"),
        "muted" to IMFieldKind.Flag(),
    )

    /**
     * 进房请求。
     *
     * **注意 auto_subscribe / publish_audio 默认是 true**：直接发零值 data，
     * 线路上会变成 false，人进了房却收不到任何流。发送侧一律从 [FieldCodec.defaults] 起手。
     */
    val JOIN: IMFrameFields = mapOf(
        "room_id" to IMFieldKind.Str(),
        "room_token" to IMFieldKind.Str(),
        "auto_subscribe" to IMFieldKind.Flag(default = true),
        "publish_audio" to IMFieldKind.Flag(default = true),
        "publish_video" to IMFieldKind.Flag(default = false),
    )

    /** 带回整个房间的快照，客户端据此一次性把九宫格搭起来。 */
    val JOIN_OK: IMFrameFields = mapOf(
        "room_id" to IMFieldKind.Str(),
        "room_kind" to IMFieldKind.Enumeration(E.ROOM_KINDS, fallback = "call_group"),
        "participant_id" to IMFieldKind.Str(),
        "max_participants" to IMFieldKind.Num(),
        "joined_at_ms" to IMFieldKind.Num(),
        "participants" to IMFieldKind.ObjArray(PARTICIPANT),
        "tracks" to IMFieldKind.ObjArray(TRACK),
    )

    /** 离房请求。 */
    val LEAVE: IMFrameFields = mapOf("room_id" to IMFieldKind.Str())

    /**
     * 发布请求。
     *
     * cid 是**客户端**生成的本地 track 标识，必须出现在随后 pub offer 的 msid 里；
     * 服务端靠它把 SDP 的 m-line 认回 track_id。
     */
    val PUBLISH: IMFrameFields = mapOf(
        "cid" to IMFieldKind.Str(),
        "kind" to IMFieldKind.Enumeration(E.TRACK_KINDS, fallback = "audio"),
        "source" to IMFieldKind.Enumeration(E.TRACK_SOURCES, fallback = "microphone"),
        "simulcast" to IMFieldKind.Flag(),
        "width" to IMFieldKind.Num(),
        "height" to IMFieldKind.Num(),
        "max_bitrate_kbps" to IMFieldKind.Num(),
    )

    /** 回带 track_id 与原样回显的 cid 供配对。 */
    val PUBLISH_OK: IMFrameFields = mapOf("track_id" to IMFieldKind.Str(), "cid" to IMFieldKind.Str())

    /** 只带 track_id 的帧（unpublish / unsubscribe）。 */
    val TRACK_ID: IMFrameFields = mapOf("track_id" to IMFieldKind.Str())

    /** 开关麦克风/摄像头。**这不是 unpublish**，Track 与协商都保留。 */
    val MUTE: IMFrameFields = mapOf("track_id" to IMFieldKind.Str(), "muted" to IMFieldKind.Flag())

    /** 订阅与换层。max_layer 是**上界不是命令**。 */
    val LAYER: IMFrameFields = mapOf(
        "track_id" to IMFieldKind.Str(),
        "max_layer" to IMFieldKind.Enumeration(E.LAYERS, fallback = "l", default = "m"),
    )

    /** room.offer 与 room.answer 共用。 */
    val SDP: IMFrameFields = mapOf(
        "pc" to IMFieldKind.Enumeration(E.PC_ROLES, fallback = "pub"),
        "sdp" to IMFieldKind.Str(),
    )

    /** trickle ICE 候选。candidate 为 "" 表示收集结束，**接收方必须容忍**。 */
    val ICE_CANDIDATE: IMFrameFields = mapOf(
        "pc" to IMFieldKind.Enumeration(E.PC_ROLES, fallback = "pub"),
        "candidate" to IMFieldKind.Str(),
        "sdp_mid" to IMFieldKind.Str(),
        "sdp_mline_index" to IMFieldKind.Num(),
    )

    /** 有人进房。 */
    val PARTICIPANT_JOINED: IMFrameFields = mapOf(
        "room_id" to IMFieldKind.Str(),
        "participant_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
        "device_id" to IMFieldKind.Str(),
        "joined_at_ms" to IMFieldKind.Num(),
    )

    /** 有人离房。reason 取 §6 的子集。 */
    val PARTICIPANT_LEFT: IMFrameFields = mapOf(
        "room_id" to IMFieldKind.Str(),
        "participant_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
        "device_id" to IMFieldKind.Str(),
        "reason" to IMFieldKind.Enumeration(E.REASONS, fallback = "error"),
        "duration_sec" to IMFieldKind.Num(),
    )

    /** 有人发布了 Track。 */
    val TRACK_PUBLISHED: IMFrameFields = TRACK + mapOf("room_id" to IMFieldKind.Str())

    /** 有人销毁了 Track。 */
    val TRACK_UNPUBLISHED: IMFrameFields = mapOf(
        "room_id" to IMFieldKind.Str(),
        "track_id" to IMFieldKind.Str(),
        "participant_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
    )

    /** 对应 onUserAudioAvailable / onUserVideoAvailable。 */
    val TRACK_MUTED: IMFrameFields = mapOf(
        "room_id" to IMFieldKind.Str(),
        "track_id" to IMFieldKind.Str(),
        "participant_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
        "kind" to IMFieldKind.Enumeration(E.TRACK_KINDS, fallback = "audio"),
        "muted" to IMFieldKind.Flag(),
    )

    /** 一个正在说话的人。volume 0~100，**整数**。 */
    val SPEAKER: IMFrameFields = mapOf(
        "participant_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
        "volume" to IMFieldKind.Num(min = 0, max = 100),
    )

    /** 服务端节流 300ms，客户端**不得**依赖更高频率。 */
    val ACTIVE_SPEAKERS: IMFrameFields = mapOf(
        "room_id" to IMFieldKind.Str(),
        "speakers" to IMFieldKind.ObjArray(SPEAKER),
    )

    /** 一个人的网络质量，level 0~6。 */
    val QUALITY_ENTRY: IMFrameFields = mapOf(
        "participant_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
        // 越界折成 0 = unknown，**不是钳到 6**——把未知说成「已断开」会误导 UI。
        "level" to IMFieldKind.Num(min = 0, max = 6, outOfRange = IMProtocolEnums.QUALITY_UNKNOWN),
    )

    /** 服务端节流 2s。 */
    val QUALITY: IMFrameFields = mapOf(
        "room_id" to IMFieldKind.Str(),
        "entries" to IMFieldKind.ObjArray(QUALITY_ENTRY),
    )

    /** 房间结束。 */
    val CLOSED: IMFrameFields = mapOf(
        "room_id" to IMFieldKind.Str(),
        "reason" to IMFieldKind.Enumeration(E.REASONS, fallback = "error"),
        "duration_sec" to IMFieldKind.Num(),
    )
}
