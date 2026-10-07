package com.imrtc.uikit

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.IMRTCError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kit 取票登录的会话（server `docs/design/KIT_TOKEN_PROVIDER_DESIGN.md` §4）。**纯 JVM**——
 * 计时、联网判断、切主线程都是注入的。场景与 Web `kitSession.test.ts`、iOS `KitSessionTests` 一一对应。
 */
class KitSessionTest {

    /** 手动推进的时钟：`advance(ms)` 跑掉到点的任务。 */
    private class Clock {
        var now = 0L
        private val tasks = ArrayList<Triple<Long, () -> Unit, BooleanArray>>()

        fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val live = booleanArrayOf(true)
            tasks += Triple(now + delayMs, task, live)
            return { live[0] = false }
        }

        fun pending(): List<Long> = tasks.filter { it.third[0] && it.first > now }.map { it.first - now }

        fun advance(ms: Long) {
            now += ms
            tasks.toList().forEach { (at, task, live) ->
                if (live[0] && at <= now) {
                    live[0] = false
                    task()
                }
            }
        }
    }

    private class FakeEngine : IMSessionEngine {
        val log = ArrayList<String>()
        var loginError: IMRTCError? = null
        /** 非空时 login 挂起，由测试手动结掉。 */
        var held: ((IMRTCError?) -> Unit)? = null
        var holdLogin = false

        override fun login(token: String, done: (IMRTCError?) -> Unit) {
            log += "login:$token"
            if (holdLogin) held = done else done(loginError)
        }

        override fun logout() { log += "logout" }
        override fun updateToken(token: String, expiresAtMs: Long) { log += "update:$token:$expiresAtMs" }
        override fun notifyNetworkChanged() { log += "nudge" }
        fun logins() = log.filter { it.startsWith("login:") }
    }

    private class Rig {
        val engine = FakeEngine()
        val clock = Clock()
        var online = true
        var calls = 0
        /** 默认同步给票；测试可换成失败 / 挂起。 */
        var next: (IMTokenCallback) -> Unit = { it.onResult(IMKitToken("t$calls"), null) }
        val session = IMKitSession(
            engine = engine,
            provider = IMTokenProvider { cb -> calls++; next(cb) },
            schedule = { delay, task -> clock.schedule(delay, task) },
            isOnline = { online },
            mainThread = { it() },
        )

        fun ensure(): Result {
            val result = Result()
            session.ensure { result.value = it; result.done = true }
            return result
        }
    }

    private class Result {
        var done = false
        var value: IMKitFailure? = null
    }

    private fun error(code: Int) = IMRTCError(code, "x", "x", "sys.hello")
    private val fail: (IMTokenCallback) -> Unit = { it.onResult(null, IllegalStateException("后台 500")) }

    @Test
    fun `启动即取票登录，登上后 ensure 立即可用`() {
        val r = Rig()
        r.session.start()
        assertEquals(listOf("login:t1"), r.engine.logins())
        assertEquals(IMKitSession.Phase.READY, r.session.phase)
        val res = r.ensure()
        assertTrue(res.done)
        assertNull(res.value)
    }

    @Test
    fun `取票失败按退避重试，成功后退避归零`() {
        val r = Rig()
        r.next = fail
        r.session.start()
        assertEquals(IMKitSession.Phase.WAITING, r.session.phase)
        assertEquals(listOf(2_000L), r.clock.pending())
        r.clock.advance(2_000)
        assertEquals(2, r.calls)
        assertEquals(listOf(4_000L), r.clock.pending())
        r.next = { it.onResult(IMKitToken("ok"), null) }
        r.clock.advance(4_000)
        assertEquals(listOf("login:ok"), r.engine.logins())
        assertEquals(IMKitSession.Phase.READY, r.session.phase)
        assertEquals(emptyList<Long>(), r.clock.pending())
    }

    @Test
    fun `退避封顶 60 s`() {
        val r = Rig()
        r.next = fail
        r.session.start()
        IMKitSession.RETRY_BACKOFF_MS.forEach { r.clock.advance(it) }
        assertEquals(listOf(60_000L), r.clock.pending())
    }

    @Test
    fun `拨号时在退避里：立刻再试一次并等它的结果`() {
        val r = Rig()
        r.next = fail
        r.session.start()
        r.next = { it.onResult(IMKitToken("now"), null) }
        val res = r.ensure()
        assertTrue(res.done)
        assertNull(res.value)
        assertEquals(listOf("login:now"), r.engine.logins())
    }

    @Test
    fun `并发 ensure 共用一轮尝试`() {
        val r = Rig()
        r.engine.holdLogin = true
        r.session.start()
        val a = r.ensure()
        val b = r.ensure()
        r.engine.held?.invoke(null)
        assertTrue(a.done && b.done)
        assertNull(a.value)
        assertNull(b.value)
        assertEquals(1, r.calls)
    }

    @Test
    fun `失败类别：2003 是网络，取票失败看设备有没有网`() {
        val r1 = Rig()
        r1.engine.loginError = error(2003)
        r1.session.start()
        r1.engine.loginError = error(2003)
        assertEquals(IMKitFailure.NETWORK, r1.ensure().value)

        val r2 = Rig()
        r2.next = fail
        r2.session.start()
        assertEquals(IMKitFailure.SERVICE, r2.ensure().value)
        r2.online = false
        assertEquals(IMKitFailure.NETWORK, r2.ensure().value)
    }

    @Test
    fun `登录失败后先登出：Kit 是唯一的重试者`() {
        val r = Rig()
        r.engine.loginError = error(1101)
        r.session.start()
        assertEquals(listOf("logout", "login:t1", "logout"), r.engine.log)
    }

    @Test
    fun `停止后迟到的票作废，不去登录`() {
        val r = Rig()
        var late: IMTokenCallback? = null
        r.next = { late = it }
        r.session.start()
        val waiting = r.ensure()
        r.session.stop()
        assertTrue(waiting.done)
        assertEquals(IMKitFailure.SERVICE, waiting.value)
        late?.onResult(IMKitToken("late"), null)
        assertEquals(emptyList<String>(), r.engine.logins())
        assertEquals(IMKitSession.Phase.IDLE, r.session.phase)
    }

    @Test
    fun `网络恢复时在退避里的立刻再试`() {
        val r = Rig()
        r.next = fail
        r.session.start()
        r.session.onNetworkRestored()
        assertEquals(2, r.calls)
    }

    @Test
    fun `票快过期：取新票交给 Engine`() {
        val r = Rig()
        r.session.start()
        r.next = { it.onResult(IMKitToken("fresh", 99), null) }
        r.session.onTokenWillExpire()
        assertTrue(r.engine.log.contains("update:fresh:99"))
    }

    @Test
    fun `AUTH_EXPIRED 被踢：登出后重新取票登录`() {
        val r = Rig()
        r.session.start()
        r.session.onKickedOut(IMKickedOutReason.AUTH_EXPIRED)
        assertEquals(listOf("login:t1", "login:t2"), r.engine.logins())
        assertEquals(IMKitSession.Phase.READY, r.session.phase)
    }

    @Test
    fun `顶号或配置被拒：不自动重试，但用户亲手点时仍试一次`() {
        val r = Rig()
        r.session.start()
        r.session.onKickedOut(IMKickedOutReason.TAKEN_OVER)
        assertEquals(IMKitSession.Phase.HALTED, r.session.phase)
        assertEquals(emptyList<Long>(), r.clock.pending())
        r.next = fail
        assertEquals(IMKitFailure.SERVICE, r.ensure().value)
        assertEquals(IMKitSession.Phase.HALTED, r.session.phase)
        assertEquals(emptyList<Long>(), r.clock.pending())
    }

    @Test
    fun `已登录、正在重连：催 Engine 立刻连并等 onConnected`() {
        val r = Rig()
        r.session.start()
        r.session.onDisconnected()
        val waiting = r.ensure()
        assertTrue(r.engine.log.contains("nudge"))
        r.session.onConnected()
        assertTrue(waiting.done)
        assertNull(waiting.value)
    }

    @Test
    fun `等太久按网络失败算`() {
        val r = Rig()
        r.session.start()
        r.session.onDisconnected()
        val waiting = r.ensure()
        r.clock.advance(IMKitSession.ENSURE_TIMEOUT_MS)
        assertTrue(waiting.done)
        assertEquals(IMKitFailure.NETWORK, waiting.value)
    }

    @Test
    fun `没启动时 ensure 直接失败`() {
        val r = Rig()
        assertEquals(IMKitFailure.SERVICE, r.ensure().value)
    }

    @Test
    fun `宿主的 provider 抛异常按取票失败算`() {
        val r = Rig()
        r.next = { throw IllegalStateException("boom") }
        r.session.start()
        assertEquals(IMKitSession.Phase.WAITING, r.session.phase)
    }
}
