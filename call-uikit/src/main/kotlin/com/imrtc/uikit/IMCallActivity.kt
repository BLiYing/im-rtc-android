package com.imrtc.uikit

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * 通话全屏页。**独立 Activity，不入宿主导航栈**——任何界面都能被来电覆盖。
 *
 * Android 与 iOS 的差异在这里落地（交互稿 §08）：
 * **返回键 = 收进小窗**，不是挂断、也不是什么都不做。挂断只有红按钮一个入口。
 *
 * # 为什么不用系统画中画（v3.2 撤掉）
 *
 * 系统画中画那一小块窗口右上角有一颗**系统自己画的关闭（×）**，
 * `PictureInPictureParams` 没有隐藏它的口子。它按下去的语义与本 Kit 对不上：
 * 通话还活着，窗口却没了——用户看到的就是「小窗消失了但电话还在打」。
 * 无论把它接成「挂断」还是「收起」，都会有一半的人按错。
 * 所以收起统一走**应用内悬浮球**（与 iOS 一致，`IMCallOverlay`）：
 * 一个入口、一种形态、一颗自己的红色挂断。
 * 代价是离开宿主 App 就看不见那个球——这与不申请 `SYSTEM_ALERT_WINDOW` 是同一笔账
 * （CONVENTIONS §8：那是敏感权限，不该由通话 SDK 替宿主做决定）。
 *
 * **全屏、边到边**：通话页自己画背景，让系统栏透出来；标题栏与控制条按 window inset 让开，
 * 画面自己铺满（见 `IMCallView.onApplyWindowInsets`）。
 */
class IMCallActivity : Activity() {

    private lateinit var view: IMCallView
    private val observer: (IMCallViewState) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullScreen()
        keepScreenOn()
        view = IMCallView(this)
        view.actions = object : IMCallView.Actions {
            override fun onAnswer() = IMCallKit.answer()
            override fun onHangup() = IMCallKit.hangup()
            override fun onToggleMic() = IMCallKit.toggleMic()
            override fun onToggleCamera() = IMCallKit.toggleCamera()
            override fun onToggleSpeaker() = IMCallKit.toggleSpeaker()
            override fun onSwitchCamera() = IMCallKit.switchCamera()
            override fun onMinimize() = minimize()
            override fun onSwap() = IMCallKit.swap()
            override fun onInvite() = IMCallKit.showInvitePicker(this@IMCallActivity)
            override fun videoViewFor(uid: String): View? = IMCallKit.videoViewFor(this@IMCallActivity, uid)
            override fun releaseVideoView(uid: String) = IMCallKit.releaseRemoteView(uid)
            override fun reportLayer(uid: String, layer: String) = IMCallKit.reportLayer(uid, layer)
            override fun localPreviewView(): View? = IMCallKit.localPreviewView(this@IMCallActivity)
            override fun hasLocalVideo(): Boolean = IMCallKit.hasLocalVideo()
        }
        setContentView(view)
        IMCallKit.observe(observer)
    }

    override fun onDestroy() {
        IMCallKit.forget(observer)
        super.onDestroy()
    }

    /**
     * 通话中不许自动息屏。
     *
     * 少这一句的后果不是「省了点电」：**Android 的 `SurfaceView` 一息屏就把 Surface 销毁掉**，
     * 而 libwebrtc 的 `EglRenderer` 只在**下一帧到达时**才画——解锁回来时它是一块空的黑面。
     * 对端还在发帧的格子会在 33ms 内重新画上，看不出异样；**对端已经不发帧的格子就永远是纯黑**，
     * 而息屏之前那一格显示的是「冻住的最后一帧」，看起来一切正常。
     * 于是「锁屏解锁后某个人黑屏」看起来像解锁引起的，其实解锁只是**擦掉了那张遮丑的旧画面**。
     * iOS 那边 `RTCMTLVideoView` 背后是 `CAMetalLayer`，图层内容在后台不会被丢，所以看不到这一幕。
     *
     * 打着电话让屏幕自己睡过去本来就不对（所有通话 App 都不这么干），顺手把这条堵上，
     * 至少「没人碰手机、屏幕自己黑掉」这条最常见的路径不会再触发上面那一幕。
     * **它不解决「用户主动锁屏」**——那条要靠「没帧了就露头像」，见 current_task 的下一步。
     */
    private fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * 全屏、边到边，状态栏与导航栏都透出来（图标走浅色——通话页恒为深色底）。
     *
     * 只做「让内容铺到系统栏底下」，**不隐藏系统栏**：通话页是要长时间停留的界面，
     * 藏掉状态栏会让人看不到时间和电量。真正的避让在 [IMCallView.onApplyWindowInsets]。
     */
    private fun goFullScreen() {
        // 不引 androidx（Kit 是要塞进别人 App 的库，少一个依赖少一次版本冲突）：
        // API 30 起用 setDecorFitsSystemWindows，之前用它等价的那组 systemUiVisibility 位。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    /** 通话中按返回 = 收进小窗（交互稿 §08 差异 1）；接通前什么都不做——挂断请点红键。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            IMCallKit.state.phase == IMCallViewState.Phase.IDLE -> super.onBackPressed()
            IMCallKit.state.canMinimize -> minimize()
        }
    }

    private fun minimize() {
        if (!IMCallKit.config.floatingWindow) return
        IMCallKit.minimize()
    }

    @Suppress("DEPRECATION")
    private fun render(state: IMCallViewState) {
        // 收进悬浮球 = 关掉全屏页（通话照常）。**不能只是隐藏**：留着它，宿主的界面还是被盖着的。
        val shouldClose = state.phase == IMCallViewState.Phase.IDLE || state.isMinimized
        /*
         **要关页面就别再画一遍。**

         `finish()` 不是立刻消失——退出动画那两三百毫秒里这一屏还在，而复位后的状态是
         一个全默认的 `IMCallViewState`（语音、非群、IDLE），照常渲染出来的是一屏
         「语音通话中」：大头像 + 标题「通话」+ 静音/扬声器/挂断。九宫格结束时版式还会从
         GRID 跳成 AUDIO，就是用户报的「多了一个画面，闪一下看不清」。
         `IMCallView.render` 里也有同一道闸（两处都留着：那边挡住悬浮球形态下的重画）。
        */
        if (shouldClose) {
            if (!isFinishing) {
                finish()
                // 通话页是盖在宿主上的一层，收起时不该再演一段滑出动画。
                overridePendingTransition(0, 0)
            }
            return
        }
        view.render(state)
    }
}
