package com.imrtc.engine

import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.FakeScheduler
import com.imrtc.engine.signaling.FakeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 握手 `sdk` 字段（协议 §1.3：string ≤64，只进日志与灰度）。
 *
 * 2026-09-11 前 Android 发的是光秃秃的 `"android"`，服务端日志里分不出版本；
 * 五端统一成 `端/版本号` 之后，这份测试守住「默认值真的进了 hello 帧」。
 */
class SdkVersionTest {

    private val transport = FakeTransport()

    private fun helloSdk(config: IMCallEngine.Config): IMJson? {
        val engine = IMCallEngine.forTest(config, EngineLoopTest.RecordingListener(), null, FakeScheduler(), transport)
        engine.login("tk-1")
        transport.open()
        return transport.sent.first { it.type == IMFrameType.HELLO }.data["sdk"]
    }

    @Test
    fun `版本常量是 端斜杠三段式 且不超协议上限`() {
        assertEquals("1.0.0", IMCallEngineVersion.VERSION)
        assertEquals("android/1.0.0", IMCallEngineVersion.SDK)
        assertTrue(IMCallEngineVersion.SDK.matches(Regex("""android/\d+\.\d+\.\d+""")))
        assertTrue(IMCallEngineVersion.SDK.toByteArray(Charsets.UTF_8).size <= 64)
    }

    @Test
    fun `默认配置的 hello 帧带上版本号`() {
        val sdk = helloSdk(IMCallEngine.Config(url = "ws://test/rtc", deviceId = "d-1"))
        assertEquals(IMJson.Str(IMCallEngineVersion.SDK), sdk)
    }

    @Test
    fun `宿主显式传的 sdk 原样发出`() {
        val sdk = helloSdk(IMCallEngine.Config("ws://test/rtc", "d-1", "android/host-9.9"))
        assertEquals(IMJson.Str("android/host-9.9"), sdk)
    }
}
