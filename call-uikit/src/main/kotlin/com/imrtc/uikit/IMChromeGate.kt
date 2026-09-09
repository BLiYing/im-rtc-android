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
     * 此刻允不允许自动隐藏。**排定时与触发时各查一次**（见 [schedule] 与 [hide]）。
     * 实现上是「版式是 VIDEO 且已接通」。
     */
    private val canAutoHide: () -> Boolean,
    /** 可见性变了就叫一声，界面拿它抬/放小窗。 */
    private val onChanged: (visible: Boolean) -> Unit,
) {

    var visible: Boolean = true
        private set

    /** 「该不该重排」那一段判定摘在 [IMAutoHideCountdown]，纯逻辑、有单测。 */
    private val countdown = IMAutoHideCountdown()

    private val hide = Runnable {
        countdown.clear()
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
        cancel()
        // restart = true：控制条刚显示出来，用户刚看到它，从头数满 3 秒。
        if (visible && arm) schedule(restart = true)
    }

    /**
     * 界面每走一次 render 就叫一声「该计时了」。**已经在倒计时就不要重来。**
     *
     * # 这一条是本类存在的最主要理由
     *
     * `IMCallKit.startTimer()` **每秒**推一次状态（通话时长 +1），一路走到
     * `IMCallActivity.render` → `IMCallView.render`，而 render 末尾就会调到这里。
     * 原先这里无条件 `removeCallbacks` + `postDelayed`，于是
     * **3 秒的计时每 1 秒被重置一次，永远走不完 —— 控制条从来没有自动隐藏过。**
     *
     * 这不是新引入的：拆出本类之前，`IMCallView.armAutoHide` 就是这么写的。
     * 之所以一直没人发现，是因为「点一下画面」那条路能手动收起控制条，
     * 看起来像是功能在工作。
     *
     * iOS 侧没踩到是因为它的每秒 tick **只刷标题栏**（`startTicking` 里调的是
     * `renderHeader`，不是 `render`），整个 render 只在状态真变化时才走一遍。
     * **两端的 tick 粒度不一样**，所以同一份判据在这边要多一道「别重置」。
     */
    fun armAutoHide() = schedule(restart = false)

    /** 撤掉待触发的那一下。宿主 View 脱离窗口时必须调（CONVENTIONS §5）。 */
    fun cancel() {
        countdown.clear()
        main.removeCallbacks(hide)
    }

    /**
     * 把定时器排上（要不要排由 [IMAutoHideCountdown] 判）。
     *
     * **排定时就查 [canAutoHide] 是故意的**，不是照搬 iOS：iOS 那边无条件排、
     * 只在触发时查，于是在 CONNECTING 阶段排下的定时器可能在**接通后不到 3 秒**就触发，
     * 而规范 §07 说的是「接通后 3s」。phase 从 CONNECTING 变 CONNECTED 本身就会走一次
     * render、再 arm 一次，所以这里挡掉不会漏。（iOS 已对齐到这一版。）
     */
    private fun schedule(restart: Boolean) {
        if (!countdown.arm(canAutoHide(), restart)) return
        main.removeCallbacks(hide)
        main.postDelayed(hide, IMKitTheme.AUTO_HIDE_MS)
    }

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
