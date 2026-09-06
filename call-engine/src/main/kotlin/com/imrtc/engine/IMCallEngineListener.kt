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

    /** 信令通道断开。`code` 是 WebSocket 关闭码，0 表示网络异常没拿到码。 */
    fun onDisconnected(code: Int, reason: String) {}

    /**
     * 别再重连了，回登录页。
     *
     * 两种情况会抛：同账号同设备号在别处登录（4403）；**连续三次鉴权失败**
     * （票过期了而宿主没换新票）。
     */
    fun onKickedOut() {}

    /** 任意内部错误。`code` 取自五仓共用的错误码表；`message` 是英文短语，**别直接显示给用户**。 */
    fun onError(code: Int, message: String) {}

    // ── 来电与拨出 ────────────────────────────────────────────────────

    /**
     * 收到邀请（被叫）。
     *
     * `calleeIds` 是**这通电话邀了谁**（不含主叫，含自己）。群通话的界面靠它把还没接的人
     * 先摆成占位格——否则主叫那边是四格、被叫这边只有两格，同一通电话两种样子。
     */
    fun onCallReceived(
        callId: String,
        caller: String,
        calleeIds: List<String>,
        mediaType: String,
        isGroup: Boolean,
    ) {}

    /** 通话接通，主被叫都抛。`role` 是 "caller" 或 "callee"。 */
    fun onCallBegin(callId: String, roomId: String, mediaType: String, role: String) {}

    /**
     * **所有结束分支的唯一出口**。
     *
     * 宿主只监听这一个回调也必须能完整记录一通电话：`reason` 取值见协议 §6
     * （表外的值已经被 Engine 折成 `error`），`durationSec` 未接通恒为 0——
     * **别自己算时长**，时钟偏移会让两端算出不同的数。
     */
    fun onCallEnd(callId: String, reason: String, durationSec: Long, endedBy: String) {}

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

    /** 语音 ↔ 视频切换。 */
    fun onCallMediaTypeChanged(callId: String, from: String, to: String) {}

    /** 某人的第一帧画面到了，UI 用来撤 loading。 */
    fun onFirstVideoFrame(uid: String) {}

    // ── 房间（会议） ──────────────────────────────────────────────────

    fun onRoomJoined(roomId: String) {}

    fun onRoomLeft(roomId: String) {}

    /** 房间被解散（全员离开或被管理接口关闭）。 */
    fun onRoomClosed(roomId: String, reason: String) {}
}

/** 一个正在说话的人。`volume` 是 0~100 的整数。 */
data class IMSpeaker(val uid: String, val volume: Int)

/** 一个人的网络质量。`level` 0~6，0 = 未知，6 = 已断开。 */
data class IMNetworkQuality(val uid: String, val level: Int)
