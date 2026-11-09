package com.neoproc.neoproccamx.pipeline

import java.util.Locale
import kotlin.math.max

internal enum class AiMotionCompensationState {
    DISABLED,
    FALLBACK_UNSUPPORTED,
    // Enabled by the user but the neural optical-flow path is unavailable (no model / NNAPI /
    // zero-copy). Instead of an inert fallback we run a device-independent, motion-adaptive temporal
    // denoise strengthening — bounded by the motion-safe ceiling so it never smears moving subjects.
    // It explicitly does NOT claim neural flow (neuralFlowActive stays false).
    HEURISTIC_ACTIVE,
    NEURAL_FLOW_ACTIVE
}

internal data class AiMotionCompensationRuntimeHints(
    val enabled: Boolean,
    val modelAssetPresent: Boolean,
    val modelRuntimeValidated: Boolean,
    val nnapiDelegateAvailable: Boolean,
    val vulkanHardwareImportActive: Boolean,
    val zeroCopyBufferPathActive: Boolean,
    val estimatedInferenceMs: Float,
    val flowConfidence: Float,
    val forwardBackwardConsistency: Float,
    val colorSimilarity: Float,
    val localVarianceStability: Float
) {
    companion object {
        fun disabled(): AiMotionCompensationRuntimeHints =
            AiMotionCompensationRuntimeHints(
                enabled = false,
                modelAssetPresent = false,
                modelRuntimeValidated = false,
                nnapiDelegateAvailable = false,
                vulkanHardwareImportActive = false,
                zeroCopyBufferPathActive = false,
                estimatedInferenceMs = Float.POSITIVE_INFINITY,
                flowConfidence = 0f,
                forwardBackwardConsistency = 0f,
                colorSimilarity = 0f,
                localVarianceStability = 0f
            )

        /**
         * Hints for a user-enabled request whose neural optical-flow capabilities are not (yet)
         * probed as present. The policy runs the bounded motion-adaptive heuristic; the neural path
         * stays gated behind the individual capability flags, which callers can set as they become
         * truthfully available. Passing `enabled = false` yields [disabled].
         */
        fun requested(enabled: Boolean): AiMotionCompensationRuntimeHints =
            disabled().copy(enabled = enabled)
    }
}

internal data class AiMotionCompensationRequest(
    val enabled: Boolean,
    val signalFamily: VideoImageSignalFamily,
    val recording: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val motionScore: Float,
    val baseTemporalBlend: Float,
    val motionSafeTemporalCeiling: Float,
    val qualityTemporalFloor: Float,
    val qualityTemporalCeiling: Float,
    val cinemaTemporalDenoise: Float,
    val chromaDenoise: Float,
    val lumaDenoise: Float,
    val modelAssetPresent: Boolean,
    val modelRuntimeValidated: Boolean = modelAssetPresent,
    val nnapiDelegateAvailable: Boolean,
    val vulkanHardwareImportActive: Boolean,
    val zeroCopyBufferPathActive: Boolean,
    val estimatedInferenceMs: Float,
    val flowConfidence: Float,
    val forwardBackwardConsistency: Float,
    val colorSimilarity: Float,
    val localVarianceStability: Float,
    val strictHighEnd: Boolean
)

internal data class AiMotionCompensationPlan(
    val state: AiMotionCompensationState,
    val neuralFlowActive: Boolean,
    val requiresWarpShader: Boolean,
    val usesJvmHeapPixels: Boolean,
    val effectiveTemporalBlend: Float,
    val confidenceAwareAlpha: Float,
    val occlusionFallbackWeight: Float,
    val reason: String,
    val signature: String
) {
    companion object {
        fun disabled(baseTemporalBlend: Float, reason: String): AiMotionCompensationPlan =
            AiMotionCompensationPlan(
                state = AiMotionCompensationState.DISABLED,
                neuralFlowActive = false,
                requiresWarpShader = false,
                usesJvmHeapPixels = false,
                effectiveTemporalBlend = baseTemporalBlend.coerceIn(0f, 1f),
                confidenceAwareAlpha = 0f,
                occlusionFallbackWeight = 1f,
                reason = reason,
                signature = "AI_MOTION_COMP_DISABLED|reason=$reason"
            )
    }
}

internal object AiMotionCompensationPolicy {
    private const val MAX_INFERENCE_BUDGET_MS = 3.0f

    fun resolve(request: AiMotionCompensationRequest): AiMotionCompensationPlan {
        val fallbackBlend = request.baseTemporalBlend
            .coerceAtLeast(request.qualityTemporalFloor.coerceIn(0f, 1f))
            .coerceAtMost(request.motionSafeTemporalCeiling.coerceIn(0f, 1f))
        if (!request.enabled) {
            return AiMotionCompensationPlan.disabled(fallbackBlend, "disabled")
        }
        val unsupportedReason = unsupportedReason(request)
        if (unsupportedReason != null) {
            // Enabled, but the neural path can't run: apply the honest, bounded, device-independent
            // motion-adaptive heuristic instead of an inert fallback so the toggle has a real effect.
            return heuristic(request, fallbackBlend, unsupportedReason)
        }

        val flowConfidence = request.flowConfidence.coerceIn(0f, 1f)
        val consistency = request.forwardBackwardConsistency.coerceIn(0f, 1f)
        val colorSimilarity = request.colorSimilarity.coerceIn(0f, 1f)
        val varianceStability = request.localVarianceStability.coerceIn(0f, 1f)
        val confidence = (
            flowConfidence * 0.34f +
                consistency * 0.28f +
                colorSimilarity * 0.22f +
                varianceStability * 0.16f
            ).coerceIn(0f, 1f)
        val occlusionFallback = (1f - confidence).coerceIn(0f, 1f)
        val strongSuppressionFloor = when (request.signalFamily) {
            VideoImageSignalFamily.RAW -> 0.90f
            VideoImageSignalFamily.LOG -> 0.94f
            VideoImageSignalFamily.HDR -> 0.95f
            VideoImageSignalFamily.SDR -> 0.93f
        }
        val neuralCeiling = if (request.strictHighEnd && request.recording) 0.99f else 0.96f
        val highConfidenceBlend = (
            strongSuppressionFloor +
                confidence * 0.04f +
                request.chromaDenoise.coerceIn(0f, 1f) * 0.006f +
                request.lumaDenoise.coerceIn(0f, 1f) * 0.004f
            ).coerceIn(max(fallbackBlend, request.qualityTemporalFloor), neuralCeiling)
        val lowConfidenceBlend = (
            fallbackBlend +
                (request.qualityTemporalCeiling - fallbackBlend).coerceAtLeast(0f) * confidence * 0.55f +
                request.cinemaTemporalDenoise.coerceIn(0f, 1f) * confidence * 0.08f
            // Low-confidence flow caps the blend conservatively at 0.70; but fallbackBlend (the
            // quality/noise floor) can itself exceed 0.70 on a noisy low-motion strict-SDR shot,
            // which made coerceIn(min,max) throw (max < min). Clamp the lower bound to the cap so
            // the range is never empty. No-op when fallbackBlend <= 0.70.
            ).coerceIn(minOf(fallbackBlend, 0.70f), 0.70f)
        val effectiveTemporalBlend = if (confidence >= 0.72f) {
            highConfidenceBlend
        } else {
            lowConfidenceBlend
        }
        return AiMotionCompensationPlan(
            state = AiMotionCompensationState.NEURAL_FLOW_ACTIVE,
            neuralFlowActive = true,
            requiresWarpShader = true,
            usesJvmHeapPixels = false,
            effectiveTemporalBlend = effectiveTemporalBlend,
            confidenceAwareAlpha = confidence,
            occlusionFallbackWeight = occlusionFallback,
            reason = "neural optical flow motion compensation active",
            signature = signature(request, effectiveTemporalBlend, confidence, "active")
        )
    }

    fun resolve(
        request: VideoNoiseReductionRequest,
        baseTemporalBlend: Float,
        motionSafeTemporalCeiling: Float
    ): AiMotionCompensationPlan =
        resolve(
            AiMotionCompensationRequest(
                enabled = request.aiMotionCompensation.enabled,
                signalFamily = request.signalFamily,
                recording = request.recording,
                width = 0,
                height = 0,
                fps = 0,
                motionScore = request.motionScore,
                baseTemporalBlend = baseTemporalBlend,
                motionSafeTemporalCeiling = motionSafeTemporalCeiling,
                qualityTemporalFloor = 0.08f,
                qualityTemporalCeiling = motionSafeTemporalCeiling,
                cinemaTemporalDenoise = baseTemporalBlend,
                chromaDenoise = 0f,
                lumaDenoise = 0f,
                modelAssetPresent = request.aiMotionCompensation.modelAssetPresent,
                modelRuntimeValidated = request.aiMotionCompensation.modelRuntimeValidated,
                nnapiDelegateAvailable = request.aiMotionCompensation.nnapiDelegateAvailable,
                vulkanHardwareImportActive = request.aiMotionCompensation.vulkanHardwareImportActive,
                zeroCopyBufferPathActive = request.aiMotionCompensation.zeroCopyBufferPathActive,
                estimatedInferenceMs = request.aiMotionCompensation.estimatedInferenceMs,
                flowConfidence = request.aiMotionCompensation.flowConfidence,
                forwardBackwardConsistency = request.aiMotionCompensation.forwardBackwardConsistency,
                colorSimilarity = request.aiMotionCompensation.colorSimilarity,
                localVarianceStability = request.aiMotionCompensation.localVarianceStability,
                strictHighEnd = request.strictHighEnd
            )
        )

    // How aggressively the non-neural heuristic pushes the temporal blend toward the motion-safe
    // ceiling on a perfectly still frame. Kept conservative so the opt-in effect stays gentle.
    private const val HEURISTIC_STILLNESS_GAIN = 0.6f

    /**
     * Device-independent motion-adaptive temporal denoise. On still frames it strengthens temporal
     * blending toward (but never past) the motion-safe ceiling; on moving frames it backs off to the
     * base blend to avoid ghosting. Bounded by the same ceiling the base policy already respects, so
     * enabling it stays inside the established safe envelope. Never claims neural flow.
     */
    private fun heuristic(
        request: AiMotionCompensationRequest,
        fallbackBlend: Float,
        neuralUnsupportedReason: String
    ): AiMotionCompensationPlan {
        val ceiling = request.motionSafeTemporalCeiling.coerceIn(0f, 1f)
        val stillness = (1f - request.motionScore.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val headroom = (ceiling - fallbackBlend).coerceAtLeast(0f)
        val heuristicBlend = (fallbackBlend + headroom * stillness * HEURISTIC_STILLNESS_GAIN)
            .coerceIn(fallbackBlend, ceiling)
        return AiMotionCompensationPlan(
            state = AiMotionCompensationState.HEURISTIC_ACTIVE,
            neuralFlowActive = false,
            requiresWarpShader = false,
            usesJvmHeapPixels = false,
            effectiveTemporalBlend = heuristicBlend,
            confidenceAwareAlpha = stillness,
            occlusionFallbackWeight = 1f,
            reason = "heuristic motion-adaptive temporal denoise (neural unavailable: $neuralUnsupportedReason)",
            signature = signature(request, heuristicBlend, stillness, "heuristic")
        )
    }

    private fun unsupportedReason(request: AiMotionCompensationRequest): String? =
        when {
            !request.modelAssetPresent -> "model missing"
            !request.modelRuntimeValidated -> "model runtime validation missing"
            !request.nnapiDelegateAvailable -> "NNAPI delegate unavailable"
            !request.vulkanHardwareImportActive -> "Vulkan hardware import inactive"
            !request.zeroCopyBufferPathActive -> "zero-copy buffer path inactive"
            !request.estimatedInferenceMs.isFinite() ||
                request.estimatedInferenceMs > MAX_INFERENCE_BUDGET_MS -> "inference budget exceeded"
            else -> null
        }

    private fun signature(
        request: AiMotionCompensationRequest,
        temporalBlend: Float,
        confidence: Float,
        state: String
    ): String =
        listOf(
            "AI_MOTION_COMP",
            "state=$state",
            "signal=${request.signalFamily.name}",
            "motion=${format(request.motionScore)}",
            "temporal=${format(temporalBlend)}",
            "confidence=${format(confidence)}",
            "thresholdFloor=${format(request.qualityTemporalFloor)}",
            "qualityCeiling=${format(request.qualityTemporalCeiling)}",
            "motionSafeCeiling=${format(request.motionSafeTemporalCeiling)}",
            "budgetMs=${format(request.estimatedInferenceMs)}",
            "zeroCopy=${request.zeroCopyBufferPathActive}"
        ).joinToString(separator = "|")

    private fun format(value: Float): String =
        if (value.isFinite()) {
            String.format(Locale.US, "%.3f", value.coerceIn(0f, 999f))
        } else {
            "inf"
        }
}
