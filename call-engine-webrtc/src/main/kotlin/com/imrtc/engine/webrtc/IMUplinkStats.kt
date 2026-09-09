package com.imrtc.engine.webrtc

import android.os.Handler
import com.imrtc.engine.log.IMRTCLog
import org.webrtc.PeerConnection
import org.webrtc.RTCStats

/**
 * 上行编码统计：**每一层实际编出来的是多少分辨率**，以及被什么限住了。
 *
 * # 为什么非要有它
 *
 * 服务端只看得见 RTP 上的 `rid` 标签，**看不见那一层里装的是什么分辨率**。
 * 于是排查「对端说糊」时会卡死在一个没法回答的问题上：日志里明明写着
 * `上行层已接入 rid=h`、订阅侧的上界也确实是 `h`，画面还是糊。
 *
 * 缺口在编码器这一段：libwebrtc 在 CPU 或码率吃紧时会**自动降采集分辨率**
 * （`degradationPreference` 不设时默认 BALANCED，分辨率与帧率一起降），
 * 降完之后 `rid` 标签一个字都不变。而本端预览走的是采集原图、不过编码器，
 * 所以**自己看着清楚、对端看着糊**——2026-09-09 真机上就是这个症状，
 * 当时手上没有任何数据能证实或否掉它。
 *
 * `qualityLimitationReason` 这个字段直接写明是 `cpu` 还是 `bandwidth`，
 * 等于把答案说出来了，不用再猜。
 *
 * # 为什么只在「变了」的时候打
 *
 * 按 CONVENTIONS §6 的判据：正常的一通电话每层只该出现一行（首次采样那一行）。
 * 分辨率或受限原因变了才再打一行——那正是要抓的事件。
 * 稳定的通话不会刷屏，出问题时时间线又完整。
 *
 * **不在媒体热路径上**：`getStats` 每 [intervalMs] 才走一次，回调在 native 的
 * 统计线程上，不碰每帧每包那条路。
 */
internal class IMUplinkStats(
    private val main: Handler,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    /** 一层上一次的样子。只用来判「变没变」与算码率。 */
    private data class Sample(
        val width: Long,
        val height: Long,
        val reason: String,
        val bytes: Long,
        val atMs: Long,
    )

    private val last = LinkedHashMap<String, Sample>()
    private var connection: PeerConnection? = null

    private val poll = object : Runnable {
        override fun run() {
            val pc = connection ?: return
            // getStats 本身是异步的；拿不到就等下一拍，不做重试——统计不值得为它加复杂度。
            runCatching { pc.getStats { report -> onReport(report.statsMap.values) } }
                .onFailure { IMRTCLog.d("media", "取上行统计失败：${it.message}") }
            main.postDelayed(this, intervalMs)
        }
    }

    /** 开始采样。**可重入**：重复调只保留最后一条 PeerConnection。 */
    fun start(connection: PeerConnection) {
        stop()
        this.connection = connection
        main.postDelayed(poll, intervalMs)
    }

    /** 停止采样。停采集 / 挂断时必须调（CONVENTIONS §5）。 */
    fun stop() {
        main.removeCallbacks(poll)
        connection = null
        last.clear()
    }

    private fun onReport(stats: Collection<RTCStats>) {
        for (entry in stats) {
            if (entry.type != "outbound-rtp") continue
            val members = entry.members
            if (members["kind"] != "video" && members["mediaType"] != "video") continue

            // 单层发布没有 rid，用 ssrc 兜底，好歹能区分开。
            val rid = members["rid"] as? String ?: "ssrc-${asLong(members["ssrc"])}"
            val width = asLong(members["frameWidth"])
            val height = asLong(members["frameHeight"])
            // 没编出过帧时这两个字段压根不存在——那本身就是「这一层没在出包」，值得记一行。
            val reason = members["qualityLimitationReason"] as? String ?: "none"
            val bytes = asLong(members["bytesSent"])
            val nowMs = System.currentTimeMillis()

            val previous = last[rid]
            last[rid] = Sample(width, height, reason, bytes, nowMs)
            val changed = previous == null ||
                previous.width != width || previous.height != height || previous.reason != reason
            if (!changed) continue

            val kbps = previous?.let {
                val seconds = (nowMs - it.atMs) / 1000.0
                if (seconds > 0) ((bytes - it.bytes) * 8 / seconds / 1000).toInt() else -1
            } ?: -1
            IMRTCLog.i(
                "media",
                "上行层实况 rid=$rid ${width}x$height fps=${asLong(members["framesPerSecond"])} " +
                    "码率=${if (kbps >= 0) "${kbps}kbps" else "?"} 受限=$reason" +
                    if (previous != null) "（上一次 ${previous.width}x${previous.height} 受限=${previous.reason}）" else "",
            )
        }
    }

    /**
     * 统计里的数字类型不定：`bytesSent` 是 `BigInteger`，`frameWidth` 是 `Long`，
     * 有的字段干脆缺席。统一收成 Long，拿不到就 0——**统计不该把通话搞崩**。
     */
    private fun asLong(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private companion object {
        /**
         * 采样周期。5 秒：既能看清「什么时候开始降的」，又不至于让 `getStats`
         * 频繁跨线程收集一大坨对象。
         */
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
