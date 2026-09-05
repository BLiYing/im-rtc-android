package com.imrtc.engine.webrtc

import android.content.Context
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMMediaAdapter
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
class IMWebRTCAdapter(context: Context) : IMMediaAdapter {

    private val appContext = context.applicationContext
    private var events: IMMediaAdapter.Events? = null

    private val audio = IMAudioRouter(appContext)
    private val peers = IMPeerConnections(appContext, PeerCallbacks())

    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null

    /** uid → 渲染器。**弱不弱引用不重要，重要的是卸载时一定要摘轨道**。 */
    private val renderers = LinkedHashMap<String, SurfaceViewRenderer>()

    /** 远端轨道：uid → VideoTrack。画面挂载与「第一帧到了」都靠它。 */
    private val remoteVideo = LinkedHashMap<String, VideoTrack>()

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
        renderers.values.forEach { renderer ->
            remoteVideo.values.forEach { track -> runCatching { track.removeSink(renderer) } }
            runCatching { renderer.release() }
        }
        renderers.clear()
        remoteVideo.clear()
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
            val track = peers.factory().createAudioTrack("audio-$cid", peers.createAudioSource())
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
        // simulcast：三层同时发上去，SFU 按每个订阅者的网速替他挑一层。
        // rid 必须是 l/m/h——与服务端的层选择、max_layer 枚举同名。
        val encodings = if (simulcast) {
            listOf(
                encoding("l", scale = 4.0, maxBitrateBps = 150_000),
                encoding("m", scale = 2.0, maxBitrateBps = 500_000),
                encoding("h", scale = 1.0, maxBitrateBps = 1_500_000),
            )
        } else {
            listOf(encoding("h", scale = 1.0, maxBitrateBps = 1_500_000))
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
        val previous = renderers.remove(uid)
        if (previous != null) {
            remoteVideo[uid]?.runCatching { removeSink(previous) }
            runCatching { previous.release() }
        }
        val renderer = view as? SurfaceViewRenderer ?: return
        renderer.init(peers.eglBase.eglBaseContext, firstFrameEvents(uid))
        renderer.setEnableHardwareScaler(true)
        renderers[uid] = renderer
        remoteVideo[uid]?.addSink(renderer)
    }

    override fun startLocalPreview(view: Any?) {
        val renderer = view as? SurfaceViewRenderer ?: return
        renderer.init(peers.eglBase.eglBaseContext, null)
        renderer.setMirror(frontCamera)
        renderers[LOCAL] = renderer
        videoTrack?.addSink(renderer)
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
        videoCapturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

        capturer = videoCapturer as? CameraVideoCapturer
        captureHelper = helper
        videoSource = source
        return peers.factory().createVideoTrack("video-$cid", source)
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
            // 服务端把 uid 放在 msid 里。挂载可能比轨道先到，所以两边都要试着接上。
            remoteVideo[streamId] = video
            renderers[streamId]?.let { video.addSink(it) }
            IMRTCLog.i("media", "远端视频轨道到达：uid=$streamId")
        }

        override fun onError(message: String) {
            events?.onMediaError(2006, message)
        }
    }

    private companion object {
        const val LOCAL = "__local__"
        const val CAPTURE_WIDTH = 640
        const val CAPTURE_HEIGHT = 360
        const val CAPTURE_FPS = 24
    }
}
