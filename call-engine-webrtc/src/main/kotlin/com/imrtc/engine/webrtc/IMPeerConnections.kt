package com.imrtc.engine.webrtc

import android.content.Context
import com.imrtc.engine.log.IMRTCLog
import org.webrtc.AudioSource
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * 两条 PeerConnection 的持有者：**pub 推流、sub 收流，各自的 offerer 是固定的**（协议 §3.3）。
 *
 * 固定 offerer 就没有 glare，五端都不需要实现 perfect negotiation / rollback：
 * pub 侧永远是客户端 offer、服务端 answer；sub 侧永远是服务端 offer、客户端 answer。
 *
 * ## 两个必须记住的坑
 *
 * 1. **早到的 ICE 候选要缓冲**。远端描述还没设就 `addIceCandidate` 会被 native 层丢掉，
 *    症状是媒体**间歇性**不通——进房即订阅时协商发生得早，SDP 里可能一个候选都没有，
 *    三方会议必现。Web 端就是这么踩的。
 * 2. **`PeerConnectionFactory.initialize` 每进程只能调一次**，`EglBase` 的共享上下文
 *    也全进程只建一份，工厂与所有渲染器共用。
 */
internal class IMPeerConnections(
    private val appContext: Context,
    private val events: Callbacks,
) {

    interface Callbacks {
        fun onLocalSdp(pc: String, type: String, sdp: String)
        fun onLocalCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onSubConnected()
        /** 远端轨道到达。`streamId` 就是 msid——服务端把 uid 放在里面。 */
        fun onRemoteTrack(pc: String, streamId: String, track: org.webrtc.MediaStreamTrack)
        fun onError(message: String)
    }

    val eglBase: EglBase = sharedEgl(appContext)

    private val factory: PeerConnectionFactory = buildFactory(appContext, eglBase)

    private var pub: PeerConnection? = null
    private var sub: PeerConnection? = null

    /** 远端描述还没设时收到的候选，先攒着。key 是 "pub"/"sub"。 */
    private val bufferedCandidates = mutableMapOf<String, MutableList<IceCandidate>>()
    private val remoteReady = mutableSetOf<String>()

    /**
     * **协商必须串行**：一条 PeerConnection 上同时飞两个 offer，第二条 answer 回来时
     * 状态已经是 stable，native 层直接报 `Called in wrong state: stable`，
     * 那条轨道就再也协商不上了。
     *
     * 闸门的状态与并发规则见 [IMNegotiationGate]——**它必须是线程安全的**，
     * 原先那三个裸 `mutableSetOf` 被三个线程并发读写，竞态下会永久卡死。
     */
    private val gate = IMNegotiationGate()

    fun factory(): PeerConnectionFactory = factory

    fun start(iceServers: List<String>) {
        val config = PeerConnection.RTCConfiguration(
            iceServers.map { PeerConnection.IceServer.builder(it).createIceServer() },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pub = factory.createPeerConnection(config, Observer("pub"))
        sub = factory.createPeerConnection(config, Observer("sub"))
        IMRTCLog.i("media", "PeerConnection 就绪：pub=${pub != null} sub=${sub != null}")
    }

    /**
     * 释放两条 PeerConnection。**可重入**：置空之后再调是空操作。
     *
     * **先 `close()` 再 `dispose()`**：`close()` 同步拆掉 transports 与 ICE agent，
     * 之后不会再有候选冒出来。M150 的 `dispose()` 第一件事其实就是调 `close()`
     * （反编译 `PeerConnection.dispose()` 确认过），所以这两行与原来那一行**等价**——
     * 显式写出来是不想让释放顺序依赖某个版本 `dispose()` 的实现细节。
     *
     * **注意：离房后还在发候选那个 bug 不是这里造成的**，是门面根本没走到 `stop()`
     * （见 `IMCallEngine.driveMedia`）。别把这段注释当成那个 bug 的成因。
     */
    fun stop() {
        gate.clear()
        pub?.apply {
            close()
            dispose()
        }
        sub?.apply {
            close()
            dispose()
        }
        pub = null
        sub = null
        bufferedCandidates.clear()
        remoteReady.clear()
    }

    fun connection(pc: String): PeerConnection? = if (pc == "sub") sub else pub

    /**
     * 只置「下一个 offer 要重启 ICE」这一位，**不自己发 offer**。
     *
     * 会话恢复（§1.4）之后由 Engine 走正常的 `restart_pub_ice` 路径来发帧——
     * 媒体层不认识信令，也不知道此刻房间在不在 joined。
     */
    fun markIceRestart(pc: String) {
        gate.markIceRestart(pc)
        /*
         **同时把在飞状态清掉。** 这个方法只在会话恢复（§1.4）之后被调用，
         而换了一条连接就意味着：之前那个 offer 的 answer 永远不会回来了——
         它是从旧 socket 上发出去的。不清的话闸门一直关着，
         紧随其后的重新协商只会排队，那条 PC 就此永久沉默：
         信令恢复了、下行也恢复了，**上行再也没协商过一次**，
         对端全程看不到画面而日志里一条错误都没有（真机 2026-09-08）。
        */
        gate.resetInFlight(pc)
    }

    /**
     * @param iceRestart 这一轮要不要**重启 ICE**（换一对新的 ufrag/pwd 重新打洞）。
     *   网抖没了、换了 Wi-Fi、锁屏久了之后，`pub` 那条 PC 会走到 `FAILED` 且**自己不会回来**；
     *   重新协商一个普通 offer 也救不了它，必须带这一位。
     */
    fun createOffer(pc: String, iceRestart: Boolean = false) {
        val connection = connection(pc) ?: return
        // 已经有一个 offer 在飞：闸门记下待补，等这一轮收工再来。
        val restart = gate.beginOffer(pc, iceRestart) ?: run {
            IMRTCLog.d("media", "$pc 协商进行中，offer 排队（iceRestart=$iceRestart）")
            return
        }
        val constraints = MediaConstraints().apply {
            if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        if (restart) IMRTCLog.i("media", "$pc 重启 ICE")
        connection.createOffer(
            object : SimpleSdpObserver("createOffer/$pc") {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection.setLocalDescription(LocalSdpObserver(pc), description)
                    events.onLocalSdp(pc, "offer", description.description)
                }

                // 造不出 offer 就没有后续的 answer 来放闸——不在这里放，闸门永久关死。
                override fun onCreateFailure(error: String) {
                    super.onCreateFailure(error)
                    gate.abortOffer(pc)
                }
            },
            constraints,
        )
    }

    fun applyRemoteSdp(pc: String, type: String, sdp: String) {
        val connection = connection(pc) ?: return
        val kind = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        // 已经 stable 了还来一条 answer = 上一轮的重复应答，丢掉。硬塞进去只会换来
        // 「Called in wrong state」，而且那条错误会被上层当成媒体故障报给宿主。
        if (kind == SessionDescription.Type.ANSWER &&
            connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER
        ) {
            IMRTCLog.d("media", "$pc 当前状态 ${connection.signalingState()}，忽略这条 answer")
            // **丢掉应答也要放闸**：这一轮不会再有别的东西来收尾了。
            gate.abortOffer(pc)
            return
        }
        connection.setRemoteDescription(
            object : SimpleSdpObserver("setRemote/$pc") {
                override fun onSetSuccess() {
                    flushCandidates(pc, connection)
                    if (kind == SessionDescription.Type.OFFER) {
                        // sub 侧收到 offer 之后要立刻回一条 answer——**服务端才是 sub 的 offerer**。
                        createAnswer(pc, connection)
                    } else {
                        // 这一轮协商收工，把排队的 offer 补上。
                        if (gate.finishOffer(pc)) createOffer(pc)
                    }
                }

                // setRemote 失败同样是终局：不放闸的话这条 PC 从此不再协商。
                override fun onSetFailure(error: String) {
                    super.onSetFailure(error)
                    if (kind == SessionDescription.Type.ANSWER) gate.abortOffer(pc)
                }
            },
            SessionDescription(kind, sdp),
        )
    }

    fun applyRemoteCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val connection = connection(pc) ?: return
        // 空串表示候选收集结束（end-of-candidates），**接收方必须容忍**（协议 §3.4）。
        if (candidate.isEmpty()) return
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (pc in remoteReady) {
            connection.addIceCandidate(ice)
        } else {
            bufferedCandidates.getOrPut(pc) { mutableListOf() }.add(ice)
            IMRTCLog.d("media", "$pc 远端描述还没设，先缓冲一个候选（共 ${bufferedCandidates[pc]?.size}）")
        }
    }

    private fun createAnswer(pc: String, connection: PeerConnection) {
        connection.createAnswer(
            object : SimpleSdpObserver("createAnswer/$pc") {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection.setLocalDescription(SimpleSdpObserver("setLocal/$pc"), description)
                    events.onLocalSdp(pc, "answer", description.description)
                }
            },
            MediaConstraints(),
        )
    }

    private fun flushCandidates(pc: String, connection: PeerConnection) {
        remoteReady += pc
        val buffered = bufferedCandidates.remove(pc) ?: return
        IMRTCLog.d("media", "$pc 远端描述已设，补交 ${buffered.size} 个缓冲候选")
        buffered.forEach { connection.addIceCandidate(it) }
    }

    fun createAudioSource(): AudioSource = factory.createAudioSource(MediaConstraints())

    /** PeerConnection 的观察者。**回调跑在 WebRTC 的 signaling 线程上**，别在里面阻塞。 */
    private inner class Observer(private val pc: String) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            events.onLocalCandidate(pc, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            IMRTCLog.i("media", "$pc ICE 状态：$state")
            if (pc == "sub" && (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED)
            ) {
                events.onSubConnected()
            }
            /*
             **ICE 失败不是终点，是该重连的信号。**

             `pub` 那条 PC 的 offerer 是客户端，所以只能由我们自己重启；
             `sub` 那条的 offerer 恒为服务端（协议 §3.3），由服务端自己重启，这里不碰。
             不重启的后果：网抖一下（换 Wi-Fi、进电梯、锁屏久了）他就**永久掉出这通通话**，
             对端的格子从此是一块黑，而界面上一切正常、谁也不挂断。
             真机上抓到过 iOS 的两条 PC 从某一刻起五分钟一轮地失败，再没回到 CONNECTED。

             重启失败还会再进 FAILED，于是天然形成一个重试节奏；人真的走了由信令层收场。
             `onError` 照旧报——顶部那条橙色提示还是要出的。
            */
            if (state == PeerConnection.IceConnectionState.FAILED) {
                events.onError("$pc ICE failed")
                if (pc == "pub") createOffer(pc, iceRestart = true)
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) = Unit

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            // **uid 从 msid 里取**：streamIds 在 RtpReceiver 上拿不到，只有这条回调带得出来。
            val track = receiver.track() ?: return
            val streamId = streams.firstOrNull()?.id ?: track.id()
            events.onRemoteTrack(pc, streamId, track)
        }
    }

    /** 四个方法只关心其中一两个，其余给个默认，省得每次写一堆空实现。 */
    /**
     * 给 `setLocalDescription` 用：失败时**放闸**。
     *
     * 本端描述设不上，服务端那条 answer 回来也会在 `applyRemoteSdp` 的状态判断里被丢掉，
     * 于是没有任何人来收尾——闸门永久关死，那条 PC 再也协商不了。
     */
    private inner class LocalSdpObserver(private val pc: String) : SimpleSdpObserver("setLocal/$pc") {
        override fun onSetFailure(error: String) {
            super.onSetFailure(error)
            gate.abortOffer(pc)
        }
    }

    private open inner class SimpleSdpObserver(private val what: String) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) {
            IMRTCLog.e("media", "$what 失败：$error")
            events.onError("$what: $error")
        }

        override fun onSetFailure(error: String) {
            IMRTCLog.e("media", "$what 失败：$error")
            events.onError("$what: $error")
        }
    }

    companion object {
        @Volatile
        private var initialized = false

        @Volatile
        private var egl: EglBase? = null

        /** 全进程一份共享的 EglBase：工厂与所有渲染器共用，否则画面挂不上去。 */
        @Synchronized
        fun sharedEgl(context: Context): EglBase {
            initializeOnce(context)
            return egl ?: EglBase.create().also { egl = it }
        }

        /** `PeerConnectionFactory.initialize` **每进程只能调一次**，调第二次会崩。 */
        @Synchronized
        private fun initializeOnce(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            initialized = true
        }

        private fun buildFactory(context: Context, egl: EglBase): PeerConnectionFactory {
            val audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
                // 硬件回声消除/降噪：**没有它就会听到自己**，而那听起来像「对方设备有问题」。
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            return PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
        }
    }
}
