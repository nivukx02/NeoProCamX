package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt

internal data class CameraExtremeDetailFallbackExposurePlan(
    val activateManualExposure: Boolean,
    val iso: Int,
    val exposureNs: Long,
    val isoCeiling: Int,
    val reason: String
)

internal object CameraExtremeDetailFallbackExposurePolicy {
    private const val TARGET_ISO_CEILING = 800

    /**
     * Detail profiles control processing, never capture exposure. Camera AE therefore remains
     * authoritative in preview and recording so an indoor meter value cannot be frozen when the
     * camera moves outdoors. Explicit Pro-mode ISO/shutter settings are applied by the normal manual
     * controls and do not pass through this retired fallback gate.
     */
    fun isGateActive(
        videoRenderContractActive: Boolean,
        videoRecordingRequestActive: Boolean,
        meteredReady: Boolean,
        rawVideoCaptureEnabled: Boolean,
        encodedVideoStrict: Boolean,
        extremeDetailProfile: Boolean,
        recordingRequiresMetered: Boolean = false
    ): Boolean = false

    fun resolve(
        active: Boolean,
        supportsManualSensor: Boolean,
        userManualIso: Int,
        userManualExposureNs: Long,
        latestIso: Int?,
        latestExposureNs: Long,
        frameDurationNs: Long,
        exposureRange: CameraLongRange,
        isoRange: CameraIntRange
    ): CameraExtremeDetailFallbackExposurePlan {
        val isoCeiling = TARGET_ISO_CEILING.coerceIn(
            minOf(isoRange.lower, isoRange.upper),
            maxOf(isoRange.lower, isoRange.upper)
        )
        fun inactive(reason: String) = CameraExtremeDetailFallbackExposurePlan(
            activateManualExposure = false,
            iso = -1,
            exposureNs = -1L,
            isoCeiling = isoCeiling,
            reason = reason
        )

        if (!active) return inactive("INACTIVE")
        if (!supportsManualSensor) return inactive("NO_MANUAL_SENSOR")
        if (userManualIso > 0 || userManualExposureNs > 0L) return inactive("USER_MANUAL_SENSOR")

        val lowerIso = minOf(isoRange.lower, isoRange.upper).coerceAtLeast(1)
        val upperIso = maxOf(isoRange.lower, isoRange.upper).coerceAtLeast(lowerIso)
        val lowerExposure = minOf(exposureRange.lower, exposureRange.upper).coerceAtLeast(1L)
        val upperExposure = maxOf(exposureRange.lower, exposureRange.upper).coerceAtLeast(lowerExposure)
        val maxExposure = frameDurationNs
            .coerceAtLeast(1L)
            .coerceIn(lowerExposure, upperExposure)
        val meteredIso = latestIso?.takeIf { it > 0 }
        if (meteredIso == null || latestExposureNs <= 0L) {
            return CameraExtremeDetailFallbackExposurePlan(
                activateManualExposure = true,
                iso = isoCeiling,
                exposureNs = maxExposure,
                isoCeiling = isoCeiling,
                reason = "EXTREME_DETAIL_CAMERA2_LOW_NOISE_BOOTSTRAP"
            )
        }
        val meteredExposure = latestExposureNs.coerceIn(lowerExposure, maxExposure)
        val meteredExposureProduct = meteredIso.toDouble() * meteredExposure.toDouble()

        val idealIso = (meteredExposureProduct / maxExposure.toDouble())
            .roundToInt()
            .coerceIn(lowerIso, upperIso)
        val targetIso = idealIso
            .coerceAtMost(upperIso)
        val requestedIso = targetIso.coerceAtLeast(lowerIso)
        val reason = if (requestedIso > isoCeiling) {
            "EXTREME_DETAIL_CAMERA2_LOW_NOISE_BRIGHTNESS_PRESERVED"
        } else {
            "EXTREME_DETAIL_CAMERA2_LOW_NOISE_LONG_SHUTTER"
        }

        return CameraExtremeDetailFallbackExposurePlan(
            activateManualExposure = true,
            iso = requestedIso,
            exposureNs = maxExposure,
            isoCeiling = isoCeiling,
            reason = reason
        )
    }
}
