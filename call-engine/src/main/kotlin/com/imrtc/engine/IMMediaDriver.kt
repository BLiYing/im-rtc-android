package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMEngineContext
import com.imrtc.engine.statemachine.IMRoomState

/**
 * 状态迁移带来的媒体动作。**媒体只跟着状态走，不自己决定什么时候起停。**
 *
 * 从 [IMCallEngine] 拆出来是体量红线（CONVENTIONS §2）；这一段本来也是独立的关注点——
 * 「状态从 before 变到 after，媒体层该起、该发布、该停、该补静音」。全部在 engine 线程上调。
 */
internal class IMMediaDriver(
    private val media: IMMediaAdapter?,
    private val publisher: IMLocalPublisher,
    private val muteBook: IMMuteBook,
    /** 补发一条 `room.mute`。走门面的状态推进入口，所以由门面给。 */
    private val sendMute: (trackId: String, muted: Boolean) -> Unit,
) {

    fun drive(before: IMEngineContext, after: IMEngineContext) {
        val adapter = media ?: return

        // 拿到 room_token 的那一刻把媒体拉起来。
        // **判据是 room_token 从无到有，不是「进入某个状态」**：主叫的 idle→inviting 那一步
        // 还没有票，等 call.connected 到了才有；按状态判会一次都不触发（第一版就是这么错的）。
        if (before.call.roomToken.isEmpty() && after.call.roomToken.isNotEmpty()) {
            adapter.start(emptyList())
        }

        // 进房成功：先确保媒体起来了，再发布本端 Track（视频发不发看摄像头意图，见 [IMLocalPublisher]）。
        // **会议房是直接 joinRoom 的，压根不经过 call**——只按 room_token 判的话这里一次都不会起，
        // 真机上的症状是「还没 start 就 publish，忽略」，人进了房但谁也听不见谁。
        //
        // **「新进房」不包括「重连恢复」**：`resumed=true` 时房间机把 reconnecting 推回 joined
        // （`IMRoomMachine.resume`），只看「不是 joined → 是 joined」会把它也当成刚进房，
        // 于是每恢复一次就重复发一整套 audio+video：多两条 `room.publish`、pub 上多挂一组
        // transceiver，`startCapture()` 还会在旧 capturer 没停的情况下再开一个摄像头采集
        // （`capturer` 字段被覆盖，旧的那个再也停不掉）。**恢复的前提就是服务端那边的发布关系还在**，
        // 本来什么都不用补。
        if (isFreshJoin(before, after)) {
            adapter.start(emptyList())
            publisher.publishDefaults(after, cameraMuted = muteBook.wanted("video") == true)
        } else if (after.room.state == IMRoomState.JOINED && before.room.state != IMRoomState.JOINED) {
            /*
              **刚变成 joined 却不发布，要留一条。**

              这是本端唯一会跳过发布的地方（恢复回来时那边的发布关系还在，本来就不该补）。
              但「进了房却没发布」也正是一整类静默故障的样子：web 端同一件事就因为
              房号没被清零而一声不响地吃掉整个发布——界面正常、日志空白、
              对端只看到首字母头像（真机 2026-09-09 14:43）。

              判据取「刚变成 joined」而不是「没发布」，所以一次恢复只出现一条，不吵。
            */
            IMRTCLog.d("engine", "进房但不发布：从 ${before.room.state.wire} 恢复回来的，发布关系还在")
            // 断线期间点了「开摄像头」的，那一路视频还没发过——不补的话按钮亮着却没有画面。
            if (muteBook.wanted("video") == false) publisher.publishCameraIfMissing(after)
        }

        // 通话结束 / 离房：停掉媒体。**必须可重入**，挂断与被踢会先后到达。
        //
        // **判据是「媒体还有没有人要」，不是某一步的 joined→idle**：会议房离房走的是
        // `joined →(leave)→ leaving →(leave.ok)→ idle` **两次 input**，没有任何一次同时
        // 满足 before=joined 且 after=idle，于是 stop() 一次都不会调。后果不是「多占点内存」——
        // 两条 PeerConnection 开着 GATHER_CONTINUALLY 继续活着，每 5 分钟重采一轮候选，
        // 一路发上去换回 `1203 not_in_room`（真机日志里从 20:39 一直刷到 21:24）。
        // 「断线 → reconnecting → 被踢 → idle」「强制收场」也是同一个漏法，一并被这条判据盖住。
        if (mediaWanted(before) && !mediaWanted(after)) {
            adapter.stop()
            publisher.clear()
            // 意图跟着这一轮媒体一起作废：下一通电话的开关由界面重新决定，
            // 留着的话会变成「上一通静音过，这一通莫名其妙也是静音的」。
            muteBook.clear()
        }
    }

    /**
     * 拿到 `track_id` 的那一刻，把攒下的静音意图补做一遍。
     *
     * **两件事都要补**：一是再 `media.setMuted` 一次——轨道是刚才 `publishDefaults`
     * 现造的，造出来默认是开着的，之前那次调用落在了一个还不存在的轨道上；
     * 二是补发 `room.mute`，让服务端与对端的界面也对上。
     */
    fun flushPendingMutes(before: IMEngineContext, after: IMEngineContext) {
        val pending = muteBook.pending(
            publisher.tracks,
            before.room.publishTrackIds,
            after.room.publishTrackIds,
        )
        for ((kind, trackId, muted) in pending) {
            IMRTCLog.i("engine", "补做发布前攒下的静音 kind=$kind muted=$muted")
            media?.setMuted(kind, muted)
            sendMute(trackId, muted)
        }
    }

    /** 媒体该不该活着：房间与通话只要还有一个不在 idle，就还有人要它。 */
    private fun mediaWanted(ctx: IMEngineContext) =
        ctx.room.state != IMRoomState.IDLE || ctx.call.state != IMCallState.IDLE

    /**
     * 这一步是不是**真的新进了一个房间**——要发布本端 Track 的那种。
     *
     * 从 reconnecting 回到 joined 是**恢复**，不是新进房：那边的发布关系一直都在。
     */
    private fun isFreshJoin(before: IMEngineContext, after: IMEngineContext) =
        after.room.state == IMRoomState.JOINED &&
            before.room.state != IMRoomState.JOINED &&
            before.room.state != IMRoomState.RECONNECTING
}
