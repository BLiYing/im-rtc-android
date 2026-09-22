package com.imrtc.demo

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 通话记录的时间文案（四端统一的规则，用例表也四端一致）：
 *
 * | 发起于 | 显示 |
 * |---|---|
 * | 今天 | `HH:mm` |
 * | 昨天 | `昨天 HH:mm` |
 * | 今年更早 | `M月d日 HH:mm` |
 * | 往年 | `yyyy年M月d日 HH:mm` |
 *
 * - **按自然日**判断今天 / 昨天（本地时区的零点），不按 24 小时：昨天 23:50 的通话今天 00:10 看仍是「昨天」。
 * - 用**发起时间**，跨零点的通话归到发起那天。
 * - 记录时间比 [nowMs] 还晚（设备与服务端时钟有偏差）按今天处理，不显示「明天」。
 * - 时分部分由 [hourMinute] 出（Demo 传系统的 12 / 24 小时制格式），本函数只管日期档位。
 */
internal fun formatCallTime(
    startedAtMs: Long,
    nowMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
    hourMinute: (Long) -> String,
): String {
    val started = Calendar.getInstance(zone, Locale.CHINA).apply { timeInMillis = startedAtMs }
    val now = Calendar.getInstance(zone, Locale.CHINA).apply { timeInMillis = nowMs }
    val time = hourMinute(startedAtMs)

    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        startedAtMs >= nowMs || sameDay(started, now) -> time
        sameDay(started, yesterday) -> dt("demo.time.yesterday", "time" to time)
        started.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
            dt("demo.time.sameYear", "month" to started.get(Calendar.MONTH) + 1, "day" to started.get(Calendar.DAY_OF_MONTH), "time" to time)
        else ->
            dt("demo.time.otherYear", "year" to started.get(Calendar.YEAR), "month" to started.get(Calendar.MONTH) + 1, "day" to started.get(Calendar.DAY_OF_MONTH), "time" to time)
    }
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
