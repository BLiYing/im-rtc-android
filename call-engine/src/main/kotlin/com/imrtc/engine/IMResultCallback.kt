package com.imrtc.engine

import com.imrtc.engine.protocol.IMErrorCode

/**
 * **发起类方法的结果**（2.0.0 起，server `docs/design/ACTION_RESULT_DESIGN.md`）。
 *
 * - 成功时 `error == null`，`value` 是这次调用的值（`call` 给 callId，其余是 [Unit]）；
 * - 失败时 `value == null`，`error` 是本地拒绝（`2005` / `1004` …）、服务端拒绝、请求超时或等应答期间断线。
 *
 * **主线程回调，每次调用恰好一次。** 成功 = 这次调用**直接发出的那一帧**收到了 `xxx.ok`；
 * 引擎随后自动发的连锁帧（接听之后的进房等）失败找不到调用方，走 [IMCallEngineListener.onError]。
 *
 * **不传回调也行**：那时失败退回 [IMCallEngineListener.onError]，免得「没接回调」变成静默丢失。
 * 传了回调，同一个失败就**不再**发 `onError`——一次失败只从一个出口报。
 *
 * 单方法接口，Kotlin 与 Java 都能直接写 lambda：`engine.hangup { _, error -> … }`。
 */
fun interface IMResultCallback<T> {
    fun onResult(value: T?, error: IMRTCError?)
}

/**
 * 调用结果里的错误。
 *
 * @property code 五仓共用错误码表里的码。
 * @property name 错误码的机读名（snake_case，如 `invite_denied`）。
 * @property message 给开发者看的说明，**别直接显示给用户**——界面文案按 [code] 查。
 * @property forType 出错的请求帧类型（如 `call.join`）；本地就拒掉、没有对应帧时为空串。
 */
data class IMRTCError(
    val code: Int,
    val name: String,
    val message: String,
    val forType: String,
) {
    internal companion object {
        /** 由错误码表里的码造；本端不认识的码（`code == null`）按 `internal` 算。 */
        fun of(code: IMErrorCode?, message: String, forType: String): IMRTCError {
            val resolved = code ?: IMErrorCode.INTERNAL
            return IMRTCError(resolved.code, resolved.wireName, message, forType)
        }

        /** `2005 invalid_state`：状态机本地拒绝、engine 已销毁、没有媒体适配器。 */
        fun invalidState(message: String, forType: String = ""): IMRTCError =
            of(IMErrorCode.INVALID_STATE, message, forType)
    }
}
