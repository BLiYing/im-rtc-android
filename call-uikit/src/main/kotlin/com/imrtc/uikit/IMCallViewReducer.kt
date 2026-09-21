package com.imrtc.uikit

/** 视图模型的全部变更入口。**界面不许自己改字段**，改法都在这里。 */
internal object IMCallViewReducer {

    /**
     * 进这一通电话时摄像头开不开。
     *
     * **1v1 视频默认开**：那一屏的产品意图就是看见对方，进来一片头像盘是错的。
     * **群通话默认关**（2026-09-09）：群里进去时没人在出镜，摄像头这件事该由用户自己点开。
     * 真正要紧的是它的连带后果——摄像头权限从「发起群通话的前置条件」降级成
     * 「按下那颗按钮时才要的东西」，于是**没有摄像头权限也能发起和参加群通话**
     * （见 `IMCallKit.placeCall` 的 `withCamera`）。人多的时候还顺带省掉一路上行。
     *
     * **会议房不走这里**（[meeting] 里仍是默认开）：同样的道理适用，
     * 但会议房刚按「默认开」在真机上验过，改它要重验，留到下一轮定。
     *
     * 规范见《界面规范》§04 末尾与《交互流程》§01。
     */
    fun defaultCameraOn(mediaType: String, isGroup: Boolean): Boolean =
        mediaType == "video" && !isGroup

    /**
     * `calleeIds` 是这通电话邀了谁（**已去掉自己**）。`selfUid` 是本端 uid：离场后被重新邀请回来的发起人
     * 收到的 `caller` 就是自己，「自己」不是远端成员，不摆格子。
     *
     * 主叫先摆上（他一定在通话里），其余被邀请的人摆成「还在响铃」的占位格——
     * 不摆的话群通话在两侧长得不一样：主叫看到四格（含没接的），被叫只看到两格。
     */
    fun incoming(
        state: IMCallViewState,
        callId: String,
        caller: String,
        calleeIds: List<String>,
        mediaType: String,
        isGroup: Boolean,
        chatGroupId: String = "",
        userData: String = "",
        selfUid: String = "",
        inviter: String = "",
        joinedIds: List<String> = emptyList(),
    ) = IMCallViewState(
        phase = IMCallViewState.Phase.INCOMING,
        callId = callId,
        peer = if (isGroup) "" else caller,
        caller = caller,
        inviter = inviter,
        chatGroupId = chatGroupId,
        userData = userData,
        mediaType = mediaType,
        isGroup = isGroup,
        cameraOn = defaultCameraOn(mediaType, isGroup),
        // **默认不外放**（拍板 2026-09-06）：视频通话一样从听筒出声，要外放由用户自己点。
        speakerOn = false,
        // 已在通话里的人摆正常格子，其余 callee 才是「呼叫中…」占位格；旧服务端不带 joined_ids 时回落成只有发起人。
        members = (joinedIds.ifEmpty { listOf(caller) } + calleeIds)
            .filter { it != selfUid }.distinct()
            .associateWith { IMCallViewState.Member(it, accepted = it in joinedIds.ifEmpty { listOf(caller) }) },
        connection = state.connection,
    )

    fun outgoing(
        state: IMCallViewState,
        peers: List<String>,
        mediaType: String,
        isGroup: Boolean,
        chatGroupId: String = "",
        userData: String = "",
    ) = IMCallViewState(
        phase = IMCallViewState.Phase.OUTGOING,
        peer = if (isGroup) "" else peers.firstOrNull().orEmpty(),
        mediaType = mediaType,
        isGroup = isGroup,
        chatGroupId = chatGroupId,
        userData = userData,
        role = "caller",
        cameraOn = defaultCameraOn(mediaType, isGroup),
        // **默认不外放**（拍板 2026-09-06）：视频通话一样从听筒出声，要外放由用户自己点。
        speakerOn = false,
        // 呼出时对方还没接——**先摆上去且标成未接听**，界面才有「呼叫中…」的占位格。
        members = peers.associateWith { IMCallViewState.Member(it, accepted = false) },
        connection = state.connection,
    )

    fun meeting(state: IMCallViewState, roomId: String) = IMCallViewState(
        phase = IMCallViewState.Phase.CONNECTING,
        roomId = roomId,
        isMeeting = true,
        isGroup = true,
        mediaType = "video",
        cameraOn = true,
        // 一条规则到底：**默认都不外放**，要外放由用户自己点（拍板 2026-09-06）。
        speakerOn = false,
        connection = state.connection,
    )

    /**
     * `caller` / `chatGroupId` / `userData` 传 null 表示「不改」——**只有旧测试的 5 参数调用点用得到**：
     * production 里 [IMKitListener.onCallBegin] 永远原样传 `onCallBegin` 回调给的值（引擎已经做过
     * §3.3 的回落，绝不会平白把已经记下的 `state.caller` 冲掉）。
     */
    fun begin(
        state: IMCallViewState,
        callId: String,
        roomId: String,
        mediaType: String,
        role: String,
        isGroup: Boolean = false,
        caller: String? = null,
        chatGroupId: String? = null,
        userData: String? = null,
    ) = state.copy(
        phase = IMCallViewState.Phase.CONNECTING,
        callId = callId,
        roomId = roomId,
        mediaType = mediaType,
        role = role,
        // `call.join` 进来的人没经过 outgoing/incoming，isGroup 只有这里第一次拿得到；
        // 已经是群通话的（incoming 记过）不会被它翻回 false。
        isGroup = state.isGroup || isGroup,
        caller = caller ?: state.caller,
        chatGroupId = chatGroupId ?: state.chatGroupId,
        userData = userData ?: state.userData,
        hint = "",
    )

    /**
     * 主动加入一通进行中的群通话（`IMCallKit.joinCall`，HOST_INTEGRATION_DESIGN §3.4）。
     *
     * 直接进「接通中…」（CONNECTING 阶段的文案本来就是它），不等 `call.join.ok`；
     * `chatGroupId` / `caller` 要等 [begin]（`onCallBegin`）才拿得到——加入者没收过 `onCallReceived`。
     */
    fun joining(state: IMCallViewState, callId: String) = IMCallViewState(
        phase = IMCallViewState.Phase.CONNECTING,
        callId = callId,
        isGroup = true,
        role = "callee",
        speakerOn = false,
        connection = state.connection,
    )

    fun connected(state: IMCallViewState) = state.copy(phase = IMCallViewState.Phase.CONNECTED)

    fun tick(state: IMCallViewState) =
        if (state.phase == IMCallViewState.Phase.CONNECTED) state.copy(durationSec = state.durationSec + 1) else state

    /** 结束。**顺手把小窗展开**：结束原因要让用户看见，藏在一个小球里等于没提示。 */
    /**
     * @param durationSec 通话时长，**由服务端给**（`call.ended.duration_sec`）。
     *   不变量 I8：四端禁止自己算时长（时钟对不齐）。传 -1 表示「沿用本地计时」——
     *   会议房没有 `call.ended`，那一条只能靠本地的计数器。
     */
    fun ended(state: IMCallViewState, reason: String, durationSec: Long = -1): IMCallViewState {
        // **已经收起来了就不再弹结束画面。** 红键看门狗本地收场、界面收起之后，Engine 的
        // onCallEnd（强制收场那条，或服务端迟到的那条）还会再来一次；照样进 ENDED 的话，
        // 「通话已结束」又闪 1.5 秒（2026-09-13 iOS frank 14:58:21 那一下）。
        if (state.phase == IMCallViewState.Phase.IDLE) return state
        return state.copy(
            phase = IMCallViewState.Phase.ENDED,
            endReason = reason,
            durationSec = if (durationSec >= 0) durationSec else state.durationSec,
            speakingUid = "",
            isMinimized = false,
            hint = "",
        )
    }

    fun userEnter(state: IMCallViewState, uid: String) = withMember(state, uid) { it.copy(accepted = true, settled = IMCallViewState.Settled.NONE) }

    fun userLeave(state: IMCallViewState, uid: String) = state.copy(members = state.members - uid)

    /**
     * 进房快照：房里此刻有谁。已接听却不在快照里的人，是响铃阶段（不在房里）时离场的——收掉他们的格子。
     * 还在响铃的占位格（`accepted == false`）不归它管。
     */
    fun roomSnapshot(state: IMCallViewState, uids: List<String>): IMCallViewState {
        // 只有被叫需要：他响铃时不在房里、名单靠来电帧摆的；主叫一路收着裁决帧，无需对账（还免得刚接听的人闪一下）。
        if (state.role != "callee") return state
        val present = uids.toSet()
        return state.copy(members = state.members.filter { (uid, m) -> !m.accepted || uid in present })
    }

    /** 主叫往群通话里又拉了一批人，先摆上占位格；已在名单里的不重复加。 */
    fun invited(state: IMCallViewState, uids: List<String>): IMCallViewState {
        val fresh = uids.filter { it !in state.members }.associateWith { IMCallViewState.Member(it, accepted = false) }
        return if (fresh.isEmpty()) state else state.copy(members = state.members + fresh)
    }

    /**
     * 某人的设备开始响铃：**不是本端加的人也摆占位格**。协议 2026-09-17 起 `call.ringing` 发给通话里的所有人——
     * A 加了 B，C 看不见 B 在响的话，只会凭空收到「B 没接听」，还会再邀请一次。
     * 只在群通话里摆（1v1 的对方本来就是大画面）；已接听的不动；标了终局又被重新邀请的清掉终局。
     */
    fun userRinging(state: IMCallViewState, uid: String): IMCallViewState {
        val live = state.phase in setOf(IMCallViewState.Phase.OUTGOING, IMCallViewState.Phase.CONNECTING, IMCallViewState.Phase.CONNECTED)
        if (!state.isGroup || !live) return state
        val member = state.members[uid] ?: return invited(state, listOf(uid))
        if (member.accepted || member.settled == IMCallViewState.Settled.NONE) return state
        return state.copy(members = state.members + (uid to member.copy(settled = IMCallViewState.Settled.NONE)))
    }

    /**
     * 邀请中的人给出了终局（拒接 / 无应答）：**先在格子上写明终局，停一会再收**（交互稿 §05 G3）。
     * 直接收掉的话，从主叫的角度看拒接就跟没发生过一样。已接听的人收到终局（理论上不会）直接忽略。
     */
    fun userSettled(state: IMCallViewState, uid: String, settled: IMCallViewState.Settled): IMCallViewState {
        val member = state.members[uid] ?: return state
        if (member.accepted) return state
        return state.copy(members = state.members + (uid to member.copy(settled = settled)))
    }

    /** 终局停够了，把格子收掉。 */
    fun userRemove(state: IMCallViewState, uid: String) = state.copy(members = state.members - uid)

    /** 服务端说本端不在通话里（1407）：藏掉加人入口。 */
    fun inviteDenied(state: IMCallViewState) = state.copy(canInvite = false, hint = IMText.t("hint.inviteDenied"))

    /**
     * 画面**从无到有**时先挂起（[IMCallViewState.Member.videoPending]），等首帧再揭示。
     * 已经有画面时再来一次 true（比如重连后的快照）不挂起——不然正在播的画面会闪回头像。
     */
    fun availability(state: IMCallViewState, uid: String, kind: String, available: Boolean) =
        withMember(state, uid) {
            if (kind != "video") {
                it.copy(audio = available)
            } else {
                it.copy(video = available, videoPending = available && (it.videoPending || !it.video))
            }
        }

    /** 新画面上屏了（或兜底到点）：揭示。**不补建成员**——兜底可能在人走之后才到点。没变化时原样返回。 */
    fun firstVideoFrame(state: IMCallViewState, uid: String): IMCallViewState {
        val member = state.members[uid]?.takeIf { it.videoPending } ?: return state
        return state.copy(members = state.members + (uid to member.copy(videoPending = false)))
    }

    fun networkQuality(state: IMCallViewState, levels: Map<String, Int>) = state.copy(
        members = state.members.mapValues { (uid, m) -> levels[uid]?.let { m.copy(networkLevel = it) } ?: m },
    )

    /**
     * 收下一份说话人名单。**服务端给的是全量快照**，不在名单里的人一律清成「没说话」。
     *
     * @param loudest 音量最大的那个 uid，只给悬浮球挑缩略画面用
     * @param selfUid 本端 uid，用来把本端那格从名单里认出来
     */
    fun speaking(
        state: IMCallViewState,
        volumes: Map<String, Int>,
        loudest: String,
        selfUid: String,
    ) = state.copy(
        speakingUid = loudest,
        selfSpeaking = selfUid.isNotEmpty() && volumes.containsKey(selfUid),
        selfVolume = volumes[selfUid] ?: 0,
        members = state.members.mapValues { (uid, m) ->
            m.copy(speaking = volumes.containsKey(uid), volume = volumes[uid] ?: 0)
        },
    )

    fun connection(state: IMCallViewState, connection: IMCallViewState.Connection) = state.copy(connection = connection)

    fun hint(state: IMCallViewState, text: String) = state.copy(hint = text)

    /** 摄像头拿不到（权限被拒 / 没设备）：通话继续，按钮禁用。 */
    fun cameraBlocked(state: IMCallViewState) = state.copy(cameraOn = false, cameraBlocked = true)

    /** 收进小窗。来电页与结束画面不许收，见 [IMCallViewState.canMinimize]。 */
    fun minimize(state: IMCallViewState) = if (state.canMinimize) state.copy(isMinimized = true) else state

    /** 从小窗 / 横幅展开回全屏。 */
    fun expand(state: IMCallViewState) = state.copy(isMinimized = false)

    fun setSwapped(state: IMCallViewState, swapped: Boolean) = state.copy(isSwapped = swapped)

    fun toggleMic(state: IMCallViewState) = state.copy(micOn = !state.micOn)

    /**
     * 权限被拒时开不了：按钮本来就是禁用态，这里再挡一道免得状态漂移。
     * 来电页上的这一下还决定「以语音接听」与否，见 [IMCallViewState.cameraOptedOut]。
     */
    fun toggleCamera(state: IMCallViewState): IMCallViewState {
        if (state.cameraBlocked) return state
        val on = !state.cameraOn
        val optedOut = if (state.phase == IMCallViewState.Phase.INCOMING) !on else state.cameraOptedOut
        return state.copy(cameraOn = on, cameraOptedOut = optedOut)
    }

    fun toggleSpeaker(state: IMCallViewState) = state.copy(speakerOn = !state.speakerOn)

    fun reset() = IMCallViewState()

    /** 更新一个成员；**不存在时先补进来**——事件比进房通知先到是常态。 */
    private fun withMember(
        state: IMCallViewState,
        uid: String,
        update: (IMCallViewState.Member) -> IMCallViewState.Member,
    ) = state.copy(members = state.members + (uid to update(state.members[uid] ?: IMCallViewState.Member(uid))))
}
