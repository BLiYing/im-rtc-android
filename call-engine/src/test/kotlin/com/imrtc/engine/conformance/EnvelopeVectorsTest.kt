package com.imrtc.engine.conformance

import com.imrtc.engine.protocol.FieldCodec
import com.imrtc.engine.protocol.IMEnvelope
import com.imrtc.engine.protocol.IMFrameRegistry
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMJsonParser
import com.imrtc.engine.protocol.IMRtcException
import com.imrtc.engine.protocol.optObj
import com.imrtc.engine.protocol.optObjArr
import com.imrtc.engine.protocol.optString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `envelope.json` 逐条跑，26 条解析用例 + 9 条默认值用例。**与另外四端同一份文件。**
 *
 * 每条用例都断言到「错误码」这一层，而不是只断言「失败了」——
 * `bad_envelope` 与 `bad_params` 在向量里是分开写的，混为一谈就等于没测。
 */
class EnvelopeVectorsTest {

    private val root = ConformanceVectors.loadChecked("envelope")

    @Test
    fun `解析用例逐条通过`() {
        val cases = root.optObjArr("cases") ?: error("cases 不是对象数组")
        var checked = 0
        for (case in cases) {
            val name = case.optString("name") ?: error("case 缺 name")
            val input = case.optString("input") ?: error("$name 缺 input")
            val expect = case.optObj("expect") ?: error("$name 缺 expect")
            runCase(name, input, expect)
            checked++
        }
        assertTrue("一条用例都没跑，向量八成没读到", checked > 0)
    }

    private fun runCase(name: String, input: String, expect: IMJson.Obj) {
        val expectOk = (expect.fields["ok"] as? IMJson.Bool)?.value ?: error("$name 的 expect 缺 ok")
        val expectedError = expect.optString("error")
        // client_action=ignore 表示「客户端收到未知帧要静默忽略」——
        // 对解码器来说仍然是抛 unknown_type，处置在上层。server_action 那条是服务端的活，
        // 我们只确认自己也把它认成 unknown_type。
        val expectsUnknownType =
            expectedError == "unknown_type" || expect.optString("client_action") == "ignore"

        val outcome = decode(input)

        if (!expectOk) {
            val error = outcome.error
            assertNotNull("$name：本该失败却成功了", error)
            assertEquals("$name：错误码不对", expectedError, error!!.errorCode.wireName)
            return
        }

        // ok:true 的用例，信封层必须解得动
        assertTrue("$name：信封解析不该失败（${outcome.error?.message}）", outcome.envelope != null)
        val envelope = outcome.envelope!!

        expect.optString("type")?.let { assertEquals("$name：type 不对", it, envelope.type) }
        expect.optString("req_id")?.let { assertEquals("$name：req_id 不对", it, envelope.reqId) }
        (expect.fields["ts"] as? IMJson.Num)?.let {
            assertEquals("$name：ts 不对", it.value, envelope.timestampMs)
        }

        if (expectsUnknownType) {
            assertNotNull("$name：未知帧类型本该被认出来", outcome.error)
            assertEquals("$name：未知帧的错误码不对", "unknown_type", outcome.error!!.errorCode.wireName)
            return
        }
        assertTrue("$name：字段解码不该失败（${outcome.error?.message}）", outcome.error == null)

        // expect.data 是**子集断言**：只比对列出来的键。
        val expectedData = expect.optObj("data") ?: return
        val actual = outcome.data ?: error("$name：没有解码后的 data")
        VectorMatch.subsetFields("$name data", expectedData.fields, actual)
    }

    @Test
    fun `默认值填充用例逐条通过`() {
        val cases = root.optObjArr("default_cases") ?: error("default_cases 不是对象数组")
        for (case in cases) {
            val name = case.optString("name") ?: error("default_case 缺 name")
            val type = case.optString("type") ?: error("$name 缺 type")
            // 注意形状不对称：input_data 是原始 JSON 文本，expect_data 才是对象。
            val inputText = case.optString("input_data") ?: error("$name 缺 input_data")
            val expected = case.optObj("expect_data") ?: error("$name 缺 expect_data")

            val fields = IMFrameRegistry.fields(type) ?: error("$name：注册表里没有 $type")
            val input = IMJsonParser.parseObject(inputText)
            val filled = FieldCodec.decode(fields, input.fields)

            // expect_data 与 cases 里的 expect.data 一样是**子集断言**：向量只列它关心的字段
            // （例如 ice_candidate 那条就故意不重复那一长串 candidate）。服务端的 Go runner
            // 用的也是 assertDataSubset——比对方式必须五端一致，否则同一份向量各测各的。
            VectorMatch.subsetFields("$name expect_data", expected.fields, filled)
            // 子集比对之外再加一条本端自己的要求：**声明过的字段一个都不能少**。
            // 「省略即取默认值」如果漏填，后果是发送侧把默认值写成零值。
            assertEquals("$name：填完默认值后字段集合不对", fields.keys, filled.keys)
        }
    }

    /**
     * 发送侧的默认值陷阱：**直接写字面量 data 会漏字段或覆盖掉非零默认值**。
     * `room.join` 是最典型的一个——漏了 `auto_subscribe`，人进了房收不到任何流。
     */
    @Test
    fun `请求帧从填好默认值的实例起手`() {
        val join = IMEnvelope.request(com.imrtc.engine.protocol.IMFrameType.ROOM_JOIN, "c-1", 1) {
            it["room_id"] = IMJson.Str("r-1")
            it["room_token"] = IMJson.Str("tk")
        }
        assertEquals(IMJson.Bool(true), join.data["auto_subscribe"])
        assertEquals(IMJson.Bool(true), join.data["publish_audio"])
        assertEquals(IMJson.Bool(false), join.data["publish_video"])

        // 编码后再解回来，结果必须一致（round-trip）。
        val decoded = IMEnvelope.decode(join.encode())
        assertEquals(join.type, decoded.type)
        assertEquals(join.data, decoded.decodedData())
    }

    private class Outcome(
        val envelope: IMEnvelope?,
        val data: Map<String, IMJson>?,
        val error: IMRtcException?,
    )

    /** 一次走完「信封 → 编码硬规则 → 字段声明」，把第一个错误带回来。 */
    private fun decode(input: String): Outcome {
        val envelope = try {
            IMEnvelope.decode(input)
        } catch (e: IMRtcException) {
            return Outcome(null, null, e)
        }
        return try {
            Outcome(envelope, envelope.decodedData(), null)
        } catch (e: IMRtcException) {
            Outcome(envelope, null, e)
        }
    }
}
