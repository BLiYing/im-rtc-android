package com.imrtc.engine

/**
 * 发起通话的可选参数（`HOST_INTEGRATION_DESIGN.md` §3.3）。
 *
 * 1v1 且不需要群号 / 自定义数据时，旧的 `call(userIds, mediaType, isGroup)` 就够；
 * 群通话要带 [chatGroupId]（宿主自己的群号，Kit 靠它决定「添加成员」列谁）时用这个重载。
 */
data class IMCallOptions
@JvmOverloads
constructor(
    /** 群通话置 true（房内含主叫最多 9 人）。默认 false。 */
    val isGroup: Boolean = false,
    /**
     * 宿主自己的群号，opaque，≤64 字节 UTF-8，禁止空白与换行（协议 §2.5）。
     * 服务端不解析、不校验群成员关系；通话期间不可改。超限本地直接拒绝，见
     * [IMCallEngine.call]（options 重载）文档。
     */
    val chatGroupId: String = "",
    /** opaque 字符串 ≤4096 字节，原样透传给被叫（`onCallReceived`）与接通者（`onCallBegin`），服务端不解析。 */
    val userData: String = "",
    /** 振铃超时，秒。0 = 使用协议默认值 30；范围 5~120，越界由服务端钳边界。 */
    val timeoutSec: Int = 0,
)
