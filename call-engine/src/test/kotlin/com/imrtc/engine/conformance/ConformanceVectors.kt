package com.imrtc.engine.conformance

import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMJsonParser
import com.imrtc.engine.protocol.optLong
import com.imrtc.engine.protocol.optString
import java.io.File

/**
 * 一致性向量的加载器（测试专用）。
 *
 * 向量是**五仓共用的同一份文件**，放在 `im-rtc-server/docs/conformance/`。
 * **禁止手抄一份到本仓**（会漂）——所以这里是去邻居仓里读，找不到就让测试失败，
 * 而不是悄悄跳过。「跳过」等于闸门不存在。
 *
 * 顺带一提：加载器用的就是本仓自己的 [IMJsonParser]，**向量文件本身就是第一批测试输入**——
 * 五份文件里没有 null、没有浮点、没有越界整数（已实测），严格解析器能原样吃下去。
 */
internal object ConformanceVectors {

    /** 五份向量的文件名（不含扩展名），与 `kind` 字段一一对应。 */
    val NAMES = listOf("call_fsm", "envelope", "error_codes", "reasons", "room_fsm")

    val dir: File by lazy { locate() }

    fun load(name: String): IMJson.Obj {
        val file = File(dir, "$name.json")
        check(file.isFile) { "向量文件不存在：${file.absolutePath}" }
        return IMJsonParser.parseObject(file.readText(Charsets.UTF_8))
    }

    /** 每份向量都必须有的三个头字段，顺手做了校验。 */
    fun loadChecked(name: String): IMJson.Obj {
        val root = load(name)
        val version = root.optLong("version")
        val kind = root.optString("kind")
        check(version == 1L) { "$name.json 的 version 应为 1，实得 $version" }
        check(kind == name) { "$name.json 的 kind 应为 \"$name\"，实得 \"$kind\"" }
        check(!root.optString("description").isNullOrBlank()) { "$name.json 缺 description" }
        return root
    }

    /**
     * 找向量目录，三条路依次试：
     * 1. 系统属性 `rtc.conformance.dir`（由 `build.gradle.kts` 从环境变量 `RTC_CONFORMANCE_DIR` 透传）；
     * 2. 从工作目录逐级往上找 `im-rtc-server/docs/conformance`（五仓平铺在同一层时这条就够）；
     * 3. 都没有就抛，并把找过的地方打出来——**不许静默跳过**。
     */
    private fun locate(): File {
        val fromProperty = System.getProperty("rtc.conformance.dir").orEmpty()
        if (fromProperty.isNotBlank()) {
            val dir = File(fromProperty)
            check(dir.isDirectory) { "RTC_CONFORMANCE_DIR 指向的不是目录：$fromProperty" }
            return dir
        }
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var cursor: File? = start
        val tried = ArrayList<String>()
        while (cursor != null) {
            val candidate = File(cursor, "im-rtc-server/docs/conformance")
            tried.add(candidate.path)
            if (candidate.isDirectory) return candidate
            cursor = cursor.parentFile
        }
        error(
            "找不到一致性向量目录。工作目录=$start\n" +
                "找过：\n  " + tried.joinToString("\n  ") + "\n" +
                "要么把 im-rtc-server 克隆到与本仓同级，要么设环境变量 RTC_CONFORMANCE_DIR。"
        )
    }
}
