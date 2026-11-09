package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Condition-backed lifecycle wait used only by bounded teardown/reconfiguration paths. Producers
 * never wait on this gate; they only signal after publishing their atomic state transition.
 */
internal class PipelineQuiescenceGate {
    private val lock = ReentrantLock()
    private val stateChanged = lock.newCondition()

    fun signalStateChanged() {
        lock.withLock { stateChanged.signalAll() }
    }

    fun awaitNotBusy(timeoutMs: Long, isBusy: () -> Boolean): Boolean = lock.withLock {
        var remainingNs = millisToNanosSaturated(timeoutMs.coerceAtLeast(0L))
        while (isBusy()) {
            if (remainingNs <= 0L) return false
            remainingNs = try {
                stateChanged.awaitNanos(remainingNs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        true
    }

    private fun millisToNanosSaturated(milliseconds: Long): Long =
        if (milliseconds > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
            Long.MAX_VALUE
        } else {
            TimeUnit.MILLISECONDS.toNanos(milliseconds)
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
