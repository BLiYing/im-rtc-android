package com.imrtc.uikit

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.imrtc.engine.log.IMRTCLog

/**
 * 应用内浮层：把来电横幅 / 悬浮球挂到宿主当前前台 Activity 的内容视图上。
 *
 * **不申请 `SYSTEM_ALERT_WINDOW`**（CONVENTIONS §8）。代价说在前面：
 * **离开宿主 App，横幅与悬浮球就看不见了**——通话本身不受影响，回到 App 它们还在。
 * 想要真正的系统级悬浮窗，那是宿主自己去申请权限、再拿 Engine 回调自己画。
 *
 * 挂载点是 `android.R.id.content`：宿主的 setContentView 就挂在它下面，
 * 往它上面再叠一层就一定盖在宿主内容之上，且不需要宿主改任何一行布局。
 */
internal class IMCallOverlay {

    /** 当前挂着的那个视图，以及它挂在谁身上——**摘的时候要摘对宿主**。 */
    private var view: View? = null
    private var host: ViewGroup? = null

    /**
     * 把 [make] 造出来的视图挂到前台 Activity 上；已经挂着同一类视图就只刷新。
     *
     * @return 挂上去（或已经挂着）的那个视图；没有前台 Activity 时返回 null，
     *   调用方据此退回全屏 Activity——**App 在后台时没有可挂的地方，这不是错误**。
     */
    fun <T : View> mount(
        activity: Activity?,
        type: Class<T>,
        make: (Activity) -> T,
        params: (Activity) -> FrameLayout.LayoutParams,
    ): T? {
        val target = activity ?: run { detach(); return null }
        val content = target.findViewById<ViewGroup>(android.R.id.content) ?: return null

        val existing = view
        // 同一个宿主、同一类视图：留着别重建，重建会丢掉悬浮球被拖到的位置。
        if (existing != null && host === content && type.isInstance(existing)) {
            @Suppress("UNCHECKED_CAST")
            return existing as T
        }

        detach()
        val fresh = make(target)
        content.addView(fresh, params(target))
        view = fresh
        host = content
        IMRTCLog.i("kit", "浮层已挂载：${type.simpleName}")
        return fresh
    }

    /** 摘掉浮层。**必须可重入**：形态切换、Activity 销毁、通话结束会先后调到。 */
    fun detach() {
        val current = view ?: return
        (current.parent as? ViewGroup)?.removeView(current)
        view = null
        host = null
    }

    companion object {
        /**
         * 横幅：顶部横向铺开，**让开状态栏**——不让的话它会压在时间与信号图标上。
         */
        fun bannerParams(activity: Activity): FrameLayout.LayoutParams {
            val resources = activity.resources
            val density = resources.displayMetrics.density
            val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val topInset = if (statusBarId > 0) {
                resources.getDimensionPixelSize(statusBarId)
            } else {
                (24 * density).toInt()
            }
            return FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP
                leftMargin = (12 * density).toInt()
                rightMargin = (12 * density).toInt()
                topMargin = topInset + (8 * density).toInt()
            }
        }
    }
}
