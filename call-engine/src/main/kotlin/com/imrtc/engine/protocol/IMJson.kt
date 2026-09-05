package com.imrtc.engine.protocol

/**
 * 协议的 JSON 值模型。
 *
 * **这个类型里压根没有 `Null` 与 `Double` 两个 case** —— 协议 §2.4 的头两条硬规则
 * （「没有浮点数」「没有 null」）就这样编进了类型系统：不是靠校验拦，是根本表达不出来。
 * iOS 端的 `IMJSON` 是同一个做法。
 *
 * 为什么不用 `org.json`：它在 JVM 单测里是空壳桩，方法一律返回默认值，测试会**假绿**
 * （CONVENTIONS §「技术栈」）。为什么不用第三方 JSON 库：协议要的是**更严**而不是更宽松的
 * 解析（整数按值判定、越界拒绝、NUL 拒绝），通用库都得再包一层校验，还不如直接写。
 *
 * 「字段不存在」用 Kotlin 的 `null` 表达（见下面的可空取值方法）——那是**语言层的缺席**，
 * 不是线路上的 JSON `null`，两者不要混为一谈。
 */
internal sealed interface IMJson {

    data class Str(val value: String) : IMJson

    /** 整数。协议里所有数字都是整数，范围 ±(2^53-1)（§2.4 规则 7）。 */
    data class Num(val value: Long) : IMJson

    data class Bool(val value: Boolean) : IMJson

    data class Arr(val items: List<IMJson>) : IMJson

    data class Obj(val fields: Map<String, IMJson>) : IMJson

    companion object {
        /** 协议允许的最大整数：2^53-1，与 JS 的 `Number.MAX_SAFE_INTEGER` 一致（§2.4 规则 7）。 */
        const val MAX_SAFE_INTEGER: Long = 9007199254740991L
    }
}

// ── 取值：拿不到就返回 null，不抛 ──────────────────────────────────────────
// 命名不叫 getXxx 是因为它们不是「一定有」的访问器；调用点必须显式处理缺席。

internal fun IMJson.Obj.optString(key: String): String? = (fields[key] as? IMJson.Str)?.value

internal fun IMJson.Obj.optLong(key: String): Long? = (fields[key] as? IMJson.Num)?.value

internal fun IMJson.Obj.optBool(key: String): Boolean? = (fields[key] as? IMJson.Bool)?.value

internal fun IMJson.Obj.optObj(key: String): IMJson.Obj? = fields[key] as? IMJson.Obj

internal fun IMJson.Obj.optArr(key: String): List<IMJson>? = (fields[key] as? IMJson.Arr)?.items

internal fun IMJson.Obj.has(key: String): Boolean = fields.containsKey(key)

/** 数组元素全是字符串时取出来；只要有一个不是就返回 null（协议 §2.4 规则 4：数组同构）。 */
internal fun IMJson.Obj.optStringArr(key: String): List<String>? {
    val items = optArr(key) ?: return null
    val out = ArrayList<String>(items.size)
    for (item in items) {
        if (item !is IMJson.Str) return null
        out.add(item.value)
    }
    return out
}

/** 数组元素全是对象时取出来；只要有一个不是就返回 null。 */
internal fun IMJson.Obj.optObjArr(key: String): List<IMJson.Obj>? {
    val items = optArr(key) ?: return null
    val out = ArrayList<IMJson.Obj>(items.size)
    for (item in items) {
        if (item !is IMJson.Obj) return null
        out.add(item)
    }
    return out
}
