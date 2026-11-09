package com.neoproc.neoproccamx.ui

/**
 * Safety net for the GPU-processed record path: when sustained validation fails ONLY because import
 * throughput fell below the selected fps (thermal load), decide whether a genuine, saveable clip already
 * exists on the encoder so it can be finalized at the ACHIEVED fps instead of aborted + deleted.
 *
 * Pure predicate — no device access, unit-testable. Gated at the call site by AlwaysSaveAtAchievedFpsFlag
 * (default OFF). Never accepts a broken clip: requires real imported frames, a bound encoder surface, and
 * a monotonic import window (so getEstimatedFps() is meaningful).
 */
internal object AlwaysSaveAtAchievedFpsPolicy {
    /** Real-footage floor: excludes 1-2 frame stubs and keeps the achieved-fps estimate computable. */
    const val MIN_ACCEPTABLE_ACHIEVED_FRAMES = 8L

    fun canAcceptAtAchievedFps(
        frameImportEnabled: Boolean,
        encoderSurfaceBound: Boolean,
        importedFrames: Long,
        firstSuccessfulImportElapsedMs: Long,
        lastSuccessfulImportElapsedMs: Long
    ): Boolean =
        frameImportEnabled &&
            encoderSurfaceBound &&
            importedFrames >= MIN_ACCEPTABLE_ACHIEVED_FRAMES &&
            firstSuccessfulImportElapsedMs > 0L &&
            lastSuccessfulImportElapsedMs > firstSuccessfulImportElapsedMs
}
