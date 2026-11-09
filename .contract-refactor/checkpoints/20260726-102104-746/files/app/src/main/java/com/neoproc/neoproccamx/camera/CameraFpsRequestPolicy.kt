package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class CameraFpsRequestPlan(
    val requestedFps: Int,
    val aeTargetFpsRange: CameraIntRange?,
    val appliedFps: Int,
    val exposureNs: Long,
    val frameDurationNs: Long
)

internal object CameraFpsRequestPolicy {
    private const val MIN_REALTIME_FPS = 24
    private const val FIFTY_HZ_LED_HALF_PERIOD_NS = 10_000_000L
    private const val SIXTY_HZ_LED_HALF_PERIOD_NS = 8_333_333L

    /**
     * X3/R3: choose the sensor/AE cadence target. While recording, honor the USER-selected record
     * fps (recordFps); otherwise (idle/armed preview in any mode) pin the preview cadence to
     * previewCadenceFps (25). This keeps a 60/120 record selection from dragging the preview cadence
     * up while keeping armed preview aligned with the 4K25 recording contract.
     */
    fun previewOrRecordRequestedFps(
        videoRecordingRequestActive: Boolean,
        previewCadenceFps: Int,
        recordFps: Int
    ): Int = if (videoRecordingRequestActive) recordFps else previewCadenceFps

    fun resolve(
        requestedFps: Int,
        availableRanges: List<CameraIntRange>,
        requestedExposureNs: Long,
        exposureRange: CameraLongRange,
        antibandingMode: Int? = null,
        maximumShutterAngleDegrees: Float = 180f,
        autoAntibandingPrefers50Hz: Boolean = CameraPreviewAntibandingPolicy.prefers50HzPowerGrid(),
        streamBasedFpsOverride: CameraStreamBasedFpsOverridePlan? = null
    ): CameraFpsRequestPlan {
        val fpsPlan = CameraPreviewFpsPolicy.resolve(
            requestedFps = requestedFps.coerceAtLeast(MIN_REALTIME_FPS),
            availableRanges = availableRanges
        )
        val streamOverride = streamBasedFpsOverride?.takeIf { it.enabled }
        val appliedFps = streamOverride?.appliedFps ?: fpsPlan.appliedPreviewFps
        val aeTargetFpsRange = streamOverride?.publicAeRange ?: fpsPlan.resolvedRange
        val requestedExposure = requestedExposureNs
            .takeIf { it > 0L }
            ?: (1_000_000_000L / appliedFps.coerceAtLeast(MIN_REALTIME_FPS))
        val previewSafeExposureNs = CameraControlMath.previewSafeExposure(
            requestedExposureNs = requestedExposure,
            previewFps = appliedFps,
            exposureLowerNs = exposureRange.lower,
            exposureUpperNs = exposureRange.upper,
            maxShutterAngleDegrees = maximumShutterAngleDegrees
        )
        val exposureNs = resolveFlickerSafeExposure(
            requestedExposureNs = requestedExposure,
            previewSafeExposureNs = previewSafeExposureNs,
            previewFps = appliedFps,
            exposureRange = exposureRange,
            antibandingMode = antibandingMode,
            autoAntibandingPrefers50Hz = autoAntibandingPrefers50Hz
        )
        val frameDurationNs = CameraControlMath.previewSafeFrameDuration(
            appliedExposureNs = exposureNs,
            previewFps = appliedFps,
            exposureLowerNs = exposureRange.lower,
            exposureUpperNs = exposureRange.upper
        )
        return CameraFpsRequestPlan(
            requestedFps = fpsPlan.requestedPreviewFps,
            aeTargetFpsRange = aeTargetFpsRange,
            appliedFps = appliedFps,
            exposureNs = exposureNs,
            frameDurationNs = frameDurationNs
        )
    }

    private fun resolveFlickerSafeExposure(
        requestedExposureNs: Long,
        previewSafeExposureNs: Long,
        previewFps: Int,
        exposureRange: CameraLongRange,
        antibandingMode: Int?,
        autoAntibandingPrefers50Hz: Boolean
    ): Long {
        val periodNs = flickerHalfPeriodNs(
            antibandingMode = antibandingMode,
            previewFps = previewFps,
            autoAntibandingPrefers50Hz = autoAntibandingPrefers50Hz
        ) ?: return previewSafeExposureNs
        val frameBudgetNs = (1_000_000_000L / previewFps.coerceAtLeast(MIN_REALTIME_FPS))
            .coerceIn(exposureRange.lower, exposureRange.upper)
        val maximumSnapExposureNs =
            if (
                requestedExposureNs >= periodNs &&
                periodNs > previewSafeExposureNs &&
                periodNs <= frameBudgetNs
            ) {
                periodNs
            } else {
                previewSafeExposureNs
            }
        if (maximumSnapExposureNs < periodNs) {
            return previewSafeExposureNs
        }
        val firstMultiple = ceilingDivide(exposureRange.lower.coerceAtLeast(1L), periodNs)
            .coerceAtLeast(1L)
        val lastMultiple = (maximumSnapExposureNs / periodNs).coerceAtLeast(firstMultiple)
        val targetMultiple = (requestedExposureNs.toDouble() / periodNs.toDouble())
            .roundToLong()
            .coerceIn(firstMultiple, lastMultiple)
        val candidates = longArrayOf(
            targetMultiple,
            targetMultiple - 1L,
            targetMultiple + 1L
        )
        return candidates
            .map { multiple -> multiple.coerceIn(firstMultiple, lastMultiple) * periodNs }
            .filter { exposureRange.contains(it) && it <= frameBudgetNs }
            .minByOrNull { candidate -> abs(candidate - requestedExposureNs) }
            ?: previewSafeExposureNs
    }

    private fun flickerHalfPeriodNs(
        antibandingMode: Int?,
        previewFps: Int,
        autoAntibandingPrefers50Hz: Boolean
    ): Long? =
        when (antibandingMode) {
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ -> FIFTY_HZ_LED_HALF_PERIOD_NS
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ -> SIXTY_HZ_LED_HALF_PERIOD_NS
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO ->
                if (autoAntibandingPrefers50Hz || previewFps in setOf(24, 25, 48, 50)) {
                    FIFTY_HZ_LED_HALF_PERIOD_NS
                } else {
                    SIXTY_HZ_LED_HALF_PERIOD_NS
                }
            else -> null
        }

    private fun ceilingDivide(value: Long, divisor: Long): Long =
        (value + divisor - 1L) / divisor
}
