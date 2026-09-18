package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson

/**
 * 会议房的**按页订阅**：把「这个人现在看得见吗」翻译成订阅与退订。
 *
 * 见 `im-rtc-server/docs/design/MEETING_ROOM_DESIGN.md` §4.3。
 *
 * ## 为什么挂在 setRemoteLayer 上，而不是新开一个 API
 *
 * **Engine 的公开 API 一个都不新增。** UIKit 与自画 UI 的宿主本来就得按可视尺寸调
 * `setRemoteLayer(uid, layer)`（九宫格报 `l`、放大报 `h`、看不见报 `none`），
 * 这套调用已经**完整地表达了「谁在当前页」**。会议房要的只是把同一组调用翻译成另一套帧：
 * `l/m/h` = 订阅或换层，`none` = 五秒后退订。新开一个 `subscribePage` 的话，
 * 宿主要为「会议」写第二套界面代码，而两套之间的差别只有引擎自己知道。
 *
 * ## 只在 auto_subscribe == "audio" 的房间里生效
 *
 * 通话房是 `all`：服务端全自动订好，`none` 的语义只是**暂停下发**（协议 §3.5），
 * 退订会让那个人永远消失。这条分支写错的后果就是通话房里有人的画面再也回不来，
 * 所以向量里给通话房单列了一组护栏用例（`room_fsm.json` 的
 * `call_room_auto_subscribe_all_layer_only`）。
 */

/**
 * 翻页离开之后**等多久才真的退订**（毫秒）。
 *
 * 退订要重协商（sub PC 少一条 m-line），而翻页是来回的动作：左滑一页看一眼再滑回来
 * 是最常见的操作。立刻退订的话这一来一回要两次协商，回来那一下还得重新等关键帧，
 * 画面黑一下。等五秒，来回翻的那一种就一次协商都不用。
 *
 * 定时器不在状态机里（状态机是纯函数）：由帧循环按 [IMRoomContext.pendingUnsubscribe] 排，
 * 到点喂一个内部事件回来。
 */
internal const val UNSUBSCRIBE_HYSTERESIS_MS = 5_000L

/**
 * 同时订阅的视频路数上限（手机：本页 8 + 迟滞 8）。
 *
 * **这个数是 SDP 墙定的，不是算力定的**：sub offer 每订一路多一条 m-line，
 * 整帧超过 64 KiB 就发不出去，而发不出去的后果是这个人的下行**永久冻结**（设计 §1.3）。
 * 所以它是硬上限，不是一个可以「先超一点看看」的建议值。
 */
internal const val MAX_SUBSCRIBED_VIDEO = 16

/** 这个房间的视频是不是由客户端按页订阅的。 */
internal fun usesPagedVideo(ctx: IMRoomContext): Boolean = ctx.autoSubscribe == "audio"

/**
 * 把一次 `setRemoteLayer` 翻译成订阅动作。
 *
 * - `none`：**先发 `room.update_layer{none}` 立刻停包**，再排五秒的退订。
 *   两件事都要：停包省的是带宽（这一下就生效），退订省的是 m-line（五秒后才值得付那次协商）。
 * - `l/m/h`：撤掉还没到点的退订；订过就只换层（**不重协商**，这正是迟滞想省下的那一次），
 *   没订过就订。
 */
internal fun pagedUpdateLayer(
    ctx: IMRoomContext,
    trackId: String,
    maxLayer: String,
): IMMachineOutput<IMRoomContext> =
    if (maxLayer == "none") pageOut(ctx, trackId) else pageIn(ctx, trackId, maxLayer)

private fun pageOut(ctx: IMRoomContext, trackId: String): IMMachineOutput<IMRoomContext> {
    // 没订过的不用退；**正在退的也不用**——那条 `room.unsubscribe` 已经在路上，
    // 再排一次迟滞，五秒后会往一条已经不存在的订阅上再打一发，
    // 而它回来的 1301 会被 dropFailedSubscribe 当成「订阅失败」处理。
    val state = ctx.subscribe[trackId] ?: return IMRoomMachine.out(ctx)
    if (state == IMSubscribeState.UNSUBSCRIBING) return IMRoomMachine.out(ctx)
    // 已经排着退订的也不用再报一次 none——它早就不出包了，
    // 再报一次只会把五秒的计时重新拉长。
    if (trackId in ctx.pendingUnsubscribe) return IMRoomMachine.out(ctx)

    return IMRoomMachine.out(
        ctx.copy(
            layers = ctx.layers + (trackId to "none"),
            pendingUnsubscribe = ctx.pendingUnsubscribe + trackId,
        ),
        send = listOf(
            IMOutgoingFrame(
                IMFrameType.ROOM_UPDATE_LAYER,
                mapOf("track_id" to IMJson.Str(trackId), "max_layer" to IMJson.Str("none")),
            ),
        ),
    )
}

private fun pageIn(
    ctx: IMRoomContext,
    trackId: String,
    maxLayer: String,
): IMMachineOutput<IMRoomContext> {
    val kept = ctx.copy(pendingUnsubscribe = ctx.pendingUnsubscribe - trackId)
    val state = kept.subscribe[trackId]
    if (state == IMSubscribeState.SUBSCRIBING || state == IMSubscribeState.SUBSCRIBED) {
        return IMRoomMachine.out(
            kept.copy(layers = kept.layers + (trackId to maxLayer)),
            send = listOf(
                IMOutgoingFrame(
                    IMFrameType.ROOM_UPDATE_LAYER,
                    mapOf("track_id" to IMJson.Str(trackId), "max_layer" to IMJson.Str(maxLayer)),
                ),
            ),
        )
    }

    /*
     **先问腾不腾得出位置，再动手**：本地拒绝要求不发帧、状态不变，
     所以不能先把强制退订发出去再反悔。

     排着迟滞的那些都还占着 m-line，它们是唯一能腾出来的位置。全退了还满，
     就**只可能是调用方一次要看超过 16 路视频**——翻页翻不出这种局面（一页 8 路），
     那是界面那边的 bug，不该由引擎悄悄吞掉。

     **不排队**：排队要有一个「什么时候轮到你」的触发点，而这里没有——
     订阅位是靠翻页腾出来的，队列只会安静地越积越长，
     表现成「第 17 个人的画面永远不出来，也没有任何报错」。
    */
    if (countLiveVideo(kept) - kept.pendingUnsubscribe.size >= MAX_SUBSCRIBED_VIDEO) {
        return IMRoomMachine.localReject(ctx)
    }

    val send = mutableListOf<IMOutgoingFrame>()
    val freed = freeSlot(kept, send)
    return IMRoomMachine.out(
        freed.copy(
            subscribe = freed.subscribe + (trackId to IMSubscribeState.SUBSCRIBING),
            layers = freed.layers + (trackId to maxLayer),
        ),
        send = send + IMOutgoingFrame(
            IMFrameType.ROOM_SUBSCRIBE,
            mapOf("track_id" to IMJson.Str(trackId), "max_layer" to IMJson.Str(maxLayer)),
        ),
    )
}

/**
 * 让迟滞到点：把排着的退订真的发出去。
 *
 * [trackId] 传 null 时把**全部**排着的一次清掉（一致性向量用的就是这一种）。
 * 帧循环按 track 排定时器，所以线上走的是带 trackId 的那一路。
 *
 * **通话房什么都不做**：它的 `none` 只是暂停，退订会让那个人的画面再也回不来。
 */
internal fun flushHysteresis(
    ctx: IMRoomContext,
    trackId: String?,
): IMMachineOutput<IMRoomContext> {
    if (!usesPagedVideo(ctx)) return IMRoomMachine.out(ctx)
    /*
     **不在 joined 就按兵不动。**

     断网重连期间这只定时器照样会到点。此时把 `room.unsubscribe` 发出去等于扔进一条死连接：
     它没有 reject 可回（退订帧没有回滚路径），那条 track 会**永远卡在 UNSUBSCRIBING**——
     16 路的账从此少算一路，攒够几次翻页就再也订不上新的人；
     更糟的是它仍占着 sub PC 的 m-line，offer 还在往 64 KiB 上顶。

     留在 pendingUnsubscribe 里不动即可：帧循环每轮按清单对账，
     这一条还在清单上，定时器会**重新排一只**，等房间回到 joined 再退。
    */
    if (ctx.state != IMRoomState.JOINED) return IMRoomMachine.out(ctx)
    val targets = if (trackId == null) ctx.pendingUnsubscribe else ctx.pendingUnsubscribe.filter { it == trackId }
    if (targets.isEmpty()) return IMRoomMachine.out(ctx)

    var next = ctx.copy(pendingUnsubscribe = ctx.pendingUnsubscribe - targets.toSet())
    val send = mutableListOf<IMOutgoingFrame>()
    for (id in targets) next = unsubscribeNow(next, id, send)
    return IMRoomMachine.out(next, send = send)
}

/**
 * 把已经不存在的 track 从待退订队列里摘掉。
 *
 * 人走了、对方 unpublish 了，那条订阅本来就没了。不摘的话定时器到点会发一条
 * 打在空处的 `room.unsubscribe`（服务端幂等，但帧循环会为它多排一轮）。
 */
internal fun dropPending(pending: List<String>, gone: Set<String>): List<String> =
    pending.filterNot { it in gone }

private fun unsubscribeNow(
    ctx: IMRoomContext,
    trackId: String,
    send: MutableList<IMOutgoingFrame>,
): IMRoomContext {
    val state = ctx.subscribe[trackId]
    if (state == null || state == IMSubscribeState.UNSUBSCRIBING) return ctx
    send += IMOutgoingFrame(IMFrameType.ROOM_UNSUBSCRIBE, mapOf("track_id" to IMJson.Str(trackId)))
    return ctx.copy(
        subscribe = ctx.subscribe + (trackId to IMSubscribeState.UNSUBSCRIBING),
        layers = ctx.layers - trackId,
    )
}

/**
 * 在订满 16 路时**提前**把排着的退订执行掉，腾出位置。
 *
 * 快速连翻几页就会踩到：第一页还在五秒迟滞里，第二页也翻走了，第三页要订新的。
 * 迟滞是一种便利，不是承诺——位置不够时先退最早翻走的那一页，正是想退的顺序。
 */
private fun freeSlot(ctx: IMRoomContext, send: MutableList<IMOutgoingFrame>): IMRoomContext {
    var next = ctx
    while (countLiveVideo(next) >= MAX_SUBSCRIBED_VIDEO && next.pendingUnsubscribe.isNotEmpty()) {
        val oldest = next.pendingUnsubscribe.first()
        next = unsubscribeNow(next, oldest, send).copy(
            pendingUnsubscribe = next.pendingUnsubscribe.drop(1),
        )
    }
    return next
}

/** 数此刻**占着 m-line** 的视频路数：订上的与正在订的都算，正在退的不算。 */
private fun countLiveVideo(ctx: IMRoomContext): Int =
    ctx.subscribe.count { (trackId, state) ->
        state != IMSubscribeState.UNSUBSCRIBING && ctx.remoteTracks[trackId]?.kind == "video"
    }
