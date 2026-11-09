package com.neoproc.neoproccamx.ai

/**
 * AF-SD (F09) — pure classifier: UPPER_BODY vs FULL_BODY from lower-body landmark visibility.
 * Uses MediaPipe pose indices for hips/knees/ankles; index-safe via getOrNull so a short landmark
 * list can never throw.
 */
internal object BodySpanClassifierPolicy {
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28

    /** A lower-body landmark counts as visible at/above this visibility. */
    const val LOWER_VISIBILITY_FLOOR = 0.30f

    /** Need at least this many visible lower-body landmarks to call it FULL_BODY. */
    const val MIN_VISIBLE_LOWER = 2

    fun classify(landmarks: List<PoseLandmarkPoint>): SubjectType {
        val lower = intArrayOf(LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE)
        val visible = lower.count { index ->
            (landmarks.getOrNull(index)?.visibility ?: 0f) >= LOWER_VISIBILITY_FLOOR
        }
        return if (visible >= MIN_VISIBLE_LOWER) SubjectType.FULL_BODY else SubjectType.UPPER_BODY
    }
}
