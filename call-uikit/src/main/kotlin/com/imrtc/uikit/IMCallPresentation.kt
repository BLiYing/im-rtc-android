package com.imrtc.uikit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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

    /** 上一次 [present] 的时刻（elapsedRealtime），给 [IMPresentRules] 限连环拉起。 */
    private var lastPresentAt = 0L

    /** Kit 停掉时回到初始。 */
    fun reset() {
        mode = Mode.HIDDEN
        bannerExpanded = false
        lastPresentAt = 0L
    }

    /**
     * 按当前状态决定用哪种形态，并把上一种收掉。**每次状态更新都会走一遍**，所以它必须便宜且幂等。
     *
     * @param hostResumed 这一次是不是因为「宿主页面回到前台」触发的（[IMActivityTracker.onHostResumed]）。
     *   只有这一路才可能发现「形态是全屏、通话页却不在前台」，见 [IMPresentRules]。
     */
    fun apply(current: IMCallViewState, context: Context?, hostResumed: Boolean = false) {
        if (current.phase == IMCallViewState.Phase.IDLE) bannerExpanded = false
        val host = IMActivityTracker.foreground()
        val wanted = desiredMode(current, host)
        val changed = wanted != mode
        mode = wanted
        when (wanted) {
            Mode.HIDDEN -> if (changed) overlay.detach()
            Mode.FULLSCREEN -> {
                overlay.detach()
                if (changed || lostFullscreen(current, host, hostResumed)) present(context)
            }
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

    /**
     * 形态没变、还是全屏，但宿主页面回到了前台：通话页被系统收走了（或压根没回到前台），补拉一次。
     * 留一条日志：这是「界面丢了但通话还在」的唯一现场，下次再出问题要靠它对时间。
     */
    private fun lostFullscreen(current: IMCallViewState, host: Activity?, hostResumed: Boolean): Boolean {
        val sinceLast = SystemClock.elapsedRealtime() - lastPresentAt
        val lost = IMPresentRules.shouldRepresent(
            phase = current.phase,
            hostResumed = hostResumed,
            hostVisible = host != null && host !is IMPermissionActivity,
            sinceLastPresentMs = sinceLast,
        )
        if (lost) IMRTCLog.w("kit", "宿主回到前台但通话页不在，重新拉起通话页（phase=${current.phase} host=${host?.javaClass?.simpleName}）")
        return lost
    }

    private fun present(context: Context?) {
        context ?: return
        lastPresentAt = SystemClock.elapsedRealtime()
        context.startActivity(Intent(context, IMCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
