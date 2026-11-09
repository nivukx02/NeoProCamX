package com.neoproc.neoproccamx.ui

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.ManualVideoColorSelectionPolicy
import com.neoproc.neoproccamx.recording.NeoNdrTruePqTransportPolicy
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy
import kotlin.math.roundToLong

internal enum class VideoPipelineDeliveryKind {
    VIDEO_SDR,
    PRO_SDR,
    HDR_HLG,
    HDR_PQ,
    LOG_10BIT,
    RAW_SENSOR
}

internal enum class VideoPipelinePreviewKind {
    SDR,
    HDR_10BIT,
    LOG_10BIT,
    RAW_SENSOR
}

internal enum class VideoPipelineToneMap {
    REC709_NEUTRAL,
    WIDE_GAMUT_SDR,
    BT2100_HLG,
    BT2100_PQ,
    LOG_ORIGINAL,
    RAW_LINEAR_MONITOR
}

internal enum class VideoSensorPipelineStage {
    SENSOR_RAW,
    DEMOSAIC,
    AUTO_WHITE_BALANCE,
    COLOR_CORRECTION_MATRIX,
    NOISE_REDUCTION,
    HDR_MERGE,
    TONE_MAPPING,
    GAMMA_CONVERSION,
    OUTPUT
}

internal enum class VideoDynamicRangeProfile {
    SDR,
    HLG10,
    HDR10,
    HDR10_PLUS
}

internal enum class VideoPipelineAdmissionState {
    PENDING,
    ADMITTED,
    REJECTED
}

internal enum class VideoPipelineAdmissionFailure {
    RAW_CAPTURE_UNAVAILABLE,
    RAW_CAPTURE_ROUTE_INACTIVE,
    TEN_BIT_TRANSPORT_UNAVAILABLE,
    HDR_TRANSPORT_UNAVAILABLE,
    WIDE_GAMUT_SDR_UNAVAILABLE
}

internal enum class VideoPipelineParityClassification {
    MATCHED_BEFORE_OUTPUT_TRANSFORM,
    RAW_SENSOR_ORIGINAL_TO_DISPLAY_MONITOR_SPLICE,
    PROCESSED_ROUTE_DIVERGED_BEFORE_OUTPUT_TRANSFORM
}

internal enum class RawMasterFidelity {
    NOT_APPLICABLE,
    SENSOR_CODE_EXACT_LINEAR
}

internal enum class RawStorageSelectionPolicy {
    NOT_APPLICABLE,
    SENSOR_WHITE_LEVEL_EXACT
}

/**
 * Semantic evidence for the point where recording and preview pipelines separate.
 *
 * A RAW master must remain sensor-original while its monitor branch is demosaiced and transformed
 * into a viewable image. That intentional split is not processed-route color-parity failure. The
 * explicit Main10 and RAW depth fields also prevent an encoded-transport `false` value from being
 * misread as evidence of an 8-bit RAW master.
 */
internal data class VideoPipelineParityEvidence(
    val classification: VideoPipelineParityClassification,
    val divergenceAfterStage: VideoSensorPipelineStage?,
    val encodedMain10TransportRequested: Boolean,
    val rawSensorSampleBitDepthRange: IntRange?,
    val rawSourceTransportContainerBitDepth: Int?,
    val rawStorageContainerBitDepthRange: IntRange?,
    val rawStorageSelectionPolicy: RawStorageSelectionPolicy,
    val rawMasterFidelity: RawMasterFidelity,
    val validationWarning: String? = null
) {
    val rawStoragePreservesSensorCodes: Boolean
        get() = rawMasterFidelity == RawMasterFidelity.SENSOR_CODE_EXACT_LINEAR &&
            rawSensorSampleBitDepthRange != null &&
            rawSourceTransportContainerBitDepth != null &&
            rawSourceTransportContainerBitDepth >= rawSensorSampleBitDepthRange.last &&
            rawStorageContainerBitDepthRange != null &&
            rawStorageContainerBitDepthRange.first >= rawSensorSampleBitDepthRange.first &&
            rawStorageContainerBitDepthRange.last >= rawSensorSampleBitDepthRange.last &&
            rawStorageSelectionPolicy == RawStorageSelectionPolicy.SENSOR_WHITE_LEVEL_EXACT

    val signature: String
        get() = listOf(
            "parity=${classification.name}",
            "spliceAfter=${divergenceAfterStage?.name ?: "NONE"}",
            "encodedMain10=$encodedMain10TransportRequested",
            "rawSensorBits=${rawSensorSampleBitDepthRange?.let { "${it.first}..${it.last}" } ?: "NA"}",
            "rawSourceTransportBits=${rawSourceTransportContainerBitDepth ?: "NA"}",
            "rawStorageBits=${rawStorageContainerBitDepthRange?.let { "${it.first}..${it.last}" } ?: "NA"}",
            "rawStoragePolicy=${rawStorageSelectionPolicy.name}",
            "rawFidelity=${rawMasterFidelity.name}",
            "rawCodePreserving=$rawStoragePreservesSensorCodes"
        ).joinToString(separator = ";")
}

internal data class PipelineStageDetail(
    val stage: VideoSensorPipelineStage,
    val gamut: String,
    val transferFunction: String,
    val vulkanFormat: String,
    val bitDepth: Int,
    val whitePoint: String = "D65",
    val transformToAcesCgMatrix: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    val transformFromAcesCgMatrix: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    val transferFunctionFormula: String = "V = L",
    val peakNits: Float = 100.0f,
    val dynamicRangeNits: Float = 100.0f,
    val chromaFormat: String = "4:4:4"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PipelineStageDetail

        if (stage != other.stage) return false
        if (gamut != other.gamut) return false
        if (transferFunction != other.transferFunction) return false
        if (vulkanFormat != other.vulkanFormat) return false
        if (bitDepth != other.bitDepth) return false
        if (whitePoint != other.whitePoint) return false
        if (!transformToAcesCgMatrix.contentEquals(other.transformToAcesCgMatrix)) return false
        if (!transformFromAcesCgMatrix.contentEquals(other.transformFromAcesCgMatrix)) return false
        if (transferFunctionFormula != other.transferFunctionFormula) return false
        if (peakNits != other.peakNits) return false
        if (dynamicRangeNits != other.dynamicRangeNits) return false
        if (chromaFormat != other.chromaFormat) return false

        return true
    }

    override fun hashCode(): Int {
        var result = stage.hashCode()
        result = 31 * result + gamut.hashCode()
        result = 31 * result + transferFunction.hashCode()
        result = 31 * result + vulkanFormat.hashCode()
        result = 31 * result + bitDepth
        result = 31 * result + whitePoint.hashCode()
        result = 31 * result + transformToAcesCgMatrix.contentHashCode()
        result = 31 * result + transformFromAcesCgMatrix.contentHashCode()
        result = 31 * result + transferFunctionFormula.hashCode()
        result = 31 * result + peakNits.hashCode()
        result = 31 * result + dynamicRangeNits.hashCode()
        result = 31 * result + chromaFormat.hashCode()
        return result
    }
}

internal data class VideoModeHardwareCapabilities(
    val rawSupported: Boolean = true,
    val manualSensorSupported: Boolean = true,
    val tenBitSupported: Boolean = true,
    val tenBitEncoderSupported: Boolean = true,
    val supportedDynamicRangeProfiles: Set<VideoDynamicRangeProfile> = setOf(
        VideoDynamicRangeProfile.SDR,
        VideoDynamicRangeProfile.HLG10,
        VideoDynamicRangeProfile.HDR10,
        VideoDynamicRangeProfile.HDR10_PLUS
    ),
    val wideGamutSdrSupported: Boolean = true,
    val fp16Supported: Boolean = true,
    val fp32Supported: Boolean = false,
    val awbAvailableModes: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8),
    val manualPostProcessingSupported: Boolean = true,
    val sensorColorFilterArrangement: String = "RGGB",
    /** Device-measured Camera2 carrier override; NDR/NIVUKX delivery remains project-owned. */
    val preferredNeoHdrCaptureTransport: HdrDynamicRangeMode? = null
) {
    val supportsSensorLinearNdr: Boolean
        get() = rawSupported && manualSensorSupported && fp16Supported && tenBitEncoderSupported

    fun supportsHdrMode(normalizedHdrMode: String): Boolean =
        when (normalizedHdrMode) {
            "OFF" -> true
            "NDR10+", "NIVUKXVISION",
            "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA" ->
                supportsSensorLinearNdr
            else -> supportedDynamicRangeProfiles.any { it != VideoDynamicRangeProfile.SDR }
        }

    fun supportsCaptureTransport(transport: HdrDynamicRangeMode): Boolean = when (transport) {
        HdrDynamicRangeMode.HLG ->
            VideoDynamicRangeProfile.HLG10 in supportedDynamicRangeProfiles
        HdrDynamicRangeMode.HDR10 ->
            VideoDynamicRangeProfile.HDR10 in supportedDynamicRangeProfiles
        HdrDynamicRangeMode.HDR10_PLUS ->
            VideoDynamicRangeProfile.HDR10_PLUS in supportedDynamicRangeProfiles
        else -> false
    }

    companion object {
        fun premium(): VideoModeHardwareCapabilities = VideoModeHardwareCapabilities()

        fun optimistic(gpu10BitSupported: Boolean): VideoModeHardwareCapabilities =
            VideoModeHardwareCapabilities(
                tenBitSupported = gpu10BitSupported,
                tenBitEncoderSupported = gpu10BitSupported
            )
    }
}

internal data class VideoModePipelineContract(
    val videoModeActive: Boolean,
    val cinemaMode: CinemaVideoMode,
    val deliveryKind: VideoPipelineDeliveryKind,
    val previewKind: VideoPipelinePreviewKind,
    val toneMap: VideoPipelineToneMap,
    val effectiveHdrMode: String,
    val effectiveColorSpace: String,
    val cameraDynamicRangeMode: HdrDynamicRangeMode,
    val truthfulColorDelivery: Boolean,
    val requiresHdrContainerMetadata: Boolean,
    val tenBitTransportRequested: Boolean,
    val rawSensorOriginal: Boolean,
    val logToneMapActive: Boolean,
    val hdrToneMapActive: Boolean,
    val recordingPipelineStages: List<VideoSensorPipelineStage>,
    val previewPipelineStages: List<VideoSensorPipelineStage>,
    val captureTruthSignature: String,
    val recordingStagesDetail: List<PipelineStageDetail> = emptyList(),
    val previewStagesDetail: List<PipelineStageDetail> = emptyList(),
    val targetBitrateBps: Long = 0L,
    val validationWarnings: List<String> = emptyList(),
    val requestedCinemaMode: CinemaVideoMode = cinemaMode,
    val requestedHdrMode: String = effectiveHdrMode,
    val requestedColorSpace: String = effectiveColorSpace,
    val admissionState: VideoPipelineAdmissionState = VideoPipelineAdmissionState.ADMITTED,
    val admissionFailures: Set<VideoPipelineAdmissionFailure> = emptySet(),
    val strictTenBitRequired: Boolean = false,
    val strictTenBitSatisfied: Boolean = false,
    val strictTenBitBlocked: Boolean = false,
    val strictSdrTenBitRequired: Boolean = false,
    val strictSdrTenBitSatisfied: Boolean = false,
    val strictSdrTenBitBlocked: Boolean = false,
    val chromaFactor: Double = 1.5,
    val entropyCoefficient: Double = 0.08,
    val shannonEntropyBitsPerPixel: Double = 0.96,
    val requiredBandwidthBytesPerSec: Double = 0.0,
    val gpuSingleFrameSizeBytes: Long = 0L,
    val gpuSwapchainTripleBufferSizeBytes: Long = 0L,
    val parityEvidence: VideoPipelineParityEvidence
) {
    val encodedMain10TransportRequested: Boolean
        get() = tenBitTransportRequested

    val neutralPreview: Boolean
        get() = previewKind == VideoPipelinePreviewKind.SDR &&
            effectiveHdrMode == "OFF" &&
            !logToneMapActive &&
            !rawSensorOriginal

    val isCinemaDeliveryMode: Boolean
        get() = rawSensorOriginal || logToneMapActive || hdrToneMapActive

    val hasRawHdrOutputShortcut: Boolean
        get() = rawSensorOriginal &&
            (recordingPipelineStages.contains(VideoSensorPipelineStage.HDR_MERGE) ||
                hdrToneMapActive ||
                requiresHdrContainerMetadata ||
                cameraDynamicRangeMode != HdrDynamicRangeMode.SDR)

    val recordingAllowed: Boolean
        get() = videoModeActive &&
            admissionState == VideoPipelineAdmissionState.ADMITTED &&
            !strictTenBitBlocked

    val admissionFailureMessage: String?
        get() = if (admissionState != VideoPipelineAdmissionState.REJECTED) {
            null
        } else {
            buildString {
                append("Requested video mode was rejected without changing its identity: ")
                append(admissionFailures.joinToString(separator = ",") { it.name })
            }
        }

    val sessionSignature: String
        get() = listOf(
            videoModeActive.toString(),
            cinemaMode.name,
            deliveryKind.name,
            previewKind.name,
            toneMap.name,
            effectiveHdrMode,
            effectiveColorSpace,
            cameraDynamicRangeMode.name,
            tenBitTransportRequested.toString(),
            recordingPipelineStages.joinToString(separator = ">") { it.name },
            previewPipelineStages.joinToString(separator = ">") { it.name },
            recordingStagesDetail.joinToString(separator = ">") {
                "${it.stage.name}:${it.gamut}:${it.transferFunction}:${it.vulkanFormat}:${it.bitDepth}:${it.peakNits}"
            },
            previewStagesDetail.joinToString(separator = ">") {
                "${it.stage.name}:${it.gamut}:${it.transferFunction}:${it.vulkanFormat}:${it.bitDepth}:${it.peakNits}"
            },
            targetBitrateBps.toString(),
            validationWarnings.joinToString(separator = "~"),
            requestedCinemaMode.name,
            requestedHdrMode,
            requestedColorSpace,
            admissionState.name,
            admissionFailures.sortedBy { it.name }.joinToString(separator = ",") { it.name },
            strictTenBitRequired.toString(),
            strictTenBitSatisfied.toString(),
            strictTenBitBlocked.toString(),
            captureTruthSignature,
            chromaFactor.toString(),
            entropyCoefficient.toString(),
            shannonEntropyBitsPerPixel.toString(),
            requiredBandwidthBytesPerSec.toString(),
            gpuSingleFrameSizeBytes.toString(),
            gpuSwapchainTripleBufferSizeBytes.toString(),
            parityEvidence.signature
        ).joinToString(separator = "|")
}

internal object VideoModePipelineContractPolicy {
    fun resolveHardwareCapabilities(
        characteristics: CameraCharacteristics?,
        gpu10BitSupported: Boolean
    ): VideoModeHardwareCapabilities =
        ContractHardwareValidator.resolve(
            characteristics = characteristics,
            gpu10BitSupported = gpu10BitSupported,
            explicitCapabilities = null
        )

    fun resolve(
        uiMode: AppMode,
        cinemaMode: CinemaVideoMode,
        rawVideoActive: Boolean,
        logEnabled: Boolean,
        requestedHdrMode: String,
        preferredColorSpace: String = "Rec.709",
        tenBitActive: Boolean = false,
        characteristics: CameraCharacteristics? = null,
        gpu10BitSupported: Boolean = true,
        hardwareCapabilities: VideoModeHardwareCapabilities? = null,
        width: Int = 3840,
        height: Int = 2160,
        fps: Int = 60
    ): VideoModePipelineContract {
        val hardware = ContractHardwareValidator.resolve(
            characteristics = characteristics,
            gpu10BitSupported = gpu10BitSupported,
            explicitCapabilities = hardwareCapabilities
        )
        val validationWarnings = mutableListOf<String>()
        val admissionFailures = linkedSetOf<VideoPipelineAdmissionFailure>()
        val videoModeActive = VideoRenderProfileQualityHintPolicy.isVideoSurface(uiMode)
        val requestedRawMode = cinemaMode == CinemaVideoMode.RAW
        val rawAvailable = hardware.rawSupported && hardware.manualSensorSupported
        if (videoModeActive && requestedRawMode && !rawAvailable) {
            admissionFailures += VideoPipelineAdmissionFailure.RAW_CAPTURE_UNAVAILABLE
            validationWarnings += "RAW sensor pipeline request rejected: physical RAW/MANUAL_SENSOR capability is unavailable; mode identity must not be rewritten."
        } else if (videoModeActive && requestedRawMode && !rawVideoActive) {
            admissionFailures += VideoPipelineAdmissionFailure.RAW_CAPTURE_ROUTE_INACTIVE
            validationWarnings += "RAW sensor pipeline request is not admitted until its native RAW route is active."
        }
        val rawAllowed = requestedRawMode && rawVideoActive && rawAvailable
        val tenBitTransportAvailable = hardware.tenBitSupported &&
            hardware.tenBitEncoderSupported &&
            gpu10BitSupported
        val strictTenBitRequired = videoModeActive && !requestedRawMode
        if (strictTenBitRequired && !tenBitTransportAvailable) {
            admissionFailures += VideoPipelineAdmissionFailure.TEN_BIT_TRANSPORT_UNAVAILABLE
            validationWarnings += "Strict video delivery requires a real Main10/P010 camera, GPU, and encoder path; 8-bit fallback is refused."
        }
        val tenBitAllowed = tenBitActive && tenBitTransportAvailable
        val sdrTenBitAvailable = tenBitTransportAvailable
        if (tenBitActive && !tenBitAllowed) {
            validationWarnings += "10-bit transport requested, but hardware or GPU P010 support is unavailable."
        }

        if (!videoModeActive) {
            return standardContract(
                videoModeActive = false,
                cinemaMode = cinemaMode,
                preferredColorSpace = preferredColorSpace,
                tenBitActive = tenBitAllowed,
                sdrTenBitAvailable = sdrTenBitAvailable,
                hardware = hardware,
                validationWarnings = validationWarnings,
                requestedCinemaMode = cinemaMode,
                requestedHdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(requestedHdrMode),
                requestedColorSpace = preferredColorSpace,
                width = width,
                height = height,
                fps = fps
            )
        }

        val rawActive = rawAllowed
        val logRequested = !requestedRawMode && (cinemaMode == CinemaVideoMode.LOG || logEnabled)
        val requestedHdrKey = if (requestedRawMode || logRequested) {
            "OFF"
        } else {
            RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(requestedHdrMode)
        }
        val requestedHdr = requestedHdrKey != "OFF"
        val proprietaryNdrRequested = isProprietaryNdrMode(requestedHdrKey)
        val hdrSupported = !requestedHdr || if (proprietaryNdrRequested) {
            gpu10BitSupported && hardware.supportsSensorLinearNdr
        } else {
            hardware.tenBitSupported && gpu10BitSupported && hardware.supportsHdrMode(requestedHdrKey)
        }
        val hdrRequested = requestedHdr && hdrSupported
        if (requestedHdr && !hdrSupported) {
            admissionFailures += VideoPipelineAdmissionFailure.HDR_TRANSPORT_UNAVAILABLE
            validationWarnings += "HDR transport '$requestedHdrKey' request rejected by the active camera/GPU capability set; SDR substitution is forbidden."
        }
        val requestedSdrColorSpace = resolveSdrColorSpace(preferredColorSpace)
        if (
            !requestedRawMode &&
            !logRequested &&
            !requestedHdr &&
            isWideGamutSdrColorSpace(requestedSdrColorSpace) &&
            !hardware.wideGamutSdrSupported
        ) {
            admissionFailures += VideoPipelineAdmissionFailure.WIDE_GAMUT_SDR_UNAVAILABLE
            validationWarnings += "Wide-gamut SDR request '$requestedSdrColorSpace' rejected; Rec.709 substitution is forbidden."
        }
        val logActive = logRequested && tenBitTransportAvailable && !hdrRequested
        val normalizedHdrMode = if (rawActive || logActive || !hdrRequested) "OFF" else requestedHdrKey
        val hdrActive = normalizedHdrMode != "OFF"
        val admissionState = if (admissionFailures.isEmpty()) {
            VideoPipelineAdmissionState.ADMITTED
        } else {
            VideoPipelineAdmissionState.REJECTED
        }
        val strictTenBitSatisfied = strictTenBitRequired && tenBitTransportAvailable
        val strictTenBitBlocked = strictTenBitRequired && !tenBitTransportAvailable

        return when {
            rawActive -> createContract(
                videoModeActive = true,
                cinemaMode = CinemaVideoMode.RAW,
                deliveryKind = VideoPipelineDeliveryKind.RAW_SENSOR,
                previewKind = VideoPipelinePreviewKind.RAW_SENSOR,
                toneMap = VideoPipelineToneMap.RAW_LINEAR_MONITOR,
                effectiveHdrMode = "OFF",
                effectiveColorSpace = "RAW",
                cameraDynamicRangeMode = HdrDynamicRangeMode.SDR,
                truthfulColorDelivery = true,
                requiresHdrContainerMetadata = false,
                tenBitTransportRequested = false,
                rawSensorOriginal = true,
                logToneMapActive = false,
                hdrToneMapActive = false,
                recordingPipelineStages = rawOriginalRecordingPipelineStages(),
                previewPipelineStages = processedPipelineStages(hdrMergeActive = false),
                validationWarnings = validationWarnings,
                requestedCinemaMode = cinemaMode,
                requestedHdrMode = requestedHdrKey,
                requestedColorSpace = preferredColorSpace,
                admissionState = admissionState,
                admissionFailures = admissionFailures,
                width = width,
                height = height,
                fps = fps,
                hardware = hardware
            )
            logActive -> createContract(
                videoModeActive = true,
                cinemaMode = CinemaVideoMode.LOG,
                deliveryKind = VideoPipelineDeliveryKind.LOG_10BIT,
                previewKind = VideoPipelinePreviewKind.LOG_10BIT,
                toneMap = VideoPipelineToneMap.LOG_ORIGINAL,
                effectiveHdrMode = "OFF",
                effectiveColorSpace = "Rec.2020-LOG",
                cameraDynamicRangeMode = HdrDynamicRangeMode.SDR,
                truthfulColorDelivery = true,
                requiresHdrContainerMetadata = false,
                tenBitTransportRequested = hardware.tenBitSupported && gpu10BitSupported,
                rawSensorOriginal = false,
                logToneMapActive = true,
                hdrToneMapActive = false,
                recordingPipelineStages = processedPipelineStages(hdrMergeActive = false),
                previewPipelineStages = processedPipelineStages(hdrMergeActive = false),
                validationWarnings = validationWarnings,
                requestedCinemaMode = cinemaMode,
                requestedHdrMode = requestedHdrKey,
                requestedColorSpace = preferredColorSpace,
                admissionState = admissionState,
                admissionFailures = admissionFailures,
                strictTenBitRequired = strictTenBitRequired,
                strictTenBitSatisfied = strictTenBitSatisfied,
                strictTenBitBlocked = strictTenBitBlocked,
                width = width,
                height = height,
                fps = fps,
                hardware = hardware
            )
            hdrActive -> createContract(
                videoModeActive = true,
                cinemaMode = CinemaVideoMode.PRO,
                deliveryKind = resolveHdrDeliveryKind(normalizedHdrMode),
                previewKind = VideoPipelinePreviewKind.HDR_10BIT,
                toneMap = resolveHdrToneMap(normalizedHdrMode),
                effectiveHdrMode = normalizedHdrMode,
                effectiveColorSpace = resolveHdrColorSpace(normalizedHdrMode),
                cameraDynamicRangeMode = resolveHdrDynamicRangeMode(normalizedHdrMode, hardware),
                truthfulColorDelivery = true,
                requiresHdrContainerMetadata = true,
                tenBitTransportRequested = true,
                rawSensorOriginal = false,
                logToneMapActive = false,
                hdrToneMapActive = true,
                recordingPipelineStages = processedPipelineStages(hdrMergeActive = true),
                previewPipelineStages = processedPipelineStages(hdrMergeActive = true),
                validationWarnings = validationWarnings,
                requestedCinemaMode = cinemaMode,
                requestedHdrMode = requestedHdrKey,
                requestedColorSpace = preferredColorSpace,
                admissionState = admissionState,
                admissionFailures = admissionFailures,
                strictTenBitRequired = strictTenBitRequired,
                strictTenBitSatisfied = strictTenBitSatisfied,
                strictTenBitBlocked = strictTenBitBlocked,
                width = width,
                height = height,
                fps = fps,
                hardware = hardware
            )
            else -> standardContract(
                videoModeActive = true,
                cinemaMode = cinemaMode,
                preferredColorSpace = if (requestedHdr && !hdrSupported && !hardware.tenBitSupported) {
                    "Rec.709"
                } else {
                    preferredColorSpace
                },
                tenBitActive = tenBitAllowed,
                sdrTenBitAvailable = sdrTenBitAvailable,
                hardware = hardware,
                validationWarnings = validationWarnings,
                requestedCinemaMode = cinemaMode,
                requestedHdrMode = requestedHdrKey,
                requestedColorSpace = preferredColorSpace,
                admissionState = admissionState,
                admissionFailures = admissionFailures,
                strictTenBitRequired = strictTenBitRequired,
                strictTenBitSatisfied = strictTenBitSatisfied,
                strictTenBitBlocked = strictTenBitBlocked,
                width = width,
                height = height,
                fps = fps
            )
        }
    }

    private fun standardContract(
        videoModeActive: Boolean,
        cinemaMode: CinemaVideoMode,
        preferredColorSpace: String,
        tenBitActive: Boolean,
        sdrTenBitAvailable: Boolean = tenBitActive,
        hardware: VideoModeHardwareCapabilities = VideoModeHardwareCapabilities.premium(),
        validationWarnings: List<String> = emptyList(),
        requestedCinemaMode: CinemaVideoMode = cinemaMode,
        requestedHdrMode: String = "OFF",
        requestedColorSpace: String = preferredColorSpace,
        admissionState: VideoPipelineAdmissionState = VideoPipelineAdmissionState.ADMITTED,
        admissionFailures: Set<VideoPipelineAdmissionFailure> = emptySet(),
        strictTenBitRequired: Boolean = false,
        strictTenBitSatisfied: Boolean = false,
        strictTenBitBlocked: Boolean = false,
        width: Int = 3840,
        height: Int = 2160,
        fps: Int = 60
    ): VideoModePipelineContract {
        val requestedColorSpace = resolveSdrColorSpace(preferredColorSpace)
        val effectiveColorSpace = if (
            isWideGamutSdrColorSpace(requestedColorSpace) &&
            !hardware.wideGamutSdrSupported
        ) {
            "Rec.709"
        } else {
            requestedColorSpace
        }
        val wideGamutSdr = isWideGamutSdrColorSpace(effectiveColorSpace) && hardware.wideGamutSdrSupported
        val eliteSdrTenBit = videoModeActive && sdrTenBitAvailable
        val tenBitTransportRequested = (tenBitActive || wideGamutSdr || eliteSdrTenBit) && sdrTenBitAvailable
        val strictSdrTenBitRequired = videoModeActive &&
            requestedCinemaMode == CinemaVideoMode.PRO &&
            requestedHdrMode == "OFF"
        val strictSdrTenBitSatisfied = strictSdrTenBitRequired && tenBitTransportRequested
        val strictSdrTenBitBlocked = strictSdrTenBitRequired && !tenBitTransportRequested
        val resolvedValidationWarnings = if (strictSdrTenBitBlocked) {
            validationWarnings + "Strict Normal/Pro SDR requires real 10-bit Main10/P010 transport; recording must refuse 8-bit SDR fallback."
        } else {
            validationWarnings
        }
        val deliveryKind = if (cinemaMode == CinemaVideoMode.PRO) {
            VideoPipelineDeliveryKind.PRO_SDR
        } else {
            VideoPipelineDeliveryKind.VIDEO_SDR
        }
        val stages = processedPipelineStages(hdrMergeActive = false)
        return createContract(
            videoModeActive = videoModeActive,
            cinemaMode = cinemaMode,
            deliveryKind = deliveryKind,
            previewKind = VideoPipelinePreviewKind.SDR,
            toneMap = if (wideGamutSdr) {
                VideoPipelineToneMap.WIDE_GAMUT_SDR
            } else {
                VideoPipelineToneMap.REC709_NEUTRAL
            },
            effectiveHdrMode = "OFF",
            effectiveColorSpace = effectiveColorSpace,
            cameraDynamicRangeMode = HdrDynamicRangeMode.SDR,
            truthfulColorDelivery = tenBitTransportRequested,
            requiresHdrContainerMetadata = false,
            tenBitTransportRequested = tenBitTransportRequested,
            rawSensorOriginal = false,
            logToneMapActive = false,
            hdrToneMapActive = false,
            recordingPipelineStages = stages,
            previewPipelineStages = stages,
            validationWarnings = resolvedValidationWarnings,
            requestedCinemaMode = requestedCinemaMode,
            requestedHdrMode = requestedHdrMode,
            requestedColorSpace = requestedColorSpace,
            admissionState = admissionState,
            admissionFailures = admissionFailures,
            strictTenBitRequired = strictTenBitRequired,
            strictTenBitSatisfied = strictTenBitSatisfied,
            strictTenBitBlocked = strictTenBitBlocked,
            width = width,
            height = height,
            fps = fps,
            hardware = hardware,
            strictSdrTenBitRequired = strictSdrTenBitRequired,
            strictSdrTenBitSatisfied = strictSdrTenBitSatisfied,
            strictSdrTenBitBlocked = strictSdrTenBitBlocked
        )
    }

    private fun createContract(
        videoModeActive: Boolean,
        cinemaMode: CinemaVideoMode,
        deliveryKind: VideoPipelineDeliveryKind,
        previewKind: VideoPipelinePreviewKind,
        toneMap: VideoPipelineToneMap,
        effectiveHdrMode: String,
        effectiveColorSpace: String,
        cameraDynamicRangeMode: HdrDynamicRangeMode,
        truthfulColorDelivery: Boolean,
        requiresHdrContainerMetadata: Boolean,
        tenBitTransportRequested: Boolean,
        rawSensorOriginal: Boolean,
        logToneMapActive: Boolean,
        hdrToneMapActive: Boolean,
        recordingPipelineStages: List<VideoSensorPipelineStage>,
        previewPipelineStages: List<VideoSensorPipelineStage>,
        validationWarnings: List<String>,
        width: Int,
        height: Int,
        fps: Int,
        hardware: VideoModeHardwareCapabilities,
        requestedCinemaMode: CinemaVideoMode = cinemaMode,
        requestedHdrMode: String = effectiveHdrMode,
        requestedColorSpace: String = effectiveColorSpace,
        admissionState: VideoPipelineAdmissionState = VideoPipelineAdmissionState.ADMITTED,
        admissionFailures: Set<VideoPipelineAdmissionFailure> = emptySet(),
        strictTenBitRequired: Boolean = false,
        strictTenBitSatisfied: Boolean = false,
        strictTenBitBlocked: Boolean = false,
        strictSdrTenBitRequired: Boolean = false,
        strictSdrTenBitSatisfied: Boolean = false,
        strictSdrTenBitBlocked: Boolean = false
    ): VideoModePipelineContract {
        val recordingStagesDetail = stageDetailsFor(
            stages = recordingPipelineStages,
            effectiveColorSpace = effectiveColorSpace,
            raw = rawSensorOriginal,
            log = logToneMapActive,
            hdr = hdrToneMapActive,
            tenBit = tenBitTransportRequested,
            preview = false,
            hardware = hardware
        )
        val previewStagesDetail = stageDetailsFor(
            stages = previewPipelineStages,
            effectiveColorSpace = effectiveColorSpace,
            raw = rawSensorOriginal,
            log = logToneMapActive,
            hdr = hdrToneMapActive,
            tenBit = tenBitTransportRequested,
            preview = true,
            hardware = hardware
        )
        val entropyCoefficient = when {
            rawSensorOriginal -> 0.28
            logToneMapActive -> 0.22
            hdrToneMapActive -> 0.18
            tenBitTransportRequested -> 0.12
            else -> 0.08
        }
        val bitsPerPixel = when {
            rawSensorOriginal -> 48
            hdrToneMapActive || logToneMapActive || tenBitTransportRequested -> 30
            else -> 24
        }
        val targetBitrateBps = (width.toLong() * height.toLong() * fps.toLong() * bitsPerPixel * entropyCoefficient).roundToLong()
        val captureTruthSignature = captureTruthSignature(
            deliveryKind = deliveryKind,
            previewKind = previewKind,
            colorSpace = effectiveColorSpace,
            hdrMode = effectiveHdrMode,
            tenBit = tenBitTransportRequested,
            log = logToneMapActive,
            raw = rawSensorOriginal,
            hdrMetadata = requiresHdrContainerMetadata
        )
        val parityEvidence = classifyPipelineParity(
            rawSensorOriginal = rawSensorOriginal,
            encodedMain10TransportRequested = tenBitTransportRequested,
            recording = recordingStagesDetail,
            preview = previewStagesDetail
        )
        val warnings = validationWarnings.toMutableList() + listOfNotNull(parityEvidence.validationWarning)

        val chromaFactor = if (rawSensorOriginal) 3.0 else 1.5
        val bitDepth = if (rawSensorOriginal) 16 else if (hdrToneMapActive || logToneMapActive || tenBitTransportRequested) 10 else 8
        val shannonEntropyBitsPerPixel = bitDepth.toDouble() * chromaFactor * entropyCoefficient
        val requiredBandwidthBytesPerSec = width.toDouble() * height.toDouble() * fps.toDouble() * (bitDepth.toDouble() / 8.0) * chromaFactor
        val gpuSingleFrameSizeBytes = (width.toDouble() * height.toDouble() * (bitDepth.toDouble() / 8.0) * chromaFactor).toLong()
        val gpuSwapchainTripleBufferSizeBytes = gpuSingleFrameSizeBytes * 3

        return VideoModePipelineContract(
            videoModeActive = videoModeActive,
            cinemaMode = cinemaMode,
            deliveryKind = deliveryKind,
            previewKind = previewKind,
            toneMap = toneMap,
            effectiveHdrMode = effectiveHdrMode,
            effectiveColorSpace = effectiveColorSpace,
            cameraDynamicRangeMode = cameraDynamicRangeMode,
            truthfulColorDelivery = truthfulColorDelivery,
            requiresHdrContainerMetadata = requiresHdrContainerMetadata,
            tenBitTransportRequested = tenBitTransportRequested,
            rawSensorOriginal = rawSensorOriginal,
            logToneMapActive = logToneMapActive,
            hdrToneMapActive = hdrToneMapActive,
            recordingPipelineStages = recordingPipelineStages,
            previewPipelineStages = previewPipelineStages,
            captureTruthSignature = captureTruthSignature,
            recordingStagesDetail = recordingStagesDetail,
            previewStagesDetail = previewStagesDetail,
            targetBitrateBps = targetBitrateBps,
            validationWarnings = warnings,
            requestedCinemaMode = requestedCinemaMode,
            requestedHdrMode = requestedHdrMode,
            requestedColorSpace = requestedColorSpace,
            admissionState = admissionState,
            admissionFailures = admissionFailures,
            strictTenBitRequired = strictTenBitRequired,
            strictTenBitSatisfied = strictTenBitSatisfied,
            strictTenBitBlocked = strictTenBitBlocked,
            strictSdrTenBitRequired = strictSdrTenBitRequired,
            strictSdrTenBitSatisfied = strictSdrTenBitSatisfied,
            strictSdrTenBitBlocked = strictSdrTenBitBlocked,
            chromaFactor = chromaFactor,
            entropyCoefficient = entropyCoefficient,
            shannonEntropyBitsPerPixel = shannonEntropyBitsPerPixel,
            requiredBandwidthBytesPerSec = requiredBandwidthBytesPerSec,
            gpuSingleFrameSizeBytes = gpuSingleFrameSizeBytes,
            gpuSwapchainTripleBufferSizeBytes = gpuSwapchainTripleBufferSizeBytes,
            parityEvidence = parityEvidence
        )
    }

    private fun resolveSdrColorSpace(preferredColorSpace: String): String {
        val value = ManualVideoColorSelectionPolicy.normalizeColorSpace(preferredColorSpace)
        if (value == "RAW") return "Rec.709"
        return if (value != ManualVideoColorSelectionPolicy.UNSELECTED && isWideGamutSdrColorSpace(value)) {
            value
        } else {
            "Rec.709"
        }
    }

    private fun isWideGamutSdrColorSpace(value: String): Boolean {
        if (value.equals("RAW", ignoreCase = true)) return false
        val profile = ManualVideoColorSelectionPolicy.resolveColorProfile(value, value)
        val key = profile.canonicalColorSpace.lowercase()
        return profile.wideGamut ||
            key.contains("2020") ||
            key.contains("2100") ||
            key.contains("p3") ||
            key.contains("aces") ||
            key.contains("neo") ||
            key.contains("nivukx") ||
            key.contains("ndr10") ||
            key.contains("wide gamut") ||
            key.contains("widegamut")
    }

    private fun processedPipelineStages(
        hdrMergeActive: Boolean
    ): List<VideoSensorPipelineStage> =
        if (hdrMergeActive) {
            listOf(
                VideoSensorPipelineStage.SENSOR_RAW,
                VideoSensorPipelineStage.DEMOSAIC,
                VideoSensorPipelineStage.AUTO_WHITE_BALANCE,
                VideoSensorPipelineStage.COLOR_CORRECTION_MATRIX,
                VideoSensorPipelineStage.NOISE_REDUCTION,
                VideoSensorPipelineStage.HDR_MERGE,
                VideoSensorPipelineStage.TONE_MAPPING,
                VideoSensorPipelineStage.GAMMA_CONVERSION,
                VideoSensorPipelineStage.OUTPUT
            )
        } else {
            listOf(
                VideoSensorPipelineStage.SENSOR_RAW,
                VideoSensorPipelineStage.DEMOSAIC,
                VideoSensorPipelineStage.AUTO_WHITE_BALANCE,
                VideoSensorPipelineStage.COLOR_CORRECTION_MATRIX,
                VideoSensorPipelineStage.NOISE_REDUCTION,
                VideoSensorPipelineStage.TONE_MAPPING,
                VideoSensorPipelineStage.GAMMA_CONVERSION,
                VideoSensorPipelineStage.OUTPUT
            )
        }

    private fun rawOriginalRecordingPipelineStages(): List<VideoSensorPipelineStage> =
        listOf(
            VideoSensorPipelineStage.SENSOR_RAW,
            VideoSensorPipelineStage.OUTPUT
        )

    private fun stageDetailsFor(
        stages: List<VideoSensorPipelineStage>,
        effectiveColorSpace: String,
        raw: Boolean,
        log: Boolean,
        hdr: Boolean,
        tenBit: Boolean,
        preview: Boolean,
        hardware: VideoModeHardwareCapabilities
    ): List<PipelineStageDetail> {
        val intermediateFormat = if (hardware.fp32Supported && raw) {
            "R32G32B32A32_SFLOAT"
        } else {
            "R16G16B16A16_SFLOAT"
        }
        val intermediateDepth = if (intermediateFormat.startsWith("R32")) 32 else 16
        val outputGamut = resolveStageGamut(effectiveColorSpace, raw)
        val outputTransfer = when {
            raw -> "Linear"
            log -> "NeoLog"
            hdr && preview -> "ST.2084_PQ"
            hdr -> "ST.2084_PQ"
            else -> "sRGB"
        }
        val outputFormat = when {
            raw -> intermediateFormat
            hdr || log || tenBit -> "A2B10G10R10_UNORM_PACK32"
            else -> "R8G8B8A8_UNORM"
        }
        val outputDepth = when {
            raw -> intermediateDepth
            hdr || log || tenBit -> 10
            else -> 8
        }

        return stages.map { stage ->
            when (stage) {
                VideoSensorPipelineStage.SENSOR_RAW -> {
                    val gamut = if (raw) "RAW" else "ACEScg"
                    val transfer = "Linear"
                    PipelineStageDetail(
                        stage = stage,
                        gamut = gamut,
                        transferFunction = transfer,
                        vulkanFormat = intermediateFormat,
                        bitDepth = intermediateDepth,
                        whitePoint = if (raw) "SensorNative" else "D60",
                        transformToAcesCgMatrix = resolveToAcesCgMatrix(gamut),
                        transformFromAcesCgMatrix = resolveFromAcesCgMatrix(gamut),
                        transferFunctionFormula = resolveTransferFunctionFormula(transfer),
                        peakNits = if (raw) 1000.0f else 100.0f,
                        dynamicRangeNits = if (raw) 1000.0f else 100.0f,
                        chromaFormat = "RAW"
                    )
                }
                VideoSensorPipelineStage.DEMOSAIC,
                VideoSensorPipelineStage.AUTO_WHITE_BALANCE,
                VideoSensorPipelineStage.COLOR_CORRECTION_MATRIX,
                VideoSensorPipelineStage.NOISE_REDUCTION,
                VideoSensorPipelineStage.HDR_MERGE -> {
                    val gamut = "ACEScg"
                    val transfer = "Linear"
                    PipelineStageDetail(
                        stage = stage,
                        gamut = gamut,
                        transferFunction = transfer,
                        vulkanFormat = intermediateFormat,
                        bitDepth = intermediateDepth,
                        whitePoint = "D60",
                        transformToAcesCgMatrix = resolveToAcesCgMatrix(gamut),
                        transformFromAcesCgMatrix = resolveFromAcesCgMatrix(gamut),
                        transferFunctionFormula = resolveTransferFunctionFormula(transfer),
                        peakNits = 100.0f,
                        dynamicRangeNits = 100.0f,
                        chromaFormat = "4:4:4"
                    )
                }
                VideoSensorPipelineStage.TONE_MAPPING -> {
                    val gamut = if (hdr) "BT.2020" else outputGamut
                    val transfer = when {
                        hdr -> "ST.2084_PQ"
                        log -> "NeoLog"
                        raw -> "Linear"
                        else -> "ACES_SDR"
                    }
                    val peak = if (hdr) 1000.0f else 100.0f
                    PipelineStageDetail(
                        stage = stage,
                        gamut = gamut,
                        transferFunction = transfer,
                        vulkanFormat = intermediateFormat,
                        bitDepth = intermediateDepth,
                        whitePoint = if (hdr) "D65" else whitePointForGamut(gamut),
                        transformToAcesCgMatrix = resolveToAcesCgMatrix(gamut),
                        transformFromAcesCgMatrix = resolveFromAcesCgMatrix(gamut),
                        transferFunctionFormula = resolveTransferFunctionFormula(transfer),
                        peakNits = peak,
                        dynamicRangeNits = peak,
                        chromaFormat = "4:4:4"
                    )
                }
                VideoSensorPipelineStage.GAMMA_CONVERSION,
                VideoSensorPipelineStage.OUTPUT -> {
                    val gamut = outputGamut
                    val transfer = outputTransfer
                    val peak = if (hdr) 1000.0f else 100.0f
                    PipelineStageDetail(
                        stage = stage,
                        gamut = gamut,
                        transferFunction = transfer,
                        vulkanFormat = outputFormat,
                        bitDepth = outputDepth,
                        whitePoint = whitePointForGamut(gamut),
                        transformToAcesCgMatrix = resolveToAcesCgMatrix(gamut),
                        transformFromAcesCgMatrix = resolveFromAcesCgMatrix(gamut),
                        transferFunctionFormula = resolveTransferFunctionFormula(transfer),
                        peakNits = peak,
                        dynamicRangeNits = peak,
                        chromaFormat = if (raw) "RAW" else "4:2:0"
                    )
                }
            }
        }
    }

    private fun resolveToAcesCgMatrix(gamut: String): DoubleArray {
        return when (gamut) {
            "BT.709" -> doubleArrayOf(0.6131, 0.3395, 0.0474, 0.0702, 0.9164, 0.0134, 0.0206, 0.1096, 0.8698)
            "DCI-P3" -> doubleArrayOf(0.7543, 0.1970, 0.0487, 0.0234, 0.9431, 0.0335, -0.0034, 0.0445, 0.9589)
            "BT.2020" -> doubleArrayOf(0.6386, 0.3160, 0.0454, 0.0439, 0.9423, 0.0138, -0.0011, 0.0372, 0.9639)
            "ACEScg" -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            "ACES2065-1" -> doubleArrayOf(
                1.4514393161, -0.2365107469, -0.2149285693,
                -0.0765537734, 1.1762296998, -0.0996759264,
                0.0083161484, -0.0060324498, 0.9977163014
            )
            "Neo Wide Gamut" -> doubleArrayOf(
                1.0800, -0.0600, -0.0200,
                -0.0300, 1.0400, -0.0100,
                0.0000, -0.0400, 1.0400
            )
            else -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        }
    }

    private fun resolveFromAcesCgMatrix(gamut: String): DoubleArray {
        return when (gamut) {
            "BT.709" -> doubleArrayOf(1.6625, -0.6149, -0.1018, -0.1245, 1.1176, -0.0071, -0.0238, -0.1384, 1.1557)
            "DCI-P3" -> doubleArrayOf(1.3323, -0.2764, -0.0579, -0.0331, 1.0664, -0.0361, 0.0063, -0.0486, 1.0425)
            "BT.2020" -> doubleArrayOf(1.5836, -0.5284, -0.0672, -0.0743, 1.0748, -0.0118, 0.0047, -0.0413, 1.0375)
            "ACEScg" -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            "ACES2065-1" -> doubleArrayOf(
                0.6954522414, 0.1406786965, 0.1638690622,
                0.0447945634, 0.8596711185, 0.0955343182,
                -0.0055258826, 0.0040252103, 1.0015006723
            )
            "Neo Wide Gamut" -> doubleArrayOf(
                0.9280, 0.0540, 0.0180,
                0.0270, 0.9630, 0.0100,
                0.0000, 0.0370, 0.9630
            )
            else -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        }
    }

    private fun resolveTransferFunctionFormula(transferFunction: String): String {
        return when (transferFunction) {
            "sRGB", "Rec.709" -> "OETF: if (L <= 0.018) L * 4.5 else 1.099 * L^0.45 - 0.099"
            "Linear" -> "V = L"
            "ST.2084_PQ" -> "EOTF: L = 10000 * ((max(V^(1/m2) - c1, 0)) / (c2 - c3 * V^(1/m2)))^(1/m1)"
            "BT.2100_HLG" -> "OETF: if (L <= 1/12) sqrt(3*L) else a*ln(12*L - b) + c"
            "NeoLog" -> "V = a * log10(b * L + 1) + c"
            else -> "V = L"
        }
    }

    private fun resolveStageGamut(effectiveColorSpace: String, raw: Boolean): String {
        if (raw || effectiveColorSpace.equals("RAW", ignoreCase = true)) return "RAW"
        val profile = ManualVideoColorSelectionPolicy.resolveColorProfile(effectiveColorSpace, effectiveColorSpace)
        return when (profile.family) {
            "NEO_ACES_AP0" -> "ACES2065-1"
            "NEO_ACES_AP1" -> "ACEScg"
            "NEO_WIDE_GAMUT" -> "Neo Wide Gamut"
            "NDR10_PLUS", "NIVUKX_VISION", "NIVUKX_APEX", "REC2020" -> "BT.2020"
            else -> "BT.709"
        }
    }

    private fun whitePointForGamut(gamut: String): String =
        when (gamut) {
            "ACEScg", "ACES2065-1" -> "D60"
            "Neo Wide Gamut" -> "D55"
            "RAW" -> "SensorNative"
            else -> "D65"
        }

    private fun calculateTargetBitrateBps(
        width: Int,
        height: Int,
        fps: Int,
        raw: Boolean,
        log: Boolean,
        hdr: Boolean,
        tenBit: Boolean
    ): Long {
        val safeWidth = width.coerceAtLeast(1).toLong()
        val safeHeight = height.coerceAtLeast(1).toLong()
        val safeFps = fps.coerceAtLeast(1).toLong()
        val bitsPerPixel = when {
            raw -> 48
            hdr || log || tenBit -> 30
            else -> 24
        }
        val entropyCoefficient = when {
            raw -> 0.28
            log -> 0.22
            hdr -> 0.18
            tenBit -> 0.12
            else -> 0.08
        }
        return (safeWidth * safeHeight * safeFps * bitsPerPixel * entropyCoefficient).roundToLong()
    }

    internal fun classifyPipelineParity(
        rawSensorOriginal: Boolean,
        encodedMain10TransportRequested: Boolean,
        recording: List<PipelineStageDetail>,
        preview: List<PipelineStageDetail>
    ): VideoPipelineParityEvidence {
        val recordingCore = recording.takeWhile { it.stage != VideoSensorPipelineStage.TONE_MAPPING }
            .map { it.stage to (it.gamut to it.whitePoint) }
        val previewCore = preview.takeWhile { it.stage != VideoSensorPipelineStage.TONE_MAPPING }
            .map { it.stage to (it.gamut to it.whitePoint) }

        val sharedPrefixLength = recordingCore.zip(previewCore)
            .takeWhile { (recordingStage, previewStage) -> recordingStage == previewStage }
            .size
        val divergenceAfterStage = recordingCore
            .getOrNull(sharedPrefixLength - 1)
            ?.first
        val recordingStages = recording.map(PipelineStageDetail::stage)
        val previewStages = preview.map(PipelineStageDetail::stage)
        val sensorOriginalLinear16Master = rawSensorOriginal &&
            recordingStages == rawOriginalRecordingPipelineStages() &&
            recording.all { detail ->
                detail.gamut == "RAW" &&
                    detail.transferFunction == "Linear" &&
                    detail.bitDepth >= RAW_SOURCE_TRANSPORT_CONTAINER_BIT_DEPTH
            }
        val viewableRawMonitor = previewStages == processedPipelineStages(hdrMergeActive = false)
        val intentionalRawMonitorSplice = sensorOriginalLinear16Master &&
            viewableRawMonitor &&
            divergenceAfterStage == VideoSensorPipelineStage.SENSOR_RAW

        return when {
            intentionalRawMonitorSplice -> VideoPipelineParityEvidence(
                classification = VideoPipelineParityClassification.RAW_SENSOR_ORIGINAL_TO_DISPLAY_MONITOR_SPLICE,
                divergenceAfterStage = VideoSensorPipelineStage.SENSOR_RAW,
                encodedMain10TransportRequested = encodedMain10TransportRequested,
                rawSensorSampleBitDepthRange = RAW_SENSOR_SAMPLE_BIT_DEPTH_RANGE,
                rawSourceTransportContainerBitDepth = RAW_SOURCE_TRANSPORT_CONTAINER_BIT_DEPTH,
                rawStorageContainerBitDepthRange = RAW_STORAGE_CONTAINER_BIT_DEPTH_RANGE,
                rawStorageSelectionPolicy = RawStorageSelectionPolicy.SENSOR_WHITE_LEVEL_EXACT,
                rawMasterFidelity = RawMasterFidelity.SENSOR_CODE_EXACT_LINEAR
            )
            recordingCore == previewCore -> VideoPipelineParityEvidence(
                classification = VideoPipelineParityClassification.MATCHED_BEFORE_OUTPUT_TRANSFORM,
                divergenceAfterStage = null,
                encodedMain10TransportRequested = encodedMain10TransportRequested,
                rawSensorSampleBitDepthRange = null,
                rawSourceTransportContainerBitDepth = null,
                rawStorageContainerBitDepthRange = null,
                rawStorageSelectionPolicy = RawStorageSelectionPolicy.NOT_APPLICABLE,
                rawMasterFidelity = RawMasterFidelity.NOT_APPLICABLE
            )
            else -> VideoPipelineParityEvidence(
                classification = VideoPipelineParityClassification.PROCESSED_ROUTE_DIVERGED_BEFORE_OUTPUT_TRANSFORM,
                divergenceAfterStage = divergenceAfterStage,
                encodedMain10TransportRequested = encodedMain10TransportRequested,
                rawSensorSampleBitDepthRange = null,
                rawSourceTransportContainerBitDepth = null,
                rawStorageContainerBitDepthRange = null,
                rawStorageSelectionPolicy = RawStorageSelectionPolicy.NOT_APPLICABLE,
                rawMasterFidelity = RawMasterFidelity.NOT_APPLICABLE,
                validationWarning = "Preview and recording color parity diverged before the output transform splice."
            )
        }
    }

    private val RAW_SENSOR_SAMPLE_BIT_DEPTH_RANGE = 10..16
    private const val RAW_SOURCE_TRANSPORT_CONTAINER_BIT_DEPTH = 16
    private val RAW_STORAGE_CONTAINER_BIT_DEPTH_RANGE = 12..16

    private fun resolveHdrColorSpace(normalizedHdrMode: String): String =
        when (normalizedHdrMode) {
            "NDR10+" -> "Rec.2020-NDR10+"
            "NIVUKXVISION" -> "Rec.2020-NIVUKX"
            "APEX_SHARP",
            "APEX_ZERO",
            "APEX_CINEMA" -> "Rec.2020-NIVUKX-APEX"
            else -> "Rec.2020-NDR10+"
        }

    private fun resolveHdrDeliveryKind(normalizedHdrMode: String): VideoPipelineDeliveryKind =
        if (isProprietaryNdrMode(normalizedHdrMode) || NeoNdrTruePqTransportPolicy.enabled) {
            VideoPipelineDeliveryKind.HDR_PQ
        } else {
            VideoPipelineDeliveryKind.HDR_HLG
        }

    private fun resolveHdrToneMap(normalizedHdrMode: String): VideoPipelineToneMap =
        if (isProprietaryNdrMode(normalizedHdrMode) || NeoNdrTruePqTransportPolicy.enabled) {
            VideoPipelineToneMap.BT2100_PQ
        } else {
            VideoPipelineToneMap.BT2100_HLG
        }

    private fun resolveHdrDynamicRangeMode(
        normalizedHdrMode: String,
        hardware: VideoModeHardwareCapabilities
    ): HdrDynamicRangeMode =
        when (normalizedHdrMode) {
            "NDR10+",
            "NIVUKXVISION",
            "APEX_SHARP",
            "APEX_ZERO",
            "APEX_CINEMA" -> HdrDynamicRangeMode.SDR
            else -> HdrDynamicRangeMode.SDR
        }

    private fun isProprietaryNdrMode(normalizedHdrMode: String): Boolean =
        normalizedHdrMode == "NDR10+" ||
            normalizedHdrMode == "NIVUKXVISION" ||
            normalizedHdrMode == "APEX_SHARP" ||
            normalizedHdrMode == "APEX_ZERO" ||
            normalizedHdrMode == "APEX_CINEMA"

    private fun captureTruthSignature(
        deliveryKind: VideoPipelineDeliveryKind,
        previewKind: VideoPipelinePreviewKind,
        colorSpace: String,
        hdrMode: String,
        tenBit: Boolean,
        log: Boolean,
        raw: Boolean,
        hdrMetadata: Boolean
    ): String =
        "${deliveryKind.name}|${previewKind.name}|$colorSpace|hdr=$hdrMode|tenBit=$tenBit|log=$log|raw=$raw|hdrMetadata=$hdrMetadata"
}

private object ContractHardwareValidator {
    private val physicalMetadataCache = IdentitySnapshotCache<Boolean, VideoModeHardwareCapabilities>()

    fun resolve(
        characteristics: CameraCharacteristics?,
        gpu10BitSupported: Boolean,
        explicitCapabilities: VideoModeHardwareCapabilities?
    ): VideoModeHardwareCapabilities {
        explicitCapabilities?.let {
            return it.copy(
                tenBitSupported = it.tenBitSupported && gpu10BitSupported,
                tenBitEncoderSupported = it.tenBitEncoderSupported && gpu10BitSupported,
                fp16Supported = it.fp16Supported && gpu10BitSupported
            )
        }
        if (characteristics == null) {
            return VideoModeHardwareCapabilities.optimistic(gpu10BitSupported)
        }
        // Capture-result/scene updates can request this contract every frame. Camera2 rebuilds its
        // complete StreamConfigurationMap on get(), even though these characteristics are immutable.
        // Resolve once per actual snapshot + GPU capability; do not change any admission decision.
        return physicalMetadataCache.resolve(characteristics, gpu10BitSupported) {
            resolvePhysicalSnapshot(characteristics, gpu10BitSupported)
        }
    }

    private fun resolvePhysicalSnapshot(
        characteristics: CameraCharacteristics,
        gpu10BitSupported: Boolean
    ): IdentitySnapshotCache.Computed<VideoModeHardwareCapabilities> {
        var completeRead = true
        val capabilities = runCatching {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        }.onFailure { completeRead = false }.getOrDefault(emptySet())
        val rawSupported = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val manualSupported = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val manualPostProcessingSupported = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)

        val dynamicProfiles = dynamicRangeProfilesFrom(characteristics) { completeRead = false }
        val nativeP010OutputSupported =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    characteristics
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(ImageFormat.YCBCR_P010)
                        ?.isNotEmpty() == true
                }.onFailure { completeRead = false }.getOrDefault(false)
            } else {
                false
            }
        val cameraTenBitTransportSupported = nativeP010OutputSupported ||
            dynamicProfiles.any { it != VideoDynamicRangeProfile.SDR }

        val awbModes = runCatching {
            characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty()
        }.onFailure { completeRead = false }.getOrDefault(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8))

        val arrangementVal = runCatching {
            characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        }.onFailure { completeRead = false }.getOrNull()
        val sensorArrangement = when (arrangementVal) {
            0 -> "RGGB"
            1 -> "GRBG"
            2 -> "GBRG"
            3 -> "BGGR"
            4 -> "RGB"
            5 -> "MONO"
            6 -> "NIR"
            else -> "UNKNOWN"
        }

        val resolved = VideoModeHardwareCapabilities(
            rawSupported = rawSupported,
            manualSensorSupported = manualSupported,
            tenBitSupported = gpu10BitSupported && cameraTenBitTransportSupported,
            tenBitEncoderSupported = gpu10BitSupported,
            supportedDynamicRangeProfiles = dynamicProfiles,
            wideGamutSdrSupported = cameraTenBitTransportSupported,
            fp16Supported = gpu10BitSupported,
            fp32Supported = false,
            awbAvailableModes = awbModes,
            manualPostProcessingSupported = manualPostProcessingSupported,
            sensorColorFilterArrangement = sensorArrangement,
            preferredNeoHdrCaptureTransport =
                com.neoproc.neoproccamx.camera.NdrCaptureTransportPolicy.resolve(
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    device = Build.DEVICE,
                    product = Build.PRODUCT,
                    hdr10Supported = VideoDynamicRangeProfile.HDR10 in dynamicProfiles,
                    hdr10PlusSupported = VideoDynamicRangeProfile.HDR10_PLUS in dynamicProfiles,
                    hlg10Supported = VideoDynamicRangeProfile.HLG10 in dynamicProfiles
                )
        )
        return IdentitySnapshotCache.Computed(resolved, cacheable = completeRead)
    }

    private fun dynamicRangeProfilesFrom(
        characteristics: CameraCharacteristics,
        onReadFailure: () -> Unit
    ): Set<VideoDynamicRangeProfile> {
        val profiles = mutableSetOf(VideoDynamicRangeProfile.SDR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val androidProfiles = runCatching {
                characteristics
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    ?.supportedProfiles
                    .orEmpty()
            }.onFailure { onReadFailure() }.getOrDefault(emptySet())
            androidProfiles.forEach { profile ->
                when (profile) {
                    DynamicRangeProfiles.HLG10 -> profiles += VideoDynamicRangeProfile.HLG10
                    DynamicRangeProfiles.HDR10 -> profiles += VideoDynamicRangeProfile.HDR10
                    DynamicRangeProfiles.HDR10_PLUS -> profiles += VideoDynamicRangeProfile.HDR10_PLUS
                    DynamicRangeProfiles.STANDARD -> profiles += VideoDynamicRangeProfile.SDR
                }
            }
        }
        return profiles
    }
}
