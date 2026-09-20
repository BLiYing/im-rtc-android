package com.imrtc.uikit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
守的是真机 2026-09-20 那个故障：通话页全屏时按 Home，停 45 分钟再回来，停在宿主首页，
通话页没了，Kit 的形态却还是 fullscreen；进程与媒体一路都活着，对端一直显示他在通话中。
根因是形态没变就不再拉起通话页。第一条用例就是它；其余几条守「不许误拉」：
权限弹窗页上、刚拉起过、通话已结束——这三种再拉都是新的故障。
*/
class PresentRulesTest {

    private val CONNECTED = IMCallViewState.Phase.CONNECTED
    private val ENDED = IMCallViewState.Phase.ENDED
    private val IDLE = IMCallViewState.Phase.IDLE

    /** **这一条直接对应真机故障。** */
    @Test
    fun `通话中宿主页回到前台且隔了很久，重新拉起通话页`() {
        assertTrue(
            "回前台停在宿主首页、通话页没了——真机 2026-09-20 正是这一幕",
            IMPresentRules.shouldRepresent(CONNECTED, hostResumed = true, hostVisible = true, sinceLastPresentMs = 45 * 60_000L),
        )
    }

    @Test
    fun `没有拉起过（自开机 Kit 从未 present）也算隔得够久`() {
        assertTrue(IMPresentRules.shouldRepresent(CONNECTED, hostResumed = true, hostVisible = true, sinceLastPresentMs = Long.MAX_VALUE))
    }

    @Test
    fun `结束画面上宿主页回到前台也要拉，让用户看见结束原因`() {
        assertTrue(IMPresentRules.shouldRepresent(ENDED, hostResumed = true, hostVisible = true, sinceLastPresentMs = 60_000L))
    }

    @Test
    fun `通话已经 IDLE 就没有要回的东西`() {
        assertFalse(IMPresentRules.shouldRepresent(IDLE, hostResumed = true, hostVisible = true, sinceLastPresentMs = 60_000L))
    }

    @Test
    fun `不是宿主页回前台触发的（比如每秒计时）不拉`() {
        assertFalse(
            "每秒计时也会走 apply，走一次拉一次就是连环弹",
            IMPresentRules.shouldRepresent(CONNECTED, hostResumed = false, hostVisible = true, sinceLastPresentMs = 60_000L),
        )
    }

    @Test
    fun `前台页是权限弹窗页时不拉，别把通话页盖在弹窗上`() {
        assertFalse(IMPresentRules.shouldRepresent(CONNECTED, hostResumed = true, hostVisible = false, sinceLastPresentMs = 60_000L))
    }

    @Test
    fun `刚拉起过不久不再拉`() {
        assertFalse(IMPresentRules.shouldRepresent(CONNECTED, hostResumed = true, hostVisible = true, sinceLastPresentMs = IMPresentRules.MIN_GAP_MS - 1))
        assertTrue(IMPresentRules.shouldRepresent(CONNECTED, hostResumed = true, hostVisible = true, sinceLastPresentMs = IMPresentRules.MIN_GAP_MS))
    }
}
