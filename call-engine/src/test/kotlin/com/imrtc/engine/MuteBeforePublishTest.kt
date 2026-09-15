package com.imrtc.engine

import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.FakeScheduler
import com.imrtc.engine.signaling.FakeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「接通之前就按了静音」——真机 2026-09-09 抓到的那一幕。
 *
 * ## 现场
 *
 * alice（Android）发起群呼，在**「正在呼叫…」阶段**按了静音，然后 bob 接通。
 * 结果 bob **照样听得见 alice 说话**，而 alice 的界面上明明写着「已静音」。
 *
 * 日志把原因写得很清楚：
 *
 * ```
 * 00:09:59.7  call.invite                    ← 发起呼叫
 * 00:10:01.0  WARN 没有 audio Track 可以开关   ← 按静音，被丢掉了
 * 00:10:04.8  call.connected                 ← 对方接了
 * 00:10:05.0  room.publish ×2                ← 这时才发布轨道，默认是开着的
 * ```
 *
 * 那一刻通话还没接通、房间还没进、轨道压根还没发布：既没有本端轨道可关，
 * 也没有 `track_id` 可发 `room.mute`。而 `setMuted` 原先把这两件事绑死了——
 * 拿不到 track_id 就直接早退，**连本端轨道都不关**。
 *
 * **这不是显示错乱，是隐私问题**：用户以为自己静音了，实际没有。
 */
class MuteBeforePublishTest {

    private val scheduler = FakeScheduler()
    private val transport = FakeTransport()
    private val listener = EngineLoopTest.RecordingListener()
    private val media = RecordingMedia()

    private val engine = IMCallEngine.forTest(
        IMCallEngine.Config(url = "ws://test/rtc", deviceId = "d-1"),
        listener,
        media,
        scheduler,
        transport,
    )

    private fun loginAndConnect() {
        engine.login("tk-1")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
    }

    /** 对方接听 → 进房 → 自动发布本端轨道，一路走到 publish.ok。 */
    private fun peerAcceptsAndPublishCompletes() {
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "room_id" to IMJson.Str("r-1"),
                "room_token" to IMJson.Str("tk-room"),
                "media_type" to IMJson.Str("audio"),
                "connected_at_ms" to IMJson.Num(1_000),
            ),
        )
        transport.replyOk(
            IMFrameType.ROOM_JOIN,
            mapOf("room_id" to IMJson.Str("r-1"), "participant_id" to IMJson.Str("p-1")),
        )
        // 进房成功之后 Engine 自动发布，服务端逐条回 publish.ok（带 track_id）。
        for (frame in transport.sent.filter { it.type == IMFrameType.ROOM_PUBLISH }) {
            val cid = (frame.data["cid"] as IMJson.Str).value
            transport.deliver(
                IMFrameType.ROOM_PUBLISH + ".ok",
                frame.reqId,
                mapOf("cid" to IMJson.Str(cid), "track_id" to IMJson.Str("t-$cid")),
            )
        }
    }

    /** 发布之前按的静音，**必须真的落到本端轨道上**——不然对端照样听得见。 */
    @Test
    fun `呼叫中按的静音，接通发布后要补做到本端轨道上`() {
        loginAndConnect()
        engine.call(listOf("bob"), "audio")

        engine.closeMicrophone()
        // 这一刻轨道还不存在，本端这次调用是空操作——但意图必须留住。
        assertTrue(
            "轨道还没发布，不该有 room.mute 帧",
            transport.countOf(IMFrameType.ROOM_MUTE) == 0,
        )

        peerAcceptsAndPublishCompletes()

        assertTrue(
            "发布之后必须补一次本端静音，否则对端听得见（这条是隐私问题，不是显示问题）",
            media.mutes.contains("audio" to true),
        )
    }

    /** 也要补发 `room.mute`，否则服务端与对端界面上仍显示你没静音。 */
    @Test
    fun `呼叫中按的静音，接通后要补发 room mute 帧`() {
        loginAndConnect()
        engine.call(listOf("bob"), "audio")
        engine.closeMicrophone()

        peerAcceptsAndPublishCompletes()

        val mute = transport.lastOf(IMFrameType.ROOM_MUTE) ?: error("没有补发 room.mute")
        assertEquals(true, (mute.data["muted"] as IMJson.Bool).value)
        assertEquals("t-local-audio-1000000", (mute.data["track_id"] as IMJson.Str).value)
    }

    /**
     * `openMicrophone()` 是 `closeMicrophone()` 的撤销：以最后一次意图为准，接通后不该静音。
     *
     * 与 [IMCallEngine.openCamera] 不同：麦克风轨道不管意图如何都在进房那一刻无条件发布
     * （[IMLocalPublisher.publishDefaults] 音频那一支不看 [IMMuteBook]），所以这里不需要
     * `publishXxxIfMissing` 那一套——`openMicrophone` 唯一要做的就是把意图翻回「不静音」。
     */
    @Test
    fun `呼叫中关闭又打开麦克风，以最后一次意图为准，接通后不静音`() {
        loginAndConnect()
        engine.call(listOf("bob"), "audio")
        engine.closeMicrophone()
        engine.openMicrophone()

        peerAcceptsAndPublishCompletes()

        // `closeMicrophone()` 那一下已经**无条件**落到本端轨道上（media.mutes 里留着一条
        // "audio" to true，轨道还不存在时这是空操作，见 IMCallEngine.applyMuted 的类注释）——
        // 这不是 bug。真正要紧的是**最后一条**：`openMicrophone()` 之后本端与补发的
        // room.mute 都要落在「不静音」，不能是关闭时那次的残留。
        assertEquals("最后一次是 openMicrophone，本端最终状态不该是静音", false, media.mutes.last { it.first == "audio" }.second)
        transport.lastOf(IMFrameType.ROOM_MUTE)?.let { mute ->
            assertEquals(false, (mute.data["muted"] as IMJson.Bool).value)
        }
    }

    /** 没按过静音的，**不许无中生有**地补一条。 */
    @Test
    fun `没按过静音就不该补发任何东西`() {
        loginAndConnect()
        engine.call(listOf("bob"), "audio")

        peerAcceptsAndPublishCompletes()

        assertEquals(0, transport.countOf(IMFrameType.ROOM_MUTE))
        assertTrue("不该凭空关掉本端轨道", media.mutes.none { it.second })
    }

    /** 补做只做一次：之后每一帧下行都不该再重发。 */
    @Test
    fun `补做过之后不再重复补`() {
        loginAndConnect()
        engine.call(listOf("bob"), "audio")
        engine.closeMicrophone()
        peerAcceptsAndPublishCompletes()
        val after = transport.countOf(IMFrameType.ROOM_MUTE)

        // 再来几帧无关的下行，补做不该被重新触发。
        transport.deliver(IMFrameType.ROOM_ACTIVE_SPEAKERS, "", mapOf("speakers" to IMJson.Arr(emptyList())))
        transport.deliver(IMFrameType.ROOM_ACTIVE_SPEAKERS, "", mapOf("speakers" to IMJson.Arr(emptyList())))

        assertEquals("补做是一次性的", after, transport.countOf(IMFrameType.ROOM_MUTE))
    }

    /** 通话结束时意图要跟着作废——否则下一通会莫名其妙地一上来就是静音的。 */
    @Test
    fun `挂断之后静音意图不许漏到下一通`() {
        loginAndConnect()
        engine.call(listOf("bob"), "audio")
        engine.closeMicrophone()
        peerAcceptsAndPublishCompletes()

        transport.deliver(
            IMFrameType.CALL_ENDED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "reason" to IMJson.Str("hangup"),
                "duration_sec" to IMJson.Num(3),
            ),
        )
        media.mutes.clear()

        // 下一通：全程没按过静音。
        engine.call(listOf("carol"), "audio")
        peerAcceptsAndPublishCompletes()

        assertTrue("上一通的静音不该漏过来", media.mutes.none { it.second })
    }

    /** 只记录调用的媒体适配器。别的方法都是空操作——这几条验的是信令与本端开关的接线。 */
    private class RecordingMedia : IMMediaAdapter {
        val mutes = mutableListOf<Pair<String, Boolean>>()

        override fun setMuted(kind: String, muted: Boolean) {
            mutes += kind to muted
        }

        override fun attachEvents(events: IMMediaAdapter.Events) = Unit
        override fun start(iceServers: List<String>) = Unit
        override fun stop() = Unit
        override fun publish(cid: String, kind: String, simulcast: Boolean) = Unit
        override fun unpublish(cid: String) = Unit
        override fun createOffer(pc: String) = Unit
        override fun restartPubICE() = Unit
        override fun applyRemoteSdp(pc: String, type: String, sdp: String) = Unit
        override fun applyRemoteCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) = Unit
        override fun claimRemoteTracks(owners: Map<String, String>) = Unit
        override fun attachView(uid: String, view: Any?) = Unit
        override fun startLocalPreview(view: Any?) = Unit
        override fun switchCamera() = Unit
        override fun setSpeakerOn(on: Boolean) = Unit
        override fun createVideoView(context: android.content.Context): android.view.View? = null
    }
}
