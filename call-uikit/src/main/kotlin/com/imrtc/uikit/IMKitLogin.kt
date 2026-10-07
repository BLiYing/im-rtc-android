package com.imrtc.uikit

import android.content.Context
import android.net.ConnectivityManager
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.IMRTCError
import com.imrtc.engine.log.IMRTCLog

/**
 * Kit 取票登录的接线（server `docs/design/KIT_TOKEN_PROVIDER_DESIGN.md`）：把 [IMKitSession] 接到
 * 主线程、系统联网状态与真 Engine 上。配了 [IMCallKitConfig.tokenProvider] 才有会话；
 * **没配就什么都不做**——宿主自己管登录，行为与 2.1.x 一致。
 *
 * 拆成单独文件是不想让 `IMCallKit.kt` 再长（体量红线，CONVENTIONS §2）。全部在主线程上用。
 */
internal object IMKitLogin {

    private var session: IMKitSession? = null

    fun start(context: Context, engine: IMCallEngine, provider: IMTokenProvider?) {
        stop()
        provider ?: return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        session = IMKitSession(
            engine = EngineAdapter(engine),
            provider = provider,
            schedule = { delayMs, task ->
                val runnable = Runnable(task)
                IMCallKit.main.postDelayed(runnable, delayMs)
                ({ IMCallKit.main.removeCallbacks(runnable) })
            },
            isOnline = { connectivity?.activeNetwork != null },
            mainThread = { task -> IMCallKit.main.post(task) },
        ).also { it.start() }
    }

    /** 停会话（会登出 Engine）。没有会话时空操作。 */
    fun stop() {
        session?.stop()
        session = null
    }

    fun onConnected() = session?.onConnected()
    fun onDisconnected() = session?.onDisconnected()
    fun onKickedOut(reason: IMKickedOutReason) = session?.onKickedOut(reason)
    fun onTokenWillExpire() = session?.onTokenWillExpire()
    /** 网络换了 / 回到前台：在退避里等着的立刻再试。 */
    fun onNetworkRestored() = session?.onNetworkRestored()

    /** [IMCallKit.ensureReady] 的实现。没配 tokenProvider 恒为 true。 */
    fun ensureReady(callback: IMReadyCallback) {
        val current = session ?: return callback.onResult(true)
        current.ensure { failure -> callback.onResult(failure == null) }
    }

    /**
     * 发帧之前确保已登录（设计 §6）：等待期间界面照常是「正在呼叫…」/「接通中…」。
     * 登不上就把 [screenStillThere] 那一屏收起（还在的话；`null` = 还没出界面）、弹一句提示，不调 [onReady]。
     */
    fun thenReady(screenStillThere: (() -> Boolean)?, onReady: () -> Unit) {
        val current = session ?: return onReady()
        current.ensure { failure ->
            if (failure == null) {
                onReady()
                return@ensure
            }
            IMRTCLog.w("kit", "通话服务没登上，不发帧 failure=$failure")
            if (screenStillThere?.invoke() == true) IMCallKit.update(IMCallViewReducer.reset())
            IMBusyGuard.toast(IMText.t(failure.hintKey))
        }
    }

    private class EngineAdapter(private val engine: IMCallEngine) : IMSessionEngine {
        override fun login(token: String, done: (IMRTCError?) -> Unit) {
            engine.login(token) { _, error -> done(error) }
        }

        override fun logout() = engine.logout()
        override fun updateToken(token: String, expiresAtMs: Long) = engine.updateToken(token, expiresAtMs)
        override fun notifyNetworkChanged() = engine.notifyNetworkChanged()
    }
}
