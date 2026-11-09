package com.neoproc.neoproccamx.pipeline

import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded exact-membership ledger for Image ownership transfers into the current recording sink.
 * Epochs prevent a late callback from an older sink installation satisfying a newer stop fence.
 */
internal class ExactTimestampAcceptanceTracker(
    private val maximumEntries: Int = 256
) {
    init {
        require(maximumEntries > 0)
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val accepted = LinkedHashSet<Long>()
    private var activeEpoch = 0L

    fun reset(): Long = lock.withLock {
        activeEpoch = if (activeEpoch == Long.MAX_VALUE) 1L else activeEpoch + 1L
        accepted.clear()
        changed.signalAll()
        activeEpoch
    }

    fun record(epoch: Long, timestampNs: Long): Boolean = lock.withLock {
        if (epoch != activeEpoch || timestampNs <= 0L) return false
        accepted.add(timestampNs)
        while (accepted.size > maximumEntries) {
            val iterator = accepted.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        changed.signalAll()
        true
    }

    fun contains(epoch: Long, timestampNs: Long): Boolean = lock.withLock {
        epoch == activeEpoch && accepted.contains(timestampNs)
    }

    fun await(epoch: Long, timestampNs: Long, timeoutNanos: Long): Boolean = lock.withLock {
        if (epoch != activeEpoch || timestampNs <= 0L || timeoutNanos <= 0L) return false
        var remainingNanos = timeoutNanos
        while (epoch == activeEpoch && !accepted.contains(timestampNs)) {
            if (remainingNanos <= 0L) return false
            remainingNanos = try {
                changed.awaitNanos(remainingNanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        epoch == activeEpoch && accepted.contains(timestampNs)
    }
}
