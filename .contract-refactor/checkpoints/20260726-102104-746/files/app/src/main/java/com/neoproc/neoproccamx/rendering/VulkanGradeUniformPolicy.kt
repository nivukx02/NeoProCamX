package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NormalSdrVideoQualityPolicy
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile

internal data class VulkanGradeUniformInput(
    val neutralVideoPreview: Boolean,
    val deliveryVideoToneBias: Boolean,
    val videoSurface: Boolean,
    val deliveryHdrMode: String,
    val logCaptureActive: Boolean,
    val sceneRenderProfile: SceneRenderProfile?,
    val hdrRenderActive: Boolean,
    val rawPreviewMonitoringActive: Boolean,
    val neoHdrColorCritical: Boolean,
    val adjustments: PreviewRenderTuning.GradeAdjustments,
    val previewMonitoringEnabled: Boolean,
    val rawVideoDelivery: Boolean,
    val normalizedHdrMode: String,
    val truthfulColorDelivery: Boolean,
    val measuredNdrNivukxCorrection: NdrNivukxMeasuredCorrection,
    val sdrQualityIntent: SdrQualityIntent,
    val toneDeliveryMode: VulkanToneDeliveryMode,
    val nativePreviewLutSize: Int
)

/** Pure grade resolution kept outside CameraViewModel so preview/encoder uniforms share one policy. */
internal object VulkanGradeUniformPolicy {
    fun resolve(input: VulkanGradeUniformInput): FloatArray = with(input) {
        val logRenderActive = !neutralVideoPreview && logCaptureActive
        val baseLogStrength = if (neutralVideoPreview) 0f else sceneRenderProfile?.logStrength ?: 0f
        val logStrength = if (neutralVideoPreview) {
            0f
        } else if (deliveryVideoToneBias && logRenderActive) {
            (baseLogStrength * 0.18f + 0.035f).coerceIn(0.06f, 0.18f)
        } else if (logRenderActive) {
            (baseLogStrength * 0.62f + 0.12f).coerceAtLeast(0.42f)
        } else {
            baseLogStrength * 0.45f
        }.coerceIn(0f, 0.68f)
        val hdrToneMappingActive = !neutralVideoPreview && hdrRenderActive
        val hdrStrength = if (!hdrToneMappingActive) {
            0f
        } else if (deliveryVideoToneBias) {
            (
                (sceneRenderProfile?.videoHdrFusionStrength ?: 0f) * 0.28f +
                    if (hdrRenderActive) 0.035f else 0f
                ).coerceIn(0f, 0.16f)
        } else {
            (
                (sceneRenderProfile?.videoHdrFusionStrength ?: 0f) * 0.72f +
                    if (hdrRenderActive) 0.14f else 0f
                ).coerceIn(0f, 0.62f)
        }
        val shadowLift = (
            (sceneRenderProfile?.shadowBoost ?: 0f) +
                (if (logRenderActive) {
                    if (deliveryVideoToneBias) 0.010f else 0.03f
                } else {
                    0f
                }) +
                (if (rawPreviewMonitoringActive) 0.035f else 0f) +
                (if (hdrRenderActive) {
                    if (deliveryVideoToneBias) 0.015f else 0.04f
                } else {
                    0f
                })
            ).coerceIn(0f, 0.82f)
        val highlightCompression = (
            (sceneRenderProfile?.highlightCompression ?: 0f) *
                (if (deliveryVideoToneBias) {
                    if (logRenderActive || hdrRenderActive) 0.36f else 0.55f
                } else {
                    if (logRenderActive || hdrRenderActive) 0.72f else 0.85f
                }) +
                (if (rawPreviewMonitoringActive) 0.06f else 0f)
            ).coerceIn(0f, if (deliveryVideoToneBias) 0.16f else 0.36f)
        val outputContrast = if (neoHdrColorCritical || deliveryVideoToneBias) {
            val strength = if (deliveryVideoToneBias) 0.38f else 0.24f
            (1f + (adjustments.contrast - 1f) * strength).coerceIn(0.94f, 1.06f)
        } else {
            adjustments.contrast
        }
        val outputSaturation = if (neoHdrColorCritical || deliveryVideoToneBias) {
            val strength = if (deliveryVideoToneBias) 0.42f else 0.26f
            (1f + (adjustments.saturation - 1f) * strength).coerceIn(0.94f, 1.06f)
        } else {
            adjustments.saturation
        }
        val displayReferredPreviewMonitoring =
            previewMonitoringEnabled && !rawVideoDelivery && !neutralVideoPreview
        val neutralizeTruthfulGrade = truthfulColorDelivery && !displayReferredPreviewMonitoring
        val stableNormalOrProSdrGrade =
            videoSurface &&
                deliveryHdrMode == "OFF" &&
                normalizedHdrMode == "OFF" &&
                !logCaptureActive &&
                !rawVideoDelivery &&
                !truthfulColorDelivery &&
                !neutralVideoPreview &&
                !neoHdrColorCritical
        val adaptiveSdrGrade = NormalSdrVideoQualityPolicy.resolve(
            intent = sdrQualityIntent,
            temporalBlend = sceneRenderProfile?.temporalBlend ?: 0.36f,
            spatialDenoise = sceneRenderProfile?.spatialDenoise ?: 0.64f,
            chromaDenoise = sceneRenderProfile?.chromaDenoise ?: 0.70f,
            shadowBoost = sceneRenderProfile?.shadowBoost ?: 0.22f,
            sharpening = sceneRenderProfile?.sharpening ?: 0.36f,
            warmth = sceneRenderProfile?.warmth ?: 0f,
            contrast = outputContrast,
            saturation = outputSaturation,
            highlightCompression = sceneRenderProfile?.highlightCompression ?: 0.30f,
            noiseMetric = sceneRenderProfile?.noiseMetrics?.lumaNoise ?: 0.35f,
            localChromaSpikeSmoothing = sceneRenderProfile?.chromaDenoise ?: 0.42f,
            sharpeningFloor = (sceneRenderProfile?.sharpening ?: 0.36f) * 0.65f,
            sharpeningCeiling = (sceneRenderProfile?.sharpening ?: 0.36f) + 0.25f,
            skinDetailProtection = 0.82f
        )
        val videoToneVisibilityFloor = when {
            !videoSurface || neutralVideoPreview -> 0f
            rawVideoDelivery -> 0.055f
            logCaptureActive -> 0.050f
            normalizedHdrMode != "OFF" || hdrRenderActive -> 0.060f
            truthfulColorDelivery -> 0.028f
            else -> 0f
        }
        val truthfulToneVisibilityFloor = if (neutralizeTruthfulGrade) videoToneVisibilityFloor else 0f
        val displayToneVisibilityFloor =
            if (displayReferredPreviewMonitoring) videoToneVisibilityFloor else 0f
        val videoHighlightRolloffFloor = when {
            !videoSurface || neutralVideoPreview -> 0f
            rawVideoDelivery -> 0.120f
            logCaptureActive -> 0.105f
            normalizedHdrMode != "OFF" || hdrRenderActive -> 0.140f
            truthfulColorDelivery -> 0.060f
            else -> 0f
        }
        val truthfulHighlightRolloffFloor =
            if (neutralizeTruthfulGrade) videoHighlightRolloffFloor else 0f
        val displayHighlightRolloffFloor =
            if (displayReferredPreviewMonitoring) videoHighlightRolloffFloor else 0f
        val truthfulHdrStrengthFloor = when {
            !neutralizeTruthfulGrade -> 0f
            normalizedHdrMode != "OFF" || hdrRenderActive -> 0.120f
            else -> 0f
        }
        val resolvedContrast = when {
            stableNormalOrProSdrGrade -> adaptiveSdrGrade.contrast
            neutralizeTruthfulGrade -> 1.0f
            else -> outputContrast
        }
        val resolvedSaturation = when {
            stableNormalOrProSdrGrade -> adaptiveSdrGrade.saturation
            neutralizeTruthfulGrade -> 1.0f
            else -> outputSaturation
        }
        val resolvedShadowLift = when {
            stableNormalOrProSdrGrade -> adaptiveSdrGrade.shadowBoost
            neutralizeTruthfulGrade -> maxOf(shadowLift, truthfulToneVisibilityFloor).coerceIn(0f, 0.82f)
            displayToneVisibilityFloor > 0f -> maxOf(shadowLift, displayToneVisibilityFloor).coerceIn(0f, 0.82f)
            else -> shadowLift
        }
        val baseResolvedHighlightCompression = when {
            stableNormalOrProSdrGrade -> adaptiveSdrGrade.highlightCompression
            neutralizeTruthfulGrade -> maxOf(highlightCompression, truthfulHighlightRolloffFloor).coerceIn(0f, 0.36f)
            displayHighlightRolloffFloor > 0f ->
                maxOf(highlightCompression, displayHighlightRolloffFloor).coerceIn(0f, 0.36f)
            else -> highlightCompression
        }
        val resolvedHighlightCompression = if (stableNormalOrProSdrGrade) {
            adaptiveSdrGrade.highlightCompression
        } else {
            (
                baseResolvedHighlightCompression +
                    if (measuredNdrNivukxCorrection.active) {
                        measuredNdrNivukxCorrection.highlightRolloffBias
                    } else {
                        0f
                    }
                ).coerceIn(0f, 0.36f)
        }
        val resolvedRedScale = when {
            stableNormalOrProSdrGrade -> 1f
            neutralizeTruthfulGrade -> 1f
            neoHdrColorCritical -> measuredNdrNivukxCorrection.redScale
            else -> adjustments.redScale
        }
        val resolvedGreenScale = when {
            stableNormalOrProSdrGrade -> 1f
            neutralizeTruthfulGrade -> 1f
            neoHdrColorCritical -> measuredNdrNivukxCorrection.greenScale
            else -> adjustments.greenScale
        }
        val resolvedBlueScale = when {
            stableNormalOrProSdrGrade -> 1f
            neutralizeTruthfulGrade -> 1f
            neoHdrColorCritical -> measuredNdrNivukxCorrection.blueScale
            else -> adjustments.blueScale
        }
        val resolvedLogStrength = when {
            stableNormalOrProSdrGrade -> 0f
            rawVideoDelivery -> 0f
            neutralizeTruthfulGrade && logCaptureActive -> maxOf(logStrength, 0.42f)
            neutralizeTruthfulGrade -> 0f
            else -> logStrength
        }
        val resolvedHdrStrength = when {
            stableNormalOrProSdrGrade -> 0f
            rawVideoDelivery -> 0f
            neutralizeTruthfulGrade -> maxOf(hdrStrength, truthfulHdrStrengthFloor).coerceIn(0f, 0.62f)
            else -> hdrStrength
        }
        val resolvedRedBias = when {
            stableNormalOrProSdrGrade -> 0f
            neutralizeTruthfulGrade || neoHdrColorCritical -> 0f
            else -> (adjustments.redBias / 255f).coerceIn(-0.2f, 0.2f)
        }
        val resolvedGreenBias = when {
            stableNormalOrProSdrGrade -> 0f
            neutralizeTruthfulGrade || neoHdrColorCritical -> 0f
            else -> (adjustments.greenBias / 255f).coerceIn(-0.2f, 0.2f)
        }
        val resolvedBlueBias = when {
            stableNormalOrProSdrGrade -> 0f
            neutralizeTruthfulGrade || neoHdrColorCritical -> 0f
            else -> (adjustments.blueBias / 255f).coerceIn(-0.2f, 0.2f)
        }
        floatArrayOf(
            resolvedContrast,
            resolvedSaturation,
            resolvedShadowLift,
            resolvedHighlightCompression,
            resolvedRedScale,
            resolvedGreenScale,
            resolvedBlueScale,
            resolvedLogStrength,
            resolvedRedBias,
            resolvedGreenBias,
            resolvedBlueBias,
            resolvedHdrStrength,
            toneDeliveryMode.shaderModeFlag,
            nativePreviewLutSize.toFloat(),
            adjustments.shadowBiasScale,
            adjustments.highlightBiasScale
        )
    }
}
