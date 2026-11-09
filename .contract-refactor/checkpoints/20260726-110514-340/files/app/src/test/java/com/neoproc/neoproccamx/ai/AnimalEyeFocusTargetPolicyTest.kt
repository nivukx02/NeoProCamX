package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AF-SD (F08) — pure golden for [AnimalEyeFocusTargetPolicy]. All values hand-computed from the raw
 * left/top/right/bottom floats, so the test is independent of the RectF mock.
 */
class AnimalEyeFocusTargetPolicyTest {

    private val eps = 1e-3f

    @Test
    fun landscapeRightwardDog_anchorsUpperFrontRightEdge() {
        // box w=0.6 h=0.3 aspect=2.0 (landscape); vx>0 => front is the RIGHT edge.
        val t = AnimalEyeFocusTargetPolicy.resolve(
            left = 0.2f, top = 0.3f, right = 0.8f, bottom = 0.6f,
            predictedX = 0f, predictedY = 0f, velocityX = 0.02f, confidence = 0.9f
        )!!
        assertEquals(SubjectType.EYE, t.subjectType)
        assertEquals(0.8f - 0.6f * 0.18f, t.focusX, eps) // 0.692
        assertEquals(0.3f + 0.3f * 0.28f, t.focusY, eps) // 0.384
        assertEquals(0.9f, t.confidence, eps)
    }

    @Test
    fun landscapeLeftwardDog_mirrorsToLeftEdge() {
        val t = AnimalEyeFocusTargetPolicy.resolve(
            left = 0.2f, top = 0.3f, right = 0.8f, bottom = 0.6f,
            predictedX = 0f, predictedY = 0f, velocityX = -0.02f, confidence = 0.9f
        )!!
        assertEquals(0.2f + 0.6f * 0.18f, t.focusX, eps) // 0.308
        assertEquals(0.3f + 0.3f * 0.28f, t.focusY, eps) // 0.384
    }

    @Test
    fun tallBoxNoMotion_centresHorizontallyUpperBand() {
        // Portrait box (bird/cat facing camera); vx=0, predictedX at centre => no front => centre X.
        val t = AnimalEyeFocusTargetPolicy.resolve(
            left = 0.4f, top = 0.2f, right = 0.6f, bottom = 0.7f,
            predictedX = 0.5f, predictedY = 0.45f, velocityX = 0f, confidence = 0.7f
        )!!
        assertEquals(0.5f, t.focusX, eps)
        assertEquals(0.2f + 0.5f * 0.28f, t.focusY, eps) // 0.34
    }

    @Test
    fun tallBoxPredictedOffset_biasesTowardPredictedFront() {
        // vx ambiguous, predictedX right-of-centre => front is the right edge, portrait inset 0.30.
        val t = AnimalEyeFocusTargetPolicy.resolve(
            left = 0.4f, top = 0.2f, right = 0.6f, bottom = 0.7f,
            predictedX = 0.58f, predictedY = 0.45f, velocityX = 0f, confidence = 0.7f
        )!!
        assertEquals(0.6f - 0.2f * 0.30f, t.focusX, eps) // 0.54
    }

    @Test
    fun degenerateBox_returnsNull() {
        assertNull(
            AnimalEyeFocusTargetPolicy.resolve(
                left = 0.6f, top = 0.3f, right = 0.4f, bottom = 0.6f,
                predictedX = 0f, predictedY = 0f, velocityX = 0.02f, confidence = 0.9f
            )
        )
    }

    @Test
    fun belowMinConfidence_returnsNull() {
        assertNull(
            AnimalEyeFocusTargetPolicy.resolve(
                left = 0.2f, top = 0.3f, right = 0.8f, bottom = 0.6f,
                predictedX = 0f, predictedY = 0f, velocityX = 0.02f, confidence = 0f
            )
        )
    }
}
