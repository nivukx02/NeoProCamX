package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.ai.FocusTarget
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.RawCaptureResolutionMode
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.recording.AudioSrc
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.ui.overlays.GridMode

internal data class ActiveDeliveryContractCacheKey(
    val appMode: AppMode,
    val cinemaMode: CinemaVideoMode,
    val rawVideoActive: Boolean,
    val logEnabled: Boolean,
    val requestedHdrMode: String,
    val preferredColorSpace: String,
    val tenBitActive: Boolean,
    val cameraId: String,
    val characteristicsIdentity: Int,
    val gpuTenBitSupported: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int
)

internal data class ActiveVulkanContractCacheKey(
    val deliveryKey: ActiveDeliveryContractCacheKey,
    val nativePreviewLutEnabled: Boolean,
    val nativePreviewLutSize: Int,
    val colorProfile: String,
    val cinemaDebayerActive: Boolean,
    val highLoadPreviewActive: Boolean,
    val previewFpsHint: Float,
    val cameraPreviewHdrTransportMode: String
)

internal data class DeferredVulkanPreviewConfigRequest(
    val geometry: FrameGeometry?,
    val force: Boolean,
    val renderState: VulkanRenderRequestSnapshot
) {
    val requestSequence: Long
        get() = renderState.requestSequence

    val profileGeneration: Long
        get() = renderState.authority.generation

    val hdrModeAuthority: String
        get() = renderState.authority.hdrMode
}

internal enum class PreviewPipelineOwner {
    NATIVE, PERSISTENT_SDR, PERSISTENT_HDR, GL_SDR, GL_HDR, DIRECT, UNKNOWN
}


// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// CameraViewModel — Extracted Types
//
// Data classes, enums and value objects that were previously nested
// inside CameraViewModel.  Extracting them reduces the God Object
// surface area and makes them independently testable.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Mutable recording configuration snapshot used during session
 * setup and live adjustments.
 */
data class RecordingConfig(
    var width: Int = 3840,
    var height: Int = 2160,
    var fps: Int = 25,
    var bitrate: Int = 50_000_000,
    var codec: VideoCodecType = VideoCodecType.HEVC,
    var audioSource: AudioSrc = AudioSrc.CAMCORDER
)

/**
 * Captures the full video-feature state so it can be
 * snapshotted, compared, and restored across mode switches.
 */
data class VideoFeatureState(
    val hdrEnabled: Boolean = false,
    val logEnabled: Boolean = false,
    val rawEnabled: Boolean = false,
    val aspect: PreviewAspectRatio = PreviewAspectRatio.FOUR_THREE,
    val colorProfile: String = "Natural",
    val lutEnabled: Boolean = false,
    val lutProfile: String = "Neutral Film",
    val colorSpace: String = "Rec.709",
    val hdrMode: String = "OFF",
    val cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
    val rawCompression: RawCompressionMode = RawCompressionMode.HIGH_COMPRESSION
)

/**
 * UI-visible state bundle published to the view layer.
 */
data class ManualCaptureState(
    val manualControlsVisible: Boolean = false
)

data class SystemTelemetryState(
    val hardwareWarning: String? = null,
    val shutterGuidance: String? = null,
    val thermalTier: HighEndRuntimeTier = HighEndRuntimeTier.CINEMA,
    val thermalThrottled: Boolean = false
)

data class CameraUiState(
    val uiMode: AppMode = AppMode.VIDEO,
    val rawEnabled: Boolean = false,
    val gridMode: GridMode = GridMode.NONE,
    val logEnabled: Boolean = false,
    val videoHdrMode: String = "OFF",
    val colorProfile: String = "Natural",
    val aiEnhancement: Boolean = false,
    val manualControlsVisible: Boolean = false,
    val previewAspect: PreviewAspectRatio = PreviewAspectRatio.FOUR_THREE,
    val gpuRecordState: GpuRecordingState = GpuRecordingState.Idle,
    val hardwareWarning: String? = null,
    val shutterGuidance: String? = null,
    val thermalTier: HighEndRuntimeTier = HighEndRuntimeTier.CINEMA,
    val thermalThrottled: Boolean = false
) {
    val manualCapture: ManualCaptureState
        get() = ManualCaptureState(
            manualControlsVisible = manualControlsVisible
        )

    val systemTelemetry: SystemTelemetryState
        get() = SystemTelemetryState(
            hardwareWarning = hardwareWarning,
            shutterGuidance = shutterGuidance,
            thermalTier = thermalTier,
            thermalThrottled = thermalThrottled
        )
}

sealed class UiAction {
    object ToggleRaw : UiAction()
    object ToggleHdrMode : UiAction()
    data class SetLogEnabled(val enabled: Boolean) : UiAction()
    object CycleGrid : UiAction()
    data class SetColorProfile(val profile: String) : UiAction()
    data class SetAiEnhancementEnabled(val enabled: Boolean) : UiAction()
    object ToggleRecording : UiAction()
    object PauseRecording : UiAction()
    object ResumeRecording : UiAction()
    data class AdjustZoom(val zoomFactor: Float) : UiAction()
    data class SetManualFocus(val target: FocusTarget?) : UiAction()
    object SwitchFacing : UiAction()

    /**
     * ELITE Rank-2 — one-shot engage of the 24p/180° "Cinematic" bundle (24 fps + 180° shutter +
     * HYBRID stabilization + LOG + NDR10+/10-bit, each gated by live hardware availability). Engage-only
     * by design: it never applies the INERT decision, so it cannot destructively reset user settings.
     */
    object ApplyCinematicBundle : UiAction()
}

/**
 * State of a focus + exposure lock (long-press lock).
 */
internal data class FocusExposureLockState(
    val focusX: Float,
    val focusY: Float,
    val baseExposureNs: Long,
    val previousExposureNs: Long,
    val previousFocusDist: Float,
    val previousAeAwbLock: Boolean,
    val previousManualFocus: FocusTarget?
)

/**
 * Snapshot of which AI tracking stages are currently active.
 * Used for dedup — only dispatches changes to the pipeline
 * when the state actually differs.
 */
internal data class AppliedAiTrackingState(
    val faceDetectionEnabled: Boolean,
    val poseTrackingEnabled: Boolean,
    val segmentationEnabled: Boolean,
    val enhancementEnabled: Boolean,
    val eyeTrackingEnabled: Boolean,
    val depthEnabled: Boolean,
    val sceneDetectionEnabled: Boolean,
    val aiAutoFocusEnabled: Boolean
) {
    /** True only when at least one model-backed stage can consume frames. */
    fun requiresModelWarmup(): Boolean =
        faceDetectionEnabled || poseTrackingEnabled || segmentationEnabled || enhancementEnabled ||
            depthEnabled || sceneDetectionEnabled
}

/**
 * Request key for the preview LUT computation.
 * When this changes the LUT is recomputed on a background thread.
 */
internal data class PreviewLutRequest(
    val neutralVideoPreview: Boolean,
    val uiMode: AppMode,
    val recordingActive: Boolean,
    val previewFpsBucket: Int,
    val rawVideoActive: Boolean,
    val hdrEnabled: Boolean,
    val colorProfile: String,
    val lutEnabled: Boolean,
    val lutProfile: String,
    val customLutPath: String?,
    val logEnabled: Boolean,
    val effectiveHdrMode: String,
    val effectiveColorSpace: String,
    val truthfulColorDelivery: Boolean,
    val cinemaModeLabel: String,
    val saturation: Float,
    val contrast: Float,
    val brightness: Float,
    val hueShift: Float,
    val highlights: Float,
    val shadows: Float
)

/**
 * Cache key for the resolution status label to avoid
 * rebuilding the same string every frame.
 */
internal data class ResolutionStatusCacheKey(
    val uiMode: AppMode,
    val cinemaMode: CinemaVideoMode,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val preparedWidth: Int,
    val preparedHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val upscaledToRequested: Boolean,
    val cineSourceWidth: Int,
    val cineSourceHeight: Int
)

/**
 * Fingerprint of user preferences — used to skip redundant
 * [CameraViewModel.applyPreferences] calls when nothing changed.
 */
internal data class PreferencesRefreshSignature(
    val uiMode: AppMode,
    val videoCodec: String,
    val videoSize: Size,
    val videoFps: Int,
    val videoBitrate: Int,
    val audioSource: String,
    val tenBitRecordingEnabled: Boolean,
    val uncompressedCinemaPipelineEnabled: Boolean,
    val sdrConversionPipelineEnabled: Boolean,
    val noiseReductionEnabled: Boolean,
    val stabilizationMode: StabilizationMode,
    val lastIso: Int,
    val lastShutterNs: Long,
    val lastWhiteBalanceKelvin: Int,
    val lastWhiteBalanceTint: Int,
    val lastFocusDistance: Float,
    val lastExposureCompensation: Int,
    val rawEnabled: Boolean,
    val gridMode: String,
    val logEnabled: Boolean,
    val videoHdrMode: String,
    val videoColorSpace: String,
    val videoDetailProfile: VideoDetailProfile,
    val videoMode: String,
    val rawVideoCompression: String,
    val rawResolutionMode: RawCaptureResolutionMode,
    val videoBitrateMode: VideoBitrateMode,
    val colorProfile: String,
    val lutEnabled: Boolean,
    val lutProfile: String,
    val customLutPath: String?,
    val aiEnhancementEnabled: Boolean,
    val aiMotionCompensationEnabled: Boolean,
    val previewAspectRatio: PreviewAspectRatio,
    val safeAreaGuidesEnabled: Boolean,
    val saturation: Float,
    val contrast: Float,
    val brightness: Float,
    val faceDetectionEnabled: Boolean,
    val eyeAutofocusEnabled: Boolean
)

/**
 * Top-level app mode.  Photo modes were removed; the app is video-only.
 */
enum class AppMode { VIDEO, PRO }
