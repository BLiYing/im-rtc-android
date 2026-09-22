package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.statemachine.IMEmittedEvent
import com.imrtc.engine.statemachine.Wire

/**
 * 把状态机吐出来的 [IMEmittedEvent]（线路形状、snake_case）翻译成 [IMCallEngineListener]
 * 的方法调用，并**切到主线程**再交给宿主。
 *
 * 为什么要有这么一层：状态机的产物是数据（回调名 + 参数表），这样它才能被一致性向量驱动；
 * 而宿主要的是类型化的方法。翻译放在这里，两边都干净。
 *
 * **回调名写错在这里是静默失败**——状态机吐一个没人认识的名字，宿主什么都收不到，
 * 界面不动但日志干净。所以 [dispatch] 的 else 分支必须记一条日志，不许直接 return。
 */
internal class IMEventDispatcher(
    private val listener: IMCallEngineListener,
    private val main: IMMainThread,
) {

    fun dispatchAll(events: List<IMEmittedEvent>) {
        for (event in events) dispatch(event)
    }

    fun dispatch(event: IMEmittedEvent) {
        val args = event.args
        when (event.callback) {
            "onConnected" -> onMain {
                listener.onConnected(args.str("session_id"), args.flag("resumed"))
            }
            // 状态机那份 onDisconnected 不带关闭码——码由连接层独占上报（见 IMSignalConnection）。
            // 这里刻意不派发它，免得宿主收到两条、其中一条还是假的。
            "onDisconnected" -> Unit
            /*
              状态机那份 onKickedOut 也不带原因——原因只有连接层知道（它才看得见关闭码，
              而且「鉴权失败到顶」复用了同一个 ws_closed_4403 内部事件）。
              与上面的 onDisconnected 同一条理由：由连接层独占上报，这里刻意不派发，
              免得宿主收到两条、其中一条还没有 reason。
            */
            "onKickedOut" -> Unit

            "onCallReceived" -> onMain {
                listener.onCallReceived(
                    args.str("call_id"),
                    args.str("caller"),
                    args.str("inviter"),
                    args.strs("callee_ids"),
                    args.strs("joined_ids"),
                    args.str("media_type"),
                    args.flag("is_group"),
                    args.str("chat_group_id"),
                    args.str("user_data"),
                )
            }
            "onCallBegin" -> onMain {
                listener.onCallBegin(
                    args.str("call_id"),
                    args.str("room_id"),
                    args.str("media_type"),
                    args.flag("is_group"),
                    args.str("role"),
                    args.str("caller"),
                    args.str("chat_group_id"),
                    args.str("user_data"),
                )
            }
            "onCallEnd" -> onMain {
                listener.onCallEnd(
                    args.str("call_id"),
                    IMCallEndReason.from(args.str("reason")),
                    args.num("duration_sec"),
                    args.str("ended_by"),
                )
            }
            "onCallSummary" -> onMain {
                listener.onCallSummary(
                    IMCallSummary(
                        callId = args.str("call_id"),
                        reason = IMCallEndReason.from(args.str("reason")),
                        durationSec = args.num("duration_sec"),
                        endedBy = args.str("ended_by"),
                        mediaType = args.str("media_type"),
                        isGroup = args.flag("is_group"),
                        chatGroupId = args.str("chat_group_id"),
                        caller = args.str("caller"),
                        role = args.str("role"),
                        peer = args.str("peer"),
                        userData = args.str("user_data"),
                    ),
                )
            }
            "onCallCancelled" -> onMain { listener.onCallCancelled(args.str("by")) }
            "onCallRejected" -> onMain { listener.onCallRejected(args.str("uid")) }
            "onCallBusy" -> onMain { listener.onCallBusy(args.str("uid")) }
            "onCallNoAnswer" -> onMain { listener.onCallNoAnswer(args.str("uid")) }
            "onCallMissed" -> onMain {
                listener.onCallMissed(args.str("call_id"), args.str("caller"), args.str("reason"))
            }
            "onHandledOnOtherDevice" -> onMain {
                listener.onHandledOnOtherDevice(args.str("call_id"), args.str("action"))
            }

            "onUserEnter" -> onMain { listener.onUserEnter(args.str("uid")) }
            "onUserLeave" -> onMain { listener.onUserLeave(args.str("uid")) }
            "onUserRinging" -> onMain { listener.onUserRinging(args.str("uid")) }
            "onUserAccept" -> onMain { listener.onUserAccept(args.str("uid")) }
            "onUserReject" -> onMain { listener.onUserReject(args.str("uid")) }
            "onUserNoResponse" -> onMain { listener.onUserNoResponse(args.str("uid")) }
            "onUserAudioAvailable" -> onMain {
                listener.onUserAudioAvailable(args.str("uid"), args.flag("available"))
            }
            "onUserVideoAvailable" -> onMain {
                listener.onUserVideoAvailable(args.str("uid"), args.flag("available"))
            }

            "onActiveSpeakers" -> {
                val speakers = args.objects("speakers").map {
                    IMSpeaker(it.str("uid"), it.num("volume").toInt())
                }
                onMain { listener.onActiveSpeakers(speakers) }
            }
            "onNetworkQuality" -> {
                val entries = args.objects("entries").map {
                    IMNetworkQuality(it.str("uid"), it.num("level").toInt())
                }
                onMain { listener.onNetworkQuality(entries) }
            }

            "onRoomJoined" -> onMain { listener.onRoomJoined(args.str("room_id"), args.strs("uids")) }
            "onRoomLeft" -> onMain { listener.onRoomLeft(args.str("room_id")) }
            "onRoomClosed" -> onMain {
                listener.onRoomClosed(args.str("room_id"), args.str("reason"))
            }

            else -> IMRTCLog.e(
                "engine",
                "状态机吐出了一个没人认识的回调名：${event.callback}——回调表少了一项，补表",
            )
        }
    }

    /**
     * 连接断开。**关闭码只从这里出**——状态机那份 onDisconnected 不带码，
     * 混着报会出现「假的 4403」，宿主想数重连次数就数不对。
     *
     * `willReconnect` 由连接层当场裁决（见 `IMSignalConnection.handleClosed`），
     * 不是这里猜的。
     */
    fun disconnected(code: Int, willReconnect: Boolean) = onMain { listener.onDisconnected(code, willReconnect) }

    fun kickedOut(reason: IMKickedOutReason) = onMain { listener.onKickedOut(reason) }

    fun tokenWillExpire(expiresAtMs: Long) = onMain { listener.onTokenWillExpire(expiresAtMs) }

    /** 媒体层直接抛的，不经过状态机。 */
    fun firstVideoFrame(uid: String, trackId: String) = onMain { listener.onFirstVideoFrame(uid, trackId) }

    fun audioRoutesChanged(routes: List<IMAudioRoute>, current: IMAudioRoute?) =
        onMain { listener.onAudioRoutesChanged(routes, current) }

    /**
     * **找不到调用方**的错误（或调用方没传回调时的退回，R7）。`name` 从错误码表按 `code` 反查；
     * 查不到（未来新码、本端还没升级）就给 `unknown`。
     */
    fun error(code: Int, message: String, forType: String = "") =
        onMain { listener.onError(code, IMErrorCode.fromCode(code)?.wireName ?: "unknown", message, forType) }

    fun error(e: IMRTCError) = onMain { listener.onError(e.code, e.name, e.message, e.forType) }

    /** 调用结果也走主线程，与回调同一条队列——状态事件先投的先到（见 [IMCallResult]）。 */
    fun onMainThread(block: () -> Unit) = onMain(block)

    private fun onMain(block: () -> Unit) = main.run(block)
}

// 四个都是「从线路 Map 里安全取值」的薄包装，唯一实现在 [Wire]（statemachine 包）——
// 这里只是保留原有的 `args.str("key")` 调用写法，不重复兜底值。
private fun Map<String, IMJson>.str(key: String) = Wire.str(this, key)

private fun Map<String, IMJson>.num(key: String) = Wire.num(this, key)

private fun Map<String, IMJson>.flag(key: String) = Wire.flag(this, key)

private fun Map<String, IMJson>.strs(key: String) = Wire.strList(this, key)

private fun Map<String, IMJson>.objects(key: String) = Wire.objects(this, key)
