package com.neoproc.neoproccamx.recording

/**
 * Pure, Android-free MIRROR of [RecordingManager]'s persistent-input-surface selection lifecycle
 * (the zero-shutter-lag / pre-armed encoder surface, s6-f10) plus the record-start accounting
 * helpers.
 *
 * This type exists ONLY to golden-lock the selection state machine and the record-start budget
 * math in JVM unit tests with zero Android dependencies — it does **not** drive any live decision.
 * The live authority remains [RecordingManager.preparePersistentInputSurface],
 * `attachVideoInputSurface`, [RecordingManager.releasePersistentInputSurface],
 * [RecordingManager.setPersistentInputSurfaceAllowed] and the `releaseInternal` session teardown.
 * `RecordingPersistentInputSurfaceContractTest` pins the live mutation lines so this mirror cannot
 * silently drift from them.
 *
 * Field mapping to live [RecordingManager] state:
 *  - [State.allowed]                <- `persistentInputSurfaceAllowed`
 *  - [State.rejected]               <- `persistentInputSurfaceRejected`
 *  - [State.surfaceArmed]           <- `persistentEncoderSurface != null`
 *  - [State.usesPersistent]         <- `encoderSurfaceUsesPersistentInput`
 *  - [State.encoderSurfaceIsPersistent] <- `encoderSurface === persistentEncoderSurface`
 *
 * Every transition below is annotated with the exact live `RecordingManager.kt` lines it mirrors so
 * a reviewer can verify the characterization is faithful, not invented.
 */
internal object RecordingPersistentInputSurfaceSelection {

    const val MICROSECONDS_PER_SECOND: Long = 1_000_000L

    /** Honest sentinel for a value that is only meaningful when measured on-device. */
    const val NOT_MEASURED: String = "NOT_MEASURED"

    data class State(
        val allowed: Boolean = true,
        val rejected: Boolean = false,
        val surfaceArmed: Boolean = false,
        val usesPersistent: Boolean = false,
        val encoderSurfaceIsPersistent: Boolean = false,
    )

    /**
     * Mirrors `preparePersistentInputSurface()` success: arms the surface and clears the rejected
     * flag (RecordingManager.kt:849-852). When `!allowed` it returns null with no field change
     * (:840-842); a still-valid cached surface is reused unchanged (:845-847).
     */
    fun prepareSucceeded(state: State): State {
        if (!state.allowed) return state
        if (state.surfaceArmed) return state
        return state.copy(surfaceArmed = true, rejected = false)
    }

    /**
     * Mirrors `preparePersistentInputSurface()` failure: nulls the surface and sets the rejected
     * flag (RecordingManager.kt:854-857).
     */
    fun prepareFailed(state: State): State {
        if (!state.allowed) return state
        return state.copy(surfaceArmed = false, rejected = true)
    }

    /**
     * Mirrors `releasePersistentInputSurface()` (RecordingManager.kt:868-877): early-returns when no
     * surface is armed (:869); detaches the encoder surface when it *is* the persistent surface
     * (:870-872); nulls the surface (:876); and **resets `rejected` to false** (:877).
     */
    fun releasePersistentSurface(state: State): State {
        if (!state.surfaceArmed) return state
        val detached =
            if (state.encoderSurfaceIsPersistent) {
                state.copy(usesPersistent = false, encoderSurfaceIsPersistent = false)
            } else {
                state
            }
        return detached.copy(surfaceArmed = false, rejected = false)
    }

    /**
     * True iff `attachVideoInputSurface` would take the persistent branch
     * (RecordingManager.kt:881-887 via
     * `RecordingPersistentInputSurfacePolicy.shouldUsePersistentInputSurface`).
     */
    fun wouldUsePersistent(state: State): Boolean =
        state.allowed && state.surfaceArmed && !state.rejected

    /**
     * Mirrors `attachVideoInputSurface` success on the persistent branch: `codec.setInputSurface`
     * succeeds, `encoderSurfaceUsesPersistentInput = true` (:889-890) and the caller assigns
     * `encoderSurface = persistent`. `usesPersistent` becomes true ONLY after the attach succeeds
     * (set-on-success, not set-then-attach). When the persistent branch is not taken it falls back
     * to the codec-owned path.
     */
    fun attachSucceeded(state: State): State {
        if (!wouldUsePersistent(state)) return attachCodecOwned(state)
        return state.copy(usesPersistent = true, encoderSurfaceIsPersistent = true)
    }

    /**
     * Mirrors `attachVideoInputSurface` codec rejection (RecordingManager.kt:894-898): it sets
     * `persistentInputSurfaceRejected = true` (:895) then calls `releasePersistentInputSurface()`
     * (:896), whose :877 **resets `rejected` back to false** and nulls the surface. The net
     * observable state therefore has `rejected = false` and `surfaceArmed = false` — this mirror
     * locks that real (characterized) behavior, NOT the transient `rejected = true`. `setInputSurface`
     * throws before :890, so `usesPersistent` is never set true on this path.
     */
    fun attachRejected(state: State): State {
        val flagged = state.copy(rejected = true)
        return releasePersistentSurface(flagged)
    }

    /** Mirrors `attachVideoInputSurface` codec-owned branch (RecordingManager.kt:902-908). */
    fun attachCodecOwned(state: State): State =
        state.copy(usesPersistent = false, encoderSurfaceIsPersistent = false)

    /**
     * Mirrors `setPersistentInputSurfaceAllowed(allowed)` (RecordingManager.kt:861-865): on `false`
     * it releases the persistent surface.
     */
    fun setAllowed(state: State, allowed: Boolean): State {
        val updated = state.copy(allowed = allowed)
        return if (!allowed) releasePersistentSurface(updated) else updated
    }

    /**
     * Mirrors `releaseInternal()` session teardown (RecordingManager.kt:3275-3284): the codec-owned
     * encoder surface is released only when the session did NOT use the persistent surface
     * (`shouldReleaseSessionSurface(usesPersistent) == !usesPersistent`); `encoderSurface` is nulled
     * and `encoderSurfaceUsesPersistentInput` cleared (:3283-3284); the persistent surface itself is
     * KEPT armed across recordings (`shouldRetire...` is always false, :3292-3300).
     */
    fun sessionReset(state: State): State =
        state.copy(usesPersistent = false, encoderSurfaceIsPersistent = false)

    // ── record-start accounting helpers (pure math) ───────────────────────────────────────────

    /**
     * Head-frame deficit from the record trigger (t0): muxed frames missing relative to input
     * frames, never negative. A zero gap is the no-dropped-startup-frame guarantee.
     */
    fun headFrameGap(inputFramesFromT0: Long, muxedFramesFromT0: Long): Long =
        (inputFramesFromT0 - muxedFramesFromT0).coerceAtLeast(0L)

    /** One frame interval in microseconds at the active fps (0 when `fps <= 0`). */
    fun frameIntervalUs(fps: Int): Long =
        if (fps > 0) MICROSECONDS_PER_SECOND / fps else 0L

    /**
     * True iff the record-start->first-frame latency is within one frame interval at `fps`
     * (the sub-frame record-start latency budget). Requires a measured, non-negative latency.
     */
    fun recordStartLatencyWithinBudget(latencyUs: Long, fps: Int): Boolean =
        fps > 0 && latencyUs in 0L..frameIntervalUs(fps)

    /**
     * Evidence string for the latency budget: [NOT_MEASURED] for the `-1` sentinel (never a
     * synthesized PASS), otherwise the within-budget boolean.
     */
    fun recordStartLatencyEvidence(latencyUs: Long, fps: Int): String =
        if (latencyUs < 0L) NOT_MEASURED else recordStartLatencyWithinBudget(latencyUs, fps).toString()

    /**
     * Evidence string for the head-frame gap: [NOT_MEASURED] for the `-1` sentinel (the gap is only
     * meaningful with a real on-device input-frame counter), otherwise the clamped gap count.
     */
    fun headFrameGapEvidence(headFrameGap: Int): String =
        if (headFrameGap < 0) NOT_MEASURED else headFrameGap.toString()
}
