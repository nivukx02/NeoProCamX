package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** AF-SD (F09) — pure UPPER_BODY vs FULL_BODY classification from lower-body landmark visibility. */
class BodySpanClassifierPolicyTest {

    private fun landmarks(visibleLowerIndices: Set<Int>): List<PoseLandmarkPoint> =
        (0 until 33).map { i ->
            PoseLandmarkPoint(
                x = 0.5f, y = 0.5f, z = 0f,
                visibility = if (i in visibleLowerIndices) 0.9f else 0f
            )
        }

    @Test
    fun hipsAndKneesVisible_isFullBody() {
        val r = BodySpanClassifierPolicy.classify(landmarks(setOf(23, 24, 25, 26)))
        assertEquals(SubjectType.FULL_BODY, r)
    }

    @Test
    fun lowerBodyInvisible_isUpperBody() {
        assertEquals(SubjectType.UPPER_BODY, BodySpanClassifierPolicy.classify(landmarks(emptySet())))
    }

    @Test
    fun emptyLandmarks_isUpperBody_noIndexOob() {
        assertEquals(SubjectType.UPPER_BODY, BodySpanClassifierPolicy.classify(emptyList()))
    }

    @Test
    fun exactlyTwoVisibleLower_isFullBody_boundary() {
        assertEquals(
            SubjectType.FULL_BODY,
            BodySpanClassifierPolicy.classify(landmarks(setOf(23, 24)))
        )
    }
}
