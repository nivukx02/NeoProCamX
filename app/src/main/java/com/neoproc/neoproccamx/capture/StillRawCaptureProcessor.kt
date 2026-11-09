package com.neoproc.neoproccamx.capture

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.utils.OrientationManager
import java.util.Locale

internal class StillRawCaptureProcessor(
    private val cameraController: CameraController,
    private val outputStore: StillCaptureOutputStore,
    private val awaitCaptureResult: suspend (Long) -> TotalCaptureResult?,
    private val aiEnhancementEnabledProvider: () -> Boolean,
    private val aiSceneTypeProvider: () -> SceneType,
    private val aiHdrSuggestedProvider: () -> Boolean,
    private val colorProfileProvider: () -> String,
    private val logEnabledProvider: () -> Boolean,
    private val lutEnabledProvider: () -> Boolean,
    private val lutLabelProvider: () -> String?
) {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    suspend fun saveNativeDng(
        image: Image,
        tag: String,
        debugLabel: String? = null
    ): String? {
        val chars = cameraController.getCameraCharacteristics() ?: return null
        val result = awaitCaptureResult(image.timestamp) ?: return null
        // Telemeter DNG colour-tag completeness: DngCreator emits Black/White level, the
        // Forward/Color matrices, NoiseProfile and illuminants automatically, but only from
        // what the characteristics/result actually carry. A missing tag means a third party
        // will not render our neutral — surface it instead of shipping it silently.
        val completeness = RawDngMetadataContract.fromCameraMetadata(chars, result)
        check(completeness.isComplete) {
            "Native DNG publication rejected: ${completeness.summary}"
        }
        return outputStore.saveDng(tag, debugLabel = debugLabel) { out ->
            DngCreator(chars, result).use { dng ->
                // ═══════════════════════════════════════════════════════════════════
                // HIGH-END DNG QUALITY UPGRADE
                // ═══════════════════════════════════════════════════════════════════
                
                // Set maximum quality with no lossy compression
                dng.setOrientation(
                    OrientationManager.exifOrientationForRotation(
                        cameraController.outputJpegOrientation()
                    )
                )
                
                // Set description with cinema-oriented metadata
                val metadata = buildCinemaGradeDngDescription()
                dng.setDescription(metadata)
                
                // GPS location if available
                val location = cameraController.lastKnownLocation
                if (location != null) {
                    dng.setLocation(location)
                }
                
                // Write high-quality DNG with metadata
                dng.writeImage(out, image)
            }
        }
    }
    
    private fun buildCinemaGradeDngDescription(): String = buildString {
        append("NeoProCamX Cinema RAW")
        append(" | Profile: ").append(colorProfileProvider())
        if (logEnabledProvider()) {
            append(" | LOG Encoding")
        }
        if (lutEnabledProvider()) {
            append(" | LUT: ").append(lutLabelProvider() ?: "Custom")
        }
        if (aiEnhancementEnabledProvider()) {
            append(" | AI Enhanced")
        }
        if (aiHdrSuggestedProvider()) {
            append(" | HDR Recommended")
        }
        if (strictHighEndEnabled()) {
            append(" | Strict High-End")
        }
        // CP17: record the distortion-correction geometry so a colourist/review knows whether this
        // DNG was captured with sensor-native lens geometry or with correction applied.
        append(" | Geometry: ").append(cameraController.ispBypassDistortionGeometryLabel())
        append(" | Scene: ").append(aiSceneTypeProvider().name)
        val iso = cameraController.captureResult?.iso
        val expNs = cameraController.captureResult?.exposureNs
        if (iso != null && iso > 0) {
            append(" | ISO ").append(iso)
        }
        if (expNs != null && expNs > 0) {
            val expMs = expNs / 1_000_000.0
            append(" | ").append(String.format(Locale.US, "%.2fms", expMs))
        }
    }

    fun saveProcessedSourceFallbackPackage(
        tag: String,
        sourceJpegBytes: ByteArray,
        renderedReference: Bitmap?,
        debugLabel: String? = null
    ): String? {
        val profileName = colorProfileProvider()
        val logEnabled = logEnabledProvider()
        val lutEnabled = lutEnabledProvider()
        val hdrSuggested = aiHdrSuggestedProvider()
        val colorSpace = resolveColorSpaceLabel(profileName)
        val transferFunction = resolveTransferFunctionLabel(profileName, logEnabled)
        val dynamicRange = resolveDynamicRangeLabel(profileName, logEnabled, hdrSuggested)
        val processingPipeline = when {
            strictHighEndEnabled() && aiEnhancementEnabledProvider() && hdrSuggested ->
                "STRICT_HIGH_END_AI_HDR_PROCESSED_SOURCE"
            strictHighEndEnabled() && aiEnhancementEnabledProvider() ->
                "STRICT_HIGH_END_AI_ENHANCED_PROCESSED_SOURCE"
            strictHighEndEnabled() && hdrSuggested ->
                "STRICT_HIGH_END_HDR_AWARE_PROCESSED_SOURCE"
            strictHighEndEnabled() ->
                "STRICT_HIGH_END_PROCESSED_SOURCE_FALLBACK"
            aiEnhancementEnabledProvider() && hdrSuggested -> "AI_HDR_PROCESSED_SOURCE"
            aiEnhancementEnabledProvider() -> "AI_ENHANCED_PROCESSED_SOURCE"
            hdrSuggested -> "HDR_AWARE_PROCESSED_SOURCE"
            else -> "PROCESSED_SOURCE_FALLBACK"
        }
        val metadata = RawOutputPackaging.buildMetadata(
            RawPackageMetadata(
                sourceType = "processed_jpeg_source",
                workflow = "PROCESSED_SOURCE_FALLBACK",
                captureTag = tag,
                nativeRawSupported = cameraController.supportsRaw,
                nativeDngEmbedded = false,
                includesSourceJpeg = true,
                includesReferenceJpeg = renderedReference != null,
                lensFacing = lensFacingLabel(cameraController.facing),
                sensorOrientation = cameraController.sensorOrientation(),
                jpegOrientation = cameraController.outputJpegOrientation(),
                outputAspect = cameraController.outputAspectRatio ?: cameraController.sensorBaseAspectRatio(),
                sceneMetadata = buildSceneMetadata(
                    sceneType = aiSceneTypeProvider(),
                    enhancementEnabled = aiEnhancementEnabledProvider(),
                    hdrSuggested = hdrSuggested
                ),
                colorProfile = profileName,
                logEnabled = logEnabled,
                lutEnabled = lutEnabled,
                lutLabel = lutLabelProvider()?.takeIf { it.isNotBlank() } ?: "OFF",
                iso = cameraController.captureResult?.iso ?: 0,
                exposureNs = cameraController.captureResult?.exposureNs ?: 0L,
                focusDistance = cameraController.captureResult?.focusDist ?: 0f,
                timestampMs = System.currentTimeMillis(),
                bitDepth = 8,
                compressionMode = "ZIP_JPEG_ASSETS",
                compressionRatio = 1.0f,
                colorSpace = colorSpace,
                transferFunction = transferFunction,
                dynamicRange = dynamicRange,
                processingPipeline = processingPipeline,
                distortionPolicy = cameraController.ispBypassDistortionGeometryLabel(),
                ispBypassVerified = cameraController.ispBypassVerdictLabel()
            )
        )
        return outputStore.saveRawPackage(tag, debugLabel = debugLabel) { out ->
            RawOutputPackaging.writePackage(
                output = out,
                metadata = metadata,
                sourceJpegBytes = sourceJpegBytes,
                renderedReference = renderedReference
            )
        }
    }

    companion object {
        internal fun lensFacingLabel(lensFacing: Int): String =
            when (lensFacing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                else -> "BACK"
            }

        internal fun buildSceneMetadata(
            sceneType: SceneType,
            enhancementEnabled: Boolean,
            hdrSuggested: Boolean
        ): String = buildString {
            append("CamX mode=PROCESSED_SOURCE_PACKAGE")
            append(" scene=").append(sceneType.name)
            append(" aiEnhance=").append(enhancementEnabled)
            append(" hdrSuggested=").append(hdrSuggested)
        }

        internal fun resolveColorSpaceLabel(profileName: String): String {
            val key = profileName.lowercase()
            return when {
                // UPGRADED: cinema-oriented color spaces with ACES primaries preference
                key.contains("aces") -> "ACES AP0"  // Academy Color Encoding System
                key.contains("acescg") -> "ACES AP1 (ACEScg)"
                key.contains("2020") || key.contains("2100") || key.contains("hdr") -> "Rec.2020 / ITU-R BT.2020"
                key.contains("neo") || key.contains("wide") -> "Neo Wide Gamut"
                key.contains("dci") || key.contains("p3") -> "DCI-P3 / Display P3"
                key.contains("adobe") -> "Adobe RGB (1998)"
                key.contains("prophoto") -> "ProPhoto RGB"
                else -> "Rec.709 / sRGB"  // Default fallback
            }
        }

        internal fun resolveTransferFunctionLabel(profileName: String, logEnabled: Boolean): String {
            if (logEnabled) return "LOG"
            val key = profileName.lowercase()
            return when {
                key.contains("ndr10") || key.contains("nivukx") || key.contains("hdr10") -> "PQ"
                key.contains("hlg") -> "HLG"
                key.contains("log") -> "LOG"
                else -> "sRGB"
            }
        }

        internal fun resolveDynamicRangeLabel(
            profileName: String,
            logEnabled: Boolean,
            hdrSuggested: Boolean
        ): String {
            val key = profileName.lowercase()
            return when {
                key.contains("nivukx") -> "NIVUKX Vision"
                key.contains("ndr10") || key.contains("hdr10") || key.contains("hlg") -> "NDR10+"
                logEnabled -> "LOG"
                hdrSuggested -> "NDR"
                else -> "SDR"
            }
        }
    }
}
