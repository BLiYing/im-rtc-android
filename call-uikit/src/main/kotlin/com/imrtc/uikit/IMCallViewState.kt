package com.imrtc.uikit

/**
 * 通话界面的视图模型：**纯值 + 纯函数 reducer**，不碰 View、不碰 Engine。
 *
 * 这样界面逻辑（红按钮该干什么、什么时候显示接听键、九宫格里谁在说话、用哪种版式）能在纯 JVM 单测里验完。
 * Web 端在这一层抓到过一个典型 bug：**会议房里点挂断毫无反应**——红按钮无条件走 hangup，
 * 而会议房里根本没有 call，通话机把它本地拒成 2005。会议的结束动作是 leaveRoom，所以这里必须有 [isMeeting]。
 *
 * 与 iOS 的 `IMCallViewState` / Web 的 `callView.ts` 同构：同样的 phase、同样的动作、同样的坑。
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
    /** 摄像头权限被拒（或没有设备）。**通话继续，只是没有画面**（交互稿 §02 P3）：按钮变禁用态写「无权限」。 */
    val cameraBlocked: Boolean = false,
    /** uid → 这个人的状态。**不含自己**。 */
    val members: Map<String, Member> = emptyMap(),
    /**
     * 此刻音量最大的那个人。
     *
     * **它可能是本端自己**，而 [members] 不含自己 —— `room.active_speakers`
     * 把房里每个人都报上来（含本端），Kit 只是原样取音量最大的那一个。
     * 所以**凡是拿它去找一块远端画面的地方，都必须先确认这个 uid 在 members 里**，
     * **音量最大的那一个人**。只给悬浮球用——它一次只放得下一路缩略画面。
     *
     * **判断某个格子要不要显示说话图标不能用它**：服务端一次给的是一份名单
     * （协议 §3.5 全量快照），三个人同时说话时这里只留得下一个，
     * 而那人若恰好是本端，远端一个格子都不会亮。格子看 [Member.speaking]。
     * 真机 2026-09-09 「说话没高亮」就是这么来的。
     */
    val speakingUid: String = "",
    /** 本端在不在说话（本端那格没有 Member，只能单独记）。 */
    val selfSpeaking: Boolean = false,
    /** 本端音量 0~100。 */
    val selfVolume: Int = 0,
    val endReason: String = "",
    /** 通话已被收进悬浮球 / 画中画。**通话本身照常进行**——这只是呈现形态。 */
    val isMinimized: Boolean = false,
    /** 1v1 视频里两块画面是否互换了（交互稿 §04）：false = 远端全屏、本端小窗。纯本端行为，但层上界要跟着换。 */
    val isSwapped: Boolean = false,
    /** 信令连接的状态，驱动顶部的橙条。 */
    val connection: Connection = Connection.OK,
    /** 还能不能加人。主叫默认能；收到 `1407 not_call_owner` 后关掉（兜底，正常情况下非主叫看不到入口）。 */
    val canInvite: Boolean = true,
    /** 一句给用户看的提示（「通话已满员」这类）。 */
    val hint: String = "",
) {

    enum class Phase { IDLE, INCOMING, OUTGOING, CONNECTING, CONNECTED, ENDED }

    enum class Connection { OK, RECONNECTING, LOST }

    /** 邀请中的成员给出的终局：拒了 / 没接。有终局的格子停 2s 再移除（交互稿 §05 G3）。 */
    enum class Settled { NONE, REJECTED, NO_ANSWER, OFFLINE }

    /**
     * 一个远端成员。`audio` 默认 true：`onUserAudioAvailable` 只在**变化**时抛，
     * 一开始就正常的人不会有事件——默认 false 会让所有人一进来都显示成静音。
     */
    data class Member(
        val uid: String,
        val audio: Boolean = true,
        val video: Boolean = false,
        /** 群通话里是否已接听。false = 还在响铃（占位格）。 */
        val accepted: Boolean = true,
        val settled: Settled = Settled.NONE,
        /** 网络质量 0~6，0 = 未知。 */
        val networkLevel: Int = 0,
        /** 是不是正在说话。**每个人各记各的**——见 [IMCallViewState.speakingUid] 那段。 */
        val speaking: Boolean = false,
        /** 0~100 的音量，映射到说话图标的条高。服务端 300ms 一次。 */
        val volume: Int = 0,
    )

    /** 三种版式（规范 §03 / §04）。 */
    enum class Layout { AUDIO, VIDEO, GRID }

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

    /** 能不能收进小窗。**只有已经接通了才行**：拨出中收起来，剩一个不会动的小球，用户不知道对方接没接。 */
    val canMinimize: Boolean get() = phase == Phase.CONNECTING || phase == Phase.CONNECTED

    /** 通话中该不该显示「摄像头」按钮。**只看 media_type**：语音通话里不给（拍板 §11-10）。 */
    val showsCameraButton: Boolean get() = mediaType == "video"

    /**
     * 要不要给「添加成员」入口（交互稿 §05）。三个条件缺一不可：是群通话（会议房没有 call）、
     * 本端是主叫（协议 1407：非主叫发 `invite_more` 会被拒）、房间没满（含本端 9 人）。
     */
    val canShowInvite: Boolean
        get() = isGroup && !isMeeting && role == "caller" && canInvite &&
            members.size + 1 < IMGrid.MAX_TILES &&
            (phase == Phase.CONNECTED || phase == Phase.CONNECTING)

    /** 还能加几个人（选人页顶部「还能加 N 人」）。 */
    val inviteSlotsLeft: Int get() = maxOf(IMGrid.MAX_TILES - 1 - members.size, 0)

    /**
     * 用哪种版式。
     *
     * **接通后的 1v1 视频恒为 VIDEO 版式**，哪怕两边都关着摄像头——那时全屏格与小窗各显示一个
     * 头像盘。原先是「都没画面就退回语音版式」，实测下来不对：小窗会整个消失，用户以为通话断了，
     * 而且关掉摄像头之后就再也点不到「互换」。没画面是格子的事，不是版式的事。
     *
     * 拨出中与来电页仍用语音版式：那时对端画面不存在，本端预览叠在右上角。
     * 与 iOS 的 `imPickLayout(for:)` 是同一条判据。
     */
    val layout: Layout get() = when {
        isGroup || isMeeting -> Layout.GRID
        mediaType != "video" -> Layout.AUDIO
        phase == Phase.OUTGOING || phase == Phase.INCOMING -> Layout.AUDIO
        else -> Layout.VIDEO
    }

    /** 标题栏那一行。**群通话与会议不能显示某一个人的名字**；人数要 `+1`：[members] 里不含自己。 */
    val titleText: String
        get() = when {
            isMeeting -> "会议 · ${members.size + 1} 人"
            isGroup -> "群通话 · ${members.size + 1} 人"
            peer.isNotEmpty() -> peer
            else -> "通话"
        }

    /**
     * 九宫格里要摆的远端成员。
     *
     * 截到 [IMGrid.MAX_REMOTE_TILES]（8）而不是 9：**本端恒占一格**，
     * 9 个远端加上自己就是 10 格，而行列只有 9 个坑——GridLayout 会越过 `rowCount`
     * 往下多排一行、跑出居中块。群通话有服务端的 9 人硬上限碰不到，**会议房不设上限**。
     */
    val tiles: List<Member> get() = members.values.take(IMGrid.MAX_REMOTE_TILES)

    val statusText: String
        get() = when {
            hint.isNotEmpty() -> hint
            phase == Phase.IDLE -> ""
            phase == Phase.INCOMING -> when {
                isGroup -> "邀请你加入群通话"
                mediaType == "video" -> "邀请你视频通话"
                else -> "邀请你语音通话"
            }
            phase == Phase.OUTGOING -> "正在呼叫…"
            phase == Phase.CONNECTING -> if (isMeeting) "正在进入会议…" else "接通中…"
            phase == Phase.CONNECTED -> IMGrid.formatDuration(durationSec)
            // **ENDED 是界面的展示状态，不是通话状态机的状态**——状态机里没有 ended，那是个事件。
            else -> if (isMeeting) "已离开会议" else endReasonText(endReason, role, durationSec)
        }

    companion object {
        /** 结束原因的人话（规范 §08），与 iOS 的 `imEndReasonText` / Web 的 `endReasonText` 逐字对齐。 */
        fun endReasonText(reason: String, role: String, durationSec: Long): String = when (reason) {
            "hangup" -> if (durationSec > 0) "通话结束 · ${IMGrid.formatDuration(durationSec)}" else "通话结束"
            "cancel" -> if (role == "caller") "已取消" else "对方已取消"
            "reject" -> if (role == "caller") "对方已拒接" else "已拒接"
            "busy" -> "对方忙线中"
            "no_answer" -> if (role == "caller") "对方无人接听" else "未接来电"
            "offline" -> "对方当前不在线"
            "answered_elsewhere" -> "已在其他设备接听"
            "rejected_elsewhere" -> "已在其他设备拒绝"
            "network" -> "网络中断"
            "room_closed" -> "房间已解散"
            "kicked" -> "已被移出"
            else -> "已结束"
        }

        /** 占位格上终局的人话。 */
        fun settledText(settled: Settled): String = when (settled) {
            Settled.NONE -> ""
            Settled.REJECTED -> "已拒绝"
            Settled.NO_ANSWER -> "未接听"
            Settled.OFFLINE -> "对方不在线"
        }

        /** 网络质量的人话（协议 §3.5 的表）。 */
        fun networkText(level: Int): String = when {
            level <= 0 -> ""
            level <= 2 -> "网络良好"
            level <= 4 -> "网络一般"
            level == 5 -> "网络很差"
            else -> "正在重连…"
        }

        /** 三根柱子亮几根：1~2 三根、3~4 两根、5~6 一根；0 不画。 */
        fun networkBarsLit(level: Int): Int = when {
            level <= 0 -> 0
            level <= 2 -> 3
            level <= 4 -> 2
            else -> 1
        }

        /** 要不要出「对方网络不佳」的提示（3 以上）。 */
        fun isNetworkPoor(level: Int): Boolean = level >= 3
    }
}

/*
videoSpeakerUid 挑「该显示谁的画面」——**只在远端成员里挑**。

# 为什么不能直接用 speakingUid

`room.active_speakers` **包含本端自己**，而本端音量往往就是最大的那个
（真机 2026-09-08 的一通 1v1：alice 45 / carol 36，两人交替领先，一秒好几次）。
于是 `speakingUid` 在 "alice" 与 "carol" 之间来回跳。

悬浮球原先写的是 `speakingUid.ifEmpty { members.keys.first() }`，
跳到 "alice" 时就拿本端 uid 去要一块远端画面：`videoViewFor` 照样造一个渲染器、
`attachView("alice", …)` 挂上去 —— **可本端根本没有远端轨道，那块画面永远是黑的**，
而且每跳一次就换一个 view，`videoHost` 摘一次挂一次，`SurfaceView` 的 surface
跟着销毁重建。用户看到的就是「小窗视频时不时黑屏一下」（2026-09-08 报的现象 1）。

`ifEmpty` 挡不住这一类：uid 不是空的，只是**不该拿来找远端画面**。
*/
internal fun IMCallViewState.videoSpeakerUid(): String {
    if (speakingUid.isNotEmpty() && members.containsKey(speakingUid)) return speakingUid
    return members.keys.firstOrNull().orEmpty()
}

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
     * `calleeIds` 是这通电话邀了谁（**已去掉自己**）。
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
    ) = IMCallViewState(
        phase = IMCallViewState.Phase.INCOMING,
        callId = callId,
        peer = if (isGroup) "" else caller,
        mediaType = mediaType,
        isGroup = isGroup,
        cameraOn = defaultCameraOn(mediaType, isGroup),
        // **默认不外放**（拍板 2026-09-06）：视频通话一样从听筒出声，要外放由用户自己点。
        speakerOn = false,
        members = linkedMapOf(caller to IMCallViewState.Member(caller)) +
            calleeIds.filter { it != caller }
                .associateWith { IMCallViewState.Member(it, accepted = false) },
        connection = state.connection,
    )

    fun outgoing(state: IMCallViewState, peers: List<String>, mediaType: String, isGroup: Boolean) =
        IMCallViewState(
            phase = IMCallViewState.Phase.OUTGOING,
            peer = if (isGroup) "" else peers.firstOrNull().orEmpty(),
            mediaType = mediaType,
            isGroup = isGroup,
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

    fun begin(state: IMCallViewState, callId: String, roomId: String, mediaType: String, role: String) =
        state.copy(
            phase = IMCallViewState.Phase.CONNECTING,
            callId = callId,
            roomId = roomId,
            mediaType = mediaType,
            role = role,
            hint = "",
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
    fun ended(state: IMCallViewState, reason: String, durationSec: Long = -1) = state.copy(
        phase = IMCallViewState.Phase.ENDED,
        endReason = reason,
        durationSec = if (durationSec >= 0) durationSec else state.durationSec,
        speakingUid = "",
        isMinimized = false,
        hint = "",
    )

    fun userEnter(state: IMCallViewState, uid: String) = withMember(state, uid) { it.copy(accepted = true, settled = IMCallViewState.Settled.NONE) }

    fun userLeave(state: IMCallViewState, uid: String) = state.copy(members = state.members - uid)

    /** 主叫往群通话里又拉了一批人，先摆上占位格；已在名单里的不重复加。 */
    fun invited(state: IMCallViewState, uids: List<String>): IMCallViewState {
        val fresh = uids.filter { it !in state.members }.associateWith { IMCallViewState.Member(it, accepted = false) }
        return if (fresh.isEmpty()) state else state.copy(members = state.members + fresh)
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

    /** 服务端说不是主叫（1407）：藏掉加人入口。 */
    fun inviteDenied(state: IMCallViewState) = state.copy(canInvite = false, hint = "只有发起人可以添加成员")

    fun availability(state: IMCallViewState, uid: String, kind: String, available: Boolean) =
        withMember(state, uid) { if (kind == "video") it.copy(video = available) else it.copy(audio = available) }

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

    /** 收进小窗。**接通之前不许收**，见 [IMCallViewState.canMinimize]。 */
    fun minimize(state: IMCallViewState) = if (state.canMinimize) state.copy(isMinimized = true) else state

    /** 从小窗 / 横幅展开回全屏。 */
    fun expand(state: IMCallViewState) = state.copy(isMinimized = false)

    fun setSwapped(state: IMCallViewState, swapped: Boolean) = state.copy(isSwapped = swapped)

    fun toggleMic(state: IMCallViewState) = state.copy(micOn = !state.micOn)

    /** 权限被拒时开不了：按钮本来就是禁用态，这里再挡一道免得状态漂移。 */
    fun toggleCamera(state: IMCallViewState) =
        if (state.cameraBlocked) state else state.copy(cameraOn = !state.cameraOn)

    fun toggleSpeaker(state: IMCallViewState) = state.copy(speakerOn = !state.speakerOn)

    fun reset() = IMCallViewState()

    /** 更新一个成员；**不存在时先补进来**——事件比进房通知先到是常态。 */
    private fun withMember(
        state: IMCallViewState,
        uid: String,
        update: (IMCallViewState.Member) -> IMCallViewState.Member,
    ) = state.copy(members = state.members + (uid to update(state.members[uid] ?: IMCallViewState.Member(uid))))
}
