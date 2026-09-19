package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.statemachine.IMCallExit
import com.imrtc.engine.statemachine.IMCallState
import com.imrtc.engine.statemachine.IMEngineContext
import com.imrtc.engine.statemachine.IMMachineInput
import com.imrtc.engine.statemachine.IMOutgoingFrame
import com.imrtc.engine.statemachine.IMRoomState

/**
 * `IMCallEngine.onRequestFailed` 的判断表：请求被服务端拒了（或没连接、超时）之后该做什么。
 *
 * 拆成单独文件是体量红线（CONVENTIONS §2）——`IMCallEngine.kt` 已经踩线，这段判断
 * 本来也和门面的「核心循环」是两个关注点，与 [IMCallInvite] / [IMNegotiationFrames] 同类拆分。
 *
 * **必须让状态机退回 idle**，否则会卡在中间态：呼叫失败却停在 inviting，界面上
 * 「正在呼叫…」转个不停，之后每次挂断都发向一个不存在的 call（1401），永远退不出去。
 * 进房失败同理——不退的话这台 Engine 之后再也进不了任何房间。
 *
 * **`room.publish` / `room.subscribe` 原先不在这张表里**（静默失败审计 §A）：那条轨道
 * 永远停在 `publishing`——`publish.ok` 不来，pub offer 永不产出，上行从未协商。
 * 界面显示已接通、计时器在走、按钮显示没静音，**对方全程听不见看不见，零提示**。
 * 2026-09-16 拍板（四端一致，参考 Web 的 `frameLoop.ts` `rollback` 表）：
 * 1. **通话里被服务端拒了，直接结束本端通话**（reason=error）：留在通话里只报错也不够——
 *    Kit 并不展示这类错误，而服务端会拒的几种情形（房间已不在、同一路重复发布）
 *    重试都救不回来。走 forceEnd 是因为它不排队、不等服务端、callEnd 只抛一次。
 * 2. **没有通话（会议房）且被服务端拒了**，只摘掉那一条 `publishing`，不收场、不额外抛回调、不离房。
 * `room.subscribe` 被拒**从不收场**，只摘 `subscribing` 那条记账——最常见的 1301
 * （`track_not_found`）是订阅与对方 `track_unpublished` 赛跑输了，通话本身没事。
 *
 * **`room.publish` 没等到应答不算被拒（2026-09-18 改，四端一致）。** 原先这张表把「服务端
 * 真拒了」与「这一问根本没送到」混为一谈，真机打了脸：18:18:39 `room.publish` 请求超时，
 * 整通电话被本端判成 `reason=error` 收场，而 **9 秒后连接就回来了、会话也在恢复窗口内
 * resume 成功**——本来能接着打的一通被自己判了死刑。[UNANSWERED_CODES] 那三个码
 * （2003 network_unreachable / 2004 signaling_timeout / 2007 not_logged_in）都只说明
 * 本端此刻与服务端不通，**不是服务端的答复**；连接回来之后同一问多半就成了。**不分通话
 * 还是会议房**，一律发 internal `publish_deferred`，把这一路挂起来等重连
 * （见 `RoomStateMachine.kt` 的 `deferPublish`），恢复之后原样补发。真连不回来的话
 * 连接层的恢复窗口倒计时照样会把通话收场，不需要这里抢着下手。
 *
 * **退出类被拒也要本地收场**（2.0.0，ACTION_RESULT_DESIGN D2）：用户按的是「结束」，服务端拒了
 * （最常见的是通话已经结束 1402 / 1401）、超时或根本没发出去，都不该让界面停在通话里。
 * `call.hangup` / `call.reject` / `call.cancel` 与强制收场同一份收场计算，只是不再发帧；
 * `room.leave` 先走 `leave_failed`，房间还没回 idle（等应答期间断线进了 reconnecting）且没有通话时同样本地收场。
 *
 * 错误本身交给谁（调用方还是 `onError`）由门面决定，这里只管状态。
 */
internal object IMRequestFailures {

    /**
     * 「这一问没能送到 / 没等到回话」的那几个码——**不是服务端的答复**。
     *
     * 与它们相对的是服务端真回了一个 `err`（1xxx）：那才叫被拒，重试救不回来。
     * 这三个都只说明本端与服务端此刻不通，而连接回来之后同一问多半就成了，
     * 所以 `room.publish` 走 `publish_deferred` 挂起等重连，而不是把通话判死刑。
     */
    private val UNANSWERED_CODES = setOf(
        IMErrorCode.NETWORK_UNREACHABLE, IMErrorCode.SIGNALING_TIMEOUT, IMErrorCode.NOT_LOGGED_IN,
    )

    /**
     * @param ctx 状态机此刻的快照，只用来判断「有没有通话」，不在这里改。
     * @param frame 那条被拒 / 没送到的请求帧。
     * @param code 请求失败的错误码；`null` 是「连认都认不出的码」，一律按被拒处理。
     * @param input 门面唯一的状态机入口（`IMCallEngine.input`）。
     * @param forceEnd 门面的强制收场（`IMForceEnd.run`），带上覆盖的结束原因。
     * @param snapshot 状态机此刻的快照（回滚之后再看一眼用）。
     * @param endLocally 按此刻状态本地收场、不发帧。
     */
    fun handle(
        ctx: IMEngineContext,
        frame: IMOutgoingFrame,
        code: IMErrorCode?,
        input: (IMMachineInput) -> Unit,
        forceEnd: (IMCallEndReason) -> Unit,
        snapshot: () -> IMEngineContext,
        endLocally: () -> Unit,
    ) {
        when (frame.type) {
            IMFrameType.CALL_INVITE, IMFrameType.CALL_ACCEPT, IMFrameType.CALL_JOIN ->
                input(IMMachineInput.Internal("call_failed"))
            IMFrameType.ROOM_JOIN -> input(IMMachineInput.Internal("join_failed"))
            // **离房被拒也要退回 idle**：服务端在「会话已不在房间里」时回 1203，
            // 而那正说明我们已经不在房里了。不接这一条的话房间永久停在 leaving——
            // 媒体停不掉（摄像头与前台服务一直开着），之后 join 也被本地拒，
            // 这台 Engine 除非 logout 否则再也进不了房。
            IMFrameType.ROOM_LEAVE -> {
                input(IMMachineInput.Internal("leave_failed"))
                val now = snapshot()
                if (now.room.state != IMRoomState.IDLE && now.call.state == IMCallState.IDLE) endLocally()
            }
            in IMCallExit.allFrameTypes -> endLocally()
            IMFrameType.ROOM_PUBLISH -> {
                if (code != null && code in UNANSWERED_CODES) {
                    // 没等到应答：挂起等重连，通话与会议房同一条路，不分。
                    IMRTCLog.w("engine", "发布没等到应答，挂起等重连 call_id=${ctx.call.callId} code=${code.code}")
                    input(IMMachineInput.Internal("publish_deferred", frame.data))
                } else if (ctx.call.state != IMCallState.IDLE) {
                    IMRTCLog.w("engine", "发布被拒，结束本端通话 call_id=${ctx.call.callId}")
                    forceEnd(IMCallEndReason.ERROR)
                } else {
                    input(
                        IMMachineInput.Internal(
                            "publish_failed",
                            mapOf("cid" to IMJson.Str(frame.data.text("cid"))),
                        ),
                    )
                }
            }
            IMFrameType.ROOM_SUBSCRIBE -> input(
                IMMachineInput.Internal(
                    "subscribe_failed",
                    mapOf("track_id" to IMJson.Str(frame.data.text("track_id"))),
                ),
            )
        }
    }
}
