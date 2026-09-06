package com.imrtc.uikit

/**
 * 头像取色（规范 §02）：没有头像图时用**首字母 + 渐变底**，取哪一个渐变由 `fnv1a32(uid) % 9` 决定。
 * **五端共用这一个哈希**——同一个 uid 在你手机上是紫的、在对方电脑上是绿的，那就是 bug。
 * 向量与 Web 的 `avatar.test.ts` / iOS 的 `AvatarTests` 同一组数。
 *
 * 纯 JVM，不碰 android.*，所以能直接单测。
 */
internal object IMAvatar {

    const val PALETTE_SIZE = 9

    /** 32 位 FNV-1a，按 UTF-8 字节算（不是 UTF-16 码元）。 */
    fun fnv1a32(text: String): Long {
        var hash = 0x811c9dc5L
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return hash
    }

    /** uid → 九色板下标。 */
    fun index(uid: String): Int = (fnv1a32(uid) % PALETTE_SIZE).toInt()

    /** 首字母（大写）；空的给问号。 */
    fun initial(label: String): String {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return "?"
        val first = trimmed.codePointAt(0)
        return String(Character.toChars(first)).uppercase()
    }
}
