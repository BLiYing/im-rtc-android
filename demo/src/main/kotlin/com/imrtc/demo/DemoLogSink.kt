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
 */
internal object DemoLogSink : IMRTCLog.Sink {

    fun install() {
        IMRTCLog.setSink(this)
        IMRTCLog.setMinLevel(IMRTCLog.Level.DEBUG)
    }

    override fun write(level: IMRTCLog.Level, tag: String, message: String) {
        val priority = when (level) {
            IMRTCLog.Level.DEBUG -> Log.DEBUG
            IMRTCLog.Level.INFO -> Log.INFO
            IMRTCLog.Level.WARN -> Log.WARN
            IMRTCLog.Level.ERROR -> Log.ERROR
        }
        Log.println(priority, "imrtc/$tag", message)
    }
}
