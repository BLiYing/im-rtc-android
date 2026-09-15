package com.imrtc.engine.protocol

/**
 * `call()` 选项的本地校验（`HOST_INTEGRATION_DESIGN.md` §3.3）。
 *
 * `chat_group_id` 超 64 字节或含空白、`user_data` 超 4096 字节：**本地直接拦下，不上线路**——
 * 与「callee_ids 里有自己」服务端拒绝走同一个出口（onError 1004 + onCallEnd(error)），
 * 见 `IMCallEngine.call(userIds, mediaType, options)`。
 *
 * 纯函数、不碰网络、不碰状态机，放在 protocol/ 里就能用普通 JUnit 跑（CONVENTIONS §1）。
 */
internal object IMCallOptionsGuard {

    /** 协议 §2.5：`chat_group_id` ≤64 字节，禁止空白与换行。 */
    private const val MAX_CHAT_GROUP_ID_BYTES = 64

    /** 协议 §2.5：`user_data` ≤4096 字节。 */
    private const val MAX_USER_DATA_BYTES = 4096

    /** 校验不过时返回一句英文短语（供 onError 使用，不直接显示给用户）；通过返回 null。 */
    fun validate(chatGroupId: String, userData: String): String? {
        val groupBytes = chatGroupId.toByteArray(Charsets.UTF_8).size
        if (groupBytes > MAX_CHAT_GROUP_ID_BYTES) {
            return "chat_group_id 长 $groupBytes 字节，上限 $MAX_CHAT_GROUP_ID_BYTES（协议 §2.5）"
        }
        if (chatGroupId.any { it.isWhitespace() }) {
            return "chat_group_id 不能含空白或换行（协议 §2.5）"
        }
        val dataBytes = userData.toByteArray(Charsets.UTF_8).size
        if (dataBytes > MAX_USER_DATA_BYTES) {
            return "user_data 长 $dataBytes 字节，上限 $MAX_USER_DATA_BYTES（协议 §2.5）"
        }
        return null
    }
}
