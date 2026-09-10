package com.imrtc.engine.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.webrtc.RtpCapabilities

/**
 * codec 排序（[IMUplinkPolicy.h264First]）。
 *
 * 这段最容易写错的两处都没有任何报错、只表现成「画质莫名其妙」：
 * ① 把非 H.264 的也过滤掉 → 回落路径没了，对端不支持这一档就直接没视频；
 * ② 排序不稳定 → 同一台机器两次协商结果不同，之后查起来毫无头绪。
 */
class UplinkPolicyTest {

    private fun codec(name: String, profile: String? = null) = RtpCapabilities.CodecCapability().apply {
        this.name = name
        if (profile != null) parameters = mapOf("profile-level-id" to profile)
    }

    private fun names(list: List<RtpCapabilities.CodecCapability>?) = list?.map { it.name }

    @Test
    fun `H264 提到最前，其余保持原有相对顺序`() {
        val input = listOf(
            codec("VP8"), codec("VP9"),
            codec("H264", "42e01f"), codec("AV1"), codec("H264", "640c1f"),
        )
        // H.264 两档按原有先后（42e01f 在 640c1f 前），其余三个也按原有先后。
        assertEquals(
            listOf("H264", "H264", "VP8", "VP9", "AV1"),
            names(IMUplinkPolicy.h264First(input)),
        )
    }

    @Test
    fun `VP8 必须留在列表里——回落路径不能被掐掉`() {
        val ordered = IMUplinkPolicy.h264First(listOf(codec("VP8"), codec("H264", "42e01f")))
        assertEquals(2, ordered?.size)
        assertEquals(
            "对端不支持这一档 H.264 时要能静默回落到 VP8，不是直接没视频",
            true, names(ordered)?.contains("VP8"),
        )
    }

    @Test
    fun `这台机器没有 H264 时返回 null，调用方保持默认顺序`() {
        assertNull(IMUplinkPolicy.h264First(listOf(codec("VP8"), codec("VP9"))))
        assertNull(IMUplinkPolicy.h264First(emptyList()))
    }

    @Test
    fun `大小写不敏感——厂商报的名字不一定是 H264`() {
        assertEquals(listOf("h264", "VP8"), names(IMUplinkPolicy.h264First(listOf(codec("VP8"), codec("h264")))))
    }

    @Test
    fun `已经在最前时顺序不变，不是每次协商都洗一遍牌`() {
        val input = listOf(codec("H264", "42e01f"), codec("VP8"), codec("VP9"))
        assertEquals(listOf("H264", "VP8", "VP9"), names(IMUplinkPolicy.h264First(input)))
    }
}
