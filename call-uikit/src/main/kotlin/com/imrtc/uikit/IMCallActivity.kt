package com.imrtc.uikit

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager

/**
 * 通话全屏页。**独立 Activity，不入宿主导航栈**——任何界面都能被来电覆盖。
 *
 * Android 与 iOS 的两处差异在这里落地（交互稿 §08）：
 * · **返回键 = 收进小窗**，不是挂断、也不是什么都不做。挂断只有红按钮一个入口。
 * · **收起默认走系统画中画**（`enterPictureInPictureMode`，16:9，带「挂断」动作）：不用权限、跨应用可见、
 *   行为符合系统习惯。语音通话 / 不支持画中画的设备退回应用内悬浮球。按 Home 键也自动进画中画。
 *
 * **全屏、边到边**：通话页自己画背景，让系统栏透出来；标题栏按 window inset 下移，
 * 否则「对方名字 + 时长」那一行会压在状态栏的时间和电量上。
 */
class IMCallActivity : Activity() {

    private lateinit var view: IMCallView
    private val observer: (IMCallViewState) -> Unit = { render(it) }
    private var receiverRegistered = false

    private val pipActions = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(EXTRA_ACTION) == ACTION_HANGUP) IMCallKit.hangup()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullScreen()
        view = IMCallView(this)
        view.actions = object : IMCallView.Actions {
            override fun onAnswer() = IMCallKit.answer()
            override fun onHangup() = IMCallKit.hangup()
            override fun onToggleMic() = IMCallKit.toggleMic()
            override fun onToggleCamera() = IMCallKit.toggleCamera()
            override fun onToggleSpeaker() = IMCallKit.toggleSpeaker()
            override fun onMinimize() = minimize()
            override fun onSwap() = IMCallKit.swap()
            override fun onInvite() = IMCallKit.showInvitePicker(this@IMCallActivity)
            override fun videoViewFor(uid: String): View? = IMCallKit.videoViewFor(this@IMCallActivity, uid)
            override fun localPreviewView(): View? = IMCallKit.localPreviewView(this@IMCallActivity)
            override fun hasLocalVideo(): Boolean = IMCallKit.hasLocalVideo()
        }
        setContentView(view)
        IMCallKit.observe(observer)
        registerPipActions()
    }

    /**
     * 全屏、边到边，状态栏与导航栏都透出来（图标走浅色——通话页恒为深色底）。
     *
     * 只做「让内容铺到系统栏底下」，**不隐藏系统栏**：通话页是要长时间停留的界面，
     * 藏掉状态栏会让人看不到时间和电量。真正的避让在 [IMCallView.applyTopInset]。
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

    override fun onDestroy() {
        IMCallKit.forget(observer)
        if (receiverRegistered) unregisterReceiver(pipActions)
        IMCallKit.inSystemPip = false
        super.onDestroy()
    }

    /** 通话中按返回 = 收进小窗（交互稿 §08 差异 1）；接通前什么都不做——挂断请点红键。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            IMCallKit.state.phase == IMCallViewState.Phase.IDLE -> super.onBackPressed()
            IMCallKit.state.canMinimize -> minimize()
        }
    }

    /** 按 Home：视频通话自动进画中画（差异 2）。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (IMCallKit.state.canMinimize && canUseSystemPip()) enterPip()
    }

    private fun minimize() {
        if (!IMCallKit.config.floatingWindow) return
        if (canUseSystemPip()) enterPip() else IMCallKit.minimize()
    }

    /** 系统画中画：Android 8+、设备支持、且是视频通话（语音通话进画中画只剩一块黑，不如悬浮球）。 */
    private fun canUseSystemPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            IMCallKit.state.mediaType == "video"

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            // **只放挂断**。画中画里的「静音」原先点了不生效（RemoteAction 打到的是另一份进程内状态，
            // 而且那一格小窗上也没有任何反馈说明它切过去了），一个点了没反应的按钮比没有更糟。
            .setActions(listOf(remoteAction(ACTION_HANGUP, IMKitIcon.PHONE_DOWN, "挂断")))
            .build()
        IMCallKit.inSystemPip = true
        IMCallKit.minimize()
        if (!enterPictureInPictureMode(params)) {
            IMCallKit.inSystemPip = false
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        IMCallKit.inSystemPip = isInPictureInPictureMode
        view.pipMode = isInPictureInPictureMode
        // 用户点了画中画上的「展开」：回全屏。
        if (!isInPictureInPictureMode && IMCallKit.state.isMinimized) IMCallKit.expand()
    }

    private fun remoteAction(action: String, icon: IMKitIcon, label: String): RemoteAction {
        val intent = Intent(PIP_ACTION).setPackage(packageName).putExtra(EXTRA_ACTION, action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = PendingIntent.getBroadcast(this, action.hashCode(), intent, flags)
        return RemoteAction(Icon.createWithResource(this, icon.resId), label, label, pending)
    }

    private fun registerPipActions() {
        val filter = IntentFilter(PIP_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActions, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipActions, filter)
        }
        receiverRegistered = true
    }

    private fun render(state: IMCallViewState) {
        view.render(state)
        // 收进悬浮球 = 关掉全屏页（通话照常）。**不能只是隐藏**：留着它，宿主的界面还是被盖着的。
        // 在系统画中画里则不关——那个小窗就是这个 Activity。
        val shouldClose = state.phase == IMCallViewState.Phase.IDLE || (state.isMinimized && !IMCallKit.inSystemPip)
        if (shouldClose && !isFinishing) finish()
    }

    private companion object {
        const val PIP_ACTION = "com.imrtc.uikit.PIP_ACTION"
        const val EXTRA_ACTION = "action"
        const val ACTION_HANGUP = "hangup"
    }
}
