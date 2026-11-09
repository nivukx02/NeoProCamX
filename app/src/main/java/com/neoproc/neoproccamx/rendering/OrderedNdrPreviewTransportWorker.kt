package com.neoproc.neoproccamx.rendering

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bounded, ordered execution domain for recorder-owned NDR monitor transports.
 *
 * The bound applies to the running task plus queued tasks, not only to the executor queue. This keeps
 * Java ownership exactly aligned with the native preview ring: no more than [maxOutstanding] transport
 * leases can exist at once. A task failure is sticky and closes admission until a lifecycle owner drains
 * the domain and explicitly starts a fresh epoch with [resumeAfterDrain]. Already-accepted tasks still
 * run after a failure so every AHardwareBuffer/token pair can be retired exactly once.
 */
internal class OrderedNdrPreviewTransportWorker(
    private val maxOutstanding: Int = DEFAULT_MAX_OUTSTANDING,
    threadName: String = "NdrPreviewTransport",
    private val onTaskRetired: () -> Unit = {}
) : Closeable {
    init {
        require(maxOutstanding > 0) { "maxOutstanding must be positive" }
    }

    internal data class Snapshot(
        val state: State,
        val acceptedTasks: Long,
        val completedTasks: Long,
        val failedTasks: Long,
        val rejectedTasks: Long,
        val outstandingTasks: Int,
        val lastFailure: String?
    )

    internal enum class State {
        PAUSED,
        RUNNING,
        FAILED,
        CLOSED
    }

    private val stateLock = Any()
    private val drainWaiters = mutableListOf<CountDownLatch>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxOutstanding),
        { runnable ->
            Thread(runnable, threadName).apply {
                isDaemon = false
                priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    private var state = State.PAUSED
    private var acceptedTasks = 0L
    private var completedTasks = 0L
    private var failedTasks = 0L
    private var rejectedTasks = 0L
    private var outstandingTasks = 0
    private var lastFailure: String? = null

    /** Starts a new admission epoch only after all work from the preceding epoch has retired. */
    fun resumeAfterDrain(clearFailure: Boolean = true): Boolean = synchronized(stateLock) {
        if (state == State.CLOSED || outstandingTasks != 0) return@synchronized false
        if (state == State.RUNNING && lastFailure == null) return@synchronized true
        if (clearFailure) lastFailure = null
        if (lastFailure != null) return@synchronized false
        state = State.RUNNING
        true
    }

    /** Stops new admission without waiting; already-accepted ownership continues to exact retirement. */
    fun pause() {
        synchronized(stateLock) {
            if (state == State.RUNNING) state = State.PAUSED
        }
    }

    /** Latches a fail-closed admission defect discovered after an irreversible encoder acceptance. */
    fun recordTerminalFailure(reason: String) {
        synchronized(stateLock) {
            if (state == State.CLOSED) return
            failedTasks++
            lastFailure = reason
            state = State.FAILED
        }
    }

    /**
     * Accepts one ordered task without blocking the camera/recording owner.
     *
     * False is an explicit fail-closed result. The caller still owns any transport that it attempted to
     * enqueue and must retire that transport itself.
     */
    fun submit(description: String, task: () -> Unit): Boolean {
        synchronized(stateLock) {
            if (state != State.RUNNING || lastFailure != null ||
                outstandingTasks >= maxOutstanding
            ) {
                rejectedTasks++
                return false
            }
            outstandingTasks++
        }

        try {
            executor.execute {
                var failure: Throwable? = null
                try {
                    task()
                } catch (throwable: Throwable) {
                    failure = throwable
                } finally {
                    synchronized(stateLock) {
                        completedTasks++
                        outstandingTasks--
                        if (failure != null) {
                            failedTasks++
                            lastFailure = buildFailureMessage(description, requireNotNull(failure))
                            if (state != State.CLOSED) state = State.FAILED
                        }
                        signalDrainIfIdleLocked()
                    }
                    // Ownership observers run only after the count is published and never while the
                    // worker monitor is held, preventing a raw-lock <-> worker-lock inversion.
                    try {
                        onTaskRetired()
                    } catch (throwable: Throwable) {
                        if (failure == null) failure = throwable
                        synchronized(stateLock) {
                            failedTasks++
                            if (lastFailure == null) {
                                lastFailure = buildFailureMessage(
                                    "$description retirement observer",
                                    throwable
                                )
                            }
                            if (state != State.CLOSED) state = State.FAILED
                        }
                    }
                }
                if (failure is VirtualMachineError) throw failure
                if (failure is ThreadDeath) throw failure
            }
        } catch (_: RejectedExecutionException) {
            synchronized(stateLock) {
                outstandingTasks--
                rejectedTasks++
                signalDrainIfIdleLocked()
            }
            return false
        }

        synchronized(stateLock) { acceptedTasks++ }
        return true
    }

    /** Pauses admission and proves that every accepted transport task has returned. */
    fun pauseAndDrain(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0L) { "timeoutMs must be non-negative" }
        val waiter = synchronized(stateLock) {
            if (state == State.RUNNING) state = State.PAUSED
            if (outstandingTasks == 0) {
                null
            } else {
                CountDownLatch(1).also(drainWaiters::add)
            }
        } ?: return true

        val drained = try {
            waiter.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!drained) {
            synchronized(stateLock) { drainWaiters.remove(waiter) }
        }
        return drained
    }

    fun snapshot(): Snapshot = synchronized(stateLock) {
        Snapshot(
            state = state,
            acceptedTasks = acceptedTasks,
            completedTasks = completedTasks,
            failedTasks = failedTasks,
            rejectedTasks = rejectedTasks,
            outstandingTasks = outstandingTasks,
            lastFailure = lastFailure
        )
    }

    /** Closes admission permanently. It never interrupts a task that may own a native transport. */
    fun closeAndDrain(timeoutMs: Long): Boolean {
        synchronized(stateLock) {
            state = State.CLOSED
        }
        val drained = pauseAndDrain(timeoutMs)
        if (!drained) return false
        executor.shutdown()
        return try {
            executor.awaitTermination(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun close() {
        closeAndDrain(DEFAULT_DRAIN_TIMEOUT_MS)
    }

    private fun signalDrainIfIdleLocked() {
        check(outstandingTasks >= 0) { "NDR preview transport ownership underflow" }
        if (outstandingTasks != 0) return
        drainWaiters.forEach(CountDownLatch::countDown)
        drainWaiters.clear()
    }

    private fun buildFailureMessage(description: String, failure: Throwable): String {
        val type = failure.javaClass.simpleName.ifBlank { failure.javaClass.name }
        val detail = failure.message?.takeIf(String::isNotBlank)
        return if (detail == null) "$description: $type" else "$description: $type: $detail"
    }

    private companion object {
        const val DEFAULT_MAX_OUTSTANDING = 3
        const val DEFAULT_DRAIN_TIMEOUT_MS = 3_000L
    }
}
