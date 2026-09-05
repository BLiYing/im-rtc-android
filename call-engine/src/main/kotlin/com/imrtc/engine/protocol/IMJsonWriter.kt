package com.imrtc.engine.protocol

/**
 * 把 [IMJson] 写回文本。
 *
 * 因为值模型里根本没有 null 与 double 两个 case，**写出来的东西天然满足协议**——
 * 不需要「序列化前再校验一遍」。这正是把规则编进类型系统换来的好处。
 *
 * **键顺序不做保证也不需要保证**：向量比对的是解析后的值，不是字节。
 * 五种语言的 JSON 库键序本来就不一致，比字节必挂。
 */
internal object IMJsonWriter {

    fun write(value: IMJson): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: IMJson) {
        when (value) {
            is IMJson.Str -> appendString(sb, value.value)
            is IMJson.Num -> sb.append(value.value)
            is IMJson.Bool -> sb.append(if (value.value) "true" else "false")
            is IMJson.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    append(sb, item)
                }
                sb.append(']')
            }
            is IMJson.Obj -> {
                sb.append('{')
                var first = true
                for ((key, item) in value.fields) {
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, key)
                    sb.append(':')
                    append(sb, item)
                }
                sb.append('}')
            }
        }
    }

    private fun appendString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                // 其余控制字符必须转义，否则写出来的不是合法 JSON。
                c.code < 0x20 -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
