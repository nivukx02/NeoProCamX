package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AIDetectionTrackingPolicyTest {

    @Test
    fun deriveFaceConfidence_rewardsEyeCoverageAndHealthyBounds() {
        val confidence = AIDetectionTrackingPolicy.deriveFaceConfidence(
            boundingBox = AIDetectionBox(0.20f, 0.20f, 0.52f, 0.66f),
            leftEye = EyePoint(0.30f, 0.34f, 0.95f),
            rightEye = EyePoint(0.42f, 0.34f, 0.94f)
        )

        assertTrue(confidence > 0.85f)
    }

    @Test
    fun shouldAcceptDetection_rejectsTinyWeakObject() {
        val accepted = AIDetectionTrackingPolicy.shouldAcceptDetection(
            profile = AIDetectionTrackingPolicy.profile(AIDetectionKind.OBJECT),
            boundingBox = AIDetectionBox(0.10f, 0.10f, 0.12f, 0.12f),
            confidence = 0.22f
        )

        assertFalse(accepted)
    }

    @Test
    fun sanitizeObjectLabel_prefersMeaningfulFallbackOverGenericPrimary() {
        val resolved = AIDetectionTrackingPolicy.sanitizeObjectLabel(
            primaryLabel = "Object",
            fallbackLabel = "bird",
            confidence = 0.91f,
            profile = AIDetectionTrackingPolicy.profile(AIDetectionKind.OBJECT)
        )

        assertEquals("Bird", resolved)
    }

    @Test
    fun matchTrackingId_requiresStrongerOverlapForWeakTinyDetection() {
        val profile = AIDetectionTrackingPolicy.profile(AIDetectionKind.OBJECT)
        val trackingStates = linkedMapOf(
            7 to MotionState(
                centerX = 0.48f,
                centerY = 0.48f,
                velocityX = 0f,
                velocityY = 0f,
                confidence = 0.8f,
                updatedAtMs = 1_000L
            )
        )

        val weakMatch = AIDetectionTrackingPolicy.matchTrackingId(
            candidateIds = trackingStates.keys,
            trackingStates = trackingStates,
            detectionBounds = AIDetectionBox(0.414f, 0.414f, 0.484f, 0.484f),
            confidence = 0.34f,
            profile = profile,
            timestampMs = 1_050L
        )
        val strongMatch = AIDetectionTrackingPolicy.matchTrackingId(
            candidateIds = trackingStates.keys,
            trackingStates = trackingStates,
            detectionBounds = AIDetectionBox(0.414f, 0.414f, 0.484f, 0.484f),
            confidence = 0.90f,
            profile = profile,
            timestampMs = 1_050L
        )

        assertNull(weakMatch)
        assertEquals(7, strongMatch)
    }

    @Test
    fun matchTrackingId_prefers_recent_confirmed_track_during_short_reacquire_gap() {
        val profile = AIDetectionTrackingPolicy.profile(AIDetectionKind.FACE)
        val trackingStates = linkedMapOf(
            3 to MotionState(
                centerX = 0.50f,
                centerY = 0.46f,
                velocityX = 0.04f,
                velocityY = 0.01f,
                confidence = 0.92f,
                updatedAtMs = 2_000L,
                width = 0.22f,
                height = 0.34f,
                hitStreak = 5,
                missCount = 1,
                lastMatchedAtMs = 1_960L
            )
        )

        val match = AIDetectionTrackingPolicy.matchTrackingId(
            candidateIds = trackingStates.keys,
            trackingStates = trackingStates,
            detectionBounds = AIDetectionBox(0.43f, 0.29f, 0.63f, 0.63f),
            confidence = 0.56f,
            profile = profile,
            timestampMs = 2_020L
        )

        assertEquals(3, match)
    }

    @Test
    fun registerTrackingMisses_incrementsMissCountAndDampsVelocity() {
        val profile = AIDetectionTrackingPolicy.profile(AIDetectionKind.OBJECT)
        val trackingStates = linkedMapOf(
            9 to MotionState(
                centerX = 0.40f,
                centerY = 0.44f,
                velocityX = 0.30f,
                velocityY = -0.18f,
                confidence = 0.88f,
                updatedAtMs = 3_000L,
                width = 0.18f,
                height = 0.24f,
                hitStreak = 4,
                missCount = 0,
                lastMatchedAtMs = 3_000L
            )
        )

        AIDetectionTrackingPolicy.registerTrackingMisses(
            trackingStates = trackingStates,
            unmatchedIds = setOf(9),
            timestampMs = 3_060L,
            profile = profile
        )

        val updated = trackingStates[9]!!
        assertEquals(1, updated.missCount)
        assertTrue(updated.velocityX < 0.30f)
        assertTrue(updated.velocityY > -0.18f)
    }

    @Test
    fun labelAffinity_rewardsExactObjectLabelMatch() {
        val profile = AIDetectionTrackingPolicy.profile(AIDetectionKind.OBJECT)

        val exact = AIDetectionTrackingPolicy.labelAffinity("Dog", "Dog", profile)
        val mismatch = AIDetectionTrackingPolicy.labelAffinity("Dog", "Car", profile)

        assertTrue(exact > 0f)
        assertTrue(mismatch < exact)
    }
}
