package com.neoproc.neoproccamx.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/**
 * Runs mutable work through one drain while coalescing queued requests to the newest value.
 *
 * Callers that need an ordering barrier can await the request. Their completion is retained when a
 * newer request is merged, so coalescing can never strand a waiter or let it pass before execution.
 */
internal class LatestWinsSerialWorkCoordinator<T>(
    private val merge: (pending: T, incoming: T) -> T,
    private val schedule: (drain: () -> Unit) -> Unit,
    private val execute: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private data class PendingWork<T>(
        val value: T,
        val waiters: List<CompletableDeferred<Unit>>
    )

    private val pending = AtomicReference<PendingWork<T>?>(null)
    private val drainScheduled = AtomicBoolean(false)

    fun submit(value: T) {
        enqueue(value, waiter = null)
    }

    suspend fun submitAndAwait(value: T) {
        val waiter = CompletableDeferred<Unit>()
        enqueue(value, waiter)
        waiter.await()
    }

    private fun enqueue(value: T, waiter: CompletableDeferred<Unit>?) {
        pending.getAndUpdate { current ->
            PendingWork(
                value = if (current == null) value else merge(current.value, value),
                waiters = when {
                    current == null && waiter == null -> emptyList()
                    current == null -> listOf(requireNotNull(waiter))
                    waiter == null -> current.waiters
                    else -> current.waiters + waiter
                }
            )
        }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        try {
            schedule(::drain)
        } catch (failure: Throwable) {
            drainScheduled.set(false)
            pending.getAndSet(null)?.fail(failure)
            notifyFailure(failure)
            if (pending.get() != null) scheduleDrain()
            if (failure is Error) throw failure
        }
    }

    private fun drain() {
        try {
            while (true) {
                val work = pending.getAndSet(null) ?: break
                try {
                    execute(work.value)
                    work.waiters.forEach { it.complete(Unit) }
                } catch (failure: Throwable) {
                    work.fail(failure)
                    notifyFailure(failure)
                    if (failure is Error) throw failure
                }
            }
        } finally {
            drainScheduled.set(false)
            if (pending.get() != null) scheduleDrain()
        }
    }

    private fun PendingWork<T>.fail(failure: Throwable) {
        waiters.forEach { it.completeExceptionally(failure) }
    }

    private fun notifyFailure(failure: Throwable) {
        runCatching { onFailure(failure) }
    }
}
