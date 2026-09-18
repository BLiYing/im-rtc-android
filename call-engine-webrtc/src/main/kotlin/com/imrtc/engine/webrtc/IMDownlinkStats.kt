package com.imrtc.engine.webrtc

import android.os.Handler
import com.imrtc.engine.log.IMRTCLog
import org.webrtc.PeerConnection
import org.webrtc.RTCStats

/**
 * 下行解码统计：**对方的包到底有没有到、到了能不能解出帧来**。
 *
 * # 为什么非要有它
 *
 * 两端一直只有 [IMUplinkStats]（上行），下行一个数都没有。于是「某一格永远是头像」
 * 这类问题只能靠**反推**，而反推分不开三件事，三件的修法完全不同：
 *
 *  1. 包压根没来（服务端没转 / 被中间网络丢光）；
 *  2. 包来了、丢得太狠拼不出完整帧（带宽不够，发布端那条流降不下来）；
 *  3. 包来了也拼全了、但**解码器出不来帧**（编解码档位对不上、硬解起不来）。
 *
 * `packetsReceived` / `packetsLost` / `framesDecoded` / `decoderImplementation`
 * 这四个字段一摆出来，三选一当场就定了。
 *
 * 2026-09-18 会议房真机（房间 41642481）就卡在这儿：Android 上 iOS 那一格全程没画面，
 * 而 iOS 那条流**只发了一层 1080p**（`RTCDefaultVideoEncoderFactory` 不套 simulcast
 * adapter，三个 `sendEncodings` 只编出第一个），服务端 `selectLayer` 又只能「订阅者要
 * l、发布者只有 h 就给 h」——到底是它把手机的下行灌爆了，还是 H.264 没解出来，
 * 当时手上没有任何一个数能分开。
 *
 * # 为什么只在「变了」的时候打
 *
 * 与 [IMUplinkStats] 同一条判据（CONVENTIONS §6）：正常一路视频只该出现一行
 * （首次出帧那一行）。**「在不在出帧」翻面**也算变了——那正是黑屏与恢复这两个要抓的
 * 事件，稳定的通话不会刷屏，出问题时时间线又完整。
 *
 * **不在媒体热路径上**：`getStats` 每 [intervalMs] 才走一次，回调在 native 的统计线程上。
 */
internal class IMDownlinkStats(
    private val main: Handler,
    /** track_id → uid。日志里要写人名，写 ssrc 没人看得懂。 */
    private val ownerOf: (String) -> String?,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    /** 一路上一次的样子。只用来判「变没变」与算增量。 */
    private data class Sample(
        val width: Long,
        val height: Long,
        val decoder: String,
        val framesDecoded: Long,
        val packetsReceived: Long,
        val packetsLost: Long,
        val bytes: Long,
        val atMs: Long,
    )

    private val last = LinkedHashMap<String, Sample>()

    /** track_id → 上一拍判定的「在出帧没有」。没这一项就分不出「一直黑」和「刚黑」。 */
    private val wasFlowing = LinkedHashMap<String, Boolean>()

    private var connection: PeerConnection? = null

    private val poll = object : Runnable {
        override fun run() {
            val pc = connection ?: return
            // getStats 本身是异步的；拿不到就等下一拍，不做重试——统计不值得为它加复杂度。
            runCatching { pc.getStats { report -> onReport(report.statsMap.values) } }
                .onFailure { IMRTCLog.d("media", "取下行统计失败：${it.message}") }
            main.postDelayed(this, intervalMs)
        }
    }

    /** 开始采样。**可重入**：重复调只保留最后一条 PeerConnection。 */
    fun start(connection: PeerConnection) {
        stop()
        this.connection = connection
        main.postDelayed(poll, intervalMs)
    }

    /** 停止采样。挂断 / 离房时必须调（CONVENTIONS §5）。 */
    fun stop() {
        main.removeCallbacks(poll)
        connection = null
        last.clear()
        wasFlowing.clear()
    }

    private fun onReport(stats: Collection<RTCStats>) {
        for (entry in stats) {
            if (entry.type != "inbound-rtp") continue
            val members = entry.members
            if (members["kind"] != "video" && members["mediaType"] != "video") continue

            val trackId = members["trackIdentifier"] as? String ?: "ssrc-${asLong(members["ssrc"])}"
            val width = asLong(members["frameWidth"])
            val height = asLong(members["frameHeight"])
            // 一帧都没解出来时这几个字段压根不存在——那本身就是最要紧的那种情况。
            val decoder = members["decoderImplementation"] as? String ?: "-"
            val framesDecoded = asLong(members["framesDecoded"])
            val packetsReceived = asLong(members["packetsReceived"])
            val packetsLost = asLong(members["packetsLost"])
            val bytes = asLong(members["bytesReceived"])
            val nowMs = System.currentTimeMillis()

            val previous = last[trackId]
            /*
             「在不在出帧」翻面才算事件：解码数停了 = 黑屏开始，又涨了 = 恢复。
             只比分辨率的话，卡住的那一路会**停在最后一行**上再不出声，
             而那恰好是最该留痕的时刻。
            */
            val flowing = previous != null && framesDecoded > previous.framesDecoded
            val changed = previous == null ||
                previous.width != width || previous.height != height ||
                previous.decoder != decoder || wasFlowing[trackId] != flowing
            last[trackId] = Sample(
                width, height, decoder, framesDecoded, packetsReceived, packetsLost, bytes, nowMs,
            )
            wasFlowing[trackId] = flowing
            if (!changed) continue

            val seconds = previous?.let { (nowMs - it.atMs) / 1000.0 } ?: 0.0
            val kbps = if (seconds > 0) ((bytes - previous!!.bytes) * 8 / seconds / 1000).toInt() else -1
            val uid = ownerOf(trackId) ?: "?"
            IMRTCLog.i(
                "media",
                "下行解码实况 uid=$uid track_id=$trackId ${width}x$height " +
                    "解码帧=$framesDecoded(+${framesDecoded - (previous?.framesDecoded ?: 0)}) " +
                    "收包=$packetsReceived(+${packetsReceived - (previous?.packetsReceived ?: 0)}) " +
                    "丢包=$packetsLost(+${packetsLost - (previous?.packetsLost ?: 0)}) " +
                    "码率=${if (kbps >= 0) "${kbps}kbps" else "?"} 解码器=$decoder",
            )
        }
    }

    /**
     * 统计里的数字类型不定：`bytesReceived` 是 `BigInteger`，`frameWidth` 是 `Long`，
     * 有的字段干脆缺席。统一收成 Long，拿不到就 0——**统计不该把通话搞崩**。
     */
    private fun asLong(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private companion object {
        /** 采样周期。与 [IMUplinkStats] 同值，两边的时间线才对得上。 */
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
