package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.ai.FocusTarget
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
    val histogramEnabled: Boolean = false,
    val waveformEnabled: Boolean = false,
    val zebraEnabled: Boolean = false,
    val peakingEnabled: Boolean = false,
    val aspect: PreviewAspectRatio = PreviewAspectRatio.AUTO,
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
data class DiagnosticOverlayState(
    val showHistogram: Boolean = true,
    val showWaveform: Boolean = true,
    val showZebra: Boolean = true,
    val zebraThreshold: Float = 0.88f,
    val peakingEnabled: Boolean = true,
    val peakingIntensity: Float = 0.55f
)

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
    val showHistogram: Boolean = true,
    val showWaveform: Boolean = true,
    val showZebra: Boolean = true,
    val zebraThreshold: Float = 0.88f,
    val peakingEnabled: Boolean = true,
    val peakingIntensity: Float = 0.55f,
    val manualControlsVisible: Boolean = false,
    val previewAspect: PreviewAspectRatio = PreviewAspectRatio.AUTO,
    val gpuRecordState: GpuRecordingState = GpuRecordingState.Idle,
    val hardwareWarning: String? = null,
    val shutterGuidance: String? = null,
    val thermalTier: HighEndRuntimeTier = HighEndRuntimeTier.CINEMA,
    val thermalThrottled: Boolean = false
) {
    val diagnosticOverlay: DiagnosticOverlayState
        get() = DiagnosticOverlayState(
            showHistogram = showHistogram,
            showWaveform = showWaveform,
            showZebra = showZebra,
            zebraThreshold = zebraThreshold,
            peakingEnabled = peakingEnabled,
            peakingIntensity = peakingIntensity
        )

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
    object ToggleHist : UiAction()
    object ToggleWaveform : UiAction()
    object ToggleZebra : UiAction()
    data class SetPeakingIntensity(val value: Float) : UiAction()
    object TogglePeaking : UiAction()
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
 * Peaking overlay region with center and radius in normalised
 * (0..1) coordinates.
 */
internal data class PeakingRegion(
    val x: Float,
    val y: Float,
    val radius: Float
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
)

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
    val noiseReductionEnabled: Boolean,
    val stabilizationMode: StabilizationMode,
    val lastIso: Int,
    val lastShutterNs: Long,
    val lastWhiteBalanceKelvin: Int,
    val rawEnabled: Boolean,
    val gridMode: String,
    val histogramVisible: Boolean,
    val waveformVisible: Boolean,
    val zebraVisible: Boolean,
    val zebraThreshold: Float,
    val peakingEnabled: Boolean,
    val peakingIntensity: Float,
    val logEnabled: Boolean,
    val videoHdrMode: String,
    val videoColorSpace: String,
    val videoDetailProfile: VideoDetailProfile,
    val videoMode: String,
    val rawVideoCompression: String,
    val videoBitrateMode: VideoBitrateMode,
    val colorProfile: String,
    val lutEnabled: Boolean,
    val lutProfile: String,
    val customLutPath: String?,
    val aiEnhancementEnabled: Boolean,
    val previewAspectRatio: PreviewAspectRatio,
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
