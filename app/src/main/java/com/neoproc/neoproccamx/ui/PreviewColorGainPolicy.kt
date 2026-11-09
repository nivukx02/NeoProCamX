package com.neoproc.neoproccamx.ui

import android.os.Build
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.color.NormalSdrColorCalibrationPolicy
import com.neoproc.neoproccamx.rendering.CameraImportColorCompensationPolicy
import java.util.Locale
import kotlin.math.pow

/** Resolves one immutable preview colour-gain candidate without mutating CameraViewModel state. */
internal object PreviewColorGainPolicy {
    fun resolve(
        renderState: VulkanRenderRequestSnapshot,
        deliveryState: VideoFormatDeliveryState,
        safeVideoHdrPreviewActive: Boolean,
        deviceModel: String = Build.MODEL
    ): PreviewColorGainCandidate {
        val videoSurface = renderState.videoSurface
        val profile = renderState.sceneRenderProfile
        val sceneType = renderState.sceneType
        val baseImportCompensation = CameraImportColorCompensationPolicy.resolve(deviceModel)
        val neutralVideoPreview = videoSurface && deliveryState.neutralPreview
        val videoHdrPreviewActive = if (neutralVideoPreview) {
            false
        } else {
            videoSurface &&
                (safeVideoHdrPreviewActive || deliveryState.effectiveHdrMode != "OFF")
        }
        val truthfulVideoDelivery = videoSurface && deliveryState.isCinemaDeliveryMode
        val originalColorCriticalPath = truthfulVideoDelivery
        val importCompensation = when {
            // HDR/LOG/RAW delivery stays original; device compensation would visibly tint it.
            originalColorCriticalPath -> baseImportCompensation.attenuate(0f)
            neutralVideoPreview -> baseImportCompensation.attenuate(0.18f)
            else -> baseImportCompensation
        }
        if (originalColorCriticalPath) {
            val identity = floatArrayOf(1f, 1f, 1f, 0f)
            return PreviewColorGainCandidate(identity, identity.copyOf())
        }
        val sceneWarmBias = when (sceneType) {
            SceneType.NIGHT, SceneType.INDOOR -> 0.005f
            SceneType.PORTRAIT -> 0.003f
            SceneType.OUTDOOR, SceneType.LANDSCAPE -> 0.0005f
            SceneType.BACKLIGHT -> 0.002f
            else -> 0.0015f
        }
        val warmth = (profile?.warmth ?: 0f) * 0.06f + sceneWarmBias
        val contrast = profile?.contrast ?: 1f
        val saturation = profile?.saturation ?: 1f
        val highlightCompression = profile?.highlightCompression ?: 0f
        val logStrength = profile?.logStrength ?: 0f
        val videoLogPreviewActive =
            videoSurface && !neutralVideoPreview && deliveryState.logVideoActive
        val lutPreviewActive = renderState.lutEnabled &&
            !neutralVideoPreview && !originalColorCriticalPath
        val hdrBoost = if (videoHdrPreviewActive) 0.010f else 0f
        val normalSdrVideoDelivery =
            videoSurface &&
                !truthfulVideoDelivery &&
                !videoLogPreviewActive &&
                !videoHdrPreviewActive &&
                !lutPreviewActive
        val evPreviewGain =
            2.0.pow((renderState.exposureCompensation.toFloat() * 0.24f).toDouble())
                .toFloat()
                .coerceIn(0.60f, 1.80f)
        val standardNaturalSdr =
            neutralVideoPreview ||
                (
                    !videoLogPreviewActive &&
                        !videoHdrPreviewActive &&
                        (!deliveryState.rawVideoActive || neutralVideoPreview) &&
                        renderState.colorProfile.equals("Natural", ignoreCase = true)
                    )
        val stableNormalOrProSdrColor = normalSdrVideoDelivery
        val normalSdrCalibration = NormalSdrColorCalibrationPolicy.ACTIVE_CORRECTION
        val neutralGain = (contrast * 0.10f + saturation * 0.08f + 0.82f)
            .coerceIn(0.99f, 1.01f)
        var red = neutralGain * (1f + warmth * 0.05f + hdrBoost - highlightCompression * 0.003f)
        var green = neutralGain
        var blue = neutralGain * (1f - warmth * 0.04f + hdrBoost * 0.01f)

        if (stableNormalOrProSdrColor) {
            red = normalSdrCalibration.redScale
            green = normalSdrCalibration.greenScale
            blue = normalSdrCalibration.blueScale
        } else if (standardNaturalSdr) {
            val preservedWarmth = when (sceneType) {
                SceneType.NIGHT, SceneType.INDOOR -> 0.004f
                SceneType.PORTRAIT -> 0.002f
                else -> 0.001f
            }
            red = 1f + preservedWarmth
            green = 1f
            blue = 1f - preservedWarmth * 0.20f
        }

        if (!stableNormalOrProSdrColor) {
            red *= evPreviewGain
            green *= evPreviewGain
            blue *= evPreviewGain

            red *= importCompensation.redScale
            green *= importCompensation.greenScale
            blue *= importCompensation.blueScale
        }

        if (videoLogPreviewActive) {
            val logPreviewGain = (1f + logStrength * 0.012f).coerceIn(1.0f, 1.02f)
            red *= logPreviewGain
            green *= logPreviewGain
            blue *= logPreviewGain
        }

        if (lutPreviewActive) {
            when (renderState.lutProfile.trim().lowercase(Locale.US)) {
                "neutral film" -> {
                    red *= 1.001f
                    green *= 1.001f
                    blue *= 1.001f
                }
                "warm cinema" -> {
                    red *= 1.006f
                    green *= 1.0f
                    blue *= 0.994f
                }
                "teal & orange" -> {
                    red *= 0.996f
                    green *= 1.002f
                    blue *= 1.006f
                }
            }
        }

        val targetStrength = when {
            originalColorCriticalPath -> 0f
            stableNormalOrProSdrColor -> 0f
            standardNaturalSdr -> 0f
            lutPreviewActive -> 0.04f
            videoLogPreviewActive -> 0.03f
            videoHdrPreviewActive -> 0.04f
            else -> 0.03f
        }
        val targetBlendStrength = when {
            originalColorCriticalPath -> 0f
            stableNormalOrProSdrColor -> 0f
            else -> maxOf(targetStrength, importCompensation.blendStrength).coerceIn(0f, 1f)
        }
        val target = floatArrayOf(
            red.coerceIn(0.60f, 1.80f),
            green.coerceIn(0.60f, 1.80f),
            blue.coerceIn(0.60f, 1.80f),
            targetBlendStrength
        )
        if (stableNormalOrProSdrColor) {
            return PreviewColorGainCandidate(target, target.copyOf())
        }
        val smoothing = when {
            standardNaturalSdr && renderState.recordingActive -> 0.12f
            standardNaturalSdr -> 0.18f
            renderState.recordingActive -> 0.16f
            videoSurface -> 0.20f
            else -> 0.24f
        }
        val nextState = renderState.previousColorGain.copyOf()
        for (index in target.indices) {
            nextState[index] += (target[index] - nextState[index]) * smoothing
        }
        return PreviewColorGainCandidate(nextState.copyOf(), nextState)
    }
}
