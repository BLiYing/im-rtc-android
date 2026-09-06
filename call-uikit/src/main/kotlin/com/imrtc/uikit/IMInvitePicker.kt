package com.imrtc.uikit

import android.app.Activity
import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * 「添加成员」的选人半屏（交互稿 §05 G2）：搜索 + 列表 + 底部「邀请 N 人」。
 *
 * **候选名单是宿主给的**（[IMCallKitConfig.inviteCandidates]）——Kit 不内置联系人系统（CONVENTIONS §12）。
 * 宿主没给名单时退化成一个 uid 输入框。已在通话里的人**置灰 + 勾选禁用**，不是隐藏：用户要能看到「他已经在里面了」。
 * 顶部实时算「还能加 N 人」= 9 − 当前人数 − 已选。
 */
internal class IMInvitePicker(
    private val activity: Activity,
    private val candidates: List<IMInviteCandidate>,
    private val inCall: Set<String>,
    private val slots: Int,
    private val onInvite: (List<String>) -> Unit,
) {
    private val picked = ArrayList<String>()
    private var query = ""
    private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    private val slotsLabel = TextView(activity)
    private val goButton = Button(activity)
    private val list = ListView(activity)
    private val adapter = Adapter()

    private val shown: List<Row>
        get() {
            val q = query.trim()
            val rows = ArrayList<Row>()
            // 宿主没给名单：输入的 uid 也能邀请。
            if (candidates.isEmpty() && q.isNotEmpty() && q !in inCall && q !in picked) rows += Row(q, "邀请 $q", "", typed = true)
            picked.filter { p -> candidates.none { it.uid == p } }.forEach { rows += Row(it, it, "") }
            candidates.filter { q.isEmpty() || it.uid.contains(q) || it.name.contains(q) }.forEach {
                rows += Row(it.uid, it.name, if (it.uid in inCall) "已在通话中" else "")
            }
            return rows
        }

    private data class Row(val uid: String, val name: String, val sub: String, val typed: Boolean = false)

    fun show() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = IMKitTheme.roundedDrawable(IMKitTheme.bannerBackground, dp(20))
            setPadding(0, dp(12), 0, dp(16))
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), dp(8))
            addView(TextView(activity).apply { text = "添加成员"; textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(IMKitTheme.primaryText) })
            slotsLabel.textSize = 11f
            slotsLabel.setTextColor(IMKitTheme.secondaryText)
            addView(slotsLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            addView(ImageButton(activity).apply {
                setImageResource(IMKitIcon.XMARK.resId); setColorFilter(IMKitTheme.primaryText)
                background = IMKitTheme.circleDrawable(IMKitTheme.controlOff); contentDescription = "关闭"
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
        }
        root.addView(head)
        val search = EditText(activity).apply {
            hint = if (candidates.isEmpty()) "输入对方 uid" else "搜索联系人"
            textSize = 13f
            setTextColor(IMKitTheme.primaryText)
            setHintTextColor(IMKitTheme.secondaryText)
            background = IMKitTheme.roundedDrawable(IMKitTheme.controlOff, dp(9))
            setPadding(dp(10), 0, dp(10), 0)
            maxLines = 1
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); adapter.notifyDataSetChanged() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { setMargins(dp(14), 0, dp(14), dp(8)) })
        list.adapter = adapter
        list.divider = null
        list.setOnItemClickListener { _, _, position, _ -> toggle(shown[position]) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        goButton.textSize = 15f
        goButton.setTypeface(null, android.graphics.Typeface.BOLD)
        goButton.isAllCaps = false
        goButton.stateListAnimator = null
        goButton.setOnClickListener { if (picked.isNotEmpty()) { onInvite(picked.toList()); dialog.dismiss() } }
        root.addView(goButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { setMargins(dp(14), dp(8), dp(14), 0) })

        refreshChrome()
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun toggle(row: Row) {
        if (row.uid in inCall) return
        if (row.uid in picked) picked.remove(row.uid) else if (slots - picked.size > 0) picked += row.uid
        refreshChrome()
        adapter.notifyDataSetChanged()
    }

    private fun refreshChrome() {
        slotsLabel.text = "还能加 ${maxOf(slots - picked.size, 0)} 人"
        goButton.text = if (picked.isEmpty()) "邀请" else "邀请 ${picked.size} 人"
        goButton.background = IMKitTheme.roundedDrawable(if (picked.isEmpty()) IMKitTheme.controlOff else IMKitTheme.answer, dp(12))
        goButton.setTextColor(if (picked.isEmpty()) IMKitTheme.secondaryText else IMKitTheme.answerIcon)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = shown[position]
            val cell = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                addView(TextView(activity).apply { gravity = Gravity.CENTER; textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(IMKitTheme.primaryText) }, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply { textSize = 13.5f; setTextColor(IMKitTheme.primaryText) })
                    addView(TextView(activity).apply { textSize = 10.5f; setTextColor(IMKitTheme.secondaryText) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
                addView(TextView(activity).apply { gravity = Gravity.CENTER; textSize = 11f }, LinearLayout.LayoutParams(dp(20), dp(20)))
            }
            val avatar = cell.getChildAt(0) as TextView
            val texts = cell.getChildAt(1) as LinearLayout
            val check = cell.getChildAt(2) as TextView
            avatar.text = IMAvatar.initial(row.name)
            avatar.background = IMKitTheme.avatarDrawable(row.uid)
            (texts.getChildAt(0) as TextView).text = row.name
            (texts.getChildAt(1) as TextView).apply { text = row.sub; visibility = if (row.sub.isEmpty()) View.GONE else View.VISIBLE }
            val already = row.uid in inCall
            val checked = already || row.uid in picked
            check.text = if (checked) "✓" else ""
            check.setTextColor(if (already) IMKitTheme.secondaryText else IMKitTheme.answerIcon)
            check.background = if (checked) IMKitTheme.circleDrawable(if (already) IMKitTheme.controlOff else IMKitTheme.answer)
            else IMKitTheme.circleDrawable(android.graphics.Color.TRANSPARENT).apply { setStroke((1.5f * activity.resources.displayMetrics.density).toInt(), 0x59FFFFFF) }
            cell.alpha = if (already) 0.45f else 1f
            return cell
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
