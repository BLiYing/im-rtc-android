package com.imrtc.uikit

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * 「添加成员」的选人半屏（交互稿 §05 G2，`HOST_INTEGRATION_DESIGN.md` §3.4 重写）。
 *
 * 候选人来自 [IMInviteSource]（provider 分页拉取，或者静态名单一次给全部）——本类不关心
 * 名单从哪来，只管：**300ms 防抖搜索**、**新请求作废旧结果**（generation 计数）、
 * **滚到底翻页**、加载中 / 失败(可重试) / 超时三态、`participantUids` 里的人「已在通话中」
 * 不可选、`selectable=false` 置灰带原因、最多选到**此刻**还空着的位子数。
 */
internal class IMInvitePicker(
    private val activity: Activity,
    private val ctx: IMInviteContext,
    private val source: IMInviteSource,
    private val allowManualInput: Boolean,
    private val onInvite: (List<String>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)

    private val picked = LinkedHashSet<String>()
    private var query = ""

    /** 还能加几个人。**每次现算**：选人页开着的时候别人可能进来了，打开那一刻的快照会让人多选。 */
    private val slotsLeft: Int get() = IMCallKit.state.inviteSlotsLeft

    /** 每发一次新请求（新搜索 / 翻页）就 +1；回调时对不上号说明已经过期，整个丢弃。 */
    private var generation = 0

    private var status: Status = Status.LOADING
    private var items: List<IMInviteCandidate> = emptyList()
    private var nextCursor: String? = null
    private var loadingMore = false
    private var pageError: String? = null

    private var searchDebounce: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    private val slotsLabel = TextView(activity)
    private val goButton = Button(activity)
    private val list = ListView(activity)
    private val centerMessage = TextView(activity)
    private val adapter = Adapter()

    private enum class Status { LOADING, ERROR, LOADED }

    fun show() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = IMKitTheme.roundedDrawable(IMKitTheme.bannerBackground, dp(20))
            setPadding(0, dp(12), 0, dp(16))
        }
        root.addView(header())
        root.addView(
            searchBox(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { setMargins(dp(14), 0, dp(14), dp(8)) },
        )

        val body = FrameLayout(activity)
        centerMessage.gravity = Gravity.CENTER
        centerMessage.textSize = 12.5f
        centerMessage.setTextColor(IMKitTheme.secondaryText)
        centerMessage.setPadding(dp(24), dp(24), dp(24), dp(24))
        // 空态/错误态整块可点：失败时点它就是「重试」，与交互稿 §05 的错误态一致。
        centerMessage.setOnClickListener { if (status == Status.ERROR) reload() }
        list.adapter = adapter
        list.divider = null
        list.setOnItemClickListener { _, _, position, _ -> toggle(rows()[position]) }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (totalItemCount == 0 || visibleItemCount == 0) return
                if (firstVisibleItem + visibleItemCount >= totalItemCount - 2) maybeLoadMore()
            }
        })
        body.addView(centerMessage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))

        goButton.textSize = 15f
        goButton.setTypeface(null, android.graphics.Typeface.BOLD)
        goButton.isAllCaps = false
        goButton.stateListAnimator = null
        goButton.setOnClickListener { if (picked.isNotEmpty()) { onInvite(picked.toList()); dialog.dismiss() } }
        root.addView(goButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { setMargins(dp(14), dp(8), dp(14), 0) })

        refreshChrome()
        dialog.setOnDismissListener { cancelTimers() }
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        load(reset = true)
    }

    private fun header() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), dp(8))
        addView(TextView(activity).apply { text = "添加成员"; textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(IMKitTheme.primaryText) })
        slotsLabel.textSize = 11f
        slotsLabel.setTextColor(IMKitTheme.secondaryText)
        addView(slotsLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        addView(
            ImageButton(activity).apply {
                setImageResource(IMKitIcon.XMARK.resId)
                setColorFilter(IMKitTheme.primaryText)
                background = IMKitTheme.circleDrawable(IMKitTheme.controlOff)
                contentDescription = "关闭"
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(dp(32), dp(32)),
        )
    }

    private fun searchBox() = EditText(activity).apply {
        hint = "搜索联系人"
        textSize = 13f
        setTextColor(IMKitTheme.primaryText)
        setHintTextColor(IMKitTheme.secondaryText)
        background = IMKitTheme.roundedDrawable(IMKitTheme.controlOff, dp(9))
        setPadding(dp(10), 0, dp(10), 0)
        activity.getDrawable(IMKitIcon.MAGNIFYING_GLASS.resId)?.mutate()?.let { icon ->
            icon.setBounds(0, 0, dp(15), dp(15))
            icon.setTint(IMKitTheme.secondaryText)
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(6)
        }
        maxLines = 1
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                debounceSearch()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    /** 停止输入 300ms 才真的发请求；这期间又敲了字，`load` 里的 generation++ 会把上一个请求作废。 */
    private fun debounceSearch() {
        searchDebounce?.let { main.removeCallbacks(it) }
        val runnable = Runnable { load(reset = true) }
        searchDebounce = runnable
        main.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun reload() = load(reset = true)

    private fun load(reset: Boolean) {
        if (reset) {
            generation++
            status = Status.LOADING
            items = emptyList()
            nextCursor = null
            pageError = null
        } else {
            loadingMore = true
        }
        refresh()
        val gen = generation
        val cursor = if (reset) null else nextCursor
        armTimeout(gen)
        runCatching {
            source.load(
                query.trim(),
                cursor,
                object : IMInviteCandidatesCallback {
                    override fun onResult(items: List<IMInviteCandidate>, nextCursor: String?) {
                        main.post { onLoaded(gen, reset, items, nextCursor) }
                    }
                    override fun onError(message: String) {
                        main.post { onFailed(gen, reset, message) }
                    }
                },
            )
        }.onFailure { onFailed(gen, reset, it.message ?: "加载失败") }
    }

    private fun maybeLoadMore() {
        if (status != Status.LOADED || loadingMore || nextCursor.isNullOrEmpty()) return
        load(reset = false)
    }

    /** **10 秒未回调算失败**——容信 iOS 现有实现在账号全无效时不回调，页面永远转圈的坑（设计 §3.4）。 */
    private fun armTimeout(gen: Int) {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        val runnable = Runnable { onTimeout(gen) }
        timeoutRunnable = runnable
        main.postDelayed(runnable, TIMEOUT_MS)
    }

    private fun onTimeout(gen: Int) {
        if (gen != generation) return
        if (loadingMore) {
            loadingMore = false
            pageError = "加载超时"
        } else {
            status = Status.ERROR
        }
        refresh()
    }

    private fun onLoaded(gen: Int, reset: Boolean, fetched: List<IMInviteCandidate>, cursor: String?) {
        if (gen != generation) return // 新请求已经作废了这次结果
        timeoutRunnable?.let { main.removeCallbacks(it) }
        items = if (reset) fetched else items + fetched
        nextCursor = cursor
        loadingMore = false
        pageError = null
        status = Status.LOADED
        refresh()
    }

    private fun onFailed(gen: Int, reset: Boolean, message: String) {
        if (gen != generation) return
        timeoutRunnable?.let { main.removeCallbacks(it) }
        if (reset) {
            status = Status.ERROR
        } else {
            loadingMore = false
            pageError = message
        }
        refresh()
    }

    // ── 展示 ──────────────────────────────────────────────────────────

    private sealed class Row {
        data class Item(val candidate: IMInviteCandidate, val blocked: Boolean, val reason: String) : Row()
        data class Typed(val uid: String) : Row()
        object LoadingMore : Row()
        data class PageError(val message: String) : Row()
    }

    private fun rows(): List<Row> {
        if (status != Status.LOADED) return emptyList()
        val q = query.trim()
        val result = ArrayList<Row>()
        // 已选但这一页里搜不到的（换搜索词的间隙）：补在最前面，免得勾了又看不见。
        picked.filter { p -> items.none { it.uid == p } }.forEach { uid ->
            result += Row.Item(IMInviteCandidate(uid), blocked = uid in ctx.participantUids, reason = "已在通话中")
        }
        items.forEach { c ->
            val blocked = c.uid in ctx.participantUids
            result += Row.Item(c, blocked = blocked, reason = if (blocked) "已在通话中" else c.unselectableReason.orEmpty())
        }
        // uid 输入框：仅在允许、且这一页（含搜索）确实什么都没有时才出现（§3.4：只出现在空态里）。
        if (allowManualInput && items.isEmpty() && q.isNotEmpty() && q !in ctx.participantUids) {
            result += Row.Typed(q)
        }
        if (loadingMore) result += Row.LoadingMore
        pageError?.let { result += Row.PageError(it) }
        return result
    }

    private fun toggle(row: Row) {
        when (row) {
            is Row.Item -> {
                if (row.blocked || !row.candidate.selectable) return
                val uid = row.candidate.uid
                if (uid in picked) picked.remove(uid) else if (picked.size < slotsLeft) picked.add(uid)
            }
            is Row.Typed -> if (picked.size < slotsLeft) picked.add(row.uid)
            is Row.PageError -> { pageError = null; load(reset = false) }
            Row.LoadingMore -> Unit
        }
        refresh()
    }

    private fun refresh() {
        refreshChrome()
        centerMessage.visibility = if (status == Status.LOADED) View.GONE else View.VISIBLE
        list.visibility = if (status == Status.LOADED) View.VISIBLE else View.GONE
        centerMessage.text = when (status) {
            Status.LOADING -> "加载中…"
            Status.ERROR -> "加载失败，点击重试"
            Status.LOADED -> ""
        }
        adapter.notifyDataSetChanged()
    }

    private fun refreshChrome() {
        slotsLabel.text = "还能加 ${maxOf(slotsLeft - picked.size, 0)} 人"
        goButton.text = if (picked.isEmpty()) "邀请" else "邀请 ${picked.size} 人"
        goButton.background = IMKitTheme.roundedDrawable(if (picked.isEmpty()) IMKitTheme.controlOff else IMKitTheme.answer, dp(12))
        goButton.setTextColor(if (picked.isEmpty()) IMKitTheme.secondaryText else IMKitTheme.answerIcon)
    }

    private fun cancelTimers() {
        searchDebounce?.let { main.removeCallbacks(it) }
        timeoutRunnable?.let { main.removeCallbacks(it) }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows().size
        override fun getItem(position: Int) = rows()[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = when (rows()[position]) {
            is Row.LoadingMore, is Row.PageError -> 1
            else -> 0
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows()[position]) {
                is Row.LoadingMore -> textRow(convertView, "加载中…")
                is Row.PageError -> textRow(convertView, "${row.message} · 点击重试")
                is Row.Typed -> candidateCell(convertView, "邀请 ${row.uid}", row.uid, sub = "", checked = row.uid in picked, blocked = false)
                is Row.Item -> candidateCell(
                    convertView,
                    row.candidate.name,
                    row.candidate.uid,
                    sub = if (row.blocked) row.reason else row.candidate.subtitle.orEmpty().ifEmpty { row.reason },
                    checked = row.blocked || row.candidate.uid in picked,
                    blocked = row.blocked || !row.candidate.selectable,
                )
            }

        private fun textRow(convertView: View?, text: String): View {
            val view = (convertView as? TextView)?.takeIf { it.tag == TAG_TEXT_ROW } ?: TextView(activity).apply {
                tag = TAG_TEXT_ROW
                gravity = Gravity.CENTER
                textSize = 12f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextColor(IMKitTheme.secondaryText)
            }
            view.text = text
            return view
        }

        private fun candidateCell(convertView: View?, name: String, uid: String, sub: String, checked: Boolean, blocked: Boolean): View {
            val cell = (convertView as? LinearLayout)?.takeIf { it.tag == TAG_CANDIDATE_ROW } ?: LinearLayout(activity).apply {
                tag = TAG_CANDIDATE_ROW
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                addView(TextView(activity).apply { gravity = Gravity.CENTER; textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(IMKitTheme.primaryText) }, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(activity).apply { textSize = 13.5f; setTextColor(IMKitTheme.primaryText) })
                        addView(TextView(activity).apply { textSize = 10.5f; setTextColor(IMKitTheme.secondaryText) })
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) },
                )
                addView(TextView(activity).apply { gravity = Gravity.CENTER; textSize = 11f }, LinearLayout.LayoutParams(dp(20), dp(20)))
            }
            val avatar = cell.getChildAt(0) as TextView
            val texts = cell.getChildAt(1) as LinearLayout
            val check = cell.getChildAt(2) as TextView
            avatar.text = IMAvatar.initial(name)
            avatar.background = IMKitTheme.avatarDrawable(uid)
            (texts.getChildAt(0) as TextView).text = name
            (texts.getChildAt(1) as TextView).apply { text = sub; visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE }
            check.text = if (checked) "✓" else ""
            check.setTextColor(if (blocked) IMKitTheme.secondaryText else IMKitTheme.answerIcon)
            check.background = if (checked) {
                IMKitTheme.circleDrawable(if (blocked) IMKitTheme.controlOff else IMKitTheme.answer)
            } else {
                IMKitTheme.circleDrawable(android.graphics.Color.TRANSPARENT)
                    .apply { setStroke((1.5f * activity.resources.displayMetrics.density).toInt(), 0x59FFFFFF) }
            }
            cell.alpha = if (blocked) 0.45f else 1f
            return cell
        }
    }

    /** [IMInvitePicker] 不是 View（持有 [Activity] 不是继承它），用 Context 版。 */
    private fun dp(value: Int): Int = activity.dp(value)

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val TIMEOUT_MS = 10_000L
        const val TAG_TEXT_ROW = "text-row"
        const val TAG_CANDIDATE_ROW = "candidate-row"
    }
}
