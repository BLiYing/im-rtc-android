package com.imrtc.engine

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `device_id` 的校验（协议 §2.5：非空、≤64 字节、charset `[A-Za-z0-9_-]`）。
 *
 * **为什么要在 SDK 层拦**：不拦的话服务端回 1004、客户端无限退避重连、
 * 界面上只写着「登录失败」，而服务端那句说得很清楚的
 * 「device_id 只允许 [A-Za-z0-9_-]，出现了 ' '」到不了端上。
 * 真机上踩过一次（Pixel 2 XL 的 Build.MODEL 就带空格）。
 */
class DeviceIdCheckTest {

    private fun check(id: String) = IMCallEngine.Config.checkDeviceId(id)

    @Test
    fun `合法的 device_id 通过`() {
        check("android-Pixel-2-XL")
        check("web_8f3a")
        check("a")
        check("A".repeat(64)) // 正好 64 字节
    }

    // 最常见的错法：直接拿 Build.MODEL 拼。
    @Test
    fun `带空格的机型名被拒且错误信息点出原因`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            check("android-Pixel 2 XL")
        }
        val msg = e.message.orEmpty()
        assertTrue("没说清哪个字符不对：$msg", msg.contains("' '"))
        assertTrue("没提示怎么办：$msg", msg.contains("Build.MODEL"))
    }

    @Test
    fun `各类非法字符都被拒`() {
        for (bad in listOf(
            "a b", "a/b", "a.b", "a:b", "a(b)", "小米手机", "a\nb", "a\tb", "emoji-😀",
        )) {
            assertThrows("$bad 应当被拒", IllegalArgumentException::class.java) { check(bad) }
        }
    }

    @Test
    fun `空与超长被拒`() {
        assertThrows(IllegalArgumentException::class.java) { check("") }
        assertThrows(IllegalArgumentException::class.java) { check("A".repeat(65)) }
    }

    // 长度按**字节**算而不是字符：多字节字符本来就过不了字符集那关，
    // 但这条守住「64 字节」这个口径本身，免得以后放宽字符集时算错。
    @Test
    fun `长度按字节算`() {
        val e = assertThrows(IllegalArgumentException::class.java) { check("A".repeat(100)) }
        assertTrue(e.message.orEmpty().contains("100 字节"))
    }

    // 构造 Config 时就该拦下来——等到 login 再报，问题已经跑远了。
    @Test
    fun `构造 Config 时立刻拒绝非法 device_id`() {
        assertThrows(IllegalArgumentException::class.java) {
            IMCallEngine.Config(url = "ws://127.0.0.1:8787/v1/ws", deviceId = "Pixel 2 XL")
        }
        // 合法的照常构造。
        IMCallEngine.Config(url = "ws://127.0.0.1:8787/v1/ws", deviceId = "Pixel-2-XL")
    }
}
