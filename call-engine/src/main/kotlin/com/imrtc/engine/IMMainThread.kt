package com.imrtc.engine

import android.os.Handler
import android.os.Looper

/**
 * 「把回调切到主线程」这件事的抽象。
 *
 * 抽出来只为一件事：**门面能在纯 JVM 单测里跑**。`Looper.getMainLooper()` 在没有
 * Robolectric 的单测里会抛「not mocked」，而为了测一条 if 分支就把 Robolectric 拉进来，
 * 等于让「跑一次单测」重新变慢——那正是 CONVENTIONS §1 想避免的。
 */
internal fun interface IMMainThread {
    fun run(block: () -> Unit)
}

/** 真实现：Android 主线程 Handler。已经在主线程上就直接跑，省一次 post。 */
internal class IMAndroidMainThread : IMMainThread {
    private val handler = Handler(Looper.getMainLooper())

    override fun run(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }
}
