package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AIFocusDistanceControllerTest {

    @Test
    fun estimateTargetDiopters_returnsNullWhenDepthIsTooLow() {
        val diopters = AIFocusDistanceController.estimateTargetDiopters(
            normalizedDepth = 0.001f,
            maxDiopters = 10f
        )

        assertEquals(null, diopters)
    }

    @Test
    fun estimateTargetDiopters_scalesDepthIntoLensRange() {
        val diopters = AIFocusDistanceController.estimateTargetDiopters(
            normalizedDepth = 0.5f,
            maxDiopters = 8f
        )

        assertNotNull(diopters)
        assertTrue(diopters!! > 0.9f)
        assertTrue(diopters < 8f)
    }

    @Test
    fun applyPid_movesTowardTargetAndPersistsState() {
        val result = AIFocusDistanceController.applyPid(
            targetDiopters = 6f,
            maxDiopters = 10f,
            state = FocusPidState(lastAppliedDiopters = 2f, lastUpdateNs = 1_000_000_000L),
            nowNs = 1_016_000_000L
        )

        assertTrue(result.appliedDiopters > 2f)
        assertEquals(result.appliedDiopters, result.nextState.lastAppliedDiopters, 0.0001f)
        assertEquals(1_016_000_000L, result.nextState.lastUpdateNs)
    }

    @Test
    fun shouldApply_ignoresTinyLensChanges() {
        assertFalse(AIFocusDistanceController.shouldApply(1.00f, 1.02f))
        assertTrue(AIFocusDistanceController.shouldApply(1.00f, 1.06f))
    }

    @Test
    fun resolveDepthAwareFocusStep_tracksConfidentEdgesWithoutLensHunt() {
        val result = AIFocusDistanceController.resolveDepthAwareFocusStep(
            normalizedDepth = 0.54f,
            maxDiopters = 8f,
            subjectConfidence = 0.92f,
            edgeConfidence = 0.86f,
            motionScore = 0.08f,
            state = FocusPidState(lastAppliedDiopters = 2.0f, lastUpdateNs = 1_000_000_000L),
            nowNs = 1_016_000_000L
        )

        assertNotNull(result.targetDiopters)
        assertTrue(result.shouldApply)
        assertTrue(result.appliedDiopters > 2.05f)
        assertTrue(result.appliedDiopters < result.targetDiopters!!)
        assertEquals(result.appliedDiopters, result.nextState.lastAppliedDiopters, 0.0001f)
    }

    @Test
    fun resolveDepthAwareFocusStep_holdsLowConfidenceDepth() {
        val result = AIFocusDistanceController.resolveDepthAwareFocusStep(
            normalizedDepth = 0.54f,
            maxDiopters = 8f,
            subjectConfidence = 0.34f,
            edgeConfidence = 0.18f,
            motionScore = 0.20f,
            state = FocusPidState(lastAppliedDiopters = 2.0f, lastUpdateNs = 1_000_000_000L),
            nowNs = 1_016_000_000L
        )

        assertEquals(null, result.targetDiopters)
        assertFalse(result.shouldApply)
        assertEquals(2.0f, result.appliedDiopters, 0.0001f)
    }

    @Test
    fun resolveDepthAwareFocusStep_holdsLowEdgeEvidenceEvenWithConfidentSubject() {
        val result = AIFocusDistanceController.resolveDepthAwareFocusStep(
            normalizedDepth = 0.54f,
            maxDiopters = 8f,
            subjectConfidence = 0.96f,
            edgeConfidence = 0.16f,
            motionScore = 0.05f,
            state = FocusPidState(lastAppliedDiopters = 2.4f, lastUpdateNs = 1_000_000_000L),
            nowNs = 1_016_000_000L
        )

        assertEquals(null, result.targetDiopters)
        assertFalse(result.shouldApply)
        assertEquals(2.4f, result.appliedDiopters, 0.0001f)
    }
}
