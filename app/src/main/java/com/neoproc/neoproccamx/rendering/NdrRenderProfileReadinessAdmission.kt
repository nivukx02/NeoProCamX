package com.neoproc.neoproccamx.rendering

/**
 * Latest-wins admission for a generation-owned render intent whose native consumers may not exist
 * yet during cold start.
 *
 * This class deliberately knows nothing about Android, Vulkan, or profile compatibility. The owner
 * validates compatibility first, then uses this state machine only to distinguish temporary route
 * unavailability from a real apply failure. A deferred intent is consumed exactly once by the first
 * matching readiness notification; generation advance and lifecycle close cancel it atomically.
 */
internal class NdrRenderProfileReadinessAdmission<T>(
    initialGeneration: Long = 0L
) {
    internal enum class Result {
        APPLIED,
        DEFERRED_UNREADY,
        STALE_GENERATION,
        APPLY_FAILED,
        CANCELLED_LIFECYCLE
    }

    private data class Pending<T>(
        val generation: Long,
        val intent: T
    )

    private var currentGeneration = initialGeneration
    private var pending: Pending<T>? = null
    private var closed = false

    @Synchronized
    fun advanceGeneration(generation: Long): Long {
        if (closed || generation <= currentGeneration) return currentGeneration
        currentGeneration = generation
        pending = null
        return currentGeneration
    }

    /** Retains only the latest compatible intent while consumers are temporarily unavailable. */
    @Synchronized
    fun submit(
        generation: Long,
        intent: T,
        consumersReady: Boolean,
        apply: (T) -> Boolean
    ): Result {
        if (closed) return Result.CANCELLED_LIFECYCLE
        if (generation <= 0L) return Result.STALE_GENERATION
        if (generation != currentGeneration) return Result.STALE_GENERATION
        if (!consumersReady) {
            pending = Pending(generation, intent)
            return Result.DEFERRED_UNREADY
        }

        // A real apply attempt is terminal for this submission. Do not turn a driver/native failure
        // into an unbounded readiness retry or retain a packet that could repeatedly fail.
        pending = null
        return if (apply(intent)) Result.APPLIED else Result.APPLY_FAILED
    }

    /**
     * Replays the latest matching intent once. Null means there was no retained work to consume.
     * An unready notification leaves the intent retained for a later actual readiness edge.
     */
    @Synchronized
    fun replayWhenReady(
        generation: Long,
        consumersReady: Boolean,
        apply: (T) -> Boolean
    ): Result? {
        if (closed) return null
        val retained = pending ?: return null
        // Generation zero is never a valid owner. Do not consume a valid positive retained packet
        // merely because a cold-start observer emitted its StateFlow sentinel.
        if (generation <= 0L) return Result.STALE_GENERATION
        if (retained.generation != currentGeneration || generation != currentGeneration) {
            pending = null
            return Result.STALE_GENERATION
        }
        if (!consumersReady) return Result.DEFERRED_UNREADY

        // Consume before invoking native code so even an exception/failure cannot replay twice.
        pending = null
        return if (apply(retained.intent)) Result.APPLIED else Result.APPLY_FAILED
    }

    @Synchronized
    fun close() {
        closed = true
        pending = null
    }

    @Synchronized
    internal fun hasPendingIntent(): Boolean = pending != null
}
