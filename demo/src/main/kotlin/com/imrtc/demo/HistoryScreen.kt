package com.imrtc.demo

import android.app.Activity
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.util.Date
import java.util.Locale

/**
 * 通话记录（草图 §02-C）：**完全由 `onCallEnd(reason, durationSec)` 拼出来**，Demo 自己存本地。
 *
 * 这一屏是「宿主会拿回调做什么」的示范，不是要求宿主照抄——
 * 换成消息气泡、换成后台查 `/v1/calls`，都是同一份数据。**未接来电红字。**
 */
internal class HistoryScreen(private val activity: Activity) : DemoScreen {

    private val list = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = DemoUI.rounded(DemoUI.CARD, DemoUI.dp(activity, 12))
    }

    override val title = "通话记录"
    override val titleAction: Pair<String, () -> Unit> = "🗑" to { DemoSession.clearRecords() }
    override val view: View = DemoUI.scroll(activity, list)

    override fun refresh() {
        list.removeAllViews()
        val records = DemoSession.records
        if (records.isEmpty()) {
            list.addView(empty())
            return
        }
        records.forEachIndexed { index, record ->
            if (index > 0) list.addView(DemoUI.separator(activity))
            list.addView(row(record))
        }
    }

    private fun empty(): View = DemoUI.note(
        activity,
        "还没有通话记录。\n（这一页完全由 onCallEnd 拼出来；会议房不产生 call，所以不会出现在这里）",
    ).apply {
        val pad = DemoUI.dp(activity, 16)
        setPadding(pad, pad, pad, pad)
    }

    private fun row(record: DemoSession.Record): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = DemoUI.dp(activity, 12)
        setPadding(pad, pad, pad, pad)

        addView(
            DemoUI.label(activity, icon(record), 20f, DemoUI.LABEL),
            LinearLayout.LayoutParams(DemoUI.dp(activity, 36), DemoUI.WRAP),
        )

        // 未接来电红字：被叫 + 没接通。这是记录页唯一需要一眼看出来的东西。
        val missed = record.role == "callee" && record.durationSec == 0L
        addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    DemoUI.label(
                        activity,
                        record.peer.ifEmpty { "（未知）" },
                        16f,
                        if (missed) DemoUI.RED else DemoUI.LABEL,
                    ).apply { typeface = Typeface.DEFAULT_BOLD },
                )
                addView(DemoUI.label(activity, summary(record), 13f, DemoUI.SECONDARY))
            },
            LinearLayout.LayoutParams(0, DemoUI.WRAP, 1f),
        )

        addView(DemoUI.label(activity, time(record.endedAtMs), 13f, DemoUI.SECONDARY))
    }

    private fun icon(record: DemoSession.Record) =
        if (record.isGroup) "👥" else if (record.mediaType == "video") "📹" else "📞"

    private fun summary(record: DemoSession.Record): String {
        val direction = if (record.role == "callee") "来电" else "呼出"
        // 协议 §2.4 规则 6：表外的值 Engine 已经折成 error 了；即便漏进来也不能把生值显给用户。
        val outcome = when (record.reason) {
            "hangup" -> formatDuration(record.durationSec)
            "cancel" -> "已取消"
            "reject" -> if (record.role == "callee") "已拒接" else "对方拒接"
            "busy" -> "对方忙线"
            "no_answer", "timeout" -> if (record.role == "callee") "未接来电" else "无应答"
            "offline" -> "对方不在线"
            "network" -> "网络中断"
            "kicked" -> "登录态失效"
            else -> "已结束"
        }
        return "$direction · $outcome"
    }

    private fun time(millis: Long): String =
        DateFormat.getTimeFormat(activity).format(Date(millis))

    /** 与 Kit 的 `IMGrid.formatDuration` 同一套格式，但**这里是宿主自己的代码**。 */
    private fun formatDuration(seconds: Long): String {
        val safe = if (seconds < 0) 0 else seconds
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }
}
