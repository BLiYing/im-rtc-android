package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameRegistry
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.IMExecutorScheduler
import com.imrtc.engine.signaling.IMScheduler
import com.imrtc.engine.signaling.IMSignalConnection
import com.imrtc.engine.signaling.IMTransport
import com.imrtc.engine.signaling.IMOkHttpTransport
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMEngineContext
import com.imrtc.engine.statemachine.IMEngineMachine
import com.imrtc.engine.statemachine.IMMachineInput
import com.imrtc.engine.statemachine.IMOutgoingFrame
import com.imrtc.engine.statemachine.IMRoomState

/**
 * **宿主唯一需要认识的类。**
 *
 * 它把信令、状态机、媒体串成一条线，对外只暴露方法与 [IMCallEngineListener] 的回调表。
 * 「只引 Engine 自画 UI」这条路走的就是它。
 *
 * ## 三条使用约定
 *
 * 1. **全部方法都可以在主线程调**：内部会切到自己的单线程，回调再切回主线程。
 * 2. **不传媒体适配器是正常用法，不是降级**：登录、振铃、成员进出、静音通知一个都不少，
 *    只有推流与画面挂载会以 `2005 invalid_state` 失败。想真通话就引 `call-engine-webrtc`。
 * 3. **`logout()` 之后这个实例还能再 `login()`**，但**必须成对**：不 logout 就丢弃实例，
 *    会漏一条线程与一条 WebSocket。
 */
class IMCallEngine private constructor(
    private val config: Config,
    listener: IMCallEngineListener,
    private val media: IMMediaAdapter?,
    private val scheduler: IMScheduler,
    transport: IMTransport,
    mainThread: IMMainThread,
) {

    /**
     * @param url 信令地址，`ws://` 或 `wss://`。**真机上别填 127.0.0.1**——那指的是手机自己。
     * @param deviceId 设备号。同一账号同一设备号在别处登录会把这边踢下线（4403）。
     */
    data class Config @JvmOverloads constructor(
        val url: String,
        val deviceId: String,
        val sdk: String = "android",
    )

    @JvmOverloads
    constructor(
        config: Config,
        listener: IMCallEngineListener,
        media: IMMediaAdapter? = null,
    ) : this(config, listener, media, IMExecutorScheduler(), IMOkHttpTransport(), IMAndroidMainThread())

    private val dispatcher = IMEventDispatcher(listener, mainThread)

    internal companion object {
        /** 单测入口：塞假的传输、调度器与主线程投递，纯 JVM 就能验完整条循环。 */
        internal fun forTest(
            config: Config,
            listener: IMCallEngineListener,
            media: IMMediaAdapter?,
            scheduler: IMScheduler,
            transport: IMTransport,
        ) = IMCallEngine(config, listener, media, scheduler, transport, IMMainThread { it() })
    }
    private val connection = IMSignalConnection(transport, scheduler, ConnectionEvents())
    private var ctx = IMEngineContext()

    /** 本端已发布的 Track：cid → kind。挂断时要按它去停采集。 */
    private val localTracks = LinkedHashMap<String, String>()

    init {
        media?.attachEvents(MediaEvents())
    }

    // ── 连接 ──────────────────────────────────────────────────────────

    /** 登录。`token` 由宿主的账号体系签发（服务端 `/v1/tokens`）。 */
    fun login(token: String) = scheduler.post {
        connection.start(
            IMSignalConnection.Config(config.url, config.deviceId, config.sdk),
            token,
        )
    }

    /**
     * 换票，对应协议 §1.5 的「4401 → 换新票再来」。
     *
     * **push 不 pull**：Engine 不去宿主的账号体系要票。语义四端一致——
     * **下一次重连生效，不打断当前连接**。
     */
    fun updateToken(token: String) = scheduler.post { connection.updateToken(token) }

    /** 登出并释放连接。**之后可以再 login。** */
    fun logout() = scheduler.post {
        media?.stop()
        localTracks.clear()
        ctx = IMEngineContext()
        connection.stop()
    }

    /** 彻底销毁：连线程一起停。**销毁后这个实例不能再用。** */
    fun destroy() {
        logout()
        scheduler.post { scheduler.shutdown() }
    }

    // ── 通话 ──────────────────────────────────────────────────────────

    /**
     * 拨出。`mediaType` 取 "audio" 或 "video"。
     *
     * 1v1 就传一个人；群通话传多个并把 `isGroup` 置 true（房内含主叫最多 9 人）。
     */
    @JvmOverloads
    fun call(userIds: List<String>, mediaType: String, isGroup: Boolean = false) = act(
        "call",
        mapOf(
            "callee_ids" to IMJson.Arr(userIds.map { IMJson.Str(it) }),
            "media_type" to IMJson.Str(mediaType),
            "is_group" to IMJson.Bool(isGroup),
        ),
    )

    fun accept() = act("accept")

    fun reject() = act("reject")

    /** 接通前主叫取消。**接通后要用 [hangup]**，两个词不共用一条路径。 */
    fun cancel() = act("cancel")

    fun hangup() = act("hangup")

    /** 群通话中途加邀，仅主叫可发。 */
    fun inviteMore(userIds: List<String>) =
        act("invite_more", mapOf("callee_ids" to IMJson.Arr(userIds.map { IMJson.Str(it) })))

    /**
     * 主动加入一通进行中的群通话。
     *
     * **「怎么知道有通话在进行中」不在本协议里**——那是宿主拿 webhook `call.started`
     * 自己发广播的事，Engine 只负责把 call_id 送上去。
     */
    fun joinCall(callId: String) = act("join_call", mapOf("call_id" to IMJson.Str(callId)))

    // ── 会议房 ────────────────────────────────────────────────────────

    fun joinRoom(roomId: String, roomToken: String) = act(
        "join",
        mapOf("room_id" to IMJson.Str(roomId), "room_token" to IMJson.Str(roomToken)),
    )

    fun leaveRoom() = act("leave")

    // ── 设备与媒体 ────────────────────────────────────────────────────

    fun openMic() = setMuted("audio", false)

    fun closeMic() = setMuted("audio", true)

    fun openCamera() = setMuted("video", false)

    fun closeCamera() = setMuted("video", true)

    fun switchCamera() = scheduler.post { media?.switchCamera() }

    fun setSpeakerOn(on: Boolean) = scheduler.post { media?.setSpeakerOn(on) }

    /** 造一个视频视图。没有媒体适配器时返回 null——UIKit 会退回头像占位。 */
    fun createVideoView(context: android.content.Context): android.view.View? =
        media?.createVideoView(context)

    /** 把某个 uid 的画面挂到视图上。`view` 传 [createVideoView] 的产物；null 表示卸载。 */
    fun attachView(uid: String, view: Any?) = scheduler.post {
        requireMedia()?.attachView(uid, view)
    }

    fun startLocalPreview(view: Any?) = scheduler.post { requireMedia()?.startLocalPreview(view) }

    private fun setMuted(kind: String, muted: Boolean) = scheduler.post {
        val cid = localTracks.entries.firstOrNull { it.value == kind }?.key
        val trackId = cid?.let { ctx.room.publishTrackIds[it] }
        if (trackId == null) {
            IMRTCLog.w("engine", "没有 $kind Track 可以开关")
            return@post
        }
        media?.setMuted(kind, muted)
        input(
            IMMachineInput.Act(
                "mute",
                mapOf("track_id" to IMJson.Str(trackId), "muted" to IMJson.Bool(muted)),
            ),
        )
    }

    // ── 内部：核心循环 ────────────────────────────────────────────────

    private fun act(op: String, args: Map<String, IMJson> = emptyMap()) =
        scheduler.post { input(IMMachineInput.Act(op, args)) }

    /**
     * **唯一的状态推进入口**：输入进状态机 → 发帧 → 抛回调 → 驱动媒体。
     *
     * 只有这一条路径能改 [ctx]。多一条就会出现「帧发了但本地记账没跟上」。
     */
    private fun input(machineInput: IMMachineInput) {
        val before = ctx
        val output = IMEngineMachine.reduce(ctx, machineInput, scheduler.nowMs())
        ctx = output.state

        for (frame in output.send) sendFrame(frame)
        dispatcher.dispatchAll(output.emit)
        driveMedia(before, output.state)
    }

    private fun sendFrame(frame: IMOutgoingFrame) {
        // 协商类帧的 SDP 要向媒体层现取——状态机只负责决定「该协商了」。
        if (frame.type == IMFrameType.ROOM_OFFER && frame.data.text("sdp").isEmpty()) {
            requireMedia()?.createOffer(frame.data.text("pc").ifEmpty { "pub" })
            return
        }
        if (frame.type == IMFrameType.ROOM_ANSWER && frame.data.text("sdp").isEmpty()) {
            // sub 侧的 answer 由媒体层在 applyRemoteSdp 之后自己抛上来，这里什么都不做。
            return
        }

        if (!IMFrameRegistry.isRequest(frame.type)) {
            connection.send(frame.type, frame.data)
            return
        }
        connection.request(frame.type, frame.data) { ok, data, code, message ->
            if (ok) {
                input(IMMachineInput.Recv(frame.type + ".ok", data))
            } else {
                onRequestFailed(frame.type, code, message)
            }
        }
    }

    /**
     * 请求被服务端拒了。
     *
     * **必须让状态机退回 idle**，否则会卡在中间态：呼叫失败却停在 inviting，界面上
     * 「正在呼叫…」转个不停，之后每次挂断都发向一个不存在的 call（1401），永远退不出去。
     * 进房失败同理——不退的话这台 Engine 之后再也进不了任何房间。
     */
    private fun onRequestFailed(type: String, code: IMErrorCode?, message: String) {
        IMRTCLog.w("engine", "$type 被拒：${code?.wireName} $message")
        dispatcher.error((code ?: IMErrorCode.INTERNAL).code, message)
        when (type) {
            IMFrameType.CALL_INVITE, IMFrameType.CALL_ACCEPT, IMFrameType.CALL_JOIN ->
                input(IMMachineInput.Internal("call_failed"))
            IMFrameType.ROOM_JOIN -> input(IMMachineInput.Internal("join_failed"))
        }
    }

    /** 状态迁移带来的媒体动作。**媒体只跟着状态走，不自己决定什么时候起停。** */
    private fun driveMedia(before: IMEngineContext, after: IMEngineContext) {
        val adapter = media ?: return

        // 拿到 room_token 的那一刻把媒体拉起来。
        // **判据是 room_token 从无到有，不是「进入某个状态」**：主叫的 idle→inviting 那一步
        // 还没有票，等 call.connected 到了才有；按状态判会一次都不触发（第一版就是这么错的）。
        if (before.call.roomToken.isEmpty() && after.call.roomToken.isNotEmpty()) {
            adapter.start(emptyList())
        }

        // 进房成功：先确保媒体起来了，再按 media_type 自动发布本端 Track。
        // **会议房是直接 joinRoom 的，压根不经过 call**——只按 room_token 判的话这里一次都不会起，
        // 真机上的症状是「还没 start 就 publish，忽略」，人进了房但谁也听不见谁。
        if (before.room.state != IMRoomState.JOINED && after.room.state == IMRoomState.JOINED) {
            adapter.start(emptyList())
            publishDefaults(after)
        }

        // 通话结束 / 离房：停掉媒体。**必须可重入**，挂断与被踢会先后到达。
        if (before.room.state == IMRoomState.JOINED && after.room.state == IMRoomState.IDLE) {
            adapter.stop()
            localTracks.clear()
        }
    }

    private fun publishDefaults(state: IMEngineContext) {
        val adapter = media ?: return
        // 会议房没有 call，媒体类型无从谈起——按视频会议处理（草图 §08）。
        val mediaType = if (state.call.state != IMCallState.IDLE) state.call.mediaType else "video"
        val kinds = if (mediaType == "video") listOf("audio", "video") else listOf("audio")
        for (kind in kinds) {
            val cid = "local-$kind-${scheduler.nowMs()}"
            localTracks[cid] = kind
            adapter.publish(cid, kind, simulcast = kind == "video")
            input(
                IMMachineInput.Act(
                    "publish",
                    mapOf(
                        "cid" to IMJson.Str(cid),
                        "kind" to IMJson.Str(kind),
                        "source" to IMJson.Str(if (kind == "video") "camera" else "microphone"),
                        "simulcast" to IMJson.Bool(kind == "video"),
                    ),
                ),
            )
        }
    }

    private fun requireMedia(): IMMediaAdapter? {
        if (media == null) {
            // **不为它新造错误码**：错误码表是五仓共用的契约，加一个码等于改五个仓 + 改向量。
            dispatcher.error(IMErrorCode.INVALID_STATE.code, "没有媒体适配器；引 call-engine-webrtc")
        }
        return media
    }

    // ── 内部：两个回调出口 ────────────────────────────────────────────

    private inner class ConnectionEvents : IMSignalConnection.Events {
        override fun onConnected(sessionId: String, resumed: Boolean) {
            input(IMMachineInput.Recv(IMFrameType.HELLO + ".ok", mapOf(
                "session_id" to IMJson.Str(sessionId),
                "resumed" to IMJson.Bool(resumed),
            )))
        }

        override fun onDisconnected(code: Int, reason: String) {
            input(IMMachineInput.Internal("disconnected"))
            // 关闭码由连接层独占上报（状态机那份 onDisconnected 不带码，dispatcher 里刻意不派发）。
            dispatcher.disconnected(code, reason)
        }

        override fun onFrame(type: String, data: Map<String, IMJson>) {
            forwardToMedia(type, data)
            input(IMMachineInput.Recv(type, data))
        }

        override fun onKickedOut() = input(IMMachineInput.Internal("ws_closed_4403"))

        override fun onError(code: IMErrorCode, message: String) =
            dispatcher.error(code.code, message)
    }

    /** 协商类下行帧要原样喂给媒体层——状态机不认识 SDP。 */
    private fun forwardToMedia(type: String, data: Map<String, IMJson>) {
        val adapter = media ?: return
        when (type) {
            IMFrameType.ROOM_OFFER -> adapter.applyRemoteSdp(
                data.text("pc"), "offer", data.text("sdp"),
            )
            IMFrameType.ROOM_ANSWER -> adapter.applyRemoteSdp(
                data.text("pc"), "answer", data.text("sdp"),
            )
            IMFrameType.ROOM_ICE_CANDIDATE -> adapter.applyRemoteCandidate(
                data.text("pc"),
                data.text("candidate"),
                data.text("sdp_mid"),
                ((data["sdp_mline_index"] as? IMJson.Num)?.value ?: 0L).toInt(),
            )
        }
    }

    private inner class MediaEvents : IMMediaAdapter.Events {
        override fun onLocalSdp(pc: String, type: String, sdp: String) = scheduler.post {
            val frameType = if (type == "offer") IMFrameType.ROOM_OFFER else IMFrameType.ROOM_ANSWER
            connection.send(frameType, mapOf("pc" to IMJson.Str(pc), "sdp" to IMJson.Str(sdp)))
        }

        override fun onLocalCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) =
            scheduler.post {
                connection.send(
                    IMFrameType.ROOM_ICE_CANDIDATE,
                    mapOf(
                        "pc" to IMJson.Str(pc),
                        "candidate" to IMJson.Str(candidate),
                        "sdp_mid" to IMJson.Str(sdpMid),
                        "sdp_mline_index" to IMJson.Num(sdpMLineIndex.toLong()),
                    ),
                )
            }

        override fun onMediaReady() = scheduler.post { input(IMMachineInput.Internal("media_ready")) }

        override fun onFirstVideoFrame(uid: String) = dispatcher.firstVideoFrame(uid)

        override fun onMediaError(code: Int, message: String) = dispatcher.error(code, message)
    }
}

private fun Map<String, IMJson>.text(key: String) = (this[key] as? IMJson.Str)?.value ?: ""
