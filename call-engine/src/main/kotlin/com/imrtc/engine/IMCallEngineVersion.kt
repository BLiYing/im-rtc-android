package com.imrtc.engine

/**
 * SDK 版本号，**五端统一**（2026-09-11 起统一版本，现为 2.0.0；iOS 的等价物是 `IMCallEngineVersion`）。
 *
 * 握手的 `sdk` 字段与 Demo「关于」都读这里，发版只改这一处。
 * `const val` 在 Java 侧就是静态常量：`IMCallEngineVersion.VERSION`。
 */
object IMCallEngineVersion {
    const val VERSION = "2.0.0"

    /**
     * 握手 `sdk` 字段的默认值（协议 §1.3：string ≤64，**只进日志与灰度，禁止参与逻辑**）。
     * 格式对齐其他端：`ios/2.0.0`、`web/2.0.0`、`desktop/2.0.0`。
     */
    const val SDK = "android/$VERSION"
}
