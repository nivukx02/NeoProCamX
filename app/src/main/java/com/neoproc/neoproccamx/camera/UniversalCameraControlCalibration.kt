package com.neoproc.neoproccamx.camera

import kotlin.math.abs
import kotlin.math.pow

/**
 * Device-safe calibration shared by automatic WB and temporal color control.
 *
 * The corpus revision below is used only to validate display-domain scene priors and control
 * stability. It never replaces a phone's Camera2/DNG matrices, black levels, gain map, or HAL AWB.
 * Runtime decisions continue to use timestamp-matched hardware metadata; these functions only
 * reject malformed evidence and make the temporal response independent of capture frame rate.
 */
internal object UniversalCameraControlCalibration {
    const val CALIBRATION_VERSION = 1
    const val CORPUS_ID = "pictograph/coco-5k"
    const val CORPUS_REVISION = "618089714878d74dc665a85a39bf160d14fd8209"
    const val DISPLAY_NEUTRAL_SATURATION_CEILING = 0.16f
    const val DISPLAY_NEUTRAL_LUMA_FLOOR = 0.01f
    const val DISPLAY_NEUTRAL_LUMA_CEILING = 0.90f

    /** Reference cadence at which the original per-frame color budgets were tuned. */
    const val REFERENCE_CADENCE_FPS = 30.0
    private const val REFERENCE_FRAME_NS = 1_000_000_000.0 / REFERENCE_CADENCE_FPS
    private const val MIN_CADENCE_SCALE = 0.25f
    private const val MAX_CADENCE_SCALE = 4f

    // Deliberately broad hardware-evidence bounds. They identify corrupt metadata without treating
    // a legitimate saturated illuminant or an unusual sensor spectral response as invalid.
    private const val MIN_POSITIVE_GAIN = 0.05f
    private const val MAX_FINITE_GAIN = 20f
    private const val MAX_CHANNEL_SPREAD = 32f
    private const val GREEN_SPLIT_FULL_CONFIDENCE = 0.05f
    private const val GREEN_SPLIT_HARD_LIMIT = 0.35f
    private const val MIN_VALID_EVIDENCE_SCALE = 0.25f

    /**
     * Calibrates HAL AWB-state confidence with the same-frame RGGB evidence.
     *
     * Ordinary finite Camera2 gain vectors retain the incoming confidence exactly. A disagreement
     * between the two green phases is tapered rather than abruptly rejected; non-finite, non-positive,
     * impossible-spread, or non-realizable vectors fail closed at zero confidence.
     */
    fun whiteBalanceEvidenceConfidence(
        baseConfidence: Float,
        red: Float,
        greenEven: Float,
        greenOdd: Float,
        blue: Float
    ): Float {
        val base = baseConfidence.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: return 0f
        val channels = floatArrayOf(red, greenEven, greenOdd, blue)
        if (channels.any { !it.isFinite() || it < MIN_POSITIVE_GAIN || it > MAX_FINITE_GAIN }) {
            return 0f
        }
        val minimum = channels.minOrNull() ?: return 0f
        val maximum = channels.maxOrNull() ?: return 0f
        if (maximum / minimum > MAX_CHANNEL_SPREAD) return 0f
        if (WhiteBalanceChromaticity.rggbGainsToUv(red, greenEven, greenOdd, blue) == null) return 0f

        val greenMean = (greenEven + greenOdd) * 0.5f
        val greenSplit = abs(greenEven - greenOdd) / greenMean
        val evidenceScale = when {
            greenSplit <= GREEN_SPLIT_FULL_CONFIDENCE -> 1f
            greenSplit >= GREEN_SPLIT_HARD_LIMIT -> 0f
            else -> {
                val t = (greenSplit - GREEN_SPLIT_FULL_CONFIDENCE) /
                    (GREEN_SPLIT_HARD_LIMIT - GREEN_SPLIT_FULL_CONFIDENCE)
                1f - (1f - MIN_VALID_EVIDENCE_SCALE) * t
            }
        }
        return (base * evidenceScale).coerceIn(0f, 1f)
    }

    /** Converts sensor timestamp spacing into a bounded multiple of the 30 fps reference interval. */
    fun cadenceScale(previousSensorTimestampNs: Long?, sensorTimestampNs: Long?): Float {
        val previous = previousSensorTimestampNs?.takeIf { it > 0L } ?: return 1f
        val current = sensorTimestampNs?.takeIf { it > previous } ?: return 1f
        return ((current - previous).toDouble() / REFERENCE_FRAME_NS)
            .toFloat()
            .coerceIn(MIN_CADENCE_SCALE, MAX_CADENCE_SCALE)
    }

    /**
     * Frame-rate-independent exponential response. At the reference cadence, the returned value is
     * exactly [referenceFactor]; at higher/lower cadence it has the same response per elapsed second.
     */
    fun cadenceAdjustedSmoothing(referenceFactor: Float, cadenceScale: Float): Float {
        val alpha = referenceFactor.coerceIn(0f, 1f)
        if (alpha == 0f || alpha == 1f) return alpha
        val scale = cadenceScale.takeIf(Float::isFinite)
            ?.coerceIn(MIN_CADENCE_SCALE, MAX_CADENCE_SCALE)
            ?: 1f
        return (1.0 - (1.0 - alpha.toDouble()).pow(scale.toDouble()))
            .toFloat()
            .coerceIn(0f, 1f)
    }
}
