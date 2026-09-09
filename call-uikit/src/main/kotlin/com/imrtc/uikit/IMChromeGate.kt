package com.imrtc.uikit

import android.os.Handler
import android.view.View

/**
 * 控制条与标题栏的「3s 后淡出、任意触摸恢复」（规范 §07）。
 *
 * 从 [IMCallView] 里抽出来的协作对象（CONVENTIONS §2）：那边已经顶着 600 行红线，
 * 而这一小块——淡入淡出、拦触摸、一个定时器——是个自洽的关注点，拆出来正好。
 *
 * 对应 iOS 的 `IMCallOverlayViewController` 里 `setChrome` / `armAutoHide` 那一段。
 * **两端的判据要一致**，所以下面两条分歧都在这里写清楚了，别再各自漂。
 */
internal class IMChromeGate(
    private val main: Handler,
    /** 要跟着淡入淡出的（标题栏、控制条、控制条底下那层渐变）。 */
    private val faded: List<View>,
    /**
     * 除了淡出、还要**真的拦住触摸**的那些（标题栏、控制条）。
     *
     * 渐变层不在这份名单里：它不可点击，本来就不吃触摸，而且可见性归
     * [IMCallView] 的 render 管（VIDEO 且未结束才 VISIBLE），这里插手会打架。
     */
    private val gated: List<View>,
    /**
     * 此刻允不允许自动隐藏。**排定时与触发时各查一次**，见 [arm]。
     * 实现上是「版式是 VIDEO 且已接通」。
     */
    private val canAutoHide: () -> Boolean,
    /** 可见性变了就叫一声，界面拿它抬/放小窗。 */
    private val onChanged: (visible: Boolean) -> Unit,
) {

    var visible: Boolean = true
        private set

    private val hide = Runnable {
        /*
         **守卫必须在触发时再查一遍，不能只在排定时查。**

         `IMCallView.render()` 在 ENDED 时提前 return（走不到末尾那行 set/arm），
         所以接通期排下的这一下**不会被撤**。没有这道守卫它就会在结束画面上
         把标题栏一起淡掉。iOS 侧正是靠触发时复查 layout 与 phase 挡住了同一种情况。
        */
        if (canAutoHide()) set(visible = false)
    }

    /**
     * 显示 / 隐藏。`arm=false` 用于「非 VIDEO 版式下永远可见」那条路——
     * 摆出来但不要计时。
     */
    fun set(visible: Boolean, arm: Boolean = true) {
        this.visible = visible
        if (visible) gate(open = true) // 淡入前先放开，否则整段淡入都看不见
        for (view in faded) {
            view.animate().alpha(if (visible) 1f else 0f).setDuration(IMKitTheme.FADE_MS)
                // **读字段而不是读形参**：淡出中途被一次淡入打断时，这一下必须是空操作。
                .withEndAction { if (!this.visible) gate(open = false) }
                .start()
        }
        onChanged(visible)
        main.removeCallbacks(hide)
        if (visible && arm) armAutoHide()
    }

    /**
     * 排定 3s 后隐藏。
     *
     * **排定时就查 [canAutoHide] 是故意的**，不是照搬 iOS：iOS 那边无条件排、
     * 只在触发时查，于是在 CONNECTING 阶段排下的定时器可能在**接通后不到 3 秒**就触发，
     * 而规范 §07 说的是「接通后 3s」。phase 从 CONNECTING 变 CONNECTED 本身就会走一次
     * render、再 arm 一次，所以这里挡掉不会漏。（iOS 已对齐到这一版。）
     */
    fun armAutoHide() {
        main.removeCallbacks(hide)
        if (canAutoHide()) main.postDelayed(hide, IMKitTheme.AUTO_HIDE_MS)
    }

    /** 撤掉待触发的那一下。宿主 View 脱离窗口时必须调（CONVENTIONS §5）。 */
    fun cancel() = main.removeCallbacks(hide)

    /**
     * 收起后**必须真的收不到触摸**。
     *
     * 原先 [IMCallView] 里只有一行 `controls.isEnabled = visible`，而 Android 上对一个
     * ViewGroup 设 `isEnabled = false` **既不传给子 View、也不拦触摸派发**
     * （[IMControlButton] 自己 `isClickable = true`，而且那边没有任何
     * `dispatchTouchEvent` / `onInterceptTouchEvent`）。于是淡到 alpha=0 之后，
     * 静音 / 摄像头 / 扬声器 / 翻转 / **挂断**五颗按钮全都还能点；又因为控制条是最后
     * addView 的、压在中间那段上面，「点一下把控制条叫回来」那一下会先被看不见的按钮吃掉。
     * 真机症状是**点屏幕底部，画面毫无反应，但自己被静音了、或者直接挂断了**。
     * 标题栏更是一行都没管，小窗与加人两颗同理。
     *
     * `INVISIBLE` 的 View 不绘制、也不参与触摸派发，等价于 iOS 那边一行
     * `isUserInteractionEnabled = false`；而且触摸会落回下面那段，
     * 顺带满足规范 §07 的「任意触摸恢复」。
     */
    private fun gate(open: Boolean) {
        val gatedVisibility = if (open) View.VISIBLE else View.INVISIBLE
        for (view in gated) view.visibility = gatedVisibility
    }
}
