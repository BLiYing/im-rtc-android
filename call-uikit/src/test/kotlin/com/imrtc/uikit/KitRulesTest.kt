package com.imrtc.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计稿 v3 落地的那几条纯逻辑：小窗四角算术、头像哈希、权限三段式、版式选择、加人入口。
 * **全部纯 JVM**——与 iOS / Web 是同一份算法，这里的数就是三端对表用的向量。
 */
class KitRulesTest {

    @Test
    fun `小窗尺寸按容器形状选，松手吸到最近的角`() {
        assertEquals(IMPipLayout.PORTRAIT, IMPipLayout.sizeFor(390.0, 844.0))
        assertEquals(IMPipLayout.LANDSCAPE, IMPipLayout.sizeFor(1280.0, 720.0))
        assertEquals(IMPipLayout.Corner.TOP_LEFT, IMPipLayout.nearestCorner(IMPipLayout.Point(10.0, 10.0), 400.0, 800.0))
        assertEquals(IMPipLayout.Corner.BOTTOM_RIGHT, IMPipLayout.nearestCorner(IMPipLayout.Point(390.0, 790.0), 400.0, 800.0))
    }

    @Test
    fun `角的坐标离边 12，控制条显示时下面两个角上移 88`() {
        val size = IMPipLayout.PORTRAIT
        assertEquals(IMPipLayout.Point(12.0, 12.0), IMPipLayout.origin(IMPipLayout.Corner.TOP_LEFT, size, 400.0, 800.0))
        assertEquals(IMPipLayout.Point(292.0, 660.0), IMPipLayout.origin(IMPipLayout.Corner.BOTTOM_RIGHT, size, 400.0, 800.0))
        assertEquals(IMPipLayout.Point(292.0, 572.0), IMPipLayout.origin(IMPipLayout.Corner.BOTTOM_RIGHT, size, 400.0, 800.0, 88.0))
        assertEquals(IMPipLayout.Point(0.0, 0.0), IMPipLayout.clamp(IMPipLayout.Point(-30.0, -30.0), size, 400.0, 800.0))
        assertEquals(IMPipLayout.Point(304.0, 672.0), IMPipLayout.clamp(IMPipLayout.Point(999.0, 999.0), size, 400.0, 800.0))
    }

    @Test
    fun `头像哈希与 Web、iOS 同一组向量`() {
        assertEquals(0x811c9dc5L, IMAvatar.fnv1a32(""))
        assertEquals(0xe40c292cL, IMAvatar.fnv1a32("a"))
        assertEquals(2267157479L, IMAvatar.fnv1a32("alice"))
        assertEquals(2261164244L, IMAvatar.fnv1a32("bob"))
        assertEquals(1728614162L, IMAvatar.fnv1a32("carol"))
        assertEquals("非 ASCII 走 UTF-8 字节", 956401659L, IMAvatar.fnv1a32("张三"))
        assertEquals((2267157479L % 9).toInt(), IMAvatar.index("alice"))
        assertEquals("B", IMAvatar.initial("bob"))
        assertEquals("?", IMAvatar.initial("  "))
        assertEquals("张", IMAvatar.initial("张三"))
    }

    @Test
    fun `权限三段式：麦克风被拒整通取消，摄像头被拒降级继续`() {
        assertEquals(listOf(IMPermissionGate.Device.MICROPHONE), IMPermissionGate.devicesFor("audio", true))
        assertEquals(listOf(IMPermissionGate.Device.MICROPHONE, IMPermissionGate.Device.CAMERA), IMPermissionGate.devicesFor("video", true))
        assertEquals("关着摄像头接听只要麦克风", listOf(IMPermissionGate.Device.MICROPHONE), IMPermissionGate.devicesFor("video", false))

        fun run(results: Map<IMPermissionGate.Device, IMPermissionGate.Result>): Pair<IMPermissionGate.Outcome?, List<IMPermissionGate.Device>> {
            val asked = ArrayList<IMPermissionGate.Device>()
            var outcome: IMPermissionGate.Outcome? = null
            IMPermissionGate.ensure(
                IMPermissionGate.devicesFor("video", true),
                { device, cb -> asked += device; cb(results[device] ?: IMPermissionGate.Result.GRANTED) },
            ) { outcome = it }
            return outcome to asked
        }
        assertEquals(IMPermissionGate.Outcome.OK, run(emptyMap()).first)
        val mic = run(mapOf(IMPermissionGate.Device.MICROPHONE to IMPermissionGate.Result.DENIED))
        assertEquals(IMPermissionGate.Outcome.MIC_BLOCKED, mic.first)
        assertEquals("麦克风被拒就不再问摄像头", listOf(IMPermissionGate.Device.MICROPHONE), mic.second)
        assertEquals(IMPermissionGate.Outcome.CAMERA_BLOCKED, run(mapOf(IMPermissionGate.Device.CAMERA to IMPermissionGate.Result.DENIED)).first)
        assertEquals(IMPermissionGate.Outcome.CANCELLED, run(mapOf(IMPermissionGate.Device.MICROPHONE to IMPermissionGate.Result.CANCELLED)).first)
        assertEquals("没有麦克风权限，无法通话", IMPermissionGate.blocked(IMPermissionGate.Device.MICROPHONE).title)
        assertEquals("没有摄像头权限，已用语音继续通话", IMPermissionGate.blocked(IMPermissionGate.Device.CAMERA).title)
    }

    private fun groupCall(role: String): IMCallViewState {
        val begun = IMCallViewReducer.begin(
            IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "video", true), "c", "r", "video", role,
        )
        return IMCallViewReducer.userEnter(IMCallViewReducer.connected(begun), "bob")
    }

    @Test
    fun `只有主叫看得到加人入口，满员或 1407 之后藏掉`() {
        assertTrue(groupCall("caller").canShowInvite)
        assertFalse("非主叫发 invite_more 会被拒成 1407，入口直接不给", groupCall("callee").canShowInvite)
        assertEquals(7, groupCall("caller").inviteSlotsLeft)
        val full = IMCallViewReducer.invited(groupCall("caller"), listOf("c", "d", "e", "f", "g", "h", "i"))
        assertEquals(8, full.members.size)
        assertFalse("含本端 9 人就满了", full.canShowInvite)
        assertFalse(IMCallViewReducer.inviteDenied(groupCall("caller")).canShowInvite)
        assertFalse("会议房没有 call，不走这条", IMCallViewReducer.meeting(IMCallViewState(), "r").canShowInvite)
    }

    @Test
    fun `邀请中的占位格：先写终局，收掉是第二步`() {
        var state = IMCallViewReducer.invited(groupCall("caller"), listOf("dave", "bob"))
        assertEquals("已在名单里的不重复加", setOf("bob", "dave"), state.members.keys)
        assertFalse("占位格标成响铃中", state.members.getValue("dave").accepted)
        state = IMCallViewReducer.userSettled(state, "dave", IMCallViewState.Settled.REJECTED)
        assertEquals(IMCallViewState.Settled.REJECTED, state.members.getValue("dave").settled)
        assertEquals("已拒绝", IMCallViewState.settledText(IMCallViewState.Settled.REJECTED))
        assertEquals("已接听的人收到终局不受影响", IMCallViewState.Settled.NONE,
            IMCallViewReducer.userSettled(state, "bob", IMCallViewState.Settled.NO_ANSWER).members.getValue("bob").settled)
        state = IMCallViewReducer.userRemove(state, "dave")
        assertEquals(setOf("bob"), state.members.keys)
    }

    @Test
    fun `版式：两端都没画面退回语音，拨出中是头像页`() {
        var state = IMCallViewReducer.begin(IMCallViewReducer.incoming(IMCallViewState(), "c", "bob", "video", false), "c", "r", "video", "callee")
        state = IMCallViewReducer.connected(state)
        assertEquals(IMCallViewState.Layout.AUDIO, state.layout(hasLocalVideo = false))
        assertEquals("本端有画面就够", IMCallViewState.Layout.VIDEO, state.layout(hasLocalVideo = true))
        assertEquals(IMCallViewState.Layout.VIDEO, IMCallViewReducer.availability(state, "bob", "video", true).layout(false))
        assertEquals(IMCallViewState.Layout.GRID, groupCall("caller").layout(true))
        val outgoing = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "video", false)
        assertEquals("拨出中是头像页，本端预览另叠一层小窗", IMCallViewState.Layout.AUDIO, outgoing.layout(true))
        assertEquals("语音通话永远是头像页", IMCallViewState.Layout.AUDIO,
            IMCallViewReducer.connected(IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false)).layout(true))
    }

    @Test
    fun `互换、权限被拒与连接横幅`() {
        var state = IMCallViewReducer.connected(IMCallViewReducer.incoming(IMCallViewState(), "c", "bob", "video", false))
        state = IMCallViewReducer.setSwapped(state, true)
        assertTrue(state.isSwapped)
        state = IMCallViewReducer.cameraBlocked(state)
        assertTrue(state.cameraBlocked)
        assertFalse(state.cameraOn)
        assertFalse("被拒时开不了", IMCallViewReducer.toggleCamera(state).cameraOn)

        val reconnecting = IMCallViewReducer.connection(IMCallViewState(), IMCallViewState.Connection.RECONNECTING)
        // 连接状态跨通话保留：新来电不该把「正在重连」抹掉。
        assertEquals(IMCallViewState.Connection.RECONNECTING, IMCallViewReducer.incoming(reconnecting, "c", "a", "audio", false).connection)
        assertEquals(3, IMCallViewState.networkBarsLit(2))
        assertEquals(2, IMCallViewState.networkBarsLit(4))
        assertEquals(1, IMCallViewState.networkBarsLit(6))
        assertTrue(IMCallViewState.isNetworkPoor(3))
        assertEquals("网络很差", IMCallViewState.networkText(5))
        assertEquals("邀请你加入群通话", IMCallViewReducer.incoming(IMCallViewState(), "c", "a", "video", true).statusText)
    }
}
