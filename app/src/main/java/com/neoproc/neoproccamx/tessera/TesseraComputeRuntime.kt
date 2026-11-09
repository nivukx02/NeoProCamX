package com.neoproc.neoproccamx.tessera

import android.os.Process
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded heterogeneous work scheduler for camera-supporting computation.
 *
 * Admission is non-blocking. Preview/recording work can evict queued background work, while
 * background work can never consume the reserved realtime capacity. Payload functions receive the
 * selected real backend and remain responsible for invoking their CPU, Vulkan, or LiteRT kernel.
 */
internal class TesseraComputeRuntime(
    private val config: TesseraRuntimeConfig,
    private val nanoTime: () -> Long = System::nanoTime
) : AutoCloseable {
    private val sequence = AtomicLong(0L)
    private val accepting = AtomicBoolean(true)
    private val outstanding = AtomicInteger(0)
    private val backgroundOutstanding = AtomicInteger(0)
    private val peakOutstanding = AtomicInteger(0)
    private val activeCameraSessions = AtomicInteger(0)
    private val backendAvailability = AtomicReference(config.backendAvailability)
    private val acceptedTasks = AtomicLong(0L)
    private val completedTasks = AtomicLong(0L)
    private val failedTasks = AtomicLong(0L)
    private val cancelledTasks = AtomicLong(0L)
    private val rejectedTasks = AtomicLong(0L)
    private val preemptedBackgroundTasks = AtomicLong(0L)
    private val deadlineMisses = AtomicLong(0L)
    private val closeComplete = CompletableFuture<Unit>()

    private val executor = ThreadPoolExecutor(
        config.workerCount,
        config.workerCount,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(),
        TesseraThreadFactory(),
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        allowCoreThreadTimeOut(false)
    }

    fun enterCameraSession() {
        activeCameraSessions.incrementAndGet()
    }

    fun exitCameraSession() {
        activeCameraSessions.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun reportBackendAvailability(backend: TesseraExecutionBackend, available: Boolean) {
        backendAvailability.updateAndGet { current ->
            when (backend) {
                TesseraExecutionBackend.CONTROL_CPU -> current.copy(controlCpu = true)
                TesseraExecutionBackend.VECTOR_GPU -> current.copy(vectorGpu = available)
                TesseraExecutionBackend.MATRIX_NPU -> current.copy(matrixNpu = available)
            }
        }
    }

    fun <T> submit(
        spec: TesseraTaskSpec,
        operation: (TesseraExecutionBackend) -> T
    ): TesseraSubmission<T> {
        val future = CompletableFuture<T>()
        if (!accepting.get()) {
            return rejected(future, TesseraRejectionReason.RUNTIME_CLOSED)
        }
        if (spec.deadlineNs?.let { it <= nanoTime() } == true) {
            deadlineMisses.incrementAndGet()
            return rejected(future, TesseraRejectionReason.DEADLINE_EXPIRED)
        }
        val availableBackends = backendAvailability.get()
        val backend = spec.preferredBackends.firstOrNull(availableBackends::supports)
            ?: return rejected(future, TesseraRejectionReason.NO_COMPATIBLE_BACKEND)

        val capacityFailure = reserveCapacity(spec.workClass)
        if (capacityFailure != TesseraRejectionReason.NONE) {
            return rejected(future, capacityFailure)
        }

        val task = ScheduledTask(
            spec = spec,
            backend = backend,
            taskSequence = sequence.getAndIncrement(),
            operation = operation,
            future = future
        )
        return try {
            executor.execute(task)
            acceptedTasks.incrementAndGet()
            TesseraSubmission(
                accepted = true,
                backend = backend,
                rejectionReason = TesseraRejectionReason.NONE,
                future = future,
                retirementFuture = task.retirement,
                cancelAction = { cancel(task, preempted = false) }
            )
        } catch (error: RejectedExecutionException) {
            task.releaseCapacityOnce()
            rejectedTasks.incrementAndGet()
            future.completeExceptionally(error)
            TesseraSubmission(
                accepted = false,
                backend = null,
                rejectionReason = TesseraRejectionReason.RUNTIME_CLOSED,
                future = future,
                retirementFuture = task.retirement,
                cancelAction = { false }
            )
        }
    }

    fun telemetry(): TesseraRuntimeTelemetry = TesseraRuntimeTelemetry(
        acceptedTasks = acceptedTasks.get(),
        completedTasks = completedTasks.get(),
        failedTasks = failedTasks.get(),
        cancelledTasks = cancelledTasks.get(),
        rejectedTasks = rejectedTasks.get(),
        preemptedBackgroundTasks = preemptedBackgroundTasks.get(),
        deadlineMisses = deadlineMisses.get(),
        outstandingTasks = outstanding.get(),
        backgroundOutstandingTasks = backgroundOutstanding.get(),
        peakOutstandingTasks = peakOutstanding.get(),
        activeCameraSessions = activeCameraSessions.get()
    )

    override fun close() {
        if (!accepting.compareAndSet(true, false)) {
            // A concurrent close owns teardown. Do not advertise a strong close while its worker payloads
            // can still be executing; provider replacement relies on this barrier.
            closeComplete.join()
            return
        }
        var interrupted = false
        try {
            executor.queue.toList().forEach { runnable ->
                val task = runnable as? ScheduledTask<*> ?: return@forEach
                cancel(task, preempted = false)
            }
            executor.shutdown()
            val graceful = try {
                executor.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (!graceful) {
                executor.shutdownNow().forEach { runnable ->
                    (runnable as? ScheduledTask<*>)?.cancelBeforeRun(preempted = false)
                }
            }

            // shutdownNow can only request interruption. Codec/native payloads may need to finish their
            // bounded unwind before their resources are safe to reuse, so close is a real retirement
            // barrier rather than returning after an arbitrary timeout with live daemon work behind it.
            while (!executor.isTerminated) {
                try {
                    executor.awaitTermination(SHUTDOWN_RETIREMENT_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    executor.shutdownNow().forEach { runnable ->
                        (runnable as? ScheduledTask<*>)?.cancelBeforeRun(preempted = false)
                    }
                }
            }
        } finally {
            closeComplete.complete(Unit)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun reserveCapacity(workClass: TesseraWorkClass): TesseraRejectionReason {
        val backgroundLimit = config.maxOutstandingTasks - config.realtimeReservedTasks
        while (true) {
            if (!accepting.get()) return TesseraRejectionReason.RUNTIME_CLOSED
            val current = outstanding.get()
            if (!workClass.realtime && current >= backgroundLimit) {
                return TesseraRejectionReason.BACKGROUND_CAPACITY_RESERVED
            }
            if (current >= config.maxOutstandingTasks) {
                if (workClass.realtime && preemptQueuedBackgroundTask()) continue
                return TesseraRejectionReason.CAPACITY_EXHAUSTED
            }
            if (!outstanding.compareAndSet(current, current + 1)) continue
            if (!workClass.realtime) backgroundOutstanding.incrementAndGet()
            peakOutstanding.accumulateAndGet(current + 1, ::maxOf)
            return TesseraRejectionReason.NONE
        }
    }

    private fun preemptQueuedBackgroundTask(): Boolean {
        val candidate = executor.queue
            .asSequence()
            .mapNotNull { it as? ScheduledTask<*> }
            .filter { !it.spec.workClass.realtime }
            .maxWithOrNull(
                compareBy<ScheduledTask<*>> { it.spec.workClass.schedulingRank }
                    .thenBy { it.taskSequence }
            )
            ?: return false
        if (!executor.remove(candidate)) return false
        candidate.cancelBeforeRun(preempted = true)
        return true
    }

    private fun cancel(task: ScheduledTask<*>, preempted: Boolean): Boolean {
        if (task.hasStarted.get()) return task.cancelFuture()
        val removed = executor.remove(task)
        return if (removed) {
            task.cancelBeforeRun(preempted)
            true
        } else {
            task.cancelFuture()
        }
    }

    private fun <T> rejected(
        future: CompletableFuture<T>,
        reason: TesseraRejectionReason
    ): TesseraSubmission<T> {
        rejectedTasks.incrementAndGet()
        val exception = when (reason) {
            TesseraRejectionReason.DEADLINE_EXPIRED -> TimeoutException("Tessera task deadline expired")
            else -> RejectedExecutionException("Tessera task rejected: $reason")
        }
        future.completeExceptionally(exception)
        return TesseraSubmission(
            accepted = false,
            backend = null,
            rejectionReason = reason,
            future = future,
            retirementFuture = CompletableFuture.completedFuture(Unit),
            cancelAction = { false }
        )
    }

    private inner class ScheduledTask<T>(
        val spec: TesseraTaskSpec,
        val backend: TesseraExecutionBackend,
        val taskSequence: Long,
        private val operation: (TesseraExecutionBackend) -> T,
        val future: CompletableFuture<T>
    ) : Runnable, Comparable<ScheduledTask<*>> {
        val hasStarted = AtomicBoolean(false)
        val retirement = CompletableFuture<Unit>()
        private val capacityReleased = AtomicBoolean(false)
        private val cancellationRecorded = AtomicBoolean(false)
        private val terminalLock = Any()

        override fun compareTo(other: ScheduledTask<*>): Int {
            val priorityOrder = spec.workClass.schedulingRank.compareTo(other.spec.workClass.schedulingRank)
            if (priorityOrder != 0) return priorityOrder
            val thisDeadline = spec.deadlineNs ?: Long.MAX_VALUE
            val otherDeadline = other.spec.deadlineNs ?: Long.MAX_VALUE
            val deadlineOrder = thisDeadline.compareTo(otherDeadline)
            return if (deadlineOrder != 0) deadlineOrder else taskSequence.compareTo(other.taskSequence)
        }

        override fun run() {
            if (!hasStarted.compareAndSet(false, true)) return
            if (future.isCancelled) {
                recordCancellationOnce()
                releaseCapacityOnce()
                return
            }
            val deadline = spec.deadlineNs
            if (deadline != null && nanoTime() > deadline) {
                deadlineMisses.incrementAndGet()
                future.completeExceptionally(TimeoutException("Tessera task '${spec.name}' missed its deadline"))
                releaseCapacityOnce()
                return
            }

            applyThreadPolicy(spec.workClass)
            try {
                val result = operation(backend)
                synchronized(terminalLock) {
                    if (future.isCancelled) {
                        recordCancellationOnce()
                    } else {
                        // Publish terminal telemetry before releasing future waiters. Completing the
                        // future first allowed successful callers to observe a stale task count.
                        completedTasks.incrementAndGet()
                        future.complete(result)
                    }
                }
            } catch (error: CancellationException) {
                synchronized(terminalLock) {
                    recordCancellationOnce()
                    future.completeExceptionally(error)
                }
            } catch (error: Throwable) {
                synchronized(terminalLock) {
                    if (future.isCancelled) {
                        recordCancellationOnce()
                    } else {
                        failedTasks.incrementAndGet()
                        future.completeExceptionally(error)
                    }
                }
            } finally {
                applyDefaultThreadPolicy()
                releaseCapacityOnce()
            }
        }

        fun cancelBeforeRun(preempted: Boolean) {
            if (hasStarted.compareAndSet(false, true)) {
                if (preempted) preemptedBackgroundTasks.incrementAndGet()
                synchronized(terminalLock) {
                    recordCancellationOnce()
                    future.completeExceptionally(CancellationException("Tessera task '${spec.name}' cancelled"))
                }
                releaseCapacityOnce()
            }
        }

        fun cancelFuture(): Boolean = synchronized(terminalLock) {
            future.cancel(false).also { cancelled ->
                if (cancelled) recordCancellationOnce()
            }
        }

        private fun recordCancellationOnce() {
            if (cancellationRecorded.compareAndSet(false, true)) {
                cancelledTasks.incrementAndGet()
            }
        }

        fun releaseCapacityOnce() {
            if (!capacityReleased.compareAndSet(false, true)) return
            outstanding.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            if (!spec.workClass.realtime) {
                backgroundOutstanding.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            }
            // Capacity is released only after the operation has returned (or before it ever started),
            // making this the exact worker-retirement point needed by exclusive conversion ownership.
            retirement.complete(Unit)
        }
    }

    private class TesseraThreadFactory : ThreadFactory {
        private val threadIndex = AtomicInteger(0)

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "Tessera-${threadIndex.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
    }

    private companion object {
        const val SHUTDOWN_GRACE_MS = 1_500L
        const val SHUTDOWN_RETIREMENT_POLL_MS = 100L

        fun applyThreadPolicy(workClass: TesseraWorkClass) {
            val priority = when (workClass) {
                TesseraWorkClass.PREVIEW,
                TesseraWorkClass.RECORDING -> Process.THREAD_PRIORITY_URGENT_DISPLAY
                TesseraWorkClass.CAPTURE,
                TesseraWorkClass.REALTIME_AI -> Process.THREAD_PRIORITY_DISPLAY
                TesseraWorkClass.OFFLINE_CONVERSION -> Process.THREAD_PRIORITY_DEFAULT
                TesseraWorkClass.BACKGROUND -> Process.THREAD_PRIORITY_BACKGROUND
            }
            runCatching { Process.setThreadPriority(priority) }
        }

        fun applyDefaultThreadPolicy() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
        }
    }
}
