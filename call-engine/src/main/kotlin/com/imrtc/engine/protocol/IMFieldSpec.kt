package com.imrtc.engine.protocol

/**
 * 帧字段的**声明式定义**。
 *
 * 这套声明负责 §2.4 里必须**结合帧定义**才能判的两条：
 * 规则 3（字段类型恒定）与规则 6（枚举封闭且带兜底）。与帧无关的四条在 [Discipline]。
 *
 * 键**直接用线路上的 snake_case 名**，解出来的也还是 `Map<String, IMJson>`。
 * 理由与 iOS 端一样：逐帧手写强类型结构等于「同一份协议写两遍」，迟早漂。
 * 强类型留给门面层按需要包（那一层还要 Java 友好，形状本来就不一样）。
 */
internal sealed interface IMFieldKind {

    data class Str(val default: String = "") : IMFieldKind

    /**
     * 整数。越界处理**不能一刀切**：`timeout_sec` 越界钳到边界（§2.6），
     * 而质量 `level` 越界折成 0 = unknown。给了 [outOfRange] 就折成它，否则钳边界。
     */
    data class Num(
        val default: Long = 0L,
        val min: Long? = null,
        val max: Long? = null,
        val outOfRange: Long? = null,
    ) : IMFieldKind

    data class Flag(val default: Boolean = false) : IMFieldKind

    /**
     * 收到集合外的值折成 [fallback]——**禁止崩溃、禁止透传给 UI**。
     * 这是「新增枚举值不算破坏兼容」（§10）成立的前提。
     */
    data class Enumeration(
        val values: List<String>,
        val fallback: String,
        val default: String? = null,
    ) : IMFieldKind

    data object StrArray : IMFieldKind

    data class EnumArray(val values: List<String>, val fallback: String) : IMFieldKind

    data class ObjArray(val fields: IMFrameFields) : IMFieldKind

    data class Nested(val fields: IMFrameFields) : IMFieldKind
}

/** 线路字段名 → 契约。 */
internal typealias IMFrameFields = Map<String, IMFieldKind>

internal object FieldCodec {

    /**
     * 把线路上的 `data` 解成**补齐默认值**的 `data`。
     *
     * 「可选字段用省略表达，接收方按默认值填」（§2.4 规则 2）就落在这里：
     * 字段缺席 → 取默认值；出现了 → 校验类型、归一化枚举、钳制数值。
     * **未知字段直接忽略**（§10 的前向兼容要求：服务端可能比客户端新）。
     */
    @Throws(IMRtcException::class)
    fun decode(fields: IMFrameFields, raw: Map<String, IMJson>, path: String = "data"): Map<String, IMJson> {
        val out = LinkedHashMap<String, IMJson>(fields.size)
        for ((wire, kind) in fields) {
            out[wire] = decodeField(kind, raw[wire], "$path.$wire")
        }
        return out
    }

    /**
     * 一帧的全默认值 `data`。
     *
     * **发送侧一定要从它起手**：直接发零值会把 `auto_subscribe` 写成 false，
     * 人进了房却收不到任何流——这是各端都踩过的「发送侧默认值陷阱」（§2.4 规则 2 的注）。
     */
    fun defaults(fields: IMFrameFields): Map<String, IMJson> {
        val out = LinkedHashMap<String, IMJson>(fields.size)
        for ((wire, kind) in fields) {
            out[wire] = defaultValue(kind)
        }
        return out
    }

    private fun decodeField(kind: IMFieldKind, value: IMJson?, path: String): IMJson {
        if (value == null) return defaultValue(kind)

        return when (kind) {
            is IMFieldKind.Str -> IMJson.Str(expectString(value, path))

            // 布尔必须是真布尔：0/1/"true" 一律拒（§2.4「禁止用 0/1 代替」）。
            is IMFieldKind.Flag -> {
                val flag = (value as? IMJson.Bool)?.value
                    ?: throw IMRtcException(IMErrorCode.BAD_PARAMS, "$path 必须是布尔，得到 ${value.kindName()}")
                IMJson.Bool(flag)
            }

            is IMFieldKind.Num -> {
                val number = (value as? IMJson.Num)?.value
                    ?: throw IMRtcException(IMErrorCode.BAD_PARAMS, "$path 必须是整数，得到 ${value.kindName()}")
                IMJson.Num(coerce(number, kind.min, kind.max, kind.outOfRange))
            }

            is IMFieldKind.Enumeration -> {
                val text = expectString(value, path)
                IMJson.Str(if (text in kind.values) text else kind.fallback)
            }

            is IMFieldKind.StrArray -> IMJson.Arr(
                expectArray(value, path).mapIndexed { index, item ->
                    IMJson.Str(expectString(item, "$path[$index]"))
                },
            )

            is IMFieldKind.EnumArray -> IMJson.Arr(
                expectArray(value, path).mapIndexed { index, item ->
                    val text = expectString(item, "$path[$index]")
                    IMJson.Str(if (text in kind.values) text else kind.fallback)
                },
            )

            is IMFieldKind.ObjArray -> IMJson.Arr(
                expectArray(value, path).mapIndexed { index, item ->
                    val elementPath = "$path[$index]"
                    IMJson.Obj(decode(kind.fields, expectObject(item, elementPath), elementPath))
                },
            )

            is IMFieldKind.Nested -> IMJson.Obj(decode(kind.fields, expectObject(value, path), path))
        }
    }

    /**
     * 字段的协议默认值。
     *
     * **数组的默认值恒为空数组，绝不是缺席**——协议里没有 null，
     * 而「字段整个不见了」与「空数组」在接收端是两种代码分支。
     */
    private fun defaultValue(kind: IMFieldKind): IMJson = when (kind) {
        is IMFieldKind.Str -> IMJson.Str(kind.default)
        is IMFieldKind.Num -> IMJson.Num(kind.default)
        is IMFieldKind.Flag -> IMJson.Bool(kind.default)
        is IMFieldKind.Enumeration -> IMJson.Str(kind.default ?: kind.fallback)
        is IMFieldKind.StrArray, is IMFieldKind.EnumArray, is IMFieldKind.ObjArray -> IMJson.Arr(emptyList())
        is IMFieldKind.Nested -> IMJson.Obj(defaults(kind.fields))
    }

    private fun coerce(value: Long, min: Long?, max: Long?, outOfRange: Long?): Long {
        val belowMin = min != null && value < min
        val aboveMax = max != null && value > max
        if (!belowMin && !aboveMax) return value
        if (outOfRange != null) return outOfRange
        return if (belowMin) (min ?: value) else (max ?: value)
    }

    private fun expectString(value: IMJson, path: String): String =
        (value as? IMJson.Str)?.value
            ?: throw IMRtcException(IMErrorCode.BAD_PARAMS, "$path 必须是字符串，得到 ${value.kindName()}")

    private fun expectArray(value: IMJson, path: String): List<IMJson> =
        (value as? IMJson.Arr)?.items
            ?: throw IMRtcException(IMErrorCode.BAD_PARAMS, "$path 必须是数组，得到 ${value.kindName()}")

    private fun expectObject(value: IMJson, path: String): Map<String, IMJson> =
        (value as? IMJson.Obj)?.fields
            ?: throw IMRtcException(IMErrorCode.BAD_PARAMS, "$path 必须是对象，得到 ${value.kindName()}")
}
