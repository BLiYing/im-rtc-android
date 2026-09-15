package com.imrtc.engine.media

import android.content.Context
import android.view.View

/**
 * 媒体层的**接缝**——`call-engine` 里只有这个接口，没有任何实现。
 *
 * 为什么这么切（与 iOS 同一条理由）：libwebrtc 是几十 MB 的预编译包，一旦被 Engine 直接
 * 依赖，「跑一次单测」就变成「连真机 + 拉几十 MB」。真实现在 `call-engine-webrtc` 模块里，
 * 宿主按需引入；**不引媒体也是正常用法**，不是降级——登录、振铃、成员进出、静音通知
 * 一个都不少，只有推流与画面挂载会以 `2005 invalid_state` 失败。
 *
 * 线程约定：所有方法都可能被 Engine 的**单线程调度器**调用；实现里不要阻塞它，
 * 更不要在里面回调回 Engine（会重入）。回调走 [Events]，实现方负责切回自己的线程。
 */
interface IMMediaAdapter {

    /** 媒体层反过来通知 Engine 的那几件事。 */
    interface Events {
        /** 本端要发一条 SDP（pub 侧的 offer / sub 侧的 answer）。 */
        fun onLocalSdp(pc: String, type: String, sdp: String)

        /** 本端收集到一个 ICE 候选。`candidate` 为空串表示收集结束。 */
        fun onLocalCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int)

        /** sub 侧连通 = 媒体就绪，通话状态机据此从 connecting 走到 connected。 */
        fun onMediaReady()

        /**
         * 某个远端第一帧画面到达，UI 用来撤掉 loading。对端关摄像头再开后（[awaitFirstVideoFrame]）会再报一次。
         * `trackId` 是那条视频轨道的 track_id；实现拿不到就给空串，**不许传假值**。
         */
        fun onFirstVideoFrame(uid: String, trackId: String)

        /** 媒体层出错（协商失败、ICE failed、采集权限被拒）。 */
        fun onMediaError(code: Int, message: String)
    }

    /** 装上回调出口。Engine 在创建时调一次。 */
    fun attachEvents(events: Events)

    /** 起 PeerConnection：pub 与 sub 各一条。`iceServers` 为空表示只用 SFU 的公网地址。 */
    fun start(iceServers: List<String>)

    /** 关掉全部媒体资源。**必须可重入**：挂断、被踢、宿主退出都会调它。 */
    fun stop()

    /** 采集并发布本端 Track。`cid` 是客户端生成的本地标识，要出现在 pub offer 的 msid 里。 */
    fun publish(cid: String, kind: String, simulcast: Boolean)

    /** 停止发布。 */
    fun unpublish(cid: String)

    /**
     * 开关本端麦克风/摄像头。**这不是 unpublish**，Track 与协商都保留。
     *
     * 关摄像头**连采集一起停**（指示灯灭），打开时原地接着采。还没发布时是空操作——
     * 进房前关摄像头走 [stopLocalPreview]。
     */
    fun setMuted(kind: String, muted: Boolean)

    /**
     * 造一条本端 offer。
     *
     * 只有 pub 侧会用到：**每条 PeerConnection 的 offerer 是固定的**（§3.3）——
     * pub 由客户端 offer、sub 由服务端 offer。固定 offerer 就没有 glare，
     * 五端都不需要实现 perfect negotiation。
     * 结果异步从 [Events.onLocalSdp] 回来。
     */
    fun createOffer(pc: String)

    /**
     * 让**下一个**上行 offer 带上 ICE restart（换一对新的 ufrag/pwd 重新打洞）。
     *
     * 只置一位、不自己发帧：发帧是 Engine 的事，媒体层不认识信令（§7.5）。
     * iOS 的 `restartPubICE()`、Web 的 `restartPubICE()` 是同一个方法，三端同名。
     */
    fun restartPubICE()

    /** 收到对端 SDP。 */
    fun applyRemoteSdp(pc: String, type: String, sdp: String)

    /** 收到对端 ICE 候选。**远端描述还没设时要缓冲**，别丢——丢了媒体会间歇性不通。 */
    fun applyRemoteCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int)

    /**
     * 造一个能显示视频的 View（`SurfaceViewRenderer`）。
     *
     * 这个口子存在的唯一理由是**分层**：`call-uikit` 不许 import `org.webrtc`（CONVENTIONS §1），
     * 但总得有人把渲染器 new 出来。由媒体层造、UIKit 只管挂到视图树上，
     * 换媒体实现时 UIKit 一行不用改。
     */
    fun createVideoView(context: Context): View?

    /** 把某个 uid 的画面挂到一个视图上；`view` 为 null 表示卸载。 */
    fun attachView(uid: String, view: Any?)

    /**
     * 告诉媒体层「哪条 track_id 是谁的」（`[track_id: uid]`）。
     *
     * **媒体层自己无从知道这件事**：`onAddTrack` 只带得出 track_id，归属写在信令帧
     * `room.track_published` 里，两者谁先到都可能。Engine 每推进一步就同步一次。
     *
     * 这条口子原先整个不存在，于是 Android 端认远端画面靠的是 msid 的 **stream id**——
     * 而服务端给所有下行轨道用的是同一个常量 stream（`im-rtc`），
     * 结果每个人的画面都被挂到同一把钥匙上，真机表现是「协商全通、一格画面都没有」。
     */
    fun claimRemoteTracks(owners: Map<String, String>)

    /**
     * 某个远端**刚变成有画面**（Engine 抛完 `onUserVideoAvailable(uid, true)` 之后调）：
     * 等下一帧真的画到屏上，再经 [Events.onFirstVideoFrame] 报一次。
     *
     * 渲染器按 uid 整通复用，`init` 之后的首帧只有一次——对端关摄像头再开时没有别的信号，
     * 界面只能按信令揭示，而信令比新画面早几百毫秒，那段时间露出来的是关之前的最后一帧。
     * 默认空实现：不渲染的适配器无事可做。
     */
    fun awaitFirstVideoFrame(uid: String) {}

    /** 本端预览。 */
    fun startLocalPreview(view: Any?)

    /**
     * 停掉进房前起的本端预览，**连采集一起停**（摄像头指示灯灭，设计 v3.7）。
     *
     * 摄像头已经发布的不停——通话中关摄像头走 [setMuted]。默认空实现：没有采集的适配器无事可做。
     */
    fun stopLocalPreview() {}

    /** 前后摄像头切换。 */
    fun switchCamera()

    /** 扬声器 / 听筒。 */
    fun setSpeakerOn(on: Boolean)
}
