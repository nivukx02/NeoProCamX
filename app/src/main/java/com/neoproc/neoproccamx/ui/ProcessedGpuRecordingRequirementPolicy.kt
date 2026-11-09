package com.neoproc.neoproccamx.ui

internal data class ProcessedGpuRecordingStartDecision(
    val allowed: Boolean,
    val failureMessage: String?
)

internal data class ProcessedGpuRecordingRuntimeDecision(
    val validated: Boolean,
    val failureMessage: String?
)

internal data class ProcessedGpuRecordingFrameEvidence(
    val frameImportEnabled: Boolean,
    val encoderSurfaceBound: Boolean,
    val importedFrames: Long
)

internal object ProcessedGpuRecordingRequirementPolicy {
    const val INTERNAL_HARDWARE_IMPORT_SESSION_TIMEOUT_MS = 4_000L
    const val THROUGHPUT_FAILURE_BLOCK_MS = 5 * 60 * 1_000L
    const val HARDWARE_IMPORT_ACTIVITY_WAIT_MS = 1_000L
    const val PREARMED_OFFSCREEN_IMPORT_ACTIVITY_WAIT_MS = 600L
    const val CAMERA_P010_SDR_PREWARM_TIMEOUT_MS = 1_500L
    const val FIRST_IMPORTED_FRAME_TIMEOUT_MS = 1_500L
    const val SUSTAINED_IMPORTED_FRAME_TIMEOUT_MS = 15_000L
    const val SUSTAINED_IMPORT_OBSERVATION_MS = 1_200L

    private const val MIN_SUSTAINED_IMPORT_FPS_RATIO = 1.00f
    private const val MIN_STARTUP_ACCEPTANCE_IMPORT_FPS_RATIO = 0.90f
    private const val MIN_STARTUP_ACCEPTANCE_ABSOLUTE_FPS = 24f
    private const val MIN_ABSOLUTE_SUSTAINED_IMPORT_FPS = 12f
    private const val MIN_STARTUP_CONTINUITY_FRAMES = 3L
    private const val STARTUP_IMPORT_STALL_LIMIT_MS = 750L
    private const val THROUGHPUT_FAILURE_PREFIX = "GPU processed recording import throughput too low"

    /**
     * Selects the counter authority for the active recording transport.
     *
     * The generic Vulkan importer and the recorder-owned offscreen EGL path are independent
     * ingress routes. Sensor-linear RAW recording legitimately keeps the generic importer disabled
     * while completed ACEScg masters are submitted directly to the encoder. Requiring the generic
     * flag in that state rejects a live encoder even though every successful native submission is
     * already represented by [offscreenSubmittedFrames].
     *
     * Keep this fail-closed: once the offscreen route owns recording, only its bound surface,
     * presentation timestamp, and successful submission counter can prove readiness. Preview or
     * stale generic-import frames cannot satisfy the recorder gate.
     */
    fun resolveFrameEvidence(
        genericFrameImportEnabled: Boolean,
        genericEncoderSurfaceBound: Boolean,
        sessionImportedFrames: Long,
        offscreenPathActive: Boolean,
        offscreenEncoderSurfaceBound: Boolean,
        offscreenSubmittedFrames: Long,
        offscreenPresentationTimeSet: Boolean
    ): ProcessedGpuRecordingFrameEvidence {
        if (!offscreenPathActive) {
            return ProcessedGpuRecordingFrameEvidence(
                frameImportEnabled = genericFrameImportEnabled,
                encoderSurfaceBound = genericEncoderSurfaceBound,
                importedFrames = sessionImportedFrames.coerceAtLeast(0L)
            )
        }

        val submittedFrames = offscreenSubmittedFrames.coerceAtLeast(0L)
        val offscreenReady =
            offscreenEncoderSurfaceBound &&
                offscreenPresentationTimeSet &&
                submittedFrames > 0L
        return ProcessedGpuRecordingFrameEvidence(
            frameImportEnabled = offscreenReady,
            encoderSurfaceBound = offscreenReady,
            importedFrames = submittedFrames
        )
    }

    fun canStart(
        deliveryKind: VideoDeliveryKind,
        feedPath: RecordingFeedPath,
        vulkanRuntimeSupported: Boolean,
        strictProcessedGpuRequired: Boolean = false,
        strictHybridP010Required: Boolean = false
    ): ProcessedGpuRecordingStartDecision =
        if (deliveryKind == VideoDeliveryKind.RAW) {
            ProcessedGpuRecordingStartDecision(allowed = true, failureMessage = null)
        } else if (
            strictHybridP010Required &&
            feedPath != RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
        ) {
            ProcessedGpuRecordingStartDecision(
                allowed = false,
                failureMessage = "Strict hybrid recording requires validated offscreen AHB/EGL P010 GPU recording."
            )
        } else if (feedPath == RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE) {
            ProcessedGpuRecordingStartDecision(
                allowed = false,
                failureMessage = "Processed video cannot use the direct Camera2 record surface path."
            )
        } else if (
            strictProcessedGpuRequired &&
            feedPath == RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
        ) {
            ProcessedGpuRecordingStartDecision(
                allowed = false,
                failureMessage = "Strict Normal/Pro SDR requires a validated processed GPU recording route."
            )
        } else if (feedPath == RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE) {
            ProcessedGpuRecordingStartDecision(allowed = true, failureMessage = null)
        } else if (feedPath == RecordingFeedPath.BYTEBUFFER_P010_ENCODER) {
            ProcessedGpuRecordingStartDecision(allowed = true, failureMessage = null)
        } else if (
            feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER ||
            vulkanRuntimeSupported
        ) {
            ProcessedGpuRecordingStartDecision(allowed = true, failureMessage = null)
        } else {
            ProcessedGpuRecordingStartDecision(
                allowed = false,
                failureMessage = "GPU processed recording is unavailable for this video mode."
            )
        }

    fun validateRunning(
        frameImportEnabled: Boolean,
        encoderSurfaceBound: Boolean,
        importedFrames: Long
    ): ProcessedGpuRecordingRuntimeDecision =
        if (frameImportEnabled && encoderSurfaceBound && importedFrames > 0L) {
            ProcessedGpuRecordingRuntimeDecision(validated = true, failureMessage = null)
        } else {
            ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "GPU processed recording did not receive validated frames."
            )
        }

    fun validateStartupContinuity(
        frameImportEnabled: Boolean,
        encoderSurfaceBound: Boolean,
        importedFrames: Long,
        lastSuccessfulImportElapsedMs: Long,
        currentElapsedRealtimeMs: Long
    ): ProcessedGpuRecordingRuntimeDecision {
        val firstFrameDecision = validateRunning(
            frameImportEnabled = frameImportEnabled,
            encoderSurfaceBound = encoderSurfaceBound,
            importedFrames = importedFrames
        )
        if (!firstFrameDecision.validated) return firstFrameDecision

        if (importedFrames < MIN_STARTUP_CONTINUITY_FRAMES || lastSuccessfulImportElapsedMs <= 0L) {
            return ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "GPU processed recording has not produced enough startup continuity evidence."
            )
        }

        val importStallMs = currentElapsedRealtimeMs - lastSuccessfulImportElapsedMs
        return if (importStallMs in 0L..STARTUP_IMPORT_STALL_LIMIT_MS) {
            ProcessedGpuRecordingRuntimeDecision(validated = true, failureMessage = null)
        } else {
            ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "GPU processed recording import stalled during startup: " +
                    "lastFrameAgeMs=$importStallMs limitMs=$STARTUP_IMPORT_STALL_LIMIT_MS."
            )
        }
    }

    fun validateSustainedRunning(
        frameImportEnabled: Boolean,
        encoderSurfaceBound: Boolean,
        importedFrames: Long,
        firstSuccessfulImportElapsedMs: Long,
        lastSuccessfulImportElapsedMs: Long,
        currentElapsedRealtimeMs: Long,
        targetFps: Int
    ): ProcessedGpuRecordingRuntimeDecision {
        val firstFrameDecision = validateRunning(
            frameImportEnabled = frameImportEnabled,
            encoderSurfaceBound = encoderSurfaceBound,
            importedFrames = importedFrames
        )
        if (!firstFrameDecision.validated) return firstFrameDecision

        if (firstSuccessfulImportElapsedMs <= 0L || lastSuccessfulImportElapsedMs <= firstSuccessfulImportElapsedMs) {
            return ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "GPU processed recording has not produced enough timing evidence."
            )
        }

        val observationElapsedMs = currentElapsedRealtimeMs - firstSuccessfulImportElapsedMs
        if (observationElapsedMs < SUSTAINED_IMPORT_OBSERVATION_MS) {
            return ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "GPU processed recording is still warming up sustained frame validation."
            )
        }

        val importStallMs = currentElapsedRealtimeMs - lastSuccessfulImportElapsedMs
        if (importStallMs !in 0L..STARTUP_IMPORT_STALL_LIMIT_MS) {
            return ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "$THROUGHPUT_FAILURE_PREFIX: " +
                    "lastFrameAgeMs=$importStallMs limitMs=$STARTUP_IMPORT_STALL_LIMIT_MS."
            )
        }

        val sampleElapsedMs = observationElapsedMs.coerceAtLeast(1L)
        val estimatedImportFps = importedFrames * 1_000f / sampleElapsedMs.toFloat()
        val requiredImportFps = maxOf(
            MIN_ABSOLUTE_SUSTAINED_IMPORT_FPS,
            targetFps.coerceAtLeast(1) * MIN_SUSTAINED_IMPORT_FPS_RATIO
        )
        val requiredFrames = kotlin.math.ceil(
            requiredImportFps * sampleElapsedMs.toFloat() / 1_000f
        ).toLong().coerceAtLeast(2L)

        return if (estimatedImportFps >= requiredImportFps && importedFrames >= requiredFrames) {
            ProcessedGpuRecordingRuntimeDecision(validated = true, failureMessage = null)
        } else {
            ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "$THROUGHPUT_FAILURE_PREFIX: " +
                    "fps=$estimatedImportFps required=$requiredImportFps frames=$importedFrames/$requiredFrames."
            )
        }
    }

    fun validateSustainedRunningWindow(
        frameImportEnabled: Boolean,
        encoderSurfaceBound: Boolean,
        importedFrames: Long,
        importedFramesAtWindowStart: Long,
        windowStartElapsedMs: Long,
        lastSuccessfulImportElapsedMs: Long,
        currentElapsedRealtimeMs: Long,
        targetFps: Int
    ): ProcessedGpuRecordingRuntimeDecision {
        val firstFrameDecision = validateRunning(
            frameImportEnabled = frameImportEnabled,
            encoderSurfaceBound = encoderSurfaceBound,
            importedFrames = importedFrames
        )
        if (!firstFrameDecision.validated) return firstFrameDecision

        if (windowStartElapsedMs <= 0L || currentElapsedRealtimeMs <= windowStartElapsedMs) {
            return ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "GPU processed recording is still warming up sustained frame validation."
            )
        }

        val observationElapsedMs = currentElapsedRealtimeMs - windowStartElapsedMs
        if (observationElapsedMs < SUSTAINED_IMPORT_OBSERVATION_MS) {
            return ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "GPU processed recording is still warming up sustained frame validation."
            )
        }

        val importStallMs = currentElapsedRealtimeMs - lastSuccessfulImportElapsedMs
        if (importStallMs !in 0L..STARTUP_IMPORT_STALL_LIMIT_MS) {
            return ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "$THROUGHPUT_FAILURE_PREFIX: " +
                    "lastFrameAgeMs=$importStallMs limitMs=$STARTUP_IMPORT_STALL_LIMIT_MS."
            )
        }

        val windowImportedFrames = (importedFrames - importedFramesAtWindowStart).coerceAtLeast(0L)
        val sampleElapsedMs = observationElapsedMs.coerceAtLeast(1L)
        val estimatedImportFps = windowImportedFrames * 1_000f / sampleElapsedMs.toFloat()
        val requiredImportFps = maxOf(
            MIN_ABSOLUTE_SUSTAINED_IMPORT_FPS,
            targetFps.coerceAtLeast(1) * MIN_SUSTAINED_IMPORT_FPS_RATIO
        )
        val requiredFrames = kotlin.math.ceil(
            requiredImportFps * sampleElapsedMs.toFloat() / 1_000f
        ).toLong().coerceAtLeast(2L)

        return if (estimatedImportFps >= requiredImportFps && windowImportedFrames >= requiredFrames) {
            ProcessedGpuRecordingRuntimeDecision(validated = true, failureMessage = null)
        } else {
            ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "$THROUGHPUT_FAILURE_PREFIX: " +
                    "fps=$estimatedImportFps required=$requiredImportFps " +
                    "frames=$windowImportedFrames/$requiredFrames."
            )
        }
    }

    fun validateNearTargetStartupRunning(
        frameImportEnabled: Boolean,
        encoderSurfaceBound: Boolean,
        importedFrames: Long,
        firstSuccessfulImportElapsedMs: Long,
        lastSuccessfulImportElapsedMs: Long,
        currentElapsedRealtimeMs: Long,
        targetFps: Int
    ): ProcessedGpuRecordingRuntimeDecision {
        val continuityDecision = validateStartupContinuity(
            frameImportEnabled = frameImportEnabled,
            encoderSurfaceBound = encoderSurfaceBound,
            importedFrames = importedFrames,
            lastSuccessfulImportElapsedMs = lastSuccessfulImportElapsedMs,
            currentElapsedRealtimeMs = currentElapsedRealtimeMs
        )
        if (!continuityDecision.validated) return continuityDecision

        if (firstSuccessfulImportElapsedMs <= 0L || lastSuccessfulImportElapsedMs <= firstSuccessfulImportElapsedMs) {
            return ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "GPU processed recording has not produced enough timing evidence."
            )
        }

        val sampleElapsedMs = (lastSuccessfulImportElapsedMs - firstSuccessfulImportElapsedMs)
            .coerceAtLeast(1L)
        val estimatedImportFps = importedFrames * 1_000f / sampleElapsedMs.toFloat()
        val requiredImportFps = maxOf(
            MIN_STARTUP_ACCEPTANCE_ABSOLUTE_FPS,
            targetFps.coerceAtLeast(1) * MIN_STARTUP_ACCEPTANCE_IMPORT_FPS_RATIO
        )
        val requiredFrames = kotlin.math.ceil(
            requiredImportFps * sampleElapsedMs.toFloat() / 1_000f
        ).toLong().coerceAtLeast(MIN_STARTUP_CONTINUITY_FRAMES)

        return if (estimatedImportFps >= requiredImportFps && importedFrames >= requiredFrames) {
            ProcessedGpuRecordingRuntimeDecision(validated = true, failureMessage = null)
        } else {
            ProcessedGpuRecordingRuntimeDecision(
                validated = false,
                failureMessage = "GPU processed recording startup cadence too low: " +
                    "fps=$estimatedImportFps required=$requiredImportFps frames=$importedFrames/$requiredFrames."
            )
        }
    }

    fun isThroughputFailure(failureMessage: String?): Boolean =
        failureMessage?.startsWith(THROUGHPUT_FAILURE_PREFIX) == true
}
