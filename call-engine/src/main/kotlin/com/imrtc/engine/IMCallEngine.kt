package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.IMExecutorScheduler
import com.imrtc.engine.signaling.IMScheduler
import com.imrtc.engine.signaling.IMSignalConnection
import com.imrtc.engine.signaling.IMTransport
import com.imrtc.engine.signaling.IMOkHttpTransport
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMMachineInput
import com.imrtc.engine.statemachine.Wire

/**
 * **宿主唯一需要认识的类。**
 *
 * 它把信令、状态机、媒体串成一条线，对外只暴露方法与 [IMCallEngineListener] 的回调表。
 * 「只引 Engine 自画 UI」这条路走的就是它。
 *
 * ## 三条使用约定
 *
 * 1. **全部方法都可以在主线程调**：内部会切到自己的单线程，回调再切回主线程。
 *    发起类方法的结果从可选的 [IMResultCallback] 回来（主线程、恰好一次）；不传回调时失败走 `onError`。
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
        val sdk: String = IMCallEngineVersion.SDK,
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

    /** 核心循环与状态机快照，见 [IMFrameLoop]。 */
    private val loop: IMFrameLoop = IMFrameLoop(media, scheduler, dispatcher) { connection }

    private val connection = IMSignalConnection(transport, scheduler, IMConnectionEvents(loop, dispatcher, media))

    /**
     * 终态销毁标记。见 [destroy]。`@Volatile`：宿主线程上读（发起类方法当场以 2005 结掉），
     * [destroy] 在宿主线程上写。
     */
    @Volatile
    private var destroyed = false

    init {
        media?.attachEvents(IMMediaEvents(loop, dispatcher, scheduler) { connection })
    }

    // ── 连接 ──────────────────────────────────────────────────────────

    /**
     * 登录。`token` 由宿主的账号体系签发（服务端 `/v1/tokens`）。
     *
     * 结果在**第一次握手有结论时**回来：连上 → 成功；握手被拒 → 那个码；连不上 → `2003`
     * （连接层照常退避重连，之后连上了会有 `onConnected`）；结论出来前 `logout()` → `2007`。
     * 已经连着、或上一次 `login` 还在等结论时再调 → `2005`：换账号或换票先 `logout()`（连着时换票用 [updateToken]）。
     */
    @JvmOverloads
    fun login(token: String, onResult: IMResultCallback<Unit>? = null) =
        post(onResult, IMFrameType.HELLO, IMCallResult.UNIT) { result ->
            if (loop.loginResult != null || connection.isConnected) {
                result.finish(IMRTCError.invalidState("已经登录了：换账号或换票请先 logout()", IMFrameType.HELLO))
                return@post
            }
            loop.loginResult = result
            connection.start(IMSignalConnection.Config(config.url, config.deviceId, config.sdk), token)
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
     * **下一次重连生效，不打断当前连接**。提示类：没有结果，销毁后是空操作。
     */
    @JvmOverloads
    fun updateToken(token: String, expiresAtMs: Long = 0L) {
        if (destroyed) return
        scheduler.post { connection.updateToken(token, expiresAtMs) }
    }

    /**
     * 告知 Engine 宿主 App 当前处于前台还是后台，只影响**断线后下一次重连要等多久**——
     * 详见 `IMSignalConnection` 类注释「后台重连节奏：不清零，最长 3 秒」（2026-09-11，
     * 真机 OPPO/ColorOS：后台每约 3 秒被系统杀一次 socket，退避走到 15s/30s 会错过
     * 服务端等 5 秒的来电窗口）。**接了 `call-uikit` 的宿主不用管**，`IMCallKit.start()`
     * 已自动喂了；自画 UI 的宿主接 `Application.ActivityLifecycleCallbacks` 后调用即可。提示类，销毁后空操作。
     */
    fun setAppForeground(foreground: Boolean) {
        if (destroyed) return
        scheduler.post { connection.setForeground(foreground) }
    }

    /** 登出并释放连接。**之后可以再 login。** 清理类：永不失败，销毁后是空操作。 */
    fun logout() {
        scheduler.post { teardown() }
    }

    private fun teardown() {
        media?.stop()
        loop.reset()
        connection.stop()
        loop.settleLogin(IMRTCError.of(IMErrorCode.NOT_LOGGED_IN, "登录还没结论就 logout 了", IMFrameType.HELLO))
    }

    /**
     * 彻底销毁：`logout` + 停线程。**不可逆，可重复调用。**
     *
     * # 之后再调别的方法（ACTION_RESULT_DESIGN R6，四端同一张表）
     *
     * - **发起类与本地设备类**（`login` / `call` / `accept` / … / `openCamera` / `switchCamera` / `startLocalPreview`）
     *   一律**以 `2005 invalid_state` 结束**，从结果回调回来（没传回调就走 `onError`）——静默丢弃的话宿主永远等不到结果。
     * - **清理类与提示类**（`logout` / `forceEnd` / `closeMicrophone` / `closeCamera` / `stopLocalPreview` /
     *   `attachView` / `attachLocalView` / `setRemoteLayer` / `setSpeakerOn` / `setAppForeground` / `updateToken`）
     *   是空操作、不报错——宿主卸载时经常无脑清理这几个，不该因为清理顺序先后而报错。
     *
     * 销毁之前一瞬间已经交出去的调用照常结算（调度器会把队里的任务跑完，那时连接已停，结果是 `2007`）。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        scheduler.post {
            teardown()
            scheduler.shutdown()
        }
    }

    // ── 通话 ──────────────────────────────────────────────────────────

    /**
     * 拨出。`mediaType` 取 "audio" 或 "video"；1v1 就传一个人，群通话传多个并把 `isGroup` 置 true（房内含主叫最多 9 人）。
     *
     * 结果值是**服务端分配的 callId**（取自 `call.invite.ok`）。被拒（名单含自己 `1004` / 服务端拒绝 / 超时 / 没登录）时
     * 结果是那个错误，**并且照发一次 `onCallEnd(error)`**——界面收起靠那个事件，回调里只做提示。
     */
    @JvmOverloads
    fun call(
        calleeIds: List<String>,
        mediaType: String,
        isGroup: Boolean = false,
        onResult: IMResultCallback<String>? = null,
    ) = placeCall(calleeIds, onResult, null) { IMCallInvite.plainArgs(calleeIds, mediaType, isGroup) }

    /**
     * 拨出（带选项）。群通话要带 `chatGroupId`（宿主自己的群号）或 `userData` 时用这个重载。
     *
     * **本地先拦**：`options.chatGroupId` 超 64 字节或含空白、`options.userData` 超 4096 字节——
     * 结果回 `1004`（forType `call.invite`），同时照发 `onCallEnd(reason="error", durationSec=0)`，
     * **不上线路**（`HOST_INTEGRATION_DESIGN.md` §3.3）。校验与参数拼装见 [IMCallInvite]。
     */
    @JvmOverloads
    fun call(
        calleeIds: List<String>,
        mediaType: String,
        options: IMCallOptions,
        onResult: IMResultCallback<String>? = null,
    ) = placeCall(calleeIds, onResult, options) { IMCallInvite.args(calleeIds, mediaType, options) }

    private fun placeCall(
        calleeIds: List<String>,
        onResult: IMResultCallback<String>?,
        options: IMCallOptions?,
        args: () -> Map<String, IMJson>,
    ) = post(onResult, IMFrameType.CALL_INVITE, { Wire.str(it, "call_id") }) { result ->
        // 这两道本地关卡只在 idle 时抢在状态机前面拦：不是 idle（这通 call() 其实是在
        // 另一通电话进行中时误调的，比如名单里误含自己）时抢先拒掉只会给正在进行的那通
        // 电话发一条假的 onCallEnd，把它错杀——让状态机去拒，按 §5.1 正常收成 2005。
        val violation = if (loop.ctx.call.state == IMCallState.IDLE) {
            IMCallInvite.selfViolation(connection.uid, calleeIds, IMFrameType.CALL_INVITE)
                ?: options?.let { IMCallInvite.optionsViolation(it) }
        } else {
            null
        }
        if (violation != null) {
            IMCallInvite.rejectLocally(dispatcher, result, violation)
        } else {
            loop.input(IMMachineInput.Act("call", args()), result)
            result.seal()
        }
    }

    /** 接听。结果在 `call.accept.ok` 时回来；之后自动发的进房失败走 `onError`（连锁帧，找不到调用方）。 */
    @JvmOverloads
    fun accept(onResult: IMResultCallback<Unit>? = null) = act("accept", onResult = onResult)

    /** 拒接。失败（通话已结束等）时本地照样收场（`onCallEnd` 照发），错误只供日志。 */
    @JvmOverloads
    fun reject(onResult: IMResultCallback<Unit>? = null) = act("reject", onResult = onResult)

    /** 接通前主叫取消。**接通后要用 [hangup]**，两个词不共用一条路径。失败时本地照样收场。 */
    @JvmOverloads
    fun cancel(onResult: IMResultCallback<Unit>? = null) = act("cancel", onResult = onResult)

    /** 挂断。失败时本地照样收场（`onCallEnd` 照发），错误只供日志。 */
    @JvmOverloads
    fun hangup(onResult: IMResultCallback<Unit>? = null) = act("hangup", onResult = onResult)

    /**
     * 强制结束当前这一场：**结束帧立刻上线路，本地收场，不等服务端。** 清理类：没有结果，销毁后空操作。
     *
     * 给「红键按下去、等不到结束事件」用——UIKit 的看门狗到点就调它。自画 UI 的宿主同理：
     * [hangup] 发出去几秒没收到 `onCallEnd`，就调这个。任何线程都能调，不阻塞、不抛。
     *
     * ## 与 hangup 的区别
     *
     * [hangup] 只发帧，状态由服务端的 `call.ended` 推进（§5.1）。这里不等：
     * 1. 在调用方线程上按此刻状态挑结束帧（通话中 hangup、响铃中 reject、会议里 room.leave，
     *    见 `forceEndFrames`），**不经过 engine 线程**直接交给信令连接——2026-09-13 iOS frank 那次，
     *    正常路径上的 room.join 晚了 28.6 秒、call.hangup 一帧没到服务端，卡的就是排队那一段。
     * 2. 本地收场排回 engine 线程：通话机、房间机归零，停媒体，抛 `onCallEnd`（会议抛 `onRoomLeft`）。
     *    服务端随后的 `call.ended` 因为本地已是 idle 被丢掉，不会抛第二次。
     *
     * ## 收场之后才到的东西
     *
     * - 卡在路上的 room.join 可能比 hangup 更晚到服务端并被放进房（服务端只验房票）：
     *   收到迟到的 `room.join.ok` 补发 `room.leave`（`RoomStateMachineRecv` 的 idle 分支）。
     * - 拨出时 invite.ok 还没回来：此刻没有 call_id 发不了 cancel；它回来后补发 `call.cancel`，
     *   被叫已经接起来（回来的是 `call.connected`）就补发 `call.hangup`（`CallStateMachineRecv`）。
     * - 迟到的候选、SDP 不交给媒体层（`IMConnectionEvents.onFrame`）。
     *
     * 连接断着时帧发不出去，只做本地收场；服务端那边由恢复窗口到期兜底。
     *
     * **销毁之后是空操作**：原先 `destroy()` 紧跟 `forceEnd()` 会读到还没清掉的旧状态、直发一轮结束帧，
     * 再把本地收场排进一个已经停掉的线程——`onCallEnd` 永远不来（CLIENT_PARITY `[^destroy]`）。
     */
    fun forceEnd() {
        if (destroyed) return
        loop.forceEnder.run()
    }

    /**
     * 群通话中途加邀，通话里的任何人都能发。名单里有自己本地回 `1004`；
     * 服务端拒绝（`1202` 满员 / `1407` 本端不在通话里 / `1409` 宿主拒绝）从结果回来，通话本身不受影响。
     */
    @JvmOverloads
    fun inviteMore(calleeIds: List<String>, onResult: IMResultCallback<Unit>? = null) =
        post(onResult, IMFrameType.CALL_INVITE_MORE, IMCallResult.UNIT) { result ->
            val violation = IMCallInvite.selfViolation(connection.uid, calleeIds, IMFrameType.CALL_INVITE_MORE)
            if (violation != null) {
                result.finish(violation)
                return@post
            }
            loop.input(IMMachineInput.Act("invite_more", mapOf("callee_ids" to IMJson.Arr(calleeIds.map { IMJson.Str(it) }))), result)
            result.seal()
        }

    /**
     * 主动加入一通进行中的群通话。**「怎么知道有通话在进行中」不在本协议里**——那是宿主拿 webhook
     * `call.started` 自己发广播的事，Engine 只负责把 call_id 送上去。
     *
     * 成功 = 服务端受理了（`call.join.ok`），接通事件随后到。被拒（`1401` 不存在 / `1402` 已结束 / `1202` 满员 /
     * `1408` 本人已在通话中 / `1409` 宿主拒绝）时结果是那个码，同时照发 `onCallEnd(error)`。
     */
    @JvmOverloads
    fun joinCall(callId: String, onResult: IMResultCallback<Unit>? = null) =
        act("join_call", mapOf("call_id" to IMJson.Str(callId)), onResult)

    // ── 会议房 ────────────────────────────────────────────────────────

    /**
     * 直接进一个会议房（不走振铃）。
     *
     * [autoSubscribe] 是服务端替你自动订多少（协议 §3.1，2.0.0 起是三档字符串）：
     *
     * - `"all"`（默认）音视频全自动订上，通话房与小会议用它；
     * - `"audio"` **会议分页画廊用这一档**：音频照旧自动订上（页外的人说话也听得见），
     *   视频一条都不自动订，由 [setRemoteLayer] 按当前页订与退
     *   （`none` = 五秒后退订，见 MEETING_ROOM_DESIGN §4.3）；
     * - `"none"` 一条都不自动订，全部由宿主自己订。
     *
     * 认不出的值按 §2.4 规则 6 兜底成 `"all"`。
     *
     * **这是 2.0.0 里本端唯一一处签名变化**：iOS / Web 的 `joinRoom` 本来就有这个参数，
     * 本端原先没有，会议房也就没法声明「视频我自己按页订」。加的是**可选参数**。
     *
     * **[autoSubscribe] 排在 [onResult] 之后，不排在它前面**：`@JvmOverloads` 是按
     * 参数表从右往左生成重载的，放前面的话 Java 那句既有的
     * `joinRoom(id, token, null)` 会**悄悄改绑**到新的三参 `(String, String, String)` 上——
     * 编译照过，运行时把 `null` 当档位塞进帧里。宿主那边一行没改却在进房时崩，
     * 而且崩的地方离改动十万八千里。排在后面，Java 的旧三参重载仍是
     * `(String, String, IMResultCallback)`，既有调用原样编译、原样运行。
     */
    @JvmOverloads
    fun joinRoom(
        roomId: String,
        roomToken: String,
        onResult: IMResultCallback<Unit>? = null,
        autoSubscribe: String = "all",
    ) = act(
        "join",
        mapOf(
            "room_id" to IMJson.Str(roomId),
            "room_token" to IMJson.Str(roomToken),
            "auto_subscribe" to IMJson.Str(autoSubscribe),
        ),
        onResult,
    )

    /** 离房。失败时本地照样收场（`onRoomLeft` 照发），错误只供日志。 */
    @JvmOverloads
    fun leaveRoom(onResult: IMResultCallback<Unit>? = null) = act("leave", onResult = onResult)

    // ── 设备与媒体 ────────────────────────────────────────────────────

    /**
     * 开麦克风：取消静音。**不需要照 [openCamera] 补「没发布就发布」**——音频轨道进房那一刻
     * 就无条件发布（[IMLocalPublisher.publishDefaults]），只有 `muted` 跟着 [IMMuteBook] 走。
     * 还没发布时只记意图、立即成功；已发布时结果跟着那条 `room.mute`。
     */
    @JvmOverloads
    fun openMicrophone(onResult: IMResultCallback<Unit>? = null) =
        post(onResult, IMFrameType.ROOM_MUTE, IMCallResult.UNIT) { result ->
            loop.applyMuted("audio", false, result)
            result.seal()
        }

    /** 关麦克风。清理类：本端立即静音，没有结果；`room.mute` 被拒不回滚本端（隐私优先），走 `onError`。 */
    fun closeMicrophone() = hush("audio")

    /** 开摄像头。进房时摄像头关着、视频还没发布的，这一下补发（见 [IMLocalPublisher]），补发被拒从结果回来。 */
    @JvmOverloads
    fun openCamera(onResult: IMResultCallback<Unit>? = null) =
        post(onResult, IMFrameType.ROOM_MUTE, IMCallResult.UNIT) { result ->
            loop.applyMuted("video", false, result)
            loop.publisher.publishCameraIfMissing(loop.ctx, result)
            result.seal()
        }

    /** 关摄像头。规则同 [closeMicrophone]。 */
    fun closeCamera() = hush("video")

    private fun hush(kind: String) {
        if (destroyed) return
        scheduler.post {
            val result = IMCallResult(dispatcher, null, IMCallResult.UNIT)
            loop.applyMuted(kind, true, result)
            result.seal()
        }
    }

    /** 切前后摄像头。本地设备类：没有媒体适配器或已销毁时回 `2005`。 */
    @JvmOverloads
    fun switchCamera(onResult: IMResultCallback<Unit>? = null) = post(onResult, "", IMCallResult.UNIT) { result ->
        val adapter = media ?: return@post result.finish(IMRTCError.invalidState(IMFrameLoop.NO_MEDIA))
        adapter.switchCamera()
        result.finish(null)
    }

    /** 扬声器开关。提示类：没有结果，销毁后空操作。 */
    fun setSpeakerOn(on: Boolean) {
        if (destroyed) return
        scheduler.post { media?.setSpeakerOn(on) }
    }

    /** 造一个视频视图。没有媒体适配器时返回 null——UIKit 会退回头像占位。 */
    fun createVideoView(context: android.content.Context): android.view.View? =
        media?.createVideoView(context)

    /** 把某个 uid 的画面挂到视图上。`view` 传 [createVideoView] 的产物；null 表示卸载。清理类，销毁后空操作。 */
    fun attachView(uid: String, view: Any?) {
        if (destroyed) return
        scheduler.post { loop.requireMedia()?.attachView(uid, view) }
    }

    /**
     * 起本端预览（只采集不发布），**当场返回 cid** 供 [attachLocalView]；在途再调同一个，发布沿用它。
     * 本地设备类：没有媒体适配器或已销毁时返回空串、结果回 `2005`；成功时结果值同返回值。
     */
    @JvmOverloads
    fun startLocalPreview(onResult: IMResultCallback<String>? = null): String {
        val adapter = media
        if (destroyed || adapter == null) {
            IMCallResult(dispatcher, onResult) { "" }
                .finish(IMRTCError.invalidState(if (destroyed) DESTROYED else IMFrameLoop.NO_MEDIA))
            return ""
        }
        val cid = loop.publisher.videoCid.acquire()
        val result = IMCallResult(dispatcher, onResult) { cid }
        if (!scheduler.tryPost { adapter.startLocalPreview(cid); result.finish(null) }) {
            result.finish(IMRTCError.invalidState(DESTROYED))
        }
        return cid
    }

    /** 把本端 cid 那条轨道挂到视图上（传 [createVideoView] 的产物）；null 表示卸载。清理类，销毁后空操作。 */
    fun attachLocalView(cid: String, view: Any?) {
        if (destroyed) return
        scheduler.post { loop.requireMedia()?.attachLocalView(cid, view) }
    }

    /**
     * 停掉进房前的本端预览，**连摄像头一起关**（指示灯灭）；没发布过的 cid 当场作废，再开是新的。
     * 摄像头已经发布的不受影响——通话中关摄像头走 [closeCamera]。清理类，销毁后空操作。
     */
    fun stopLocalPreview() {
        if (destroyed) return
        loop.publisher.videoCid.releaseIfUnpublished()
        scheduler.post { media?.stopLocalPreview() }
    }

    /**
     * 报某人画面的**层上界**（协议 §3.5：上界不是命令）。提示类：没有结果，失败走 `onError`，销毁后空操作。
     *
     * 九宫格缩略图报 `l`、全屏报 `h`。**不触发重协商**，也不保证立刻切——
     * 服务端要等目标层的关键帧，还会再按带宽估计压一次。
     *
     * **漏调这一条的代价是隐形的**：服务端按默认的 `m` 给每一路下发，
     * 九宫格里八个小格子每格都收半高清，带宽与解码器一起翻几倍，
     * 症状是「画面卡、掉帧」而不是任何一条报错。
     */
    fun setRemoteLayer(uid: String, layer: String) {
        if (destroyed) return
        scheduler.post {
            var sent = 0
            for ((trackId, info) in loop.ctx.room.remoteTracks) {
                if (info.uid != uid || info.kind != "video") continue
                val result = IMCallResult(dispatcher, null, IMCallResult.UNIT)
                loop.input(
                    IMMachineInput.Act("update_layer", mapOf("track_id" to IMJson.Str(trackId), "max_layer" to IMJson.Str(layer))),
                    result,
                )
                result.seal()
                sent++
            }
            /*
             **这两行是这条通路唯一的外部可见性**：服务端不记录成功的 room 帧，客户端也不逐帧打日志。
             **找不到轨道不是错**：人先进来、轨道后到是常态，轨道到了 Kit 会重报
             （`IMCallKit.invalidateReportedLayer`）。所以那一支记 DEBUG 不记 WARN。
            */
            if (sent > 0) {
                IMRTCLog.i("engine", "层上界已报 uid=$uid layer=$layer tracks=$sent")
            } else {
                IMRTCLog.d("engine", "层上界暂不发 uid=$uid layer=$layer（他的视频轨道还没到）")
            }
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /** 发起类动作的公共外壳：喂进状态机、帧交出去之后封口，结果见 [IMCallResult]。 */
    private fun act(op: String, args: Map<String, IMJson> = emptyMap(), onResult: IMResultCallback<Unit>?) =
        post(onResult, "", IMCallResult.UNIT) { result ->
            loop.input(IMMachineInput.Act(op, args), result)
            result.seal()
        }

    /**
     * 把一次宿主调用排到 engine 线程上。**已销毁、或调度器没收下**（「查 isShutdown → execute」之间线程被停掉）
     * 时当场以 `2005` 结掉——静默丢弃的话回调永远不来（R5 / R6）。
     */
    private fun <T> post(
        onResult: IMResultCallback<T>?,
        forType: String,
        valueOf: (Map<String, IMJson>) -> T,
        block: (IMCallResult<T>) -> Unit,
    ) {
        val result = IMCallResult(dispatcher, onResult, valueOf)
        if (destroyed || !scheduler.tryPost { block(result) }) {
            result.finish(IMRTCError.invalidState(DESTROYED, forType))
        }
    }
}

/** 销毁之后调发起类方法时的说明。 */
private const val DESTROYED = "engine 已销毁（destroy 之后不可再用）"
