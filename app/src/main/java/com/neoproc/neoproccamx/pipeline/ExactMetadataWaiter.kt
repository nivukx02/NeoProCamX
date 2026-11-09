package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Condition-backed hand-off for metadata that must belong to one exact sensor timestamp.
 *
 * The supplied [probe] runs while [lock] is held. [Pending] releases that lock while sleeping, so a
 * Camera2 result callback can publish without ever blocking its callback thread. [Terminal] is a
 * fail-closed result: callers must skip the authoritative output instead of borrowing metadata from
 * an adjacent frame.
 */
internal class ExactMetadataWaiter(
    private val lock: ReentrantLock
) {
    internal sealed class Probe<out T> {
        class Ready<T>(val value: T) : Probe<T>()
        object Pending : Probe<Nothing>()
        object Terminal : Probe<Nothing>()
    }

    private val changed = lock.newCondition()

    fun <T> await(
        timeoutMs: Long,
        probe: () -> Probe<T>
    ): T? {
        require(timeoutMs >= 0L) { "timeoutMs must be non-negative" }
        return lock.withLock {
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                when (val state = probe()) {
                    is Probe.Ready -> return@withLock state.value
                    Probe.Terminal -> return@withLock null
                    Probe.Pending -> Unit
                }
                if (remainingNs <= 0L) return@withLock null
                remainingNs = try {
                    changed.awaitNanos(remainingNs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@withLock null
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    /** Caller must hold the lock supplied to this waiter. */
    fun signalChangedLocked() {
        changed.signalAll()
    }

    /** Safe wake-up for lifecycle paths that do not already hold the metadata lock. */
    fun signalChanged() {
        lock.withLock { changed.signalAll() }
    }
}
