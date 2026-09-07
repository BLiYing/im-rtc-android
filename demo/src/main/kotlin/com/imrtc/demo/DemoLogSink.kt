package com.imrtc.demo

import android.util.Log
import com.imrtc.engine.log.IMRTCLog

/**
 * Demo 的日志出口：把 Engine 的日志转到 logcat。
 *
 * **这是全仓唯一允许碰 `android.util.Log` 的地方**，和 `IMRTCLog.kt` 同理——
 * 日志纪律门禁（`scripts/check-logging.sh`）豁免的就是这两个文件名。
 * 业务代码一律走 `IMRTCLog`，否则脱敏、必带字段、热路径静默几条规矩全都落空。
 *
 * SDK **默认不装任何 sink**：不往宿主的 logcat 里乱写是基本礼貌。
 *
 * # 并联，不是替换
 *
 * 登录之后会挂上 [RemoteLogSink] 把日志回传服务端，但 **logcat 这一路必须留着**：
 * 现场排查时人手里有的就是 logcat，不能因为多了个回传就把它关掉。
 * 所以这里是 fan-out——两路各写各的，一路失败不影响另一路。
 */
internal object DemoLogSink : IMRTCLog.Sink {

    /**
     * 回传服务端那一路，登录后挂上、登出时摘掉。
     *
     * `@Volatile` 而不是加锁：写日志不该成为同步点，而这个引用的换手
     * 只发生在登录/登出，读到旧值最多是把一条日志发给刚拆掉的 sink。
     */
    @Volatile
    private var remote: IMRTCLog.Sink? = null

    fun install() {
        IMRTCLog.setSink(this)
        IMRTCLog.setMinLevel(IMRTCLog.Level.DEBUG)
    }

    /** 挂上回传。重复调用会先停掉上一个——换服务器重登时不能留着旧的在跑。 */
    fun attachRemote(sink: RemoteLogSink) {
        detachRemote()
        sink.start()
        remote = sink
    }

    /** 摘掉回传并把手里剩下的发出去。 */
    fun detachRemote() {
        (remote as? RemoteLogSink)?.stop()
        remote = null
    }

    override fun write(level: IMRTCLog.Level, tag: String, message: String) {
        val priority = when (level) {
            IMRTCLog.Level.DEBUG -> Log.DEBUG
            IMRTCLog.Level.INFO -> Log.INFO
            IMRTCLog.Level.WARN -> Log.WARN
            IMRTCLog.Level.ERROR -> Log.ERROR
        }
        Log.println(priority, "imrtc/$tag", message)
        // **远程那一路出问题不能连累 logcat**：这里是日志出口，
        // 它自己抛异常会把正在打日志的那条业务线程一起带走。
        runCatching { remote?.write(level, tag, message) }
    }
}
