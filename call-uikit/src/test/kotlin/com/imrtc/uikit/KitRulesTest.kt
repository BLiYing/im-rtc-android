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

        /*
         **发起时申请哪些设备只看 `media_type`，不看群不群、也不看摄像头默认开没开**（交互稿 §01 的表）。
         问归问：群通话默认关着摄像头，就不采集、不发布视频（见 `IMLocalPublisher`）。
        */
        assertEquals(
            "群视频照样要问摄像头权限",
            listOf(IMPermissionGate.Device.MICROPHONE, IMPermissionGate.Device.CAMERA),
            IMPermissionGate.devicesForPlacing("video", isGroup = true),
        )
        assertEquals(
            listOf(IMPermissionGate.Device.MICROPHONE, IMPermissionGate.Device.CAMERA),
            IMPermissionGate.devicesForPlacing("video", isGroup = false),
        )
        assertEquals(
            "语音通话不要摄像头",
            listOf(IMPermissionGate.Device.MICROPHONE),
            IMPermissionGate.devicesForPlacing("audio", isGroup = true),
        )

        // 接听：只有来电页上亲手关掉摄像头的才只要麦克风（§11-10）；群通话默认关着不算，照样问。
        assertEquals(
            listOf(IMPermissionGate.Device.MICROPHONE, IMPermissionGate.Device.CAMERA),
            IMPermissionGate.devicesForAnswering("video", cameraOptedOut = false),
        )
        assertEquals(
            "关掉摄像头再接听 = 以语音接听，只要麦克风",
            listOf(IMPermissionGate.Device.MICROPHONE),
            IMPermissionGate.devicesForAnswering("video", cameraOptedOut = true),
        )
        assertEquals(
            listOf(IMPermissionGate.Device.MICROPHONE),
            IMPermissionGate.devicesForAnswering("audio", cameraOptedOut = false),
        )

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

    /**
     * **接通后的 1v1 视频恒为 VIDEO 版式**：两边都关摄像头时也不退回语音页，
     * 否则小窗整个消失，用户以为断了，而且再也点不到互换。
     */
    @Test
    fun `版式：接通后的视频通话恒为视频页，拨出中是头像页`() {
        var state = IMCallViewReducer.begin(IMCallViewReducer.incoming(IMCallViewState(), "c", "bob", emptyList(), "video", false), "c", "r", "video", "callee")
        state = IMCallViewReducer.connected(state)
        assertEquals(IMCallViewState.Layout.VIDEO, state.layout)
        assertEquals(IMCallViewState.Layout.VIDEO, IMCallViewReducer.availability(state, "bob", "video", true).layout)
        assertEquals(IMCallViewState.Layout.GRID, groupCall("caller").layout)
        val outgoing = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "video", false)
        assertEquals("拨出中是头像页，本端预览另叠一层小窗", IMCallViewState.Layout.AUDIO, outgoing.layout)
        assertEquals("语音通话永远是头像页", IMCallViewState.Layout.AUDIO,
            IMCallViewReducer.connected(IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false)).layout)
    }

    @Test
    fun `互换、权限被拒与连接横幅`() {
        var state = IMCallViewReducer.connected(IMCallViewReducer.incoming(IMCallViewState(), "c", "bob", emptyList(), "video", false))
        state = IMCallViewReducer.setSwapped(state, true)
        assertTrue(state.isSwapped)
        state = IMCallViewReducer.cameraBlocked(state)
        assertTrue(state.cameraBlocked)
        assertFalse(state.cameraOn)
        assertFalse("被拒时开不了", IMCallViewReducer.toggleCamera(state).cameraOn)

        val reconnecting = IMCallViewReducer.connection(IMCallViewState(), IMCallViewState.Connection.RECONNECTING)
        // 连接状态跨通话保留：新来电不该把「正在重连」抹掉。
        assertEquals(IMCallViewState.Connection.RECONNECTING, IMCallViewReducer.incoming(reconnecting, "c", "a", emptyList(), "audio", false).connection)
        assertEquals(3, IMCallViewState.networkBarsLit(2))
        assertEquals(2, IMCallViewState.networkBarsLit(4))
        assertEquals(1, IMCallViewState.networkBarsLit(6))
        assertTrue(IMCallViewState.isNetworkPoor(3))
        assertEquals("网络很差", IMCallViewState.networkText(5))
        assertEquals("邀请你加入群通话", IMCallViewReducer.incoming(IMCallViewState(), "c", "a", emptyList(), "video", true).statusText)
    }

    /**
     * 结束画面的两条：**提示要清掉**、**时长用服务端给的那个**。
     *
     * 提示不清的话，结束画面上写的是刚刚那句「bob 已拒接」而不是结束原因「对方已拒接」——
     * 同一个结局在 iOS 与 Android 上写着不一样的话。
     */
    @Test
    fun `结束时清掉提示，时长用服务端给的`() {
        var state = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false)
        state = IMCallViewReducer.hint(state, "bob 已拒接")
        assertEquals("bob 已拒接", state.hint)
        state = IMCallViewReducer.ended(state, "reject", 0)
        assertEquals("", state.hint)
        assertEquals("对方已拒接", state.statusText)

        var call = IMCallViewReducer.connected(IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false))
        call = IMCallViewReducer.ended(call, "hangup", 201)
        assertEquals("通话结束 · 03:21", call.statusText)
    }

    /** 与 iOS 的 `imEndReasonText` / Web 的 `endReasonText` 逐字对齐——漏一条就是两端写着不一样的话。 */
    @Test
    fun `每种结束原因都有自己那句话`() {
        val table = mapOf(
            "cancel" to "已取消", "reject" to "对方已拒接", "busy" to "对方忙线中",
            "no_answer" to "对方无人接听", "offline" to "对方当前不在线", "network" to "网络中断",
            "answered_elsewhere" to "已在其他设备接听", "rejected_elsewhere" to "已在其他设备拒绝",
            "room_closed" to "房间已解散", "kicked" to "已被移出",
        )
        for ((reason, want) in table) {
            assertEquals(reason, want, IMCallViewState.endReasonText(reason, "caller", 0))
        }
        assertEquals("已结束", IMCallViewState.endReasonText("什么鬼", "caller", 0))
    }

    /*
     前后台判定：**装钩子之前就在前台的那个界面，它的 onStop 不算「App 进后台」。**

     不加这一条就是 2026-09-06 那个真机 bug：钩子是登录成功后才装的，宿主首页早就 onStart 过、
     没被数进去；接听后通话页 onStart（+1），~0.5s 开场动画放完首页 onStop（-1 → 0），
     Kit 当成切后台把摄像头 mute 掉——本机界面毫无异样，坏的是对端（只看到头像）。
    */
    @Test
    fun `装钩子之前就 started 的界面，它的 stop 不算进后台`() {
        val state = IMForegroundState()
        val host = Any()   // 宿主首页：钩子装上时它已经 started 了，我们没见过它的 onStart
        val callPage = Any()

        assertTrue("第一个见到的 onStart 算回前台", state.started(callPage))
        assertFalse("没见过 onStart 的界面退下去，不能算整个 App 进后台", state.stopped(host))
        assertTrue("通话页自己退下去才是真的进后台", state.stopped(callPage))
    }

    @Test
    fun `正常的前后台来回：只在空集合与非空之间翻转时才回调`() {
        val state = IMForegroundState()
        val a = Any()
        val b = Any()

        assertTrue(state.started(a))
        assertFalse("已经在前台了，再起一个界面不重复报", state.started(b))
        assertFalse("还剩一个界面在前台", state.stopped(a))
        assertTrue("最后一个退下去才报后台", state.stopped(b))
        assertTrue("再起来又是回前台", state.started(a))
        // 同一个 key 重复 stop 不该再报一次（onStop 与 onDestroy 都清一次也不会出事）。
        assertTrue(state.stopped(a))
        assertFalse(state.stopped(a))
    }
}
