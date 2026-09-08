package com.imrtc.engine.webrtc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
协商闸门。守的是真机 2026-09-08 那个故障：

Wi-Fi 关掉再打开，信令恢复了、下行也恢复了，**上行再也没协商过一次**——
对端全程看不到画面，本端界面停在「正在重连」，而日志里一条错误都没有。
根因是闸门（原先是三个裸 `mutableSetOf`）被三个线程并发读写，
竞态下 `negotiating -= pc` 丢失，那条 PC 从此永远「协商进行中」。

所以这里除了逐条语义，**必须有一条多线程用例**：闸门是并发数据结构，
单线程测得再全也证明不了它不会卡死。
*/
class IMNegotiationGateTest {

    @Test
    fun `第一个 offer 放行，第二个排队`() {
        val gate = IMNegotiationGate()
        assertNotNull("第一个应当放行", gate.beginOffer("pub", iceRestart = false))
        assertNull("在飞时第二个应当排队", gate.beginOffer("pub", iceRestart = false))
        assertTrue(gate.isNegotiating("pub"))
    }

    @Test
    fun `收工后把排队的那个补出来`() {
        val gate = IMNegotiationGate()
        gate.beginOffer("pub", iceRestart = false)
        gate.beginOffer("pub", iceRestart = false) // 排队
        assertTrue("应当告诉调用方还欠一个 offer", gate.finishOffer("pub"))
        assertFalse(gate.isNegotiating("pub"))
        assertFalse("没有排队的了，不该再补", gate.finishOffer("pub"))
    }

    /*
      **补协商补的是普通 offer，ICE restart 那一位不能跟着丢。**
      丢了的话网断了这条 PC 就永远重连不上，而日志里一切正常。
    */
    @Test
    fun `排队期间提出的 ICE restart 要留到补那一轮`() {
        val gate = IMNegotiationGate()
        gate.beginOffer("pub", iceRestart = false)
        assertNull("在飞，应当排队", gate.beginOffer("pub", iceRestart = true))
        gate.finishOffer("pub")

        assertEquals("补的这一轮必须带上 ICE restart", true, gate.beginOffer("pub", iceRestart = false))
        gate.finishOffer("pub")
        assertEquals("这一位是一次性的，不该再带", false, gate.beginOffer("pub", iceRestart = false))
    }

    @Test
    fun `markIceRestart 只置位，下一轮 offer 带上`() {
        val gate = IMNegotiationGate()
        gate.markIceRestart("pub")
        assertEquals("置过位，这一轮应当重启 ICE", true, gate.beginOffer("pub", iceRestart = false))
        gate.finishOffer("pub")
        assertEquals("这一位是一次性的", false, gate.beginOffer("pub", iceRestart = false))
    }

    /*
      失败也必须放闸。少放一处就是永久卡死，**而且没有任何报错**——
      只表现为「对端再也看不到我」。
    */
    @Test
    fun `失败收场后闸门要放开`() {
        val gate = IMNegotiationGate()
        gate.beginOffer("pub", iceRestart = false)
        gate.abortOffer("pub")
        assertFalse(gate.isNegotiating("pub"))
        assertNotNull("放闸之后应当能再发", gate.beginOffer("pub", iceRestart = false))
    }

    @Test
    fun `失败收场不补排队的那个`() {
        val gate = IMNegotiationGate()
        gate.beginOffer("pub", iceRestart = false)
        gate.beginOffer("pub", iceRestart = false) // 排队
        gate.abortOffer("pub")
        // 这一轮都失败了，立刻再发一个多半同样下场；交给上层的重试节奏驱动。
        assertFalse(gate.isNegotiating("pub"))
    }

    /*
      **这条直接对应真机故障。** 换了一条连接，之前那个 offer 的 answer
      永远不会回来了（它是从旧 socket 上发出去的）。不重置的话闸门一直关着，
      恢复后的重新协商只会排队，那条 PC 就此永久沉默。
    */
    @Test
    fun `会话恢复后重置在飞状态，让重新协商发得出去`() {
        val gate = IMNegotiationGate()
        gate.beginOffer("pub", iceRestart = false) // 断网前在飞的那个
        assertNull("不重置的话恢复后只能排队", gate.beginOffer("pub", iceRestart = true))

        gate.resetInFlight("pub")

        assertEquals(
            "重置之后应当发得出去，且带上之前欠下的 ICE restart",
            true, gate.beginOffer("pub", iceRestart = false),
        )
    }

    @Test
    fun `两条 PC 互不干扰`() {
        val gate = IMNegotiationGate()
        gate.beginOffer("pub", iceRestart = false)
        assertNotNull("sub 不该被 pub 挡住", gate.beginOffer("sub", iceRestart = false))
        assertTrue(gate.isNegotiating("pub"))
        assertTrue(gate.isNegotiating("sub"))
    }

    /*
      **同一时刻只许一个 offer 拿到放行**——这是闸门存在的全部理由。

      两个 offer 同时在飞，第二条 answer 回来时状态已经是 stable，native 层报
      `Called in wrong state`，那条轨道就再也协商不上了。

      这条用例断言的正是竞态**必然**会破坏的那个不变量：N 个线程同时申请，
      放行的只能有一个。裸 `mutableSetOf` 下两个线程会双双看到「不在 negotiating 里」
      而同时拿到放行。跑多轮是因为竞态窗口很窄，单轮未必撞得上。

      **注意它证明不了什么**：并发用例只能提高撞见的概率，撞不上不等于没有竞态。
      真正让闸门不会卡死的是 [IMNegotiationGate] 里那把锁，以及
      「每一个终局都放闸」「恢复时重置在飞状态」这两条——后者有确定性用例守着。
    */
    @Test
    fun `并发申请时只许一个 offer 放行`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(300) { round ->
                val gate = IMNegotiationGate()
                val granted = AtomicInteger()
                val start = CountDownLatch(1)
                val done = CountDownLatch(threads)
                repeat(threads) {
                    pool.submit {
                        start.await()
                        if (gate.beginOffer("pub", iceRestart = false) != null) granted.incrementAndGet()
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue("第 $round 轮并发申请没跑完", done.await(10, TimeUnit.SECONDS))
                assertEquals(
                    "第 $round 轮有 ${granted.get()} 个 offer 同时拿到放行——" +
                        "两个 offer 一起在飞，第二条 answer 回来就是 Called in wrong state",
                    1, granted.get(),
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
