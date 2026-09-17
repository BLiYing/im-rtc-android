package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameRegistry
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.IMScheduler
import com.imrtc.engine.signaling.IMSignalConnection
import com.imrtc.engine.statemachine.IMEngineContext
import com.imrtc.engine.statemachine.IMEngineMachine
import com.imrtc.engine.statemachine.IMMachineInput
import com.imrtc.engine.statemachine.IMMachineOutput
import com.imrtc.engine.statemachine.IMOutgoingFrame
import com.imrtc.engine.statemachine.forceEnd

/**
 * engine 的**核心循环**：输入喂进状态机 → 产出的帧发出去 → 应答再喂回来；以及每次宿主调用的结算。
 *
 * 从 [IMCallEngine] 拆出来是体量红线（CONVENTIONS §2），但这一刀本来就该切（Web 的 `frameLoop.ts` 同一刀）：
 * 门面负责**对宿主的那张 API 表**，这里负责**状态机与线路之间的往返**。状态机快照归这里管。
 *
 * 除注明的以外全部在 engine 线程上调。
 */
internal class IMFrameLoop(
    private val media: IMMediaAdapter?,
    private val scheduler: IMScheduler,
    private val dispatcher: IMEventDispatcher,
    /** 信令连接。取成函数是因为连接的事件出口反过来要引用这个循环，构造时还没有它。 */
    private val connection: () -> IMSignalConnection,
) {

    /**
     * 状态机的当前快照。**只在 engine 线程上写**（[applyOutput]），`@Volatile` 是给
     * `IMCallEngine.forceEnd` 在调用方线程上同步读的——它是不可变的 data class，读到的最多比 engine 线程晚一拍，
     * 晚的那一拍由 [IMForceEnd] 落地时的比对兜住。
     */
    @Volatile
    var ctx = IMEngineContext()
        private set

    /** 本端轨道怎么发布、发了哪些（cid → kind）。见 [IMLocalPublisher]。 */
    val publisher = IMLocalPublisher(media, scheduler::nowMs) { machineInput, result -> input(machineInput, result) }

    /** 发布之前按下的静音，攒在这儿等 track_id 回来再补做。见 [IMMuteBook]。 */
    private val muteBook = IMMuteBook()

    /** 状态迁移带来的媒体动作。见 [IMMediaDriver]。 */
    private val mediaDriver = IMMediaDriver(media, publisher, muteBook) { trackId, muted -> sendMute(trackId, muted, null) }

    /** 会议房翻页退订的五秒迟滞（`RoomStateMachinePaging.kt`）。到点喂一个内部事件回状态机。 */
    private val unsubscribeTimers = IMUnsubscribeTimers(scheduler) { trackId ->
        input(IMMachineInput.Internal("unsubscribe_hysteresis_elapsed", mapOf("track_id" to IMJson.Str(trackId))))
    }

    /** 强制收场的两段（直发结束帧 / 落地本地收场），见 [IMForceEnd]。 */
    val forceEnder by lazy {
        IMForceEnd(scheduler, connection(), { ctx }) { before, output -> applyOutput(before, output, null) }
    }

    /**
     * 等握手结论的那次 `login`（连上 / 第一次尝试失败 / logout）。见 `IMEngineEvents` 与 [settleLogin]。
     * [loginFailure] 是这次尝试里握手被拒的那个码——随后那条关闭才是结论的时刻。
     */
    var loginResult: IMCallResult<Unit>? = null
    var loginFailure: IMRTCError? = null

    /** 给 `login` 收尾。没有在等的就什么都不做。 */
    fun settleLogin(error: IMRTCError?) {
        val result = loginResult ?: return
        loginResult = null
        loginFailure = null
        result.finish(error)
    }

    /** logout：状态机、发布记账、静音意图一起归零。 */
    fun reset() {
        publisher.clear()
        muteBook.clear()
        unsubscribeTimers.clear()
        ctx = IMEngineContext()
    }

    /**
     * 输入进状态机，结果交给 [applyOutput] 落地。
     *
     * [result] 不为 null 时这是**宿主的一次调用**：本地拒绝与直接帧的失败都记给它，不发 `onError`（R3）；
     * 为 null 的是找不到调用方的输入（下行帧、内部事件、引擎自己发起的动作），帧失败照旧发 `onError`。
     * 调用方负责在这次调用的输入都喂完之后 [IMCallResult.seal]。
     */
    fun input(machineInput: IMMachineInput, result: IMCallResult<*>? = null) {
        val output = IMEngineMachine.reduce(ctx, machineInput, scheduler.nowMs())
        output.reject?.let { code ->
            logLocalReject(machineInput, code)
            result?.reject(IMRTCError.of(code, "当前状态不接受这个操作", ""))
        }
        applyOutput(ctx, output, result)
    }

    /**
     * **唯一的状态落地入口**：记状态 → 发帧 → 抛回调 → 驱动媒体。
     *
     * 只有这一条路径能改 [ctx]。多一条就会出现「帧发了但本地记账没跟上」。
     */
    private fun applyOutput(
        before: IMEngineContext,
        output: IMMachineOutput<IMEngineContext>,
        result: IMCallResult<*>?,
    ) {
        ctx = output.state

        // 把「哪条轨道是谁的」同步给媒体层。**轨道与归属谁先到都可能**：「轨道后到」那一半
        // 媒体层自己接住（IMWebRTCAdapter 收新轨道时用存好的 trackOwners 对齐），这里只管
        // 「归属变了」那一半，引用没变就跳过——remoteTracks 不可变，reduce 没碰过就还是原引用。
        if (before.room.remoteTracks !== output.state.room.remoteTracks) {
            media?.claimRemoteTracks(output.state.room.remoteTracks.mapValues { it.value.uid })
        }
        // 翻页退订的定时器**每轮对账一次**，不在各条来路上各排各撤（见 [IMUnsubscribeTimers]）。
        unsubscribeTimers.sync(output.state.room.pendingUnsubscribe)

        for (frame in output.send) sendFrame(frame, result)
        dispatcher.dispatchAll(output.emit)
        for (uid in videoTurnedOn(output.emit)) media?.awaitFirstVideoFrame(uid)
        mediaDriver.drive(before, output.state)
        // **排在 drive 之后**：新进房那一步正是在它里面发布轨道的，
        // 而要补的静音得等那些轨道的 track_id 回来（下一轮 input）才做得成。
        mediaDriver.flushPendingMutes(before, output.state)
    }

    /**
     * 开关本端某一类轨道。
     *
     * **本地静音与那条 `room.mute` 帧是两件事，不能绑死**：关掉本端轨道根本不需要
     * `track_id`（那是服务端分配的），只有帧需要。原先两者绑在一起，拿不到 track_id
     * 就连本端也不关——而「拿不到」恰恰发生在最该静音的时候（还没发布）。见 [IMMuteBook]。
     */
    fun applyMuted(kind: String, muted: Boolean, result: IMCallResult<*>?) {
        // 意图先记下：轨道还没发布时，这是唯一留得住它的地方。
        muteBook.want(kind, muted)
        // **无条件应用到本端**。轨道还不存在时它是空操作，随后 [IMMediaDriver.flushPendingMutes] 会补。
        media?.setMuted(kind, muted)

        val cid = publisher.tracks.entries.firstOrNull { it.value == kind }?.key
        val trackId = cid?.let { ctx.room.publishTrackIds[it] }
        if (trackId == null) {
            // 不是错误，是「来早了」：帧等发布完再补发。
            IMRTCLog.d("engine", "$kind 轨道还没发布，静音意图先记下（muted=$muted）")
            return
        }
        sendMute(trackId, muted, result)
    }

    private fun sendMute(trackId: String, muted: Boolean, result: IMCallResult<*>?) = input(
        IMMachineInput.Act(
            "mute",
            mapOf("track_id" to IMJson.Str(trackId), "muted" to IMJson.Bool(muted)),
        ),
        result,
    )

    /**
     * 发一帧，并把应答喂回状态机。
     *
     * [result] 不为 null 时这一帧是宿主调用直接发出的：**`.ok` 落进状态机就结算**，不等它连锁出来的帧
     * （那些帧由 `input(Recv)` 发出、不带 result，失败走 `onError`——R2）。
     */
    private fun sendFrame(frame: IMOutgoingFrame, result: IMCallResult<*>?) {
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
            connection().send(frame.type, frame.data)
            return
        }
        val startedMs = scheduler.nowMs()
        // 没连接时 request 的回调是同步回来的，所以必须先登记。
        result?.expect()
        connection().request(frame.type, frame.data) { ok, data, code, message ->
            noteSlowRequest(frame.type, startedMs, failed = !ok)
            if (ok) {
                input(IMMachineInput.Recv(frame.type + ".ok", data))
                result?.succeed(data)
            } else {
                onRequestFailed(frame, code, message, result)
            }
        }
    }

    /**
     * 记下「请求发出到拿回应答（或失败）」慢得不正常的那几次。
     *
     * 2026-09-13 iOS frank 的 room.join 从状态机产出到服务端收到隔了 28.6 秒，而端上一个字都没留下。
     * 拿这条的 `elapsed_ms` 对服务端的受理时刻，分得清慢在本端发出之前还是服务端那边。
     */
    private fun noteSlowRequest(type: String, startedMs: Long, failed: Boolean) {
        val elapsedMs = scheduler.nowMs() - startedMs
        if (elapsedMs < SLOW_REQUEST_MS) return
        IMRTCLog.w("engine", "请求往返慢 type=$type elapsed_ms=$elapsedMs failed=$failed")
    }

    /**
     * 请求被拒 / 超时 / 没连接 / 等应答时断线。
     *
     * 顺序是**先定出口、再回滚、最后结算**：找不到调用方的错误照旧先发 `onError`（与回滚前一致）；
     * 交给调用方的那个要等回滚把 `onCallEnd(error)` 之类的状态事件抛完再交付（R4：状态事件先到）。
     * 回滚判断表在 [IMRequestFailures]。
     */
    private fun onRequestFailed(frame: IMOutgoingFrame, code: IMErrorCode?, message: String, result: IMCallResult<*>?) {
        val error = IMRTCError.of(code, message, frame.type)
        IMRTCLog.w("engine", "${frame.type} 被拒：${error.name} $message")
        val taken = result?.fail(error) ?: false
        if (!taken) dispatcher.error(error)
        IMRequestFailures.handle(
            ctx,
            frame,
            input = { input(it) },
            forceEnd = { reason -> forceEndSync(reason) },
            snapshot = { ctx },
            endLocally = ::endLocally,
        )
        result?.flush()
    }

    /**
     * 在 engine 线程上**同步**强制收场：发结束帧 + 落地状态与事件，一步做完，不经 [IMForceEnd] 的
     * 跨线程两段式（那是给 `IMCallEngine.forceEnd()`——调用方线程发起、要立刻把帧直发出去——
     * 准备的）。这里已经在 engine 线程上（`onRequestFailed` 由 [sendFrame] 的请求回调触发），
     * `applyOutput` 本来就会同步发帧、落状态、抛事件，不需要再跨线程排一次。
     *
     * **`result?.flush()` 必须等这一步落完 `onCallEnd` 再交付**（R4：状态事件先到）——
     * 用 [IMForceEnd.run] 的话 `land()` 是 `scheduler.post` 排到下一个任务，`flush()` 会抢在前面。
     */
    private fun forceEndSync(reason: IMCallEndReason) {
        val plan = IMEngineMachine.forceEnd(ctx, scheduler.nowMs(), reason)
        if (plan.emit.isEmpty()) return
        IMRTCLog.w(
            "engine",
            "强制收场 call_id=${ctx.call.callId} room_id=${ctx.room.roomId} frames=${plan.send.joinToString(",") { it.type }}",
        )
        applyOutput(ctx, plan, null)
    }

    /** 按此刻状态本地收场（通话或会议），**不发帧**——结束帧刚刚已经试过了。已经收干净时什么都不做。 */
    private fun endLocally() {
        val plan = IMEngineMachine.forceEnd(ctx, scheduler.nowMs())
        if (plan.emit.isEmpty()) return
        IMRTCLog.w("engine", "结束帧失败，本地收场 call_id=${ctx.call.callId} room_id=${ctx.room.roomId}")
        applyOutput(ctx, plan.copy(send = emptyList()), null)
    }

    /** 没有媒体适配器时报 2005（找不到调用方的那几处用）。 */
    fun requireMedia(): IMMediaAdapter? {
        // 不为它新造错误码：错误码表是五仓共用的契约，加一个码等于改五个仓 + 改向量。
        if (media == null) dispatcher.error(IMErrorCode.INVALID_STATE.code, NO_MEDIA)
        return media
    }

    /**
     * 把「状态机本地拒掉了一个动作」记成一条**说得清的**日志：错误对象里只有 `2005 invalid_state`，
     * 哪个动作、当时什么状态一个字都没有。引擎自己发起的动作（`restart_pub_ice`）被拒时**只有**这一条。
     */
    private fun logLocalReject(machineInput: IMMachineInput, code: IMErrorCode) {
        val op = (machineInput as? IMMachineInput.Act)?.op ?: return
        IMRTCLog.w(
            "engine",
            "动作被状态机本地拒绝 op=$op code=${code.code} call_state=${ctx.call.state.wire} room_state=${ctx.room.state.wire}",
        )
    }

    companion object {
        /** 请求往返超过这么久记一条。正常是几十毫秒。 */
        private const val SLOW_REQUEST_MS = 2_000L

        const val NO_MEDIA = "没有媒体适配器；引 call-engine-webrtc"
    }
}
