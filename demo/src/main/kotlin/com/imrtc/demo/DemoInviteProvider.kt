package com.imrtc.demo

import android.os.Handler
import android.os.Looper
import com.imrtc.uikit.IMInviteCandidate
import com.imrtc.uikit.IMInviteCandidatesCallback
import com.imrtc.uikit.IMInviteContext
import com.imrtc.uikit.IMInviteMemberProvider

/**
 * Demo 的「按通话向宿主要候选人」实现（`HOST_INTEGRATION_DESIGN.md` §3.4 验收用假数据）。
 *
 * - 真实的 Demo 账号（[ContactPicker]）排最前面；后面补几十个假成员凑够分页，
 *   验 `IMInvitePicker` 的「滚到底取下一页」。
 * - 搜索词 `fail` 模拟失败（出「加载失败」+ 重试）；`slow` 模拟超时（15s 才回调，
 *   比 Kit 的 10s 超时长，验超时态）。
 * - 不接管选人页（`presentInvitePicker` 用默认的 false），走 Kit 自带的 [com.imrtc.uikit.IMInvitePicker]。
 *
 * **这一层完全是宿主代码**——真实宿主会换成群成员接口（分页、搜索都交给自己的后端）。
 */
internal class DemoInviteProvider : IMInviteMemberProvider {

    private val main = Handler(Looper.getMainLooper())

    override fun loadCandidates(
        ctx: IMInviteContext,
        query: String,
        cursor: String?,
        callback: IMInviteCandidatesCallback,
    ) {
        when (query.trim()) {
            "fail" -> main.post { callback.onError(dt("demo.invite.fail")) }
            "slow" -> main.postDelayed({ callback.onResult(pageFor(query, cursor), null) }, SLOW_DELAY_MS)
            else -> main.post {
                val page = pageFor(query, cursor)
                val pageIndex = (cursor?.toIntOrNull() ?: 0)
                val hasMore = (pageIndex + 1) * PAGE_SIZE < matching(query).size
                callback.onResult(page, if (hasMore) (pageIndex + 1).toString() else null)
            }
        }
    }

    private fun matching(query: String): List<String> {
        val q = query.trim()
        return ALL_UIDS.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
    }

    private fun pageFor(query: String, cursor: String?): List<IMInviteCandidate> {
        val pageIndex = cursor?.toIntOrNull() ?: 0
        return matching(query)
            .drop(pageIndex * PAGE_SIZE)
            .take(PAGE_SIZE)
            .map { uid ->
                if (uid in ContactPicker.all()) {
                    IMInviteCandidate(uid)
                } else {
                    IMInviteCandidate(uid, subtitle = dt("demo.invite.fakeSubtitle"))
                }
            }
    }

    private companion object {
        const val PAGE_SIZE = 12
        const val FAKE_COUNT = 40
        const val SLOW_DELAY_MS = 15_000L

        /** 真实 Demo 账号排前面，后面是假成员。 */
        val ALL_UIDS: List<String> = ContactPicker.all() + (1..FAKE_COUNT).map { "fake-%02d".format(it) }
    }
}
