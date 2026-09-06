package com.imrtc.engine.protocol

/** sys 域：连接、鉴权、心跳、错误。见 §1 与 §7。 */
internal object SysFrames {

    /** `data` 恒为 `{}` 的帧：sys.ping / sys.pong / 各种纯 ack。 */
    val EMPTY: IMFrameFields = emptyMap()

    /**
     * WS 打开后必须在 5 秒内发出的第一帧（§1.2）。
     *
     * token 走首帧而不是 URL 查询串：查询串会进网关日志、Referer 与浏览器历史。
     */
    val HELLO: IMFrameFields = mapOf(
        "protocol_version" to IMFieldKind.Num(default = 1),
        "token" to IMFieldKind.Str(),
        "device_id" to IMFieldKind.Str(),
        // 重连恢复用；首次连接为 ""。
        "session_id" to IMFieldKind.Str(),
        // 仅用于日志与灰度，**禁止参与逻辑**。
        "sdk" to IMFieldKind.Str(),
    )

    /** 服务端下发的限额，让客户端能本地预校验（§2.6）。 */
    val LIMITS: IMFrameFields = mapOf(
        "max_frame_bytes" to IMFieldKind.Num(),
        "max_callees" to IMFieldKind.Num(),
        "max_room_participants" to IMFieldKind.Num(),
        "max_user_data_bytes" to IMFieldKind.Num(),
        "ring_timeout_sec_default" to IMFieldKind.Num(),
    )

    /** 鉴权成功的应答。 */
    val HELLO_OK: IMFrameFields = mapOf(
        "uid" to IMFieldKind.Str(),
        "device_id" to IMFieldKind.Str(),
        "session_id" to IMFieldKind.Str(),
        // 供客户端算时钟偏移，**只做展示**。
        "server_time_ms" to IMFieldKind.Num(),
        "resumed" to IMFieldKind.Flag(),
        "ping_interval_sec" to IMFieldKind.Num(),
        // 本次握手用的那张票的到期时刻（Unix 毫秒）。**0 = 未知**。
        // 客户端据此在到期前主动换票；**禁止自己解析 token 取 exp**——
        // 票对客户端是不透明的，可能根本不是 JWT。
        "token_expires_at_ms" to IMFieldKind.Num(),
        "limits" to IMFieldKind.Nested(LIMITS),
    )

    /** `sys.error` 的 data（§7）。 */
    val ERROR: IMFrameFields = mapOf(
        "code" to IMFieldKind.Num(),
        "name" to IMFieldKind.Str(),
        // 英文固定短语，给开发者看；**禁止直接显示给用户**。
        "msg" to IMFieldKind.Str(),
        "for_type" to IMFieldKind.Str(),
        "retryable" to IMFieldKind.Flag(),
    )
}
