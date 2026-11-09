package com.neoproc.neoproccamx.ai

/**
 * s7-f07 / CP97 — pure N-consecutive-frame debounce for [SceneDetector] scene changes.
 *
 * SceneDetector already gates scene changes by a confidence MARGIN (a low-confidence new scene is
 * rejected). The missing piece is a DEBOUNCE: a single confident frame can still flip the scene,
 * pumping exposure/tone. This policy requires a confident new scene to persist for N consecutive
 * frames before it is committed. Gated behind [SceneHysteresisFlag]; never consulted while OFF, so
 * the default single-confident-frame behavior is bit-for-bit preserved.
 */
internal object SceneStateHysteresisPolicy {

    /** Consecutive confident frames a NEW scene must hold before it is committed. */
    const val REQUIRED_CONSECUTIVE = 3
    const val REQUIRED_CONSECUTIVE_STRICT = 2

    fun requiredConsecutive(strictHighEnd: Boolean): Int =
        if (strictHighEnd) REQUIRED_CONSECUTIVE_STRICT else REQUIRED_CONSECUTIVE

    /** Result of one debounce step. [commit] true => switch now; else keep the committed scene. */
    data class Debounce(val commit: Boolean, val pendingScene: SceneType?, val pendingCount: Int)

    /**
     * Advance the debounce given a confident candidate scene that DIFFERS from the committed one.
     * PURE. A run of the same candidate accumulates; a different candidate restarts the run.
     */
    fun advance(
        candidateScene: SceneType,
        pendingScene: SceneType?,
        pendingCount: Int,
        requiredConsecutive: Int
    ): Debounce {
        val required = requiredConsecutive.coerceAtLeast(1)
        val count = if (candidateScene == pendingScene) pendingCount + 1 else 1
        val commit = count >= required
        return Debounce(
            commit = commit,
            pendingScene = if (commit) null else candidateScene,
            pendingCount = if (commit) 0 else count
        )
    }
}
