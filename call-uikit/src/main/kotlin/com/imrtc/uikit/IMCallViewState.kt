package com.imrtc.uikit

/**
 * 通话界面的视图模型：**纯值 + 纯函数 reducer**，不碰 View、不碰 Engine。
 *
 * 这样界面逻辑（红按钮该干什么、什么时候显示接听键、九宫格里谁在说话）能在纯 JVM 单测里验完。
 * Web 端在这一层抓到过一个典型 bug：**会议房里点挂断毫无反应**——红按钮无条件走 hangup，
 * 而会议房里根本没有 call，通话机把它本地拒成 2005，宿主只看到一条没头没尾的 error。
 * 会议的结束动作是 leaveRoom，所以这里必须有 [isMeeting] 这个区分。
 */
internal data class IMCallViewState(
    val phase: Phase = Phase.IDLE,
    val callId: String = "",
    val roomId: String = "",
    /** 会议房：**没有 call，结束动作是 leaveRoom 不是 hangup**。 */
    val isMeeting: Boolean = false,
    val isGroup: Boolean = false,
    val mediaType: String = "audio",
    val role: String = "",
    val peer: String = "",
    val durationSec: Long = 0,
    val micOn: Boolean = true,
    val cameraOn: Boolean = false,
    val speakerOn: Boolean = false,
    /** uid → 这个人的音视频可用状态。 */
    val members: Map<String, Member> = emptyMap(),
    val speakingUid: String = "",
    val endReason: String = "",
) {

    enum class Phase { IDLE, INCOMING, OUTGOING, CONNECTING, CONNECTED, ENDED }

    data class Member(val uid: String, val audio: Boolean = true, val video: Boolean = false)

    /** 红按钮该干什么——**四向分派**，这是那个 Web bug 的落点。 */
    val hangupAction: Action
        get() = when {
            isMeeting -> Action.LEAVE_ROOM
            phase == Phase.INCOMING -> Action.REJECT
            phase == Phase.OUTGOING -> Action.CANCEL
            phase == Phase.CONNECTING || phase == Phase.CONNECTED -> Action.HANGUP
            else -> Action.NONE
        }

    enum class Action { NONE, REJECT, CANCEL, HANGUP, LEAVE_ROOM }

    val showAnswerButton: Boolean get() = phase == Phase.INCOMING

    /**
     * 标题栏那一行。
     *
     * **群通话与会议不能显示某一个人的名字。** 真机上把八个人叫起来，标题写着「alice」——
     * 那是名单里恰好排第一的那个人，跟这通电话是谁发起的、都有谁在，一点关系都没有。
     * 人数要 `+1`：[members] 里**不含自己**。
     */
    val titleText: String
        get() = when {
            isMeeting -> "会议（${members.size + 1} 人）"
            isGroup -> "群通话（${members.size + 1} 人）"
            peer.isNotEmpty() -> peer
            else -> "通话"
        }

    val tiles: List<Member> get() = members.values.take(IMGrid.MAX_TILES)

    val statusText: String
        get() = when (phase) {
            IMCallViewState.Phase.IDLE -> ""
            IMCallViewState.Phase.INCOMING -> if (mediaType == "video") "邀请你视频通话" else "邀请你语音通话"
            IMCallViewState.Phase.OUTGOING -> "正在呼叫…"
            IMCallViewState.Phase.CONNECTING -> "接通中…"
            IMCallViewState.Phase.CONNECTED -> IMGrid.formatDuration(durationSec)
            // **ENDED 是界面的展示状态，不是通话状态机的状态**——状态机里没有 ended，
            // 那是个事件。停留 1.5 秒再收场由界面自己控制。
            IMCallViewState.Phase.ENDED -> endedText(endReason)
        }

    private fun endedText(reason: String): String = when (reason) {
        "hangup" -> "通话结束"
        "cancel" -> "已取消"
        "reject" -> "对方拒绝"
        "busy" -> "对方忙线"
        "no_answer" -> "无人接听"
        "offline" -> "对方不在线"
        "answered_elsewhere" -> "已在其他设备接听"
        "rejected_elsewhere" -> "已在其他设备拒绝"
        "network" -> "网络断开"
        "room_closed" -> "房间已解散"
        "kicked" -> "已被移出"
        else -> "通话结束"
    }
}

/** 视图模型的全部变更入口。**界面不许自己改字段**，改法都在这里。 */
internal object IMCallViewReducer {

    fun incoming(state: IMCallViewState, callId: String, caller: String, mediaType: String, isGroup: Boolean) =
        state.copy(
            phase = IMCallViewState.Phase.INCOMING,
            callId = callId,
            // 群呼的标题走人数，不走名字——见 [IMCallViewState.titleText]。
            peer = if (isGroup) "" else caller,
            mediaType = mediaType,
            isGroup = isGroup,
            isMeeting = false,
            cameraOn = mediaType == "video",
            speakerOn = mediaType == "video",
            members = mapOf(caller to IMCallViewState.Member(caller)),
        )

    fun outgoing(state: IMCallViewState, peers: List<String>, mediaType: String, isGroup: Boolean) =
        state.copy(
            phase = IMCallViewState.Phase.OUTGOING,
            peer = if (isGroup) "" else peers.firstOrNull().orEmpty(),
            mediaType = mediaType,
            isGroup = isGroup,
            isMeeting = false,
            cameraOn = mediaType == "video",
            speakerOn = mediaType == "video",
            members = peers.associateWith { IMCallViewState.Member(it) },
        )

    fun meeting(state: IMCallViewState, roomId: String) = state.copy(
        phase = IMCallViewState.Phase.CONNECTING,
        roomId = roomId,
        isMeeting = true,
        isGroup = true,
        mediaType = "video",
        cameraOn = true,
        speakerOn = true,
    )

    fun begin(state: IMCallViewState, callId: String, roomId: String, mediaType: String, role: String) =
        state.copy(
            phase = IMCallViewState.Phase.CONNECTING,
            callId = callId,
            roomId = roomId,
            mediaType = mediaType,
            role = role,
        )

    fun connected(state: IMCallViewState) = state.copy(phase = IMCallViewState.Phase.CONNECTED)

    fun tick(state: IMCallViewState) =
        if (state.phase == IMCallViewState.Phase.CONNECTED) {
            state.copy(durationSec = state.durationSec + 1)
        } else {
            state
        }

    fun ended(state: IMCallViewState, reason: String) =
        state.copy(phase = IMCallViewState.Phase.ENDED, endReason = reason, speakingUid = "")

    fun userEnter(state: IMCallViewState, uid: String) =
        state.copy(members = state.members + (uid to (state.members[uid] ?: IMCallViewState.Member(uid))))

    fun userLeave(state: IMCallViewState, uid: String) = state.copy(members = state.members - uid)

    fun availability(state: IMCallViewState, uid: String, kind: String, available: Boolean): IMCallViewState {
        val member = state.members[uid] ?: IMCallViewState.Member(uid)
        val updated = if (kind == "video") member.copy(video = available) else member.copy(audio = available)
        return state.copy(members = state.members + (uid to updated))
    }

    fun speaking(state: IMCallViewState, uid: String) = state.copy(speakingUid = uid)

    fun toggleMic(state: IMCallViewState) = state.copy(micOn = !state.micOn)

    fun toggleCamera(state: IMCallViewState) = state.copy(cameraOn = !state.cameraOn)

    fun toggleSpeaker(state: IMCallViewState) = state.copy(speakerOn = !state.speakerOn)

    fun reset() = IMCallViewState()
}
