package com.imrtc.engine.webrtc

import android.content.Context
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.media.IMVideoProfile
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.RendererCommon
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * [IMMediaAdapter] 的 libwebrtc 实现（M150）。
 *
 * **只有这个模块依赖 `org.webrtc`**——Engine 那边只认接口，所以「跑一次单测」不必先拉几十 MB。
 *
 * ## 三条与协议对齐的做法
 *
 * 1. **`cid` 要出现在 pub offer 的 msid 里**：`addTransceiver` 的 `streamIds` 传 `cid`，
 *    服务端靠它把 SDP 的 m-line 认回 `track_id`（协议 §3.2）。
 * 2. **simulcast 三层 rid 固定为 `l`/`m`/`h`**，与服务端的层选择、以及 `max_layer` 枚举同名。
 * 3. **sub 侧 ICE 连通 = 媒体就绪**，通话状态机据此从 connecting 走到 connected。
 *
 * ## 生命周期
 *
 * `SurfaceViewRenderer` 的 `init` / `release` **必须成对**，且释放顺序有讲究：
 * 先把轨道从渲染器上摘掉再 release，反了会崩在 native 层。
 */
class IMWebRTCAdapter @JvmOverloads constructor(
    context: Context,
    /**
     * 采集画质档位。见 [IMVideoProfile]：**策略归宿主**，不是服务端下发的。
     *
     * 换档位要**换一个适配器实例**（等于重登），不能中途改：采集已经按旧尺寸起来了，
     * 悄悄改字段只会让日志里的数字和实际推的流对不上。
     */
    private val videoProfile: IMVideoProfile = IMVideoProfile.DEFAULT,
) : IMMediaAdapter {

    private val appContext = context.applicationContext
    private var events: IMMediaAdapter.Events? = null

    private val audio = IMAudioRouter(appContext)
    private val peers = IMPeerConnections(appContext, PeerCallbacks())

    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null

    /** uid → 渲染器（本端预览用 [LOCAL] 这把钥匙）。**卸载时一定要先摘轨道**。 */
    private val renderers = LinkedHashMap<String, SurfaceViewRenderer>()

    /**
     * 远端轨道：**track_id → VideoTrack**。
     *
     * 键是 track_id 而不是 uid：轨道到达时归属通常还不知道（信令帧可能后到），
     * 先按 track_id 收着，等 [claimRemoteTracks] 认领。
     */
    private val remoteVideo = LinkedHashMap<String, VideoTrack>()

    /** 归属表：track_id → uid，由信令层通过 [claimRemoteTracks] 灌进来。 */
    private val trackOwners = LinkedHashMap<String, String>()

    /** 已经挂上去的：track_id → 渲染器。摘 sink 要拿它，重复挂也靠它判。 */
    private val attached = LinkedHashMap<String, SurfaceViewRenderer>()

    private var running = false
    private var frontCamera = true

    override fun attachEvents(events: IMMediaAdapter.Events) {
        this.events = events
    }

    override fun start(iceServers: List<String>) {
        if (running) return
        running = true
        audio.start()
        peers.start(iceServers)
        IMCallForegroundService.start(appContext, withCamera = false)
    }

    /** **必须可重入**：挂断、被踢、宿主退出会先后到达。 */
    override fun stop() {
        if (!running) return
        running = false
        stopCapture()
        // 先摘轨道再 release：反了会崩在 native 层。
        attached.forEach { (trackId, renderer) ->
            remoteVideo[trackId]?.let { track -> runCatching { track.removeSink(renderer) } }
        }
        attached.clear()
        renderers[LOCAL]?.let { renderer -> runCatching { videoTrack?.removeSink(renderer) } }
        renderers.values.forEach { renderer -> runCatching { renderer.release() } }
        renderers.clear()
        remoteVideo.clear()
        trackOwners.clear()
        audioTrack = null
        videoTrack = null
        peers.stop()
        audio.stop()
        IMCallForegroundService.stop(appContext)
    }

    override fun publish(cid: String, kind: String, simulcast: Boolean) {
        val connection = peers.connection("pub") ?: run {
            IMRTCLog.w("media", "还没 start 就 publish，忽略")
            return
        }
        if (kind == "audio") {
            // **track id 必须就是 cid**：服务端按 msid 的第二段（= track id）认领 m-line（协议 §3.2）。
            // 这里原先是 "audio-$cid"，服务端永远认不回来——上行 RTP 到了却一直挂在
            // 「先攒着」的队列里，别人一格画面都没有、也听不见声音，而日志里只有一行 DEBUG。
            val track = peers.factory().createAudioTrack(cid, peers.createAudioSource())
            audioTrack = track
            connection.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    listOf(cid),
                ),
            )
            return
        }

        val track = startCapture(cid) ?: return
        videoTrack = track
        // 预览可能**早于**采集就挂好了（拨出时先给本端一个格子）。那时候还没有轨道，
        // 不在这里补挂的话本端预览一辈子是空的——真机上的「开了摄像头自己也看不见」。
        renderers[LOCAL]?.let { renderer ->
            renderer.setMirror(frontCamera)
            track.addSink(renderer)
        }
        // simulcast：三层同时发上去，SFU 按每个订阅者的网速替他挑一层。
        // rid 必须是 l/m/h——与服务端的层选择、max_layer 枚举同名。
        val encodings = if (simulcast) {
            videoProfile.simulcastLayers.map { encoding(it.rid, it.scaleDownBy, it.bitrateBps) }
        } else {
            listOf(encoding("h", scale = 1.0, maxBitrateBps = videoProfile.maxBitrateBps))
        }
        connection.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                listOf(cid),
                encodings,
            ),
        )
        IMCallForegroundService.start(appContext, withCamera = true)
    }

    override fun unpublish(cid: String) {
        // v1 一通电话就一组本端 Track，停采集即可；细到单条 Track 的拆除留给会议期。
        stopCapture()
    }

    override fun setMuted(kind: String, muted: Boolean) {
        if (kind == "video") videoTrack?.setEnabled(!muted) else audioTrack?.setEnabled(!muted)
    }

    override fun createOffer(pc: String) = peers.createOffer(pc)

    override fun applyRemoteSdp(pc: String, type: String, sdp: String) =
        peers.applyRemoteSdp(pc, type, sdp)

    override fun applyRemoteCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) =
        peers.applyRemoteCandidate(pc, candidate, sdpMid, sdpMLineIndex)

    override fun createVideoView(context: android.content.Context): android.view.View =
        SurfaceViewRenderer(context)

    override fun attachView(uid: String, view: Any?) {
        detachRenderer(uid)
        val renderer = view as? SurfaceViewRenderer ?: return
        renderer.init(peers.eglBase.eglBaseContext, firstFrameEvents(uid))
        renderer.setEnableHardwareScaler(true)
        renderers[uid] = renderer
        bindRemoteTracks()
    }

    override fun claimRemoteTracks(owners: Map<String, String>) {
        trackOwners.putAll(owners)
        bindRemoteTracks()
    }

    override fun startLocalPreview(view: Any?) {
        renderers[LOCAL]?.let { old ->
            runCatching { videoTrack?.removeSink(old) }
            runCatching { old.release() }
        }
        val renderer = view as? SurfaceViewRenderer ?: run {
            renderers.remove(LOCAL)
            return
        }
        renderer.init(peers.eglBase.eglBaseContext, null)
        renderer.setMirror(frontCamera)
        renderers[LOCAL] = renderer
        // 采集可能还没起来（拨出时先摆格子）；publish 里会补挂。
        videoTrack?.addSink(renderer)
    }

    /**
     * bindRemoteTracks 把「已认领归属 + 有渲染器」的远端轨道接上去。
     *
     * 轨道、归属、渲染器三者**到达顺序完全不定**，所以三条路径（onRemoteTrack /
     * claimRemoteTracks / attachView）都调它，由这一个地方判重与换绑。
     */
    private fun bindRemoteTracks() {
        for ((trackId, track) in remoteVideo) {
            val renderer = trackOwners[trackId]?.let { renderers[it] }
            val current = attached[trackId]
            if (current === renderer) continue
            if (current != null) runCatching { track.removeSink(current) }
            if (renderer == null) {
                attached.remove(trackId)
                continue
            }
            runCatching { track.addSink(renderer) }
            attached[trackId] = renderer
        }
    }

    /** 卸掉某个 uid 的渲染器：先把挂在它上面的轨道摘干净，再 release（反了会崩在 native 层）。 */
    private fun detachRenderer(uid: String) {
        val previous = renderers.remove(uid) ?: return
        val gone = attached.filterValues { it === previous }.keys
        for (trackId in gone) {
            remoteVideo[trackId]?.let { runCatching { it.removeSink(previous) } }
            attached.remove(trackId)
        }
        runCatching { previous.release() }
    }

    override fun switchCamera() {
        capturer?.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    frontCamera = isFront
                    renderers[LOCAL]?.setMirror(isFront)
                }

                override fun onCameraSwitchError(error: String) {
                    IMRTCLog.w("media", "翻转摄像头失败：$error")
                }
            },
        )
    }

    override fun setSpeakerOn(on: Boolean) = audio.setSpeakerOn(on)

    // ── 采集 ──────────────────────────────────────────────────────────

    private fun startCapture(cid: String): VideoTrack? {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(appContext)) {
            Camera2Enumerator(appContext)
        } else {
            Camera1Enumerator(true)
        }
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: run {
                IMRTCLog.e("media", "没有可用摄像头")
                events?.onMediaError(2002, "device not found")
                return null
            }
        val videoCapturer = enumerator.createCapturer(name, null) ?: return null
        val helper = SurfaceTextureHelper.create("capture", peers.eglBase.eglBaseContext)
        val source = peers.factory().createVideoSource(false)
        videoCapturer.initialize(helper, appContext, source.capturerObserver)
        videoCapturer.startCapture(videoProfile.width, videoProfile.height, videoProfile.frameRate)

        capturer = videoCapturer as? CameraVideoCapturer
        captureHelper = helper
        videoSource = source
        // track id 就是 cid（同 publish 里那条注释）。
        return peers.factory().createVideoTrack(cid, source)
    }

    private fun stopCapture() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        captureHelper?.dispose()
        videoSource?.dispose()
        capturer = null
        captureHelper = null
        videoSource = null
    }

    private fun encoding(rid: String, scale: Double, maxBitrateBps: Int) =
        RtpParameters.Encoding(rid, true, scale).apply { this.maxBitrateBps = maxBitrateBps }

    /** 第一帧到了就撤 loading——UI 全靠这个信号，不然会露一段黑屏。 */
    private fun firstFrameEvents(uid: String) = object : RendererCommon.RendererEvents {
        override fun onFirstFrameRendered() {
            events?.onFirstVideoFrame(uid)
        }

        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
    }

    private inner class PeerCallbacks : IMPeerConnections.Callbacks {
        override fun onLocalSdp(pc: String, type: String, sdp: String) {
            events?.onLocalSdp(pc, type, sdp)
        }

        override fun onLocalCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
            events?.onLocalCandidate(pc, candidate, sdpMid, sdpMLineIndex)
        }

        override fun onSubConnected() {
            events?.onMediaReady()
        }

        override fun onRemoteTrack(pc: String, streamId: String, track: org.webrtc.MediaStreamTrack) {
            val video = track as? VideoTrack ?: return
            /*
             **键是 track_id，不是 stream id。** 订阅侧 SDP 的 msid 第二段就是 track_id
             （协议 §3.2），而 stream id 服务端给的是同一个常量（`im-rtc`）——
             原先按 stream id 收，等于所有人的画面共用一把钥匙，真机上一格都不出。

             归属这时候通常还不知道（`room.track_published` 可能后到），先收着，
             等 claimRemoteTracks 认领。
            */
            val trackId = track.id()
            remoteVideo[trackId] = video
            IMRTCLog.i("media", "远端视频轨道到达：track_id=$trackId stream=$streamId")
            bindRemoteTracks()
        }

        override fun onError(message: String) {
            events?.onMediaError(2006, message)
        }
    }

    private companion object {
        const val LOCAL = "__local__"
    }
}
