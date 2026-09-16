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
        // 宿主自己的群号，服务端不解析、不校验群成员关系（HOST_INTEGRATION_DESIGN §3.2）。
        // Kit 靠它决定「添加成员」列谁；通话期间不可改，invite_more / join 都不带它。
        "chat_group_id" to IMFieldKind.Str(),
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
        // 这次邀请是谁发的：首次邀请就是主叫，call.invite_more 加进来的人是发那条加人请求的人。
        // 旧服务端不带它，回落到 caller（在状态机里做）。
        "inviter" to IMFieldKind.Str(),
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
        // 同 INVITE 的 chat_group_id，原样带出去。被叫靠它把「添加成员」指向哪个群。
        "chat_group_id" to IMFieldKind.Str(),
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
        /*
         2026-09-15 起补的三个字段（HOST_INTEGRATION_DESIGN §3.2）：`call.join` 进来的人没收过
         `call.incoming`，断线恢复后的端也可能丢了它，只有这里能原样拿到。
         onCallBegin 里取这里的值，为空时（老服务端）回落到本通 call.incoming / call() 选项记下的值
         ——见 CallStateMachineRecv.handleConnected。
        */
        "caller" to IMFieldKind.Str(),
        "chat_group_id" to IMFieldKind.Str(),
        "user_data" to IMFieldKind.Str(),
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
        /*
         这通电话是谁打的。**忙线那条不振铃**，被叫拿到的第一帧也是最后一帧就是它——
         没有这个字段就说不出「谁来过电话」（协议 §4.2）。**字段没登记在这里，解码器会丢掉它**，
         `onCallMissed` 拿到的 caller 就是空串。
        */
        "caller" to IMFieldKind.Str(),
    )
}
