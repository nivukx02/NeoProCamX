package com.neoproc.neoproccamx.rendering

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Why the live preview temporal stage did not use a previous frame, or why its history was reset.
 * These values are emitted verbatim in device telemetry; NONE means that FP16 history was sampled.
 */
internal enum class PreviewTemporalFusionReason {
    NONE,
    HISTORY_WARMUP,
    FIRST_ELIGIBLE_FRAME,
    FP16_UNSUPPORTED,
    RESOURCE_UNAVAILABLE,
    OUTPUT_UNAVAILABLE,
    PRESENTATION_DISABLED,
    NON_ENHANCE_GRADE,
    DEBAYER_PIXEL_ROUTE,
    TUNING_UNAVAILABLE,
    INVALID_TIMESTAMP,
    SAME_FRAME_REDRAW,
    TIMESTAMP_NON_MONOTONIC,
    TIMESTAMP_DISCONTINUITY,
    CALIBRATION_CHANGED,
    WHITE_BALANCE_TRANSITION,
    GRADE_CHANGED,
    LUT_CHANGED,
    OUTPUT_ATTACHED,
    OUTPUT_DETACHED,
    PIXEL_ROUTE_CHANGED,
    RECORDING_PRIORITY_CHANGED,
    RECORDING_PRIORITY,
    CONTEXT_REBUILT,
    FP16_RENDER_TARGET_FAILED,
    RELEASED
}

/** Reusable input packet so the render loop does not allocate one object per camera frame. */
internal class PreviewTemporalFusionInput {
    var sensorTimestampNs: Long = 0L
    var enhanceGradeActive: Boolean = false
    var fp16TextureSupported: Boolean = false
    var historyResourcesAvailable: Boolean = false
    var outputAttached: Boolean = false
    var presentationEnabled: Boolean = false
    var debayerPixelRoute: Boolean = false
    var tuningActive: Boolean = false
    var tuningConfidence: Float = 0f
    var spatialDenoise: Float = 0f
    var chromaDenoise: Float = 0f
    var calibrationRevision: Long = 0L
    var redCorrection: Float = 1f
    var greenCorrection: Float = 1f
    var blueCorrection: Float = 1f
    var availableHistoryFrames: Int = 0
}

/** Reusable decision packet consumed directly as GLES uniforms by [PreviewGlCompositor]. */
internal class PreviewTemporalFusionDecision {
    var enabled: Boolean = false
        private set
    var acceptCurrentFrameIntoHistory: Boolean = false
        private set
    var resetHistory: Boolean = false
        private set
    var historyFrameCount: Int = 0
        private set
    var lumaStrength: Float = 0f
        private set
    var chromaStrength: Float = 0f
        private set
    var noisePressure: Float = 0f
        private set
    var reason: PreviewTemporalFusionReason = PreviewTemporalFusionReason.HISTORY_WARMUP
        private set

    internal fun setBypass(
        reason: PreviewTemporalFusionReason,
        resetHistory: Boolean,
        acceptCurrentFrameIntoHistory: Boolean
    ) {
        enabled = false
        this.acceptCurrentFrameIntoHistory = acceptCurrentFrameIntoHistory
        this.resetHistory = resetHistory
        historyFrameCount = 0
        lumaStrength = 0f
        chromaStrength = 0f
        noisePressure = 0f
        this.reason = reason
    }

    internal fun setActive(
        historyFrameCount: Int,
        lumaStrength: Float,
        chromaStrength: Float,
        noisePressure: Float
    ) {
        enabled = historyFrameCount > 0 && lumaStrength > 0f && chromaStrength > 0f
        acceptCurrentFrameIntoHistory = true
        resetHistory = false
        this.historyFrameCount = historyFrameCount.coerceIn(1, MAX_HISTORY_FRAMES)
        this.lumaStrength = lumaStrength.coerceIn(0f, MAX_LUMA_STRENGTH)
        this.chromaStrength = chromaStrength.coerceIn(0f, MAX_CHROMA_STRENGTH)
        this.noisePressure = noisePressure.coerceIn(0f, 1f)
        reason = if (enabled) PreviewTemporalFusionReason.NONE else
            PreviewTemporalFusionReason.HISTORY_WARMUP
    }

    private companion object {
        const val MAX_HISTORY_FRAMES = 2
        const val MAX_LUMA_STRENGTH = 0.30f
        const val MAX_CHROMA_STRENGTH = 0.50f
    }
}

/**
 * Admission and reset policy for the live screen-space temporal denoiser.
 *
 * This policy deliberately does not claim optical-flow alignment or super-resolution. It admits
 * history only on the exact NIV99-tuned SDR preview timeline. The shader then rejects inconsistent
 * pixels independently, leaving current-frame pixels untouched where motion cannot be proven safe.
 */
internal class PreviewTemporalFusionPolicy {
    private var previousTimestampNs = 0L
    private var previousCalibrationRevision = 0L
    private var previousRedCorrection = 1f
    private var previousGreenCorrection = 1f
    private var previousBlueCorrection = 1f

    fun resolve(
        input: PreviewTemporalFusionInput,
        output: PreviewTemporalFusionDecision
    ) {
        val ineligibleReason = eligibilityFailure(input)
        if (ineligibleReason != PreviewTemporalFusionReason.NONE) {
            reset()
            output.setBypass(
                reason = ineligibleReason,
                resetHistory = true,
                acceptCurrentFrameIntoHistory = false
            )
            return
        }

        if (previousTimestampNs == 0L) {
            remember(input)
            output.setBypass(
                reason = PreviewTemporalFusionReason.FIRST_ELIGIBLE_FRAME,
                resetHistory = true,
                acceptCurrentFrameIntoHistory = true
            )
            return
        }

        if (input.sensorTimestampNs == previousTimestampNs) {
            // UI/LUT setters may redraw the already-presented SurfaceTexture. Never feed a frame back
            // into its own history and never destroy valid history merely because no new sensor frame
            // was latched.
            output.setBypass(
                reason = PreviewTemporalFusionReason.SAME_FRAME_REDRAW,
                resetHistory = false,
                acceptCurrentFrameIntoHistory = false
            )
            return
        }

        val resetReason = timelineResetReason(input)
        if (resetReason != PreviewTemporalFusionReason.NONE) {
            remember(input)
            output.setBypass(
                reason = resetReason,
                resetHistory = true,
                acceptCurrentFrameIntoHistory = true
            )
            return
        }

        remember(input)
        val historyCount = input.availableHistoryFrames.coerceIn(0, MAX_HISTORY_FRAMES)
        if (historyCount == 0) {
            output.setBypass(
                reason = PreviewTemporalFusionReason.HISTORY_WARMUP,
                resetHistory = false,
                acceptCurrentFrameIntoHistory = true
            )
            return
        }

        val confidence = input.tuningConfidence.coerceIn(0f, 1f)
        val normalizedLumaNoise = (input.spatialDenoise / MAX_INPUT_SPATIAL_DENOISE)
            .coerceIn(0f, 1f)
        val normalizedChromaNoise = (input.chromaDenoise / MAX_INPUT_CHROMA_DENOISE)
            .coerceIn(0f, 1f)
        val noisePressure = max(normalizedLumaNoise, normalizedChromaNoise)
        val historyMaturity = if (historyCount >= MAX_HISTORY_FRAMES) 1f else 0.72f

        // 0.86 is the locked simulation candidate's ceiling, not an unconditional pixel mix. The
        // live coefficients below are further bounded by exact-frame confidence, noise evidence,
        // history maturity, and the shader's per-pixel residual/scene/motion rejection.
        val lumaStrength = SIMULATION_TEMPORAL_CEILING *
            (MIN_LUMA_MIX + LUMA_NOISE_RANGE * noisePressure) * confidence * historyMaturity
        val chromaStrength = SIMULATION_TEMPORAL_CEILING *
            (MIN_CHROMA_MIX + CHROMA_NOISE_RANGE * noisePressure) * confidence * historyMaturity
        output.setActive(historyCount, lumaStrength, chromaStrength, noisePressure)
    }

    fun reset() {
        previousTimestampNs = 0L
        previousCalibrationRevision = 0L
        previousRedCorrection = 1f
        previousGreenCorrection = 1f
        previousBlueCorrection = 1f
    }

    private fun eligibilityFailure(input: PreviewTemporalFusionInput): PreviewTemporalFusionReason {
        if (!input.fp16TextureSupported) return PreviewTemporalFusionReason.FP16_UNSUPPORTED
        if (!input.historyResourcesAvailable) return PreviewTemporalFusionReason.RESOURCE_UNAVAILABLE
        if (!input.outputAttached) return PreviewTemporalFusionReason.OUTPUT_UNAVAILABLE
        if (!input.presentationEnabled) return PreviewTemporalFusionReason.PRESENTATION_DISABLED
        if (input.debayerPixelRoute) return PreviewTemporalFusionReason.DEBAYER_PIXEL_ROUTE
        if (!input.enhanceGradeActive) return PreviewTemporalFusionReason.NON_ENHANCE_GRADE
        if (!input.tuningActive || !input.tuningConfidence.isFinite() ||
            input.tuningConfidence <= 0f ||
            !input.spatialDenoise.isFinite() || !input.chromaDenoise.isFinite() ||
            !input.redCorrection.isFinite() || !input.greenCorrection.isFinite() ||
            !input.blueCorrection.isFinite()
        ) {
            return PreviewTemporalFusionReason.TUNING_UNAVAILABLE
        }
        if (input.sensorTimestampNs <= 0L) return PreviewTemporalFusionReason.INVALID_TIMESTAMP
        return PreviewTemporalFusionReason.NONE
    }

    private fun timelineResetReason(input: PreviewTemporalFusionInput): PreviewTemporalFusionReason {
        if (input.sensorTimestampNs < previousTimestampNs) {
            return PreviewTemporalFusionReason.TIMESTAMP_NON_MONOTONIC
        }
        val intervalNs = input.sensorTimestampNs - previousTimestampNs
        if (intervalNs < MIN_FRAME_INTERVAL_NS || intervalNs > MAX_FRAME_INTERVAL_NS) {
            return PreviewTemporalFusionReason.TIMESTAMP_DISCONTINUITY
        }
        // The live coordinator's revision is a monotonic per-frame/configuration sequence, not a
        // static calibration ID. Normal increments must not flush history; only a regression proves
        // that the producer timeline was replaced or rewound.
        if (input.calibrationRevision < previousCalibrationRevision) {
            return PreviewTemporalFusionReason.CALIBRATION_CHANGED
        }
        val wbDelta = maxOf(
            logRatioDistance(input.redCorrection, previousRedCorrection),
            logRatioDistance(input.greenCorrection, previousGreenCorrection),
            logRatioDistance(input.blueCorrection, previousBlueCorrection)
        )
        if (wbDelta > MAX_WB_LOG_DELTA) {
            return PreviewTemporalFusionReason.WHITE_BALANCE_TRANSITION
        }
        return PreviewTemporalFusionReason.NONE
    }

    private fun remember(input: PreviewTemporalFusionInput) {
        previousTimestampNs = input.sensorTimestampNs
        previousCalibrationRevision = input.calibrationRevision
        previousRedCorrection = input.redCorrection
        previousGreenCorrection = input.greenCorrection
        previousBlueCorrection = input.blueCorrection
    }

    private fun logRatioDistance(current: Float, previous: Float): Float {
        val safeCurrent = current.coerceIn(MIN_POSITIVE_GAIN, MAX_REASONABLE_GAIN)
        val safePrevious = previous.coerceIn(MIN_POSITIVE_GAIN, MAX_REASONABLE_GAIN)
        return abs(ln(safeCurrent / safePrevious))
    }

    private companion object {
        const val MAX_HISTORY_FRAMES = 2
        const val MIN_FRAME_INTERVAL_NS = 8_000_000L
        const val MAX_FRAME_INTERVAL_NS = 100_000_000L
        const val MAX_WB_LOG_DELTA = 0.035f
        const val MIN_POSITIVE_GAIN = 0.01f
        const val MAX_REASONABLE_GAIN = 16f
        const val MAX_INPUT_SPATIAL_DENOISE = 0.62f
        const val MAX_INPUT_CHROMA_DENOISE = 0.86f
        const val SIMULATION_TEMPORAL_CEILING = 0.86f
        const val MIN_LUMA_MIX = 0.08f
        const val LUMA_NOISE_RANGE = 0.27f
        const val MIN_CHROMA_MIX = 0.15f
        const val CHROMA_NOISE_RANGE = 0.43f
    }
}
