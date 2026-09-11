package com.imrtc.engine.webrtc

import android.os.Handler
import android.os.SystemClock
import com.imrtc.engine.log.IMRTCLog
import org.webrtc.EglRenderer
import org.webrtc.SurfaceViewRenderer

/**
 * 对端摄像头**重开**之后，等新画面真的画到屏上，再报一次 `onFirstVideoFrame`。
 *
 * # 为什么 `onFirstFrameRendered` 不够
 *
 * 它**每次 `init` 只来一次**，而渲染器按 uid 整通复用（`IMCallKit.remoteViews`）——对端关了摄像头再开，
 * 这边没有任何「新画面到了」的信号。Kit 只好按 `onUserVideoAvailable(true)`（`room.track_muted`）揭示格子，
 * 可那条信令比新画面早 450–900ms（真机 2026-09-11 19:17，日志 `远端视频出帧（断流 Xms 后）`）。
 * 这段时间 `SurfaceView` 上是**关摄像头之前的最后一帧**：父容器 INVISIBLE 不销毁 Surface，
 * EglRenderer 也不会自己清屏——于是「画面出来了，又刷新一下」。
 *
 * # 怎么做
 *
 * Engine 抛完 `onUserVideoAvailable(uid, true)` 之后调 [arm]：给那块渲染器挂一个**一次性**帧监听。
 * `addFrameListener(…, 0f)` 在 `swapBuffers` 之后才回调，scale 0 不拷像素；没有 Surface 丢掉的帧不回调。
 * `init` 之后的首帧（[firstFrameRendered]）走同一个去重，同一帧两条路只报一次。
 *
 * [awaiting] **只在主线程上碰**；两个回调都在渲染线程，先 post 回来。
 */
internal class IMFirstFrameGate(
    private val main: Handler,
    private val emit: (uid: String) -> Unit,
) {

    /** uid → 开始等的时刻（elapsedRealtime），只为日志里的等待时长。 */
    private val awaiting = HashMap<String, Long>()

    /** 主线程。`renderer` 为空（格子还没摆）就只记下，由之后 `init` 的首帧兑现。 */
    fun arm(uid: String, renderer: SurfaceViewRenderer?) {
        if (uid !in awaiting) awaiting[uid] = SystemClock.elapsedRealtime()
        renderer ?: return
        // 已经 release 的渲染器上挂监听是静默空操作；连着开关几次会挂上几个，settle 的去重兜着。
        runCatching { renderer.addFrameListener(EglRenderer.FrameListener { main.post { settle(uid) } }, 0f) }
    }

    /** 渲染线程：`init` 之后的第一帧。**照旧必报**（原有契约），顺带划掉等待。 */
    fun firstFrameRendered(uid: String) {
        main.post {
            awaiting.remove(uid)
            emit(uid)
        }
    }

    /** 主线程，通话结束时。 */
    fun clear() = awaiting.clear()

    private fun settle(uid: String) {
        val since = awaiting.remove(uid) ?: return
        // 一次开摄像头一行，量级跟着用户操作走。
        IMRTCLog.i("media", "远端画面重开后新帧上屏 key=$uid waitMs=${SystemClock.elapsedRealtime() - since}")
        emit(uid)
    }
}
