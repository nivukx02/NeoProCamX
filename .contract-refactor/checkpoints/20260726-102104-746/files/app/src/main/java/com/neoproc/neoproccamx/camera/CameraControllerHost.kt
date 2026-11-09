package com.neoproc.neoproccamx.camera

import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.os.Handler
import android.util.Range
import android.util.Size
import android.view.Surface
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.Npv10CaptureSourcePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executor

/**
 * CameraControllerHost — Shared state bridge for coordinator-based architecture.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * COORDINATOR BRIDGE INTERFACE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Exposes the mutable state and Camera2 objects that all three coordinators
 * (CameraDeviceOrchestrator, SessionCoordinator, RequestProcessor) need.
 *
 * This interface eliminates circular dependencies between coordinators by
 * giving each one a read/write window into the shared CameraController state.
 *
 * Thread safety contract: All mutable fields follow the same threading model
 * as the original CameraController — Camera2 callbacks on bgHandler,
 * state mutations on Main via CoroutineScope.
 */
interface CameraControllerHost {

    // ── Camera2 Core Objects ─────────────────────────────────────────────────
    val cameraManager: CameraManager
    var cameraDevice: CameraDevice?
    var captureSession: CameraCaptureSession?
    var previewBuilder: CaptureRequest.Builder?

    // ── Threading ────────────────────────────────────────────────────────────
    var backgroundHandler: Handler?
    val cameraCallbackExecutor: Executor
    val coroutineScope: CoroutineScope
    fun runOnCameraThread(action: () -> Unit)

    // ── State Flows ──────────────────────────────────────────────────────────
    val stateFlow: MutableStateFlow<CameraState>
    val paramsFlow: MutableStateFlow<CameraParams>
    val resultFlow: MutableStateFlow<LiveResult?>
    val frameGeometryFlow: MutableStateFlow<FrameGeometry?>
    fun transitionCameraState(state: CameraState, reason: String? = null)

    // ── Camera Info (populated by loadCharacteristics) ────────────────────────
    var currentCameraId: String
    var facing: Int
    var characteristics: CameraCharacteristics?
    var activeArray: Rect?
    var currentFocalLength: Float
    var hardwareLevel: Int
    var highEndHardwareSupported: Boolean
    var pixelArraySize: Size?

    // ── Capabilities ─────────────────────────────────────────────────────────
    var supportsRaw: Boolean
    var supportsManualSensor: Boolean
    var supportsOIS: Boolean
    var supportsVideoStabilization: Boolean
    var supportsHdrSceneMode: Boolean
    var supportsTonemapContrastCurve: Boolean
    var ultraHighResRawSupported: Boolean
    var minZoom: Float
    var maxZoom: Float
    var maxAfRegions: Int
    var maxAeRegions: Int
    var isoRange: Range<Int>
    var exposureRange: Range<Long>
    var evRange: Range<Int>
    var minFocusDist: Float
    var fpsList: Array<Range<Int>>

    // ── Size Catalogs ────────────────────────────────────────────────────────
    var photoSizes: List<Size>
    var maxPhotoSize: Size?
    var previewSizes: List<Size>
    var recordingSizes: List<Size>
    var videoSizes: List<Size>
    var rawSizes: List<Size>
    var maxRawSize: Size?
    var maxResolutionRawSize: Size?
    var activeArrayRawSize: Size?
    var maxYuvSize: Size?

    // ── Surfaces (access via getPreviewSurface / setPreviewSurface / setRecordSurface) ─
    var jpegReader: ImageReader?
    var rawReader: ImageReader?
    var yuvReader: ImageReader?
    var hardwareImportReader: ImageReader?

    // ── Manual Controls ──────────────────────────────────────────────────────
    var manualIso: Int
    var manualExposureNs: Long
    var manualFocusDist: Float
    var manualWbKelvin: Int
    var flashMode: FlashMode
    var evIndex: Int
    var zoomRatio: Float
    var aeAwbLocked: Boolean

    // ── Preview Look ─────────────────────────────────────────────────────────
    var previewLogLookEnabled: Boolean
    var previewHdrLookEnabled: Boolean
    var previewRawLookEnabled: Boolean

    // ── Tonemap Curves (populated by loadCharacteristics) ────────────────────
    var previewLogToneCurve: TonemapCurve?
    var previewHdrToneCurve: TonemapCurve?
    var previewRawToneCurve: TonemapCurve?
    var recordingLogToneCurve: TonemapCurve?
    var recordingHdrToneCurve: TonemapCurve?
    var recordingRawToneCurve: TonemapCurve?

    // ── Focus State ──────────────────────────────────────────────────────────
    var aiDrivenFocusDist: Float
    var persistentAfRegion: Rect?
    var persistentAeRegion: Rect?

    // ── Capture State ────────────────────────────────────────────────────────
    var targetOutputAspectRatio: Float?
    var stillCaptureJpegEnabled: Boolean
    var stillCaptureRawEnabled: Boolean

    // ── Retry & Recovery State ───────────────────────────────────────────────
    var initializationAttempts: Int
    var reopenAttempts: Int
    var sessionRecoveryPending: Boolean
    var conservativeSessionFallback: Boolean
    var recordingSafeSessionFallback: Boolean
    var lastOpenReadyCallback: (() -> Unit)?
    var lastOpenErrorCallback: ((String) -> Unit)?

    // ── Session Generation ───────────────────────────────────────────────────
    val sessionGeneration: java.util.concurrent.atomic.AtomicLong

    // ── Callbacks ────────────────────────────────────────────────────────────
    var onYuvFrame: ((ImageReader) -> Unit)?
    var onHardwareFrame: ((ImageReader) -> Unit)?
    var onJpegReady: ((ImageReader) -> Unit)?
    var onRawReady: ((ImageReader) -> Unit)?
    var onCaptureResult: ((TotalCaptureResult) -> Unit)?
    var onStillCaptureFailed: ((String) -> Unit)?
    var onCameraPipelineWarning: ((String) -> Unit)?

    // ── Cross-coordinator Callbacks ──────────────────────────────────────────
    /** Called by DeviceOrchestrator after device opens to trigger session creation. */
    fun onDeviceOpened(device: CameraDevice, onReady: () -> Unit, onError: (String) -> Unit)

    /** Called by SessionCoordinator after session configures to start preview. */
    fun onSessionConfigured(session: CameraCaptureSession, generation: Long, onConfigured: () -> Unit)

    /** Called to trigger reader setup before session creation. */
    fun setupReaders()

    /** Called to release non-essential camera resources on close. */
    fun releaseCameraResources(clearPreviewSurface: Boolean)

    /** Arms and awaits definitive Camera2 device/session onClosed acknowledgements. */
    fun beginCameraRelease(): CameraReleaseTicket
    fun acknowledgeCameraDeviceClosed(device: CameraDevice)
    fun awaitCameraRelease(ticket: CameraReleaseTicket, timeoutMs: Long): Boolean
    fun isCameraReleaseFullyAcknowledged(ticket: CameraReleaseTicket): Boolean
    fun pendingCameraReleaseDescription(ticket: CameraReleaseTicket): String

    /** Called to start the preview freeze watchdog. */
    fun startPreviewWatchdog()

    /** Called to stop the preview freeze watchdog. */
    fun stopPreviewWatchdog()

    /** Called to clear pending in-flight refresh. */
    fun clearPendingInFlightRefresh()

    /** Called to refresh frame geometry after characteristics change. */
    fun refreshFrameGeometry()

    /** Called to build tonemap curves after characteristics load. */
    fun buildTonemapCurves(maxCurvePoints: Int)

    /** Called to start preview streaming. */
    fun startPreview(
        expectedGeneration: Long = sessionGeneration.get(),
        expectedSession: CameraCaptureSession? = captureSession
    )

    // ── AI AutoFocus Extensions ──────────────────────────────────────────────
    fun setAiDrivenFocusDistance(dist: Float)
    fun clearAiDrivenFocusDistance()
    fun setSubjectMeteringRegions(af: Rect?, ae: Rect?)
    fun clearFocusRegion()
    fun triggerSubjectAutoFocus(
        focusRegion: Rect,
        exposureRegion: Rect,
        focusNormalizedX: Float,
        focusNormalizedY: Float
    )

    fun captureStill(
        captureRaw: Boolean,
        captureJpeg: Boolean,
        overrides: StillCaptureOverrides? = null
    )

    fun captureStillBurst(
        captureRaw: Boolean,
        overridesList: List<StillCaptureOverrides>
    )

    fun stillOutputAspectRatio(): Float

    // ── UI/ViewModel Bridge ──────────────────────────────────────────────────
    fun setISO(iso: Int)
    fun setAutoISO()
    fun setExposure(ns: Long)
    fun setAutoExposure()
    fun setLockedExposure(ns: Long)
    fun setWB(kelvin: Int)
    /** Manual white balance with an explicit green-magenta tint (P0.1 Pro-Control accuracy). */
    fun setWB(kelvin: Int, tint: Int)
    fun setAutoWB()
    fun setFocusDist(dist: Float)
    fun setAutoFocus()
    fun setEV(index: Int)
    fun setFlash(mode: FlashMode)
    fun setZoom(ratio: Float)
    fun setFocusRegion(region: android.graphics.Rect?)
    fun setAeAwbLock(locked: Boolean)
    fun isAeAwbLocked(): Boolean
    
    fun setNoiseReductionEnabled(enabled: Boolean)
    fun setVideoDetailProfile(profile: VideoDetailProfile)
    fun setProcessedVideoSceneQuality(motionRisk: Float, noiseRisk: Float)
    fun setFaceDetectionEnabled(enabled: Boolean)
    fun setStabilizationMode(mode: StabilizationMode)
    fun setJpegQuality(quality: Int)
    fun setRawVideoCaptureEnabled(enabled: Boolean)
    fun setNpv10TenBitCaptureEnabled(enabled: Boolean)
    fun setNpv10CaptureTargetSize(size: Size?)
    fun isNpv10TenBitSessionConfigured(size: Size): Boolean
    fun resolveNpv10CaptureSourcePlan(
        selectedResolution: Size,
        rawModeActive: Boolean = false
    ): Npv10CaptureSourcePlan
    fun processedVideoQualityEvidence(): Map<String, String>
    fun setThermalResourceCeiling(ceiling: Float)
    /**
     * Closed-loop adaptive highlight protection (NDR10+/NIVUKX): the live scene average luma (0..1) and
     * near-clipped highlight fraction (0..1) from the analysis stream. Deepens the highlight-protect EV
     * bias only when real clipping is present. No-op in SDR / when the adaptive-highlight flag is off.
     */
    fun setHdrExposureSceneMetrics(
        averageLuma: Float,
        highlightClipFraction: Float,
        redHighlightClipFraction: Float = 0f,
        analysisValid: Boolean = true
    )
    fun setHdrDynamicRangeMode(mode: HdrDynamicRangeMode)
    fun describeHdrDynamicRangeState(): HdrDynamicRangeState
    fun setCinemaFidelityOverridesEnabled(enabled: Boolean)
    fun setPreviewLookControls(logEnabled: Boolean, hdrEnabled: Boolean, rawEnabled: Boolean)
    fun setVideoPreviewActive(active: Boolean)
    
    fun setPreviewViewport(width: Int, height: Int, contentAspectRatio: Float?)
    fun setAnalysisTargetSize(size: Size?)
    /**
     * Request (or release) the CPU analysis YUV stream that feeds the HUD monitors
     * (histogram / waveform / focus-peaking / zebra) during preview. Enabling it hot-rebuilds the
     * capture session to attach a small downscaled analysis reader; disabling it reverts to the
     * default preview-only graph. No-op during active recording (the encoded graph owns the streams).
     */
    fun setPreviewAnalysisRequested(enabled: Boolean)
    fun setHardwareImportTargetSize(size: Size?)
    fun setHardwareImportP010SourceEnabled(enabled: Boolean)
    fun supportsHardwareImportP010Source(size: Size, fps: Int): Boolean
    fun setInternalRecordingHardwareImportEnabled(enabled: Boolean)
    fun setPreviewContinuityRecordSurfaceEnabled(enabled: Boolean)
    fun setRecordingReadoutOverrideSize(size: Size?)
    fun setRecordingEncoderTargetSize(size: Size?)
    fun setRecordingSensorPixelModeMaximumResolution(enabled: Boolean)
    fun setRawPreviewCaptureEnabled(enabled: Boolean)
    fun setRecordSurface(surface: android.view.Surface?)
    fun setPreviewSurface(surface: android.view.Surface?)
    fun getPreviewSurface(): android.view.Surface?
    
    fun startVideoCapture()
    fun stopVideoCapture()
    fun drainRawReaderSynchronously()
    
    fun switchFacing()
    fun selectCamera()
    fun setFPS(fps: Int): Int
    
    fun triggerTapAF(nx: Float, ny: Float)
    fun setDeviceRotationDegrees(degrees: Int)
    
    fun resetPreviewActivityTracking()
    fun hasRecentPreviewActivity(): Boolean
    fun videoOrientationHint(): Int
    
    val hardwareLevelName: String
    val focusDistanceRange: Float
    
    fun destroy()
}
