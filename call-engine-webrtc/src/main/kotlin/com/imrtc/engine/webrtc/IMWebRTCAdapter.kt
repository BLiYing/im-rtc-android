package com.imrtc.engine.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.media.IMVideoProfile
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
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
 *
 * ## 渲染这一摊**全部在主线程上**（[onMain]）
 *
 * `SurfaceViewRenderer.init` / `setEnableHardwareScaler` / `setScalingType` 头一行就是
 * `ThreadUtils.checkIsOnMainThread()`，不在主线程直接抛 `IllegalStateException`。
 * 而 Engine 的方法一律跑在它自己那条单线程上，`IMExecutorScheduler` 又会把异常吞掉记一条日志——
 * 于是**渲染器一次都没初始化成功，画面永远是空的，而日志里只有一行「任务抛了异常」**。
 * 真机上就是「Android 端自己和别人的视频都不显示」。
 *
 * 顺带把轨道 / 归属 / 渲染器三张表也收到主线程上：它们本来就被三条线程碰
 * （Engine 线程的 `claimRemoteTracks`、WebRTC 信令线程的 `onRemoteTrack`、UI 线程的挂载），
 * 收到一条线程上比加锁简单，也不会有半个绑定关系的中间态。
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
    /**
     * 视频走**硬件 H.264** 还是原来的 VP8 软编。默认开。
     *
     * **策略归宿主**（与 [videoProfile] 同一条边界）：硬编在 Android 上的成败取决于
     * 这台机器的 MediaCodec 实现，厂商之间差别很大。所以它必须是个**能不换包就回退**
     * 的开关，而不是写死的常量——Demo 把它放在设置页里，换了重登生效。
     *
     * 这一位同时决定两件事，它们**必须同进同退**（见 [IMUplinkPolicy]）：
     * 编码器工厂套不套 simulcast adapter、以及 offer 里把哪个 codec 排在前面。
     */
    private val preferHardwareH264: Boolean = true,
) : IMMediaAdapter {

    private val appContext = context.applicationContext
    private var events: IMMediaAdapter.Events? = null

    /** 渲染相关的一切都在主线程上跑（见类注释）。已经在主线程时就地执行，不排队。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }

    private val audio = IMAudioRouter(appContext)
    private val peers = IMPeerConnections(appContext, PeerCallbacks(), preferHardwareH264)

    /** 上行怎么编（层、预算、codec、降级偏好）。见 [IMUplinkPolicy] 的类注释。 */
    private val uplink = IMUplinkPolicy(videoProfile, preferHardwareH264) { peers.factory() }

    /** 上行每一层实际编出多少分辨率、被什么限住。见 [IMUplinkStats] 的类注释。 */
    private val uplinkStats = IMUplinkStats(main)

    /** 帧尺寸 → 裁切还是留边。见 [IMVideoFitter]。 */
    private val fitter = IMVideoFitter(main) { renderers[it] }

    /** 对端摄像头重开后，等新画面真的上屏再报首帧。见 [IMFirstFrameGate]。 */
    private val firstFrames = IMFirstFrameGate(main) { events?.onFirstVideoFrame(it) }

    private var audioTrack: AudioTrack? = null

    /** 推上去的那条视频轨道，id = cid。 */
    @Volatile
    private var videoTrack: VideoTrack? = null

    /**
     * **只给本端预览用的那条轨道**，与 [videoTrack] 共用同一个 [videoSource]。
     *
     * 为什么要两条：拨出中还没有房间可发布，而用户此刻就该看见自己（草图 §03-E）。
     * 而推流那条的 id **必须是 cid**（协议 §3.2），cid 要等进房发布时才生成——
     * 所以预览不能等它。一个 source 上挂两条 track 是 libwebrtc 允许的，摄像头只开一次。
     */
    @Volatile
    private var previewTrack: VideoTrack? = null

    /** 采集这一摊被主线程（预览）与 Engine 线程（发布）同时碰，统一在这把锁下。 */
    private val captureLock = Any()

    @Volatile
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null

    /** 通话中关了摄像头：capturer 停着、轨道留着（见 [setMuted]）。 */
    @Volatile
    private var capturePaused = false

    /** 本端预览「开 / 关」只认最后一次（见 [IMPreviewIntent]）。 */
    private val previewIntent = IMPreviewIntent()

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
        // **不能无条件传 false**：本端预览可能早就把摄像头开起来了（拨出中就看得见自己），
        // 这里再把前台服务降级成「只有麦克风」，Android 14 起就是「正在用摄像头却没有 camera 类型」，
        // 后台一挂就抛 SecurityException。
        IMCallForegroundService.start(appContext, withCamera = videoSource != null)
    }

    /** **必须可重入**：挂断、被踢、宿主退出会先后到达。 */
    override fun stop() {
        if (!running) {
            /*
             **采集与前台服务不认 running。** `running` 只在进房时由 [start] 置上，
             而来电页 / 拨出中的本端预览早就经 [ensureCapture] 把摄像头和前台服务起来了。
             进房前就结束的通话（拒接、对方取消、振铃超时）走到这里 running 还是 false——
             原先直接 return，摄像头灯常亮、通知栏常驻，直到下一通电话进房再 stop。
             两件都幂等，挂断与被踢先后到达时重入无害。
            */
            stopCapture()
            IMCallForegroundService.stop(appContext)
            return
        }
        running = false
        uplinkStats.stop()
        stopCapture()
        // 先摘轨道再 release：反了会崩在 native 层。
        // 渲染器的释放也归主线程（`release` 与 `init` 要在同一条线程上成对）。
        onMain {
            attached.forEach { (trackId, renderer) ->
                remoteVideo[trackId]?.let { track -> runCatching { track.removeSink(renderer) } }
            }
            attached.clear()
            renderers[LOCAL]?.let { renderer -> runCatching { videoTrack?.removeSink(renderer) } }
            renderers.values.forEach { renderer -> runCatching { renderer.release() } }
            renderers.clear()
            firstFrames.clear()
            remoteVideo.clear()
            trackOwners.clear()
        }
        audioTrack = null
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

        // 采集可能早就起来了（拨出中的本端预览）——那时摄像头只开一次，这里只是多挂一条轨道。
        val source = ensureCapture() ?: return
        // track id 就是 cid（同上面那条注释）。
        val track = peers.factory().createVideoTrack(cid, source)
        videoTrack = track
        // simulcast：三层同时发上去，SFU 按每个订阅者的网速替他挑一层。
        // rid 必须是 l/m/h——与服务端的层选择、max_layer 枚举同名。
        val encodings = uplink.encodings(simulcast)
        val transceiver = connection.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                listOf(cid),
                encodings,
            ),
        )
        uplink.applyToVideo(connection, transceiver, cid, simulcast)
        // 采样从这里起：此刻编码器才真的有活干。
        uplinkStats.start(connection)
        // 前台服务的 camera 类型由 ensureCapture 负责升级——采集起来的那一刻才算真的在用摄像头。
    }

    override fun unpublish(cid: String) {
        // v1 一通电话就一组本端 Track，停采集即可；细到单条 Track 的拆除留给会议期。
        stopCapture()
    }

    /**
     * 通话中关摄像头 = **停采集**（指示灯灭），不只是 `setEnabled(false)`——那样灯一直亮着。
     * 轨道、transceiver、source 都留着：打开时同一个 capturer 原地接着采，不重新协商。
     * 还没发布（进房前）时什么都不做，那时关摄像头走 [stopLocalPreview]。
     */
    override fun setMuted(kind: String, muted: Boolean) {
        if (kind != "video") { audioTrack?.setEnabled(!muted); return }
        val track = videoTrack ?: return
        track.setEnabled(!muted)
        setCapturePaused(muted)
    }

    private fun setCapturePaused(paused: Boolean): Unit = synchronized(captureLock) {
        val active = capturer ?: return
        if (paused == capturePaused) return
        if (paused) {
            runCatching { active.stopCapture() }
        } else {
            // 暂停期间权限可能在系统设置里被收回：照开只会异步失败、画面全黑、日志空白。
            if (!cameraPermitted()) { events?.onMediaError(2001, "camera permission denied"); return }
            active.startCapture(videoProfile.width, videoProfile.height, videoProfile.frameRate)
        }
        capturePaused = paused
    }

    override fun createOffer(pc: String) = peers.createOffer(pc)

    override fun restartPubICE() = peers.markIceRestart("pub")

    override fun applyRemoteSdp(pc: String, type: String, sdp: String) =
        peers.applyRemoteSdp(pc, type, sdp)

    override fun applyRemoteCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) =
        peers.applyRemoteCandidate(pc, candidate, sdpMid, sdpMLineIndex)

    override fun createVideoView(context: android.content.Context): android.view.View =
        SurfaceViewRenderer(context)

    override fun attachView(uid: String, view: Any?) = onMain {
        detachRenderer(uid)
        val renderer = view as? SurfaceViewRenderer ?: return@onMain
        // **这两行必须在主线程**，否则直接抛 IllegalStateException（见类注释）。
        renderer.init(peers.eglBase.eglBaseContext, firstFrameEvents(uid))
        renderer.setEnableHardwareScaler(true)
        fitter.mount(uid, renderer)
        renderers[uid] = renderer
        bindRemoteTracks()
    }

    /** 渲染器复用、`init` 后的首帧只有一次，对端重开摄像头要另外等新画面上屏（见 [IMFirstFrameGate]）。 */
    override fun awaitFirstVideoFrame(uid: String) = onMain { firstFrames.arm(uid, renderers[uid]) }

    override fun claimRemoteTracks(owners: Map<String, String>) = onMain {
        trackOwners.putAll(owners)
        bindRemoteTracks()
    }

    /**
     * 本端预览。**它自己会把摄像头开起来**（只采集、不发布）。
     *
     * 拨出中还没有房间可发布，而用户此刻就该看见自己。预览走 [previewTrack]，
     * 与推流那条共用同一个 source，所以摄像头只开一次、切换也只有一处。
     */
    override fun startLocalPreview(view: Any?) {
        // 号在 Engine 线程上领：与 stopLocalPreview 同一条线程，先后就是 Kit 调用的先后。
        val token = previewIntent.begin()
        onMain { attachLocalPreview(view, token) }
    }

    private fun attachLocalPreview(view: Any?, token: Long) {
        if (!previewIntent.isCurrent(token)) return
        renderers[LOCAL]?.let { old ->
            runCatching { previewTrack?.removeSink(old) }
            runCatching { old.release() }
        }
        val renderer = view as? SurfaceViewRenderer ?: run {
            renderers.remove(LOCAL)
            return
        }
        // **本端预览也要 RendererEvents**：原先传 null，于是拿不到帧尺寸、缩放判据无从计算。
        renderer.init(peers.eglBase.eglBaseContext, firstFrameEvents(LOCAL))
        renderer.setEnableHardwareScaler(true)
        fitter.mount(LOCAL, renderer)
        renderer.setMirror(frontCamera)
        renderers[LOCAL] = renderer
        // 对号与起采集在同一把锁里：停止要么整个排在前面（这里作罢），要么排在后面（把这次停掉）。
        synchronized(captureLock) {
            if (previewIntent.isCurrent(token)) ensurePreviewTrack()?.addSink(renderer)
        }
    }

    /**
     * 停掉进房前的本端预览，**连摄像头一起关**（设计 v3.7 第 6 步）。
     *
     * 来电页 / 拨出中开过预览又关掉，原先只熄按钮，指示灯要亮到通话结束。
     * 已经发布进房的不停——通话中关摄像头走 [setMuted]。前台服务的 camera 类型是
     * [ensureCapture] 起采集时升上去的，这里要降回去（还没进房的话压根不该有前台服务）。
     */
    override fun stopLocalPreview() {
        synchronized(captureLock) {
            if (videoTrack != null) return
            previewIntent.cancel()
            if (videoSource == null) return
            stopCapture()
        }
        if (running) {
            IMCallForegroundService.start(appContext, withCamera = false)
        } else {
            IMCallForegroundService.stop(appContext)
        }
    }

    /** 造（或复用）只给预览看的那条轨道。拿不到摄像头时返回 null，界面退回头像。 */
    private fun ensurePreviewTrack(): VideoTrack? {
        previewTrack?.let { return it }
        val source = ensureCapture() ?: return null
        val track = peers.factory().createVideoTrack(PREVIEW_TRACK_ID, source)
        previewTrack = track
        return track
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
        // 停着采集时 libwebrtc 只会回一句 "camera is not running"；界面上关着摄像头时翻转键本来就是灰的。
        if (capturePaused) { IMRTCLog.w("media", "摄像头关着，不翻转"); return }
        capturer?.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    frontCamera = isFront
                    onMain { renderers[LOCAL]?.setMirror(isFront) }
                }

                override fun onCameraSwitchError(error: String) {
                    IMRTCLog.w("media", "翻转摄像头失败：$error")
                }
            },
        )
    }

    override fun setSpeakerOn(on: Boolean) = audio.setSpeakerOn(on)

    // ── 采集 ──────────────────────────────────────────────────────────

    /**
     * 起摄像头采集，**幂等**：已经在采了就直接返回那个 source。
     *
     * 本端预览与推流共用它——摄像头只开一次。拿不到摄像头时抛 `2002 device_not_found` 并返回 null。
     */
    private fun ensureCapture(): VideoSource? = synchronized(captureLock) {
        videoSource?.let { return it }
        /*
         **没有摄像头权限就别去开。** `startCapture` 会异步失败，而 source 已经缓存下来——
         之后授权了再开也只拿到这个死 source：按钮亮着、一帧画面都没有、日志里什么都没有。
         不缓存、报 2001（Kit 据此把按钮置成「无权限」），授权之后下一次从头再来。
        */
        if (!cameraPermitted()) {
            IMRTCLog.w("media", "没有摄像头权限，不起采集")
            events?.onMediaError(2001, "camera permission denied")
            return null
        }
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
        IMCallForegroundService.start(appContext, withCamera = true)
        return source
    }

    private fun stopCapture() = synchronized(captureLock) {
        val preview = previewTrack
        previewTrack = null
        videoTrack = null
        // 先把预览的 sink 摘干净再 dispose，反了会崩在 native 层。
        onMain { renderers[LOCAL]?.let { r -> runCatching { preview?.removeSink(r) } } }
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        captureHelper?.dispose()
        videoSource?.dispose()
        capturer = null
        captureHelper = null
        videoSource = null
        capturePaused = false
    }

    private fun cameraPermitted() =
        appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** 第一帧到了就撤 loading——UI 全靠这个信号，不然会露一段黑屏。每次 `init` 新造一个。 */
    private fun firstFrameEvents(uid: String) = object : RendererCommon.RendererEvents {
        override fun onFirstFrameRendered() {
            firstFrames.firstFrameRendered(uid)
        }

        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
            fitter.onFrameSize(uid, width, height, rotation)
        }
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
            IMRTCLog.i("media", "远端视频轨道到达：track_id=$trackId stream=$streamId")
            // 这里是 WebRTC 的信令线程；三张表都归主线程管（见类注释）。
            onMain {
                remoteVideo[trackId] = video
                bindRemoteTracks()
            }
        }

        override fun onError(message: String) {
            events?.onMediaError(2006, message)
        }
    }

    private companion object {
        const val LOCAL = "__local__"

        /** 预览轨道的 id。**不会上线路**（它没进过任何 transceiver），随便取一个不与 cid 冲突的。 */
        const val PREVIEW_TRACK_ID = "im-local-preview"
    }
}
