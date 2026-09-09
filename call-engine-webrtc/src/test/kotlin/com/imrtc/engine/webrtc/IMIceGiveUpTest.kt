package com.imrtc.engine.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
ICE 自愈的放弃判定（协议 §7.2）。

守的是这条：一律自愈、永不上报的话，上行永久失败在宿主那边**完全无感**——
对端格子已经黑了、计时器还在走，而界面上一切正常、谁也不挂断。
反过来每次 FAILED 都报又会把切网抖动误报成「通话废了」，还会每 30 秒刷一条。

差一个 `>=` 就退化成上面两种之一，所以逐条钉死。
*/
class IMIceGiveUpTest {

    @Test
    fun `前两次是抖动，不上报`() {
        val giveUp = IMIceGiveUp()
        assertFalse("第 1 次不该报", giveUp.noteFailure())
        assertFalse("第 2 次不该报", giveUp.noteFailure())
        assertEquals(2, giveUp.restartCount())
    }

    @Test
    fun `第三次才放弃并上报`() {
        val giveUp = IMIceGiveUp()
        giveUp.noteFailure()
        giveUp.noteFailure()
        assertTrue("连续 3 次就该让宿主知道", giveUp.noteFailure())
    }

    @Test
    fun `同一轮只报一次——放弃是告诉宿主，不是不救了`() {
        val giveUp = IMIceGiveUp()
        repeat(2) { giveUp.noteFailure() }
        assertTrue(giveUp.noteFailure())
        assertFalse("第 4 次不该再刷", giveUp.noteFailure())
        assertFalse("第 5 次也不该", giveUp.noteFailure())
        assertEquals("但重试计数照走——调用方仍在继续自愈", 5, giveUp.restartCount())
    }

    @Test
    fun `救回来过就是新一轮，不拿旧账凑数`() {
        val giveUp = IMIceGiveUp()
        giveUp.noteFailure()
        giveUp.noteFailure()
        giveUp.noteConnected()
        assertEquals(0, giveUp.restartCount())
        assertFalse("清零后第 1 次", giveUp.noteFailure())
        assertFalse("清零后第 2 次", giveUp.noteFailure())
        assertTrue("清零后也要满 3 次才报", giveUp.noteFailure())
    }

    @Test
    fun `放弃之后再救回来，下一轮还能再报一次`() {
        val giveUp = IMIceGiveUp()
        repeat(3) { giveUp.noteFailure() }
        giveUp.noteConnected()
        repeat(2) { giveUp.noteFailure() }
        assertTrue("新一轮的故障是新消息，不该被上一轮的 gaveUp 永久压住", giveUp.noteFailure())
    }

    @Test
    fun `阈值可配`() {
        val giveUp = IMIceGiveUp(giveUpAfter = 1)
        assertTrue("阈值 1 就是第一次失败即报", giveUp.noteFailure())
    }
}
