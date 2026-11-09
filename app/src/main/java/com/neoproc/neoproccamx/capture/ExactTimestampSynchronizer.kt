package com.neoproc.neoproccamx.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal sealed class ExactOrNewerTimestampResult<out T> {
    data class Exact<T>(val value: T) : ExactOrNewerTimestampResult<T>()
    data class Superseded(val newerTimestampNs: Long) : ExactOrNewerTimestampResult<Nothing>()
    data object TimedOut : ExactOrNewerTimestampResult<Nothing>()
}

/**
 * Associates independently delivered values by their authoritative sensor timestamp.
 *
 * Camera2 results and ImageReader images can arrive in either order and on different threads.
 * Nearest-neighbour matching is unsafe for DNG metadata, so this bounded synchronizer accepts exact
 * matches only and suspends consumers without blocking the camera callback thread.
 */
internal class ExactTimestampSynchronizer<T>(
    private val retainedValueCapacity: Int
) {
    init {
        require(retainedValueCapacity > 0) { "retainedValueCapacity must be positive" }
    }

    private val lock = Any()
    private val retainedValues = object : LinkedHashMap<Long, T>(
        retainedValueCapacity,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, T>?): Boolean =
            size > retainedValueCapacity
    }
    private val waiters = HashMap<Long, CompletableDeferred<T>>()
    private val exactOrNewerWaiters =
        HashMap<Long, CompletableDeferred<ExactOrNewerTimestampResult<T>>>()

    fun offer(timestampNs: Long, value: T) {
        val completion = synchronized(lock) {
            val exactWaiter = waiters.remove(timestampNs)
            val orderedExactWaiter = exactOrNewerWaiters.remove(timestampNs)
            val supersededWaiters = exactOrNewerWaiters
                .filterKeys { waitingTimestampNs -> waitingTimestampNs < timestampNs }
                .map { (waitingTimestampNs, waiter) ->
                    exactOrNewerWaiters.remove(waitingTimestampNs)
                    waiter
                }

            if (exactWaiter == null && orderedExactWaiter == null) {
                retainedValues[timestampNs] = value
            }
            Triple(exactWaiter, orderedExactWaiter, supersededWaiters)
        }
        completion.first?.complete(value)
        completion.second?.complete(ExactOrNewerTimestampResult.Exact(value))
        completion.third.forEach { waiter ->
            waiter.complete(ExactOrNewerTimestampResult.Superseded(timestampNs))
        }
    }

    fun pollExact(timestampNs: Long): T? = synchronized(lock) {
        retainedValues.remove(timestampNs)
    }

    suspend fun awaitExact(timestampNs: Long, timeoutMillis: Long): T? {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        val deferred = synchronized(lock) {
            retainedValues.remove(timestampNs)?.let { return it }
            waiters.getOrPut(timestampNs) { CompletableDeferred() }
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } finally {
            synchronized(lock) {
                if (waiters[timestampNs] === deferred) {
                    waiters.remove(timestampNs)
                }
            }
        }
    }

    /**
     * Waits for an exact value while allowing a monotonically newer authoritative timestamp to
     * prove that [timestampNs] can never match. The newer value remains retained for its own image.
     *
     * Camera2 sensor timestamps are monotonically increasing within one capture session. This
     * operation lets an ordered ImageReader drain reject pre-capture backlog immediately after the
     * tagged still result arrives, without changing to acquireLatestImage() and risking the exact
     * still frame being auto-closed.
     */
    suspend fun awaitExactOrNewer(
        timestampNs: Long,
        timeoutMillis: Long
    ): ExactOrNewerTimestampResult<T> {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        val deferred = synchronized(lock) {
            retainedValues.remove(timestampNs)?.let {
                return ExactOrNewerTimestampResult.Exact(it)
            }
            retainedValues.keys
                .asSequence()
                .filter { retainedTimestampNs -> retainedTimestampNs > timestampNs }
                .minOrNull()
                ?.let { newerTimestampNs ->
                    return ExactOrNewerTimestampResult.Superseded(newerTimestampNs)
                }
            exactOrNewerWaiters.getOrPut(timestampNs) { CompletableDeferred() }
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: ExactOrNewerTimestampResult.TimedOut
        } finally {
            synchronized(lock) {
                if (exactOrNewerWaiters[timestampNs] === deferred) {
                    exactOrNewerWaiters.remove(timestampNs)
                }
            }
        }
    }

    fun close() {
        val pending = synchronized(lock) {
            retainedValues.clear()
            buildList<CompletableDeferred<*>> {
                addAll(waiters.values)
                addAll(exactOrNewerWaiters.values)
            }.also {
                waiters.clear()
                exactOrNewerWaiters.clear()
            }
        }
        pending.forEach { it.cancel() }
    }
}
