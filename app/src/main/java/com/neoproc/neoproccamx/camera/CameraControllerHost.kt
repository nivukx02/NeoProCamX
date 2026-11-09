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

data class SoftwareAePriorityMeterRequirement(
    val required: Boolean,
    val controlRevision: Long
)

/** Immutable identity of the first frame produced by one recording-active P010 request. */
data class Npv10TenBitProducerProof(
    val publicationGeneration: Long,
    val sessionGeneration: Long,
    val sequenceId: Int,
    val firstFrameNumber: Long,
    val firstFrameSensorTimestampNs: Long
)

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
    var supportsPreviewStabilization: Boolean
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

    /**
     * Arm a one-shot reopen for a transient camera-service outage. The host owns the
     * CameraManager availability callback so the registration follows its background-thread lifecycle.
     */
    fun armCameraAvailabilityRecovery(reason: String)

    /** Clear any armed availability recovery when a camera opens or an intentional close begins. */
    fun clearCameraAvailabilityRecovery()

    // ── Session Generation ───────────────────────────────────────────────────
    val sessionGeneration: java.util.concurrent.atomic.AtomicLong

    // ── Callbacks ────────────────────────────────────────────────────────────
    var onYuvFrame: ((ImageReader) -> Unit)?
    var onHardwareFrame: ((ImageReader) -> Unit)?
    var onJpegReady: ((ImageReader) -> Unit)?
    var onRawReady: ((ImageReader) -> Unit)?
    var onCaptureResult: ((TotalCaptureResult) -> Unit)?
    var onRawCaptureResultWithSessionGeneration: ((TotalCaptureResult, Long) -> Unit)?
    var onNiv99CaptureResultWithSessionGeneration: ((TotalCaptureResult, Long) -> Unit)?
    var onCaptureResultWithSessionGeneration: ((TotalCaptureResult, Long) -> Unit)?
    var onSessionGenerationAdvanced: ((Long) -> Unit)?
    var onSessionGenerationConfigured: ((Long) -> Unit)?
    var onStillCaptureFailed: ((StillCaptureFailure) -> Unit)?
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

    /**
     * Invalidates the closed device's session/request lineage before an automatic reopen while
     * preserving the selected preview surface and recording intent.
     */
    fun prepareCameraSessionForDeviceRecovery()

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
    fun setAiDrivenFocusDistance(dist: Float, confidence: Float)
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
        token: StillCaptureRequestToken,
        captureRaw: Boolean,
        captureJpeg: Boolean,
        overrides: StillCaptureOverrides? = null
    )

    fun captureStillBurst(
        tokens: List<StillCaptureRequestToken>,
        captureRaw: Boolean,
        overridesList: List<StillCaptureOverrides>
    )

    /** Token-validated release acknowledgement; may restore a still-owned preview/session state. */
    fun acknowledgeStillJpegBufferReleased(token: StillCaptureRequestToken)

    /** Token-validated RAW release; resumes any repeating RAW stream only after Image.close(). */
    fun acknowledgeStillRawBufferReleased(token: StillCaptureRequestToken)

    /** True while a still-owned repeating/max-resolution session has not been restored yet. */
    fun isStillCaptureSessionRestorationPending(): Boolean

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
    fun setSensorLinearNdrCaptureEnabled(enabled: Boolean)
    fun setNpv10TenBitCaptureEnabled(enabled: Boolean)
    fun setNpv10CaptureTargetSize(size: Size?)
    fun setModeSwitchStandbyAnalysisOutput(size: Size?, tenBit: Boolean)
    fun isNpv10TenBitSessionConfigured(size: Size): Boolean
    /**
     * Stronger than session membership: proves that the active recording request targets the exact
     * P010 reader and has delivered a current-generation sensor frame at [fps].
     */
    fun isNpv10TenBitProducerReady(size: Size, fps: Int): Boolean = false
    /**
     * Returns the exact recording-request boundary used to reject idle/pre-arm P010 pixel evidence.
     * A null result means the producer is not currently authoritative.
     */
    fun npv10TenBitProducerProof(size: Size, fps: Int): Npv10TenBitProducerProof? = null
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
        pqHighlightPressureFraction: Float = 0f,
        pqRedHighlightPressureFraction: Float = 0f,
        sourcePqCandidate: Boolean = false,
        analysisValid: Boolean = true,
        measuredAtElapsedRealtimeMs: Long = 0L,
        /** Exact Camera2 session provenance; stale generations are rejected before control use. */
        sourceSessionGeneration: Long = -1L
    )
    /** RAW_SENSOR-domain meter for proprietary sensor-linear NDR; no YUV/HLG input is involved. */
    fun setSensorLinearNdrExposureSceneMetrics(
        averageLuma: Float,
        medianLuma: Float,
        p95Luma: Float,
        p99Luma: Float,
        p99Red: Float,
        p99Green: Float,
        p99Blue: Float,
        highlightClipFraction: Float,
        redHighlightClipFraction: Float,
        sampleCount: Int,
        sensorSensitivityIso: Int = 0,
        exposureTimeNs: Long = 0L,
        noiseS: Float = 0f,
        noiseO: Float = 0f,
        reportedExposureCompensationIndex: Int? = null,
        measuredAtElapsedRealtimeMs: Long = 0L,
        /** Exact Camera2 session provenance; stale generations are rejected before control use. */
        sourceSessionGeneration: Long = -1L,
        /** Exact RAW frame/control provenance for software ISO/shutter-priority authority. */
        sourceSensorTimestampNs: Long = 0L,
        sourceControlRevision: Long = -1L
    )
    /** True only while one manual sensor axis needs the app-owned live complementary-axis meter. */
    fun isSoftwareAePriorityMeterRequired(): Boolean
    fun softwareAePriorityMeterRequirement(): SoftwareAePriorityMeterRequirement
    /** Exact revision attached to the immutable request that produced [result], or -1 if unproven. */
    fun softwareAeControlRevisionFor(result: TotalCaptureResult): Long
    fun setHdrDynamicRangeMode(
        mode: HdrDynamicRangeMode,
        ndrExposureGovernorEnabled: Boolean = false
    )
    fun describeHdrDynamicRangeState(): HdrDynamicRangeState
    fun setCinemaFidelityOverridesEnabled(enabled: Boolean)
    fun setProfessionalVideoControlsActive(enabled: Boolean)
    fun setPreviewLookControls(logEnabled: Boolean, hdrEnabled: Boolean, rawEnabled: Boolean)
    fun setVideoPreviewActive(active: Boolean)
    /**
     * Selects the optical task used on the next safe camera-open boundary. Implementations must not
     * reopen or replace a lens while a device/session is active.
     */
    fun setLensSelectionTaskHint(hint: LensSelectionTaskHint)
    
    fun setPreviewViewport(width: Int, height: Int, contentAspectRatio: Float?)
    fun setAnalysisTargetSize(size: Size?)
    /**
     * Request or release the CPU analysis YUV stream used by proprietary NDR exposure/highlight
     * processing. Enabling it hot-rebuilds the capture session with a small downscaled reader;
     * disabling it restores the default preview-only graph. No-op during active recording.
     */
    fun setPreviewAnalysisRequested(enabled: Boolean)
    /**
     * Removes only the optional analysis reader from an active recording request while retaining
     * that Surface in the configured session. The encoded/RAW source, visible preview, controls,
     * resolution, cadence, bit depth and bitrate remain untouched.
     */
    fun setRecordingThermalAnalysisIsolationEnabled(enabled: Boolean)
    /**
     * Builds the final processed-recording Camera2 graph before the clip clock starts and preserves
     * that graph through the clip. It is cleared by failure cleanup, stop, or camera release.
     */
    fun setProcessedRecordingSessionPreparationActive(enabled: Boolean)
    fun setHardwareImportTargetSize(size: Size?)
    fun setHardwareImportP010SourceEnabled(enabled: Boolean)
    fun supportsHardwareImportP010Source(size: Size, fps: Int): Boolean
    /** True only when the exact P010 reader Surface belongs to the current, settled Camera2 session. */
    fun isHardwareImportP010SessionConfigured(size: Size): Boolean
    fun setInternalRecordingHardwareImportEnabled(enabled: Boolean)
    fun setPreviewContinuityRecordSurfaceEnabled(enabled: Boolean)
    fun setRecordingReadoutOverrideSize(size: Size?)
    fun setRecordingEncoderTargetSize(size: Size?)
    fun setRecordingSensorPixelModeMaximumResolution(enabled: Boolean)
    fun setRawVideoPrearmEnabled(enabled: Boolean)
    fun setRawPreviewCaptureEnabled(enabled: Boolean)
    fun refreshRepeatingRequest()
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
