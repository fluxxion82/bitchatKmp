package com.bitchat.embedded

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext

/**
 * A coroutine dispatcher that queues tasks for synchronous execution.
 *
 * All dispatched tasks are queued and only executed when flush() is called.
 * This ensures all Compose operations happen on the same thread as rendering,
 * preventing "Detected multithreaded access to SnapshotStateObserver" crashes.
 *
 * This is similar to Compose's internal FlushCoroutineDispatcher but accessible
 * for use in embedded scenarios where we control the main loop.
 */
@OptIn(InternalCoroutinesApi::class)
class FlushCoroutineDispatcher : CoroutineDispatcher(), Delay {
    private val tasks = ArrayDeque<Runnable>()
    private val tasksLock = SpinLock()
    private val delayedTasks = mutableListOf<DelayedTask>()
    private val delayedTasksLock = SpinLock()

    private data class DelayedTask(
        val timeMillis: Long,
        val scheduledAt: Long,
        val continuation: CancellableContinuation<Unit>,
    )

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasksLock.withLock {
            tasks.add(block)
        }
    }

    /**
     * Execute all pending tasks synchronously on the current thread.
     * Also processes any delayed tasks whose time has elapsed.
     */
    fun flush() {
        val now = currentTimeMillis()

        // Process delayed tasks that are ready
        val readyTasks = delayedTasksLock.withLock {
            val ready = delayedTasks.filter { now >= it.scheduledAt + it.timeMillis }
            delayedTasks.removeAll(ready.toSet())
            ready
        }
        readyTasks.forEach { task ->
            task.continuation.resumeWith(Result.success(Unit))
        }

        // Process all immediate tasks (including any added by delayed task continuations)
        while (true) {
            val task = tasksLock.withLock { tasks.removeFirstOrNull() } ?: break
            task.run()
        }
    }

    /**
     * Check if there are pending tasks.
     */
    fun hasPendingTasks(): Boolean {
        val now = currentTimeMillis()
        return tasksLock.withLock { tasks.isNotEmpty() } ||
            delayedTasksLock.withLock { delayedTasks.any { now >= it.scheduledAt + it.timeMillis } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val task = DelayedTask(timeMillis, currentTimeMillis(), continuation)
        delayedTasksLock.withLock {
            delayedTasks.add(task)
        }
        continuation.invokeOnCancellation {
            delayedTasksLock.withLock {
                delayedTasks.remove(task)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun currentTimeMillis(): Long {
        return memScoped {
            val ts = alloc<platform.posix.timespec>()
            platform.posix.clock_gettime(platform.posix.CLOCK_MONOTONIC, ts.ptr)
            ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
        }
    }
}

/**
 * Simple spin lock for Kotlin/Native synchronization.
 * Uses atomic compare-and-swap for low-contention scenarios.
 */
private class SpinLock {
    private val locked = AtomicInt(0)

    inline fun <T> withLock(block: () -> T): T {
        // Spin until we acquire the lock
        while (!locked.compareAndSet(0, 1)) {
            // Spin
        }
        return try {
            block()
        } finally {
            locked.value = 0
        }
    }
}
