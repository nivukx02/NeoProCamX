package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.RawVideoContractPolicy
import com.neoproc.neoproccamx.recording.RecordingContainerSelectionPolicy
import com.neoproc.neoproccamx.recording.VideoRecordingContractPolicy

internal enum class RecordingDeviceColorMetadataFamily {
    REC709_SDR,
    BT2020_HLG,
    BT2020_PQ,
    REC2020_LOG,
    RAW_SENSOR
}

internal data class RecordingDeviceMatrixTarget(
    val width: Int,
    val height: Int,
    val fps: Int
) {
    val dimensionLabel: String = "${width}x$height"
    val label: String = "${width}x${height}_${fps}fps"
    val previewAspectRatio: PreviewAspectRatio = when {
        width == height -> PreviewAspectRatio.SQUARE
        width.toLong() * 9L == height.toLong() * 16L -> PreviewAspectRatio.SIXTEEN_NINE
        width.toLong() * 3L == height.toLong() * 4L -> PreviewAspectRatio.FOUR_THREE
        else -> PreviewAspectRatio.AUTO
    }
    val aspectToken: String = previewAspectRatio.label
}

internal data class RecordingDeviceMatrixCoverage(
    val deliveryKinds: Set<VideoDeliveryKind>,
    val colorFamilies: Set<RecordingDeviceColorMetadataFamily>,
    val targetLabels: Set<String>
)

internal data class RecordingDeviceMatrixPlan(
    val rows: List<RecordingDeviceMatrixRow>,
    val coverage: RecordingDeviceMatrixCoverage
)

internal data class RecordingDeviceMatrixRow(
    val id: String,
    val displayName: String,
    val preset: CameraModePreset,
    val appMode: AppMode,
    val cinemaMode: CinemaVideoMode,
    val target: RecordingDeviceMatrixTarget,
    val requestedHdrMode: String,
    val requestedColorSpace: String,
    val logEnabled: Boolean,
    val rawEnabled: Boolean,
    val colorProfile: String,
    val expectedDeliveryKind: VideoDeliveryKind,
    val expectedBitDepth: Int,
    val expectedContainerExtension: String,
    val expectedMediaStoreMime: String,
    val colorMetadataFamily: RecordingDeviceColorMetadataFamily,
    val previewRecordingParityRequired: Boolean,
    val graphContract: VideoPipelineContract,
    val parityPlan: RecordingPipelineParityPlan,
    val validationLabel: String,
    val debugExtras: DebugCommandExtras
)

internal object RecordingDeviceMatrixPolicy {
    private const val DEBUG_ACTION_RECORD_CLIP = "record_clip"
    private const val MIN_DURATION_MS = 400L
    private const val MAX_DURATION_MS = 120_000L
    private const val PROCESSED_VIDEO_BIT_DEPTH = 10

    private val processedTargets = listOf(
        RecordingDeviceMatrixTarget(width = 1920, height = 1080, fps = 25),
        RecordingDeviceMatrixTarget(width = 3840, height = 2160, fps = 25),
        RecordingDeviceMatrixTarget(width = 1920, height = 1080, fps = 60)
    )

    private val rawTargets = listOf(
        RecordingDeviceMatrixTarget(width = 1920, height = 1080, fps = 24)
    )

    fun resolveDefaultPlan(
        durationMs: Long = DebugCommandParser.DEFAULT_DEBUG_DURATION_MS,
        validationNamespace: String = "phase5"
    ): RecordingDeviceMatrixPlan {
        val safeDurationMs = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        val safeValidationNamespace = validationNamespace
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .ifEmpty { "phase5" }
        val rows = buildList {
            processedProfiles.forEach { profile ->
                processedTargets.forEach { target ->
                    add(
                        resolveRow(
                            profile = profile,
                            target = target,
                            durationMs = safeDurationMs,
                            validationNamespace = safeValidationNamespace
                        )
                    )
                }
            }
            rawProfiles.forEach { profile ->
                rawTargets.forEach { target ->
                    add(
                        resolveRow(
                            profile = profile,
                            target = target,
                            durationMs = safeDurationMs,
                            validationNamespace = safeValidationNamespace
                        )
                    )
                }
            }
        }
        return RecordingDeviceMatrixPlan(
            rows = rows,
            coverage = RecordingDeviceMatrixCoverage(
                deliveryKinds = rows.mapTo(linkedSetOf()) { it.expectedDeliveryKind },
                colorFamilies = rows.mapTo(linkedSetOf()) { it.colorMetadataFamily },
                targetLabels = rows.mapTo(linkedSetOf()) { it.target.label }
            )
        )
    }

    private fun resolveRow(
        profile: RecordingDeviceMatrixProfile,
        target: RecordingDeviceMatrixTarget,
        durationMs: Long,
        validationNamespace: String
    ): RecordingDeviceMatrixRow {
        val tenBitRequested = profile.expectedDeliveryKind != VideoDeliveryKind.RAW &&
            VideoRecordingContractPolicy.resolveTenBitRecordingRequested(
                cinemaMode = profile.cinemaMode,
                normalizedHdrMode = profile.requestedHdrMode
            )
        val graphContract = VideoPipelineGraphController.resolve(
            VideoPipelineGraphRequest(
                appMode = AppMode.VIDEO,
                cinemaMode = profile.cinemaMode,
                rawVideoActive = profile.rawEnabled,
                logEnabled = profile.logEnabled,
                requestedHdrMode = profile.requestedHdrMode,
                preferredColorSpace = profile.requestedColorSpace,
                tenBitRequested = tenBitRequested,
                previewWidth = 1920,
                previewHeight = 1080,
                encoderWidth = target.width,
                encoderHeight = target.height,
                fps = target.fps,
                recordingState = GpuRecordingState.Idle,
                displayMonitoringLabel = profile.id
            )
        )
        require(graphContract.deliveryKind == profile.expectedDeliveryKind) {
            "Recording matrix row ${profile.id} resolved ${graphContract.deliveryKind}, expected ${profile.expectedDeliveryKind}"
        }

        val parityPlan = RecordingPipelineParityPolicy.resolve(
            graphContract = graphContract,
            rawSupported = true,
            vulkanRuntimeSupported = true
        )
        val containerSelection = RecordingContainerSelectionPolicy.resolve(
            cinemaMode = graphContract.cinemaMode,
            rawVideoEnabled = graphContract.rawEnabled,
            logVideoEnabled = graphContract.logEnabled,
            hdrMode = graphContract.effectiveHdrMode
        )
        val validationLabel = "${validationNamespace}_${profile.id}_${target.label}"
        val expectedBitDepth = if (profile.expectedDeliveryKind == VideoDeliveryKind.RAW) {
            RawVideoContractPolicy.UNCOMPRESSED_RAW_BIT_DEPTH
        } else {
            PROCESSED_VIDEO_BIT_DEPTH
        }

        return RecordingDeviceMatrixRow(
            id = "${profile.id}_${target.label}",
            displayName = "${profile.displayName} ${target.width}x${target.height} ${target.fps}fps",
            preset = profile.preset,
            appMode = AppMode.VIDEO,
            cinemaMode = graphContract.cinemaMode,
            target = target,
            requestedHdrMode = profile.requestedHdrMode,
            requestedColorSpace = profile.requestedColorSpace,
            logEnabled = graphContract.logEnabled,
            rawEnabled = graphContract.rawEnabled,
            colorProfile = profile.colorProfile,
            expectedDeliveryKind = graphContract.deliveryKind,
            expectedBitDepth = expectedBitDepth,
            expectedContainerExtension = containerSelection.fileExtension,
            expectedMediaStoreMime = containerSelection.mediaStoreMime,
            colorMetadataFamily = profile.colorMetadataFamily,
            previewRecordingParityRequired = graphContract.deliveryKind != VideoDeliveryKind.RAW,
            graphContract = graphContract,
            parityPlan = parityPlan,
            validationLabel = validationLabel,
            debugExtras = DebugCommandExtras(
                mode = AppMode.VIDEO.name,
                preset = profile.preset.name,
                action = DEBUG_ACTION_RECORD_CLIP,
                durationMs = durationMs.toString(),
                raw = graphContract.rawEnabled,
                log = graphContract.logEnabled,
                aspect = target.aspectToken,
                colorProfile = profile.colorProfile,
                validationLabel = validationLabel,
                videoColorSpace = profile.requestedColorSpace,
                videoHdrMode = graphContract.effectiveHdrMode,
                videoResolution = target.dimensionLabel,
                videoFps = target.fps.toString(),
                videoMode = graphContract.cinemaMode.name,
                rawCompression = RawVideoContractPolicy.UNCOMPRESSED_12_BIT_MODE.name
                    .takeIf { graphContract.rawEnabled }
            )
        )
    }

    private data class RecordingDeviceMatrixProfile(
        val id: String,
        val displayName: String,
        val preset: CameraModePreset,
        val cinemaMode: CinemaVideoMode,
        val requestedHdrMode: String,
        val requestedColorSpace: String,
        val logEnabled: Boolean,
        val rawEnabled: Boolean,
        val colorProfile: String,
        val expectedDeliveryKind: VideoDeliveryKind,
        val colorMetadataFamily: RecordingDeviceColorMetadataFamily
    )

    private val processedProfiles = listOf(
        RecordingDeviceMatrixProfile(
            id = "video_sdr",
            displayName = "Normal SDR Video",
            preset = CameraModePreset.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            requestedColorSpace = "Rec.709",
            logEnabled = false,
            rawEnabled = false,
            colorProfile = "Natural",
            expectedDeliveryKind = VideoDeliveryKind.SDR,
            colorMetadataFamily = RecordingDeviceColorMetadataFamily.REC709_SDR
        ),
        RecordingDeviceMatrixProfile(
            id = "pro_sdr",
            displayName = "Pro SDR Video",
            preset = CameraModePreset.PRO,
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            requestedColorSpace = "Rec.709",
            logEnabled = false,
            rawEnabled = false,
            colorProfile = "Natural",
            expectedDeliveryKind = VideoDeliveryKind.SDR,
            colorMetadataFamily = RecordingDeviceColorMetadataFamily.REC709_SDR
        ),
        RecordingDeviceMatrixProfile(
            id = "hdr_hlg",
            displayName = "HDR HLG Video",
            preset = CameraModePreset.HDR,
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "HLG",
            requestedColorSpace = "Rec.2020",
            logEnabled = false,
            rawEnabled = false,
            colorProfile = "Natural",
            expectedDeliveryKind = VideoDeliveryKind.HDR,
            colorMetadataFamily = RecordingDeviceColorMetadataFamily.BT2020_HLG
        ),
        RecordingDeviceMatrixProfile(
            id = "ndr10_plus",
            displayName = "NDR10+ Video",
            preset = CameraModePreset.HDR,
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            requestedColorSpace = "Rec.2020-NDR10+",
            logEnabled = false,
            rawEnabled = false,
            colorProfile = "NDR10+",
            expectedDeliveryKind = VideoDeliveryKind.HDR,
            // NDR10+/NIVUKX is NeoProCamX's own genuine BT.2100 PQ (ST.2084) delivery: the record
            // shader converts the captured HLG10 to PQ and the file is tagged ST.2084 (Fork A,
            // NeoNdrTruePqTransportPolicy). Its recorded-file family is BT2020_PQ. The plain HDR HLG
            // mode above remains BT2020_HLG, so the matrix covers both BT.2100 transports.
            colorMetadataFamily = RecordingDeviceColorMetadataFamily.BT2020_PQ
        ),
        RecordingDeviceMatrixProfile(
            id = "log_nlog_base",
            displayName = "N-Log Base Video",
            preset = CameraModePreset.LOG_VIDEO,
            cinemaMode = CinemaVideoMode.LOG,
            requestedHdrMode = "OFF",
            requestedColorSpace = "Rec.2020",
            logEnabled = true,
            rawEnabled = false,
            colorProfile = "N-Log Base",
            expectedDeliveryKind = VideoDeliveryKind.LOG,
            colorMetadataFamily = RecordingDeviceColorMetadataFamily.REC2020_LOG
        ),
        RecordingDeviceMatrixProfile(
            id = "log_nlog_cinema",
            displayName = "N-Log Cinema Video",
            preset = CameraModePreset.LOG_VIDEO,
            cinemaMode = CinemaVideoMode.LOG,
            requestedHdrMode = "OFF",
            requestedColorSpace = "Rec.2020",
            logEnabled = true,
            rawEnabled = false,
            colorProfile = "N-Log Cinema",
            expectedDeliveryKind = VideoDeliveryKind.LOG,
            colorMetadataFamily = RecordingDeviceColorMetadataFamily.REC2020_LOG
        )
    )

    private val rawProfiles = listOf(
        RecordingDeviceMatrixProfile(
            id = "raw_12bit",
            displayName = "RAW Sensor 12-bit Video",
            preset = CameraModePreset.RAW_MODE,
            cinemaMode = CinemaVideoMode.RAW,
            requestedHdrMode = "OFF",
            requestedColorSpace = "RAW",
            logEnabled = false,
            rawEnabled = true,
            colorProfile = "Natural",
            expectedDeliveryKind = VideoDeliveryKind.RAW,
            colorMetadataFamily = RecordingDeviceColorMetadataFamily.RAW_SENSOR
        )
    )
}
