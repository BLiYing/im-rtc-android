package com.imrtc.uikit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动隐藏的倒计时判定（[IMAutoHideCountdown]）。
 *
 * **第一条是这个类存在的理由**：真机上控制条从来没自动隐藏过，因为
 * `IMCallKit.startTimer()` 每秒推一次状态 → `IMCallView.render` → `armAutoHide`，
 * 而 arm 原先无条件重排，3 秒计时被 1 秒的 tick 一直打断。
 */
class AutoHideCountdownTest {

    @Test
    fun `每秒来一次 render 不该重置正在走的倒计时`() {
        val c = IMAutoHideCountdown()
        // 接通那一刻：排上。
        assertTrue("第一次该排上", c.arm(canAutoHide = true, restart = false))
        // 之后每秒一次 render——**一次都不该重排**，否则 3 秒永远走不完。
        repeat(10) { tick ->
            assertFalse("第 $tick 次 render 重排了倒计时", c.arm(canAutoHide = true, restart = false))
        }
        assertTrue("倒计时应当还在", c.pending)
    }

    @Test
    fun `控制条重新显示出来时要从头数`() {
        val c = IMAutoHideCountdown()
        c.arm(canAutoHide = true, restart = false)
        // 用户点了一下画面，控制条重新出现：这一次**必须**重排，否则会提前收起。
        assertTrue("刚显示出来该从头数", c.arm(canAutoHide = true, restart = true))
    }

    @Test
    fun `触发或撤销之后，下一次 render 能重新排上`() {
        val c = IMAutoHideCountdown()
        c.arm(canAutoHide = true, restart = false)
        c.clear() // 定时器触发了 / 被撤了
        assertFalse("清掉之后不该还挂着", c.pending)
        assertTrue("下一次 render 该能重新排上", c.arm(canAutoHide = true, restart = false))
    }

    @Test
    fun `此刻不允许隐藏时不排，也不留下待触发的状态`() {
        val c = IMAutoHideCountdown()
        // 比如 CONNECTING 阶段、或者九宫格版式。
        assertFalse(c.arm(canAutoHide = false, restart = false))
        assertFalse("不该留下一个假的 pending", c.pending)
        // 接通之后那一次 render 要能排上——**这就是不留假 pending 的意义**。
        assertTrue(c.arm(canAutoHide = true, restart = false))
    }

    @Test
    fun `正在倒计时时条件变成不允许，重排会把它清掉`() {
        val c = IMAutoHideCountdown()
        c.arm(canAutoHide = true, restart = false)
        // 版式从 1v1 切成九宫格：界面会走 set(visible=true, arm=false) → cancel，
        // 但万一走的是重排这条路，也不该留着一个会在九宫格里触发的定时器。
        assertFalse(c.arm(canAutoHide = false, restart = true))
        assertFalse(c.pending)
    }
}
