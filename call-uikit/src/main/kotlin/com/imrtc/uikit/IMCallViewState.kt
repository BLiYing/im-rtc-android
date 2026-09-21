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
    /** 发起人 uid，只在被叫侧有值（主叫侧就是自己）。邀请上下文的 `callerUid` 从这里取。 */
    val caller: String = "",
    /**
     * **把你加进来的那个人**，只在被叫侧有值。群通话中途被加进来时他不是 [caller]；
     * 空串表示旧服务端没带（见 [incomingFromUid] 的回落）。**只用于来电界面显示谁在邀请你**，
     * 摆格子与选人页一律用 [caller]。
     */
    val inviter: String = "",
    /**
     * 宿主自己的群号，来自 `onCallReceived` / `onCallBegin`（可能为空——不是每通电话都属于某个群）。
     * `IMInviteMemberProvider` 靠它决定「添加成员」该向宿主要哪个群的候选人
     * （`HOST_INTEGRATION_DESIGN.md` §3.4）。
     */
    val chatGroupId: String = "",
    /** 同 [chatGroupId]，主叫在 `IMCallOptions.userData` 里塞的 opaque 数据，原样透传给 provider。 */
    val userData: String = "",
    val durationSec: Long = 0,
    val micOn: Boolean = true,
    val cameraOn: Boolean = false,
    val speakerOn: Boolean = false,
    /** 摄像头权限被拒（或没有设备）。**通话继续，只是没有画面**（交互稿 §02 P3）：按钮变禁用态写「无权限」。 */
    val cameraBlocked: Boolean = false,
    /**
     * 用户在**来电页上亲手关掉了**摄像头（拍板 §11-10：关掉摄像头再接听 = 以语音接听）。
     *
     * 不能拿 `!cameraOn` 代替：群通话默认就是关着进来的，那不是用户的选择，
     * 接听时照样要问摄像头权限（交互稿 §01）。
     */
    val cameraOptedOut: Boolean = false,
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
    /** 还能不能加人。默认能；收到 `1407 not_call_owner`（本端已不在通话里）后关掉（兜底，正常情况下那时看不到入口）。 */
    val canInvite: Boolean = true,
    /** 一句给用户看的提示（「通话已满员」这类）。 */
    val hint: String = "",
) {

    enum class Phase { IDLE, INCOMING, OUTGOING, CONNECTING, CONNECTED, ENDED }

    enum class Connection { OK, RECONNECTING, LOST }

    /**
     * 邀请中的成员给出的终局：拒了 / 没接。有终局的格子停 2s 再移除（交互稿 §05 G3）。
     *
     * **没有 OFFLINE**：群通话里成员「不在线」在线路上折进了 `call.no_answer{uid}`
     * （server `RTC_PROTOCOL.md` §4.3「群通话里的『不在线』多发一条 call.no_answer{uid}」），
     * Kit 收到的只有 [NO_ANSWER]，没有独立的线路事件能落到这个值上。
     */
    enum class Settled { NONE, REJECTED, NO_ANSWER }

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
        /**
         * 画面刚变成可用、**新画面还没上屏**：等 `onFirstVideoFrame`（兜底见 `IMKitListener.REVEAL_FALLBACK_MS`）。
         * 这段时间格子照旧露头像——渲染器整通复用，Surface 上还留着关摄像头之前的最后一帧。
         */
        val videoPending: Boolean = false,
    ) {
        /** 格子该不该露画面。**界面只认这个，不认 [video]**。 */
        val showsVideo: Boolean get() = video && !videoPending
    }

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

    /**
     * 能不能收进小窗：拨出中、接通中、通话中都行，与 iOS / Web 同一条（除了来电页与结束画面）。
     * **来电页不给**：小窗上没有接听键，收进去就接不了。拨出中收起来有球下的红键可取消，被拒 / 忙线由 [IMCallViewReducer.ended] 展开回全屏报原因。
     */
    val canMinimize: Boolean get() = phase == Phase.OUTGOING || phase == Phase.CONNECTING || phase == Phase.CONNECTED

    /** 通话中该不该显示「摄像头」按钮。**只看 media_type**：语音通话里不给（拍板 §11-10）。 */
    val showsCameraButton: Boolean get() = mediaType == "video"

    /**
     * 要不要给「添加成员」入口（交互稿 §05）。条件缺一不可：是群通话（会议房没有 call）、已接通、房间没满（含本端 9 人）。
     * **不看主叫被叫**：通话里的任何人都能加人（2026-09-15 起）；还在响铃的人阶段不对，自然没有入口。
     */
    val canShowInvite: Boolean
        get() = isGroup && !isMeeting && canInvite &&
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

    /**
     * 来电横幅 / 来电页上显示的那个人：**谁邀请的你**。
     *
     * 群通话里加你进来的不一定是发起人（`call.invite_more`），所以优先用 [inviter]。
     * 旧服务端不带那个字段时回落到「格子里第一个人」——即这条改动之前一直在用的人。
     */
    val incomingFromUid: String get() = inviter.ifEmpty { members.keys.firstOrNull() ?: peer }

    /**
     * 标题栏那一行。**群通话与会议不能显示某一个人的名字**；人数要 `+1`：[members] 里不含自己。
     *
     * 会议写**房号**、不写人数：右上角那颗「👥 N」已经是人数的出处，标题再写一遍
     * 就是同一个数字的第二处真相。房号才是这一屏里要念给别人听的那个东西（点一下能复制）。
     */
    val titleText: String
        get() = when {
            isMeeting -> if (roomId.isEmpty()) IMText.t("call.meeting") else IMText.t("call.meetingRoom", "room" to roomId)
            isGroup -> IMText.t("call.group", "n" to (members.size + 1))
            peer.isNotEmpty() -> peer
            else -> IMText.t("call.default")
        }

    /**
     * 九宫格里要摆的远端成员。
     *
     * 截到 [IMGrid.MAX_REMOTE_TILES]（8）而不是 9：**本端恒占一格**，
     * 9 个远端加上自己就是 10 格，而行列只有 9 个坑——GridLayout 会越过 `rowCount`
     * 往下多排一行、跑出居中块。群通话有服务端的 9 人硬上限碰不到，**会议房不设上限**。
     */
    val tiles: List<Member> get() = members.values.take(IMGrid.MAX_REMOTE_TILES)

    /** 没有格子的远端成员（会议房超过一屏时）：声音照收，视频报 none，胶囊说一句「还有 N 人未显示」。 */
    val hiddenMembers: List<Member> get() = members.values.drop(IMGrid.MAX_REMOTE_TILES)

    val statusText: String
        get() = when {
            hint.isNotEmpty() -> hint
            phase == Phase.IDLE -> ""
            phase == Phase.INCOMING -> when {
                isGroup -> IMText.t("incoming.group")
                mediaType == "video" -> IMText.t("incoming.video")
                else -> IMText.t("incoming.audio")
            }
            phase == Phase.OUTGOING -> IMText.t("call.status.calling")
            phase == Phase.CONNECTING -> IMText.t(if (isMeeting) "call.status.enteringMeeting" else "call.status.connecting")
            phase == Phase.CONNECTED -> IMGrid.formatDuration(durationSec)
            // **ENDED 是界面的展示状态，不是通话状态机的状态**——状态机里没有 ended，那是个事件。
            else -> if (isMeeting) IMText.t("end.meetingLeft") else endReasonText(endReason, role, durationSec)
        }

    companion object {

        /**
         * 本地收场时写哪个结束原因。**照红键实际发出去的那个动作写**，不写 `"network"`。
         *
         * 复现出来的那一次网络是好的——是权限门没落定、帧压根没发出去。
         * 屏幕上写「网络中断」是在冤枉网络，用户会去检查 WiFi。
         * 与 iOS 的 `imEndWatchdogReason` 是同一条判据。
         */
        fun watchdogReason(action: Action): String = when (action) {
            Action.CANCEL -> "cancel"
            Action.REJECT -> "reject"
            Action.HANGUP, Action.LEAVE_ROOM, Action.NONE -> "hangup"
        }
        /** 结束原因的人话（规范 §08），与 iOS 的 `imEndReasonText` / Web 的 `endReasonText` 逐字对齐。 */
        fun endReasonText(reason: String, role: String, durationSec: Long): String = when (reason) {
            "hangup" -> if (durationSec > 0) IMText.t("end.hangupDuration", "duration" to IMGrid.formatDuration(durationSec)) else IMText.t("end.hangup")
            "cancel" -> IMText.t(if (role == "caller") "end.cancelCaller" else "end.cancelCallee")
            "reject" -> IMText.t(if (role == "caller") "end.rejectCaller" else "end.rejectCallee")
            "busy" -> IMText.t("end.busy")
            "no_answer" -> IMText.t(if (role == "caller") "end.noAnswerCaller" else "end.noAnswerCallee")
            "offline" -> IMText.t("end.offline")
            "answered_elsewhere" -> IMText.t("end.answeredElsewhere")
            "rejected_elsewhere" -> IMText.t("end.rejectedElsewhere")
            "network" -> IMText.t("end.network")
            "room_closed" -> IMText.t("end.roomClosed")
            "kicked" -> IMText.t("end.kicked")
            else -> IMText.t("end.default")
        }

        /** 占位格上终局的人话。 */
        fun settledText(settled: Settled): String = when (settled) {
            Settled.NONE -> ""
            Settled.REJECTED -> IMText.t("tile.rejected")
            Settled.NO_ANSWER -> IMText.t("tile.noAnswer")
        }

        /** 网络质量的人话（协议 §3.5 的表）。 */
        fun networkText(level: Int): String = when {
            level <= 0 -> ""
            level <= 2 -> IMText.t("net.good")
            level <= 4 -> IMText.t("net.fair")
            level == 5 -> IMText.t("net.poor")
            else -> IMText.t("net.reconnecting")
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
