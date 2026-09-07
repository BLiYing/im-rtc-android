package com.imrtc.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * device_id 的字符集守卫。
 *
 * 协议 §2.5：`≤64，charset [A-Za-z0-9_-]`。而 `Build.MODEL` 里带空格是常态——
 * "Pixel 2 XL"、"Redmi Note 8 Pro" 都是。不清洗的症状是**握手一律 1004
 * bad_params、无限退避重连**，界面上只写着「登录失败」。
 *
 * 这条用例挡的就是那个：真机上跑一次要装 APK、连线、点登录，
 * 而在这里跑一遍只要几毫秒。
 */
class DeviceIdTest {

    /** 与 DemoSession.sanitizeDeviceId 同一套规则；那边是 private，这里复刻一份守着契约。 */
    private fun sanitize(raw: String): String =
        raw.map { ch ->
            if (ch.isLetterOrDigit() && ch.code < 128 || ch == '_' || ch == '-') ch else '-'
        }.joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifEmpty { "unknown" }
            .take(56)

    private val legal = Regex("^[A-Za-z0-9_-]+$")

    @Test
    fun `带空格的机型名清洗后合法`() {
        // 这台真机上实际炸过的那个值。
        assertEquals("Pixel-2-XL", sanitize("Pixel 2 XL"))
        assertEquals("Redmi-Note-8-Pro", sanitize("Redmi Note 8 Pro"))
    }

    @Test
    fun `各家真实机型名都清洗成合法字符集`() {
        val models = listOf(
            "Pixel 2 XL", "PKD130", "SM-G991B", "Redmi Note 8 Pro",
            "MI 8 Lite", "ONEPLUS A6000", "HUAWEI P30 Pro", "vivo X60",
            "Nexus 5X", "moto g(7) power", "ASUS_I001DA",
        )
        for (model in models) {
            val id = "android-" + sanitize(model)
            assertTrue("$model 清洗后仍不合法：$id", legal.matches(id))
            assertTrue("$model 清洗后超长：${id.length}", id.length <= 64)
        }
    }

    @Test
    fun `中文与括号也挡得住`() {
        // 有些国产 ROM 的 MODEL 里真会出现中文。
        assertTrue(legal.matches("android-" + sanitize("小米手机 10")))
        assertTrue(legal.matches("android-" + sanitize("moto g(7) power")))
    }

    // 连续的非法字符不该变成一串 `-`——那既难读，也白占那 64 字节的额度。
    @Test
    fun `连续与首尾的非法字符被压掉`() {
        assertEquals("a-b", sanitize("a   b"))
        assertEquals("a-b", sanitize("  a // b  "))
        assertEquals("ab", sanitize("---ab---"))
    }

    @Test
    fun `全是非法字符时退回 unknown 而不是空`() {
        // 空的 device_id 在服务端同样是 1004，而且更难查——日志里那一格是空白。
        assertEquals("unknown", sanitize("###"))
        assertEquals("unknown", sanitize(""))
        assertEquals("unknown", sanitize("   "))
    }

    @Test
    fun `超长机型名被截断`() {
        val id = "android-" + sanitize("X".repeat(200))
        assertTrue("截断后仍超 64：${id.length}", id.length <= 64)
        assertTrue(legal.matches(id))
    }
}
