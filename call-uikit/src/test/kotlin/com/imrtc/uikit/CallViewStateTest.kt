package com.imrtc.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面逻辑的单测。**纯 JVM，不需要设备**——视图模型是纯值，reducer 是纯函数。
 *
 * 这里守的两件事都是「不报错但用户会骂」的那类：红按钮点了没反应、时长显示成负数。
 */
class CallViewStateTest {

    @Test
    fun `红按钮四向分派——会议房走 leaveRoom 而不是 hangup`() {
        // Web 端的真 bug：会议房里点挂断毫无反应。红按钮无条件走 hangup，
        // 而会议房里根本没有 call，通话机把它本地拒成 2005，用户只看到「点了没反应」。
        val meeting = IMCallViewReducer.meeting(IMCallViewState(), "r-1")
        assertEquals(IMCallViewState.Action.LEAVE_ROOM, meeting.hangupAction)

        val incoming = IMCallViewReducer.incoming(IMCallViewState(), "c-1", "alice", "audio", false)
        assertEquals(IMCallViewState.Action.REJECT, incoming.hangupAction)
        assertTrue("来电时才显示接听键", incoming.showAnswerButton)

        val outgoing = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false)
        assertEquals(IMCallViewState.Action.CANCEL, outgoing.hangupAction)
        assertFalse(outgoing.showAnswerButton)

        val connected = IMCallViewReducer.connected(IMCallViewReducer.begin(outgoing, "c-1", "r-1", "audio", "caller"))
        assertEquals(IMCallViewState.Action.HANGUP, connected.hangupAction)

        assertEquals(IMCallViewState.Action.NONE, IMCallViewState().hangupAction)
    }

    @Test
    fun `群通话与会议的标题走人数，不走某个人的名字`() {
        // 真机上把八个人叫起来，标题写着「alice」——那只是名单里排第一的那个人。
        // 人数要 +1：members 里不含自己。
        val group = IMCallViewReducer.outgoing(
            IMCallViewState(),
            listOf("alice", "bob", "carol", "dave", "erin", "frank", "grace", "heidi"),
            "video",
            isGroup = true,
        )
        assertEquals("群通话（9 人）", group.titleText)

        val meeting = IMCallViewReducer.userEnter(
            IMCallViewReducer.meeting(IMCallViewState(), "r-1"),
            "bob",
        )
        assertEquals("会议（2 人）", meeting.titleText)

        // 1v1 还是显示对方是谁。
        val single = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false)
        assertEquals("bob", single.titleText)
        // 没有对方信息时给一个中性词，**不能把 room_id 甩到用户脸上**。
        assertEquals("通话", IMCallViewState().titleText)
    }

    @Test
    fun `时长只在接通后走，且格式化正确`() {
        var state = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false)
        state = IMCallViewReducer.tick(state)
        assertEquals("还没接通不该计时", 0L, state.durationSec)

        state = IMCallViewReducer.connected(state)
        repeat(65) { state = IMCallViewReducer.tick(state) }
        assertEquals(65L, state.durationSec)
        assertEquals("01:05", state.statusText)

        assertEquals("00:00", IMGrid.formatDuration(0))
        assertEquals("00:00", IMGrid.formatDuration(-3))
        assertEquals("1:00:01", IMGrid.formatDuration(3601))
    }

    @Test
    fun `结束原因翻译成人话，且表外的值不会漏出去`() {
        val ended = IMCallViewReducer.ended(IMCallViewState(), "no_answer")
        assertEquals("无人接听", ended.statusText)
        // 协议 §2.4 规则 6：表外的值 Engine 已经折成 error 了；即便漏进来也不能把生值显给用户。
        assertEquals("通话结束", IMCallViewReducer.ended(IMCallViewState(), "supernova").statusText)
    }

    @Test
    fun `成员进出与音视频可用状态`() {
        var state = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "video", true)
        state = IMCallViewReducer.userEnter(state, "carol")
        assertEquals(setOf("bob", "carol"), state.members.keys)

        state = IMCallViewReducer.availability(state, "carol", "video", true)
        assertTrue(state.members.getValue("carol").video)
        state = IMCallViewReducer.availability(state, "carol", "audio", false)
        assertFalse("静音了", state.members.getValue("carol").audio)

        state = IMCallViewReducer.userLeave(state, "bob")
        assertEquals(setOf("carol"), state.members.keys)
    }

    @Test
    fun `九宫格的行列与层上界`() {
        assertEquals(1 to 1, IMGrid.dimensions(1))
        assertEquals(1 to 2, IMGrid.dimensions(2))
        assertEquals(2 to 2, IMGrid.dimensions(4))
        assertEquals(3 to 3, IMGrid.dimensions(9))
        // 上限 9：群通话就是 3×3，不做分页轮换
        assertEquals(3 to 3, IMGrid.dimensions(12))

        // **格子越小越该要小图**：漏发这个上界的话服务端记 m、实际发 h。
        assertEquals("h", IMGrid.layerFor(2, focused = false))
        assertEquals("m", IMGrid.layerFor(4, focused = false))
        assertEquals("l", IMGrid.layerFor(9, focused = false))
        assertEquals("被放大的那一格永远要大图", "h", IMGrid.layerFor(9, focused = true))
    }

    @Test
    fun `格子最多九个`() {
        var state = IMCallViewState()
        repeat(12) { state = IMCallViewReducer.userEnter(state, "u$it") }
        assertEquals(9, state.tiles.size)
    }
}
