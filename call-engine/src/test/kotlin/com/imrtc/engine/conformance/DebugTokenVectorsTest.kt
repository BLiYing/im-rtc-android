package com.imrtc.engine.conformance

import com.imrtc.engine.IMDebugTokenGenerator
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMJsonParser
import com.imrtc.engine.protocol.optObj
import com.imrtc.engine.protocol.optObjArr
import com.imrtc.engine.protocol.optLong
import com.imrtc.engine.protocol.optString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** 调试密钥本地签票与 `debug_token.json` 对照（向量在 server 仓，不抄进本仓）。 */
class DebugTokenVectorsTest {

    private val root = ConformanceVectors.loadChecked("debug_token")

    private fun decode(part: String): IMJson.Obj =
        IMJsonParser.parseObject(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8))

    @Test
    fun `hmac 用例签名与向量一致`() {
        val cases = root.optObjArr("hmac_cases") ?: error("缺 hmac_cases")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            assertEquals(c.optString("name"), c.optString("expect_signature"),
                IMDebugTokenGenerator.hmacSha256Base64Url(c.optString("secret")!!, c.optString("signing_input")!!))
        }
    }

    @Test
    fun `sign 用例的 header 与 claims 一致且签名可验`() {
        val cases = root.optObjArr("sign_cases") ?: error("缺 sign_cases")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c.optString("name")
            val input = c.optObj("input")!!
            val secret = c.optString("secret")!!
            val token = IMDebugTokenGenerator.generateDebugToken(
                appId = input.optString("app_id")!!,
                keyId = c.optString("key_id")!!,
                secret = secret,
                uid = input.optString("uid")!!,
                deviceId = input.optString("device_id"),
                ttlSec = input.optLong("ttl_sec") ?: 0,
                nowUnixSec = c.optLong("now_unix")!!,
            )
            val parts = token.split(".")
            assertEquals("$name 三段", 3, parts.size)
            assertTrue("$name 不许有填充", !token.contains("="))
            assertEquals("$name header", c.optObj("expect_header"), decode(parts[0]))
            assertEquals("$name claims", c.optObj("expect_claims"), decode(parts[1]))
            assertEquals("$name 签名", IMDebugTokenGenerator.hmacSha256Base64Url(secret, parts[0] + "." + parts[1]), parts[2])
        }
    }

    @Test
    fun `reject 用例必须拒绝`() {
        val cases = root.optObjArr("reject_cases") ?: error("缺 reject_cases")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val input = c.optObj("input")!!
            try {
                IMDebugTokenGenerator.generateDebugToken(
                    appId = input.optString("app_id")!!,
                    keyId = c.optString("key_id") ?: "dbg-1",
                    secret = c.optString("secret") ?: "0123456789abcdef0123456789abcdef",
                    uid = input.optString("uid")!!,
                    nowUnixSec = 1790000000,
                )
                fail("${c.optString("name")} 应被拒绝")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
