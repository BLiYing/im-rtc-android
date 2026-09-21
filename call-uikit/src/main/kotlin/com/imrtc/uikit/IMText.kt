package com.imrtc.uikit

import java.util.Locale

/** Kit 支持的界面语言。与 Web / iOS 的 `locale` 同名同义；源语言是简体中文。 */
enum class IMLocale(val tag: String) {
    ZH_CN("zh-CN"),
    EN("en");

    companion object {
        /**
         * 跟随系统：按语言主码归类（`en-US`、`en-GB` 都归 EN；`zh-*` 归 ZH_CN），不认识的回落中文。
         * SDK **默认不跟系统**（默认 [ZH_CN]），想跟随的宿主自己调这个——
         * 默认变了会让宿主原本全中文的界面突然出英文。
         */
        @JvmStatic
        @JvmOverloads
        fun system(languages: List<String> = listOf(Locale.getDefault().language)): IMLocale {
            for (lang in languages) {
                val primary = lang.lowercase().substringBefore('-').substringBefore('_')
                if (primary == "zh") return ZH_CN
                entries.firstOrNull { it.tag.substringBefore('-') == primary }?.let { return it }
            }
            return ZH_CN
        }
    }
}

/**
 * Kit 的文案入口。**是对象级单例、不带 Context**：状态类、格式化函数这些纯逻辑
 * （JVM 单测直接跑）也要出人话，没法都拿 `getString`。文案表由 `scripts/gen-i18n.py`
 * 从跨端的 `strings.json` 生成，禁止手抄。
 *
 * 查找顺序：宿主覆盖 → 当前语言 → 中文 → key 本身（漏了一眼看得出）。
 * 已经画在屏幕上的提示不会回译，下一条才用新语言。
 */
object IMText {

    @Volatile
    var locale: IMLocale = IMLocale.ZH_CN

    /** 宿主按语言覆盖个别文案（只写要改的 key）。 */
    @Volatile
    var overrides: Map<IMLocale, Map<String, String>> = emptyMap()

    /** Kit 启动时把配置里的语言与覆盖同步过来（之后改配置由 setter 同步）。 */
    internal fun sync(config: IMCallKitConfig) {
        locale = config.locale
        overrides = config.messages
    }

    private fun table(locale: IMLocale): Map<String, String> =
        if (locale == IMLocale.EN) IMMessages.en else IMMessages.zhCn

    /** 取文案，`{name}` 占位符用 [params] 替换。 */
    @JvmStatic
    fun t(key: String, vararg params: Pair<String, Any>): String {
        val raw = overrides[locale]?.get(key) ?: table(locale)[key] ?: IMMessages.zhCn[key] ?: key
        if (params.isEmpty()) return raw
        var out = raw
        for ((name, value) in params) out = out.replace("{$name}", value.toString())
        return out
    }
}
