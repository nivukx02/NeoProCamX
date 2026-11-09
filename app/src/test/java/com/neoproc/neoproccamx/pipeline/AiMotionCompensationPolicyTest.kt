package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMotionCompensationPolicyTest {

    @Test
    fun missingModelRunsHeuristicWithoutClaimingNeuralFlow() {
        val plan = resolve(modelAssetPresent = false, nnapiDelegateAvailable = true)

        // Enabled but neural unavailable -> honest device-independent heuristic. It never claims
        // neural flow or a warp shader, and it stays within the motion-safe ceiling.
        assertEquals(AiMotionCompensationState.HEURISTIC_ACTIVE, plan.state)
        assertFalse(plan.neuralFlowActive)
        assertFalse(plan.requiresWarpShader)
        assertFalse(plan.usesJvmHeapPixels)
        assertTrue(plan.reason.contains("model missing"))
        assertTrue(plan.effectiveTemporalBlend <= 0.46f)
    }

    @Test
    fun highConfidenceNeuralFlowAllowsStrongTemporalSuppressionWithoutJvmPixels() {
        val plan = resolve(
            modelAssetPresent = true,
            nnapiDelegateAvailable = true,
            vulkanHardwareImportActive = true,
            zeroCopyBufferPathActive = true,
            flowConfidence = 0.94f,
            forwardBackwardConsistency = 0.92f,
            colorSimilarity = 0.90f,
            localVarianceStability = 0.88f
        )

        assertEquals(AiMotionCompensationState.NEURAL_FLOW_ACTIVE, plan.state)
        assertTrue(plan.neuralFlowActive)
        assertTrue(plan.requiresWarpShader)
        assertFalse(plan.usesJvmHeapPixels)
        assertTrue("temporal=${plan.effectiveTemporalBlend}", plan.effectiveTemporalBlend >= 0.95f)
        assertTrue("temporal=${plan.effectiveTemporalBlend}", plan.effectiveTemporalBlend <= 0.99f)
        assertTrue(plan.signature.contains("thresholdFloor=0.180"))
        assertTrue(plan.signature.contains("qualityCeiling=0.540"))
    }

    @Test
    fun lowWarpConfidenceScalesBackBlendToAvoidOcclusionSmearing() {
        val highConfidence = resolve(
            modelAssetPresent = true,
            nnapiDelegateAvailable = true,
            flowConfidence = 0.94f,
            forwardBackwardConsistency = 0.92f,
            colorSimilarity = 0.90f,
            localVarianceStability = 0.88f
        )
        val lowConfidence = resolve(
            modelAssetPresent = true,
            nnapiDelegateAvailable = true,
            flowConfidence = 0.22f,
            forwardBackwardConsistency = 0.20f,
            colorSimilarity = 0.30f,
            localVarianceStability = 0.25f
        )

        assertEquals(AiMotionCompensationState.NEURAL_FLOW_ACTIVE, lowConfidence.state)
        assertTrue(lowConfidence.effectiveTemporalBlend < highConfidence.effectiveTemporalBlend)
        assertTrue(lowConfidence.effectiveTemporalBlend <= 0.70f)
        assertTrue(lowConfidence.occlusionFallbackWeight > highConfidence.occlusionFallbackWeight)
    }

    @Test
    fun strictFrameBudgetFailureFallsToHeuristicWithinMotionSafeCeiling() {
        val plan = resolve(
            modelAssetPresent = true,
            nnapiDelegateAvailable = true,
            estimatedInferenceMs = 4.2f
        )

        assertEquals(AiMotionCompensationState.HEURISTIC_ACTIVE, plan.state)
        assertFalse(plan.neuralFlowActive)
        assertTrue(plan.reason.contains("budget"))
        assertTrue(plan.effectiveTemporalBlend <= 0.46f)
    }

    @Test
    fun presentModelStillRequiresRuntimeTensorValidationBeforeNeuralFlow() {
        val plan = resolve(
            modelAssetPresent = true,
            modelRuntimeValidated = false,
            nnapiDelegateAvailable = true
        )

        assertEquals(AiMotionCompensationState.HEURISTIC_ACTIVE, plan.state)
        assertFalse(plan.neuralFlowActive)
        assertTrue(plan.reason.contains("runtime validation"))
    }

    @Test
    fun disabledRequestIsInertAndEchoesTheBaseBlend() {
        val plan = resolve(enabled = false, modelAssetPresent = false)

        assertEquals(AiMotionCompensationState.DISABLED, plan.state)
        assertFalse(plan.neuralFlowActive)
        // Off = byte-identical to the base temporal blend (baseTemporalBlend=0.34 within the range).
        assertEquals(0.34f, plan.effectiveTemporalBlend, 1e-4f)
    }

    @Test
    fun heuristicStrengthensStillFramesButBacksOffOnMotionAndNeverExceedsCeiling() {
        // Still frame -> blend rises above the base but stays at/under the motion-safe ceiling.
        val still = resolveHeuristic(motionScore = 0.0f, baseTemporalBlend = 0.30f, motionSafeTemporalCeiling = 0.80f)
        assertEquals(AiMotionCompensationState.HEURISTIC_ACTIVE, still.state)
        assertTrue("still=${still.effectiveTemporalBlend}", still.effectiveTemporalBlend > 0.30f)
        assertTrue("still=${still.effectiveTemporalBlend}", still.effectiveTemporalBlend <= 0.80f)

        // Fully moving -> no strengthening, blend echoes the base (anti-ghosting).
        val moving = resolveHeuristic(motionScore = 1.0f, baseTemporalBlend = 0.30f, motionSafeTemporalCeiling = 0.80f)
        assertEquals(0.30f, moving.effectiveTemporalBlend, 1e-4f)
        assertTrue(moving.effectiveTemporalBlend < still.effectiveTemporalBlend)
    }

    private fun resolveHeuristic(
        motionScore: Float,
        baseTemporalBlend: Float,
        motionSafeTemporalCeiling: Float
    ): AiMotionCompensationPlan =
        AiMotionCompensationPolicy.resolve(
            AiMotionCompensationRequest(
                enabled = true,
                signalFamily = VideoImageSignalFamily.SDR,
                recording = true,
                width = 1920, height = 1080, fps = 30,
                motionScore = motionScore,
                baseTemporalBlend = baseTemporalBlend,
                motionSafeTemporalCeiling = motionSafeTemporalCeiling,
                qualityTemporalFloor = 0.08f,
                qualityTemporalCeiling = motionSafeTemporalCeiling,
                cinemaTemporalDenoise = 0.40f,
                chromaDenoise = 0.0f,
                lumaDenoise = 0.0f,
                modelAssetPresent = false,          // neural unavailable -> heuristic path
                modelRuntimeValidated = false,
                nnapiDelegateAvailable = false,
                vulkanHardwareImportActive = false,
                zeroCopyBufferPathActive = false,
                estimatedInferenceMs = Float.POSITIVE_INFINITY,
                flowConfidence = 0f,
                forwardBackwardConsistency = 0f,
                colorSimilarity = 0f,
                localVarianceStability = 0f,
                strictHighEnd = true
            )
        )

    private fun resolve(
        enabled: Boolean = true,
        modelAssetPresent: Boolean = true,
        modelRuntimeValidated: Boolean = modelAssetPresent,
        nnapiDelegateAvailable: Boolean = false,
        vulkanHardwareImportActive: Boolean = true,
        zeroCopyBufferPathActive: Boolean = true,
        estimatedInferenceMs: Float = 2.4f,
        flowConfidence: Float = 0.90f,
        forwardBackwardConsistency: Float = 0.90f,
        colorSimilarity: Float = 0.90f,
        localVarianceStability: Float = 0.90f
    ): AiMotionCompensationPlan =
        AiMotionCompensationPolicy.resolve(
            AiMotionCompensationRequest(
                enabled = enabled,
                signalFamily = VideoImageSignalFamily.HDR,
                recording = true,
                width = 3840,
                height = 2160,
                fps = 60,
                motionScore = 0.72f,
                baseTemporalBlend = 0.34f,
                motionSafeTemporalCeiling = 0.46f,
                qualityTemporalFloor = 0.18f,
                qualityTemporalCeiling = 0.54f,
                cinemaTemporalDenoise = 0.40f,
                chromaDenoise = 0.90f,
                lumaDenoise = 0.74f,
                modelAssetPresent = modelAssetPresent,
                modelRuntimeValidated = modelRuntimeValidated,
                nnapiDelegateAvailable = nnapiDelegateAvailable,
                vulkanHardwareImportActive = vulkanHardwareImportActive,
                zeroCopyBufferPathActive = zeroCopyBufferPathActive,
                estimatedInferenceMs = estimatedInferenceMs,
                flowConfidence = flowConfidence,
                forwardBackwardConsistency = forwardBackwardConsistency,
                colorSimilarity = colorSimilarity,
                localVarianceStability = localVarianceStability,
                strictHighEnd = true
            )
        )
}
