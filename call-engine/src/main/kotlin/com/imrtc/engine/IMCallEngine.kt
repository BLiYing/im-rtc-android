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
     *   **必须满足协议 §2.5**：非空、≤64 字节、charset `[A-Za-z0-9_-]`，见下面的 init。
     */
    data class Config @JvmOverloads constructor(
        val url: String,
        val deviceId: String,
        val sdk: String = "android",
    ) {
        init {
            checkDeviceId(deviceId)
        }

        companion object {
            /** 协议 §2.5：`device_id` ≤64 **字节**（不是字符），charset `[A-Za-z0-9_-]`。 */
            private const val MAX_DEVICE_ID_BYTES = 64

            /**
             * 校验 `device_id`，不合规就抛 `IllegalArgumentException`。
             *
             * **在构造 Config 时就拦下来,而不是等服务端拒绝**。不拦的症状是:
             * 服务端回 1004、客户端无限退避重连、界面上只写着「登录失败」,
             * 而服务端那句说得很清楚的「device_id 只允许 [A-Za-z0-9_-]，出现了 ' '」
             * **到不了端上**。真机上实测踩过一次,查了一轮才定位到是机型名。
             *
             * **最常见的错法是直接用 `Build.MODEL`**：`Pixel 2 XL`、`Redmi Note 8 Pro`、
             * `MI 8 Lite`、`moto g(7) power` 都带空格或括号。
             * 要用它就先清洗——但**别用「删掉空格」那种做法**：`MI 8` 与 `MI8`
             * 是两款不同的机器，删完就撞成同一个 device_id，而撞号的后果是两台设备
             * 互相顶号、轮流把对方踢下线。换成 `-` 才不会。
             *
             * SDK **只校验不改写**：`device_id` 要求跨重启稳定,
             * 悄悄替宿主改掉,宿主自己那套设备管理就对不上账了。
             */
            @JvmStatic
            fun checkDeviceId(deviceId: String) {
                require(deviceId.isNotEmpty()) { "device_id 不能为空（协议 §2.5）" }
                val bytes = deviceId.toByteArray(Charsets.UTF_8).size
                require(bytes <= MAX_DEVICE_ID_BYTES) {
                    "device_id 长 $bytes 字节，上限 $MAX_DEVICE_ID_BYTES（协议 §2.5）"
                }
                val bad = deviceId.firstOrNull { ch ->
                    !(ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-')
                }
                require(bad == null) {
                    "device_id 只允许 [A-Za-z0-9_-]，出现了 '$bad'（协议 §2.5）。" +
                        "直接用 Build.MODEL 的话记得先清洗——机型名里带空格是常态"
                }
            }
        }
    }

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

    /** 本端轨道怎么发布、发了哪些（cid → kind）。见 [IMLocalPublisher]。 */
    private val publisher = IMLocalPublisher(media, scheduler::nowMs) { input(it) }

    /** 发布之前按下的静音，攒在这儿等 track_id 回来再补做。见 [IMMuteBook]。 */
    private val muteBook = IMMuteBook()

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
     * 本端 uid，由 `sys.hello.ok` 带回（协议 §1.3）。登录之前是空串。
     *
     * 界面拿它把自己从 `callee_ids` 之类的名单里剔掉——「自己」不是远端成员。
     */
    val uid: String get() = connection.uid

    /**
     * 换票，对应协议 §1.5 的「4401 → 换新票再来」。
     *
     * **push 不 pull**：Engine 不去宿主的账号体系要票。语义四端一致——
     * **下一次重连生效，不打断当前连接**。
     */
    @JvmOverloads
    fun updateToken(token: String, expiresAtMs: Long = 0L) =
        scheduler.post { connection.updateToken(token, expiresAtMs) }

    /** 登出并释放连接。**之后可以再 login。** */
    fun logout() = scheduler.post {
        media?.stop()
        publisher.clear()
        muteBook.clear()
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

    /** 开摄像头。进房时摄像头关着、视频还没发布的，这一下补发（见 [IMLocalPublisher]）。 */
    fun openCamera() = scheduler.post {
        applyMuted("video", false)
        publisher.publishCameraIfMissing(ctx)
    }

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

    /**
     * 停掉进房前的本端预览，**连摄像头一起关**（指示灯灭）。来电页 / 拨出中关摄像头时由 UIKit 调。
     * 摄像头已经发布的不受影响——通话中关摄像头走 [closeCamera]。
     */
    fun stopLocalPreview() = scheduler.post { media?.stopLocalPreview() }

    /**
     * 报某人画面的**层上界**（协议 §3.5：上界不是命令）。
     *
     * 九宫格缩略图报 `l`、全屏报 `h`。**不触发重协商**，也不保证立刻切——
     * 服务端要等目标层的关键帧，还会再按带宽估计压一次。
     *
     * **漏调这一条的代价是隐形的**：服务端按默认的 `m` 给每一路下发，
     * 九宫格里八个小格子每格都收半高清，带宽与解码器一起翻几倍，
     * 症状是「画面卡、掉帧」而不是任何一条报错。iOS 的 `setRemoteLayer(_:layer:)`、
     * Web 的 `setRemoteLayer` 是同一条；本端从缺到有是 2026-09-06 补的。
     */
    fun setRemoteLayer(uid: String, layer: String) = scheduler.post {
        var sent = 0
        for ((trackId, info) in ctx.room.remoteTracks) {
            if (info.uid != uid || info.kind != "video") continue
            input(
                IMMachineInput.Act(
                    "update_layer",
                    mapOf("track_id" to IMJson.Str(trackId), "max_layer" to IMJson.Str(layer)),
                ),
            )
            sent++
        }
        /*
         **这两行是这条通路唯一的外部可见性。**

         服务端不记录成功的 room 帧（`room.subscribe` / `room.update_layer` 在它的日志里一条都没有），
         客户端也不逐帧打日志——于是「层上界压根没发出去」这件事悄悄躺了好几周：
         门面上连这个方法都没有，而症状只是「画面卡」，没有任何一条报错。
         想验证这条通路有没有真的走通，除了这行日志没有别的办法。

         **找不到轨道不是错**：人先进来、轨道后到是常态，轨道到了 Kit 会重报
         （`IMCallKit.invalidateReportedLayer`）。所以那一支记 DEBUG 不记 WARN。
        */
        if (sent > 0) {
            IMRTCLog.i("engine", "层上界已报 uid=$uid layer=$layer tracks=$sent")
        } else {
            IMRTCLog.d("engine", "层上界暂不发 uid=$uid layer=$layer（他的视频轨道还没到）")
        }
    }

    /**
     * 开关本端某一类轨道。
     *
     * **本地静音与那条 `room.mute` 帧是两件事，不能绑死**：关掉本端轨道根本不需要
     * `track_id`（那是服务端分配的），只有帧需要。原先两者绑在一起，拿不到 track_id
     * 就连本端也不关——而「拿不到」恰恰发生在最该静音的时候（还没发布）。见 [desiredMuted]。
     */
    private fun setMuted(kind: String, muted: Boolean) = scheduler.post { applyMuted(kind, muted) }

    private fun applyMuted(kind: String, muted: Boolean) {
        // 意图先记下：轨道还没发布时，这是唯一留得住它的地方。
        muteBook.want(kind, muted)
        // **无条件应用到本端**。轨道还不存在时它是空操作，随后 [flushPendingMutes] 会补。
        media?.setMuted(kind, muted)

        val cid = publisher.tracks.entries.firstOrNull { it.value == kind }?.key
        val trackId = cid?.let { ctx.room.publishTrackIds[it] }
        if (trackId == null) {
            // 不是错误，是「来早了」：帧等发布完再补发。
            IMRTCLog.d("engine", "$kind 轨道还没发布，静音意图先记下（muted=$muted）")
            return
        }
        sendMute(trackId, muted)
    }

    private fun sendMute(trackId: String, muted: Boolean) = input(
        IMMachineInput.Act(
            "mute",
            mapOf("track_id" to IMJson.Str(trackId), "muted" to IMJson.Bool(muted)),
        ),
    )

    /**
     * 拿到 `track_id` 的那一刻，把攒下的静音意图补做一遍。
     *
     * **两件事都要补**：一是再 `media.setMuted` 一次——轨道是刚才 `publishDefaults`
     * 现造的，造出来默认是开着的，之前那次调用落在了一个还不存在的轨道上；
     * 二是补发 `room.mute`，让服务端与对端的界面也对上。
     */
    private fun flushPendingMutes(before: IMEngineContext, after: IMEngineContext) {
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

        // 每推进一步就把「哪条轨道是谁的」同步给媒体层。**轨道与归属谁先到都可能**，
        // 所以这一步不能只挂在 track_published 那一支上（iOS 的 IMFrameLoop、Web 的
        // frameLoop.ts 都是同一处）。
        media?.claimRemoteTracks(output.state.room.remoteTracks.mapValues { it.value.uid })

        for (frame in output.send) sendFrame(frame)
        dispatcher.dispatchAll(output.emit)
        driveMedia(before, output.state)
        // **排在 driveMedia 之后**：新进房那一步正是在它里面发布轨道的，
        // 而要补的静音得等那些轨道的 track_id 回来（下一轮 input）才做得成。
        flushPendingMutes(before, output.state)
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
            // **离房被拒也要退回 idle**：服务端在「会话已不在房间里」时回 1203，
            // 而那正说明我们已经不在房里了。不接这一条的话房间永久停在 leaving——
            // 媒体停不掉（摄像头与前台服务一直开着），之后 join 也被本地拒，
            // 这台 Engine 除非 logout 否则再也进不了房。
            IMFrameType.ROOM_LEAVE -> input(IMMachineInput.Internal("leave_failed"))
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
        // 「断线 → reconnecting → 被踢 → idle」也是同一个漏法，一并被这条判据盖住。
        if (mediaWanted(before) && !mediaWanted(after)) {
            adapter.stop()
            publisher.clear()
            // 意图跟着这一轮媒体一起作废：下一通电话的开关由界面重新决定，
            // 留着的话会变成「上一通静音过，这一通莫名其妙也是静音的」。
            muteBook.clear()
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
            /*
             协议 §1.4：恢复之后媒体面要重新协商。服务端那侧主动下发
             `room.offer{pc:"sub"}`，而 `pub` 这条的 offerer 是本端，只能自己重发。

             **不能只靠「PC 判 FAILED 的那一刻」那条路**——网一断信令也跟着断，
             房间立刻变成 reconnecting，而 PC 要等约 30 秒才判 FAILED：那时房间机
             会把 restart_pub_ice 本地拒掉，而它不进 bufferedOps，于是永远丢失。
             iOS 真机 2026-09-07 抓到的就是这一幕，三端同一条路。

             **不查 PC 当前状态、无条件重启**：换了连接就等于换了网络路径，旧候选多半已废；
             服务端那侧也是无条件重启 sub，两边对称。房间不在 joined 时状态机自会拒掉。
            */
            if (!resumed) return
            IMRTCLog.i("engine", "会话已恢复，重新协商上行")
            media?.restartPubICE()
            input(IMMachineInput.Act("restart_pub_ice", emptyMap()))
        }

        override fun onDisconnected(code: Int, reason: String) {
            input(IMMachineInput.Internal("disconnected"))
            // 关闭码由连接层独占上报（状态机那份 onDisconnected 不带码，dispatcher 里刻意不派发）。
            dispatcher.disconnected(code, reason)
        }

        override fun onFrame(type: String, data: Map<String, IMJson>) {
            media?.applyNegotiationFrame(type, data)
            input(IMMachineInput.Recv(type, data))
        }

        override fun onKickedOut(reason: IMKickedOutReason) {
            // 状态机只认「被踢了」这一件事；原因是给宿主做处置判断的，两者分开走
            // （dispatcher 里刻意不派发状态机那份 onKickedOut）。
            input(IMMachineInput.Internal("ws_closed_4403"))
            dispatcher.kickedOut(reason)
        }

        override fun onSessionUnrecoverable() =
            input(IMMachineInput.Internal("session_unrecoverable"))

        override fun onTokenWillExpire(expiresAtMs: Long) =
            dispatcher.tokenWillExpire(expiresAtMs)

        override fun onError(code: IMErrorCode, message: String) =
            dispatcher.error(code.code, message)
    }

    private inner class MediaEvents : IMMediaAdapter.Events {
        override fun onLocalSdp(pc: String, type: String, sdp: String) = scheduler.post {
            val frameType = if (type == "offer") IMFrameType.ROOM_OFFER else IMFrameType.ROOM_ANSWER
            connection.send(frameType, mapOf("pc" to IMJson.Str(pc), "sdp" to IMJson.Str(sdp)))
        }

        override fun onLocalCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) =
            scheduler.post {
                // **不在房里就不往上发**。候选只对「我们此刻正待在里面的那个房间」有意义，
                // 发上去只会换回一条 `1203 not_in_room`，对谁都没用。
                //
                // 这是第二道防线：媒体层理应在离房时就被停掉（见 [driveMedia]），但候选是
                // **从 native 的 signaling 线程冒上来的异步事件**，天生可能比 stop() 晚一拍；
                // libwebrtc 又开着 GATHER_CONTINUALLY，网络一变就重采一轮。出口这一道挡住的
                // 正是这段时间差，也顺带保证「媒体层哪天再漏一次」不会又变成服务端的 WARN 刷屏。
                if (ctx.room.state != IMRoomState.JOINED) {
                    IMRTCLog.d("engine", "房间在 ${ctx.room.state.wire}，丢弃 $pc 的本端候选")
                    return@post
                }
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
