package com.neoproc.neoproccamx.camera

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class ManualSensorEvCompensationResult(
    val iso: Int,
    val exposureNs: Long,
    val evStops: Float
)

internal object ManualSensorEvCompensationPolicy {
    fun resolve(
        baseIso: Int,
        baseExposureNs: Long,
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

        val clampedIso = baseIso.coerceIn(lowerIso, upperIso)
        val clampedExposure = baseExposureNs.coerceIn(lowerExposure, upperExposure)
        val evStops = evIndex * evStepStops
        if (evIndex == 0 || evStepStops <= 0f) {
            return ManualSensorEvCompensationResult(
                iso = clampedIso,
                exposureNs = clampedExposure,
                evStops = 0f
            )
        }

        val gain = 2.0.pow(evStops.toDouble())
        val targetExposureProduct = clampedIso.toDouble() * clampedExposure.toDouble() * gain
        return if (gain >= 1.0) {
            val exposure = (clampedExposure.toDouble() * gain)
                .roundToLong()
                .coerceIn(lowerExposure, upperExposure)
            val iso = (targetExposureProduct / exposure.toDouble())
                .roundToInt()
                .coerceIn(lowerIso, upperIso)
            ManualSensorEvCompensationResult(
                iso = iso,
                exposureNs = exposure,
                evStops = evStops
            )
        } else {
            val iso = (clampedIso.toDouble() * gain)
                .roundToInt()
                .coerceIn(lowerIso, upperIso)
            val exposure = (targetExposureProduct / iso.toDouble())
                .roundToLong()
                .coerceIn(lowerExposure, upperExposure)
            ManualSensorEvCompensationResult(
                iso = iso,
                exposureNs = exposure,
                evStops = evStops
            )
        }
    }
}
