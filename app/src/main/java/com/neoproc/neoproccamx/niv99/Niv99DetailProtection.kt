package com.neoproc.neoproccamx.niv99

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Niv99DetailMap(capacity: Int) {
    val edgeConfidence: FloatArray = FloatArray(capacity)
    val fineTextureConfidence: FloatArray = FloatArray(capacity)
    val directionalCoherence: FloatArray = FloatArray(capacity)
    val repeatedPatternConfidence: FloatArray = FloatArray(capacity)
    val textConfidence: FloatArray = FloatArray(capacity)
    val foliageConfidence: FloatArray = FloatArray(capacity)
    val hairConfidence: FloatArray = FloatArray(capacity)
    val aggregateProtection: FloatArray = FloatArray(capacity)
    val sharpeningCeiling: FloatArray = FloatArray(capacity)
    var count: Int = 0
        internal set
}

/** Structure-aware detail protection; it controls denoise/sharpening, never format parameters. */
class Niv99DetailProtection {
    fun classify(
        statistics: Niv99TileStatistics,
        semantics: Niv99SemanticTileMap?,
        profile: Niv99DetailProfile,
        output: Niv99DetailMap
    ): Niv99ValidationResult {
        if (statistics.count > output.aggregateProtection.size ||
            semantics != null && semantics.count != statistics.count
        ) {
            return Niv99ValidationResult.INVALID_DIMENSIONS
        }
        semantics?.sanitize()
        output.count = statistics.count
        for (index in 0 until statistics.count) {
            val gradientR = statistics.gradientRed[index]
            val gradientG = statistics.gradientGreen[index]
            val gradientB = statistics.gradientBlue[index]
            val gradientMagnitude = (gradientR + 2f * gradientG + gradientB) * 0.25f
            val chromaticGradientDifference = max(
                abs(gradientR - gradientG),
                abs(gradientB - gradientG)
            )
            val edge = Niv99Math.smoothstep(EDGE_LOW, EDGE_HIGH, gradientMagnitude)
            val variance = (
                statistics.varianceRed[index] + 2f * statistics.varianceGreen[index] +
                    statistics.varianceBlue[index]
                ) * 0.25f
            val noiseFloor = 1f / max(statistics.signalToNoise[index], 1f)
            val textureEnergy = sqrt(max(variance, 0f)) - noiseFloor * NOISE_TEXTURE_SCALE
            val texture = Niv99Math.smoothstep(TEXTURE_LOW, TEXTURE_HIGH, textureEnergy) *
                (1f - Niv99Math.smoothstep(CHROMA_NOISE_LOW, CHROMA_NOISE_HIGH, chromaticGradientDifference))
            val coherence = if (gradientMagnitude > 1e-7f) {
                (1f - chromaticGradientDifference / gradientMagnitude).coerceIn(0f, 1f)
            } else {
                0f
            }
            val hair = semantics?.hair?.get(index) ?: 0f
            val foliage = semantics?.foliage?.get(index) ?: 0f
            val text = semantics?.text?.get(index) ?: 0f
            val repeated = semantics?.repeatedPattern?.get(index) ?: 0f
            val semanticDetail = max(max(hair, foliage), max(text, repeated))
            val profileProtection = when (profile) {
                Niv99DetailProfile.FOCUSED -> 0.86f
                Niv99DetailProfile.NATURAL -> 0.94f
                Niv99DetailProfile.EXTREME -> 1f
            }
            val protection = max(max(edge, texture), semanticDetail) * profileProtection
            val noisePenalty = Niv99Math.smoothstep(0.02f, 0.24f, noiseFloor) *
                (1f - max(text, repeated))
            val baseSharpeningCeiling = when (profile) {
                Niv99DetailProfile.FOCUSED -> 0.42f
                Niv99DetailProfile.NATURAL -> 0.30f
                Niv99DetailProfile.EXTREME -> 0.52f
            }
            output.edgeConfidence[index] = edge
            output.fineTextureConfidence[index] = texture
            output.directionalCoherence[index] = coherence
            output.repeatedPatternConfidence[index] = repeated
            output.textConfidence[index] = text
            output.foliageConfidence[index] = foliage
            output.hairConfidence[index] = hair
            output.aggregateProtection[index] = Niv99Math.saturateFinite(protection)
            output.sharpeningCeiling[index] = (
                baseSharpeningCeiling * (0.45f + 0.55f * protection) * (1f - 0.60f * noisePenalty)
                ).coerceIn(0f, baseSharpeningCeiling)
        }
        return Niv99ValidationResult.VALID
    }

    companion object {
        private const val EDGE_LOW = 0.004f
        private const val EDGE_HIGH = 0.08f
        private const val TEXTURE_LOW = 0.002f
        private const val TEXTURE_HIGH = 0.035f
        private const val CHROMA_NOISE_LOW = 0.015f
        private const val CHROMA_NOISE_HIGH = 0.12f
        private const val NOISE_TEXTURE_SCALE = 0.08f
    }
}

class Niv99SkinMap(capacity: Int) {
    val confidence: FloatArray = FloatArray(capacity)
    val textureProtection: FloatArray = FloatArray(capacity)
    val localIlluminantSuppression: FloatArray = FloatArray(capacity)
    var count: Int = 0
        internal set
}

/**
 * Skin protection requires independent semantic and chromatic evidence. It can preserve an identified
 * skin region but can never recolor an unclassified object toward a skin locus.
 */
class Niv99SkinProtection {
    fun resolve(
        statistics: Niv99TileStatistics,
        semantics: Niv99SemanticTileMap?,
        whiteBalance: Niv99WhiteBalanceSolution,
        output: Niv99SkinMap
    ): Niv99ValidationResult {
        if (statistics.count > output.confidence.size ||
            semantics != null && semantics.count != statistics.count
        ) {
            return Niv99ValidationResult.INVALID_DIMENSIONS
        }
        output.count = statistics.count
        for (index in 0 until statistics.count) {
            val semantic = semantics?.skin?.get(index) ?: 0f
            if (semantic < MIN_SEMANTIC_SKIN) {
                output.confidence[index] = 0f
                output.textureProtection[index] = 0f
                output.localIlluminantSuppression[index] = 0f
                continue
            }
            val red = statistics.meanRed[index] * whiteBalance.redGain
            val green = statistics.meanGreen[index] * whiteBalance.greenGain
            val blue = statistics.meanBlue[index] * whiteBalance.blueGain
            val sum = max(red + green + blue, 1e-6f)
            val normalizedRed = red / sum
            val normalizedGreen = green / sum
            val normalizedBlue = blue / sum
            val opponentWarm = normalizedRed - normalizedGreen
            val opponentBlue = normalizedBlue - normalizedGreen
            val warmGate = Niv99Math.smoothstep(-0.01f, 0.13f, opponentWarm) *
                (1f - Niv99Math.smoothstep(0.13f, 0.28f, opponentWarm))
            val blueGate = 1f - Niv99Math.smoothstep(-0.02f, 0.16f, opponentBlue)
            val luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f
            val exposureGate = Niv99Math.smoothstep(0.015f, 0.08f, luminance) *
                (1f - Niv99Math.smoothstep(0.72f, 0.98f, luminance))
            val chromaticEvidence = warmGate * blueGate * exposureGate
            val confidence = semantic * chromaticEvidence * statistics.reliability[index]
            output.confidence[index] = Niv99Math.saturateFinite(confidence)
            output.textureProtection[index] = Niv99Math.saturateFinite(confidence * 0.92f)
            // Suppress aggressive local WB only where both independent evidences agree.
            output.localIlluminantSuppression[index] = Niv99Math.saturateFinite(confidence * 0.70f)
        }
        return Niv99ValidationResult.VALID
    }

    companion object {
        private const val MIN_SEMANTIC_SKIN = 0.20f
    }
}

object Niv99ReconstructionSharpeningPolicy {
    fun resolve(
        measuredMtf50Retention: Float,
        noiseStandardDeviation: Float,
        edgeConfidence: Float,
        textureConfidence: Float,
        haloRisk: Float,
        profile: Niv99DetailProfile,
        tileCeiling: Float
    ): Float {
        if (!measuredMtf50Retention.isFinite() || !noiseStandardDeviation.isFinite() ||
            !edgeConfidence.isFinite() || !textureConfidence.isFinite() || !haloRisk.isFinite()
        ) {
            return 0f
        }
        val deficit = (TARGET_MTF_RETENTION - measuredMtf50Retention).coerceIn(0f, 0.35f)
        val noiseGate = 1f - Niv99Math.smoothstep(0.012f, 0.09f, noiseStandardDeviation)
        val haloGate = 1f - Niv99Math.saturateFinite(haloRisk)
        val structureGate = max(edgeConfidence, textureConfidence * 0.75f)
        val profileScale = when (profile) {
            Niv99DetailProfile.FOCUSED -> 0.90f
            Niv99DetailProfile.NATURAL -> 0.72f
            Niv99DetailProfile.EXTREME -> 1f
        }
        return min(
            tileCeiling.coerceAtLeast(0f),
            deficit * 1.8f * noiseGate * haloGate * structureGate * profileScale
        )
    }

    private const val TARGET_MTF_RETENTION = 0.94f
}
