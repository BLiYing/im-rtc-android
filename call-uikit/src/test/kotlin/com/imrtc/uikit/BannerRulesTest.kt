package com.imrtc.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
顶部橙条（规范 §08）。

守的是真机 2026-09-08 那个故障：Wi-Fi 关掉再打开，信令恢复了、上下行视频都回来了、
心跳一路没断过，**橙条却永远停在「正在重连…」**，而且怎么操作都撤不掉。
根因是「恢复成 OK」那一格恰好落在 `if (text.isNotEmpty() || connection != OK)` 的
假分支上——那一刻文案是空串、connection 又正好是 OK，`banner.apply("")` 一次都不会调。

所以第一条用例就是它，剩下的是不许把另一半（「对方网络不佳」只停 2s）改坏。
*/
class BannerRulesTest {

    private val OK = IMCallViewState.Connection.OK
    private val RECONNECTING = IMCallViewState.Connection.RECONNECTING
    private val LOST = IMCallViewState.Connection.LOST

    /** **这一条直接对应真机故障。** */
    @Test
    fun `连接恢复成 OK 时把「正在重连」撤掉`() {
        assertEquals(
            "恢复了却不撤，橙条就永远挂着——真机 2026-09-08 正是这一幕",
            "",
            IMBannerRules.next(OK, poor = false, poorShown = false, current = IMBannerRules.RECONNECTING),
        )
    }

    @Test
    fun `连接恢复成 OK 时把「连接已断开」也撤掉`() {
        assertEquals("", IMBannerRules.next(OK, poor = false, poorShown = false, current = IMBannerRules.LOST))
    }

    @Test
    fun `断线时写「正在重连」，放弃时写「连接已断开」`() {
        assertEquals(IMBannerRules.RECONNECTING, IMBannerRules.next(RECONNECTING, poor = false, poorShown = false, current = ""))
        assertEquals(IMBannerRules.LOST, IMBannerRules.next(LOST, poor = false, poorShown = false, current = ""))
    }

    /*
      连接类文案**压过**网络不佳：断线的时候「对方网络不佳」是句废话，
      而且它一旦盖上去，连接恢复那一格就认不出「橙条上写的是连接类文案」，
      于是又回到撤不掉的老路上。
    */
    @Test
    fun `断线时不让「对方网络不佳」盖上去`() {
        assertEquals(IMBannerRules.RECONNECTING, IMBannerRules.next(RECONNECTING, poor = true, poorShown = false, current = ""))
    }

    @Test
    fun `有人网络差且这一轮还没出过时出「对方网络不佳」`() {
        assertEquals(IMBannerRules.POOR, IMBannerRules.next(OK, poor = true, poorShown = false, current = ""))
    }

    /*
      **出过就别再出。** `room.active_speakers` 一秒来好几帧、每帧都渲染一次；
      不挡这一下，2s 的定时器每次都被顶回去，这条横幅就一直霸占顶部。
    */
    @Test
    fun `已经出过就不再重复出`() {
        assertNull(IMBannerRules.next(OK, poor = true, poorShown = true, current = IMBannerRules.POOR))
    }

    /*
      **「对方网络不佳」不由这里撤**，它有自己的 2s 定时器。
      在这里跟着撤等于每来一帧 active_speakers 就把它抹掉，根本来不及看见。
    */
    @Test
    fun `「对方网络不佳」还挂着时这一轮不动橙条`() {
        assertNull(IMBannerRules.next(OK, poor = false, poorShown = false, current = IMBannerRules.POOR))
    }

    @Test
    fun `一切正常且橙条本来就空着时不动它`() {
        assertNull(IMBannerRules.next(OK, poor = false, poorShown = false, current = ""))
    }
}
