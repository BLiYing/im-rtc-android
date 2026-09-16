package com.imrtc.engine.webrtc

import com.imrtc.engine.log.IMRTCLog
import org.webrtc.CameraVideoCapturer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一支 capturer 的摄像头事件：**打不开 / 中途被抢走要有回音**（静默失败审计 android #1）。
 *
 * 原先 `createCapturer(name, null)` 传的是 null：摄像头被别的 App 占着、HAL 打不开、
 * 通话中被系统相机抢走，这些都在 `startCapture` 之后**异步**失败，而 source 已经缓存下来——
 * 按钮亮着、一帧画面都没有、日志里什么都没有，之后再点「开摄像头」拿到的还是这个死 source。
 *
 * 这里只做两件事：失败（`onCameraError` / `onCameraDisconnected`）记一笔 [failed] 并回调 [onLost]；
 * 下次要用摄像头的人先 [consumeFailure]，拿到 true 就把这支 capturer 重起一遍，而不是照用死 source。
 * 卡帧（`onCameraFreezed`）只记日志：libwebrtc 的看门狗在帧恢复前会反复报，不能当失败收场。
 *
 * **一支 capturer 一个实例**：停采集时 [retire]，之后迟到的回调（关摄像头时的 error 之类）一律不算。
 */
internal class IMCameraEvents(private val onLost: (String) -> Unit) : CameraVideoCapturer.CameraEventsHandler {
    private val retired = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    /** 停采集时调：此后的事件与这一支 capturer 的新主人无关。 */
    fun retire() = retired.set(true)

    /** 上一次失败还没被处理过就返回 true，并清掉标记（只认一次）。 */
    fun consumeFailure(): Boolean = failed.getAndSet(false)

    override fun onCameraError(errorDescription: String) = lost("error: $errorDescription")

    override fun onCameraDisconnected() = lost("disconnected")

    override fun onCameraFreezed(errorDescription: String) {
        if (!retired.get()) IMRTCLog.w("media", "摄像头卡帧：$errorDescription")
    }

    override fun onCameraOpening(cameraName: String) {
        if (!retired.get()) IMRTCLog.i("media", "摄像头打开中：$cameraName")
    }

    override fun onFirstFrameAvailable() {
        if (!retired.get()) IMRTCLog.i("media", "摄像头出第一帧")
    }

    override fun onCameraClosed() = Unit

    private fun lost(reason: String) {
        if (retired.get()) return
        failed.set(true)
        IMRTCLog.e("media", "摄像头不可用（$reason），下次打开时重起采集")
        onLost(reason)
    }
}
