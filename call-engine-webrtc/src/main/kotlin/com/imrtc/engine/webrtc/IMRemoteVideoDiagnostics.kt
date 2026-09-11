package com.imrtc.engine.webrtc

import android.os.Handler
import android.os.SystemClock
import com.imrtc.engine.log.IMRTCLog
import org.webrtc.PeerConnection
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.Locale

/**
 * 远端画面的诊断日志：**断流之后恢复**的那几秒到底发生了什么。
 *
 * # 为什么要有它
 *
 * 2026-09-11 真机：iOS 通话中关摄像头再打开，Android 这边「画面已经出来了，又刷新一下」；
 * 把 iOS 档位降到 720p 也没减轻。候选原因界面上长得一模一样：
 *  1. 解码尺寸跳档 → `SurfaceViewRenderer` 硬件缩放重设 fixed size，surface 跟着重建；
 *  2. 恢复后中途又来一个关键帧（层上界重报 / 解码端要 PLI）——画质一跳；
 *  3. 发送端先憋着再一口气冲出来——帧间隔先长后短；
 *  4. 摄像头重启后曝光在收敛——只能看画面，日志把前三个排除掉才轮得到它。
 * 这里三路证据各打一份，和 iOS 的「上行视频采样」、服务端日志按时间对起来就能分开：
 *  - 渲染器：首帧上屏耗时、帧尺寸变化（对应 1）；
 *  - 帧间隔：恢复后 3 秒内的帧数、最长帧间隔、尺寸变了几次（对应 1、3）；
 *  - 下行统计：恢复后 1 / 3 / 6 秒各采一次 inbound-rtp（关键帧、PLI、卡顿、丢帧，对应 2、3）。
 *
 * # 有界（CONVENTIONS §6）
 *
 * 只在「断流 ≥ 1 秒后恢复」时打，同一条轨道 10 秒内最多报一轮（挡掉的次数记进下一轮）。
 * 正常通话里每条轨道只有开头那一轮；每开关一次摄像头多一轮，一轮 5 行。
 *
 * # 代价
 *
 * 每条远端视频轨道多挂一个 Java sink：libwebrtc 每帧要多跨一次 JNI、包一个 `VideoFrame`。
 * 回调里只喂 [IMFrameGapTracker] 几个整数，有事件才 post。排查结束后可以整个摘掉。
 *
 * # 线程
 *
 * 表都归主线程（与 [IMWebRTCAdapter] 的三张表同一条线）。帧回调在解码线程上；
 * 渲染器事件在渲染线程上；统计回调在 native 线程上，只拼一行日志。
 */
internal class IMRemoteVideoDiagnostics(
    private val main: Handler,
    /** 取下行 PeerConnection。注入成函数：它归 [IMPeerConnections] 管。 */
    private val subConnection: () -> PeerConnection?,
    /** track_id → uid。**只在主线程上调**：归属表归主线程管。 */
    private val ownerOf: (String) -> String?,
) {

    private class Watch(val track: VideoTrack, val sink: VideoSink)

    private val watches = LinkedHashMap<String, Watch>()

    /** track_id → 上一轮报告的时刻（elapsedRealtime），与 10 秒闸挡掉了几次。 */
    private val lastReportMs = HashMap<String, Long>()
    private val suppressed = HashMap<String, Int>()

    /** 当前窗口报过「恢复」的轨道。没报恢复的窗口也不报收尾。 */
    private val reporting = HashSet<String>()

    /** 渲染器 key（uid 或本端）→ 上一次打尺寸日志的时刻，与挡掉了几次。 */
    private val lastSizeLogMs = HashMap<String, Long>()
    private val sizeSuppressed = HashMap<String, Int>()

    /** 延时采样都挂在这个 token 上，[clear] 一把撤掉。 */
    private val token = Any()

    /** 开始盯一条远端视频轨道。**主线程调**；同一条重复调无害。 */
    fun watch(trackId: String, track: VideoTrack) {
        if (watches[trackId]?.track === track) return
        unwatch(trackId)
        val tracker = IMFrameGapTracker()
        // 热路径：每帧都走。只喂时间戳与尺寸，有事件才 post（一段断流一两次）。
        val sink = VideoSink { frame ->
            val event = tracker.onFrame(SystemClock.elapsedRealtime(), frame.rotatedWidth, frame.rotatedHeight)
            if (event != null) main.post { onEvent(trackId, event) }
        }
        runCatching { track.addSink(sink) }
        watches[trackId] = Watch(track, sink)
    }

    /** 通话结束：摘掉所有 sink、撤掉还没到点的采样。**主线程调**。 */
    fun clear() {
        watches.keys.toList().forEach(::unwatch)
        main.removeCallbacksAndMessages(token)
        lastReportMs.clear()
        suppressed.clear()
        lastSizeLogMs.clear()
        sizeSuppressed.clear()
    }

    /** 渲染器首帧上屏。渲染线程上调；每个渲染器每次 `init` 最多一次。 */
    fun firstFrameRendered(key: String, initAtMs: Long) {
        val waited = SystemClock.elapsedRealtime() - initAtMs
        main.post { IMRTCLog.i("media", "渲染器首帧上屏 key=$key 距 init=${waited}ms") }
    }

    /** 渲染器报帧尺寸变了。渲染线程上调；同一个 key 3 秒内只打一行。 */
    fun resolutionChanged(key: String, width: Int, height: Int, rotation: Int) {
        val atMs = SystemClock.elapsedRealtime()
        main.post {
            val last = lastSizeLogMs[key]
            if (last != null && atMs - last < SIZE_LOG_INTERVAL_MS) {
                sizeSuppressed[key] = (sizeSuppressed[key] ?: 0) + 1
                return@post
            }
            lastSizeLogMs[key] = atMs
            val skipped = sizeSuppressed.remove(key) ?: 0
            IMRTCLog.i(
                "media",
                "渲染器帧尺寸 key=$key ${width}x$height rot=$rotation" +
                    if (skipped > 0) "（此前 3 秒内另有 $skipped 次变化没打）" else "",
            )
        }
    }

    private fun unwatch(trackId: String) {
        val watch = watches.remove(trackId) ?: return
        // 轨道可能已经随 PeerConnection 一起 dispose 了：那时 removeSink 会抛，sink 也已经被它放掉。
        runCatching { watch.track.removeSink(watch.sink) }
        reporting.remove(trackId)
    }

    private fun onEvent(trackId: String, event: IMFrameGapTracker.Event) {
        if (!watches.containsKey(trackId)) return
        val uid = ownerOf(trackId) ?: "?"
        val window = event.closed
        if (window != null && reporting.remove(trackId)) {
            IMRTCLog.i(
                "media",
                "远端视频恢复后 ${window.spanMs}ms：uid=$uid 帧数=${window.frames} " +
                    "最长帧间隔=${window.maxFrameGapMs}ms 尺寸变化=${window.sizeChanges}次 " +
                    "${window.firstWidth}x${window.firstHeight}→${window.lastWidth}x${window.lastHeight}",
            )
        }
        if (!event.resumed) return
        val now = SystemClock.elapsedRealtime()
        val last = lastReportMs[trackId]
        if (last != null && now - last < REPORT_INTERVAL_MS) {
            suppressed[trackId] = (suppressed[trackId] ?: 0) + 1
            return
        }
        lastReportMs[trackId] = now
        reporting.add(trackId)
        val skipped = suppressed.remove(trackId) ?: 0
        val gap = if (event.gapBeforeMs < 0) "首帧" else "断流 ${event.gapBeforeMs}ms 后"
        IMRTCLog.i(
            "media",
            "远端视频出帧（$gap）uid=$uid track=$trackId" +
                if (skipped > 0) "（此前 10 秒内另有 $skipped 次恢复没报）" else "",
        )
        for (offsetMs in STATS_OFFSETS_MS) {
            main.postAtTime({ sampleInbound(trackId, uid, offsetMs) }, token, SystemClock.uptimeMillis() + offsetMs)
        }
    }

    private fun sampleInbound(trackId: String, uid: String, offsetMs: Long) {
        if (!watches.containsKey(trackId)) return
        val pc = subConnection() ?: return
        val phase = "+${offsetMs / 1000}s"
        runCatching {
            pc.getStats { report ->
                val entry = report.statsMap.values.firstOrNull {
                    it.type == "inbound-rtp" && it.members["trackIdentifier"] == trackId
                }
                if (entry == null) {
                    IMRTCLog.d("media", "下行视频采样($phase) uid=$uid：统计里没找到 track=$trackId")
                } else {
                    IMRTCLog.i("media", "下行视频采样($phase) uid=$uid " + IMStatsText.line(entry.members, INBOUND_KEYS))
                }
            }
        }.onFailure { IMRTCLog.d("media", "取下行统计失败：${it.message}") }
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 10_000L
        const val SIZE_LOG_INTERVAL_MS = 3_000L
        val STATS_OFFSETS_MS = longArrayOf(1_000L, 3_000L, 6_000L)

        /** inbound-rtp 要看的字段。缺席的不写（见 [IMStatsText]）。 */
        val INBOUND_KEYS = listOf(
            "frameWidth", "frameHeight", "framesPerSecond", "framesReceived", "framesDecoded", "framesDropped",
            "keyFramesDecoded", "freezeCount", "totalFreezesDuration", "pauseCount", "totalPausesDuration",
            "pliCount", "firCount", "nackCount", "packetsLost", "jitterBufferDelay", "jitterBufferEmittedCount",
            "decoderImplementation",
        )
    }
}

/**
 * 把一条统计的若干字段拼成 `k=v k=v`。
 *
 * **缺席的字段不写、不补 0**：「是 0」和「压根没这一项」在排查时是两回事。
 * 数字类型不定（`BigInteger` / `Long` / `Double`），浮点最多三位小数并去掉尾零。
 */
internal object IMStatsText {
    fun line(members: Map<String, Any?>, keys: List<String>): String =
        keys.mapNotNull { key -> members[key]?.let { "$key=${text(it)}" } }.joinToString(" ")

    fun text(value: Any): String = when (value) {
        is Double -> trim(value)
        is Float -> trim(value.toDouble())
        else -> value.toString()
    }

    private fun trim(value: Double): String =
        String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
}
