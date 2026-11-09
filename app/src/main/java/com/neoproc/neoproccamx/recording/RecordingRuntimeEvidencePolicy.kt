package com.neoproc.neoproccamx.recording

import java.util.Locale

internal data class RecordingRuntimeEvidence(
    val allGpuProcessingClaimed: Boolean,
    val zeroCopyProcessingClaimed: Boolean,
    val gpuProcessingValidated: Boolean,
    val processedRecordingFramesValidated: Boolean,
    val zeroCopyValidated: Boolean,
    val performanceTelemetryValidated: Boolean,
    val gpuProcessingPath: String,
    val zeroCopyEvidencePath: String,
    val ispFallbackRequestApplied: Boolean,
    val ispFallbackRequestSummary: String,
    val cpuUsagePercent: Float?,
    val maxFrameTimeMs: Float?,
    val enhancementUniformSignature: String,
    val previewEnhancementUniformSignature: String,
    val recordingEnhancementUniformSignature: String,
    val enhancementTemporalBlend: Float,
    val enhancementSpatialDenoise: Float,
    val enhancementChromaDenoise: Float,
    val enhancementSharpening: Float,
    val enhancementSubjectDetailGain: Float,
    val enhancementDetailProfile: String,
    val targetCpuUsagePercent: Float = RecordingRuntimeEvidencePolicy.DEFAULT_TARGET_CPU_USAGE_PERCENT
)

internal object RecordingRuntimeEvidencePolicy {
    const val DEFAULT_TARGET_CPU_USAGE_PERCENT = 10f
    private const val CAMERA2_ISP_FALLBACK = "CAMERA2_ISP_ENCODED_FALLBACK"
    private const val CAMERA2_PREVIEW_PARITY = "CAMERA2_PREVIEW_PARITY_RECORD_SURFACE"
    private const val VULKAN_IMPORT_ENCODER = "VULKAN_INTERNAL_HARDWARE_IMPORT_ENCODER"
    private const val VULKAN_OFFSCREEN_AHB_EGL_ENCODER = "VULKAN_OFFSCREEN_AHB_EGL_ENCODER"
    private const val APP_P010_BYTEBUFFER_ENCODER = "APP_P010_BYTEBUFFER_ENCODER"
    private const val NPV10_PRIVATE_MASTER_WRITE = "NPV10_PRIVATE_MASTER_WRITE"
    private const val NOT_REPORTED = "NOT_REPORTED"

    /**
     * Process CPU consumed during the exact live-recording window.
     *
     * Keeping the end timestamps explicit prevents container finalisation and offline develop work
     * from being charged to the realtime recording budget after the user has already pressed stop.
     */
    fun processCpuUsagePercent(
        startProcessCpuMs: Long,
        startElapsedNs: Long,
        endProcessCpuMs: Long,
        endElapsedNs: Long
    ): Float? {
        if (startProcessCpuMs <= 0L || startElapsedNs <= 0L || endElapsedNs < startElapsedNs) {
            return null
        }
        val elapsedWallMs = ((endElapsedNs - startElapsedNs) / 1_000_000L).coerceAtLeast(1L)
        val elapsedCpuMs = (endProcessCpuMs - startProcessCpuMs).coerceAtLeast(0L)
        return (elapsedCpuMs.toFloat() / elapsedWallMs.toFloat() * 100f).coerceAtLeast(0f)
    }

    fun fromMetadata(
        metadataLookup: (String) -> String?,
        cpuUsagePercent: Float? = null,
        maxFrameTimeMs: Float? = null,
        targetCpuUsagePercent: Float = DEFAULT_TARGET_CPU_USAGE_PERCENT
    ): RecordingRuntimeEvidence {
        val explicitGpuPath = metadataLookup.clean("gpuProcessingPath")
        val recordingUsesCustomGpu = metadataLookup.boolean("recordingUsesCustomGpu")
        val camera2DirectFallback = metadataLookup.boolean("camera2DirectFallback")
        val customProcessingBypassed = metadataLookup.boolean("customProcessingBypassed") || camera2DirectFallback
        val feedPath = metadataLookup.clean("parityFeedPath").orEmpty()
        val camera2PreviewParity = feedPath.equals(CAMERA2_PREVIEW_PARITY, ignoreCase = true)
        val pipeline = metadataLookup.clean("pipeline").orEmpty().uppercase(Locale.US)
        val importedFrames = maxOf(
            metadataLookup.long("vulkanSessionImportedFrames"),
            metadataLookup.long("offscreenAhbImportedFrames"),
            metadataLookup.long("offscreenEglSubmittedFrames")
        )
        val encodedProcessedFrames = maxOf(
            metadataLookup.long("vulkanEncodedProcessedFrames"),
            metadataLookup.long("offscreenEglSubmittedFrames")
        )
        val byteBufferSubmittedFrames = maxOf(
            metadataLookup.long("byteBufferSubmittedFrames"),
            metadataLookup.long("byteBufferP010FramesQueued")
        )
        val byteBufferEncodedFrames = maxOf(
            metadataLookup.long("byteBufferEncodedFrames"),
            metadataLookup.long("byteBufferP010FramesEncoded")
        )
        val byteBufferReadoutEncoderActive =
            metadataLookup.boolean("byteBufferReadoutEncoderActive") ||
                metadataLookup.boolean("byteBufferP010EncoderActive") ||
                feedPath.equals("BYTEBUFFER_P010_ENCODER", ignoreCase = true)
        val liveP010QueuedEnhancedFrames = metadataLookup.long("liveP010QueuedEnhancedFrames")
        val liveP010GpuValidated =
            byteBufferReadoutEncoderActive &&
                liveP010QueuedEnhancedFrames > 0L &&
                liveP010QueuedEnhancedFrames <= byteBufferSubmittedFrames
        val frameImportEnabled = metadataLookup.boolean("vulkanFrameImportEnabled")
        val offscreenSurfaceBound = metadataLookup.boolean("offscreenEglEncoderSurfaceBound")
        val encoderSurfaceBound =
            metadataLookup.boolean("vulkanEncoderSurfaceBound") || offscreenSurfaceBound
        val offscreenPath =
            feedPath.equals("OFFSCREEN_AHB_EGL_ENCODER", ignoreCase = true) ||
                metadataLookup.boolean("offscreenAhbEglEncoderActive") ||
                metadataLookup.long("offscreenEglSubmittedFrames") > 0L
        val importEvidenceValidated =
            recordingUsesCustomGpu &&
                !customProcessingBypassed &&
                importedFrames > 0L &&
                encoderSurfaceBound
        val gpuValidated = importEvidenceValidated || liveP010GpuValidated
        val byteBufferProcessedFramesValidated =
            byteBufferReadoutEncoderActive &&
                byteBufferSubmittedFrames > 0L &&
                byteBufferEncodedFrames > 0L
        val niv99Required = metadataLookup.boolean("niv99RecordingRequired")
        val niv99ProcessedFrames = metadataLookup.long("niv99RecordingProcessedFrames")
        val niv99PersistedFrames = metadataLookup.long("niv99RecordingPersistedFrames")
        val niv99ExpectedFrames = metadataLookup.long("niv99RecordingExpectedFrames")
        val niv99FailedFrames = metadataLookup.long("niv99RecordingFailedFrames")
        val niv99ProcessedFramesValidated =
            niv99Required &&
                niv99ExpectedFrames > 0L &&
                niv99ProcessedFrames == niv99ExpectedFrames &&
                niv99PersistedFrames == niv99ExpectedFrames &&
                niv99FailedFrames == 0L
        val processedRecordingFramesValidated =
            (gpuValidated && encodedProcessedFrames > 0L) ||
                byteBufferProcessedFramesValidated ||
                niv99ProcessedFramesValidated
        val zeroCopyValidated = gpuValidated && frameImportEnabled && importedFrames > 0L
        val gpuPath = explicitGpuPath ?: when {
            byteBufferReadoutEncoderActive -> APP_P010_BYTEBUFFER_ENCODER
            (gpuValidated || recordingUsesCustomGpu) && offscreenPath -> VULKAN_OFFSCREEN_AHB_EGL_ENCODER
            gpuValidated || recordingUsesCustomGpu -> VULKAN_IMPORT_ENCODER
            camera2PreviewParity -> CAMERA2_PREVIEW_PARITY
            customProcessingBypassed -> CAMERA2_ISP_FALLBACK
            pipeline == "CUSTOM-PACKED10-NPV10" || pipeline == "UNCOMPRESSED-NPV10" ->
                NPV10_PRIVATE_MASTER_WRITE
            feedPath.isNotBlank() -> feedPath
            else -> NOT_REPORTED
        }
        val zeroCopyEvidencePath = metadataLookup.clean("zeroCopyEvidencePath") ?: if (zeroCopyValidated && offscreenPath) {
            "offscreenAhbEgl:submittedFrames=${metadataLookup.long("offscreenEglSubmittedFrames")}"
        } else if (zeroCopyValidated) {
            "vulkanImportStats:sessionImportedFrames=$importedFrames"
        } else {
            ""
        }
        val resolvedCpuUsagePercent = metadataLookup.float("cpuUsagePercent") ?: cpuUsagePercent
        val resolvedMaxFrameTimeMs = metadataLookup.float("maxFrameTimeMs") ?: maxFrameTimeMs
        val telemetryValidated =
            metadataLookup.boolean("performanceTelemetryValidated") ||
                (resolvedCpuUsagePercent != null &&
                    resolvedMaxFrameTimeMs != null &&
                    resolvedMaxFrameTimeMs > 0f)
        val enhancementUniformSignature = metadataLookup.clean("enhancementUniformSignature").orEmpty()
        val previewEnhancementUniformSignature =
            metadataLookup.clean("previewEnhancementUniformSignature") ?: enhancementUniformSignature
        val recordingEnhancementUniformSignature =
            metadataLookup.clean("recordingEnhancementUniformSignature") ?: enhancementUniformSignature
        return RecordingRuntimeEvidence(
            allGpuProcessingClaimed = metadataLookup.boolean("allGpuProcessingClaimed"),
            zeroCopyProcessingClaimed = metadataLookup.boolean("zeroCopyProcessingClaimed") || zeroCopyValidated,
            gpuProcessingValidated = gpuValidated,
            processedRecordingFramesValidated = processedRecordingFramesValidated,
            zeroCopyValidated = zeroCopyValidated,
            performanceTelemetryValidated = telemetryValidated,
            gpuProcessingPath = gpuPath,
            zeroCopyEvidencePath = zeroCopyEvidencePath,
            ispFallbackRequestApplied = metadataLookup.boolean("ispFallbackRequestApplied"),
            ispFallbackRequestSummary = metadataLookup.clean("ispFallbackRequestSummary").orEmpty(),
            cpuUsagePercent = resolvedCpuUsagePercent,
            maxFrameTimeMs = resolvedMaxFrameTimeMs,
            enhancementUniformSignature = enhancementUniformSignature,
            previewEnhancementUniformSignature = previewEnhancementUniformSignature,
            recordingEnhancementUniformSignature = recordingEnhancementUniformSignature,
            enhancementTemporalBlend = metadataLookup.float("enhancementTemporalBlend") ?: 0f,
            enhancementSpatialDenoise = metadataLookup.float("enhancementSpatialDenoise") ?: 0f,
            enhancementChromaDenoise = metadataLookup.float("enhancementChromaDenoise") ?: 0f,
            enhancementSharpening = metadataLookup.float("enhancementSharpening") ?: 0f,
            enhancementSubjectDetailGain = metadataLookup.float("enhancementSubjectDetailGain") ?: 0f,
            enhancementDetailProfile = metadataLookup.clean("enhancementDetailProfile").orEmpty(),
            targetCpuUsagePercent = targetCpuUsagePercent
        )
    }

    private fun ((String) -> String?).clean(key: String): String? =
        invoke(key)?.trim()?.takeIf { it.isNotBlank() }

    private fun ((String) -> String?).boolean(key: String): Boolean =
        when (clean(key)?.lowercase(Locale.US)) {
            "true", "1", "yes", "supported", "enabled", "pass" -> true
            else -> false
        }

    private fun ((String) -> String?).long(key: String): Long =
        clean(key)?.toLongOrNull() ?: 0L

    private fun ((String) -> String?).float(key: String): Float? =
        clean(key)?.toFloatOrNull()
}
