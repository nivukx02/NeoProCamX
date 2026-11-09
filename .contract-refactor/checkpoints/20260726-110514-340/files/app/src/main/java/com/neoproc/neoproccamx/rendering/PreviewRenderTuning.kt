package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * HIGH-END PREVIEW RENDER TUNING - UPGRADED
 * 
 * Provides professional-grade preview rendering parameters:
 * - Extended contrast range for LOG video modes
 * - Better shadow lift for dark video modes
 * - Proper LOG parity bias scale passing
 * - HDR brightness compensation
 */
internal object PreviewRenderTuning {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    internal data class LutSummary(
        val redBias: Float,
        val greenBias: Float,
        val blueBias: Float,
        val warmthBias: Float,
        val lumaBias: Float = 0f,
        val lumaRange: Float = 0f,
        val chromaEnergy: Float = 0f
    )

    internal data class GradeAdjustments(
        val contrast: Float,
        val saturation: Float,
        val redScale: Float,
        val greenScale: Float,
        val blueScale: Float,
        val redBias: Float,
        val greenBias: Float,
        val blueBias: Float,
        // UPGRADED: Add LOG parity bias scales for shader
        val shadowBiasScale: Float = 1f,
        val highlightBiasScale: Float = 1f
    )

    internal data class StabilizationTransform(
        val zoom: Float,
        val translationX: Float,
        val translationY: Float
    )

    internal fun stabilizeForDirectPreviewFallback(
        adjustments: GradeAdjustments
    ): GradeAdjustments {
        return adjustments.copy(
            contrast = 1f,
            saturation = 1f,
            redScale = 1f,
            greenScale = 1f,
            blueScale = 1f,
            redBias = 0f,
            greenBias = 0f,
            blueBias = 0f,
            shadowBiasScale = 1f,
            highlightBiasScale = 1f
        )
    }

    internal fun stabilizeForNeutralVideoPreview(
        adjustments: GradeAdjustments,
        neutralVideoPreview: Boolean
    ): GradeAdjustments =
        if (neutralVideoPreview) {
            stabilizeForDirectPreviewFallback(adjustments)
        } else {
            adjustments
        }

    fun summarizeLut(packedRgb: FloatArray?, size: Int): LutSummary? {
        if (packedRgb == null || size <= 1 || packedRgb.size < 3) return null
        val totalEntries = packedRgb.size / 3
        if (totalEntries <= 0) return null

        val sampleCount = minOf(128, totalEntries)
        val stride = max(1, totalEntries / sampleCount)
        val phase = max(1, totalEntries / 7)
        var red = 0f
        var green = 0f
        var blue = 0f
        var luma = 0f
        var chroma = 0f
        var minLuma = 1f
        var maxLuma = 0f
        var minChannel = 1f
        var maxChannel = 0f

        for (sampleIndex in 0 until sampleCount) {
            val index = (sampleIndex * stride + (sampleIndex * sampleIndex + phase)) % totalEntries
            val base = index * 3
            val r = packedRgb[base].coerceIn(0f, 1f)
            val g = packedRgb[base + 1].coerceIn(0f, 1f)
            val b = packedRgb[base + 2].coerceIn(0f, 1f)
            val currentLuma = r * 0.2126f + g * 0.7152f + b * 0.0722f
            val currentChroma = max(r, max(g, b)) - min(r, min(g, b))

            red += r
            green += g
            blue += b
            luma += currentLuma
            chroma += currentChroma
            minLuma = min(minLuma, currentLuma)
            maxLuma = max(maxLuma, currentLuma)
            minChannel = min(minChannel, min(r, min(g, b)))
            maxChannel = max(maxChannel, max(r, max(g, b)))
        }

        val sampleDivisor = sampleCount.toFloat()
        val avgRed = red / sampleDivisor
        val avgGreen = green / sampleDivisor
        val avgBlue = blue / sampleDivisor
        val avgLuma = luma / sampleDivisor
        val avgChroma = chroma / sampleDivisor
        val lumaDynamicRange = (maxLuma - minLuma).coerceIn(0f, 1f)
        val channelSpanRange = (maxChannel - minChannel).coerceIn(0f, 1f)
        return LutSummary(
            redBias = (avgRed - 0.5f).coerceIn(-0.4f, 0.4f),
            greenBias = (avgGreen - 0.5f).coerceIn(-0.4f, 0.4f),
            blueBias = (avgBlue - 0.5f).coerceIn(-0.4f, 0.4f),
            warmthBias = ((avgRed - avgBlue) * 0.62f + (avgLuma - 0.5f) * 0.08f)
                .coerceIn(-0.25f, 0.25f),
            lumaBias = (avgLuma - 0.5f).coerceIn(-0.20f, 0.20f),
            lumaRange = max(lumaDynamicRange, channelSpanRange * 0.35f).coerceIn(0f, 1f),
            chromaEnergy = avgChroma.coerceIn(0f, 0.8f)
        )
    }

    fun buildGradeAdjustments(
        profile: SceneRenderProfile,
        highLoadPreviewMode: Boolean,
        lutEnabled: Boolean,
        lutSummary: LutSummary?
    ): GradeAdjustments {
        val lutWarmth = lutSummary?.warmthBias ?: 0f
        val lutLumaBias = lutSummary?.lumaBias ?: 0f
        val lutLumaRange = lutSummary?.lumaRange ?: 0f
        val lutChromaEnergy = lutSummary?.chromaEnergy ?: 0f
        val lutContrastBias = if (lutEnabled) {
            (0.52f - lutLumaRange) * 0.14f - lutLumaBias * 0.06f
        } else {
            0f
        }
        val lutSaturationBalance = if (lutEnabled) {
            (0.26f - lutChromaEnergy) * 0.16f
        } else {
            0f
        }
        val warmth = (profile.warmth + lutWarmth + lutLumaBias * 0.05f)
            .coerceIn(-0.22f, 0.22f)
        val logFlatness = profile.logStrength.coerceIn(0f, 1f)
        val hdrFusion = profile.videoHdrFusionStrength.coerceIn(0f, 1f)
        val logParity = profile.logPreviewParity
        
        val highLoadPenaltyScale = if (logFlatness > 0f) {
            val maxPenalty = if (strictHighEndEnabled()) 0.62f else 0.75f 
            logParity.highLoadPenaltyScale.coerceIn(0f, maxPenalty)
        } else {
            1f
        }
        
        val lutSaturationBoost = if (lutEnabled) {
            if (logFlatness > 0f) {
                val boost = if (strictHighEndEnabled()) 1.85f else 1.70f 
                logParity.lutSaturationBoost * boost
            } else {
                0.045f 
            }
        } else {
            0f
        }
        
        val highLoadChromaPenalty = if (highLoadPreviewMode) {
            (0.16f + hdrFusion * 0.18f + logFlatness * 0.12f) * highLoadPenaltyScale
        } else {
            0f
        }
        val highLoadContrastPenalty = if (highLoadPreviewMode) {
            (0.07f + hdrFusion * 0.10f + logFlatness * 0.08f) * highLoadPenaltyScale
        } else {
            0f
        }
        val saturationCeiling = if (highLoadPreviewMode) 2.20f else 3.55f
        val contrastCeiling = if (highLoadPreviewMode) 2.45f else 3.65f

        val saturation = (
            profile.saturation +
                lutSaturationBoost +
                lutSaturationBalance +
                logParity.saturationLift * logFlatness * 2.25f - 
                hdrFusion * 0.005f - 
                highLoadChromaPenalty
            ).coerceIn(0.20f, saturationCeiling)
        
        val contrast = (
            profile.contrast -
                profile.highlightCompression * 0.015f + 
                lutContrastBias +
                logParity.contrastLift * logFlatness * 2.45f + 
                hdrFusion * 0.24f -
                highLoadContrastPenalty
            ).coerceIn(0.35f, contrastCeiling)

        val lutRed = lutSummary?.redBias ?: 0f
        val lutGreen = lutSummary?.greenBias ?: 0f
        val lutBlue = lutSummary?.blueBias ?: 0f
        
        val shadowLift = (
            profile.shadowBoost * 64f + 
                logFlatness * 42f * logParity.shadowBiasScale + 
                hdrFusion * 28f + 
                (if (lutEnabled) (0.24f - lutLumaRange).coerceAtLeast(0f) * 24f else 0f) -
                (if (lutEnabled) lutLumaBias.coerceAtLeast(0f) * 3.5f else 0f)
            ).coerceIn(0f, 150f)
        
        val highlightBias = (
            profile.highlightCompression * 32f + 
                logFlatness * 16.5f * logParity.highlightBiasScale + 
                hdrFusion * 12.2f + 
                (if (lutEnabled) lutLumaBias.coerceAtLeast(0f) * 11.5f else 0f) +
                (if (lutEnabled) lutLumaRange.coerceAtLeast(0.5f) * 4.2f else 0f)
            ).coerceIn(0f, 120f)

        val hdrColorCritical = hdrFusion >= 0.50f && logFlatness <= 0.01f && !lutEnabled
        val hdrNeutralBias = (shadowLift * 0.54f - highlightBias * 0.62f).coerceIn(-18f, 18f)
        val redScale = if (hdrColorCritical) {
            (1f + warmth * 0.03f - hdrFusion * 0.006f).coerceIn(0.985f, 1.01f)
        } else {
            (1f + warmth * 0.22f + lutRed * 0.08f - logFlatness * 0.012f + lutLumaBias * 0.025f)
                .coerceIn(0.78f, 1.22f)
        }
        val greenScale = if (hdrColorCritical) {
            1f
        } else {
            (1f + lutGreen * 0.07f - hdrFusion * 0.006f + lutLumaBias * 0.020f)
                .coerceIn(0.82f, 1.18f)
        }
        val blueScale = if (hdrColorCritical) {
            (1f - warmth * 0.01f + hdrFusion * 0.004f).coerceIn(0.99f, 1.015f)
        } else {
            (1f - warmth * 0.20f + lutBlue * 0.08f - logFlatness * 0.015f + lutLumaBias * 0.015f)
                .coerceIn(0.78f, 1.22f)
        }
        val redBias = if (hdrColorCritical) {
            (hdrNeutralBias - hdrFusion * 1.5f + warmth * 1.5f).coerceIn(-18f, 18f)
        } else {
            (shadowLift - highlightBias * 0.52f + lutRed * 14f + warmth * 12f + lutLumaBias * 18f)
                .coerceIn(-28f, 35f)
        }
        val greenBias = if (hdrColorCritical) {
            hdrNeutralBias
        } else {
            (shadowLift * 0.68f - highlightBias * 0.62f + lutGreen * 12f + lutLumaBias * 12f)
                .coerceIn(-24f, 28f)
        }
        val blueBias = if (hdrColorCritical) {
            (hdrNeutralBias + hdrFusion * 1.5f - warmth * 0.5f).coerceIn(-18f, 18f)
        } else {
            (shadowLift * 0.55f - highlightBias * 0.70f + lutBlue * 14f - warmth * 10f + lutLumaBias * 10f)
                .coerceIn(-28f, 30f)
        }

        return GradeAdjustments(
            contrast = contrast,
            saturation = saturation,
            redScale = redScale,
            greenScale = greenScale,
            blueScale = blueScale,
            redBias = redBias,
            greenBias = greenBias,
            blueBias = blueBias,
            shadowBiasScale = logParity.shadowBiasScale.coerceIn(0.25f, 1.4f),
            highlightBiasScale = logParity.highlightBiasScale.coerceIn(0.25f, 1.4f)
        )
    }

    fun computeStabilizationTransform(
        strength: Float,
        anchorX: Float,
        anchorY: Float,
        contentWidth: Float,
        contentHeight: Float
    ): StabilizationTransform {
        if (strength <= 0f || contentWidth <= 0f || contentHeight <= 0f) {
            return StabilizationTransform(zoom = 1f, translationX = 0f, translationY = 0f)
        }

        val safeStrength = strength.coerceIn(0f, 1f)
        val safeAnchorX = anchorX.coerceIn(0f, 1f)
        val safeAnchorY = anchorY.coerceIn(0f, 1f)
        val stabilizationScalar = if (strictHighEndEnabled()) 0.18f else 0.16f
        val requestedTranslationX = (0.5f - safeAnchorX) * contentWidth * safeStrength * stabilizationScalar
        val requestedTranslationY = (0.5f - safeAnchorY) * contentHeight * safeStrength * stabilizationScalar
        val requiredZoomX = 1f + (abs(requestedTranslationX) * 2f / contentWidth.coerceAtLeast(1e-4f))
        val requiredZoomY = 1f + (abs(requestedTranslationY) * 2f / contentHeight.coerceAtLeast(1e-4f))
        val zoom = max(
            1f + safeStrength * 0.075f,
            max(requiredZoomX, requiredZoomY)
        ).coerceIn(1f, 1.2f)
        val maxTranslationX = ((contentWidth * zoom) - contentWidth) * 0.5f
        val maxTranslationY = ((contentHeight * zoom) - contentHeight) * 0.5f
        val translationX = requestedTranslationX.coerceIn(-maxTranslationX, maxTranslationX)
        val translationY = requestedTranslationY.coerceIn(-maxTranslationY, maxTranslationY)
        return StabilizationTransform(
            zoom = zoom,
            translationX = if (abs(translationX) < 0.001f) 0f else translationX,
            translationY = if (abs(translationY) < 0.001f) 0f else translationY
        )
    }
}
