package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.IMScheduler
import com.imrtc.engine.signaling.IMSignalConnection
import com.imrtc.engine.signaling.IMSignalConnectionEvents
import com.imrtc.engine.statemachine.IMMachineInput
import com.imrtc.engine.statemachine.IMRoomState

/**
 * engine 的两个**向上出口**：信令连接的事件、媒体层的事件。都在 engine 线程上喂进 [IMFrameLoop]。
 *
 * 从 [IMCallEngine] 拆出来是体量红线（CONVENTIONS §2）；两者都只做接线，没有自己的状态。
 */
internal class IMConnectionEvents(
    private val loop: IMFrameLoop,
    private val dispatcher: IMEventDispatcher,
    private val media: IMMediaAdapter?,
) : IMSignalConnectionEvents {

    override fun onConnected(sessionId: String, resumed: Boolean) {
        loop.input(
            IMMachineInput.Recv(
                IMFrameType.HELLO + ".ok",
                mapOf("session_id" to IMJson.Str(sessionId), "resumed" to IMJson.Bool(resumed)),
            ),
        )
        loop.settleLogin(null)
        /*
         协议 §1.4：恢复之后媒体面要重新协商。服务端那侧主动下发
         `room.offer{pc:"sub"}`，而 `pub` 这条的 offerer 是本端，只能自己重发。

         **不能只靠「PC 判 FAILED 的那一刻」那条路**——网一断信令也跟着断，
         房间立刻变成 reconnecting，而 PC 要等约 30 秒才判 FAILED：那时房间机
         会把 restart_pub_ice 本地拒掉，而它不进 bufferedOps，于是永远丢失。
         iOS 真机 2026-09-07 抓到的就是这一幕，三端同一条路。

         **不查 PC 当前状态、无条件重启**：换了连接就等于换了网络路径，旧候选多半已废；
         服务端那侧也是无条件重启 sub，两边对称。房间不在 joined 时状态机自会拒掉（只留日志）。
        */
        if (!resumed) return
        IMRTCLog.i("engine", "会话已恢复，重新协商上行")
        media?.restartPubICE()
        loop.input(IMMachineInput.Act("restart_pub_ice", emptyMap()))
    }

    override fun onDisconnected(code: Int, willReconnect: Boolean) {
        loop.input(IMMachineInput.Internal("disconnected"))
        // 关闭码由连接层独占上报（状态机那份 onDisconnected 不带码，dispatcher 里刻意不派发）。
        dispatcher.disconnected(code, willReconnect)
    }

    /**
     * 还没连上的那次尝试失败了：`login` 的结果就在这一刻给（握手被拒给那个码，连不上给 2003）。
     * 连接层会照常退避重连；之后真连上了宿主会收到 `onConnected`。
     */
    override fun onConnectAttemptFailed() {
        if (loop.loginResult == null) return
        val error = loop.loginFailure
            ?: IMRTCError.of(IMErrorCode.NETWORK_UNREACHABLE, "连接失败", IMFrameType.HELLO)
        loop.settleLogin(error)
    }

    override fun onFrame(type: String, data: Map<String, IMJson>) {
        /*
         **房间已经不在了，迟到的媒体帧不许交给媒体层。**

         强制收场、通话结束之后才到的候选或 SDP 照常交下去，媒体层会在一个没人要的房间上
         重新协商、甚至再拉起一对 PC，一直挂到下一次停媒体。状态机那一侧由房间机的 idle 分支丢弃。
        */
        if (loop.ctx.room.state == IMRoomState.IDLE && type in MEDIA_FRAMES) {
            IMRTCLog.d("engine", "房间已不在，丢弃迟到的媒体帧 type=$type")
            return
        }
        media?.applyNegotiationFrame(type, data)
        loop.input(IMMachineInput.Recv(type, data))
    }

    override fun onKickedOut(reason: IMKickedOutReason) {
        // 状态机只认「被踢了」这一件事；原因给宿主做处置判断，两者分开走（dispatcher 里刻意不派发状态机那份 onKickedOut）。
        loop.input(IMMachineInput.Internal("ws_closed_4403"))
        dispatcher.kickedOut(reason)
    }

    override fun onSessionUnrecoverable() = loop.input(IMMachineInput.Internal("session_unrecoverable"))

    override fun onTokenWillExpire(expiresAtMs: Long) = dispatcher.tokenWillExpire(expiresAtMs)

    /** 等登录结论期间的错误（握手被拒）归那次 `login`，随后那条关闭时交付；其余照旧发 `onError`。 */
    override fun onError(code: IMErrorCode, message: String) {
        if (loop.loginResult != null) {
            if (loop.loginFailure == null) loop.loginFailure = IMRTCError.of(code, message, IMFrameType.HELLO)
            return
        }
        dispatcher.error(code.code, message)
    }

    private companion object {
        /** 交给媒体层之前要先看房间还在不在的那几帧。见 [onFrame]。 */
        val MEDIA_FRAMES = setOf(IMFrameType.ROOM_ICE_CANDIDATE, IMFrameType.ROOM_OFFER, IMFrameType.ROOM_ANSWER)
    }
}

internal class IMMediaEvents(
    private val loop: IMFrameLoop,
    private val dispatcher: IMEventDispatcher,
    private val scheduler: IMScheduler,
    private val connection: () -> IMSignalConnection,
) : IMMediaAdapter.Events {

    override fun onLocalSdp(pc: String, type: String, sdp: String) = scheduler.post {
        val frameType = if (type == "offer") IMFrameType.ROOM_OFFER else IMFrameType.ROOM_ANSWER
        connection().send(frameType, mapOf("pc" to IMJson.Str(pc), "sdp" to IMJson.Str(sdp)))
    }

    override fun onLocalCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) =
        scheduler.post {
            // **不在房里就不往上发**。候选只对「我们此刻正待在里面的那个房间」有意义，
            // 发上去只会换回一条 `1203 not_in_room`，对谁都没用。
            //
            // 这是第二道防线：媒体层理应在离房时就被停掉（见 [IMMediaDriver]），但候选是
            // **从 native 的 signaling 线程冒上来的异步事件**，天生可能比 stop() 晚一拍；
            // libwebrtc 又开着 GATHER_CONTINUALLY，网络一变就重采一轮。出口这一道挡住的
            // 正是这段时间差，也顺带保证「媒体层哪天再漏一次」不会又变成服务端的 WARN 刷屏。
            if (loop.ctx.room.state != IMRoomState.JOINED) {
                IMRTCLog.d("engine", "房间在 ${loop.ctx.room.state.wire}，丢弃 $pc 的本端候选")
                return@post
            }
            connection().send(
                IMFrameType.ROOM_ICE_CANDIDATE,
                mapOf(
                    "pc" to IMJson.Str(pc),
                    "candidate" to IMJson.Str(candidate),
                    "sdp_mid" to IMJson.Str(sdpMid),
                    "sdp_mline_index" to IMJson.Num(sdpMLineIndex.toLong()),
                ),
            )
        }

    override fun onMediaReady() = scheduler.post { loop.input(IMMachineInput.Internal("media_ready")) }

    override fun onFirstVideoFrame(uid: String, trackId: String) = dispatcher.firstVideoFrame(uid, trackId)

    override fun onAudioRoutesChanged(routes: List<IMAudioRoute>, current: IMAudioRoute?) =
        dispatcher.audioRoutesChanged(routes, current)

    override fun onMediaError(code: Int, message: String) = dispatcher.error(code, message)
}
