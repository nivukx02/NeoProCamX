package com.neoproc.neoproccamx.ai

/**
 * s7-f02 / CP93 — pure identity-lock hysteresis for FaceTracker.
 *
 * Gated behind [FaceTrackerHardeningFlag]; never consulted while the flag is OFF (the caller passes a
 * null locked id so [lockBias] is always 0 and the existing association is unchanged).
 *
 * The "locked" identity is simply the most-established track (highest hit-streak, at least
 * [MIN_LOCK_STREAK] consecutive hits). Giving it a small extra match score makes a marginally-better
 * competing detection unable to steal its track ID — eliminating spurious ID churn under brief
 * occlusion or a transient stronger face. It is fed into the EXISTING
 * [AIDetectionTrackingPolicy.matchTrackingId] via its candidateScoreAdjuster hook, so no competing
 * association is created.
 */
internal object FaceIdentityLockPolicy {

    /** Extra match-score bonus applied to the locked track (kept small, within the match band). */
    const val LOCK_STICKINESS = 0.06f
    const val LOCK_STICKINESS_STRICT = 0.08f

    /** Minimum consecutive hits for a track to be considered an established, lockable identity. */
    const val MIN_LOCK_STREAK = 2

    /**
     * The most-established track id (max hit-streak >= [MIN_LOCK_STREAK]), or null if none qualifies.
     * Deterministic: on a streak tie the lowest id wins. PURE.
     */
    fun selectLockedId(trackingStates: Map<Int, MotionState>): Int? {
        var bestId: Int? = null
        var bestStreak = MIN_LOCK_STREAK - 1
        for ((id, state) in trackingStates) {
            val streak = state.hitStreak
            val current = bestId
            if (streak > bestStreak || (streak == bestStreak && current != null && id < current)) {
                if (streak >= MIN_LOCK_STREAK) {
                    bestStreak = streak
                    bestId = id
                }
            }
        }
        return bestId
    }

    /** Stickiness bonus for [candidateId] iff it is the locked identity; 0 otherwise. PURE. */
    fun lockBias(candidateId: Int, lockedId: Int?, stickiness: Float): Float =
        if (lockedId != null && candidateId == lockedId) stickiness else 0f
}
