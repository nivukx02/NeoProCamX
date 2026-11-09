package com.neoproc.neoproccamx.rendering

/**
 * Thread-safe monotonic admission for native-visible replacements inside a mode generation.
 *
 * Equal revisions are idempotent only when their immutable payload is identical. A different
 * payload carrying an already-used revision is a producer contract violation, while an older
 * revision is ordinary stale work and cannot disturb the current packet.
 */
internal class NdrRenderProfileRevisionAdmission<T>(
    initialGeneration: Long = 0L,
    private val samePayload: (T, T) -> Boolean
) {
    internal enum class Result {
        ACCEPTED_NEW,
        IDEMPOTENT,
        STALE_GENERATION,
        STALE_REVISION,
        CONFLICTING_REVISION,
        CANCELLED_LIFECYCLE
    }

    private data class Accepted<T>(
        val identity: NdrRenderProfileIdentity,
        val payload: T
    )

    private var currentGeneration = initialGeneration
    private var latest: Accepted<T>? = null
    private var closed = false

    @Synchronized
    fun advanceGeneration(generation: Long): Long {
        if (closed || generation <= currentGeneration) return currentGeneration
        currentGeneration = generation
        latest = null
        return currentGeneration
    }

    @Synchronized
    fun inspect(
        identity: NdrRenderProfileIdentity,
        payload: T
    ): Result {
        return classifyLocked(identity, payload)
    }

    @Synchronized
    fun admit(
        identity: NdrRenderProfileIdentity,
        payload: T
    ): Result {
        val result = classifyLocked(identity, payload)
        if (result == Result.ACCEPTED_NEW) {
            latest = Accepted(identity, payload)
        }
        return result
    }

    /** Side-effect-free while the caller owns this instance monitor. */
    private fun classifyLocked(
        identity: NdrRenderProfileIdentity,
        payload: T
    ): Result {
        if (closed) return Result.CANCELLED_LIFECYCLE
        if (identity.profileGeneration <= 0L ||
            identity.profileGeneration != currentGeneration
        ) return Result.STALE_GENERATION
        if (identity.configRevision <= 0L) return Result.STALE_REVISION

        val accepted = latest
        if (accepted == null) {
            return Result.ACCEPTED_NEW
        }
        if (identity.configRevision < accepted.identity.configRevision) {
            return Result.STALE_REVISION
        }
        if (identity.configRevision == accepted.identity.configRevision) {
            return if (samePayload(accepted.payload, payload)) {
                Result.IDEMPOTENT
            } else {
                Result.CONFLICTING_REVISION
            }
        }

        return Result.ACCEPTED_NEW
    }

    @Synchronized
    fun latestIdentity(): NdrRenderProfileIdentity? = latest?.identity

    @Synchronized
    fun close() {
        closed = true
        latest = null
    }
}
