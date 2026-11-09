package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import java.util.Locale

internal enum class CameraProcessedVideoSourceQualityTier {
    STANDARD,
    MOTION_STABLE,
    LOW_NOISE_DETAIL,
    MOTION_STABLE_LOW_NOISE_DETAIL
}

internal data class CameraProcessedVideoQualityRequestPlan(
    val edgeMode: Int?,
    val noiseReductionMode: Int?,
    val hotPixelMode: Int?,
    val shadingMode: Int?,
    val tonemapMode: Int?,
    val aberrationMode: Int?,
    val distortionCorrectionMode: Int?,
    val requestPolicyVersion: String = CameraProcessedVideoQualityRequestPolicy.REQUEST_POLICY_VERSION,
    val sourceQualityTier: CameraProcessedVideoSourceQualityTier = CameraProcessedVideoSourceQualityTier.STANDARD,
    val motionRisk: Float = 0f,
    val noiseRisk: Float = 0f,
    val antiMotionBlurEnabled: Boolean = false,
    val motionStableFps: Int? = null,
    val motionStableAeTargetFpsRange: CameraIntRange? = null,
    val motionStableFrameDurationNs: Long? = null,
    val maxExposureTimeNs: Long? = null,
    val sourceQualityBudgetMultiplier: Double = 1.0
)

internal fun CameraProcessedVideoQualityRequestPlan.toEvidenceSummary(): String =
    listOf(
        "edgeMode=$edgeMode",
        "noiseReductionMode=$noiseReductionMode",
        "hotPixelMode=$hotPixelMode",
        "shadingMode=$shadingMode",
        "tonemapMode=$tonemapMode",
        "aberrationMode=$aberrationMode",
        "distortionCorrectionMode=$distortionCorrectionMode",
        "requestPolicyVersion=$requestPolicyVersion",
        "sourceQualityTier=$sourceQualityTier",
        "motionRisk=${motionRisk.evidenceDecimal()}",
        "noiseRisk=${noiseRisk.evidenceDecimal()}",
        "antiMotionBlurEnabled=$antiMotionBlurEnabled",
        "motionStableFps=${motionStableFps ?: "unset"}",
        "motionStableAeTargetFpsRange=${motionStableAeTargetFpsRange.evidenceRange()}",
        "motionStableFrameDurationNs=${motionStableFrameDurationNs ?: "unset"}",
        "maxExposureTimeNs=${maxExposureTimeNs ?: "unset"}",
        "sourceQualityBudgetMultiplier=${sourceQualityBudgetMultiplier.evidenceDecimal()}"
    ).joinToString(separator = ";")

internal object CameraProcessedVideoQualityRequestPolicy {
    const val REQUEST_POLICY_VERSION = "V20"

    private const val NS_PER_SECOND = 1_000_000_000L
    private const val MIN_REALTIME_FPS = 24
    private const val HIGH_MOTION_RISK_THRESHOLD = 0.58f
    private const val HIGH_NOISE_RISK_THRESHOLD = 0.55f

    /**
     * Processed video requests the best available device ISP cleanup stack.
     * Strict high-end also applies this final pass to Pro/Cinema SDR so later
     * request layers cannot leave the encoder with low-quality ISP controls.
     */
    fun resolve(
        strictHighEndEnabled: Boolean,
        rawVideoCaptureEnabled: Boolean,
        consumerAutoVideo: Boolean = true,
        availability: IspBypassAvailability?,
        requestedFps: Int = 25,
        availableAeFpsRanges: List<CameraIntRange> = emptyList(),
        exposureRange: CameraLongRange? = null,
        motionRisk: Float = 0f,
        noiseRisk: Float = 0f,
        videoDetailProfile: VideoDetailProfile? = null,
        noiseReductionEnabled: Boolean = true
    ): CameraProcessedVideoQualityRequestPlan? {
        if (rawVideoCaptureEnabled) return null
        if (!consumerAutoVideo && !strictHighEndEnabled) return null
        val sourceQualityPlan = resolveSourceQualityPlan(
            strictHighEndEnabled = strictHighEndEnabled,
            requestedFps = requestedFps,
            availableAeFpsRanges = availableAeFpsRanges,
            exposureRange = exposureRange,
            motionRisk = motionRisk,
            noiseRisk = noiseRisk,
            videoDetailProfile = videoDetailProfile
        )
        val edgeFallbacks = if (strictHighEndEnabled) {
            intArrayOf()
        } else {
            intArrayOf(CaptureRequest.EDGE_MODE_FAST, CaptureRequest.EDGE_MODE_OFF)
        }
        val noiseFallbacks = if (strictHighEndEnabled) {
            intArrayOf()
        } else {
            intArrayOf(CaptureRequest.NOISE_REDUCTION_MODE_FAST, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        }
        val hotPixelFallbacks = if (strictHighEndEnabled) {
            intArrayOf()
        } else {
            intArrayOf(CaptureRequest.HOT_PIXEL_MODE_FAST, CaptureRequest.HOT_PIXEL_MODE_OFF)
        }
        val shadingFallbacks = if (strictHighEndEnabled) {
            intArrayOf()
        } else {
            intArrayOf(CaptureRequest.SHADING_MODE_FAST, CaptureRequest.SHADING_MODE_OFF)
        }
        val tonemapFallbacks = if (strictHighEndEnabled) {
            intArrayOf()
        } else {
            intArrayOf(CaptureRequest.TONEMAP_MODE_FAST)
        }
        val aberrationFallbacks = if (strictHighEndEnabled) {
            intArrayOf()
        } else {
            intArrayOf(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
            )
        }
        val distortionFallbacks = if (strictHighEndEnabled) {
            intArrayOf()
        } else {
            intArrayOf(
                CaptureRequest.DISTORTION_CORRECTION_MODE_FAST,
                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
            )
        }

        return CameraProcessedVideoQualityRequestPlan(
            edgeMode = bestSupportedMode(
                preferred = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                fallbacks = edgeFallbacks,
                availableModes = availability?.edgeModes,
                assumePreferredWhenMetadataMissing = strictHighEndEnabled
            ),
            // An EXPLICIT user Noise Reduction OFF is honored even under strict high-end:
            // request NOISE_REDUCTION_MODE_OFF (+ HOT_PIXEL_MODE_OFF) so the processed-video
            // re-override pass cannot clobber the user's OFF back to HIGH_QUALITY. The
            // default (noiseReductionEnabled=true) keeps the exact HIGH_QUALITY behavior.
            noiseReductionMode = if (!noiseReductionEnabled) {
                bestSupportedMode(
                    preferred = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                    fallbacks = intArrayOf(),
                    availableModes = availability?.noiseReductionModes,
                    assumePreferredWhenMetadataMissing = true
                )
            } else {
                bestSupportedMode(
                    preferred = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    fallbacks = noiseFallbacks,
                    availableModes = availability?.noiseReductionModes,
                    assumePreferredWhenMetadataMissing = strictHighEndEnabled
                )
            },
            hotPixelMode = if (!noiseReductionEnabled) {
                bestSupportedMode(
                    preferred = CaptureRequest.HOT_PIXEL_MODE_OFF,
                    fallbacks = intArrayOf(),
                    availableModes = availability?.hotPixelModes,
                    assumePreferredWhenMetadataMissing = true
                )
            } else {
                bestSupportedMode(
                    preferred = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
                    fallbacks = hotPixelFallbacks,
                    availableModes = availability?.hotPixelModes,
                    assumePreferredWhenMetadataMissing = strictHighEndEnabled
                )
            },
            shadingMode = bestSupportedMode(
                preferred = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
                fallbacks = shadingFallbacks,
                availableModes = availability?.shadingModes,
                assumePreferredWhenMetadataMissing = strictHighEndEnabled
            ),
            tonemapMode = bestSupportedMode(
                preferred = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                fallbacks = tonemapFallbacks,
                availableModes = availability?.tonemapModes,
                assumePreferredWhenMetadataMissing = strictHighEndEnabled
            ),
            aberrationMode = bestSupportedMode(
                preferred = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                fallbacks = aberrationFallbacks,
                availableModes = availability?.colorAberrationModes,
                assumePreferredWhenMetadataMissing = strictHighEndEnabled
            ),
            distortionCorrectionMode = bestSupportedMode(
                preferred = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                fallbacks = distortionFallbacks,
                availableModes = availability?.distortionCorrectionModes,
                assumePreferredWhenMetadataMissing = strictHighEndEnabled
            ),
            requestPolicyVersion = REQUEST_POLICY_VERSION,
            sourceQualityTier = sourceQualityPlan.sourceQualityTier,
            motionRisk = sourceQualityPlan.motionRisk,
            noiseRisk = sourceQualityPlan.noiseRisk,
            antiMotionBlurEnabled = sourceQualityPlan.antiMotionBlurEnabled,
            motionStableFps = sourceQualityPlan.motionStableFps,
            motionStableAeTargetFpsRange = sourceQualityPlan.motionStableAeTargetFpsRange,
            motionStableFrameDurationNs = sourceQualityPlan.motionStableFrameDurationNs,
            maxExposureTimeNs = sourceQualityPlan.maxExposureTimeNs,
            sourceQualityBudgetMultiplier = sourceQualityPlan.sourceQualityBudgetMultiplier
        )
    }

    private fun resolveSourceQualityPlan(
        strictHighEndEnabled: Boolean,
        requestedFps: Int,
        availableAeFpsRanges: List<CameraIntRange>,
        exposureRange: CameraLongRange?,
        motionRisk: Float,
        noiseRisk: Float,
        videoDetailProfile: VideoDetailProfile?
    ): SourceQualityPlan {
        val normalizedMotionRisk = motionRisk.coerceIn(0f, 1f)
        val normalizedNoiseRisk = noiseRisk.coerceIn(0f, 1f)
        val highMotionRisk = normalizedMotionRisk >= HIGH_MOTION_RISK_THRESHOLD
        val highNoiseOrDetail =
            normalizedNoiseRisk >= HIGH_NOISE_RISK_THRESHOLD ||
                videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL
        val sourceQualityTier = when {
            highMotionRisk && highNoiseOrDetail ->
                CameraProcessedVideoSourceQualityTier.MOTION_STABLE_LOW_NOISE_DETAIL
            highMotionRisk -> CameraProcessedVideoSourceQualityTier.MOTION_STABLE
            highNoiseOrDetail -> CameraProcessedVideoSourceQualityTier.LOW_NOISE_DETAIL
            else -> CameraProcessedVideoSourceQualityTier.STANDARD
        }
        val motionStableAeRange =
            resolveMotionStableAeRange(highMotionRisk, requestedFps, availableAeFpsRanges)
        val motionStableFps = motionStableAeRange
            ?.normalizedUpperFps()
            ?.coerceAtLeast(MIN_REALTIME_FPS)
            ?: requestedFps.coerceAtLeast(MIN_REALTIME_FPS).takeIf { highMotionRisk }
        val motionStableFrameDurationNs = motionStableFps?.let { NS_PER_SECOND / it }
        // LOW-NOISE LONG-SHUTTER (noise-SOURCE reduction). In a high-noise (dim / high-gain) scene
        // with NO motion risk, pin the sensor to a full 180-degree shutter via exposure-time priority
        // so AE lowers ISO to match, instead of short-shuttering and cranking gain (the actual noise
        // source). AE stays ON/adaptive (SENSOR_EXPOSURE_TIME_PRIORITY, NOT AE_OFF), so brightness is
        // preserved and dim scenes are no worse than today. Gated on highNoiseOrDetail, so it can
        // never over-pin the shutter (and overexpose) in a bright, already-low-ISO scene. Mutually
        // exclusive with the high-motion short-shutter branch below (that path shortens exposure).
        val lowNoiseLongShutter = highNoiseOrDetail && !highMotionRisk
        val lowNoiseFrameDurationNs =
            if (lowNoiseLongShutter) NS_PER_SECOND / requestedFps.coerceAtLeast(MIN_REALTIME_FPS) else null
        val maxExposureTimeNs = when {
            motionStableFrameDurationNs != null -> clampExposureForRange(
                exposureNs = motionStableFrameDurationNs / exposureDivisorForMotionRisk(normalizedMotionRisk),
                frameDurationNs = motionStableFrameDurationNs,
                exposureRange = exposureRange
            )
            lowNoiseFrameDurationNs != null -> clampExposureForRange(
                exposureNs = lowNoiseFrameDurationNs / 2, // full 180-degree shutter
                frameDurationNs = lowNoiseFrameDurationNs,
                exposureRange = exposureRange
            )
            else -> null
        }

        return SourceQualityPlan(
            sourceQualityTier = sourceQualityTier,
            motionRisk = normalizedMotionRisk,
            noiseRisk = normalizedNoiseRisk,
            antiMotionBlurEnabled = highMotionRisk,
            motionStableFps = motionStableFps,
            motionStableAeTargetFpsRange = motionStableAeRange,
            motionStableFrameDurationNs = motionStableFrameDurationNs,
            maxExposureTimeNs = maxExposureTimeNs,
            sourceQualityBudgetMultiplier = sourceQualityBudgetMultiplier(
                strictHighEndEnabled = strictHighEndEnabled,
                motionRisk = normalizedMotionRisk,
                noiseRisk = normalizedNoiseRisk,
                videoDetailProfile = videoDetailProfile
            )
        )
    }

    private fun resolveMotionStableAeRange(
        highMotionRisk: Boolean,
        requestedFps: Int,
        availableAeFpsRanges: List<CameraIntRange>
    ): CameraIntRange? {
        if (!highMotionRisk) return null
        val targetFps = requestedFps.coerceAtLeast(MIN_REALTIME_FPS)
        if (availableAeFpsRanges.isEmpty()) return CameraIntRange(targetFps, targetFps)
        val unitScale = if (availableAeFpsRanges.any { it.upper >= 1_000 }) 1_000 else 1
        val scaledTarget = targetFps * unitScale
        return availableAeFpsRanges
            .filter { range -> range.contains(scaledTarget) }
            .minWithOrNull(
                compareBy<CameraIntRange> { it.span }
                    .thenBy { kotlin.math.abs(it.lower - scaledTarget) + kotlin.math.abs(it.upper - scaledTarget) }
                    .thenByDescending { it.lower }
            )
            ?: availableAeFpsRanges.maxByOrNull { it.upper }
    }

    private fun CameraIntRange.normalizedUpperFps(): Int =
        if (upper >= 1_000) upper / 1_000 else upper

    private fun exposureDivisorForMotionRisk(motionRisk: Float): Long =
        when {
            motionRisk >= 0.85f -> 4L
            motionRisk >= 0.70f -> 3L
            else -> 2L
        }

    private fun clampExposureForRange(
        exposureNs: Long,
        frameDurationNs: Long,
        exposureRange: CameraLongRange?
    ): Long {
        val frameBoundedExposure = exposureNs.coerceIn(1L, frameDurationNs.coerceAtLeast(1L))
        if (exposureRange == null) return frameBoundedExposure
        val lower = minOf(exposureRange.lower, exposureRange.upper).coerceAtLeast(1L)
        val upper = maxOf(exposureRange.lower, exposureRange.upper)
            .coerceAtLeast(lower)
            .coerceAtMost(frameDurationNs.coerceAtLeast(1L))
            .coerceAtLeast(lower)
        return frameBoundedExposure.coerceIn(lower, upper)
    }

    private fun sourceQualityBudgetMultiplier(
        strictHighEndEnabled: Boolean,
        motionRisk: Float,
        noiseRisk: Float,
        videoDetailProfile: VideoDetailProfile?
    ): Double {
        var multiplier = 1.0
        if (motionRisk >= HIGH_MOTION_RISK_THRESHOLD) {
            multiplier += 0.08 + motionRisk.toDouble() * 0.10
        }
        if (noiseRisk >= HIGH_NOISE_RISK_THRESHOLD) {
            multiplier += 0.06 + noiseRisk.toDouble() * 0.12
        }
        if (videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL) {
            multiplier += 0.14
        }
        if (strictHighEndEnabled && multiplier > 1.0) {
            multiplier += 0.04
        }
        return multiplier.coerceIn(1.0, 1.48)
    }

    private fun bestSupportedMode(
        preferred: Int,
        fallbacks: IntArray,
        availableModes: List<Int>?,
        assumePreferredWhenMetadataMissing: Boolean
    ): Int? {
        if (availableModes == null) return preferred
        if (availableModes.isEmpty()) {
            return if (assumePreferredWhenMetadataMissing) preferred else null
        }
        return sequenceOf(preferred)
            .plus(fallbacks.asSequence())
            .firstOrNull { it in availableModes }
    }

    private data class SourceQualityPlan(
        val sourceQualityTier: CameraProcessedVideoSourceQualityTier,
        val motionRisk: Float,
        val noiseRisk: Float,
        val antiMotionBlurEnabled: Boolean,
        val motionStableFps: Int?,
        val motionStableAeTargetFpsRange: CameraIntRange?,
        val motionStableFrameDurationNs: Long?,
        val maxExposureTimeNs: Long?,
        val sourceQualityBudgetMultiplier: Double
    )
}

/**
 * The app-owned Vulkan renderer performs lens geometry in its own coordinate
 * domain. Request sensor-native geometry only when the Camera2 HAL advertises
 * OFF; otherwise leave the key unset and report that no confirmation exists.
 */
internal object CameraGpuOwnedGeometryPolicy {
    fun resolveDistortionCorrectionMode(
        appOwnedGpuProcessingEnabled: Boolean,
        strictHighEndEnabled: Boolean = false,
        availability: IspBypassAvailability?
    ): Int? =
        CaptureRequest.DISTORTION_CORRECTION_MODE_OFF.takeIf {
            appOwnedGpuProcessingEnabled &&
                !strictHighEndEnabled &&
                availability?.distortionCorrectionModes?.contains(it) == true
        }

    fun isDistortionCorrectionOffConfirmed(
        appOwnedGpuProcessingEnabled: Boolean,
        appliedMode: Int?
    ): Boolean =
        appOwnedGpuProcessingEnabled &&
            appliedMode == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
}

private fun Float.evidenceDecimal(): String =
    String.format(Locale.US, "%.3f", this)

private fun Double.evidenceDecimal(): String =
    String.format(Locale.US, "%.3f", this)

private fun CameraIntRange?.evidenceRange(): String =
    this?.let { "${it.lower}-${it.upper}" } ?: "unset"
