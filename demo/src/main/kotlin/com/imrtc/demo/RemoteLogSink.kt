package com.imrtc.demo

import com.imrtc.engine.log.IMRTCLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 把 Engine 的日志送回服务端落盘（**仅开发**）。
 *
 * # 为什么需要
 *
 * 真机上的日志只活在 logcat 里，而 logcat 要人接着线、还要在出问题的那一刻正好开着。
 * 「无法挂断」那类问题的现场往往拿不到——服务端只看得见「帧没来」，
 * 到底是 Engine 没发、发了没到、还是界面根本没调，从服务端一侧分不出来。
 *
 * 送回服务端之后，一次通话的**两端加服务端**能按时间轴放在一起读
 * （`im-rtc-server/scripts/timeline.py`，它按 `client-android-*.log` 认端）。
 *
 * # 边界
 *
 * 这是 **Demo 的东西，不是 SDK 的**。Engine 只提供 `IMRTCLog.setSink` 这个接缝，
 * 「日志送到哪里去」永远是宿主的决定。服务端那个接收口也只在 `-demo-login` 下注册。
 *
 * # 与 logcat 的关系
 *
 * **不替换 logcat，是并联**（见 [DemoLogSink]）。iOS 那边装了远程 sink 之后
 * Xcode 控制台就没有 Engine 日志了；Android 上 logcat 是现场排查的主力手段，
 * 不能因为多了个回传就把它关掉。
 */
internal class RemoteLogSink(
    server: String,
    private val client: String,
    private val http: OkHttpClient = defaultClient(),
) : IMRTCLog.Sink {

    private val endpoint = server.trimEnd('/') + "/v1/dev/logs"
    private val buffer = LogBuffer()
    private var timer: ScheduledExecutorService? = null

    /** 起攒批定时器。**必须配对调用 [stop]**，否则换服务器重登后会有两个在跑。 */
    fun start() {
        if (timer != null) return
        timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "imrtc-logsink").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({ flush() }, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** 停掉并把手里剩下的发出去——最后那几条往往正是要看的。 */
    fun stop() {
        timer?.shutdownNow()
        timer = null
        flush()
    }

    /**
     * 记一条。**绝不能抛、绝不能阻塞**——日志失败不该影响通话。
     *
     * 这个方法会在任意线程被调用（Engine 的读循环、主线程、媒体回调都有），
     * 所以只做入队，发送交给定时器那条线程。
     */
    override fun write(level: IMRTCLog.Level, tag: String, message: String) {
        buffer.add(Entry(System.currentTimeMillis(), level.name, tag, message))
    }

    private fun flush() {
        val batch = buffer.drain(BATCH_SIZE)
        if (batch.isEmpty()) return

        val request = Request.Builder()
            .url(endpoint)
            .post(encode(client, batch).toRequestBody(JSON))
            .build()
        runCatching { http.newCall(request).execute().use { /* 结果不看 */ } }
        // **发失败就算了：不重试、不回队。** 日志是尽力而为的，
        // 重试只会在服务端不可达时把队列撑爆，还会拖住下一轮。
    }

    /** 一条待发的日志。 */
    internal data class Entry(
        val atMS: Long,
        val level: String,
        val tag: String,
        val message: String,
    )

    /**
     * 攒批队列。**纯逻辑、不碰 Android 也不碰网络**，所以能直接单测。
     *
     * 满了丢**最旧**的：正在排查的问题总在最近这几条里，
     * 丢新的等于把最要紧的那段丢掉。
     */
    internal class LogBuffer {
        private val entries = ArrayDeque<Entry>()

        @Synchronized
        fun add(entry: Entry) {
            entries.addLast(entry)
            while (entries.size > MAX_QUEUE) entries.removeFirst()
        }

        @Synchronized
        fun drain(max: Int): List<Entry> {
            if (entries.isEmpty()) return emptyList()
            val take = minOf(max, entries.size)
            return List(take) { entries.removeFirst() }
        }

        @Synchronized
        fun size(): Int = entries.size
    }

    internal companion object {

        /**
         * 拼上报用的 JSON。
         *
         * **手写而不是用 `org.json`**：后者在 JVM 单测里是空壳桩，方法一律返回默认值，
         * 测试会假绿（CONVENTIONS「技术栈」）。Engine 里那套 `IMJson` 是 `internal`，
         * 跨模块用不了；而这里的结构是固定的四个字段，手写一份反而能被单测真正验证。
         */
        fun encode(client: String, entries: List<Entry>): String {
            val sb = StringBuilder(entries.size * 96)
            sb.append("{\"client\":")
            appendString(sb, client)
            sb.append(",\"entries\":[")
            entries.forEachIndexed { index, entry ->
                if (index > 0) sb.append(',')
                sb.append("{\"at_ms\":").append(entry.atMS)
                sb.append(",\"level\":")
                appendString(sb, entry.level)
                sb.append(",\"msg\":")
                appendString(sb, entry.message)
                // Sink 接口只给 (level, tag, message)，没有结构化字段。
                // tag 是唯一的额外维度，放进 fields 好让时间轴上看得出模块。
                sb.append(",\"fields\":{\"tag\":")
                appendString(sb, entry.tag)
                sb.append("}}")
            }
            return sb.append("]}").toString()
        }

        /** JSON 字符串转义。日志里出现引号、反斜杠、换行是家常便饭，漏一个整批就废了。 */
        private fun appendString(sb: StringBuilder, raw: String) {
            sb.append('"')
            for (ch in raw) {
                when {
                    ch == '"' -> sb.append("\\\"")
                    ch == '\\' -> sb.append("\\\\")
                    ch == '\n' -> sb.append("\\n")
                    ch == '\r' -> sb.append("\\r")
                    ch == '\t' -> sb.append("\\t")
                    ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                    else -> sb.append(ch)
                }
            }
            sb.append('"')
        }
        /** 攒批间隔。1 秒足够密，也不至于让日志本身成为负担。 */
        const val FLUSH_SECONDS = 1L

        /** 队列上限。满了丢最旧的。 */
        const val MAX_QUEUE = 500

        /** 单批条数上限，与服务端的 `devLogMaxEntries` 对齐。 */
        const val BATCH_SIZE = 200

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * **超时必须显式设短**。
         *
         * iOS 那边踩过：默认 60 秒，而发送期间有个「正在发」的闩要等回调才放开——
         * 一个卡住的请求就能让**后面所有日志静默丢掉**，而且完全看不出来
         * （日志文件停在某个时间点不动，应用其实还活着）。
         * 这里是同步 execute，卡住会直接占死攒批线程，后果一样。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
