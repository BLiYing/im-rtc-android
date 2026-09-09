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
) : IMMediaAdapter {

    private val appContext = context.applicationContext
    private var events: IMMediaAdapter.Events? = null

    /** 渲染相关的一切都在主线程上跑（见类注释）。已经在主线程时就地执行，不排队。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }

    private val audio = IMAudioRouter(appContext)
    private val peers = IMPeerConnections(appContext, PeerCallbacks())

    /** 上行每一层实际编出多少分辨率、被什么限住。见 [IMUplinkStats] 的类注释。 */
    private val uplinkStats = IMUplinkStats(main)

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
        if (!running) return
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
        if (simulcast) seedUplinkBudget(connection)
        preferResolutionOverFramerate(connection, cid)
        // 采样从这里起：此刻编码器才真的有活干。
        uplinkStats.start(connection)
        // 前台服务的 camera 类型由 ensureCapture 负责升级——采集起来的那一刻才算真的在用摄像头。
    }

    /**
     * 把**上行真实预算**告诉 BWE，别让它从 300 kbps 起爬。
     *
     * # 不做这一步会怎样（真机日志复现过）
     *
     * libwebrtc 发送侧 BWE 的起始估计默认是 300 kbps。我们推三层，总共要
     * [IMVideoProfile.simulcastUplinkBudgetBps]（720p 是 2.15 Mbps），
     * 而 `SimulcastRateAllocator` 在总码率不够时**自底向上**分配、给顶层分 0 bps。
     * 于是开局只有 `l` 层出包，`m`/`h` 要等 BWE 一路探测上来才活：
     * 服务端日志里是 `上行层存活性变化 layer=h live=False`，订阅端看到的是糊成
     * 320×180 的画面。实测一通 1v1 里 h 层死了 37 秒；另一通群通 27 秒全程只有 `l`。
     *
     * **这不是调参，是把一个本来就知道的数字填进去。** 服务端对下行做的是同一件事
     * （`internal/sfu/bwe.go` 的 `bweInitialBitrate = 2_000_000`，注释写着
     * 「种子给低了会在开局把所有人砸到 l」）——上行同理，只是一直没人填。
     *
     * 三个参数的取法：
     *  - min = 最低那层的码率。再低就不是「糊」而是「没画面」，那不该由 BWE 替用户决定
     *    （与 `bwe.go` 的 `capForEstimate` 「撑不住全 l 时仍然返回 l」同一条取舍）。
     *  - start = 总预算。**只影响开局**，之后仍由 TWCC 反馈接管；
     *    网络真的窄，BWE 第一批反馈就会把它压下去，不会一直硬灌。
     *  - max = 总预算。再高也没用——我们一共就只要这么多，别让它白探测。
     *
     * 单层发布（`simulcast=false`）不需要：那时总量就等于 `maxBitrateBps`，
     * 300 kbps 起爬也只是第一秒略糊，不会有整层拿不到码率这种事。
     */
    private fun seedUplinkBudget(connection: PeerConnection) {
        val budget = videoProfile.simulcastUplinkBudgetBps
        val floor = videoProfile.simulcastLayers.first().bitrateBps
        // 失败不该让通话挂掉：拿不到就是退回 libwebrtc 的默认起点，画面糊一会儿而已。
        if (connection.setBitrate(floor, budget, budget)) {
            IMRTCLog.i("media", "上行预算已播种 min=$floor start=$budget max=$budget（档位 ${videoProfile.name}）")
        } else {
            IMRTCLog.w("media", "上行预算播种失败，退回 libwebrtc 默认起点（开局可能只有 l 层）")
        }
    }

    /**
     * CPU / 码率吃紧时**掉帧率，不掉分辨率**。
     *
     * # 为什么
     *
     * libwebrtc 不设这一项时默认 `BALANCED`——分辨率与帧率一起降。真机实测
     * （2026-09-10，OPPO 推三层 VP8）h 层从 720×1280 掉到 **540×960** 并标着
     * `受限=cpu`（见 [IMUplinkStats] 打的「上行层实况」）。
     *
     * 而本产品最吃分辨率的场景是**看清画面里的字**（共享屏幕、对着文档拍）——
     * 那种场景下 15fps 完全够用，**分辨率掉一档就直接看不清了**。
     * 所以这里显式反转默认取舍。
     *
     * # 代价，说清楚
     *
     * CPU 真顶不住时帧率会掉得很低（实测见过 fps=1~4），画面变成近乎静止的幻灯片。
     * 那仍然比「糊成一团但很流畅」好——**至少信息还在**。
     * 如果以后有「流畅优先」的场景（比如纯聊天），该由宿主选，不该在这里写死。
     *
     * 只对视频生效；音频没有分辨率这回事。失败不影响通话，记一条日志就够。
     */
    private fun preferResolutionOverFramerate(connection: PeerConnection, cid: String) {
        val sender = connection.senders.firstOrNull { it.track()?.id() == cid } ?: run {
            IMRTCLog.w("media", "找不到 cid=$cid 的 sender，降级偏好没设上")
            return
        }
        val params = sender.parameters ?: return
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        if (sender.setParameters(params)) {
            IMRTCLog.i("media", "编码降级偏好=MAINTAIN_RESOLUTION（宁可掉帧率也保分辨率）")
        } else {
            IMRTCLog.w("media", "降级偏好没设上，退回 libwebrtc 默认的 BALANCED（分辨率会跟着降）")
        }
    }

    override fun unpublish(cid: String) {
        // v1 一通电话就一组本端 Track，停采集即可；细到单条 Track 的拆除留给会议期。
        stopCapture()
    }

    override fun setMuted(kind: String, muted: Boolean) {
        if (kind == "video") videoTrack?.setEnabled(!muted) else audioTrack?.setEnabled(!muted)
    }

    override fun createOffer(pc: String) = peers.createOffer(pc)

    override fun restartPubICE() = peers.markIceRestart("pub")

    override fun applyRemoteSdp(pc: String, type: String, sdp: String) =
        peers.applyRemoteSdp(pc, type, sdp)

    override fun applyRemoteCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) =
        peers.applyRemoteCandidate(pc, candidate, sdpMid, sdpMLineIndex)

    override fun createVideoView(context: android.content.Context): android.view.View =
        SurfaceViewRenderer(context)

    /**
     * 按 [IMVideoFit] 的判据决定这一格是裁切填满还是等比留边，并重新布局。
     *
     * # 为什么量的是**父容器**而不是渲染器自己
     *
     * 渲染器给的是 `WRAP_CONTENT`（见 `IMVideoTile`），它的尺寸**由 scalingType 反推出来**——
     * 拿它去决定 scalingType 就成了循环。父容器（`videoHost`）是 `MATCH_PARENT`、
     * 尺寸稳定，才是「这一格有多大」的正确来源。
     *
     * # 为什么不能给 `MATCH_PARENT`
     *
     * libwebrtc 的 `RendererCommon.VideoLayoutMeasure.measure()` 里有一句
     * 「If the measure specification is forcing a specific size, yield」——
     * 测量规格是 `EXACTLY`（`MATCH_PARENT` 就是）时它**直接忽略 scalingType**、
     * 占满给定尺寸再裁切填充。2026-09-10 第一版改动就栽在这里：只设了
     * `setScalingType`、而 `IMVideoTile` 给的是 `MATCH_PARENT`，**是个空操作**，
     * 真机上仍然满格裁切。
     *
     * **两个参数都要传**：单参版只设「方向一致」那一种，而方向不一致恰恰是要处理的那一种。
     *
     * **必须在主线程**（见类注释）。
     */
    private fun applyVideoFit(renderer: SurfaceViewRenderer, frame: IntArray?) {
        val host = renderer.parent as? android.view.View
        val fill = frame == null ||
            IMVideoFit.shouldFill(frame[0], frame[1], frame[2], host?.width ?: 0, host?.height ?: 0)
        val type = if (fill) {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        }
        renderer.setScalingType(type, type)
        renderer.requestLayout()
    }

    /** uid（或 [LOCAL]）→ 最近一帧的 `[未旋转宽, 未旋转高, 旋转角]`。布局变化时要拿它重算。 */
    private val lastFrameSize = LinkedHashMap<String, IntArray>()

    /**
     * 记下这一路的帧尺寸并重算缩放。**在渲染线程上被调用**，所以先回主线程。
     *
     * 布局也要监听：第一帧到达时父容器可能还没量出来（宽高是 0），
     * 那时 [IMVideoFit.shouldFill] 会先给 FILL，等 layout 稳定后这里再算一次。
     */
    private fun onFrameSize(key: String, width: Int, height: Int, rotation: Int) {
        val frame = intArrayOf(width, height, rotation)
        onMain {
            lastFrameSize[key] = frame
            renderers[key]?.let { applyVideoFit(it, frame) }
        }
    }

    /** 父容器尺寸一变就重算（转屏、进出全屏、九宫格行列变化都会走到）。 */
    private fun watchLayout(key: String, renderer: SurfaceViewRenderer) {
        renderer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyVideoFit(renderer, lastFrameSize[key])
        }
    }

    override fun attachView(uid: String, view: Any?) = onMain {
        detachRenderer(uid)
        val renderer = view as? SurfaceViewRenderer ?: return@onMain
        // **这两行必须在主线程**，否则直接抛 IllegalStateException（见类注释）。
        renderer.init(peers.eglBase.eglBaseContext, firstFrameEvents(uid))
        renderer.setEnableHardwareScaler(true)
        applyVideoFit(renderer, lastFrameSize[uid])
        watchLayout(uid, renderer)
        renderers[uid] = renderer
        bindRemoteTracks()
    }

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
    override fun startLocalPreview(view: Any?) = onMain {
        renderers[LOCAL]?.let { old ->
            runCatching { previewTrack?.removeSink(old) }
            runCatching { old.release() }
        }
        val renderer = view as? SurfaceViewRenderer ?: run {
            renderers.remove(LOCAL)
            return@onMain
        }
        // **本端预览也要 RendererEvents**：原先传 null，于是拿不到帧尺寸、缩放判据无从计算。
        renderer.init(peers.eglBase.eglBaseContext, firstFrameEvents(LOCAL))
        renderer.setEnableHardwareScaler(true)
        applyVideoFit(renderer, lastFrameSize[LOCAL])
        watchLayout(LOCAL, renderer)
        renderer.setMirror(frontCamera)
        renderers[LOCAL] = renderer
        ensurePreviewTrack()?.addSink(renderer)
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
    }

    private fun encoding(rid: String, scale: Double, maxBitrateBps: Int) =
        RtpParameters.Encoding(rid, true, scale).apply { this.maxBitrateBps = maxBitrateBps }

    /** 第一帧到了就撤 loading——UI 全靠这个信号，不然会露一段黑屏。 */
    private fun firstFrameEvents(uid: String) = object : RendererCommon.RendererEvents {
        override fun onFirstFrameRendered() {
            events?.onFirstVideoFrame(uid)
        }

        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
            onFrameSize(uid, width, height, rotation)
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
