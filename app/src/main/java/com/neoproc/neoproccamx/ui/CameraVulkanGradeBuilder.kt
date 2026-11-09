package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.apex.ApexMode
import com.neoproc.neoproccamx.color.NeoLogCurve
import com.neoproc.neoproccamx.color.NeoLogProfilePolicy
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.rendering.NdrNivukxMeasuredCorrectionPolicy
import com.neoproc.neoproccamx.rendering.PreviewRenderTuning
import com.neoproc.neoproccamx.rendering.VulkanGradeUniformInput
import com.neoproc.neoproccamx.rendering.VulkanGradeUniformPolicy
import com.neoproc.neoproccamx.rendering.VulkanPreviewRenderContract
import com.neoproc.neoproccamx.rendering.VulkanToneDeliveryMode

/** Immutable input for resolving one preview/encoder grade outside CameraViewModel. */
internal data class CameraVulkanGradeRequest(
    val neutralVideoPreview: Boolean,
    val deliveryParityBias: Boolean,
    val highLoadPreview: Boolean,
    val truthfulColorDelivery: Boolean,
    val previewLookState: VideoFormatPreviewLookState,
    val renderContract: VulkanPreviewRenderContract?,
    val videoSurface: Boolean,
    val hdrRenderActive: Boolean,
    val deliveryState: VideoFormatDeliveryState,
    val lutEnabled: Boolean,
    val nativePreviewLutEnabled: Boolean,
    val nativePreviewLutSize: Int,
    val sceneRenderProfile: SceneRenderProfile?,
    val sdrQualityIntent: SdrQualityIntent,
    val colorProfile: String,
    val fallbackLogEnabled: Boolean
)

internal data class CameraVulkanGradeResult(
    val uniforms: FloatArray,
    val apexMode: ApexMode?
)

/** Pure grade resolver; state mutation and runtime engine injection remain with the caller. */
internal object CameraVulkanGradeBuilder {
    fun build(request: CameraVulkanGradeRequest): CameraVulkanGradeResult = with(request) {
        val adjustments = PreviewRenderTuning.stabilizeForNeutralVideoPreview(
            adjustments = PreviewRenderTuning.buildGradeAdjustments(
                profile = sceneRenderProfile ?: SceneRenderProfile(
                    temporalBlend = 0f,
                    spatialDenoise = 0f,
                    chromaDenoise = 0f,
                    shadowBoost = 0f,
                    sharpening = 0f,
                    warmth = 0f,
                    contrast = 1f,
                    saturation = 1f,
                    highlightCompression = 0f,
                    logStrength = 0f,
                    ditherStrength = 0f,
                    videoHdrFusionStrength = 0f
                ),
                highLoadPreviewMode = highLoadPreview,
                lutEnabled = lutEnabled &&
                    !(renderContract?.nativePreviewLutEnabled ?: nativePreviewLutEnabled) &&
                    !neutralVideoPreview &&
                    !truthfulColorDelivery,
                lutSummary = null
            ),
            neutralVideoPreview = neutralVideoPreview
        )
        val fallbackPreviewMonitoring = previewLookState.displayReferredMonitoring || (
            videoSurface && deliveryState.isCinemaDeliveryMode &&
                !truthfulColorDelivery && !neutralVideoPreview
            )
        val previewMonitoring = renderContract?.previewMonitoringEnabled ?: fallbackPreviewMonitoring
        val logCaptureActive = renderContract?.logCaptureActive ?: (
            previewLookState.logEnabled ||
                if (videoSurface) deliveryState.logVideoActive else fallbackLogEnabled
            )
        val rawPreviewMonitoringActive = renderContract?.let {
            it.rawVideoActive && it.previewMonitoringEnabled
        } ?: (previewLookState.rawEnabled || (
            videoSurface && deliveryState.rawVideoActive &&
                !truthfulColorDelivery && !neutralVideoPreview
            ))
        val rawVideoDelivery = renderContract?.rawVideoDelivery ?: (
            truthfulColorDelivery && videoSurface && deliveryState.rawVideoActive
            )
        val normalizedHdrMode = renderContract?.normalizedHdrMode ?: when {
            previewLookState.hdrEnabled && previewLookState.hdrMode != "OFF" -> previewLookState.hdrMode
            videoSurface -> deliveryState.effectiveHdrMode
            else -> "OFF"
        }
        val neoHdrColorCritical = normalizedHdrMode.equals("NDR10+", ignoreCase = true) ||
            normalizedHdrMode.equals("NIVUKXVISION", ignoreCase = true)
        val measuredCorrection = if (neutralVideoPreview) {
            NdrNivukxMeasuredCorrectionPolicy.resolve("OFF")
        } else {
            NdrNivukxMeasuredCorrectionPolicy.resolve(normalizedHdrMode)
        }
        val toneDeliveryMode = renderContract?.toneDeliveryMode ?: VulkanToneDeliveryMode.resolve(
            previewLutEnabled = nativePreviewLutEnabled && !truthfulColorDelivery,
            truthfulColorDelivery = truthfulColorDelivery,
            rawVideoDelivery = rawVideoDelivery,
            rawVideoActive = previewLookState.rawEnabled || deliveryState.rawVideoActive,
            logCaptureActive = logCaptureActive,
            normalizedHdrMode = normalizedHdrMode,
            logCurve = if (logCaptureActive) {
                NeoLogProfilePolicy.resolve(colorProfile, logEnabled = true).curve
            } else {
                NeoLogCurve.NONE
            },
            previewMonitoringEnabled = previewMonitoring
        )
        val deliveryVideoToneBias = deliveryParityBias && videoSurface &&
            (logCaptureActive || deliveryState.effectiveHdrMode != "OFF")
        val uniforms = VulkanGradeUniformPolicy.resolve(
            VulkanGradeUniformInput(
                neutralVideoPreview = neutralVideoPreview,
                deliveryVideoToneBias = deliveryVideoToneBias,
                videoSurface = videoSurface,
                deliveryHdrMode = deliveryState.effectiveHdrMode,
                logCaptureActive = logCaptureActive,
                sceneRenderProfile = sceneRenderProfile,
                hdrRenderActive = hdrRenderActive,
                rawPreviewMonitoringActive = rawPreviewMonitoringActive,
                neoHdrColorCritical = neoHdrColorCritical,
                adjustments = adjustments,
                previewMonitoringEnabled = previewMonitoring,
                rawVideoDelivery = rawVideoDelivery,
                normalizedHdrMode = normalizedHdrMode,
                truthfulColorDelivery = truthfulColorDelivery,
                measuredNdrNivukxCorrection = measuredCorrection,
                sdrQualityIntent = sdrQualityIntent,
                toneDeliveryMode = toneDeliveryMode,
                nativePreviewLutSize = nativePreviewLutSize
            )
        )
        val apexMode = when (toneDeliveryMode.shaderModeFlag) {
            in 49.5f..50.5f -> ApexMode.SHARP
            in 50.5f..51.5f -> ApexMode.ZERO
            in 51.5f..52.5f -> ApexMode.CINEMA
            else -> null
        }
        CameraVulkanGradeResult(uniforms = uniforms, apexMode = apexMode)
    }
}
