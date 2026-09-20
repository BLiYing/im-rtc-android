package com.imrtc.engine

/**
 * **回调总表**——设计文档 §7.5，四端同名。
 *
 * 「只引 Engine 自画 UI」这条路的全部内容就是这张表：宿主实现它，拿到事件后自行决定界面。
 * **`call-uikit` 也只是这张表的一个消费者**，没有任何私有通道——宿主能拿到的信息与它完全一致。
 * 一旦某个界面需要 Engine 开私有口子，说明表少了一项，**补表，不开后门**。
 *
 * 全部方法都有默认空实现（编译成真正的 Java 默认方法，靠 `-Xjvm-default=all`），
 * 所以 Java 宿主也只需要挑自己关心的那几个覆盖，不必写 24 个空方法。
 *
 * **全部回调都在主线程抛**：宿主拿到就画界面，不必自己 hop。
 */
interface IMCallEngineListener {

    // ── 连接 ──────────────────────────────────────────────────────────

    /** 信令通道建立。`resumed=true` 表示是断线恢复，房间与通话都还在。 */
    fun onConnected(sessionId: String, resumed: Boolean) {}

    /**
     * 信令通道断开。`code` 是 WebSocket 关闭码，0 表示网络异常没拿到码。
     *
     * `willReconnect` 是连接层**当场**给出的裁决：还会不会自动重连。4401 用尽、被踢（4403）、
     * 宿主主动 `logout()` 都是 false；其余（含普通网络抖动）是 true。**别再用 `code == 4403`
     * 猜**——4401 用尽时 code 还是 4401，猜不出「这次不会再重连」。
     */
    fun onDisconnected(code: Int, willReconnect: Boolean) {}

    /**
     * 别再重连了，回登录页。
     *
     * 两种情况会抛：同账号同设备号在别处登录（4403）；**连续三次鉴权失败**
     * （票过期了而宿主没换新票）。
     */
    fun onKickedOut(reason: IMKickedOutReason) {}

    /**
     * 当前这张票快到期了（默认到期前 60s），宿主该去取新票并 [IMCallEngine.updateToken]。
     *
     * **不处理也不会立刻出事**——服务端不复查活连接，票过期不断线。但下一次重连
     * （切基站、NAT 超时、切后台回来）会撞上 4401，用户被踢回登录页。
     * 这个回调就是把那次「必然发生但时间不定」的掉线消灭在发生之前。
     *
     * 服务端说「未知」（`token_expires_at_ms` 为 0）时**不会触发**，
     * 此时退化成被动行为，是刻意降级不是故障。
     */
    fun onTokenWillExpire(expiresAtMs: Long) {}

    /**
     * **找不到调用方的错误**（2.0.0 起）：断线后放弃重连、服务端主动推的 `sys.error`、媒体层自发故障、
     * 引擎随后自动发的连锁帧失败（例如接听之后的 `room.join`），以及**调发起类方法时没传
     * [IMResultCallback]** 的那次失败。传了回调的失败只从回调回来，这里不会再报一次。
     *
     * `code` 取自五仓共用的错误码表；`name` 是错误码的机读名（snake_case，如 `bad_params`）；
     * `message` 给开发者看，**别直接显示给用户**；`forType` 是出错的请求帧类型（如 `room.join`），
     * 没有对应请求时为空串。
     */
    fun onError(code: Int, name: String, message: String, forType: String) {}

    // ── 来电与拨出 ────────────────────────────────────────────────────

    /**
     * 收到邀请（被叫）。
     *
     * `calleeIds` 是**这通电话邀了谁**（不含主叫，含自己）。群通话的界面靠它把还没接的人
     * 先摆成占位格——否则主叫那边是四格、被叫这边只有两格，同一通电话两种样子。
     *
     * `joinedIds` 是**此刻已经在通话里的人**（不含自己；发起人没离场就在里面）。展开页据此把他们摆成
     * 正常格子，`calleeIds` 里不在 `joinedIds` 的才是「呼叫中…」。旧服务端不带 = 空列表，回落成只有 `caller`。
     *
     * `caller` 恒为**这通电话的发起人**；`inviter` 是**把你加进来的那个人**。首次邀请两者相同，
     * 群通话中途 `inviteMore` 加人时 `inviter` 是发那条加人请求的人（来电界面该显示他）。
     * 旧服务端不带 `inviter` 时 Engine 已回落成 `caller`，宿主不用自己兜底。
     *
     * `chatGroupId` 是宿主自己的群号（可能为空串——不是每通电话都属于某个群），
     * `chatGroupId` 靠它决定「添加成员」该向宿主要哪个群的候选人（见 `call-uikit` 的
     * `IMInviteMemberProvider`）。`userData` 是主叫在 [IMCallEngine.call] 选项里塞的
     * opaque 数据，原样透传，Engine 不解析（`HOST_INTEGRATION_DESIGN.md` §3.2/§3.3）。
     */
    fun onCallReceived(
        callId: String,
        caller: String,
        inviter: String,
        calleeIds: List<String>,
        joinedIds: List<String>,
        mediaType: String,
        isGroup: Boolean,
        chatGroupId: String,
        userData: String,
    ) {}

    /**
     * 通话接通，主被叫都抛。`role` 是 "caller" 或 "callee"。
     *
     * `caller`、`chatGroupId`、`userData` 取自 `call.connected`；`call.join` 进来的人
     * 没收过 `onCallReceived`，只能从这里第一次拿到群号。老服务端没有这些字段时，
     * 回落到本通 `onCallReceived` / [IMCallEngine.call] 选项里记下的值
     * （`HOST_INTEGRATION_DESIGN.md` §3.3）。
     */
    fun onCallBegin(
        callId: String,
        roomId: String,
        mediaType: String,
        isGroup: Boolean,
        role: String,
        caller: String,
        chatGroupId: String,
        userData: String,
    ) {}

    /**
     * **所有结束分支的唯一出口**。
     *
     * 宿主只监听这一个回调也必须能完整记录一通电话：`reason` 取值见协议 §6
     * （表外的值已经被 Engine 折成 [IMCallEndReason.ERROR]），`durationSec` 未接通恒为 0——
     * **别自己算时长**，时钟偏移会让两端算出不同的数。
     */
    fun onCallEnd(callId: String, reason: IMCallEndReason, durationSec: Long, endedBy: String) {}

    /**
     * 这通电话的事实一次给齐（通话记录设计 §4）。**紧跟 [onCallEnd]、每通拿到 call_id 的电话恰好一次**；
     * 未接通、被拒、`*_elsewhere` 也来（看 [IMCallSummary.reason]）。宿主要发通话记录消息的话，
     * 只在 `summary.role == "caller"` 时发，不用自己比对 uid。本地就地拒掉 / 发不出去的 `call()` 不触发。
     */
    fun onCallSummary(summary: IMCallSummary) {}

    /** 未接通的四种裁决之一，**只在 1v1 抛**，随后必有 [onCallEnd]。 */
    fun onCallCancelled(uid: String) {}

    /** 见 [onCallCancelled]。 */
    fun onCallRejected(uid: String) {}

    /** 见 [onCallCancelled]。 */
    fun onCallBusy(uid: String) {}

    /** 见 [onCallCancelled]。 */
    fun onCallNoAnswer(uid: String) {}

    /**
     * **通话中**有人打进来，服务端已经替你回了忙线——你不会为这一通振铃。
     *
     * MVP 是单通道：同一时刻只有一通电话（协议 §4.3 的忙线分支）。这条回调只是让界面
     * 能提示一句「谁来过电话」，不需要宿主做任何处理。
     */
    fun onCallMissed(callId: String, caller: String, reason: String) {}

    /** 本账号另一台设备接听或拒绝了这通电话。`action` 是 "accept" 或 "reject"。 */
    fun onHandledOnOtherDevice(callId: String, action: String) {}

    // ── 成员 ──────────────────────────────────────────────────────────

    fun onUserEnter(uid: String) {}

    fun onUserLeave(uid: String) {}

    /**
     * 某人的设备开始响铃（协议 `call.ringing`）。**通话里的人都收到**，不含正在响铃的人自己——
     * 群通话里别人加了人，你也能给他摆「呼叫中」占位格，随后由 [onUserAccept] / [onUserReject] /
     * [onUserNoResponse] 收掉。1v1 主叫也会收到（可据此把「正在呼叫…」改成「等待对方接听」）。
     */
    fun onUserRinging(uid: String) {}

    /** 群通话里某人接听了，其余人都收到。 */
    fun onUserAccept(uid: String) {}

    /** 群通话里某人拒接了。**群里不会跟着抛 [onCallRejected]**——通话还在继续。 */
    fun onUserReject(uid: String) {}

    /** 群通话里某人一直没应答。 */
    fun onUserNoResponse(uid: String) {}

    /** 某人开关了麦克风。 */
    fun onUserAudioAvailable(uid: String, available: Boolean) {}

    /** 某人开关了摄像头。 */
    fun onUserVideoAvailable(uid: String, available: Boolean) {}

    // ── 媒体与质量 ────────────────────────────────────────────────────

    /** 主讲人变化，服务端判定并节流 300ms。**客户端不得依赖更高频率。** */
    fun onActiveSpeakers(speakers: List<IMSpeaker>) {}

    /** 各方网络质量，服务端节流 2s。 */
    fun onNetworkQuality(entries: List<IMNetworkQuality>) {}

    /**
     * 某人的第一帧画面到了，UI 用来撤 loading。`trackId` 是那条视频轨道的 track_id；
     * 媒体层拿不到时给空串（**不是假值**——拿不到的场景见媒体层实现的类注释）。
     */
    fun onFirstVideoFrame(uid: String, trackId: String) {}

    // ── 房间（会议） ──────────────────────────────────────────────────

    /**
     * 进房成功（会议与通话都抛）。`memberIds` 是进房这一刻房里已有的人（快照，不含自己），之后进出的人走
     * [onUserEnter] / [onUserLeave]。**响铃阶段就摆好的成员名单要拿它对账**：那段时间不在房里，别人离场收不到通知。
     */
    fun onRoomJoined(roomId: String, memberIds: List<String>) {}

    fun onRoomLeft(roomId: String) {}

    /** 房间被解散（全员离开或被管理接口关闭）。 */
    fun onRoomClosed(roomId: String, reason: String) {}
}

/** 一个正在说话的人。`volume` 是 0~100 的整数。 */
data class IMSpeaker(val uid: String, val volume: Int)

/** 一个人的网络质量。`level` 0~6，0 = 未知，6 = 已断开。 */
data class IMNetworkQuality(val uid: String, val level: Int)
