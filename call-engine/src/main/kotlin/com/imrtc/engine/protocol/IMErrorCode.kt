package com.imrtc.engine.protocol

/**
 * 错误码全表，对应协议 §7 与 `docs/conformance/error_codes.json`。
 *
 * 三条规矩，破一条就是破契约：
 * 1. **code 与 name 只增不改不删**；废弃的码保留占位。
 * 2. **`wire` 组的码会出现在 `sys.error` 帧里；`local` 组永不上线路**，只经 onError 抛给宿主。
 * 3. **`msg` 是英文固定短语，五仓必须发出完全相同的字符串**——它是契约的一部分，
 *    不是各端自由发挥的提示语。给开发者看，**禁止直接显示给用户**；
 *    UI 文案由端上按 code 查自己的本地化表。
 *
 * 本文件是从向量一次性生成的（照抄 45 条容易手滑）。之后向量再改，
 * `ErrorCodeVectorsTest` 会逐条比对并挂掉——**那正是它存在的意义**。
 */
internal enum class IMErrorCode(
    val code: Int,
    /** 线路上的 name，与错误码表一一对应。 */
    val wireName: String,
    /** 英文固定短语，禁止直接显示给用户。 */
    val msg: String,
    val group: String,
    val retryable: Boolean,
) {
    /** 信封字段缺失/类型错/data 为 null */
    BAD_ENVELOPE(1001, "bad_envelope", "malformed envelope", "protocol", false),
    /** 未知 type */
    UNKNOWN_TYPE(1002, "unknown_type", "unknown frame type", "protocol", false),
    /** 会议层留位帧，v1 未实现 */
    NOT_IMPLEMENTED(1003, "not_implemented", "frame not implemented", "protocol", false),
    /** data 字段非法：超长、枚举越界、数组含自己 */
    BAD_PARAMS(1004, "bad_params", "invalid frame parameters", "protocol", false),
    /** 单帧超过 64 KiB */
    FRAME_TOO_LARGE(1005, "frame_too_large", "frame too large", "protocol", false),
    /** sys.hello.protocol_version 不受支持 */
    PROTOCOL_VERSION_UNSUPPORTED(1006, "protocol_version_unsupported", "protocol version unsupported", "protocol", false),
    /** 上行频率超限 */
    RATE_LIMITED(1007, "rate_limited", "rate limited", "protocol", true),
    /** token 签名/受众/绑定关系错 */
    TOKEN_INVALID(1101, "token_invalid", "token invalid", "auth", false),
    /** token 过期，换新的重试 */
    TOKEN_EXPIRED(1102, "token_expired", "token expired", "auth", true),
    /** 未发 sys.hello 就发别的帧 */
    NOT_AUTHENTICATED(1103, "not_authenticated", "not authenticated", "auth", false),
    /** 同 uid 同 device_id 在别处登录 */
    KICKED_OUT(1104, "kicked_out", "kicked out", "auth", false),
    /** session_id 无效或超出 30s 恢复窗口 */
    SESSION_NOT_RESUMABLE(1105, "session_not_resumable", "session not resumable", "auth", false),
    /**
     * 票据合法但该 app_id 已被停用（宿主在控制台停用了整个应用）。
     *
     * **与 1101/1102 不是一回事**：那两个是「票有问题，换一张再来」，
     * 而这个是「票没问题，是这个应用被停了」——端上该显示「服务已停用」
     * 而不是把人送回登录页反复重试。
     */
    APP_DISABLED(1106, "app_disabled", "application disabled", "auth", false),
    /** 房间不存在或已关闭 */
    ROOM_NOT_FOUND(1201, "room_not_found", "room not found", "room", false),
    /** 超出 max_participants */
    ROOM_FULL(1202, "room_full", "room is full", "room", false),
    /** 未 join 就发房间帧 */
    NOT_IN_ROOM(1203, "not_in_room", "not in room", "room", false),
    /** 重复 join 同一房间 */
    ALREADY_IN_ROOM(1204, "already_in_room", "already in room", "room", false),
    /** 房间已被解散 */
    ROOM_CLOSED(1205, "room_closed", "room closed", "room", false),
    /** 无该操作权限 */
    PERMISSION_DENIED(1206, "permission_denied", "permission denied", "room", false),
    /** 目标 participant 不在房 */
    PARTICIPANT_NOT_FOUND(1207, "participant_not_found", "participant not found", "room", false),
    /** 订阅/退订不存在的 track */
    TRACK_NOT_FOUND(1301, "track_not_found", "track not found", "media", false),
    /** 同 source 重复发布或房间策略禁止 */
    PUBLISH_DENIED(1302, "publish_denied", "publish denied", "media", false),
    /** 订阅自己的 track 或无订阅权限 */
    SUBSCRIBE_DENIED(1303, "subscribe_denied", "subscribe denied", "media", false),
    /** SDP 解析失败/超长/cid 认不回来/在非 offerer 侧发 offer */
    SDP_INVALID(1304, "sdp_invalid", "sdp invalid", "media", false),
    /** pc 对应的 PeerConnection 不存在 */
    PC_NOT_FOUND(1305, "pc_not_found", "peer connection not found", "media", false),
    /** max_layer 取值非法 */
    LAYER_UNAVAILABLE(1306, "layer_unavailable", "layer unavailable", "media", false),
    /** SDP 里没有共同编码 */
    CODEC_UNSUPPORTED(1307, "codec_unsupported", "codec unsupported", "media", false),
    /** call_id 不存在 */
    CALL_NOT_FOUND(1401, "call_not_found", "call not found", "call", false),
    /** 通话已结束，客户端必须静默吞掉 */
    CALL_ENDED(1402, "call_ended", "call already ended", "call", false),
    /** 保留：v1 走 call.ended{offline} 而非报错 */
    CALLEE_OFFLINE(1403, "callee_offline", "callee offline", "call", false),
    /** 保留：v1 走 call.ended{busy} 而非报错 */
    CALLEE_BUSY(1404, "callee_busy", "callee busy", "call", false),
    /** 在错误状态下 accept/reject/cancel/hangup/join */
    INVALID_CALL_STATE(1405, "invalid_call_state", "invalid call state", "call", false),
    /** callee_ids 超上限 */
    TOO_MANY_CALLEES(1406, "too_many_callees", "too many callees", "call", false),
    /** 非主叫发 call.cancel；不在通话里的人发 call.invite_more */
    NOT_CALL_OWNER(1407, "not_call_owner", "not call owner", "call", false),
    /** 自己已在别的通话中 */
    ALREADY_IN_CALL(1408, "already_in_call", "already in call", "call", false),
    /** 宿主的邀请鉴权回调拒绝了 call.invite / call.invite_more / call.join（或回调失败且应用配成拒绝） */
    INVITE_DENIED(1409, "invite_denied", "invite denied by host", "call", false),
    /** 内部错误兜底 */
    INTERNAL(1501, "internal", "internal error", "server", true),
    /** 无可用 SFU 节点 */
    SFU_UNAVAILABLE(1502, "sfu_unavailable", "sfu unavailable", "server", true),
    /** 服务端正在优雅关闭 */
    SHUTTING_DOWN(1503, "shutting_down", "server shutting down", "server", true),
    /** 持久化失败 */
    STORE_ERROR(1504, "store_error", "store error", "server", true),
    /** 用户拒绝麦克风/摄像头权限 */
    DEVICE_PERMISSION_DENIED(2001, "device_permission_denied", "device permission denied", "local", false),
    /** 没有可用的采集设备 */
    DEVICE_NOT_FOUND(2002, "device_not_found", "device not found", "local", false),
    /** 信令连不上、DNS/TLS 失败 */
    NETWORK_UNREACHABLE(2003, "network_unreachable", "network unreachable", "local", true),
    /** 请求 10 秒无应答 */
    SIGNALING_TIMEOUT(2004, "signaling_timeout", "signaling timeout", "local", true),
    /** 宿主在错误状态下调 Engine 方法 */
    INVALID_STATE(2005, "invalid_state", "invalid state", "local", false),
    /** PeerConnection 协商失败 / ICE failed */
    MEDIA_NEGOTIATION_FAILED(2006, "media_negotiation_failed", "media negotiation failed", "local", false),
    /** 未 login 就调业务方法 */
    NOT_LOGGED_IN(2007, "not_logged_in", "not logged in", "local", false),
    ;

    /** 会不会出现在线路上的 `sys.error` 帧里。 */
    val isWire: Boolean get() = group != "local"

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** 未知码返回 null——**不要造一个假的枚举值**，上层要能分辨「没见过这个码」。 */
        fun fromCode(code: Int): IMErrorCode? = byCode[code]
    }
}

/** 关闭码（WebSocket close code），对应协议 §1.5。 */
internal enum class IMCloseCode(val code: Int, val meaning: String, val shouldReconnect: Boolean) {
    NORMAL(1000, "正常关闭（客户端主动 logout）", false),
    GOING_AWAY(1001, "服务端下线/重启", true),
    BAD_PROTOCOL(4400, "信封非法/帧超长/协议版本不支持", false),
    UNAUTHORIZED(4401, "未鉴权/鉴权超时/token 无效或过期", true),
    KICKED(4403, "被踢（同 uid 同 device_id 在别处登录）", false),
    RATE_LIMITED(4429, "频率超限", true),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): IMCloseCode? = byCode[code]
    }
}
