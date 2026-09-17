package com.imrtc.uikit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

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

    /**
     * App 前后台判定。**通话页也算在内**（与 [current] 不同）：判「App 在不在前台」要看全部界面，
     * 而通话页恰恰是通话中最常在前台的那一个。
     */
    private val foregroundState = IMForegroundState()

    /** App 前后台切换。通话页据此暂停 / 恢复本端视频（交互稿 §03）。 */
    var onForegroundChanged: ((Boolean) -> Unit)? = null

    /**
     * 宿主的某个页面 resumed 了（通话页不算）。**悬浮球要靠它才挂得上**：点「收进小窗」时通话页还在前台、
     * 宿主页面还没 resume，形态判定拿不到宿主只能先 hidden；通话中每秒计时会再判一次所以看不出来，
     * 拨出中没有计时，不补这一下球就一直不出来（2026-09-17 PKD130 真机）。
     */
    var onHostResumed: (() -> Unit)? = null

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
        onHostResumed?.invoke()
    }

    override fun onActivityPaused(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        if (foregroundState.started(activity)) onForegroundChanged?.invoke(true)
    }

    override fun onActivityStopped(activity: Activity) {
        if (foregroundState.stopped(activity)) onForegroundChanged?.invoke(false)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    // onStop 一定先于 onDestroy，前台记账在那时就清了，这里只用管 current。
    override fun onActivityDestroyed(activity: Activity) {
        if (current?.get() === activity) current = null
    }
}

/**
 * 「App 在不在前台」的纯逻辑：**只认我们亲眼看见 started 过的界面**。
 *
 * 为什么不是一个计数器（这就是 2026-09-06 那个「对端看不到我的画面」的根）：
 * 生命周期钩子是在 `IMCallKit.start` 里装的，而宿主是**登录成功之后**才调它的
 * （Demo 在 `DemoSession.onLoggedIn`）——那时宿主首页早就 `onStart` 过了，
 * 计数器压根没数到它。于是：
 *
 * ```
 * 装钩子（count=0，实际首页在前台）
 * 接听 → IMCallActivity.onStart → count=1 → 回调 foreground(true)（无害）
 *       ~0.5s 后开场动画放完 → 首页 onStop → count=0 → 回调 foreground(false)
 *       → IMCallKit 以为切后台了，把摄像头 mute 掉
 * ```
 *
 * 症状极具迷惑性：**本机界面一切正常**——「关摄像头」按钮还亮着、本端预览也还在画，
 * 因为 `cameraOn` 这个界面状态压根没被改，被关掉的只是上行轨道；
 * 坏的是**对端**，它收到 `room.track_muted{video}` 后只显示头像。
 * 真机上 100% 复现，且「进一次后台再回来」就自愈（那一轮把首页数进去了），
 * 于是它看起来还很随机。
 *
 * 所以这里改成记**集合**，并且**没见过它 start 就不认它的 stop**：
 * 装钩子之前就在前台的那个界面退下去时，不该被算成「整个 App 进后台」。
 * 代价是「装钩子后用户第一次按 Home」这一次不报后台——那时还没有通话（phase=IDLE），
 * 收不到也没有任何影响，而下一次 onStart 就把它数进来了，之后永远准。
 *
 * 用 [Any] 而不是 Activity 作键：这样它就是纯 JVM 逻辑，不用 Robolectric 也能测
 * （CONVENTIONS §1「需要平台能力就注入抽象」）。
 */
internal class IMForegroundState {

    /** 弱引用持有：Activity 被回收了不该因为这个集合而泄漏。 */
    private val started: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    /** 登记一次 onStart。@return 是否**刚从后台回到前台**。 */
    fun started(key: Any): Boolean {
        val wasBackground = started.isEmpty()
        started.add(key)
        return wasBackground
    }

    /** 登记一次 onStop。@return 是否**刚从前台进了后台**。 */
    fun stopped(key: Any): Boolean {
        if (!started.remove(key)) return false
        return started.isEmpty()
    }
}
