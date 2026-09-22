package com.imrtc.demo

import android.app.Activity
import android.app.AlertDialog

/**
 * 群呼选人（草图 §05-I）。**联系人来自本地写死的列表**——CONVENTIONS §11：
 * 不内置好友/联系人系统，Demo 的联系人来自本地，宿主用自己的。
 *
 * 上限 8 个：自己 + 8 = 9 人，正好 3×3。
 *
 * **名单要 9 个人。** 自己会被过滤掉，8 个名字只剩 7 个可选，于是最多凑出 8 格，
 * **永远看不到真正的九宫格**——而九宫格正是这一屏存在的理由。
 *
 * **2026-09-09 从 9 个加到 16 个**：9 个人剔掉自己剩 8 个，**恰好等于 [LIMIT]**，
 * 于是「选到第 9 个该被挡住」这条根本走不到——上限逻辑一直没被验过。
 * 加到 16 之后可选 15 个，能真的撞上限。九宫格该有的 9 个人依然凑得出。
 */
internal object ContactPicker {

    /** 与 iOS Demo 同一份名单，双端联调时不用互相翻文档。 */
    private val ALL = listOf(
        "alice", "bob", "carol", "dave", "erin", "frank", "grace", "heidi", "ivan",
        "judy", "mallory", "niaj", "olivia", "peggy", "rupert", "sybil",
    )

    const val LIMIT = 8

    /** 整份名单（含自己），给 Kit 的「添加成员」候选用。 */
    fun all(): List<String> = ALL

    /**
     * 弹多选。
     *
     * 用对话框而不是再推一屏：Android 这边 Demo 是单 Activity + 底部 tab，
     * 推一屏就得自己维护返回栈，而这一步的信息量只有「勾几个名字」。
     */
    fun show(activity: Activity, selected: List<String>, onDone: (List<String>) -> Unit) {
        // **把自己排除掉**：呼叫名单里含主叫的话，服务端会以 1004 拒掉**整通**电话。
        val contacts = ALL.filter { it != DemoSession.username }
        val checked = BooleanArray(contacts.size) { selected.contains(contacts[it]) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(titleFor(checked))
            .setMultiChoiceItems(contacts.toTypedArray(), checked) { _, _, _ -> }
            .setPositiveButton(dt("demo.picker.done")) { _, _ ->
                // 按名单顺序回传，跟勾选顺序无关——稳定的顺序更好核对。
                onDone(contacts.filterIndexed { index, _ -> checked[index] })
            }
            .setNegativeButton(dt("demo.picker.cancel"), null)
            .create()

        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                val wantOn = !checked[position]
                if (wantOn && checked.count { it } >= LIMIT) {
                    // 到上限就不响应——比弹二级提示温和，而且标题一直显示着 8 / 8。
                    dialog.listView.setItemChecked(position, false)
                    return@setOnItemClickListener
                }
                checked[position] = wantOn
                dialog.listView.setItemChecked(position, wantOn)
                dialog.setTitle(titleFor(checked))
            }
        }
        dialog.show()
    }

    private fun titleFor(checked: BooleanArray) = dt("demo.picker.titleCount", "n" to checked.count { it }, "max" to LIMIT)
}
