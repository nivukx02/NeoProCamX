package com.neoproc.neoproccamx.camera

internal const val MODE_SWITCH_RAW_STANDBY_REASON =
    "High-end mode-switch RAW standby output"
internal const val MODE_SWITCH_YUV_STANDBY_REASON =
    "High-end mode-switch YUV/P010 standby output"

internal enum class CinemaStreamGraphMode {
    PREVIEW_STILL_READY,
    VIDEO_PREVIEW_READY,
    ENCODED_CINEMA,
    ENCODED_CINEMA_WITH_YUV_READOUT,
    SENSOR_LINEAR_NDR_PREVIEW,
    SENSOR_LINEAR_NDR_RECORDING,
    RAW_ORIGINAL_RECORDING,
    UNSUPPORTED
}

internal enum class CinemaStreamRequirement {
    REQUIRED,
    OPTIONAL,
    OMITTED
}

internal enum class CinemaStreamFallbackStep {
    HARDWARE_IMPORT,
    JPEG_STILL,
    YUV_ANALYSIS,
    STREAM_USE_CASE_HINTS
}

internal data class CinemaStreamGraphInventory(
    val supportsRaw: Boolean,
    val rawOutputSizes: List<CameraStreamSize>,
    val yuvOutputSizes: List<CameraStreamSize>,
    val jpegOutputSizes: List<CameraStreamSize>,
    val encoderOutputSizes: List<CameraStreamSize>,
    val fallbackPhotoSize: CameraStreamSize,
    /** Largest normal-pixel-mode RAW output; excludes max-resolution-mode-only dimensions. */
    val activeArrayRawSize: CameraStreamSize? = null
)

internal data class CinemaStreamGraphRequest(
    val recordingActive: Boolean,
    val videoPreviewActive: Boolean = false,
    val uncompressedReadoutRecording: Boolean = false,
    val rawVideoCaptureEnabled: Boolean,
    /** RAW_SENSOR -> project Vulkan demosaic -> linear ACEScg FP16; never NPRAW semantics. */
    val sensorLinearNdrCaptureEnabled: Boolean = false,
    // Full-depth RAW recording output prepared while RAW mode is idle. The repeating request targets
    // and drains it before capture so record start does not trigger a HAL pipeline warm-up.
    val rawVideoPrearmEnabled: Boolean = false,
    // RAW cinema-preview debayer feed: keep the RAW_SENSOR reader in-session during VIDEO+RAW preview
    // (not recording) so the GPU cinema-debayer can produce a genuine sensor-RAW monitor image.
    // Independent of rawVideoCaptureEnabled so it never implies a RAW recording session.
    val rawVideoPreviewActive: Boolean = false,
    val recordingSafeSessionFallback: Boolean,
    val hardwareImportRequested: Boolean,
    val internalEncoderHardwareImportActive: Boolean = false,
    val readoutSidecarMode: CameraReadoutSidecarMode,
    val analysisTargetSize: CameraStreamSize?,
    val recordingReadoutOverrideSize: CameraStreamSize?,
    val recordingEncoderTargetSize: CameraStreamSize?,
    // A small CPU-analysis YUV stream is requested for the proprietary NDR closed-loop
    // exposure/highlight meter. It remains OPTIONAL and is the first stream removed by the recording-safe
    // fallback, so unsupported output combinations never reduce encoder resolution, depth, or cadence.
    val previewAnalysisRequested: Boolean = false,
    /**
     * Retain the optional analysis reader allocation while excluding its Surface from a sustained
     * processed recording session. Required NPV10/RAW readout routes always take precedence.
     */
    val optionalAnalysisSuspendedForRecording: Boolean = false,
    // Allocate both standby readers when supported, but attach only the Surface owned by the active
    // mode. Some Qualcomm/Vivo providers abort asynchronously when RAW_SENSOR and P010 coexist in one
    // session even if only one is targeted by repeating requests.
    val modeSwitchRawStandbyRequested: Boolean = false,
    val modeSwitchAnalysisStandbyRequested: Boolean = false
)

internal data class CinemaStreamRoute(
    val requirement: CinemaStreamRequirement,
    val size: CameraStreamSize?,
    val createReader: Boolean,
    val includeInSession: Boolean,
    val reason: String
)

internal data class CinemaStreamGraphPlan(
    val mode: CinemaStreamGraphMode,
    val rawSensor: CinemaStreamRoute,
    val yuvAnalysis: CinemaStreamRoute,
    val jpegStill: CinemaStreamRoute,
    val encoder: CinemaStreamRoute,
    val hardwareImport: CinemaStreamRoute,
    val fallbackDropOrder: List<CinemaStreamFallbackStep>,
    val diagnostics: List<String>
) {
    /**
     * Any RAW route included in the session owns a live sensor-pixel Surface. This includes optional
     * RAW preview/prearm routes: Camera2PipelineRequestPolicy deliberately targets them in the repeating
     * request. A retained P010/YUV standby Surface must never be attached beside that RAW Surface because
     * affected Qualcomm CHI providers can accept the graph and then abort asynchronously.
     */
    val rawSensorSurfaceActive: Boolean
        get() = rawSensor.includeInSession
}

internal object CinemaStreamGraphPolicy {

    fun resolve(
        request: CinemaStreamGraphRequest,
        inventory: CinemaStreamGraphInventory
    ): CinemaStreamGraphPlan {
        val encoderSize = selectEncoderSize(
            preferred = request.recordingEncoderTargetSize,
            sizes = inventory.encoderOutputSizes
        )
        val jpegSize = largest(inventory.jpegOutputSizes) ?: inventory.fallbackPhotoSize
        val rawSize = if (inventory.supportsRaw) {
            selectRawSize(request, inventory)
        } else {
            null
        }
        val yuvSize = selectYuvSize(request, inventory)
        // Match the internal analysis stream aspect to the displayed preview aspect so scene samples
        // correspond to the same field of view.
        val analysisAspect = request.analysisTargetSize
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }
        val previewAnalysisSize = selectPreviewAnalysisSize(inventory, analysisAspect)
        // A mode-switch standby reader can also be the live preview-analysis stream. In that case
        // analysis intent owns its dimensions: do not let the generic standby branch restore the
        // full recording readout and consume 4K P010 bandwidth after recording stops.
        val modeSwitchAnalysisSize = if (request.previewAnalysisRequested) {
            previewAnalysisSize
        } else {
            yuvSize
        }

        val videoPreview = request.videoPreviewActive && !request.recordingActive
        val rawOriginalRecording = request.recordingActive && request.rawVideoCaptureEnabled
        val rawOriginalPrearm = videoPreview && request.rawVideoPrearmEnabled
        val sensorLinearNdr = request.sensorLinearNdrCaptureEnabled
        val sensorLinearNdrRecording = request.recordingActive && sensorLinearNdr
        val encodedRecording =
            request.recordingActive && !request.rawVideoCaptureEnabled && !sensorLinearNdr
        val uncompressedReadoutRequested =
            request.uncompressedReadoutRecording && !request.rawVideoCaptureEnabled
        val uncompressedReadoutRecording = request.recordingActive && uncompressedReadoutRequested
        val uncompressedReadoutPrearm = videoPreview && uncompressedReadoutRequested
        val safeRecordingFallback = request.recordingActive && request.recordingSafeSessionFallback
        val internalEncoderFeed =
            request.hardwareImportRequested &&
                request.internalEncoderHardwareImportActive &&
                !request.rawVideoCaptureEnabled &&
                !sensorLinearNdr

        val rawSensor = when {
            sensorLinearNdr && rawSize != null -> route(
                requirement = CinemaStreamRequirement.REQUIRED,
                size = rawSize,
                createReader = true,
                includeInSession = true,
                reason = if (sensorLinearNdrRecording) {
                    "Sensor-linear NDR RAW_SENSOR recording feed"
                } else {
                    "Sensor-linear NDR RAW_SENSOR preview feed"
                }
            )
            (rawOriginalRecording || rawOriginalPrearm) && rawSize != null -> route(
                requirement = if (rawOriginalRecording) {
                    CinemaStreamRequirement.REQUIRED
                } else {
                    CinemaStreamRequirement.OPTIONAL
                },
                size = rawSize,
                createReader = true,
                includeInSession = true,
                reason = if (rawOriginalRecording) {
                    "RAW_SENSOR original recording path"
                } else {
                    "Idle full-depth RAW recording prearm"
                }
            )
            encodedRecording -> omitted("RAW still companion omitted during encoded recording")
            videoPreview && request.rawVideoPreviewActive && rawSize != null -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = rawSize,
                createReader = true,
                includeInSession = true,
                reason = "RAW cinema-preview debayer feed"
            )
            videoPreview && request.modeSwitchRawStandbyRequested && rawSize != null -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = rawSize,
                createReader = true,
                includeInSession = !request.modeSwitchAnalysisStandbyRequested,
                reason = if (request.modeSwitchAnalysisStandbyRequested) {
                    "$MODE_SWITCH_RAW_STANDBY_REASON (reader retained; P010/YUV surface active)"
                } else {
                    MODE_SWITCH_RAW_STANDBY_REASON
                }
            )
            videoPreview -> omitted("RAW still companion omitted during video preview to match recording stream graph")
            rawSize != null && !safeRecordingFallback -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = rawSize,
                createReader = true,
                includeInSession = true,
                reason = "RAW still companion path"
            )
            rawSize != null -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = rawSize,
                createReader = true,
                includeInSession = false,
                reason = "RAW still companion omitted from recording-safe session"
            )
            else -> omitted("RAW_SENSOR unavailable")
        }

        val yuvAnalysis = when {
            yuvSize == null -> omitted("YUV_420_888 unavailable")
            // Sensor-linear NDR is sourced exclusively from required RAW_SENSOR frames. Evaluate this
            // before every generic readout branch so stale NPV10 state can never recreate P010 beside it.
            sensorLinearNdr -> omitted("Sensor-linear NDR owns RAW_SENSOR readout; vendor YUV disabled")
            rawSensor.includeInSession && request.modeSwitchAnalysisStandbyRequested -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = modeSwitchAnalysisSize,
                createReader = true,
                // Retain the allocated reader for the next processed-mode session, but never attach
                // its Surface beside any live RAW_SENSOR route. Optional RAW preview/prearm routes are
                // repeating-request targets too, so requirement level cannot be used as the safety gate.
                includeInSession = false,
                reason = "$MODE_SWITCH_YUV_STANDBY_REASON (reader retained; RAW surface active)"
            )
            rawOriginalRecording -> omitted("RAW_SENSOR recording owns original readout; YUV sidecar disabled")
            rawOriginalPrearm -> omitted("Prearmed RAW_SENSOR output replaces idle P010/YUV readout")
            uncompressedReadoutRecording -> route(
                requirement = CinemaStreamRequirement.REQUIRED,
                size = yuvSize,
                createReader = true,
                includeInSession = !safeRecordingFallback,
                reason = "Custom packed-10 NPV10 recording requires a native P010 readout stream"
            )
            uncompressedReadoutPrearm -> route(
                requirement = CinemaStreamRequirement.REQUIRED,
                size = yuvSize,
                createReader = true,
                includeInSession = true,
                reason = "Idle native P010 packet-capture prearm"
            )
            encodedRecording && request.optionalAnalysisSuspendedForRecording -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = previewAnalysisSize ?: yuvSize,
                createReader = true,
                includeInSession = false,
                reason = "Optional analysis reader retained outside thermally isolated recording session"
            )
            videoPreview && request.modeSwitchAnalysisStandbyRequested -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = modeSwitchAnalysisSize,
                createReader = true,
                includeInSession = true,
                reason = MODE_SWITCH_YUV_STANDBY_REASON
            )
            encodedRecording && request.previewAnalysisRequested &&
                previewAnalysisSize != null && !safeRecordingFallback -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = previewAnalysisSize,
                createReader = true,
                includeInSession = true,
                reason = "NDR exposure/highlight analysis feed during encoded recording"
            )
            // Outside recording, attach a small downscaled YUV stream only when internal scene
            // analysis is requested. The default preview graph remains unchanged otherwise.
            !request.recordingActive && request.previewAnalysisRequested && previewAnalysisSize != null -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = previewAnalysisSize,
                createReader = true,
                includeInSession = true,
                reason = "NDR preview exposure/highlight analysis feed"
            )
            encodedRecording -> route(
                requirement = CinemaStreamRequirement.OMITTED,
                size = null,
                createReader = false,
                includeInSession = false,
                reason = "Encoded recording keeps preview and encoder only to preserve HAL video ISP quality"
            )
            videoPreview -> route(
                requirement = CinemaStreamRequirement.OMITTED,
                size = null,
                createReader = false,
                includeInSession = false,
                reason = "Video preview keeps the recording-safe preview-only graph"
            )
            request.readoutSidecarMode == CameraReadoutSidecarMode.DISABLED -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = yuvSize,
                createReader = true,
                includeInSession = !safeRecordingFallback && !request.recordingActive,
                reason = "YUV reader kept for preview analysis; recording sidecar disabled"
            )
            safeRecordingFallback -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = yuvSize,
                createReader = true,
                includeInSession = false,
                reason = "YUV analysis omitted from recording-safe session"
            )
            else -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = yuvSize,
                createReader = true,
                includeInSession = true,
                reason = "YUV_420_888 preview analysis path"
            )
        }

        // The maximum-size JPEG route is a first-class output in every camera mode. Keeping it attached
        // before recording starts enables Camera2 video snapshots without rebuilding or sampling the
        // lower-resolution preview. It remains required through the recording-safe graph: saved-photo
        // fidelity and availability are part of the capture contract, never an analysis-sidecar fallback.
        val jpegStill = route(
            requirement = CinemaStreamRequirement.REQUIRED,
            size = jpegSize,
            createReader = true,
            includeInSession = true,
            reason = when {
                request.recordingActive -> "Maximum-resolution JPEG video-snapshot path"
                videoPreview -> "Preconfigured maximum-resolution JPEG video-snapshot path"
                safeRecordingFallback -> "Required maximum-resolution JPEG still path"
                else -> "Maximum-resolution JPEG original still path"
            }
        )

        val encoder = when {
            sensorLinearNdrRecording -> route(
                requirement = CinemaStreamRequirement.REQUIRED,
                size = encoderSize,
                createReader = false,
                includeInSession = false,
                reason = "NDR MediaCodec surface is fed only by offscreen FP16 EGL, not Camera2"
            )
            request.recordingActive && encoderSize != null -> route(
                requirement = CinemaStreamRequirement.REQUIRED,
                size = encoderSize,
                createReader = false,
                includeInSession = true,
                reason = "Encoder delivery surface is required for active recording"
            )
            encoderSize != null -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = encoderSize,
                createReader = false,
                includeInSession = false,
                reason = "Encoder size catalogued for future recording"
            )
            request.recordingActive -> route(
                requirement = CinemaStreamRequirement.REQUIRED,
                size = null,
                createReader = false,
                includeInSession = false,
                reason = "Recording requested but encoder size unavailable"
            )
            else -> omitted("Encoder inactive")
        }

        val hardwareImport = route(
            requirement = if (request.hardwareImportRequested && !sensorLinearNdr) {
                CinemaStreamRequirement.OPTIONAL
            } else {
                CinemaStreamRequirement.OMITTED
            },
            size = null,
            createReader = request.hardwareImportRequested && !sensorLinearNdr &&
                ((internalEncoderFeed && !safeRecordingFallback) || (!request.recordingActive && !videoPreview)),
            includeInSession = request.hardwareImportRequested && !sensorLinearNdr &&
                ((internalEncoderFeed && !safeRecordingFallback) || (!request.recordingActive && !videoPreview)),
            reason = if (sensorLinearNdr) {
                "Sensor-linear NDR imports the Vulkan FP16 export directly"
            } else if (request.hardwareImportRequested) {
                if (internalEncoderFeed && !safeRecordingFallback) {
                    "Hidden hardware import feeds internal encoder while visible preview stays direct"
                } else if (request.recordingActive) {
                    "Hardware import omitted during recording to keep one Camera2 preview+encoder path"
                } else if (videoPreview) {
                    "Hardware import omitted during video preview to match recording stream graph"
                } else {
                    "Optional GPU hardware import stream"
                }
            } else {
                "Hardware import not requested"
            }
        )

        val mode = when {
            sensorLinearNdrRecording && rawSensor.includeInSession ->
                CinemaStreamGraphMode.SENSOR_LINEAR_NDR_RECORDING
            sensorLinearNdr && rawSensor.includeInSession ->
                CinemaStreamGraphMode.SENSOR_LINEAR_NDR_PREVIEW
            rawOriginalRecording && rawSensor.includeInSession -> CinemaStreamGraphMode.RAW_ORIGINAL_RECORDING
            uncompressedReadoutRecording && yuvAnalysis.includeInSession ->
                CinemaStreamGraphMode.ENCODED_CINEMA_WITH_YUV_READOUT
            uncompressedReadoutRecording -> CinemaStreamGraphMode.UNSUPPORTED
            request.recordingActive && encoder.includeInSession -> CinemaStreamGraphMode.ENCODED_CINEMA
            videoPreview -> CinemaStreamGraphMode.VIDEO_PREVIEW_READY
            !request.recordingActive -> CinemaStreamGraphMode.PREVIEW_STILL_READY
            else -> CinemaStreamGraphMode.UNSUPPORTED
        }

        return CinemaStreamGraphPlan(
            mode = mode,
            rawSensor = rawSensor,
            yuvAnalysis = yuvAnalysis,
            jpegStill = jpegStill,
            encoder = encoder,
            hardwareImport = hardwareImport,
            fallbackDropOrder = resolveFallbackDropOrder(request),
            diagnostics = buildDiagnostics(mode, rawSensor, yuvAnalysis, jpegStill, encoder, hardwareImport)
        )
    }

    private fun selectRawSize(
        request: CinemaStreamGraphRequest,
        inventory: CinemaStreamGraphInventory
    ): CameraStreamSize? {
        val fallback = largest(inventory.rawOutputSizes) ?: return null
        if (request.sensorLinearNdrCaptureEnabled) {
            // NDR owns a sensor-linear master. Delivery aspect is a downstream GPU crop and must not
            // retarget the Camera2 RAW producer to an advertised-but-provider-unstable cropped size.
            return SensorLinearNdrRawSourceSizePolicy.resolve(
                activeArrayRawSize = inventory.activeArrayRawSize,
                supportedRawSizes = inventory.rawOutputSizes,
                fallback = fallback
            )
        }
        return CameraReadoutTargetSizePolicy.resolveRawReaderStreamSize(
            recordingReadoutOverrideSize = request.recordingReadoutOverrideSize,
            supportedRawSizes = inventory.rawOutputSizes,
            fallback = fallback
        )
    }

    private fun selectYuvSize(
        request: CinemaStreamGraphRequest,
        inventory: CinemaStreamGraphInventory
    ): CameraStreamSize? {
        val fallback = largest(inventory.yuvOutputSizes) ?: return null
        if (request.modeSwitchAnalysisStandbyRequested) {
            // The standby reader must keep one buffer queue across processed and RAW modes. RAW's
            // sensor override and encoder-sidecar preference describe other routes and must never
            // resize this P010 output during a mode switch or RAW start/stop retarget.
            return CameraReadoutTargetSizePolicy.resolveYuvReaderStreamSize(
                analysisTargetSize = request.analysisTargetSize,
                recordingReadoutOverrideSize = null,
                recordingEncoderTargetSize = null,
                readoutSidecarMode = CameraReadoutSidecarMode.DISABLED,
                supportedYuvSizes = inventory.yuvOutputSizes,
                fallback = fallback
            )
        }
        return CameraReadoutTargetSizePolicy.resolveYuvReaderStreamSize(
            analysisTargetSize = request.analysisTargetSize,
            recordingReadoutOverrideSize = request.recordingReadoutOverrideSize,
            recordingEncoderTargetSize = request.recordingEncoderTargetSize,
            readoutSidecarMode = request.readoutSidecarMode,
            supportedYuvSizes = inventory.yuvOutputSizes,
            fallback = fallback
        )
    }

    // Long-edge cap for the gated NDR preview-analysis stream. A ~720p readout is ample for internal
    // exposure statistics while keeping the extra stream cheap on copy-bound devices.
    private const val PREVIEW_ANALYSIS_MAX_LONG_EDGE = 1280

    private fun selectPreviewAnalysisSize(
        inventory: CinemaStreamGraphInventory,
        targetAspect: Float? = null
    ): CameraStreamSize? {
        val supported = inventory.yuvOutputSizes.filter { it.width > 0 && it.height > 0 }
        if (supported.isEmpty()) return null
        val capped = supported.filter { maxOf(it.width, it.height) <= PREVIEW_ANALYSIS_MAX_LONG_EDGE }
        val pool = capped.ifEmpty { listOf(supported.minByOrNull { it.area }!!) }
        if (targetAspect == null || targetAspect <= 0f) {
            return pool.maxByOrNull { it.area }
        }
        // Prefer sizes whose aspect matches the displayed preview (orientation-agnostic), so the
        // stretched-to-preview mask aligns; fall back to the largest capped size when none match.
        val epsilon = 0.05f
        val aspectMatched = pool.filter {
            val ratio = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
            val targetRatio = if (targetAspect >= 1f) targetAspect else 1f / targetAspect
            kotlin.math.abs(ratio - targetRatio) <= epsilon
        }
        return (aspectMatched.ifEmpty { pool }).maxByOrNull { it.area }
    }

    private fun selectEncoderSize(
        preferred: CameraStreamSize?,
        sizes: List<CameraStreamSize>
    ): CameraStreamSize? =
        when {
            preferred != null && sizes.any { it.sameDimensions(preferred) } -> preferred
            preferred != null -> {
                val normalizedPreferred = preferred.normalized()
                sizes
                    .filter { size ->
                        val normalized = size.normalized()
                        normalized.width <= normalizedPreferred.width &&
                            normalized.height <= normalizedPreferred.height
                    }
                    .maxWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })
                    ?: sizes.minWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })
            }
            else -> largest(sizes)
        }

    private fun resolveFallbackDropOrder(
        request: CinemaStreamGraphRequest
    ): List<CinemaStreamFallbackStep> =
        buildList {
            if (request.hardwareImportRequested && !request.recordingActive && !request.videoPreviewActive) {
                add(CinemaStreamFallbackStep.HARDWARE_IMPORT)
            }
            if (request.recordingActive &&
                (request.rawVideoCaptureEnabled || request.previewAnalysisRequested)) {
                add(CinemaStreamFallbackStep.YUV_ANALYSIS)
            }
            add(CinemaStreamFallbackStep.STREAM_USE_CASE_HINTS)
        }.distinct()

    private fun buildDiagnostics(
        mode: CinemaStreamGraphMode,
        vararg routes: CinemaStreamRoute
    ): List<String> =
        buildList {
            add("Cinema stream graph mode=$mode")
            routes.forEach { route ->
                add("${route.requirement} ${route.size?.formatStep("size") ?: "no-size"} session=${route.includeInSession}: ${route.reason}")
            }
        }

    private fun route(
        requirement: CinemaStreamRequirement,
        size: CameraStreamSize?,
        createReader: Boolean,
        includeInSession: Boolean,
        reason: String
    ): CinemaStreamRoute =
        CinemaStreamRoute(
            requirement = requirement,
            size = size,
            createReader = createReader,
            includeInSession = includeInSession,
            reason = reason
        )

    private fun omitted(reason: String): CinemaStreamRoute =
        route(
            requirement = CinemaStreamRequirement.OMITTED,
            size = null,
            createReader = false,
            includeInSession = false,
            reason = reason
        )

    private fun largest(sizes: List<CameraStreamSize>): CameraStreamSize? =
        sizes.maxWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })

    private fun CameraStreamSize.sameDimensions(other: CameraStreamSize): Boolean =
        (width == other.width && height == other.height) ||
            (width == other.height && height == other.width)

    private fun CameraStreamSize.normalized(): CameraStreamSize =
        if (width >= height) this else CameraStreamSize(height, width)
}
