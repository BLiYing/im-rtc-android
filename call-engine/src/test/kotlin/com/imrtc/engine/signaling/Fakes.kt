package com.imrtc.engine.signaling

import com.imrtc.engine.protocol.IMEnvelope
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMJsonWriter

/**
 * 假调度器：**时间由测试推动**。
 *
 * 信令层最容易错的是时序（握手、心跳、退避、超时），而这些用真定时器测就只能 sleep——
 * 一条 30 秒退避的用例要跑 30 秒，没人会去跑第二次。这里 3 毫秒验完。
 */
internal class FakeScheduler : IMScheduler {

    private class Task(val dueMs: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    private var now = 1_000_000L

    override fun post(task: () -> Unit) {
        tasks += Task(now, task)
        drain()
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit): IMScheduler.Cancellable {
        val entry = Task(now + delayMs, task)
        tasks += entry
        return IMScheduler.Cancellable { entry.cancelled = true }
    }

    override fun nowMs(): Long = now

    override fun shutdown() = tasks.clear()

    /** 把时间往前推，沿途到期的任务按顺序执行（执行中新排的任务也会被带上）。 */
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.dueMs <= target }.minByOrNull { it.dueMs } ?: break
            now = maxOf(now, next.dueMs)
            tasks.remove(next)
            next.block()
        }
        now = target
    }

    private fun drain() {
        while (true) {
            val next = tasks.filter { !it.cancelled && it.dueMs <= now }.minByOrNull { it.dueMs } ?: return
            tasks.remove(next)
            next.block()
        }
    }
}

/** 假传输：记下发出去的帧，让测试手动喂下行数据与关闭事件。 */
internal class FakeTransport : IMTransport {

    var connectCount = 0
        private set

    var closeCount = 0
        private set

    val sent = mutableListOf<IMEnvelope>()
    private var listener: IMTransport.Listener? = null

    override fun connect(url: String, listener: IMTransport.Listener) {
        connectCount++
        this.listener = listener
    }

    override fun send(text: String) {
        sent += IMEnvelope.decode(text)
    }

    override fun close(code: Int, reason: String) {
        closeCount++
    }

    // ── 测试驱动 ──────────────────────────────────────────────────────

    fun open() = listener?.onOpen()

    fun deliver(type: String, reqId: String, data: Map<String, IMJson> = emptyMap()) {
        val text = IMJsonWriter.write(
            IMJson.Obj(
                linkedMapOf(
                    "type" to IMJson.Str(type),
                    "req_id" to IMJson.Str(reqId),
                    "ts" to IMJson.Num(1),
                    "data" to IMJson.Obj(data),
                ),
            ),
        )
        listener?.onText(text)
    }

    fun closed(code: Int, reason: String = "test") = listener?.onClosed(code, reason)

    fun failure(error: Throwable = RuntimeException("boom")) = listener?.onFailure(error)

    /** 最后一条发出去的某类型帧。 */
    fun lastOf(type: String): IMEnvelope? = sent.lastOrNull { it.type == type }

    fun countOf(type: String): Int = sent.count { it.type == type }

    /** 应答刚才那条请求。 */
    fun replyOk(requestType: String, data: Map<String, IMJson> = emptyMap()) {
        val request = lastOf(requestType) ?: error("还没发过 $requestType")
        deliver(requestType + IMEnvelope.OK_SUFFIX, request.reqId, data)
    }

    fun replyError(requestType: String, code: Long, name: String, msg: String = "") {
        val request = lastOf(requestType) ?: error("还没发过 $requestType")
        deliver(
            "sys.error",
            request.reqId,
            mapOf(
                "code" to IMJson.Num(code),
                "name" to IMJson.Str(name),
                "msg" to IMJson.Str(msg),
                "for_type" to IMJson.Str(requestType),
                "retryable" to IMJson.Bool(false),
            ),
        )
    }
}
