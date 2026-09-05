package com.imrtc.engine.protocol

/**
 * §2.4「七条编码硬规则」里能**脱离帧定义**判定的那几条。
 *
 * 它们是「C++ 端能实现」这句话的落点：不放浮点、不放 null、数组同构、嵌套不超过两层，
 * 五端的解码器就都不需要处理 `optional<optional<T>>`、undefined/null 之分、异构数组这些坑。
 *
 * 另外两条（字段类型恒定、枚举封闭带兜底）没法脱离帧定义判，由 [FieldCodec] 负责。
 *
 * 浮点与 null 在 [IMJsonParser] 里就被挡掉了（那两条编进了类型系统），
 * 所以这里只剩「同构数组」与「嵌套深度」两件事。
 */
internal object Discipline {

    /**
     * `data` 本身算第 1 层，`data` 里再嵌一层对象算第 2 层，第 3 层非法。
     *
     * 例：`data.limits.max_callees` 合法；`data.a.b.c`（c 是对象）非法。
     * **数组不增加深度**——`speakers:[{uid,volume}]` 里的元素对象仍是第 2 层。
     */
    const val MAX_OBJECT_DEPTH = 2

    @Throws(IMRtcException::class)
    fun check(data: Map<String, IMJson>) = walkObject(data, depth = 1, path = "data")

    private fun walk(value: IMJson, depth: Int, path: String) {
        when (value) {
            is IMJson.Str, is IMJson.Num, is IMJson.Bool -> return
            is IMJson.Obj -> walkObject(value.fields, depth + 1, path)
            is IMJson.Arr -> walkArray(value.items, depth, path)
        }
    }

    private fun walkObject(fields: Map<String, IMJson>, depth: Int, path: String) {
        if (depth > MAX_OBJECT_DEPTH) {
            throw IMRtcException(
                IMErrorCode.BAD_PARAMS,
                "$path: 对象嵌套 $depth 层 > 上限 $MAX_OBJECT_DEPTH；" +
                    "要塞任意结构请用 user_data（opaque 字符串）",
            )
        }
        // 键序不影响判定，但排一下能让报错稳定可复现。
        for (key in fields.keys.sorted()) {
            val value = fields[key] ?: continue
            walk(value, depth, "$path.$key")
        }
    }

    /**
     * 除了递归检查元素，还要确认数组是**同构**的（§2.4 规则 4）。
     *
     * 异构数组在 Kotlin/TS 里勉强能表达，在 C++ 里就得上 variant——所以协议层直接禁掉。
     */
    private fun walkArray(items: List<IMJson>, depth: Int, path: String) {
        var firstKind: String? = null
        items.forEachIndexed { index, item ->
            val kind = item.kindName()
            val expected = firstKind
            if (expected == null) {
                firstKind = kind
            } else if (kind != expected) {
                throw IMRtcException(
                    IMErrorCode.BAD_PARAMS,
                    "$path: 数组必须同构，第 0 个是 $expected、第 $index 个是 $kind；要成对请用对象数组",
                )
            }
            walk(item, depth, "$path[$index]")
        }
    }
}

/** 给报错用的类型名。 */
internal fun IMJson.kindName(): String = when (this) {
    is IMJson.Str -> "string"
    is IMJson.Num -> "int"
    is IMJson.Bool -> "bool"
    is IMJson.Arr -> "array"
    is IMJson.Obj -> "object"
}
