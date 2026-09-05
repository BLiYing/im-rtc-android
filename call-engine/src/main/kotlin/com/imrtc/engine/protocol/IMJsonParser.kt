package com.imrtc.engine.protocol

import java.math.BigDecimal

/**
 * 严格 JSON 解析器：比标准 JSON **更严**，严的每一条都对应协议 §2.4 的一条硬规则。
 *
 * 三条最容易被忽略、且已经在别的端上踩过的规矩：
 *
 * 1. **数字按「值」判定，不按「字面量」判定。** `1e3` 是整数 1000，**必须收**；
 *    `15e-1` 是 1.5，**必须拒**。服务端最初按字面量判（见到 `e` 就拒），
 *    与 TS 端漂移过一次——现在五端统一按值判定，`envelope.json` 里有四条用例守着。
 * 2. **`null` 一律拒绝**，不是「当作缺省」。协议里可选字段的表达方式是**省略**（§2.4 规则 2）。
 * 3. **重复键取最后一个**（last-wins）。这不是协议规定，是**对齐另外四端**：
 *    Go 的 `encoding/json`、`JSON.parse`、Swift 的 `JSONSerialization` 全是 last-wins。
 *    在这里自作主张报错，会造成「同一份线路数据只有 Android 端解不出来」。
 *
 * 线程安全：无状态，可并发调用（每次解析用一个私有的游标对象）。
 */
internal object IMJsonParser {

    /**
     * 嵌套深度上限。这是**防御性上限**（挡恶意深嵌套把栈打爆），
     * 不是 §2.4 规则 5 的「对象不嵌套超过两层」——那条在字段声明层查。
     */
    private const val MAX_DEPTH = 32

    @Throws(IMJsonError::class)
    fun parse(text: String): IMJson {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        if (cursor.atEnd()) throw cursor.structure("空输入")
        val value = cursor.readValue(depth = 0)
        cursor.skipWhitespace()
        if (!cursor.atEnd()) throw cursor.structure("尾部有多余内容")
        return value
    }

    /** 只接受对象的入口——协议帧与一致性向量文件的顶层都必须是对象。 */
    @Throws(IMJsonError::class)
    fun parseObject(text: String): IMJson.Obj =
        parse(text) as? IMJson.Obj ?: throw IMJsonError(IMJsonError.Kind.STRUCTURE, "顶层不是对象", 0)

    // ── 以下是游标本体 ────────────────────────────────────────────────────

    private class Cursor(private val src: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        fun structure(reason: String) = IMJsonError(IMJsonError.Kind.STRUCTURE, reason, pos)

        fun value(reason: String) = IMJsonError(IMJsonError.Kind.VALUE, reason, pos)

        fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): IMJson {
            if (depth > MAX_DEPTH) throw structure("嵌套超过 $MAX_DEPTH 层")
            if (atEnd()) throw structure("值缺失")
            return when (val c = src[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> IMJson.Str(readString())
                't' -> { expectLiteral("true"); IMJson.Bool(true) }
                'f' -> { expectLiteral("false"); IMJson.Bool(false) }
                'n' -> {
                    expectLiteral("null")
                    throw structure("协议禁止 null（§2.4 规则 2：可选字段用省略表达）")
                }
                else ->
                    if (c == '-' || c in '0'..'9') readNumber()
                    else throw structure("无法识别的值起始字符 '$c'")
            }
        }

        private fun expectLiteral(literal: String) {
            if (!src.startsWith(literal, pos)) throw structure("期望字面量 $literal")
            pos += literal.length
        }

        private fun readObject(depth: Int): IMJson.Obj {
            pos++ // '{'
            val fields = LinkedHashMap<String, IMJson>()
            skipWhitespace()
            if (!atEnd() && src[pos] == '}') {
                pos++
                return IMJson.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || src[pos] != '"') throw structure("对象的键必须是字符串")
                val key = readString()
                skipWhitespace()
                if (atEnd() || src[pos] != ':') throw structure("键之后期望 ':'")
                pos++
                skipWhitespace()
                fields[key] = readValue(depth + 1) // 重复键：last-wins，理由见类注释
                skipWhitespace()
                if (atEnd()) throw structure("对象没有闭合")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return IMJson.Obj(fields) }
                    else -> throw structure("对象里期望 ',' 或 '}'")
                }
            }
        }

        private fun readArray(depth: Int): IMJson.Arr {
            pos++ // '['
            val items = ArrayList<IMJson>()
            skipWhitespace()
            if (!atEnd() && src[pos] == ']') {
                pos++
                return IMJson.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items.add(readValue(depth + 1))
                skipWhitespace()
                if (atEnd()) throw structure("数组没有闭合")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return IMJson.Arr(items) }
                    else -> throw structure("数组里期望 ',' 或 ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // 开引号
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw structure("字符串没有闭合")
                when (val c = src[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> { pos++; sb.append(readEscape()) }
                    else -> {
                        if (c.code < 0x20) {
                            throw structure("字符串里出现未转义的控制字符 U+%04X".format(c.code))
                        }
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) throw structure("转义序列被截断")
            val c = src[pos]
            pos++
            return when (c) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > src.length) throw structure("\\u 转义被截断")
                    val hex = src.substring(pos, pos + 4)
                    val code = hex.toIntOrNull(16) ?: throw structure("\\u 后面不是四位十六进制")
                    pos += 4
                    // 协议：字符串禁止内嵌 NUL（§2.4 补充）。未转义的 NUL 走不到这里——
                    // 它的码点 < 0x20，在 readString 里已按控制字符拦掉。
                    if (code == 0) throw value("字符串禁止内嵌 NUL（\\u0000）")
                    code.toChar()
                }
                else -> throw structure("无法识别的转义 \\$c")
            }
        }

        /**
         * 读一个数字。先按 JSON 语法扫出 token（语法不对 = STRUCTURE），
         * 再按**值**判断它是不是协议允许的整数（值不对 = VALUE）。
         */
        private fun readNumber(): IMJson.Num {
            val start = pos
            if (!atEnd() && src[pos] == '-') pos++
            // 整数部分：0 或 [1-9][0-9]*，不许前导零
            if (atEnd() || src[pos] !in '0'..'9') throw structure("数字缺少整数部分")
            if (src[pos] == '0') {
                pos++
            } else {
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            var hasFraction = false
            if (!atEnd() && src[pos] == '.') {
                hasFraction = true
                pos++
                if (atEnd() || src[pos] !in '0'..'9') throw structure("小数点后缺少数字")
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            var hasExponent = false
            if (!atEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                hasExponent = true
                pos++
                if (!atEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                if (atEnd() || src[pos] !in '0'..'9') throw structure("指数部分缺少数字")
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)

            // 纯整数字面量：直接走 Long；Long 都装不下的一律算越界（VALUE）
            if (!hasFraction && !hasExponent) {
                val v = token.toLongOrNull() ?: throw value("整数超出 ±(2^53-1)：$token")
                return IMJson.Num(checkedRange(v, token))
            }
            // 带小数点或指数：**按值**判定是不是整数（1e3 收，15e-1 拒）
            val decimal = try {
                BigDecimal(token)
            } catch (e: NumberFormatException) {
                throw value("无法解析的数字：$token")
            }
            if (decimal.stripTrailingZeros().scale() > 0) {
                throw value("协议禁止浮点数（§2.4 规则 1）：$token")
            }
            val asLong = try {
                decimal.toBigIntegerExact().longValueExact()
            } catch (e: ArithmeticException) {
                throw value("整数超出 ±(2^53-1)：$token")
            }
            return IMJson.Num(checkedRange(asLong, token))
        }

        private fun checkedRange(v: Long, token: String): Long {
            if (v > IMJson.MAX_SAFE_INTEGER || v < -IMJson.MAX_SAFE_INTEGER) {
                throw value("整数超出 ±(2^53-1)（§2.4 规则 7）：$token")
            }
            return v
        }
    }
}
