package com.imrtc.engine.statemachine

import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMJson

/**
 * 状态机的公共类型。
 *
 * 状态机是**纯函数 reducer**：`(state, input) -> (state, send, emit)`。
 * 不碰网络、不碰 UI、不碰计时器——所以它能被 `docs/conformance` 下的
 * `call_fsm.json` / `room_fsm.json` 逐条驱动，与另外四端跑**同一份**用例，
 * 而且完全不需要设备。这也是 CONVENTIONS §1「statemachine 不许 import android.*」的由来。
 */

/** 状态机要求发出去的一帧（线路形状，snake_case）。 */
internal data class IMOutgoingFrame(
    val type: String,
    val data: Map<String, IMJson> = emptyMap(),
)

/**
 * 状态机要求抛给宿主的一个回调。
 *
 * `args` 的键用**协议的 snake_case 名**，与一致性向量一致；
 * 由门面转成 Kotlin/Java 惯用形式再交给宿主。
 */
internal data class IMEmittedEvent(
    val callback: String,
    val args: Map<String, IMJson> = emptyMap(),
)

/** 驱动状态机的三种输入（与向量的 act / recv / internal 一一对应）。 */
internal sealed interface IMMachineInput {
    /** 宿主调用了 engine 的公开方法。 */
    data class Act(val op: String, val args: Map<String, IMJson> = emptyMap()) : IMMachineInput

    /** 收到一条下行帧。 */
    data class Recv(val type: String, val data: Map<String, IMJson>) : IMMachineInput

    /**
     * engine 内部事件，既不来自信令也不来自宿主（如媒体就绪）。
     *
     * `args` 只有「哪一条被拒了」这类需要带标识的才有：`publish_failed` 的 `cid`、
     * `subscribe_failed` 的 `track_id`（静默失败审计 §A）。
     */
    data class Internal(val name: String, val args: Map<String, IMJson> = emptyMap()) : IMMachineInput
}

/** 一次状态转移的产物。 */
internal data class IMMachineOutput<S>(
    val state: S,
    val send: List<IMOutgoingFrame> = emptyList(),
    val emit: List<IMEmittedEvent> = emptyList(),
)

/**
 * 「当前状态不接受这个操作」的落点：不发帧、只本地抛一条 `onError(INVALID_STATE)`。
 *
 * [CallStateMachine.invalidState] 与 [RoomStateMachine.localReject] 曾经是逐字重复的两份
 * 实现，各自 `ctx` 类型不同（`IMCallContext` / `IMRoomContext`），这里用泛型收成一份。
 */
internal fun <S> invalidStateOutput(ctx: S): IMMachineOutput<S> = IMMachineOutput(
    ctx,
    emit = listOf(
        IMEmittedEvent(
            "onError",
            mapOf(
                "code" to n(IMErrorCode.INVALID_STATE.code.toLong()),
                "name" to s(IMErrorCode.INVALID_STATE.wireName),
            ),
        ),
    ),
)

/**
 * 从线路数据里安全取值的小工具。
 *
 * **缺字段不报错、取默认值**：帧级解码（`FieldCodec`）已经补过默认值了，
 * 这里只是防御性兜底；状态机不该因为一个字段没写就抛异常。
 */
internal object Wire {
    fun str(data: Map<String, IMJson>, key: String): String = (data[key] as? IMJson.Str)?.value ?: ""

    fun num(data: Map<String, IMJson>, key: String): Long = (data[key] as? IMJson.Num)?.value ?: 0L

    fun flag(data: Map<String, IMJson>, key: String): Boolean = (data[key] as? IMJson.Bool)?.value ?: false

    fun strList(data: Map<String, IMJson>, key: String): List<String> =
        ((data[key] as? IMJson.Arr)?.items ?: emptyList()).mapNotNull { (it as? IMJson.Str)?.value }

    fun objects(data: Map<String, IMJson>, key: String): List<Map<String, IMJson>> =
        ((data[key] as? IMJson.Arr)?.items ?: emptyList()).mapNotNull { (it as? IMJson.Obj)?.fields }
}

/** 造 `IMJson` 的简写，让状态机里的帧构造读起来还像帧。 */
internal fun s(value: String): IMJson = IMJson.Str(value)

internal fun n(value: Long): IMJson = IMJson.Num(value)

internal fun b(value: Boolean): IMJson = IMJson.Bool(value)

internal fun arr(values: List<String>): IMJson = IMJson.Arr(values.map { IMJson.Str(it) })
