package com.imrtc.uikit

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.IMNetworkQuality
import com.imrtc.engine.IMSpeaker

/**
 * 包在宿主 listener 外面的一层：先喂 Kit，再原样转给宿主。
 *
 * **每一条都只用回调给的参数**——没有一处去 engine 里"多问一句"。
 */
internal class IMKitListener(private val host: IMCallEngineListener) : IMCallEngineListener {

    private val state get() = IMCallKit.state

    // 顶部橙条：正在重连 / 连接已断开（规范 §08）。通话不结束、计时器继续走。
    override fun onConnected(sessionId: String, resumed: Boolean) {
        IMCallKit.update(IMCallViewReducer.connection(state, IMCallViewState.Connection.OK))
        host.onConnected(sessionId, resumed)
    }

    override fun onDisconnected(code: Int, reason: String) {
        // 4401 是「换票再来」、4403 是被踢，其余都是会自己回来的断线。
        //
        // **已经是 LOST 就不再翻回 RECONNECTING**：放弃是终态，而 onKickedOut 与
        // onDisconnected 的先后在不同放弃路径上并不一致——收到 close 那两条是先断后踢，
        // 而握手当场被拒是先踢、close 后到。翻回去的症状是顶条上永远写着「正在重连」，
        // 底下那条连接却根本不会再重连。重连成功时 onConnected 会把它拨回 OK。
        val lost = code == 4403 || state.connection == IMCallViewState.Connection.LOST
        IMCallKit.update(IMCallViewReducer.connection(state, if (lost) IMCallViewState.Connection.LOST else IMCallViewState.Connection.RECONNECTING))
        host.onDisconnected(code, reason)
    }

    override fun onKickedOut(reason: IMKickedOutReason) {
        IMCallKit.update(IMCallViewReducer.connection(state, IMCallViewState.Connection.LOST))
        host.onKickedOut(reason)
    }

    override fun onTokenWillExpire(expiresAtMs: Long) {
        // Kit 对票期没有界面表达——换票是宿主的事（票从宿主的账号体系来）。
        // 这里只做透传，不吞掉：吞了的话用 Kit 的宿主就收不到这个回调了。
        host.onTokenWillExpire(expiresAtMs)
    }

    /** 加人的两条失败分支（交互稿 §05）：满员出提示；非主叫把入口藏掉。别的错误码由宿主处理。 */
    override fun onError(code: Int, message: String) {
        when (code) {
            // 加人被拒：出提示 / 藏入口，**并且把刚摆上去的占位格收回来**。
            1202 -> {
                IMCallKit.hint("通话已满员（最多 9 人）")
                IMCallKit.revokeLastInvite()
            }
            1407 -> {
                IMCallKit.update(IMCallViewReducer.inviteDenied(state))
                IMCallKit.revokeLastInvite()
            }
            // 媒体层报「没权限 / 没设备」：摄像头拿不到就降级为语音继续。
            2001, 2002 -> if (state.mediaType == "video") IMCallKit.update(IMCallViewReducer.cameraBlocked(state))
        }
        host.onError(code, message)
    }

    override fun onCallReceived(
        callId: String,
        caller: String,
        calleeIds: List<String>,
        mediaType: String,
        isGroup: Boolean,
    ) {
        // 名单里含自己，摆格子之前先去掉——「自己」不是远端成员。
        val others = calleeIds.filter { it != IMCallKit.engine?.uid }
        IMCallKit.update(IMCallViewReducer.incoming(state, callId, caller, others, mediaType, isGroup))
        host.onCallReceived(callId, caller, calleeIds, mediaType, isGroup)
    }

    /** 通话中有人打进来，服务端已经替我们回了忙线——**只提示，不动当前通话**。 */
    override fun onCallMissed(callId: String, caller: String, reason: String) {
        IMCallKit.hint("$caller 来电，已自动回复忙线")
        host.onCallMissed(callId, caller, reason)
    }

    override fun onCallBegin(callId: String, roomId: String, mediaType: String, role: String) {
        IMCallKit.update(IMCallViewReducer.begin(state, callId, roomId, mediaType, role))
        host.onCallBegin(callId, roomId, mediaType, role)
    }

    override fun onCallEnd(callId: String, reason: String, durationSec: Long, endedBy: String) {
        IMCallKit.stopTimer()
        // 还在响铃的来电直接收起，不留结束画面：被叫这一侧什么都还没做。主叫那一侧要停一下说明原因。
        if (state.phase == IMCallViewState.Phase.INCOMING) {
            IMCallKit.update(IMCallViewReducer.reset())
        } else {
            IMCallKit.update(IMCallViewReducer.ended(state, reason, durationSec))
            // 停一会让用户看清结束原因再收场。**说不清原因的那几种要停久一点**（与 iOS / Web 同一张表）。
            val hold = if (reason == "hangup" || reason == "cancel") 1_500L else 3_000L
            IMCallKit.main.postDelayed({ if (state.phase == IMCallViewState.Phase.ENDED) IMCallKit.update(IMCallViewReducer.reset()) }, hold)
        }
        host.onCallEnd(callId, reason, durationSec, endedBy)
    }

    // 四个便利事件只在 1v1 抛，随后必有 onCallEnd——所以这里只做提示，不改阶段。
    override fun onCallCancelled(uid: String) = host.onCallCancelled(uid)
    override fun onCallRejected(uid: String) { IMCallKit.hint("$uid 已拒接"); host.onCallRejected(uid) }
    override fun onCallBusy(uid: String) { IMCallKit.hint("$uid 忙线中"); host.onCallBusy(uid) }
    override fun onCallNoAnswer(uid: String) { IMCallKit.hint("$uid 无应答"); host.onCallNoAnswer(uid) }
    /** 他设备处理了：来电页会随后收到 onCallEnd 而静默消失，这里**不弹提示**（交互稿 §06）。 */
    override fun onHandledOnOtherDevice(callId: String, action: String) = host.onHandledOnOtherDevice(callId, action)

    override fun onUserEnter(uid: String) {
        IMCallKit.update(IMCallViewReducer.userEnter(state, uid))
        host.onUserEnter(uid)
    }

    override fun onUserLeave(uid: String) {
        IMCallKit.update(IMCallViewReducer.userLeave(state, uid))
        host.onUserLeave(uid)
    }

    override fun onUserAccept(uid: String) {
        IMCallKit.update(IMCallViewReducer.userEnter(state, uid))
        host.onUserAccept(uid)
    }

    // 拒接与无应答要在格子上写明终局再收掉——直接收的话，从主叫的角度看拒接就跟什么都没发生一样。
    override fun onUserReject(uid: String) {
        IMCallKit.update(IMCallViewReducer.userSettled(state, uid, IMCallViewState.Settled.REJECTED))
        host.onUserReject(uid)
    }

    override fun onUserNoResponse(uid: String) {
        IMCallKit.update(IMCallViewReducer.userSettled(state, uid, IMCallViewState.Settled.NO_ANSWER))
        host.onUserNoResponse(uid)
    }

    override fun onUserAudioAvailable(uid: String, available: Boolean) {
        IMCallKit.update(IMCallViewReducer.availability(state, uid, "audio", available))
        host.onUserAudioAvailable(uid, available)
    }

    override fun onUserVideoAvailable(uid: String, available: Boolean) {
        // 轨道来了才轮得到报层：人进来那一刻报的那次是空转（见 invalidateReportedLayer）。
        if (available) IMCallKit.invalidateReportedLayer(uid)
        IMCallKit.update(IMCallViewReducer.availability(state, uid, "video", available))
        host.onUserVideoAvailable(uid, available)
    }

    override fun onActiveSpeakers(speakers: List<IMSpeaker>) {
        /*
          **整份名单都要喂进去，不能只取音量最大的那个。**

          原先这里是 `maxByOrNull { it.volume }`：三个人同时说话只亮一个格子，
          而那人若恰好是本端，远端一个都不亮——真机 2026-09-09「说话没高亮」就是它。
          说话图标是每格各记各的（`Member.speaking`），本来就不需要先选出一个人。

          `loudest` 仍然要算：悬浮球一次只放得下一路缩略画面，那儿确实只能挑一个。
        */
        val volumes = speakers.associate { it.uid to it.volume }
        val loudest = speakers.maxByOrNull { it.volume }?.uid.orEmpty()
        IMCallKit.update(
            IMCallViewReducer.speaking(state, volumes, loudest, IMCallKit.engine?.uid.orEmpty()),
        )
        host.onActiveSpeakers(speakers)
    }

    override fun onNetworkQuality(entries: List<IMNetworkQuality>) {
        IMCallKit.update(IMCallViewReducer.networkQuality(state, entries.associate { it.uid to it.level }))
        host.onNetworkQuality(entries)
    }

    override fun onCallMediaTypeChanged(callId: String, from: String, to: String) = host.onCallMediaTypeChanged(callId, from, to)

    override fun onFirstVideoFrame(uid: String) {
        // 第一帧到了，界面撤 loading：让格子重画一次就够。**顺带重报一次层上界**——
        // 到这一步轨道一定在了，而人进来那一刻报的那次多半是空转。
        IMCallKit.invalidateReportedLayer(uid)
        IMCallKit.update(state)
        host.onFirstVideoFrame(uid)
    }

    override fun onRoomJoined(roomId: String) {
        IMCallKit.update(IMCallViewReducer.connected(state))
        IMCallKit.startTimer()
        // Kit 自己拨出 / 接听时，关摄像头的意图进房之前就给过 Engine（`IMCallKit.syncCameraIntent`），视频根本没发。
        // 这里再关一遍，兜的是宿主自己调 `engine.call` / `accept` 的路径——**用户表示不出镜，指示灯就不该亮**。
        if (state.mediaType == "video" && !state.cameraOn) IMCallKit.engine?.closeCamera()
        if (!state.micOn) IMCallKit.engine?.closeMic()
        /*
         **把「视频通话默认外放」真的应用到音频路由上。**

         `speakerOn` 一直只是个界面开关：视图状态里写着 true、按钮也是亮的，
         可 `engine.setSpeakerOn` 从来没被调用过——声音还是从听筒出来，
         举着手机看画面的人根本听不见。iOS 在同一处（`onRoomJoined`）就是这么做的。
        */
        IMCallKit.engine?.setSpeakerOn(state.speakerOn)
        IMCallKit.onLocalMediaStarted()
        host.onRoomJoined(roomId)
    }

    override fun onRoomLeft(roomId: String) {
        IMCallKit.stopTimer()
        // 会议没有 onCallEnd，收尾只能靠这一条；振铃通话的房间也会在结束时清掉，那时已经在 ENDED 了，别动。
        if (state.isMeeting) IMCallKit.update(IMCallViewReducer.ended(state, "hangup"))
        if (state.isMeeting) IMCallKit.main.postDelayed({ if (state.phase == IMCallViewState.Phase.ENDED) IMCallKit.update(IMCallViewReducer.reset()) }, 1_500)
        host.onRoomLeft(roomId)
    }

    override fun onRoomClosed(roomId: String, reason: String) {
        IMCallKit.stopTimer()
        IMCallKit.update(IMCallViewReducer.ended(state, reason))
        IMCallKit.main.postDelayed({ if (state.phase == IMCallViewState.Phase.ENDED) IMCallKit.update(IMCallViewReducer.reset()) }, 1_500)
        host.onRoomClosed(roomId, reason)
    }
}
