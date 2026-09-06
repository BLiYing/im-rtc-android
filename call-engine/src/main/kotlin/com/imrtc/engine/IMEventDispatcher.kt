package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.statemachine.IMEmittedEvent

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
            "onKickedOut" -> onMain { listener.onKickedOut() }
            "onError" -> onMain {
                listener.onError(args.num("code").toInt(), args.str("name"))
            }

            "onCallReceived" -> onMain {
                listener.onCallReceived(
                    args.str("call_id"),
                    args.str("caller"),
                    args.strs("callee_ids"),
                    args.str("media_type"),
                    args.flag("is_group"),
                )
            }
            "onCallBegin" -> onMain {
                listener.onCallBegin(
                    args.str("call_id"),
                    args.str("room_id"),
                    args.str("media_type"),
                    args.str("role"),
                )
            }
            "onCallEnd" -> onMain {
                listener.onCallEnd(
                    args.str("call_id"),
                    args.str("reason"),
                    args.num("duration_sec"),
                    args.str("ended_by"),
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

            "onRoomJoined" -> onMain { listener.onRoomJoined(args.str("room_id")) }
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
     */
    fun disconnected(code: Int, reason: String) = onMain { listener.onDisconnected(code, reason) }

    /** 媒体层直接抛的两个，不经过状态机。 */
    fun firstVideoFrame(uid: String) = onMain { listener.onFirstVideoFrame(uid) }

    fun error(code: Int, message: String) = onMain { listener.onError(code, message) }

    fun mediaTypeChanged(callId: String, from: String, to: String) =
        onMain { listener.onCallMediaTypeChanged(callId, from, to) }

    private fun onMain(block: () -> Unit) = main.run(block)
}

private fun Map<String, IMJson>.str(key: String) = (this[key] as? IMJson.Str)?.value ?: ""

private fun Map<String, IMJson>.num(key: String) = (this[key] as? IMJson.Num)?.value ?: 0L

private fun Map<String, IMJson>.flag(key: String) = (this[key] as? IMJson.Bool)?.value ?: false

private fun Map<String, IMJson>.strs(key: String): List<String> =
    ((this[key] as? IMJson.Arr)?.items ?: emptyList()).mapNotNull { (it as? IMJson.Str)?.value }

private fun Map<String, IMJson>.objects(key: String): List<Map<String, IMJson>> =
    ((this[key] as? IMJson.Arr)?.items ?: emptyList()).mapNotNull { (it as? IMJson.Obj)?.fields }
