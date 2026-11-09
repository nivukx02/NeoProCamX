package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStrictStopFrameGatePolicyTest {
    @Test
    fun thermalSourceCadenceDriftDoesNotInventAnApplicationDrop() {
        val decision = evaluate(
            wallExpectedFrameCount = 15_614L,
            submittedFrameCount = 15_601L,
            encodedSampleCount = 15_601L
        )

        assertTrue(decision.integrityPassed)
        assertEquals(15_601L, decision.minimumEncodedSamples)
        assertEquals(13L, decision.sourceCadenceShortfallFrames)
        assertEquals(0L, decision.applicationLossFrames)
    }

    @Test
    fun acceptedGpuFrameMissingFromEncoderFailsPublication() {
        val decision = evaluate(
            wallExpectedFrameCount = 15_614L,
            submittedFrameCount = 15_601L,
            encodedSampleCount = 15_600L
        )

        assertFalse(decision.integrityPassed)
        assertEquals(1L, decision.applicationLossFrames)
        assertTrue(decision.failureReasons.any { it.contains("accepted/encoded mismatch") })
    }

    @Test
    fun invalidPtsCadenceFailsEvenWhenCountsMatch() {
        val decision = evaluate(
            wallExpectedFrameCount = 250L,
            submittedFrameCount = 250L,
            encodedSampleCount = 250L,
            encoderCadenceWithinTolerance = false,
            nonMonotonicPtsCount = 1L
        )

        assertFalse(decision.integrityPassed)
        assertTrue(decision.failureReasons.any { it.contains("non-monotonic") })
        assertTrue(decision.failureReasons.any { it.contains("cadence") })
    }

    @Test
    fun explicitGpuOrIdentityLossFailsWithoutChangingQualityTargets() {
        val decision = evaluate(
            wallExpectedFrameCount = 250L,
            submittedFrameCount = 250L,
            encodedSampleCount = 250L,
            hotPathDroppedFrames = 2L,
            frameIdentityLosses = 1L,
            pendingPresentations = 1L,
            unmatchedEncoderOutputs = 1L
        )

        assertFalse(decision.integrityPassed)
        assertEquals(5L, decision.applicationLossFrames)
    }

    @Test
    fun disabledGatePreservesNonStrictPipelineBehavior() {
        val decision = evaluate(
            enabled = false,
            wallExpectedFrameCount = 250L,
            submittedFrameCount = 0L,
            encodedSampleCount = 0L,
            encoderCadenceWithinTolerance = false
        )

        assertFalse(decision.required)
        assertTrue(decision.integrityPassed)
    }

    private fun evaluate(
        enabled: Boolean = true,
        wallExpectedFrameCount: Long,
        submittedFrameCount: Long,
        encodedSampleCount: Long,
        hotPathDroppedFrames: Long = 0L,
        frameIdentityLosses: Long = 0L,
        pendingPresentations: Long = 0L,
        unmatchedEncoderOutputs: Long = 0L,
        encoderCadenceWithinTolerance: Boolean = true,
        nonMonotonicPtsCount: Long = 0L
    ): RecordingStrictStopFrameGateDecision =
        RecordingStrictStopFrameGatePolicy.evaluate(
            RecordingStrictStopFrameGateRequest(
                enabled = enabled,
                wallExpectedFrameCount = wallExpectedFrameCount,
                submittedFrameCount = submittedFrameCount,
                encodedSampleCount = encodedSampleCount,
                hotPathDroppedFrames = hotPathDroppedFrames,
                frameIdentityLosses = frameIdentityLosses,
                pendingPresentations = pendingPresentations,
                unmatchedEncoderOutputs = unmatchedEncoderOutputs,
                encoderCadenceWithinTolerance = encoderCadenceWithinTolerance,
                nonMonotonicPtsCount = nonMonotonicPtsCount
            )
        )
}
