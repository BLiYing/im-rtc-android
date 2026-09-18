package com.imrtc.engine

import com.imrtc.engine.protocol.IMEnvelope
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

    private fun helloField(config: IMCallEngine.Config, field: String): IMJson? {
        val engine = IMCallEngine.forTest(config, EngineLoopTest.RecordingListener(), null, FakeScheduler(), transport)
        engine.login("tk-1")
        transport.open()
        return transport.sent.first { it.type == IMFrameType.HELLO }.data[field]
    }

    private fun helloSdk(config: IMCallEngine.Config): IMJson? = helloField(config, "sdk")

    /**
     * **校真正发出去的那一帧**，不是字段声明的默认值。
     *
     * 这两处原先各写各的：帧声明 `SysFrames.HELLO` 升到了 2，而发送侧
     * `IMSignalConnection.Config.protocolVersion` 还留着 1，于是握手一直报 1，
     * 真机一连就被 1006 拒掉——**而全套单测是绿的**，因为向量校的正是帧声明那一侧。
     */
    @Test
    fun `握手报的协议版本是真的发出去的那个`() {
        val version = helloField(IMCallEngine.Config(url = "ws://test/rtc", deviceId = "d-1"), "protocol_version")
        assertEquals(IMJson.Num(IMEnvelope.PROTOCOL_VERSION), version)
        assertEquals(2L, IMEnvelope.PROTOCOL_VERSION)
    }

    @Test
    fun `版本常量是 端斜杠三段式 且不超协议上限`() {
        assertEquals("1.0.0", IMCallEngineVersion.VERSION)
        assertEquals("android/1.0.0", IMCallEngineVersion.SDK)
        assertTrue(IMCallEngineVersion.SDK.matches(Regex("""android/\d+\.\d+\.\d+""")))
        assertTrue(IMCallEngineVersion.SDK.toByteArray(Charsets.UTF_8).size <= 64)
    }

    /**
     * 握手里报的版本与 Maven 坐标里的版本必须是同一个数（根 `build.gradle.kts` 的发布配置）。
     * 系统属性由 `call-engine/build.gradle.kts` 从 `gradle.properties` 的 `IMRTC_VERSION` 注入；
     * 只改了其中一处就在这里红，而不是等宿主报「日志里的版本对不上」。
     */
    @Test
    fun `版本常量与发布版本号一致`() {
        val published = System.getProperty("imrtc.version").orEmpty()
        assertTrue("没注入 imrtc.version：请经 Gradle 跑测试", published.isNotEmpty())
        assertEquals(published, IMCallEngineVersion.VERSION)
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
