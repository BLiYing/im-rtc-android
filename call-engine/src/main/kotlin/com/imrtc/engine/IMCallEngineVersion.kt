package com.imrtc.engine

/**
 * SDK 版本号（2026-09-11 起五端统一起步；**2.1.1 是 Android 独立打的 bugfix patch**——
 * 修 org.webrtc 改名撞包名的崩溃，其余四端仍在 2.1.0，不必跟着挪。iOS 的等价物是 `IMCallEngineVersion`）。
 *
 * 握手的 `sdk` 字段与 Demo「关于」都读这里，发版只改这一处。
 * `const val` 在 Java 侧就是静态常量：`IMCallEngineVersion.VERSION`。
 */
object IMCallEngineVersion {
    const val VERSION = "2.2.0"

    /**
     * 握手 `sdk` 字段的默认值（协议 §1.3：string ≤64，**只进日志与灰度，禁止参与逻辑**）。
     * 格式对齐其他端：`ios/2.1.0`、`web/2.1.0`、`desktop/2.1.0`。
     */
    const val SDK = "android/$VERSION"
}
