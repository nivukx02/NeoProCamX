package com.neoproc.neoproccamx.camera

internal enum class CinemaStreamGraphMode {
    PREVIEW_STILL_READY,
    VIDEO_PREVIEW_READY,
    ENCODED_CINEMA,
    ENCODED_CINEMA_WITH_YUV_READOUT,
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
    val fallbackPhotoSize: CameraStreamSize
)

internal data class CinemaStreamGraphRequest(
    val recordingActive: Boolean,
    val videoPreviewActive: Boolean = false,
    val uncompressedReadoutRecording: Boolean = false,
    val rawVideoCaptureEnabled: Boolean,
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
    // A small CPU-analysis YUV stream is requested for HUD monitors or the proprietary NDR closed-loop
    // exposure/highlight meter. It remains OPTIONAL and is the first stream removed by the recording-safe
    // fallback, so unsupported output combinations never reduce encoder resolution, depth, or cadence.
    val previewAnalysisRequested: Boolean = false
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
)

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
        // Match the analysis stream aspect to the displayed preview aspect (from the VM's
        // aspect-matched analysisTargetSize) so the focus-peaking / zebra masks, which are stretched
        // onto the preview rect, land on the correct pixels instead of a mismatched-FOV frame.
        val analysisAspect = request.analysisTargetSize
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }
        val previewAnalysisSize = selectPreviewAnalysisSize(inventory, analysisAspect)

        val rawOriginalRecording = request.recordingActive && request.rawVideoCaptureEnabled
        val encodedRecording = request.recordingActive && !request.rawVideoCaptureEnabled
        val videoPreview = request.videoPreviewActive && !request.recordingActive
        val uncompressedReadoutRequested =
            request.uncompressedReadoutRecording && !request.rawVideoCaptureEnabled
        val uncompressedReadoutRecording = encodedRecording && uncompressedReadoutRequested
        val uncompressedReadoutPrearm = videoPreview && uncompressedReadoutRequested
        val safeRecordingFallback = request.recordingActive && request.recordingSafeSessionFallback
        val internalEncoderFeed =
            request.hardwareImportRequested &&
                request.internalEncoderHardwareImportActive &&
                !request.rawVideoCaptureEnabled

        val rawSensor = when {
            rawOriginalRecording && rawSize != null -> route(
                requirement = CinemaStreamRequirement.REQUIRED,
                size = rawSize,
                createReader = true,
                includeInSession = true,
                reason = "RAW_SENSOR original recording path"
            )
            encodedRecording -> omitted("RAW still companion omitted during encoded recording")
            videoPreview && request.rawVideoPreviewActive && rawSize != null -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = rawSize,
                createReader = true,
                includeInSession = true,
                reason = "RAW cinema-preview debayer feed"
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
            rawOriginalRecording -> omitted("RAW_SENSOR recording owns original readout; YUV sidecar disabled")
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
            encodedRecording && request.previewAnalysisRequested &&
                previewAnalysisSize != null && !safeRecordingFallback -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = previewAnalysisSize,
                createReader = true,
                includeInSession = true,
                reason = "NDR exposure/highlight analysis feed during encoded recording"
            )
            // Gated HUD monitor feed: outside recording, when a monitor is enabled, attach a small
            // downscaled YUV analysis stream so the CPU pipeline can compute histogram / waveform /
            // focus-peaking / zebra. Takes precedence over the videoPreview/encoded omissions below,
            // which only apply when this feed is NOT requested (so the default graph is unchanged).
            !request.recordingActive && request.previewAnalysisRequested && previewAnalysisSize != null -> route(
                requirement = CinemaStreamRequirement.OPTIONAL,
                size = previewAnalysisSize,
                createReader = true,
                includeInSession = true,
                reason = "HUD monitor analysis feed (histogram / waveform / focus-peaking / zebra)"
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
            requirement = if (request.hardwareImportRequested) {
                CinemaStreamRequirement.OPTIONAL
            } else {
                CinemaStreamRequirement.OMITTED
            },
            size = null,
            createReader = request.hardwareImportRequested &&
                ((internalEncoderFeed && !safeRecordingFallback) || (!request.recordingActive && !videoPreview)),
            includeInSession = request.hardwareImportRequested &&
                ((internalEncoderFeed && !safeRecordingFallback) || (!request.recordingActive && !videoPreview)),
            reason = if (request.hardwareImportRequested) {
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
        return CameraReadoutTargetSizePolicy.resolveYuvReaderStreamSize(
            analysisTargetSize = request.analysisTargetSize,
            recordingReadoutOverrideSize = request.recordingReadoutOverrideSize,
            recordingEncoderTargetSize = request.recordingEncoderTargetSize,
            readoutSidecarMode = request.readoutSidecarMode,
            supportedYuvSizes = inventory.yuvOutputSizes,
            fallback = fallback
        )
    }

    // Long-edge cap for the gated preview HUD-monitor analysis stream. The CPU monitors downsample
    // the luma plane heavily (stride sampling, 64-column waveform, small mask grids), so a ~720p
    // readout is ample while keeping the extra preview stream cheap on copy-bound devices.
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
