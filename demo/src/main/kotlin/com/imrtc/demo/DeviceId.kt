package com.imrtc.demo

/**
 * 把 `Build.MODEL` 清洗成合法的 `device_id`（协议 §2.5：非空、≤64 字节、`[A-Za-z0-9_-]`）。
 *
 * **这是宿主该做的事，不是 SDK 的事**——`IMCallEngine.Config` 只校验不改写，因为
 * `device_id` 要求跨重启稳定，SDK 悄悄替宿主改掉，宿主自己那套设备管理就对不上账了。
 * 这份是给宿主抄的示范。
 *
 * 放在顶层而不是 `DemoSession` 里，是为了**能被单测直接调到**：`DemoSession` 是个
 * `object`，初始化时就要 `Handler(Looper.getMainLooper())`，纯 JVM 单测一碰就炸，
 * 于是测试只能复刻一份逻辑自测自——那种用例改坏了生产代码也照样绿。
 */

/** `Build.MODEL` 里带空格是常态："Pixel 2 XL"、"Redmi Note 8 Pro" 都是。 */
internal fun sanitizeDeviceId(rawModel: String): String {
    val substituted = rawModel.map { ch ->
        if (ch.isLetterOrDigit() && ch.code < 128 || ch == '_' || ch == '-') ch else '-'
    }.joinToString("")
    val cleaned = substituted.replace(DASHES, "-").trim('-')

    // **「换成 `-`」是一对一的，不会撞号；「压掉」和「裁掉」才会。**
    //
    // 只替换不补指纹的症状：「红米 10」与「小米手机 10」中文全变成 `-`、连成一串再压掉，
    // 两台机器清洗完都是 `android-10`；纯中文机型名（「荣耀」「华为畅享」）更是压成空。
    // 而撞号的后果是两台设备**互相顶号、轮流把对方踢下线**——正是
    // `IMCallEngine.Config.checkDeviceId` 的注释里警告的那件事，只是那儿说的是
    // 「删空格」，这儿是「压掉非法字符」，殊途同归。
    val lossy = cleaned.isEmpty() || cleaned != substituted || cleaned.length > MAX_MODEL_CHARS
    if (!lossy) return cleaned

    // 指纹取 `String.hashCode`：它的算法**写在 JDK 规范里**（`s[0]*31^(n-1)+…`），
    // 跨重启、跨设备、跨版本都算得出同一个值，正好满足 device_id「跨重启稳定」的要求。
    // 常见机型名（PKD130 / Pixel 2 XL / SM-G991B / Redmi Note 8 Pro）都走不到这儿，
    // 它们清洗后一个字符都没少，仍然是人眼读得懂的那串。
    val fingerprint = Integer.toHexString(rawModel.hashCode()).padStart(FINGERPRINT_CHARS, '0')
    val head = cleaned.take(MAX_MODEL_CHARS - FINGERPRINT_CHARS - 1).trim('-')
    return if (head.isEmpty()) fingerprint else "$head-$fingerprint"
}

/** `"android-"` 前缀占 8 字节，留给机型名 56 字节。清洗后全是 ASCII，字符数即字节数。 */
private const val MAX_MODEL_CHARS = 56

private const val FINGERPRINT_CHARS = 8

/** 编译一次就够——`deviceId` 每读一次就重新 `Regex("-+")` 是白花的钱。 */
private val DASHES = Regex("-+")
