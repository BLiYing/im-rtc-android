package com.imrtc.engine.protocol

/**
 * call 域：振铃流程。见 §4。
 * **Call 层不碰媒体**——接通后一切走 room 帧，所以这里没有一个 SDP 字段。
 */
internal object CallFrames {

    private val E = IMProtocolEnums

    /**
     * 发起通话。
     *
     * user_data 是 opaque **字符串**不是对象——宿主要塞任意结构自己序列化。
     * 这条是为了 C++ 端不必处理任意嵌套（§2.4 规则 5）。服务端原样透传，不解析。
     */
    val INVITE: IMFrameFields = mapOf(
        // 1v1 恰好 1 个；群 ≤8（房内含主叫共 9 人）。
        "callee_ids" to IMFieldKind.StrArray,
        "media_type" to IMFieldKind.Enumeration(E.MEDIA_TYPES, fallback = "audio"),
        "is_group" to IMFieldKind.Flag(),
        // "" = 服务端建房。
        "room_id" to IMFieldKind.Str(),
        "timeout_sec" to IMFieldKind.Num(
            default = E.DEFAULT_TIMEOUT_SEC,
            min = E.MIN_TIMEOUT_SEC,
            max = E.MAX_TIMEOUT_SEC,
        ),
        "user_data" to IMFieldKind.Str(),
    )

    /** **主叫此时禁止 room.join**——接听前不进 SFU（§4.1）。 */
    val INVITE_OK: IMFrameFields = mapOf(
        "call_id" to IMFieldKind.Str(),
        "room_id" to IMFieldKind.Str(),
        "invited_at_ms" to IMFieldKind.Num(),
    )

    /**
     * 只带 call_id 的上行帧共用（accept / reject / cancel / hangup / join）。
     *
     * 接通后主叫也用 hangup，**不用 cancel**——两个词不共用一条路径，
     * 避免「取消一通已接通的电话」这种歧义。
     */
    val CALL_ID: IMFrameFields = mapOf("call_id" to IMFieldKind.Str())

    /** 群通话中途加邀（P4）。仅主叫可发。 */
    val INVITE_MORE: IMFrameFields = mapOf(
        "call_id" to IMFieldKind.Str(),
        "callee_ids" to IMFieldKind.StrArray,
    )

    /** 被叫收到的邀请，对应 onCallReceived。 */
    val INCOMING: IMFrameFields = mapOf(
        "call_id" to IMFieldKind.Str(),
        "room_id" to IMFieldKind.Str(),
        "caller" to IMFieldKind.Str(),
        "callee_ids" to IMFieldKind.StrArray,
        "media_type" to IMFieldKind.Enumeration(E.MEDIA_TYPES, fallback = "audio"),
        "is_group" to IMFieldKind.Flag(),
        "timeout_sec" to IMFieldKind.Num(
            default = E.DEFAULT_TIMEOUT_SEC,
            min = E.MIN_TIMEOUT_SEC,
            max = E.MAX_TIMEOUT_SEC,
        ),
        "invited_at_ms" to IMFieldKind.Num(),
        "user_data" to IMFieldKind.Str(),
    )

    /**
     * 告诉主叫「对方设备开始响铃了」，每个被叫 uid 只发一次。
     * UI 据此把「正在呼叫…」改成「等待对方接听…」。它**不对应任何回调**。
     */
    val RINGING: IMFrameFields = mapOf(
        "call_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
        "device_count" to IMFieldKind.Num(),
    )

    /** 某成员的裁决，四个帧共用（call.accepted / rejected / busy / no_answer）。 */
    val MEMBER_OUTCOME: IMFrameFields = mapOf(
        "call_id" to IMFieldKind.Str(),
        "uid" to IMFieldKind.Str(),
    )

    /** 主叫取消，发给全部被叫设备。 */
    val CANCELLED: IMFrameFields = mapOf("call_id" to IMFieldKind.Str(), "by" to IMFieldKind.Str())

    /**
     * 「可以进房了」，对应 onCallBegin。
     *
     * call.accept.ok 是纯 ack，房间信息只在这一条帧里——一个东西一条路径。
     */
    val CONNECTED: IMFrameFields = mapOf(
        "call_id" to IMFieldKind.Str(),
        "room_id" to IMFieldKind.Str(),
        // 绑定 (room_id, uid, device_id)，TTL 5 分钟、一次性。**不要整条打日志**。
        "room_token" to IMFieldKind.Str(),
        "media_type" to IMFieldKind.Enumeration(E.MEDIA_TYPES, fallback = "audio"),
        "is_group" to IMFieldKind.Flag(),
        // 通话时长的起点，服务端时钟。
        "connected_at_ms" to IMFieldKind.Num(),
        "accepted_by" to IMFieldKind.Str(),
    )

    /** 本账号另一台设备处理了这通电话。 */
    val HANDLED_ELSEWHERE: IMFrameFields = mapOf(
        "call_id" to IMFieldKind.Str(),
        "action" to IMFieldKind.Enumeration(E.HANDLED_ACTIONS, fallback = "accept"),
        "device_id" to IMFieldKind.Str(),
    )

    /**
     * **唯一终态帧**。
     *
     * 铁律：每个成员设备收到且仅收到一条；所有结局都走它；
     * 宿主只监听它也必须能完整记录一通电话。
     */
    val ENDED: IMFrameFields = mapOf(
        "call_id" to IMFieldKind.Str(),
        "room_id" to IMFieldKind.Str(),
        "reason" to IMFieldKind.Enumeration(E.REASONS, fallback = "error"),
        // 未接通恒为 0。**各端禁止自己算时长**（时钟偏移），一律用这个值。
        "duration_sec" to IMFieldKind.Num(),
        "ended_by" to IMFieldKind.Str(),
    )
}
