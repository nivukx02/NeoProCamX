package com.neoproc.neoproccamx.ui

import android.util.Size

/**
 * Owns the newest direct recording-resolution choice for the lifetime of a CameraViewModel.
 *
 * Resolution selection and lifecycle preference restoration can run on different dispatchers. A
 * one-call `explicitResolutionSelection` flag protects the initial selection, but it does not
 * protect later pre-arm/recording re-resolution of the same persisted size. This generation-owned
 * authority makes the direct choice durable until a newer direct choice replaces it.
 */
internal class RecordingResolutionSelectionAuthority {

    internal data class Claim(
        val generation: Long,
        val width: Int,
        val height: Int
    )

    private val lock = Any()
    private var generation = 0L
    private var activeClaim: Claim? = null

    fun claim(size: Size): Claim = claim(size.width, size.height)

    /**
     * Rehydrates the last user-selected recording size during cold start without replacing a newer
     * in-memory selection. Persisted recording resolution remains authoritative over an independent
     * preview-aspect preference after process recreation.
     */
    fun restorePersistedIfUnclaimed(size: Size): Claim =
        restorePersistedIfUnclaimed(size.width, size.height)

    internal fun restorePersistedIfUnclaimed(width: Int, height: Int): Claim = synchronized(lock) {
        activeClaim ?: createClaim(width, height)
    }

    internal fun claim(width: Int, height: Int): Claim = synchronized(lock) {
        createClaim(width, height)
    }

    fun owns(size: Size): Boolean = owns(size.width, size.height)

    internal fun owns(width: Int, height: Int): Boolean = synchronized(lock) {
        val normalized = normalize(width, height)
        activeClaim?.let { claim ->
            claim.width == normalized.first && claim.height == normalized.second
        } == true
    }

    fun snapshot(): Claim? = synchronized(lock) { activeClaim }

    /**
     * Returns whether [claim] still owns the recording geometry.
     *
     * Comparing the complete immutable claim, including its generation, prevents an ABA sequence
     * (for example 1080p -> 4K -> 1080p) from making an old asynchronous resolver current again.
     */
    fun isCurrent(claim: Claim): Boolean = synchronized(lock) {
        activeClaim == claim
    }

    /**
     * Publishes a short asynchronous result only while [claim] remains authoritative.
     *
     * The validation and commit share one monitor with [claim], so the two legal orderings are
     * deterministic: either this commit finishes first and the newer direct choice wins next, or
     * the direct choice has already advanced the generation and this stale commit is rejected.
     */
    fun commitIfCurrent(claim: Claim, commit: () -> Unit): Boolean = synchronized(lock) {
        if (activeClaim != claim) {
            false
        } else {
            commit()
            true
        }
    }

    private fun createClaim(width: Int, height: Int): Claim {
        val normalized = normalize(width, height)
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return Claim(
            generation = generation,
            width = normalized.first,
            height = normalized.second
        ).also { activeClaim = it }
    }

    private fun normalize(width: Int, height: Int): Pair<Int, Int> =
        maxOf(width, height).coerceAtLeast(1) to minOf(width, height).coerceAtLeast(1)
}
