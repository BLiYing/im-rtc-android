package com.imrtc.uikit

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.IMRTCError
import com.imrtc.engine.log.IMRTCLog

/**
 * 宿主取回来的一张 RTC 接入票（server `docs/design/KIT_TOKEN_PROVIDER_DESIGN.md`）。
 * `expiresAtMs` 不知道就填 0，Engine 会从 `sys.hello.ok` 拿权威值。与 Web / iOS 同名同义。
 */
class IMKitToken @JvmOverloads constructor(val token: String, val expiresAtMs: Long = 0L)

/** 取票结果：成功给 `token`，失败给 `error`（二者恰好一个非空）。任何线程回调都行。 */
fun interface IMTokenCallback {
    fun onResult(token: IMKitToken?, error: Throwable?)
}

/**
 * 「从你的后台取一张 RTC 接入票」——配在 [IMCallKitConfig.tokenProvider]。
 *
 * **给了它，登录归 Kit**：何时取、失败了怎么重来、拨号前补登录都不用宿主管（见 [IMKitSession]）。
 */
fun interface IMTokenProvider {
    fun fetchToken(callback: IMTokenCallback)
}

/** [IMCallKit.ensureReady] 的回调：`true` = 已登录、可以用 Engine 了。主线程回来。 */
fun interface IMReadyCallback {
    fun onResult(ready: Boolean)
}

/** 登不上时给用户的话分两类（设计 §5）：查网络 / 稍后再试。 */
internal enum class IMKitFailure(val hintKey: String) {
    NETWORK("hint.serviceUnreachable"),
    SERVICE("hint.serviceUnavailable"),
}

/** 会话用到的那一小片 Engine。真实现见 [IMKitLogin]，单测换假的。 */
internal interface IMSessionEngine {
    /** 结果回到主线程；`null` = 登上了。 */
    fun login(token: String, done: (IMRTCError?) -> Unit)
    fun logout()
    fun updateToken(token: String, expiresAtMs: Long)
    fun notifyNetworkChanged()
}

/**
 * Kit 取票登录的会话：**Engine 仍是 push**（`updateToken`，RTC_CALL_DESIGN §7.5 不变），**Kit 来 pull**。
 *
 * 纯逻辑，计时、联网判断、切主线程都注入——JVM 单测直接驱动（`KitSessionTest`）。
 * **只在主线程上用**：取票回调由 [mainThread] 切回来。
 */
internal class IMKitSession(
    private val engine: IMSessionEngine,
    private val provider: IMTokenProvider,
    /** 排一个定时任务，返回撤销函数。 */
    private val schedule: (delayMs: Long, task: () -> Unit) -> (() -> Unit),
    private val isOnline: () -> Boolean,
    private val mainThread: (() -> Unit) -> Unit,
) {
    enum class Phase { IDLE, CONNECTING, READY, WAITING, HALTED }

    var phase = Phase.IDLE
        private set

    /** 代际：每轮尝试、每次停止都换代，迟到的回调认得出自己作废了。 */
    private var generation = 0
    private var failures = 0
    /** 顶号 / 配置被拒：不自动重试，直到下一次登录成功或重新启动。 */
    private var haltedByKick = false
    private var connected = false
    private var cancelRetry: (() -> Unit)? = null
    private val attemptWaiters = ArrayList<Waiter>()
    private val reconnectWaiters = ArrayList<Waiter>()

    fun start() {
        if (phase != Phase.IDLE) return
        attempt()
    }

    /** 在途的取票 / 登录回来一律作废，等待者按失败结掉，Engine 登出。 */
    fun stop() {
        if (phase == Phase.IDLE) return
        generation++
        clearRetry()
        phase = Phase.IDLE
        connected = false
        haltedByKick = false
        failures = 0
        settle(attemptWaiters, IMKitFailure.SERVICE)
        settle(reconnectWaiters, IMKitFailure.SERVICE)
        engine.logout()
    }

    /** 确保已登录：回 `null` = 可以用了，否则是失败类别。**同一时刻只有一轮尝试**，多处调用共用它。 */
    fun ensure(done: (IMKitFailure?) -> Unit) {
        when (phase) {
            Phase.IDLE -> done(IMKitFailure.SERVICE)
            Phase.READY -> if (connected) done(null) else {
                // 登上过、正在重连：叫 Engine 别按退避等了，立刻连，然后等 onConnected。
                engine.notifyNetworkChanged()
                wait(reconnectWaiters, done)
            }
            Phase.CONNECTING -> wait(attemptWaiters, done)
            // HALTED 也试：这是用户亲手点的，代价是一次请求（设计 §4）。
            // 先排队再尝试：取票与登录都同步回来时（缓存的票），这一轮会在 attempt() 里就结掉。
            Phase.WAITING, Phase.HALTED -> {
                wait(attemptWaiters, done)
                attempt()
            }
        }
    }

    fun onConnected() {
        connected = true
        if (phase == Phase.READY) settle(reconnectWaiters, null)
    }

    fun onDisconnected() {
        connected = false
    }

    fun onKickedOut(reason: IMKickedOutReason) {
        connected = false
        if (phase == Phase.IDLE) return
        if (reason == IMKickedOutReason.AUTH_EXPIRED) {
            // 票的问题：取一张新票重登。Engine 已经放弃这条连接，先登出再来。
            IMRTCLog.i("kit", "票失效被踢，重新取票登录")
            haltedByKick = false
            engine.logout()
            attempt()
            return
        }
        // 顶号该回登录页、配置错了重试也没用——都不自动重来（宿主照常收到 onKickedOut）。
        IMRTCLog.w("kit", "被踢下线，不再自动登录 reason=$reason")
        haltedByKick = true
        clearRetry()
        if (phase == Phase.READY || phase == Phase.WAITING) phase = Phase.HALTED
        settle(reconnectWaiters, IMKitFailure.SERVICE)
    }

    /** 票快过期：取新票交给 Engine（下次重连生效）。取不到只记日志，降级成 4401 → AUTH_EXPIRED 那条路。 */
    fun onTokenWillExpire() {
        if (phase != Phase.READY) return
        val mine = generation
        fetch { ticket, error ->
            if (mine != generation) return@fetch
            if (ticket == null || ticket.token.isEmpty()) {
                IMRTCLog.w("kit", "续票时取票失败：${error?.message}")
                return@fetch
            }
            engine.updateToken(ticket.token, ticket.expiresAtMs)
            IMRTCLog.i("kit", "已续票")
        }
    }

    /** 网络恢复 / 回到前台：在退避里等着的立刻再试。 */
    fun onNetworkRestored() {
        if (phase == Phase.WAITING) attempt()
    }

    private fun attempt() {
        clearRetry()
        generation++
        phase = Phase.CONNECTING
        val mine = generation
        fetch { ticket, error ->
            if (mine != generation) return@fetch
            when {
                ticket == null -> {
                    IMRTCLog.w("kit", "取票失败：${error?.message}")
                    fail(if (isOnline()) IMKitFailure.SERVICE else IMKitFailure.NETWORK)
                }
                ticket.token.isEmpty() -> {
                    IMRTCLog.w("kit", "取票返回空票")
                    fail(IMKitFailure.SERVICE)
                }
                else -> login(mine, ticket.token)
            }
        }
    }

    private fun login(mine: Int, token: String) {
        // 清掉任何半截状态（上一轮没收干净的连接）；没登录时是空操作。
        engine.logout()
        engine.login(token) { error ->
            if (mine != generation) return@login
            if (error != null) {
                IMRTCLog.w("kit", "登录失败 code=${error.code} ${error.name}")
                // Kit 是唯一的重试者：login 回 2003 后连接层还在后台重连，不收就和这里的退避打架。
                engine.logout()
                fail(failureFor(error))
                return@login
            }
            phase = Phase.READY
            connected = true
            failures = 0
            haltedByKick = false
            IMRTCLog.i("kit", "已登录")
            settle(attemptWaiters, null)
        }
    }

    /** 取票的回调可能在任何线程：切回主线程再碰状态。宿主的 provider 抛了也按失败算。 */
    private fun fetch(done: (IMKitToken?, Throwable?) -> Unit) {
        try {
            provider.fetchToken { ticket, error -> mainThread { done(ticket, error) } }
        } catch (e: RuntimeException) {
            done(null, e)
        }
    }

    /** 连不上 / 超时是网络，其余看设备有没有网。 */
    private fun failureFor(error: IMRTCError): IMKitFailure = when {
        error.code == NETWORK_UNREACHABLE || error.code == SIGNALING_TIMEOUT -> IMKitFailure.NETWORK
        isOnline() -> IMKitFailure.SERVICE
        else -> IMKitFailure.NETWORK
    }

    private fun fail(kind: IMKitFailure) {
        settle(attemptWaiters, kind)
        if (haltedByKick) {
            phase = Phase.HALTED
            return
        }
        phase = Phase.WAITING
        val delay = RETRY_BACKOFF_MS[minOf(failures, RETRY_BACKOFF_MS.size - 1)]
        failures++
        val mine = generation
        cancelRetry = schedule(delay) {
            cancelRetry = null
            if (mine == generation && phase == Phase.WAITING) attempt()
        }
    }

    private fun clearRetry() {
        cancelRetry?.invoke()
        cancelRetry = null
    }

    /** 排进一张等待表，最多等 [ENSURE_TIMEOUT_MS]，超时按网络失败算。 */
    private fun wait(list: MutableList<Waiter>, done: (IMKitFailure?) -> Unit) {
        val waiter = Waiter(done)
        waiter.cancelTimeout = schedule(ENSURE_TIMEOUT_MS) {
            list.remove(waiter)
            waiter.finish(IMKitFailure.NETWORK)
        }
        list += waiter
    }

    private fun settle(list: MutableList<Waiter>, failure: IMKitFailure?) {
        val due = list.toList()
        list.clear()
        due.forEach { it.finish(failure) }
    }

    private class Waiter(private val done: (IMKitFailure?) -> Unit) {
        var cancelTimeout: (() -> Unit)? = null
        private var finished = false

        fun finish(failure: IMKitFailure?) {
            if (finished) return
            finished = true
            cancelTimeout?.invoke()
            done(failure)
        }
    }

    companion object {
        /** 失败后的退避（设计 §4）：封顶 60 s，不设次数上限，成功即归零。与 Web / iOS 同表。 */
        val RETRY_BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000, 16_000, 32_000, 60_000)
        /** [ensure] 最多等多久。 */
        const val ENSURE_TIMEOUT_MS = 10_000L
        private const val NETWORK_UNREACHABLE = 2003
        private const val SIGNALING_TIMEOUT = 2004
    }
}
