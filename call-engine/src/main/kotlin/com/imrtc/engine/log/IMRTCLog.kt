package com.imrtc.engine.log

/**
 * Engine 的统一日志入口（CONVENTIONS §6）。
 *
 * **业务代码里禁止出现 `android.util.Log` / `println`**，硬闸在 `scripts/check-logging.sh`。
 * 走统一入口换来三件事：脱敏、必带字段、以及把日志**回传服务端汇入一条时间轴**的能力
 * （开发期用，五端的日志能按时间排在一起读）。
 *
 * 线程安全：`sink` 是 `@Volatile`，写日志本身不加锁——日志不该成为一个同步点。
 */
object IMRTCLog {

    enum class Level(val tag: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    /** 一条日志的去处。宿主可以注入自己的实现（例如同时写文件、回传服务端）。 */
    fun interface Sink {
        fun write(level: Level, tag: String, message: String)
    }

    @Volatile
    private var sink: Sink? = null

    @Volatile
    private var minLevel: Level = Level.DEBUG

    /** 装一个 sink。传 null 等于关掉日志（SDK **默认就是关的**，不往宿主的 logcat 里乱写）。 */
    @JvmStatic
    fun setSink(sink: Sink?) {
        this.sink = sink
    }

    @JvmStatic
    fun setMinLevel(level: Level) {
        minLevel = level
    }

    @JvmStatic
    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)

    @JvmStatic
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)

    @JvmStatic
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)

    @JvmStatic
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    private fun log(level: Level, tag: String, message: String) {
        if (level.ordinal < minLevel.ordinal) return
        sink?.write(level, tag, message)
    }

    /**
     * 凭据脱敏：**只留前 6 位 + 长度**。
     *
     * token / room_token 这类东西整条打出来，等于把它写进了宿主的 logcat、崩溃上报、
     * 用户发过来的日志文件里。前 6 位足够肉眼比对「是不是同一枚票」，也足够定位问题。
     */
    @JvmStatic
    fun redact(secret: String): String = when {
        secret.isEmpty() -> "(空)"
        secret.length <= 6 -> "***(${secret.length})"
        else -> secret.take(6) + "***(" + secret.length + ")"
    }

    /**
     * SDP 太长，日志里只留摘要。
     *
     * 整条 SDP 有几千字节，打出来会把前后文冲掉——真要看 SDP 的时候是在抓包或者
     * `chrome://webrtc-internals` 里看，不是在这里。
     */
    @JvmStatic
    fun sdpDigest(sdp: String): String = "sdp(${sdp.length}B)"
}
