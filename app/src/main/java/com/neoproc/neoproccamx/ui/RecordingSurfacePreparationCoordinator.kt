package com.neoproc.neoproccamx.ui

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes idle encoder pre-arm work with the recording-start ownership handoff.
 *
 * [RecordingManager.prepare] and its cleanup methods mutate one shared MediaCodec session. An idle
 * pre-arm transaction must therefore finish all preparation and cleanup before recording start is
 * allowed to inspect or mutate that session. Recording start first publishes its ownership through
 * [RecordingStartupHandoff], then waits at [awaitPrearmCompletion]. Any later pre-arm request sees
 * that published ownership and exits without touching the encoder.
 */
internal class RecordingSurfacePreparationCoordinator {

    private val transactionMutex = Mutex()
    private val teardownLock = ReentrantLock()
    private val teardownQuiescent = teardownLock.newCondition()

    /** Guarded by [teardownLock]. Once set, this coordinator can never be reopened. */
    private var teardownSealed = false

    /** Guarded by [teardownLock]. The mutex guarantees that at most one transaction is active. */
    private var transactionActive = false

    internal enum class TeardownQuiescence {
        PROVEN,
        TIMED_OUT,
        INTERRUPTED
    }

    /**
     * Capability returned by [sealForTeardown]. Holding this object proves that pre-arm admission
     * was closed synchronously before a retirement worker began waiting for native quiescence.
     */
    internal class TeardownSeal internal constructor(
        private val coordinator: RecordingSurfacePreparationCoordinator
    ) {
        /**
         * Blocks the calling retirement/background thread until the transaction that was already
         * active when the seal was installed exits, or until [timeoutMillis] elapses.
         *
         * This must not be called from the main thread. Interruption is preserved on the caller so
         * retirement can fail closed instead of silently continuing with an unproven detach.
         */
        fun awaitQuiescence(timeoutMillis: Long): TeardownQuiescence =
            coordinator.awaitSealedQuiescence(timeoutMillis)
    }

    suspend fun <T> runPrearmTransaction(transaction: () -> T): T =
        transactionMutex.withLock {
            teardownLock.lock()
            try {
                if (teardownSealed) {
                    // Cancellation is intentional here: callers run as best-effort pre-arm jobs,
                    // and teardown rejection must not surface as an application failure.
                    throw CancellationException(
                        "Recording-surface pre-arm rejected after teardown was sealed"
                    )
                }
                check(!transactionActive) {
                    "Pre-arm transaction mutex admitted overlapping ownership"
                }
                transactionActive = true
            } finally {
                teardownLock.unlock()
            }
            try {
                transaction()
            } finally {
                teardownLock.lock()
                try {
                    transactionActive = false
                    teardownQuiescent.signalAll()
                } finally {
                    teardownLock.unlock()
                }
            }
        }

    /** Waits until a pre-arm transaction that won the race has completed its cleanup. */
    suspend fun awaitPrearmCompletion() {
        transactionMutex.withLock {
            // The lock acquisition itself is the ownership barrier.
        }
    }

    /**
     * Permanently closes pre-arm admission without blocking the caller.
     *
     * Install this seal synchronously at the start of ViewModel teardown, then transfer the
     * returned capability to the retirement daemon. A transaction that became active before this
     * method linearized may finish, but no transaction body can begin after it linearizes.
     */
    fun sealForTeardown(): TeardownSeal {
        teardownLock.lock()
        try {
            teardownSealed = true
        } finally {
            teardownLock.unlock()
        }
        return TeardownSeal(this)
    }

    private fun awaitSealedQuiescence(timeoutMillis: Long): TeardownQuiescence {
        require(timeoutMillis >= 0L) { "timeoutMillis must be non-negative" }
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val startedAtNanos = System.nanoTime()

        teardownLock.lock()
        try {
            // Only TeardownSeal can enter this method. Keep this invariant explicit so a future
            // refactor cannot turn a bare wait into false proof while admission remains open.
            check(teardownSealed) { "Pre-arm teardown quiescence requested without a seal" }
            var remainingNanos = timeoutNanos
            while (transactionActive) {
                if (remainingNanos <= 0L) return TeardownQuiescence.TIMED_OUT
                try {
                    remainingNanos = teardownQuiescent.awaitNanos(remainingNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return TeardownQuiescence.INTERRUPTED
                }
                val elapsedRemaining = timeoutNanos - (System.nanoTime() - startedAtNanos)
                remainingNanos = minOf(remainingNanos, elapsedRemaining)
            }
            return TeardownQuiescence.PROVEN
        } finally {
            teardownLock.unlock()
        }
    }
}
