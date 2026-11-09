package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest

/**
 * Narrow Camera2 exposure guard for the one proven NPV10 cadence failure.
 *
 * The iQOO HAL accepted a fixed 30-30 FPS range at 1280x720 but repeatedly delivered a 66.6 ms
 * sensor interval while automatic exposure remained at 30 ms. Packing and durable writes both
 * stayed well below their deadlines, so changing buffer depth or recording quality cannot repair
 * that producer-side miss. This policy reserves 8.3 ms of the 30 FPS frame for sensor/HAL readout
 * while preserving scene brightness through the existing software shutter-priority ISO loop. The
 * same guard disables HAL antibanding for this fixed-manual request: the measured iQOO path was
 * quantizing a 25 ms request against its 50 Hz policy and periodically emitting a 66.6 ms interval.
 *
 * Deliberately excluded: Direct Gallery/encoder routes, 25 FPS, every other resolution, stills,
 * and any operator-selected ISO or shutter. Those paths retain their existing exposure authority.
 */
internal object Npv10CadenceExposurePolicy {
    const val TARGET_WIDTH = 1280
    const val TARGET_HEIGHT = 720
    const val TARGET_FPS = 30
    const val FIXED_EXPOSURE_TIME_NS = 25_000_000L

    fun resolve(
        npv10TenBitCaptureEnabled: Boolean,
        privateMasterPipeline: Boolean,
        sdrProcessedTransport: Boolean,
        captureWidth: Int?,
        captureHeight: Int?,
        targetFps: Int,
        supportsManualSensor: Boolean,
        manualIso: Int,
        manualExposureTimeNs: Long
    ): Npv10CadenceExposurePlan {
        val dimensionsMatch = captureWidth != null && captureHeight != null &&
            minOf(captureWidth, captureHeight) == TARGET_HEIGHT &&
            maxOf(captureWidth, captureHeight) == TARGET_WIDTH
        val reason = when {
            !npv10TenBitCaptureEnabled -> "NPV10_DISABLED"
            !privateMasterPipeline -> "DIRECT_OR_ENCODER_PIPELINE"
            !sdrProcessedTransport -> "NON_SDR_TRANSPORT"
            !dimensionsMatch -> "NON_1K_CAPTURE_SIZE"
            targetFps != TARGET_FPS -> "NON_30_FPS"
            !supportsManualSensor -> "MANUAL_SENSOR_UNAVAILABLE"
            manualIso > 0 || manualExposureTimeNs > 0L -> "USER_MANUAL_CONTROL"
            else -> "NPV10_1K30_SENSOR_CADENCE_GUARD"
        }
        val active = reason == "NPV10_1K30_SENSOR_CADENCE_GUARD"
        return Npv10CadenceExposurePlan(
            active = active,
            fixedExposureTimeNs = FIXED_EXPOSURE_TIME_NS.takeIf { active },
            forceAntibandingOff = active,
            reason = reason
        )
    }

    /**
     * Applies the device-specific cadence override only when the HAL advertises OFF and only to a
     * non-still request. Every unrelated pipeline retains its existing power-grid policy.
     */
    fun resolveAntibandingMode(
        plan: Npv10CadenceExposurePlan,
        requestedMode: Int?,
        availableModes: IntArray,
        stillCapture: Boolean
    ): Int? = if (
        plan.forceAntibandingOff &&
        !stillCapture &&
        availableModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF)
    ) {
        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
    } else {
        requestedMode
    }
}

internal data class Npv10CadenceExposurePlan(
    val active: Boolean,
    val fixedExposureTimeNs: Long?,
    val forceAntibandingOff: Boolean,
    val reason: String
)
