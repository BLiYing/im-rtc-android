package com.imrtc.uikit

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.imrtc.engine.log.IMRTCLog

/**
 * 呈现形态：全屏页 / 来电横幅 / 悬浮球 / 系统画中画——**按当前状态挑一种，把上一种收掉**。
 *
 * 从 [IMCallKit] 拆出来（体量红线，CONVENTIONS §2）。边界是「状态 → 挂哪种界面」：
 * 它不改视图状态，界面上的点击照旧回到 [IMCallKit] 的动作上。
 */
internal class IMCallPresentation(private val overlay: IMCallOverlay) {

    private enum class Mode { HIDDEN, BANNER, BUBBLE, FULLSCREEN }

    /** 横幅已经被用户点开过（或 5s 到点自动升级）。**它必须独立于 [IMCallViewState]**，否则界面来回跳。 */
    var bannerExpanded = false
    private var mode = Mode.HIDDEN

    /** Kit 停掉时回到初始。 */
    fun reset() {
        mode = Mode.HIDDEN
        bannerExpanded = false
    }

    /** 按当前状态决定用哪种形态，并把上一种收掉。**每次状态更新都会走一遍**，所以它必须便宜且幂等。 */
    fun apply(current: IMCallViewState, context: Context?) {
        if (current.phase == IMCallViewState.Phase.IDLE) bannerExpanded = false
        val host = IMActivityTracker.foreground()
        val wanted = desiredMode(current, host)
        val changed = wanted != mode
        mode = wanted
        when (wanted) {
            Mode.HIDDEN -> if (changed) overlay.detach()
            Mode.FULLSCREEN -> { overlay.detach(); if (changed) present(context) }
            Mode.BANNER -> mountBanner(host, current)
            Mode.BUBBLE -> mountBubble(host, current)
        }
        if (changed) IMRTCLog.i("kit", "通话界面形态：${wanted.name.lowercase()}")
    }

    /**
     * 形态判定。顺序有讲究，**小窗优先于横幅**：来电时不可能是小窗（还没接通），反过来接通后也不该再出横幅。
     * 在系统画中画里就是 FULLSCREEN（那个 Activity 还活着），别往宿主界面上再挂一个悬浮球。
     */
    private fun desiredMode(current: IMCallViewState, host: Activity?): Mode = when {
        current.phase == IMCallViewState.Phase.IDLE -> Mode.HIDDEN
        current.isMinimized && IMCallKit.config.floatingWindow -> if (host != null) Mode.BUBBLE else Mode.HIDDEN
        current.phase == IMCallViewState.Phase.INCOMING && IMCallKit.config.bannerFirst && !bannerExpanded && host != null -> Mode.BANNER
        else -> Mode.FULLSCREEN
    }

    private fun mountBanner(host: Activity?, current: IMCallViewState) {
        val banner = overlay.mount(
            host, IMIncomingBanner::class.java,
            { activity ->
                IMIncomingBanner(activity).apply {
                    onAccept = { IMCallKit.answer() }
                    onReject = { IMCallKit.hangup() }
                    onExpand = { IMCallKit.expand() }
                    onToggleCamera = { IMCallKit.toggleCamera() }
                }
            },
            { activity -> IMCallOverlay.bannerParams(activity) },
        )
        banner?.render(current)
    }

    private fun mountBubble(host: Activity?, current: IMCallViewState) {
        val bubble = overlay.mount(
            host, IMFloatingBubble::class.java,
            { activity -> IMFloatingBubble(activity).apply { onExpand = { IMCallKit.expand() }; onHangup = { IMCallKit.hangup() } } },
            { activity -> IMFloatingBubble.initialParams(activity) },
        )
        bubble?.render(current)
        // 视频通话的悬浮球放主讲人的缩略画面（规范 §06）。
        if (bubble != null && host != null && current.mediaType == "video") {
            // **只在远端成员里挑**：speakingUid 可能是本端自己，见 [videoSpeakerUid]。
            val speaker = current.videoSpeakerUid()
            bubble.setVideoView(if (speaker.isEmpty()) null else IMCallKit.videoViewFor(host, speaker))
        }
    }

    private fun present(context: Context?) {
        context ?: return
        context.startActivity(Intent(context, IMCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
