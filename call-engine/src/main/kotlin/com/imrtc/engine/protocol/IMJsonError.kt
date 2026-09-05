package com.imrtc.engine.protocol

/**
 * 解析失败。`kind` 决定它最终变成协议里的哪个错误码——**这个分类不是随手定的，
 * 是一致性向量 `envelope.json` 逐条钉死的**：
 *
 * | 输入 | kind | 对外错误 |
 * |---|---|---|
 * | `{"type":"sys.ping",` （截断） | [Kind.STRUCTURE] | `bad_envelope` |
 * | `"data":null` / `{"call_id":null}` | [Kind.STRUCTURE] | `bad_envelope` |
 * | `{"volume":73.5}` | [Kind.VALUE] | `bad_params` |
 * | `{"n":15e-1}` | [Kind.VALUE] | `bad_params` |
 * | `{"n":9007199254740992}` | [Kind.VALUE] | `bad_params` |
 *
 * 一句话：**「这段文本不是合法的协议 JSON」是 STRUCTURE，「是合法 JSON 但值不合协议」是 VALUE。**
 * 映射到错误码在信封层做（第二刀），本层只负责分类。
 */
internal class IMJsonError(
    val kind: Kind,
    val reason: String,
    /** 出错位置在原文中的字节下标，便于日志定位；不进对外错误信息。 */
    val offset: Int,
) : Exception("$kind@$offset: $reason") {

    enum class Kind {
        /** 结构不对：不是合法 JSON、出现 null、有多余内容、嵌套过深。→ `bad_envelope` */
        STRUCTURE,

        /** 结构合法但值违反协议：非整数、整数越界、字符串内嵌 NUL。→ `bad_params` */
        VALUE,
    }
}
