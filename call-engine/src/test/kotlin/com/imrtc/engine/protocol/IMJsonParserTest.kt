package com.imrtc.engine.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 严格解析器的行为，逐条对着协议 §2.4 的硬规则。
 *
 * 分类不是形式主义：[IMJsonError.Kind.STRUCTURE] 最终变成 `bad_envelope`，
 * [IMJsonError.Kind.VALUE] 变成 `bad_params`，而 `envelope.json` 里对这两个错误是分开断言的。
 * 分错了，任务二接信封层时会直接挂在向量上。
 */
class IMJsonParserTest {

    // ── 能收的 ────────────────────────────────────────────────────────────

    @Test
    fun `最小合法帧`() {
        val obj = IMJsonParser.parseObject("""{"type":"sys.ping","req_id":"c-1","ts":1756876800123,"data":{}}""")
        assertEquals("sys.ping", obj.optString("type"))
        assertEquals("c-1", obj.optString("req_id"))
        assertEquals(1756876800123L, obj.optLong("ts"))
        assertEquals(emptyMap<String, IMJson>(), obj.optObj("data")?.fields)
    }

    @Test
    fun `指数写法的整数按值收下`() {
        // 这条是五端漂移过的地方：服务端最初按字面量判（见到 e 就拒），TS 端只能按值判。
        assertEquals(1000L, (IMJsonParser.parse("1e3") as IMJson.Num).value)
        assertEquals(100L, (IMJsonParser.parse("1.0e2") as IMJson.Num).value)
        assertEquals(-2000L, (IMJsonParser.parse("-2E3") as IMJson.Num).value)
        assertEquals(0L, (IMJsonParser.parse("-0") as IMJson.Num).value)
    }

    @Test
    fun `安全整数上界收得下`() {
        assertEquals(9007199254740991L, (IMJsonParser.parse("9007199254740991") as IMJson.Num).value)
        assertEquals(-9007199254740991L, (IMJsonParser.parse("-9007199254740991") as IMJson.Num).value)
    }

    @Test
    fun `字符串转义与嵌套结构`() {
        val obj = IMJsonParser.parseObject(
            """{"a":"x\ty\n\"z\"中","b":[1,2,3],"c":{"d":{"e":true}},"f":[]}"""
        )
        assertEquals("x\ty\n\"z\"中", obj.optString("a"))
        assertEquals(listOf(1L, 2L, 3L), obj.optArr("b")?.map { (it as IMJson.Num).value })
        assertEquals(true, obj.optObj("c")?.optObj("d")?.optBool("e"))
        assertEquals(emptyList<IMJson>(), obj.optArr("f"))
    }

    @Test
    fun `重复键取最后一个`() {
        // 对齐另外四端（Go/JS/Swift 都是 last-wins）。在这里报错会造成「只有 Android 解不出来」。
        assertEquals(2L, IMJsonParser.parseObject("""{"a":1,"a":2}""").optLong("a"))
    }

    // ── 结构不对：STRUCTURE → bad_envelope ────────────────────────────────

    @Test
    fun `协议禁止 null`() {
        assertStructure("""{"type":"sys.ping","req_id":"c-1","ts":1,"data":null}""")
        assertStructure("""{"type":"call.hangup","req_id":"c-1","ts":1,"data":{"call_id":null}}""")
        assertStructure("""[null]""")
    }

    @Test
    fun `残缺与畸形的 JSON`() {
        assertStructure("""{"type":"sys.ping",""")   // 截断
        assertStructure("")                           // 空输入
        assertStructure("   ")                        // 只有空白
        assertStructure("""{"a":1} {"b":2}""")        // 尾部有多余内容
        assertStructure("""{"a":1,}""")               // 尾逗号
        assertStructure("""{a:1}""")                  // 键没加引号
        assertStructure("""{"a":01}""")               // 前导零
        assertStructure("""{"a":1.}""")               // 小数点后没数字
        assertStructure("""{"a":1e}""")               // 指数没数字
        assertStructure("""{"a":"x}""")               // 字符串没闭合
        assertStructure("""{"a":"x\q"}""")            // 无法识别的转义
        assertStructure("\"line\nbreak\"")            // 未转义的控制字符
    }

    @Test
    fun `嵌套过深会被挡住而不是把栈打爆`() {
        // 这是防御性上限（32 层），不是 §2.4 规则 5 的两层限制——那条在字段声明层查。
        assertStructure("[".repeat(64) + "1" + "]".repeat(64))
    }

    // ── 值不对：VALUE → bad_params ────────────────────────────────────────

    @Test
    fun `协议禁止浮点数`() {
        assertValue("""{"volume":73.5}""")
        assertValue("""{"n":15e-1}""")   // 1.5，写成指数形式也一样拒
        assertValue("""0.1""")
    }

    @Test
    fun `整数越界`() {
        assertValue("9007199254740992")            // 2^53
        assertValue("-9007199254740992")
        assertValue("99999999999999999999999")     // 连 Long 都装不下
        assertValue("1e30")                        // 是整数，但超安全区
    }

    @Test
    fun `字符串禁止内嵌 NUL`() {
        // JSON 文本里写的是 6 个字符的转义序列，解析出来才是 NUL 码点
        assertValue("\"a\\u0000b\"")
    }

    // ── 断言小工具 ────────────────────────────────────────────────────────

    private fun assertStructure(text: String) = assertKind(text, IMJsonError.Kind.STRUCTURE)

    private fun assertValue(text: String) = assertKind(text, IMJsonError.Kind.VALUE)

    private fun assertKind(text: String, expected: IMJsonError.Kind) {
        try {
            IMJsonParser.parse(text)
            fail("本该拒绝却收下了：$text")
        } catch (e: IMJsonError) {
            assertEquals("分类错了：$text（${e.reason}）", expected, e.kind)
            assertTrue("错误信息里没写原因：$text", e.reason.isNotBlank())
        }
    }
}
