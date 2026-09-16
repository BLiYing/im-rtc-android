package com.imrtc.uikit

import com.imrtc.engine.IMCallEndReason
import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.IMNetworkQuality
import com.imrtc.engine.IMSpeaker
import com.imrtc.engine.log.IMRTCLog

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

    override fun onDisconnected(code: Int, willReconnect: Boolean) {
        // `willReconnect=false` 覆盖被踢、4401 用尽、主动 logout 三种放弃场景——
        // 不必再靠 `code == 4403` 猜（4401 用尽时 code 还是 4401，猜不出来）。
        //
        // **已经是 LOST 就不再翻回 RECONNECTING**：放弃是终态。重连成功时 onConnected 会把它拨回 OK。
        val lost = !willReconnect || state.connection == IMCallViewState.Connection.LOST
        IMCallKit.update(IMCallViewReducer.connection(state, if (lost) IMCallViewState.Connection.LOST else IMCallViewState.Connection.RECONNECTING))
        host.onDisconnected(code, willReconnect)
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

    /**
     * 加人 / 加入的失败分支（交互稿 §05、HOST_INTEGRATION_DESIGN §3.4）：
     * 满员出提示；本端已不在通话里（1407，只会来自加人）把入口藏掉；
     * 1409（宿主邀请鉴权回调拒绝）按「加人」还是「加入」分两句文案。别的错误码由宿主处理。
     */
    override fun onError(code: Int, name: String, message: String) {
        when (code) {
            // 加人 / 加入被拒都可能满员：出提示，**并且把刚摆上去的占位格收回来**（加入没有占位格，空操作）。
            1202 -> {
                IMCallKit.hint("通话已满员（最多 9 人）")
                IMCallKit.revokeLastInvite()
            }
            1407 -> {
                IMCallKit.update(IMCallViewReducer.inviteDenied(state))
                IMCallKit.revokeLastInvite()
            }
            1409 -> if (IMJoinCallState.joining) {
                IMCallKit.hint("无法加入该通话")
            } else {
                IMCallKit.hint("对方暂时无法被邀请")
                IMCallKit.revokeLastInvite()
            }
            // Engine 本地就拒掉的加入（状态不对 / 没登录）没有 onCallEnd，要自己收回「接通中…」。
            2005, 2007 -> if (IMJoinCallState.onLocalRejection(code)) IMCallKit.hint("无法加入该通话")
            // 媒体层报「没权限 / 没设备」：摄像头拿不到就降级为语音继续。
            2001, 2002 -> if (state.mediaType == "video") {
                // 可能是采集起来之后异步报的（摄像头被别的 App 抢走 / 打不开）：Engine 那头也关掉，
                // 否则它仍以为摄像头开着，对端等着一路永远不来的画面。
                if (state.cameraOn) IMCallKit.engine?.run { closeCamera(); stopLocalPreview() }
                IMCallKit.update(IMCallViewReducer.cameraBlocked(state))
                if (code == 2002) IMCallKit.hint("摄像头不可用")
            }
            // `joinCall` 的其余拒绝分支（1401/1402/1405/1408，协议 §4.1）：统一提示，随后的
            // onCallEnd(error) 会把界面收起，不必在这里另外处理状态。
            else -> if (IMJoinCallState.joining) IMCallKit.hint("无法加入该通话")
        }
        host.onError(code, name, message)
    }

    override fun onCallReceived(
        callId: String,
        caller: String,
        inviter: String,
        calleeIds: List<String>,
        mediaType: String,
        isGroup: Boolean,
        chatGroupId: String,
        userData: String,
    ) {
        // 名单里含自己，摆格子之前先去掉——「自己」不是远端成员。
        val others = calleeIds.filter { it != IMCallKit.engine?.uid }
        IMCallKit.update(
            IMCallViewReducer.incoming(
                state, callId, caller, others, mediaType, isGroup, chatGroupId, userData,
                selfUid = IMCallKit.engine?.uid.orEmpty(),
                inviter = inviter,
            ),
        )
        host.onCallReceived(callId, caller, inviter, calleeIds, mediaType, isGroup, chatGroupId, userData)
    }

    /** 通话中有人打进来，服务端已经替我们回了忙线——**只提示，不动当前通话**。 */
    override fun onCallMissed(callId: String, caller: String, reason: String) {
        IMCallKit.hint("$caller 来电，已自动回复忙线")
        host.onCallMissed(callId, caller, reason)
    }

    override fun onCallBegin(
        callId: String,
        roomId: String,
        mediaType: String,
        isGroup: Boolean,
        role: String,
        caller: String,
        chatGroupId: String,
        userData: String,
    ) {
        IMJoinCallState.joining = false
        IMCallKit.update(
            IMCallViewReducer.begin(state, callId, roomId, mediaType, role, isGroup, caller, chatGroupId, userData),
        )
        host.onCallBegin(callId, roomId, mediaType, isGroup, role, caller, chatGroupId, userData)
    }

    override fun onCallEnd(callId: String, reason: IMCallEndReason, durationSec: Long, endedBy: String) {
        IMJoinCallState.joining = false
        IMCallKit.stopTimer()
        // 还在响铃的来电直接收起，不留结束画面：被叫这一侧什么都还没做。主叫那一侧要停一下说明原因。
        if (state.phase == IMCallViewState.Phase.INCOMING) {
            IMCallKit.update(IMCallViewReducer.reset())
        } else {
            IMCallKit.update(IMCallViewReducer.ended(state, reason.wire, durationSec))
            // 停一会让用户看清结束原因再收场。**说不清原因的那几种要停久一点**（与 iOS / Web 同一张表）。
            val hold = if (reason == IMCallEndReason.HANGUP || reason == IMCallEndReason.CANCEL) 1_500L else 3_000L
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
        // 画面从无到有时格子先不揭示，等 onFirstVideoFrame（见 Member.videoPending）。
        IMCallKit.update(IMCallViewReducer.availability(state, uid, "video", available))
        armRevealFallback(uid)
        host.onUserVideoAvailable(uid, available)
    }

    /**
     * uid → 「新画面迟迟不上屏也照样揭示」的兜底。媒体层报不出首帧时（没接媒体、渲染器没有 Surface），
     * 格子不能一直停在头像上。**每次重开换一个、旧的撤掉**：连着开关时，上一次的兜底不能提前揭示这一次。
     */
    private val revealFallbacks = HashMap<String, Runnable>()

    private fun armRevealFallback(uid: String) {
        revealFallbacks.remove(uid)?.let { IMCallKit.main.removeCallbacks(it) }
        if (state.members[uid]?.videoPending != true) return
        val fallback = Runnable {
            revealFallbacks.remove(uid)
            val next = IMCallViewReducer.firstVideoFrame(state, uid)
            if (next === state) return@Runnable
            IMRTCLog.w("kit", "新画面 ${REVEAL_FALLBACK_MS}ms 没上屏，照样揭示 uid=$uid")
            IMCallKit.update(next)
        }
        revealFallbacks[uid] = fallback
        IMCallKit.main.postDelayed(fallback, REVEAL_FALLBACK_MS)
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

    override fun onFirstVideoFrame(uid: String, trackId: String) {
        // 新画面上屏了，揭示格子（见 Member.videoPending）。**顺带重报一次层上界**——
        // 到这一步轨道一定在了，而人进来那一刻报的那次多半是空转。
        IMCallKit.invalidateReportedLayer(uid)
        revealFallbacks.remove(uid)?.let { IMCallKit.main.removeCallbacks(it) }
        IMCallKit.update(IMCallViewReducer.firstVideoFrame(state, uid))
        host.onFirstVideoFrame(uid, trackId)
    }

    override fun onRoomJoined(roomId: String) {
        IMCallKit.update(IMCallViewReducer.connected(state))
        IMCallKit.startTimer()
        // Kit 自己拨出 / 接听时，关摄像头的意图进房之前就给过 Engine（`IMCallKit.syncCameraIntent`），视频根本没发。
        // 这里再关一遍，兜的是宿主自己调 `engine.call` / `accept` 的路径——**用户表示不出镜，指示灯就不该亮**。
        if (state.mediaType == "video" && !state.cameraOn) IMCallKit.engine?.closeCamera()
        if (!state.micOn) IMCallKit.engine?.closeMicrophone()
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

    private companion object {
        /** 真机上信令比新画面早 450–900ms（2026-09-11 19:17）。兜底要比它长，否则到点露出来的还是旧帧。 */
        const val REVEAL_FALLBACK_MS = 2_000L
    }
}
