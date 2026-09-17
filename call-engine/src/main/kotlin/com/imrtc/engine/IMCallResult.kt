package com.imrtc.engine

import com.imrtc.engine.protocol.IMJson

/**
 * **一次宿主调用的结算**：收集这次调用直接发出的那几帧的应答，结果恰好交付一次（ACTION_RESULT_DESIGN R1 / R5）。
 *
 * 除 [finish] 外都只在 engine 线程上调。交付切主线程；没传回调时失败退回 `onError`（R7）。
 *
 * # 什么时候算完
 *
 * 门面把这次调用喂进状态机、帧都交出去之后调 [seal]；此后每一帧的应答 / 失败回来就 [flush] 一次，
 * 在途帧数归零即交付。**状态事件先于结果**：请求失败时门面先跑回滚（`onCallEnd(error)` 之类），再 [flush]——
 * 两边都经主线程投递，先投的先到。
 *
 * 同一次调用发了几帧的，调用方拿第一个失败，其余的由门面照旧发 `onError`（见 [fail] 的返回值）。
 */
internal class IMCallResult<T>(
    private val dispatcher: IMEventDispatcher,
    private val callback: IMResultCallback<T>?,
    private val valueOf: (Map<String, IMJson>) -> T,
) {
    private var pending = 0
    private var sealed = false
    private var done = false
    private var error: IMRTCError? = null
    private var reply: Map<String, IMJson> = emptyMap()

    /** 一帧直接帧交给了信令连接，等它的应答。**必须在 `request` 之前调**：没连接时回调是同步回来的。 */
    fun expect() {
        pending++
    }

    /** 那一帧收到了 `.ok`（状态机已经喂过了）。 */
    fun succeed(data: Map<String, IMJson>) {
        pending--
        reply = data
        flush()
    }

    /**
     * 那一帧失败了：记下、先不交付（门面接着要跑回滚）。
     *
     * @return false 表示这次调用已经记过一个失败——这一个没人接，调用方要自己发 `onError`。
     */
    fun fail(e: IMRTCError): Boolean {
        pending--
        if (error != null) return false
        error = e
        return true
    }

    /** 状态机就地拒掉了这次调用。 */
    fun reject(e: IMRTCError) {
        if (error == null) error = e
    }

    /** 这次调用的帧都交出去了，之后不会再有新的直接帧。 */
    fun seal() {
        sealed = true
        flush()
    }

    /** 条件满足（已封口、没有在途帧）就交付。 */
    fun flush() {
        if (done || !sealed || pending > 0) return
        done = true
        deliver(error)
    }

    /**
     * 根本没进 engine 线程就有了结论（destroy 之后、调度器拒收、参数本地校验不过、登录的握手结论）：当场交付。
     * 可以在调用方线程上调——那时这次调用的任务不会再跑，不存在并发。
     */
    fun finish(e: IMRTCError?) {
        if (done) return
        if (e != null && error == null) error = e
        sealed = true
        pending = 0
        flush()
    }

    private fun deliver(e: IMRTCError?) {
        val cb = callback
        if (cb == null) {
            if (e != null) dispatcher.error(e)
            return
        }
        val value = if (e == null) valueOf(reply) else null
        dispatcher.onMainThread { cb.onResult(value, e) }
    }

    companion object {
        /** 没有值的结果（除 `call` 以外的发起类方法）。 */
        val UNIT: (Map<String, IMJson>) -> Unit = {}
    }
}
