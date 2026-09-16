package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.signaling.IMScheduler
import com.imrtc.engine.signaling.IMSignalConnection
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMEngineContext
import com.imrtc.engine.statemachine.IMEngineMachine
import com.imrtc.engine.statemachine.IMMachineOutput
import com.imrtc.engine.statemachine.forceEnd

/**
 * [IMCallEngine.forceEnd] 的两段：**调用方线程上直发结束帧**，**engine 线程上落地本地收场**。
 *
 * 从门面拆出来是体量红线（CONVENTIONS §2）；为什么要分两段、为什么不走 engine 线程发帧，
 * 见 [IMCallEngine.forceEnd] 的注释。帧怎么挑是纯函数 `IMEngineMachine.forceEnd`。
 */
internal class IMForceEnd(
    private val scheduler: IMScheduler,
    private val connection: IMSignalConnection,
    /** 状态机的当前快照（门面的 `@Volatile ctx`）。 */
    private val snapshot: () -> IMEngineContext,
    /** 门面唯一的状态落地入口。只在 engine 线程上调。 */
    private val applyOutput: (IMEngineContext, IMMachineOutput<IMEngineContext>) -> Unit,
) {

    /**
     * 在调用方线程上跑：挑帧、直发，再把本地收场排回 engine 线程。
     *
     * `reason` 不给就按此刻状态挑（红键那条）；给了就覆盖——`room.publish` 被拒时
     * `IMCallEngine.onRequestFailed` 传 [IMCallEndReason.ERROR]，见该处注释。
     */
    fun run(reason: IMCallEndReason? = null) {
        val taken = snapshot()
        val plan = IMEngineMachine.forceEnd(taken, scheduler.nowMs(), reason)
        if (plan.emit.isEmpty()) {
            IMRTCLog.i("engine", "强制收场：没有进行中的通话或房间")
            return
        }
        IMRTCLog.w(
            "engine",
            "强制收场 call_id=${taken.call.callId} call_state=${taken.call.state.wire} " +
                "room_id=${taken.room.roomId} room_state=${taken.room.state.wire} " +
                "frames=${plan.send.joinToString(",") { it.type }}",
        )
        if (plan.send.isNotEmpty() && !connection.isConnected) {
            IMRTCLog.w("engine", "强制收场：没有信令连接，结束帧发不出去，只做本地收场")
        } else {
            for (frame in plan.send) connection.fire(frame.type, frame.data)
        }
        scheduler.post { land(taken, reason) }
    }

    /**
     * 在 engine 线程上落地。**只收同一场**：快照可能比 engine 线程晚一拍，这一拍里那通电话要是
     * 已经正常结束、甚至又来了一通新的，照着现在的状态收场就会把新来的那通一声不响地吞掉——
     * 所以先比对 call_id 与 room_id。唯一的例外是拨出中：快照那一刻 invite.ok 还没回来、什么都没发，
     * 这一拍里它回来了（还在 inviting、call_id 到了），那就是同一场，cancel 在这里补上。
     * 其余情况结束帧不重发——[run] 已经发过了。再晚一点回来的 invite.ok 由通话机的 idle 分支补。
     *
     * `reason` 原样带过来：直发阶段与落地阶段必须算出同一个结束原因，否则日志与回调对不上。
     */
    private fun land(taken: IMEngineContext, reason: IMCallEndReason?) {
        val current = snapshot()
        val inviteLanded = taken.call.callId.isEmpty() &&
            current.call.state == IMCallState.INVITING && current.call.callId.isNotEmpty()
        val sameOne = current.call.callId == taken.call.callId && current.room.roomId == taken.room.roomId
        if (!inviteLanded && !sameOne) {
            IMRTCLog.i("engine", "强制收场落地时那一场已经不在了，跳过本地收场 call_id=${taken.call.callId}")
            return
        }
        val ended = IMEngineMachine.forceEnd(current, scheduler.nowMs(), reason)
        if (inviteLanded) for (frame in ended.send) connection.fire(frame.type, frame.data)
        applyOutput(current, ended.copy(send = emptyList()))
    }
}
