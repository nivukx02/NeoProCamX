package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import java.util.Locale
import kotlin.math.max

data class VideoDetailSharpeningMetrics(
    val edgeConfidence: Float,
    val haloRisk: Float,
    val moireAliasRisk: Float,
    val textureConfidence: Float,
    val noisyShadowSharpeningSuppression: Float
) {
    fun clamped(): VideoDetailSharpeningMetrics =
        VideoDetailSharpeningMetrics(
            edgeConfidence = edgeConfidence.coerceIn(0f, 1f),
            haloRisk = haloRisk.coerceIn(0f, 1f),
            moireAliasRisk = moireAliasRisk.coerceIn(0f, 1f),
            textureConfidence = textureConfidence.coerceIn(0f, 1f),
            noisyShadowSharpeningSuppression = noisyShadowSharpeningSuppression.coerceIn(0f, 1f)
        )

    fun toMetadata(prefix: String = "detail"): Map<String, String> {
        val metrics = clamped()
        return linkedMapOf(
            metricKey(prefix, "edgeConfidence") to format(metrics.edgeConfidence),
            metricKey(prefix, "haloRisk") to format(metrics.haloRisk),
            metricKey(prefix, "moireAliasRisk") to format(metrics.moireAliasRisk),
            metricKey(prefix, "textureConfidence") to format(metrics.textureConfidence),
            metricKey(prefix, "noisyShadowSharpeningSuppression") to format(metrics.noisyShadowSharpeningSuppression)
        )
    }

    companion object {
        val EMPTY = VideoDetailSharpeningMetrics(
            edgeConfidence = 0f,
            haloRisk = 0f,
            moireAliasRisk = 0f,
            textureConfidence = 0f,
            noisyShadowSharpeningSuppression = 0f
        )
    }
}

data class VideoDetailSharpeningPlan(
    val edgeAwareOnly: Boolean,
    val sharpeningAfterDenoise: Boolean,
    val globalSharpeningAllowed: Boolean,
    val pixelDuplicationAllowed: Boolean,
    val edgeConfidence: Float,
    val sharpeningStrength: Float,
    val microContrast: Float,
    val detailReconstruction: Float,
    val haloSuppression: Float,
    val moireAliasGuard: Float,
    val skinTextureGuard: Float,
    val noisyShadowSharpeningSuppression: Float,
    val trueResolutionClaim: String,
    val metrics: VideoDetailSharpeningMetrics,
    val selectedDetailProfile: VideoDetailProfile? = null,
    val subjectDetailPlan: SubjectDetailEnhancementPlan? = null,
    val signature: String
) {
    fun toMetadata(prefix: String = "detail"): Map<String, String> =
        linkedMapOf(
            metricKey(prefix, "edgeAwareOnly") to edgeAwareOnly.toString(),
            metricKey(prefix, "sharpeningAfterDenoise") to sharpeningAfterDenoise.toString(),
            metricKey(prefix, "globalSharpeningAllowed") to globalSharpeningAllowed.toString(),
            metricKey(prefix, "pixelDuplicationAllowed") to pixelDuplicationAllowed.toString(),
            metricKey(prefix, "sharpeningStrength") to format(sharpeningStrength),
            metricKey(prefix, "microContrast") to format(microContrast),
            metricKey(prefix, "reconstruction") to format(detailReconstruction),
            metricKey(prefix, "haloSuppression") to format(haloSuppression),
            metricKey(prefix, "moireAliasGuard") to format(moireAliasGuard),
            metricKey(prefix, "skinTextureGuard") to format(skinTextureGuard),
            metricKey(prefix, "trueResolutionClaim") to trueResolutionClaim,
            metricKey(prefix, "signature") to signature
        ).apply {
            selectedDetailProfile?.let { put("selectedDetailProfile", it.name) }
            putAll(metrics.toMetadata(prefix))
            subjectDetailPlan?.let { putAll(it.toMetadata()) }
        }
}

internal data class VideoDetailSharpeningRequest(
    val signalFamily: VideoImageSignalFamily,
    val recording: Boolean,
    val lumaLevel: Float,
    val edgeDensity: Float,
    val perceptualSharpness: Float,
    val skinToneConfidence: Float,
    val textureConfidence: Float,
    val selectedWidth: Int,
    val selectedHeight: Int,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val noiseMetrics: VideoNoiseMetrics,
    val strictHighEnd: Boolean,
    val videoDetailProfile: VideoDetailProfile? = null,
    val subjectDetailPlan: SubjectDetailEnhancementPlan? = null
)

internal object VideoDetailSharpeningPolicy {
    fun resolve(request: VideoDetailSharpeningRequest): VideoDetailSharpeningPlan {
        val luma = request.lumaLevel.coerceIn(0f, 1f)
        val edgeDensity = request.edgeDensity.coerceIn(0f, 1f)
        val perceptualSharpness = request.perceptualSharpness.coerceIn(0f, 1f)
        val skin = request.skinToneConfidence.coerceIn(0f, 1f)
        val texture = max(request.textureConfidence.coerceIn(0f, 1f), edgeDensity)
        val noise = request.noiseMetrics.clamped()
        val noisePressure = (
            noise.lumaNoise * 0.34f +
                noise.chromaNoise * 0.24f +
                noise.frameToFrameRgbVariance * 0.28f +
                noise.temporalInstability * 0.14f
            ).coerceIn(0f, 1f)
        val shadow = (1f - ((luma - 0.05f) / 0.42f)).coerceIn(0f, 1f)
        val noisyShadow = (
            shadow * (noisePressure * 0.74f + noise.frameToFrameRgbVariance * 0.26f) +
                shadow * noise.chromaNoise * 0.10f
            )
            .coerceIn(0f, 1f)
        val extremeDetailProfile = request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL
        val flatSkin = (skin * (1f - edgeDensity).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val edgeConfidence = (
            edgeDensity * 0.54f +
                perceptualSharpness * 0.40f -
                noisePressure * 0.08f -
                flatSkin * 0.04f
            ).coerceIn(0f, 1f)
        val highConfidenceGate = ((edgeConfidence - 0.56f) / 0.30f).coerceIn(0f, 1f) *
            (1f - noisyShadow * 0.80f).coerceIn(0f, 1f)
        val modeLift = when (request.signalFamily) {
            VideoImageSignalFamily.RAW -> 0.02f
            VideoImageSignalFamily.LOG -> 0.03f
            VideoImageSignalFamily.HDR -> 0.04f
            VideoImageSignalFamily.SDR -> 0.05f
        }
        val recordingLift = if (request.recording) 0.08f else 0.04f
        val strictDetailLift = if (request.strictHighEnd) 0.30f else 0f
        
        // Select strict sharpening ceiling dynamically: EXTREME_DETAIL uses BeyondImaxEliteProfile, other strict modes use ImaxStrictProfile
        val ceiling = if (request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL) {
            com.neoproc.neoproccamx.core.BeyondImaxEliteProfile.SHARPENING_STRICT_CEILING
        } else {
            ImaxStrictProfile.SHARPENING_STRICT_CEILING
        }
        
        val calculatedSharpeningStrength = (
            0.22f +
                edgeConfidence * 0.56f +
                highConfidenceGate * 0.26f +
                modeLift +
                recordingLift +
                strictDetailLift -
                noisyShadow * 0.30f -
                flatSkin * 0.20f -
                noisePressure * 0.06f
            ).coerceIn(0.05f, ceiling)
            
        // Scale microcontrast and detail reconstruction when high-end or extreme detail is active to boost texture pop
        val microContrastScale = if (request.strictHighEnd || extremeDetailProfile) 1.25f else 1.0f
        
        val calculatedMicroContrast = (
            (0.20f +
                texture * 0.22f +
                edgeConfidence * 0.38f -
                noisyShadow * 0.14f -
                flatSkin * 0.10f) * microContrastScale
            ).coerceIn(0f, 0.74f)
        val calculatedDetailReconstruction = (
            (highConfidenceGate * (0.28f + edgeConfidence * 0.48f) +
                texture * 0.18f -
                flatSkin * 0.08f) * microContrastScale
            ).coerceIn(0f, 0.95f)
        val extremeCleanGate = if (extremeDetailProfile) {
            val edgeDrivenGate = (
                ((edgeConfidence - 0.44f) / 0.36f).coerceIn(0f, 1f) *
                    (1f - noisyShadow * 0.84f).coerceIn(0f, 1f) *
                    (1f - noisePressure * 0.46f).coerceIn(0f, 1f)
                ).coerceIn(0f, 1f)
            val cleanTextureGate = (
                ((texture - 0.32f) / 0.28f).coerceIn(0f, 1f) *
                    (1f - noisyShadow * 0.84f).coerceIn(0f, 1f) *
                    (1f - noisePressure * 0.46f).coerceIn(0f, 1f) *
                    0.45f
                ).coerceIn(0f, 1f)
            max(edgeDrivenGate, cleanTextureGate)
        } else {
            0f
        }
        val extremeSharpening = (
            calculatedSharpeningStrength +
                extremeCleanGate * 0.20f -
                noisyShadow * 0.08f -
                noisePressure * 0.04f
            ).coerceIn(0.26f, ceiling)
        val extremeMicroContrast = (
            (calculatedMicroContrast +
                extremeCleanGate * 0.20f -
                noisyShadow * 0.08f) * microContrastScale
            ).coerceIn(0f, 0.82f)
        val extremeDetailReconstruction = (
            (calculatedDetailReconstruction +
                extremeCleanGate * 0.18f -
                noisyShadow * 0.10f) * microContrastScale
            ).coerceIn(0f, 0.98f)
        val controlledSubjectPlan = request.subjectDetailPlan?.takeIf {
            request.videoDetailProfile != null && it.profile == request.videoDetailProfile
        }
        val cleanNaturalBaseline = (
            calculatedSharpeningStrength *
                1.0f
            ).coerceIn(minOf(0.18f, calculatedSharpeningStrength), calculatedSharpeningStrength)
        val focusedProfileLift = (
            (1f - noisePressure) *
                (1f - noisyShadow) *
                if (request.recording) 0.05f else 0.035f
            ).coerceIn(0f, 0.05f)
        val focusedBaseline = (calculatedSharpeningStrength + focusedProfileLift)
            .coerceIn(calculatedSharpeningStrength, ceiling)
        val focusedSubjectLift = controlledSubjectPlan
            ?.takeIf { it.subjectDetailApplied }
            ?.let { plan ->
                val normalizedGain = if (SubjectDetailEnhancementPolicy.MAX_ADDITIVE_DETAIL_GAIN > 0f) {
                    plan.additiveDetailGain / SubjectDetailEnhancementPolicy.MAX_ADDITIVE_DETAIL_GAIN
                } else {
                    0f
                }
                normalizedGain.coerceIn(0f, 1f) * 0.12f
            } ?: 0f
        val extremeProfileFloor = (
            calculatedSharpeningStrength +
                (1f - noisePressure) * (1f - noisyShadow) * 0.10f
            ).coerceIn(calculatedSharpeningStrength, ceiling)
        // Extreme noisy backoff removes only the profile lift; the normal edge-aware plan remains the floor.
        val sharpeningStrength = when (request.videoDetailProfile) {
            VideoDetailProfile.NATURAL_DETAIL -> cleanNaturalBaseline
            VideoDetailProfile.FOCUSED_DETAIL ->
                (focusedBaseline + focusedSubjectLift).coerceIn(focusedBaseline, ceiling)
            VideoDetailProfile.EXTREME_DETAIL -> max(extremeProfileFloor, extremeSharpening)
            null -> calculatedSharpeningStrength
        }
        val microContrast = when (request.videoDetailProfile) {
            VideoDetailProfile.NATURAL_DETAIL ->
                minOf(calculatedMicroContrast * 0.88f, sharpeningStrength * 0.90f)
            VideoDetailProfile.FOCUSED_DETAIL ->
                max(
                    calculatedMicroContrast * 0.86f,
                    minOf(calculatedMicroContrast, sharpeningStrength * 0.82f)
                )
            VideoDetailProfile.EXTREME_DETAIL -> max(calculatedMicroContrast, extremeMicroContrast)
            null -> calculatedMicroContrast
        }
        val detailReconstruction = when (request.videoDetailProfile) {
            VideoDetailProfile.NATURAL_DETAIL ->
                minOf(calculatedDetailReconstruction * 0.86f, sharpeningStrength * 0.94f)
            VideoDetailProfile.FOCUSED_DETAIL ->
                max(
                    calculatedDetailReconstruction * 0.88f,
                    minOf(calculatedDetailReconstruction, sharpeningStrength * 0.88f)
                )
            VideoDetailProfile.EXTREME_DETAIL -> max(calculatedDetailReconstruction, extremeDetailReconstruction)
            null -> calculatedDetailReconstruction
        }
        val extremeArtifactGuard = if (extremeDetailProfile) {
            (
                noisePressure * 0.10f +
                    noisyShadow * 0.16f +
                    (1f - edgeConfidence).coerceIn(0f, 1f) * 0.08f
                ).coerceIn(0f, 0.22f)
        } else {
            0f
        }
        val haloRisk = (
            edgeConfidence * 0.36f +
                sharpeningStrength * 0.34f +
                noisePressure * 0.16f +
                noisyShadow * 0.14f
            ).coerceIn(0f, 1f)
            
        // Scale halo floor and cap to prevent ringing artifact quality gate failures
        val haloFloor = if (request.strictHighEnd || extremeDetailProfile) 0.80f else 0.70f
        val haloCap = if (request.strictHighEnd || extremeDetailProfile) 0.999f else 0.998f
        val haloSuppression = (
            0.62f +
                haloRisk * 0.05f +
                noisyShadow * 0.18f +
                noisePressure * 0.04f +
                (if (request.strictHighEnd) 0.04f else 0f) +
                extremeArtifactGuard
            ).coerceIn(haloFloor, haloCap)
            
        val moireAliasRisk = (
            texture * 0.28f +
                edgeDensity * 0.20f +
                highConfidenceGate * 0.16f -
                noisePressure * 0.08f
            ).coerceIn(0f, 1f)
            
        // Scale guard caps to prevent moire or skin artifact failures
        val guardCap = if (request.strictHighEnd || extremeDetailProfile) 0.999f else 0.995f
        val moireAliasGuard = (
            0.68f +
                moireAliasRisk * 0.18f +
                haloSuppression * 0.12f +
                extremeArtifactGuard
            ).coerceIn(0.72f, guardCap)
        val skinTextureGuard = (
            0.54f +
                skin * 0.16f +
                flatSkin * 0.16f +
                texture * 0.08f +
                extremeArtifactGuard
            ).coerceIn(0.60f, guardCap)
        val metrics = VideoDetailSharpeningMetrics(
            edgeConfidence = edgeConfidence,
            haloRisk = haloRisk,
            moireAliasRisk = moireAliasRisk,
            textureConfidence = texture,
            noisyShadowSharpeningSuppression = noisyShadow
        ).clamped()
        val trueResolutionClaim = trueResolutionClaim(
            selectedWidth = request.selectedWidth,
            selectedHeight = request.selectedHeight,
            nativeWidth = request.nativeWidth,
            nativeHeight = request.nativeHeight
        )
        return VideoDetailSharpeningPlan(
            edgeAwareOnly = true,
            sharpeningAfterDenoise = true,
            globalSharpeningAllowed = false,
            pixelDuplicationAllowed = false,
            edgeConfidence = edgeConfidence,
            sharpeningStrength = sharpeningStrength,
            microContrast = microContrast,
            detailReconstruction = detailReconstruction,
            haloSuppression = haloSuppression,
            moireAliasGuard = moireAliasGuard,
            skinTextureGuard = skinTextureGuard,
            noisyShadowSharpeningSuppression = noisyShadow,
            trueResolutionClaim = trueResolutionClaim,
            metrics = metrics,
            selectedDetailProfile = request.videoDetailProfile,
            subjectDetailPlan = controlledSubjectPlan,
            signature = listOf(
                request.signalFamily.name,
                "profile=${request.videoDetailProfile?.name ?: "LEGACY"}",
                "edge=${format(edgeConfidence)}",
                "sharp=${format(sharpeningStrength)}",
                "recon=${format(detailReconstruction)}",
                "halo=${format(haloSuppression)}",
                "truth=$trueResolutionClaim"
            ).joinToString(separator = "|")
        )
    }

    fun metadataFrom(valueForKey: (String) -> String?): Map<String, String> =
        knownMetadataKeys
            .mapNotNull { key -> valueForKey(key)?.let { value -> key to value } }
            .toMap()

    fun recordingEvidenceMetadata(
        previewMetadata: Map<String, String>,
        recordingGpuFrameImportValidated: Boolean
    ): Map<String, String> {
        val previewApplied =
            (previewMetadata["previewSubjectDetailApplied"] ?: previewMetadata["subjectDetailApplied"])
                .equals("true", ignoreCase = true)
        val previewGain =
            previewMetadata["previewSubjectDetailGain"] ?: previewMetadata["subjectDetailGain"] ?: "0.000000"
        val recordingApplied = recordingGpuFrameImportValidated && previewApplied
        return linkedMapOf<String, String>().apply {
            putAll(previewMetadata)
            put("previewSubjectDetailApplied", previewApplied.toString())
            put("previewSubjectDetailGain", previewGain)
            put("recordingSubjectDetailApplied", recordingApplied.toString())
            put("recordingGpuFrameImportValidated", recordingGpuFrameImportValidated.toString())
            put(
                "detailProcessingPath",
                when {
                    !recordingGpuFrameImportValidated -> "CAMERA2_FALLBACK"
                    recordingApplied -> "VULKAN_MASKED_SUBJECT_DETAIL"
                    else -> "VULKAN_NO_ADDITIVE_DETAIL"
                }
            )
            put("subjectDetailApplied", recordingApplied.toString())
            put("subjectDetailGain", if (recordingApplied) previewGain else "0.000000")
        }
    }

    fun npv10MasterEvidenceMetadata(previewMetadata: Map<String, String>): Map<String, String> {
        val previewApplied =
            (previewMetadata["previewSubjectDetailApplied"] ?: previewMetadata["subjectDetailApplied"])
                .equals("true", ignoreCase = true)
        val previewGain =
            previewMetadata["previewSubjectDetailGain"] ?: previewMetadata["subjectDetailGain"] ?: "0.000000"
        return linkedMapOf<String, String>().apply {
            putAll(previewMetadata)
            put("previewSubjectDetailApplied", previewApplied.toString())
            put("previewSubjectDetailGain", previewGain)
            put("recordingSubjectDetailApplied", "false")
            put("recordingGpuFrameImportValidated", "false")
            put("subjectDetailApplied", "false")
            put("subjectDetailGain", "0.000000")
            put("detailProcessingPath", "NPV10_PRIVATE_MASTER_UNMODIFIED")
            put("npv10ExportSubjectMaskStored", "false")
        }
    }

    private val knownMetadataKeys = listOf(
        "detailEdgeAwareOnly",
        "detailSharpeningAfterDenoise",
        "detailGlobalSharpeningAllowed",
        "detailPixelDuplicationAllowed",
        "detailSharpeningStrength",
        "detailMicroContrast",
        "detailReconstruction",
        "detailHaloSuppression",
        "detailMoireAliasGuard",
        "detailSkinTextureGuard",
        "detailTrueResolutionClaim",
        "detailSignature",
        "detailEdgeConfidence",
        "detailHaloRisk",
        "detailMoireAliasRisk",
        "detailTextureConfidence",
        "detailNoisyShadowSharpeningSuppression",
        "selectedDetailProfile",
        "subjectDetailRequested",
        "subjectDetailApplied",
        "subjectMaskPresent",
        "subjectDetailGain",
        "naturalZeroSharpening",
        "subjectDetailSignature",
        "previewSubjectDetailApplied",
        "previewSubjectDetailGain",
        "recordingSubjectDetailApplied",
        "recordingGpuFrameImportValidated",
        "detailProcessingPath"
    )

    private fun trueResolutionClaim(
        selectedWidth: Int,
        selectedHeight: Int,
        nativeWidth: Int,
        nativeHeight: Int
    ): String {
        val safeSelectedWidth = selectedWidth.coerceAtLeast(1)
        val safeSelectedHeight = selectedHeight.coerceAtLeast(1)
        val safeNativeWidth = nativeWidth.coerceAtLeast(1)
        val safeNativeHeight = nativeHeight.coerceAtLeast(1)
        val selectedFitsNative = safeSelectedWidth <= safeNativeWidth && safeSelectedHeight <= safeNativeHeight
        return if (selectedFitsNative) {
            "TRUE_NATIVE_${safeSelectedWidth}x${safeSelectedHeight}_NO_FAKE_SUPER_RESOLUTION"
        } else {
            "PERCEPTUAL_DETAIL_ONLY_NATIVE_${safeNativeWidth}x${safeNativeHeight}_OUTPUT_${safeSelectedWidth}x${safeSelectedHeight}_NOT_TRUE_NATIVE_NO_PIXEL_DUPLICATION"
        }
    }
}

private fun metricKey(prefix: String, key: String): String =
    if (prefix.isBlank()) {
        key
    } else {
        prefix + key.replaceFirstChar { char -> char.uppercase(Locale.US) }
    }

private fun format(value: Float): String =
    String.format(Locale.US, "%.6f", value.coerceIn(0f, 2f))
