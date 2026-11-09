package com.neoproc.neoproccamx.niv99

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Niv99SpatialPlan(capacity: Int) {
    val rawStrength: FloatArray = FloatArray(capacity)
    val lumaStrength: FloatArray = FloatArray(capacity)
    val chromaStrength: FloatArray = FloatArray(capacity)
    val directionalStrength: FloatArray = FloatArray(capacity)
    val waveletFineThreshold: FloatArray = FloatArray(capacity)
    val waveletMediumThreshold: FloatArray = FloatArray(capacity)
    val waveletCoarseThreshold: FloatArray = FloatArray(capacity)
    val edgeProtection: FloatArray = FloatArray(capacity)
    val textureProtection: FloatArray = FloatArray(capacity)
    val skinProtection: FloatArray = FloatArray(capacity)
    val sharpening: FloatArray = FloatArray(capacity)
    var count: Int = 0
        internal set
    var meanRawStrength: Float = 0f
        internal set
    var meanLumaStrength: Float = 0f
        internal set
    var meanChromaStrength: Float = 0f
        internal set
    var meanSharpening: Float = 0f
        internal set
}

/** Variance-normalized spatial and multiscale policy with conservative luma/detail behavior. */
class Niv99SpatialDenoisePolicy {
    fun resolve(
        context: Niv99FrameContext,
        statistics: Niv99TileStatistics,
        noise: Niv99NoiseModelSnapshot,
        detail: Niv99DetailMap,
        skin: Niv99SkinMap,
        output: Niv99SpatialPlan
    ): Niv99ValidationResult {
        if (statistics.count > output.rawStrength.size || detail.count != statistics.count ||
            skin.count != statistics.count
        ) {
            return Niv99ValidationResult.INVALID_DIMENSIONS
        }
        output.count = statistics.count
        var rawSum = 0.0
        var lumaSum = 0.0
        var chromaSum = 0.0
        var sharpenSum = 0.0
        for (index in 0 until statistics.count) {
            val red = statistics.meanRed[index]
            val green = statistics.meanGreen[index]
            val blue = statistics.meanBlue[index]
            val variance = noise.meanVariance(red, green, blue)
            val sigma = sqrt(max(variance, MIN_VARIANCE))
            val signal = max((red + 2f * green + blue) * 0.25f, MIN_SIGNAL)
            val normalizedNoise = (sigma / signal).coerceIn(0f, MAX_NORMALIZED_NOISE)
            val noisePressure = Niv99Math.smoothstep(NOISE_LOW, NOISE_HIGH, normalizedNoise)
            val shadowPressure = 1f - Niv99Math.smoothstep(SHADOW_LOW, SHADOW_HIGH, signal)
            val edgeProtection = detail.edgeConfidence[index]
            val textureProtection = max(
                detail.aggregateProtection[index],
                skin.textureProtection[index]
            )
            val profile = profileTuning(context.detailProfile)
            val detailGuard = max(edgeProtection * profile.edgeProtection, textureProtection * profile.textureProtection)
            val motionGuard = Niv99Math.smoothstep(0.12f, 0.82f, context.motionMagnitude)
            val base = (noisePressure * 0.72f + shadowPressure * noisePressure * 0.28f)
            val rawStrength = (base * profile.rawStrength * (1f - detailGuard * 0.72f))
                .coerceIn(0f, profile.maximumRawStrength)
            val lumaStrength = (base * profile.lumaStrength *
                (1f - detailGuard * 0.82f) * (1f - motionGuard * 0.12f))
                .coerceIn(0f, profile.maximumLumaStrength)
            val chromaStructureGuard = max(edgeProtection * 0.55f, skin.confidence[index] * 0.45f)
            val chromaStrength = (base * profile.chromaStrength * (1f - chromaStructureGuard * 0.42f))
                .coerceIn(0f, profile.maximumChromaStrength)
            val directionalStrength = lumaStrength *
                (0.55f + 0.45f * detail.directionalCoherence[index])
            val fineThreshold = sigma * Niv99Math.lerp(0.65f, 1.35f, lumaStrength) *
                (1f - textureProtection * 0.62f)
            val mediumThreshold = sigma * Niv99Math.lerp(0.85f, 1.65f, lumaStrength) *
                (1f - textureProtection * 0.48f)
            val coarseThreshold = sigma * Niv99Math.lerp(0.55f, 1.15f, lumaStrength) *
                (1f - edgeProtection * 0.72f)
            val sharpening = Niv99ReconstructionSharpeningPolicy.resolve(
                measuredMtf50Retention = profile.assumedPreSharpenMtfRetention,
                noiseStandardDeviation = sigma,
                edgeConfidence = edgeProtection,
                textureConfidence = detail.fineTextureConfidence[index],
                haloRisk = lumaStrength * edgeProtection,
                profile = context.detailProfile,
                tileCeiling = detail.sharpeningCeiling[index]
            )
            output.rawStrength[index] = rawStrength
            output.lumaStrength[index] = lumaStrength
            output.chromaStrength[index] = max(chromaStrength, lumaStrength).coerceAtMost(profile.maximumChromaStrength)
            output.directionalStrength[index] = directionalStrength
            output.waveletFineThreshold[index] = fineThreshold
            output.waveletMediumThreshold[index] = mediumThreshold
            output.waveletCoarseThreshold[index] = coarseThreshold
            output.edgeProtection[index] = edgeProtection
            output.textureProtection[index] = textureProtection
            output.skinProtection[index] = skin.confidence[index]
            output.sharpening[index] = sharpening
            rawSum += rawStrength
            lumaSum += lumaStrength
            chromaSum += output.chromaStrength[index]
            sharpenSum += sharpening
        }
        val denominator = max(statistics.count, 1)
        output.meanRawStrength = (rawSum / denominator).toFloat()
        output.meanLumaStrength = (lumaSum / denominator).toFloat()
        output.meanChromaStrength = (chromaSum / denominator).toFloat()
        output.meanSharpening = (sharpenSum / denominator).toFloat()
        return Niv99ValidationResult.VALID
    }

    private fun profileTuning(profile: Niv99DetailProfile): ProfileTuning = when (profile) {
        Niv99DetailProfile.FOCUSED -> FOCUSED
        Niv99DetailProfile.NATURAL -> NATURAL
        Niv99DetailProfile.EXTREME -> EXTREME
    }

    private class ProfileTuning(
        val rawStrength: Float,
        val lumaStrength: Float,
        val chromaStrength: Float,
        val edgeProtection: Float,
        val textureProtection: Float,
        val maximumRawStrength: Float,
        val maximumLumaStrength: Float,
        val maximumChromaStrength: Float,
        val assumedPreSharpenMtfRetention: Float
    )

    companion object {
        private val FOCUSED = ProfileTuning(
            rawStrength = 0.76f,
            lumaStrength = 0.58f,
            chromaStrength = 0.88f,
            edgeProtection = 0.90f,
            textureProtection = 0.84f,
            maximumRawStrength = 0.78f,
            maximumLumaStrength = 0.62f,
            maximumChromaStrength = 0.86f,
            assumedPreSharpenMtfRetention = 0.88f
        )
        private val NATURAL = ProfileTuning(
            rawStrength = 0.68f,
            lumaStrength = 0.48f,
            chromaStrength = 0.84f,
            edgeProtection = 0.96f,
            textureProtection = 0.96f,
            maximumRawStrength = 0.72f,
            maximumLumaStrength = 0.54f,
            maximumChromaStrength = 0.82f,
            assumedPreSharpenMtfRetention = 0.91f
        )
        private val EXTREME = ProfileTuning(
            rawStrength = 0.62f,
            lumaStrength = 0.42f,
            chromaStrength = 0.80f,
            edgeProtection = 1f,
            textureProtection = 1f,
            maximumRawStrength = 0.68f,
            maximumLumaStrength = 0.48f,
            maximumChromaStrength = 0.80f,
            assumedPreSharpenMtfRetention = 0.86f
        )
        private const val MIN_VARIANCE = 1e-12f
        private const val MIN_SIGNAL = 1e-5f
        private const val MAX_NORMALIZED_NOISE = 2f
        private const val NOISE_LOW = 0.015f
        private const val NOISE_HIGH = 0.28f
        private const val SHADOW_LOW = 0.012f
        private const val SHADOW_HIGH = 0.18f
    }
}

