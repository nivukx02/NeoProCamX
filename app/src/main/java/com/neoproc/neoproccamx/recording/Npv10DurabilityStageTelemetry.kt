package com.neoproc.neoproccamx.recording

import java.util.concurrent.atomic.AtomicLong

/** Queue-drain time and filesystem-force time are distinct; neither may impersonate the other. */
internal data class Npv10DurabilityStageSnapshot(
    val commitWaitAttempts: Long = 0L,
    val commitWaitFailures: Long = 0L,
    val averageCommitWaitMs: Double = 0.0,
    val maximumCommitWaitMs: Double = 0.0,
    val syncAttempts: Long = 0L,
    val syncFailures: Long = 0L,
    val averageSyncMs: Double = 0.0,
    val maximumSyncMs: Double = 0.0
) {
    fun toEvidenceMetadata(): Map<String, String> = linkedMapOf(
        "npv10CheckpointCommitWaitAttempts" to commitWaitAttempts.toString(),
        "npv10CheckpointCommitWaitFailures" to commitWaitFailures.toString(),
        "npv10CheckpointAverageCommitWaitMs" to averageCommitWaitMs.toString(),
        "npv10CheckpointMaximumCommitWaitMs" to maximumCommitWaitMs.toString(),
        "npv10CheckpointSyncAttempts" to syncAttempts.toString(),
        "npv10CheckpointSyncFailures" to syncFailures.toString(),
        "npv10CheckpointAverageSyncMs" to averageSyncMs.toString(),
        "npv10CheckpointMaximumSyncMs" to maximumSyncMs.toString()
    )
}

/** Observes existing operations only: failures propagate and checkpoint/durability policy is intact. */
internal class Npv10DurabilityStageTelemetry(private val nanoTime: () -> Long = System::nanoTime) {
    private class Stage {
        val attempts = AtomicLong()
        val failures = AtomicLong()
        val totalNs = AtomicLong()
        val maximumNs = AtomicLong()

        fun averageMs(): Double = if (attempts.get() == 0L) 0.0 else {
            totalNs.get().toDouble() / attempts.get().toDouble() / 1_000_000.0
        }
    }

    private val commitWait = Stage()
    private val sync = Stage()

    fun awaitCommit(operation: () -> Unit) = measure(commitWait, operation)
    fun syncFile(operation: () -> Unit) = measure(sync, operation)

    fun snapshot(): Npv10DurabilityStageSnapshot = Npv10DurabilityStageSnapshot(
        commitWaitAttempts = commitWait.attempts.get(),
        commitWaitFailures = commitWait.failures.get(),
        averageCommitWaitMs = commitWait.averageMs(),
        maximumCommitWaitMs = commitWait.maximumNs.get() / 1_000_000.0,
        syncAttempts = sync.attempts.get(),
        syncFailures = sync.failures.get(),
        averageSyncMs = sync.averageMs(),
        maximumSyncMs = sync.maximumNs.get() / 1_000_000.0
    )

    private fun measure(stage: Stage, operation: () -> Unit) {
        val started = nanoTime()
        try {
            operation()
        } catch (failure: Throwable) {
            stage.failures.incrementAndGet()
            throw failure
        } finally {
            // Subtraction retains System.nanoTime wrap semantics for bounded checkpoint intervals.
            val elapsed = (nanoTime() - started).coerceAtLeast(0L)
            stage.totalNs.addAndGet(elapsed)
            var previous = stage.maximumNs.get()
            while (elapsed > previous && !stage.maximumNs.compareAndSet(previous, elapsed)) {
                previous = stage.maximumNs.get()
            }
            stage.attempts.incrementAndGet()
        }
    }
}
