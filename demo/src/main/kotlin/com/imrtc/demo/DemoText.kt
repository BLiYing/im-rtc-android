package com.imrtc.demo

import com.imrtc.uikit.IMLocale
import com.imrtc.uikit.IMText

/**
 * 取 Demo 页面自己的文案（登录、拨号、记录、设置那些）。
 * 语言跟 Kit 走同一个开关（[IMText.locale]），缺译回落中文；表与 SDK 的分开，Demo 文案不打进 SDK 包。
 */
internal fun dt(key: String, vararg params: Pair<String, Any>): String {
    val table = if (IMText.locale == IMLocale.EN) DemoMessages.en else DemoMessages.zhCn
    var out = table[key] ?: DemoMessages.zhCn[key] ?: key
    for ((name, value) in params) out = out.replace("{$name}", value.toString())
    return out
}
