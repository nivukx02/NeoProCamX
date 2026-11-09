package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for the round-2 crash in [AiMotionCompensationPolicy]: when the quality/noise
 * floor (fallbackBlend) exceeds the low-confidence cap (0.70) — a noisy, low-motion strict-SDR
 * recording scene — `lowConfidenceBlend.coerceIn(fallbackBlend, 0.70f)` threw an empty-range
 * IllegalArgumentException. The fix clamps the lower bound to the cap.
 */
class AiMotionCompensationPolicyCrashTest {

    private fun crashCaseRequest() = AiMotionCompensationRequest(
        enabled = true,
        signalFamily = VideoImageSignalFamily.SDR,
        recording = true,
        width = 3840, height = 2160, fps = 30,
        motionScore = 0.10f,                 // low motion -> high motion-safe ceiling
        baseTemporalBlend = 0.85f,           // > 0.70 -> fallbackBlend > the low-confidence cap
        motionSafeTemporalCeiling = 0.88f,
        qualityTemporalFloor = 0.08f,
        qualityTemporalCeiling = 0.90f,
        cinemaTemporalDenoise = 0.40f,
        chromaDenoise = 0.90f,
        lumaDenoise = 0.74f,
        modelAssetPresent = true,
        modelRuntimeValidated = true,
        nnapiDelegateAvailable = true,       // all support flags -> neural-flow branch executes
        vulkanHardwareImportActive = true,
        zeroCopyBufferPathActive = true,
        estimatedInferenceMs = 2.0f,
        flowConfidence = 0.50f,              // low confidence -> the low-confidence blend is used
        forwardBackwardConsistency = 0.50f,
        colorSimilarity = 0.50f,
        localVarianceStability = 0.50f,
        strictHighEnd = true
    )

    @Test
    fun `does not throw when the quality floor exceeds the low-confidence cap`() {
        val plan = AiMotionCompensationPolicy.resolve(crashCaseRequest()) // must not throw
        assertTrue(plan.effectiveTemporalBlend in 0f..1f)
        // Low confidence (< 0.72) selects the low-confidence blend, capped at 0.70.
        assertEquals(0.70f, plan.effectiveTemporalBlend, 1e-4f)
    }
}
