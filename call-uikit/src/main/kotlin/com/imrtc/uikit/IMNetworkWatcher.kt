package com.imrtc.uikit

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.imrtc.engine.log.IMRTCLog

/**
 * 监听系统默认网络，**换成另一个网络**时告诉 Engine（`IMCallEngine.notifyNetworkChanged`）。
 *
 * 2026-09-18 20:45 真机 OPPO/ColorOS：Wi-Fi 自己断开重连 1.5 秒，换了随机 MAC 与 IP，
 * 信令还在按退避 30 秒一档空等，服务端 30 秒恢复窗口先到期，通话被结束。Engine 拿到这条
 * 信号就会清零退避立刻重连（连着的先探死活），规则在 Engine 的 `IMSignalConnection`。
 *
 * 只要 `ACCESS_NETWORK_STATE`（普通权限，装上即有，call-engine-webrtc 已声明）。
 * 注册失败（个别 ROM 限制每个 App 的回调数）只记一条日志，不影响通话——退回老的退避节奏。
 */
internal class IMNetworkWatcher(private val onChanged: () -> Unit) {

    private val tracker = IMDefaultNetworkTracker<Network>()
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!tracker.onAvailable(network)) return
                IMRTCLog.i("kit", "系统默认网络换了 → 通知 Engine 立即重连")
                onChanged()
            }

            override fun onLost(network: Network) = tracker.onLost(network)
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: RuntimeException) {
            IMRTCLog.w("kit", "监听网络变化没注册上，断网后按原退避重连：${e.message}")
            return
        }
        manager = cm
        callback = cb
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            manager?.unregisterNetworkCallback(cb)
        } catch (e: RuntimeException) {
            IMRTCLog.w("kit", "注销网络监听失败：${e.message}")
        }
        manager = null
        tracker.reset()
    }
}

/**
 * 「默认网络是不是换成了另一个」的判定，与 Android 类型无关，单测直接喂。
 *
 * - 注册时系统会先回调一次**当前**网络——那不是变化。
 * - 同一个网络重复 `onAvailable`（有的 ROM 会）不是变化。
 * - 丢了再回来（哪怕是同一个 handle）算变化：中间断过，旧连接大概率已经死了。
 *
 * 回调在 ConnectivityManager 自己的线程上来，所以加锁。
 */
internal class IMDefaultNetworkTracker<T : Any> {

    private var seen = false
    private var current: T? = null

    /** @return 这一次算不算「换了网络」。 */
    @Synchronized
    fun onAvailable(network: T): Boolean {
        val changed = seen && network != current
        seen = true
        current = network
        return changed
    }

    @Synchronized
    fun onLost(network: T) {
        if (network == current) current = null
    }

    @Synchronized
    fun reset() {
        seen = false
        current = null
    }
}
