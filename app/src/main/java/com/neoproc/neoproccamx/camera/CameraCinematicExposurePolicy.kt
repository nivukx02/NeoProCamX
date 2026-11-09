package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.utils.ExposureCalculator
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class CameraCinematicExposurePlan(
    val activateManualExposure: Boolean,
    val iso: Int,
    val exposureNs: Long
)

/**
 * Resolves stable cinema timing without overriding explicit user controls.
 *
 * A public Camera2 manual-sensor request can establish stable frame timing;
 * it does not prove sensor readout quality. When the operator has not chosen
 * a shutter, cinema modes use the standard 180-degree exposure duration.
 */
internal object CameraCinematicExposurePolicy {
    fun resolve(
        cinematicActive: Boolean,
        supportsManualSensor: Boolean,
        requestedIso: Int,
        requestedExposureNs: Long,
        latestIso: Int?,
        targetFps: Int
    ): CameraCinematicExposurePlan {
        val validMeteredIso = latestIso?.takeIf { it > 0 }
        // Automatic cinema exposure must remain under the live Camera2 AE loop.  Freezing one
        // previously-metered ISO together with a 180-degree shutter made LOG/RAW unable to react to
        // reframing (for example, a dark room toward a bright window), producing permanent clipping.
        // Manual sensor timing is now entered only after the operator explicitly chooses ISO and/or
        // shutter.  This preserves the professional 180-degree path without pretending a stale ISO is
        // an adaptive exposure engine.
        val explicitManualSensorRequest = requestedIso > 0 || requestedExposureNs > 0L
        return CameraCinematicExposurePlan(
            activateManualExposure = cinematicActive &&
                supportsManualSensor &&
                explicitManualSensorRequest &&
                (requestedIso > 0 || validMeteredIso != null),
            iso = if (requestedIso > 0) requestedIso else (validMeteredIso ?: 100),
            exposureNs = if (requestedExposureNs > 0L) {
                requestedExposureNs
            } else {
                ExposureCalculator.shutterFor180Rule(targetFps.coerceAtLeast(1))
            }
        )
    }

    /**
     * Cinema/LOG/RAW exposure-compensation with a locked (180°) shutter as the DEFAULT: EV is realized
     * on ISO first so the motion cadence is preserved for moderate compensation. Only when ISO hits a
     * rail (bright scene at the sensor floor, or a dim scene at the ceiling) does the residual gain the
     * ISO could not absorb spill into the shutter, within the frame-duration budget — so EV always moves
     * brightness in BOTH directions instead of silently saturating. At EV=0 the shutter stays locked and
     * ISO equals the (anchored) baseline, so the neutral look is exactly restored.
     */
    fun applyLockedShutterEv(
        baseIso: Int,
        lockedExposureNs: Long,
        evIndex: Int,
        evStepStops: Float,
        isoRange: CameraIntRange,
        exposureRange: CameraLongRange,
        frameExposureBudgetNs: Long
    ): ManualSensorEvCompensationResult {
        val lowerIso = minOf(isoRange.lower, isoRange.upper).coerceAtLeast(1)
        val upperIso = maxOf(isoRange.lower, isoRange.upper).coerceAtLeast(lowerIso)
        val lowerExposure = minOf(exposureRange.lower, exposureRange.upper).coerceAtLeast(1L)
        val upperExposure = maxOf(exposureRange.lower, exposureRange.upper)
            .let { upper ->
                if (frameExposureBudgetNs > 0L) {
                    minOf(upper, frameExposureBudgetNs.coerceAtLeast(lowerExposure))
                } else {
                    upper
                }
            }
            .coerceAtLeast(lowerExposure)
        val anchoredIso = baseIso.coerceIn(lowerIso, upperIso)
        val lockedExposure = lockedExposureNs.coerceIn(lowerExposure, upperExposure)
        val evStops = if (evStepStops > 0f) evIndex * evStepStops else 0f
        if (evStops == 0f) {
            return ManualSensorEvCompensationResult(
                iso = anchoredIso,
                exposureNs = lockedExposure,
                evStops = 0f
            )
        }
        val totalGain = 2.0.pow(evStops.toDouble())
        // ISO first — keep the locked shutter for as long as ISO has headroom.
        val adjustedIso = (anchoredIso.toDouble() * totalGain)
            .roundToInt()
            .coerceIn(lowerIso, upperIso)
        // Residual gain the ISO could not realize because it railed → spill into the shutter.
        val realizedIsoGain = adjustedIso.toDouble() / anchoredIso.toDouble()
        val residualGain = if (realizedIsoGain > 0.0) totalGain / realizedIsoGain else 1.0
        val adjustedExposure = if (abs(residualGain - 1.0) < 1e-3) {
            lockedExposure
        } else {
            (lockedExposure.toDouble() * residualGain)
                .roundToLong()
                .coerceIn(lowerExposure, upperExposure)
        }
        return ManualSensorEvCompensationResult(
            iso = adjustedIso,
            exposureNs = adjustedExposure,
            evStops = evStops
        )
    }
}
