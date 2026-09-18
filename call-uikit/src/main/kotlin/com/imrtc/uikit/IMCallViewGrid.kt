package com.imrtc.uikit

import android.view.View

/*
 通话页的**格子那一半**：群通话九宫格、会议分页画廊、钉住后的演讲者视图
 （MEETING_ROOM_DESIGN §4.1 / §4.4）。

 从 `IMCallView` 拆出来是体量红线（CONVENTIONS §2，那个文件贴着 600 行）；
 这一刀本来也该切：`IMCallView` 负责「整屏怎么组装」，这里负责「格子摆哪儿」。

 与群通话拆成两条路径，是因为设计 §3 的门控表要求「格子组件行为不变」：
 [IMCallGridView] 仍然只回答「给定这一页的格子怎么排」，分页是它的外层。
 群通话完全不经过会议那条路。
 */

/** 群通话九宫格。会议房转交给 [renderMeeting]。 */
internal fun IMCallView.renderGrid(state: IMCallViewState) {
    unpinFull()
    pip.visibility = View.GONE
    if (state.isMeeting) {
        renderMeeting(state)
        return
    }
    pagePill.show("")
    val members = state.tiles
    retireTiles(members.map { it.uid }.toSet())
    applySelf(state, actions?.hasLocalVideo() ?: false, 44)
    val ordered = ArrayList<View>()
    ordered += selfTile
    selfTile.setRounded(true)
    // 层上界按格子数算：**加号格已经没有了**，格数就是真人数（本端 + 远端）。
    val layer = IMGrid.layerFor(members.size + 1, focused = false)
    for (m in members) {
        ordered += tileFor(m, layer)
    }
    /*
     **九宫格里没有加号格**（v3.3 撤掉）。加人入口只有标题栏右上角那一颗
     （`canShowInvite` 同一条判据）：网格里再放一个是同一个动作的第二个入口，
     而它还会占掉一个格位——三个人的通话看起来像四个人，行列也跟着多排一格。
    */
    layoutGrid(ordered, fixedTileCount = 0)
    // 没格子的人视频报 none，并说一句「还有 N 人未显示」（MEETING_ROOM_DESIGN §4.3 / §4.5）。
    state.hiddenMembers.forEach { actions?.reportLayer(it.uid, "none") }
    hiddenPill.show(state.hiddenMembers.size)
}

/**
 * 会议房的舞台：**分页画廊**，双击钉住进演讲者视图。
 *
 * 两种形态共用一套格子（按 uid 复用），所以切换时不会重建渲染器、画面不闪。
 * **没有格子的人一律报 `none`**——引擎在会议房里会把它翻译成「五秒后退订」。
 */
internal fun IMCallView.renderMeeting(state: IMCallViewState) {
    val plan = meeting.plan(state, System.currentTimeMillis())

    /*
     **`none` 要先报，再报这一页的 l/h。**

     会议房里 `none` 就是「排退订」，而新一页的 `l` 是「订阅」，订阅那头顶着 16 路的硬上限。
     反过来先报 `l` 的话，翻页的那一瞬间旧页还整整占着 8 路、新页又要 8 路，
     第 17 路直接被本地拒掉——表现成「翻过去有一格永远是头像」，而且一条报错都不抛。
     （`tileFor` 顺手就把层报了，所以这一段必须在摆格子之前。）
    */
    plan.offscreen.forEach { actions?.reportLayer(it.uid, "none") }

    val keep = plan.visible.map { it.uid }.toMutableSet()
    plan.pinned?.let { keep += it.uid }
    /*
     **翻走 / 被钉住挤下去的人，格子多留五秒再摘。**

     引擎那边翻走并不立刻退订，而是等五秒（`IMRoomMachine` 的迟滞），为的就是
     「左滑看一眼再滑回来」不必重协商。格子这边要是立刻摘掉，翻回来就得重建 SurfaceView、
     重新 attach、重等一个关键帧——引擎省下的那次协商在画面上一点也看不出来。

     钉住那一下更明显：`plan.visible` 这时只剩底部条 3 个人，当前页其余 5 个全被摘掉；
     取消钉住回画廊，整页从头重建，黑一片。多留五秒正好覆盖「钉一下看看就取消」。

     真的离开房间的人不在 `plan.offscreen` 里（那是「还在房里、只是没格子」），照旧立刻摘。
    */
    retireTiles(
        keep,
        linger = plan.offscreen.map { it.uid }.toSet(),
        graceMs = IMMeetingGallery.TILE_GRACE_MS,
    )
    applySelf(state, actions?.hasLocalVideo() ?: false, 44)
    selfTile.setRounded(true)

    if (plan.pinned != null) {
        // 先把九宫格清空：格子马上要挂到演讲者视图上，`grid.tiles` 里还记着它们的话，
        // 转屏时 onLayout 的补摆会把主画面那一格抢回来（见 IMCallView.onLayout）。
        layoutGrid(emptyList(), 0)
        speakerStage.setMain(tileFor(plan.pinned, "h", rounded = false))
        // 底部条第一格恒是自己，与画廊「自己占第一格」同一条规则。
        speakerStage.setStrip(listOf(selfTile) + plan.visible.map { tileFor(it, "l") })
        pagePill.show("")
        hiddenPill.show(0)
    } else {
        speakerStage.detach()
        val layer = IMGrid.layerFor(
            if (plan.fixedTileCount > 0) plan.fixedTileCount else plan.visible.size + 1,
            focused = false,
        )
        val ordered = ArrayList<View>()
        ordered += selfTile
        for (m in plan.visible) ordered += tileFor(m, layer)
        layoutGrid(ordered, plan.fixedTileCount)
        pagePill.show(plan.pageText)
        // 分页之后没有「看不见的人」这回事，只有「在别的页上」——那枚 M1 胶囊就此退役。
        hiddenPill.show(0)
    }
}

/**
 * tileFor 取某人的格子、刷上最新内容、报层上界。**按 uid 复用**，不重建。
 *
 * 渲染器一直挂着，有没有画面交给 `apply` 用 visibility 切（与 iOS 一致）。
 * 原先「没画面就 setVideoView(null)」会把 SurfaceView 摘下来、Surface 当场销毁；
 * 对端一开摄像头就得重建 Surface 再等一个关键帧——白等半秒还闪一下。
 */
private fun IMCallView.tileFor(
    m: IMCallViewState.Member,
    layer: String,
    rounded: Boolean = true,
): IMVideoTile {
    val tile = tiles.getOrPut(m.uid) { IMVideoTile(context) }
    tile.setRounded(rounded)
    tile.setVideoView(actions?.videoViewFor(m.uid))
    tile.apply(
        m.uid, m.uid, m.showsVideo, m.audio, m.speaking, m.volume,
        isRinging = !m.accepted, settled = m.settled, networkLevel = m.networkLevel,
    )
    attachPinGesture(tile, m.uid)
    actions?.reportLayer(m.uid, layer)
    return tile
}

/**
 * 把可用区算出来交给 [IMCallGridView]——**摆放本身在那边**（含「没变就不重挂」那条闸）。
 *
 * 可用区要连**给控制条让出来的那条 padding** 一起扣掉，否则九宫格是在整块屏幕里居中，
 * 最后一行被按钮压着。
 *
 * `fixedTileCount > 0` 时恒按那么多格算行列：会议分页固定 3×3，
 * **最后一页不满时格子和满页一样大**，不放大（§4.1）。
 */
internal fun IMCallView.layoutGrid(ordered: List<View>, fixedTileCount: Int) {
    val gap = dp(IMKitTheme.TILE_GAP_DP)
    // 每格四周各留 gap/2 的外边距，所以可用区要先扣掉一整个 gap，算出来的边长才放得下。
    val width = stage.width - stage.paddingLeft - stage.paddingRight - dp(24) - gap
    val height = stage.height - stage.paddingTop - stage.paddingBottom - dp(8) - gap
    grid.apply(ordered, width, height, gap, fallbackCell = dp(120), fixedTileCount = fixedTileCount)
}

/**
 * 收掉不再需要的远端格子。**卸载要成对**：不摘的话渲染器还占着解码器。
 *
 * [linger] 里的人多留 [graceMs] 再摘——**会议翻页专用**，理由见 [renderMeeting]。
 * 群通话不传，行为与原先一字不差。
 */
internal fun IMCallView.retireTiles(
    wanted: Set<String>,
    linger: Set<String> = emptySet(),
    graceMs: Long = 0,
) {
    val now = System.currentTimeMillis()
    wanted.forEach { tileSeenAt[it] = now }
    tiles.keys.filter { it !in wanted }.forEach { uid ->
        if (graceMs > 0 && uid in linger && now - (tileSeenAt[uid] ?: 0L) < graceMs) return@forEach
        val tile = tiles.remove(uid) ?: return@forEach
        tileSeenAt.remove(uid)
        tile.setVideoView(null)
        (tile.parent as? android.view.ViewGroup)?.removeView(tile)
        if (fullTile === tile) fullTile = null
        // 视图摘了还不算完，Engine 那一侧也要解绑（见 Actions.releaseVideoView）。
        actions?.releaseVideoView(uid)
    }
}
