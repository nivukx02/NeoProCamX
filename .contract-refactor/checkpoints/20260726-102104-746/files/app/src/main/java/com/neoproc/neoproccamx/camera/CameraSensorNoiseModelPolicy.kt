package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Pair
import java.util.Locale

data class SensorNoiseModel(
    val shotNoiseCoefficient: Float,
    val readNoiseCoefficient: Float,
    val source: String
) {
    fun varianceForLinearLuma(linearLuma: Float): Float =
        (shotNoiseCoefficient * linearLuma.coerceIn(0f, 1f) + readNoiseCoefficient)
            .coerceAtLeast(0f)

    val signature: String
        get() = String.format(
            Locale.US,
            "heteroscedastic|source=%s|shot=%.7f|read=%.7f",
            source,
            shotNoiseCoefficient,
            readNoiseCoefficient
        )

    companion object {
        val FALLBACK = SensorNoiseModel(
            shotNoiseCoefficient = ImaxStrictProfile.SHOT_NOISE_COEFFICIENT,
            readNoiseCoefficient = ImaxStrictProfile.READ_NOISE_COEFFICIENT,
            source = "fallback-conservative"
        )
    }
}

/**
 * The heteroscedastic noise inputs a [com.neoproc.neoproccamx.rendering.RawBufferDescriptor] hands to
 * the MFSR / temporal-denoise / demosaic-threshold consumer (s1-f06 / CP12). `noiseS` is the
 * signal-dependent (shot) term, `noiseO` the constant (read) term, so the consumer's variance is
 * `noiseO + noiseS·signal`. Kept as a named value so a JVM boundary test can prove the descriptor
 * never hands a real profile to the consumer as 0.
 */
data class RawDescriptorNoiseInputs(
    val noiseS: Float,
    val noiseO: Float
)

object CameraSensorNoiseModelPolicy {
    fun fromCharacteristics(characteristics: CameraCharacteristics?): SensorNoiseModel =
        fromSensitivityRangeOrNull(
            characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?.let { it.lower to it.upper }
        ) ?: SensorNoiseModel.FALLBACK

    fun fromCaptureResult(result: CaptureResult?): SensorNoiseModel? =
        fromNoiseProfileOrNull(result?.get(CaptureResult.SENSOR_NOISE_PROFILE))

    fun fromNoiseProfile(noiseProfile: Array<Pair<Double, Double>>?): SensorNoiseModel {
        return fromNoiseProfileOrNull(noiseProfile) ?: SensorNoiseModel.FALLBACK
    }

    fun fromCoefficients(noiseProfile: Iterable<kotlin.Pair<Double, Double>>?): SensorNoiseModel =
        fromCoefficientPairsOrNull(noiseProfile) ?: SensorNoiseModel.FALLBACK

    /**
     * Descriptor-noise precedence — the SINGLE SOURCE OF TRUTH for the heteroscedastic model the
     * RAW buffer descriptor carries:
     *   dynamic CaptureResult `SENSOR_NOISE_PROFILE`  ›  characteristics `SENSOR_INFO_SENSITIVITY_RANGE`
     *   ›  conservative [SensorNoiseModel.FALLBACK].
     * Pure-typed so the boundary that feeds MFSR `noiseS`/`noiseO` (s1-f06 / CP12) is unit-testable
     * without Camera2. Equivalent to `fromCaptureResult(result) ?: fromCharacteristics(chars)`, but
     * every branch returns a strictly-positive shot/read pair — so this layer can never strand the
     * consumer at noise=0 when a real profile (or even no profile) exists.
     */
    fun resolveDescriptorModel(
        captureNoiseProfile: Iterable<kotlin.Pair<Double, Double>>?,
        sensitivityRange: kotlin.Pair<Int, Int>?
    ): SensorNoiseModel =
        fromCoefficientPairsOrNull(captureNoiseProfile)
            ?: fromSensitivityRangeOrNull(sensitivityRange)
            ?: SensorNoiseModel.FALLBACK

    /**
     * The exact `(noiseS, noiseO)` a descriptor must carry for [model] — the consumer boundary that
     * feeds MFSR `noiseS`/`noiseO` and the denoise/demosaic threshold inputs. Named so the boundary
     * is a single testable mapping rather than two inline field assignments.
     */
    fun descriptorNoiseInputs(model: SensorNoiseModel): RawDescriptorNoiseInputs =
        RawDescriptorNoiseInputs(
            noiseS = model.shotNoiseCoefficient,
            noiseO = model.readNoiseCoefficient
        )

    private fun fromSensitivityRangeOrNull(range: kotlin.Pair<Int, Int>?): SensorNoiseModel? {
        if (range == null) return null
        val (lower, upper) = range
        val minIso = lower.coerceAtLeast(1).toFloat()
        val maxIso = upper.coerceAtLeast(lower).toFloat()
        val gainRatio = (maxIso / minIso).coerceAtLeast(1f)
        return SensorNoiseModel(
            shotNoiseCoefficient = (
                ImaxStrictProfile.SHOT_NOISE_COEFFICIENT + gainRatio * 0.00004f
                ).coerceIn(0.00001f, 0.25f),
            readNoiseCoefficient = (
                ImaxStrictProfile.READ_NOISE_COEFFICIENT + gainRatio * 0.000008f
                ).coerceIn(0.000001f, 0.10f),
            source = "CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE"
        )
    }

    private fun fromNoiseProfileOrNull(noiseProfile: Array<Pair<Double, Double>>?): SensorNoiseModel? {
        return fromCoefficientPairsOrNull(
            noiseProfile?.mapNotNull { pair ->
                val shot = pair.first
                val read = pair.second
                if (shot == null || read == null) null else shot to read
            }
        )
    }

    private fun fromCoefficientPairsOrNull(
        noiseProfile: Iterable<kotlin.Pair<Double, Double>>?
    ): SensorNoiseModel? {
        val validPairs = noiseProfile
            ?.mapNotNull { (shotDouble, readDouble) ->
                val shot = shotDouble.toFloat()
                val read = readDouble.toFloat()
                if (
                    !shot.isFinite() || !read.isFinite() ||
                    shot < 0f || read < 0f
                ) {
                    null
                } else {
                    shot to read
                }
            }
            .orEmpty()

        if (validPairs.isEmpty()) return null

        val shotAverage = validPairs.map { it.first }.average().toFloat()
        val readAverage = validPairs.map { it.second }.average().toFloat()
        // A profile reporting zero shot AND zero read noise is non-physical — no sensor is noiseless. Reject
        // it so resolveDescriptorModel falls through to the conservative model, rather than hand the
        // MFSR/denoise consumer an unrealistically optimistic near-zero variance via the coerceIn floor.
        // (A merely tiny-but-positive profile is still honoured and the floor keeps it strictly positive.)
        if (shotAverage <= 0f && readAverage <= 0f) return null
        return SensorNoiseModel(
            shotNoiseCoefficient = shotAverage.coerceIn(0.00001f, 0.25f),
            readNoiseCoefficient = readAverage.coerceIn(0.000001f, 0.10f),
            source = "CaptureResult.SENSOR_NOISE_PROFILE"
        )
    }
}
