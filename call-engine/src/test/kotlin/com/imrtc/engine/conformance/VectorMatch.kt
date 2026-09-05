package com.imrtc.engine.conformance

import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.fail

/**
 * 向量的比对方式：**递归子集**。与服务端 Go runner 的 `matchSubset` 一比一对应。
 *
 * 规则三条：
 * - **对象**：只比向量列出来的键，多出来的字段不算错（前向兼容：实现可以比向量新）。
 * - **数组**：长度必须一致，然后逐个递归——这样「少抛一个成员」会被抓到，
 *   而元素对象里多出来的字段仍然放行。
 * - **标量**：相等。
 *
 * 为什么不是全等：回调的载荷本来就比帧窄。例如 `room.active_speakers` 帧里带
 * `participant_id`，而 §7.5 的 `onActiveSpeakers` 只给宿主 `[{uid, volume}]`——
 * 用全等比就会把「按回调表裁剪」误判成错误。
 *
 * **五端必须用同一种比对方式**，否则同一份向量各测各的，「五仓跑同一份」就成了句空话。
 */
internal object VectorMatch {

    fun subset(path: String, expected: IMJson, actual: IMJson?) {
        if (actual == null) {
            fail("$path：字段缺失（期望 ${render(expected)}）")
            return
        }
        when (expected) {
            is IMJson.Obj -> {
                val got = actual as? IMJson.Obj
                    ?: return fail("$path：想要对象，得到 ${render(actual)}")
                for ((key, value) in expected.fields) {
                    subset("$path.$key", value, got.fields[key])
                }
            }

            is IMJson.Arr -> {
                val got = actual as? IMJson.Arr
                    ?: return fail("$path：想要数组，得到 ${render(actual)}")
                if (expected.items.size != got.items.size) {
                    return fail("$path：数组长度 ${got.items.size}，想要 ${expected.items.size}")
                }
                expected.items.forEachIndexed { index, item ->
                    subset("$path[$index]", item, got.items[index])
                }
            }

            else -> if (expected != actual) {
                fail("$path：期望 ${render(expected)}，实得 ${render(actual)}")
            }
        }
    }

    /** 对象的每个键都按 [subset] 比一遍，供「只有 data / args 这一层」的调用点用。 */
    fun subsetFields(path: String, expected: Map<String, IMJson>, actual: Map<String, IMJson>) {
        for ((key, value) in expected) {
            subset("$path.$key", value, actual[key])
        }
    }

    private fun render(value: IMJson): String = when (value) {
        is IMJson.Str -> "\"${value.value}\""
        is IMJson.Num -> value.value.toString()
        is IMJson.Bool -> value.value.toString()
        is IMJson.Arr -> "[${value.items.size} 项]"
        is IMJson.Obj -> "{${value.fields.keys.joinToString(",")}}"
    }
}
