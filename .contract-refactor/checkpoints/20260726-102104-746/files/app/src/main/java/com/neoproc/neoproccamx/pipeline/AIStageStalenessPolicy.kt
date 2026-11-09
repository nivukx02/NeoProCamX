package com.neoproc.neoproccamx.pipeline

import kotlin.math.max

/**
 * s7-f01 / CP92 — bounded-staleness math for the fused AI bundle.
 *
 * A fused bundle mixes fresh stages with staleness-cached stages (a stage that skipped this
 * frame contributes its last value). Consumers (focus/exposure) must be able to reject a
 * bundle whose OLDEST contributing stage is too old, to avoid acting on lagged geometry.
 *
 * Pure functions; no Android types. Dormant unless [SensorPacedFrameSelectorFlag.enabled].
 */
internal object AIStageStalenessPolicy {

    /**
     * @param currentFrameIndex the fused frame's own index.
     * @param contributingStageFrameIndices the frame index at which each contributing stage was
     *   last refreshed. Empty => age 0 (nothing to be stale about).
     * @return how many frames old the OLDEST contributing stage is (>= 0).
     */
    fun oldestStageAgeFrames(
        currentFrameIndex: Long,
        contributingStageFrameIndices: LongArray
    ): Long {
        if (contributingStageFrameIndices.isEmpty()) return 0L
        var oldest = currentFrameIndex
        for (idx in contributingStageFrameIndices) {
            if (idx < oldest) oldest = idx
        }
        return max(0L, currentFrameIndex - oldest)
    }

    /**
     * @param currentTimestampMs the fused frame's timestamp (ms).
     * @param contributingStageTimestampsMs last-refresh timestamp (ms) of each contributing stage.
     * @return milliseconds since the OLDEST contributing stage was refreshed (>= 0).
     */
    fun oldestStageAgeMs(
        currentTimestampMs: Long,
        contributingStageTimestampsMs: LongArray
    ): Long {
        if (contributingStageTimestampsMs.isEmpty()) return 0L
        var oldest = currentTimestampMs
        for (ts in contributingStageTimestampsMs) {
            if (ts < oldest) oldest = ts
        }
        return max(0L, currentTimestampMs - oldest)
    }

    /**
     * Consumer-side gate. @return true if the bundle is fresh enough to act on.
     * A non-positive [budgetMs] disables the gate (always fresh), preserving today's behavior
     * for any consumer that has not opted into a budget.
     */
    fun isFreshEnough(oldestStageAgeMs: Long, budgetMs: Long): Boolean {
        if (budgetMs <= 0L) return true
        return oldestStageAgeMs <= budgetMs
    }
}
