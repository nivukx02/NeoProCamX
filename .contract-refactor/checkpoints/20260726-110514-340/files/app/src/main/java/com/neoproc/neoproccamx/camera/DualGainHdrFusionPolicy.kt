package com.neoproc.neoproccamx.camera

import kotlin.math.abs

data class DualGainHdrFrameMetadata(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val format: String,
    val exposureTimeNs: Long,
    val analogGain: Float,
    val hardwareBufferBacked: Boolean
)

data class DualGainHdrFusionRequest(
    val enabled: Boolean = DualGainHdrFusionPolicy.DEFAULT_REQUESTED,
    val publicSynchronizedDualGainAvailable: Boolean = false,
    val privateExtensionRequested: Boolean = false,
    val lcgFrame: DualGainHdrFrameMetadata? = null,
    val hcgFrame: DualGainHdrFrameMetadata? = null,
    val kGain: Float = 1.0f,
    val noiseFloor: Float = 0.0f,
    val saturationThreshold: Float = 0.94f,
    val existingHdrMergeFallbackAvailable: Boolean = true
)

enum class DualGainHdrFusionPath {
    DISABLED,
    HARDWARE_SYNCHRONIZED_DUAL_GAIN,
    EXISTING_HDR_MERGE_FALLBACK,
    UNSUPPORTED
}

data class DualGainHdrFusionPlan(
    val path: DualGainHdrFusionPath,
    val supported: Boolean,
    val fallbackReason: String,
    val timestampSkewNs: Long,
    val kGain: Float,
    val noiseFloor: Float,
    val saturationThreshold: Float,
    val motionMaskEnabled: Boolean,
    val feedsAcesColorEngine: Boolean,
    val feedsDenoiseMotionConfidence: Boolean,
    val signature: String
)

object DualGainHdrFusionPolicy {
    const val DEFAULT_REQUESTED: Boolean = true
    const val MAX_TIMESTAMP_SKEW_NS = 1_000L
    private const val PUBLIC_DUAL_GAIN_SIGNATURE = "PUBLIC_CAMERA2_DUAL_GAIN"
    private const val WORKING_SPACE = "ACESCG_LINEAR"

    fun resolve(request: DualGainHdrFusionRequest): DualGainHdrFusionPlan {
        if (!request.enabled) {
            return fallback(
                request = request,
                reason = "DUAL_GAIN_DISABLED",
                skew = timestampSkew(request.lcgFrame, request.hcgFrame),
                path = DualGainHdrFusionPath.DISABLED
            )
        }
        if (request.privateExtensionRequested) {
            return fallback(
                request = request,
                reason = "PRIVATE_EXTENSION_REJECTED_PUBLIC_CAMERA2_ONLY",
                skew = timestampSkew(request.lcgFrame, request.hcgFrame)
            )
        }
        if (!request.publicSynchronizedDualGainAvailable) {
            return fallback(
                request = request,
                reason = "PUBLIC_SYNCHRONIZED_DUAL_GAIN_NOT_AVAILABLE",
                skew = timestampSkew(request.lcgFrame, request.hcgFrame)
            )
        }
        val lcg = request.lcgFrame
        val hcg = request.hcgFrame
        if (lcg == null || hcg == null) {
            return fallback(
                request = request,
                reason = "DUAL_GAIN_FRAME_PAIR_MISSING",
                skew = timestampSkew(lcg, hcg)
            )
        }
        val validationError = validateFramePair(lcg, hcg)
        if (validationError != null) {
            return fallback(
                request = request,
                reason = validationError,
                skew = timestampSkew(lcg, hcg)
            )
        }
        if (!request.kGain.isFinite() || request.kGain < 1.0f || request.kGain > 64.0f) {
            return fallback(
                request = request,
                reason = "INVALID_SENSOR_GAIN_COEFFICIENT",
                skew = timestampSkew(lcg, hcg)
            )
        }
        val skew = timestampSkew(lcg, hcg)
        if (skew > MAX_TIMESTAMP_SKEW_NS) {
            return fallback(
                request = request,
                reason = "DUAL_GAIN_TIMESTAMP_SKEW_TOO_HIGH:$skew",
                skew = skew
            )
        }

        val clampedNoiseFloor = request.noiseFloor.coerceIn(0.0f, 0.20f)
        val clampedSaturation = request.saturationThreshold.coerceIn(0.72f, 0.995f)
        return DualGainHdrFusionPlan(
            path = DualGainHdrFusionPath.HARDWARE_SYNCHRONIZED_DUAL_GAIN,
            supported = true,
            fallbackReason = "NONE",
            timestampSkewNs = skew,
            kGain = request.kGain,
            noiseFloor = clampedNoiseFloor,
            saturationThreshold = clampedSaturation,
            motionMaskEnabled = true,
            feedsAcesColorEngine = true,
            feedsDenoiseMotionConfidence = true,
            signature = listOf(
                "path=$PUBLIC_DUAL_GAIN_SIGNATURE",
                "working=$WORKING_SPACE",
                "timestampSkewNs=$skew",
                "kGain=${request.kGain}",
                "motionMask=true",
                "fallback=false"
            ).joinToString(separator = "|")
        )
    }

    private fun validateFramePair(
        lcg: DualGainHdrFrameMetadata,
        hcg: DualGainHdrFrameMetadata
    ): String? {
        if (lcg.width <= 0 || lcg.height <= 0 || hcg.width <= 0 || hcg.height <= 0) {
            return "DUAL_GAIN_INVALID_DIMENSIONS"
        }
        if (lcg.width != hcg.width || lcg.height != hcg.height) {
            return "DUAL_GAIN_DIMENSION_MISMATCH"
        }
        if (!lcg.format.equals(hcg.format, ignoreCase = true)) {
            return "DUAL_GAIN_FORMAT_MISMATCH"
        }
        if (!lcg.hardwareBufferBacked || !hcg.hardwareBufferBacked) {
            return "DUAL_GAIN_REQUIRES_HARDWARE_BUFFER_PAIR"
        }
        return null
    }

    private fun fallback(
        request: DualGainHdrFusionRequest,
        reason: String,
        skew: Long,
        path: DualGainHdrFusionPath = if (request.existingHdrMergeFallbackAvailable) {
            DualGainHdrFusionPath.EXISTING_HDR_MERGE_FALLBACK
        } else {
            DualGainHdrFusionPath.UNSUPPORTED
        }
    ): DualGainHdrFusionPlan =
        DualGainHdrFusionPlan(
            path = path,
            supported = false,
            fallbackReason = reason,
            timestampSkewNs = skew,
            kGain = request.kGain.coerceIn(1.0f, 64.0f),
            noiseFloor = request.noiseFloor.coerceIn(0.0f, 0.20f),
            saturationThreshold = request.saturationThreshold.coerceIn(0.72f, 0.995f),
            motionMaskEnabled = false,
            feedsAcesColorEngine = false,
            feedsDenoiseMotionConfidence = false,
            signature = listOf(
                "path=$path",
                "reason=$reason",
                "working=$WORKING_SPACE",
                "timestampSkewNs=$skew",
                "opticalFlowPreWarp=${reason.startsWith("DUAL_GAIN_TIMESTAMP_SKEW_TOO_HIGH")}",
                "fallback=true"
            ).joinToString(separator = "|")
        )

    private fun timestampSkew(
        lcg: DualGainHdrFrameMetadata?,
        hcg: DualGainHdrFrameMetadata?
    ): Long =
        if (lcg == null || hcg == null) {
            Long.MAX_VALUE
        } else {
            abs(lcg.timestampNs - hcg.timestampNs)
        }
}
