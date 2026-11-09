package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

/**
 * WhiteBalanceConversion — Scientific white balance color conversion.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * EXTRACTED FROM CameraController — Phase 2 God-Class Decomposition
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Converts color temperature (Kelvin) to RGGB channel gains using the Tanner
 * Helland Planckian locus approximation, with optional sensor intrinsic
 * calibration correction and green-magenta tint compensation for N-LOG / NWG
 * parity.
 *
 * Thread safety: Stateless. All methods are safe to call from any thread.
 */
object WhiteBalanceConversion {

    /**
     * Convert a Kelvin value to RGGB channel gain vector.
     *
     * @param kelvin Color temperature in Kelvin (clamped to 2000–12000).
     * @param greenMagentaTint Green-magenta tint offset (1.0 = neutral).
     * @param characteristics Camera characteristics for sensor calibration correction.
     *                         If null, no sensor intrinsic correction is applied.
     * @return [RggbChannelVector] suitable for [CaptureRequest.COLOR_CORRECTION_GAINS].
     */
    fun kelvinToRggb(
        kelvin: Int,
        greenMagentaTint: Float = 1.0f,
        characteristics: CameraCharacteristics? = null
    ): RggbChannelVector {
        // Extract row 1 (green row) of the sensor forward matrix as plain floats so the gain math
        // itself is Android-free and unit-testable ([kelvinToRggbGains]).
        val sensorForwardGreenRow = characteristics?.get(
            CameraCharacteristics.SENSOR_FORWARD_MATRIX1
        )?.let { fm ->
            floatArrayOf(
                fm.getElement(1, 0).toFloat(),
                fm.getElement(1, 1).toFloat(),
                fm.getElement(1, 2).toFloat()
            )
        }
        val gains = kelvinToRggbGains(kelvin, greenMagentaTint, sensorForwardGreenRow)
        return RggbChannelVector(gains[0], gains[1], gains[2], gains[3])
    }

    /**
     * Pure Kelvin → normalized RGGB gains `[r, gEven, gOdd, b]`. Extracted from [kelvinToRggb] so
     * the gain math is unit-testable without the Android [RggbChannelVector] (whose getters return
     * defaults under unit tests). [sensorForwardGreenRow], when non-null, is row 1 (the green row)
     * of `SENSOR_FORWARD_MATRIX1` as `[r, g, b]` for the sensor intrinsic calibration correction.
     */
    fun kelvinToRggbGains(
        kelvin: Int,
        greenMagentaTint: Float = 1.0f,
        sensorForwardGreenRow: FloatArray? = null
    ): FloatArray {
        // ═══════════════════════════════════════════════════════════════════
        // SCIENTIFIC WB: Planckian locus + sensor intrinsic calibration
        // ═══════════════════════════════════════════════════════════════════
        // Tanner Helland approximation of blackbody radiation, corrected by
        // the sensor's physical forward matrix to eliminate the ~2% color
        // drift at 3200K (Tungsten) that pure theoretical models produce.
        val temp = kelvin.coerceIn(2000, 12000).toFloat() / 100f

        val red = if (temp <= 66f) 255f
        else (329.698727446f * (temp - 60f).pow(-0.1332047592f)).coerceIn(0f, 255f)

        val green = if (temp <= 66f)
            (99.4708025861f * ln(temp) - 161.1195681661f).coerceIn(0f, 255f)
        else
            (288.1221695283f * (temp - 60f).pow(-0.0755148492f)).coerceIn(0f, 255f)

        val blue = if (temp >= 66f) 255f
        else if (temp <= 19f) 0f
        else (138.5177312231f * ln(temp - 10f) - 305.0447927307f).coerceIn(0f, 255f)

        // Invert: gains = green / channel (warm → blue boost, cool → red boost)
        val gNorm = green.coerceAtLeast(1f)
        var rGain = (gNorm / red.coerceAtLeast(1f)).coerceIn(0.1f, 10f)
        var bGain = (gNorm / blue.coerceAtLeast(1f)).coerceIn(0.1f, 10f)

        // ── Sensor intrinsic calibration correction ──────────────────────
        if (sensorForwardGreenRow != null && sensorForwardGreenRow.size >= 3) {
            val fmR = sensorForwardGreenRow[0]
            val fmG = sensorForwardGreenRow[1]
            val fmB = sensorForwardGreenRow[2]
            val safeG = fmG.coerceAtLeast(0.01f)
            val sensorRCorrection = (fmR / safeG).coerceIn(0.85f, 1.15f)
            val sensorBCorrection = (fmB / safeG).coerceIn(0.85f, 1.15f)
            rGain = (rGain * (0.70f + 0.30f * sensorRCorrection)).coerceIn(0.1f, 10f)
            bGain = (bGain * (0.70f + 0.30f * sensorBCorrection)).coerceIn(0.1f, 10f)
        }

        // Green-magenta tint compensation for N-LOG / NWG parity.
        val tintCompensation = when {
            greenMagentaTint > 1.0f -> {
                val tintFactor = (greenMagentaTint - 1.0f).coerceIn(0f, 0.3f)
                val gOut = 1.0f - tintFactor * 0.5f
                val rOut = rGain + tintFactor * 0.15f
                val bOut = bGain + tintFactor * 0.15f
                Triple(rOut, gOut, bOut)
            }
            greenMagentaTint < 1.0f -> {
                val tintFactor = (1.0f - greenMagentaTint).coerceIn(0f, 0.3f)
                val gOut = 1.0f + tintFactor * 0.5f
                val rOut = (rGain - tintFactor * 0.1f).coerceAtLeast(0.1f)
                val bOut = (bGain - tintFactor * 0.1f).coerceAtLeast(0.1f)
                Triple(rOut, gOut, bOut)
            }
            else -> Triple(rGain, 1f, bGain)
        }

        // ISP-ceiling normalization prevents highlight clipping at extreme Kelvin
        val ispCeiling = 10.0f
        val maxGain = maxOf(tintCompensation.first, tintCompensation.second, tintCompensation.third)
        val normalizedR: Float
        val normalizedGr: Float
        val normalizedGb: Float
        val normalizedB: Float
        if (maxGain > ispCeiling) {
            val scale = ispCeiling / maxGain
            normalizedR = (tintCompensation.first * scale).coerceIn(0.1f, ispCeiling)
            normalizedGr = (tintCompensation.second * scale).coerceIn(0.1f, ispCeiling)
            normalizedGb = normalizedGr
            normalizedB = (tintCompensation.third * scale).coerceIn(0.1f, ispCeiling)
        } else {
            normalizedR = tintCompensation.first
            normalizedGr = tintCompensation.second
            normalizedGb = tintCompensation.second
            normalizedB = tintCompensation.third
        }

        return floatArrayOf(normalizedR, normalizedGr, normalizedGb, normalizedB)
    }
}
