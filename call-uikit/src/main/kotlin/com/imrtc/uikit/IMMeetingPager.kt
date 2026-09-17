package com.imrtc.uikit

import kotlin.math.ceil

/**
 * 会议分页画廊的**纯算术**与**第一页排谁**（MEETING_ROOM_DESIGN §4.1 / §4.2）。
 *
 * 分页是格子容器的**外层**：这里只回答「这一页该放哪几个人」与「第一页该是谁」，
 * 格子怎么排仍然是 [IMGrid] 的事，[IMCallGridView] 本身一行不用改。
 * 群通话上限 9 人，永远只有一页，走的还是老路径。
 *
 * Web 的同一层是 `packages/call-uikit-react/src/layout/pager.ts` 与 `firstPage.ts`，
 * iOS 是 `IMMeetingPager.swift`，三端跑同一组场景。
 */
internal object IMMeetingPager {

    /** 会议每页几格。**自己占第一格**，所以远端只剩 8 个位置。 */
    const val TILES_PER_PAGE = IMGrid.MAX_TILES

    /** 每页放得下几个远端。 */
    const val REMOTES_PER_PAGE = IMGrid.MAX_REMOTE_TILES

    /** 连续说话多久才够格换进第一页。 */
    const val PROMOTE_AFTER_MS = 1_500L

    /** 在第一页待满这么久的人才可能被换出去。 */
    const val MIN_STAY_MS = 10_000L

    /** 第一页每这么久最多换一个人。 */
    const val SWAP_COOLDOWN_MS = 2_000L

    /**
     * 总页数，**至少 1**（一个远端都没有时也有「第 1 页」，上面只有自己）。
     *
     * 每页都留一格给自己，所以 49 个远端是 7 页而不是 6 页——这是「自己恒在第一格」
     * 那条规则的代价，与群通话保持一致，不给翻页另立一套。
     */
    fun pageCount(remoteCount: Int, perPage: Int = REMOTES_PER_PAGE): Int {
        if (perPage <= 0) return 1
        return maxOf(1, ceil(remoteCount.toDouble() / perPage).toInt())
    }

    /** 把页码夹回 `0 until total`。人走了导致页数变少时要用它收回来。 */
    fun clampPage(page: Int, total: Int): Int = page.coerceIn(0, maxOf(total - 1, 0))

    /**
     * 取某一页的远端。
     *
     * **最后一页不满时不补、也不放大**：格子和满页一样大，从左上往下排（§4.1）。
     * 放大的话层会从 l 跳到 m、还要多等一次关键帧，翻页时整屏重排。
     * 「不放大」由容器恒按满页算行列来保证，这里只负责切片。
     */
    fun <T> pageSlice(items: List<T>, page: Int, perPage: Int = REMOTES_PER_PAGE): List<T> {
        if (perPage <= 0) return items
        val start = page * perPage
        if (start >= items.size) return emptyList()
        return items.subList(start, minOf(start + perPage, items.size))
    }

    /** 这个房间此刻要不要分页。**总人数 ≤ 9 时和群通话完全一样**：没有页码、没有滑动（§4.1）。 */
    fun paged(remoteCount: Int, perPage: Int = REMOTES_PER_PAGE): Boolean = remoteCount > perPage

    /** 底部页码，`1 / 7` 这样。它**取代**了 M1 的「还有 N 人未显示」胶囊（§4.5）。 */
    fun pageLabel(page: Int, total: Int): String = "${minOf(page + 1, total)} / $total"

    /**
     * 第一页排谁的全部记账（§4.2）。**调用方持有它**，本对象只做纯变换。
     *
     * ## 为什么不是「按说话时间排序」
     *
     * 按说话时间直接排序的话，两个人来回搭话就会让第一页每 300ms 重排一次——
     * `room.active_speakers` 本来就是 300ms 一条。用户看到的是格子不停地跳位置，谁也看不清。
     * 所以这里的每一条规则都是**防抖**：说够 1.5 s 才晋升、待满 10 s 才可能被换走、
     * 每 2 s 最多换一个人。
     *
     * ## 第二页往后是稳定的
     *
     * 这里只动第一页。第二页起恒按**进房顺序**——翻到后面的人不该因为有人说话而被挪走。
     */
    data class FirstPageState(
        /** 远端的当前排列。前 `firstPageSize` 个就是第一页。 */
        val order: List<String> = emptyList(),
        /** uid → 这一轮连续说话是从什么时候开始的。 */
        val speakingSince: Map<String, Long> = emptyMap(),
        /** uid → 最近一次说话的时刻。从没说过话的人没有这一条。 */
        val lastSpokeAt: Map<String, Long> = emptyMap(),
        /** uid → 进入第一页的时刻，用来判「待满 10 s」。 */
        val enteredAt: Map<String, Long> = emptyMap(),
        /** 上一次换人的时刻，用来限频。 */
        val lastSwapAt: Long = 0,
    )

    /** 一次重排要看的全部外部事实。 */
    data class FirstPageInput(
        /** 此刻房里的远端 uid，**按进房顺序**。 */
        val uids: List<String>,
        /** 此刻正在说话的人。 */
        val speaking: Set<String>,
        /** 开着摄像头的人。同等条件下先换走没开摄像头的。 */
        val withVideo: Set<String>,
        /** 钉住的人（空串 = 没钉）。**钉住的人不许被换走**。 */
        val pinned: String,
        val nowMs: Long,
        /** 第一页放得下几个远端。 */
        val firstPageSize: Int = REMOTES_PER_PAGE,
    )

    /**
     * 走一次规则，返回新的记账。
     *
     * 调用时机：`room.active_speakers` 到了、有人进出、以及界面自己的节拍——
     * **多调几次无害**，每一条规则都带时间闸。
     */
    fun reorderFirstPage(state: FirstPageState, input: FirstPageInput): FirstPageState =
        promote(trackSpeaking(syncMembers(state, input), input), input)

    /**
     * 让排列跟上房里的人。
     *
     * **有人离开时后面的人依次前补，只动那一页；新人追加到末尾**（§4.2）。
     * 直接按 `uids` 重排的话，第一页里熬上来的人会在任何一次进出时被打回进房顺序。
     */
    private fun syncMembers(state: FirstPageState, input: FirstPageInput): FirstPageState {
        val present = input.uids.toSet()
        val kept = state.order.filter { it in present }
        val known = kept.toSet()
        val order = kept + input.uids.filter { it !in known }
        if (order == state.order) return state

        // 一开始就在第一页的人（首次进房、或补位补上来的）也要记进入时刻，
        // 否则 10 s 的驻留判据没有起点，他们会被第一个说话的人立刻顶掉。
        val entered = state.enteredAt.toMutableMap()
        for (uid in order.take(maxOf(input.firstPageSize, 0))) {
            entered.getOrPut(uid) { input.nowMs }
        }
        return state.copy(order = order, enteredAt = entered.filterKeys { it in present })
    }

    /** 记「这一轮连续说了多久」与「最近一次说话是什么时候」。 */
    private fun trackSpeaking(state: FirstPageState, input: FirstPageInput): FirstPageState {
        val since = mutableMapOf<String, Long>()
        val lastSpoke = state.lastSpokeAt.toMutableMap()
        for (uid in input.speaking) {
            // 上一轮就在说的接着算；刚开口的从现在起算。
            since[uid] = state.speakingSince[uid] ?: input.nowMs
            lastSpoke[uid] = input.nowMs
        }
        val present = input.uids.toSet()
        return state.copy(speakingSince = since, lastSpokeAt = lastSpoke.filterKeys { it in present })
    }

    /** 把够格的人换进第一页，一次最多一个。 */
    private fun promote(state: FirstPageState, input: FirstPageInput): FirstPageState {
        if (input.nowMs - state.lastSwapAt < SWAP_COOLDOWN_MS) return state

        val first = state.order.take(maxOf(input.firstPageSize, 0))
        val onFirst = first.toSet()

        // 候选：不在第一页、且已经连续说了 ≥ 1.5 s。多个候选时挑说得最久的那个。
        val candidate = state.speakingSince.entries
            .filter { it.key !in onFirst && it.key in state.order }
            .filter { input.nowMs - it.value >= PROMOTE_AFTER_MS }
            .minWithOrNull(compareBy({ it.value }, { it.key }))
            ?.key ?: return state

        val victim = pickVictim(state, input, first) ?: return state
        val victimIndex = state.order.indexOf(victim)
        val candidateIndex = state.order.indexOf(candidate)
        if (victimIndex < 0 || candidateIndex < 0) return state

        val order = state.order.toMutableList()
        // **换位置而不是插队**：插队会把第一页后半段整体挪一格，看上去像全屏重排。
        order[victimIndex] = candidate
        order[candidateIndex] = victim
        return state.copy(
            order = order,
            enteredAt = state.enteredAt + (candidate to input.nowMs),
            lastSwapAt = input.nowMs,
        )
    }

    /**
     * 挑第一页里该让位的那个：**最久没发言的**，同等条件下先换没开摄像头的。
     *
     * 只考虑**待满 10 s** 的人；钉住的人永远不动（他是被明确指定要看的）。
     * 一个都挑不出来就这一轮不换——宁可让候选多等一会儿，也不要把刚上来的人立刻顶掉。
     */
    private fun pickVictim(
        state: FirstPageState,
        input: FirstPageInput,
        first: List<String>,
    ): String? {
        var victim: String? = null
        var victimSpoke = Long.MAX_VALUE
        var victimHasVideo = true
        for (uid in first) {
            if (uid == input.pinned) continue
            val entered = state.enteredAt[uid] ?: input.nowMs
            if (input.nowMs - entered < MIN_STAY_MS) continue
            // 从没说过话的排在最前面（0 比任何时刻都早）。
            val spoke = state.lastSpokeAt[uid] ?: 0L
            val hasVideo = uid in input.withVideo
            val better = spoke < victimSpoke || (spoke == victimSpoke && victimHasVideo && !hasVideo)
            if (!better) continue
            victim = uid
            victimSpoke = spoke
            victimHasVideo = hasVideo
        }
        return victim
    }
}
