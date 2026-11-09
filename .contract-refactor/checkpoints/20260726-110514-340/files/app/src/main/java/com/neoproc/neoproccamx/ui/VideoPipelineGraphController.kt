package com.neoproc.neoproccamx.ui

import android.media.MediaFormat
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState

internal enum class VideoDeliveryKind {
    SDR,
    HDR,
    LOG,
    RAW
}

internal enum class VideoDenoiseProfile {
    ENCODED_VIDEO_STRICT,
    ZERO_GRAIN_CLARITY_V20,
    HDR_CLEAN,
    LOG_DETAIL,
    RAW_TRUTHFUL
}

internal enum class VideoSharpnessProfile {
    FOCUSED_DETAIL,
    NATURAL_DETAIL,
    EXTREME_DETAIL,
    MOTION_LOCKED_MICRO_DETAIL_V20,
    HDR_HIGHLIGHT_SAFE,
    LOG_EDGE_PRESERVE,
    RAW_MONITORING
}

internal enum class VideoCameraRequestFamily {
    IDLE_PREVIEW,
    VIDEO_PREVIEW_RECORDING_PARITY,
    RAW_SENSOR_TRUTHFUL
}

internal data class VideoPipelineGraphRequest(
    val appMode: AppMode,
    val cinemaMode: CinemaVideoMode,
    val rawVideoActive: Boolean,
    val logEnabled: Boolean,
    val requestedHdrMode: String,
    val preferredColorSpace: String,
    val tenBitRequested: Boolean,
    val previewWidth: Int,
    val previewHeight: Int,
    val encoderWidth: Int,
    val encoderHeight: Int,
    val fps: Int,
    val recordingState: GpuRecordingState,
    val videoDetailProfile: VideoDetailProfile? = null,
    val gpu10BitSupported: Boolean = true,
    val hardwareCapabilities: VideoModeHardwareCapabilities? = null,
    val displayMonitoringLabel: String = ""
)

internal data class VideoPipelineContract(
    val mode: AppMode,
    val cinemaMode: CinemaVideoMode,
    val deliveryKind: VideoDeliveryKind,
    val requestedHdrMode: String,
    val effectiveHdrMode: String,
    val logEnabled: Boolean,
    val rawEnabled: Boolean,
    val tenBitRequested: Boolean,
    val previewWidth: Int,
    val previewHeight: Int,
    val encoderWidth: Int,
    val encoderHeight: Int,
    val fps: Int,
    val colorStandard: Int,
    val colorTransfer: Int,
    val colorRange: Int,
    val cameraRequestFamily: VideoCameraRequestFamily,
    val recordingGradePreview: Boolean,
    val denoiseProfile: VideoDenoiseProfile,
    val sharpnessProfile: VideoSharpnessProfile,
    val deliveryState: VideoFormatDeliveryState,
    val graphSignature: String
)

internal object VideoPipelineGraphController {

    fun resolve(request: VideoPipelineGraphRequest): VideoPipelineContract {
        val deliveryState = VideoFormatDeliveryPolicy.resolve(
            uiMode = request.appMode,
            cinemaMode = request.cinemaMode,
            rawVideoActive = request.rawVideoActive,
            logEnabled = request.logEnabled,
            requestedHdrMode = request.requestedHdrMode,
            preferredColorSpace = request.preferredColorSpace,
            tenBitActive = request.tenBitRequested,
            gpu10BitSupported = request.gpu10BitSupported,
            hardwareCapabilities = request.hardwareCapabilities
        )
        val deliveryKind = resolveDeliveryKind(deliveryState)
        val colorStandard = resolveColorStandard(deliveryKind, deliveryState)
        val colorTransfer = resolveColorTransfer(deliveryKind, deliveryState.effectiveHdrMode)
        val colorRange = MediaFormat.COLOR_RANGE_FULL
        val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(request.appMode)
        val cameraRequestFamily = when (deliveryKind) {
            VideoDeliveryKind.RAW -> VideoCameraRequestFamily.RAW_SENSOR_TRUTHFUL
            VideoDeliveryKind.SDR,
            VideoDeliveryKind.HDR,
            VideoDeliveryKind.LOG -> if (videoSurface) {
                VideoCameraRequestFamily.VIDEO_PREVIEW_RECORDING_PARITY
            } else {
                VideoCameraRequestFamily.IDLE_PREVIEW
            }
        }
        val recordingGradePreview = VideoRenderProfileQualityHintPolicy.useRecordingGradeRenderProfile(
            appMode = request.appMode,
            recordingState = request.recordingState
        )
        val denoiseProfile = when (deliveryKind) {
            VideoDeliveryKind.SDR -> if (request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL) {
                VideoDenoiseProfile.ZERO_GRAIN_CLARITY_V20
            } else {
                VideoDenoiseProfile.ENCODED_VIDEO_STRICT
            }
            VideoDeliveryKind.HDR -> VideoDenoiseProfile.HDR_CLEAN
            VideoDeliveryKind.LOG -> VideoDenoiseProfile.LOG_DETAIL
            VideoDeliveryKind.RAW -> VideoDenoiseProfile.RAW_TRUTHFUL
        }
        val sharpnessProfile = when (deliveryKind) {
            VideoDeliveryKind.SDR -> resolveSdrSharpnessProfile(request.videoDetailProfile)
            VideoDeliveryKind.HDR -> VideoSharpnessProfile.HDR_HIGHLIGHT_SAFE
            VideoDeliveryKind.LOG -> VideoSharpnessProfile.LOG_EDGE_PRESERVE
            VideoDeliveryKind.RAW -> VideoSharpnessProfile.RAW_MONITORING
        }
        return VideoPipelineContract(
            mode = request.appMode,
            cinemaMode = deliveryState.pipelineContract.cinemaMode,
            deliveryKind = deliveryKind,
            requestedHdrMode = request.requestedHdrMode,
            effectiveHdrMode = deliveryState.effectiveHdrMode,
            logEnabled = deliveryState.logVideoActive,
            rawEnabled = deliveryState.rawVideoActive,
            tenBitRequested = deliveryState.tenBitActive,
            previewWidth = request.previewWidth,
            previewHeight = request.previewHeight,
            encoderWidth = request.encoderWidth,
            encoderHeight = request.encoderHeight,
            fps = request.fps,
            colorStandard = colorStandard,
            colorTransfer = colorTransfer,
            colorRange = colorRange,
            cameraRequestFamily = cameraRequestFamily,
            recordingGradePreview = recordingGradePreview,
            denoiseProfile = denoiseProfile,
            sharpnessProfile = sharpnessProfile,
            deliveryState = deliveryState,
            graphSignature = graphSignature(
                request = request,
                deliveryState = deliveryState,
                deliveryKind = deliveryKind,
                colorStandard = colorStandard,
                colorTransfer = colorTransfer,
                colorRange = colorRange,
                cameraRequestFamily = cameraRequestFamily,
                denoiseProfile = denoiseProfile,
                sharpnessProfile = sharpnessProfile
            )
        )
    }

    private fun resolveDeliveryKind(deliveryState: VideoFormatDeliveryState): VideoDeliveryKind =
        when {
            deliveryState.rawVideoActive -> VideoDeliveryKind.RAW
            deliveryState.logVideoActive -> VideoDeliveryKind.LOG
            deliveryState.effectiveHdrMode != "OFF" -> VideoDeliveryKind.HDR
            else -> VideoDeliveryKind.SDR
        }

    private fun resolveSdrSharpnessProfile(profile: VideoDetailProfile?): VideoSharpnessProfile =
        when (profile) {
            VideoDetailProfile.FOCUSED_DETAIL -> VideoSharpnessProfile.FOCUSED_DETAIL
            VideoDetailProfile.EXTREME_DETAIL -> VideoSharpnessProfile.EXTREME_DETAIL
            VideoDetailProfile.NATURAL_DETAIL -> VideoSharpnessProfile.MOTION_LOCKED_MICRO_DETAIL_V20
            null -> VideoSharpnessProfile.NATURAL_DETAIL
        }

    private fun resolveColorStandard(
        deliveryKind: VideoDeliveryKind,
        deliveryState: VideoFormatDeliveryState
    ): Int =
        when {
            deliveryKind == VideoDeliveryKind.HDR -> MediaFormat.COLOR_STANDARD_BT2020
            deliveryKind == VideoDeliveryKind.LOG -> MediaFormat.COLOR_STANDARD_BT2020
            deliveryKind == VideoDeliveryKind.SDR && isWideGamutColorSpace(deliveryState.effectiveColorSpace) ->
                MediaFormat.COLOR_STANDARD_BT2020
            else -> MediaFormat.COLOR_STANDARD_BT709
        }

    private fun isWideGamutColorSpace(colorSpace: String): Boolean {
        val key = colorSpace.lowercase()
        return key.contains("2020") ||
            key.contains("2100") ||
            key.contains("p3") ||
            key.contains("aces") ||
            key.contains("neo") ||
            key.contains("wide gamut") ||
            key.contains("widegamut")
    }

    private fun resolveColorTransfer(
        deliveryKind: VideoDeliveryKind,
        @Suppress("UNUSED_PARAMETER") hdrMode: String
    ): Int =
        if (deliveryKind == VideoDeliveryKind.HDR) {
            // This is the CAPTURE/preview transport: the sensor delivers HLG10 (the device's 10-bit
            // HDR capture profile), so the pipeline/preview stays HLG. The RECORDED-FILE transport is
            // decided separately by RecordingHdrVideoLookPolicy: under NeoNdrTruePqTransportPolicy
            // (Fork A, default) the offscreen record shader converts this HLG10 to NeoProCamX's own
            // genuine BT.2100 PQ (ST.2084) and tags the file PQ. Capture HLG10 -> recorded PQ is the
            // intended two-stage path (never HLG pixels wearing a PQ tag).
            MediaFormat.COLOR_TRANSFER_HLG
        } else {
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        }

    private fun graphSignature(
        request: VideoPipelineGraphRequest,
        deliveryState: VideoFormatDeliveryState,
        deliveryKind: VideoDeliveryKind,
        colorStandard: Int,
        colorTransfer: Int,
        colorRange: Int,
        cameraRequestFamily: VideoCameraRequestFamily,
        denoiseProfile: VideoDenoiseProfile,
        sharpnessProfile: VideoSharpnessProfile
    ): String = listOf(
        request.appMode.name,
        deliveryState.pipelineContract.cinemaMode.name,
        deliveryKind.name,
        deliveryState.effectiveHdrMode,
        deliveryState.logVideoActive.toString(),
        deliveryState.rawVideoActive.toString(),
        deliveryState.tenBitActive.toString(),
        "${request.previewWidth}x${request.previewHeight}",
        "${request.encoderWidth}x${request.encoderHeight}",
        request.fps.toString(),
        colorStandard.toString(),
        colorTransfer.toString(),
        colorRange.toString(),
        cameraRequestFamily.name,
        denoiseProfile.name,
        sharpnessProfile.name,
        deliveryState.pipelineContract.sessionSignature
    ).joinToString(separator = "|")
}
