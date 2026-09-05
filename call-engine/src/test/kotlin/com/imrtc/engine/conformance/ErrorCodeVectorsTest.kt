package com.imrtc.engine.conformance

import com.imrtc.engine.protocol.IMCloseCode
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.optObjArr
import com.imrtc.engine.protocol.optString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地错误码表 ↔ `error_codes.json` **逐条相等**。
 *
 * 这是一道防漂闸门，不是「测一下枚举写没写对」。错误码表是五仓共用的契约：
 * code、name、**连 msg 那句英文短语都必须一模一样**。加一个码等于改五个仓 + 改向量。
 *
 * 服务端有一份等价的 `errcode_test.go`，Web 与 iOS 各有一份——五份一起挂，才说明是向量变了。
 */
class ErrorCodeVectorsTest {

    private val root = ConformanceVectors.loadChecked("error_codes")

    @Test
    fun `错误码逐条相等`() {
        val wire = root.optObjArr("wire") ?: error("wire 不是对象数组")
        val local = root.optObjArr("local") ?: error("local 不是对象数组")

        for (entry in wire + local) {
            val code = (entry.fields["code"] as? IMJson.Num)?.value?.toInt() ?: error("条目缺 code")
            val name = entry.optString("name") ?: error("$code 缺 name")
            val msg = entry.optString("msg") ?: error("$code 缺 msg")
            val group = entry.optString("group") ?: error("$code 缺 group")
            val retryable = (entry.fields["retryable"] as? IMJson.Bool)?.value ?: error("$code 缺 retryable")

            val actual = IMErrorCode.fromCode(code)
            assertNotNull("本地表里没有错误码 $code（$name）", actual)
            assertEquals("$code 的 name 不一致", name, actual!!.wireName)
            // msg 是契约的一部分，不是提示语——差一个字都算破坏契约。
            assertEquals("$code 的 msg 不一致", msg, actual.msg)
            assertEquals("$code 的 group 不一致", group, actual.group)
            assertEquals("$code 的 retryable 不一致", retryable, actual.retryable)
        }

        // 反向也要查：本地多出向量里没有的码，同样是漂移。
        assertEquals("本地错误码数量与向量不一致", wire.size + local.size, IMErrorCode.entries.size)
    }

    @Test
    fun `wire 与 local 的分组不能混`() {
        val local = root.optObjArr("local") ?: error("local 不是对象数组")
        val localCodes = local.map { (it.fields["code"] as? IMJson.Num)?.value?.toInt() }.toSet()

        for (entry in IMErrorCode.entries) {
            val shouldBeWire = entry.code !in localCodes
            assertEquals(
                "${entry.code} ${entry.wireName} 的 isWire 判断错了" +
                    "——local 组的码**永不上线路**，只经 onError 抛给宿主",
                shouldBeWire,
                entry.isWire,
            )
        }
    }

    @Test
    fun `关闭码逐条相等`() {
        val closeCodes = root.optObjArr("close_codes") ?: error("close_codes 不是对象数组")
        for (entry in closeCodes) {
            val code = (entry.fields["code"] as? IMJson.Num)?.value?.toInt() ?: error("条目缺 code")
            val reconnect = (entry.fields["reconnect"] as? IMJson.Bool)?.value ?: error("$code 缺 reconnect")
            val actual = IMCloseCode.fromCode(code)
            assertNotNull("本地表里没有关闭码 $code", actual)
            assertEquals("关闭码 $code 的重连策略不一致", reconnect, actual!!.shouldReconnect)
        }
        assertEquals("本地关闭码数量与向量不一致", closeCodes.size, IMCloseCode.entries.size)

        // 4401 = 未鉴权，**要重连但得先换票**（§1.5）；这条与「4401 三次上限」是配套的。
        assertTrue("4401 应当允许重连", IMCloseCode.UNAUTHORIZED.shouldReconnect)
        // 4403 = 被踢，**不许自动重连**，否则两台设备会互相把对方踢下线，死循环。
        assertTrue("4403 不该重连", !IMCloseCode.KICKED.shouldReconnect)
    }
}
