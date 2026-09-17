package com.imrtc.uikit

import com.imrtc.engine.IMCallEngine

/**
 * App 切到后台 / 回到前台（交互稿 §03）。从 [IMCallKit] 拆出来是体量红线（CONVENTIONS §2）。
 *
 * **后台不允许继续采集摄像头**（Android 从 9 开始就是这条规矩，各家 ROM 更严），
 * 对端看到的就是一片黑——比看到头像糟糕得多。所以进后台把摄像头轨道 mute 掉，
 * 对端收到「摄像头已关闭」、看到头像；回前台**恢复到用户原来的选择**：
 * 他进后台前本来就关着摄像头，回前台不要替他打开。与 iOS 的 `IMCallController` 同一条规则。
 *
 * 进系统画中画不算切后台：那时候采集照跑，画面就在那一小块窗口里。
 *
 * **不管是否在通话都要喂给 Engine**：`setAppForeground` 只影响信令重连节奏
 * （见 `IMSignalConnection` 类注释「后台重连节奏」），跟下面摄像头那段是两件事——
 * 通话中被前台服务托着、App 本身被切到后台（比如通话中按了 Home）也算「后台」，
 * 一样要按后台节奏重连，这样反而比前台的退避（最长 30s）更快够上服务端 5s 的等待窗口。
 *
 * 只在主线程上用（[IMActivityTracker] 的回调就在主线程）。
 */
internal class IMBackgroundCamera {

    /** 摄像头是**因为切后台**才关的——只有这种情况回前台才自动打开。 */
    private var pausedByBackground = false

    fun onForegroundChanged(instance: IMCallEngine, state: IMCallViewState, foreground: Boolean) {
        instance.setAppForeground(foreground)
        if (!foreground) {
            if (state.phase == IMCallViewState.Phase.IDLE || !state.cameraOn) return
            pausedByBackground = true
            instance.closeCamera()
            return
        }
        if (!pausedByBackground) return
        pausedByBackground = false
        if (state.cameraOn) instance.openCamera()
    }
}
