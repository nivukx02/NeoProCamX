package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.VulkanImportStats

internal data class ProcessedGpuRecordingValidationStats(
    val frameImportEnabled: Boolean,
    val encoderSurfaceBound: Boolean,
    val importedFrames: Long,
    val firstSuccessfulImportElapsedMs: Long,
    val lastSuccessfulImportElapsedMs: Long,
    val offscreenPathActive: Boolean,
    val offscreenPresentationTimeSet: Boolean
)

internal data class SensorLinearNdrPreviewStopBoundary(
    val quiesced: Boolean,
    val failureReason: String?
)

internal fun VulkanImportStats.toProcessedGpuRecordingValidationStats():
    ProcessedGpuRecordingValidationStats {
    val offscreenPathActive = offscreenAhbEglEncoderActive || offscreenEglSubmittedFrames > 0L
    val frameEvidence = ProcessedGpuRecordingRequirementPolicy.resolveFrameEvidence(
        genericFrameImportEnabled = frameImportEnabled,
        genericEncoderSurfaceBound = encoderSurfaceBound,
        sessionImportedFrames = sessionImportedFrames,
        offscreenPathActive = offscreenPathActive,
        offscreenEncoderSurfaceBound = offscreenEglEncoderSurfaceBound,
        offscreenSubmittedFrames = offscreenEglSubmittedFrames,
        offscreenPresentationTimeSet = offscreenEglPresentationTimeSet
    )
    return ProcessedGpuRecordingValidationStats(
        frameImportEnabled = frameEvidence.frameImportEnabled,
        encoderSurfaceBound = frameEvidence.encoderSurfaceBound,
        importedFrames = frameEvidence.importedFrames,
        firstSuccessfulImportElapsedMs = firstSuccessfulImportElapsedMs,
        lastSuccessfulImportElapsedMs = lastSuccessfulImportElapsedMs,
        offscreenPathActive = offscreenPathActive,
        offscreenPresentationTimeSet = !offscreenPathActive || offscreenEglPresentationTimeSet
    )
}
