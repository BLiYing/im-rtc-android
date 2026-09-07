package com.imrtc.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * device_id 的字符集守卫（协议 §2.5：`≤64，charset [A-Za-z0-9_-]`）。
 *
 * `Build.MODEL` 里带空格是常态——"Pixel 2 XL"、"Redmi Note 8 Pro" 都是。不清洗的症状是
 * **握手一律 1004 bad_params、无限退避重连**，界面上只写着「登录失败」。
 * 真机上跑一次要装 APK、连线、点登录，在这里跑一遍只要几毫秒。
 *
 * **这些用例调的是生产代码本身**（`DeviceId.kt` 的顶层函数），不是复刻一份。
 * 复刻的那种改坏了生产代码也照样绿，等于没测。
 */
class DeviceIdTest {

    private val legal = Regex("^[A-Za-z0-9_-]+$")

    private fun id(model: String) = "android-" + sanitizeDeviceId(model)

    @Test
    fun `带空格的机型名清洗后合法且仍然读得懂`() {
        // 这台真机上实际炸过的那个值。清洗没丢字符，所以不补指纹。
        assertEquals("Pixel-2-XL", sanitizeDeviceId("Pixel 2 XL"))
        assertEquals("Redmi-Note-8-Pro", sanitizeDeviceId("Redmi Note 8 Pro"))
    }

    @Test
    fun `各家真实机型名都清洗成合法字符集`() {
        val models = listOf(
            "Pixel 2 XL", "PKD130", "SM-G991B", "Redmi Note 8 Pro",
            "MI 8 Lite", "ONEPLUS A6000", "HUAWEI P30 Pro", "vivo X60",
            "Nexus 5X", "moto g(7) power", "ASUS_I001DA",
        )
        for (model in models) {
            val id = id(model)
            assertTrue("$model 清洗后仍不合法：$id", legal.matches(id))
            assertTrue("$model 清洗后超长：${id.length}", id.length <= 64)
        }
    }

    @Test
    fun `中文与括号也挡得住`() {
        // 有些国产 ROM 的 MODEL 里真会出现中文。
        assertTrue(legal.matches(id("小米手机 10")))
        assertTrue(legal.matches(id("moto g(7) power")))
    }

    /**
     * **最要紧的一条：不同机型不能清洗成同一个 device_id。**
     *
     * 光「非法字符换成 `-` 再压掉」的话，中文全被压没，「红米 10」与「小米手机 10」
     * 都剩下 `10`，纯中文机型名更是压成空。而撞号的后果是两台设备**互相顶号、
     * 轮流把对方踢下线**——服务端按 (uid, device_id) 顶号，两台机器会一直打架。
     *
     * 只断言「清洗后合法」是抓不到这个的：`android-10` 与 `android-unknown` 都合法。
     */
    @Test
    fun `压掉字符的机型名靠指纹保持互不相同`() {
        val collidingBefore = listOf(
            // 中文全变成 `-`、连成一串再压掉，这三个原先都只剩下 `10`。
            "红米 10", "小米手机 10", "荣耀 10",
            // 纯中文机型名原先一律压成空，全都退回同一个 `unknown`。
            "华为畅享", "荣耀", "小米",
            // 连续非法字符被压成一个，原先与「只有一个」的那种撞在一起。
            "MI--8", "MI 8",
        )
        val ids = collidingBefore.map { id(it) }
        for ((model, value) in collidingBefore.zip(ids)) {
            assertTrue("$model 清洗后不合法：$value", legal.matches(value))
            assertTrue("$model 清洗后超长：${value.length}", value.length <= 64)
        }
        assertEquals("这些机型名清洗后撞号了：$ids", ids.size, ids.toSet().size)
    }

    /**
     * **已知且有意的残留：只差「用哪个非法字符当分隔」的两个名字仍然撞号。**
     *
     * `"MI 8"` 与 `"MI-8"` 都得到 `android-MI-8`——一对一的替换不补指纹，
     * 换来的是常见机型名（Pixel 2 XL、Redmi Note 8 Pro）在服务端日志里仍然人眼可读。
     * 收窄这条要给**所有**带空格的机型名都缀上指纹，代价比收益大：真实世界里没有哪家
     * 会同时出「MI 8」和「MI-8」两款机器，而**同一个用户手上同时有这两台**才会出事。
     *
     * 这条用例钉的是这个取舍本身——哪天决定不要这个残留了，它会红，那正是该看见的地方。
     */
    @Test
    fun `只差分隔符的两个名字仍然撞号（已知取舍）`() {
        assertEquals(id("MI 8"), id("MI-8"))
        assertEquals("android-Pixel-2-XL", id("Pixel 2 XL"))
    }

    @Test
    fun `全是非法字符时不会都变成同一个值`() {
        // 空的 device_id 在服务端同样是 1004，而且更难查——日志里那一格是空白。
        assertTrue(legal.matches(id("###")))
        assertTrue(legal.matches(id("")))
        assertTrue(legal.matches(id("   ")))
        assertNotEquals(id("###"), id("   "))
    }

    /** 同一个机型名每次都要算出同一个值——device_id 必须跨重启稳定。 */
    @Test
    fun `同一个机型名结果稳定`() {
        repeat(3) { assertEquals(sanitizeDeviceId("红米 10"), sanitizeDeviceId("红米 10")) }
    }

    @Test
    fun `超长机型名被截断且仍互不相同`() {
        val a = id("X".repeat(200))
        val b = id("X".repeat(199) + "Y")
        assertTrue("截断后仍超 64：${a.length}", a.length <= 64)
        assertTrue(legal.matches(a))
        // 截断本身也会丢信息，所以这两个也要靠指纹分开。
        assertNotEquals(a, b)
    }

    /** 清洗的产物必须过得了 SDK 那道校验——两边同一套规则，别各写各的。 */
    @Test
    fun `清洗结果不会以 - 结尾`() {
        assertTrue(!sanitizeDeviceId("a".repeat(60) + " b").endsWith("-"))
        assertTrue(!sanitizeDeviceId("---ab---").endsWith("-"))
    }
}
