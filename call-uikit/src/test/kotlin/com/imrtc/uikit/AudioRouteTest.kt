package com.imrtc.uikit

import com.imrtc.engine.IMAudioRoute
import com.imrtc.engine.IMAudioRouteKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扬声器键按可选路由的条数变形（设计稿 §04 v3.5）。与 iOS `AudioRoutePickerTests` 同一组用例。 */
class AudioRouteTest {

    private val earpiece = IMAudioRoute(IMAudioRouteKind.EARPIECE, "", IMAudioRoute.EARPIECE_UID)
    private val speaker = IMAudioRoute(IMAudioRouteKind.SPEAKER, "", IMAudioRoute.SPEAKER_UID)
    private val airpods = IMAudioRoute(IMAudioRouteKind.BLUETOOTH, "AirPods Pro", "9")
    private val builtInTwo = listOf(earpiece, speaker)

    @After
    fun reset() {
        IMText.locale = IMLocale.ZH_CN
        IMText.overrides = emptyMap()
    }

    @Test
    fun `只有内置两条：二态开关；清单为空（媒体没起）也是`() {
        assertFalse(IMCallViewState(audioRoutes = builtInTwo).showsRoutePicker)
        assertFalse(IMCallViewState().showsRoutePicker)
    }

    @Test
    fun `出现第三条就是路由选择，选回听筒之后蓝牙还在清单里，入口不消失`() {
        val state = IMCallViewReducer.audioRoutes(IMCallViewState(), builtInTwo + airpods, airpods)
        assertTrue(state.showsRoutePicker)
        val back = IMCallViewReducer.audioRoutes(state, builtInTwo + airpods, earpiece)
        assertTrue(back.showsRoutePicker)
        assertEquals(earpiece, back.currentAudioRoute)
    }

    @Test
    fun `扬声器布尔跟着在用的那条走；清单为空时不动它`() {
        val on = IMCallViewReducer.audioRoutes(IMCallViewState(), builtInTwo + airpods, speaker)
        assertTrue(on.speakerOn)
        val bt = IMCallViewReducer.audioRoutes(on, builtInTwo + airpods, airpods)
        assertFalse(bt.speakerOn)
        val kept = IMCallViewReducer.audioRoutes(on, emptyList(), null)
        assertTrue(kept.speakerOn)
    }

    @Test
    fun `文案：外接设备用真名，内置两条按语言取词`() {
        assertEquals("AirPods Pro", routeDisplayName(airpods))
        assertEquals("听筒", routeDisplayName(earpiece))
        assertEquals("扬声器", routeDisplayName(speaker))
        IMText.locale = IMLocale.EN
        assertEquals("Speaker", routeDisplayName(speaker))
    }
}
