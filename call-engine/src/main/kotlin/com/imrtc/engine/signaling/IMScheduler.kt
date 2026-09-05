package com.imrtc.engine.signaling

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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

    /** 延迟执行；返回的句柄可取消。 */
    fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable

    fun nowMs(): Long

    /** 停掉调度器；之后再 post 一律忽略。 */
    fun shutdown()
}

/** 真实现：一条单线程的 `ScheduledExecutorService`。 */
internal class IMExecutorScheduler : IMScheduler {

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "com.imrtc.engine").apply { isDaemon = true }
        }

    override fun post(task: () -> Unit) {
        if (executor.isShutdown) return
        executor.execute(guarded(task))
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit): IMScheduler.Cancellable {
        if (executor.isShutdown) return IMScheduler.Cancellable {}
        val future: ScheduledFuture<*> =
            executor.schedule(guarded(task), delayMs, TimeUnit.MILLISECONDS)
        return IMScheduler.Cancellable { future.cancel(false) }
    }

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun shutdown() {
        executor.shutdownNow()
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
