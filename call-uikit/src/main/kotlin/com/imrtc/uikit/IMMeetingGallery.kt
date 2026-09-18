package com.imrtc.uikit

/**
 * 会议画廊的**状态与算计**：这一页有谁、页码怎么写、钉住了谁
 * （MEETING_ROOM_DESIGN §4.1 / §4.2 / §4.4）。
 *
 * **它不碰视图**：算完交回一个 [Plan]，摆格子仍然是 [IMCallGridView] 的事。
 * 于是分页这件事在「格子怎么排」之外，与设计 §3 的门控表一致——格子组件行为不变。
 *
 * 与 iOS 的 `IMMeetingGallery`、Web 的 `MeetingStage` + `useMeetingOrder` 同职责。
 */
internal class IMMeetingGallery {

    /** 当前页（从 0 起）。 */
    var page: Int = 0
        private set

    /** 钉住的人；空串 = 没钉，在画廊里。 */
    var pinnedUid: String = ""
        private set

    private var order = IMMeetingPager.FirstPageState()

    /** 一次渲染要的全部结论。 */
    data class Plan(
        /** 这一页要摆格子的远端（画廊），或底部条上的人（演讲者视图）。 */
        val visible: List<IMCallViewState.Member>,
        /** 此刻没有格子的人——他们要报 `none`。 */
        val offscreen: List<IMCallViewState.Member>,
        /** 钉住的那个人；null = 画廊。 */
        val pinned: IMCallViewState.Member?,
        /** 底部页码，空串 = 不画（人不够一页时）。 */
        val pageText: String,
        /** 要不要按满页算行列（分页时恒 3×3，最后一页不放大）；0 = 按实际格数。 */
        val fixedTileCount: Int,
    )

    companion object {
        /** 演讲者视图底部条放几格：自己 + 最近 3 位（§4.4）。 */
        const val STRIP_TILES = 4

        /**
         * 翻走 / 被挤下去的格子**多留多久**再摘，与引擎那边的退订迟滞取同一个数。
         *
         * 五秒是 `IMRoomMachine.UNSUBSCRIBE_HYSTERESIS_MS`：引擎正是等这么久才真的退订，
         * 格子早退一步的话滑回来照样要重建、重等关键帧，那次省下的协商白省。
         */
        const val TILE_GRACE_MS = 5_000L
    }

    /** 算这一轮该显示什么。`nowMs` 由调用方给，让这套时间闸在测试里可控。 */
    fun plan(state: IMCallViewState, nowMs: Long): Plan {
        val ordered = reorder(state, nowMs)

        dropPinnedIfGone(ordered.map { it.uid })

        val pinned = ordered.firstOrNull { it.uid == pinnedUid }
        if (pinned != null) {
            val rest = ordered.filter { it.uid != pinned.uid }
            return Plan(
                visible = rest.take(STRIP_TILES - 1),
                offscreen = rest.drop(STRIP_TILES - 1),
                pinned = pinned,
                pageText = "",
                fixedTileCount = 0,
            )
        }

        if (!IMMeetingPager.paged(ordered.size)) {
            // 人不够一页：和群通话完全一样（§4.1），连页码都不画。
            return Plan(ordered, emptyList(), null, "", 0)
        }

        val total = IMMeetingPager.pageCount(ordered.size)
        page = IMMeetingPager.clampPage(page, total)
        val visible = IMMeetingPager.pageSlice(ordered, page)
        val shown = visible.map { it.uid }.toSet()
        return Plan(
            visible = visible,
            offscreen = ordered.filter { it.uid !in shown },
            pinned = null,
            pageText = IMMeetingPager.pageLabel(page, total),
            fixedTileCount = IMMeetingPager.TILES_PER_PAGE,
        )
    }

    /**
     * 钉住的人走了就自动回画廊，否则主画面会一直盯着一个不在房里的 uid。
     *
     * **渲染的最前面也要调一次**：通话页是先按 [pinnedUid] 决定画廊与演讲者视图谁露面，
     * 再调 [plan]。只在 plan 里清的话，那个人离开的那一帧两者会对不上——
     * 演讲者视图露着而主画面已经空了，整块黑到下一次渲染。幂等，多调无害。
     */
    fun dropPinnedIfGone(present: Collection<String>) {
        if (pinnedUid.isNotEmpty() && pinnedUid !in present) pinnedUid = ""
    }

    /** 翻页。`delta` 取 +1（下一页）/ -1（上一页）。返回页码有没有真的变。 */
    fun turn(delta: Int, remoteCount: Int): Boolean {
        val total = IMMeetingPager.pageCount(remoteCount)
        val next = IMMeetingPager.clampPage(page + delta, total)
        if (next == page) return false
        page = next
        return true
    }

    /** 钉住 / 取消钉住。双击同一个人 = 取消（与「再双击一次回去」的直觉一致）。 */
    fun togglePin(uid: String) {
        pinnedUid = if (pinnedUid == uid) "" else uid
    }

    /** 取消钉住（点 📌）。 */
    fun unpin() {
        pinnedUid = ""
    }

    /** 离房 / 结束时归零，下一次进会议不带着上一次的页码与钉住。 */
    fun reset() {
        page = 0
        pinnedUid = ""
        order = IMMeetingPager.FirstPageState()
    }

    /** reorder 把成员按「第一页发言人优先、第二页起进房顺序」排一遍。 */
    private fun reorder(state: IMCallViewState, nowMs: Long): List<IMCallViewState.Member> {
        val people = state.members.values.toList()
        // 一页装得下就没有「换进第一页」这回事，原样用进房顺序。
        if (!IMMeetingPager.paged(people.size)) return people

        order = IMMeetingPager.reorderFirstPage(
            order,
            IMMeetingPager.FirstPageInput(
                uids = people.map { it.uid },
                speaking = people.filter { it.speaking }.map { it.uid }.toSet(),
                withVideo = people.filter { it.showsVideo }.map { it.uid }.toSet(),
                pinned = pinnedUid,
                nowMs = nowMs,
            ),
        )

        val byUid = people.associateBy { it.uid }.toMutableMap()
        val sorted = ArrayList<IMCallViewState.Member>(people.size)
        for (uid in order.order) {
            val found = byUid.remove(uid) ?: continue
            sorted += found
        }
        // 排列还没跟上（刚进来的人）时兜底追加，一个都不许丢——
        // 丢了就是「有人在房里但没有格子」，而且没有任何报错。
        return sorted + people.filter { it.uid in byUid }
    }
}
