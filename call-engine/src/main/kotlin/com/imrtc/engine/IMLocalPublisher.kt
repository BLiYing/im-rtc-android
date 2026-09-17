package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMEngineContext
import com.imrtc.engine.statemachine.IMMachineInput
import com.imrtc.engine.statemachine.IMRoomState

/**
 * 本端轨道的发布：进房那一刻的默认一组，以及**关着摄像头进房、之后才打开**时补发的那一路视频。
 *
 * # 为什么视频不再无条件跟着 media_type 发
 *
 * 原先进房就按 `media_type` 发布 audio+video，界面上摄像头那颗按钮是开是关一概不看。
 * 发布视频就要起采集，于是这三种人都被开了摄像头：群通话（默认关着）、来电页上关掉摄像头再接听的
 * （= 以语音接听，拍板 §11-10）、摄像头权限被拒的。后两种按设计**只给过麦克风权限**。
 * iOS / Web 一直是等用户开摄像头才采集，这里对齐过去（2026-09-10）。
 *
 * 判据是 [IMMuteBook] 里记下的意图：进房之前 `closeCamera()` 过，就只发音频。
 *
 * # 为什么单独一个类
 *
 * `IMCallEngine.kt` 贴着 600 行的体量红线（CONVENTIONS §2）。
 */
internal class IMLocalPublisher(
    private val media: IMMediaAdapter?,
    private val nowMs: () -> Long,
    private val input: (IMMachineInput) -> Unit,
) {

    /** 本端已发布的 Track：cid → kind。挂断时要按它去停采集。 */
    val tracks = LinkedHashMap<String, String>()

    /** 摄像头那条轨道的 cid：预览与发布共用一个，见 [IMLocalVideoCid]。 */
    val videoCid = IMLocalVideoCid(nowMs)

    /** 刚进房：音频照发，视频看 [cameraMuted]。 */
    fun publishDefaults(state: IMEngineContext, cameraMuted: Boolean) {
        publish("audio")
        if (mediaType(state) != "video") return
        if (cameraMuted) {
            IMRTCLog.i("engine", "进房时摄像头关着，视频先不发布，等 openCamera")
            return
        }
        publish("video")
    }

    /**
     * 打开摄像头时补发视频——**只在已经进房、视频通话、还没发过视频**时。
     *
     * 进房之前不发：意图已经记在 [IMMuteBook] 里，进房时 [publishDefaults] 照着发。
     * 语音通话不发：那是另一种通话，不在这里悄悄升级。
     */
    fun publishCameraIfMissing(state: IMEngineContext) {
        if (state.room.state != IMRoomState.JOINED) return
        if (mediaType(state) != "video" || tracks.containsValue("video")) return
        IMRTCLog.i("engine", "进房时没发视频，摄像头现在打开了，补发")
        publish("video")
    }

    fun clear() {
        tracks.clear()
        videoCid.reset()
    }

    /** 会议房没有 call，媒体类型无从谈起——按视频会议处理（草图 §08）。 */
    private fun mediaType(state: IMEngineContext) =
        if (state.call.state != IMCallState.IDLE) state.call.mediaType else "video"

    private fun publish(kind: String) {
        val adapter = media ?: return
        val video = kind == "video"
        // 视频沿用预览已经领过的 cid（媒体层那条轨道的 id 就是它），没预览过才现领。
        val cid = if (video) videoCid.publish() else "local-$kind-${nowMs()}"
        tracks[cid] = kind
        adapter.publish(cid, kind, simulcast = video)
        input(
            IMMachineInput.Act(
                "publish",
                mapOf(
                    "cid" to IMJson.Str(cid),
                    "kind" to IMJson.Str(kind),
                    "source" to IMJson.Str(if (video) "camera" else "microphone"),
                    "simulcast" to IMJson.Bool(video),
                ),
            ),
        )
    }
}
