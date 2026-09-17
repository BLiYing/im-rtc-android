package com.imrtc.engine

import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.signaling.FakeScheduler
import com.imrtc.engine.signaling.FakeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 摄像头关着进房，**视频就不发布、不采集**；之后打开摄像头再补发。
 *
 * 起因（2026-09-10）：Engine 原先进房就按 `media_type` 发布 audio+video，发布视频就要起采集——
 * 群通话默认关着摄像头的、来电页上关掉摄像头再接听的（= 以语音接听，§11-10）、摄像头权限被拒的，
 * 全都被开了摄像头。按设计后两种人**只给过麦克风权限**。iOS / Web 一直是等用户开摄像头才采集。
 */
class CameraIntentPublishTest {

    private val scheduler = FakeScheduler()
    private val transport = FakeTransport()
    private val media = RecordingMedia()

    private val engine = IMCallEngine.forTest(
        IMCallEngine.Config(url = "ws://test/rtc", deviceId = "d-1"),
        EngineLoopTest.RecordingListener(),
        media,
        scheduler,
        transport,
    )

    private fun login() {
        engine.login("tk-1")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
    }

    /** 对方接听 → 进房。进房那一步 Engine 自动发布本端轨道。 */
    private fun connectAndJoin(mediaType: String) {
        transport.deliver(
            IMFrameType.CALL_CONNECTED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "room_id" to IMJson.Str("r-1"),
                "room_token" to IMJson.Str("tk-room"),
                "media_type" to IMJson.Str(mediaType),
                "connected_at_ms" to IMJson.Num(1_000),
            ),
        )
        transport.replyOk(
            IMFrameType.ROOM_JOIN,
            mapOf("room_id" to IMJson.Str("r-1"), "participant_id" to IMJson.Str("p-1")),
        )
    }

    /** 服务端给还没回过的 publish 逐条回 publish.ok。 */
    private fun ackPublishes() {
        for (frame in transport.sent.filter { it.type == IMFrameType.ROOM_PUBLISH }.drop(acked)) {
            val cid = (frame.data["cid"] as IMJson.Str).value
            transport.deliver(
                IMFrameType.ROOM_PUBLISH + ".ok",
                frame.reqId,
                mapOf("cid" to IMJson.Str(cid), "track_id" to IMJson.Str("t-$cid")),
            )
            acked++
        }
    }

    private var acked = 0

    private fun publishedKinds() = transport.sent
        .filter { it.type == IMFrameType.ROOM_PUBLISH }
        .map { (it.data["kind"] as IMJson.Str).value }

    @Test
    fun `进房之前关了摄像头，视频通话只发音频，也不去开摄像头`() {
        login()
        engine.closeCamera()
        engine.call(listOf("bob", "carol"), "video", isGroup = true)
        connectAndJoin("video")

        assertEquals(listOf("audio"), publishedKinds())
        assertEquals("没发视频就不该碰摄像头", listOf("audio"), media.published)
    }

    @Test
    fun `进房后打开摄像头，补发视频并走一轮 pub 协商`() {
        login()
        engine.closeCamera()
        engine.call(listOf("bob"), "video")
        connectAndJoin("video")
        ackPublishes()
        val offersBefore = media.offers.count { it == "pub" }

        engine.openCamera()
        assertEquals(listOf("audio", "video"), publishedKinds())
        assertEquals(listOf("audio", "video"), media.published)

        ackPublishes()
        assertEquals("新挂的视频轨道要重新协商才发得出去", offersBefore + 1, media.offers.count { it == "pub" })
    }

    @Test
    fun `补发只发一次，反复开关不再发`() {
        login()
        engine.closeCamera()
        engine.call(listOf("bob"), "video")
        connectAndJoin("video")
        ackPublishes()

        engine.openCamera()
        ackPublishes()
        engine.closeCamera()
        engine.openCamera()
        engine.openCamera()

        assertEquals(listOf("audio", "video"), publishedKinds())
    }

    @Test
    fun `摄像头开着进房，照旧发音频加视频`() {
        login()
        engine.call(listOf("bob"), "video")
        connectAndJoin("video")

        assertEquals(listOf("audio", "video"), publishedKinds())
    }

    @Test
    fun `进房之前关了又开，以最后一次为准`() {
        login()
        engine.closeCamera()
        engine.openCamera()
        engine.call(listOf("bob", "carol"), "video", isGroup = true)
        connectAndJoin("video")

        assertEquals(listOf("audio", "video"), publishedKinds())
    }

    @Test
    fun `语音通话里打开摄像头不发视频`() {
        login()
        engine.call(listOf("bob"), "audio")
        connectAndJoin("audio")
        ackPublishes()

        engine.openCamera()

        assertEquals(listOf("audio"), publishedKinds())
    }

    @Test
    fun `上一通关着摄像头，不许漏到下一通`() {
        login()
        engine.closeCamera()
        engine.call(listOf("bob"), "video")
        connectAndJoin("video")
        ackPublishes()
        transport.deliver(
            IMFrameType.CALL_ENDED,
            "",
            mapOf(
                "call_id" to IMJson.Str("call-1"),
                "reason" to IMJson.Str("hangup"),
                "duration_sec" to IMJson.Num(3),
            ),
        )

        engine.call(listOf("carol"), "video")
        connectAndJoin("video")

        assertEquals(listOf("audio", "audio", "video"), publishedKinds())
    }

    @Test
    fun `进房前关摄像头：停预览交给媒体层，一帧都不发`() {
        login()
        val framesBefore = transport.sent.size
        engine.closeCamera()
        engine.stopLocalPreview()

        assertEquals(1, media.previewStops)
        assertEquals(framesBefore, transport.sent.size)
    }

    // ── 本端预览的 cid 与发布对齐（§7.5 `startLocalPreview(): cid`，2026-09-17） ──

    private fun publishedVideoCid() = transport.sent
        .filter { it.type == IMFrameType.ROOM_PUBLISH && (it.data["kind"] as IMJson.Str).value == "video" }
        .map { (it.data["cid"] as IMJson.Str).value }
        .single()

    private fun endCall() = transport.deliver(
        IMFrameType.CALL_ENDED,
        "",
        mapOf(
            "call_id" to IMJson.Str("call-1"),
            "reason" to IMJson.Str("hangup"),
            "duration_sec" to IMJson.Num(3),
        ),
    )

    @Test
    fun `预览领到的 cid 就是进房发布视频用的 cid，媒体层也按它起预览`() {
        login()
        val cid = engine.startLocalPreview()
        engine.attachLocalView(cid, "view")
        engine.call(listOf("bob"), "video")
        connectAndJoin("video")

        assertEquals(cid, publishedVideoCid())
        assertEquals(listOf(cid), media.previewCids)
        assertEquals(listOf(cid to "view"), media.localViews)
        assertEquals(cid, media.publishedCids.last())
    }

    @Test
    fun `预览在途时再调一次，拿回同一个 cid`() {
        login()
        val first = engine.startLocalPreview()
        assertEquals(first, engine.startLocalPreview())
    }

    @Test
    fun `进房前关了预览再开，换一个新 cid，发布用新的`() {
        login()
        val old = engine.startLocalPreview()
        engine.stopLocalPreview()
        val fresh = engine.startLocalPreview()
        assertNotEquals(old, fresh)

        engine.call(listOf("bob"), "video")
        connectAndJoin("video")
        assertEquals(fresh, publishedVideoCid())
    }

    @Test
    fun `已经发布的摄像头关了预览再开，cid 不变`() {
        login()
        engine.call(listOf("bob"), "video")
        connectAndJoin("video")
        val published = publishedVideoCid()

        engine.closeCamera()
        engine.stopLocalPreview()
        assertEquals(published, engine.startLocalPreview())
    }

    @Test
    fun `没预览过、进房后才开摄像头：补发的 cid 之后开预览也拿得到`() {
        login()
        engine.closeCamera()
        engine.call(listOf("bob"), "video")
        connectAndJoin("video")
        ackPublishes()

        engine.openCamera()
        assertEquals(publishedVideoCid(), engine.startLocalPreview())
    }

    @Test
    fun `通话结束后 cid 作废，下一通预览是新的`() {
        login()
        engine.call(listOf("bob"), "video")
        connectAndJoin("video")
        val first = publishedVideoCid()
        endCall()

        assertNotEquals(first, engine.startLocalPreview())
    }

    /** 只记录发布与协商的媒体适配器。 */
    private class RecordingMedia : IMMediaAdapter {
        val published = mutableListOf<String>()
        val publishedCids = mutableListOf<String>()
        val offers = mutableListOf<String>()
        val previewCids = mutableListOf<String>()
        val localViews = mutableListOf<Pair<String, Any?>>()
        var previewStops = 0

        override fun stopLocalPreview() {
            previewStops++
        }

        override fun publish(cid: String, kind: String, simulcast: Boolean) {
            published += kind
            publishedCids += cid
        }

        override fun createOffer(pc: String) {
            offers += pc
        }

        override fun attachEvents(events: IMMediaAdapter.Events) = Unit
        override fun start(iceServers: List<String>) = Unit
        override fun stop() = Unit
        override fun unpublish(cid: String) = Unit
        override fun setMuted(kind: String, muted: Boolean) = Unit
        override fun restartPubICE() = Unit
        override fun applyRemoteSdp(pc: String, type: String, sdp: String) = Unit
        override fun applyRemoteCandidate(pc: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) = Unit
        override fun claimRemoteTracks(owners: Map<String, String>) = Unit
        override fun attachView(uid: String, view: Any?) = Unit
        override fun startLocalPreview(cid: String) {
            previewCids += cid
        }

        override fun attachLocalView(cid: String, view: Any?) {
            localViews += cid to view
        }
        override fun switchCamera() = Unit
        override fun setSpeakerOn(on: Boolean) = Unit
        override fun createVideoView(context: android.content.Context): android.view.View? = null
    }
}
