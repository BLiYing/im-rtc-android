package com.imrtc.demo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 通话记录时间文案：四端共用同一张用例表（今天 / 昨天 / 今年更早 / 往年 / 跨零点 / 时钟偏差）。 */
class HistoryTimeTest {
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    private val hm: (Long) -> String = { ms ->
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun fmt(started: Long, now: Long) = formatCallTime(started, now, zone, hm)

    private val now = at(2026, 9, 19, 11, 40)

    @Test fun `今天只显示时分`() = assertEquals("11:35", fmt(at(2026, 9, 19, 11, 35), now))

    @Test fun `今天 00 点整也是今天`() = assertEquals("00:00", fmt(at(2026, 9, 19, 0, 0), now))

    @Test fun `昨天`() = assertEquals("昨天 23:11", fmt(at(2026, 9, 18, 23, 11), now))

    @Test fun `跨零点按自然日不按 24 小时`() =
        assertEquals("昨天 23:50", fmt(at(2026, 9, 18, 23, 50), at(2026, 9, 19, 0, 10)))

    @Test fun `今年更早显示月日`() = assertEquals("9月15日 18:17", fmt(at(2026, 9, 15, 18, 17), now))

    @Test fun `往年带年份`() = assertEquals("2025年12月31日 09:05", fmt(at(2025, 12, 31, 9, 5), now))

    @Test fun `元旦看去年除夕算昨天`() =
        assertEquals("昨天 23:59", fmt(at(2025, 12, 31, 23, 59), at(2026, 1, 1, 8, 0)))

    @Test fun `记录时间比现在还晚按今天`() = assertEquals("11:50", fmt(at(2026, 9, 19, 11, 50), now))
}
