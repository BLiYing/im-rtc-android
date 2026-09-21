package com.imrtc.uikit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多语言：文案表两种语言齐全、占位符一致、切换与覆盖的查找顺序、跟随系统的归类。 */
class IMTextTest {

    @After
    fun reset() {
        IMText.locale = IMLocale.ZH_CN
        IMText.overrides = emptyMap()
    }

    @Test
    fun `两种语言 key 一样、没有空串`() {
        assertEquals(IMMessages.zhCn.keys, IMMessages.en.keys)
        assertTrue((IMMessages.zhCn.values + IMMessages.en.values).none { it.isEmpty() })
    }

    @Test
    fun `占位符在各语言里一致`() {
        val hole = Regex("\\{\\w+}")
        for ((key, zh) in IMMessages.zhCn) {
            val en = IMMessages.en.getValue(key)
            assertEquals(key, hole.findAll(zh).map { it.value }.sorted().toList(), hole.findAll(en).map { it.value }.sorted().toList())
        }
    }

    @Test
    fun `默认中文，切到英文后出英文并带参数`() {
        assertEquals("对方忙线中", IMText.t("end.busy"))
        IMText.locale = IMLocale.EN
        assertEquals("User is busy", IMText.t("end.busy"))
        assertEquals("Call ended · 01:05", IMCallViewState.endReasonText("hangup", "caller", 65))
    }

    @Test
    fun `宿主覆盖优先，缺失回落 key`() {
        IMText.locale = IMLocale.EN
        IMText.overrides = mapOf(IMLocale.EN to mapOf("ctl.accept" to "Pick up"))
        assertEquals("Pick up", IMText.t("ctl.accept"))
        assertEquals("nope", IMText.t("nope"))
    }

    @Test
    fun `配置的 locale 会同步到文案入口`() {
        val config = IMCallKitConfig()
        config.locale = IMLocale.EN
        assertEquals(IMLocale.EN, IMText.locale)
    }

    @Test
    fun `跟随系统按语言主码归类`() {
        assertEquals(IMLocale.EN, IMLocale.system(listOf("en-US")))
        assertEquals(IMLocale.EN, IMLocale.system(listOf("fr", "en_GB")))
        assertEquals(IMLocale.ZH_CN, IMLocale.system(listOf("zh-Hant-TW")))
        assertEquals(IMLocale.ZH_CN, IMLocale.system(listOf("ja")))
    }
}
