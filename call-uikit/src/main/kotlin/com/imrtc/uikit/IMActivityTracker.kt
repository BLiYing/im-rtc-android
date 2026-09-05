package com.imrtc.uikit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * 记住宿主当前在前台的那个 Activity。
 *
 * 横幅与悬浮球都是**应用内浮层**——挂在当前 Activity 的 `android.R.id.content` 上，
 * 而不是申请 `SYSTEM_ALERT_WINDOW` 开系统级悬浮窗（CONVENTIONS §8：那是敏感权限，
 * 会影响宿主上架，不该由通话 SDK 替宿主做这个决定）。要挂就得先知道挂到哪。
 *
 * **拿弱引用**：Kit 是个进程级单例，强引用住一个 Activity 就是教科书级的内存泄漏。
 *
 * [IMCallActivity] 自己不算——它是 Kit 的全屏页，往它身上挂横幅等于横幅盖着全屏页，
 * 而这两种形态本来就是互斥的。
 */
internal object IMActivityTracker : Application.ActivityLifecycleCallbacks {

    private var current: WeakReference<Activity>? = null
    private var installed = false

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    /** 当前前台 Activity；没有（App 在后台、或者宿主没走标准生命周期）就返回 null。 */
    fun foreground(): Activity? {
        val activity = current?.get() ?: return null
        return if (activity.isFinishing || activity.isDestroyed) null else activity
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is IMCallActivity) return
        current = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (current?.get() === activity) current = null
    }
}
