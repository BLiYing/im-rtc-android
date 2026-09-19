package com.imrtc.demo

import android.app.Activity
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.imrtc.engine.IMCallHistoryRecord
import java.util.Date
import java.util.Locale

/**
 * 通话记录（草图 §02-C）：**调 SDK 的 `fetchCallHistory` 从服务端拉**，游标翻页。
 *
 * 这一屏是「宿主会拿 SDK 做什么」的示范，不是要求宿主照抄——
 * 想自己存，就拿 `onCallEnd(reason, durationSec)` 落自己的库；想让换设备、重装后记录还在，就查这里。
 * **未接来电红字。**
 *
 * - 进页、通话结束、点标题栏的 ↻ 都重拉首页；滚到底自动加下一页，`nextCursor == null` 就停。
 * - 服务端的 `reason` 不分角色，角色由 `caller == 我` 推出。
 */
internal class HistoryScreen(private val activity: Activity) : DemoScreen {

    private val list = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = DemoUI.rounded(DemoUI.CARD, DemoUI.dp(activity, 12))
    }

    private val scroll = DemoUI.scroll(activity, list)

    private var records: List<IMCallHistoryRecord> = emptyList()
    private var nextCursor: Long? = null
    private var loading = false
    private var message: String? = null

    /** 刷新会让还在路上的旧请求作废：应答回来时代数对不上就丢掉。 */
    private var generation = 0

    override val title = "通话记录"
    override val titleAction: Pair<String, () -> Unit> = "↻" to { refresh() }
    override val view: View = scroll

    init {
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val bottom = list.height - (scroll.scrollY + scroll.height)
            if (bottom < DemoUI.dp(activity, 200)) loadMore()
        }
    }

    override fun refresh() {
        generation++
        loading = false
        nextCursor = null
        load(first = true)
    }

    private fun loadMore() {
        if (nextCursor != null) load(first = false)
    }

    private fun load(first: Boolean) {
        val engine = DemoSession.engine
        if (engine == null) {
            show(emptyList(), "未登录")
            return
        }
        if (loading) return
        loading = true
        val ticket = generation
        engine.fetchCallHistory(PAGE_SIZE, if (first) null else nextCursor) { page, error ->
            if (ticket != generation) return@fetchCallHistory
            loading = false
            if (page != null) {
                nextCursor = page.nextCursor
                show(if (first) page.records else records + page.records, null)
            } else {
                show(if (first) emptyList() else records, "加载失败：${error?.message}\n点右上角 ↻ 重试")
            }
        }
    }

    private fun show(items: List<IMCallHistoryRecord>, note: String?) {
        records = items
        message = note
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(empty())
            return
        }
        val me = DemoSession.engine?.uid.orEmpty()
        items.forEachIndexed { index, record ->
            if (index > 0) list.addView(DemoUI.separator(activity))
            list.addView(row(record, me))
        }
    }

    private fun empty(): View = DemoUI.note(
        activity,
        message ?: "还没有通话记录。\n（会议房不产生 call，所以不会出现在这里）",
    ).apply {
        val pad = DemoUI.dp(activity, 16)
        setPadding(pad, pad, pad, pad)
    }

    private fun row(record: IMCallHistoryRecord, me: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = DemoUI.dp(activity, 12)
        setPadding(pad, pad, pad, pad)

        addView(
            DemoUI.label(activity, icon(record), 20f, DemoUI.LABEL),
            LinearLayout.LayoutParams(DemoUI.dp(activity, 36), DemoUI.WRAP),
        )

        val role = if (record.caller == me) "caller" else "callee"
        // 未接来电红字：被叫 + 没接通。这是记录页唯一需要一眼看出来的东西。
        val missed = role == "callee" && record.durationSec == 0
        addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    DemoUI.label(
                        activity,
                        peerText(record, me),
                        16f,
                        if (missed) DemoUI.RED else DemoUI.LABEL,
                    ).apply { typeface = Typeface.DEFAULT_BOLD },
                )
                addView(DemoUI.label(activity, summary(record, role), 13f, DemoUI.SECONDARY))
            },
            LinearLayout.LayoutParams(0, DemoUI.WRAP, 1f),
        )

        addView(DemoUI.label(activity, time(record.startedAtMs), 13f, DemoUI.SECONDARY))
    }

    private fun icon(record: IMCallHistoryRecord) =
        if (record.isGroup) "👥" else if (record.mediaType == "video") "📹" else "📞"

    /** 对方是谁：被叫看主叫；主叫看第一个被叫（群通话显示人数）。 */
    private fun peerText(record: IMCallHistoryRecord, me: String): String {
        if (record.isGroup) {
            val extra = if (record.members.any { it.uid == record.caller }) 0 else 1
            return "群通话 · ${maxOf(record.members.size, 1) + extra} 人"
        }
        if (record.caller != me) return record.caller.ifEmpty { "（未知）" }
        return record.members.firstOrNull { it.uid != me }?.uid ?: "（未知）"
    }

    private fun summary(record: IMCallHistoryRecord, role: String): String {
        val direction = if (role == "callee") "来电" else "呼出"
        // 协议 §2.4 规则 6：表外的值 Engine 已经折成 error 了；即便漏进来也不能把生值显给用户。
        // 文案对齐 call-uikit 的 IMCallViewState.endReasonText（那个函数是 internal，
        // Demo 是独立模块摸不到，只能照抄一份；RTC_PROTOCOL.md §6/§7.5 的 reason 表为准）。
        val outcome = when (record.reason) {
            "hangup" -> formatDuration(record.durationSec.toLong())
            "cancel" -> "已取消"
            "reject" -> if (role == "callee") "已拒接" else "对方拒接"
            "busy" -> "对方忙线中"
            "no_answer", "timeout" -> if (role == "callee") "未接来电" else "无应答"
            "offline" -> "对方当前不在线"
            "answered_elsewhere" -> "已在其他设备接听"
            "rejected_elsewhere" -> "已在其他设备拒绝"
            "room_closed" -> "房间已解散"
            "network" -> "网络中断"
            // 协议 §7.5：kicked 是「被主持人/管理 API 移出通话」，不是登录态失效（那是连接层
            // 的 IMKickedOutReason，另一件事）——写错了会把用户指向错误的排查方向。
            "kicked" -> "已被移出"
            else -> "已结束"
        }
        return "$direction · $outcome"
    }

    /** 今天 `HH:mm`、昨天 `昨天 HH:mm`、今年更早 `M月d日 HH:mm`、往年带年份；见 [formatCallTime]。 */
    private fun time(millis: Long): String =
        formatCallTime(millis, System.currentTimeMillis()) { DateFormat.getTimeFormat(activity).format(Date(it)) }

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

    private companion object {
        const val PAGE_SIZE = 20
    }
}
