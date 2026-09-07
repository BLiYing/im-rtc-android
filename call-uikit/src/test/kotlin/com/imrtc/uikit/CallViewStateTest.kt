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

        val incoming = IMCallViewReducer.incoming(IMCallViewState(), "c-1", "alice", emptyList(), "audio", false)
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
        assertEquals("群通话 · 9 人", group.titleText)

        val meeting = IMCallViewReducer.userEnter(
            IMCallViewReducer.meeting(IMCallViewState(), "r-1"),
            "bob",
        )
        assertEquals("会议 · 2 人", meeting.titleText)

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
        val caller = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false)
        assertEquals("对方无人接听", IMCallViewReducer.ended(caller, "no_answer").statusText)
        assertEquals("未接来电", IMCallViewReducer.ended(IMCallViewState(), "no_answer").statusText)
        // 协议 §2.4 规则 6：表外的值 Engine 已经折成 error 了；即便漏进来也不能把生值显给用户。
        assertEquals("已结束", IMCallViewReducer.ended(IMCallViewState(), "supernova").statusText)
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
        // **决定列数的不是人数，是容器形状**：同样 2 个人，横屏是左右排。
        assertEquals(2 to 1, IMGrid.dimensions(2, aspect = 1.8))

        // **格子越小越该要小图**：漏发这个上界的话服务端记 m、实际发 h。
        assertEquals("h", IMGrid.layerFor(1, focused = false))
        assertEquals("m", IMGrid.layerFor(2, focused = false))
        assertEquals("m", IMGrid.layerFor(4, focused = false))
        assertEquals("l", IMGrid.layerFor(9, focused = false))
        assertEquals("被放大的那一格永远要大图", "h", IMGrid.layerFor(9, focused = true))
    }

    /**
     * 竖屏上 3~4 格恒为两列——**不管容器多窄**，与 iOS / Web 同一条规则。
     *
     * 按「格子最大」挑的话，翻转压在手机的常见比例上（3 格 0.662、4 格 0.495）：
     * 0.48 是这块舞台区**多算了一整条控制条**时的比例（修 stage 下边界之前），
     * 0.68 是修完之后的；两个都必须排成「第一行两个」，否则同一通电话两种样子。
     */
    @Test
    fun `竖屏三格与四格恒为两列`() {
        for (aspect in listOf(0.4, 0.48, 0.6, 0.648, 0.68, 0.9)) {
            assertEquals("aspect=$aspect", 2 to 2, IMGrid.dimensions(3, aspect))
            assertEquals("aspect=$aspect", 2 to 2, IMGrid.dimensions(4, aspect))
        }
        // 两个人仍然上下摞（那一条是尺寸判据，没被这条规则盖掉）。
        assertEquals(1 to 2, IMGrid.dimensions(2, aspect = 0.7))
        // 横屏不受这条约束：宽容器上三个人一行排开。
        assertEquals(3 to 1, IMGrid.dimensions(3, aspect = 2.0))
    }

    /**
     * **同一批人，列数会往小走**——这一条是 [IMCallGridView] 那个「改行列数之前先退 spec」的前提。
     *
     * GridLayout 每次 measure 都会把不写行列的格子改写成具体下标，于是「在场子视图的最大下标」
     * 就是上一版的列数；这时把 `columnCount` 调小，`Axis.setCount` 当场抛 IllegalArgumentException。
     * 触发路径不用转屏就有：第一轮 `render` 早于第一次 layout，只能按默认 `aspect = 0.7` 估
     * （9 个人 3×3），量到真尺寸那一轮是 0.48（控制条的下 padding 还没生效）→ 2×5。
     * **发起群通话闪退就是这么来的。**
     */
    @Test
    fun `同一批人列数也会变小`() {
        assertEquals("第一轮按默认形状估", 3 to 3, IMGrid.dimensions(9, aspect = 0.7))
        assertEquals("量到真尺寸这一轮", 2 to 5, IMGrid.dimensions(9, aspect = 0.48))
        // 转屏是同一条路，只是方向反过来：行数从 5 掉到 2。
        assertEquals(5 to 2, IMGrid.dimensions(9, aspect = 1.8))
    }

    @Test
    fun `接通之前不许收进悬浮球`() {
        // 拨出中收起来，剩一个不会动的小球挂在那儿：既不知道对方接没接，
        // 也想不起来怎么挂断。所以 minimize 在这两个阶段必须是空操作。
        val outgoing = IMCallViewReducer.outgoing(IMCallViewState(), listOf("bob"), "audio", false)
        assertFalse(IMCallViewReducer.minimize(outgoing).isMinimized)

        val incoming = IMCallViewReducer.incoming(IMCallViewState(), "c-1", "alice", emptyList(), "audio", false)
        assertFalse(IMCallViewReducer.minimize(incoming).isMinimized)

        val connected = IMCallViewReducer.connected(
            IMCallViewReducer.begin(outgoing, "c-1", "r-1", "audio", "caller"),
        )
        assertTrue(IMCallViewReducer.minimize(connected).isMinimized)
        assertFalse(IMCallViewReducer.expand(IMCallViewReducer.minimize(connected)).isMinimized)
    }

    @Test
    fun `通话结束要把小窗展开，否则结束原因没人看见`() {
        val connected = IMCallViewReducer.connected(
            IMCallViewReducer.begin(IMCallViewState(), "c-1", "r-1", "audio", "caller"),
        )
        val minimized = IMCallViewReducer.minimize(connected)
        assertTrue(minimized.isMinimized)

        // 「对方拒绝」「对方忙线」藏在一个 60dp 的球里等于没提示。
        val ended = IMCallViewReducer.ended(minimized, "busy")
        assertFalse("结束时必须退出小窗", ended.isMinimized)
        assertEquals("对方忙线中", ended.statusText)
    }

    @Test
    fun `格子最多九个——含本端`() {
        var state = IMCallViewState()
        repeat(12) { state = IMCallViewReducer.userEnter(state, "u$it") }
        // 远端 8 + 本端 1 = 9。**本端那一格不能被挤出去**。
        assertEquals(IMGrid.MAX_REMOTE_TILES, state.tiles.size)
        assertEquals(IMGrid.MAX_TILES, state.tiles.size + 1)
    }
}
