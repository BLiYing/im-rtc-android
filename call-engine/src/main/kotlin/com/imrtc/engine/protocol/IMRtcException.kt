package com.imrtc.engine.protocol

/**
 * 协议层的失败。带**错误码表里的码**，不是随手写的字符串。
 *
 * `detail` 是给开发者看的中文说明（带字段路径），[IMErrorCode.msg] 才是契约里那句英文短语。
 * 两者不要混：抛给宿主时给码 + msg，写日志时给 detail。
 */
internal class IMRtcException(
    val errorCode: IMErrorCode,
    val detail: String,
) : Exception("${errorCode.code} ${errorCode.wireName}: $detail") {

    companion object {
        /**
         * 把 JSON 解析失败翻译成协议错误。
         *
         * 这个映射是 `envelope.json` 逐条钉死的：**结构不对 → `bad_envelope`，
         * 值不合协议 → `bad_params`**。别图省事全归到一个码上——
         * 服务端与另外三端都按这两个码分别断言。
         */
        fun from(error: IMJsonError): IMRtcException = when (error.kind) {
            IMJsonError.Kind.STRUCTURE -> IMRtcException(IMErrorCode.BAD_ENVELOPE, error.reason)
            IMJsonError.Kind.VALUE -> IMRtcException(IMErrorCode.BAD_PARAMS, error.reason)
        }
    }
}
