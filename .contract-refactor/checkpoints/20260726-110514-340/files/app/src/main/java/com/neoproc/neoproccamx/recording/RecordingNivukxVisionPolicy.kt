package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NivukxVisionConformance
import com.neoproc.neoproccamx.color.NivukxVisionLookEngine
import com.neoproc.neoproccamx.color.NivukxVisionMath
import com.neoproc.neoproccamx.color.NivukxVisionRenderPlan
import com.neoproc.neoproccamx.color.NivukxVisionRenderPolicy
import com.neoproc.neoproccamx.color.NivukxVisionRenderRequest
import java.util.Locale

internal data class RecordingNivukxVisionPlan(
    val active: Boolean,
    val productProfile: String,
    val renderPlan: NivukxVisionRenderPlan,
    val lookName: String,
    val sceneParameters: NivukxVisionMath.SceneParameters
) {
    fun asMetadata(): Map<String, String> {
        if (!active) {
            return mapOf(
                "nivukxVisionActive" to "false",
                "nivukxVisionProductProfile" to productProfile
            )
        }
        val houseLook = NivukxVisionLookEngine.houseLooks().first { it.name == lookName }
        return mapOf(
            "nivukxVisionActive" to "true",
            "nivukxVisionProductProfile" to productProfile,
            "nivukxVisionLayer" to "LOOK_LAYER_ABOVE_NIVUKX_DR",
            "nivukxVisionTransportCollision" to "false",
            "nivukxVisionConformance" to renderPlan.conformance.name,
            "nivukxVisionEliteHonored" to renderPlan.eliteHonored.toString(),
            "nivukxVisionRequestedBitDepth" to renderPlan.requestedBitDepth.toString(),
            "nivukxVisionHonoredBitDepth" to renderPlan.honoredBitDepth.toString(),
            "nivukxVisionFallbackReason" to renderPlan.fallbackReason,
            "nivukxVisionPrimaryDelivery" to renderPlan.primaryDelivery.name,
            "nivukxVisionWorkingGamut" to renderPlan.workingGamut,
            "nivukxVisionWorkingPixelPrecision" to renderPlan.workingPixelPrecision,
            "nivukxVisionSolvePrecision" to renderPlan.solvePrecision,
            "nivukxVisionAppearanceModel" to renderPlan.colorAppearanceModel,
            "nivukxVisionChromaCompression" to renderPlan.chromaCompression,
            "nivukxVisionLookName" to houseLook.name,
            "nivukxVisionLookLutSize" to houseLook.lutSize.toString(),
            "nivukxVisionLookLutSha256" to houseLook.sha256,
            "nivukxVisionOmegaScene" to sceneParameters.omega.format6(),
            "nivukxVisionGammaCompressScene" to sceneParameters.gammaCompress.format6(),
            "nivukxVisionMetadataTiers" to renderPlan.metadataTiers.joinToString(separator = ","),
            "nivukxVisionExternalProfileUsed" to "false"
        )
    }

    private fun Double.format6(): String =
        String.format(Locale.US, "%.6f", this)
}

internal object RecordingNivukxVisionPolicy {
    fun resolve(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String
    ): RecordingNivukxVisionPlan {
        val active = request.mode == EliteVideoPipelineMode.HDR &&
            normalizedHdrVersion.equals("NIVUKXVISION", ignoreCase = true)
        val renderPlan = NivukxVisionRenderPolicy.resolve(
            NivukxVisionRenderRequest(
                active = active,
                requestedBitDepth = 12,
                sourceBitDepth = request.sourceBitDepth,
                storageBitDepth = request.storageBitDepth,
                codec = request.codec.name,
                classProtectionAvailable = active && request.storageBitDepth >= 12,
                metadataSigningAvailable = active,
                nlogs12Requested = false
            )
        )
        return RecordingNivukxVisionPlan(
            active = active,
            productProfile = NivukxVisionRenderPolicy.PRODUCT_PROFILE,
            renderPlan = renderPlan,
            lookName = when (renderPlan.conformance) {
                NivukxVisionConformance.ELITE -> "NVX-Cinema-Neutral"
                NivukxVisionConformance.PRO -> "NVX-Filmic-Warm"
                NivukxVisionConformance.LITE -> "NVX-Cinema-Neutral"
            },
            sceneParameters = NivukxVisionMath.resolveSceneParameters(
                p50 = 120.0,
                p99 = if (request.storageBitDepth >= 12) 4_000.0 else 1_000.0,
                lPeak = 10_000.0
            )
        )
    }
}
