package com.imrtc.engine.signaling

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Engine 的**单线程**调度器：所有状态变更都在这一条线程上跑。
 *
 * 为什么是执行器而不是协程：SDK 是要塞进别人 App 的，少一个 `kotlinx-coroutines` 依赖
 * 就少一份版本冲突；而且公开面本来就不许出现 `suspend`（CONVENTIONS §4），
 * 内部再引一套结构化并发换不来什么。状态机是纯函数，一条线程足够。
 *
 * 抽成接口是为了**测时序**：假实现里时间由测试推动，可以在 3 毫秒内验完 30 秒的退避。
 */
internal interface IMScheduler {

    fun interface Cancellable {
        fun cancel()
    }

    /** 丢到 engine 线程上执行。 */
    fun post(task: () -> Unit)

    /**
     * 同 [post]，但**说清楚收没收下**：调度器已经停了就返回 false，任务不会执行。
     *
     * 发起类方法靠它兑现「结果恰好回一次」——没收下的那次调用要当场以 `2005` 结掉（ACTION_RESULT_DESIGN R5 / R6），
     * 否则回调永远不来。
     */
    fun tryPost(task: () -> Unit): Boolean {
        post(task)
        return true
    }

    /** 延迟执行；返回的句柄可取消。 */
    fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable

    fun nowMs(): Long

    /** 停掉调度器；之后再 post 一律忽略。**已经排进队的立即任务照常跑完**，延时任务作废。 */
    fun shutdown()
}

/** 真实现：一条单线程的 `ScheduledExecutorService`。 */
internal class IMExecutorScheduler : IMScheduler {

    /*
      **停下时已经排进队的立即任务要跑完**（`shutdown` 而不是 `shutdownNow`）：宿主在 destroy 之前一瞬间调的
      `hangup(cb)` 可能正排在销毁任务后面——丢掉它，那个回调就永远不来（R5）。跑的时候连接已经停了，
      它会以 `2007` 结掉。延时任务（心跳、重连、超时）一律作废，免得销毁后还在转。
    */
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "com.imrtc.engine").apply { isDaemon = true }
    }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    override fun post(task: () -> Unit) {
        tryPost(task)
    }

    /**
     * 「查 isShutdown → execute」之间线程可能正好被停掉，`execute` 会抛 [RejectedExecutionException]——
     * 原先不接，从宿主线程上冒出去就是一次崩溃（CLIENT_PARITY `[^destroy]`）。
     */
    override fun tryPost(task: () -> Unit): Boolean {
        if (executor.isShutdown) return false
        return try {
            executor.execute(guarded(task))
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit): IMScheduler.Cancellable {
        if (executor.isShutdown) return IMScheduler.Cancellable {}
        val future: ScheduledFuture<*> = try {
            executor.schedule(guarded(task), delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            return IMScheduler.Cancellable {}
        }
        return IMScheduler.Cancellable { future.cancel(false) }
    }

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun shutdown() {
        executor.shutdown()
    }

    /**
     * 吞掉任务里抛出的异常。
     *
     * `ScheduledExecutorService` 有个很坑的默认行为：任务抛异常时**线程静默死掉**，
     * 之后所有 post 都不再执行，而且没有任何报错。信令线程死了 = 整个 Engine 静止，
     * 症状是「什么都不响应但也不崩」。宁可记一条日志继续跑。
     */
    private fun guarded(task: () -> Unit): Runnable = Runnable {
        try {
            task()
        } catch (t: Throwable) {
            com.imrtc.engine.log.IMRTCLog.e(
                "engine",
                "engine 线程上的任务抛了异常：${t.javaClass.simpleName} ${t.message}",
            )
        }
    }
}
