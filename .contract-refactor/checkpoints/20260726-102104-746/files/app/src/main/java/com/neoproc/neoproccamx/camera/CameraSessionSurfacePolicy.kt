package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import android.view.Surface

// ════════════════════════════════════════════════════════════════════════════════
// HIGH-END CAMERA SESSION SURFACE POLICY
// ════════════════════════════════════════════════════════════════════════════════
// Professional-grade surface management with output priority, quality modes,
// and advanced pipeline configuration for cinema workflows.
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Surface output priority for resource allocation.
 */
internal enum class SurfacePriority {
    /** Preview must never drop frames */
    PREVIEW_CRITICAL,
    /** Recording takes priority */
    RECORDING_CRITICAL,
    /** Balanced for photo capture */
    CAPTURE_OPTIMIZED,
    /** AI/analysis can be delayed */
    ANALYSIS_BACKGROUND
}

/**
 * Pipeline mode for session configuration.
 */
internal enum class PipelineMode {
    /** Standard preview only */
    PREVIEW_ONLY,
    /** Preview with still capture capability */
    PREVIEW_CAPTURE,
    /** Video recording mode */
    RECORDING,
    /** High-speed/slow-motion recording */
    HIGH_SPEED,
    /** RAW video capture */
    RAW_VIDEO,
    /** Dual output (preview + secondary) */
    DUAL_OUTPUT
}

/**
 * Surface validation result.
 */
internal enum class SurfaceValidation {
    VALID,
    INVALID_NULL,
    INVALID_RELEASED,
    INVALID_SIZE_MISMATCH,
    INVALID_FORMAT_UNSUPPORTED
}

internal data class CameraSessionSurfacePlan(
    val sessionSurfaces: List<Surface>,
    val previewTargets: List<Surface>,
    val videoTargets: List<Surface>,
    val usesDirectPreviewSurface: Boolean,
    val usesHardwareImportSurface: Boolean,
    
    // Professional extensions
    val pipelineMode: PipelineMode = PipelineMode.PREVIEW_ONLY,
    val surfacePriority: SurfacePriority = SurfacePriority.PREVIEW_CRITICAL,
    val captureTargets: List<Surface> = emptyList(),
    val analysisTargets: List<Surface> = emptyList(),
    val supportsParallelCapture: Boolean = false,
    val maxConcurrentTargets: Int = 3,
    val requiresReprocessing: Boolean = false
)

/**
 * Extended surface configuration for professional use.
 */
internal data class SurfaceConfiguration(
    val surface: Surface,
    val purpose: SurfacePurpose,
    val priority: Int,
    val isOptional: Boolean = false,
    val validationResult: SurfaceValidation = SurfaceValidation.VALID
)

internal enum class SurfacePurpose {
    PREVIEW,
    HARDWARE_IMPORT,
    YUV_ANALYSIS,
    JPEG_CAPTURE,
    RAW_CAPTURE,
    VIDEO_RECORD,
    DEPTH_OUTPUT,
    HEIC_CAPTURE
}

internal object CameraSessionSurfacePolicy {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun resolve(
        previewSurface: Surface?,
        hardwareImportSurface: Surface?,
        yuvSurface: Surface?,
        jpegSurface: Surface?,
        rawSurface: Surface?,
        recordSurface: Surface?,
        rawVideoCaptureEnabled: Boolean,
        recordingSafeSession: Boolean = false
    ): CameraSessionSurfacePlan? {
        val previewOutputSurface = previewSurface ?: hardwareImportSurface
        val usesHardwareImportPreview = prefersDedicatedHardwareImportAnalysis(
            previewSurfacePresent = previewSurface != null,
            hardwareImportSurfacePresent = hardwareImportSurface != null
        )
        val liveAnalysisSurface = if (usesHardwareImportPreview) null else yuvSurface
        val sessionAnalysisSurface = if (recordingSafeSession) null else liveAnalysisSurface
        val previewTargets = validDistinctSurfaces {
            previewOutputSurface?.let(::add)
            sessionAnalysisSurface?.let(::add)
            if (rawVideoCaptureEnabled) {
                rawSurface?.let(::add)
            }
        }
        val videoTargets = validDistinctSurfaces {
            previewOutputSurface?.let(::add)
            recordSurface?.let(::add)
            sessionAnalysisSurface?.let(::add)
            if (rawVideoCaptureEnabled) {
                rawSurface?.let(::add)
            }
        }
        val sessionSurfaces = validDistinctSurfaces {
            addAll(if (recordSurface != null) videoTargets else previewTargets)
            // Recording-safe mode keeps preview and record surfaces, but drops
            // analysis and still-capture outputs that some devices cannot combine
            // with a live encoder input.
            if (!recordingSafeSession) {
                jpegSurface?.let(::add)
                if (!rawVideoCaptureEnabled) {
                    rawSurface?.let(::add)
                }
            }
        }
        if (sessionSurfaces.isEmpty()) return null

        val resolvedPreviewTargets = previewTargets.ifEmpty {
            validDistinctSurfaces {
                previewOutputSurface?.let(::add)
                sessionAnalysisSurface?.let(::add)
            }
        }
        val resolvedVideoTargets = videoTargets.ifEmpty {
            validDistinctSurfaces {
                previewOutputSurface?.let(::add)
                recordSurface?.let(::add)
                sessionAnalysisSurface?.let(::add)
                if (rawVideoCaptureEnabled) {
                    rawSurface?.let(::add)
                }
            }
        }
        
        // Determine pipeline mode
        val pipelineMode = determinePipelineMode(
            hasRecordSurface = recordSurface != null,
            hasRawSurface = rawSurface != null,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled
        )
        
        // Build capture targets
        val captureTargets = validDistinctSurfaces {
            if (!recordingSafeSession) {
                jpegSurface?.let(::add)
                rawSurface?.let(::add)
            }
        }
        
        // Build analysis targets
        val analysisTargets = validDistinctSurfaces {
            if (!recordingSafeSession) {
                if (usesHardwareImportPreview) {
                    hardwareImportSurface?.let(::add)
                } else {
                    liveAnalysisSurface?.let(::add)
                }
            }
        }

        return CameraSessionSurfacePlan(
            sessionSurfaces = sessionSurfaces,
            previewTargets = resolvedPreviewTargets,
            videoTargets = resolvedVideoTargets,
            usesDirectPreviewSurface = previewSurface != null && resolvedPreviewTargets.contains(previewSurface),
            usesHardwareImportSurface =
                hardwareImportSurface != null && resolvedPreviewTargets.contains(hardwareImportSurface),
            pipelineMode = pipelineMode,
            surfacePriority = when {
                recordSurface != null -> SurfacePriority.RECORDING_CRITICAL
                strictHighEndEnabled() && captureTargets.isNotEmpty() -> SurfacePriority.CAPTURE_OPTIMIZED
                else -> SurfacePriority.PREVIEW_CRITICAL
            },
            captureTargets = captureTargets,
            analysisTargets = analysisTargets,
            supportsParallelCapture = !recordingSafeSession && jpegSurface != null && rawSurface != null,
            maxConcurrentTargets = if (strictHighEndEnabled()) {
                maxOf(sessionSurfaces.size, 8) // FLAGSHIP: 8 concurrent targets
            } else {
                sessionSurfaces.size
            },
            requiresReprocessing = false
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROFESSIONAL SURFACE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Resolve with explicit surface configurations for professional use.
     */
    fun resolveAdvanced(
        configurations: List<SurfaceConfiguration>
    ): CameraSessionSurfacePlan? {
        // Filter valid surfaces
        val validConfigs = configurations.filter { 
            it.validationResult == SurfaceValidation.VALID && 
            validateSurface(it.surface) == SurfaceValidation.VALID
        }
        
        if (validConfigs.isEmpty()) return null
        
        // Sort by priority
        val sortedConfigs = validConfigs.sortedBy { it.priority }
        
        // Categorize surfaces
        val previewSurfaces = sortedConfigs.filter { 
            it.purpose == SurfacePurpose.PREVIEW || it.purpose == SurfacePurpose.HARDWARE_IMPORT 
        }.map { it.surface }
        
        val videoSurfaces = sortedConfigs.filter { 
            it.purpose == SurfacePurpose.VIDEO_RECORD || 
            it.purpose == SurfacePurpose.PREVIEW ||
            it.purpose == SurfacePurpose.HARDWARE_IMPORT
        }.map { it.surface }
        
        val captureSurfaces = sortedConfigs.filter { 
            it.purpose == SurfacePurpose.JPEG_CAPTURE || 
            it.purpose == SurfacePurpose.RAW_CAPTURE ||
            it.purpose == SurfacePurpose.HEIC_CAPTURE
        }.map { it.surface }
        
        val analysisSurfaces = sortedConfigs.filter { 
            it.purpose == SurfacePurpose.YUV_ANALYSIS || it.purpose == SurfacePurpose.DEPTH_OUTPUT 
        }.map { it.surface }
        
        val hasRecording = sortedConfigs.any { it.purpose == SurfacePurpose.VIDEO_RECORD }
        val hasRaw = sortedConfigs.any { it.purpose == SurfacePurpose.RAW_CAPTURE }
        
        return CameraSessionSurfacePlan(
            sessionSurfaces = sortedConfigs.map { it.surface }.distinct(),
            previewTargets = previewSurfaces.distinct(),
            videoTargets = videoSurfaces.distinct(),
            usesDirectPreviewSurface = sortedConfigs.any { it.purpose == SurfacePurpose.PREVIEW },
            usesHardwareImportSurface = sortedConfigs.any { it.purpose == SurfacePurpose.HARDWARE_IMPORT },
            pipelineMode = determinePipelineMode(hasRecording, hasRaw, hasRaw),
            surfacePriority = if (hasRecording) SurfacePriority.RECORDING_CRITICAL else SurfacePriority.PREVIEW_CRITICAL,
            captureTargets = captureSurfaces.distinct(),
            analysisTargets = analysisSurfaces.distinct(),
            supportsParallelCapture = captureSurfaces.size > 1,
            maxConcurrentTargets = if (strictHighEndEnabled()) {
                maxOf(sortedConfigs.size, 8) // FLAGSHIP: 8 concurrent targets
            } else {
                sortedConfigs.size
            }
        )
    }
    
    /**
     * Validate a surface for use in camera session.
     */
    fun validateSurface(surface: Surface?): SurfaceValidation {
        surface ?: return SurfaceValidation.INVALID_NULL
        return try {
            if (!surface.isValid) {
                SurfaceValidation.INVALID_RELEASED
            } else {
                SurfaceValidation.VALID
            }
        } catch (e: Exception) {
            SurfaceValidation.INVALID_RELEASED
        }
    }
    
    /**
     * Validate all surfaces in a plan.
     */
    fun validatePlan(plan: CameraSessionSurfacePlan): List<Pair<Surface, SurfaceValidation>> {
        return plan.sessionSurfaces.map { surface ->
            surface to validateSurface(surface)
        }
    }
    
    /**
     * Check if plan needs rebuild due to invalid surfaces.
     */
    fun planNeedsRebuild(plan: CameraSessionSurfacePlan): Boolean {
        return plan.sessionSurfaces.any { validateSurface(it) != SurfaceValidation.VALID }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SURFACE PRIORITY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get optimal target selection based on priority.
     */
    fun selectTargetsForPriority(
        plan: CameraSessionSurfacePlan,
        priority: SurfacePriority
    ): List<Surface> {
        return when (priority) {
            SurfacePriority.PREVIEW_CRITICAL -> plan.previewTargets
            SurfacePriority.RECORDING_CRITICAL -> plan.videoTargets
            SurfacePriority.CAPTURE_OPTIMIZED -> plan.captureTargets.ifEmpty { plan.previewTargets }
            SurfacePriority.ANALYSIS_BACKGROUND -> plan.analysisTargets.ifEmpty { plan.previewTargets }
        }
    }
    
    /**
     * Calculate maximum surfaces that can be used concurrently.
     */
    fun calculateMaxConcurrentSurfaces(
        pipelineMode: PipelineMode,
        hardwareLevel: Int
    ): Int {
        val strictHighEnd = strictHighEndEnabled()
        // Hardware level: 0=LIMITED, 1=FULL, 2=LEVEL_3, 3=EXTERNAL
        val baseMax = when (hardwareLevel) {
            0 -> 2 // LIMITED
            1 -> 3 // FULL
            2 -> 4 // LEVEL_3
            else -> 2
        }
        
        val resolved = when (pipelineMode) {
            PipelineMode.PREVIEW_ONLY -> minOf(baseMax, 2)
            PipelineMode.PREVIEW_CAPTURE -> baseMax
            PipelineMode.RECORDING -> minOf(baseMax, 3)
            PipelineMode.HIGH_SPEED -> 2 // High-speed has strict limits
            PipelineMode.RAW_VIDEO -> minOf(baseMax, 4) // FLAGSHIP: Extra for raw
            PipelineMode.DUAL_OUTPUT -> baseMax
        }
        return if (strictHighEnd) {
            (resolved + 4).coerceAtMost(8) // FLAGSHIP: Force up to 8 max
        } else {
            resolved
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private inline fun validDistinctSurfaces(block: MutableList<Surface>.() -> Unit): List<Surface> {
        return buildList(block)
            .distinct()
            .filter { surface -> runCatching { surface.isValid }.getOrDefault(true) }
    }

    internal fun prefersDedicatedHardwareImportAnalysis(
        previewSurfacePresent: Boolean,
        hardwareImportSurfacePresent: Boolean
    ): Boolean = !previewSurfacePresent && hardwareImportSurfacePresent
    
    private fun determinePipelineMode(
        hasRecordSurface: Boolean,
        hasRawSurface: Boolean,
        rawVideoCaptureEnabled: Boolean
    ): PipelineMode {
        return when {
            rawVideoCaptureEnabled && hasRawSurface -> PipelineMode.RAW_VIDEO
            hasRecordSurface -> PipelineMode.RECORDING
            hasRawSurface -> PipelineMode.PREVIEW_CAPTURE
            else -> PipelineMode.PREVIEW_ONLY
        }
    }
}
