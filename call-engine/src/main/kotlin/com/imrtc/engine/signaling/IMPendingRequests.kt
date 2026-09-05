package com.imrtc.engine.signaling

import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMJson

/**
 * 按 `req_id` 配对请求与应答（协议 §2.2）。
 *
 * 三条规矩：
 * 1. **每个请求恰好有一条应答**（`xxx.ok` 或 `sys.error`）。
 * 2. **迟到的应答必须能容忍**：超时之后才回来的那一条，丢掉即可，**不得崩溃**。
 * 3. 请求 10 秒无应答 → `2004 signaling_timeout`。这个码**不上线路**，只抛给宿主。
 */
internal class IMPendingRequests(
    private val scheduler: IMScheduler,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** 应答回调：成功给 data，失败给错误码与说明。 */
    fun interface Callback {
        fun onResult(ok: Boolean, data: Map<String, IMJson>, code: IMErrorCode?, message: String)
    }

    private class Entry(val type: String, val callback: Callback, val timer: IMScheduler.Cancellable)

    private val entries = LinkedHashMap<String, Entry>()
    private var sequence = 0L

    /** 生成一个新的 req_id。带前缀是为了在服务端日志里一眼看出是客户端发的。 */
    fun nextReqId(): String {
        sequence++
        return "a-$sequence"
    }

    fun register(reqId: String, type: String, callback: Callback) {
        val timer = scheduler.postDelayed(timeoutMs) {
            entries.remove(reqId)?.callback?.onResult(
                false,
                emptyMap(),
                IMErrorCode.SIGNALING_TIMEOUT,
                "$type 等了 ${timeoutMs}ms 没有应答",
            )
        }
        entries[reqId] = Entry(type, callback, timer)
    }

    /** 成功应答。返回 false 表示这条应答**迟到了**（对应的请求已超时），调用方丢掉即可。 */
    fun resolve(reqId: String, data: Map<String, IMJson>): Boolean {
        val entry = entries.remove(reqId) ?: return false
        entry.timer.cancel()
        entry.callback.onResult(true, data, null, "")
        return true
    }

    /** 失败应答（`sys.error`）。 */
    fun reject(reqId: String, code: IMErrorCode, message: String): Boolean {
        val entry = entries.remove(reqId) ?: return false
        entry.timer.cancel()
        entry.callback.onResult(false, emptyMap(), code, message)
        return true
    }

    /** 这条请求发的是什么帧。日志里用，报错时能说清「哪个请求超时了」。 */
    fun typeOf(reqId: String): String? = entries[reqId]?.type

    /**
     * 连接断了：把在飞的请求全部以指定错误结掉。
     *
     * **不能就这么留着**——那些回调永远不会被调用，宿主会一直等下去。
     */
    fun failAll(code: IMErrorCode, message: String) {
        val snapshot = entries.values.toList()
        entries.clear()
        for (entry in snapshot) {
            entry.timer.cancel()
            entry.callback.onResult(false, emptyMap(), code, message)
        }
    }

    val size: Int get() = entries.size

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
