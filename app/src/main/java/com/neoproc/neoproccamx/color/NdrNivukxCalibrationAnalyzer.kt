package com.neoproc.neoproccamx.color

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

internal data class CalibrationPatchMeasurement(
    val patchId: String,
    val measuredSrgb: CalibrationRgb
)

internal data class NdrNivukxCorrectionProfile(
    val redScale: Float,
    val greenScale: Float,
    val blueScale: Float,
    val highlightRolloffBias: Float,
    val shadowLiftBias: Float,
    val saturationScale: Float,
    val skinHueCorrectionDegrees: Float,
    val skyHueCorrectionDegrees: Float,
    val confidence: Float
)

internal object NdrNivukxCalibrationAnalyzer {
    private const val EPSILON = 1e-6f

    fun analyze(measurements: List<CalibrationPatchMeasurement>): NdrNivukxCorrectionProfile {
        val measuredById = measurements.associate { it.patchId to it.measuredSrgb.sanitized() }
        val pairs = NdrNivukxCalibrationTarget.patches.mapNotNull { patch ->
            measuredById[patch.id]?.let { CalibrationPair(patch, it) }
        }
        if (pairs.isEmpty()) return neutral(confidence = 0f)

        val redOverflow = averagePositiveError(
            pairs = pairs.filter {
                it.patch.role == CalibrationPatchRole.SKIN ||
                    it.patch.role == CalibrationPatchRole.SATURATED_RED
            },
            selector = { reference, measured ->
                val redLift = measured.r - reference.r
                val otherLift = ((measured.g - reference.g) + (measured.b - reference.b)) * 0.5f
                redLift - otherLift * 0.45f
            }
        )

        val neutralBalance = channelBalanceFromNeutrals(pairs)
        val redScale = (neutralBalance.redScale - redOverflow * 0.55f).coerceIn(0.72f, 1.08f)
        val greenScale = neutralBalance.greenScale.coerceIn(0.82f, 1.08f)
        val blueScale = neutralBalance.blueScale.coerceIn(0.82f, 1.08f)

        val highlightPairs = pairs.filter { it.patch.role == CalibrationPatchRole.HIGHLIGHT }
        val highlightClipPressure = averagePositiveError(highlightPairs) { reference, measured ->
            val measuredPeak = maxOf(measured.r, measured.g, measured.b)
            val referencePeak = maxOf(reference.r, reference.g, reference.b)
            val hardClipPressure =
                if (measured.r >= 0.995f || measured.g >= 0.995f || measured.b >= 0.995f) 0.08f else 0f
            val peakClipping = measuredPeak - max(0.985f, referencePeak)
            val lumaLift = luma(measured) - luma(reference)
            max(hardClipPressure, max(peakClipping, lumaLift * 0.65f))
        }
        val highlightRolloffBias = (highlightClipPressure * 0.75f).coerceIn(0f, 0.22f)

        val shadowCrush = averagePositiveError(
            pairs = pairs.filter { it.patch.role == CalibrationPatchRole.SHADOW },
            selector = { reference, measured ->
                val referenceY = luma(reference).coerceAtLeast(0.01f)
                val absoluteLoss = luma(reference) - luma(measured)
                val relativeLoss = absoluteLoss / referenceY
                absoluteLoss + relativeLoss * 0.07f
            }
        )
        val shadowLiftBias = (shadowCrush * 0.95f).coerceIn(0f, 0.18f)

        val saturationScale = resolveSaturationScale(pairs)
        val skinHueCorrection = hueCorrectionForRole(pairs, CalibrationPatchRole.SKIN)
        val skyHueCorrection = hueCorrectionForRole(pairs, CalibrationPatchRole.SKY)
        val confidence = (pairs.size.toFloat() / NdrNivukxCalibrationTarget.patches.size.toFloat())
            .coerceIn(0f, 1f)

        return NdrNivukxCorrectionProfile(
            redScale = redScale,
            greenScale = greenScale,
            blueScale = blueScale,
            highlightRolloffBias = highlightRolloffBias,
            shadowLiftBias = shadowLiftBias,
            saturationScale = saturationScale,
            skinHueCorrectionDegrees = skinHueCorrection,
            skyHueCorrectionDegrees = skyHueCorrection,
            confidence = confidence
        )
    }

    private data class CalibrationPair(
        val patch: CalibrationPatch,
        val measured: CalibrationRgb
    )

    private data class ChannelBalance(
        val redScale: Float,
        val greenScale: Float,
        val blueScale: Float
    )

    private fun neutral(confidence: Float): NdrNivukxCorrectionProfile =
        NdrNivukxCorrectionProfile(
            redScale = 1f,
            greenScale = 1f,
            blueScale = 1f,
            highlightRolloffBias = 0f,
            shadowLiftBias = 0f,
            saturationScale = 1f,
            skinHueCorrectionDegrees = 0f,
            skyHueCorrectionDegrees = 0f,
            confidence = confidence
        )

    private fun channelBalanceFromNeutrals(pairs: List<CalibrationPair>): ChannelBalance {
        val neutrals = pairs.filter { it.patch.role == CalibrationPatchRole.NEUTRAL }
        if (neutrals.isEmpty()) return ChannelBalance(1f, 1f, 1f)
        val redRatio = neutrals.meanRatio { it.r }
        val greenRatio = neutrals.meanRatio { it.g }
        val blueRatio = neutrals.meanRatio { it.b }
        val anchor = (redRatio + greenRatio + blueRatio) / 3f
        return ChannelBalance(
            redScale = (anchor / redRatio.coerceAtLeast(EPSILON)).coerceIn(0.72f, 1.08f),
            greenScale = (anchor / greenRatio.coerceAtLeast(EPSILON)).coerceIn(0.82f, 1.08f),
            blueScale = (anchor / blueRatio.coerceAtLeast(EPSILON)).coerceIn(0.82f, 1.08f)
        )
    }

    private fun List<CalibrationPair>.meanRatio(channel: (CalibrationRgb) -> Float): Float {
        if (isEmpty()) return 1f
        return map { pair ->
            channel(pair.measured).coerceAtLeast(EPSILON) /
                channel(pair.patch.referenceSrgb).coerceAtLeast(EPSILON)
        }.average().toFloat().coerceAtLeast(EPSILON)
    }

    private fun averagePositiveError(
        pairs: List<CalibrationPair>,
        selector: (reference: CalibrationRgb, measured: CalibrationRgb) -> Float
    ): Float {
        if (pairs.isEmpty()) return 0f
        return pairs.map {
            selector(it.patch.referenceSrgb.sanitized(), it.measured).coerceAtLeast(0f)
        }.average().toFloat()
    }

    private fun resolveSaturationScale(pairs: List<CalibrationPair>): Float {
        val colorPairs = pairs.filter {
            it.patch.role != CalibrationPatchRole.NEUTRAL &&
                it.patch.role != CalibrationPatchRole.SHADOW &&
                it.patch.role != CalibrationPatchRole.HIGHLIGHT &&
                it.patch.role != CalibrationPatchRole.TEXTURE
        }
        if (colorPairs.isEmpty()) return 1f
        val measuredToReference = colorPairs.map { pair ->
            val referenceChroma = chroma(pair.patch.referenceSrgb).coerceAtLeast(0.02f)
            chroma(pair.measured) / referenceChroma
        }.average().toFloat()
        return (1f / measuredToReference.coerceAtLeast(0.70f)).coerceIn(0.82f, 1.12f)
    }

    private fun hueCorrectionForRole(
        pairs: List<CalibrationPair>,
        role: CalibrationPatchRole
    ): Float {
        val rolePairs = pairs.filter { it.patch.role == role }
        if (rolePairs.isEmpty()) return 0f
        val measuredDrift = rolePairs.map { pair ->
            shortestHueDelta(
                reference = hueDegrees(pair.patch.referenceSrgb),
                measured = hueDegrees(pair.measured)
            )
        }.average().toFloat()
        return (-measuredDrift).coerceIn(-8f, 8f)
    }

    private fun luma(rgb: CalibrationRgb): Float =
        rgb.r * 0.2126f + rgb.g * 0.7152f + rgb.b * 0.0722f

    private fun chroma(rgb: CalibrationRgb): Float =
        maxOf(rgb.r, rgb.g, rgb.b) - minOf(rgb.r, rgb.g, rgb.b)

    private fun hueDegrees(rgb: CalibrationRgb): Float {
        val maxValue = maxOf(rgb.r, rgb.g, rgb.b)
        val minValue = minOf(rgb.r, rgb.g, rgb.b)
        val delta = maxValue - minValue
        if (delta <= EPSILON) return 0f
        val hue = when (maxValue) {
            rgb.r -> 60f * (((rgb.g - rgb.b) / delta) % 6f)
            rgb.g -> 60f * (((rgb.b - rgb.r) / delta) + 2f)
            else -> 60f * (((rgb.r - rgb.g) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    private fun shortestHueDelta(reference: Float, measured: Float): Float {
        val delta = ((measured - reference + 540f) % 360f) - 180f
        return if (delta.isFinite()) delta else 0f
    }
}
