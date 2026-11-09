package com.neoproc.neoproccamx.ui

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import java.lang.ref.WeakReference
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.neoproc.neoproccamx.ai.AICameraFrameResult
import com.neoproc.neoproccamx.NeoProCamXApp
import com.neoproc.neoproccamx.ai.AIAutoFocusCoordinator
import com.neoproc.neoproccamx.ai.AIProcessor
import com.neoproc.neoproccamx.ai.DetectedFace
import com.neoproc.neoproccamx.ai.DepthData
import com.neoproc.neoproccamx.ai.EnhancementResult
import com.neoproc.neoproccamx.ai.FocusTarget
import com.neoproc.neoproccamx.ai.FocusTargetType
import com.neoproc.neoproccamx.ai.PrimarySubject
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.ai.SubjectMeteringTarget
import com.neoproc.neoproccamx.ai.SubjectType
import com.neoproc.neoproccamx.apex.ApexMode
import com.neoproc.neoproccamx.apex.ApexPipelineOrchestrator
import com.neoproc.neoproccamx.apex.ApexPipelineState
import com.neoproc.neoproccamx.apex.ApexRuntimeSceneMapper
import com.neoproc.neoproccamx.camera.CameraBackgroundClosePolicy
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.camera.NdrAdaptiveHighlightExposurePolicy
import com.neoproc.neoproccamx.camera.Camera2CaptureFrameEvidence
import com.neoproc.neoproccamx.camera.Camera2FrameLineageAccumulator
import com.neoproc.neoproccamx.camera.Camera2RequestTag
import com.neoproc.neoproccamx.camera.CameraRect
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.camera.FlashMode
import com.neoproc.neoproccamx.camera.LiveResult
import com.neoproc.neoproccamx.camera.RecordingStopPreviewPolicy
import com.neoproc.neoproccamx.camera.VideoColdOpenHardwareImportSessionFlag
import com.neoproc.neoproccamx.cinema.CinemaResolutionCatalog
import com.neoproc.neoproccamx.capture.CaptureEvent
import com.neoproc.neoproccamx.capture.StillCapture
import com.neoproc.neoproccamx.color.ColorChannel
import com.neoproc.neoproccamx.color.ColorScience
import com.neoproc.neoproccamx.color.LUTProcessor
import com.neoproc.neoproccamx.color.RecordGradeBaker
import com.neoproc.neoproccamx.color.NeoLogCurve
import com.neoproc.neoproccamx.color.NeoLogProfilePolicy
import com.neoproc.neoproccamx.color.NormalSdrColorCalibrationPolicy
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.color.VideoProfileIntentPolicy
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.StartupSmoothnessPolicy
import com.neoproc.neoproccamx.pipeline.AICameraPipeline
import com.neoproc.neoproccamx.pipeline.FramePipelineManager
import com.neoproc.neoproccamx.pipeline.RawVideoManager
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.pipeline.SceneRenderProfileEngine
import com.neoproc.neoproccamx.pipeline.VideoDetailSharpeningPolicy
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.SubjectDetailEnhancementPolicy
import com.neoproc.neoproccamx.pipeline.SubjectDetailEnhancementRequest
import com.neoproc.neoproccamx.recording.*
import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import com.neoproc.neoproccamx.camera.CameraStreamSize
import com.neoproc.neoproccamx.camera.RawCaptureResolutionMode
import com.neoproc.neoproccamx.camera.RawCaptureResolutionModePolicy
import com.neoproc.neoproccamx.camera.RawCaptureTier
import com.neoproc.neoproccamx.camera.RawMaxResViabilityPolicy
import com.neoproc.neoproccamx.camera.RawSensorMaxResCapabilityPolicy
import com.neoproc.neoproccamx.camera.ResolvedRawCaptureTier
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.ui.RecordingSourceResolutionPlan
import com.neoproc.neoproccamx.ui.RecordingSourceResolutionPolicy
import com.neoproc.neoproccamx.rendering.*
import com.neoproc.neoproccamx.rendering.RecordDevelopLivePipelinePolicy
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.ui.controls.CinemaManualControlPolicy
import com.neoproc.neoproccamx.ui.controls.CinemaManualMeteringMode
import com.neoproc.neoproccamx.ui.overlays.GridMode
import com.neoproc.neoproccamx.utils.PreferencesManager
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import com.neoproc.neoproccamx.pipeline.MemoryProfiler
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.CINEMA_4K60_MIN_BITRATE_BPS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.CINEMA_AUTO_LOCK_STABLE_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.CINEMA_RUNTIME_SYSTEM_REFRESH_INTERVAL_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.CUSTOM_LUT_PROFILE
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.HIGH_END_MAX_BITRATE_BPS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.LOG_TEN_BIT_BITRATE_BOOST
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.MAX_CINEMA_RECORD_HEIGHT
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.MAX_CINEMA_RECORD_WIDTH
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.MIN_HIGH_END_JPEG_QUALITY
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.PHOTO_MODE_PREVIEW_FPS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.RAW_MODE_MAX_FPS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.RAW_MODE_MAX_LONG_EDGE
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.SHUTTER_RULE_EXPOSURE_DENOMINATOR
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.SHUTTER_RULE_TARGET_FPS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.SHUTTER_RULE_WARNING_TOLERANCE
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.SLOW_RECORDING_PREPARE_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.SLOW_RECORDING_START_TOTAL_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.SLOW_RECORDING_STOP_TOTAL_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.TAP_TO_FOCUS_HOLD_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.THERMAL_BITRATE_REDUCTION_FACTOR
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.THERMAL_GUARD_PREVIEW_MAX_AREA
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.THERMAL_GUARD_PREVIEW_MAX_LONG_EDGE
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.VIDEO_IDLE_PREVIEW_FPS
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private const val CAMERA2_FRAME_LINEAGE_MAX_TRACKED_CAPTURE_RESULTS = 240

private const val FORCE_UNIVERSAL_TEN_BIT_PROCESSED = true
private const val STRICT_GPU_VALIDATION_IDLE_RECHECK_MS = 250L

private data class ProcessedGpuRecordingValidationStats(
    val frameImportEnabled: Boolean,
    val encoderSurfaceBound: Boolean,
    val importedFrames: Long,
    val firstSuccessfulImportElapsedMs: Long,
    val lastSuccessfulImportElapsedMs: Long,
    val offscreenPathActive: Boolean,
    val offscreenPresentationTimeSet: Boolean
)

private fun VulkanImportStats.toProcessedGpuRecordingValidationStats(): ProcessedGpuRecordingValidationStats {
    val offscreenPathActive = offscreenAhbEglEncoderActive || offscreenEglSubmittedFrames > 0L
    val offscreenReady =
        offscreenPathActive &&
            offscreenEglEncoderSurfaceBound &&
            offscreenEglPresentationTimeSet
    val effectiveImportedFrames =
        if (offscreenPathActive) {
            maxOf(sessionImportedFrames, offscreenEglSubmittedFrames)
        } else {
            sessionImportedFrames
        }
    return ProcessedGpuRecordingValidationStats(
        frameImportEnabled = frameImportEnabled,
        encoderSurfaceBound = encoderSurfaceBound || offscreenReady,
        importedFrames = effectiveImportedFrames,
        firstSuccessfulImportElapsedMs = firstSuccessfulImportElapsedMs,
        lastSuccessfulImportElapsedMs = lastSuccessfulImportElapsedMs,
        offscreenPathActive = offscreenPathActive,
        offscreenPresentationTimeSet = !offscreenPathActive || offscreenEglPresentationTimeSet
    )
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = (app as NeoProCamXApp).preferencesManager

    // Expose from sub-systems
    val camera       = CameraController(app)
    val colorScience = ColorScience()
    private val aiAfCoordinator = AIAutoFocusCoordinator(camera)
    val pipeline     = FramePipelineManager()
    private val stillCapture = StillCapture(
        context = app,
        cameraController = camera,
        colorScience = colorScience,
        computationalProcessor = AIProcessor(),
        captureRecommendationProvider = { pipeline.captureRecommendation.value },
        aiEnhancementEnabledProvider = { effectiveAiEnhancementEnabled() },
        aiSceneTypeProvider = { _aiSceneType.value },
        aiHdrSuggestedProvider = { _aiHdrSuggested.value },
        debugValidationLabelProvider = { debugValidationLabel }
    )
    private val aiCameraPipeline = AICameraPipeline(
        app,
        object : AICameraPipeline.Listener {
            override fun onFrameProcessed(result: AICameraFrameResult) {
                enqueueAICameraFrameResult(result)
            }

            override fun onPipelineError(throwable: Throwable) {
                Log.w("CameraViewModel", "AICameraPipeline error", throwable)
            }
        }
    )
    private val vulkanRuntime = VulkanRuntimeManager()
    private val rawVideoManager = RawVideoManager(app, camera)
    private val renderProfileEngine = SceneRenderProfileEngine()
    private val apexPipelineState = ApexPipelineState()
    // SNCE v2.0 HVC live colour engine — bounded, fail-safe nudge to the preview/encoder grade.
    // s2-f05/CP47: confidence-gating ON (user-directed 2026-07-04) — scales the WB nudge DOWN when
    // illuminant-fusion confidence is low; only ever reduces, never widens the ±0.05/±0.06 ceiling.
    private val snceColorEngine = com.neoproc.neoproccamx.snce.SnceLiveColorController(confidenceGatingEnabled = true)
    @Volatile private var lastSncePushMs = 0L
    // CP-QV21 NRLI: push the bounded near-identity analytic look LUT once when the gate is on.
    @Volatile private var nrliLutPushed = false
    val recording = RecordingConfig()
    val gpuRecording = RecordingManager(app)
    private val npv10Recording = Npv10RecordingManager(app)
    private val recordingTransitionState = MutableStateFlow<GpuRecordingState>(GpuRecordingState.Idle)
    private val rawCustomRecordingState = MutableStateFlow<GpuRecordingState>(GpuRecordingState.Idle)
    private val rawCustomRecordingDuration = MutableStateFlow(0L)
    @Volatile private var rawCustomRecordingStartedElapsedMs = 0L
    @Volatile private var rawCustomRecordingAccumulatedMs = 0L
    private var rawCustomDurationJob: Job? = null
    @Volatile private var prearmedRecordingSurface: Surface? = null
    @Volatile private var prearmedRecordingSurfaceAttachedToSession: Boolean = false
    @Volatile private var prearmedRecordingSignature: RecordingPrearmSignature? = null
    @Volatile private var prearmedRecordingFeedPath: RecordingFeedPath? = null
    @Volatile private var lastRecordingPrearmReadinessLogSignature: String? = null
    @Volatile private var recordingReadyForClipTiming: Boolean = false
    private val recordingSurfacePrearmInFlight = AtomicBoolean(false)
    private val recordingSurfacePrearmForcedRefreshRequested = AtomicBoolean(false)
    private val recordingSurfacePrearmRetuneScheduled = AtomicBoolean(false)
    private val recordingStartInProgress = AtomicBoolean(false)
    private val recordingStartCancellationRequested = AtomicBoolean(false)
    private val recordingStopInProgress = AtomicBoolean(false)
    @Volatile private var recordingStartJob: Job? = null
    @Volatile private var npv10CapturePrearmedSignature: String? = null
    @Volatile private var npv10CaptureRefreshSignature: String? = null
    val thermalPolicyResolver = ThermalRuntimePolicyResolver()
    private val memoryProfiler = MemoryProfiler(app)
    private val conversionIsolation = CameraConversionIsolationCoordinator(
        dispatchMainAndWait = { action ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
            } else {
                val completed = CountDownLatch(1)
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    try { action() } finally { completed.countDown() }
                }
                check(completed.await(5L, TimeUnit.SECONDS)) { "Main thread did not accept conversion isolation" }
            }
        },
        captureActive = {
            recordingStartInProgress.get() || recordingStopInProgress.get() ||
                gpuRecordState.value is GpuRecordingState.Starting ||
                gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused ||
                gpuRecordState.value is GpuRecordingState.Stopping ||
                gpuRecordState.value is GpuRecordingState.Finalizing ||
                cameraState.value == CameraState.Recording
        },
        cameraClosed = { cameraState.value == CameraState.Closed && cameraCloseJob?.isActive != true },
        closeCamera = ::closeCamera,
        reopenCamera = ::openCamera,
        trace = { Log.i("ConversionIsolation", "event=$it owner=${System.identityHashCode(this).toString(16)} state=${cameraState.value}") }
    )

    // ── Extracted Coordinators ───────────────────────────────────
    private val highEndPack = HighEndFeaturePackCoordinator(
        prefs = prefs,
        camera = camera,
        recording = recording,
        actions = object : HighEndFeaturePackActions {
            override fun setAiEnhancementEnabled(enabled: Boolean) = this@CameraViewModel.setAiEnhancementEnabled(enabled)
            override fun setNoiseReductionEnabled(enabled: Boolean) = this@CameraViewModel.setNoiseReductionEnabled(enabled)
            override fun setFaceDetectionEnabled(enabled: Boolean) = this@CameraViewModel.setFaceDetectionEnabled(enabled)
            override fun setEyeAutofocusEnabled(enabled: Boolean) = this@CameraViewModel.setEyeAutofocusEnabled(enabled)
            override fun setHistogramEnabled(enabled: Boolean) = this@CameraViewModel.setHistogramEnabled(enabled)
            override fun setWaveformEnabled(enabled: Boolean) = this@CameraViewModel.setWaveformEnabled(enabled)
            override fun setZebraEnabled(enabled: Boolean) = this@CameraViewModel.setZebraEnabled(enabled)
            override fun setZebraThresh(threshold: Float) = this@CameraViewModel.setZebraThresh(threshold)
            override fun setPeakingEnabled(enabled: Boolean) = this@CameraViewModel.setPeakingEnabled(enabled)
            override fun setPeakingIntensity(intensity: Float) = this@CameraViewModel.setPeakingIntensity(intensity)
            override fun setGridMode(mode: GridMode) = this@CameraViewModel.setGridMode(mode)
            override fun setStabilizationMode(mode: StabilizationMode) = this@CameraViewModel.setStabilizationMode(mode)
            override fun setAudioSrc(source: AudioSrc) = this@CameraViewModel.setAudioSrc(source)
            override fun setVideoResolution(size: Size) = this@CameraViewModel.setVideoResolution(size)
            override fun setFPS(fps: Int) = this@CameraViewModel.setFPS(fps)
            override fun setVideoBitrateMode(mode: VideoBitrateMode) = this@CameraViewModel.setVideoBitrateMode(mode)
            override fun setVideoBitrate(bps: Int) = this@CameraViewModel.setVideoBitrate(bps)
            override fun setCinemaVideoMode(mode: CinemaVideoMode) = this@CameraViewModel.setCinemaVideoMode(mode)
            override fun setVideoHdrMode(mode: String) = this@CameraViewModel.setVideoHdrMode(mode)
            override fun setTenBitRecordingEnabled(enabled: Boolean) = this@CameraViewModel.setTenBitRecordingEnabled(enabled)
            override fun setVideoColorSpace(colorSpace: String) = this@CameraViewModel.setVideoColorSpace(colorSpace)
            override fun setColorProfile(profile: String) = this@CameraViewModel.setColorProfile(profile)
            override fun setRawVideoCompressionMode(mode: RawCompressionMode) = this@CameraViewModel.setRawVideoCompressionMode(mode)
            override fun setLutProfile(profile: String) = this@CameraViewModel.setLutProfile(profile)
            override fun setLutEnabled(enabled: Boolean) = this@CameraViewModel.setLutEnabled(enabled)
            override fun setISO(iso: Int) = this@CameraViewModel.setISO(iso)
            override fun setExposure(ns: Long) = this@CameraViewModel.setExposure(ns)
            override fun setWB(kelvin: Int) = this@CameraViewModel.setWB(kelvin)
            override fun setEV(ev: Int) = this@CameraViewModel.setEV(ev)
            override fun applyAutomaticVideoBitrateIfNeeded(force: Boolean) =
                this@CameraViewModel.applyAutomaticVideoBitrateIfNeeded(force)
            override fun updateCinemaRuntimeStats(previewFps: Float) =
                this@CameraViewModel.updateCinemaRuntimeStats(lastPreviewFpsSample)
            override fun availableVideoSizes(): List<Size> =
                this@CameraViewModel.availableVideoSizes()
        }
    )

    private val hostImpl = object : CameraViewModelHost {
        override val application = app
        override val viewModelScope = this@CameraViewModel.viewModelScope
        override val prefs = this@CameraViewModel.prefs
        override val camera = this@CameraViewModel.camera
        override val gpuRecording = this@CameraViewModel.gpuRecording
        override val rawVideoManager = this@CameraViewModel.rawVideoManager
        override val vulkanRuntime = this@CameraViewModel.vulkanRuntime
        override val pipeline = this@CameraViewModel.pipeline
        override val gpuRecordState get() = this@CameraViewModel.gpuRecordState
        override val cameraState get() = this@CameraViewModel.cameraState
        override val uiMode get() = this@CameraViewModel._uiMode
        override val cinemaVideoMode get() = this@CameraViewModel._cinemaVideoMode
        override val rawOn get() = this@CameraViewModel._rawOn
        override val logOn get() = this@CameraViewModel._logOn
        override val rawVideoCompression get() = this@CameraViewModel._rawVideoCompression
        override val cinemaRuntimeStats get() = this@CameraViewModel._cinemaRuntimeStats
        override val recordingConfig get() = this@CameraViewModel.recording
        
        override suspend fun runOnMain(block: () -> Unit) = this@CameraViewModel.runOnMain(block)
        override fun runOnMainIfNeeded(block: () -> Unit) = this@CameraViewModel.runOnMainIfNeeded(block)
        override suspend fun updatePreviewViewAwait(block: suspend CameraVulkanPreviewView.() -> Unit) = this@CameraViewModel.updatePreviewViewAwait(block)
        override fun handleCameraError(throwable: Throwable, operation: String, userVisibleMessage: String?) = this@CameraViewModel.handleCameraError(throwable, operation, userVisibleMessage)
        override fun maybeApplyThermalGuard(thermalLabel: String) = this@CameraViewModel.maybeApplyThermalGuard(thermalLabel)
        override fun updateShutterRuleGuidance() = this@CameraViewModel.updateShutterRuleGuidance()
        
        override fun syncGeometryTargets() = this@CameraViewModel.syncGeometryTargets()
        override fun refreshPreviewPipelineIfNeeded(forceSessionRefresh: Boolean) = this@CameraViewModel.refreshPreviewPipelineIfNeeded(forceSessionRefresh)
        override fun syncEffectiveRecordingColorProfile() = this@CameraViewModel.syncEffectiveRecordingColorProfile()
        override suspend fun syncPreviewPerformanceModeOnMain() = this@CameraViewModel.syncPreviewPerformanceModeOnMain()
        override fun syncActivePreviewFrameRate() = this@CameraViewModel.syncActivePreviewFrameRate()
        override fun syncCameraPreviewLookControls() = this@CameraViewModel.syncCameraPreviewLookControls()
        
        override fun applyRecordingOutputAspectOverride(size: Size?) = this@CameraViewModel.applyRecordingOutputAspectOverride(size)
        override fun clearRecordingReadoutOverride() = this@CameraViewModel.clearRecordingReadoutOverride()
        override fun applyRecordingCameraLookControls() = this@CameraViewModel.applyRecordingCameraLookControls()
        override fun releaseCinemaAutoLock(resetCameraState: Boolean, reason: String) = this@CameraViewModel.releaseCinemaAutoLock(resetCameraState, reason)
        override fun scheduleCenterAutoPrime() = this@CameraViewModel.scheduleCenterAutoPrime()
        
        override fun preferredRecordingSize(): Size = this@CameraViewModel.preferredRecordingSize()
        override fun preferredRecordingAnalysisSize(base: Size): Size = this@CameraViewModel.preferredRecordingAnalysisSize(base)
        override fun preferredPreviewAnalysisSize(): Size = this@CameraViewModel.preferredPreviewAnalysisSize()
        override fun isEffectiveVideoHdrRequested(): Boolean = this@CameraViewModel.isEffectiveVideoHdrRequested()
        override fun currentThermalLabel(): String = this@CameraViewModel.currentThermalLabel()
        
        override val lastRecordingSelection: ResolvedRecordingSelection? get() = this@CameraViewModel.lastRecordingSelection
        override val selectedVideoSize: Size? get() = this@CameraViewModel.selectedVideoSize
        override fun resolveRecordingSourcePlan(requestedOutput: Size) = this@CameraViewModel.resolveRecordingSourcePlan(requestedOutput)
    }

    private val modeController = CameraModeController(
        cameraController = camera,
        pipelineManager = pipeline,
        prefs = prefs
    )
    private val recordingController = CameraRecordingController(
        recordingManager = gpuRecording,
        pipelineManager = pipeline,
        prefs = prefs
    )
    private val previewController = CameraPreviewController(
        cameraController = camera,
        pipelineManager = pipeline,
        prefs = prefs
    )
    private val settingsController = CameraSettingsController(
        cameraController = camera,
        pipelineManager = pipeline,
        prefs = prefs
    )

    private val statsEngine = CinemaRuntimeStatsEngine(hostImpl) { stats ->
        _cinemaRuntimeStats.value = stats
    }



    // Persisted preview surface reference
    // Stored so we can re-set previewSurface on every camera re-open.
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var previewViewHolder: WeakReference<CameraVulkanPreviewView>? = null
    private var previewViewRef: CameraVulkanPreviewView?
        get() = previewViewHolder?.get()
        set(value) {
            previewViewHolder = value?.let { WeakReference(it) }
        }
    // Stored so onCleared() can removeThermalStatusListener; an anonymous listener would be unremovable and
    // pin this whole ViewModel via captured `this` for the life of the process.
    @Volatile private var thermalPowerManager: PowerManager? = null
    @Volatile private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var currentSceneRenderProfile: SceneRenderProfile? = null
    @Volatile private var selectedVideoSize: Size? = null
    @Volatile private var lastRecordingSelection: ResolvedRecordingSelection? = null
    @Volatile private var boundPreviewSize: Size? = null
    @Volatile private var currentRotationDegrees: Int = 0
    @Volatile private var currentPreviewStreamAspectRatio: Float? = null
    @Volatile private var closeCameraAfterRecordingStop = false
    @Volatile private var cameraCloseJob: Job? = null
    @Volatile private var recordingPreviewDetachInFlight = false
    @Volatile private var recordingPreviewDetachGeneration = 0L
    @Volatile private var pendingRecordingPreviewSurface: Surface? = null
    @Volatile private var recordingPreviewSurfaceSafelyDetached = false
    @Volatile private var recordingPreviewRebindInProgress = false
    @Volatile private var recordingOutputAspectRatioOverride: Float? = null
    @Volatile private var activeRecordingFeedPath: RecordingFeedPath? = null
    @Volatile private var lastValidatedGpuRecordingEvidence = ValidatedGpuRecordingEvidence.EMPTY
    @Volatile private var vulkanRecordingThroughputBlockedUntilElapsedMs = 0L
    @Volatile private var vulkanRecordingThroughputBlockedSignature = ""
    @Volatile private var vulkanRecordingDeviceBlockLogged = false
    @Volatile private var strictProcessedGpuValidationJob: Job? = null
    @Volatile private var lastPreviewFpsSample: Float = 0f
    @Volatile private var lastThermalMitigationAtMs: Long = 0L
    @Volatile private var lastGpuEnhancementUniforms: GpuImageEnhancementUniforms? = null
    @Volatile private var thermalAnalysisFpsCap: Float? = null
    @Volatile private var thermalPreviewMaxLongEdge: Int? = null
    @Volatile private var thermalPreviewMaxArea: Int? = null
    @Volatile private var performancePreviewMaxLongEdge: Int? = null
    @Volatile private var performancePreviewMaxArea: Int? = null
    @Volatile private var performanceAnalysisMaxLongEdge: Int? = null
    @Volatile private var previewPerformanceState = PreviewPerformanceState()
    @Volatile private var pendingZoomRatio: Float? = null
    @Volatile private var debugValidationLabel: String? = null
    @Volatile private var previewColorGainState = floatArrayOf(1f, 1f, 1f, 0.12f)
    @Volatile private var nativePreviewLutEnabled = false
    @Volatile private var nativePreviewLutSize = 0
    @Volatile private var lastVulkanPreviewRenderSignature: String? = null
    @Volatile private var lastPreviewColorUniformSha256: String = ""
    @Volatile private var lastRecordingColorUniformSha256: String = ""
    @Volatile private var lastPreviewGradeSha256: String = ""
    @Volatile private var lastRecordingGradeSha256: String = ""
    @Volatile private var lastMonitorDeliveryColorUniformDifferenceAllowed: Boolean = false
    @Volatile private var lastVideoPipelineGraphSignature: String? = null
    @Volatile private var lastRecordingPipelineParitySignature: String? = null
    @Volatile private var lastPixelProcessingRealitySignature: String? = null
    @Volatile private var lastCamera2SessionGraphParitySignature: String? = null
    @Volatile private var cachedPreviewLut: Pair<FloatArray, Int>? = null
    @Volatile private var cachedPreviewLutRequest: PreviewLutRequest? = null
    // WYSIWYG parity: the EXACT creative cube the offscreen-EGL recorder bakes into the SDR/PRO file
    // (film-print look, default-ON). Captured in syncGpuLutState; used as the preview compositor's
    // fallback cube when the neutral SDR/PRO preview would otherwise forward null (the "gray" gap).
    @Volatile private var activeSdrRecordCreativeLut: Pair<FloatArray, Int>? = null
    @Volatile private var pendingPreviewLutRequest: PreviewLutRequest? = null
    @Volatile private var previewLutSyncRevision = 0L
    private val pendingAICameraFrameResult = AtomicReference<AICameraFrameResult?>(null)
    private val aiFrameResultDrainScheduled = AtomicBoolean(false)
    @Volatile private var nativeVisiblePreviewActive = false
    @Volatile private var nativeVisiblePreviewConfirmed = false
    @Volatile private var nativePreviewFallbackUntilElapsedMs = 0L
    @Volatile private var nativePreviewFallbackCount = 0
    @Volatile private var nativePreviewRetryRequiresSurfaceReset = false
    @Volatile private var nativePreviewActivationElapsedMs = 0L
    @Volatile private var nativePreviewHandoffPending = false
    @Volatile private var nativePreviewSurfaceResizePending = false
    @Volatile private var nativePreviewCameraReleaseConfirmed = false
    @Volatile private var directPreviewSurfaceActive = false
    @Volatile private var cameraOpenRequestInFlight = false
    @Volatile private var pendingGeometrySyncAfterLayout = false
    @Volatile private var lastAppliedFrameGeometry: FrameGeometry? = null
    @Volatile private var lastAppliedNativeCompositorActive: Boolean? = null
    private var nativePreviewWatchdogJob: Job? = null
    private var nativePreviewRetryJob: Job? = null
    private var nativePreviewHandoffBindJob: Job? = null
    private var nativePreviewHandoffSurface: Surface? = null
    private var previewLutSyncJob: Job? = null
    private var aiWarmupJob: Job? = null
    private var recordingCapabilityWarmupJob: Job? = null
    @Volatile private var cachedAv1Supported = false
    @Volatile private var av1SupportResolved = false
    private var videoFeatureState = VideoFeatureState()
    private var aiFocusResumeJob: Job? = null
    private var autoFocusPrimeJob: Job? = null
    private var focusExposureLockState: FocusExposureLockState? = null
    @Volatile private var cinemaAutoLockCandidateStartElapsedMs: Long = 0L
    @Volatile private var cinemaAutoLockActive = false
    @Volatile private var cinemaAutoLockPreviousAeAwbLock = false
    @Volatile private var cinemaAutoLockPreviousFocusDistance = -1f
    @Volatile private var tapToFocusHoldUntilElapsedMs: Long = 0L
    private var rawVideoCallbacksInstalled = false
    private var savedCaptureResultCallback: ((TotalCaptureResult) -> Unit)? = null
    private val camera2FrameLineageLock = Any()
    private var camera2FrameLineageSessionGeneration = 0L
    private var camera2FrameLineageRequestRevision = 0L
    private var camera2FrameLineageTargetRoles: Set<String> = emptySet()
    private var camera2FrameLineagePipelineSignature = ""
    private var camera2FrameLineageAccumulator: Camera2FrameLineageAccumulator? = null
    private var camera2FrameLineageRecordingActive = false
    private var camera2FrameLineageTrackedCaptureResults = 0
    @Volatile private var lastCamera2FrameLineageMetadata: Map<String, String> = emptyMap()
    private var startupDeferredRestoreJob: Job? = null
    private var refreshPreferencesJob: Job? = null
    private val recordingSelectionMutationLock = Any()
    @Volatile private var lastAppliedAiTrackingState: AppliedAiTrackingState? = null
    @Volatile private var lastAppliedHighLoadPreviewMode: Boolean? = null
    @Volatile private var lastAppliedPreferencesSignature: PreferencesRefreshSignature? = null
    @Volatile private var startupFeatureGateActive = true
    @Volatile private var startupAiRampActive = true
    @Volatile private var startupDirectPreviewActive = true
    @Volatile private var lastCinemaRuntimeSystemRefreshElapsedMs = 0L
    @Volatile private var cachedCinemaRuntimeFreeBytes = 0L
    @Volatile private var cachedCinemaRuntimeThermalLabel = "Unknown"
    @Volatile private var cinemaRuntimeSystemStatsFetchInFlight = false
    @Volatile private var thermalBitrateReductionApplied = false
    @Volatile private var cachedResolutionStatusLabelKey: ResolutionStatusCacheKey? = null
    @Volatile private var cachedResolutionStatusLabelValue = ""
    private val manualControlDispatcher = CameraManualControlDispatcher()
    private val modeSwitchPreviewWorkCoalescer = ModeSwitchPreviewWorkCoalescer()

    // UI State
    private val _uiMode    = MutableStateFlow(AppMode.VIDEO)
    val uiMode: StateFlow<AppMode> = _uiMode
    private val _sdrQualityIntent = MutableStateFlow(SdrQualityIntent.NORMAL)

    private val _rawOn     = MutableStateFlow(false)
    val rawOnFlow: StateFlow<Boolean> = _rawOn

    private val _gridMode  = MutableStateFlow(GridMode.NONE)
    val gridMode: StateFlow<GridMode> = _gridMode

    // Monitoring overlays are product-locked OFF for the zero-drop recording profile.
    private val _showHist  = MutableStateFlow(false)
    val showHistogramFlow: StateFlow<Boolean> = _showHist

    private val _showWfm   = MutableStateFlow(false)
    val showWaveformFlow: StateFlow<Boolean> = _showWfm

    private val _showZebra = MutableStateFlow(false)
    val showZebraFlow: StateFlow<Boolean> = _showZebra

    private val _zebraT    = MutableStateFlow(0.88f)
    val zebraThreshold: StateFlow<Float> = _zebraT

    private val _peaking   = MutableStateFlow(false)
    val peakingEnabledFlow: StateFlow<Boolean> = _peaking

    private val _peakingIntensity = MutableStateFlow(0.55f)
    val peakingIntensityFlow: StateFlow<Float> = _peakingIntensity

    // Open-gate / safe-area composition guides (default off, restored from prefs on startup).
    private val _safeAreaGuides = MutableStateFlow(false)
    val safeAreaGuidesFlow: StateFlow<Boolean> = _safeAreaGuides

    private val _manualControlsVisible = MutableStateFlow(false)
    val manualControlsVisibleFlow: StateFlow<Boolean> = _manualControlsVisible
    private val _manualMeteringMode = MutableStateFlow(CinemaManualMeteringMode.MATRIX)
    internal val manualMeteringModeFlow: StateFlow<CinemaManualMeteringMode> = _manualMeteringMode.asStateFlow()

    private val _logOn     = MutableStateFlow(false)
    val logEnabledFlow: StateFlow<Boolean> = _logOn

    private val _videoHdrOn = MutableStateFlow(false)
    val videoHdrEnabledFlow: StateFlow<Boolean> = _videoHdrOn

    private val _colorProfile = MutableStateFlow("Natural")
    val colorProfileFlow: StateFlow<String> = _colorProfile
    private var colorSpaceSelectedThisSession = false
    private var colorScienceSelectedThisSession = false

    private val _lutEnabled = MutableStateFlow(false)

    private val _lutProfile = MutableStateFlow("Neutral Film")
    val lutProfile: StateFlow<String> = _lutProfile

    private val _customLutAvailable = MutableStateFlow(false)
    val customLutAvailable: StateFlow<Boolean> = _customLutAvailable

    private val _aiEnhancement = MutableStateFlow(effectiveAiEnhancementEnabled())
    val aiEnhancementFlow: StateFlow<Boolean> = _aiEnhancement
    private val _aiMotionCompensation = MutableStateFlow(prefs.aiMotionCompensationEnabled)
    val aiMotionCompensationFlow: StateFlow<Boolean> = _aiMotionCompensation.asStateFlow()

    private val _videoColorSpace = MutableStateFlow("Rec.709")
    val videoColorSpace: StateFlow<String> = _videoColorSpace
    private val _videoDetailProfile = MutableStateFlow(prefs.videoDetailProfile)
    val videoDetailProfile: StateFlow<VideoDetailProfile> = _videoDetailProfile.asStateFlow()

    private val _videoHdrMode = MutableStateFlow("OFF")
    val videoHdrMode: StateFlow<String> = _videoHdrMode

    private val _cinemaVideoMode = MutableStateFlow(CinemaVideoMode.PRO)
    val cinemaVideoMode: StateFlow<CinemaVideoMode> = _cinemaVideoMode

    private val _rawVideoCompression = MutableStateFlow(RawCompressionMode.HIGH_COMPRESSION)
    val rawVideoCompression: StateFlow<RawCompressionMode> = _rawVideoCompression

    private val _rawResolutionMode = MutableStateFlow(prefs.rawResolutionMode)
    val rawResolutionMode: StateFlow<RawCaptureResolutionMode> = _rawResolutionMode

    private val _videoBitrateMode = MutableStateFlow(VideoBitrateMode.AUTO)
    val videoBitrateMode: StateFlow<VideoBitrateMode> = _videoBitrateMode

    private val _uncompressedCinemaPipeline = MutableStateFlow(prefs.uncompressedCinemaPipelineEnabled)
    val uncompressedCinemaPipelineEnabled: StateFlow<Boolean> = _uncompressedCinemaPipeline.asStateFlow()

    private val _cinemaRuntimeStats = MutableStateFlow(CinemaRuntimeStats())
    val cinemaRuntimeStats: StateFlow<CinemaRuntimeStats> = _cinemaRuntimeStats

    /**
     * Honest, plain transport label for the recording HUD based on the ACTIVE feed path, so the
     * badge names the real working path (P010 / direct encoder surface / GPU import) instead of the
     * cryptic "CPU"/"UNPROVEN" elite vocabulary that reads as an error. Null when not recording.
     */
    fun activeRecordingTransportHudLabel(): String? =
        when (activeRecordingFeedPath) {
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER -> "P010"
            RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
            RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE -> "ENCODER_SURFACE_DIRECT"
            RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
            RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER -> "AHB_GPU_COMPATIBILITY"
            null -> null
        }

    private val _uiStateFlow = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiStateFlow.asStateFlow()
    private val actionChannel = Channel<UiAction>(
        capacity = CameraUiResponsivenessPolicy.UI_ACTION_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    private val _previewAspect = MutableStateFlow(PreviewAspectRatio.AUTO)
    val previewAspect: StateFlow<PreviewAspectRatio> = _previewAspect
    private val _aiFaces = MutableStateFlow<List<DetectedFace>>(emptyList())
    private val _aiDepthData = MutableStateFlow<DepthData?>(null)
    private val _aiPrimaryFocus = MutableStateFlow<FocusTarget?>(null)
    private val _aiPrimarySubject = MutableStateFlow<PrimarySubject?>(null)
    private val _aiSubjectMask = MutableStateFlow<SubjectMask?>(null)
    private val _aiSceneType = MutableStateFlow(SceneType.GENERAL)
    private val _aiHdrSuggested = MutableStateFlow(false)
    private val _aiLowLightSuggested = MutableStateFlow(false)
    private val _aiEnhancementResult = MutableStateFlow<EnhancementResult?>(null)
    private val _focusAssistVisual = MutableStateFlow(FocusAssistVisual.TAP)
    private val manualFocusOverride = MutableStateFlow<FocusTarget?>(null)
    private val activePeakingRegion = MutableStateFlow<PeakingRegion?>(null)

    // Sub-systems
    val cameraState:      StateFlow<CameraState>      = camera.state
    private val startupCoordinator = CameraStartupCoordinator(
        scope = viewModelScope,
        cameraState = cameraState,
        actions = object : CameraStartupActions {
            override fun startRecordingCapabilityWarmup() =
                this@CameraViewModel.startRecordingCapabilityWarmup()

            override fun selectCamera() {
                camera.selectCamera()
            }

            override fun applyStartupPreferences() =
                this@CameraViewModel.applyStartupPreferences()

            override fun completeStartupFastPath() =
                this@CameraViewModel.completeStartupFastPath()

            override fun releaseStartupNativePreviewGate() {
                startupDirectPreviewActive = false
            }

            override fun refreshPreviewPipelineIfNeeded() {
                if (previewSurface == null) return
                if (
                    cameraState.value == CameraState.Open ||
                        cameraState.value == CameraState.Streaming
                ) {
                    this@CameraViewModel.refreshPreviewPipelineIfNeeded()
                }
            }

            override fun isStartupFeatureGateActive(): Boolean = startupFeatureGateActive

            override fun completeStartupAiRamp() {
                startupAiRampActive = false
            }

            override fun applyAiTrackingPreferences() {
                this@CameraViewModel.applyAiTrackingPreferences()
            }

            override fun scheduleAiWarmUp(delayMs: Long, allowDuringStartupAiRamp: Boolean) {
                this@CameraViewModel.scheduleAiWarmUp(
                    delayMs = delayMs,
                    allowDuringStartupAiRamp = allowDuringStartupAiRamp
                )
            }
        }
    )
    val liveResult:       StateFlow<LiveResult?>      = camera.result
    val previewFrameTimestamps: SharedFlow<Long>      = camera.previewFrameTimestamps
    val cameraParams                                   = camera.params
    val gpuRecordState:   StateFlow<GpuRecordingState> =
        combine(
            gpuRecording.state,
            npv10Recording.state,
            rawCustomRecordingState,
            recordingTransitionState
        ) {
                gpuState,
                npv10State,
                rawState,
                transitionState ->
            when {
                transitionState !is GpuRecordingState.Idle -> transitionState
                rawState !is GpuRecordingState.Idle -> rawState
                npv10State !is GpuRecordingState.Idle -> npv10State
                else -> gpuState
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GpuRecordingState.Idle)
    val recordState:      StateFlow<RecordingState>   =
        gpuRecordState
            .map(RecordingStateBridge::toRecordingState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, RecordingState.Idle)
    val recDuration:      StateFlow<Long>             =
        combine(
            gpuRecording.duration,
            npv10Recording.duration,
            npv10Recording.state,
            rawCustomRecordingDuration,
            rawCustomRecordingState
        ) { gpuDuration, npv10Duration, npv10State, rawDuration, rawState ->
            if (rawState is GpuRecordingState.Recording ||
                rawState is GpuRecordingState.Paused ||
                rawState is GpuRecordingState.Stopping
            ) {
                rawDuration
            } else if (npv10State is GpuRecordingState.Recording ||
                npv10State is GpuRecordingState.Paused ||
                npv10State is GpuRecordingState.Stopping) {
                npv10Duration
            } else {
                gpuDuration
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val gpuRecDuration:   StateFlow<Long>             = gpuRecording.duration
    val rawVideoState                               = rawVideoManager.state
    val rawVideoFrameCount                          = rawVideoManager.frameCount
    val faces: StateFlow<List<DetectedFace>>     = _aiFaces.asStateFlow()
    val depthData: StateFlow<DepthData?>         = _aiDepthData.asStateFlow()
    val primaryFocus: StateFlow<FocusTarget?>    = _aiPrimaryFocus.asStateFlow()
    val primarySubject: StateFlow<PrimarySubject?> = _aiPrimarySubject.asStateFlow()
    val subjectMask: StateFlow<SubjectMask?>     = _aiSubjectMask.asStateFlow()
    val aiSceneType: StateFlow<SceneType>        = _aiSceneType.asStateFlow()
    val aiHdrSuggested: StateFlow<Boolean>       = _aiHdrSuggested.asStateFlow()
    val aiLowLightSuggested: StateFlow<Boolean>  = _aiLowLightSuggested.asStateFlow()
    val aiEnhancementResult: StateFlow<EnhancementResult?> = _aiEnhancementResult.asStateFlow()
    val focusAssistVisual: StateFlow<FocusAssistVisual> = _focusAssistVisual.asStateFlow()
    private val mergedAIFocus = MutableStateFlow<com.neoproc.neoproccamx.ai.FocusTarget?>(null)
    private val mergedAiMetering = MutableStateFlow<SubjectMeteringTarget?>(null)
    private val _recordingSavedPath = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val recordingSavedPath: SharedFlow<String> = _recordingSavedPath
    val captureEvents: SharedFlow<CaptureEvent> = stillCapture.events

    // Pipeline outputs
    val histogram: StateFlow<IntArray?> = pipeline.histogram
    val waveformColumns: StateFlow<IntArray?> = pipeline.waveformColumns
    val peakingMask = pipeline.peakingMask
    val zebraMask = pipeline.zebraMask
    val frameGeometry: StateFlow<FrameGeometry?> = camera.frameGeometry
    val avgLuma:   StateFlow<Float>     = pipeline.avgLuma
    val sceneAnalysis = pipeline.sceneAnalysis
    val captureRecommendation = pipeline.captureRecommendation
    val vulkanSupported: StateFlow<Boolean> = vulkanRuntime.supported
    val vulkanCapabilitySummary: StateFlow<String> = vulkanRuntime.capabilitySummary
    val vulkanImportStats: StateFlow<VulkanImportStats> = vulkanRuntime.importStats

    @Volatile private var healthLastImportedFrames: Long = -1L
    @Volatile private var healthLastComputeDispatches: Long = -1L
    @Volatile private var healthLastFrameStampNs: Long = 0L
    @Volatile private var healthLastFreshElapsedMs: Long = 0L
    @Volatile private var healthEverFresh: Boolean = false
    @Volatile private var healthMemCacheMs: Long = 0L
    @Volatile private var healthMemUsageMb: Int? = null
    @Volatile private var healthMemUtilPct: Int? = null

    fun buildHealthSnapshot(nowMs: Long): com.neoproc.neoproccamx.monitor.RuntimeSignalSnapshot {
        val camState = cameraState.value
        val recState = gpuRecordState.value
        val stats = vulkanImportStats.value
        val stats2 = cinemaRuntimeStats.value
        val recording = recState is GpuRecordingState.Recording || recState is GpuRecordingState.Paused

        val frameStampNs = liveResult.value?.timestampNs ?: 0L
        val frameAdvanced = frameStampNs != 0L && frameStampNs != healthLastFrameStampNs
        val importedAdvanced = healthLastImportedFrames in 0 until stats.sessionImportedFrames
        if (frameAdvanced || importedAdvanced) {
            healthLastFreshElapsedMs = nowMs
            healthEverFresh = true
        }
        healthLastFrameStampNs = frameStampNs
        healthLastImportedFrames = stats.sessionImportedFrames
        val fps = stats2.previewFps
        val lastDelivery = if (healthEverFresh) healthLastFreshElapsedMs else null
        val halFlowing = lastDelivery != null && (nowMs - lastDelivery) < 1_500L

        val encStatus = vulkanRuntime.currentEncoderSurfaceStatus
        val cameraOpen = camState !is CameraState.Closed &&
            camState !is CameraState.Closing &&
            camState !is CameraState.Error

        // ---- Issue 2: preview-time 10-bit P010 capability -------------------------------------
        val hdr = camera.describeHdrDynamicRangeState()
        val deviceTenBit = hdr.support.anyHdrSupported ||
            (camera.getCameraCapabilityInventory()?.supportsTenBitHdr == true)
        val encoderP010 = byteBufferP010EncoderCandidateAvailable()
        val feedPathName = activeRecordingFeedPath?.name
        // The BYTEBUFFER_P010 arm sources a genuine 10-bit camera P010 ImageReader; other arms source
        // the encoder surface (not an app-owned P010 reader) so we must not assert a source depth there.
        val p010SrcDepth = if (recording && feedPathName?.startsWith("BYTEBUFFER_P010") == true) 10 else null

        // ---- Issue 1: LIVE (non-stale) GPU compute telemetry ----------------------------------
        val liveCompute = vulkanRuntime.liveComputeTelemetry()
        // Live dispatch = the cumulative dispatch count ADVANCED since the previous tick (mirrors the
        // frame-delivery freshness discipline above). A non-zero-but-static count is NOT "dispatching now".
        val totalDispatches = liveCompute.totalDispatches
        val computeActivelyDispatching = totalDispatches != null &&
            healthLastComputeDispatches in 0 until totalDispatches
        if (totalDispatches != null) healthLastComputeDispatches = totalDispatches

        // ---- Issue 4: ISP request mode + hardware bypass capability ---------------------------
        val nativeBayerActive = rawVideoState.value is com.neoproc.neoproccamx.pipeline.RawVideoState.Capturing ||
            rawVideoFrameCount.value > 0

        // ---- Rich diagnostics: throttled memory sampling (~1 Hz) off the 150 ms poll -----------
        if (nowMs - healthMemCacheMs > 1_000L) {
            runCatching { memoryProfiler.captureSnapshot() }.getOrNull()?.let { m ->
                healthMemUsageMb = m.totalUsageMb
                healthMemUtilPct = m.utilizationPercent
            }
            healthMemCacheMs = nowMs
        }

        return com.neoproc.neoproccamx.monitor.RuntimeSignalSnapshot(
            sampleElapsedMs = nowMs,
            cameraOpen = cameraOpen,
            recording = recording,
            paused = recState is GpuRecordingState.Paused,
            hardwareWarning = uiState.value.hardwareWarning,
            cameraErrorMessage = (camState as? CameraState.Error)?.msg,
            gpuRecordErrorMessage = (recState as? GpuRecordingState.Error)?.message,
            lastFrameDeliveryElapsedMs = lastDelivery,
            previewFps = fps,
            dropEstimate = stats2.dropEstimate,
            vulkanSupported = vulkanSupported.value,
            vulkanPipelineHealthy = vulkanRuntime.isPipelineHealthy(),
            consecutiveFailedImports = stats.consecutiveFailedImports,
            sessionImportedFrames = stats.sessionImportedFrames,
            encoderSurfaceBound = stats.encoderSurfaceBound || stats.offscreenEglEncoderSurfaceBound,
            encoderActive = recState is GpuRecordingState.Ready ||
                recState is GpuRecordingState.Recording ||
                recState is GpuRecordingState.Paused,
            recordingTransportLabel = activeRecordingTransportHudLabel(),
            activeFeedPath = feedPathName,
            simulatedTenBitFallbackActive = gpuRecording.simulatedTenBitFallbackActive,
            p010SourceBitDepth = p010SrcDepth,
            encoderSurfaceBitDepth = if (encStatus.bound) encStatus.bitDepth else null,
            encoderSurfacePrecisionProven = encStatus.surfacePrecisionProven,
            encoderSurfacePrecisionFallback = encStatus.precisionFallback,
            encoderOutputTenBit = gpuRecording.encoderOutputTenBitOrNull(),
            // Issue 2: preview 10-bit capability (READY) + live proof.
            deviceTenBitHdrSupported = deviceTenBit,
            encoderP010Supported = encoderP010,
            previewTenBitProfileApplied = hdr.previewProfile != null,
            previewTransferCharacteristics = hdr.previewTransportMode.name,
            // Issue 1: live GPU compute telemetry (read fresh every tick — no stale snapshot cache).
            computeAverageMs = liveCompute.averageMs ?: vulkanRuntime.currentMeasuredAverageComputeDurationMs,
            computeDispatched = computeActivelyDispatching,
            computeTotalDispatches = liveCompute.totalDispatches,
            computePeakMs = liveCompute.peakMs,
            computeGpuTimestamped = liveCompute.gpuTimestamped,
            capabilitySummary = vulkanCapabilitySummary.value,
            ispVerdictLabel = camera.ispBypassVerdictLabel(),
            ispReadbackWarning = camera.ispBypassReadbackWarning(),
            halResultsFlowing = halFlowing,
            // Issue 4: ISP request mode + hardware bypass capability.
            ispRawRequestActive = camera.rawVideoCaptureEnabled,
            rawStreamActive = camera.isRawStreamActive(),
            ispBypassSupportedByHardware = camera.ispBypassSupportedByHardware(),
            vendorIspPresent = camera.vendorIspRequired(),
            nativeBayerActive = nativeBayerActive,
            // Issue 3: real hardware capability report (descriptive, exact reasons).
            capabilityBadge = camera.capabilityHealthBadge(),
            capabilityScore = camera.capabilityHealthScore(),
            capabilityLimitations = camera.capabilityHealthLimitations(),
            // Rich diagnostics.
            lastFrameSensorTimestampNs = frameStampNs.takeIf { it != 0L },
            lastSuccessfulImportElapsedMs = stats.lastSuccessfulImportElapsedMs.takeIf { it > 0L },
            sessionFailedImports = stats.sessionFailedImports,
            memoryUsageMb = healthMemUsageMb,
            memoryUtilizationPercent = healthMemUtilPct,
            pendingMuxerSamples = if (recording) gpuRecording.pendingMuxerSamples() else null,
            droppedMuxerSamples = if (recording) gpuRecording.droppedMuxerSamples() else null
        )
    }

    /** Health-recovery hook: re-probe GPU capabilities + encoder-surface status (non-destructive). */
    fun healthRecoveryRefreshGpuCapabilities(): Boolean {
        vulkanRuntime.requestCapabilitySummaryRefresh()
        return true
    }

    /** Health-recovery hook: re-arm the CP17 ISP metadata read-back (non-destructive). */
    fun healthRecoveryRearmIspReadback(): Boolean {
        camera.requestIspBypassReadbackRearm()
        return true
    }

    fun healthRecoveryResetImportHealth(): Boolean {
        vulkanRuntime.requestCapabilitySummaryRefresh()
        return vulkanRuntime.resetTransientImportHealth()
    }

    private fun updateState(block: (CameraUiState) -> CameraUiState) {
        _uiStateFlow.update(block)
    }

    private suspend fun applyLegacyP010SustainableFps(
        preparedEncoderSize: Size,
        reason: String
    ) {
        val sustainableP010Fps =
            LegacyP010FpsPolicy.resolve(recording.fps, preparedEncoderSize)
        if (sustainableP010Fps == recording.fps) return

        val previousFps = recording.fps
        recording.fps = sustainableP010Fps
        gpuRecording.videoFps = sustainableP010Fps
        prefs.videoFps = sustainableP010Fps
        camera.setFPS(sustainableP010Fps)
        updatePreviewViewAwait {
            setEncoderFrameRate(sustainableP010Fps)
        }
        Log.i(
            "CameraViewModel",
            "Legacy P010 fallback capped fps from $previousFps to " +
                "$sustainableP010Fps for ${preparedEncoderSize.width}x${preparedEncoderSize.height} " +
                "reason=$reason"
        )
    }

    private suspend fun applyProcessedGpuSustainableFps(
        preparedEncoderSize: Size,
        reason: String
    ) {
        val sustainableGpuFps =
            ProcessedGpuRecordFpsPolicy.resolve(recording.fps, preparedEncoderSize)
        if (sustainableGpuFps == recording.fps) return

        val previousFps = recording.fps
        recording.fps = sustainableGpuFps
        gpuRecording.videoFps = sustainableGpuFps
        prefs.videoFps = sustainableGpuFps
        vulkanRuntime.setOffscreenEncoderTargetFrameRate(sustainableGpuFps)
        camera.setFPS(sustainableGpuFps)
        updatePreviewViewAwait {
            setEncoderFrameRate(sustainableGpuFps)
        }
        Log.i(
            "CameraViewModel",
            "Processed GPU sustainable-fps cap: fps $previousFps -> " +
                "$sustainableGpuFps for ${preparedEncoderSize.width}x${preparedEncoderSize.height} " +
                "reason=$reason"
        )
    }

    fun dispatch(action: UiAction) {
        val result = actionChannel.trySend(action)
        if (result.isSuccess) return

        val closeCause = result.exceptionOrNull()
        if (closeCause != null) {
            Log.w("CameraViewModel", "Dropped UI action because the action channel is closed: $action")
            return
        }

        if (CameraUiResponsivenessPolicy.shouldDropWhenActionQueueFull(action)) {
            Log.w("CameraViewModel", "Dropped stale high-frequency UI action because the action queue is full: $action")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                actionChannel.send(action)
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Dropped UI action because the action channel closed before enqueue: $action", e)
            }
        }
    }

    private fun startActionProcessor() {
        viewModelScope.launch(Dispatchers.Default) {
            for (action in actionChannel) {
                try {
                    processAction(action)
                } catch (e: Exception) {
                    Log.e("CameraViewModel", "Error processing action: $action", e)
                }
            }
        }
    }

    private fun startSnceColorEngine() {
        com.neoproc.neoproccamx.snce.SnceColorEngineFlag.registerOnChanged {
            viewModelScope.launch(Dispatchers.Default) {
                runCatching { pushVulkanPreviewRenderConfig(force = true) }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            val filmLut = com.neoproc.neoproccamx.snce.SnceNrliAnalyticLut.buildFilmPrintLut()
            while (isActive && !nrliLutPushed) {
                if (com.neoproc.neoproccamx.snce.SnceColorEngineFlag.enabled &&
                    com.neoproc.neoproccamx.snce.SnceNrliLutFlag.enabled &&
                    vulkanRuntime.updateNrliLut(filmLut.rgb, 1.0f)
                ) {
                    nrliLutPushed = true
                    Log.i("CameraViewModel", "ELITE NRLI film-print LUT push ok=true size=${filmLut.size} strength=1.0")
                }
                if (!nrliLutPushed) kotlinx.coroutines.delay(750L)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            pipeline.sceneAnalysis.collect { analysis ->
                if (analysis == null || !com.neoproc.neoproccamx.snce.SnceColorEngineFlag.enabled) return@collect
                val frame = pipeline.latestFrame.get() ?: return@collect
                if (frame.uBytes.isEmpty() || frame.vBytes.isEmpty()) return@collect
                val yCopy = frame.yBytes.copyOf()
                val uCopy = frame.uBytes.copyOf()
                val vCopy = frame.vBytes.copyOf()
                val yuv = com.neoproc.neoproccamx.snce.SnceYuvThumbnail.Frame(
                    width = frame.width, height = frame.height,
                    yBytes = yCopy, uBytes = uCopy, vBytes = vCopy,
                    yRowStride = frame.yRowStride, yPixelStride = frame.yPixelStride,
                    uRowStride = frame.uRowStride, vRowStride = frame.vRowStride,
                    uPixelStride = frame.uPixelStride, vPixelStride = frame.vPixelStride,
                    bitDepth = frame.bitDepth
                )
                val changed = snceColorEngine.analyze(yuv, estimatedLux = analysis.estimatedLux)
                if (changed) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastSncePushMs > 150L) {
                        lastSncePushMs = now
                        runCatching { pushVulkanPreviewRenderConfig(force = true) }
                    }
                }
            }
        }
    }

    private suspend fun processAction(action: UiAction) {
        when (action) {
            UiAction.ToggleRaw -> runUiAction { toggleRaw() }
            UiAction.ToggleHdrMode -> runUiAction { toggleHdrMode() }
            is UiAction.SetLogEnabled -> runUiAction { setLogEnabled(action.enabled) }
            UiAction.CycleGrid -> runUiAction { cycleGrid() }
            UiAction.ToggleHist -> runUiAction { toggleHist() }
            UiAction.ToggleWaveform -> runUiAction { toggleWaveform() }
            UiAction.ToggleZebra -> runUiAction { toggleZebra() }
            is UiAction.SetPeakingIntensity -> runUiAction { setPeakingIntensity(action.value) }
            UiAction.TogglePeaking -> runUiAction { togglePeaking() }
            is UiAction.SetColorProfile -> runUiAction { setColorProfile(action.profile) }
            is UiAction.SetAiEnhancementEnabled -> runUiAction {
                setAiEnhancementEnabled(action.enabled)
            }
            UiAction.ToggleRecording -> runUiAction { toggleRecording() }
            UiAction.PauseRecording -> runUiAction { pauseRecording() }
            UiAction.ResumeRecording -> runUiAction { resumeRecording() }
            is UiAction.AdjustZoom -> runUiAction { setZoom(action.zoomFactor) }
            is UiAction.SetManualFocus -> runUiAction {
                val target = action.target
                if (target == null) {
                    setAutoFocus()
                } else {
                    tapToFocus(target.normalizedX, target.normalizedY)
                }
            }
            UiAction.SwitchFacing -> runUiAction { switchFacing() }
            UiAction.ApplyCinematicBundle -> runUiAction { applyCinematicBundle() }
        }
    }

    private suspend fun runUiAction(block: () -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            block()
            publishCompositeUiState()
        }
    }

    private fun startCompositeUiStateBridge() {
        val sources: List<Flow<Any?>> = listOf(
            modeController.domainState.map { it as Any? },
            previewController.domainState.map { it as Any? },
            recordingController.domainState.map { it as Any? },
            settingsController.domainState.map { it as Any? },
            _logOn.map { it as Any? },
            _videoHdrMode.map { it as Any? },
            _colorProfile.map { it as Any? }
        )
        viewModelScope.launch(Dispatchers.Default) {
            combine(sources) { values -> values }.collect { values ->
                updateState { current ->
                    CameraUiStateCompositionPolicy.compose(
                        mode = values[0] as ModeDomainState,
                        preview = values[1] as PreviewDomainState,
                        recording = values[2] as RecordingDomainState,
                        settings = values[3] as SettingsDomainState,
                        logEnabled = values[4] as Boolean,
                        videoHdrMode = values[5] as String,
                        colorProfile = values[6] as String,
                        previous = current
                    )
                }
            }
        }
        publishCompositeUiState()
    }

    private fun publishCompositeUiState() {
        val mode = modeController.domainState.value
        val preview = previewController.domainState.value
        val recording = recordingController.domainState.value
        val settings = settingsController.domainState.value
        updateState { current ->
            CameraUiStateCompositionPolicy.compose(
                mode = mode,
                preview = preview,
                recording = recording,
                settings = settings,
                logEnabled = _logOn.value,
                videoHdrMode = _videoHdrMode.value,
                colorProfile = _colorProfile.value,
                previous = current
            )
        }
    }

    private fun syncModeDomainFromLegacy() {
        modeController.syncFromLegacy(
            activeMode = _uiMode.value,
            cinemaMode = _cinemaVideoMode.value,
            rawCompression = _rawVideoCompression.value,
            rawEnabled = _rawOn.value
        )
    }

    private fun syncPreviewDomainFromLegacy() {
        previewController.syncFromLegacy(_previewAspect.value)
    }

    private fun syncSettingsLegacyFromDomain() {
        val state = settingsController.domainState.value
        _gridMode.value = state.gridMode
        _showHist.value = false
        _showWfm.value = false
        _showZebra.value = false
        _zebraT.value = state.zebraThreshold
        _peaking.value = false
        _peakingIntensity.value = state.peakingIntensity
        _aiEnhancement.value = effectiveAiEnhancementEnabled(state.aiEnhancement)
        _aiMotionCompensation.value = state.aiMotionCompensation
        _uncompressedCinemaPipeline.value = true
        _videoDetailProfile.value = state.videoDetailProfile
        gpuRecording.videoDetailProfile = _videoDetailProfile.value
        _manualControlsVisible.value = state.manualControlsVisible
        previewViewRef?.setZebraEnabled(effectiveZebraEnabled())
        previewViewRef?.setZebraThreshold(state.zebraThreshold)
        previewViewRef?.setPeakingEnabled(effectivePeakingEnabled())
        previewViewRef?.setPeakingIntensity(state.peakingIntensity)
        if (!effectivePeakingEnabled()) {
            previewViewRef?.clearPeakingFocusRegion()
        }
        // Toggling a monitor from the top-bar chips flows through here (not applyPreferences), so
        // attach/release the CPU-analysis stream that actually feeds the monitors on this path too.
        syncPreviewAnalysisFeed()
    }

    private fun startControllerDomainSync() {
        viewModelScope.launch(Dispatchers.Default) {
            combine(
                _uiMode,
                _cinemaVideoMode,
                _rawVideoCompression,
                _rawOn
            ) { activeMode, cinemaMode, rawCompression, rawEnabled ->
                ModeDomainState(
                    activeMode = activeMode,
                    cinemaMode = cinemaMode,
                    rawCompression = rawCompression,
                    rawEnabled = rawEnabled
                )
            }.collect { state ->
                modeController.syncFromLegacy(
                    activeMode = state.activeMode,
                    cinemaMode = state.cinemaMode,
                    rawCompression = state.rawCompression,
                    rawEnabled = state.rawEnabled
                )
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            _previewAspect.collect { aspect ->
                previewController.syncFromLegacy(aspect)
            }
        }

        val settingsSources: List<Flow<Any?>> = listOf(
            _gridMode.map { it as Any? },
            _showHist.map { it as Any? },
            _showWfm.map { it as Any? },
            _showZebra.map { it as Any? },
            _zebraT.map { it as Any? },
            _peaking.map { it as Any? },
            _peakingIntensity.map { it as Any? },
            _aiEnhancement.map { it as Any? },
            _aiMotionCompensation.map { it as Any? },
            _uncompressedCinemaPipeline.map { it as Any? },
            _videoDetailProfile.map { it as Any? },
            _manualControlsVisible.map { it as Any? }
        )
        viewModelScope.launch(Dispatchers.Default) {
            combine(settingsSources) { values ->
                SettingsDomainState(
                    gridMode = values[0] as GridMode,
                    showHistogram = values[1] as Boolean,
                    showWaveform = values[2] as Boolean,
                    showZebra = values[3] as Boolean,
                    zebraThreshold = values[4] as Float,
                    peakingEnabled = values[5] as Boolean,
                    peakingIntensity = values[6] as Float,
                    aiEnhancement = values[7] as Boolean,
                    aiMotionCompensation = values[8] as Boolean,
                    uncompressedCinemaPipeline = values[9] as Boolean,
                    videoDetailProfile = values[10] as VideoDetailProfile,
                    manualControlsVisible = values[11] as Boolean
                )
            }.collect { state ->
                settingsController.syncFromLegacy(
                    gridMode = state.gridMode,
                    showHistogram = state.showHistogram,
                    showWaveform = state.showWaveform,
                    showZebra = state.showZebra,
                    zebraThreshold = state.zebraThreshold,
                    peakingEnabled = state.peakingEnabled,
                    peakingIntensity = state.peakingIntensity,
                    aiEnhancement = state.aiEnhancement,
                    aiMotionCompensation = state.aiMotionCompensation,
                    uncompressedCinemaPipeline = state.uncompressedCinemaPipeline,
                    videoDetailProfile = state.videoDetailProfile,
                    manualControlsVisible = state.manualControlsVisible
                )
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            combine(gpuRecordState, recDuration) { state, duration -> state to duration }
                .collect { (state, duration) ->
                    recordingController.syncExternalState(state, duration)
                }
        }
    }

    /**
     * Captures a full-resolution JPEG from the dedicated still surface. The preview surface is never
     * sampled or upscaled, so the heat-efficient viewfinder resolution cannot reduce saved-photo quality.
     */
    fun captureHighQualityPhoto(): Boolean {
        if (stillCapture.isCaptureInFlight()) return false
        val cameraReady = cameraState.value == CameraState.Open ||
            cameraState.value == CameraState.Streaming ||
            cameraState.value == CameraState.Recording
        val recordingTransitionActive = gpuRecordState.value is GpuRecordingState.Starting ||
            gpuRecordState.value is GpuRecordingState.Stopping ||
            gpuRecordState.value is GpuRecordingState.Finalizing
        if (!cameraReady || recordingTransitionActive || camera.jpegReader == null) return false

        // Deliberately request JPEG-only here even in RAW video mode. The RAW_SENSOR reader remains owned
        // by the lossless video recorder while the independent maximum-size JPEG surface takes the photo.
        stillCapture.captureSingle(raw = false)
        return true
    }

    fun isHighQualityPhotoCaptureInFlight(): Boolean = stillCapture.isCaptureInFlight()

    // -------------------------------------------------------------------------
    // INIT
    // -------------------------------------------------------------------------

    init {
        // JPEG ownership stays with the still pipeline. RAW and result callbacks remain coordinated by
        // this ViewModel so RAW video packet recording and Camera2 lineage evidence are never displaced.
        stillCapture.setupCallbacks(
            registerRawCallback = false,
            registerCaptureResultCallback = false
        )
        startActionProcessor()
        startControllerDomainSync()
        startCompositeUiStateBridge()
        startStartupBootstrap()
        startSnceColorEngine()
        gpuRecording.runtimeEvidenceMetadataProvider = ::buildRecordingRuntimeEvidenceMetadata
        npv10Recording.runtimeEvidenceMetadataProvider = ::buildRecordingRuntimeEvidenceMetadata
        
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                val tier = when (status) {
                    0 -> HighEndRuntimeTier.ULTRA
                    1 -> HighEndRuntimeTier.CINEMA
                    2 -> HighEndRuntimeTier.PROFESSIONAL
                    3 -> HighEndRuntimeTier.SUSTAINED
                    4 -> HighEndRuntimeTier.RECOVERY
                    else -> HighEndRuntimeTier.RECOVERY
                }
                val isThrottled = status >= 3 // THERMAL_STATUS_THROTTLED
                updateState { it.copy(thermalTier = tier, thermalThrottled = isThrottled) }
                
                val thermalState = com.neoproc.neoproccamx.pipeline.ThermalState.fromAndroidStatus(status)
                camera.setThermalResourceCeiling(thermalState.resourceCeiling)
                
                maybeApplyThermalGuard(tier.name)
                Log.i("CameraViewModel", "Thermal status change: status=$status tier=$tier")
            }
            powerManager.addThermalStatusListener(thermalListener)
            thermalPowerManager = powerManager
            thermalStatusListener = thermalListener
            Log.i("CameraViewModel", "Thermal status listener registered")
        }
        pipeline.connectAICameraPipeline(aiCameraPipeline)
        pipeline.connectVulkanRuntime(vulkanRuntime)
        pipeline.setDedicatedHardwareImportEnabled(false)
        vulkanRuntime.setFrameImportEnabled(false)
        if (prefs.isOffscreenEglEncoderProvenUnavailable(offscreenEglRecallFingerprint())) {
            // A prior session ON THIS SAME build+OS fingerprint proved this device cannot back the
            // offscreen 10-bit encoder (a genuine GPU hardware wall). Skip re-probing so recording
            // starts directly on the proven P010 path. A new app build (EGL fix) or a driver OTA
            // changes the fingerprint, so the recall lapses and the path is re-probed honestly.
            vulkanRuntime.markOffscreenAhbEglEncoderProvenUnavailable()
        }
        viewModelScope.launch(Dispatchers.Default) {
            // s7-f13/CP9C: pass the app-private cache dir so the default-ON disk pipeline cache has a real
            // path (VulkanBridge validates the header's device identity before use, else fresh compile).
            vulkanRuntime.initialize(getApplication<Application>().cacheDir)
            // Propagate the HDR mode selected during camera init so that
            // the first swapchain build uses the matching
            // (VkFormat, VkColorSpaceKHR) pair instead of defaulting to SDR.
            vulkanRuntime.setHdrDynamicRangeMode(
                if (_uiMode.value == AppMode.VIDEO) {
                    HdrDynamicRangeMode.fromUiMode(
                        uiMode = _videoHdrMode.value,
                        logEnabled = _logOn.value
                    )
                } else {
                    HdrDynamicRangeMode.SDR
                }
            )
            runOnMain {
                refreshPreviewPipelineIfNeeded()
            }
        }
        viewModelScope.launch {
            camera.frameGeometry.collect { geometry ->
                applyFrameGeometry(geometry)
            }
        }

        // Wire: Camera YUV -> pipeline (pipeline extracts bytes, closes image, dispatches)
        camera.onYuvFrame = { reader -> pipeline.enqueue(reader) }
        camera.onHardwareFrame = { reader -> pipeline.enqueueHardware(reader) }
        camera.awaitFramePipelineIdleBeforeReaderClose = { timeoutMs ->
            pipeline.awaitIdleBeforeReaderClose(timeoutMs)
        }
        camera.onCaptureResult = { result ->
            recordCamera2FrameLineageCaptureResult(result)
            stillCapture.onCaptureResult(result)
        }
        vulkanRuntime.setFrameLineageSubmissionListener(::recordCamera2FrameLineageSubmission)
        camera.onCameraPipelineWarning = { message ->
            runOnMainIfNeeded { setHardwareWarning(message) }
        }
        viewModelScope.launch {
            cameraState.collect { state ->
                if (state == CameraState.Open || state == CameraState.Streaming) {
                    runOnMainIfNeeded {
                        // A newly settled Camera2 graph is a fresh ownership opportunity. Previous
                        // EGL/session failures must not permanently strand PQ preview on the direct
                        // SurfaceView after the stream becomes healthy.
                        if (state == CameraState.Streaming && shouldCompositeVisibleGlPreview()) {
                            glPreviewEngageRetries = 0
                        }
                        syncGlPreviewCompositorBinding()
                    }
                }
            }
        }

        // AI eye/face tracking -> tap-to-focus
        aiAfCoordinator.observeAI(mergedAiMetering)
        viewModelScope.launch {
            combine(
                _aiPrimaryFocus,
                manualFocusOverride
            ) { aiFocus: com.neoproc.neoproccamx.ai.FocusTarget?, manualFocus ->
                if (manualFocus != null) {
                    manualFocus
                } else if (cameraParams.value.focusDist >= 0f || camera.isAeAwbLocked()) {
                    null
                } else {
                    aiFocus
                }
            }.collectLatest { mergedAIFocus.value = it }
        }

        viewModelScope.launch {
            combine(manualFocusOverride, _aiPrimaryFocus) { manualFocus, primaryFocus ->
                FocusAssistVisualPolicy.resolve(
                    manualFocus = manualFocus,
                    primaryFocus = primaryFocus
                )
            }.collectLatest { _focusAssistVisual.value = it }
        }

        viewModelScope.launch {
            combine(
                _aiPrimarySubject,
                mergedAIFocus,
                _aiSceneType,
                _aiHdrSuggested,
                _aiLowLightSuggested,
                manualFocusOverride
            ) { values: Array<Any?> ->
                val aiSubject = values[0] as? PrimarySubject
                val aiFocus = values[1] as? com.neoproc.neoproccamx.ai.FocusTarget
                val sceneType = values[2] as? SceneType ?: SceneType.GENERAL
                val hdrSuggested = values[3] as? Boolean ?: false
                val lowLightSuggested = values[4] as? Boolean ?: false
                val manualFocus = values[5] as? FocusTarget
                when {
                    manualFocus != null -> SubjectMeteringTarget(
                        focusX = manualFocus.normalizedX,
                        focusY = manualFocus.normalizedY,
                        subjectBounds = android.graphics.RectF(
                            (manualFocus.normalizedX - 0.18f).coerceIn(0f, 1f),
                            (manualFocus.normalizedY - 0.18f).coerceIn(0f, 1f),
                            (manualFocus.normalizedX + 0.18f).coerceIn(0f, 1f),
                            (manualFocus.normalizedY + 0.18f).coerceIn(0f, 1f)
                        ),
                        subjectType = when (manualFocus.type) {
                            FocusTargetType.EYE -> SubjectType.EYE
                            FocusTargetType.FACE -> SubjectType.FACE
                            FocusTargetType.BODY -> SubjectType.BODY
                            FocusTargetType.OBJECT -> SubjectType.OBJECT
                        },
                        category = com.neoproc.neoproccamx.ai.SubjectCategory.OBJECT,
                        trackingId = manualFocus.trackId,
                        confidence = 1f,
                        focusDepthScore = 0f,
                        hdrSuggested = hdrSuggested || sceneType == SceneType.BACKLIGHT,
                        lowLightSuggested = lowLightSuggested || sceneType == SceneType.NIGHT || sceneType == SceneType.INDOOR
                    )
                    aiSubject != null -> SubjectMeteringTarget(
                        focusX = prioritizedSubjectFocusX(aiSubject, aiFocus),
                        focusY = prioritizedSubjectFocusY(aiSubject, aiFocus),
                        subjectBounds = aiSubject.bounds,
                        subjectType = aiSubject.subjectType,
                        category = aiSubject.category,
                        trackingId = aiSubject.trackingId,
                        confidence = aiSubject.confidence,
                        focusDepthScore = aiSubject.depthScore,
                        hdrSuggested = hdrSuggested || sceneType == SceneType.BACKLIGHT,
                        lowLightSuggested = lowLightSuggested || sceneType == SceneType.NIGHT || sceneType == SceneType.INDOOR,
                        // AF-SD (F12): gated per-frame scene contrast from the already-computed
                        // enhancement sharpness/noise; OFF => neutral 1.0f (byte-identical target).
                        sceneContrast = if (com.neoproc.neoproccamx.ai.SceneContrastProducerFlag.enabled) {
                            _aiEnhancementResult.value?.let {
                                com.neoproc.neoproccamx.ai.AIFocusSceneContrastPolicy.sceneContrastFromEnhancement(
                                    it.sharpnessScore, it.estimatedNoiseLevel
                                )
                            } ?: com.neoproc.neoproccamx.ai.AIFocusSceneContrastPolicy.NEUTRAL_CONTRAST
                        } else {
                            com.neoproc.neoproccamx.ai.AIFocusSceneContrastPolicy.NEUTRAL_CONTRAST
                        }
                    )
                    else -> null
                }
            }.collectLatest { mergedAiMetering.value = it }
        }

        viewModelScope.launch {
            liveResult.collectLatest { result ->
                evaluateCinemaAutoLock(result)
            }
        }

        // Keep the NDR exposure governor on its own frame-analysis flow. It must not depend on a
        // render-profile value changing: a visually identical render profile can still contain a new
        // highlight/red-clip measurement that requires a different sensor exposure.
        viewModelScope.launch(Dispatchers.Default) {
            combine(
                pipeline.avgLuma,
                pipeline.histogram,
                pipeline.redHighlightClipFraction
            ) { averageLuma, histogram, redHighlightClipFraction ->
                val analysisValid = histogram?.any { it > 0 } == true
                val highlightClipFraction =
                    NdrAdaptiveHighlightExposurePolicy.highlightClipFraction(histogram)
                Triple(averageLuma, highlightClipFraction, redHighlightClipFraction) to analysisValid
            }.collectLatest { metrics ->
                camera.setHdrExposureSceneMetrics(
                    averageLuma = metrics.first.first,
                    highlightClipFraction = metrics.first.second,
                    redHighlightClipFraction = metrics.first.third,
                    analysisValid = metrics.second
                )
            }
        }

        viewModelScope.launch {
            combine(
                sceneAnalysis,
                captureRecommendation,
                _uiMode,
                _logOn,
                _videoHdrOn,
                _rawOn,
                _cinemaVideoMode,
                _colorProfile,
                _lutEnabled,
                _lutProfile,
                _aiEnhancement,
                _videoColorSpace,
                _videoHdrMode,
                _videoDetailProfile,
                _aiSubjectMask,
                liveResult
            ) { values: Array<Any?> ->
                val scene = values[0] as? com.neoproc.neoproccamx.pipeline.SceneAnalysis
                val recommendation =
                    values[1] as? com.neoproc.neoproccamx.pipeline.CaptureRecommendation
                val mode = values[2] as? AppMode ?: AppMode.VIDEO
                val logOn = values[3] as? Boolean ?: false
                val hdrOn = values[4] as? Boolean ?: false
                val rawOn = values[5] as? Boolean ?: false
                val cinemaMode = values[6] as? CinemaVideoMode ?: CinemaVideoMode.PRO
                val colorProfile = values[7] as? String ?: "Natural"
                val lutEnabled = values[8] as? Boolean ?: false
                val lutProfile = values[9] as? String ?: "Neutral Film"
                val aiEnhancementEnabled = effectiveAiEnhancementEnabled(values[10] as? Boolean ?: false)
                val videoColorSpace = values[11] as? String ?: "Rec.709"
                val videoHdrMode = values[12] as? String ?: "OFF"
                val videoDetailProfile = values[13] as? VideoDetailProfile ?: VideoDetailProfile.FOCUSED_DETAIL
                val subjectMask = values[14] as? SubjectMask
                val liveCaptureResult = values[15] as? LiveResult
                val graphContract = activeVideoPipelineContract(
                    appMode = mode,
                    cinemaMode = cinemaMode,
                    rawVideoActive = mode == AppMode.VIDEO &&
                        cinemaMode == CinemaVideoMode.RAW &&
                        rawOn,
                    logEnabled = logOn,
                    requestedHdrMode = videoHdrMode,
                    preferredColorSpace = videoColorSpace,
                    tenBitRequested = gpuRecording.tenBitEnabled ||
                        resolveVideoRecordingTenBitRequested(cinemaMode, videoHdrMode),
                    recordingState = gpuRecordState.value,
                    videoDetailProfile = videoDetailProfile
                )
                val deliveryState = graphContract.deliveryState
                val neutralVideoPreview = VideoRenderProfileQualityHintPolicy.isVideoSurface(mode) &&
                    deliveryState.neutralPreview
                val effectiveProfile =
                    if (neutralVideoPreview || deliveryState.rawVideoActive) {
                        "Natural"
                    } else {
                        "$videoColorSpace $colorProfile"
                    }
                val effectiveLogEnabled = if (neutralVideoPreview) false else deliveryState.logVideoActive
                val effectiveHdrEnabled = if (neutralVideoPreview) {
                    false
                } else {
                    hdrOn || deliveryState.effectiveHdrMode != "OFF"
                }
                val simulatedTenBitFallback =
                    VideoRenderProfileQualityHintPolicy.isVideoSurface(mode) &&
                        deliveryState.tenBitActive &&
                        !gpuRecording.supports10Bit()
                apexPipelineState.updateScene(
                    ApexRuntimeSceneMapper.fromPipeline(
                        scene = scene,
                        recommendation = recommendation,
                        liveResult = liveCaptureResult
                    )
                )
                val measuredNoise = VideoNoiseMetrics.fromScene(scene)
                camera.setProcessedVideoSceneQuality(
                    motionRisk = scene?.motionScore ?: 0f,
                    noiseRisk = maxOf(
                        measuredNoise.lumaNoise,
                        measuredNoise.chromaNoise,
                        measuredNoise.frameToFrameRgbVariance
                    )
                )
                val outputRenderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
                    .normalizeForOrientation()
                val sourceRenderSize = preferredVideoSourceSize()
                val subjectDetailPlan = SubjectDetailEnhancementPolicy.resolve(
                    SubjectDetailEnhancementRequest(
                        profile = videoDetailProfile,
                        maskAvailable = subjectMask != null,
                        maskTrackingId = subjectMask?.trackingId,
                        maskConfidence = subjectMask?.intensity ?: 0f,
                        motionScore = scene?.motionScore ?: 0f,
                        noiseFloor = scene?.normalizedNoiseFloor ?: 0f,
                        skinToneConfidence = 0f
                    )
                )
                renderProfileEngine.build(
                    scene = scene,
                    recommendation = recommendation,
                    logEnabled = effectiveLogEnabled,
                    hdrEnabled = effectiveHdrEnabled,
                    colorProfile = effectiveProfile,
                    aiEnhancementEnabled = aiEnhancementEnabled,
                    simulatedTenBitFallback = simulatedTenBitFallback,
                    lutEnabled = lutEnabled,
                    lutProfile = lutProfile,
                    hdrMode = deliveryState.effectiveHdrMode,
                    previewFps = lastPreviewFpsSample.takeIf { it > 0f } ?: recording.fps.toFloat(),
                    recording = graphContract.recordingGradePreview,
                    outputWidth = outputRenderSize.width,
                    outputHeight = outputRenderSize.height,
                    sourceWidth = sourceRenderSize.width,
                    sourceHeight = sourceRenderSize.height,
                    videoDetailProfile = videoDetailProfile,
                    subjectDetailPlan = subjectDetailPlan,
                    aiMotionCompensationEnabled = _aiMotionCompensation.value
                )
            }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collectLatest { profile ->
                    currentSceneRenderProfile = profile
                    updatePreviewView { setSceneRenderProfile(profile) }
                    applyFrameGeometry(camera.frameGeometry.value)
                }
        }

        viewModelScope.launch {
            combine(mergedAIFocus, _peaking, activePeakingRegion) { target, peakingEnabled, manualRegion ->
                Triple(target, peakingEnabled, manualRegion)
            }.collectLatest { (target, peakingEnabled, manualRegion) ->
                val previewView = previewViewRef ?: return@collectLatest
                if (_uiMode.value == AppMode.VIDEO && stabilizationMode != StabilizationMode.OFF) {
                    val stabilizationStrength = when (stabilizationMode) {
                        StabilizationMode.HYBRID -> 0.09f
                        StabilizationMode.EIS -> 0.075f
                        StabilizationMode.OIS -> 0.045f
                        StabilizationMode.OFF -> 0f
                    }
                    previewView.setStabilizationStrength(stabilizationStrength)
                    val anchorX = target?.normalizedX ?: 0.5f
                    val anchorY = target?.normalizedY ?: 0.5f
                    previewView.setStabilizationAnchor(anchorX, anchorY)
                } else {
                    previewView.setStabilizationStrength(0f)
                    previewView.setStabilizationAnchor(0.5f, 0.5f)
                }
                if (!peakingEnabled) {
                    previewView.clearPeakingFocusRegion()
                    pipeline.clearPeakingFocusRegion()
                    return@collectLatest
                }
                val resolvedRegion = when {
                    manualRegion != null -> manualRegion
                    target != null -> PeakingRegion(
                        x = target.normalizedX,
                        y = target.normalizedY,
                        radius = if (_uiMode.value == AppMode.VIDEO) 0.24f else 0.28f
                    )
                    else -> null
                }
                if (resolvedRegion != null) {
                    previewView.setPeakingFocusRegion(
                        resolvedRegion.x,
                        resolvedRegion.y,
                        resolvedRegion.radius
                    )
                    pipeline.setPeakingFocusRegion(
                        resolvedRegion.x,
                        resolvedRegion.y,
                        resolvedRegion.radius
                    )
                    return@collectLatest
                }
                previewView.clearPeakingFocusRegion()
                pipeline.clearPeakingFocusRegion()
            }
        }
    }

    // -------------------------------------------------------------------------
    // PREVIEW SURFACE WIRING
    // -------------------------------------------------------------------------

    /**
     * Called once from MainActivity after the preview view is inflated.
     * Connects the pipeline to GL (for requestRender) and AI.
     */
    fun refreshPreferences() {
        refreshPreferencesJob?.cancel()
        val signature = currentPreferencesRefreshSignature()
        if (signature == lastAppliedPreferencesSignature) return
        refreshPreferencesJob = viewModelScope.launch(Dispatchers.Default) {
            applyPreferences(signature = signature)
        }
    }

    private fun currentPreferencesRefreshSignature(): PreferencesRefreshSignature =
        PreferencesRefreshSignature(
            uiMode = persistedUiMode(),
            videoCodec = prefs.videoCodec,
            videoSize = prefs.videoSize.normalizeForOrientation(),
            videoFps = prefs.videoFps,
            videoBitrate = prefs.videoBitrate,
            audioSource = prefs.audioSource,
            tenBitRecordingEnabled = prefs.tenBitRecordingEnabled,
            uncompressedCinemaPipelineEnabled = prefs.uncompressedCinemaPipelineEnabled,
            noiseReductionEnabled = effectiveNoiseReductionEnabled(),
            stabilizationMode = prefs.stabilizationMode,
            lastIso = prefs.lastISO,
            lastShutterNs = prefs.lastShutterNs,
            lastWhiteBalanceKelvin = prefs.lastWBKelvin,
            rawEnabled = prefs.rawEnabled,
            gridMode = prefs.gridMode,
            histogramVisible = prefs.histogramVisible,
            waveformVisible = prefs.waveformVisible,
            zebraVisible = prefs.zebraVisible,
            zebraThreshold = prefs.zebraThreshold,
            peakingEnabled = prefs.focusPeakingEnabled,
            peakingIntensity = prefs.focusPeakingIntensity,
            logEnabled = prefs.logEnabled,
            videoHdrMode = prefs.videoHdrMode,
            videoColorSpace = prefs.videoColorSpace,
            videoDetailProfile = prefs.videoDetailProfile,
            videoMode = prefs.videoMode,
            rawVideoCompression = prefs.rawVideoCompression,
            videoBitrateMode = prefs.videoBitrateMode,
            colorProfile = prefs.colorProfile,
            lutEnabled = prefs.lutEnabled,
            lutProfile = prefs.lutProfile,
            customLutPath = prefs.customLutPath,
            aiEnhancementEnabled = effectiveAiEnhancementEnabled(),
            previewAspectRatio = prefs.previewAspectRatio,
            saturation = prefs.saturation,
            contrast = prefs.contrast,
            brightness = prefs.brightness,
            faceDetectionEnabled = prefs.faceDetectionEnabled,
            eyeAutofocusEnabled = prefs.eyeAutofocusEnabled
        )

    private fun applyPreferences(
        signature: PreferencesRefreshSignature = currentPreferencesRefreshSignature(),
        force: Boolean = false
    ) {
        if (!force && signature == lastAppliedPreferencesSignature) return
        val restoredUiMode = persistedUiMode()
        if (_uiMode.value != restoredUiMode) {
            _uiMode.value = restoredUiMode
        }
        prefs.lastUiMode = restoredUiMode.name

        val wantsCustomLut = prefs.lutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true)
        val customLutLoaded = restorePersistedCustomLut(loadContent = wantsCustomLut)
        val desiredGpuCodec = RecordingCapabilityPolicy.resolveRequestedCodec(
            requested = VideoRecordingContractPolicy.resolveRequestedCodec(
                runCatching { VideoCodecType.valueOf(prefs.videoCodec.uppercase(Locale.US)) }
                    .getOrDefault(VideoCodecType.HEVC)
            ),
            av1Supported = supportsAv1Fast()
        )
        val requestedSizeAtResolve = prefs.videoSize.normalizeForOrientation()
        val resolvedSelection = resolveRecordingSelection(requestedSizeAtResolve, desiredGpuCodec)
        val desiredAudio = audioSourceFromPref(prefs.audioSource)

        val previewFrameRatePlan =
            RecordingPreviewFrameRatePolicy.resolve(resolvedSelection, prefs.videoFps)
        if (prefs.videoFps != previewFrameRatePlan.encoderFps) {
            prefs.videoFps = previewFrameRatePlan.encoderFps
        }
        syncRecordingBitrateMode(prefs.videoBitrateMode)
        recording.fps = previewFrameRatePlan.encoderFps
        recording.bitrate = prefs.videoBitrate
        recording.audioSource = desiredAudio
        val preferenceResolutionStillCurrent = synchronized(recordingSelectionMutationLock) {
            if (prefs.videoSize.normalizeForOrientation() != requestedSizeAtResolve) {
                false
            } else {
                applyResolvedRecordingConfig(
                    resolvedSelection = resolvedSelection,
                    fps = previewFrameRatePlan.encoderFps,
                    bitrate = prefs.videoBitrate,
                    audioSource = desiredAudio
                )
                true
            }
        }
        if (!preferenceResolutionStillCurrent) {
            Log.i(
                "CameraViewModel",
                "Skipped stale preference resolution restore requested=" +
                    "${requestedSizeAtResolve.width}x${requestedSizeAtResolve.height} " +
                    "current=${prefs.videoSize.width}x${prefs.videoSize.height}"
            )
            refreshPreferences()
            return
        }
        syncRecordingCodecAndBitDepthContract(cinemaMode = _cinemaVideoMode.value)

        camera.setNoiseReductionEnabled(effectiveNoiseReductionEnabled())
        camera.setStabilizationMode(prefs.stabilizationMode)
        restorePersistedManualControls()
        syncActivePreviewFrameRate()

        val normalizedVideoHdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(prefs.videoHdrMode)
        val requestedCinemaMode = CinemaVideoMode.fromName(prefs.videoMode)
        val restoredVideoMode = VideoModeRestorePolicy.resolve(
            requestedHdrMode = normalizedVideoHdrMode,
            hdrRequested = normalizedVideoHdrMode != "OFF",
            uiMode = _uiMode.value,
            requestedCinemaMode = requestedCinemaMode,
            rawSupported = camera.supportsRaw
        )
        _rawOn.value = restoredVideoMode.rawEnabled
        _uncompressedCinemaPipeline.value = true
        _aiMotionCompensation.value = prefs.aiMotionCompensationEnabled
        _gridMode.value = gridModeFromPref(prefs.gridMode)
        _showHist.value = prefs.histogramVisible
        _showWfm.value = prefs.waveformVisible
        _showZebra.value = prefs.zebraVisible
        _zebraT.value = prefs.zebraThreshold
        _peaking.value = prefs.focusPeakingEnabled
        _peakingIntensity.value = prefs.focusPeakingIntensity
        _safeAreaGuides.value = prefs.safeAreaGuidesEnabled
        updatePreviewView {
            setPeakingEnabled(_peaking.value)
            setPeakingIntensity(_peakingIntensity.value)
        }
        pipeline.setPeakingEnabled(_peaking.value)
        pipeline.setPeakingIntensity(_peakingIntensity.value)
        pipeline.setZebraEnabled(_showZebra.value)
        pipeline.setZebraThreshold(_zebraT.value)
        pipeline.setWaveformEnabled(_showWfm.value)
        pipeline.setAiMotionCompensationEnabled(_aiMotionCompensation.value)
        val restoredCinemaMode = normalizeNpv10CinemaMode(restoredVideoMode.cinemaMode)
        _logOn.value = restoredCinemaMode == CinemaVideoMode.LOG
        val normalizedVideoColorSpace = ManualVideoColorSelectionPolicy
            .normalizeColorSpace(prefs.videoColorSpace)
            .takeUnless { it == ManualVideoColorSelectionPolicy.UNSELECTED }
            ?: prefs.videoColorSpace
        if (prefs.videoHdrMode != normalizedVideoHdrMode) {
            prefs.videoHdrMode = normalizedVideoHdrMode
        }
        if (prefs.videoColorSpace != normalizedVideoColorSpace) {
            prefs.videoColorSpace = normalizedVideoColorSpace
        }
        _videoColorSpace.value = normalizedVideoColorSpace
        _videoDetailProfile.value = prefs.videoDetailProfile
        gpuRecording.videoDetailProfile = _videoDetailProfile.value
        camera.setVideoDetailProfile(_videoDetailProfile.value)
        _videoHdrMode.value = restoredVideoMode.hdrMode
        // Normal processed recording stays active; RAW remains an independent NPRAW sensor path.
        _cinemaVideoMode.value = restoredCinemaMode
        _rawVideoCompression.value = normalizeRawCompressionForMode(
            RawCompressionMode.fromName(prefs.rawVideoCompression),
            restoredCinemaMode
        )
        _rawResolutionMode.value = prefs.rawResolutionMode
        syncRecordingBitrateMode(prefs.videoBitrateMode)
        _videoHdrOn.value = restoredVideoMode.hdrEnabled
        _colorProfile.value = prefs.colorProfile
        val restoredLutProfile = when {
            prefs.lutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true) && !customLutLoaded -> "Neutral Film"
            else -> prefs.lutProfile
        }
        val restoredLutEnabled = prefs.lutEnabled &&
            (!restoredLutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true) || customLutLoaded)
        _lutEnabled.value = restoredLutEnabled
        _lutProfile.value = restoredLutProfile
        _aiEnhancement.value = effectiveAiEnhancementEnabled()
        _previewAspect.value = prefs.previewAspectRatio
        applyAiTrackingPreferences()

        // Apply cinema mode last since it drives RAW/LOG flags and may force HDR off for fidelity modes.
        applyCinemaModeSelection(restoredVideoMode.cinemaMode, persist = false)
        enforceVideoMeteringDefaultIfNeeded()

        applyColorPipelineState(
            colorProfile = prefs.colorProfile,
            lutEnabled = restoredLutEnabled,
            lutProfile = restoredLutProfile,
            logEnabled = _logOn.value
        )
        colorScience.saturation = prefs.saturation
        colorScience.contrast = prefs.contrast
        colorScience.brightness = prefs.brightness
        gpuRecording.videoColorSpace = _videoColorSpace.value
        syncEffectiveRecordingColorProfile()
        rawVideoManager.compressionMode = _rawVideoCompression.value
        val configuredAspect = _previewAspect.value
        videoFeatureState = VideoFeatureState(
            rawEnabled = restoredVideoMode.rawEnabled,
            logEnabled = restoredVideoMode.logEnabled,
            hdrEnabled = restoredVideoMode.hdrEnabled,
            histogramEnabled = _showHist.value,
            waveformEnabled = _showWfm.value,
            zebraEnabled = _showZebra.value,
            peakingEnabled = _peaking.value,
            aspect = configuredAspect,
            colorProfile = _colorProfile.value,
            lutEnabled = _lutEnabled.value,
            lutProfile = _lutProfile.value,
            colorSpace = _videoColorSpace.value,
            hdrMode = restoredVideoMode.hdrMode,
            cinemaMode = restoredVideoMode.cinemaMode,
            rawCompression = _rawVideoCompression.value
        )
        restoreVideoFeatures(persist = false)
        syncCameraPreviewLookControls()
        applyAutomaticVideoBitrateIfNeeded(force = true)
        syncGeometryTargets()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
        lastAppliedPreferencesSignature = currentPreferencesRefreshSignature()
    }

    private fun restorePersistedManualControls() {
        if (!camera.supportsManualSensor) return
        val restorePlan = CameraManualControlStateSyncPolicy.resolveRestorePlan(
            persistedIso = prefs.lastISO,
            isoRange = camera.effectiveIsoRange,
            persistedExposureNs = prefs.lastShutterNs,
            exposureRange = camera.exposureRange,
            persistedWhiteBalanceKelvin = prefs.lastWBKelvin,
            whiteBalanceRange = camera.manualWhiteBalanceRange
        )
        when (val iso = restorePlan.iso) {
            is ManualControlValue.Manual -> camera.setISO(iso.value.toInt())
            ManualControlValue.Auto -> camera.setAutoISO()
        }
        when (val exposure = restorePlan.exposureNs) {
            is ManualControlValue.Manual -> camera.setExposure(exposure.value.toLong())
            ManualControlValue.Auto -> camera.setAutoExposure()
        }
        when (val whiteBalance = restorePlan.whiteBalanceKelvin) {
            is ManualControlValue.Manual -> camera.setWB(whiteBalance.value.toInt(), prefs.lastWBTint)
            ManualControlValue.Auto -> camera.setAutoWB()
        }
    }


    private fun supportedVideoSize(
        preferred: Size,
        codec: GpuVideoCodec = gpuRecording.videoCodec
    ): Size = resolveRecordingSelection(preferred, codec).size

    private fun resolveRecordingSelection(
        preferred: Size,
        preferredCodec: GpuVideoCodec = gpuRecording.videoCodec
    ): ResolvedRecordingSelection {
        val graphContract = activeVideoPipelineContract()
        val parityPlan = activeRecordingPipelineParityPlan(graphContract)
        return CameraRecordingSelectionCoordinator.resolve(
            CameraRecordingSelectionRequest(
                preferred = preferred,
                preferredCodec = preferredCodec,
                availableSizes = availableVideoSizes(),
                pixelArraySize = camera.pixelArraySize,
                maxRawSize = camera.maxRawSize,
                fallbackSensorSize = camera.fullSensorSize(),
                uiMode = _uiMode.value,
                selectedOutputAspectRatio = selectedRecordingAspectRatio(),
                cinemaMode = graphContract.cinemaMode,
                rawEnabled = parityPlan.rawRequestedForSessionPlan,
                rawSupported = camera.supportsRaw,
                av1Supported = supportsAv1Fast(),
                hdrRequested = parityPlan.hdrRequestedForSessionPlan,
                tenBitRequested = parityPlan.tenBitRequestedForSessionPlan,
                fps = resolveContractRequestedFps(prefs.videoFps),
                normalizeRecordingRequest = { size -> size.normalizeForOrientation() },
                resolveSupportedSize = { size, codec, fps ->
                    gpuRecording.resolveSupportedSize(size, codec, fps)
                }
            )
        )
    }

    private fun availableVideoSizes(): List<Size> =
        buildList {
            addAll(camera.recordingSizes)
            addAll(camera.videoSizes)
        }
            .distinctBy { "${it.width}x${it.height}" }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }

    internal fun availableVideoDimensionsForSettings(): List<RecordingDimensions> =
        availableVideoSizes().map { size -> RecordingDimensions(size.width, size.height) }

    private fun audioSourceFromPref(value: String): AudioSrc =
        CameraPreferenceValuePolicy.audioSourceFromPref(value)

    private fun isRawVideoModeActive(): Boolean =
        _uiMode.value == AppMode.VIDEO &&
            _cinemaVideoMode.value == CinemaVideoMode.RAW &&
            _rawOn.value

    private fun resolveContractRequestedFps(
        requestedFps: Int,
        cinemaMode: CinemaVideoMode = _cinemaVideoMode.value
    ): Int =
        CameraPreferenceValuePolicy.resolveContractRequestedFps(
            requestedFps = requestedFps,
            rawVideoModeActive = _uiMode.value == AppMode.VIDEO && cinemaMode == CinemaVideoMode.RAW,
            rawModeMaxFps = RAW_MODE_MAX_FPS
        )

    private fun resolveVideoRecordingTenBitRequested(
        cinemaMode: CinemaVideoMode = _cinemaVideoMode.value,
        hdrMode: String = _videoHdrMode.value
    ): Boolean =
        VideoRecordingContractPolicy.resolveTenBitRecordingRequested(
            cinemaMode = cinemaMode,
            normalizedHdrMode = hdrMode
        )

    private fun normalizeRawCompressionForMode(
        mode: RawCompressionMode,
        cinemaMode: CinemaVideoMode = _cinemaVideoMode.value
    ): RawCompressionMode =
        CameraPreferenceValuePolicy.normalizeRawCompressionForMode(
            mode = mode,
            cinemaMode = cinemaMode
        )

    private fun syncRecordingCodecAndBitDepthContract(
        requestedCodec: VideoCodecType = recording.codec,
        cinemaMode: CinemaVideoMode = _cinemaVideoMode.value
    ) {
        val graphContract = activeVideoPipelineContract(
            cinemaMode = cinemaMode,
            rawVideoActive = _uiMode.value == AppMode.VIDEO &&
                cinemaMode == CinemaVideoMode.RAW &&
                _rawOn.value
        )
        val parityPlan = activeRecordingPipelineParityPlan(graphContract)
        val sessionPlan = RecordingCapabilityPolicy.resolveSessionPlan(
            requestedCodec = VideoRecordingContractPolicy.resolveRequestedCodec(requestedCodec),
            av1Supported = supportsAv1Fast(),
            hdrRequested = parityPlan.hdrRequestedForSessionPlan,
            rawRequested = parityPlan.rawRequestedForSessionPlan,
            rawSupported = camera.supportsRaw,
            logEnabled = parityPlan.logEnabledForSessionPlan,
            tenBitRequested = parityPlan.tenBitRequestedForSessionPlan
        )
        prefs.tenBitRecordingEnabled = parityPlan.tenBitRequestedForSessionPlan
        gpuRecording.videoCodec = sessionPlan.codec
        gpuRecording.tenBitEnabled = sessionPlan.tenBitPipelineEnabled
        recording.codec = RecordingCapabilityPolicy.toVideoCodecType(sessionPlan.codec)
        prefs.videoCodec = recording.codec.name
        logRecordingPipelineParityPlan(parityPlan, reason = "codec-bit-depth")
    }

    private fun activeVideoFormatDeliveryState(): VideoFormatDeliveryState =
        VideoFormatDeliveryPolicy.resolve(
            uiMode = _uiMode.value,
            cinemaMode = _cinemaVideoMode.value,
            rawVideoActive = isRawVideoModeActive(),
            logEnabled = _logOn.value,
            requestedHdrMode = _videoHdrMode.value,
            preferredColorSpace = _videoColorSpace.value,
            tenBitActive = gpuRecording.tenBitEnabled || resolveVideoRecordingTenBitRequested(),
            characteristics = camera.characteristics,
            gpu10BitSupported = gpuRecording.supports10Bit(),
            width = recording.width,
            height = recording.height,
            fps = recording.fps
        )

    private fun activeVideoPipelineContract(
        appMode: AppMode = _uiMode.value,
        cinemaMode: CinemaVideoMode = _cinemaVideoMode.value,
        rawVideoActive: Boolean = appMode == AppMode.VIDEO &&
            cinemaMode == CinemaVideoMode.RAW &&
            _rawOn.value,
        logEnabled: Boolean = _logOn.value,
        requestedHdrMode: String = _videoHdrMode.value,
        preferredColorSpace: String = _videoColorSpace.value,
        tenBitRequested: Boolean = gpuRecording.tenBitEnabled ||
            resolveVideoRecordingTenBitRequested(cinemaMode, requestedHdrMode),
        recordingState: GpuRecordingState = gpuRecordState.value,
        videoDetailProfile: VideoDetailProfile = _videoDetailProfile.value
    ): VideoPipelineContract {
        val previewSize = activeVideoGraphPreviewSize()
        return VideoPipelineGraphController.resolve(
            VideoPipelineGraphRequest(
                appMode = appMode,
                cinemaMode = cinemaMode,
                rawVideoActive = rawVideoActive,
                logEnabled = logEnabled,
                requestedHdrMode = requestedHdrMode,
                preferredColorSpace = preferredColorSpace,
                tenBitRequested = tenBitRequested,
                previewWidth = previewSize.width,
                previewHeight = previewSize.height,
                encoderWidth = gpuRecording.videoWidth,
                encoderHeight = gpuRecording.videoHeight,
                fps = recording.fps,
                recordingState = recordingState,
                videoDetailProfile = videoDetailProfile,
                gpu10BitSupported = gpuRecording.supports10Bit()
            )
        )
    }

    private fun activeVideoGraphPreviewSize(): Size =
        boundPreviewSize ?: selectedVideoSize ?: Size(1920, 1080)

    private fun activeRecordingPipelineParityPlan(
        graphContract: VideoPipelineContract = activeVideoPipelineContract(),
        actualFeedPathOverride: RecordingFeedPath? = null
    ): RecordingPipelineParityPlan {
        val hybridRoutePlan = activeRecordingHybridRoutePlan(graphContract)
        return RecordingPipelineParityPolicy.resolve(
            graphContract = graphContract,
            rawSupported = camera.supportsRaw,
            vulkanRuntimeSupported = vulkanRecordingPathQualified(),
            offscreenAhbEglEncoderSupported = hybridRoutePlan.offscreenAhbEglEncoderSupported,
            byteBufferP010EncoderSupported = hybridRoutePlan.byteBufferP010EncoderSupported,
            preferByteBufferP010Encoder = hybridRoutePlan.preferByteBufferP010Encoder,
            selectedColorScience = resolvePreviewRecordingParityColorScience(graphContract),
            stabilizationMode = stabilizationMode.name,
            actualFeedPathOverride = actualFeedPathOverride ?: activeRecordingFeedPath,
            renderGraphLock = activePreviewRecordingRenderGraphLock(graphContract)
        )
    }

    private fun vulkanEncoderSurfaceBlockReason(): String? =
        VulkanEncoderSurfaceSafetyPolicy.blockingReason(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            product = Build.PRODUCT
        )

    private fun byteBufferP010EncoderCandidateAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            gpuRecording.supports10Bit(GpuVideoCodec.HEVC)

    @Volatile
    private var hdrZeroCopySizeRateSupportCacheBacking:
        java.util.concurrent.ConcurrentHashMap<String, Boolean>? = null

    private fun hdrZeroCopySizeRateSupportCache():
        java.util.concurrent.ConcurrentHashMap<String, Boolean> {
        hdrZeroCopySizeRateSupportCacheBacking?.let { return it }
        val created = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        hdrZeroCopySizeRateSupportCacheBacking = created
        return created
    }

    private fun hdrZeroCopyEncoderAcceptsSizeRate(width: Int, height: Int, fps: Int): Boolean =
        hdrZeroCopySizeRateSupportCache().getOrPut("${width}x${height}@$fps") {
            RecordingCodecSupportProbe.supportsHevcMain10SizeAndRate(width, height, fps)
        }

    private fun hdrZeroCopyRecordingEligible(graphContract: VideoPipelineContract): Boolean {
        if (graphContract.deliveryState.effectiveHdrMode == "OFF") return false
        val hdr = camera.describeHdrDynamicRangeState()
        // Neo HDR recording is PQ-only. A policy-resolved value is not enough: the currently
        // configured preview session must have actually accepted a BT.2100 PQ 10-bit profile before this route
        // can be admitted. HLG and STANDARD are never silent NDR fallbacks.
        if (!NdrInputTransportPolicy.isPqProfile(hdr.previewProfile)) return false
        val size = selectedVideoSize ?: Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
        return hdrZeroCopyEncoderAcceptsSizeRate(size.width, size.height, recording.fps)
    }

    private fun activeRecordingHybridRoutePlan(
        graphContract: VideoPipelineContract = activeVideoPipelineContract()
    ): RecordingHybridRoutePlan {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val devicePrefersP010 =
            vulkanEncoderSurfaceBlockReason() != null ||
                manufacturer.contains("vivo", ignoreCase = true) ||
                manufacturer.contains("iqoo", ignoreCase = true) ||
                model.contains("I2405", ignoreCase = true)
        return RecordingHybridRoutePolicy.resolve(
            deliveryKind = graphContract.deliveryKind,
            effectiveHdrMode = graphContract.deliveryState.effectiveHdrMode,
            tenBitRequested = graphContract.tenBitRequested,
            apiLevel = Build.VERSION.SDK_INT,
            vulkanRuntimeSupported = vulkanSupported.value,
            vulkanPipelineHealthy = vulkanRuntime.isPipelineHealthy(),
            vulkanEncoderSurfaceBlocked = vulkanEncoderSurfaceBlockReason() != null,
            offscreenProvenUnavailable = vulkanRuntime.isOffscreenAhbEglEncoderProvenUnavailable,
            byteBufferP010CandidateAvailable = byteBufferP010EncoderCandidateAvailable(),
            devicePrefersP010 = devicePrefersP010,
            bypassByteBufferP010ForRealtime = bypassByteBufferP010ForRealtime(graphContract),
            hdrZeroCopyEligible = hdrZeroCopyRecordingEligible(graphContract)
        )
    }

    private fun preferByteBufferP010EncoderRoute(
        graphContract: VideoPipelineContract = activeVideoPipelineContract()
    ): Boolean =
        activeRecordingHybridRoutePlan(graphContract).preferByteBufferP010Encoder

    private fun bypassByteBufferP010ForRealtime(graphContract: VideoPipelineContract = activeVideoPipelineContract()): Boolean =
        (selectedVideoSize ?: Size(gpuRecording.videoWidth, gpuRecording.videoHeight)).let { RecordingRealtimeRoutePolicy.shouldBypassByteBufferP010ForRealtime(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT, it.width, it.height, recording.fps, graphContract.deliveryKind, graphContract.deliveryState.effectiveHdrMode) }

    private fun vulkanRecordingPathQualified(): Boolean {
        val deviceBlockReason = vulkanEncoderSurfaceBlockReason()
        if (deviceBlockReason != null) {
            if (!vulkanRecordingDeviceBlockLogged) {
                vulkanRecordingDeviceBlockLogged = true
                Log.w(
                    "CameraViewModel",
                    "Vulkan recording hardware-import path blocked: " +
                        "reason=$deviceBlockReason " +
                        "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                        "device=${Build.DEVICE} product=${Build.PRODUCT}"
                )
            }
            return false
        }
        val signature = currentVulkanRecordingThroughputSignature()
        if (
            signature == vulkanRecordingThroughputBlockedSignature &&
            SystemClock.elapsedRealtime() < vulkanRecordingThroughputBlockedUntilElapsedMs
        ) {
            return false
        }
        return vulkanSupported.value &&
            vulkanRuntime.isPipelineHealthy()
    }

    /** Build+OS fingerprint that scopes the persisted offscreen-EGL "unavailable" recall. Includes
     *  the app version (a new build with an EGL fix must re-probe) and the OS fingerprint (a GPU
     *  driver OTA must re-probe), so a stale failure is never trusted forever. */
    private fun offscreenEglRecallFingerprint(): String =
        "${com.neoproc.neoproccamx.BuildConfig.VERSION_CODE}:${Build.FINGERPRINT}"

    private fun offscreenAhbEglEncoderQualified(
        graphContract: VideoPipelineContract = activeVideoPipelineContract()
    ): Boolean =
        activeRecordingHybridRoutePlan(graphContract).offscreenAhbEglEncoderSupported

    private fun currentVulkanRecordingThroughputSignature(): String {
        val size = selectedVideoSize ?: Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
        return listOf(
            size.width,
            size.height,
            recording.fps,
            _videoHdrMode.value,
            _videoColorSpace.value,
            _cinemaVideoMode.value.name
        ).joinToString(separator = ":")
    }

    private fun hasValidatedVulkanRecordingEvidenceForCurrentSignature(): Boolean {
        val evidence = lastValidatedGpuRecordingEvidence
        return evidence.frameImportEnabled &&
            evidence.encoderSurfaceBound &&
            evidence.importedFrames > 0L &&
            evidence.recordingSignature == currentVulkanRecordingThroughputSignature()
    }

    private fun hasCurrentRecordingEncoderSurfaceEvidence(): Boolean {
        val recordingInProgress = when (gpuRecordState.value) {
            is GpuRecordingState.Recording,
            is GpuRecordingState.Paused,
            is GpuRecordingState.Stopping -> true
            else -> false
        }
        return recordingInProgress &&
            lastValidatedGpuRecordingEvidence.importedFrames > 0L &&
            lastValidatedGpuRecordingEvidence.recordingSignature ==
                currentVulkanRecordingThroughputSignature()
    }

    private fun markVulkanRecordingThroughputFailure(failureMessage: String?) {
        vulkanRecordingThroughputBlockedSignature = currentVulkanRecordingThroughputSignature()
        vulkanRecordingThroughputBlockedUntilElapsedMs =
            SystemClock.elapsedRealtime() +
                ProcessedGpuRecordingRequirementPolicy.THROUGHPUT_FAILURE_BLOCK_MS
        Log.w(
            "CameraViewModel",
            "Vulkan recording hardware-import path blocked after measured throughput failure: " +
                "signature=$vulkanRecordingThroughputBlockedSignature " +
                "blockedUntilMs=$vulkanRecordingThroughputBlockedUntilElapsedMs " +
                "reason=${failureMessage ?: "unknown"}"
        )
    }

    private fun activePreviewRecordingRenderGraphLock(
        graphContract: VideoPipelineContract = activeVideoPipelineContract()
    ): PreviewRecordingRenderGraphLock {
        val renderContract = activeVulkanPreviewRenderContract()
        val colorScience = resolvePreviewRecordingParityColorScience(graphContract)
        val colorSpace = graphContract.deliveryState.effectiveColorSpace
        val modeContract = graphContract.deliveryState.pipelineContract
        val previewShaderSignature = listOf(
            "shader",
            renderContract.signature,
            graphContract.graphSignature,
            graphContract.denoiseProfile.name,
            graphContract.sharpnessProfile.name
        ).joinToString(separator = ":")
        val recordingShaderSignature = listOf(
            "shader",
            renderContract.signature,
            graphContract.graphSignature,
            graphContract.denoiseProfile.name,
            graphContract.sharpnessProfile.name
        ).joinToString(separator = ":")
        val previewLutSignature = listOf(
            "lut",
            nativePreviewLutEnabled.toString(),
            nativePreviewLutSize.toString(),
            colorScience,
            _colorProfile.value
        ).joinToString(separator = ":")
        val recordingLutSignature = listOf(
            "lut",
            nativePreviewLutEnabled.toString(),
            nativePreviewLutSize.toString(),
            colorScience,
            _colorProfile.value
        ).joinToString(separator = ":")
        val previewEnhancementIdentity = lastGpuEnhancementUniforms?.signature.orEmpty()
        val recordingEnhancementIdentity = lastGpuEnhancementUniforms?.signature.orEmpty()
        val previewStages = modeContract.previewPipelineStages.map { it.name }
        val recordingStages = modeContract.recordingPipelineStages.map { it.name }
        val previewOutput = modeContract.previewStagesDetail
            .lastOrNull { it.stage == VideoSensorPipelineStage.OUTPUT }
            ?: modeContract.previewStagesDetail.lastOrNull()
        val recordingOutput = modeContract.recordingStagesDetail
            .lastOrNull { it.stage == VideoSensorPipelineStage.OUTPUT }
            ?: modeContract.recordingStagesDetail.lastOrNull()
        val previewOutputBitDepth = previewOutput?.bitDepth ?: 0
        val plannedRecordingOutputBitDepth = recordingOutput?.bitDepth ?: 0
        val liveEncoderSurfaceStatus = vulkanRuntime.currentEncoderSurfaceStatus
        val validatedEncoderSurfaceCurrent =
            hasCurrentRecordingEncoderSurfaceEvidence()
        val vulkanEncoderSurfaceExpected =
            activeRecordingFeedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ||
                liveEncoderSurfaceStatus.bound ||
                validatedEncoderSurfaceCurrent
        val actualRecordingOutputBitDepth = when {
            liveEncoderSurfaceStatus.precisionProven ->
                minOf(plannedRecordingOutputBitDepth, liveEncoderSurfaceStatus.bitDepth)
            validatedEncoderSurfaceCurrent &&
                lastValidatedGpuRecordingEvidence.encoderSurfacePrecisionProven ->
                minOf(
                    plannedRecordingOutputBitDepth,
                    lastValidatedGpuRecordingEvidence.encoderSurfaceBitDepth
                )
            vulkanEncoderSurfaceExpected -> 0
            else -> plannedRecordingOutputBitDepth
        }
        val actualRecordingOutputFormat = when {
            liveEncoderSurfaceStatus.precisionProven -> liveEncoderSurfaceStatus.format
            validatedEncoderSurfaceCurrent &&
                lastValidatedGpuRecordingEvidence.encoderSurfacePrecisionProven ->
                lastValidatedGpuRecordingEvidence.encoderSurfaceFormat
            vulkanEncoderSurfaceExpected -> "UNKNOWN"
            else -> recordingOutput?.vulkanFormat.orEmpty()
        }
        return PreviewRecordingRenderGraphLock(
            previewShaderSha256 = previewShaderSignature,
            recordingShaderSha256 = recordingShaderSignature,
            previewLutSha256 = previewLutSignature,
            recordingLutSha256 = recordingLutSignature,
            previewColorSpace = colorSpace,
            recordingColorSpace = colorSpace,
            previewColorScience = colorScience,
            recordingColorScience = colorScience,
            previewColorUniformsSha256 = lastPreviewColorUniformSha256,
            recordingColorUniformsSha256 = lastRecordingColorUniformSha256,
            monitorDeliveryColorUniformDifferenceAllowed =
                lastMonitorDeliveryColorUniformDifferenceAllowed,
            previewEnhancementIdentity = previewEnhancementIdentity,
            recordingEnhancementIdentity = recordingEnhancementIdentity,
            previewGradeIdentity = lastPreviewGradeSha256,
            recordingGradeIdentity = lastRecordingGradeSha256,
            previewDenoiseProfile = graphContract.denoiseProfile.name,
            recordingDenoiseProfile = graphContract.denoiseProfile.name,
            previewSharpnessProfile = graphContract.sharpnessProfile.name,
            recordingSharpnessProfile = graphContract.sharpnessProfile.name,
            previewStages = previewStages,
            recordingStages = recordingStages,
            previewOutputFormat = previewOutput?.vulkanFormat.orEmpty(),
            recordingOutputFormat = actualRecordingOutputFormat,
            previewOutputTransfer = previewOutput?.transferFunction.orEmpty(),
            recordingOutputTransfer = recordingOutput?.transferFunction.orEmpty(),
            previewOutputGamut = previewOutput?.gamut.orEmpty(),
            recordingOutputGamut = recordingOutput?.gamut.orEmpty(),
            previewOutputBitDepth = previewOutputBitDepth,
            recordingOutputBitDepth = actualRecordingOutputBitDepth,
            previewOutputQuantizationLevels =
                CameraViewModelRenderIdentityPolicy.quantizationLevelsForBitDepth(
                    previewOutputBitDepth
                ),
            recordingOutputQuantizationLevels =
                CameraViewModelRenderIdentityPolicy.quantizationLevelsForBitDepth(
                    actualRecordingOutputBitDepth
                ),
            pixelRealityBypassDetected = false,
            timelineSemaphoreRequired = true,
            averageFrameProcessingMs = vulkanRuntime.currentMeasuredAverageComputeDurationMs,
            averageRuntimeBudgetMs = if (strictHighEndQualityEnabled()) 6.0 else 14.0,
            runtimeMetadata =
                camera2FrameLineageRuntimeMetadata() +
                    recordDevelopRuntimeMetadata(
                        videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
                    )
        )
    }

    private fun recordDevelopRuntimeMetadata(
        videoSurface: Boolean = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
    ): Map<String, String> {
        val recordDevelopLightMaster = RecordDevelopLivePipelinePolicy.active(videoSurface)
        return linkedMapOf(
            "recordDevelopModeEnabled" to RecordDevelopModeFlag.enabled.toString(),
            "recordDevelopLightMaster" to recordDevelopLightMaster.toString(),
            "recordDevelopLiveColorProcessing" to if (recordDevelopLightMaster) {
                "FLAT_10BIT_MASTER_ONLY"
            } else {
                "LIVE_GPU_DEVELOP"
            },
            "recordDevelopOfflineDevelopRequired" to recordDevelopLightMaster.toString(),
            "recordDevelopOfflineOutputFormat" to if (recordDevelopLightMaster) {
                "R210_444"
            } else {
                ""
            },
            "recordDevelopOfflineEnhanceProfile" to if (recordDevelopLightMaster) {
                "ELITE_SNCE_TEMPORAL_DENOISE_DETAIL"
            } else {
                ""
            }
        )
    }

    private fun beginCamera2FrameLineageSession(
        parityPlan: RecordingPipelineParityPlan
    ) {
        val generation = SystemClock.elapsedRealtimeNanos().takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        val roles = buildSet {
            add("capture")
            if (parityPlan.feedPath != RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE) {
                add("preview")
            }
            if (parityPlan.feedPath != RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE) {
                add("encoder")
            }
            add(parityPlan.feedPath.name)
        }
        synchronized(camera2FrameLineageLock) {
            camera2FrameLineageSessionGeneration = generation
            camera2FrameLineageRequestRevision = 0L
            camera2FrameLineageTargetRoles = roles
            camera2FrameLineagePipelineSignature = parityPlan.paritySignature
            camera2FrameLineageAccumulator =
                Camera2FrameLineageAccumulator(expectedSessionGeneration = generation)
            camera2FrameLineageRecordingActive = true
            camera2FrameLineageTrackedCaptureResults = 0
            lastCamera2FrameLineageMetadata = camera2FrameLineageMetadataLocked()
        }
    }

    private fun freezeCamera2FrameLineageSession() {
        synchronized(camera2FrameLineageLock) {
            if (camera2FrameLineageAccumulator == null) return
            lastCamera2FrameLineageMetadata = camera2FrameLineageMetadataLocked()
            camera2FrameLineageRecordingActive = false
        }
    }

    private fun recordCamera2FrameLineageCaptureResult(result: TotalCaptureResult) {
        val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        synchronized(camera2FrameLineageLock) {
            val accumulator = camera2FrameLineageAccumulator ?: return
            if (!camera2FrameLineageRecordingActive) return
            if (camera2FrameLineageTrackedCaptureResults >= CAMERA2_FRAME_LINEAGE_MAX_TRACKED_CAPTURE_RESULTS) {
                lastCamera2FrameLineageMetadata = camera2FrameLineageMetadataLocked()
                camera2FrameLineageRecordingActive = false
                return
            }
            camera2FrameLineageRequestRevision++
            camera2FrameLineageTrackedCaptureResults++
            accumulator.recordCaptureResult(
                Camera2CaptureFrameEvidence(
                    sessionGeneration = camera2FrameLineageSessionGeneration,
                    requestRevision = camera2FrameLineageRequestRevision,
                    frameNumber = result.frameNumber,
                    sensorTimestampNs = timestampNs,
                    sensorFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                    exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    whiteBalanceMode = result.get(CaptureResult.CONTROL_AWB_MODE)
                        ?.let { mode -> "AWB_$mode" },
                    requestTag = Camera2RequestTag(
                        sessionGeneration = camera2FrameLineageSessionGeneration,
                        requestRevision = camera2FrameLineageRequestRevision,
                        targetRoles = camera2FrameLineageTargetRoles,
                        pipelineSignature = camera2FrameLineagePipelineSignature
                    )
                )
            )
            maybeFreezeCamera2FrameLineageIfReadyLocked()
        }
    }

    private fun recordCamera2FrameLineageSubmission(
        submission: VulkanFrameLineageSubmission
    ) {
        if (submission.sensorTimestampNs <= 0L) return
        synchronized(camera2FrameLineageLock) {
            val accumulator = camera2FrameLineageAccumulator ?: return
            if (!camera2FrameLineageRecordingActive) return
            accumulator.recordImageTimestamp(submission.sensorTimestampNs)
            if (submission.previewSubmitted) {
                accumulator.recordPreviewSubmission(
                    sensorTimestampNs = submission.sensorTimestampNs,
                    presentationTimestampNs = submission.presentationTimeNs
                )
            }
            if (submission.encoderSubmitted) {
                accumulator.recordEncoderSubmission(
                    sensorTimestampNs = submission.sensorTimestampNs,
                    presentationTimestampNs = submission.presentationTimeNs
                )
            }
            maybeFreezeCamera2FrameLineageIfReadyLocked()
        }
    }

    private fun maybeFreezeCamera2FrameLineageIfReadyLocked() {
        val metadata = camera2FrameLineageMetadataLocked()
        lastCamera2FrameLineageMetadata = metadata
        if (
            metadata["camera2FrameLineageCertified"] == "true" ||
            camera2FrameLineageTrackedCaptureResults >= CAMERA2_FRAME_LINEAGE_MAX_TRACKED_CAPTURE_RESULTS
        ) {
            camera2FrameLineageRecordingActive = false
        }
    }

    private fun camera2FrameLineageRuntimeMetadata(): Map<String, String> =
        synchronized(camera2FrameLineageLock) {
            camera2FrameLineageMetadataLocked()
        }

    private fun camera2FrameLineageMetadataLocked(): Map<String, String> {
        val accumulator = camera2FrameLineageAccumulator
            ?: return lastCamera2FrameLineageMetadata.ifEmpty {
                mapOf(
                    "camera2FrameLineageCertificatePresent" to "false",
                    "camera2FrameLineageCertified" to "false"
                )
            }
        return accumulator.validateStrictCertificate().toCompactMetadataMap() + mapOf(
            "camera2FrameLineageSessionGeneration" to
                camera2FrameLineageSessionGeneration.toString(),
            "camera2FrameLineageRecordingActive" to camera2FrameLineageRecordingActive.toString(),
            "camera2FrameLineageTargetRoles" to
                camera2FrameLineageTargetRoles.joinToString(separator = ","),
            "camera2FrameLineagePipelineSignature" to camera2FrameLineagePipelineSignature,
            "camera2FrameLineageTrackedCaptureResults" to
                camera2FrameLineageTrackedCaptureResults.toString()
        )
    }

    private fun activePixelProcessingRealityReport(
        parityPlan: RecordingPipelineParityPlan = activeRecordingPipelineParityPlan()
    ): PixelProcessingRealityReport {
        val renderContract = activeVulkanPreviewRenderContract()
        val importStats = vulkanImportStats.value
        val validationStats = importStats.toProcessedGpuRecordingValidationStats()
        return PixelProcessingRealityPolicy.resolve(
            PixelProcessingRealityRequest(
                parityPlan = parityPlan,
                nativePreviewActive = nativeVisiblePreviewActive,
                vulkanRuntimeSupported = vulkanSupported.value,
                aiFrameProcessingActive = hasObservedAiFrameProcessing(),
                multiFrameVideoProcessingActive = false,
                recordingGpuFrameImportValidated =
                    validationStats.frameImportEnabled &&
                        validationStats.encoderSurfaceBound &&
                        validationStats.importedFrames > 0L,
                rawGpuDebayerActive = nativeVisiblePreviewActive &&
                    vulkanSupported.value &&
                    renderContract.renderPath == VulkanPreviewRenderPath.CINEMA_DEBAYER_MONITOR
            )
        )
    }

    private fun hasObservedAiFrameProcessing(): Boolean =
        _aiEnhancement.value &&
            (_aiEnhancementResult.value != null || pendingAICameraFrameResult.get() != null)

    private fun activeCamera2SessionGraphParityPlan(
        graphContract: VideoPipelineContract = activeVideoPipelineContract(),
        parityPlan: RecordingPipelineParityPlan = activeRecordingPipelineParityPlan(graphContract),
        previewSize: Size = activeVideoGraphPreviewSize(),
        encoderSize: Size = Size(gpuRecording.videoWidth, gpuRecording.videoHeight),
        proposedAnalysisSize: Size = preferredRecordingAnalysisSize(encoderSize),
        proposedReadoutOverrideSize: Size? = null,
        recordSurfaceAttachedToSession: Boolean = prearmedRecordingSurfaceAttachedToSession,
        encoderSurfaceAvailable: Boolean = prearmedRecordingSurface != null ||
            gpuRecordState.value is GpuRecordingState.Ready ||
            gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused,
        previousSessionSignature: String? = null,
        stage: Camera2SessionGraphStage = Camera2SessionGraphStage.IDLE
    ): Camera2SessionGraphParityPlan =
        Camera2SessionGraphParityPolicy.resolve(
            Camera2SessionGraphParityRequest(
                graphContract = graphContract,
                parityPlan = parityPlan,
                previewSize = previewSize,
                encoderSize = encoderSize,
                proposedAnalysisSize = proposedAnalysisSize,
                proposedReadoutOverrideSize = proposedReadoutOverrideSize,
                recordSurfaceAttachedToSession = recordSurfaceAttachedToSession,
                encoderSurfaceAvailable = encoderSurfaceAvailable,
                previousSessionSignature = previousSessionSignature,
                stage = stage
            )
        )

    private fun activeVideoModePipelineContract(): VideoModePipelineContract =
        VideoModePipelineContractPolicy.resolve(
            uiMode = _uiMode.value,
            cinemaMode = _cinemaVideoMode.value,
            rawVideoActive = isRawVideoModeActive(),
            logEnabled = _logOn.value,
            requestedHdrMode = _videoHdrMode.value,
            preferredColorSpace = _videoColorSpace.value,
            tenBitActive = gpuRecording.tenBitEnabled || resolveVideoRecordingTenBitRequested(),
            characteristics = camera.characteristics,
            gpu10BitSupported = gpuRecording.supports10Bit(),
            width = recording.width,
            height = recording.height,
            fps = recording.fps
        )

    private fun activeVideoPreviewLookState(): VideoFormatPreviewLookState =
        if (VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)) {
            VideoFormatPreviewLookPolicy.resolve(activeVideoFormatDeliveryState())
        } else {
            VideoFormatPreviewLookState()
        }

    private fun activeVulkanPreviewRenderContract(): VulkanPreviewRenderContract {
        val deliveryState = activeVideoFormatDeliveryState()
        val previewLookState = activeVideoPreviewLookState()
        val logCurve = if (previewLookState.logEnabled || deliveryState.logVideoActive) {
            NeoLogProfilePolicy.resolve(_colorProfile.value, logEnabled = true).curve
        } else {
            NeoLogCurve.NONE
        }
        return VulkanPreviewRenderContractPolicy.resolve(
            pipelineContract = deliveryState.pipelineContract,
            previewLookState = previewLookState,
            nativePreviewLutEnabled = nativePreviewLutEnabled,
            nativePreviewLutSize = nativePreviewLutSize,
            logCurve = logCurve,
            cinemaDebayerActive = _uiMode.value == AppMode.VIDEO &&
                _cinemaVideoMode.value == CinemaVideoMode.RAW,
            highLoadPreviewActive = isHighLoadPreviewMode(),
            previewFpsHint = lastPreviewFpsSample.takeIf { it > 0f }
                ?: VIDEO_IDLE_PREVIEW_FPS.toFloat(),
            cameraPreviewHdrTransportMode = camera.describeHdrDynamicRangeState().previewTransportMode
        )
    }

    private fun isWideGamutVideoSpace(colorSpace: String): Boolean =
        colorSpace.contains("2020", ignoreCase = true) ||
            colorSpace.contains("p3", ignoreCase = true) ||
            colorSpace.contains("aces", ignoreCase = true) ||
            colorSpace.contains("wide", ignoreCase = true) ||
            colorSpace.contains("neo", ignoreCase = true)

    private fun isEffectiveVideoHdrRequested(): Boolean =
        activeVideoFormatDeliveryState().effectiveHdrMode != "OFF"

    private fun normalizeColorProfileName(name: String): String =
        NeoLogProfilePolicy.canonicalSupportedLogFormat(name)
            ?: ColorScience.BUILT_IN_PROFILES.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
            ?: "Natural"

    private fun resolvePreviewRecordingParityColorScience(graphContract: VideoPipelineContract): String =
        when (graphContract.deliveryKind) {
            VideoDeliveryKind.HDR -> RecordingHdrVideoLookPolicy
                .originalHdrColorProfile(graphContract.effectiveHdrMode)
                ?: graphContract.effectiveHdrMode
            VideoDeliveryKind.LOG -> NeoLogProfilePolicy.effectiveOriginalLogFormat(
                profileName = _colorProfile.value,
                logEnabled = true
            ) ?: _colorProfile.value
            VideoDeliveryKind.RAW -> "RAW_SENSOR_ORIGINAL"
            VideoDeliveryKind.SDR -> normalizeColorProfileName(_colorProfile.value)
        }

    private fun resolveEffectiveRecordingColorProfile(
        recordingHdrMode: String = activeVideoFormatDeliveryState().effectiveHdrMode
    ): String {
        val deliveryState = activeVideoFormatDeliveryState()
        if (deliveryState.rawVideoActive) return "Natural"
        RecordingHdrVideoLookPolicy.originalHdrColorProfile(recordingHdrMode)?.let { return it }
        NeoLogProfilePolicy.effectiveOriginalLogFormat(
            profileName = _colorProfile.value,
            logEnabled = deliveryState.logVideoActive
        )?.let { return it }
        return when (normalizeColorProfileName(_colorProfile.value)) {
            "NDR10+ Native", "NIVUKX Vision" -> "Natural"
            else -> normalizeColorProfileName(_colorProfile.value)
        }
    }

    private fun syncEffectiveRecordingColorProfile() {
        val graphContract = activeVideoPipelineContract()
        val parityPlan = activeRecordingPipelineParityPlan(graphContract)
        val deliveryState = graphContract.deliveryState
        val hdrState = camera.describeHdrDynamicRangeState()
        val recordingTransport = when (parityPlan.deliveryKind) {
            VideoDeliveryKind.HDR -> VideoHdrRecordingTransportPolicy.resolve(
                requestedHdrMode = deliveryState.effectiveHdrMode,
                requestedColorSpace = deliveryState.effectiveColorSpace,
                hdrState = hdrState
            )
            VideoDeliveryKind.SDR,
            VideoDeliveryKind.LOG,
            VideoDeliveryKind.RAW -> VideoHdrRecordingTransport(
                hdrMode = "OFF",
                colorSpace = parityPlan.encoderColorSpace,
                colorProfile = null
            )
        }
        gpuRecording.videoHdrMode = recordingTransport.hdrMode
        gpuRecording.videoLogEnabled = parityPlan.encoderLogEnabled
        gpuRecording.videoColorSpace = recordingTransport.colorSpace
        gpuRecording.videoColorProfile = resolveEffectiveRecordingColorProfile(
            recordingHdrMode = recordingTransport.hdrMode
        )
        logVideoPipelineGraphContract(graphContract, reason = "color-sync")
        logRecordingPipelineParityPlan(parityPlan, reason = "color-sync")
        if (deliveryState.effectiveHdrMode != "OFF" || recordingTransport.hdrMode != "OFF") {
            Log.i(
                "CameraViewModel",
                "Recording HDR transport requested=${deliveryState.effectiveHdrMode}/${deliveryState.effectiveColorSpace} " +
                    "camera=${hdrState.recordingTransportMode} appliedProfile=${hdrState.encoderProfile} " +
                    "resolvedProfile=${hdrState.resolvedProfileForCurrentMode} " +
                    "encoder=${recordingTransport.hdrMode}/${recordingTransport.colorSpace}"
            )
        }
        gpuRecording.cinemaMode = effectiveRecordingCinemaMode(parityPlan)
        gpuRecording.rawVideoEnabled = parityPlan.encoderRawEnabled
    }

    private fun logVideoPipelineGraphContract(
        contract: VideoPipelineContract,
        reason: String,
        force: Boolean = false
    ) {
        if (!force && lastVideoPipelineGraphSignature == contract.graphSignature) return
        lastVideoPipelineGraphSignature = contract.graphSignature
        Log.i(
            "CameraViewModel",
            "Video graph contract[$reason]=${contract.graphSignature} " +
                "delivery=${contract.deliveryKind} hdr=${contract.effectiveHdrMode} " +
                "log=${contract.logEnabled} raw=${contract.rawEnabled} " +
                "preview=${contract.previewWidth}x${contract.previewHeight} " +
                "encoder=${contract.encoderWidth}x${contract.encoderHeight}@${contract.fps}"
        )
    }

    private fun logRecordingPipelineParityPlan(
        plan: RecordingPipelineParityPlan,
        reason: String,
        force: Boolean = false
    ) {
        if (!force && lastRecordingPipelineParitySignature == plan.paritySignature) {
            logPixelProcessingRealityReport(
                report = activePixelProcessingRealityReport(plan),
                reason = reason
            )
            return
        }
        lastRecordingPipelineParitySignature = plan.paritySignature
        Log.i(
            "CameraViewModel",
            "Recording parity[$reason]=${plan.paritySignature} " +
                "delivery=${plan.deliveryKind} feed=${plan.feedPath} " +
                "hdr=${plan.encoderHdrMode} color=${plan.encoderColorSpace} " +
                "log=${plan.encoderLogEnabled} raw=${plan.encoderRawEnabled} " +
                "tenBit=${plan.tenBitRequestedForSessionPlan} " +
                "certificate=${plan.parityCertificate.status} " +
                "blocked=${plan.parityCertificate.recordingBlocked} " +
                "shader=${plan.parityCertificate.renderGraphShaderSha256} " +
                "lut=${plan.parityCertificate.renderGraphLutSha256} " +
                "stages=${plan.parityCertificate.renderGraphStagesSignature} " +
                "geom=${plan.parityCertificate.geometryMatrixSignature} " +
                "viewportScale=${plan.viewportResolutionScale} " +
                "previewSig=${plan.parityCertificate.previewSignature} " +
                "recordingSig=${plan.parityCertificate.recordingSignature} " +
                "fallback=${plan.parityCertificate.fallbackReason}"
        )
        Log.i(
            "CameraViewModel",
            "Recording parity user-intent: hdr=${_videoHdrMode.value}"
        )
        when {
            plan.parityCertificate.camera2DirectFallback -> Log.w(
                "CameraViewModel",
                "Recording parity fallback[$reason] " +
                    "planned=${plan.parityCertificate.plannedFeedPath} " +
                    "actual=${plan.parityCertificate.actualFeedPath} " +
                    "failures=${plan.parityCertificate.failureCodes}"
            )
            plan.parityCertificate.directCamera2PathDetected -> Log.i(
                "CameraViewModel",
                "Recording parity direct-path[$reason] " +
                    "planned=${plan.parityCertificate.plannedFeedPath} " +
                    "actual=${plan.parityCertificate.actualFeedPath} " +
                    "reason=${plan.parityCertificate.fallbackReason}"
            )
        }
        logPixelProcessingRealityReport(
            report = activePixelProcessingRealityReport(plan),
            reason = reason,
            force = force
        )
    }

    private fun logPixelProcessingRealityReport(
        report: PixelProcessingRealityReport,
        reason: String,
        force: Boolean = false
    ) {
        if (!force && lastPixelProcessingRealitySignature == report.reportSignature) return
        lastPixelProcessingRealitySignature = report.reportSignature
        val message = "Pixel processing reality[$reason]=${report.reportSignature} " +
            "feed=${report.feedPath} previewGpu=${report.previewUsesCustomGpu} " +
            "recordingGpu=${report.recordingUsesCustomGpu} " +
            "real=${report.realStageNames.joinToString(separator = ",")} " +
            "plannedOnly=${report.plannedOnlyStageNames.joinToString(separator = ",")} " +
            "unsupported=${report.unsupportedStageNames.joinToString(separator = ",")}"
        if (report.camera2DirectBypassesCustomProcessing) {
            Log.w(
                "CameraViewModel",
                "$message Camera2 path bypasses custom Vulkan pixel processing for recording"
            )
        } else {
            Log.i("CameraViewModel", message)
        }
    }

    private fun logCamera2SessionGraphParityPlan(
        plan: Camera2SessionGraphParityPlan,
        reason: String,
        force: Boolean = false
    ) {
        if (!force && lastCamera2SessionGraphParitySignature == plan.sessionSignature) return
        lastCamera2SessionGraphParitySignature = plan.sessionSignature
        Log.i(
            "CameraViewModel",
            "Camera2 session graph[$reason]=${plan.sessionSignature} " +
                "kind=${plan.graphKind} delivery=${plan.deliveryKind} " +
                "refresh=${plan.refreshAction} preview=${plan.previewSize.width}x${plan.previewSize.height} " +
                "encoder=${plan.encoderSize.width}x${plan.encoderSize.height} " +
                "analysis=${plan.analysisTargetSize.width}x${plan.analysisTargetSize.height} " +
                "readout=${plan.readoutOverrideSize?.let { "${it.width}x${it.height}" } ?: "none"} " +
                "dualSwapchain=${plan.dualSwapchainParityRequired} " +
                "geom=${plan.geometryMatrixSignature} " +
                "viewportScale=${plan.viewportResolutionScale} " +
                "recordSurface=${plan.attachRecordSurfaceToSession} " +
                "previewContinuity=${plan.enablePreviewContinuityRecordSurface} " +
                "rawReadout=${plan.rawSensorOwnsReadout} reason=${plan.diagnosticReason}"
        )
    }

    private fun effectiveRecordingCinemaMode(plan: RecordingPipelineParityPlan): CinemaVideoMode =
        when {
            plan.encoderRawEnabled -> CinemaVideoMode.RAW
            plan.encoderLogEnabled -> CinemaVideoMode.LOG
            else -> CinemaVideoMode.PRO
        }

    private fun isVideoFormatNeutralPreviewEnabled(): Boolean =
        VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value) &&
            activeVideoFormatDeliveryState().neutralPreview

    private fun isVideoLogCaptureEnabled(): Boolean =
        VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value) &&
            activeVideoFormatDeliveryState().logVideoActive

    private fun isCinemaFidelityModeActive(): Boolean =
        _uiMode.value == AppMode.VIDEO &&
            (
                _manualControlsVisible.value ||
                    _cinemaVideoMode.value == CinemaVideoMode.LOG ||
                    _cinemaVideoMode.value == CinemaVideoMode.RAW
                )

    private fun camera2LowProcessingOverridesActive(): Boolean =
        _uiMode.value == AppMode.VIDEO &&
            (
                _cinemaVideoMode.value == CinemaVideoMode.LOG ||
                    _cinemaVideoMode.value == CinemaVideoMode.RAW ||
                    isVideoLogCaptureEnabled() ||
                    isRawVideoModeActive()
                )

    private fun isVideoLogPreviewActive(): Boolean =
        VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value) &&
            activeVideoFormatDeliveryState().logVideoActive &&
            !activeVideoFormatDeliveryState().neutralPreview

    private fun strictHighEndQualityEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun effectiveAiEnhancementEnabled(preferred: Boolean = prefs.aiEnhancementEnabled): Boolean =
        PreferencesManager.AI_ENHANCEMENT_SETTINGS_ENABLED && preferred

    private fun effectiveNoiseReductionEnabled(preferred: Boolean = prefs.noiseReductionEnabled): Boolean =
        preferred

    private fun currentManualVideoColorSelectionPlan(): ManualVideoColorSelectionPlan =
        ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = _videoColorSpace.value,
                colorScience = _colorProfile.value,
                colorSpaceSelectedByUser = colorSpaceSelectedThisSession,
                colorScienceSelectedByUser = colorScienceSelectedThisSession,
                savedColorSpacePreference = prefs.videoColorSpaceManuallySelected,
                savedColorSciencePreference = prefs.videoColorScienceManuallySelected
            )
        )

    private fun ensureManualVideoColorSelectionReadyForRecording(): Boolean {
        val plan = currentManualVideoColorSelectionPlan()
        if (plan.readyForRecording) return true
        val message = "Select Video Color Space and Color Science before recording: " +
            plan.failureReasons.joinToString(separator = "; ")
        Log.w("CameraViewModel", message)
        gpuRecording.reportExternalError(message)
        return false
    }

    private fun enforceJpegQualityFloor(): Int {
        val normalized = prefs.jpegQuality.coerceIn(MIN_HIGH_END_JPEG_QUALITY, 100)
        if (prefs.jpegQuality != normalized) {
            prefs.jpegQuality = normalized
        }
        return normalized
    }

    private fun persistedUiMode(): AppMode = AppMode.VIDEO

    private fun applyVideoProfileIntent(profileName: String) {
        if (_uiMode.value != AppMode.VIDEO) return
        val intent = VideoProfileIntentPolicy.resolve(profileName)
        if (intent.requiresWideGamut && !isWideGamutVideoSpace(_videoColorSpace.value)) {
            Log.i(
                "CameraViewModel",
                "Video profile '$profileName' prefers a wide-gamut color space; waiting for manual user selection"
            )
        }
        if (intent.impliesLogMode && !_logOn.value) {
            setLogEnabled(true)
        }
        if (!intent.impliesLogMode &&
            intent.recommendedHdrMode != null &&
            _videoHdrMode.value == "OFF"
        ) {
            setVideoHdrMode(intent.recommendedHdrMode)
        }
    }

    private fun targetPreviewFpsForCurrentMode(): Int {
        if (_uiMode.value != AppMode.VIDEO) return PHOTO_MODE_PREVIEW_FPS
        val resolvedSelection = lastRecordingSelection ?: resolveRecordingSelection(
            selectedVideoSize ?: prefs.videoSize,
            gpuRecording.videoCodec
        )
        val requestedFps = if (isRawVideoModeActive()) {
            recording.fps.coerceAtMost(RAW_MODE_MAX_FPS)
        } else {
            recording.fps
        }.coerceAtLeast(24)
        val frameRatePlan = RecordingPreviewFrameRatePolicy.resolve(
            selection = resolvedSelection,
            requestedFps = requestedFps
        )
        val recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping ||
            cameraState.value == CameraState.Recording
        return if (recordingActive) {
            frameRatePlan.previewFps
        } else {
            if (isRawVideoModeActive()) {
                VIDEO_IDLE_PREVIEW_FPS.coerceAtMost(frameRatePlan.previewFps)
            } else {
                frameRatePlan.previewFps
            }
        }
    }

    private fun syncActivePreviewFrameRate() {
        val requestedPreviewFps = targetPreviewFpsForCurrentMode()
        val appliedPreviewFps = camera.setFPS(requestedPreviewFps)
        if (_uiMode.value != AppMode.VIDEO) return
        val recordingActive =
            gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused ||
                gpuRecordState.value is GpuRecordingState.Stopping ||
                cameraState.value == CameraState.Recording
        val alignment = RecordingCameraFpsAlignmentPolicy.resolve(
            requestedEncoderFps = recording.fps,
            appliedCameraFps = appliedPreviewFps,
            videoModeActive = true
        )
        if (alignment.alignedToCamera) {
            val previousFps = recording.fps
            recording.fps = alignment.encoderFps
            gpuRecording.videoFps = alignment.encoderFps
            prefs.videoFps = alignment.encoderFps
            Log.i(
                "CameraViewModel",
                "Recording FPS aligned to camera output requested=$previousFps " +
                    "cameraApplied=$appliedPreviewFps encoder=${alignment.encoderFps}"
            )
        }
        if (!recordingActive && !alignment.alignedToCamera) return
        updatePreviewView { setEncoderFrameRate(alignment.previewFps) }
    }

    private fun scheduleAiWarmUp(
        delayMs: Long = StartupSmoothnessPolicy.aiWarmupDelayMs(),
        allowDuringStartupAiRamp: Boolean = false
    ) {
        aiWarmupJob?.cancel()
        aiWarmupJob = viewModelScope.launch(Dispatchers.Default) {
            delay(delayMs)
            if (startupFeatureGateActive) return@launch
            if (!allowDuringStartupAiRamp && startupAiRampActive) return@launch
            when (cameraState.value) {
                CameraState.Streaming,
                CameraState.Recording -> Unit
                else -> return@launch
            }
            if (shouldKeepRecordingSurfacePrearmed()) return@launch
            aiCameraPipeline.warmUpAsync()
        }
    }

    private fun startRecordingCapabilityWarmup() {
        if (recordingCapabilityWarmupJob != null) return
        recordingCapabilityWarmupJob = viewModelScope.launch(Dispatchers.Default) {
            val av1Supported = runCatching {
                gpuRecording.warmupCapabilityCaches()
            }.onFailure {
                Log.w("CameraViewModel", "Recording capability warm-up failed", it)
            }.getOrDefault(false)
            cachedAv1Supported = av1Supported
            av1SupportResolved = true
        }
    }

    private fun supportsAv1Fast(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return if (av1SupportResolved) cachedAv1Supported else false
        }
        val supported = gpuRecording.supportsAv1()
        cachedAv1Supported = supported
        av1SupportResolved = true
        return supported
    }

    private fun startStartupBootstrap() {
        startupCoordinator.startBootstrap()
    }

    private fun scheduleStartupOpenAwait() {
        startupCoordinator.scheduleOpenAwait()
    }

    private fun applyStartupPreferences() {
        val restoredUiMode = persistedUiMode()
        if (_uiMode.value != restoredUiMode) {
            _uiMode.value = restoredUiMode
        }
        prefs.lastUiMode = restoredUiMode.name

        val preferredCodec = RecordingCapabilityPolicy.resolveRequestedCodec(
            requested = VideoRecordingContractPolicy.resolveRequestedCodec(VideoCodecType.HEVC),
            av1Supported = supportsAv1Fast()
        )
        val desiredAudio = audioSourceFromPref(prefs.audioSource)
        val startupVideoSize = prefs.videoSize.normalizeForOrientation()
        val normalizedVideoHdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(prefs.videoHdrMode)
        val requestedCinemaMode = CinemaVideoMode.fromName(prefs.videoMode)
        val restoredVideoMode = VideoModeRestorePolicy.resolve(
            requestedHdrMode = normalizedVideoHdrMode,
            hdrRequested = normalizedVideoHdrMode != "OFF",
            uiMode = restoredUiMode,
            requestedCinemaMode = requestedCinemaMode,
            rawSupported = camera.supportsRaw
        )
        val normalizedVideoColorSpace = ManualVideoColorSelectionPolicy
            .normalizeColorSpace(prefs.videoColorSpace)
            .takeUnless { it == ManualVideoColorSelectionPolicy.UNSELECTED }
            ?: prefs.videoColorSpace
        val hasCustomLutPath = !prefs.customLutPath.isNullOrBlank()
        val wantsCustomLut = prefs.lutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true)
        val restoredLutProfile = when {
            wantsCustomLut && !hasCustomLutPath -> "Neutral Film"
            else -> prefs.lutProfile
        }
        val restoredLutEnabled = prefs.lutEnabled &&
            (!restoredLutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true) || hasCustomLutPath)

        syncRecordingBitrateMode(prefs.videoBitrateMode)
        recording.width = startupVideoSize.width
        recording.height = startupVideoSize.height
        recording.fps = resolveContractRequestedFps(prefs.videoFps)
        recording.bitrate = prefs.videoBitrate
        recording.audioSource = desiredAudio
        recording.codec = RecordingCapabilityPolicy.toVideoCodecType(preferredCodec)
        selectedVideoSize = startupVideoSize
        lastRecordingSelection = null

        gpuRecording.videoWidth = startupVideoSize.width
        gpuRecording.videoHeight = startupVideoSize.height
        gpuRecording.videoFps = resolveContractRequestedFps(prefs.videoFps)
        gpuRecording.videoBitrate = prefs.videoBitrate
        gpuRecording.audioSource = desiredAudio
        gpuRecording.videoCodec = preferredCodec
        gpuRecording.videoColorSpace = normalizedVideoColorSpace
        gpuRecording.videoColorProfile = prefs.colorProfile
        gpuRecording.videoLogEnabled = restoredVideoMode.logEnabled
        gpuRecording.videoHdrMode = restoredVideoMode.hdrMode
        gpuRecording.cinemaMode = restoredVideoMode.cinemaMode
        gpuRecording.rawVideoEnabled = restoredVideoMode.rawEnabled
        val startupSizeNormalized = startupVideoSize.normalizeForOrientation()
        val startupSizeSupported = availableVideoSizes().any {
            it.normalizeForOrientation() == startupSizeNormalized
        }
        val strictStartupResolution =
            startupSizeSupported &&
                startupSizeNormalized.width >= 3840 &&
                startupSizeNormalized.height >= 2160
        gpuRecording.strictRequestedResolution = strictStartupResolution
        gpuRecording.strictCameraResolution = strictStartupResolution
        gpuRecording.strictEncoderResolution = strictStartupResolution

        camera.setJpegQuality(enforceJpegQualityFloor())
        camera.setNoiseReductionEnabled(effectiveNoiseReductionEnabled())
        camera.setStabilizationMode(prefs.stabilizationMode)

        _rawOn.value = restoredVideoMode.rawEnabled
        _gridMode.value = gridModeFromPref(prefs.gridMode)
        _showHist.value = prefs.histogramVisible
        _showWfm.value = prefs.waveformVisible
        _showZebra.value = prefs.zebraVisible
        _zebraT.value = prefs.zebraThreshold
        _peaking.value = prefs.focusPeakingEnabled
        _peakingIntensity.value = prefs.focusPeakingIntensity
        val restoredCinemaMode = normalizeNpv10CinemaMode(restoredVideoMode.cinemaMode)
        _logOn.value = restoredCinemaMode == CinemaVideoMode.LOG
        _videoColorSpace.value = normalizedVideoColorSpace
        _videoHdrMode.value = restoredVideoMode.hdrMode
        // Normal processed recording stays active; RAW remains an independent NPRAW sensor path.
        _cinemaVideoMode.value = restoredCinemaMode
        _rawVideoCompression.value = normalizeRawCompressionForMode(
            RawCompressionMode.fromName(prefs.rawVideoCompression),
            restoredCinemaMode
        )
        _rawResolutionMode.value = prefs.rawResolutionMode
        syncRecordingBitrateMode(prefs.videoBitrateMode)
        _videoHdrOn.value = restoredVideoMode.hdrEnabled
        _colorProfile.value = prefs.colorProfile
        _lutEnabled.value = restoredLutEnabled
        _lutProfile.value = restoredLutProfile
        _customLutAvailable.value = hasCustomLutPath
        _aiEnhancement.value = effectiveAiEnhancementEnabled()
        _previewAspect.value = prefs.previewAspectRatio

        colorScience.saturation = prefs.saturation
        colorScience.contrast = prefs.contrast
        colorScience.brightness = prefs.brightness
        rawVideoManager.compressionMode = _rawVideoCompression.value

        videoFeatureState = VideoFeatureState(
            rawEnabled = restoredVideoMode.rawEnabled,
            logEnabled = restoredVideoMode.logEnabled,
            hdrEnabled = restoredVideoMode.hdrEnabled,
            histogramEnabled = _showHist.value,
            waveformEnabled = _showWfm.value,
            zebraEnabled = _showZebra.value,
            peakingEnabled = _peaking.value,
            aspect = _previewAspect.value,
            colorProfile = _colorProfile.value,
            lutEnabled = _lutEnabled.value,
            lutProfile = _lutProfile.value,
            colorSpace = _videoColorSpace.value,
            hdrMode = restoredVideoMode.hdrMode,
            cinemaMode = restoredVideoMode.cinemaMode,
            rawCompression = _rawVideoCompression.value
        )
        applyCinemaModeSelection(restoredVideoMode.cinemaMode, persist = false)
        enforceVideoMeteringDefaultIfNeeded()

        applyColorPipelineState(
            colorProfile = prefs.colorProfile,
            lutEnabled = restoredLutEnabled,
            lutProfile = restoredLutProfile,
            logEnabled = _logOn.value
        )
        syncCameraPreviewLookControls()
        syncMonitoringPipelineState()
        applyAiTrackingPreferences()
        syncGeometryTargets()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }

    private fun completeStartupFastPath() {
        if (!startupFeatureGateActive) return
        startupFeatureGateActive = false
        // The monitor pipeline flags and the CPU-analysis feed are gated by startupFeatureGateActive.
        // Sync them immediately as the gate clears (a monitor may already be enabled from prefs) so
        // the feed attaches without waiting on the deferred applyPreferences ordering below.
        syncMonitoringPipelineState()
        startupAiRampActive = true
        startupDeferredRestoreJob?.cancel()
        scheduleStartupNativePreviewRelease()
        scheduleAiWarmUp(allowDuringStartupAiRamp = true)
        startupDeferredRestoreJob = viewModelScope.launch(Dispatchers.Default) {
            applyPreferences(force = true)
            refreshPreviewPipelineIfNeeded()
            runOnMainIfNeeded { syncGlPreviewCompositorBinding() }
        }
        startupCoordinator.scheduleAiRamp(resolveStartupAiRampDelayMs())
    }

    private fun resolveStartupAiRampDelayMs(): Long {
        val strictHighEndAi = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        return StartupSmoothnessPolicy.aiRampDelayMs(strictHighEnd = strictHighEndAi)
    }

    private fun scheduleStartupNativePreviewRelease() {
        startupCoordinator.scheduleNativePreviewRelease()
    }

    private fun gridModeFromPref(value: String): GridMode =
        CameraPreferenceValuePolicy.gridModeFromPref(value)

    fun connectPreviewView(previewView: CameraVulkanPreviewView) {
        previewViewRef = previewView
        // Re-evaluate the additive GL preview compositor when its gate is flipped at runtime (ADB A/B).
        GlVisiblePreviewPolicy.registerOnChanged {
            runOnMainIfNeeded { syncGlPreviewCompositorBinding() }
        }
        // 10-bit PQ WYSIWYG preview gate (debug.pq_preview) — re-evaluate the same binding on flip.
        Hdr10PqPreviewPolicy.registerOnChanged {
            runOnMainIfNeeded { syncGlPreviewCompositorBinding() }
        }
        // PQ-preview output transfer (debug.pq_preview_sdr_out): SDR-display 8-bit vs 10-bit BT.2020-PQ.
        // A live flip must fully rebuild the EGL surface (the config/colorspace differ), so release the
        // running compositor first, then rebind — the fresh start() reads the flag snapshot.
        com.neoproc.neoproccamx.rendering.Hdr10PqPreviewSdrOutputFlag.registerOnChanged {
            runOnMainIfNeeded {
                releaseHdr10PqPreviewCompositorIfActive()
                syncGlPreviewCompositorBinding()
            }
        }
        // Film-print record-look gate (debug.film_print_look) — re-push the record creative LUT on flip
        // so the on-device A/B takes effect on the next recording without needing a config change.
        com.neoproc.neoproccamx.rendering.FilmPrintLookRecordFlag.registerOnChanged {
            runOnMainIfNeeded { syncGpuLutState() }
        }
        com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.registerOnChanged {
            runOnMainIfNeeded {
                syncGpuLutState()
                pushVulkanPreviewRenderConfig(force = true)
            }
        }
        // Keep-monitor-grade-while-recording gate (debug.preview_grade_recording) — re-evaluate the
        // compositor binding on flip so the on-device A/B engages/reverts the grade during recording.
        com.neoproc.neoproccamx.rendering.PreviewGradeWhileRecordingFlag.registerOnChanged {
            runOnMainIfNeeded { syncGlPreviewCompositorBinding() }
        }
        // Full creative colour engine -> recorded pixels gate (debug.full_color_record) — re-push the
        // record LUT on flip so the on-device A/B takes effect on the next recording.
        com.neoproc.neoproccamx.rendering.FullColorEngineRecordFlag.registerOnChanged {
            runOnMainIfNeeded { syncGpuLutState() }
        }
        // Preview colour parity (WYSIWYG) gate (debug.preview_color_parity) — re-push the preview cube
        // on flip so the on-device A/B engages/reverts the creative-LUT preview stage immediately.
        com.neoproc.neoproccamx.rendering.PreviewColorParityFlag.registerOnChanged {
            runOnMainIfNeeded { syncGpuLutState() }
        }
        com.neoproc.neoproccamx.rendering.RawDebayerPreviewOrientationParityFlag.registerOnChanged {
            runOnMainIfNeeded { applyFrameGeometry(camera.frameGeometry.value) }
        }
        pipeline.connectPreviewView(previewView)
        pipeline.connectAICameraPipeline(aiCameraPipeline)
        previewView.setOnRenderInvalidationListener(null)
        previewView.setNativeCompositorActive(shouldUseNativeVulkanPreview())
        previewView.setSceneRenderProfile(currentSceneRenderProfile ?: buildFallbackGpuSceneRenderProfile(activeVulkanPreviewRenderContract()))
        previewView.setFrameGeometry(camera.frameGeometry.value)
        previewView.getPreviewSurface()?.let(::bindPreviewSurface)
        syncGpuLutState()
        syncGeometryTargets()
        previewView.setEncoderFrameRate(recording.fps)
        previewView.setZebraEnabled(effectiveZebraEnabled())
        previewView.setZebraThreshold(_zebraT.value)
        previewView.setPeakingEnabled(effectivePeakingEnabled())
        previewView.setPeakingIntensity(_peakingIntensity.value)
        syncPreviewViewport(previewView)
        pipeline.setPeakingEnabled(effectivePeakingEnabled())
        pipeline.setPeakingIntensity(_peakingIntensity.value)
        lastAppliedHighLoadPreviewMode = null
        syncPreviewPerformanceMode()
    }


    fun onSurfaceReady(surface: Surface?) {
        Log.d("CameraViewModel", "onSurfaceReady: $surface")
        if (surface == null) {
            handlePreviewSurfaceUnavailable()
            return
        }
        if (recordingPreviewDetachInFlight) {
            pendingRecordingPreviewSurface = surface
            Log.i(
                "CameraViewModel",
                "Deferring replacement preview bind until the recording-only Camera2 session is live"
            )
            return
        }
        val recordingOrStopping = isRecordingOrStopping()
        if (recordingOrStopping) {
            closeCameraAfterRecordingStop = false
        }
        val safeRecordingRebind =
            recordingOrStopping && recordingPreviewSurfaceSafelyDetached
        if (safeRecordingRebind) {
            recordingPreviewRebindInProgress = true
        }
        try {
            if (nativePreviewSurfaceResizePending) {
                nativePreviewSurfaceResizePending = false
                Log.d("CameraViewModel", "Binding visible Vulkan preview after compositor surface resize")
                bindPreviewSurface(
                    surface = surface,
                    refreshSession = false,
                    preferNativePreviewOverride = true
                )
                return
            }
            if (previewSurface === surface) {
                syncGeometryTargets()
                return
            }
            bindPreviewSurface(surface)
        } finally {
            if (safeRecordingRebind) {
                recordingPreviewRebindInProgress = false
                recordingPreviewSurfaceSafelyDetached = false
            }
        }
    }

    /**
     * Alternative to onSurfaceReady that just stores the display surface without auto-opening.
     */
    fun setPreviewSurface(surface: Surface?) {
        if (surface == null) {
            clearPreviewSurface()
            return
        }
        if (previewSurface === surface) return
        Log.d("CameraViewModel", "setPreviewSurface: $surface")
        bindPreviewSurface(surface)
    }

    // -------------------------------------------------------------------------
    // CAMERA OPEN / CLOSE
    // -------------------------------------------------------------------------

    fun openCamera() {
        if (conversionIsolation.isActive) return
        val surface = previewSurface
        val closeJob = cameraCloseJob
        val openDecision = CameraForegroundOpenPolicy.resolve(
            hasPreviewSurface = surface != null,
            cameraState = cameraState.value,
            closeInFlight = closeJob?.isActive == true
        )
        if (openDecision.shouldWaitForClose && closeJob != null) {
            Log.d("CameraViewModel", "openCamera delayed until background close finishes")
            viewModelScope.launch(Dispatchers.Default) {
                closeJob.join()
                runOnMain { openCamera() }
            }
            return
        }
        if (!openDecision.shouldOpenNow) {
            if (surface == null) {
                Log.w("CameraViewModel", "openCamera skipped: previewSurface is NULL")
            }
            return
        }
        closeCameraAfterRecordingStop = false
        if (cameraOpenRequestInFlight) {
            Log.d("CameraViewModel", "openCamera ignored: request already in flight")
            return
        }
        lastAppliedAiTrackingState = null
        lastAppliedHighLoadPreviewMode = null
        startStartupBootstrap()
        if (surface == null) {
            Log.w("CameraViewModel", "openCamera skipped: previewSurface is NULL")
            return
        }
        startupCoordinator.cancelSessionJobs()
        startupDeferredRestoreJob?.cancel()
        startupDirectPreviewActive = true
        startupFeatureGateActive = true
        startupAiRampActive = true
        scheduleStartupOpenAwait()
        bindPreviewSurface(surface)

        syncGeometryTargets()
        camera.startBackground()
        pipeline.start()
        primeNpv10CaptureGraphForOpen()
        cameraOpenRequestInFlight = true
        
        com.neoproc.neoproccamx.utils.BootTrace.mark("camera.open.requested")
        viewModelScope.launch(Dispatchers.Default) {
            camera.openCamera(
                onReady  = {
                    com.neoproc.neoproccamx.utils.BootTrace.mark("camera.session.ready")
                    cameraOpenRequestInFlight = false
                    scheduleCenterAutoPrime()
                    if (VideoColdOpenHardwareImportSessionFlag.enabled) {
                        Log.i(
                            "CameraViewModel",
                            "Cold-open hardware-import-in-session prearm flag ON (scaffold); using the " +
                                "shipped forced prearm rebuild until the in-session path is device-verified"
                        )
                    }
                    requestRecordingSurfacePrearm(forceSessionRefresh = true)
                    scheduleRecordingSurfacePrearmRetune()
                    // Capabilities are populated now (camera open / switch / lens switch / app start);
                    // recompute the RAW-tier indicator without touching the capture session.
                    refreshRawCaptureTierStatus()
                },
                onError  = {
                    cameraOpenRequestInFlight = false
                    /* cameraState.Error is already set */
                }
            )
        }
    }

    fun resumeCameraForForeground() {
        val surface = previewSurface
        val closeJob = cameraCloseJob
        val recordingOrStopping =
            gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused ||
                gpuRecordState.value is GpuRecordingState.Stopping
        if (recordingOrStopping && surface != null) {
            closeCameraAfterRecordingStop = false
        }
        val decision = CameraForegroundResumePolicy.resolve(
            hasPreviewSurface = surface != null,
            cameraState = cameraState.value,
            closeInFlight = closeJob?.isActive == true,
            recordingOrStopping = recordingOrStopping
        )
        when {
            decision.shouldWaitForClose && closeJob != null -> {
                Log.i("CameraViewModel", "foreground resume waiting for camera close")
                viewModelScope.launch(Dispatchers.Default) {
                    closeJob.join()
                    runOnMain { openCamera() }
                }
            }
            decision.shouldRecycleActiveSession -> {
                Log.i("CameraViewModel", "foreground resume recycling active camera session")
                closeCamera()
                val recycleCloseJob = cameraCloseJob
                viewModelScope.launch(Dispatchers.Default) {
                    recycleCloseJob?.join()
                    runOnMain { openCamera() }
                }
            }
            decision.shouldOpenNow -> openCamera()
            surface == null -> Log.w("CameraViewModel", "foreground resume skipped: previewSurface is NULL")
            else -> Unit
        }
    }

    private fun prepareCameraCloseState() {
        closeCameraAfterRecordingStop = false
        cameraOpenRequestInFlight = false
        aiWarmupJob?.cancel()
        aiFocusResumeJob?.cancel()
        autoFocusPrimeJob?.cancel()
        startupCoordinator.cancelSessionJobs()
        startupDeferredRestoreJob?.cancel()
        lastAppliedAiTrackingState = null
        lastAppliedHighLoadPreviewMode = null
        tapToFocusHoldUntilElapsedMs = 0L
        startupDirectPreviewActive = true
        startupFeatureGateActive = true
        startupAiRampActive = true
        stopNativePreviewWatchdog()
        clearNativePreviewHandoffState()
        resetNativePreviewFallbackState()
        resetPreviewPerformanceState()
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        applyRecordingOutputAspectOverride(null)
        previewViewRef?.setNativeCompositorActive(false)
        vulkanRuntime.setFrameImportEnabled(false)
        vulkanRuntime.setPreviewSurface(null)
        if (glPreviewCompositorActive) {
            previewGlCompositor.release()
            glPreviewCompositorActive = false
        }
        releaseHdr10PqPreviewCompositorIfActive()
        glPreviewEngageRetries = 0
        glPreviewEngageRetryScheduled = false
        pipeline.stop()
    }

    fun closeCamera() {
        if (cameraCloseJob?.isActive == true || cameraState.value == CameraState.Closed) return
        Log.i("CameraViewModel", "closeCamera requested owner=${System.identityHashCode(this).toString(16)} state=${cameraState.value}")
        prepareCameraCloseState()
        cameraCloseJob = viewModelScope.launch(Dispatchers.Default) {
            try { camera.closeCamera() } catch (error: Exception) { Log.e("CameraViewModel", "Camera close failed", error) } finally {
                camera.stopBackground()
                cameraCloseJob = null
            }
        }
        // Keep the display surface reference so Vulkan can reuse it after the next openCamera().
    }
    fun handleAppBackgrounded() {
        val decision = CameraBackgroundClosePolicy.resolve(
            isRecordingActive = gpuRecordState.value is GpuRecordingState.Starting || gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused,
            isRecordingStopping = gpuRecordState.value is GpuRecordingState.Stopping
        )
        closeCameraAfterRecordingStop = decision.deferCameraCloseUntilRecordingStops
        if (decision.requestRecordingStop) {
            stopRecording()
        }
        if (decision.closeCameraImmediately) {
            closeCamera()
        }
    }

    private fun isRecordingOrStopping(): Boolean =
        gpuRecordState.value is GpuRecordingState.Starting || gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping ||
            cameraState.value == CameraState.Recording

    private fun handlePreviewSurfaceUnavailable() {
        if (isRecordingOrStopping()) {
            clearPreviewSurfaceForActiveRecording()
            return
        }
        clearPreviewSurface()
        if (cameraCloseJob?.isActive != true &&
            cameraState.value != CameraState.Closing &&
            cameraState.value != CameraState.Closed
        ) {
            closeCamera()
        }
    }

    private fun idleRecordingSurfacePrearmSkipReason(): String? {
        if (_uiMode.value != AppMode.VIDEO) return "not-video-mode"
        if (recordingStartInProgress.get()) return "recording-starting"
        if (cameraState.value == CameraState.Recording) return "camera-recording"
        if (_cinemaVideoMode.value == CinemaVideoMode.RAW) {
            return "custom-npraw-packet-stream-does-not-use-mediacodec"
        }
        if (_uncompressedCinemaPipeline.value) {
            return "custom-npv10-packet-stream-does-not-use-mediacodec"
        }
        if (
            gpuRecordState.value !is GpuRecordingState.Idle &&
            !(gpuRecordState.value is GpuRecordingState.Ready && prearmedRecordingSurface != null)
        ) {
            return "encoder-state-${gpuRecordState.value::class.simpleName}"
        }
        val parityPlan = activeRecordingPipelineParityPlan()
        if (parityPlan.feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER) {
            return "hardware-import-uses-codec-owned-surface"
        }
        return when {
            parityPlan.encoderRawEnabled -> "raw-recording-prepares-on-record-start"
            parityPlan.encoderLogEnabled -> "log-recording-prepares-on-record-start"
            parityPlan.encoderHdrMode != "OFF" -> "hdr-recording-prepares-on-record-start"
            else -> null
        }
    }

    private fun shouldKeepRecordingSurfacePrearmed(): Boolean =
        idleRecordingSurfacePrearmSkipReason() == null
    private fun requestRecordingSurfacePrearm(forceSessionRefresh: Boolean = false) {
        if (shouldUseUncompressedCinemaPipeline()) {
            requestNpv10CapturePrearm(forceSessionRefresh)
            releasePrearmedRecordingSurface(refreshSession = false)
            return
        }
        npv10CapturePrearmedSignature = null
        npv10Recording.discardPreparedPhysicalReservation()
        camera.setNpv10TenBitCaptureEnabled(false)
        if (forceSessionRefresh) {
            recordingSurfacePrearmForcedRefreshRequested.set(true)
        }
        val prearmSkipReason = idleRecordingSurfacePrearmSkipReason()
        if (prearmSkipReason != null) {
            Log.i(
                "CameraViewModel",
                "Idle recording surface prearm skipped: $prearmSkipReason; encoder prepares on record start"
            )
            releasePrearmedRecordingSurface(refreshSession = forceSessionRefresh)
            return
        }
        if (prearmedRecordingSurface != null &&
            (prearmedRecordingSurfaceAttachedToSession || !forceSessionRefresh)
        ) {
            val parityPlan = activeRecordingPipelineParityPlan()
            val expectedSignature = currentRecordingPrearmSignature(parityPlan)
            if (isCurrentPrearmReusableForPlan(parityPlan, expectedSignature)) {
                return
            }
            Log.i(
                "CameraViewModel",
                "Pre-armed recording surface no longer matches selected encoder; refreshing " +
                    "attached=$prearmedRecordingSurfaceAttachedToSession " +
                    "feed=$prearmedRecordingFeedPath expectedFeed=${parityPlan.feedPath} " +
                    "prepared=$prearmedRecordingSignature expected=$expectedSignature"
            )
        }
        if (!recordingSurfacePrearmInFlight.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val refreshSession =
                    forceSessionRefresh || recordingSurfacePrearmForcedRefreshRequested.getAndSet(false)
                prearmRecordingSurfaceNow(refreshSession = refreshSession)
            } finally {
                recordingSurfacePrearmInFlight.set(false)
                if (recordingSurfacePrearmForcedRefreshRequested.get() &&
                    shouldKeepRecordingSurfacePrearmed() &&
                    !isRecordingSurfacePrearmedForDebugStart()
                ) {
                    requestRecordingSurfacePrearm(forceSessionRefresh = true)
                }
            }
        }
    }
    private fun requestNpv10CapturePrearm(forceSessionRefresh: Boolean) {
        if (recordingStartInProgress.get() || recordingStopInProgress.get() ||
            cameraState.value != CameraState.Streaming
        ) return
        val requestedFps = recording.fps.coerceAtLeast(1)
        camera.setNpv10TenBitCaptureEnabled(true)
        camera.setRawVideoCaptureEnabled(false)
        val plan = camera.resolveNpv10CaptureSourcePlan(preferredRecordingSize())
        if (!plan.nativeTenBitCapture) return
        npv10Recording.preparePhysicalReservationAsync()
        val signature = npv10CapturePrearmSignature(plan.size, requestedFps)
        camera.setNpv10CaptureTargetSize(plan.size)
        camera.setFPS(requestedFps)
        val sessionConfigured = camera.isNpv10TenBitSessionConfigured(plan.size)
        if (sessionConfigured) npv10CapturePrearmedSignature = signature
        val inFlightSignature = npv10CaptureRefreshSignature
            .takeIf { camera.sessionRefreshPending.get() }
        if (!Npv10CapturePrearmPolicy.shouldRefresh(
                requestedSignature = signature,
                preparedSignature = npv10CapturePrearmedSignature,
                inFlightSignature = inFlightSignature,
                sessionConfigured = sessionConfigured,
                forceRefresh = forceSessionRefresh
            )
        ) return
        npv10CaptureRefreshSignature = signature
        camera.refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true) {
            if (camera.isNpv10TenBitSessionConfigured(plan.size)) {
                npv10CapturePrearmedSignature = signature
                Log.i("CameraViewModel", "Pre-armed native P010 packet capture session $signature")
            }
            npv10CaptureRefreshSignature = null
        }
    }

    private fun primeNpv10CaptureGraphForOpen() {
        if (!shouldUseUncompressedCinemaPipeline()) return
        val requestedFps = recording.fps.coerceAtLeast(1)
        camera.setNpv10TenBitCaptureEnabled(true)
        camera.setRawVideoCaptureEnabled(false)
        camera.setFPS(requestedFps)
        camera.setNpv10CaptureTargetSize(preferredRecordingSize())
        if (camera.characteristics == null) return
        runCatching { camera.resolveNpv10CaptureSourcePlan(preferredRecordingSize()) }
            .getOrNull()
            ?.takeIf { it.nativeTenBitCapture }
            ?.let { plan ->
                camera.setNpv10CaptureTargetSize(plan.size)
                npv10CapturePrearmedSignature = npv10CapturePrearmSignature(plan.size, requestedFps)
            }
    }
    private fun npv10CapturePrearmSignature(size: Size, fps: Int): String =
        "${size.width}x${size.height}@$fps:P010_10"
    fun prearmRecordingSurfaceForSelectedVideoSettings(forceSessionRefresh: Boolean = true) {
        if (_uiMode.value != AppMode.VIDEO) return
        requestRecordingSurfacePrearm(forceSessionRefresh = forceSessionRefresh)
    }

    fun isRecordingSurfacePrearmedForDebugStart(): Boolean {
        if (_uiMode.value != AppMode.VIDEO) return false
        idleRecordingSurfacePrearmSkipReason()?.let { return true }
        val parityPlan = activeRecordingPipelineParityPlan()
        val expectedSignature = currentRecordingPrearmSignature(parityPlan)
        val reusablePrearm = isCurrentPrearmReusableForPlan(parityPlan, expectedSignature)
        val ready = when (parityPlan.feedPath) {
            RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE,
            RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE ->
                reusablePrearm && prearmedRecordingSurfaceAttachedToSession
            RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER -> reusablePrearm
            RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER -> true
        }
        if (!ready) {
            logRecordingPrearmReadinessMiss(parityPlan, expectedSignature, reusablePrearm)
        } else if (
            parityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER &&
            camera.sessionRefreshPending.get()
        ) {
            logRecordingPrearmReadinessMiss(parityPlan, expectedSignature, reusablePrearm)
            return false
        }
        return ready
    }

    private fun isCurrentPrearmReusableForPlan(
        parityPlan: RecordingPipelineParityPlan,
        expectedSignature: RecordingPrearmSignature = currentRecordingPrearmSignature(parityPlan)
    ): Boolean =
        prearmedRecordingFeedPath == parityPlan.feedPath &&
            RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                recordingStateReady = gpuRecordState.value is GpuRecordingState.Ready,
                surfaceAvailable = prearmedRecordingSurface != null,
                preparedSignature = prearmedRecordingSignature,
                expectedSignature = expectedSignature
            )

    private fun logRecordingPrearmReadinessMiss(
        parityPlan: RecordingPipelineParityPlan,
        expectedSignature: RecordingPrearmSignature,
        reusablePrearm: Boolean
    ) {
        val logSignature = listOf(
            parityPlan.feedPath.name,
            gpuRecordState.value::class.simpleName.orEmpty(),
            (prearmedRecordingSurface != null).toString(),
            prearmedRecordingSurfaceAttachedToSession.toString(),
            prearmedRecordingFeedPath?.name.orEmpty(),
            reusablePrearm.toString(),
            prearmedRecordingSignature.toString(),
            expectedSignature.toString()
        ).joinToString(separator = "|")
        if (logSignature == lastRecordingPrearmReadinessLogSignature) return
        lastRecordingPrearmReadinessLogSignature = logSignature
        Log.i(
            "CameraViewModel",
            "Recording prearm not ready for debug start: feed=${parityPlan.feedPath} " +
                "gpuState=${gpuRecordState.value::class.simpleName} " +
                "surface=${prearmedRecordingSurface != null} " +
                "attached=$prearmedRecordingSurfaceAttachedToSession " +
                "preparedFeed=$prearmedRecordingFeedPath reusable=$reusablePrearm " +
                "prepared=$prearmedRecordingSignature expected=$expectedSignature"
        )
    }

    fun isRecordingReadyForDebugClipTiming(): Boolean {
        val npv10Active =
            npv10Recording.state.value is GpuRecordingState.Recording ||
                npv10Recording.state.value is GpuRecordingState.Paused
        if (npv10Active) {
            return npv10Recording.writtenFrameCount() > 0L
        }
        val gpuActive =
            gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused
        if (!gpuActive) return false
        return when (activeRecordingFeedPath) {
            RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
            RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER -> recordingReadyForClipTiming
            RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
            RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE -> recordingReadyForClipTiming ||
                cameraState.value == CameraState.Recording
            null -> recordingReadyForClipTiming
        }
    }

    private fun markGpuRecordingReadyForClipTiming() {
        recordingReadyForClipTiming = true
        gpuRecording.markClipTimingStartForEvidence()
    }

    fun markDebugClipTimingStartForEvidence() {
        if (!isRecordingReadyForDebugClipTiming()) return
        gpuRecording.markClipTimingStartForEvidence()
    }

    private fun prepareGpuRecordingForPrearmedVideoSession(): Boolean {
        if (_uiMode.value != AppMode.VIDEO) return false
        val app = getApplication<Application>()
        gpuRecording.audioEnabled =
            recording.audioSource != AudioSrc.NONE &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val initialGraphContract = activeVideoPipelineContract()
        val initialParityPlan = activeRecordingPipelineParityPlan(initialGraphContract)
        val byteBufferP010RoutePlanned =
            initialParityPlan.feedPath == RecordingFeedPath.BYTEBUFFER_P010_ENCODER
        val offscreenAhbEglRoutePlanned =
            initialParityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
        val highPrecisionRoutePlanned =
            byteBufferP010RoutePlanned ||
                offscreenAhbEglRoutePlanned
        val sessionPlan = RecordingCapabilityPolicy.resolveSessionPlan(
            requestedCodec = recording.codec,
            av1Supported = supportsAv1Fast(),
            hdrRequested = initialParityPlan.hdrRequestedForSessionPlan,
            rawRequested = initialParityPlan.rawRequestedForSessionPlan,
            rawSupported = camera.supportsRaw,
            logEnabled = initialParityPlan.logEnabledForSessionPlan,
            tenBitRequested = initialParityPlan.tenBitRequestedForSessionPlan
        )
        gpuRecording.videoCodec = if (highPrecisionRoutePlanned) {
            GpuVideoCodec.HEVC
        } else {
            sessionPlan.codec
        }
        val recordingSelection = resolveRecordingSelection(
            preferredRecordingSize(),
            gpuRecording.videoCodec
        )
        val resolvedBitrate = resolveConfiguredBitrate(
            selection = recordingSelection,
            fps = recording.fps,
            codec = recordingSelection.codec
        )
        applyResolvedRecordingConfig(
            resolvedSelection = recordingSelection,
            fps = recording.fps,
            bitrate = resolvedBitrate,
            audioSource = recording.audioSource
        )
        val recordingSourcePlan = resolveRecordingSourcePlan(recordingSelection.requestedSize)
        val resolvedReadoutOverride = resolveRecordingReadoutOverrideSize(
            selection = recordingSelection,
            sourcePlan = recordingSourcePlan
        )
        val preparedEncoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
        if (offscreenAhbEglRoutePlanned) {
            val sustainableGpuFps =
                ProcessedGpuRecordFpsPolicy.resolve(recording.fps, preparedEncoderSize)
            if (sustainableGpuFps != recording.fps) {
                val previousFps = recording.fps
                recording.fps = sustainableGpuFps
                gpuRecording.videoFps = sustainableGpuFps
                prefs.videoFps = sustainableGpuFps
                camera.setFPS(sustainableGpuFps)
                Log.i(
                    "CameraViewModel",
                    "Processed GPU sustainable-fps cap: fps $previousFps -> " +
                        "$sustainableGpuFps for ${preparedEncoderSize.width}x${preparedEncoderSize.height} " +
                        "reason=prearm"
                )
            }
            vulkanRuntime.setOffscreenEncoderTargetFrameRate(recording.fps)
        }
        val prearmSessionGraphPlan = activeCamera2SessionGraphParityPlan(
            graphContract = initialGraphContract,
            parityPlan = initialParityPlan,
            encoderSize = preparedEncoderSize,
            proposedAnalysisSize = preferredRecordingAnalysisSize(preparedEncoderSize),
            proposedReadoutOverrideSize = resolvedReadoutOverride,
            encoderSurfaceAvailable = false,
            recordSurfaceAttachedToSession = prearmedRecordingSurfaceAttachedToSession,
            stage = Camera2SessionGraphStage.PREARMING
        )
        camera.setRecordingReadoutOverrideSize(prearmSessionGraphPlan.readoutOverrideSize)
        camera.setRecordingEncoderTargetSize(prearmSessionGraphPlan.encoderSize)
        camera.setAnalysisTargetSize(prearmSessionGraphPlan.analysisTargetSize)
        logCamera2SessionGraphParityPlan(prearmSessionGraphPlan, reason = "prearm-config")
        gpuRecording.orientationHintDegrees = camera.videoOrientationHint()
        gpuRecording.tenBitEnabled = when {
            highPrecisionRoutePlanned -> true
            FORCE_UNIVERSAL_TEN_BIT_PROCESSED &&
                !initialParityPlan.rawRequestedForSessionPlan &&
                gpuRecording.supports10Bit() -> true
            else -> sessionPlan.tenBitPipelineEnabled
        }
        syncEffectiveRecordingColorProfile()
        val preparedParityPlan = activeRecordingPipelineParityPlan()
        gpuRecording.cinemaMode = effectiveRecordingCinemaMode(preparedParityPlan)
        gpuRecording.rawVideoEnabled = preparedParityPlan.encoderRawEnabled
        gpuRecording.videoLogEnabled = preparedParityPlan.encoderLogEnabled
        gpuRecording.videoHdrMode = preparedParityPlan.encoderHdrMode
        gpuRecording.videoColorSpace = preparedParityPlan.encoderColorSpace
        logRecordingPipelineParityPlan(preparedParityPlan, reason = "prearm")
        return true
    }

    private fun currentRecordingPrearmSignature(
        parityPlan: RecordingPipelineParityPlan = activeRecordingPipelineParityPlan()
    ): RecordingPrearmSignature =
        RecordingPrearmSignature(
            width = gpuRecording.videoWidth,
            height = gpuRecording.videoHeight,
            fps = gpuRecording.videoFps,
            bitrate = gpuRecording.videoBitrate,
            codec = gpuRecording.videoCodec,
            audioEnabled = gpuRecording.audioEnabled,
            tenBitEnabled = gpuRecording.tenBitEnabled,
            rawVideoEnabled = parityPlan.encoderRawEnabled,
            logEnabled = parityPlan.encoderLogEnabled,
            hdrMode = parityPlan.encoderHdrMode,
            colorSpace = parityPlan.encoderColorSpace,
            cinemaMode = effectiveRecordingCinemaMode(parityPlan)
        )

    private fun prebindInternalHardwareImportEncoderSurface(
        surface: Surface,
        parityPlan: RecordingPipelineParityPlan
    ): Boolean {
        if (parityPlan.feedPath != RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ||
            !vulkanSupported.value
        ) {
            return false
        }
        val bound = vulkanRuntime.setEncoderSurface(surface)
        if (bound) {
            Log.i(
                "CameraViewModel",
                "Pre-armed persistent encoder surface bound to Vulkan hardware-import target"
            )
        } else {
            Log.w(
                "CameraViewModel",
                "Pre-armed Vulkan hardware-import encoder surface bind deferred until recording start"
            )
        }
        return bound
    }

    private fun prebindOffscreenAhbEglEncoderSurface(
        surface: Surface,
        parityPlan: RecordingPipelineParityPlan
    ): Boolean {
        if (parityPlan.feedPath != RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER ||
            !vulkanSupported.value
        ) {
            return false
        }
        val encoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
        val deliveryMode = RecordingHybridRoutePolicy.resolveOffscreenDeliveryMode(
            deliveryKind = parityPlan.deliveryKind,
            effectiveHdrMode = parityPlan.encoderHdrMode
        )
        val bound = vulkanRuntime.setOffscreenAhbEglEncoderSurface(
            surface = surface,
            width = encoderSize.width,
            height = encoderSize.height,
            preferredDeliveryMode = deliveryMode,
            outputMirrorPlan = camera.recordOutputMirrorPlan("prearm-offscreen"),
            inputDynamicRangeProfile = camera.describeHdrDynamicRangeState().previewProfile
        )
        if (bound) {
            Log.i(
                "CameraViewModel",
                "Pre-armed offscreen AHB EGL encoder surface bound " +
                    "${encoderSize.width}x${encoderSize.height} delivery=${deliveryMode.name}"
            )
        } else {
            Log.w(
                "CameraViewModel",
                "Pre-armed offscreen AHB EGL encoder surface bind failed; recording start will retry"
            )
        }
        return bound
    }

    private fun prearmOffscreenHardwareImportSource(
        encoderSize: Size,
        refreshSession: Boolean
    ): Boolean {
        val p010SourceSize = encoderSize.normalizeForOrientation()
        if (!camera.supportsHardwareImportP010Source(p010SourceSize, recording.fps)) {
            Log.w(
                "CameraViewModel",
                "Pre-armed offscreen AHB EGL source skipped: P010 hardware import unavailable " +
                    "${encoderSize.width}x${encoderSize.height}@${recording.fps}"
            )
            return false
        }
        camera.setPreviewContinuityRecordSurfaceEnabled(false)
        camera.setRecordSurface(null, refreshSession = false)
        camera.setRecordingEncoderTargetSize(encoderSize)
        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        camera.setHardwareImportTargetSize(encoderSize)
        camera.setInternalRecordingHardwareImportEnabled(true)
        camera.setHardwareImportP010SourceEnabled(true)
        pipeline.setDedicatedHardwareImportEnabled(true)
        vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
        vulkanRuntime.setFrameImportEnabled(true)
        if (!refreshSession) {
            Log.i(
                "CameraViewModel",
                "Pre-armed offscreen AHB EGL source prepared; Camera2 source attachment pending session refresh"
            )
            scheduleRecordingSurfacePrearmRetry()
            return false
        }
        val refreshed = CountDownLatch(1)
        camera.refreshOutputsAndSession(
            rebuildReaders = true,
            forceRebuild = true
        ) {
            refreshed.countDown()
        }
        val attached = refreshed.await(5, TimeUnit.SECONDS)
        if (attached) {
            Log.i(
                "CameraViewModel",
                "Pre-armed offscreen AHB EGL P010 hardware-import source attached to preview session"
            )
            runOnMainIfNeeded {
                syncGlPreviewCompositorBinding()
                scheduleGlPreviewCompositorBindingResync("offscreen-p010-prearm")
            }
        } else {
            Log.w(
                "CameraViewModel",
                "Pre-armed offscreen AHB EGL P010 source attachment was not confirmed"
            )
            scheduleRecordingSurfacePrearmRetry()
        }
        return attached
    }

    private fun prearmRecordingSurfaceNow(refreshSession: Boolean): Boolean {
        if (!shouldKeepRecordingSurfacePrearmed()) return false
        if (prearmedRecordingSurface != null && gpuRecordState.value is GpuRecordingState.Ready) {
            val expectedSignature = currentRecordingPrearmSignature()
            if (prearmedRecordingSignature != expectedSignature) {
                Log.i(
                    "CameraViewModel",
                    "Pre-armed recording surface stale; rebuilding prepared encoder"
                )
                vulkanRuntime.setEncoderSurface(null)
                vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
                prearmedRecordingSurface = null
                prearmedRecordingSurfaceAttachedToSession = false
                prearmedRecordingSignature = null
                prearmedRecordingFeedPath = null
                gpuRecording.discardPreparedSession()
                gpuRecording.releasePersistentInputSurface()
            } else {
                val parityPlan = activeRecordingPipelineParityPlan()
                if (parityPlan.feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER) {
                    val surface = prearmedRecordingSurface ?: return true
                    prebindInternalHardwareImportEncoderSurface(surface, parityPlan)
                    prearmedRecordingSurfaceAttachedToSession = false
                    Log.i(
                        "CameraViewModel",
                        "Pre-armed persistent encoder surface kept private for internal hardware-import path"
                    )
                    return true
                }
                if (parityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
                    val surface = prearmedRecordingSurface ?: return true
                    if (prebindOffscreenAhbEglEncoderSurface(surface, parityPlan)) {
                        val encoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
                        val sourceAttached =
                            prearmedRecordingSurfaceAttachedToSession ||
                                prearmOffscreenHardwareImportSource(
                                    encoderSize = encoderSize,
                                    refreshSession = refreshSession
                                )
                        prearmedRecordingSurfaceAttachedToSession = sourceAttached
                        Log.i(
                            "CameraViewModel",
                            if (sourceAttached) {
                                "Pre-armed offscreen AHB EGL encoder surface kept private; " +
                                    "P010 hardware-import source already attached to preview session"
                            } else {
                                "Pre-armed offscreen AHB EGL encoder surface kept private; " +
                                    "P010 hardware-import source attachment pending retry"
                            }
                        )
                        return true
                    }
                    releasePrearmedRecordingSurface(refreshSession = false)
                    return false
                }
                if (!prearmedRecordingSurfaceAttachedToSession && refreshSession) {
                    val surface = prearmedRecordingSurface ?: return true
                    val encoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
                    val prearmAttachPlan = activeCamera2SessionGraphParityPlan(
                        encoderSize = encoderSize,
                        proposedAnalysisSize = preferredRecordingAnalysisSize(encoderSize),
                        proposedReadoutOverrideSize = null,
                        encoderSurfaceAvailable = true,
                        recordSurfaceAttachedToSession = false,
                        stage = Camera2SessionGraphStage.PREARMING
                    )
                    camera.setRecordingReadoutOverrideSize(prearmAttachPlan.readoutOverrideSize)
                    camera.setRecordingEncoderTargetSize(prearmAttachPlan.encoderSize)
                    camera.setAnalysisTargetSize(prearmAttachPlan.analysisTargetSize)
                    logCamera2SessionGraphParityPlan(prearmAttachPlan, reason = "prearm-attach", force = true)
                    camera.setRecordSurface(surface, refreshSession = false)
                    val refreshed = CountDownLatch(1)
                    camera.refreshOutputsAndSession(
                        rebuildReaders = prearmAttachPlan.refreshAction ==
                            Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS,
                        forceRebuild = prearmAttachPlan.refreshAction != Camera2SessionRefreshAction.NONE
                    ) {
                        refreshed.countDown()
                    }
                    prearmedRecordingSurfaceAttachedToSession =
                        RecordingPersistentInputSurfacePolicy.isCameraSessionAttachmentConfirmed(
                            sessionRefreshRequested = true,
                            sessionRefreshCompleted = refreshed.await(5, TimeUnit.SECONDS)
                        )
                    if (prearmedRecordingSurfaceAttachedToSession) {
                        Log.i("CameraViewModel", "Pre-armed persistent encoder surface attached to preview session")
                    } else {
                        Log.w(
                            "CameraViewModel",
                            "Pre-armed persistent encoder surface attachment retry was not confirmed"
                        )
                        scheduleRecordingSurfacePrearmRetry()
                    }
                }
                return true
            }
        }
        if (gpuRecordState.value is GpuRecordingState.Ready) {
            gpuRecording.discardPreparedSession()
        }
        if (!prepareGpuRecordingForPrearmedVideoSession()) return false
        val prearmParityPlan = activeRecordingPipelineParityPlan()
        if (prearmParityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
            gpuRecording.setVideoInputMode(RecordingVideoInputMode.SURFACE)
            gpuRecording.setRealtimeSurfaceCadenceEnabled(false)
            gpuRecording.setPersistentInputSurfaceAllowed(false)
            val surface = gpuRecording.prepare() ?: run {
                prearmedRecordingSignature = null
                prearmedRecordingFeedPath = null
                return false
            }
            if (recordingStartInProgress.get()) {
                vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
                gpuRecording.discardPreparedSession()
                prearmedRecordingSignature = null
                prearmedRecordingFeedPath = null
                Log.i(
                    "CameraViewModel",
                    "Pre-armed offscreen encoder surface discarded because recording start owns encoder configuration"
                )
                return false
            }
            if (!prebindOffscreenAhbEglEncoderSurface(surface, prearmParityPlan)) {
                vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
                gpuRecording.discardPreparedSession()
                prearmedRecordingSignature = null
                prearmedRecordingFeedPath = null
                return false
            }
            prearmedRecordingSurface = surface
            prearmedRecordingFeedPath = prearmParityPlan.feedPath
            val prearmedEncoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
            if (!camera.supportsHardwareImportP010Source(
                    prearmedEncoderSize.normalizeForOrientation(),
                    recording.fps
                )
            ) {
                Log.w(
                    "CameraViewModel",
                    "Pre-armed offscreen AHB EGL encoder prepared, but P010 source is unavailable " +
                        "${prearmedEncoderSize.width}x${prearmedEncoderSize.height}@${recording.fps}"
                )
            }
            prearmedRecordingSurfaceAttachedToSession = false
            prearmedRecordingSignature = currentRecordingPrearmSignature(prearmParityPlan)
            logRecordingPipelineParityPlan(prearmParityPlan, reason = "prearm-offscreen-signature")
            vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
            prearmedRecordingSurfaceAttachedToSession =
                prearmOffscreenHardwareImportSource(
                    encoderSize = prearmedEncoderSize,
                    refreshSession = refreshSession
                )
            Log.i(
                "CameraViewModel",
                if (prearmedRecordingSurfaceAttachedToSession) {
                    "Pre-armed offscreen AHB EGL encoder surface prepared; " +
                        "P010 hardware-import source attached before record start"
                } else {
                    "Pre-armed offscreen AHB EGL encoder surface prepared; " +
                        "P010 hardware-import source attachment pending retry"
                }
            )
            return true
        }
        gpuRecording.setPersistentInputSurfaceAllowed(true)
        val persistentSurface = gpuRecording.preparePersistentInputSurface() ?: return false
        val surface = gpuRecording.prepare() ?: run {
            prearmedRecordingSignature = null
            prearmedRecordingFeedPath = null
            return false
        }
        if (recordingStartInProgress.get()) {
            vulkanRuntime.setEncoderSurface(null)
            gpuRecording.discardPreparedSession()
            gpuRecording.releasePersistentInputSurface()
            prearmedRecordingSignature = null
            prearmedRecordingFeedPath = null
            Log.i(
                "CameraViewModel",
                "Pre-armed recording surface discarded because recording start owns encoder configuration"
            )
            return false
        }
        if (surface !== persistentSurface) {
            Log.w(
                "CameraViewModel",
                "Pre-armed recording disabled: encoder did not accept the persistent input surface"
            )
            gpuRecording.discardPreparedSession()
            prearmedRecordingSignature = null
            prearmedRecordingFeedPath = null
            return false
        }
        prearmedRecordingSurface = surface
        val parityPlan = activeRecordingPipelineParityPlan()
        prearmedRecordingFeedPath = parityPlan.feedPath
        prearmedRecordingSignature = currentRecordingPrearmSignature(parityPlan)
        logRecordingPipelineParityPlan(parityPlan, reason = "prearm-signature")
        prebindInternalHardwareImportEncoderSurface(surface, parityPlan)
        val attachToPreviewSession =
            parityPlan.feedPath != RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER &&
                RecordingPersistentInputSurfacePolicy.shouldAttachPrearmedSurfaceToPreviewSession(
                    codecConfigured = gpuRecordState.value is GpuRecordingState.Ready,
                    previewSessionValidated = false
                )
        if (attachToPreviewSession) {
            val encoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
            val prearmAttachPlan = activeCamera2SessionGraphParityPlan(
                encoderSize = encoderSize,
                proposedAnalysisSize = preferredRecordingAnalysisSize(encoderSize),
                proposedReadoutOverrideSize = null,
                encoderSurfaceAvailable = true,
                recordSurfaceAttachedToSession = false,
                stage = Camera2SessionGraphStage.PREARMING
            )
            camera.setRecordingReadoutOverrideSize(prearmAttachPlan.readoutOverrideSize)
            camera.setRecordingEncoderTargetSize(prearmAttachPlan.encoderSize)
            camera.setAnalysisTargetSize(prearmAttachPlan.analysisTargetSize)
            logCamera2SessionGraphParityPlan(prearmAttachPlan, reason = "prearm-attach", force = true)
            val attachedToSession = if (refreshSession) {
                camera.setRecordSurface(surface, refreshSession = false)
                val refreshed = CountDownLatch(1)
                camera.refreshOutputsAndSession(
                    rebuildReaders = prearmAttachPlan.refreshAction ==
                        Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS,
                    forceRebuild = prearmAttachPlan.refreshAction != Camera2SessionRefreshAction.NONE
                ) {
                    refreshed.countDown()
                }
                RecordingPersistentInputSurfacePolicy.isCameraSessionAttachmentConfirmed(
                    sessionRefreshRequested = true,
                    sessionRefreshCompleted = refreshed.await(5, TimeUnit.SECONDS)
                )
            } else {
                false
            }
            prearmedRecordingSurfaceAttachedToSession = attachedToSession
            if (attachedToSession) {
                Log.i("CameraViewModel", "Pre-armed persistent encoder surface attached to preview session")
            } else if (!refreshSession) {
                Log.i(
                    "CameraViewModel",
                    "Pre-armed persistent encoder surface prepared; Camera2 preview attachment pending session refresh"
                )
                scheduleRecordingSurfacePrearmRetry()
            } else {
                Log.w(
                    "CameraViewModel",
                    "Pre-armed persistent encoder surface prepared, but Camera2 session attachment was not confirmed"
                )
                scheduleRecordingSurfacePrearmRetry()
            }
        } else {
            prearmedRecordingSurfaceAttachedToSession = false
            Log.i(
                "CameraViewModel",
                "Pre-armed persistent encoder surface prepared; Camera2 preview attachment deferred"
            )
        }
        return true
    }

    private fun scheduleRecordingSurfacePrearmRetry() {
        viewModelScope.launch(Dispatchers.IO) {
            delay(500L)
            requestRecordingSurfacePrearm(forceSessionRefresh = true)
        }
    }

    private fun scheduleRecordingSurfacePrearmRetune() {
        if (!recordingSurfacePrearmRetuneScheduled.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(2_500L)
                if (shouldKeepRecordingSurfacePrearmed() &&
                    gpuRecordState.value is GpuRecordingState.Ready &&
                    prearmedRecordingSurface != null
                ) {
                    val expectedSignature = currentRecordingPrearmSignature()
                    if (prearmedRecordingSignature != expectedSignature) {
                        Log.i(
                            "CameraViewModel",
                            "Pre-armed recording surface retune rebuilding stale encoder signature"
                        )
                        releasePrearmedRecordingSurface(refreshSession = true)
                        requestRecordingSurfacePrearm(forceSessionRefresh = true)
                    } else {
                        Log.i(
                            "CameraViewModel",
                            "Pre-armed recording surface retune kept stable encoder graph"
                        )
                    }
                }
            } finally {
                recordingSurfacePrearmRetuneScheduled.set(false)
            }
        }
    }

    private fun releasePrearmedRecordingSurface(refreshSession: Boolean) {
        if (prearmedRecordingSurface == null) return
        if (gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping) {
            return
        }
        val releasedFeedPath = prearmedRecordingFeedPath
        vulkanRuntime.setEncoderSurface(null)
        vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
        prearmedRecordingSurface = null
        prearmedRecordingSignature = null
        prearmedRecordingFeedPath = null
        if (releasedFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER ||
            releasedFeedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
        ) {
            pipeline.setDedicatedHardwareImportEnabled(false)
            camera.setInternalRecordingHardwareImportEnabled(false)
            camera.setHardwareImportP010SourceEnabled(false)
            camera.setHardwareImportTargetSize(null)
        }
        if (prearmedRecordingSurfaceAttachedToSession) {
            if (releasedFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
                camera.setRecordSurface(null, refreshSession = false)
                if (refreshSession) {
                    camera.refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
                }
            } else {
                camera.setRecordSurface(null, refreshSession = refreshSession)
            }
        }
        prearmedRecordingSurfaceAttachedToSession = false
        if (gpuRecordState.value is GpuRecordingState.Ready) {
            gpuRecording.discardPreparedSession()
        }
        gpuRecording.releasePersistentInputSurface()
        Log.i("CameraViewModel", "Released pre-armed encoder surface")
    }

    fun startRecording() {
        if (stillCapture.isCaptureInFlight()) {
            Log.i("CameraViewModel", "startRecording ignored while a full-resolution photo is in flight")
            return
        }
        if (!recordingController.startRecording()) return
        if (recordingStopInProgress.get()) return
        if (!recordingStartInProgress.compareAndSet(false, true)) {
            Log.i("CameraViewModel", "startRecording ignored: recording start already in progress")
            return
        }
        recordingStartCancellationRequested.set(false)
        recordingTransitionState.value = GpuRecordingState.Starting
        startRecordingInternal()
    }

    private fun startRecordingInternal() {
        closeCameraAfterRecordingStop = false
        // stopRecordingInternal after a successful save so it applies to exactly one clip.
        gpuRecording.pendingCustomRecordingName =
            if (prefs.customRecordingNameEnabled) {
                com.neoproc.neoproccamx.recording.RecordingFileNamePolicy
                    .sanitizeCustomName(prefs.customRecordingName)
                    .ifEmpty { null }
            } else {
                null
            }
        recordingStartJob = viewModelScope.launch(Dispatchers.IO) {
            var rawVideoEnabled = false
            var attemptedCameraVideoStart = false
            var recordingRuntimeHintActive = false
            var recordingSessionStarted = false
            val recordingStartElapsedMs = SystemClock.elapsedRealtime()
            try {
                if (rawCustomRecordingState.value is GpuRecordingState.Error) {
                    rawCustomRecordingState.value = GpuRecordingState.Idle
                }
                if (gpuRecordState.value is GpuRecordingState.Recording ||
                    gpuRecordState.value is GpuRecordingState.Paused ||
                    gpuRecordState.value is GpuRecordingState.Stopping
                ) {
                    Log.w("CameraViewModel", "startRecording ignored: state=${gpuRecordState.value}")
                    return@launch
                }

                if (cameraState.value != CameraState.Streaming) {
                    Log.w("CameraViewModel", "startRecording ignored: cameraState=${cameraState.value}")
                    gpuRecording.reportExternalError("Camera preview is not ready for recording")
                    return@launch
                }
                if (!ensureManualVideoColorSelectionReadyForRecording()) {
                    return@launch
                }
                strictHighEndThermalAdmissionFailure()?.let { message ->
                    Log.w("CameraViewModel", message)
                    setHardwareWarning(message)
                    gpuRecording.reportExternalError(message)
                    return@launch
                }
                releaseCinemaAutoLock(resetCameraState = true, reason = "recording_restart")
                clearStaleAutofocusRegionForRecordingStart()
                clearRecordingReadoutOverride()
                lastValidatedGpuRecordingEvidence = ValidatedGpuRecordingEvidence.EMPTY
                strictProcessedGpuValidationJob?.cancel()
                strictProcessedGpuValidationJob = null
                recordingReadyForClipTiming = false
                applyRecordingCriticalRuntimeHints(
                    recordingActive = true,
                    previewFps = recording.fps.coerceAtLeast(1).toFloat()
                )
                recordingRuntimeHintActive = true
                val aiFeaturesRequestedForRecordingStart =
                    effectiveAiEnhancementEnabled() || prefs.faceDetectionEnabled || prefs.eyeAutofocusEnabled
                applyAiTrackingPreferences()
                val aiWarmupWaitMs = if (aiFeaturesRequestedForRecordingStart) 250L else 0L
                if (aiWarmupWaitMs > 0L &&
                    !aiCameraPipeline.awaitWarmupIdleBeforeRecording(timeoutMs = aiWarmupWaitMs)
                ) {
                    Log.w(
                        "CameraViewModel",
                        "Recording start continuing after short AI warm-up idle wait timed out; " +
                            "new AI stage initialization remains blocked for the recording hot path"
                    )
                }
                if (_uiMode.value == AppMode.VIDEO && _cinemaVideoMode.value != CinemaVideoMode.RAW) {
                    RecordDevelopModeFlag.set(true)
                }

                val recordingSize = preferredRecordingSize()
                if (shouldUseCustomRawRecordingPipeline()) {
                    val started = startCustomRawRecordingSession(recordingSize)
                    if (!started && !recordingStartCancellationRequested.get()) {
                        gpuRecording.reportExternalError("Custom RAW packet recording could not start")
                    } else {
                        recordingSessionStarted = true
                    }
                    return@launch
                }
                if (shouldUseUncompressedCinemaPipeline()) {
                    val started = startNpv10RecordingSession(recordingSize)
                    if (!started && !recordingStartCancellationRequested.get()) {
                        gpuRecording.reportExternalError("Custom packed-10 NPV10 recording could not start")
                    } else {
                        recordingSessionStarted = true
                    }
                    return@launch
                }
                camera.setNpv10TenBitCaptureEnabled(false)
                val app = getApplication<Application>()
                gpuRecording.audioEnabled =
                    recording.audioSource != AudioSrc.NONE &&
                        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                val initialGraphContract = activeVideoPipelineContract()
                val initialParityPlan = activeRecordingPipelineParityPlan(initialGraphContract)
                val byteBufferP010RoutePlanned =
                    initialParityPlan.feedPath == RecordingFeedPath.BYTEBUFFER_P010_ENCODER
                val offscreenAhbEglRoutePlanned =
                    initialParityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
                val highPrecisionRoutePlanned =
                    byteBufferP010RoutePlanned ||
                        offscreenAhbEglRoutePlanned
                val sessionPlan = RecordingCapabilityPolicy.resolveSessionPlan(
                    requestedCodec = recording.codec,
                    av1Supported = supportsAv1Fast(),
                    hdrRequested = initialParityPlan.hdrRequestedForSessionPlan,
                    rawRequested = initialParityPlan.rawRequestedForSessionPlan,
                    rawSupported = camera.supportsRaw,
                    logEnabled = initialParityPlan.logEnabledForSessionPlan,
                    tenBitRequested = initialParityPlan.tenBitRequestedForSessionPlan
                )
                gpuRecording.videoCodec = if (highPrecisionRoutePlanned) {
                    GpuVideoCodec.HEVC
                } else {
                    sessionPlan.codec
                }
                val recordingSelection = resolveRecordingSelection(
                    recordingSize,
                    gpuRecording.videoCodec
                )
                val resolvedBitrate = resolveConfiguredBitrate(
                    selection = recordingSelection,
                    fps = recording.fps,
                    codec = recordingSelection.codec
                )
                applyResolvedRecordingConfig(
                    resolvedSelection = recordingSelection,
                    fps = recording.fps,
                    bitrate = resolvedBitrate,
                    audioSource = recording.audioSource
                )
                applyPreRecordingPerformanceCaps(recordingSelection.sourceSize)
                val recordingSourcePlan = resolveRecordingSourcePlan(recordingSelection.requestedSize)
                val resolvedReadoutOverride = resolveRecordingReadoutOverrideSize(
                    selection = recordingSelection,
                    sourcePlan = recordingSourcePlan
                )
                val preparedEncoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
                if (byteBufferP010RoutePlanned) {
                    applyLegacyP010SustainableFps(
                        preparedEncoderSize = preparedEncoderSize,
                        reason = "recording-start"
                    )
                } else if (offscreenAhbEglRoutePlanned) {
                    applyProcessedGpuSustainableFps(
                        preparedEncoderSize = preparedEncoderSize,
                        reason = "recording-start"
                    )
                }
                vulkanRuntime.setOffscreenEncoderTargetFrameRate(recording.fps)
                val previewBindingGraphContract = activeVideoPipelineContract()
                val previewBindingParityPlan =
                    activeRecordingPipelineParityPlan(previewBindingGraphContract)
                val previewBindingSessionGraphPlan = activeCamera2SessionGraphParityPlan(
                    graphContract = previewBindingGraphContract,
                    parityPlan = previewBindingParityPlan,
                    encoderSize = preparedEncoderSize,
                    proposedAnalysisSize = preferredRecordingAnalysisSize(preparedEncoderSize),
                    proposedReadoutOverrideSize = resolvedReadoutOverride,
                    encoderSurfaceAvailable = false,
                    recordSurfaceAttachedToSession = prearmedRecordingSurfaceAttachedToSession,
                    stage = Camera2SessionGraphStage.STARTING
                )
                val preferNativePreviewForRecording =
                    RecordingPreviewBindingPolicy.resolvePreferNativePreviewOverride(
                        fullSensorReadoutForced = previewBindingSessionGraphPlan.rawSensorOwnsReadout
                    )
                camera.setRecordingReadoutOverrideSize(previewBindingSessionGraphPlan.readoutOverrideSize)
                camera.setRecordingEncoderTargetSize(previewBindingSessionGraphPlan.encoderSize)
                camera.setAnalysisTargetSize(previewBindingSessionGraphPlan.analysisTargetSize)
                logCamera2SessionGraphParityPlan(
                    previewBindingSessionGraphPlan,
                    reason = "recording-start-preview",
                    force = true
                )
                Log.i(
                    "CameraViewModel",
                    "Recording start: preview binding override native=$preferNativePreviewForRecording " +
                        "rawReadout=${previewBindingSessionGraphPlan.rawSensorOwnsReadout} " +
                        "sourceFullSensor=${recordingSourcePlan.fullSensorReadoutForced}"
                )
                runOnMain {
                    if (_uiMode.value == AppMode.VIDEO) {
                        // Keep an already-running monitor compositor; missing compositor rebinds stay blocked.
                        if (!com.neoproc.neoproccamx.rendering.PreviewGradeWhileRecordingFlag.enabled) {
                            releaseVisibleGlPreviewCompositorsForRecordingFeed(
                                previewBindingParityPlan.feedPath
                            )
                        }
                        syncGeometryTargets()
                        refreshPreviewPipelineIfNeeded(
                            forceSessionRefresh = false,
                            preferNativePreviewOverride = preferNativePreviewForRecording,
                            deferSessionRefresh = true
                        )
                        scheduleGlPreviewCompositorBindingResync("recording-start-preview")
                    }
                }
                gpuRecording.orientationHintDegrees = camera.videoOrientationHint()
                gpuRecording.tenBitEnabled = when {
                    highPrecisionRoutePlanned -> true
                    // Unified pipeline forces 10-bit P010 for every processed mode (incl. SDR),
                    // but ONLY where the encoder can actually back 10-bit. RAW stays excluded, and
                    // hardware without 10-bit support keeps its existing bit depth (no downgrade).
                    FORCE_UNIVERSAL_TEN_BIT_PROCESSED &&
                        !initialParityPlan.rawRequestedForSessionPlan &&
                        gpuRecording.supports10Bit() -> true
                    else -> sessionPlan.tenBitPipelineEnabled
                }
                syncEffectiveRecordingColorProfile()
                logVideoPipelineGraphContract(
                    activeVideoPipelineContract(),
                    reason = "recording-start",
                    force = true
                )
                Log.i(
                    "CameraViewModel",
                    "startRecording requested=${recordingSelection.requestedSize.width}x${recordingSelection.requestedSize.height} " +
                        "source=${recordingSelection.sourceSize.width}x${recordingSelection.sourceSize.height} " +
                        "upscaled=${recordingSelection.upscaledToRequested} " +
                        "cameraExact=${recordingSelection.cameraExactMatch} " +
                        "encoderExact=${recordingSelection.encoderExactMatch} " +
                        "strictRequested=${gpuRecording.strictRequestedResolution} " +
                        "strictCamera=${gpuRecording.strictCameraResolution} " +
                        "strictEncoder=${gpuRecording.strictEncoderResolution} " +
                        "codec=${gpuRecording.videoCodec}"
                )
                syncPreviewPerformanceModeOnMain()
                rawVideoManager.compressionMode = _rawVideoCompression.value
                val recordingParityPlan = activeRecordingPipelineParityPlan()
                beginCamera2FrameLineageSession(recordingParityPlan)
                val byteBufferP010Input =
                    recordingParityPlan.feedPath == RecordingFeedPath.BYTEBUFFER_P010_ENCODER
                gpuRecording.setVideoInputMode(
                    if (byteBufferP010Input) {
                        RecordingVideoInputMode.BYTEBUFFER_P010
                    } else {
                        RecordingVideoInputMode.SURFACE
                    }
                )
                gpuRecording.setRealtimeSurfaceCadenceEnabled(
                    recordingParityPlan.feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
                )
                updatePreviewViewAwait { resetPreviewFpsTracking() }
                val vulkanImportStatsAtStart = vulkanImportStats.value
                Log.i(
                    "CameraViewModel",
                    "Recording feed path selected=${recordingParityPlan.feedPath} " +
                        "vulkanQualified=${vulkanRecordingPathQualified()} " +
                        "offscreenAhbEglQualified=${offscreenAhbEglEncoderQualified()} " +
                        "nativePreviewActive=$nativeVisiblePreviewActive " +
                        "nativePreviewConfirmed=$nativeVisiblePreviewConfirmed " +
                        "directPreviewActive=$directPreviewSurfaceActive " +
                        "previewSurfaceBound=${vulkanImportStatsAtStart.previewSurfaceBound} " +
                        "frameImportEnabled=${vulkanImportStatsAtStart.frameImportEnabled} " +
                        "importedFrames=${vulkanImportStatsAtStart.sessionImportedFrames}"
                )
                runCatching {
                    // Why the zero-copy HDR route did/didn't engage (logged once per recording).
                    val hdrNow = camera.describeHdrDynamicRangeState()
                    val sizeNow = selectedVideoSize ?: Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
                    NeoLogger.i(
                        "CameraViewModel",
                        "ZEROCOPY_GATE feedPath=${recordingParityPlan.feedPath} " +
                            "deliveryKind=${recordingParityPlan.deliveryKind} " +
                            "effectiveHdrMode=${activeVideoPipelineContract().deliveryState.effectiveHdrMode} " +
                            "anyHdr=${hdrNow.support.anyHdrSupported} " +
                            "resolvedProfile=${hdrNow.resolvedProfileForCurrentMode} " +
                            "encoderAcceptsSizeRate=${hdrZeroCopyEncoderAcceptsSizeRate(sizeNow.width, sizeNow.height, recording.fps)} " +
                            "size=${sizeNow.width}x${sizeNow.height} fps=${recording.fps}"
                    )
                }
                rawVideoEnabled = recordingParityPlan.encoderRawEnabled
                val processedGpuStartDecision = ProcessedGpuRecordingRequirementPolicy.canStart(
                    deliveryKind = recordingParityPlan.deliveryKind,
                    feedPath = recordingParityPlan.feedPath,
                    vulkanRuntimeSupported = vulkanSupported.value,
                    strictProcessedGpuRequired = recordingParityPlan.deliveryKind == VideoDeliveryKind.SDR && !bypassByteBufferP010ForRealtime(),
                    strictHybridP010Required = recordingParityPlan.deliveryKind != VideoDeliveryKind.RAW
                )
                if (!processedGpuStartDecision.allowed) {
                    val message = processedGpuStartDecision.failureMessage
                        ?: "GPU processed recording is unavailable for this video mode."
                    setHardwareWarning(message)
                    cleanupFailedRecordingStart(
                        message = message,
                        rawVideoEnabled = false,
                        refreshSession = true,
                        stopCameraVideo = false
                    )
                    return@launch
                }
                if (recordingParityPlan.parityCertificate.recordingBlocked) {
                    val message = "Recording blocked because preview/recording GPU parity is not valid: " +
                        recordingParityPlan.parityCertificate.failureCodes.joinToString(separator = ",")
                    setHardwareWarning(message)
                    cleanupFailedRecordingStart(
                        message = message,
                        rawVideoEnabled = false,
                        refreshSession = true,
                        stopCameraVideo = false
                    )
                    return@launch
                }
                gpuRecording.cinemaMode = effectiveRecordingCinemaMode(recordingParityPlan)
                gpuRecording.rawVideoEnabled = recordingParityPlan.encoderRawEnabled
                gpuRecording.videoLogEnabled = recordingParityPlan.encoderLogEnabled
                gpuRecording.videoHdrMode = recordingParityPlan.encoderHdrMode
                gpuRecording.videoColorSpace = recordingParityPlan.encoderColorSpace
                gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata(
                    encoderSize = preparedEncoderSize,
                    selection = recordingSelection,
                    scene = pipeline.sceneAnalysis.value,
                    recommendation = pipeline.captureRecommendation.value
                )
                logRecordingPipelineParityPlan(
                    recordingParityPlan,
                    reason = "recording-start",
                    force = true
                )
                if (rawVideoEnabled) {
                    installRawVideoCallbacks()
                    camera.setRawVideoCaptureEnabled(true)
                    val engageMaxRes = resolveRawCaptureTier().engageMaxResPixelMode
                    camera.setRecordingSensorPixelModeMaximumResolution(engageMaxRes)
                    rawVideoManager.sensorPixelModeMaximumResolutionUsed = engageMaxRes
                    rawVideoManager.orientationHintDegrees = camera.videoOrientationHint()
                } else {
                    camera.setRawVideoCaptureEnabled(false)
                    camera.setRecordingSensorPixelModeMaximumResolution(false)
                }

                val expectedPrearmSignature = currentRecordingPrearmSignature(recordingParityPlan)
                val internalHardwareImportUsesCodecOwnedSurface =
                    recordingParityPlan.feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
                val offscreenAhbEglUsesCodecOwnedSurface =
                    recordingParityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
                val hardwareImportUsesCodecOwnedSurface =
                    internalHardwareImportUsesCodecOwnedSurface ||
                        offscreenAhbEglUsesCodecOwnedSurface
                val reusablePrearmedSurface =
                    !hardwareImportUsesCodecOwnedSurface &&
                        !byteBufferP010Input &&
                        RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                        recordingStateReady = gpuRecordState.value is GpuRecordingState.Ready,
                        surfaceAvailable = prearmedRecordingSurface != null,
                        preparedSignature = prearmedRecordingSignature,
                        expectedSignature = expectedPrearmSignature
                    )
                val reusableOffscreenPrearmedSurface =
                    offscreenAhbEglUsesCodecOwnedSurface &&
                        !byteBufferP010Input &&
                        RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                            recordingStateReady = gpuRecordState.value is GpuRecordingState.Ready,
                            surfaceAvailable = prearmedRecordingSurface != null,
                            preparedSignature = prearmedRecordingSignature,
                            expectedSignature = expectedPrearmSignature
                        )
                val offscreenSourceAlreadyInSession =
                    reusableOffscreenPrearmedSurface && prearmedRecordingSurfaceAttachedToSession
                if (internalHardwareImportUsesCodecOwnedSurface && prearmedRecordingSurface != null) {
                    Log.i(
                        "CameraViewModel",
                        "Recording start: retiring persistent pre-arm for codec-owned Vulkan hardware-import surface"
                    )
                    releasePrearmedRecordingSurface(refreshSession = false)
                } else if (byteBufferP010Input && prearmedRecordingSurface != null) {
                    Log.i(
                        "CameraViewModel",
                        "Recording start: retiring persistent pre-arm for non-prearmed recording input"
                    )
                    releasePrearmedRecordingSurface(refreshSession = false)
                }
                if (gpuRecordState.value is GpuRecordingState.Ready &&
                    !reusablePrearmedSurface &&
                    !reusableOffscreenPrearmedSurface
                ) {
                    releasePrearmedRecordingSurface(refreshSession = false)
                }
                gpuRecording.setPersistentInputSurfaceAllowed(!hardwareImportUsesCodecOwnedSurface)
                if (byteBufferP010Input) {
                    gpuRecording.setPersistentInputSurfaceAllowed(false)
                }
                val prepareStartElapsedMs = SystemClock.elapsedRealtime()
                var encoderSurface = if (reusablePrearmedSurface) {
                    Log.i(
                        "CameraViewModel",
                        "Recording start: reusing pre-configured MediaCodec persistent input surface"
                    )
                    prearmedRecordingSurface
                } else if (reusableOffscreenPrearmedSurface) {
                    Log.i(
                        "CameraViewModel",
                        "Recording start: reusing pre-armed offscreen AHB EGL encoder surface"
                    )
                    prearmedRecordingSurface
                } else {
                    gpuRecording.prepare()
                }
                RuntimeStageTelemetry.recordDuration(
                    tag = "CameraViewModel",
                    stage = "startRecording.prepare",
                    durationMs = SystemClock.elapsedRealtime() - prepareStartElapsedMs,
                    slowThresholdMs = SLOW_RECORDING_PREPARE_MS
                )
                if (encoderSurface == null && !byteBufferP010Input) {
                    Log.e("CameraViewModel", "startRecording: prepare() returned null")
                    cleanupFailedRecordingStart(
                        message = "Video encoder could not be prepared",
                        rawVideoEnabled = rawVideoEnabled,
                        refreshSession = false,
                        stopCameraVideo = false
                    )
                    return@launch
                }
                syncReportedRecordingBitrate()
                val encoderSurfaceAlreadyInSession =
                    !byteBufferP010Input &&
                    RecordingPersistentInputSurfacePolicy.shouldSkipSessionRebuildForPrearmedSurface(
                        surfaceMatches =
                            prearmedRecordingSurface === encoderSurface &&
                                prearmedRecordingSignature == expectedPrearmSignature,
                        surfaceAttachedToCameraSession = prearmedRecordingSurfaceAttachedToSession
                    )

                val startingSessionGraphPlan = activeCamera2SessionGraphParityPlan(
                    parityPlan = recordingParityPlan,
                    encoderSize = preparedEncoderSize,
                    proposedAnalysisSize = preferredRecordingAnalysisSize(preparedEncoderSize),
                    proposedReadoutOverrideSize = resolvedReadoutOverride,
                    encoderSurfaceAvailable = encoderSurface != null,
                    recordSurfaceAttachedToSession = encoderSurfaceAlreadyInSession,
                    previousSessionSignature = null,
                    stage = Camera2SessionGraphStage.STARTING
                )
                logCamera2SessionGraphParityPlan(
                    startingSessionGraphPlan,
                    reason = "recording-start",
                    force = true
                )
                Log.i(
                    "CameraViewModel",
                    "Recording start Camera2 session rawReadout=${startingSessionGraphPlan.rawSensorOwnsReadout}"
                )
                updatePreviewViewAwait {
                    setEncoderSize(preparedEncoderSize.width, preparedEncoderSize.height)
                }
                runOnMain { applyRecordingOutputAspectOverride(preparedEncoderSize) }
                camera.setRecordingReadoutOverrideSize(startingSessionGraphPlan.readoutOverrideSize)
                camera.setRecordingEncoderTargetSize(startingSessionGraphPlan.encoderSize)
                camera.setAnalysisTargetSize(startingSessionGraphPlan.analysisTargetSize)
                gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata(
                    encoderSize = preparedEncoderSize,
                    selection = recordingSelection,
                    scene = pipeline.sceneAnalysis.value,
                    recommendation = pipeline.captureRecommendation.value
                )
                suspend fun clearInternalRecordingFeed() {
                    updatePreviewViewAwait {
                        setEncoderFeedEnabled(false)
                        setEncoderSurface(null)
                    }
                    vulkanRuntime.setFrameImportEnabled(false)
                    vulkanRuntime.setEncoderSurface(null)
                    vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
                    pipeline.setDedicatedHardwareImportEnabled(false)
                    camera.setInternalRecordingHardwareImportEnabled(false)
                    camera.setHardwareImportP010SourceEnabled(false)
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    camera.setHardwareImportTargetSize(null)
                }

                fun startRawCaptureAfterRecordingSessionReady() {
                    if (!rawVideoEnabled ||
                        rawVideoManager.state.value is com.neoproc.neoproccamx.pipeline.RawVideoState.Capturing
                    ) {
                        return
                    }
                    rawVideoManager.startCapture()
                    Log.i(
                        "CameraViewModel",
                        "RAW capture armed after recording session reached stable state"
                    )
                }

                suspend fun startCamera2DirectRecording(): Boolean {
                    activeRecordingFeedPath = RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE
                    val actualDirectParityPlan =
                        if (recordingParityPlan.feedPath == RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE) {
                            recordingParityPlan
                        } else {
                            activeRecordingPipelineParityPlan(
                                actualFeedPathOverride = RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE
                            )
                        }
                    logRecordingPipelineParityPlan(
                        actualDirectParityPlan,
                        reason = if (actualDirectParityPlan.parityCertificate.camera2DirectFallback) {
                            "camera2-direct-fallback"
                        } else {
                            "camera2-direct-path"
                        },
                        force = true
                    )
                    clearInternalRecordingFeed()
                    camera.setRecordingReadoutOverrideSize(startingSessionGraphPlan.readoutOverrideSize)
                    camera.setRecordingEncoderTargetSize(startingSessionGraphPlan.encoderSize)
                    camera.setAnalysisTargetSize(startingSessionGraphPlan.analysisTargetSize)
                    camera.setRecordSurface(encoderSurface, refreshSession = false)
                    prearmedRecordingSurfaceAttachedToSession = true
                    if (startingSessionGraphPlan.refreshAction == Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY) {
                        Log.i(
                            "CameraViewModel",
                            "Recording start: using pre-armed persistent encoder surface; " +
                                "Camera2 session rebuild skipped"
                        )
                    } else {
                        Log.i("CameraViewModel", "Recording start: rebuilding session once with encoder surface")
                        if (!awaitFreshPreviewSessionBeforeRecording(
                                forceRebuild = true,
                                rebuildReaders = startingSessionGraphPlan.refreshAction ==
                                    Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS
                            )
                        ) {
                            cleanupFailedRecordingStart(
                                message = "Preview session refresh timed out before recording start",
                                rawVideoEnabled = rawVideoEnabled,
                                refreshSession = true,
                                stopCameraVideo = false
                            )
                            return false
                        }
                    }

                    if (
                        actualDirectParityPlan.deliveryKind == VideoDeliveryKind.HDR &&
                        !NdrInputTransportPolicy.isPqProfile(
                            camera.describeHdrDynamicRangeState().encoderProfile
                        )
                    ) {
                        cleanupFailedRecordingStart(
                            message = "NDR recording requires an applied BT.2100 PQ 10-bit profile; HLG/SDR was rejected.",
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }

                    gpuRecording.start()
                    if (!awaitGpuRecordingState()) {
                        val message = (gpuRecordState.value as? GpuRecordingState.Error)?.message
                            ?: "Video encoder failed to enter recording state"
                        cleanupFailedRecordingStart(
                            message = message,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }

                    applyRecordingCameraLookControls()
                    attemptedCameraVideoStart = true
                    camera.startVideoCapture()
                    if (!awaitCameraRecordingState()) {
                        cleanupFailedRecordingStart(
                            message = "Camera session failed to switch into recording mode",
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = true
                        )
                        return false
                    }
                    syncActivePreviewFrameRate()
                    syncPreviewPerformanceModeOnMain()
                    startRawCaptureAfterRecordingSessionReady()
                    markGpuRecordingReadyForClipTiming()
                    startRecordingForegroundService()
                    updateCinemaRuntimeStats(lastPreviewFpsSample)
                    Log.i("CameraViewModel", "Camera2 direct recording started")
                    return true
                }

                suspend fun startCamera2PreviewParityRecording(): Boolean {
                    activeRecordingFeedPath = RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
                    val actualPreviewParityPlan =
                        if (recordingParityPlan.feedPath ==
                            RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
                        ) {
                            recordingParityPlan
                        } else {
                            activeRecordingPipelineParityPlan(
                                actualFeedPathOverride =
                                    RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
                            )
                        }
                    logRecordingPipelineParityPlan(
                        actualPreviewParityPlan,
                        reason = "camera2-preview-parity-fallback",
                        force = true
                    )
                    val previewParityStartDecision = ProcessedGpuRecordingRequirementPolicy.canStart(
                        deliveryKind = actualPreviewParityPlan.deliveryKind,
                        feedPath = RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
                        vulkanRuntimeSupported = vulkanSupported.value,
                        strictProcessedGpuRequired = actualPreviewParityPlan.deliveryKind == VideoDeliveryKind.SDR && !bypassByteBufferP010ForRealtime(),
                        strictHybridP010Required = actualPreviewParityPlan.deliveryKind != VideoDeliveryKind.RAW
                    )
                    if (!previewParityStartDecision.allowed) {
                        val message = previewParityStartDecision.failureMessage
                            ?: "Strict processed recording route is unavailable."
                        setHardwareWarning(message)
                        cleanupFailedRecordingStart(
                            message = message,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }
                    clearInternalRecordingFeed()
                    val previewParitySessionGraphPlan = activeCamera2SessionGraphParityPlan(
                        parityPlan = actualPreviewParityPlan,
                        encoderSize = preparedEncoderSize,
                        proposedAnalysisSize = preferredRecordingAnalysisSize(preparedEncoderSize),
                        proposedReadoutOverrideSize = resolvedReadoutOverride,
                        encoderSurfaceAvailable = true,
                        recordSurfaceAttachedToSession =
                            prearmedRecordingSurfaceAttachedToSession &&
                                prearmedRecordingSurface === encoderSurface,
                        previousSessionSignature = null,
                        stage = Camera2SessionGraphStage.STARTING
                    )
                    logCamera2SessionGraphParityPlan(
                        previewParitySessionGraphPlan,
                        reason = "camera2-preview-parity-fallback",
                        force = true
                    )
                    camera.setRecordingReadoutOverrideSize(previewParitySessionGraphPlan.readoutOverrideSize)
                    camera.setRecordingEncoderTargetSize(previewParitySessionGraphPlan.encoderSize)
                    camera.setAnalysisTargetSize(previewParitySessionGraphPlan.analysisTargetSize)
                    camera.setRecordSurface(encoderSurface, refreshSession = false)
                    if (previewParitySessionGraphPlan.refreshAction == Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY) {
                        prearmedRecordingSurfaceAttachedToSession = true
                        camera.setPreviewContinuityRecordSurfaceEnabled(true)
                        Log.i(
                            "CameraViewModel",
                            "Recording start: using pre-armed persistent encoder surface; " +
                                "Camera2 session rebuild skipped"
                        )
                    } else {
                        prearmedRecordingSurface = null
                        prearmedRecordingSurfaceAttachedToSession = false
                        prearmedRecordingSignature = null
                        prearmedRecordingFeedPath = null
                        Log.i(
                            "CameraViewModel",
                            "Recording start: rebuilding preview-continuity session with encoder target"
                        )
                        if (!awaitFreshPreviewSessionBeforeRecording(
                                forceRebuild = true,
                                rebuildReaders = previewParitySessionGraphPlan.refreshAction ==
                                    Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS
                            )
                        ) {
                            cleanupFailedRecordingStart(
                                message = "Preview session refresh timed out before preview-continuity recording start",
                                rawVideoEnabled = rawVideoEnabled,
                                refreshSession = true,
                                stopCameraVideo = false
                            )
                            return false
                        }
                        camera.setPreviewContinuityRecordSurfaceEnabled(true)
                    }

                    if (
                        actualPreviewParityPlan.deliveryKind == VideoDeliveryKind.HDR &&
                        !NdrInputTransportPolicy.isPqProfile(
                            camera.describeHdrDynamicRangeState().encoderProfile
                        )
                    ) {
                        cleanupFailedRecordingStart(
                            message = "NDR recording requires an applied BT.2100 PQ 10-bit profile; HLG/SDR was rejected.",
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }

                    gpuRecording.start()
                    if (!awaitGpuRecordingState()) {
                        val message = (gpuRecordState.value as? GpuRecordingState.Error)?.message
                            ?: "Video encoder failed to enter recording state"
                        cleanupFailedRecordingStart(
                            message = message,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }

                    applyRecordingCameraLookControls()
                    syncActivePreviewFrameRate()
                    syncPreviewPerformanceModeOnMain()
                    startRawCaptureAfterRecordingSessionReady()
                    markGpuRecordingReadyForClipTiming()
                    startRecordingForegroundService()
                    updateCinemaRuntimeStats(lastPreviewFpsSample)
                    // Zero-copy HDR verification + silent-8bit guard: this direct camera->encoder
                    // surface is the full-fps 10-bit route. Confirm the device actually pinned a
                    // 10-bit DynamicRangeProfile; if HDR was requested but the encoder surface fell
                    // back to STANDARD (8-bit), tell the user instead of shipping a fake-HDR clip.
                    runCatching {
                        val hdrState = camera.describeHdrDynamicRangeState()
                        val hdrActive =
                            activeVideoPipelineContract().deliveryState.effectiveHdrMode != "OFF"
                        val tenBitApplied = hdrState.encoderProfile != null &&
                            hdrState.encoderProfile != HdrDynamicRangePolicy.PROFILE_STANDARD
                        NeoLogger.critical(
                            "CameraViewModel",
                            "HDR_DIRECT_SURFACE route=PREVIEW_PARITY hdrActive=$hdrActive " +
                                "tenBitApplied=$tenBitApplied appliedEncoderProfile=${hdrState.encoderProfile} " +
                                "resolvedProfile=${hdrState.resolvedProfileForCurrentMode} " +
                                "size=${preparedEncoderSize.width}x${preparedEncoderSize.height} fps=${recording.fps}"
                        )
                        if (hdrActive && !tenBitApplied) {
                            setHardwareWarning(
                                "10-bit HDR could not be locked on the encoder for this size/fps; " +
                                    "the clip may be 8-bit. Try a lower resolution or frame rate for 10-bit HDR."
                            )
                        }
                    }
                    Log.i("CameraViewModel", "Preview-continuity Camera2 recording started; visible preview request unchanged")
                    return true
                }

                suspend fun retryPreviewContinuityAfterInternalHardwareImportFailure(
                    message: String
                ): Boolean {
                    if (strictHighEndQualityEnabled()) {
                        val strictFailureMessage =
                            "$message Camera2 preview-continuity fallback blocked because " +
                                "strict high-end mode requires validated Vulkan GPU recording."
                        Log.e(
                            "CameraViewModel",
                            "Recording start: internal hardware import failed; " +
                                "Camera2 preview-continuity fallback blocked. reason=$message"
                        )
                        setHardwareWarning(strictFailureMessage)
                        cleanupFailedRecordingStart(
                            message = strictFailureMessage,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }
                    Log.w(
                        "CameraViewModel",
                        "Recording start: internal hardware import failed; " +
                            "retrying preview-continuity encoder path. reason=$message"
                    )
                    setHardwareWarning("$message Retrying stable preview-continuity recording path.")
                    updatePreviewViewAwait {
                        setEncoderFeedEnabled(false)
                        setEncoderSurface(null)
                    }
                    vulkanRuntime.setFrameImportEnabled(false)
                    vulkanRuntime.setEncoderSurface(null)
                    vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
                    pipeline.setDedicatedHardwareImportEnabled(false)
                    camera.setInternalRecordingHardwareImportEnabled(false)
                    camera.setHardwareImportP010SourceEnabled(false)
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    camera.setHardwareImportTargetSize(null)
                    camera.setRecordSurface(null, refreshSession = false)
                    activeRecordingFeedPath = null

                    gpuRecording.reportExternalError(message)
                    gpuRecording.setPersistentInputSurfaceAllowed(true)
                    encoderSurface = gpuRecording.prepare()
                    if (encoderSurface == null) {
                        cleanupFailedRecordingStart(
                            message = "$message Preview-continuity retry could not prepare encoder.",
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }
                    syncReportedRecordingBitrate()
                    return startCamera2PreviewParityRecording()
                }

                suspend fun rejectOffscreenAhbEglRecordingNoDowngrade(
                    message: String
                ): Boolean {
                    val strictMessage =
                        "$message Strict hybrid CPU/GPU/P010 recording requires validated " +
                            "offscreen AHB/EGL P010 at the selected FPS; legacy bytebuffer P010 " +
                            "fallback is blocked to avoid FPS and quality downgrade."
                    Log.w(
                        "CameraViewModel",
                        "$strictMessage " +
                            "capabilities=${vulkanRuntime.capabilitySummary.value}"
                    )
                    runCatching {
                        if (vulkanRuntime.isOffscreenAhbEglEncoderHardwareWallProven) {
                            prefs.setOffscreenEglEncoderProvenUnavailable(offscreenEglRecallFingerprint())
                            NeoLogger.i(
                                "CameraViewModel",
                                "Offscreen encoder is a proven GPU hardware wall on this build/device; " +
                                    "strict no-downgrade recording start aborted and recall persisted."
                            )
                        } else {
                            NeoLogger.i(
                                "CameraViewModel",
                                "Offscreen encoder not proven this attempt (re-probable next launch); " +
                                    "strict no-downgrade recording start aborted."
                            )
                        }
                    }
                    setHardwareWarning(strictMessage)
                    vulkanRuntime.setFrameImportEnabled(false)
                    vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
                    pipeline.setDedicatedHardwareImportEnabled(false)
                    camera.setInternalRecordingHardwareImportEnabled(false)
                    camera.setHardwareImportP010SourceEnabled(false)
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    camera.setHardwareImportTargetSize(null)
                    camera.setRecordSurface(null, refreshSession = false)
                    activeRecordingFeedPath = null
                    lastValidatedGpuRecordingEvidence = ValidatedGpuRecordingEvidence.EMPTY
                    gpuRecording.discardPreparedSession()
                    gpuRecording.setRealtimeSurfaceCadenceEnabled(false)
                    gpuRecording.setPersistentInputSurfaceAllowed(false)
                    cleanupFailedRecordingStart(
                        message = strictMessage,
                        rawVideoEnabled = rawVideoEnabled,
                        refreshSession = true,
                        stopCameraVideo = attemptedCameraVideoStart
                    )
                    return false
                }

                suspend fun startInternalHardwareImportRecording(): Boolean {
                    activeRecordingFeedPath = RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
                    gpuRecording.setRealtimeSurfaceCadenceEnabled(true)
                    val actualInternalParityPlan =
                        if (recordingParityPlan.feedPath ==
                            RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
                        ) {
                            recordingParityPlan
                        } else {
                            activeRecordingPipelineParityPlan(
                                actualFeedPathOverride =
                                    RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
                            )
                        }
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    camera.setRecordSurface(null, refreshSession = false)
                    camera.setRecordingEncoderTargetSize(preparedEncoderSize)
                    prearmedRecordingSurface = null
                    prearmedRecordingSurfaceAttachedToSession = false
                    prearmedRecordingSignature = null
                    prearmedRecordingFeedPath = null
                    camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
                    camera.setHardwareImportTargetSize(preparedEncoderSize)
                    camera.setInternalRecordingHardwareImportEnabled(true)
                    camera.setHardwareImportP010SourceEnabled(false)
                    pipeline.setDedicatedHardwareImportEnabled(true)
                    vulkanRuntime.setFrameImportEnabled(false)
                    vulkanRuntime.beginPreviewImportSession()
                    val encoderBound = vulkanRuntime.setEncoderSurface(encoderSurface)
                    updatePreviewViewAwait { setEncoderSurface(encoderSurface) }
                    if (!encoderBound) {
                        val message =
                            "GPU processed recording is unavailable: encoder surface binding failed."
                        Log.e(
                            "CameraViewModel",
                            message
                        )
                        setHardwareWarning(message)
                        cleanupFailedRecordingStart(
                            message = message,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }
                    pushVulkanPreviewRenderConfig(force = true)
                    logRecordingPipelineParityPlan(
                        actualInternalParityPlan,
                        reason = "internal-hardware-import",
                        force = true
                    )
                    updatePreviewViewAwait { setEncoderFeedEnabled(true) }
                    gpuRecording.start()
                    if (!awaitGpuRecordingState()) {
                        val message = (gpuRecordState.value as? GpuRecordingState.Error)?.message
                            ?: "Video encoder failed to enter recording state"
                        cleanupFailedRecordingStart(
                            message = message,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }
                    applyRecordingCameraLookControls()
                    vulkanRuntime.setFrameImportEnabled(true)
                    camera.setHardwareImportTargetSize(preparedEncoderSize)
                    Log.i(
                        "CameraViewModel",
                        "Recording start: rebuilding session with armed hidden hardware-import encoder feed"
                    )
                    if (!awaitFreshPreviewSessionBeforeRecording(
                            timeoutMs =
                                ProcessedGpuRecordingRequirementPolicy
                                    .INTERNAL_HARDWARE_IMPORT_SESSION_TIMEOUT_MS,
                            forceRebuild = true,
                            rebuildReaders = true,
                            waitForHardwareImportActivity = true
                        )) {
                        return retryPreviewContinuityAfterInternalHardwareImportFailure(
                            "Preview session refresh timed out before internal recording start."
                        )
                    }
                    if (!awaitValidatedProcessedGpuRecordingFrames()) {
                        val message =
                            "GPU processed recording failed: sustained imported frames did not reach the selected FPS."
                        Log.e("CameraViewModel", message)
                        return retryPreviewContinuityAfterInternalHardwareImportFailure(message)
                    }
                    updatePreviewViewAwait { resetPreviewFpsTracking() }
                    gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata(
                        encoderSize = preparedEncoderSize,
                        selection = recordingSelection,
                        scene = pipeline.sceneAnalysis.value,
                        recommendation = pipeline.captureRecommendation.value
                    )
                    logRecordingPipelineParityPlan(
                        actualInternalParityPlan,
                        reason = "internal-hardware-import-validated",
                        force = true
                    )
                    syncActivePreviewFrameRate()
                    syncPreviewPerformanceModeOnMain()
                    startRawCaptureAfterRecordingSessionReady()
                    markGpuRecordingReadyForClipTiming()
                    startRecordingForegroundService()
                    updateCinemaRuntimeStats(lastPreviewFpsSample)
                    Log.i("CameraViewModel", "Internal hardware-import recording started; visible preview unchanged")
                    return true
                }

                suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {
                    activeRecordingFeedPath = RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
                    gpuRecording.setRealtimeSurfaceCadenceEnabled(
                        com.neoproc.neoproccamx.recording.FullSensorConstantFpsPolicy.useConstantMuxCadence(
                            preparedEncoderSize.width,
                            preparedEncoderSize.height
                        )
                    )
                    val actualOffscreenParityPlan =
                        if (recordingParityPlan.feedPath ==
                            RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
                        ) {
                            recordingParityPlan
                        } else {
                            activeRecordingPipelineParityPlan(
                                actualFeedPathOverride =
                                    RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
                            )
                        }
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    val offscreenDeliveryMode =
                        RecordingHybridRoutePolicy.resolveOffscreenDeliveryMode(
                            deliveryKind = actualOffscreenParityPlan.deliveryKind,
                            effectiveHdrMode = actualOffscreenParityPlan.encoderHdrMode
                    )
                    val p010SourceSize = preparedEncoderSize.normalizeForOrientation()
                    if (!camera.supportsHardwareImportP010Source(p010SourceSize, recording.fps)) {
                        val message =
                            "Offscreen EGL P010 hardware-import source is unavailable at " +
                                "${preparedEncoderSize.width}x${preparedEncoderSize.height}@${recording.fps}fps."
                        Log.e("CameraViewModel", message)
                        return rejectOffscreenAhbEglRecordingNoDowngrade(message)
                    }
                    if (offscreenSourceAlreadyInSession) {
                        Log.i(
                            "CameraViewModel",
                            "Recording start: pre-armed offscreen AHB EGL P010 source exists; " +
                                "strict start will verify live hardware-import attachment"
                        )
                    }
                    camera.setRecordSurface(null, refreshSession = false)
                    prearmedRecordingSurface = null
                    prearmedRecordingSurfaceAttachedToSession = offscreenSourceAlreadyInSession
                    prearmedRecordingSignature = null
                    prearmedRecordingFeedPath = null
                    camera.setRecordingEncoderTargetSize(preparedEncoderSize)
                    camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
                    camera.setHardwareImportTargetSize(preparedEncoderSize)
                    camera.setInternalRecordingHardwareImportEnabled(true)
                    camera.setHardwareImportP010SourceEnabled(true)
                    pipeline.setDedicatedHardwareImportEnabled(true)
                    attemptedCameraVideoStart = true
                    camera.startVideoCapture()
                    vulkanRuntime.setFrameImportEnabled(false)
                    vulkanRuntime.beginPreviewImportSession(
                        preserveActiveOffscreenImport = offscreenSourceAlreadyInSession
                    )
                    pushVulkanPreviewRenderConfig(force = true)
                    logRecordingPipelineParityPlan(
                        actualOffscreenParityPlan,
                        reason = "offscreen-ahb-egl-encoder",
                        force = true
                    )
                    updatePreviewViewAwait { setEncoderFeedEnabled(false) }
                    val encoderBound = vulkanRuntime.setOffscreenAhbEglEncoderSurface(
                        surface = encoderSurface,
                        width = preparedEncoderSize.width,
                        height = preparedEncoderSize.height,
                        preferredDeliveryMode = offscreenDeliveryMode,
                        outputMirrorPlan = camera.recordOutputMirrorPlan("record-start-offscreen"),
                        inputDynamicRangeProfile = camera.describeHdrDynamicRangeState().previewProfile
                    )
                    if (!encoderBound) {
                        val message =
                            "Offscreen EGL did not prove a recordable 10-bit ${offscreenDeliveryMode.name} encoder surface."
                        runCatching {
                            NeoLogger.critical(
                                    "CameraViewModel",
                                    "$message Strict no-downgrade start aborting. encoderSize=" +
                                        "${preparedEncoderSize.width}x${preparedEncoderSize.height} " +
                                        "lastEncoderStatus=${vulkanRuntime.currentEncoderSurfaceStatus}"
                            )
                        }
                        return rejectOffscreenAhbEglRecordingNoDowngrade(message)
                    }
                    gpuRecording.start()
                    if (!awaitGpuRecordingState()) {
                        val message = (gpuRecordState.value as? GpuRecordingState.Error)?.message
                            ?: "Video encoder failed to enter recording state"
                        cleanupFailedRecordingStart(
                            message = message,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            // #3 fix: startVideoCapture() already ran above (attemptedCameraVideoStart=true), so a
                            // gpuRecording state failure MUST stop the camera video too — otherwise recordingActive
                            // stays true / state stays Recording forever and every later startRecording is rejected
                            // (and the preview watchdog no-ops while "recording"). Matches every sibling path.
                            stopCameraVideo = attemptedCameraVideoStart
                        )
                        return false
                    }
                    applyRecordingCameraLookControls()
                    vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(true)
                    vulkanRuntime.setFrameImportEnabled(true)
                    camera.setHardwareImportTargetSize(preparedEncoderSize)
                    camera.setHardwareImportP010SourceEnabled(true)
                    if (offscreenSourceAlreadyInSession) {
                        if (awaitPrearmedOffscreenHardwareImportActivity()) {
                            Log.i(
                                "CameraViewModel",
                                "Recording start: using pre-armed offscreen AHB EGL P010 source; " +
                                    "Camera2 session rebuild skipped"
                            )
                        } else {
                            prearmedRecordingSurfaceAttachedToSession = false
                            Log.w(
                                "CameraViewModel",
                                "Recording start: pre-armed offscreen AHB EGL P010 source was stale; " +
                                    "rebuilding Camera2 source before strict validation"
                            )
                            if (!awaitFreshPreviewSessionBeforeRecording(
                                    timeoutMs =
                                        ProcessedGpuRecordingRequirementPolicy
                                            .INTERNAL_HARDWARE_IMPORT_SESSION_TIMEOUT_MS,
                                    forceRebuild = true,
                                    rebuildReaders = true,
                                    waitForHardwareImportActivity = true
                                )) {
                                return rejectOffscreenAhbEglRecordingNoDowngrade(
                                    "Preview session refresh timed out while reproving pre-armed offscreen EGL P010 source."
                                )
                            }
                        }
                    } else {
                        prearmedRecordingSurfaceAttachedToSession = false
                        Log.i(
                            "CameraViewModel",
                            "Recording start: rebuilding session with offscreen AHB EGL P010 source for strict proof"
                        )
                        if (!awaitFreshPreviewSessionBeforeRecording(
                                timeoutMs =
                                    ProcessedGpuRecordingRequirementPolicy
                                        .INTERNAL_HARDWARE_IMPORT_SESSION_TIMEOUT_MS,
                                forceRebuild = true,
                                rebuildReaders = true,
                                waitForHardwareImportActivity = true
                            )) {
                            return rejectOffscreenAhbEglRecordingNoDowngrade(
                                "Preview session refresh timed out before offscreen EGL recording start."
                            )
                        }
                    }
                    if (!awaitCameraRecordingState()) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "Camera session failed to switch into offscreen P010 recording request mode."
                        )
                    }
                    if (
                        offscreenDeliveryMode == OffscreenEglDeliveryMode.PQ &&
                        !NdrInputTransportPolicy.isPqProfile(
                            camera.describeHdrDynamicRangeState().encoderProfile
                        )
                    ) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "NDR recording requires an applied BT.2100 PQ 10-bit Camera2 profile; " +
                                "HLG and STANDARD transport were rejected."
                        )
                    }
                    if (!awaitFirstProcessedGpuRecordingFrame(requireOffscreenSourceProof = true)) {
                        val offscreenStatus = vulkanRuntime.currentEncoderSurfaceStatus
                        if (!offscreenStatus.sourcePrecisionProven) {
                            val message =
                                "Offscreen EGL source precision was rejected or could not be proven."
                            Log.e(
                                "CameraViewModel",
                                "$message sourceFormat=${offscreenStatus.sourceFormat} " +
                                    "sourceBitDepth=${offscreenStatus.sourceBitDepth} " +
                                    "sourcePrecision=${offscreenStatus.sourcePrecision}"
                            )
                            return rejectOffscreenAhbEglRecordingNoDowngrade(message)
                        }
                        val message =
                            "GPU processed recording failed: offscreen EGL submitted frames did not reach the selected FPS."
                        Log.e("CameraViewModel", message)
                        return rejectOffscreenAhbEglRecordingNoDowngrade(message)
                    }
                    updatePreviewViewAwait { resetPreviewFpsTracking() }
                    gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata(
                        encoderSize = preparedEncoderSize,
                        selection = recordingSelection,
                        scene = pipeline.sceneAnalysis.value,
                        recommendation = pipeline.captureRecommendation.value
                    )
                    logRecordingPipelineParityPlan(
                        actualOffscreenParityPlan,
                        reason = "offscreen-ahb-egl-encoder-first-frame",
                        force = true
                    )
                    syncActivePreviewFrameRate()
                    syncPreviewPerformanceModeOnMain()
                    markGpuRecordingReadyForClipTiming()
                    startRawCaptureAfterRecordingSessionReady()
                    startRecordingForegroundService()
                    launchStrictProcessedGpuSustainedValidation(
                        expectedFeedPath = RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
                    )
                    updateCinemaRuntimeStats(lastPreviewFpsSample)
                    Log.i("CameraViewModel", "Offscreen AHB EGL encoder recording started; visible preview unchanged")
                    return true
                }

                suspend fun startByteBufferP010Recording(): Boolean {
                    activeRecordingFeedPath = RecordingFeedPath.BYTEBUFFER_P010_ENCODER
                    gpuRecording.setRealtimeSurfaceCadenceEnabled(false)
                    val actualByteBufferParityPlan =
                        if (recordingParityPlan.feedPath == RecordingFeedPath.BYTEBUFFER_P010_ENCODER) {
                            recordingParityPlan
                        } else {
                            activeRecordingPipelineParityPlan(
                                actualFeedPathOverride = RecordingFeedPath.BYTEBUFFER_P010_ENCODER
                            )
                        }
                    clearInternalRecordingFeed()
                    camera.setNpv10TenBitCaptureEnabled(true)
                    val capturePlan = try {
                        camera.resolveNpv10CaptureSourcePlan(preparedEncoderSize.normalizeForOrientation())
                    } catch (t: Throwable) {
                        cleanupFailedRecordingStart(
                            message = "P010 readout is unavailable for bytebuffer recording: ${t.message}",
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }
                    if (!capturePlan.nativeTenBitCapture) {
                        cleanupFailedRecordingStart(
                            message = "P010 bytebuffer recording requires native YCBCR_P010 readout.",
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }
                    camera.setRecordSurface(null, refreshSession = false)
                    camera.setRecordingEncoderTargetSize(preparedEncoderSize)
                    camera.setRecordingReadoutOverrideSize(null)
                    camera.setHardwareImportTargetSize(null)
                    camera.setInternalRecordingHardwareImportEnabled(false)
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    camera.setNpv10CaptureTargetSize(capturePlan.size)
                    prearmedRecordingSurface = null
                    prearmedRecordingSurfaceAttachedToSession = false
                    prearmedRecordingSignature = null
                    prearmedRecordingFeedPath = null
                    logRecordingPipelineParityPlan(
                        actualByteBufferParityPlan,
                        reason = "bytebuffer-p010-encoder",
                        force = true
                    )

                    gpuRecording.start()
                    if (!awaitGpuRecordingState()) {
                        val message = (gpuRecordState.value as? GpuRecordingState.Error)?.message
                            ?: "P010 bytebuffer encoder failed to enter recording state"
                        cleanupFailedRecordingStart(
                            message = message,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = false
                        )
                        return false
                    }
                    pipeline.setRawYuvImageSink { image, _ ->
                        gpuRecording.queueExternalVideoImage(image)
                    }
                    applyRecordingCriticalRuntimeHints(
                        recordingActive = true,
                        previewFps = recording.fps.coerceAtLeast(1).toFloat()
                    )
                    applyRecordingCameraLookControls()
                    attemptedCameraVideoStart = true
                    camera.startVideoCapture()
                    if (!awaitFreshPreviewSessionBeforeRecording(
                            forceRebuild = true,
                            rebuildReaders = true
                        )
                    ) {
                        pipeline.setRawYuvImageSink(null)
                        cleanupFailedRecordingStart(
                            message = "Preview session refresh timed out before P010 bytebuffer recording start",
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = true
                        )
                        return false
                    }
                    if (!awaitCameraRecordingState()) {
                        pipeline.setRawYuvImageSink(null)
                        cleanupFailedRecordingStart(
                            message = "Camera session failed to switch into P010 readout recording mode",
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = true
                        )
                        return false
                    }
                    syncActivePreviewFrameRate()
                    syncPreviewPerformanceModeOnMain()
                    startRawCaptureAfterRecordingSessionReady()
                    markGpuRecordingReadyForClipTiming()
                    startRecordingForegroundService()
                    updateCinemaRuntimeStats(lastPreviewFpsSample)
                    Log.i(
                        "CameraViewModel",
                        "P010 bytebuffer recording started readout=${capturePlan.size.width}x${capturePlan.size.height} " +
                            "sourceFormat=${capturePlan.sourceFormat} bitDepth=${capturePlan.sourceBitDepth}"
                    )
                    return true
                }

                val recordingFeedPath = recordingParityPlan.feedPath
                val recordingStarted = when (recordingFeedPath) {
                    RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ->
                        startInternalHardwareImportRecording()
                    RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER -> {
                        startOffscreenAhbEglEncoderRecording()
                    }
                    RecordingFeedPath.BYTEBUFFER_P010_ENCODER ->
                        startByteBufferP010Recording()
                    RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE ->
                        startCamera2PreviewParityRecording()
                    RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE ->
                        startCamera2DirectRecording()
                }
                if (!recordingStarted) return@launch
                recordingSessionStarted = true
            } catch (e: CancellationException) {
                if (!recordingStartCancellationRequested.get()) throw e
                withContext(NonCancellable) {
                    when (gpuRecording.state.value) {
                        GpuRecordingState.Recording,
                        GpuRecordingState.Paused -> runCatching { gpuRecording.stop() }
                        else -> gpuRecording.discardPreparedSession()
                    }
                    cleanupFailedRecordingStart(
                        message = "Recording start cancelled",
                        rawVideoEnabled = rawVideoEnabled,
                        refreshSession = true,
                        stopCameraVideo = attemptedCameraVideoStart,
                        reportError = false
                    )
                }
                Log.i("CameraViewModel", "Recording start cancelled by shutter stop request")
            } catch (e: Exception) {
                handleCameraError(
                    throwable = e,
                    operation = "startRecording",
                    userVisibleMessage = "Video recording failed due to a camera hardware limit."
                )
                cleanupFailedRecordingStart(
                    message = "Video recording failed: ${e.message ?: "unexpected error"}",
                    rawVideoEnabled = rawVideoEnabled,
                    refreshSession = true,
                    stopCameraVideo = attemptedCameraVideoStart
                )
            } finally {
                recordingStartJob = null
                if (recordingRuntimeHintActive && !recordingSessionStarted) {
                    recordingStartInProgress.set(false)
                    applyRecordingCriticalRuntimeHints(recordingActive = false)
                    applyAiTrackingPreferences()
                } else {
                    recordingStartInProgress.set(false)
                }
                recordingTransitionState.value = GpuRecordingState.Idle
                recordingStartCancellationRequested.set(false)
                RuntimeStageTelemetry.recordDuration(
                    tag = "CameraViewModel",
                    stage = "startRecording.total",
                    durationMs = SystemClock.elapsedRealtime() - recordingStartElapsedMs,
                    slowThresholdMs = SLOW_RECORDING_START_TOTAL_MS
                )
            }
        }
    }

    private fun clearStaleAutofocusRegionForRecordingStart() {
        if (focusExposureLockState != null) return
        if (SystemClock.elapsedRealtime() < tapToFocusHoldUntilElapsedMs) return
        if (cameraParams.value.focusDist >= 0f) return
        manualFocusOverride.value = null
        activePeakingRegion.value = null
        previewViewRef?.clearPeakingFocusRegion()
        pipeline.clearPeakingFocusRegion()
        camera.clearFocusRegion()
        camera.setAutoFocus()
    }

    fun pauseRecording() {
        if (recordingController.pauseRecording()) {
            pauseRecordingInternal()
        }
    }

    private fun pauseRecordingInternal() {
        viewModelScope.launch(Dispatchers.IO) {
            when {
                rawCustomRecordingState.value is GpuRecordingState.Recording -> {
                    if (rawVideoManager.pauseCapture()) {
                        rawCustomRecordingAccumulatedMs +=
                            (SystemClock.elapsedRealtime() - rawCustomRecordingStartedElapsedMs)
                                .coerceAtLeast(0L)
                        rawCustomRecordingDuration.value = rawCustomRecordingAccumulatedMs
                        rawCustomRecordingState.value = GpuRecordingState.Paused
                    }
                }
                npv10Recording.state.value is GpuRecordingState.Recording -> {
                    npv10Recording.pause()
                }
                gpuRecording.state.value is GpuRecordingState.Recording -> {
                    gpuRecording.pause()
                }
                else -> {
                    Log.d("CameraViewModel", "pauseRecording ignored: state=${gpuRecordState.value}")
                }
            }
            updateCinemaRuntimeStats(lastPreviewFpsSample)
        }
    }

    fun resumeRecording() {
        if (recordingController.resumeRecording()) {
            resumeRecordingInternal()
        }
    }

    private fun resumeRecordingInternal() {
        viewModelScope.launch(Dispatchers.IO) {
            when {
                rawCustomRecordingState.value is GpuRecordingState.Paused -> {
                    if (rawVideoManager.resumeCapture()) {
                        rawCustomRecordingStartedElapsedMs = SystemClock.elapsedRealtime()
                        rawCustomRecordingState.value = GpuRecordingState.Recording
                    }
                }
                npv10Recording.state.value is GpuRecordingState.Paused -> {
                    npv10Recording.resume()
                }
                gpuRecording.state.value is GpuRecordingState.Paused -> {
                    gpuRecording.resume()
                }
                else -> {
                    Log.d("CameraViewModel", "resumeRecording ignored: state=${gpuRecordState.value}")
                }
            }
            updateCinemaRuntimeStats(lastPreviewFpsSample)
        }
    }

    fun stopRecording() {
        if (recordingStartInProgress.get()) {
            if (recordingStartCancellationRequested.compareAndSet(false, true)) {
                recordingTransitionState.value = GpuRecordingState.Stopping
                recordingStartJob?.cancel(CancellationException("Shutter requested stop during startup"))
                Log.i("CameraViewModel", "Shutter stop accepted during recording startup")
            }
            return
        }
        val backendActive = rawCustomRecordingState.value is GpuRecordingState.Recording ||
            npv10Recording.state.value is GpuRecordingState.Recording ||
            npv10Recording.hasActiveSession() ||
            gpuRecording.state.value is GpuRecordingState.Recording ||
            rawCustomRecordingState.value is GpuRecordingState.Paused ||
            npv10Recording.state.value is GpuRecordingState.Paused || gpuRecording.state.value is GpuRecordingState.Paused
        if ((!recordingController.stopRecording() && !backendActive) ||
            !recordingStopInProgress.compareAndSet(false, true)) return
        recordingTransitionState.value = GpuRecordingState.Stopping
        stopRecordingInternal()
    }

    private fun rebindVisiblePreviewTargetBeforeRecordingStopRetarget(
        keepPrearmedRecordSurface: Boolean
    ) {
        val surface = previewSurface
        val surfaceAvailable = surface != null &&
            runCatching { surface.isValid }.getOrDefault(false)
        if (!RecordingStopPreviewPolicy.shouldRebindVisiblePreviewTarget(
                keepPrearmedRecordSurface = keepPrearmedRecordSurface,
                previewSurfaceAvailable = surfaceAvailable,
                nativeVisiblePreviewActive = nativeVisiblePreviewActive
            )
        ) {
            return
        }
        camera.setPreviewSurface(surface)
        Log.i(
            "CameraViewModel",
            "Recording stop: rebound visible preview surface before Camera2 request retarget"
        )
    }

    private fun stopRecordingInternal() {
        if (rawCustomRecordingState.value is GpuRecordingState.Recording ||
            rawCustomRecordingState.value is GpuRecordingState.Paused ||
            rawCustomRecordingState.value is GpuRecordingState.Stopping
        ) {
            recordingReadyForClipTiming = false
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    runCatching { stopCustomRawRecordingSession() }.onFailure { throwable ->
                        rawCustomRecordingState.value = GpuRecordingState.Error(
                            throwable.message ?: "Custom RAW stop failed"
                        )
                        handleCameraError(
                            throwable = throwable,
                            operation = "stopCustomRawRecording",
                            userVisibleMessage = "Custom RAW finalisation failed."
                        )
                    }
                } finally {
                    recordingStopInProgress.set(false)
                    recordingTransitionState.value = GpuRecordingState.Idle
                }
            }
            return
        }
        if (npv10Recording.hasActiveSession() ||
            npv10Recording.state.value is GpuRecordingState.Recording ||
            npv10Recording.state.value is GpuRecordingState.Paused ||
            npv10Recording.state.value is GpuRecordingState.Stopping
        ) {
            recordingReadyForClipTiming = false
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    stopNpv10RecordingSession()
                } finally {
                    recordingStopInProgress.set(false)
                    recordingTransitionState.value = GpuRecordingState.Idle
                }
            }
            return
        }
        if (gpuRecording.state.value !is GpuRecordingState.Recording &&
            gpuRecording.state.value !is GpuRecordingState.Paused
        ) {
            recordingStopInProgress.set(false)
            recordingTransitionState.value = GpuRecordingState.Idle
            return
        }
        recordingReadyForClipTiming = false
        viewModelScope.launch(Dispatchers.IO) {
            var rawVideoPath: String? = null
            var detachedRecordSurfaceBeforeEncoderStop = false
            val recordingStopElapsedMs = SystemClock.elapsedRealtime()
            val feedPath = activeRecordingFeedPath
            try {
                strictProcessedGpuValidationJob?.cancel()
                strictProcessedGpuValidationJob = null
                gpuRecording.markStopRequestedForEvidence()
                if (feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
                    Log.i("CameraViewModel", "Recording stop: keeping offscreen submission active until hardware-import tail drains")
                }
                freezeCamera2FrameLineageSession()
                val rawCaptureStopResult = stopRawCaptureIfNeeded()
                rawVideoPath = rawCaptureStopResult.path
                when (val outcome = rawCaptureStopResult.outcome) {
                    RecordingOperationOutcome.Success -> Unit
                    is RecordingOperationOutcome.Failure -> {
                        handleCameraError(
                            throwable = outcome.cause ?: Exception(outcome.reason),
                            operation = outcome.operation,
                            userVisibleMessage = "RAW capture stop encountered a hardware limit."
                        )
                    }
                }
                if (feedPath == RecordingFeedPath.BYTEBUFFER_P010_ENCODER) {
                    pipeline.setRawYuvImageSink(null)
                    when (val stopCameraResult = stopCameraVideoCaptureSafely()) {
                        RecordingOperationOutcome.Success -> Unit
                        is RecordingOperationOutcome.Failure -> {
                            handleCameraError(
                                throwable = stopCameraResult.cause ?: Exception(stopCameraResult.reason),
                                operation = stopCameraResult.operation,
                                userVisibleMessage = "P010 readout stop hit a camera limit; cleanup is continuing."
                            )
                        }
                    }
                } else if (feedPath == RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE) {
                    when (val stopCameraResult = stopCameraVideoCaptureSafely()) {
                        RecordingOperationOutcome.Success -> Unit
                        is RecordingOperationOutcome.Failure -> {
                            handleCameraError(
                                throwable = stopCameraResult.cause ?: Exception(stopCameraResult.reason),
                                operation = stopCameraResult.operation,
                                userVisibleMessage = "Camera stop path hit a hardware limit; cleanup is continuing."
                            )
                        }
                    }
                }
                val detachBeforeStopResult = if (feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER) {
                    detachInternalRecordingFeedBeforeEncoderStop()
                } else if (feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
                    detachInternalRecordingFeedBeforeEncoderStop(
                        keepOffscreenEncoderSurfaceForEos = true
                    )
                } else if (feedPath == RecordingFeedPath.BYTEBUFFER_P010_ENCODER) {
                    RecordingOperationOutcome.Success
                } else {
                    detachRecordingSurfaceBeforeEncoderStop()
                }
                when (detachBeforeStopResult) {
                    RecordingOperationOutcome.Success -> {
                        detachedRecordSurfaceBeforeEncoderStop = true
                    }
                    is RecordingOperationOutcome.Failure -> {
                        handleCameraError(
                            throwable = detachBeforeStopResult.cause ?: Exception(detachBeforeStopResult.reason),
                            operation = detachBeforeStopResult.operation,
                            userVisibleMessage = "Camera surface detach failed before encoder stop; cleanup is continuing."
                        )
                    }
                }
                if (feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
                    when (val stopCameraResult = stopCameraVideoCaptureSafely()) {
                        RecordingOperationOutcome.Success -> Unit
                        is RecordingOperationOutcome.Failure -> {
                            handleCameraError(
                                throwable = stopCameraResult.cause ?: Exception(stopCameraResult.reason),
                                operation = stopCameraResult.operation,
                                userVisibleMessage = "Offscreen recording request stop hit a hardware limit; cleanup is continuing."
                            )
                        }
                    }
                }
                val path = gpuRecording.stop()
                Log.i("CameraViewModel", "GPU recording stopped -> $path")
                val developMasterFile = gpuRecording.lastDevelopMasterFile
                val savedDevelopMasterFile =
                    developMasterFile?.takeIf { file -> file.isFile && file.length() > 0L }
                if (savedDevelopMasterFile != null) {
                    runCatching {
                        val facts = camera.captureColorFactsSnapshot()
                        val lux = sceneAnalysis.value?.estimatedLux ?: 0f
                        // Read the FROZEN recorded colour truth (the same fields that chose the SDR/NDR dir +
                        // container tags), NOT the live reactive UI state — otherwise dir/tags/sidecar can diverge
                        // (e.g. NDR resolves videoColorSpace="Rec.2020" while the UI flow stays "Rec.709").
                        val meta = com.neoproc.neoproccamx.recording.RecordDevelopSidecarFactory.build(
                            width = gpuRecording.videoWidth,
                            height = gpuRecording.videoHeight,
                            fps = gpuRecording.videoFps,
                            storageBitDepth = if (gpuRecording.tenBitEnabled) 10 else 8,
                            colorSpaceLabel = gpuRecording.videoColorSpace,
                            hdrModeLabel = gpuRecording.videoHdrMode,
                            colorProfile = gpuRecording.videoColorProfile,
                            detailProfile = gpuRecording.videoDetailProfile.name,
                            wbR = facts?.wbR ?: 1f,
                            wbGr = facts?.wbGr ?: 1f,
                            wbGb = facts?.wbGb ?: 1f,
                            wbB = facts?.wbB ?: 1f,
                            kelvin = facts?.kelvin,
                            tint = facts?.tint,
                            estimatedLux = lux,
                            // #4 fix: the flat intermediate holds the raw camera codes, so decode them offline
                            // with the SAME transfer the live tone operator used (NdrPqDecodeFlag) — PQ on a
                            // PQ-capturing device — instead of hard-coding HLG (which decoded PQ-as-HLG and
                            // shipped a hue-bent/highlight-crushed NDR master). Reversible via debug.ndr_pq_decode.
                            captureTransferIsPq = com.neoproc.neoproccamx.rendering.NdrPqDecodeFlag.enabled,
                            orientationHintDegrees = gpuRecording.orientationHintDegrees
                        )
                        com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.write(savedDevelopMasterFile, meta)
                        Log.i("CameraViewModel", "record-develop sidecar written -> ${savedDevelopMasterFile.name}")
                    }.onFailure { t ->
                        Log.w("CameraViewModel", "record-develop sidecar write failed", t)
                    }
                }
                val savedPath = path ?: savedDevelopMasterFile?.absolutePath
                if (savedPath != null) {
                    _recordingSavedPath.tryEmit(savedPath)
                    val consumedCustomName = gpuRecording.pendingCustomRecordingName
                    gpuRecording.pendingCustomRecordingName = null
                    if (consumedCustomName != null) {
                        runOnMain {
                            val currentPending = com.neoproc.neoproccamx.recording.RecordingFileNamePolicy
                                .sanitizeCustomName(prefs.customRecordingName)
                                .ifEmpty { null }
                            if (currentPending == consumedCustomName) {
                                prefs.customRecordingNameEnabled = false
                                prefs.customRecordingName = ""
                            }
                        }
                    }
                }
                if (rawVideoPath != null) {
                    Log.i("CameraViewModel", "RAW video sequence saved -> $rawVideoPath")
                }
            } catch (e: Exception) {
                handleCameraError(
                    throwable = e,
                    operation = "stopRecording",
                    userVisibleMessage = "Video stop failed because a camera hardware limit was reached."
                )
                if (gpuRecordState.value !is GpuRecordingState.Error) {
                    gpuRecording.reportExternalError("Stopping video failed: ${e.message ?: "unexpected error"}")
                }
            } finally {
                try {
                    stopRecordingForegroundService()
                    updatePreviewViewAwait {
                        setEncoderFeedEnabled(false)
                        setEncoderSurface(null)
                    }
                    vulkanRuntime.setEncoderSurface(null)
                    vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
                    pipeline.setRawYuvImageSink(null)
                    pipeline.setRawYuvFrameSink(null)
                    if (!detachedRecordSurfaceBeforeEncoderStop) {
                        when (val detachSurfaceResult = detachRecordingSurfaceSafely()) {
                            RecordingOperationOutcome.Success -> Unit
                            is RecordingOperationOutcome.Failure -> {
                                handleCameraError(
                                    throwable = detachSurfaceResult.cause ?: Exception(detachSurfaceResult.reason),
                                    operation = detachSurfaceResult.operation,
                                    userVisibleMessage = "Camera surface detach failed during stop cleanup."
                                )
                            }
                        }
                    }
                    val keepPrearmedSessionSurface =
                        prearmedRecordingSurface != null && prearmedRecordingSurfaceAttachedToSession
                    runOnMain { applyRecordingOutputAspectOverride(null) }
                    if (keepPrearmedSessionSurface) {
                        prearmedRecordingSignature?.let { signature ->
                            val encoderSize = Size(signature.width, signature.height)
                            camera.setRecordingEncoderTargetSize(encoderSize)
                            camera.setAnalysisTargetSize(preferredRecordingAnalysisSize(encoderSize))
                        }
                    } else {
                        clearRecordingReadoutOverride()
                        resetPreviewPerformanceState()
                        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
                    }
                    runOnMain { syncGeometryTargets() }
                    camera.setInternalRecordingHardwareImportEnabled(false)
                    camera.setHardwareImportP010SourceEnabled(false)
                    rebindVisiblePreviewTargetBeforeRecordingStopRetarget(
                        keepPrearmedRecordSurface = keepPrearmedSessionSurface
                    )
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    camera.setHardwareImportTargetSize(null)
                    camera.setNpv10TenBitCaptureEnabled(false)
                    if (keepPrearmedSessionSurface) {
                        Log.i(
                            "CameraViewModel",
                            "Recording stop: skipping Camera2 session rebuild because pre-armed encoder surface remains attached"
                        )
                    } else if (
                        RecordStopSingleSessionRebuildFlag.enabled &&
                        feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
                    ) {
                        Log.i(
                            "CameraViewModel",
                            "Recording stop: single-rebuild mode — deferring the Camera2 session rebuild to the trailing preview restore"
                        )
                    } else {
                        when (val refreshResult = refreshRecordingOutputsSafely()) {
                            RecordingOperationOutcome.Success -> Unit
                            is RecordingOperationOutcome.Failure -> {
                                handleCameraError(
                                    throwable = refreshResult.cause ?: Exception(refreshResult.reason),
                                    operation = refreshResult.operation,
                                    userVisibleMessage = "Preview session restore failed after stopping recording."
                                )
                            }
                        }
                    }
                    runOnMain {
                        markGlPreviewOwnerCooldown()
                        refreshPreviewPipelineIfNeeded(forceSessionRefresh = !keepPrearmedSessionSurface)
                    }
                    syncCameraPreviewLookControls()
                    releaseCinemaAutoLock(resetCameraState = true, reason = "recording_stop")
                    syncActivePreviewFrameRate()
                    camera.setRawVideoCaptureEnabled(false)
                    gpuRecording.setVideoInputMode(RecordingVideoInputMode.SURFACE)
                    gpuRecording.setRealtimeSurfaceCadenceEnabled(false)
                    activeRecordingFeedPath = null
                    // Keep the RAW callbacks installed if we're still in RAW preview (seamless
                    // record -> preview-debayer handoff); restore only when leaving RAW.
                    reconcileRawVideoCallbacks()
                    if (rawVideoPath != null) {
                        viewModelScope.launch(Dispatchers.Default) {
                            val rawDrainStartElapsedMs = SystemClock.elapsedRealtime()
                            delay(250L)
                            when (val drainResult = drainRawReaderSafely()) {
                                RecordingOperationOutcome.Success -> Unit
                                is RecordingOperationOutcome.Failure -> {
                                    handleCameraError(
                                        throwable = drainResult.cause ?: Exception(drainResult.reason),
                                        operation = drainResult.operation,
                                        userVisibleMessage = "RAW buffer drain failed after recording stop."
                                    )
                                }
                            }
                            RuntimeStageTelemetry.recordDuration(
                                tag = "CameraViewModel",
                                stage = "stopRecording.rawDrain",
                                durationMs = SystemClock.elapsedRealtime() - rawDrainStartElapsedMs,
                                slowThresholdMs = 300L
                            )
                        }
                    }
                    scheduleCenterAutoPrime()
                    applyRecordingCriticalRuntimeHints(recordingActive = false)
                    syncPreviewPerformanceModeOnMain()
                    updateCinemaRuntimeStats(lastPreviewFpsSample)
                } finally {
                    if (closeCameraAfterRecordingStop) {
                        runOnMain { closeCamera() }
                    }
                    recordingStopInProgress.set(false)
                    recordingTransitionState.value = GpuRecordingState.Idle
                    RuntimeStageTelemetry.recordDuration(
                        tag = "CameraViewModel",
                        stage = "stopRecording.total",
                        durationMs = SystemClock.elapsedRealtime() - recordingStopElapsedMs,
                        slowThresholdMs = SLOW_RECORDING_STOP_TOTAL_MS
                    )
                }
            }
        }
    }
    /** Strict processed recording uses the custom lossless packed-10 stream, never MediaCodec. */
    private fun shouldUseUncompressedCinemaPipeline(): Boolean =
        _uiMode.value == AppMode.VIDEO &&
            _cinemaVideoMode.value != CinemaVideoMode.RAW &&
            _uncompressedCinemaPipeline.value
    private fun shouldUseCustomRawRecordingPipeline(): Boolean =
        _uiMode.value == AppMode.VIDEO &&
            _cinemaVideoMode.value == CinemaVideoMode.RAW &&
            _rawOn.value
    private suspend fun startCustomRawRecordingSession(recordingSize: Size): Boolean {
        var rawCaptureStarted = false
        var cameraRecordingStarted = false
        rawCustomRecordingState.value = GpuRecordingState.Ready
        return try {
            if (prearmedRecordingSurface != null || gpuRecording.state.value is GpuRecordingState.Ready) {
                releasePrearmedRecordingSurface(refreshSession = false)
            }
            require(camera.supportsRaw) { "The selected camera does not expose RAW_SENSOR video data" }
            val normalizedSize = recordingSize.normalizeForOrientation()
            val requestedFps = resolveContractRequestedFps(recording.fps).coerceAtLeast(1)
            applyPreRecordingPerformanceCaps(normalizedSize)
            applyRecordingCriticalRuntimeHints(
                recordingActive = true,
                previewFps = requestedFps.toFloat()
            )
            installRawVideoCallbacks()
            // Stop idle debayer retention; acquire-and-close leaves the full reader budget to RAW packets.
            rawVideoManager.rawPreviewProcessor = null
            rawVideoManager.compressionMode = RawCompressionMode.LOSSLESS_REALTIME
            camera.setNpv10TenBitCaptureEnabled(false)
            camera.setRecordSurface(null, refreshSession = false)
            camera.setRecordingEncoderTargetSize(null)
            camera.setHardwareImportTargetSize(null)
            camera.setInternalRecordingHardwareImportEnabled(false)
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            camera.setAnalysisTargetSize(preferredRecordingAnalysisSize(normalizedSize))
            camera.setFPS(requestedFps)
            val rawTier = resolveRawCaptureTier(forceRefresh = true)
            val rawReadoutSize = rawTier.rawSize?.let { Size(it.width, it.height) }
                ?: camera.fullSensorSize()
            camera.setRecordingReadoutOverrideSize(rawReadoutSize)
            camera.setRecordingSensorPixelModeMaximumResolution(rawTier.engageMaxResPixelMode)
            rawVideoManager.sensorPixelModeMaximumResolutionUsed = rawTier.engageMaxResPixelMode
            rawVideoManager.orientationHintDegrees = camera.videoOrientationHint()
            camera.setRawVideoCaptureEnabled(true)
            // Enter recording before rebuilding so RawSensorReaderDepthPolicy allocates sustained depth.
            camera.startVideoCapture()
            cameraRecordingStarted = true
            if (!awaitFreshPreviewSessionBeforeRecording(
                    timeoutMs = 20_000L,
                    forceRebuild = true,
                    rebuildReaders = true
                )
            ) {
                error("RAW Camera2 session did not configure")
            }
            // Preallocation starts here; packet packing and pwrite remain on RawVideoManager workers.
            rawVideoManager.startCapture()
            rawCaptureStarted = true
            if (!awaitCameraRecordingState()) {
                error("Camera did not enter custom RAW recording state")
            }
            if (!awaitFirstCustomRawPacket()) {
                error("Custom RAW recorder did not persist a camera packet")
            }
            rawCustomRecordingAccumulatedMs = 0L
            rawCustomRecordingStartedElapsedMs = SystemClock.elapsedRealtime()
            rawCustomRecordingDuration.value = 0L
            rawCustomRecordingState.value = GpuRecordingState.Recording
            startRawCustomDurationUpdates()
            recordingReadyForClipTiming = true
            startRecordingForegroundService()
            syncActivePreviewFrameRate()
            syncPreviewPerformanceModeOnMain()
            updateCinemaRuntimeStats(lastPreviewFpsSample)
            Log.i(
                "CameraViewModel",
                "Custom RAW packet recording started readout=${rawReadoutSize.width}x${rawReadoutSize.height} " +
                    "fps=$requestedFps codec=NPRAW_DIRECT_16_BIT_LOSSLESS mediaCodec=false " +
                    "nativeDepthPreserved=true maxResolutionPixelMode=${rawTier.engageMaxResPixelMode}"
            )
            true
        } catch (t: Throwable) {
            val cancelledByShutter =
                t is CancellationException && recordingStartCancellationRequested.get()
            rawCustomDurationJob?.cancel()
            rawCustomDurationJob = null
            if (cameraRecordingStarted) runCatching { camera.stopVideoCapture() }
            if (rawCaptureStarted) runCatching { rawVideoManager.stopCapture() }
            camera.setRawVideoCaptureEnabled(false)
            camera.setRecordingSensorPixelModeMaximumResolution(false)
            camera.setRecordingReadoutOverrideSize(null)
            if (rawVideoCallbacksNeeded()) enableIdleRawPreviewProcessor()
            stopRecordingForegroundService()
            rawCustomRecordingState.value = if (cancelledByShutter) {
                GpuRecordingState.Idle
            } else {
                GpuRecordingState.Error(t.message ?: "Custom RAW recording failed")
            }
            if (cancelledByShutter) {
                Log.i("CameraViewModel", "Custom RAW startup cancelled by shutter")
            } else {
                handleCameraError(
                    throwable = t,
                    operation = "startCustomRawRecording",
                    userVisibleMessage = "Custom RAW recording failed to start. No lower-bit fallback was used."
                )
            }
            false
        }
    }
    private suspend fun awaitFirstCustomRawPacket(timeoutMs: Long = 12_000L): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            if (rawVideoManager.writtenFrameCount() > 0) return true
            if (rawVideoManager.state.value is com.neoproc.neoproccamx.pipeline.RawVideoState.Error) {
                return false
            }
            delay(50L)
        }
        return false
    }
    private fun startRawCustomDurationUpdates() {
        rawCustomDurationJob?.cancel()
        rawCustomDurationJob = viewModelScope.launch(Dispatchers.Default) {
            while (rawCustomRecordingState.value is GpuRecordingState.Recording ||
                rawCustomRecordingState.value is GpuRecordingState.Paused
            ) {
                rawCustomRecordingDuration.value = rawCustomRecordingAccumulatedMs +
                    if (rawCustomRecordingState.value is GpuRecordingState.Recording) {
                        (SystemClock.elapsedRealtime() - rawCustomRecordingStartedElapsedMs)
                            .coerceAtLeast(0L)
                    } else {
                        0L
                    }
                delay(200L)
            }
        }
    }
    private suspend fun stopCustomRawRecordingSession() {
        rawCustomRecordingState.value = GpuRecordingState.Stopping
        rawCustomDurationJob?.cancel()
        rawCustomDurationJob = null
        recordingReadyForClipTiming = false
        val path = try {
            runCatching { camera.stopVideoCapture() }
            rawVideoManager.stopCapture()
        } finally {
            stopRecordingForegroundService()
            applyRecordingCriticalRuntimeHints(recordingActive = false)
            camera.setRawVideoCaptureEnabled(false)
            camera.setRecordingSensorPixelModeMaximumResolution(false)
            clearRecordingReadoutOverride()
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
            camera.setInternalRecordingHardwareImportEnabled(false)
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            camera.setHardwareImportTargetSize(null)
            if (rawVideoCallbacksNeeded()) enableIdleRawPreviewProcessor()
            refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
            syncCameraPreviewLookControls()
            syncActivePreviewFrameRate()
            syncPreviewPerformanceModeOnMain()
            updateCinemaRuntimeStats(lastPreviewFpsSample)
        }
        rawCustomRecordingDuration.value = 0L
        rawCustomRecordingAccumulatedMs = 0L
        rawCustomRecordingStartedElapsedMs = 0L
        rawCustomRecordingState.value = GpuRecordingState.Idle
        path?.let {
            _recordingSavedPath.tryEmit(it)
            Log.i("CameraViewModel", "Custom RAW packet stream saved -> $it")
        }
    }
    private suspend fun startNpv10RecordingSession(recordingSize: Size): Boolean {
        val normalizedRecordingSize = recordingSize.normalizeForOrientation()
        return try {
            if (prearmedRecordingSurface != null || gpuRecording.state.value is GpuRecordingState.Ready) {
                releasePrearmedRecordingSurface(refreshSession = false)
            }
            camera.setNpv10TenBitCaptureEnabled(true)
            camera.setRawVideoCaptureEnabled(false)
            val initialCapturePlan = camera.resolveNpv10CaptureSourcePlan(normalizedRecordingSize)
            val analysisSize = initialCapturePlan.size
            val requestedFps = recording.fps.coerceAtLeast(1)
            applyNpv10RecordingFeatureParity(analysisSize, requestedFps)
            camera.setFPS(requestedFps)
            camera.setRecordSurface(null, refreshSession = false)
            camera.setRecordingEncoderTargetSize(null)
            camera.setRecordingReadoutOverrideSize(null)
            camera.setHardwareImportTargetSize(null)
            camera.setInternalRecordingHardwareImportEnabled(false)
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            camera.setNpv10CaptureTargetSize(analysisSize)
            val activeCapturePlan = initialCapturePlan
            require(activeCapturePlan.nativeTenBitCapture) {
                "NPV10 requires native processed 10-bit P010 capture."
            }
            val activeSize = activeCapturePlan.size
            val manualColorPlan = currentManualVideoColorSelectionPlan().also {
                it.requireReadyForRecording()
            }
            val recordingModeLabel = Npv10RecordingModeMetadataPolicy.resolve(
                _videoHdrMode.value,
                _logOn.value,
                _sdrQualityIntent.value
            )
            val config = Npv10SessionConfig(
                width = activeSize.width,
                height = activeSize.height,
                fps = requestedFps,
                mode = recordingModeLabel,
                colorProfile = buildNpv10ColorProfileLabel(manualColorPlan),
                orientationHintDegrees = camera.videoOrientationHint(),
                sourceBitDepth = activeCapturePlan.sourceBitDepth,
                sourceFormat = activeCapturePlan.sourceFormat,
                payloadFormat = activeCapturePlan.payloadFormat,
                exactSelectedResolution = activeCapturePlan.exactSelectedResolution,
                selectedColorSpace = manualColorPlan.selectedColorSpace,
                selectedColorScience = manualColorPlan.selectedColorScience,
                colorSelectionSource = manualColorPlan.colorSelectionSource,
                manualColorSelectionRequired = manualColorPlan.manualColorSelectionRequired,
                rawSensorCapture = false,
                sensorPixelModeMaximumResolution = false,
                validationLabel = debugValidationLabel.orEmpty(),
                featureMetadata = buildNpv10FeatureMetadata(
                    selectedSize = normalizedRecordingSize,
                    capturePlan = activeCapturePlan,
                    requestedFps = requestedFps,
                    manualColorPlan = manualColorPlan,
                    recordingModeLabel = recordingModeLabel
                ),
                startedAtEpochMs = System.currentTimeMillis()
            )
            val output = npv10Recording.start(config)
            val mismatchedFrameLogCount = AtomicInteger(0)
            pipeline.setRawYuvFrameSink(null)
            pipeline.setRawYuvImageSink { image, _ ->
                if (image.width == activeSize.width &&
                    image.height == activeSize.height &&
                    image.format == activeCapturePlan.imageFormat
                ) {
                    npv10Recording.enqueueP010Image(image)
                } else if (mismatchedFrameLogCount.getAndIncrement() < 3) {
                    Log.w(
                        "CameraViewModel",
                        "Dropping mismatched NPV10 P010 frame " +
                            "${image.width}x${image.height} format=${image.format}; " +
                            "expected=${activeSize.width}x${activeSize.height} " +
                            "format=${activeCapturePlan.imageFormat}"
                    )
                    runCatching { image.close() }
                    true
                } else {
                    runCatching { image.close() }
                    true
                }
            }
            applyRecordingCriticalRuntimeHints(
                recordingActive = true,
                previewFps = requestedFps.toFloat()
            )
            val expectedPrearmSignature = npv10CapturePrearmSignature(activeSize, requestedFps)
            val prearmSignatureMatches = npv10CapturePrearmedSignature == expectedPrearmSignature
            val matchingRefreshInFlight = npv10CaptureRefreshSignature == expectedPrearmSignature && camera.sessionRefreshPending.get()
            val capturePrearmed = prearmSignatureMatches &&
                camera.isNpv10TenBitSessionConfigured(activeSize)
            if (prearmSignatureMatches && !capturePrearmed && !matchingRefreshInFlight) {
                Log.w(
                    "CameraViewModel",
                    "NPV10 pre-arm signature was stale; rebuilding the live P010 Camera2 session"
                )
                npv10CapturePrearmedSignature = null
            }
            camera.resetPreviewActivityTracking()
            camera.startVideoCapture()
            requestNpv10P010SessionRefresh(capturePrearmed || matchingRefreshInFlight, expectedPrearmSignature, activeSize)
            if (!awaitNpv10FirstWrittenFrame()) {
                pipeline.setRawYuvImageSink(null)
                pipeline.setRawYuvFrameSink(null)
                runCatching { npv10Recording.stop() }
                stopRecordingForegroundService()
                camera.stopVideoCapture()
                camera.setNpv10TenBitCaptureEnabled(false)
                camera.setRawVideoCaptureEnabled(false)
                camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
                return false
            }
            syncActivePreviewFrameRate()
            syncPreviewPerformanceModeOnMain()
            recordingReadyForClipTiming = true
            startRecordingForegroundService()
            updateCinemaRuntimeStats(lastPreviewFpsSample)
            Log.i(
                "CameraViewModel",
                "Custom packed-10 NPV10 recording started -> ${output.name} " +
                    "selected=${normalizedRecordingSize.width}x${normalizedRecordingSize.height} " +
                    "capture=${activeSize.width}x${activeSize.height} " +
                    "format=${activeCapturePlan.imageFormat} " +
                    "sourceFormat=${activeCapturePlan.sourceFormat} " +
                    "sourceBitDepth=${activeCapturePlan.sourceBitDepth} " +
                    "payloadFormat=${activeCapturePlan.payloadFormat} " +
                    "nativeTenBit=${activeCapturePlan.nativeTenBitCapture} " +
                    "exactSelected=${activeCapturePlan.exactSelectedResolution}"
            )
            true
        } catch (t: Throwable) {
            val cancelledByShutter =
                t is CancellationException && recordingStartCancellationRequested.get()
            pipeline.setRawYuvImageSink(null)
            pipeline.setRawYuvFrameSink(null)
            val capturedMaster = runCatching { npv10Recording.stop() }.getOrNull()
            capturedMaster?.let { Log.i("CameraViewModel", "NPV10 private master preserved -> ${it.absolutePath}") }
            npv10Recording.resetCancelledStartError()
            stopRecordingForegroundService()
            runCatching { camera.stopVideoCapture() }
            camera.setRawVideoCaptureEnabled(false)
            if (cancelledByShutter) {
                Log.i("CameraViewModel", "Custom NPV10 startup cancelled by shutter")
            } else {
                camera.setNpv10TenBitCaptureEnabled(false)
                handleCameraError(
                    throwable = t,
                    operation = "startNpv10Recording",
                    userVisibleMessage =
                        "Custom packed-10 NPV10 recording failed to start. No 8-bit fallback was used."
                )
            }
            false
        }
    }
    private fun requestNpv10P010SessionRefresh(capturePrearmed: Boolean, signature: String, size: Size) {
        if (capturePrearmed) {
            Log.i("CameraViewModel", "NPV10 start reused ready/in-flight native P010 Camera2 session")
        } else {
            npv10CaptureRefreshSignature = signature
            camera.refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true) {
                if (camera.isNpv10TenBitSessionConfigured(size)) npv10CapturePrearmedSignature = signature
                npv10CaptureRefreshSignature = null
                Log.i("CameraViewModel", "NPV10 P010 recording session refresh configured")
            }
        }
    }
    private suspend fun awaitNpv10FirstWrittenFrame(timeoutMs: Long = 12_000L): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            when (val state = npv10Recording.state.value) {
                GpuRecordingState.Recording,
                GpuRecordingState.Paused -> Unit
                is GpuRecordingState.Error -> {
                    Log.e("CameraViewModel", "awaitNpv10FirstWrittenFrame: recorder error: ${state.message}")
                    return false
                }
                else -> Unit
            }
            if (npv10Recording.writtenFrameCount() > 0L) {
                return true
            }
            delay(50L)
        }
        val telemetry = pipeline.rawYuvImageSinkTelemetry()
        Log.e(
            "CameraViewModel",
            "awaitNpv10FirstWrittenFrame: TIMEOUT after ${timeoutMs}ms " +
                "state=${npv10Recording.state.value} callbacks=${telemetry.sourceImageCallbacks} " +
                "owned=${telemetry.ownershipTransferred} unsupported=${telemetry.unsupportedImages} " +
                "declined=${telemetry.sinkDeclined}"
        )
        return false
    }
    private suspend fun stopNpv10RecordingSession() {
        val recordingStopElapsedMs = SystemClock.elapsedRealtime()
        var previewRestored = false
        try {
            val captureDetachStartedMs = SystemClock.elapsedRealtime()
            pipeline.setRawYuvImageSink(null)
            pipeline.setRawYuvFrameSink(null)
            npv10Recording.beginFinalization()
            runCatching { camera.stopVideoCapture() }
                .onFailure { Log.w("CameraViewModel", "NPV10 camera stop failed; writer shutdown continuing", it) }
            // Capture is idle; keep the restart lock while accepted packets seal on the writer lane.
            recordingTransitionState.value = GpuRecordingState.Idle
            stopRecordingForegroundService()
            RuntimeStageTelemetry.recordDuration(
                tag = "CameraViewModel",
                stage = "stopRecording.npv10CaptureDetach",
                durationMs = SystemClock.elapsedRealtime() - captureDetachStartedMs,
                slowThresholdMs = SLOW_RECORDING_STOP_TOTAL_MS
            )
            val previewRestoreStartedMs = SystemClock.elapsedRealtime()
            restorePreviewAfterNpv10CaptureStop()
            previewRestored = true
            RuntimeStageTelemetry.recordDuration(
                tag = "CameraViewModel",
                stage = "stopRecording.npv10PreviewRestore",
                durationMs = SystemClock.elapsedRealtime() - previewRestoreStartedMs,
                slowThresholdMs = SLOW_RECORDING_STOP_TOTAL_MS
            )
            val writerFinalizationStartedMs = SystemClock.elapsedRealtime()
            val output = npv10Recording.stop()
            RuntimeStageTelemetry.recordDuration(
                tag = "CameraViewModel",
                stage = "stopRecording.npv10WriterFinalization",
                durationMs = SystemClock.elapsedRealtime() - writerFinalizationStartedMs,
                slowThresholdMs = SLOW_RECORDING_STOP_TOTAL_MS
            )
            output?.let { master ->
                _recordingSavedPath.tryEmit(master.absolutePath)
                Log.i("CameraViewModel", "NPV10 private master saved -> ${master.absolutePath}")
            }
        } catch (t: Throwable) {
            runCatching { npv10Recording.stop() }
                .onFailure { cleanupFailure -> t.addSuppressed(cleanupFailure) }
            handleCameraError(
                throwable = t,
                operation = "stopNpv10Recording",
                userVisibleMessage = "Custom packed-10 NPV10 stop failed; cleanup is continuing."
            )
        } finally {
            stopRecordingForegroundService()
            if (!previewRestored) {
                runCatching { restorePreviewAfterNpv10CaptureStop() }
                    .onFailure { restoreFailure ->
                        Log.e("CameraViewModel", "NPV10 preview restore failed during stop cleanup", restoreFailure)
                    }
            }
            RuntimeStageTelemetry.recordDuration(
                tag = "CameraViewModel",
                stage = "stopRecording.npv10",
                durationMs = SystemClock.elapsedRealtime() - recordingStopElapsedMs,
                slowThresholdMs = SLOW_RECORDING_STOP_TOTAL_MS
            )
        }
    }
    private suspend fun restorePreviewAfterNpv10CaptureStop() {
        applyRecordingCriticalRuntimeHints(recordingActive = false)
        camera.setRawVideoCaptureEnabled(false)
        clearRecordingReadoutOverride()
        val keepNpv10Prearmed = shouldUseUncompressedCinemaPipeline()
        camera.setNpv10TenBitCaptureEnabled(keepNpv10Prearmed)
        if (keepNpv10Prearmed) {
            camera.resolveNpv10CaptureSourcePlan(preferredRecordingSize()).size.also { size ->
                camera.setNpv10CaptureTargetSize(size)
                npv10CapturePrearmedSignature =
                    npv10CapturePrearmSignature(size, recording.fps.coerceAtLeast(1))
            }
        } else {
            npv10CapturePrearmedSignature = null
        }
        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        camera.setInternalRecordingHardwareImportEnabled(false)
        camera.setPreviewContinuityRecordSurfaceEnabled(false)
        camera.setHardwareImportTargetSize(null)
        runOnMain {
            applyRecordingOutputAspectOverride(null)
            syncGeometryTargets()
        }
        // A still-prearmed P010 graph is the idle graph; do not rebuild its unchanged Camera2 session.
        refreshPreviewPipelineIfNeeded(forceSessionRefresh = !keepNpv10Prearmed)
        syncCameraPreviewLookControls()
        releaseCinemaAutoLock(resetCameraState = true, reason = "npv10_recording_stop")
        syncActivePreviewFrameRate()
        syncPreviewPerformanceModeOnMain()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }
    private fun buildNpv10ColorProfileLabel(manualColorPlan: ManualVideoColorSelectionPlan): String {
        val mode = _cinemaVideoMode.value.name
        val transfer = when {
            _logOn.value -> "LOG"
            _videoHdrOn.value || !_videoHdrMode.value.equals("OFF", ignoreCase = true) -> "HDR"
            else -> "SDR"
        }
        return "$mode-$transfer-${manualColorPlan.selectedColorSpace}-${manualColorPlan.selectedColorScience}"
    }
    private fun applyNpv10RecordingFeatureParity(captureSize: Size, requestedFps: Int) {
        // Native P010 is prearmed at its camera-reported size. Do not install preview/analysis caps here:
        // those caps retune the live graph and can force a quality-reducing stop-time session rebuild.
        camera.setNoiseReductionEnabled(effectiveNoiseReductionEnabled())
        val stabilizationMode = this.stabilizationMode
        camera.setStabilizationMode(stabilizationMode)
        applyAiTrackingPreferences()
        syncEffectiveRecordingColorProfile()
        applyRecordingCameraLookControls()
        syncGpuLutState()
        syncActivePreviewFrameRate()
        Log.i(
            "CameraViewModel",
            "NPV10 feature parity applied " +
                "capture=${captureSize.width}x${captureSize.height} fps=$requestedFps " +
                "mode=${_cinemaVideoMode.value} log=${_logOn.value} hdr=${_videoHdrMode.value} " +
                "color=${_videoColorSpace.value}/${_colorProfile.value} lut=${_lutEnabled.value}:${_lutProfile.value} " +
                "stabilization=$stabilizationMode noise=${effectiveNoiseReductionEnabled()} ai=${_aiEnhancement.value}"
        )
    }
    private fun buildNpv10FeatureMetadata(
        selectedSize: Size,
        capturePlan: Npv10CaptureSourcePlan,
        requestedFps: Int,
        manualColorPlan: ManualVideoColorSelectionPlan,
        recordingModeLabel: String
    ): Map<String, String> {
        val previewLookState = activeVideoPreviewLookState()
        val noiseMetrics = currentSceneRenderProfile?.noiseMetrics ?: VideoNoiseMetrics.EMPTY
        val normalizedHdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(_videoHdrMode.value)
        val bt2100PqActive = previewLookState.hdrEnabled &&
            Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrMode)
        val hlgActive = previewLookState.hdrEnabled &&
            previewLookState.hdrMode.equals(Bt2100TransportPolicy.LEGACY_HLG_MODE, ignoreCase = true)
        val wideGamutSdr = !previewLookState.hdrEnabled &&
            !previewLookState.logEnabled &&
            isWideGamutVideoSpace(manualColorPlan.selectedColorSpace)
        val signalFamily = when {
            previewLookState.hdrEnabled -> "HDR"
            previewLookState.logEnabled -> "LOG"
            else -> "SDR"
        }
        val transport = when {
            hlgActive -> Bt2100TransportPolicy.TRANSPORT_HLG_MAIN10
            previewLookState.hdrEnabled -> Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10
            previewLookState.logEnabled -> "BT2020_LOG_SDR"
            wideGamutSdr -> "BT2020_SDR"
            else -> "REC709_SDR"
        }
        val transfer = when {
            hlgActive -> Bt2100TransportPolicy.TRANSFER_HLG
            previewLookState.hdrEnabled -> Bt2100TransportPolicy.TRANSFER_PQ
            else -> "BT.709"
        }
        val wideGamutTransport = previewLookState.hdrEnabled || previewLookState.logEnabled || wideGamutSdr
        return linkedMapOf(
            "pipeline" to "custom-packed10-npv10",
            "packetStreamOnly" to "true",
            "individualFrameFiles" to "false",
            "mediaCodecUsed" to "false",
            "normalSettingsParity" to "true",
            "selectedResolution" to "${selectedSize.width}x${selectedSize.height}",
            "captureResolution" to "${capturePlan.size.width}x${capturePlan.size.height}",
            "exactSelectedResolution" to capturePlan.exactSelectedResolution.toString(),
            "sourceImageFormat" to capturePlan.imageFormat.toString(),
            "sourceFormat" to capturePlan.sourceFormat,
            "sourceBitDepth" to capturePlan.sourceBitDepth.toString(),
            "storageBitDepth" to Npv10ContainerPolicy.BIT_DEPTH.toString(),
            "payloadFormat" to capturePlan.payloadFormat.name,
            "nativeTenBitCapture" to capturePlan.nativeTenBitCapture.toString(),
            "requestedFps" to requestedFps.toString(),
            "appliedCameraFps" to cameraParams.value.fps.toString(),
            "cinemaMode" to _cinemaVideoMode.value.name,
            "recordingMode" to recordingModeLabel,
            "sdrQualityIntent" to _sdrQualityIntent.value.name,
            "logEnabled" to previewLookState.logEnabled.toString(),
            "hdrEnabled" to previewLookState.hdrEnabled.toString(),
            "rawLookEnabled" to previewLookState.rawEnabled.toString(),
            "hdrMode" to _videoHdrMode.value,
            "signalFamily" to signalFamily,
            "transport" to transport,
            "transfer" to transfer,
            "primaries" to if (wideGamutTransport) "BT.2020" else "BT.709",
            "matrix" to if (wideGamutTransport) "BT.2020-NCL" else "BT.709",
            "dynamicRange" to if (bt2100PqActive) {
                Bt2100TransportPolicy.displayLabelForHdrVersion(normalizedHdrMode)
            } else {
                _videoHdrMode.value.takeIf { !it.equals("OFF", ignoreCase = true) } ?: "OFF"
            },
            "bt2100PqActive" to bt2100PqActive.toString(),
            "bt2100Primaries" to if (bt2100PqActive) Bt2100TransportPolicy.PRIMARIES else "",
            "bt2100Transfer" to if (bt2100PqActive) Bt2100TransportPolicy.TRANSFER_PQ else "",
            "bt2100Matrix" to if (bt2100PqActive) Bt2100TransportPolicy.MATRIX_NCL else "",
            "bt2100PrivateMasterTransport" to if (bt2100PqActive) {
                Bt2100TransportPolicy.TRANSPORT_NPV10_PQ_PRIVATE_MASTER
            } else {
                ""
            },
            "colorSpace" to manualColorPlan.selectedColorSpace,
            "colorProfile" to manualColorPlan.selectedColorScience,
            "lutEnabled" to _lutEnabled.value.toString(),
            "lutProfile" to _lutProfile.value,
            "noiseReduction" to effectiveNoiseReductionEnabled().toString(),
            "stabilization" to stabilizationMode.name,
            "aiEnhancement" to _aiEnhancement.value.toString(),
            "faceDetection" to prefs.faceDetectionEnabled.toString(),
            "eyeAutofocus" to prefs.eyeAutofocusEnabled.toString(),
            "histogram" to _showHist.value.toString(),
            "waveform" to effectiveWaveformEnabled().toString(),
            "zebra" to effectiveZebraEnabled().toString(),
            "peaking" to effectivePeakingEnabled().toString(),
            "manualIso" to prefs.lastISO.toString(),
            "manualExposureNs" to prefs.lastShutterNs.toString(),
            "manualWhiteBalanceKelvin" to prefs.lastWBKelvin.toString(),
            "audioSourceSetting" to recording.audioSource.name,
            "audioStoredInNpv10" to "false",
            "codecSetting" to recording.codec.name,
            "bitrateModeSetting" to _videoBitrateMode.value.name,
            "bitrateSetting" to recording.bitrate.toString()
        ).apply {
            putAll(manualColorPlan.toMetadata())
            putAll(noiseMetrics.toMetadata())
            put("noiseReductionSignature", currentSceneRenderProfile?.noiseReductionSignature.orEmpty())
            putAll(
                VideoDetailSharpeningPolicy.npv10MasterEvidenceMetadata(
                    currentSceneRenderProfile?.detailSharpeningMetadata.orEmpty()
                )
            )
        }
    }

    fun toggleRecording() {
        if (recordingStartInProgress.get()) {
            stopRecording()
            return
        }
        if (recordingController.toggleRecording()) {
            toggleRecordingInternal()
        }
    }

    private fun toggleRecordingInternal() {
        when (RecordingTogglePolicy.nextAction(gpuRecordState.value)) {
            RecordingTogglePolicy.Action.START -> startRecording()
            RecordingTogglePolicy.Action.STOP -> stopRecording()
            RecordingTogglePolicy.Action.IGNORE -> {
                Log.d("CameraViewModel", "toggleRecording ignored: state=${gpuRecordState.value}")
            }
        }
    }

    private fun stopCameraVideoCaptureSafely(): RecordingOperationOutcome =
        try {
            camera.stopVideoCapture()
            RecordingOperationOutcome.Success
        } catch (e: Exception) {
            RecordingOperationOutcome.Failure(
                operation = "camera.stopVideoCapture",
                reason = e.message ?: "unknown camera stop error",
                cause = e
            )
        }

    private suspend fun detachRecordingSurfaceBeforeEncoderStop(): RecordingOperationOutcome =
        try {
            val refreshed = CompletableDeferred<Unit>()
            val keepPrearmedSessionSurface =
                RecordingPersistentInputSurfacePolicy.shouldSkipSessionRebuildForPrearmedSurface(
                    surfaceMatches = prearmedRecordingSurface != null,
                    surfaceAttachedToCameraSession = prearmedRecordingSurfaceAttachedToSession
                )
            updatePreviewViewAwait { setEncoderFeedEnabled(false) }
            vulkanRuntime.setEncoderSurface(null)
            rebindVisiblePreviewTargetBeforeRecordingStopRetarget(
                keepPrearmedRecordSurface = keepPrearmedSessionSurface
            )
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            if (keepPrearmedSessionSurface) {
                Log.i(
                    "CameraViewModel",
                    "Recording stop: kept pre-armed persistent encoder surface in Camera2 session"
                )
                delay(8L)
                RecordingOperationOutcome.Success
            } else {
                camera.setRecordSurface(null, refreshSession = false)
                prearmedRecordingSurface = null
                prearmedRecordingSurfaceAttachedToSession = false
                prearmedRecordingSignature = null
                prearmedRecordingFeedPath = null
                camera.refreshOutputsAndSession(rebuildReaders = false, forceRebuild = true) {
                    refreshed.complete(Unit)
                }
                val completed = withTimeoutOrNull(750L) {
                    refreshed.await()
                    true
                } ?: false
                if (!completed) {
                    RecordingOperationOutcome.Failure(
                        operation = "camera.detachRecordSurfaceBeforeEncoderStop",
                        reason = "timed out waiting for Camera2 session without encoder surface"
                    )
                } else {
                    delay(8L)
                    RecordingOperationOutcome.Success
                }
            }
        } catch (e: Exception) {
            RecordingOperationOutcome.Failure(
                operation = "camera.detachRecordSurfaceBeforeEncoderStop",
                reason = e.message ?: "unknown pre-stop record surface detach error",
                cause = e
            )
        }

    private suspend fun detachInternalRecordingFeedBeforeEncoderStop(
        keepOffscreenEncoderSurfaceForEos: Boolean = false
    ): RecordingOperationOutcome =
        try {
            preserveValidatedGpuRecordingEvidence(vulkanImportStats.value)
            camera.setInternalRecordingHardwareImportEnabled(false)
            camera.setHardwareImportP010SourceEnabled(false)
            camera.setHardwareImportTargetSize(null)
            val hardwareSourceDetached = if (keepOffscreenEncoderSurfaceForEos) {
                Log.i(
                    "CameraViewModel",
                    "Recording stop: deferring offscreen hardware-import reader rebuild until preview restore"
                )
                true
            } else {
                awaitFreshPreviewSessionBeforeRecording(
                    forceRebuild = true,
                    rebuildReaders = true
                )
            }
            updatePreviewViewAwait {
                setEncoderFeedEnabled(false)
                setEncoderSurface(null)
            }
            pipeline.setDedicatedHardwareImportEnabled(false)
            val hardwareDispatcherIdle = pipeline.awaitIdleBeforeReaderClose(timeoutMs = if (keepOffscreenEncoderSurfaceForEos) 2_000L else 750L)
            if (!hardwareDispatcherIdle) {
                Log.w(
                    "CameraViewModel",
                    "Recording stop: hardware-import dispatcher still busy before encoder EOS"
                )
                if (keepOffscreenEncoderSurfaceForEos && RecordStopSubmitQuiesceFlag.enabled) {
                    val submitQuiesced = pipeline.awaitHardwareSubmitQuiescent(
                        timeoutMs = RecordStopSubmitQuiesceFlag.QUIESCE_TIMEOUT_MS
                    )
                    if (!submitQuiesced) {
                        Log.w(
                            "CameraViewModel",
                            "Recording stop: native hardware submit still in flight after bounded quiesce poll"
                        )
                    }
                }
            }
            if (keepOffscreenEncoderSurfaceForEos) vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
            vulkanRuntime.setFrameImportEnabled(false)
            vulkanRuntime.setEncoderSurface(null)
            if (!keepOffscreenEncoderSurfaceForEos) {
                vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
            } else {
                Log.i(
                    "CameraViewModel",
                    "Recording stop: keeping offscreen EGL encoder surface alive until MediaCodec EOS"
                )
            }
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            prearmedRecordingSurface = null
            prearmedRecordingSurfaceAttachedToSession = false
            prearmedRecordingSignature = null
            prearmedRecordingFeedPath = null
            if (hardwareSourceDetached) {
                RecordingOperationOutcome.Success
            } else {
                RecordingOperationOutcome.Failure(
                    operation = "internalHardwareImport.detachBeforeEncoderStop",
                    reason = "timed out while removing the Camera2 hardware-import source"
                )
            }
        } catch (e: Exception) {
            RecordingOperationOutcome.Failure(
                operation = "internalHardwareImport.detachBeforeEncoderStop",
                reason = e.message ?: "unknown internal recording feed detach error",
                cause = e
            )
        }

    private fun stopRawCaptureIfNeeded(): RawCaptureStopResult {
        if (!rawVideoCallbacksInstalled) {
            return RawCaptureStopResult(
                path = null,
                outcome = RecordingOperationOutcome.Success
            )
        }
        return try {
            RawCaptureStopResult(
                path = rawVideoManager.stopCapture(),
                outcome = RecordingOperationOutcome.Success
            )
        } catch (e: Exception) {
            RawCaptureStopResult(
                path = null,
                outcome = RecordingOperationOutcome.Failure(
                    operation = "rawVideoManager.stopCapture",
                    reason = e.message ?: "unknown raw stop error",
                    cause = e
                )
            )
        }
    }

    private fun detachRecordingSurfaceSafely(): RecordingOperationOutcome =
        try {
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            camera.setRecordSurface(null)
            prearmedRecordingSurface = null
            prearmedRecordingSurfaceAttachedToSession = false
            prearmedRecordingSignature = null
            prearmedRecordingFeedPath = null
            RecordingOperationOutcome.Success
        } catch (e: Exception) {
            RecordingOperationOutcome.Failure(
                operation = "camera.setRecordSurface(null)",
                reason = e.message ?: "unknown record surface detach error",
                cause = e
            )
        }

    private fun refreshRecordingOutputsSafely(): RecordingOperationOutcome =
        try {
            camera.refreshOutputsAndSession()
            RecordingOperationOutcome.Success
        } catch (e: Exception) {
            RecordingOperationOutcome.Failure(
                operation = "camera.refreshOutputsAndSession",
                reason = e.message ?: "unknown session refresh error",
                cause = e
            )
        }

    private fun drainRawReaderSafely(): RecordingOperationOutcome =
        try {
            camera.drainRawReaderSynchronously()
            RecordingOperationOutcome.Success
        } catch (e: Exception) {
            RecordingOperationOutcome.Failure(
                operation = "camera.drainRawReaderSynchronously",
                reason = e.message ?: "unknown raw reader drain error",
                cause = e
            )
        }

    // -------------------------------------------------------------------------
    // MANUAL CONTROLS
    // -------------------------------------------------------------------------

    fun setISO(iso: Int) {
        val resolvedIso = CameraManualControlStateSyncPolicy.clampIso(iso, camera.effectiveIsoRange)
        CameraManualControlStateSyncPolicy.setIso(resolvedIso, camera.effectiveIsoRange)
        prefs.lastISO = resolvedIso
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_ISO_KEY) {
            camera.setISO(resolvedIso)
        }
    }
    fun setAutoISO() {
        CameraManualControlStateSyncPolicy.setIsoAuto()
        prefs.lastISO = -1
        dispatchManualControlUpdate { camera.setAutoISO() }
    }
    fun setExposure(ns: Long) {
        val resolvedExposure = CameraManualControlStateSyncPolicy.clampExposureNs(ns, camera.exposureRange)
        CameraManualControlStateSyncPolicy.setExposureNs(resolvedExposure, camera.exposureRange)
        prefs.lastShutterNs = resolvedExposure
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_EXPOSURE_KEY) {
            camera.setExposure(resolvedExposure)
        }
        updateShutterRuleGuidance()
    }
    fun setAutoExposure() {
        CameraManualControlStateSyncPolicy.setExposureAuto()
        prefs.lastShutterNs = -1L
        dispatchManualControlUpdate { camera.setAutoExposure() }
        updateShutterRuleGuidance()
    }
    fun setWB(kelvin: Int) = setWB(kelvin, prefs.lastWBTint)

    /** Manual white balance with an explicit green-magenta tint (P0.1 Pro-Control accuracy). */
    fun setWB(kelvin: Int, tint: Int) {
        val resolvedKelvin = CameraManualControlStateSyncPolicy.clampWhiteBalanceKelvin(
            kelvin,
            camera.manualWhiteBalanceRange
        )
        prefs.lastWBKelvin = resolvedKelvin
        prefs.lastWBTint = tint
        CameraManualControlStateSyncPolicy.setWhiteBalanceKelvin(resolvedKelvin)
        CameraManualControlStateSyncPolicy.setWhiteBalanceTint(tint)
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_WHITE_BALANCE_KEY) {
            camera.setWB(resolvedKelvin, tint)
        }
    }
    fun setAutoWB() {
        CameraManualControlStateSyncPolicy.setWhiteBalanceAuto()
        prefs.lastWBKelvin = -1
        prefs.lastWBTint = 0
        dispatchManualControlUpdate { camera.setAutoWB() }
    }
    fun setFocusDist(d: Float)  {
        CameraManualControlStateSyncPolicy.setFocusDistance(d)
        releaseCinemaAutoLock(resetCameraState = false, reason = "manual_focus_distance")
        aiFocusResumeJob?.cancel()
        autoFocusPrimeJob?.cancel()
        tapToFocusHoldUntilElapsedMs = 0L
        aiAfCoordinator.setEnabled(false)
        activePeakingRegion.value = PeakingRegion(0.5f, 0.5f, 0.38f)
        previewViewRef?.setPeakingFocusRegion(0.5f, 0.5f, 0.38f)
        pipeline.setPeakingFocusRegion(0.5f, 0.5f, 0.38f)
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_FOCUS_DISTANCE_KEY) {
            camera.clearFocusRegion()
            camera.setFocusDist(d)
        }
    }
    fun setAutoFocus()          {
        CameraManualControlStateSyncPolicy.setFocusAuto()
        releaseCinemaAutoLock(resetCameraState = false, reason = "manual_autofocus")
        tapToFocusHoldUntilElapsedMs = 0L
        manualFocusOverride.value = null
        activePeakingRegion.value = null
        previewViewRef?.clearPeakingFocusRegion()
        pipeline.clearPeakingFocusRegion()
        dispatchManualControlUpdate {
            camera.clearFocusRegion()
            camera.setAutoFocus()
        }
        restoreAIAutoFocusFromPrefs()
    }
    fun setFocusRegion(region: android.graphics.Rect) = dispatchManualControlUpdate { camera.setFocusRegion(region) }
    fun clearFocusRegion()      = dispatchManualControlUpdate { camera.clearFocusRegion() }
    fun cycleManualMeteringMode() {
        val modes = CinemaManualMeteringMode.entries
        val next = modes[(modes.indexOf(_manualMeteringMode.value) + 1) % modes.size]
        _manualMeteringMode.value = next
        applyManualMeteringMode(next)
    }

    private fun applyManualMeteringMode(mode: CinemaManualMeteringMode) {
        val activeArray = camera.activeArray?.let {
            CameraRect(it.left, it.top, it.right, it.bottom)
        } ?: return
        val region = CinemaManualControlPolicy.meteringRegion(activeArray, mode)?.let {
            android.graphics.Rect(it.left, it.top, it.right, it.bottom)
        }
        dispatchManualControlUpdate {
            camera.setSubjectMeteringRegions(af = null, ae = region)
        }
    }

    fun setEV(ev: Int) {
        CameraManualControlStateSyncPolicy.setExposureCompensation(ev.toFloat())
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_EV_KEY) {
            camera.setEV(ev)
        }
        viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(16L)
            refreshPreviewCompositorState()
        }
    }
    fun setFlash(f: FlashMode)  = camera.setFlash(f)
    fun setZoom(r: Float) {
        val clamped = CameraZoomRequestPolicy.resolve(
            requestedZoom = r,
            minZoom = camera.minZoom,
            maxZoom = camera.maxZoom,
            beyondImaxActive = isBeyondImaxActive()
        )
        pendingZoomRatio = clamped
        manualControlDispatcher.postLatestZoom {
            val target = pendingZoomRatio ?: return@postLatestZoom
            pendingZoomRatio = null
            camera.setZoom(target)
        }
    }
    fun setZoomPreset(targetRatio: Float) {
        setZoom(targetRatio.coerceIn(camera.minZoom, camera.maxZoom))
    }

    /**
     * True when zoom is intentionally hard-locked (RAW/beyond-IMAX full-sensor readout must not crop).
     * The UI uses this to show "zoom locked" feedback instead of silently ignoring a pinch (#27).
     */
    fun isZoomLocked(): Boolean = isBeyondImaxActive()

    /**
     * Physical-lens / zoom preset stops (0.5x/1x/2x/3x/5x filtered to the device's range) for the
     * quick-switch chips (#25). Empty/one-element when only a single lens is usable.
     */
    fun recommendedZoomPresets(): List<Float> =
        com.neoproc.neoproccamx.camera.CameraLensSelectionPolicy.getRecommendedZoomSteps(
            minZoom = camera.minZoom,
            maxZoom = camera.maxZoom
        )
    fun setFPS(fps: Int)        {
        val requestedFps = resolveContractRequestedFps(fps)
        val frameRatePlan = RecordingPreviewFrameRatePolicy.resolve(lastRecordingSelection, requestedFps)
        recording.fps = frameRatePlan.encoderFps
        gpuRecording.videoFps = frameRatePlan.encoderFps
        previewViewRef?.setEncoderFrameRate(frameRatePlan.encoderFps)
        prefs.videoFps = frameRatePlan.encoderFps
        syncActivePreviewFrameRate()
        applyAutomaticVideoBitrateIfNeeded(force = true)
        updateShutterRuleGuidance()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
        if (_uiMode.value == AppMode.VIDEO && cameraState.value == CameraState.Streaming) {
            requestRecordingSurfacePrearm(forceSessionRefresh = true)
        }
    }

    fun applyCinematicBundle() {
        if (_uiMode.value != AppMode.VIDEO) return
        val inv = camera.getCameraCapabilityInventory()
        val avail = com.neoproc.neoproccamx.cinema.CinematicBundlePolicy.CinematicBundleAvailability(
            eisAvailable = inv?.supportsVideoStabilization == true,
            bokehAvailable = false, // Vogel-spiral bokeh pass is device-gated OFF; no VM engage yet (honest drop).
            hdrSupport = camera.describeHdrDynamicRangeState().support,
            tenBitSupport = com.neoproc.neoproccamx.color.NeoTenBitHardwareSupport(
                hdr10Supported = inv?.supportsTenBitHdr == true
            )
        )
        val decision = com.neoproc.neoproccamx.cinema.CinematicBundlePolicy.resolve(enabled = true, avail = avail)
        val plan = com.neoproc.neoproccamx.cinema.CinematicBundleApplicationPlan.from(decision, recording.fps)
        camera.beginBatchUpdate()
        try {
            plan.fps?.let { setFPS(it) }
            setCinemaVideoMode(plan.cinemaMode)
            setVideoHdrMode(plan.hdrModeToken)
            setStabilizationMode(plan.stabilization)
            if (plan.shutterNs > 0L) setExposure(plan.shutterNs)
        } finally {
            camera.endBatchUpdate()
        }
        if (plan.droppedMembers.isNotEmpty()) {
            setHardwareWarning("Cinematic 24p engaged — unavailable: " + plan.droppedMembers.joinToString())
        }
    }

    fun tapToFocus(nx: Float, ny: Float) {
        releaseCinemaAutoLock(resetCameraState = true, reason = "tap_focus")
        autoFocusPrimeJob?.cancel()
        tapToFocusHoldUntilElapsedMs = SystemClock.elapsedRealtime() + TAP_TO_FOCUS_HOLD_MS
        // Refocus must not silently drop a user's explicit AE/AWB lock mid-clip — clearing it lets
        // exposure/white balance drift during recording. The tap metering-unlock window is already
        // gated OFF while recording (VideoColorStabilityPolicy), so nothing needs unlocking here;
        // preserve the lock to keep recorded color stable. Preview / no-lock behavior is unchanged.
        val recordingActiveForTap = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping
        if (!(recordingActiveForTap && camera.isAeAwbLocked())) {
            camera.setAeAwbLock(false)
        }
        suspendAIAutoFocusTemporarily(TAP_TO_FOCUS_HOLD_MS)
        manualFocusOverride.value = FocusTarget(nx, ny, FocusTargetType.OBJECT, -1)
        activePeakingRegion.value = PeakingRegion(nx, ny, 0.34f)
        previewViewRef?.setPeakingFocusRegion(nx, ny, 0.40f)
        pipeline.setPeakingFocusRegion(nx, ny, 0.40f)
        syncPreviewViewport(previewViewRef)
        camera.triggerTapAF(nx, ny)
    }

    fun lockFocusAndExposure(nx: Float, ny: Float) {
        releaseCinemaAutoLock(resetCameraState = false, reason = "manual_lock")
        val previousParams = cameraParams.value
        val previousManualFocus = manualFocusOverride.value
        val previousAeAwbLock = camera.isAeAwbLocked()
        tapToFocus(nx, ny)
        viewModelScope.launch {
            delay(350L)
            val latest = liveResult.value
            focusExposureLockState = FocusExposureLockState(
                focusX = nx,
                focusY = ny,
                baseExposureNs = latest?.exposureNs?.takeIf { it > 0L }
                    ?: previousParams.exposureNs.takeIf { it > 0L }
                    ?: 16_666_667L,
                previousExposureNs = previousParams.exposureNs,
                previousFocusDist = previousParams.focusDist,
                previousAeAwbLock = previousAeAwbLock,
                previousManualFocus = previousManualFocus
            )
            previewViewRef?.setPeakingFocusRegion(nx, ny, 0.38f)
            pipeline.setPeakingFocusRegion(nx, ny, 0.38f)
            activePeakingRegion.value = PeakingRegion(nx, ny, 0.34f)
            aiFocusResumeJob?.cancel()
            aiAfCoordinator.setEnabled(false, clearMeteringRegionWhenDisabling = false)
            // Only hold the lens at a MANUAL distance when a real, positive focus distance is
            // available. Lenses that report 0 diopters at infinity (uncalibrated — e.g. vivo I2405)
            // would otherwise pin CONTROL_AF_MODE_OFF at infinity and never recover (dead AF that
            // also no-ops the AI-AF coordinator). In that case keep continuous AF and lock only
            // AE/AWB, so "focus + exposure lock" degrades to a stable-exposure lock, never a dead lens.
            val lockedFocusDistance = latest?.focusDist?.takeIf { it > 0f }
                ?: previousParams.focusDist.takeIf { it > 0f }
            if (lockedFocusDistance != null) {
                camera.setFocusDist(lockedFocusDistance)
            } else {
                camera.setAutoFocus()
            }
            camera.setAeAwbLock(true)
        }
    }

    fun unlockFocusAndExposure() {
        val lockState = focusExposureLockState
        focusExposureLockState = null
        tapToFocusHoldUntilElapsedMs = 0L
        if (lockState?.previousExposureNs != null && lockState.previousExposureNs > 0L) {
            camera.setExposure(lockState.previousExposureNs)
        } else {
            camera.setAutoExposure()
        }
        if (lockState?.previousFocusDist != null && lockState.previousFocusDist > 0f) {
            camera.setFocusDist(lockState.previousFocusDist)
        } else {
            // A captured 0 (infinity / uncalibrated lens) resumes continuous AF instead of
            // re-pinning the lens off-AF at infinity.
            camera.setAutoFocus()
        }
        camera.setAeAwbLock(lockState?.previousAeAwbLock == true)
        manualFocusOverride.value = lockState?.previousManualFocus
        activePeakingRegion.value = null
        previewViewRef?.clearPeakingFocusRegion()
        pipeline.clearPeakingFocusRegion()
        if (manualFocusOverride.value == null) {
            restoreAIAutoFocusFromPrefs()
        }
    }

    private fun shouldUseCinemaAutoLock(): Boolean {
        if (_uiMode.value != AppMode.VIDEO) return false
        if (gpuRecordState.value !is GpuRecordingState.Recording) return false
        if (!isCinemaFidelityModeActive()) return false
        if (!prefs.eyeAutofocusEnabled) return false
        if (focusExposureLockState != null) return false
        return true
    }

    private fun evaluateCinemaAutoLock(result: LiveResult?) {
        if (!shouldUseCinemaAutoLock()) {
            cinemaAutoLockCandidateStartElapsedMs = 0L
            releaseCinemaAutoLock(resetCameraState = true, reason = "inactive")
            return
        }
        if (cinemaAutoLockActive) return
        val aiTargetPresent = mergedAiMetering.value != null
        val focused = result?.afFocused == true
        val focusDistance = result?.focusDist ?: cameraParams.value.focusDist
        if (!aiTargetPresent || !focused || focusDistance < 0f) {
            cinemaAutoLockCandidateStartElapsedMs = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (cinemaAutoLockCandidateStartElapsedMs <= 0L) {
            cinemaAutoLockCandidateStartElapsedMs = now
            return
        }
        if (now - cinemaAutoLockCandidateStartElapsedMs < CINEMA_AUTO_LOCK_STABLE_MS) {
            return
        }
        applyCinemaAutoLock(focusDistance)
    }

    private fun applyCinemaAutoLock(focusDistance: Float) {
        if (cinemaAutoLockActive || focusExposureLockState != null) return
        val maxFocusDistance = camera.focusDistanceRange.coerceAtLeast(0f)
        val resolvedDistance = focusDistance.coerceIn(0f, maxFocusDistance)
        cinemaAutoLockCandidateStartElapsedMs = 0L
        cinemaAutoLockPreviousAeAwbLock = camera.isAeAwbLocked()
        cinemaAutoLockPreviousFocusDistance = cameraParams.value.focusDist
        cinemaAutoLockActive = true
        if (resolvedDistance > 0f) {
            camera.setFocusDist(resolvedDistance)
        }
        // else: reported focus distance is 0 (infinity / uncalibrated lens) — leave continuous AF
        // running rather than pinning the lens off-AF at infinity while recording; still lock AE/AWB.
        camera.setAeAwbLock(true)
        applyAiTrackingPreferences()
        Log.i("CameraViewModel", "Cinema auto-lock applied focus=${"%.3f".format(Locale.US, resolvedDistance)}")
    }

    private fun releaseCinemaAutoLock(
        resetCameraState: Boolean,
        reason: String
    ) {
        cinemaAutoLockCandidateStartElapsedMs = 0L
        if (!cinemaAutoLockActive) return
        val restoreFocusDistance = cinemaAutoLockPreviousFocusDistance
        val restoreAeAwbLock = cinemaAutoLockPreviousAeAwbLock
        cinemaAutoLockActive = false
        cinemaAutoLockPreviousFocusDistance = -1f
        cinemaAutoLockPreviousAeAwbLock = false
        if (resetCameraState && focusExposureLockState == null) {
            if (restoreFocusDistance > 0f) {
                camera.setFocusDist(restoreFocusDistance)
            } else {
                // 0 (infinity / uncalibrated lens) resumes continuous AF, never a dead lens.
                camera.setAutoFocus()
            }
            camera.setAeAwbLock(restoreAeAwbLock)
        }
        applyAiTrackingPreferences()
        Log.i("CameraViewModel", "Cinema auto-lock released reason=$reason")
    }

    fun adjustLockedExposureOffset(sliderValue: Int) {
        val lockState = focusExposureLockState ?: run {
            setEV(sliderValue)
            return
        }
        val offsetStops = (sliderValue.coerceIn(-4, 4) / 2.0)
        val targetExposure =
            (lockState.baseExposureNs.toDouble() * 2.0.pow(offsetStops))
                .toLong()
                .coerceAtLeast(1L)
        camera.setLockedExposure(targetExposure)
    }

    fun setVideoResolution(size: Size) {
        val appliedSize: Size
        synchronized(recordingSelectionMutationLock) {
            val sanitizedSize = sanitizeRequestedVideoResolutionForCinemaMode(size)
            val resolvedSelection = resolveRecordingSelection(sanitizedSize)
            applyResolvedRecordingConfig(
                resolvedSelection = resolvedSelection,
                fps = recording.fps,
                bitrate = resolveConfiguredBitrate(resolvedSelection, recording.fps),
                audioSource = recording.audioSource
            )
            prefs.videoSize = resolvedSelection.requestedSize
            prefs.videoCodec = recording.codec.name
            appliedSize = resolvedSelection.requestedSize
        }
        // "4K Full 4:3" (4096x3072) and any other 4:3 capture dimension auto-align the preview aspect
        // to 4:3 so the framing matches the recorded frame without a second manual step. Only 4:3 is
        // auto-applied — other resolutions leave the user's chosen preview aspect untouched.
        val aw = appliedSize.width.toLong()
        val ah = appliedSize.height.toLong()
        val isFourThree = aw > 0 && ah > 0 && (aw * 3L == ah * 4L || ah * 3L == aw * 4L)
        if (isFourThree && _previewAspect.value != PreviewAspectRatio.FOUR_THREE) {
            setPreviewAspectRatio(PreviewAspectRatio.FOUR_THREE)
        }
        syncGeometryTargets()
        if (_uiMode.value == AppMode.VIDEO) {
            refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
            if (cameraState.value == CameraState.Streaming) {
                requestRecordingSurfacePrearm(forceSessionRefresh = true)
            }
        }
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }

    private fun sanitizeRequestedVideoResolutionForCinemaMode(
        requestedSize: Size,
        mode: CinemaVideoMode = _cinemaVideoMode.value
    ): Size {
        val normalized = requestedSize.normalizeForOrientation()
        val cinemaBounded =
            if (
                normalized.width > MAX_CINEMA_RECORD_WIDTH ||
                    normalized.height > MAX_CINEMA_RECORD_HEIGHT
            ) {
                Size(MAX_CINEMA_RECORD_WIDTH, MAX_CINEMA_RECORD_HEIGHT)
            } else {
                normalized
            }
        if (_uiMode.value != AppMode.VIDEO || mode != CinemaVideoMode.RAW) {
            return cinemaBounded
        }
        if (cinemaBounded.width <= RAW_MODE_MAX_LONG_EDGE) {
            return cinemaBounded
        }
        val rawSafeCandidates = availableVideoSizes()
            .map { it.normalizeForOrientation() }
            .distinctBy { "${it.width}x${it.height}" }
            .filter { candidate -> candidate.width <= RAW_MODE_MAX_LONG_EDGE }
            .sortedByDescending { candidate -> candidate.width.toLong() * candidate.height.toLong() }
        if (rawSafeCandidates.isNotEmpty()) {
            val targetAspect = cinemaBounded.width.toFloat() / cinemaBounded.height.coerceAtLeast(1).toFloat()
            return rawSafeCandidates.minWithOrNull(
                compareBy<Size> { candidate ->
                    abs((candidate.width.toFloat() / candidate.height.coerceAtLeast(1).toFloat()) - targetAspect)
                }.thenByDescending { candidate ->
                    candidate.width.toLong() * candidate.height.toLong()
                }
            ) ?: rawSafeCandidates.first()
        }
        val scaledShortEdge = (
            cinemaBounded.height.toFloat() *
                (RAW_MODE_MAX_LONG_EDGE.toFloat() / cinemaBounded.width.toFloat())
            ).roundToInt().coerceAtLeast(1)
        return Size(RAW_MODE_MAX_LONG_EDGE, scaledShortEdge)
    }

    private fun enforceRawVideoResolutionCapIfNeeded() {
        if (_uiMode.value != AppMode.VIDEO || _cinemaVideoMode.value != CinemaVideoMode.RAW) return
        val currentRequested = (selectedVideoSize ?: prefs.videoSize).normalizeForOrientation()
        val cappedRequested = sanitizeRequestedVideoResolutionForCinemaMode(
            requestedSize = currentRequested,
            mode = CinemaVideoMode.RAW
        )
        if (cappedRequested != currentRequested) {
            setVideoResolution(cappedRequested)
        } else {
            // Ensure session is refreshed even if resolution didn't change, 
            // as RAW mode requires specific capture request configurations.
            refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
        }
    }
    fun setVideoBitrate(bps: Int)           {
        prefs.videoBitrateMode = VideoBitrateMode.CUSTOM
        syncRecordingBitrateMode(VideoBitrateMode.CUSTOM)
        applyVideoBitrateValue(bps)
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }
    fun setVideoBitrateMode(mode: VideoBitrateMode) {
        prefs.videoBitrateMode = mode
        syncRecordingBitrateMode(mode)
        if (
            mode == VideoBitrateMode.CINEMA_MASTER &&
            resolveVideoRecordingTenBitRequested() &&
            recording.codec != VideoCodecType.HEVC
        ) {
            setVideoCodec(VideoCodecType.HEVC)
        }
        if (isAutomaticBitrateMode(mode)) {
            applyAutomaticVideoBitrateIfNeeded(force = true)
        }
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }

    private fun syncRecordingBitrateMode(mode: VideoBitrateMode) {
        _videoBitrateMode.value = mode
        gpuRecording.cinemaMasteringEnabled = mode == VideoBitrateMode.CINEMA_MASTER
        gpuRecording.manualBitrateSelectionEnabled = mode == VideoBitrateMode.CUSTOM
    }
    fun setVideoCodec(c: VideoCodecType)    {
        val requestedCodec = VideoRecordingContractPolicy.resolveRequestedCodec(c).normalizeForStrictHighEnd()
        val parityPlan = activeRecordingPipelineParityPlan()
        val sessionPlan = RecordingCapabilityPolicy.resolveSessionPlan(
            requestedCodec = requestedCodec,
            av1Supported = supportsAv1Fast(),
            hdrRequested = parityPlan.hdrRequestedForSessionPlan,
            rawRequested = parityPlan.rawRequestedForSessionPlan,
            rawSupported = camera.supportsRaw,
            logEnabled = parityPlan.logEnabledForSessionPlan,
            tenBitRequested = parityPlan.tenBitRequestedForSessionPlan
        )
        gpuRecording.videoCodec = sessionPlan.codec
        gpuRecording.tenBitEnabled = sessionPlan.tenBitPipelineEnabled
        recording.codec = RecordingCapabilityPolicy.toVideoCodecType(gpuRecording.videoCodec)
        prefs.videoCodec = recording.codec.name
        prefs.tenBitRecordingEnabled = parityPlan.tenBitRequestedForSessionPlan
        selectedVideoSize?.let { setVideoResolution(it) }
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }
    fun setTenBitRecordingEnabled(enabled: Boolean) {
        if (enabled && recording.codec != VideoCodecType.HEVC) {
            setVideoCodec(VideoCodecType.HEVC)
        }
        val forcedByActiveMode = resolveVideoRecordingTenBitRequested(
            cinemaMode = _cinemaVideoMode.value,
            hdrMode = _videoHdrMode.value
        )
        val resolvedRequested = if (forcedByActiveMode) {
            true
        } else {
            enabled && resolveVideoRecordingTenBitRequested()
        }
        val resolvedPipelineEnabled = RecordingCapabilityPolicy.resolveTenBitPipelineEnabled(
            requested = resolvedRequested,
            codec = gpuRecording.videoCodec
        )
        val hdrFlag = _videoHdrMode.value != "OFF"
        if (
            prefs.tenBitRecordingEnabled == resolvedRequested &&
            gpuRecording.tenBitEnabled == resolvedPipelineEnabled &&
            _videoHdrOn.value == hdrFlag
        ) {
            return
        }
        prefs.tenBitRecordingEnabled = resolvedRequested
        gpuRecording.tenBitEnabled = resolvedPipelineEnabled
        _videoHdrOn.value = hdrFlag
        applyAutomaticVideoBitrateIfNeeded(force = true)
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }
    fun setAudioSrc(s: AudioSrc)            {
        val resolvedSource = s
        recording.audioSource = resolvedSource
        gpuRecording.audioSource = resolvedSource
        prefs.audioSource = when (resolvedSource) {
            AudioSrc.MIC -> "BUILTIN_MIC"
            AudioSrc.UNPROCESSED -> "UNPROCESSED"
            AudioSrc.NONE -> "NONE"
            AudioSrc.CAMCORDER -> "CAMCORDER"
        }
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }

    fun setPreviewAspectRatio(aspectRatio: PreviewAspectRatio) {
        previewController.setPreviewAspectRatio(aspectRatio)
        applyPreviewAspectRatio(aspectRatio, persist = true)
        syncPreviewDomainFromLegacy()
    }

    private fun applyPreviewAspectRatio(
        aspectRatio: PreviewAspectRatio,
        persist: Boolean
    ) {
        val resolvedAspect = aspectRatio
        if (_previewAspect.value == resolvedAspect) {
            if (persist) {
                prefs.previewAspectRatio = resolvedAspect
            }
            if (videoFeatureState.aspect != resolvedAspect) {
                videoFeatureState = videoFeatureState.copy(aspect = resolvedAspect)
            }
            syncPreviewDomainFromLegacy()
            return
        }
        _previewAspect.value = resolvedAspect
        if (persist) {
            prefs.previewAspectRatio = resolvedAspect
        }
        videoFeatureState = videoFeatureState.copy(aspect = resolvedAspect)
        applyAutomaticVideoBitrateIfNeeded(force = true)
        syncGeometryTargets()
        val recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping
        refreshPreviewPipelineIfNeeded(forceSessionRefresh = !recordingActive)
        syncPreviewDomainFromLegacy()
    }

    fun setDeviceRotationDegrees(degrees: Int) {
        currentRotationDegrees = ((degrees % 360) + 360) % 360
        camera.setDeviceRotationDegrees(degrees)
        applyFrameGeometry(camera.frameGeometry.value)
    }

    fun refreshGeometry() {
        syncGeometryTargets()
    }

    private fun applyColorPipelineState(
        colorProfile: String,
        lutEnabled: Boolean,
        lutProfile: String,
        logEnabled: Boolean
    ) {
        val effectiveLutEnabled = when {
            !lutEnabled -> false
            lutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true) -> _customLutAvailable.value
            else -> true
        }
        _colorProfile.value = colorProfile
        _lutEnabled.value = effectiveLutEnabled
        _lutProfile.value = lutProfile
        colorScience.loadBuiltInProfile(colorProfile)
        if (!effectiveLutEnabled) {
            colorScience.setLutToggleEnabled(false)
        } else if (!lutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true)) {
            colorScience.setBuiltInLut(lutProfile)
        } else {
            colorScience.setLutToggleEnabled(true)
        }
        colorScience.logEnabled = isVideoLogPreviewActive()
        syncGpuLutState()
    }

    private fun restorePersistedCustomLut(loadContent: Boolean = true): Boolean {
        val path = prefs.customLutPath ?: run {
            _customLutAvailable.value = false
            return false
        }
        val file = File(path)
        val loaded = when {
            !file.isFile -> false
            !loadContent -> true
            else -> colorScience.loadCubeLUTBlocking(file)
        }
        _customLutAvailable.value = loaded
        if (!loaded) {
            prefs.customLutPath = null
            if (prefs.lutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true)) {
                prefs.lutEnabled = false
                prefs.lutProfile = "Neutral Film"
            }
        }
        return loaded
    }

    private fun syncGpuLutState() {
        val previewFpsHint = lastPreviewFpsSample.takeIf { it > 0f } ?: VIDEO_IDLE_PREVIEW_FPS.toFloat()
        val recordingActive =
            gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused ||
                gpuRecordState.value is GpuRecordingState.Stopping
        val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
        val deliveryState =
            if (videoSurface) activeVideoFormatDeliveryState() else VideoFormatDeliveryState()
        val previewLookState =
            if (videoSurface) {
                VideoFormatPreviewLookPolicy.resolve(deliveryState)
            } else {
                VideoFormatPreviewLookState()
            }
        val request = PreviewLutRequestPolicy.build(
            uiMode = _uiMode.value,
            deliveryState = deliveryState,
            previewLookState = previewLookState,
            recordingActive = recordingActive,
            previewFpsHint = previewFpsHint,
            colorProfile = _colorProfile.value,
            lutEnabled = _lutEnabled.value,
            lutProfile = _lutProfile.value,
            customLutPath = prefs.customLutPath,
            saturation = colorScience.saturation,
            contrast = colorScience.contrast,
            brightness = colorScience.brightness,
            hueShift = colorScience.hueShift,
            highlights = colorScience.highlights,
            shadows = colorScience.shadows
        )
        // Record-grade stage: push the internal look grade + colour-science grade to the offscreen-EGL
        // recorder so the selected color science can reach recorded SDR pixels. SDR-gated (the
        // native layer also forces LUT strength 0 on HLG/PQ, protecting the proven HDR transport).
        // Pushed UNCONDITIONALLY here, before the preview-request cache early-returns below, so a
        // preview cache hit can never silently skip the record grade.
        val isSdrRecordDelivery = videoSurface &&
            deliveryState.effectiveHdrMode == "OFF" &&
            !deliveryState.logVideoActive &&
            !deliveryState.rawVideoActive
        val recordDevelopLightMaster = RecordDevelopLivePipelinePolicy.active(videoSurface)
        // Full creative colour engine -> recorded SDR pixels (default OFF, reversible). Feeds the COMPLETE
        // ColorScience pipeline (profile look, curves, gamut, tonemap, hue/highlights/shadows, creative
        // LUT, sat/contrast) baked by getPreviewLutFloatArray into the record creative-LUT stage, instead
        // of only the raw .cube. The grade matrix is neutralised below so sat/contrast is not doubled.
        val fullColorRecord =
            !recordDevelopLightMaster &&
                com.neoproc.neoproccamx.rendering.FullColorEngineRecordFlag.enabled &&
                isSdrRecordDelivery
        val recordLutCandidate = when {
            fullColorRecord -> colorScience.getPreviewLutFloatArray(recording = true)
            _lutEnabled.value && isSdrRecordDelivery -> colorScience.getLutFloatArray()
            // Film-print look A/B (default ON): deliver the analytic film-print show-LUT to recorded SDR
            // pixels via the offscreen-EGL creative-LUT stage — the actual vivo record pipeline (the
            // Vulkan NRLI pass that normally carries this look never runs on vivo). Only when the user has
            // no creative LUT of their own; reversible via debug.film_print_look, OFF => today's look.
            // (Under the record-then-develop engine the live lane records FLAT, so this same look is applied
            // OFFLINE by the SDR develop card via RecordDevelopSdrLookPolicy — gated on this same flag.)
            com.neoproc.neoproccamx.rendering.FilmPrintLookRecordFlag.enabled && isSdrRecordDelivery ->
                com.neoproc.neoproccamx.rendering.FilmPrintLookRecordFlag.recordLut
            else -> null
        }
        val recordLut = RecordDevelopLivePipelinePolicy.recordCreativeLut(videoSurface, recordLutCandidate)
        vulkanRuntime.updateRecordCreativeLut(recordLut?.first, recordLut?.second ?: 0)
        // Capture the exact cube baked into the SDR/PRO file so the live preview can show the SAME look
        // (closes the "preview looks gray/flat vs recorded file" WYSIWYG gap). Null for non-SDR delivery
        // (LOG/RAW/HDR handle their own look), so those previews are untouched.
        activeSdrRecordCreativeLut = if (isSdrRecordDelivery && !recordDevelopLightMaster) recordLut else null
        val recordGrade = RecordGradeBaker.bake(
            saturation = if (fullColorRecord) 1f else colorScience.saturation,
            contrast = if (fullColorRecord) 1f else colorScience.contrast,
            warmth = 0f,
            isNeutral = recordDevelopLightMaster || fullColorRecord || colorScience.isNeutralLook() || !isSdrRecordDelivery
        )
        vulkanRuntime.setOffscreenRecordGrade(recordGrade.matrix, recordGrade.contrast)
        if (request == cachedPreviewLutRequest) {
            runOnMainIfNeeded {
                applyResolvedPreviewLut(cachedPreviewLut)
            }
            return
        }
        if (request == pendingPreviewLutRequest) return
        val revision = previewLutSyncRevision + 1L
        previewLutSyncRevision = revision
        pendingPreviewLutRequest = request
        previewLutSyncJob?.cancel()
        previewLutSyncJob = viewModelScope.launch(Dispatchers.Default) {
            val lut = if (request.neutralVideoPreview) {
                null
            } else {
                colorScience.getPreviewLutFloatArray(
                    previewFps = previewFpsHint,
                    recording = recordingActive
                )
            }
            if (revision != previewLutSyncRevision) return@launch
            cachedPreviewLut = lut
            cachedPreviewLutRequest = request
            pendingPreviewLutRequest = null
            runOnMain {
                if (revision != previewLutSyncRevision) return@runOnMain
                applyResolvedPreviewLut(lut)
            }
        }
    }

    private fun applyResolvedPreviewLut(lut: Pair<FloatArray, Int>?) {
        nativePreviewLutEnabled = lut != null
        nativePreviewLutSize = lut?.second ?: 0
        vulkanRuntime.updatePreviewLut(
            lutData = lut?.first,
            size = nativePreviewLutSize
        )
        val previewView = previewViewRef
        if (lut != null) {
            previewView?.setLutTexture(lut.first, lut.second)
        } else {
            previewView?.setLutTexture(null, 0)
        }
        previewView?.enableLUT(lut != null)
        // WYSIWYG preview colour parity (default OFF): feed the SAME display-referred cube the (disabled)
        // Vulkan preview path receives into the LIVE 8-bit GL compositor's creative-LUT stage. The shader
        // applies it ONLY in SDR_ENHANCE; null (flag OFF / neutral look) clears it -> byte-identical.
        val parityOn = com.neoproc.neoproccamx.rendering.PreviewColorParityFlag.enabled
        // Resolve ONE cube so first/size can never diverge: prefer the live preview cube; when it is null
        // (neutral SDR/PRO — the gray case) fall back to the exact cube the file bakes so preview == file.
        val previewCube = if (parityOn) (lut ?: activeSdrRecordCreativeLut) else null
        previewGlCompositor.setCreativeLut(previewCube?.first, previewCube?.second ?: 0)
        applyFrameGeometry(camera.frameGeometry.value)
    }

    private fun refreshPreviewCompositorState(rebuildLut: Boolean = false) {
        if (modeSwitchPreviewWorkCoalescer.requestCompositorRefresh(rebuildLut)) return
        if (rebuildLut) {
            syncGpuLutState()
        } else {
            applyFrameGeometry(camera.frameGeometry.value)
        }
    }

    fun beginModeSwitchPreviewWorkBatch() {
        markGlPreviewOwnerCooldown(GL_PREVIEW_MODE_SWITCH_OWNER_COOLDOWN_MS)
        modeSwitchPreviewWorkCoalescer.begin()
    }

    fun endModeSwitchPreviewWorkBatch() {
        // Re-evaluate the GL preview compositor against the NOW fully-settled mode state, while the
        // coalescer batch is still open (depth > 0). During the switch, per-setter syncs can run with
        // stale fields (e.g. NDR→LOG resolves IDENTITY because the HDR flags clear only later in the
        // preset), so the engage/disengage would be missed. Doing it here — after every preset field is
        // applied — lets an engage/disengage register its forced session refresh into THIS batch's single
        // flush, so it rides the batch's own session rebuild (no extra teardown, no downgrade).
        syncGlPreviewCompositorBinding()
        val flush = modeSwitchPreviewWorkCoalescer.end()
        when (flush.compositorRefresh) {
            PreviewCompositorRefresh.LUT -> refreshPreviewCompositorState(rebuildLut = true)
            PreviewCompositorRefresh.GEOMETRY -> refreshPreviewCompositorState(rebuildLut = false)
            PreviewCompositorRefresh.NONE -> Unit
        }
        when (flush.previewPipelineRefresh) {
            PreviewPipelineRefresh.FORCE_SESSION -> refreshPreviewPipelineIfNeeded(
                forceSessionRefresh = true,
                deferSessionRefresh = true
            )
            PreviewPipelineRefresh.STANDARD -> refreshPreviewPipelineIfNeeded(
                forceSessionRefresh = false,
                deferSessionRefresh = true
            )
            PreviewPipelineRefresh.NONE -> Unit
        }
    }

    // Last Camera2 dynamic-range transport pushed to the session, so a look-only HDR<->HDR switch that
    // keeps the same DynamicRangeProfile does not force a (reddish-flashing) session rebuild.
    private var lastAppliedCameraDynamicRangeMode: HdrDynamicRangeMode? = null

    private fun syncCameraPreviewLookControls() {
        val previewLookState = activeVideoPreviewLookState()
        val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
        camera.setVideoPreviewActive(videoSurface)
        camera.setPreviewLookControls(
            logEnabled = previewLookState.logEnabled,
            hdrEnabled = previewLookState.hdrEnabled,
            rawEnabled = previewLookState.rawEnabled
        )
        camera.setCinemaFidelityOverridesEnabled(camera2LowProcessingOverridesActive())
        val currentCameraTransport =
            if (_uiMode.value == AppMode.VIDEO) {
                activeVideoModePipelineContract().cameraDynamicRangeMode
            } else {
                HdrDynamicRangeMode.SDR
            }
        val transportChanged = currentCameraTransport != lastAppliedCameraDynamicRangeMode
        lastAppliedCameraDynamicRangeMode = currentCameraTransport
        // Prime the small analysis size before the controller performs its single combined
        // dynamic-range + NDR-meter session rebuild. This avoids a second teardown during mode changes.
        if (analysisFeedRequested()) {
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        }
        syncCameraHdrDynamicRangeMode()
        if (cameraState.value == CameraState.Streaming) {
            // Only force a session rebuild when the Camera2 dynamic-range transport actually changed.
            // An HDR->HDR look switch with the same transport (NDR10+ -> NIVUKX Vision, both HLG10)
            // needs no rebuild; forcing one flashes a reddish SDR-interpreted HDR frame.
            requestRecordingSurfacePrearm(forceSessionRefresh = transportChanged)
        }
        // Re-evaluate the additive GL preview compositor for the (settled) mode. Runs inside the
        // mode-switch batch so the engage/disengage rides the batch's single session rebuild.
        syncGlPreviewCompositorBinding()
    }

    private fun applyRecordingCameraLookControls() {
        if (!VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)) return
        val previewLookState = activeVideoPreviewLookState()
        camera.setVideoPreviewActive(true)
        camera.setPreviewLookControls(
            logEnabled = previewLookState.logEnabled,
            hdrEnabled = previewLookState.hdrEnabled,
            rawEnabled = previewLookState.rawEnabled
        )
        camera.setCinemaFidelityOverridesEnabled(camera2LowProcessingOverridesActive())
        syncCameraHdrDynamicRangeMode()
    }

    /**
     * Resolve the canonical [HdrDynamicRangeMode] from the user-visible
     * preference fields and forward it to the camera so the next session
     * builds OutputConfigurations with the matching DynamicRangeProfile.
     *
     * Single source of truth — every code path that mutates HDR state
     * (setVideoHdrMode, setLogEnabled, setCinemaVideoMode, mode presets)
     * routes through here via [syncCameraPreviewLookControls].
     *
     * Outside VIDEO mode the dynamic range collapses to SDR — photo
     * capture has its own JPEG/HEIC pipeline.
     */
    private fun syncCameraHdrDynamicRangeMode() {
        val mode = if (_uiMode.value == AppMode.VIDEO) {
            activeVideoModePipelineContract().cameraDynamicRangeMode
        } else {
            HdrDynamicRangeMode.SDR
        }
        camera.setHdrDynamicRangeMode(mode)
        // Keep the Vulkan compositor in sync with the preview render contract,
        // not blindly with Camera2. LOG capture can need 10-bit camera transport
        // while the visible preview must remain a LOG monitor path instead of
        // an HDR display sink.
        vulkanRuntime.setHdrDynamicRangeMode(
            activeVulkanPreviewRenderContract().swapchainDynamicRangeMode
        )
    }

    fun setColorProfile(name: String) {
        val normalized = normalizeColorProfileName(name)
        colorScienceSelectedThisSession = true
        prefs.videoColorScienceManuallySelected = true
        if (_colorProfile.value == normalized && videoFeatureState.colorProfile == normalized) {
            if (prefs.colorProfile != normalized) prefs.colorProfile = normalized
            return
        }
        _colorProfile.value = normalized
        prefs.colorProfile = normalized
        colorScience.loadBuiltInProfile(normalized)
        syncEffectiveRecordingColorProfile()
        applyVideoProfileIntent(normalized)
        videoFeatureState = videoFeatureState.copy(colorProfile = normalized)
        syncGpuLutState()
        pushVulkanPreviewRenderConfig(force = true)
    }

    fun setVideoColorSpace(name: String) {
        val effectiveColorSpace = ManualVideoColorSelectionPolicy.normalizeColorSpace(name)
        if (effectiveColorSpace == ManualVideoColorSelectionPolicy.UNSELECTED) {
            gpuRecording.reportExternalError("Select a valid Video Color Space before recording")
            return
        }
        colorSpaceSelectedThisSession = true
        prefs.videoColorSpaceManuallySelected = true
        val codecUpgradeNeeded = _uiMode.value == AppMode.VIDEO &&
            isWideGamutVideoSpace(effectiveColorSpace) &&
            recording.codec == VideoCodecType.AVC
        if (
            _videoColorSpace.value == effectiveColorSpace &&
            prefs.videoColorSpace == effectiveColorSpace &&
            gpuRecording.videoColorSpace == effectiveColorSpace &&
            !codecUpgradeNeeded
        ) {
            return
        }
        _videoColorSpace.value = effectiveColorSpace
        prefs.videoColorSpace = effectiveColorSpace
        gpuRecording.videoColorSpace = effectiveColorSpace
        if (codecUpgradeNeeded) {
            setVideoCodec(VideoCodecType.HEVC)
        }
        pushVulkanPreviewRenderConfig(force = true)
        refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
        if (_uiMode.value == AppMode.VIDEO && cameraState.value == CameraState.Streaming) {
            requestRecordingSurfacePrearm(forceSessionRefresh = true)
        }
    }

    fun setVideoDetailProfile(profile: VideoDetailProfile) {
        settingsController.setVideoDetailProfile(profile)
        syncSettingsLegacyFromDomain()
        camera.setVideoDetailProfile(profile)
        pushVulkanPreviewRenderConfig(force = true)
        if (_uiMode.value == AppMode.VIDEO && cameraState.value == CameraState.Streaming) {
            requestRecordingSurfacePrearm(forceSessionRefresh = false)
        }
    }

    fun loadCustomLUT(file: File) {
        viewModelScope.launch { importCustomLut(file) }
    }

    suspend fun importCustomLut(file: File): Boolean {
        val imported = colorScience.loadCubeLUT(file)
        if (!imported) return false
        _customLutAvailable.value = true
        _lutEnabled.value = true
        _lutProfile.value = CUSTOM_LUT_PROFILE
        prefs.customLutPath = file.absolutePath
        prefs.lutEnabled = true
        prefs.lutProfile = CUSTOM_LUT_PROFILE
        syncGpuLutState()
        videoFeatureState = videoFeatureState.copy(lutEnabled = true, lutProfile = CUSTOM_LUT_PROFILE)
        return true
    }

    fun setLutEnabled(enabled: Boolean) {
        val targetProfile = _lutProfile.value
        if (!enabled && !_lutEnabled.value) {
            if (prefs.lutEnabled) prefs.lutEnabled = false
            if (videoFeatureState.lutEnabled || videoFeatureState.lutProfile != targetProfile) {
                videoFeatureState = videoFeatureState.copy(lutEnabled = false, lutProfile = targetProfile)
            }
            return
        }
        val customReady = if (targetProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true)) {
            restorePersistedCustomLut()
        } else {
            true
        }
        val effectiveEnabled = enabled &&
            (!targetProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true) || customReady)
        if (
            _lutEnabled.value == effectiveEnabled &&
            prefs.lutEnabled == effectiveEnabled &&
            videoFeatureState.lutEnabled == effectiveEnabled &&
            videoFeatureState.lutProfile == targetProfile
        ) {
            return
        }
        _lutEnabled.value = effectiveEnabled
        prefs.lutEnabled = effectiveEnabled
        if (effectiveEnabled) {
            if (!targetProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true)) {
                colorScience.setBuiltInLut(targetProfile)
            } else {
                colorScience.setLutToggleEnabled(true)
            }
        } else {
            colorScience.setLutToggleEnabled(false)
        }
        syncGpuLutState()
        videoFeatureState = videoFeatureState.copy(lutEnabled = effectiveEnabled, lutProfile = targetProfile)
    }

    fun setLutProfile(name: String) {
        if (name.equals(CUSTOM_LUT_PROFILE, ignoreCase = true)) {
            if (!restorePersistedCustomLut()) return
            _lutProfile.value = CUSTOM_LUT_PROFILE
            prefs.lutProfile = CUSTOM_LUT_PROFILE
            colorScience.setLutToggleEnabled(_lutEnabled.value)
            syncGpuLutState()
            videoFeatureState = videoFeatureState.copy(lutProfile = CUSTOM_LUT_PROFILE)
            return
        }
        val normalized = ColorScience.BUILT_IN_LUTS.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: "Neutral Film"
        _lutProfile.value = normalized
        prefs.lutProfile = normalized
        colorScience.setBuiltInLut(normalized)
        if (!_lutEnabled.value) {
            colorScience.setLutToggleEnabled(false)
        }
        syncGpuLutState()
        videoFeatureState = videoFeatureState.copy(lutProfile = normalized)
    }

    fun setDebugValidationLabel(label: String?) {
        debugValidationLabel = label?.trim()?.takeIf { it.isNotEmpty() }
        gpuRecording.outputLabelSuffix = debugValidationLabel
    }

    fun setLogEnabled(on: Boolean) {
        val modeTarget = when {
            on -> CinemaVideoMode.LOG
            _rawOn.value -> CinemaVideoMode.RAW
            else -> CinemaVideoMode.PRO
        }
        if (_logOn.value == on && _cinemaVideoMode.value == modeTarget) {
            if (prefs.logEnabled != on) prefs.logEnabled = on
            return
        }
        _logOn.value = on
        prefs.logEnabled = on
        colorScience.logEnabled = isVideoLogPreviewActive()
        syncEffectiveRecordingColorProfile()
        videoFeatureState = videoFeatureState.copy(logEnabled = on)
        applyCinemaModeSelection(modeTarget)
        applyAutomaticVideoBitrateIfNeeded(force = true)
        syncCameraPreviewLookControls()
        refreshPreviewCompositorState(rebuildLut = true)
    }
    fun setSaturation(v: Float) {
        prefs.saturation = v
        colorScience.saturation = v
        refreshPreviewCompositorState(rebuildLut = true)
    }

    fun setContrast(v: Float) {
        prefs.contrast = v
        colorScience.contrast = v
        refreshPreviewCompositorState(rebuildLut = true)
    }

    fun setBrightness(v: Float) {
        prefs.brightness = v
        colorScience.brightness = v
        refreshPreviewCompositorState(rebuildLut = true)
    }

    fun setHighlights(v: Float) {
        colorScience.highlights = v
        refreshPreviewCompositorState(rebuildLut = true)
    }

    fun setShadows(v: Float) {
        colorScience.shadows = v
        refreshPreviewCompositorState(rebuildLut = true)
    }

    fun setToneCurve(ch: ColorChannel, pts: List<Pair<Float, Float>>) {
        colorScience.setToneCurve(ch, pts)
        refreshPreviewCompositorState(rebuildLut = true)
    }

    fun clearLUT() {
        colorScience.clearLUT()
        prefs.customLutPath = null
        _customLutAvailable.value = false
        _lutEnabled.value = false
        prefs.lutEnabled = false
        _lutProfile.value = "Neutral Film"
        prefs.lutProfile = "Neutral Film"
        syncGpuLutState()
        videoFeatureState = videoFeatureState.copy(lutEnabled = false, lutProfile = "Neutral Film")
    }

    /** Enable/disable optical image stabilization. */
    fun setOIS(on: Boolean) = setStabilizationMode(if (on) StabilizationMode.OIS else StabilizationMode.OFF)

    fun setStabilizationMode(mode: StabilizationMode) {
        prefs.stabilizationMode = mode
        camera.setStabilizationMode(mode)
        previewViewRef?.setStabilizationStrength(
            when (mode) {
                StabilizationMode.HYBRID -> 0.09f
                StabilizationMode.EIS -> 0.075f
                StabilizationMode.OIS -> 0.045f
                StabilizationMode.OFF -> 0f
            }
        )
    }

    val stabilizationMode: StabilizationMode
        get() = prefs.stabilizationMode

    val faceDetectionEnabled: Boolean
        get() = prefs.faceDetectionEnabled

    val eyeAutofocusEnabled: Boolean
        get() = prefs.eyeAutofocusEnabled

    val aiEnhancementEnabled: Boolean
        get() = _aiEnhancement.value

    val aiMotionCompensationEnabled: Boolean
        get() = _aiMotionCompensation.value

    val highEndProfileEnabled: Boolean
        get() = prefs.highEndProfileEnabled

    val highEndVideoColorPackEnabled: Boolean
        get() = prefs.highEndVideoColorEnabled

    val highEndFocusAiPackEnabled: Boolean
        get() = prefs.highEndFocusAiEnabled

    val highEndMonitoringPackEnabled: Boolean
        get() = prefs.highEndMonitoringEnabled

    val highEndAudioStabilizationPackEnabled: Boolean
        get() = prefs.highEndAudioStabilizationEnabled

    val highEndManualControlPackEnabled: Boolean
        get() = prefs.highEndManualControlsEnabled

    val noiseReductionEnabled: Boolean
        get() = effectiveNoiseReductionEnabled()

    /** Whether the one-shot "Custom Recording Name" feature is armed for the next recording. */
    val customRecordingNameEnabled: Boolean
        get() = prefs.customRecordingNameEnabled

    /** The user-typed one-shot recording name (already sanitized); "" when none is set. */
    val customRecordingName: String
        get() = prefs.customRecordingName

    val isUncompressedCinemaPipelineEnabled: Boolean
        get() = _uncompressedCinemaPipeline.value

    val isPeakingEnabled: Boolean
        get() = _peaking.value

    val isLogEnabled: Boolean
        get() = _logOn.value

    val isRawEnabled: Boolean
        get() = _rawOn.value

    val isVideoHdrEnabled: Boolean
        get() = _videoHdrOn.value

    val isHistogramEnabled: Boolean
        get() = _showHist.value

    val isZebraEnabled: Boolean
        get() = _showZebra.value

    val isWaveformEnabled: Boolean
        get() = _showWfm.value

    val isSafeAreaGuidesEnabled: Boolean
        get() = _safeAreaGuides.value

    val currentLutProfile: String
        get() = _lutProfile.value

    val currentVideoHdrMode: String
        get() = _videoHdrMode.value

    val currentVideoDetailProfile: VideoDetailProfile
        get() = _videoDetailProfile.value

    val currentColorProfile: String
        get() = _colorProfile.value

    val currentVideoColorSpace: String
        get() = _videoColorSpace.value

    val isLutEnabled: Boolean
        get() = _lutEnabled.value

    val isCustomLutAvailable: Boolean
        get() = _customLutAvailable.value

    val isAiEnhancementEnabled: Boolean
        get() = _aiEnhancement.value

    val isLowLightSuggested: Boolean
        get() = _aiLowLightSuggested.value

    val isAiHdrSuggested: Boolean
        get() = _aiHdrSuggested.value

    val isManualControlsVisible: Boolean
        get() = _manualControlsVisible.value

    fun listNpv10MastersForConversion(): List<Npv10MasterClip> =
        npv10Recording.listMasters()

    fun listNpv10MastersForConversion(
        kind: com.neoproc.neoproccamx.recording.RecordDevelopMasterKind
    ): List<Npv10MasterClip> = npv10Recording.listMasters()
        .filter { com.neoproc.neoproccamx.recording.Npv10MasterLanePolicy.resolve(it.file) == kind }

    fun <T> runWithExclusiveConversionResources(work: () -> T): T =
        conversionIsolation.run(work)

    fun convertNpv10MasterToMov(
        clip: Npv10MasterClip,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener? = null
    ): String =
        convertNpv10MasterToMovAndDeleteSource(clip, onProgress)

    fun renderNpv10ThumbnailArgb(
        clip: Npv10MasterClip
    ): com.neoproc.neoproccamx.recording.ThumbnailImage? =
        runCatching { com.neoproc.neoproccamx.recording.Npv10ThumbnailRenderer.render(clip.file) }.getOrNull()

    fun deleteNpv10Master(clip: Npv10MasterClip): Boolean =
        runCatching { clip.file.delete() }.getOrDefault(false)

    // ── Record-then-develop LIGHT intermediates -> SDR/NDR develop cards ─────────
    fun listRecordDevelopMastersForConversion(
        kind: com.neoproc.neoproccamx.recording.RecordDevelopMasterKind
    ): List<com.neoproc.neoproccamx.recording.RecordDevelopMasterClip> =
        gpuRecording.listRecordDevelopMasters(kind)

    fun deleteRecordDevelopMaster(
        clip: com.neoproc.neoproccamx.recording.RecordDevelopMasterClip
    ): Boolean = gpuRecording.deleteRecordDevelopMaster(clip)

    fun hasAnyRecordDevelopMasters(): Boolean =
        runCatching { gpuRecording.hasAnyRecordDevelopMasters() }.getOrDefault(false)

    /** Develop a light-record intermediate to a graded uncompressed 10-bit MOV, then publish it to Albums. */
    fun convertRecordDevelopMaster(
        clip: com.neoproc.neoproccamx.recording.RecordDevelopMasterClip,
        compatV210: Boolean = false,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener? = null
    ): String = convertRecordDevelopMasterToMovAndPublish(
        clip,
        com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV,
        onProgress
    )

    internal fun convertRecordDevelopMaster(
        clip: com.neoproc.neoproccamx.recording.RecordDevelopMasterClip,
        deliveryFormat: com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener? = null
    ): String = convertRecordDevelopMasterToMovAndPublish(clip, deliveryFormat, onProgress)

    /** Decode a record-develop master's first frame into a small ARGB thumbnail (null on any failure). */
    fun renderRecordDevelopThumbnailArgb(
        clip: com.neoproc.neoproccamx.recording.RecordDevelopMasterClip
    ): com.neoproc.neoproccamx.recording.ThumbnailImage? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(clip.file.absolutePath)
            val frame = retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@runCatching null
            val maxDim = 160
            val scale = minOf(maxDim.toFloat() / frame.width, maxDim.toFloat() / frame.height, 1f)
            val w = maxOf(1, (frame.width * scale).toInt())
            val h = maxOf(1, (frame.height * scale).toInt())
            val scaled = frame.scale(w, h, true)
            val argb = IntArray(w * h)
            scaled.getPixels(argb, 0, w, 0, 0, w, h)
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            com.neoproc.neoproccamx.recording.ThumbnailImage(width = w, height = h, argb = argb)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()
    private fun convertRecordDevelopMasterToMovAndPublish(
        clip: com.neoproc.neoproccamx.recording.RecordDevelopMasterClip,
        deliveryFormat: com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener? = null
    ): String {
        require(
            deliveryFormat !=
                com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat.HEVC_MAIN10_422_MOV
        ) {
            "HEVC 10-bit 4:2:2 MOV requires a real P210/Y210 MediaCodec input path. " +
                "Use V210 10-bit 4:2:2 MOV for true 4:2:2 without chroma downgrade."
        }
        val app = getApplication<Application>()
        val outputRoot = File(app.externalCacheDir ?: app.cacheDir, "record_develop_mov_exports")
        val tempOutput = File(
            outputRoot.apply { mkdirs() },
            "${clip.file.nameWithoutExtension}_${deliveryFormat.fileSuffix}." +
                com.neoproc.neoproccamx.recording.RecordDevelopMovConverter.OUTPUT_EXTENSION
        )
        val sidecar = com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.read(clip.file)
        val outputFormat = deliveryFormat.uncompressedFormat
            ?: com.neoproc.neoproccamx.recording.RecordDevelopOutputFormat.R210_444
        val options = com.neoproc.neoproccamx.recording.RecordDevelopConversionOptions(
            outputFormat = outputFormat,
            deliveryFormat = deliveryFormat,
            // SDR develop parity with the live look: restore the creative film-print grade the flat LIGHT
            // intermediate strips, gated on the SAME film-look A/B flag as the live path (NDR is force-zeroed
            // in buildDevelopPlan, so HDR delivery is never double-graded). OFF ⇒ 0f, neutral develop.
            filmPrintStrength =
                com.neoproc.neoproccamx.recording.RecordDevelopSdrLookPolicy.developFilmPrintStrength()
        )
        com.neoproc.neoproccamx.recording.RecordDevelopConversionDiagnostics.logStart(clip, tempOutput, options, sidecar != null, outputRoot, app.getExternalFilesDir(null) ?: outputRoot)
        return try {
            val audit = com.neoproc.neoproccamx.recording.RecordDevelopMovConverter.convert(
                master = clip.file,
                output = tempOutput,
                sidecar = sidecar,
                options = options,
                sourceKind = clip.kind,
                readerFactory = { file ->
                    com.neoproc.neoproccamx.recording.RecordDevelopMediaCodecP010Reader(file)
                },
                onFrame = buildRecordDevelopProgressReporter(clip, onProgress),
                gpuAcceleratorFactory = { context ->
                    com.neoproc.neoproccamx.recording.NativeRecordDevelopGpuAccelerator(context)
                }
            )
            com.neoproc.neoproccamx.recording.RecordDevelopConversionDiagnostics.logDone(audit, tempOutput)
            // Keep the private intermediate after a verified read-only develop so SDR/NDR can be
            // exported repeatedly to R210, V210, HEVC Main10 4:2:0, and HEVC 4:2:2.
            val developVerified = audit.frameCount > 0L &&
                audit.inputMasterHashUnchanged &&
                tempOutput.length() > 0L
            if (!developVerified) {
                Log.w(
                    "CameraViewModel",
                    "Record-develop produced no verifiable master (frames=${audit.frameCount}, " +
                        "hashUnchanged=${audit.inputMasterHashUnchanged}, bytes=${tempOutput.length()}); " +
                        "source intermediate preserved: ${clip.file.absolutePath}"
                )
                throw IllegalStateException(
                    "Record-develop failed to produce a verifiable master; source retained: ${clip.file.absolutePath}"
                )
            }
            val publishedName = publishConvertedRecordDevelopMovToAlbum(tempOutput, clip, deliveryFormat)
            Log.i(
                "CameraViewModel",
                "Record-develop source master preserved for additional exports: ${clip.file.absolutePath}"
            )
            publishedName
        } catch (t: Throwable) {
            com.neoproc.neoproccamx.recording.RecordDevelopConversionDiagnostics.logFailure(clip, tempOutput, options, outputRoot, t)
            throw t
        } finally {
            runCatching {
                com.neoproc.neoproccamx.recording.RecordDevelopMovConverter.certificateSidecarFile(tempOutput).delete()
            }
            runCatching { tempOutput.delete() }
        }
    }

    /** Per-frame progress reporter for a record-develop → MOV develop (null when no listener). */
    private fun buildRecordDevelopProgressReporter(
        clip: com.neoproc.neoproccamx.recording.RecordDevelopMasterClip,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener?
    ): ((Int) -> Unit)? {
        if (onProgress == null) return null
        // HEVC frame count is not O(1)-derivable from geometry; 0 total keeps progress indeterminate.
        val total = com.neoproc.neoproccamx.recording.RecordDevelopMovConverter.estimateFrameCount(clip.file)
        val estimator = com.neoproc.neoproccamx.recording.ConversionEtaEstimator(
            framesTotal = total.toLong(),
            pathLabel = "CPU/GPU hybrid gate",
            startNanos = System.nanoTime()
        )
        onProgress(estimator.startSnapshot())
        return { exactFrames -> onProgress(estimator.onFrameCompleted(System.nanoTime(), exactFrames.toLong())) }
    }

    private fun publishConvertedRecordDevelopMovToAlbum(
        convertedMov: File,
        clip: com.neoproc.neoproccamx.recording.RecordDevelopMasterClip,
        deliveryFormat: com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat
    ): String {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val laneTag = clip.kind.name.lowercase(Locale.US)
        val displayName = "${clip.file.nameWithoutExtension}_${laneTag}_${deliveryFormat.fileSuffix}_dev.mov"
        val nowMs = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/quicktime")
            put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
            put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1000L)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MOVIES}/CamX")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore develop-MOV entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                // Large-buffer copy (byte-identical, reversible): the JDK-default 8 KiB copyTo turned this
                // multi-GB MediaStore/FUSE publish into ~450k tiny writes — the real convert-tail bottleneck.
                convertedMov.inputStream().use { input ->
                    com.neoproc.neoproccamx.recording.RecordDevelopPublishCopyPolicy.copyWithLargeBuffer(input, output)
                }
            } ?: error("Failed to open MediaStore develop-MOV output")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                    },
                    null,
                    null
                )
            }
            _recordingSavedPath.tryEmit(uri.toString())
            return displayName
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun deleteConvertedRecordDevelopSourceMaster(
        clip: com.neoproc.neoproccamx.recording.RecordDevelopMasterClip
    ) {
        // Delete through the lane store so the sibling colour sidecar is removed atomically with the master.
        val deleted = gpuRecording.deleteRecordDevelopMaster(clip)
        if (!deleted && clip.file.exists()) {
            throw IllegalStateException(
                "Converted record-develop intermediate could not be deleted: ${clip.file.absolutePath}"
            )
        }
        Log.i("CameraViewModel", "Deleted converted record-develop intermediate -> ${clip.file.absolutePath}")
    }

    // ── RAW clip -> 10-bit NeoLog MOV / CinemaDNG converter ─────────────────────
    // RAW clips are the recorded 12-bit uncompressed masters (stream.npraw + develop.meta) written by
    // RawVideoManager under <DCIM|Documents>/CamX/NEO_RADIANCE_RAW_*/. Discovery goes through MediaStore
    // (scoped-storage safe — a raw File scan of shared storage returns nothing on Q+); the artifacts are
    // opened via ContentResolver and streamed through the existing single-frame develop cores.
    private val rawClipConversionCoordinator =
        com.neoproc.neoproccamx.recording.Raw12ClipConversionCoordinator()

    private val rawClipDiscovery by lazy {
        com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery(getApplication())
    }

    fun listRawClipsForConversion(): List<com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef> =
        runCatching {
            val discovered = rawClipDiscovery.listClips()
            if (rawVideoManager.state.value is com.neoproc.neoproccamx.pipeline.RawVideoState.Capturing) {
                return@runCatching discovered.filterNot { it.streamPending }
            }
            var recoveredPending = false
            discovered.filter { it.streamPending }.forEach { clip ->
                val recovery = recoverRawClipPreallocatedTail(clip, forceInspect = true)
                if (recovery != null && recovery.recoveredFrames > 0) {
                    val published = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    getApplication<Application>().contentResolver.update(clip.streamUri, published, null, null)
                    recoveredPending = true
                }
            }
            val current = if (recoveredPending) rawClipDiscovery.listClips() else discovered
            current.filterNot { it.streamPending }
        }.getOrDefault(emptyList())

    /** Develop a RAW clip's first frame into a small ARGB thumbnail (null on any failure). */
    fun renderRawClipThumbnailArgb(
        clip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef
    ): com.neoproc.neoproccamx.recording.ThumbnailImage? = runCatching {
        recoverRawClipPreallocatedTail(clip)
        val meta = decodeRawClipMeta(clip)
        val resolver = getApplication<Application>().contentResolver
        val first = resolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
            com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readFrame(input)
        } ?: return@runCatching null
        com.neoproc.neoproccamx.recording.Raw12ThumbnailRenderer.render(first.samples, meta)
    }.getOrNull()

    /** Develop a RAW clip to a 10-bit NeoLog .MOV (chosen look + container) and publish to Movies/CamX. */
    fun convertRawClipToMov(
        clip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef,
        variant: com.neoproc.neoproccamx.core.NeoLogCurve.Variant =
            com.neoproc.neoproccamx.core.NeoLogCurve.Variant.BASE,
        format: com.neoproc.neoproccamx.recording.RawToNeoLogMovConverter.OutputFormat =
            com.neoproc.neoproccamx.recording.RawToNeoLogMovConverter.OutputFormat.MASTER_444,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener? = null
    ): String {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        recoverRawClipPreallocatedTail(clip)
        val meta = decodeRawClipMeta(clip)
        val outputRoot = File(app.cacheDir, "raw_neolog_mov_exports").apply { mkdirs() }
        val suffix =
            if (format == com.neoproc.neoproccamx.recording.RawToNeoLogMovConverter.OutputFormat.MASTER_444) "444" else "v210"
        val tempOutput = File(outputRoot, "${clip.name}_neolog_$suffix.mov")
        // Develop across CPU cores (byte-identical output — same single-frame math per frame); the worker
        // count is bounded so the large per-frame develop working set cannot exhaust memory.
        val parallelism = com.neoproc.neoproccamx.recording.ConversionParallelismPolicy.workerCount(
            frameBytes = rawDevelopWorkingSetBytes(meta)
        )
        val onFrame = buildRawProgressReporter(clip, parallelism, onProgress)
        return try {
            resolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
                rawClipConversionCoordinator.convert(
                    input,
                    meta,
                    com.neoproc.neoproccamx.recording.RawToNeoLogMovConverter.Options(variant = variant, format = format),
                    tempOutput.absolutePath,
                    parallelism = parallelism,
                    onFrame = onFrame
                )
            } ?: error("Failed to open RAW stream for conversion")
            publishConvertedRawMovToAlbum(tempOutput)
        } finally {
            runCatching { tempOutput.delete() }
        }
    }

    /**
     * Export a RAW clip as a CinemaDNG sequence — one standard uncompressed .dng per frame under
     * Documents/CamX/<clip>_CinemaDNG/ (opens directly in DaVinci Resolve / Lightroom). Single-frame,
     * patent-safe: no merge/fusion. The source .npraw master is left in place.
     */
    fun convertRawClipToCinemaDng(
        clip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener? = null
    ): String {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        recoverRawClipPreallocatedTail(clip)
        val meta = decodeRawClipMeta(clip)
        val folderRelative = "${android.os.Environment.DIRECTORY_DOCUMENTS}/CamX/${clip.name}_CinemaDNG"
        // One .dng per frame is written + published to MediaStore serially (the DNG writer is I/O-bound
        // and each frame is an independent file), so this path stays single-threaded; the progress bar
        // + ETA still track it frame-by-frame.
        val report = buildRawProgressReporter(clip, parallelism = 1, onProgress = onProgress)
        var frameCount = 0
        resolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
            while (true) {
                val frame = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readFrame(input) ?: break
                val displayName = com.neoproc.neoproccamx.recording.RawCinemaDngWriter.frameFileName(frameCount)
                val uri = insertPendingCinemaDng(displayName, folderRelative)
                    ?: error("Failed to create CinemaDNG MediaStore entry")
                try {
                    resolver.openOutputStream(uri, "w")?.use { out ->
                        com.neoproc.neoproccamx.recording.RawCinemaDngWriter.writeFrame(out, frame.samples, meta)
                    } ?: error("Failed to open CinemaDNG output")
                    publishPendingMediaStoreEntry(uri)
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
                frameCount++
                report?.invoke(frameCount)
            }
        } ?: error("Failed to open RAW stream for CinemaDNG export")
        if (frameCount == 0) error("RAW clip had no decodable frames")
        _recordingSavedPath.tryEmit(folderRelative)
        return "$frameCount-frame CinemaDNG → $folderRelative"
    }

    /** Delete a whole RAW clip (stream.npraw + develop.meta + any siblings) from MediaStore. */
    fun deleteRawClip(
        clip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef
    ): Boolean = runCatching { rawClipDiscovery.deleteClip(clip) }.getOrDefault(false)

    // ── Conversion progress / ETA plumbing (shared by the RAW + NPV10 converters) ───────────────
    /** Build the per-frame progress reporter for an NPV10 → MOV convert (null when no listener). */
    private fun buildNpv10ProgressReporter(
        clip: Npv10MasterClip,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener?
    ): ((Int) -> Unit)? {
        if (onProgress == null) return null
        val total = com.neoproc.neoproccamx.recording.Npv10MovConverter.estimateFrameCount(clip.file)
        // Label reflects the device's compute capability (the converter chooses GPU-sequential vs
        // CPU-parallel per clip internally, gating on transport + grade); never claims GPU where the
        // native accelerator is absent (e.g. vivo).
        val label = if (com.neoproc.neoproccamx.recording.Npv10ExportGpuAccelerator.isAccelerationAvailable()) {
            "GPU (Vulkan) + CPU"
        } else {
            "CPU (multi-core)"
        }
        val estimator = com.neoproc.neoproccamx.recording.ConversionEtaEstimator(
            framesTotal = total.toLong(),
            pathLabel = label,
            startNanos = System.nanoTime()
        )
        onProgress(estimator.startSnapshot())
        return { exactFrames -> onProgress(estimator.onFrameCompleted(System.nanoTime(), exactFrames.toLong())) }
    }

    /** Build the per-frame progress reporter for a RAW → NeoLog MOV convert (null when no listener). */
    private fun buildRawProgressReporter(
        clip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef,
        parallelism: Int,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener?
    ): ((Int) -> Unit)? {
        if (onProgress == null) return null
        val total = countRawClipFrames(clip)
        val estimator = com.neoproc.neoproccamx.recording.ConversionEtaEstimator(
            framesTotal = total.toLong(),
            pathLabel = "CPU ×$parallelism",
            startNanos = System.nanoTime()
        )
        onProgress(estimator.startSnapshot())
        return { exactFrames -> onProgress(estimator.onFrameCompleted(System.nanoTime(), exactFrames.toLong())) }
    }

    /**
     * A process death can leave an NPRAW file at its aligned 10 GiB reservation length. Inspect only
     * reservation-shaped files, then trim to the last complete CRC-valid frame before decode/export.
     * Normal clean clips skip this pre-pass entirely.
     */
    private fun recoverRawClipPreallocatedTail(
        clip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef,
        forceInspect: Boolean = clip.streamPending
    ): com.neoproc.neoproccamx.pipeline.NprawRecoveryResult? {
        if (rawVideoManager.state.value is com.neoproc.neoproccamx.pipeline.RawVideoState.Capturing) return null
        val resolver = getApplication<Application>().contentResolver
        val pfd = resolver.openFileDescriptor(clip.streamUri, "rw") ?: return null
        val fd = pfd.fileDescriptor
        val originalBytes = android.system.Os.fstat(fd).st_size
        val looksReserved = originalBytes in
            com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy.MIN_RESERVATION_BYTES..
                com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy.HARD_MAX_BYTES &&
            originalBytes % com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy.BLOCK_BYTES == 0L
        if (!forceInspect && !looksReserved) {
            pfd.close()
            return null
        }
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).buffered().use { input ->
            val result = com.neoproc.neoproccamx.pipeline.NprawStreamRecovery.scan(input, originalBytes)
            if (result.shouldTruncate) {
                android.system.Os.ftruncate(fd, result.committedBytes)
                android.system.Os.fsync(fd)
                Log.w(
                    "CameraViewModel",
                    "Recovered NPRAW reservation tail: ${result.originalBytes} -> " +
                        "${result.committedBytes} bytes, frames=${result.recoveredFrames}"
                )
            }
            result
        }
    }

    /** Fast header-only pre-count of a RAW clip's frames for an exact ETA (0 on any failure). */
    private fun countRawClipFrames(
        clip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef
    ): Int = runCatching {
        getApplication<Application>().contentResolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
            com.neoproc.neoproccamx.pipeline.NprawFrameCodec.countFrames(input)
        } ?: 0
    }.getOrDefault(0)

    /** Approximate per-frame develop working set (mono float plane + interleaved RGB shorts + pack). */
    private fun rawDevelopWorkingSetBytes(
        meta: com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
    ): Long = meta.width.toLong() * meta.height.toLong() * 10L

    private fun decodeRawClipMeta(
        clip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef
    ): com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata {
        val resolver = getApplication<Application>().contentResolver
        val bytes = resolver.openInputStream(clip.metaUri)?.use { it.readBytes() }
            ?: error("Failed to open develop.meta")
        return com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.decode(bytes)
    }

    private fun insertPendingCinemaDng(displayName: String, relativePath: String): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return getApplication<Application>().contentResolver
            .insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
    }

    private fun publishPendingMediaStoreEntry(uri: android.net.Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getApplication<Application>().contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        }
    }

    private fun publishConvertedRawMovToAlbum(convertedMov: File): String {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val displayName = convertedMov.name
        val nowMs = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/quicktime")
            put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
            put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1000L)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MOVIES}/CamX")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore RAW-MOV entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                // Large-buffer copy (byte-identical, reversible): the JDK-default 8 KiB copyTo turned this
                // multi-GB MediaStore/FUSE publish into ~450k tiny writes — the real convert-tail bottleneck.
                convertedMov.inputStream().use { input ->
                    com.neoproc.neoproccamx.recording.RecordDevelopPublishCopyPolicy.copyWithLargeBuffer(input, output)
                }
            } ?: error("Failed to open MediaStore RAW-MOV output")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            _recordingSavedPath.tryEmit(uri.toString())
            return displayName
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }
    private fun convertNpv10MasterToMovAndDeleteSource(
        clip: Npv10MasterClip,
        onProgress: com.neoproc.neoproccamx.recording.ConversionProgressListener? = null
    ): String {
        val app = getApplication<Application>()
        val outputRoot = File(app.cacheDir, "npv10_mov_exports")
        val tempOutput = Npv10MovConverter.defaultOutputFile(clip.file, outputRoot)
        return try {
            // P4 Phase A: carry the live ColorScience preview grade into the NPV10 export so the
            // converted MOV matches the WYSIWYG preview (SDR-gated inside the converter). Null when the
            // current look is neutral (needsPreviewPipeline=false) => export unchanged.
            val gradeLut = colorScience.getPreviewLutFloatArray(recording = false)
            val progressReporter = buildNpv10ProgressReporter(clip, onProgress)
            val audit = Npv10MovConverter.convert(
                clip.file,
                tempOutput,
                gradeLut = gradeLut,
                onFrame = { framesDone ->
                    progressReporter?.invoke(framesDone)
                }
            )
            // NO-DOWNGRADE: never delete the irreplaceable 10-bit private master unless the derived
            // MOV passed structural-integrity audit. On FAIL, preserve the master and surface the
            // failure so the clip is retried/inspected rather than silently lost behind a bad export.
            if (audit.auditStatus != Npv10AuditStatus.PASS) {
                Log.w(
                    "CameraViewModel",
                    "NPV10 MOV conversion audit FAILED (status=${audit.auditStatus}); " +
                        "source master preserved: ${clip.file.absolutePath}"
                )
                throw IllegalStateException(
                    "NPV10 MOV conversion audit failed (${audit.auditStatus}); master retained: ${clip.file.absolutePath}"
                )
            }
            val publishedName = publishConvertedNpv10MovToAlbum(tempOutput, clip.file)
            deleteConvertedNpv10SourceMaster(clip.file)
            publishedName
        } finally {
            runCatching { Npv10MovConverter.certificateSidecarFile(tempOutput).delete() }
            runCatching { Npv10MovConverter.focusMapSidecarFile(tempOutput).delete() }
            runCatching { tempOutput.delete() }
        }
    }

    private fun deleteConvertedNpv10SourceMaster(sourceMaster: File) {
        if (!sourceMaster.exists()) return
        if (!sourceMaster.delete() && sourceMaster.exists()) {
            throw IllegalStateException(
                "Converted NPV10 private master could not be deleted: ${sourceMaster.absolutePath}"
            )
        }
        Log.i("CameraViewModel", "Deleted converted NPV10 private master -> ${sourceMaster.absolutePath}")
    }

    private fun publishConvertedNpv10MovToAlbum(convertedMov: File, sourceMaster: File): String {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        require(!Npv10MovPublishPolicy.galleryEligibility(sourceMaster).publishable) {
            "NPV10 private master must not be published to gallery"
        }
        val plan = Npv10MovPublishPolicy.resolveConvertedMov(convertedMov)
        val nowMs = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, plan.displayName)
            put(MediaStore.Video.Media.MIME_TYPE, plan.mimeType)
            put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
            put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1000L)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, plan.relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore MOV entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                // Large-buffer copy (byte-identical, reversible): the JDK-default 8 KiB copyTo turned this
                // multi-GB MediaStore/FUSE publish into ~450k tiny writes — the real convert-tail bottleneck.
                convertedMov.inputStream().use { input ->
                    com.neoproc.neoproccamx.recording.RecordDevelopPublishCopyPolicy.copyWithLargeBuffer(input, output)
                }
            } ?: error("Failed to open MediaStore MOV output")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                }
                resolver.update(uri, publishValues, null, null)
            }
            _recordingSavedPath.tryEmit(uri.toString())
            return plan.displayName
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /**
     * Set audio source by index from VideoSettingsFragment spinner.
     * 0=CAMCORDER, 1=BUILTIN_MIC, 2=UNPROCESSED, 3=NONE
     */
    fun setAudioSourceIndex(idx: Int) {
        setAudioSrc(CameraPreferenceValuePolicy.audioSourceFromIndex(idx))
    }

    fun setHighEndProfileEnabled(enabled: Boolean) = highEndPack.setProfileEnabled(enabled)
    fun setHighEndVideoColorPackEnabled(enabled: Boolean) {
        highEndPack.setVideoColorPackEnabled(enabled)
        if (enabled && prefs.highEndProfileEnabled) syncVideoFeatureStateAfterHighEnd()
    }
    fun setHighEndFocusAiPackEnabled(enabled: Boolean) = highEndPack.setFocusAiPackEnabled(enabled)
    fun setHighEndMonitoringPackEnabled(enabled: Boolean) = highEndPack.setMonitoringPackEnabled(enabled)
    fun setHighEndAudioStabilizationPackEnabled(enabled: Boolean) = highEndPack.setAudioStabilizationPackEnabled(enabled)
    fun setHighEndManualControlPackEnabled(enabled: Boolean) = highEndPack.setManualControlPackEnabled(enabled)

    fun applyHighEndFeaturePack() {
        highEndPack.applyFullPack()
        syncVideoFeatureStateAfterHighEnd()
    }

    private fun syncVideoFeatureStateAfterHighEnd() {
        videoFeatureState = videoFeatureState.copy(
            aspect = _previewAspect.value,
            colorProfile = _colorProfile.value,
            lutEnabled = _lutEnabled.value,
            lutProfile = _lutProfile.value,
            colorSpace = _videoColorSpace.value,
            hdrEnabled = false,
            hdrMode = "OFF",
            cinemaMode = CinemaVideoMode.PRO,
            logEnabled = false,
            rawCompression = _rawVideoCompression.value
        )
    }

    /** Get the combined preview LUT (base profile + optional creative LUT) for GPU upload. */
    fun getLutForGpu(): Pair<FloatArray, Int>? = cachedPreviewLut

    // -------------------------------------------------------------------------
    // OVERLAY TOGGLES
    // -------------------------------------------------------------------------

    fun toggleRaw()      {
        if (!camera.supportsRaw) {
            applyCinemaModeSelection(CinemaVideoMode.PRO)
            syncModeDomainFromLegacy()
            return
        }
        modeController.toggleRawMode(rawSupported = camera.supportsRaw, logEnabled = _logOn.value)
        val enableRawVideo = _cinemaVideoMode.value != CinemaVideoMode.RAW || !_rawOn.value
        applyCinemaModeSelection(
            if (enableRawVideo) {
                CinemaVideoMode.RAW
            } else if (_logOn.value) {
                CinemaVideoMode.LOG
            } else {
                CinemaVideoMode.PRO
            }
        )
        syncModeDomainFromLegacy()
    }
    fun setRawEnabled(enabled: Boolean) {
        val rawRequested = enabled && camera.supportsRaw
        val modeTarget = if (rawRequested) {
            CinemaVideoMode.RAW
        } else if (_logOn.value) {
            CinemaVideoMode.LOG
        } else {
            CinemaVideoMode.PRO
        }
        if (_cinemaVideoMode.value == modeTarget && _rawOn.value == rawRequested) return
        modeController.toggleRawMode(rawSupported = camera.supportsRaw, logEnabled = _logOn.value)
        applyCinemaModeSelection(modeTarget)
        syncModeDomainFromLegacy()
    }
    fun setFaceDetectionEnabled(enabled: Boolean) {
        prefs.faceDetectionEnabled = enabled
        applyAiTrackingPreferences()
    }

    private fun enforceVideoMeteringDefaultIfNeeded(
        uiMode: AppMode = _uiMode.value,
        cinemaMode: CinemaVideoMode = _cinemaVideoMode.value,
        hdrMode: String = _videoHdrMode.value
    ) {
        if (!CameraModeMeteringDefaultPolicy.shouldEnableForVideoMode(uiMode, cinemaMode, hdrMode)) return
        // Face detection is an explicit user setting. Video metering must not silently enable the
        // realtime face/eye stack because that adds Camera2 + AI workload during high-end recording.
        applyAiTrackingPreferences()
    }

    fun setEyeAutofocusEnabled(enabled: Boolean) {
        prefs.faceDetectionEnabled = false
        settingsController.setEyeAutofocusEnabled(enabled)
        applyAiTrackingPreferences()
    }

    fun setAiEnhancementEnabled(enabled: Boolean) {
        settingsController.setAiEnhancementEnabled(effectiveAiEnhancementEnabled(enabled))
        syncSettingsLegacyFromDomain()
        applyAiTrackingPreferences()
    }

    fun setAiMotionCompensationEnabled(enabled: Boolean) {
        settingsController.setAiMotionCompensationEnabled(enabled)
        syncSettingsLegacyFromDomain()
    }

    fun setNoiseReductionEnabled(enabled: Boolean) {
        settingsController.setNoiseReductionEnabled(enabled)
    }

    /** Arm/disarm the one-shot custom recording name (persisted; resets after a successful save). */
    fun setCustomRecordingNameEnabled(enabled: Boolean) {
        prefs.customRecordingNameEnabled = enabled
    }

    /**
     * Store the user's one-shot recording name (sanitized). A non-blank name also arms the feature so
     * the next recording uses it; a blank name disarms it so recordings revert to "CamX ddMMyyyy".
     */
    fun setCustomRecordingName(name: String) {
        val sanitized = com.neoproc.neoproccamx.recording.RecordingFileNamePolicy.sanitizeCustomName(name)
        prefs.customRecordingName = sanitized
        prefs.customRecordingNameEnabled = sanitized.isNotEmpty()
    }

    fun setUncompressedCinemaPipelineEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        if (_uncompressedCinemaPipeline.value) return
        settingsController.setUncompressedCinemaPipelineEnabled(true)
        syncSettingsLegacyFromDomain()
        refreshPreviewPipelineIfNeeded(forceSessionRefresh = false)
    }

    fun setGridMode(mode: GridMode) {
        settingsController.setGridMode(mode)
        syncSettingsLegacyFromDomain()
    }

    fun cycleGrid() {
        settingsController.cycleGrid()
        syncSettingsLegacyFromDomain()
    }

    fun toggleHist() {
        settingsController.toggleHist()
        syncSettingsLegacyFromDomain()
    }

    fun setHistogramEnabled(enabled: Boolean) {
        settingsController.setHistogramEnabled(enabled)
        syncSettingsLegacyFromDomain()
    }

    fun toggleWaveform() {
        settingsController.toggleWaveform()
        syncSettingsLegacyFromDomain()
    }

    fun setWaveformEnabled(enabled: Boolean) {
        settingsController.setWaveformEnabled(enabled)
        syncSettingsLegacyFromDomain()
    }

    fun toggleZebra() {
        settingsController.toggleZebra()
        syncSettingsLegacyFromDomain()
    }

    fun setZebraEnabled(enabled: Boolean) {
        settingsController.setZebraEnabled(enabled)
        syncSettingsLegacyFromDomain()
    }

    fun togglePeaking() {
        settingsController.togglePeaking()
        syncSettingsLegacyFromDomain()
    }

    fun setPeakingEnabled(enabled: Boolean) {
        settingsController.setPeakingEnabled(enabled)
        syncSettingsLegacyFromDomain()
    }

    fun setPeakingIntensity(value: Float) {
        settingsController.setPeakingIntensity(value)
        syncSettingsLegacyFromDomain()
    }

    fun setSafeAreaGuidesEnabled(enabled: Boolean) {
        prefs.safeAreaGuidesEnabled = enabled
        _safeAreaGuides.value = enabled
    }

    fun toggleManualControls() {
        settingsController.toggleManualControls()
        syncSettingsLegacyFromDomain()
        syncCameraPreviewLookControls()
    }

    fun setZebraThresh(v: Float) {
        settingsController.setZebraThresh(v)
        syncSettingsLegacyFromDomain()
    }

    fun setMode(m: AppMode) {
        modeController.setMode(m)
        _uiMode.value = modeController.domainState.value.activeMode
        syncModeDomainFromLegacy()
    }

    internal fun setSdrQualityIntent(intent: SdrQualityIntent) {
        if (_sdrQualityIntent.value == intent) return
        _sdrQualityIntent.value = intent
        pushVulkanPreviewRenderConfig(force = true)
    }

    fun toggleHdrMode() {
        setVideoHdrEnabled(!_videoHdrOn.value)
    }

    fun setVideoHdrEnabled(enabled: Boolean) {
        if (_uiMode.value != AppMode.VIDEO) return
        setVideoHdrMode(if (enabled) "NDR10+" else "OFF")
    }

    fun setVideoHdrMode(mode: String) {
        val previousCinemaMode = _cinemaVideoMode.value
        val previousPipelineSignature = activeVideoModePipelineContract().sessionSignature
        val previousHdrUiMode = _videoHdrMode.value
        val transition = VideoHdrModeTransitionPolicy.resolve(
            requestedMode = mode,
            uiMode = _uiMode.value,
            currentCinemaMode = _cinemaVideoMode.value
        )
        val normalized = transition.normalizedHdrMode
        if (
            transition.cinemaModeOverride == null &&
            _videoHdrMode.value == normalized &&
            _videoHdrOn.value == (normalized != "OFF") &&
            (normalized == "OFF" || isWideGamutVideoSpace(_videoColorSpace.value))
        ) {
            if (prefs.videoHdrMode != normalized) prefs.videoHdrMode = normalized
            return
        }
        transition.cinemaModeOverride?.let { overrideMode ->
            // Normalize the backing cinema mode first, then refresh the session once
            // after the final HDR state is in place so preview does not bounce.
            applyCinemaModeSelection(
                mode = overrideMode,
                persist = false,
                refreshSession = false
            )
        }
        _videoHdrMode.value = normalized
        prefs.videoHdrMode = normalized
        if (normalized != "OFF" && _uiMode.value == AppMode.VIDEO && recording.codec != VideoCodecType.HEVC) {
            setVideoCodec(VideoCodecType.HEVC)
        }
        prefs.tenBitRecordingEnabled = resolveVideoRecordingTenBitRequested(
            cinemaMode = _cinemaVideoMode.value,
            hdrMode = normalized
        )
        gpuRecording.tenBitEnabled = RecordingCapabilityPolicy.resolveTenBitPipelineEnabled(
            requested = resolveVideoRecordingTenBitRequested(
                cinemaMode = _cinemaVideoMode.value,
                hdrMode = normalized
            ),
            codec = gpuRecording.videoCodec
        )
        _videoHdrOn.value = normalized != "OFF"
        syncEffectiveRecordingColorProfile()
        videoFeatureState = videoFeatureState.copy(
            hdrEnabled = _videoHdrOn.value,
            hdrMode = normalized,
            colorSpace = _videoColorSpace.value
        )
        enforceVideoMeteringDefaultIfNeeded(hdrMode = normalized)
        syncCameraPreviewLookControls()
        pushVulkanPreviewRenderConfig(force = true)
        applyAutomaticVideoBitrateIfNeeded(force = true)
        updateCinemaRuntimeStats(lastPreviewFpsSample)
        // FIX: Invalidate LUT cache to force regeneration when switching HDR
        cachedPreviewLutRequest = null
        refreshPreviewCompositorState(rebuildLut = true)

        // HDR mode change rebuilds the OutputConfiguration with a new
        // DynamicRangeProfile (HDR10/HLG/STANDARD). syncCameraHdrDynamicRangeMode()
        // (invoked by syncCameraPreviewLookControls above) already triggers a
        // session refresh when the canonical mode actually changes, so we
        // skip the redundant refresh here. The legacy refresh remains as a
        // safety net for tone-curve-only edge cases (LOG flag toggles
        // without HDR-mode change).
        // NDR10+ <-> NIVUKX Vision (and any HDR<->HDR change with the same Camera2 transport) map to the
        // SAME DynamicRangeProfile (both HLG10). Detect that so the session refresh below is skipped and
        // the preview does not flash a reddish SDR-interpreted HDR frame during a needless rebuild.
        val previousHdrTransport = HdrDynamicRangeMode.fromUiMode(previousHdrUiMode, prefs.logEnabled)
        val currentHdrTransport = HdrDynamicRangeMode.fromUiMode(normalized, prefs.logEnabled)
        val hdrToHdrSameTransport =
            previousHdrTransport == currentHdrTransport &&
                previousHdrTransport != HdrDynamicRangeMode.SDR
        refreshSessionForModeSwitchIfIdle(
            previousMode = previousCinemaMode,
            currentMode = _cinemaVideoMode.value,
            previousPipelineSignature = previousPipelineSignature,
            currentPipelineSignature = activeVideoModePipelineContract().sessionSignature,
            hdrToHdrSameTransport = hdrToHdrSameTransport
        )
    }

    // -------------------------------------------------------------------------
    // CAMERA SWITCH
    // -------------------------------------------------------------------------

    fun resetZoom() {
        pendingZoomRatio = 1f
        camera.setZoom(1f)
    }

    fun switchFacing() {
        val switchPlan = CameraFacingSwitchPolicy.buildPlan(
            currentFacing = camera.facing,
            hasTargetFacing = camera.hasCameraFacing(
                if (camera.facing == CameraCharacteristics.LENS_FACING_BACK) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
            ),
            isRecordingOrStopping = gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused ||
                gpuRecordState.value is GpuRecordingState.Stopping,
            cameraState = cameraState.value
        )
        if (!switchPlan.shouldProceed) {
            if (switchPlan.blockedReason == "recording") {
            Log.w("CameraViewModel", "switchFacing ignored while recording")
            } else {
                Log.w("CameraViewModel", "switchFacing ignored: no camera for facing=${switchPlan.targetFacing}")
            }
            return
        }

        val restart = switchPlan.shouldRestartCamera
        if (restart) {
            val targetFacing = switchPlan.targetFacing
            prepareCameraCloseState()
            cameraCloseJob = viewModelScope.launch(Dispatchers.Default) {
                try {
                    camera.closeCamera()
                    camera.stopBackground()
                    camera.switchFacing()
                    selectedVideoSize = null
                    lastRecordingSelection = null
                    applyPreferences(force = true)
                    runOnMain {
                        previewViewRef?.setLensFacing(targetFacing)
                        syncGeometryTargets()
                    }
                } catch (t: Throwable) {
                    handleCameraError(
                        throwable = t,
                        operation = "switchFacing",
                        userVisibleMessage = "Camera switch failed; reopening the current camera."
                    )
                } finally {
                    cameraCloseJob = null
                    runOnMain { openCamera() }
                }
            }
            return
        }

        camera.switchFacing()
        previewViewRef?.setLensFacing(switchPlan.targetFacing)
        selectedVideoSize = null
        lastRecordingSelection = null
        applyPreferences(force = true)
        syncGeometryTargets()
    }

    private fun syncGeometryTargets() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMainIfNeeded { syncGeometryTargets() }
            return
        }
        val previewView = previewViewRef
        val viewportWidth = previewView?.width ?: 0
        val viewportHeight = previewView?.height ?: 0
        if (previewView != null && (viewportWidth <= 0 || viewportHeight <= 0)) {
            if (!pendingGeometrySyncAfterLayout) {
                pendingGeometrySyncAfterLayout = true
                previewView.post {
                    pendingGeometrySyncAfterLayout = false
                    if (previewViewRef === previewView) {
                        syncGeometryTargets()
                    }
                }
            }
        } else {
            pendingGeometrySyncAfterLayout = false
        }
        val sensorAspect = currentPreviewStreamAspectRatio ?: basePreviewStreamAspectRatio()
        val cropAspect = outputAspectRatio()
        SensorCropEngine.setFullSensorAspectRatio(camera.sensorBaseAspectRatio())
        val framingAspect = cropAspect ?: sensorAspect
        val displayAspect =
            SensorCropEngine.displayAspectForBounds(
                framingAspect,
                viewportWidth,
                viewportHeight
            ) ?: framingAspect

        // DIAGNOSTIC LOGGING FOR HIGH-END GEOMETRY
        android.util.Log.d("NeoProCamX_Geom", "Geometry Sync: " +
            "Sensor=${"%.3f".format(sensorAspect)} " +
            "Crop=${"%.3f".format(cropAspect ?: 0f)} " +
            "Display=${"%.3f".format(displayAspect)} " +
            "View=${viewportWidth}x${viewportHeight} (${"%.3f".format(viewportWidth.toFloat()/viewportHeight.coerceAtLeast(1).toFloat())})")

        camera.targetOutputAspectRatio = cropAspect
        previewView?.setDisplayContentAspectRatioOverride(framingAspect)
        previewView?.setEncoderContentAspectRatioOverride(framingAspect)
        previewView?.setAspectRatioOverride(
            displayAspect,
            fillViewport = shouldFillPreviewViewport()
        )
        syncPreviewViewport(previewView)
        applyFrameGeometry(camera.frameGeometry.value)
        syncPreviewPerformanceMode()
    }

    private fun shouldFillPreviewViewport(): Boolean =
        PreviewViewportFillPolicy.shouldFillViewport(
            appMode = _uiMode.value,
            previewAspectRatio = _previewAspect.value
        )

    private fun prioritizedSubjectFocusX(
        aiSubject: PrimarySubject,
        aiFocus: com.neoproc.neoproccamx.ai.FocusTarget?
    ): Float {
        return aiFocus?.normalizedX ?: aiSubject.focusX
    }

    private fun prioritizedSubjectFocusY(
        aiSubject: PrimarySubject,
        aiFocus: com.neoproc.neoproccamx.ai.FocusTarget?
    ): Float {
        return aiFocus?.normalizedY ?: aiSubject.focusY
    }

    private fun syncPreviewViewport(previewView: CameraVulkanPreviewView?) {
        val view = previewView ?: return
        if (view.width <= 0 || view.height <= 0) return
        camera.setPreviewViewport(
            width = view.width,
            height = view.height,
            contentAspectRatio = view.getResolvedDisplayAspect()
        )
    }

    private fun dispatchManualControlUpdate(action: () -> Unit) {
        manualControlDispatcher.post(action)
    }

    private fun dispatchLatestManualControlUpdate(key: String, action: () -> Unit) {
        manualControlDispatcher.postLatest(key, action)
    }

    private fun runOnMainIfNeeded(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            viewModelScope.launch(Dispatchers.Main.immediate) { block() }
        }
    }

    private suspend fun runOnMain(block: () -> Unit) {
        withContext(Dispatchers.Main.immediate) { block() }
    }

    private fun updatePreviewView(block: CameraVulkanPreviewView.() -> Unit) {
        runOnMainIfNeeded {
            previewViewRef?.apply(block)
        }
    }

    private suspend fun updatePreviewViewAwait(block: suspend CameraVulkanPreviewView.() -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            previewViewRef?.block()
        }
    }

    private fun applyFrameGeometry(geometry: FrameGeometry?) {
        val nativeCompositorActive = shouldUseNativeVulkanPreview()
        val geometryChanged = geometry != lastAppliedFrameGeometry
        val compositorChanged = lastAppliedNativeCompositorActive != nativeCompositorActive
        if (geometryChanged || compositorChanged) {
            updatePreviewView {
                if (geometryChanged) {
                    setFrameGeometry(geometry)
                }
                if (compositorChanged) {
                    setNativeCompositorActive(nativeCompositorActive)
                }
            }
            lastAppliedFrameGeometry = geometry
            lastAppliedNativeCompositorActive = nativeCompositorActive
        }
        pipeline.setFrameGeometry(geometry)
        pushVulkanPreviewRenderConfig(geometry = geometry)
        // RAW debayer-monitor orientation parity (default OFF => identity, byte-identical). Uses the SAME
        // display rotation/mirror the OES/record paths trust (FrameGeometry). Recomputed on every geometry
        // change (device rotation) so RAW stays upright once calibrated ON on-device.
        val debayerOrientOn = com.neoproc.neoproccamx.rendering.RawDebayerPreviewOrientationParityFlag.enabled
        runCatching {
            previewGlCompositor.setDebayerOrientation(
                if (debayerOrientOn && geometry != null) geometry.previewRotationDegrees else 0,
                debayerOrientOn && geometry?.previewMirrored == true
            )
        }
    }

    private fun pushVulkanPreviewRenderConfig(
        geometry: FrameGeometry? = camera.frameGeometry.value,
        force: Boolean = false
    ) {
        val renderContract = activeVulkanPreviewRenderContract()
        val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
        val neoColorScienceEnabled = videoSurface
        val neoHdrMappingEnabled = renderContract.normalizedHdrMode != "OFF"
        val nivukxVision =
            renderContract.normalizedHdrMode.equals("NIVUKXVISION", ignoreCase = true)
        val ndrNivukxMeasuredCorrection =
            NdrNivukxMeasuredCorrectionPolicy.resolve(renderContract.normalizedHdrMode)
        val signatureChanged = renderContract.signature != lastVulkanPreviewRenderSignature
        val previewGrade = buildVulkanPreviewGrade(renderContract)
        val encoderGradeCandidate = VulkanPreviewRecordingParityPolicy.resolveEncoderGrade(
            previewGrade = previewGrade,
            encoderGrade = buildVulkanEncoderGrade(),
            videoModeActive = videoSurface,
            strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly,
            monitorOnlyToneDeliveryActive = renderContract.previewMonitoringEnabled
        )
        val encoderGrade = RecordDevelopLivePipelinePolicy.encoderGrade(videoSurface, encoderGradeCandidate)
        val previewColorScience = VulkanColorScienceUniformPolicy.resolve(
            enabled = neoColorScienceEnabled,
            hdrMappingEnabled = neoHdrMappingEnabled,
            logDeliveryEnabled = false,
            nivukxVision = nivukxVision,
            hdrKnee = if (nivukxVision) 0.55f else 0.59f,
            hdrShoulder = if (nivukxVision) 4.12f else 3.72f,
            volumeCompression = if (nivukxVision) 0.64f else 0.54f,
            colorSpaceName = _videoColorSpace.value,
            colorScienceName = _colorProfile.value,
            measuredCorrection = ndrNivukxMeasuredCorrection
        ).pack()
        val encoderColorScienceCandidate = VulkanColorScienceUniformPolicy.resolve(
            enabled = neoColorScienceEnabled,
            hdrMappingEnabled = neoHdrMappingEnabled,
            logDeliveryEnabled = renderContract.logCaptureActive,
            nivukxVision = nivukxVision,
            hdrKnee = if (nivukxVision) 0.55f else 0.59f,
            hdrShoulder = if (nivukxVision) 4.12f else 3.72f,
            volumeCompression = if (nivukxVision) 0.64f else 0.54f,
            colorSpaceName = _videoColorSpace.value,
            colorScienceName = _colorProfile.value,
            measuredCorrection = ndrNivukxMeasuredCorrection
        ).pack()
        val encoderColorScienceCandidateParity = VulkanPreviewRecordingParityPolicy.resolveEncoderColorScience(
            previewColorScience = previewColorScience,
            encoderColorScience = encoderColorScienceCandidate,
            videoModeActive = videoSurface,
            logDeliveryActive = renderContract.logCaptureActive,
            strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        )
        val encoderColorScience = RecordDevelopLivePipelinePolicy.encoderColorScience(
            videoSurface,
            encoderColorScienceCandidateParity
        )
        lastPreviewGradeSha256 =
            CameraViewModelRenderIdentityPolicy.colorUniformSha256(previewGrade)
        lastRecordingGradeSha256 =
            CameraViewModelRenderIdentityPolicy.colorUniformSha256(encoderGrade)
        lastPreviewColorUniformSha256 =
            CameraViewModelRenderIdentityPolicy.colorUniformSha256(previewColorScience)
        lastRecordingColorUniformSha256 =
            CameraViewModelRenderIdentityPolicy.colorUniformSha256(encoderColorScience)
        lastMonitorDeliveryColorUniformDifferenceAllowed =
            VulkanPreviewRecordingParityPolicy.isAllowedMonitorDeliveryColorUniformDelta(
                previewColorScience = previewColorScience,
                recordingColorScience = encoderColorScience
            )
        vulkanRuntime.setHdrDynamicRangeMode(renderContract.swapchainDynamicRangeMode)
        // X2/R1: request a 10-bit packed sRGB DISPLAY swapchain for SDR preview (Normal/Pro/Log) so
        // far-field gradients keep 10-bit tonal precision (dithered to 1023) instead of 8-bit
        // banding. Gated on the live strict-high-end default; the native side ignores it for HDR
        // modes (already 10-bit) and falls back byte-identically to 8-bit when the driver advertises
        // no 10-bit SDR display pair. Idempotent: native early-returns when the flag is unchanged.
        vulkanRuntime.setPreferTenBitSdrDisplay(
            HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        )
        vulkanRuntime.updateRenderConfig(
            previewTransform = buildVulkanPreviewTransform(geometry),
            encoderTransform = geometry?.encoderUvTransformMatrix() ?: floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
            ),
            colorGain = buildPreviewColorGain(),
            previewGrade = previewGrade,
            encoderGrade = encoderGrade,
            previewColorScience = previewColorScience,
            encoderColorScience = encoderColorScience
        )
        val profile = currentSceneRenderProfile ?: buildFallbackGpuSceneRenderProfile(renderContract)
        val colorSpaceName = _videoColorSpace.value
        val colorScienceName = _colorProfile.value
        val forceNormalSdrRealtimeLock = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value) &&
            renderContract.normalizedHdrMode == "OFF" &&
            !renderContract.logCaptureActive &&
            !isRawVideoModeActive()
        val signalFamily = if (forceNormalSdrRealtimeLock) {
            GpuSignalFamily.SDR
        } else {
            GpuSignalFamilyResolver.resolve(
                mode = _uiMode.value.name,
                logEnabled = isVideoLogCaptureEnabled(),
                hdrEnabled = isEffectiveVideoHdrRequested(),
                rawEnabled = isRawVideoModeActive(),
                profile = "$colorSpaceName $colorScienceName"
            )
        }
        val enhancementUniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = signalFamily,
            colorSpaceName = colorSpaceName,
            colorScienceName = colorScienceName,
            activeTransport = null,
            iso = cameraParams.value.iso.takeIf { it > 0 }?.toFloat() ?: 100f,
            noiseMetric = profile.noiseMetrics.lumaNoise,
            sensorShotNoiseCoefficient = camera.sensorNoiseModel.shotNoiseCoefficient,
            sensorReadNoiseCoefficient = camera.sensorNoiseModel.readNoiseCoefficient,
            videoDetailProfile = _videoDetailProfile.value,
            subjectDetailPlan = profile.subjectDetailPlan,
            forceNormalSdrRealtimeLock = forceNormalSdrRealtimeLock,
            sdrQualityIntent = if (forceNormalSdrRealtimeLock) {
                _sdrQualityIntent.value
            } else {
                null
            },
            // F-AIENH: when AI Enhancement is ON, route the measured enhancement (perceptual
            // sharpness deficit + estimated noise) into the record sharpen/denoise uniforms.
            // Null result -> zero boost (identity), so OFF is byte-identical to today.
            aiRecordEnhancementActive = _aiEnhancement.value,
            aiMeasuredPerceptualSharpness = _aiEnhancementResult.value?.sharpnessScore ?: 0f,
            aiMeasuredNoiseLevel = _aiEnhancementResult.value?.estimatedNoiseLevel ?: 0f
        )
        val liveGpuEnhancementUniforms =
            RecordDevelopLivePipelinePolicy.enhancementUniforms(videoSurface, enhancementUniforms)
        lastGpuEnhancementUniforms = liveGpuEnhancementUniforms
        vulkanRuntime.updateEnhancementUniforms(liveGpuEnhancementUniforms)
        // HIGH-END: feed the resolved live enhancement uniforms to the recorder. Record-develop mode
        // resolves them to a flat/no-op packet here; the heavy denoise/detail/color stack runs offline.
        val liveEnhanceArray = liveGpuEnhancementUniforms.toFloatArray()
        // Recorded-clip colour fidelity: the strict-high-end SDR path drives chromaDenoise (slot 6)
        // to >=0.90 (up to 0.9997), which the export enhance shader's chroma deadband turns into a
        // near-zero retain (~0.0005..0.10), crushing real colour toward grayscale — the recorded clip
        // looked black-and-white while the preview (a different shader) stayed vivid. Cap ONLY the
        // recorder's chroma-denoise so the deadband smooths chroma noise without destroying chroma signal.
        // In record-develop mode this array is already flat and later passed as null to the ByteBuffer path.
        if (liveEnhanceArray.size > 6) {
            liveEnhanceArray[6] = RecordChromaFidelityPolicy.capRecordChromaDenoise(liveEnhanceArray[6])
        }
        // SNCE v2.0 HVC: fold the bounded perceptual chroma nudge into the recorded-pixel ABI
        // (neo_p010_export_enhance.comp slot 11 = saturation, slot 9 = warmth). No-op when SNCE
        // is neutral/disabled, so recorded pixels are byte-identical unless SNCE is actively nudging.
        if (liveEnhanceArray.size > 11) {
            liveEnhanceArray[11] = snceColorEngine.p010SaturationMul()
            liveEnhanceArray[9] = snceColorEngine.p010WarmthBias()
        }
        gpuRecording.setLiveEnhanceUniforms(
            RecordDevelopLivePipelinePolicy.liveEnhanceUniforms(videoSurface, liveEnhanceArray)
        )
        vulkanRuntime.updateSubjectDetailMask(
            _aiSubjectMask.value.takeIf {
                !RecordDevelopLivePipelinePolicy.active(videoSurface) &&
                _videoDetailProfile.value == VideoDetailProfile.FOCUSED_DETAIL &&
                    profile.subjectDetailPlan?.subjectDetailApplied == true
            }
        )
        if (force || signatureChanged) {
            lastVulkanPreviewRenderSignature = renderContract.signature
        }
    }

    private fun buildFallbackGpuSceneRenderProfile(renderContract: VulkanPreviewRenderContract): SceneRenderProfile {
        val outputRenderSize = Size(
            gpuRecording.videoWidth.coerceAtLeast(recording.width).coerceAtLeast(1),
            gpuRecording.videoHeight.coerceAtLeast(recording.height).coerceAtLeast(1)
        )
            .normalizeForOrientation()
        val sourceRenderSize = preferredVideoSourceSize()
        return renderProfileEngine.build(
            scene = null,
            recommendation = null,
            logEnabled = renderContract.logCaptureActive,
            hdrEnabled = renderContract.normalizedHdrMode != "OFF",
            colorProfile = _colorProfile.value,
            aiEnhancementEnabled = effectiveAiEnhancementEnabled(),
            simulatedTenBitFallback = gpuRecording.simulatedTenBitFallbackActive,
            lutEnabled = _lutEnabled.value,
            lutProfile = _lutProfile.value,
            hdrMode = renderContract.normalizedHdrMode,
            previewFps = lastPreviewFpsSample.takeIf { it > 0f } ?: recording.fps.toFloat(),
            recording = VideoRenderProfileQualityHintPolicy.useRecordingGradeRenderProfile(
                appMode = _uiMode.value,
                recordingState = gpuRecordState.value
            ),
            outputWidth = outputRenderSize.width,
            outputHeight = outputRenderSize.height,
            sourceWidth = sourceRenderSize.width,
            sourceHeight = sourceRenderSize.height,
            videoDetailProfile = _videoDetailProfile.value,
            subjectDetailPlan = null,
            aiMotionCompensationEnabled = _aiMotionCompensation.value
        )
    }

    private fun buildPreviewColorGain(): FloatArray {
        val profile = currentSceneRenderProfile
        val sceneType = _aiSceneType.value
        val baseImportCompensation = CameraImportColorCompensationPolicy.resolve(Build.MODEL)
        val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
        val neutralVideoPreview =
            videoSurface && isVideoFormatNeutralPreviewEnabled()
        val videoHdrPreviewActive = if (neutralVideoPreview) {
            false
        } else {
            videoSurface &&
                (isSafeVideoHdrPreviewActive() || isEffectiveVideoHdrRequested())
        }
        val truthfulVideoDelivery =
            videoSurface &&
                (isRawVideoModeActive() || isVideoLogCaptureEnabled() || isEffectiveVideoHdrRequested())
        val originalColorCriticalPath = truthfulVideoDelivery
        val importCompensation = when {
            // HDR/LOG/RAW delivery should stay original. Do not inject device compensation
            // into the truthful color paths because even small tints show up clearly there.
            originalColorCriticalPath -> baseImportCompensation.attenuate(0f)
            neutralVideoPreview -> baseImportCompensation.attenuate(0.18f)
            else -> baseImportCompensation
        }
        if (originalColorCriticalPath) {
            previewColorGainState = floatArrayOf(1f, 1f, 1f, 0f)
            return previewColorGainState.copyOf()
        }
        val sceneWarmBias = when (sceneType) {
            SceneType.NIGHT, SceneType.INDOOR -> 0.005f
            SceneType.PORTRAIT -> 0.003f
            SceneType.OUTDOOR, SceneType.LANDSCAPE -> 0.0005f
            SceneType.BACKLIGHT -> 0.002f
            else -> 0.0015f
        }
        val warmth = (profile?.warmth ?: 0f) * 0.06f + sceneWarmBias
        val contrast = profile?.contrast ?: 1f
        val saturation = profile?.saturation ?: 1f
        val highlightCompression = profile?.highlightCompression ?: 0f
        val logStrength = profile?.logStrength ?: 0f
        val videoLogPreviewActive = !neutralVideoPreview && isVideoLogPreviewActive()
        val lutPreviewActive = _lutEnabled.value && !neutralVideoPreview && !originalColorCriticalPath
        val hdrBoost = if (videoHdrPreviewActive) 0.010f else 0f
        val normalSdrVideoDelivery =
            videoSurface &&
                !truthfulVideoDelivery &&
                !videoLogPreviewActive &&
                !videoHdrPreviewActive &&
                !lutPreviewActive
        val evPreviewGain =
            2.0.pow((cameraParams.value.ev.toFloat() * 0.24f).toDouble())
                .toFloat()
                .coerceIn(0.60f, 1.80f)
        val standardNaturalSdr =
            neutralVideoPreview ||
                (
                    !videoLogPreviewActive &&
                        !videoHdrPreviewActive &&
                        (
                            !_rawOn.value ||
                                (videoSurface && isVideoFormatNeutralPreviewEnabled())
                            ) &&
                        _colorProfile.value.equals("Natural", ignoreCase = true)
                    )
        val stableNormalOrProSdrColor =
            normalSdrVideoDelivery
        val normalSdrCalibration = NormalSdrColorCalibrationPolicy.ACTIVE_CORRECTION
        val neutralGain = (contrast * 0.10f + saturation * 0.08f + 0.82f).coerceIn(0.99f, 1.01f)
        var red = neutralGain * (1f + warmth * 0.05f + hdrBoost - highlightCompression * 0.003f)
        var green = neutralGain
        var blue = neutralGain * (1f - warmth * 0.04f + hdrBoost * 0.01f)

        if (stableNormalOrProSdrColor) {
            red = normalSdrCalibration.redScale
            green = normalSdrCalibration.greenScale
            blue = normalSdrCalibration.blueScale
        } else if (standardNaturalSdr) {
            val preservedWarmth = when (sceneType) {
                SceneType.NIGHT, SceneType.INDOOR -> 0.004f
                SceneType.PORTRAIT -> 0.002f
                else -> 0.001f
            }
            red = 1f + preservedWarmth
            green = 1f
            blue = 1f - preservedWarmth * 0.20f
        }

        if (!stableNormalOrProSdrColor) {
            red *= evPreviewGain
            green *= evPreviewGain
            blue *= evPreviewGain

            red *= importCompensation.redScale
            green *= importCompensation.greenScale
            blue *= importCompensation.blueScale
        }

        if (videoLogPreviewActive) {
            val logPreviewGain = (1f + logStrength * 0.012f).coerceIn(1.0f, 1.02f)
            red *= logPreviewGain
            green *= logPreviewGain
            blue *= logPreviewGain
        }

        if (lutPreviewActive) {
            when (_lutProfile.value.trim().lowercase(Locale.US)) {
                "neutral film" -> {
                    red *= 1.001f
                    green *= 1.001f
                    blue *= 1.001f
                }
                "warm cinema" -> {
                    red *= 1.006f
                    green *= 1.0f
                    blue *= 0.994f
                }
                "teal & orange" -> {
                    red *= 0.996f
                    green *= 1.002f
                    blue *= 1.006f
                }
            }
        }

        val targetStrength = when {
            originalColorCriticalPath -> 0f
            stableNormalOrProSdrColor -> 0f
            standardNaturalSdr -> 0f
            lutPreviewActive -> 0.04f
            videoLogPreviewActive -> 0.03f
            videoHdrPreviewActive -> 0.04f
            else -> 0.03f
        }
        val targetBlendStrength = when {
            originalColorCriticalPath -> 0f
            stableNormalOrProSdrColor -> 0f
            else -> maxOf(targetStrength, importCompensation.blendStrength).coerceIn(0f, 1f)
        }
        val target = floatArrayOf(
            red.coerceIn(0.60f, 1.80f),
            green.coerceIn(0.60f, 1.80f),
            blue.coerceIn(0.60f, 1.80f),
            targetBlendStrength
        )
        if (stableNormalOrProSdrColor) {
            previewColorGainState = target.copyOf()
            return previewColorGainState.copyOf()
        }
        val smoothing = when {
            standardNaturalSdr && gpuRecordState.value is GpuRecordingState.Recording -> 0.12f
            standardNaturalSdr -> 0.18f
            gpuRecordState.value is GpuRecordingState.Recording -> 0.16f
            _uiMode.value == AppMode.VIDEO -> 0.20f
            else -> 0.24f
        }
        for (index in target.indices) {
            previewColorGainState[index] += (target[index] - previewColorGainState[index]) * smoothing
        }
        return previewColorGainState.copyOf()
    }

    private fun resolvedAspectRatio(viewWidth: Int, viewHeight: Int): Float? =
        _previewAspect.value.resolve(viewWidth, viewHeight)

    private fun outputAspectRatio(): Float? =
        recordingOutputAspectRatioOverride ?: SensorCropEngine.targetAspectRatio(_previewAspect.value)

    private fun selectedRecordingAspectRatio(): Float? =
        SensorCropEngine.targetAspectRatio(_previewAspect.value)
            ?: SensorCropEngine.targetAspectRatio(prefs.previewAspectRatio)

    private fun normalizedAspectRatio(width: Int, height: Int): Float {
        val longEdge = maxOf(width, height).coerceAtLeast(1)
        val shortEdge = minOf(width, height).coerceAtLeast(1)
        return longEdge.toFloat() / shortEdge.toFloat()
    }

    private fun applyRecordingOutputAspectOverride(encoderSize: Size?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMainIfNeeded { applyRecordingOutputAspectOverride(encoderSize) }
            return
        }
        val nextAspect = RecordingOutputAspectPolicy.resolveOverride(
            previewAspectRatio = _previewAspect.value,
            encoderSize = encoderSize
        )
        val currentAspect = recordingOutputAspectRatioOverride
        val unchanged = when {
            currentAspect == null && nextAspect == null -> true
            currentAspect != null && nextAspect != null -> abs(currentAspect - nextAspect) < 0.0001f
            else -> false
        }
        if (unchanged) return
        recordingOutputAspectRatioOverride = nextAspect
        syncGeometryTargets()
    }

    fun previewSourceAspect(viewWidth: Int, viewHeight: Int): Float? =
        previewController.previewSourceAspect(viewWidth, viewHeight)

    private fun resolvePreviewSourceAspect(viewWidth: Int, viewHeight: Int): Float? =
        resolveDisplayRatio(
            ratio = currentPreviewStreamAspectRatio ?: basePreviewStreamAspectRatio(),
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )

    private fun snapshotCurrentModeState() {
        videoFeatureState = videoFeatureState.copy(
            rawEnabled = _rawOn.value,
            logEnabled = _logOn.value,
            hdrEnabled = _videoHdrOn.value,
            histogramEnabled = _showHist.value,
            waveformEnabled = _showWfm.value,
            zebraEnabled = _showZebra.value,
            peakingEnabled = _peaking.value,
            aspect = _previewAspect.value,
            colorProfile = _colorProfile.value,
            lutEnabled = _lutEnabled.value,
            lutProfile = _lutProfile.value,
            colorSpace = _videoColorSpace.value,
            hdrMode = _videoHdrMode.value,
            cinemaMode = _cinemaVideoMode.value,
            rawCompression = _rawVideoCompression.value
        )
    }

    private fun restoreVideoFeatures(persist: Boolean = true) {
        val restoredCinemaMode = RecordingCapabilityPolicy.normalizeCinemaMode(
            requestedMode = RecordingCapabilityPolicy.deriveCinemaMode(
                rawRequested = videoFeatureState.rawEnabled || videoFeatureState.cinemaMode == CinemaVideoMode.RAW,
                rawSupported = camera.supportsRaw,
                logEnabled = videoFeatureState.logEnabled || videoFeatureState.cinemaMode == CinemaVideoMode.LOG
            ),
            rawSupported = camera.supportsRaw
        )
        _previewAspect.value = videoFeatureState.aspect
        _showHist.value = false
        _showWfm.value = false
        _showZebra.value = false
        _peaking.value = false
        _videoColorSpace.value = videoFeatureState.colorSpace
        val restoredHdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(videoFeatureState.hdrMode)
        val restoredVideoMode = VideoModeRestorePolicy.resolve(
            requestedHdrMode = restoredHdrMode,
            hdrRequested = videoFeatureState.hdrEnabled && restoredHdrMode != "OFF",
            uiMode = _uiMode.value,
            requestedCinemaMode = restoredCinemaMode,
            rawSupported = camera.supportsRaw
        )
        _videoHdrMode.value = restoredVideoMode.hdrMode
        _videoHdrOn.value = restoredVideoMode.hdrEnabled
        _rawVideoCompression.value = normalizeRawCompressionForMode(
            videoFeatureState.rawCompression,
            restoredVideoMode.cinemaMode
        )
        applyCinemaModeSelection(restoredVideoMode.cinemaMode, persist = false)
        applyColorPipelineState(
            colorProfile = _colorProfile.value,
            lutEnabled = videoFeatureState.lutEnabled,
            lutProfile = videoFeatureState.lutProfile,
            logEnabled = _logOn.value
        )
        gpuRecording.videoColorSpace = _videoColorSpace.value
        syncEffectiveRecordingColorProfile()
        rawVideoManager.compressionMode = _rawVideoCompression.value
        if (persist) {
            prefs.rawEnabled = _rawOn.value
            prefs.previewAspectRatio = _previewAspect.value
            prefs.histogramVisible = _showHist.value
            prefs.waveformVisible = _showWfm.value
            prefs.zebraVisible = _showZebra.value
            prefs.focusPeakingEnabled = _peaking.value
            prefs.logEnabled = _logOn.value
            prefs.tenBitRecordingEnabled = resolveVideoRecordingTenBitRequested()
            prefs.videoColorSpace = _videoColorSpace.value
            prefs.videoHdrMode = _videoHdrMode.value
            prefs.colorProfile = _colorProfile.value
            prefs.lutEnabled = _lutEnabled.value
            prefs.lutProfile = _lutProfile.value
            prefs.videoMode = _cinemaVideoMode.value.name
            prefs.rawVideoCompression = _rawVideoCompression.value.name
        }
        syncCameraPreviewLookControls()
        syncMonitoringPipelineState()
        applyAiTrackingPreferences()
        refreshPreviewCompositorState()
        syncGeometryTargets()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }

    private fun syncMonitoringPipelineState() {
        previewViewRef?.setZebraEnabled(effectiveZebraEnabled())
        previewViewRef?.setZebraThreshold(_zebraT.value)
        previewViewRef?.setPeakingEnabled(effectivePeakingEnabled())
        previewViewRef?.setPeakingIntensity(_peakingIntensity.value)
        if (!effectivePeakingEnabled()) {
            previewViewRef?.clearPeakingFocusRegion()
        }
        pipeline.setWaveformEnabled(effectiveWaveformEnabled())
        pipeline.setZebraEnabled(effectiveZebraEnabled())
        pipeline.setZebraThreshold(_zebraT.value)
        pipeline.setPeakingEnabled(effectivePeakingEnabled())
        pipeline.setPeakingIntensity(_peakingIntensity.value)
        if (!effectivePeakingEnabled()) {
            pipeline.clearPeakingFocusRegion()
        }
        syncPreviewAnalysisFeed()
    }

    /**
     * True when any HUD monitor is active and should be fed live frames. Histogram/waveform/zebra/
     * peaking all read the CPU analysis pipeline, which only runs when the camera attaches a YUV
     * analysis stream. Gated by the startup feature gate so we never rebuild the session mid-startup.
     */
    private fun analysisFeedRequested(): Boolean =
        _uiMode.value == AppMode.VIDEO &&
            Bt2100TransportPolicy.isNeoBt2100HdrVersion(_videoHdrMode.value)

    /**
     * Ask the camera to attach (or release) the CPU-analysis YUV stream that backs the HUD monitors.
     * The controller hot-rebuilds the session only on an actual transition, so calling this on every
     * settings sync is cheap. Also refresh the downscaled analysis target size so the added stream
     * stays small.
     */
    private fun syncPreviewAnalysisFeed() {
        val requested = analysisFeedRequested()
        if (requested) {
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        }
        camera.setPreviewAnalysisRequested(requested)
    }

    private fun effectiveWaveformEnabled(): Boolean = false

    private fun effectiveZebraEnabled(): Boolean = false

    private fun effectivePeakingEnabled(): Boolean = false

    private fun sensorSpaceAspectRatio(
        @Suppress("UNUSED_PARAMETER") viewWidth: Int,
        @Suppress("UNUSED_PARAMETER") viewHeight: Int
    ): Float? {
        return outputAspectRatio()
    }

    private fun basePreviewStreamAspectRatio(): Float =
        SensorCropEngine.normalizeLongShortRatio(camera.sensorBaseAspectRatio())

    private fun resolveDisplayRatio(ratio: Float?, viewWidth: Int, viewHeight: Int): Float? {
        return SensorCropEngine.displayAspectForBounds(ratio, viewWidth, viewHeight)
            ?: ratio?.let { SensorCropEngine.normalizeLongShortRatio(it) }
    }

    private fun chooseSizeForAspect(
        sizes: List<Size>,
        targetAspect: Float?,
        maxArea: Int = Int.MAX_VALUE
    ): Size? =
        CameraPreviewSizeSelectionPolicy.chooseSizeForAspect(
            sizes = sizes,
            targetAspect = targetAspect,
            maxArea = maxArea
        )

    private fun distinctNormalizedSizes(vararg groups: List<Size>): List<Size> =
        CameraPreviewSizeSelectionPolicy.distinctNormalizedSizes(*groups)

    private fun findNormalizedSizeMatch(
        sizes: List<Size>,
        target: Size
    ): Size? =
        CameraPreviewSizeSelectionPolicy.findNormalizedSizeMatch(
            sizes = sizes,
            target = target
        )

    private fun shouldAttemptNativeVulkanPreview(surface: Surface? = previewSurface): Boolean {
        if (startupDirectPreviewActive || startupFeatureGateActive || directPreviewSurfaceActive) {
            return false
        }
        if (nativePreviewRetryRequiresSurfaceReset && surface != null && surface === previewSurface) {
            return false
        }
        if (cameraOwnsVisiblePreviewSurface(surface)) {
            return false
        }
        val cooldownRemainingMs =
            (nativePreviewFallbackUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return VulkanVisiblePreviewPolicy.resolveActivation(
            runtimeSupported = vulkanSupported.value,
            hasDisplaySurface = surface != null,
            fallbackCooldownRemainingMs = cooldownRemainingMs,
            // The bound preview surface plus imported-frame watchdog is the runtime signal.
            // Failed or stalled native presentation returns to the direct preview path.
            hasPresentationFeedback = surface != null
        ).shouldAttemptNativePreview
    }

    private fun cameraOwnsVisiblePreviewSurface(surface: Surface?): Boolean {
        if (surface == null || camera.getPreviewSurface() !== surface) {
            return false
        }
        return when (cameraState.value) {
            CameraState.Open,
            CameraState.Streaming,
            CameraState.Recording -> true
            else -> false
        }
    }

    private fun stopNativePreviewWatchdog() {
        nativePreviewWatchdogJob?.cancel()
        nativePreviewWatchdogJob = null
    }

    private fun stopNativePreviewRetry() {
        nativePreviewRetryJob?.cancel()
        nativePreviewRetryJob = null
    }

    private fun resetNativePreviewFallbackState() {
        nativePreviewFallbackUntilElapsedMs = 0L
        nativePreviewFallbackCount = 0
        nativePreviewRetryRequiresSurfaceReset = false
        stopNativePreviewRetry()
    }

    private fun clearNativePreviewHandoffState() {
        nativePreviewHandoffPending = false
        nativePreviewSurfaceResizePending = false
        nativePreviewCameraReleaseConfirmed = false
        nativePreviewHandoffSurface = null
        nativePreviewHandoffBindJob?.cancel()
        nativePreviewHandoffBindJob = null
    }

    private fun completeNativePreviewHandoffAfterCameraRelease(
        preferNativePreviewOverride: Boolean?
    ) {
        val pendingSurface = nativePreviewHandoffSurface
        if (!nativePreviewHandoffPending || pendingSurface == null) {
            return
        }
        if (previewSurface !== pendingSurface) {
            clearNativePreviewHandoffState()
            return
        }
        nativePreviewCameraReleaseConfirmed = true
        nativePreviewHandoffBindJob?.cancel()
        nativePreviewHandoffBindJob = viewModelScope.launch {
            delay(VulkanVisiblePreviewPolicy.SURFACE_HANDOFF_SETTLE_MS)
            if (!nativePreviewHandoffPending || previewSurface !== pendingSurface) {
                return@launch
            }
            bindPreviewSurface(
                surface = pendingSurface,
                refreshSession = false,
                preferNativePreviewOverride = preferNativePreviewOverride
            )
            if (!nativeVisiblePreviewActive && directPreviewSurfaceActive) {
                camera.refreshOutputsAndSession(forceRebuild = true)
            }
            nativePreviewHandoffBindJob = null
        }
    }

    private fun completeGlPreviewCompositorHandoffAfterCameraRelease(surface: Surface) {
        if (!glPreviewSurfaceReleasePending || previewSurface !== surface) return
        glPreviewHandoffFallbackJob?.cancel()
        glPreviewHandoffFallbackJob = null
        glPreviewHandoffBindJob?.cancel()
        glPreviewHandoffBindJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            glPreviewVisibleSurfaceReleaseConfirmed = false
            delay(GL_PREVIEW_CAMERA2_RELEASE_SETTLE_MS)
            if (!glPreviewSurfaceReleasePending || previewSurface !== surface) {
                glPreviewHandoffBindJob = null
                return@launch
            }
            glPreviewSurfaceReleasePending = false
            if (recordingStartOrActiveBlocksVisiblePreviewRebind()) {
                glPreviewVisibleSurfaceReleaseConfirmed = false
                camera.setPreviewSurface(surface)
                Log.i("CameraViewModel", "GL preview compositor handoff skipped during recording")
                glPreviewHandoffBindJob = null
                return@launch
            }
            glPreviewVisibleSurfaceReleaseConfirmed = true
            bindPreviewSurface(
                surface = surface,
                refreshSession = false,
                preferNativePreviewOverride = false
            )
            camera.refreshOutputsAndSession(forceRebuild = true)
            glPreviewHandoffBindJob = null
        }
    }

    private fun scheduleGlPreviewCompositorHandoffFallback(surface: Surface) {
        glPreviewHandoffFallbackJob?.cancel()
        glPreviewHandoffFallbackJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(GL_PREVIEW_CAMERA2_RELEASE_FALLBACK_MS)
            if (!glPreviewSurfaceReleasePending || previewSurface !== surface) {
                glPreviewHandoffFallbackJob = null
                return@launch
            }
            Log.w(
                "CameraViewModel",
                "GL preview compositor handoff timed out waiting for Camera2 surface release; " +
                    "keeping direct preview to avoid EGL already-connected freeze"
            )
            glPreviewSurfaceReleasePending = false
            glPreviewVisibleSurfaceReleaseConfirmed = false
            glPreviewEngageRetries = GL_PREVIEW_MAX_ENGAGE_RETRIES
            camera.setPreviewSurface(surface)
            if (cameraPreviewReadyForGlPreviewCompositor()) {
                camera.refreshOutputsAndSession(forceRebuild = true)
            }
            glPreviewHandoffFallbackJob = null
        }
    }

    private fun scheduleNativePreviewRetry(surface: Surface) {
        stopNativePreviewRetry()
        val delayMs =
            (nativePreviewFallbackUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (delayMs <= 0L) return
        nativePreviewRetryJob = viewModelScope.launch {
            delay(delayMs)
            withContext(Dispatchers.Main.immediate) {
                if (previewSurface !== surface) return@withContext
                if (nativeVisiblePreviewActive) return@withContext
                if (cameraState.value != CameraState.Open && cameraState.value != CameraState.Streaming) {
                    return@withContext
                }
                if (directPreviewSurfaceActive) {
                    nativePreviewRetryRequiresSurfaceReset = true
                    stopNativePreviewRetry()
                    Log.d(
                        "CameraViewModel",
                        "Deferring visible Vulkan preview retry until the preview surface resets"
                    )
                    return@withContext
                }
                if (!shouldAttemptNativeVulkanPreview(surface)) return@withContext
                Log.d("CameraViewModel", "Retrying visible Vulkan preview after cooldown")
                bindPreviewSurface(surface)
            }
        }
    }

    private fun markNativePreviewFallback(
        surface: Surface,
        reason: String,
        requiresSurfaceReset: Boolean = false
    ) {
        nativeVisiblePreviewConfirmed = false
        nativePreviewFallbackCount += 1
        nativePreviewRetryRequiresSurfaceReset =
            nativePreviewRetryRequiresSurfaceReset || requiresSurfaceReset
        nativePreviewFallbackUntilElapsedMs =
            SystemClock.elapsedRealtime() +
                VulkanVisiblePreviewPolicy.resolveRetryDelayMs(nativePreviewFallbackCount)
        Log.w("CameraViewModel", "Visible Vulkan preview fallback: $reason")
        if (nativePreviewRetryRequiresSurfaceReset) {
            stopNativePreviewRetry()
        } else {
            scheduleNativePreviewRetry(surface)
        }
    }

    private fun confirmNativePreviewStable() {
        if (nativeVisiblePreviewConfirmed) return
        nativeVisiblePreviewConfirmed = true
        resetNativePreviewFallbackState()
        Log.d("CameraViewModel", "Visible Vulkan preview confirmed stable")
    }

    private fun startNativePreviewWatchdog(surface: Surface) {
        stopNativePreviewWatchdog()
        nativePreviewActivationElapsedMs = SystemClock.elapsedRealtime()
        nativePreviewWatchdogJob = viewModelScope.launch(Dispatchers.Default) {
            var cameraImportStreamObserved = false
            delay(VulkanVisiblePreviewPolicy.WATCHDOG_WARMUP_MS)
            while (isActive && nativeVisiblePreviewActive && previewSurface === surface) {
                val currentCameraState = cameraState.value
                if (!cameraImportStreamObserved) {
                    if (
                        currentCameraState != CameraState.Streaming &&
                            currentCameraState != CameraState.Recording
                    ) {
                        nativePreviewActivationElapsedMs = SystemClock.elapsedRealtime()
                        delay(VulkanVisiblePreviewPolicy.WATCHDOG_INTERVAL_MS)
                        continue
                    }
                    cameraImportStreamObserved = true
                    nativePreviewActivationElapsedMs = SystemClock.elapsedRealtime()
                }
                if (currentCameraState == CameraState.Recording) {
                    delay(VulkanVisiblePreviewPolicy.WATCHDOG_INTERVAL_MS)
                    continue
                }
                val decision = VulkanVisiblePreviewPolicy.evaluateHealth(
                    stats = vulkanImportStats.value,
                    activationElapsedMs = SystemClock.elapsedRealtime() - nativePreviewActivationElapsedMs
                )
                if (decision.shouldFallback) {
                    withContext(Dispatchers.Main.immediate) {
                        if (previewSurface === surface && nativeVisiblePreviewActive) {
                            val requiresSurfaceReset =
                                decision.reason == "Preview surface not bound in Vulkan runtime" ||
                                    decision.reason == "No imported preview frames after activation"
                            markNativePreviewFallback(
                                surface = surface,
                                reason = decision.reason,
                                requiresSurfaceReset = requiresSurfaceReset
                            )
                            bindPreviewSurface(surface)
                        }
                    }
                    return@launch
                }
                if (decision.shouldConfirmStable && !nativeVisiblePreviewConfirmed) {
                    withContext(Dispatchers.Main.immediate) {
                        if (previewSurface === surface && nativeVisiblePreviewActive) {
                            confirmNativePreviewStable()
                        }
                    }
                }
                delay(VulkanVisiblePreviewPolicy.WATCHDOG_INTERVAL_MS)
            }
        }
    }

    // ── Additive per-mode GL preview compositor ───────────────────────────────
    // Preview-only SurfaceTexture→GL→SurfaceView stage that makes LOG preview look like
    // LOG and RAW preview look like a viewable monitor image, WITHOUT touching the record
    // pipeline or the recorded file (preview and record share one repeating request, so a
    // per-mode look cannot be injected via Camera2 tonemap keys). Its own isolated EGL
    // context; bypassed for NDR/HDR (10-bit direct) and normal SDR. Gate default OFF.
    private val previewGlCompositor = PreviewGlCompositor()
    private var glPreviewCompositorActive = false
    // Gated 10-bit BT.2020-PQ WYSIWYG preview (Hdr10PqPreviewPolicy, default OFF). Parallel to the
    // 8-bit compositor above; engaged ONLY for NDR/NIVUKX PQ modes when debug.pq_preview is flipped on.
    private val hdr10PqPreviewCompositor = Hdr10PqPreviewCompositor()
    private var hdr10PqPreviewCompositorActive = false
    // DE-RISK: the 10-bit PQ EGL engage (its ~2s surface creation is the vivo 10-bit-EGL / ANR hazard)
    // runs on a background dispatcher, never the main thread, so engaging can never ANR. True while an
    // async engage is in flight; guards against launching duplicates.
    @Volatile private var hdr10PqPreviewEngageInProgress = false
    private var glPreviewSurfaceReleasePending = false
    private var glPreviewVisibleSurfaceReleaseConfirmed = false
    private var glPreviewHandoffBindJob: Job? = null
    private var glPreviewHandoffFallbackJob: Job? = null
    private var glPreviewBindingResyncJob: Job? = null
    // Bounded so a genuinely stuck engage can't churn the session.
    private val GL_PREVIEW_MAX_ENGAGE_RETRIES = 4
    private val GL_PREVIEW_ENGAGE_RETRY_DELAY_MS = 1_200L
    private val GL_PREVIEW_STARTUP_OWNER_COOLDOWN_MS = 1_500L
    private val GL_PREVIEW_MODE_SWITCH_OWNER_COOLDOWN_MS = 750L
    private var glPreviewOwnerCooldownUntilElapsedMs =
        SystemClock.elapsedRealtime() + GL_PREVIEW_STARTUP_OWNER_COOLDOWN_MS
    // Bounded self-heal for a cross-HDR engage while the SurfaceView holder is settling.
    private var glPreviewEngageRetries = 0
    private var glPreviewEngageRetryScheduled = false
    private val GL_PREVIEW_CAMERA2_RELEASE_SETTLE_MS =
        VulkanVisiblePreviewPolicy.SURFACE_HANDOFF_SETTLE_MS.coerceAtLeast(1_500L)
    private val GL_PREVIEW_CAMERA2_RELEASE_FALLBACK_MS =
        GL_PREVIEW_CAMERA2_RELEASE_SETTLE_MS + 1_500L

    // Canonical PQ delivery mode for the CURRENT video state, or -1 if not an NDR/NIVUKX PQ mode.
    private fun activeNdrPqDeliveryMode(): Int {
        if (_uiMode.value != AppMode.VIDEO || _logOn.value || isRawVideoModeActive()) return -1
        return when (RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(currentVideoHdrMode)) {
            "NDR10+" -> Hdr10PqPreviewCompositor.MODE_NDR10_PLUS
            "NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA" ->
                Hdr10PqPreviewCompositor.MODE_NIVUKX_VISION
            else -> -1
        }
    }

    // Gate for the 10-bit PQ preview compositor. FALSE whenever debug.pq_preview is OFF (default),
    // so the proven preview path stays byte-identical; only an explicit flip engages it.
    private fun shouldCompositeHdr10PqPreview(): Boolean =
        Hdr10PqPreviewPolicy.shouldComposite(
            gateEnabled = Hdr10PqPreviewPolicy.enabled,
            ndrPqDeliveryActive = activeNdrPqDeliveryMode() >= 0,
            nativeVulkanPreviewActive = nativeVisiblePreviewActive,
            glSdrCompositorActive = resolveGlPreviewGrade() != GlVisiblePreviewPolicy.PreviewGrade.IDENTITY
        )

    private fun shouldCompositeVisibleGlPreview(): Boolean =
        shouldCompositeHdr10PqPreview() ||
            resolveGlPreviewGrade() != GlVisiblePreviewPolicy.PreviewGrade.IDENTITY

    private fun releaseHdr10PqPreviewCompositorIfActive() {
        if (hdr10PqPreviewCompositorActive) {
            hdr10PqPreviewCompositor.release()
            hdr10PqPreviewCompositorActive = false
        }
    }

    private fun resolveGlPreviewGrade(): GlVisiblePreviewPolicy.PreviewGrade =
        GlVisiblePreviewPolicy.resolveGrade(
            gateEnabled = GlVisiblePreviewPolicy.enabled,
            // When the genuine NeoLog HAL tonemap is baking N-LOG into the camera stream itself, the
            // shared preview already IS NeoLog (true WYSIWYG) — so the compositor must NOT also apply
            // its NEOLOG grade (that would double-log). RAW preview is unaffected.
            logPreviewActive = isVideoLogPreviewActive() &&
                !com.neoproc.neoproccamx.camera.NeoLogHalTonemapPolicy.enabled,
            rawPreviewActive = isRawVideoModeActive(),
            hdrActive = isVideoHdrEnabled || currentVideoHdrMode != "OFF",
            nativeVulkanPreviewActive = nativeVisiblePreviewActive
        )

    private fun glPreviewGradeCode(grade: GlVisiblePreviewPolicy.PreviewGrade): Int =
        when (grade) {
            GlVisiblePreviewPolicy.PreviewGrade.NEOLOG -> PreviewGlCompositor.GRADE_NEOLOG
            GlVisiblePreviewPolicy.PreviewGrade.RAW_MONITOR -> PreviewGlCompositor.GRADE_RAW_MONITOR
            GlVisiblePreviewPolicy.PreviewGrade.SDR_ENHANCE -> PreviewGlCompositor.GRADE_SDR_ENHANCE
            GlVisiblePreviewPolicy.PreviewGrade.IDENTITY -> PreviewGlCompositor.GRADE_IDENTITY
        }

    private fun recordingStartOrActiveBlocksVisiblePreviewRebind(): Boolean {
        if (recordingStartInProgress.get()) return true
        // A replacement display surface may be rebound during recording only after Camera2 has
        // confirmed that the destroyed surface is absent from the live data-only session.
        if (recordingPreviewRebindInProgress) return false
        val state = gpuRecordState.value
        val recordingActiveOrStopping =
            state is GpuRecordingState.Recording ||
                state is GpuRecordingState.Paused ||
                state is GpuRecordingState.Stopping ||
                cameraState.value == CameraState.Recording
        // Preserve an already-running RAW/LOG monitor compositor, but never create a new GL/PQ owner mid-record.
        val stableVisibleMonitorAlreadyRunning =
            (glPreviewCompositorActive && previewGlCompositor.isRunning()) ||
                (hdr10PqPreviewCompositorActive && hdr10PqPreviewCompositor.isRunning())
        if (
            com.neoproc.neoproccamx.rendering.PreviewGradeWhileRecordingFlag.enabled &&
            shouldCompositeVisibleGlPreview() &&
            stableVisibleMonitorAlreadyRunning
        ) {
            return false
        }
        // Idle offscreen prearm must keep the hidden P010 source attached, but it must not freeze the
        // visible preview into the flat direct Camera2 monitor path. Only an actual recording session
        // uses the preserve guard to block visible-surface churn.
        if (
            shouldPreserveInternalRecordingImportForPreviewBinding() &&
            (recordingActiveOrStopping || activeRecordingFeedPath != null)
        ) {
            return true
        }
        return recordingActiveOrStopping
    }

    private fun glPreviewOwnerCooldownRemainingMs(): Long =
        (glPreviewOwnerCooldownUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private fun glPreviewOwnerTransitionBlocksVisiblePreviewRebind(): Boolean =
        recordingStartOrActiveBlocksVisiblePreviewRebind() || glPreviewOwnerCooldownRemainingMs() > 0L

    private fun markGlPreviewOwnerCooldown(durationMs: Long = 1_500L) {
        glPreviewOwnerCooldownUntilElapsedMs = maxOf(
            glPreviewOwnerCooldownUntilElapsedMs,
            SystemClock.elapsedRealtime() + durationMs
        )
    }

    private fun recordingImportFeedPathToPreserveForPreviewBinding(): RecordingFeedPath? {
        val feedPath = activeRecordingFeedPath ?: if (recordingStartInProgress.get()) {
            activeRecordingPipelineParityPlan().feedPath
        } else {
            null
        }
        if (feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ||
            feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
        ) {
            return feedPath
        }
        if (
            _uiMode.value == AppMode.VIDEO &&
            prearmedRecordingSurface != null &&
            prearmedRecordingSurfaceAttachedToSession &&
            prearmedRecordingFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER &&
            shouldKeepRecordingSurfacePrearmed()
        ) {
            return RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
        }
        return null
    }

    private fun shouldPreserveInternalRecordingImportForPreviewBinding(): Boolean =
        recordingImportFeedPathToPreserveForPreviewBinding() != null

    private fun releaseVisibleGlPreviewCompositorsForRecordingFeed(feedPath: RecordingFeedPath) {
        if (feedPath != RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER &&
            feedPath != RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
        ) {
            return
        }
        val releasedSdrCompositor = glPreviewCompositorActive || previewGlCompositor.isRunning()
        if (releasedSdrCompositor) {
            previewGlCompositor.release()
            glPreviewCompositorActive = false
        }
        // Preserve the isolated SDR monitor; it never owns the 10-bit encoder or rebinds mid-record.
        val preservePqMonitor =
            com.neoproc.neoproccamx.rendering.Hdr10PqPreviewSdrOutputFlag.enabled &&
                hdr10PqPreviewCompositorActive &&
                hdr10PqPreviewCompositor.isRunning()
        val releasedPqCompositor =
            !preservePqMonitor &&
                (hdr10PqPreviewCompositorActive || hdr10PqPreviewCompositor.isRunning())
        if (releasedPqCompositor) {
            releaseHdr10PqPreviewCompositorIfActive()
        }
        if (releasedSdrCompositor || releasedPqCompositor || glPreviewSurfaceReleasePending) {
            glPreviewSurfaceReleasePending = false
            glPreviewVisibleSurfaceReleaseConfirmed = false
            glPreviewHandoffBindJob?.cancel()
            glPreviewHandoffBindJob = null
            glPreviewHandoffFallbackJob?.cancel()
            glPreviewHandoffFallbackJob = null
            glPreviewBindingResyncJob?.cancel()
            glPreviewBindingResyncJob = null
            markGlPreviewOwnerCooldown(12_000L)
            if (!preservePqMonitor) {
                previewSurface?.takeIf { runCatching { it.isValid }.getOrDefault(false) }?.let { surface ->
                    camera.setPreviewSurface(surface)
                }
            }
            Log.i(
                "CameraViewModel",
                "Visible GL preview compositor released for ${feedPath.name} recording session"
            )
        }
        if (preservePqMonitor) {
            Log.i(
                "CameraViewModel",
                "NDR PQ compositor preserved through ${feedPath.name} recording session"
            )
        }
    }

    /**
     * RAW's debayer monitor owns the visible EGL surface independently of Camera2. Tear that owner
     * down explicitly on RAW exit before the next mode binds; otherwise its last frame can remain
     * frozen over PRO/LOG while the camera continues streaming to a stale compositor input.
     */
    private fun releaseRawPreviewMonitorForModeExit(
        previousMode: CinemaVideoMode,
        currentMode: CinemaVideoMode
    ) {
        if (previousMode != CinemaVideoMode.RAW || currentMode == CinemaVideoMode.RAW) return
        glPreviewOwnerCooldownUntilElapsedMs = SystemClock.elapsedRealtime()
        runCatching { previewGlCompositor.clearDebayerPresent() }
        previewGlCompositor.release()
        glPreviewCompositorActive = false
        glPreviewSurfaceReleasePending = false
        glPreviewVisibleSurfaceReleaseConfirmed = true
        glPreviewHandoffBindJob?.cancel()
        glPreviewHandoffBindJob = null
        glPreviewHandoffFallbackJob?.cancel()
        glPreviewHandoffFallbackJob = null
        glPreviewBindingResyncJob?.cancel()
        glPreviewBindingResyncJob = null
        stopNativePreviewWatchdog()
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        previewViewRef?.setNativeCompositorActive(false)
        vulkanRuntime.setPreviewSurface(null)
        previewSurface
            ?.takeIf { runCatching { it.isValid }.getOrDefault(false) }
            ?.let { surface ->
                bindPreviewSurface(
                    surface = surface,
                    refreshSession = false,
                    preferNativePreviewOverride = false
                )
            }
        modeSwitchPreviewWorkCoalescer.requestPreviewPipelineRefresh(forceSessionRefresh = true)
    }

    private fun cameraPreviewReadyForGlPreviewCompositor(): Boolean =
        cameraState.value == CameraState.Open ||
            cameraState.value == CameraState.Streaming ||
            cameraState.value == CameraState.Recording

    private fun clearStoppedVisibleGlPreviewCompositorOwnership() {
        var cleared = false
        if (glPreviewCompositorActive && !previewGlCompositor.isRunning()) {
            glPreviewCompositorActive = false
            cleared = true
        }
        if (hdr10PqPreviewCompositorActive && !hdr10PqPreviewCompositor.isRunning()) {
            hdr10PqPreviewCompositorActive = false
            cleared = true
        }
        if (cleared) {
            glPreviewVisibleSurfaceReleaseConfirmed = false
            glPreviewSurfaceReleasePending = false
            glPreviewHandoffFallbackJob?.cancel()
            glPreviewHandoffFallbackJob = null
            Log.w("CameraViewModel", "Visible GL preview compositor ownership reset after render thread stopped")
        }
    }

    private fun visibleGlPreviewSurfaceReadyForNewEglAcquire(visibleSurface: Surface): Boolean {
        if (!cameraPreviewReadyForGlPreviewCompositor()) return false
        if (camera.sessionRefreshPending.get()) return false
        if (recordingStartOrActiveBlocksVisiblePreviewRebind()) return false
        if (glPreviewSurfaceReleasePending) return false
        if (!glPreviewVisibleSurfaceReleaseConfirmed) return false
        if (cameraOwnsVisiblePreviewSurface(visibleSurface)) return false
        return true
    }

    /**
     * Chooses the visible-preview camera target. When a non-identity per-mode grade applies,
     * route the camera into the compositor's SurfaceTexture and drive the composited output to
     * [visibleSurface]; otherwise return false and let the caller feed the camera directly
     * (today's byte-identical path). Falls back to direct on any GL init failure.
     */
    private fun applyGlPreviewCompositorTarget(visibleSurface: Surface, previewSize: Size): Boolean {
        clearStoppedVisibleGlPreviewCompositorOwnership()
        // Gated 10-bit BT.2020-PQ WYSIWYG path (default OFF). When engaged it OWNS the preview
        // surface (its own isolated 10-bit EGL context); the 8-bit compositor is released. Fully
        // no-op when the gate is off — pqMode stays -1 and control falls through unchanged.
        val hdrDynamicRangeState = camera.describeHdrDynamicRangeState()
        val appliedPreviewProfile = NdrInputTransportPolicy.effectivePreviewProfile(
            appliedPreviewProfile = hdrDynamicRangeState.previewProfile,
            resolvedProfileForCurrentMode = hdrDynamicRangeState.resolvedProfileForCurrentMode
        )
        val pqMode = if (
            shouldCompositeHdr10PqPreview() &&
            NdrInputTransportPolicy.isPqProfile(appliedPreviewProfile)
        ) {
            activeNdrPqDeliveryMode()
        } else {
            -1
        }
        if (pqMode >= 0) {
            if (glPreviewCompositorActive) {
                previewGlCompositor.release()
                glPreviewCompositorActive = false
            }
            // Already engaged: keep the PQ surface bound and just refresh the delivery mode (no rebuild).
            if (hdr10PqPreviewCompositorActive && hdr10PqPreviewCompositor.isRunning()) {
                if (cameraOwnsVisiblePreviewSurface(visibleSurface)) {
                    releaseHdr10PqPreviewCompositorIfActive()
                    glPreviewVisibleSurfaceReleaseConfirmed = false
                    scheduleGlPreviewEngageRetry()
                    return false
                }
                hdr10PqPreviewCompositor.setInputProfile(appliedPreviewProfile)
                hdr10PqPreviewCompositor.setMode(pqMode)
                hdr10PqPreviewCompositor.inputSurface()?.let { camera.setPreviewSurface(it) }
                return true
            }
            if (!cameraPreviewReadyForGlPreviewCompositor()) return false
            if (camera.sessionRefreshPending.get()) {
                scheduleGlPreviewEngageRetry()
                return false
            }
            if (!visibleGlPreviewSurfaceReadyForNewEglAcquire(visibleSurface)) {
                scheduleGlPreviewEngageRetry()
                return false
            }
            // SDR-DISPLAY OUTPUT MODE (default ON): the compositor binds a plain 8-bit sRGB window
            // surface (the config proven on this device by PreviewGlCompositor), so there is NO
            // 10-bit-EGL / ANR hazard. Engage SYNCHRONOUSLY on the main thread and return TRUE so the
            // CALLER's session rebuild re-attaches the camera to the compositor's SurfaceTexture —
            // exactly like the proven 8-bit path below. The async engage (kept only for the real
            // 10-bit BT.2020-PQ surface) called camera.setPreviewSurface() but never triggered a
            // session rebuild, so the camera stayed bound to the DIRECT SurfaceView and the corrected
            // hue-preserving SDR output was never actually displayed.
            if (com.neoproc.neoproccamx.rendering.Hdr10PqPreviewSdrOutputFlag.enabled) {
                val startedPq = hdr10PqPreviewCompositor.start(
                    visibleSurface,
                    previewSize.width,
                    previewSize.height,
                    pqMode,
                    appliedPreviewProfile
                )
                val pqInputSync = if (startedPq) hdr10PqPreviewCompositor.inputSurface() else null
                if (pqInputSync == null) {
                    // EGL build failed on a still-reconfiguring holder; stay on the direct preview and
                    // schedule a bounded retry so the look self-heals once the surface settles.
                    releaseHdr10PqPreviewCompositorIfActive()
                    scheduleGlPreviewEngageRetry()
                    return false
                }
                glPreviewEngageRetries = 0
                hdr10PqPreviewCompositorActive = true
                camera.setPreviewSurface(pqInputSync)
                Log.i(
                    "CameraViewModel",
                    "HDR10 PQ preview compositor engaged (sync SDR-out) mode=$pqMode ${previewSize.width}x${previewSize.height}"
                )
                return true
            }
            // Build the 10-bit PQ EGL surface off-main; until it confirms, direct HLG10 preview remains.
            // On success the camera is rebound to the PQ input surface on the main thread.
            if (!hdr10PqPreviewEngageInProgress) {
                hdr10PqPreviewEngageInProgress = true
                val engageWidth = previewSize.width
                val engageHeight = previewSize.height
                val engageMode = pqMode
                viewModelScope.launch {
                    val pqInput = withContext(Dispatchers.Default) {
                        if (hdr10PqPreviewCompositor.start(
                                visibleSurface,
                                engageWidth,
                                engageHeight,
                                engageMode,
                                appliedPreviewProfile
                            )
                        ) {
                            hdr10PqPreviewCompositor.inputSurface()
                        } else {
                            null
                        }
                    }
                    if (pqInput != null) {
                        // The ~2s EGL build ran while the main thread was free, so the surface this
                        // engage targeted may have been torn down / replaced in the meantime
                        // (surfaceDestroyed → clearPreviewSurface nulls previewSurface). Re-check the
                        // surface identity and camera readiness on completion; if stale, release the
                        // just-built compositor and skip the bind so we never bind the camera to (or
                        // render into) a dead Surface and leak the isolated 10-bit EGL context.
                        val engageSurfaceStillCurrent =
                            previewSurface === visibleSurface &&
                                runCatching { visibleSurface.isValid }.getOrDefault(false) &&
                                cameraPreviewReadyForGlPreviewCompositor()
                        if (engageSurfaceStillCurrent) {
                            glPreviewEngageRetries = 0
                            hdr10PqPreviewCompositorActive = true
                            camera.setPreviewSurface(pqInput)
                            Log.i(
                                "CameraViewModel",
                                "HDR10 PQ preview compositor engaged (async) mode=$engageMode ${engageWidth}x${engageHeight}"
                            )
                        } else {
                            hdr10PqPreviewCompositor.release()
                            hdr10PqPreviewCompositorActive = false
                            Log.w(
                                "CameraViewModel",
                                "HDR10 PQ preview compositor engage discarded: preview surface changed during async EGL build"
                            )
                        }
                    } else {
                        // 10-bit PQ EGL surface unavailable on this device (10-bit-EGL / ANR wall). Stay on
                        // the direct HLG10 preview; bounded retry so it self-heals if the holder was
                        // mid-reconfigure. (start() already released internally on failure, so active=false.)
                        releaseHdr10PqPreviewCompositorIfActive()
                        scheduleGlPreviewEngageRetry()
                    }
                    hdr10PqPreviewEngageInProgress = false
                }
            }
            return false
        }
        releaseHdr10PqPreviewCompositorIfActive()
        val grade = resolveGlPreviewGrade()
        if (grade == GlVisiblePreviewPolicy.PreviewGrade.IDENTITY) {
            glPreviewEngageRetries = 0
            if (glPreviewCompositorActive) {
                previewGlCompositor.release()
                glPreviewCompositorActive = false
            }
            glPreviewSurfaceReleasePending = false
            glPreviewVisibleSurfaceReleaseConfirmed = false
            glPreviewHandoffBindJob?.cancel()
            glPreviewHandoffBindJob = null
            glPreviewHandoffFallbackJob?.cancel()
            glPreviewHandoffFallbackJob = null
            glPreviewBindingResyncJob?.cancel()
            glPreviewBindingResyncJob = null
            return false
        }
        if (!cameraPreviewReadyForGlPreviewCompositor()) return false
        if (glPreviewCompositorActive && previewGlCompositor.isRunning()) {
            if (cameraOwnsVisiblePreviewSurface(visibleSurface)) {
                previewGlCompositor.release()
                glPreviewCompositorActive = false
                glPreviewVisibleSurfaceReleaseConfirmed = false
                scheduleGlPreviewEngageRetry()
                return false
            }
            previewGlCompositor.inputSurface()?.let { input ->
                glPreviewEngageRetries = 0
                previewGlCompositor.setGrade(glPreviewGradeCode(grade))
                camera.setPreviewSurface(input)
                return true
            }
            previewGlCompositor.release()
            glPreviewCompositorActive = false
            glPreviewVisibleSurfaceReleaseConfirmed = false
        }
        if (camera.sessionRefreshPending.get()) { scheduleGlPreviewEngageRetry(); return false }
        if (
            !(glPreviewCompositorActive && previewGlCompositor.isRunning()) &&
            !visibleGlPreviewSurfaceReadyForNewEglAcquire(visibleSurface)
        ) {
            scheduleGlPreviewEngageRetry()
            return false
        }
        val started = previewGlCompositor.start(
            visibleSurface,
            previewSize.width,
            previewSize.height,
            glPreviewGradeCode(grade)
        )
        val input = if (started) previewGlCompositor.inputSurface() else null
        if (input == null) {
            previewGlCompositor.release()
            glPreviewCompositorActive = false
            glPreviewVisibleSurfaceReleaseConfirmed = false
            // Engage failed (typically the EGL surface on a still-reconfiguring HDR→SDR holder). Camera
            // will be bound DIRECT by the caller — a valid natural preview. Schedule a bounded retry so
            // the per-mode look self-heals once the SDR holder settles; capped so it can never loop.
            scheduleGlPreviewEngageRetry()
            return false
        }
        glPreviewEngageRetries = 0
        glPreviewCompositorActive = true
        glPreviewVisibleSurfaceReleaseConfirmed = false
        camera.setPreviewSurface(input)
        Log.i("CameraViewModel", "GL preview compositor engaged grade=$grade ${previewSize.width}x${previewSize.height}")
        return true
    }

    private fun scheduleGlPreviewCompositorBindingResync(
        reason: String,
        minimumDelayMs: Long = GL_PREVIEW_ENGAGE_RETRY_DELAY_MS
    ) {
        val canonicalReason =
            com.neoproc.neoproccamx.rendering.GlPreviewBindingRetryPolicy.canonicalReason(reason)
        if (glPreviewBindingResyncJob?.isActive == true) return
        if (
            !com.neoproc.neoproccamx.rendering.GlPreviewBindingRetryPolicy.shouldSchedule(
                shouldComposite = shouldCompositeVisibleGlPreview(),
                attempts = glPreviewEngageRetries,
                maxRetries = GL_PREVIEW_MAX_ENGAGE_RETRIES
            )
        ) return
        glPreviewEngageRetries += 1
        glPreviewBindingResyncJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(minimumDelayMs)
            glPreviewBindingResyncJob = null
            if (previewSurface == null || !shouldCompositeVisibleGlPreview()) return@launch
            Log.i(
                "CameraViewModel",
                "Retrying GL preview compositor binding after $canonicalReason " +
                    "($glPreviewEngageRetries/$GL_PREVIEW_MAX_ENGAGE_RETRIES)"
            )
            syncGlPreviewCompositorBinding()
            // A Camera2→EGL ownership handoff deliberately takes longer than the retry delay.
            // Starting another rebuild while that handoff is settling cancels its progress and can
            // churn sessions forever, leaving the visible SurfaceView black/frozen. The handoff and
            // its timeout own recovery from here; only retry again when no transition is active.
            val transitionInProgress =
                glPreviewSurfaceReleasePending ||
                    glPreviewHandoffBindJob?.isActive == true ||
                    glPreviewHandoffFallbackJob?.isActive == true ||
                    hdr10PqPreviewEngageInProgress ||
                    camera.sessionRefreshPending.get()
            val stillNeedsCompositor =
                com.neoproc.neoproccamx.rendering.GlPreviewBindingRetryPolicy.shouldContinue(
                    shouldComposite = shouldCompositeVisibleGlPreview(),
                    sdrCompositorActive = glPreviewCompositorActive,
                    pqCompositorActive = hdr10PqPreviewCompositorActive,
                    transitionInProgress = transitionInProgress,
                    attempts = glPreviewEngageRetries,
                    maxRetries = GL_PREVIEW_MAX_ENGAGE_RETRIES
                )
            if (stillNeedsCompositor) {
                scheduleGlPreviewCompositorBindingResync(canonicalReason)
            }
        }
    }

    /**
     * Waits for deliberate startup/mode-settle ownership cooldowns without spending the bounded
     * EGL failure budget. A cooldown is not a compositor failure; counting it as one allowed four
     * harmless mode/session transitions to permanently strand NDR on direct PQ/YUV preview.
     */
    private fun scheduleGlPreviewOwnerCooldownResync(reason: String) {
        if (glPreviewBindingResyncJob?.isActive == true) return
        val remainingMs = glPreviewOwnerCooldownRemainingMs()
        if (remainingMs <= 0L) {
            syncGlPreviewCompositorBinding()
            return
        }
        val canonicalReason =
            com.neoproc.neoproccamx.rendering.GlPreviewBindingRetryPolicy.canonicalReason(reason)
        glPreviewBindingResyncJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(remainingMs + 50L)
            glPreviewBindingResyncJob = null
            if (previewSurface == null || !shouldCompositeVisibleGlPreview()) return@launch
            Log.i(
                "CameraViewModel",
                "Re-evaluating GL preview compositor after $canonicalReason ownership cooldown"
            )
            syncGlPreviewCompositorBinding()
        }
    }

    /** Post ONE delayed re-attempt of a failed compositor engage, up to a small cap. Main-thread. */
    private fun scheduleGlPreviewEngageRetry() {
        if (glPreviewSurfaceReleasePending || glPreviewHandoffBindJob != null || glPreviewHandoffFallbackJob != null) return
        if (glPreviewEngageRetryScheduled) return
        if (glPreviewEngageRetries >= GL_PREVIEW_MAX_ENGAGE_RETRIES) return
        if (cameraOwnsVisiblePreviewSurface(previewSurface)) return
        if (glPreviewOwnerCooldownRemainingMs() > 0L) {
            scheduleGlPreviewOwnerCooldownResync("engage-owner-cooldown")
            return
        }
        glPreviewEngageRetries += 1
        glPreviewEngageRetryScheduled = true
        viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(GL_PREVIEW_ENGAGE_RETRY_DELAY_MS)
            glPreviewEngageRetryScheduled = false
            if (recordingStartOrActiveBlocksVisiblePreviewRebind()) return@launch
            if (glPreviewOwnerCooldownRemainingMs() > 0L) {
                scheduleGlPreviewOwnerCooldownResync("engage-owner-cooldown")
                return@launch
            }
            // Re-run the standard binding evaluation; if a non-identity grade is still wanted and not yet
            // engaged, this re-attempts the engage on the now-settled SDR holder. If it fails again the
            // camera simply stays direct (natural) — no black, no downgrade.
            syncGlPreviewCompositorBinding()
        }
    }

    /**
     * Engage/disengage the GL preview compositor when the LOG/RAW/HDR mode changes. A grade-only
     * change (LOG↔RAW while still composited) just swaps the shader uniform — no rebind. When
     * engagement flips, force ONE preview re-bind (never mid-record) so [applyPreviewPipelineBinding]
     * re-selects the camera's visible-preview target.
     */
    private fun syncGlPreviewCompositorBinding() {
        if (previewSurface == null) return
        clearStoppedVisibleGlPreviewCompositorOwnership()
        // Gated 10-bit PQ preview: settle its engage/disengage FIRST. This whole block is skipped
        // when the gate is off and PQ is not active (pqComposite==false && !active) → the proven
        // 8-bit path below is reached byte-identically.
        val pqComposite = shouldCompositeHdr10PqPreview()
        if (pqComposite || hdr10PqPreviewCompositorActive) {
            if (pqComposite && !cameraPreviewReadyForGlPreviewCompositor()) {
                return
            }
            val pqDesynced = pqComposite && hdr10PqPreviewCompositorActive &&
                !hdr10PqPreviewCompositor.isRunning()
            if (pqComposite == hdr10PqPreviewCompositorActive && !pqDesynced) {
                // Engagement unchanged; an NDR10+↔NIVUKX PQ swap just updates the mode uniform.
                if (hdr10PqPreviewCompositorActive) {
                    hdr10PqPreviewCompositor.setMode(activeNdrPqDeliveryMode().coerceAtLeast(0))
                }
                return
            }
            if (recordingStartOrActiveBlocksVisiblePreviewRebind()) {
                // Never create a new EGL owner mid-record. CameraState.Streaming re-evaluates this
                // after recording stops; polling here only burns retries and churns the graph.
                return
            }
            if (!hdr10PqPreviewCompositorActive && glPreviewOwnerCooldownRemainingMs() > 0L) {
                scheduleGlPreviewOwnerCooldownResync("pq-owner-cooldown")
                return
            }
            // Do not reset the bounded recovery counter before the compositor actually owns the
            // surface. Success resets it in applyGlPreviewCompositorTarget().
            refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
            return
        }
        val grade = resolveGlPreviewGrade()
        val shouldComposite = grade != GlVisiblePreviewPolicy.PreviewGrade.IDENTITY
        if (shouldComposite && !cameraPreviewReadyForGlPreviewCompositor()) {
            return
        }
        // Self-heal: we believe we are composited but the GL stage silently lost its context (isRunning
        // == false). Fall THROUGH to the rebuild path so the look is strictly re-engaged instead of the
        // early-return leaving a direct, ungraded preview.
        val desynced = shouldComposite && glPreviewCompositorActive && !previewGlCompositor.isRunning()
        if (shouldComposite == glPreviewCompositorActive && !desynced) {
            // Engagement unchanged. Composite→composite (LOG↔RAW) just swaps the shader uniform; a
            // bypass→bypass transition (e.g. SDR↔PRO↔NDR) does nothing — no preview rebind, so those
            // non-compositor mode switches keep their existing, proven preview path untouched.
            if (glPreviewCompositorActive) previewGlCompositor.setGrade(glPreviewGradeCode(grade))
            return
        }
        if (recordingStartOrActiveBlocksVisiblePreviewRebind()) {
            return
        }
        if (!glPreviewCompositorActive && glPreviewOwnerCooldownRemainingMs() > 0L) {
            scheduleGlPreviewOwnerCooldownResync("owner-cooldown")
            return
        }
        // Do not reset the bounded recovery counter before the compositor actually owns the
        // surface. Success resets it in applyGlPreviewCompositorTarget().
        // Engagement flips (bypass↔composite): re-bind the preview surface, coalesced into the active
        // mode-switch batch and applied by the batch's own session rebuild (the path proven to reliably
        // re-attach the camera to / from the compositor's SurfaceTexture).
        refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
    }

    private fun applyPreviewPipelineBinding(
        surface: Surface,
        previewSize: Size,
        preferNativePreview: Boolean
    ): Boolean {
        if (preferNativePreview) {
            val handoffPendingForSurface =
                nativePreviewHandoffPending && nativePreviewHandoffSurface === surface
            val directSurfaceOwnedByCamera =
                !handoffPendingForSurface &&
                    cameraOwnsVisiblePreviewSurface(surface)
            camera.setPreviewSurface(null)
            camera.setHardwareImportTargetSize(previewSize)
            camera.setInternalRecordingHardwareImportEnabled(true)
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize(previewSize, nativePreview = true))
            pipeline.setDedicatedHardwareImportEnabled(true)
            vulkanRuntime.beginPreviewImportSession()
            vulkanRuntime.setFrameImportEnabled(true)
            if (directSurfaceOwnedByCamera) {
                stopNativePreviewWatchdog()
                vulkanRuntime.setPreviewSurface(null)
                nativeVisiblePreviewActive = false
                nativeVisiblePreviewConfirmed = false
                directPreviewSurfaceActive = false
                nativePreviewSurfaceResizePending = false
                nativePreviewCameraReleaseConfirmed = false
                nativePreviewHandoffPending = true
                nativePreviewHandoffSurface = surface
                // Transient native→native rebind: both producers are already detached from the surface,
                // so hold the last native presentation (no shrink to the direct-fit) until native
                // re-attaches. A genuine fallback below clears the hold with a plain setNativeCompositorActive(false).
                previewViewRef?.setNativeCompositorActive(false, transientHandoff = true)
                return false
            }
            if (previewViewRef?.setNativeCompositorActive(true) == true) {
                stopNativePreviewWatchdog()
                nativeVisiblePreviewActive = false
                nativeVisiblePreviewConfirmed = false
                directPreviewSurfaceActive = false
                nativePreviewSurfaceResizePending = true
                return false
            }
            if (
                !VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface(
                    requestNativePreview = true,
                    cameraOwnsSurface = cameraOwnsVisiblePreviewSurface(surface),
                    cameraReleaseConfirmed = nativePreviewCameraReleaseConfirmed,
                    replacementSurfaceReady = !nativePreviewSurfaceResizePending
                )
            ) {
                return false
            }
            val nativeBound = vulkanRuntime.setPreviewSurface(surface)
            if (nativeBound) {
                clearNativePreviewHandoffState()
                directPreviewSurfaceActive = false
                nativeVisiblePreviewActive = true
                nativeVisiblePreviewConfirmed = false
                startNativePreviewWatchdog(surface)
                return true
            }
            val failedAfterHandoff = handoffPendingForSurface
            if (failedAfterHandoff) {
                clearNativePreviewHandoffState()
            }
            markNativePreviewFallback(
                surface = surface,
                reason = if (failedAfterHandoff) {
                    "Visible Vulkan preview bind failed after direct preview release"
                } else {
                    "Visible Vulkan preview bind failed; waiting for surface reset"
                },
                requiresSurfaceReset = !failedAfterHandoff
            )
        }

        stopNativePreviewWatchdog()
        clearNativePreviewHandoffState()
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        directPreviewSurfaceActive = true
        previewViewRef?.setNativeCompositorActive(false)
        val preservedRecordingImportFeedPath =
            recordingImportFeedPathToPreserveForPreviewBinding()
        if (preservedRecordingImportFeedPath != null) {
            pipeline.setDedicatedHardwareImportEnabled(true)
            vulkanRuntime.setFrameImportEnabled(true)
            camera.setInternalRecordingHardwareImportEnabled(true)
            if (preservedRecordingImportFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
                val encoderWidth = gpuRecording.videoWidth
                val encoderHeight = gpuRecording.videoHeight
                if (encoderWidth > 0 && encoderHeight > 0) {
                    camera.setHardwareImportTargetSize(Size(encoderWidth, encoderHeight))
                    camera.setHardwareImportP010SourceEnabled(true)
                }
            }
        } else {
            pipeline.setDedicatedHardwareImportEnabled(false)
            vulkanRuntime.setFrameImportEnabled(false)
            camera.setInternalRecordingHardwareImportEnabled(false)
            camera.setHardwareImportTargetSize(null)
        }
        vulkanRuntime.setPreviewSurface(null)
        clearStoppedVisibleGlPreviewCompositorOwnership()
        val glPreviewNeedsReleasedVisibleSurface =
            shouldCompositeVisibleGlPreview() &&
                !glPreviewCompositorActive &&
                !hdr10PqPreviewCompositorActive &&
                !glPreviewVisibleSurfaceReleaseConfirmed &&
                cameraPreviewReadyForGlPreviewCompositor() &&
                !glPreviewOwnerTransitionBlocksVisiblePreviewRebind()
        if (glPreviewNeedsReleasedVisibleSurface) {
            if (!glPreviewSurfaceReleasePending) {
                glPreviewSurfaceReleasePending = true
                glPreviewVisibleSurfaceReleaseConfirmed = false
                camera.setPreviewSurface(null)
                scheduleGlPreviewCompositorHandoffFallback(surface)
            }
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize(previewSize, nativePreview = false))
            return false
        }
        if (
            glPreviewSurfaceReleasePending &&
            previewSurface === surface &&
            !glPreviewCompositorActive
        ) {
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize(previewSize, nativePreview = false))
            return false
        }
        glPreviewSurfaceReleasePending = false
        glPreviewHandoffFallbackJob?.cancel()
        glPreviewHandoffFallbackJob = null
        if (
            recordingStartOrActiveBlocksVisiblePreviewRebind() &&
            resolveGlPreviewGrade() != GlVisiblePreviewPolicy.PreviewGrade.IDENTITY &&
            !glPreviewCompositorActive &&
            cameraOwnsVisiblePreviewSurface(surface)
        ) {
            glPreviewVisibleSurfaceReleaseConfirmed = false
            camera.setPreviewSurface(surface)
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize(previewSize, nativePreview = false))
            return false
        }
        // Additive per-mode preview grade: when engaged, the camera feeds the GL compositor's
        // SurfaceTexture and the composited image is drawn to the visible surface; otherwise the
        // camera renders directly into the visible surface exactly as before.
        if (!applyGlPreviewCompositorTarget(surface, previewSize)) {
            glPreviewVisibleSurfaceReleaseConfirmed = false
            camera.setPreviewSurface(surface)
        }
        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize(previewSize, nativePreview = false))
        return false
    }

    private fun bindPreviewSurface(
        surface: Surface,
        refreshSession: Boolean = true,
        preferNativePreviewOverride: Boolean? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMainIfNeeded {
                bindPreviewSurface(
                    surface = surface,
                    refreshSession = refreshSession,
                    preferNativePreviewOverride = preferNativePreviewOverride
                )
            }
            return
        }
        val previewSize = preferredPreviewSize()
        val sameSurface = previewSurface === surface
        val sameSize = boundPreviewSize == previewSize
        val previousNativePreviewActive = nativeVisiblePreviewActive
        val preserveReleasedHandoffForReplacement =
            !sameSurface &&
                preferNativePreviewOverride == true &&
                nativePreviewHandoffPending &&
                nativePreviewCameraReleaseConfirmed
        if (!sameSurface && !preserveReleasedHandoffForReplacement) {
            resetNativePreviewFallbackState()
            clearNativePreviewHandoffState()
            glPreviewSurfaceReleasePending = false
            glPreviewVisibleSurfaceReleaseConfirmed = false
            glPreviewHandoffBindJob?.cancel()
            glPreviewHandoffBindJob = null
            glPreviewHandoffFallbackJob?.cancel()
            glPreviewHandoffFallbackJob = null
            glPreviewBindingResyncJob?.cancel()
            glPreviewBindingResyncJob = null
        }
        if (preserveReleasedHandoffForReplacement) {
            nativePreviewHandoffSurface = surface
        }
        boundPreviewSize = previewSize
        currentPreviewStreamAspectRatio = SensorCropEngine.normalizeLongShortRatio(
            previewSize.width.toFloat() / previewSize.height.toFloat()
        )
        previewSurface = surface
        val activeNativePreview = applyPreviewPipelineBinding(
            surface = surface,
            previewSize = previewSize,
            preferNativePreview =
                preferNativePreviewOverride ?: shouldAttemptNativeVulkanPreview(surface)
        )
        val glPreviewReleasePendingForSurface =
            glPreviewSurfaceReleasePending && previewSurface === surface
        previewViewRef?.setStreamGeometry(
            previewSize.width,
            previewSize.height,
            camera.sensorOrientation()
        )
        syncGeometryTargets()
        if (
            refreshSession &&
            (
                !sameSurface ||
                    !sameSize ||
                    previousNativePreviewActive != activeNativePreview ||
                    (nativePreviewHandoffPending && nativePreviewHandoffSurface === surface) ||
                    glPreviewReleasePendingForSurface
                )
        ) {
            when (cameraState.value) {
                CameraState.Open,
                CameraState.Streaming,
                CameraState.Recording -> {
                    if (nativePreviewHandoffPending && nativePreviewHandoffSurface === surface) {
                        camera.refreshOutputsAndSession(
                            forceRebuild = true,
                            onPreviousSessionClosed = {
                                runOnMainIfNeeded {
                                    completeNativePreviewHandoffAfterCameraRelease(
                                        preferNativePreviewOverride
                                    )
                                }
                            }
                        )
                    } else if (glPreviewReleasePendingForSurface) {
                        scheduleGlPreviewCompositorHandoffFallback(surface)
                        camera.refreshOutputsAndSession(
                            forceRebuild = true,
                            onPreviousSessionClosed = {
                                runOnMainIfNeeded {
                                    completeGlPreviewCompositorHandoffAfterCameraRelease(surface)
                                }
                            }
                        )
                    } else {
                        camera.refreshOutputsAndSession()
                    }
                }
                else -> Unit
            }
        }
    }

    internal fun refreshPreviewPipelineIfNeeded(
        forceSessionRefresh: Boolean = false,
        preferNativePreviewOverride: Boolean? = null,
        deferSessionRefresh: Boolean = false
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMainIfNeeded {
                refreshPreviewPipelineIfNeeded(
                    forceSessionRefresh = forceSessionRefresh,
                    preferNativePreviewOverride = preferNativePreviewOverride,
                    deferSessionRefresh = deferSessionRefresh
                )
            }
            return
        }
        if (
            preferNativePreviewOverride == null &&
            !deferSessionRefresh &&
            modeSwitchPreviewWorkCoalescer.requestPreviewPipelineRefresh(forceSessionRefresh)
        ) {
            return
        }
        val surface = previewSurface ?: return
        if (
            !forceSessionRefresh &&
            (gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused ||
                gpuRecordState.value is GpuRecordingState.Stopping)
        ) {
            return
        }

        val desiredPreviewSize = preferredPreviewSize()
        val current = boundPreviewSize
        val sizeChanged = current == null ||
            current.width != desiredPreviewSize.width ||
            current.height != desiredPreviewSize.height
        val desiredNativePreview =
            preferNativePreviewOverride ?: shouldAttemptNativeVulkanPreview(surface)
        val pipelineChanged = desiredNativePreview != nativeVisiblePreviewActive

        if (!sizeChanged && !forceSessionRefresh && !pipelineChanged) return

        val previousBoundPreviewSize = boundPreviewSize
        val previousNativePreviewActive = nativeVisiblePreviewActive
        bindPreviewSurface(
            surface = surface,
            refreshSession = false,
            preferNativePreviewOverride = preferNativePreviewOverride
        )
        val appliedSizeChanged = previousBoundPreviewSize != boundPreviewSize
        val appliedPipelineChanged = previousNativePreviewActive != nativeVisiblePreviewActive
        val appliedHandoffPending = nativePreviewHandoffPending && nativePreviewHandoffSurface === surface
        val appliedGlPreviewReleasePending = glPreviewSurfaceReleasePending && previewSurface === surface
        if (
            !forceSessionRefresh &&
            !appliedSizeChanged &&
            !appliedPipelineChanged &&
            !appliedHandoffPending &&
            !appliedGlPreviewReleasePending
        ) {
            return
        }
        if (deferSessionRefresh) {
            return
        }
        when (cameraState.value) {
            CameraState.Open,
            CameraState.Streaming,
            CameraState.Recording -> {
                if (appliedHandoffPending) {
                    camera.refreshOutputsAndSession(
                        forceRebuild = true,
                        onPreviousSessionClosed = {
                            runOnMainIfNeeded {
                                completeNativePreviewHandoffAfterCameraRelease(
                                    preferNativePreviewOverride
                                )
                            }
                        }
                    )
                } else if (appliedGlPreviewReleasePending) {
                    scheduleGlPreviewCompositorHandoffFallback(surface)
                    camera.refreshOutputsAndSession(
                        forceRebuild = true,
                        onPreviousSessionClosed = {
                            runOnMainIfNeeded {
                                completeGlPreviewCompositorHandoffAfterCameraRelease(surface)
                            }
                        }
                    )
                } else {
                    camera.refreshOutputsAndSession()
                }
            }
            else -> Unit
        }
    }

    private fun preferredVideoSourceSize(): Size {
        return (
            lastRecordingSelection?.sourceSize
                ?: lastRecordingSelection?.requestedSize
                ?: selectedVideoSize
                ?: prefs.videoSize
            ).normalizeForOrientation()
    }

    private fun currentRequestedPreviewLoadLongEdge(): Int {
        return if (VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)) {
            preferredVideoSourceSize().width
        } else {
            (boundPreviewSize ?: CameraPreviewSessionSizingPolicy.defaultPhotoPreviewFallback())
                .normalizeForOrientation()
                .width
        }
    }

    private fun currentPreviewPerformanceLimits(
        recordingActive: Boolean = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping
    ): PreviewPerformanceLimits {
        val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
        return PreviewPerformanceStabilityPolicy.resolveLimits(
            profile = previewPerformanceState.profile,
            videoMode = videoSurface,
            recordingActive = recordingActive,
            requestedOutputLongEdge = currentRequestedPreviewLoadLongEdge()
        )
    }

    private fun resetPreviewPerformanceState() {
        previewPerformanceState = PreviewPerformanceState()
        performancePreviewMaxLongEdge = null
        performancePreviewMaxArea = null
        performanceAnalysisMaxLongEdge = null
    }

    private fun applyPreRecordingPerformanceCaps(recordingSize: Size) {
        val normalized = recordingSize.normalizeForOrientation()
        val limits = PreviewPerformanceStabilityPolicy.resolveLimits(
            profile = PreviewPerformanceProfile.DEFAULT,
            videoMode = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value),
            recordingActive = true,
            requestedOutputLongEdge = normalized.width
        )
        previewPerformanceState = PreviewPerformanceState()
        performancePreviewMaxLongEdge = limits.previewMaxLongEdge
        performancePreviewMaxArea = limits.previewMaxArea
        performanceAnalysisMaxLongEdge = limits.analysisMaxLongEdge
    }

    private fun shouldFreezePreviewPerformanceRetuneForPrearmedRecording(): Boolean {
        val strictPacketCapturePrearmed = shouldUseUncompressedCinemaPipeline() &&
            runCatching { camera.resolveNpv10CaptureSourcePlan(preferredRecordingSize()) }
                .getOrNull()
                ?.let { it.nativeTenBitCapture && camera.isNpv10TenBitSessionConfigured(it.size) } == true
        val offscreenEncoderPrearmed = prearmedRecordingSurface != null &&
            prearmedRecordingSurfaceAttachedToSession &&
            prearmedRecordingFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER &&
            shouldKeepRecordingSurfacePrearmed()
        return PreviewPerformanceStabilityPolicy.shouldFreezeIdleRetune(
            _uiMode.value == AppMode.VIDEO,
            strictPacketCapturePrearmed,
            offscreenEncoderPrearmed
        )
    }

    private fun evaluatePreviewPerformanceStability(measuredFps: Float) {
        val recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping
        if (!recordingActive && shouldFreezePreviewPerformanceRetuneForPrearmedRecording()) {
            return
        }
        val decision = PreviewPerformanceStabilityPolicy.evaluate(
            state = previewPerformanceState,
            measuredPreviewFps = measuredFps,
            requestedPreviewFps = cameraParams.value.fps.coerceAtLeast(24),
            videoMode = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value),
            recordingActive = recordingActive,
            requestedOutputLongEdge = currentRequestedPreviewLoadLongEdge(),
            thermalGuardActive = thermalPreviewMaxLongEdge != null || thermalPreviewMaxArea != null,
            nowElapsedMs = SystemClock.elapsedRealtime()
        )
        previewPerformanceState = decision.state
        performancePreviewMaxLongEdge = decision.limits.previewMaxLongEdge
        performancePreviewMaxArea = decision.limits.previewMaxArea
        performanceAnalysisMaxLongEdge = decision.limits.analysisMaxLongEdge
        syncPreviewPerformanceMode()
        if (decision.shouldRefreshPreviewPipeline && !recordingActive) {
            refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
        }
    }

    private fun clearPreviewSurface() {
        stopNativePreviewWatchdog()
        stopNativePreviewRetry()
        clearNativePreviewHandoffState()
        glPreviewBindingResyncJob?.cancel()
        glPreviewBindingResyncJob = null
        resetPreviewPerformanceState()
        previewSurface = null
        boundPreviewSize = null
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        nativePreviewFallbackUntilElapsedMs = 0L
        nativePreviewFallbackCount = 0
        nativePreviewRetryRequiresSurfaceReset = false
        directPreviewSurfaceActive = false
        updatePreviewView { setNativeCompositorActive(false) }
        pipeline.setDedicatedHardwareImportEnabled(false)
        vulkanRuntime.setFrameImportEnabled(false)
        vulkanRuntime.setPreviewSurface(null)
        camera.setInternalRecordingHardwareImportEnabled(false)
        camera.setHardwareImportTargetSize(null)
        clearRecordingReadoutOverride()
        if (glPreviewCompositorActive) {
            previewGlCompositor.release()
            glPreviewCompositorActive = false
        }
        releaseHdr10PqPreviewCompositorIfActive()
        camera.setPreviewSurface(null)
    }

    private fun clearPreviewSurfaceForActiveRecording() {
        if (recordingPreviewDetachInFlight) return
        val detachGeneration = recordingPreviewDetachGeneration + 1L
        recordingPreviewDetachGeneration = detachGeneration
        recordingPreviewDetachInFlight = true
        recordingPreviewSurfaceSafelyDetached = false
        pendingRecordingPreviewSurface = null
        stopNativePreviewWatchdog()
        stopNativePreviewRetry()
        clearNativePreviewHandoffState()
        glPreviewBindingResyncJob?.cancel()
        glPreviewBindingResyncJob = null
        previewSurface = null
        boundPreviewSize = null
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        nativePreviewFallbackUntilElapsedMs = 0L
        nativePreviewFallbackCount = 0
        nativePreviewRetryRequiresSurfaceReset = false
        directPreviewSurfaceActive = false
        updatePreviewView { setNativeCompositorActive(false) }
        // Detach display presentation only; preserve P010/RAW capture and foreground-service state.
        vulkanRuntime.setPreviewSurface(null)
        camera.setPreviewSurface(null)
        val completeDetach = {
            runOnMainIfNeeded {
                if (recordingPreviewDetachGeneration != detachGeneration) {
                    return@runOnMainIfNeeded
                }
                if (glPreviewCompositorActive) {
                    previewGlCompositor.release()
                    glPreviewCompositorActive = false
                }
                releaseHdr10PqPreviewCompositorIfActive()
                glPreviewSurfaceReleasePending = false
                glPreviewVisibleSurfaceReleaseConfirmed = true
                recordingPreviewDetachInFlight = false
                recordingPreviewSurfaceSafelyDetached = true
                val replacement = pendingRecordingPreviewSurface
                pendingRecordingPreviewSurface = null
                Log.i(
                    "CameraViewModel",
                    "Recording-only Camera2 session active; visible preview surface safely detached"
                )
                if (replacement != null &&
                    runCatching { replacement.isValid }.getOrDefault(false)
                ) {
                    onSurfaceReady(replacement)
                }
            }
        }
        when (cameraState.value) {
            CameraState.Open,
            CameraState.Streaming,
            CameraState.Recording -> camera.refreshOutputsAndSession(
                rebuildReaders = false,
                forceRebuild = true,
                onPreviousSessionClosed = completeDetach
            )
            else -> completeDetach()
        }
    }

    private fun preferredPreviewSize(): Size {
        val videoMode = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
        val normalizedVideoSourceSize = preferredVideoSourceSize()
        val displayPreviewBudget = mobileDisplayPreviewBudget()
        val sizes = if (videoMode) {
            distinctNormalizedSizes(
                camera.videoSizes,
                camera.recordingSizes,
                camera.previewSizes,
                availableVideoSizes()
            )
        } else {
            distinctNormalizedSizes(
                camera.previewSizes,
                camera.videoSizes,
                camera.recordingSizes,
                availableVideoSizes()
            )
        }
        val targetAspect = outputAspectRatio()
            ?: if (videoMode) {
                normalizedVideoSourceSize.width.toFloat() / normalizedVideoSourceSize.height.toFloat()
            } else {
                basePreviewStreamAspectRatio()
            }
        val limits = CameraPreviewSessionSizingPolicy.resolvePreviewLimits(
            videoMode = videoMode,
            strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly,
            videoSourceLongEdge = normalizedVideoSourceSize.width,
            thermalPreviewMaxLongEdge = thermalPreviewMaxLongEdge,
            thermalPreviewMaxArea = thermalPreviewMaxArea,
            performancePreviewMaxLongEdge = performancePreviewMaxLongEdge,
            performancePreviewMaxArea = performancePreviewMaxArea,
            targetAspect = targetAspect,
            displayMaxLongEdge = displayPreviewBudget.width,
            displayMaxArea = (displayPreviewBudget.width.toLong() * displayPreviewBudget.height.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            // Viewfinder bandwidth is intentionally capped for sustained thermals. Recording and the
            // dedicated maximum-size JPEG output are independently sized and retain full fidelity.
            heatEfficientPreview = true
        )
        val appliedMaxLongEdge = activeVideoPipelineContract().let { contract -> RecordingRealtimeRoutePolicy.resolvePreviewMaxLongEdgeForRealtime(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT, normalizedVideoSourceSize.width, normalizedVideoSourceSize.height, recording.fps, contract.deliveryKind, contract.deliveryState.effectiveHdrMode, limits.maxLongEdge) }
        val appliedMaxArea = limits.maxArea
        if (videoMode) {
            findNormalizedSizeMatch(sizes, normalizedVideoSourceSize)?.let { sourceMatch ->
                if (
                    CameraPreviewSessionSizingPolicy.shouldPreferSourceSizeMatch(
                        sourceSize = sourceMatch,
                        targetAspect = targetAspect,
                        maxLongEdge = appliedMaxLongEdge,
                        maxArea = appliedMaxArea
                    )
                ) {
                    return sourceMatch
                }
            }
        }
        val cappedCandidates = sizes.filter {
            maxOf(it.width, it.height) <= appliedMaxLongEdge && (it.width * it.height) <= appliedMaxArea
        }
        val candidates = if (cappedCandidates.isNotEmpty()) cappedCandidates else sizes
        return chooseSizeForAspect(candidates, targetAspect)
            ?: candidates.maxByOrNull { it.width * it.height }
            ?: CameraPreviewSessionSizingPolicy.defaultStreamPreviewFallback()
    }

    private fun mobileDisplayPreviewBudget(): Size {
        val metrics = getApplication<Application>().resources.displayMetrics
        return Size(
            maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1),
            minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        )
    }

    private fun preferredPreviewAnalysisSize(
        previewSize: Size = preferredPreviewSize(),
        nativePreview: Boolean = shouldUseNativeVulkanPreview()
    ): Size {
        val normalizedPreviewSize = previewSize.normalizeForOrientation()
        val targetAspect = normalizedPreviewSize.width.toFloat() / normalizedPreviewSize.height.toFloat()
        val appliedMaxLongEdge = CameraPreviewSessionSizingPolicy.resolvePreviewAnalysisMaxLongEdge(
            strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly,
            normalizedPreviewLongEdge = normalizedPreviewSize.width,
            performanceAnalysisMaxLongEdge = performanceAnalysisMaxLongEdge,
            nativePreview = nativePreview
        )
        val sizes = distinctNormalizedSizes(
            camera.previewSizes,
            camera.videoSizes,
            camera.recordingSizes,
            listOf(previewSize)
        )
        val cappedCandidates = sizes.filter { maxOf(it.width, it.height) <= appliedMaxLongEdge }
        val candidates = if (cappedCandidates.isNotEmpty()) cappedCandidates else sizes
        return chooseSizeForAspect(candidates, targetAspect)
            ?: candidates.maxByOrNull { it.width * it.height }
            ?: previewSize
    }

    private fun preferredRecordingSize(): Size =
        (selectedVideoSize ?: prefs.videoSize).normalizeForOrientation()

    private fun preferredRecordingAnalysisSize(recordingSize: Size): Size {
        val normalizedRecordingSize = recordingSize.normalizeForOrientation()
        val targetAspect = normalizedRecordingSize.width.toFloat() / normalizedRecordingSize.height.toFloat()
        val appliedMaxLongEdge = activeVideoPipelineContract().let { contract ->
            RecordingRealtimeRoutePolicy.resolveRecordingAnalysisMaxLongEdgeForRealtime(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT, normalizedRecordingSize.width, normalizedRecordingSize.height, recording.fps, contract.deliveryKind, contract.deliveryState.effectiveHdrMode, CameraPreviewSessionSizingPolicy.resolveRecordingAnalysisMaxLongEdge(normalizedRecordingLongEdge = normalizedRecordingSize.width, performanceAnalysisMaxLongEdge = performanceAnalysisMaxLongEdge))
        }
        val sizes = distinctNormalizedSizes(
            camera.previewSizes,
            camera.videoSizes,
            camera.recordingSizes,
            listOf(preferredPreviewSize())
        )
        val cappedCandidates = sizes.filter { maxOf(it.width, it.height) <= appliedMaxLongEdge }
        val candidates = if (cappedCandidates.isNotEmpty()) cappedCandidates else sizes
        return chooseSizeForAspect(candidates, targetAspect)
            ?: preferredPreviewSize()
    }

    private fun applyResolvedRecordingConfig(
        resolvedSelection: ResolvedRecordingSelection,
        fps: Int,
        bitrate: Int,
        audioSource: AudioSrc
    ) {
        markGlPreviewOwnerCooldown()
        val requested = resolvedSelection.requestedSize
        val output = resolvedSelection.size
        val highResRequested = shouldEnforceNativeRecordingResolution(resolvedSelection)
        val requestedFps = resolveContractRequestedFps(fps)
        val effectiveFps = RecordingPreviewFrameRatePolicy.resolveEncoderFps(resolvedSelection, requestedFps)
        val effectiveBitrate = gpuRecording.resolveSupportedBitrate(
            requestedBitrate = bitrate,
            size = output,
            codec = when (resolvedSelection.codec) {
                "AVC", "H264" -> GpuVideoCodec.AVC
                "AV1", "AV01" -> GpuVideoCodec.AV1
                else -> GpuVideoCodec.HEVC
            },
            videoDetailProfile = _videoDetailProfile.value,
            fps = effectiveFps
        )

        selectedVideoSize = requested
        if (prefs.videoSize != requested) {
            prefs.videoSize = requested
        }
        lastRecordingSelection = resolvedSelection
        recording.width = output.width
        recording.height = output.height
        recording.fps = effectiveFps
        recording.bitrate = effectiveBitrate
        recording.audioSource = audioSource
        val resolvedCodecType = VideoRecordingContractPolicy.resolveRequestedCodec(
            when (resolvedSelection.codec) {
                "AVC", "H264" -> VideoCodecType.AVC
                "AV1", "AV01" -> VideoCodecType.AV1
                else -> VideoCodecType.HEVC
            }
        )
        recording.codec = resolvedCodecType
        gpuRecording.videoCodec = when (resolvedCodecType) {
            VideoCodecType.AVC -> com.neoproc.neoproccamx.recording.GpuVideoCodec.AVC
            VideoCodecType.AV1 -> com.neoproc.neoproccamx.recording.GpuVideoCodec.AV1
            VideoCodecType.HEVC -> com.neoproc.neoproccamx.recording.GpuVideoCodec.HEVC
        }

        gpuRecording.videoWidth = output.width
        gpuRecording.videoHeight = output.height
        gpuRecording.videoFps = effectiveFps
        gpuRecording.videoBitrate = effectiveBitrate
        gpuRecording.audioSource = audioSource
        if (_uiMode.value == AppMode.VIDEO) {
            camera.setRecordingEncoderTargetSize(output)
        }
        syncRecordingBitrateMode(_videoBitrateMode.value)
        gpuRecording.setCameraSupportedRecordingSizes(availableVideoSizes())
        resetPreviewPerformanceState()
        updatePreviewView { setEncoderFrameRate(effectiveFps) }
        syncPreviewPerformanceMode()
        syncActivePreviewFrameRate()
        val strictNativeResolution =
            highResRequested &&
                !resolvedSelection.upscaledToRequested &&
                resolvedSelection.cameraExactMatch &&
                resolvedSelection.encoderExactMatch
        gpuRecording.strictRequestedResolution = strictNativeResolution
        gpuRecording.strictCameraResolution = strictNativeResolution
        gpuRecording.strictEncoderResolution = strictNativeResolution
        Log.i(
            "CameraViewModel",
            "applyResolvedRecordingConfig requested=${requested.width}x${requested.height} " +
                "source=${resolvedSelection.sourceSize.width}x${resolvedSelection.sourceSize.height} " +
                "output=${output.width}x${output.height} upscaledToRequested=${resolvedSelection.upscaledToRequested} " +
                "effectiveRequested=${requested.width}x${requested.height} " +
                "bitrate=$bitrate requestedFps=$effectiveFps appliedFps=${recording.fps} " +
                "mode=${_videoBitrateMode.value} " +
                "strictNativeResolution=$strictNativeResolution"
        )
    }

    private fun shouldEnforceNativeRecordingResolution(selection: ResolvedRecordingSelection): Boolean {
        val requested = selection.requestedSize.normalizeForOrientation()
        return requested.width >= 3840 && requested.height >= 2160
    }

    private fun recommendedBitrateForSelection(
        selection: ResolvedRecordingSelection,
        fps: Int = recording.fps,
        codec: String = selection.codec
    ): Int {
        if (_videoBitrateMode.value == VideoBitrateMode.CINEMA_MASTER) {
            return cinemaMasterBitrateForSelection(selection, fps)
        }
        val size = selection.size
        val effectiveFps = RecordingPreviewFrameRatePolicy.resolveEncoderFps(selection, fps)
        val parityPlan = activeRecordingPipelineParityPlan()
        val logCaptureEnabled = parityPlan.logEnabledForSessionPlan
        val tenBitRequested = gpuRecording.tenBitEnabled || parityPlan.tenBitRequestedForSessionPlan
        val gpuCodec = when (codec) {
            "AVC", "H264" -> com.neoproc.neoproccamx.recording.GpuVideoCodec.AVC
            "AV1", "AV01" -> com.neoproc.neoproccamx.recording.GpuVideoCodec.AV1
            else -> com.neoproc.neoproccamx.recording.GpuVideoCodec.HEVC
        }
        var recommended = RecordingManager.recommendedBitrateFor(
            w = size.width,
            h = size.height,
            fps = effectiveFps,
            codec = gpuCodec,
            hdrMode = parityPlan.encoderHdrMode,
            logEnabled = logCaptureEnabled,
            tenBit = tenBitRequested,
            colorSpace = parityPlan.encoderColorSpace,
            colorProfile = _colorProfile.value,
            videoDetailProfile = _videoDetailProfile.value
        )
        if (logCaptureEnabled || tenBitRequested) {
            recommended = (recommended * LOG_TEN_BIT_BITRATE_BOOST).roundToInt()
        }
        if (isCinema4k60Selection(selection, effectiveFps)) {
            recommended = maxOf(recommended, CINEMA_4K60_MIN_BITRATE_BPS)
        }
        return recommended
    }

    private fun isCinema4k60Selection(
        selection: ResolvedRecordingSelection,
        effectiveFps: Int
    ): Boolean {
        if (_uiMode.value != AppMode.VIDEO) return false
        val normalizedSize = selection.size.normalizeForOrientation()
        return normalizedSize.width >= 3840 && normalizedSize.height >= 2160 && effectiveFps >= 60
    }

    private fun cinemaMasterBitrateForSelection(
        selection: ResolvedRecordingSelection,
        fps: Int = recording.fps
    ): Int {
        val effectiveFps = RecordingPreviewFrameRatePolicy.resolveEncoderFps(selection, fps)
        val size = selection.size
        val pixels = size.width.toLong() * size.height.toLong()
        val strictImaxFloorBps = HighEndQualityPolicy.minimumBitrateBps(
            width = size.width,
            height = size.height,
            fps = effectiveFps
        ).toLong()
        val maxImaxMasterBps = maxOf(
            strictImaxFloorBps,
            when {
                pixels >= 7680L * 4320L -> 180_000_000L
                pixels >= 3840L * 2160L -> 100_000_000L
                else -> 45_000_000L
            }
        )
        val baseBps = pixels * effectiveFps * 5 // High-end 5 bits per pixel.
        return baseBps
            .coerceAtLeast(strictImaxFloorBps)
            .coerceAtMost(maxImaxMasterBps)
            .toInt()
            .coerceAtLeast(100_000)
    }

    private fun isAutomaticBitrateMode(mode: VideoBitrateMode): Boolean =
        mode == VideoBitrateMode.AUTO || mode == VideoBitrateMode.CINEMA_MASTER

    private fun resolveConfiguredBitrate(
        selection: ResolvedRecordingSelection,
        fps: Int = recording.fps,
        codec: String = selection.codec
    ): Int {
        val requestedGpuCodec = when (codec) {
            "AVC", "H264" -> GpuVideoCodec.AVC
            "AV1", "AV01" -> GpuVideoCodec.AV1
            else -> GpuVideoCodec.HEVC
        }
        val effectiveFps = RecordingPreviewFrameRatePolicy.resolveEncoderFps(selection, fps)
        return if (!isAutomaticBitrateMode(_videoBitrateMode.value)) {
            gpuRecording.resolveSupportedBitrate(
                requestedBitrate = prefs.videoBitrate,
                size = selection.size,
                codec = requestedGpuCodec,
                videoDetailProfile = _videoDetailProfile.value,
                fps = effectiveFps
            )
        } else {
            gpuRecording.resolveSupportedBitrate(
                requestedBitrate = recommendedBitrateForSelection(selection, fps, codec),
                size = selection.size,
                codec = requestedGpuCodec,
                videoDetailProfile = _videoDetailProfile.value,
                fps = effectiveFps
            )
        }
    }

    fun reportedVideoBitrateBitsPerSecond(): Int =
        RecordingBitrateReportingPolicy.resolveVisibleBitrate(
            configuredBitrate = gpuRecording.videoBitrate,
            activeOutputBitrate = gpuRecording.activeOutputBitrateBitsPerSecond(),
            measuredOutputBitrate = gpuRecording.measuredOutputBitrateBitsPerSecond(),
            state = gpuRecordState.value
        )

    fun requestedVideoBitrateBitsPerSecond(): Int = prefs.videoBitrate

    private fun syncReportedRecordingBitrate() {
        recording.bitrate = reportedVideoBitrateBitsPerSecond()
    }

    private fun applyVideoBitrateValue(bps: Int) {
        prefs.videoBitrate = bps
        val selected = lastRecordingSelection?.size ?: Size(
            recording.width.coerceAtLeast(1),
            recording.height.coerceAtLeast(1)
        )
        val resolved = gpuRecording.resolveSupportedBitrate(
            requestedBitrate = bps,
            size = selected,
            codec = gpuRecording.videoCodec,
            videoDetailProfile = _videoDetailProfile.value,
            fps = recording.fps
        )
        recording.bitrate = resolved
        gpuRecording.videoBitrate = resolved
    }

    private fun applyAutomaticVideoBitrateIfNeeded(force: Boolean = false) {
        if (!isAutomaticBitrateMode(_videoBitrateMode.value)) return
        val selection = if (force) {
            resolveRecordingSelection(
                selectedVideoSize ?: prefs.videoSize,
                gpuRecording.videoCodec
            )
        } else {
            lastRecordingSelection ?: resolveRecordingSelection(
                selectedVideoSize ?: prefs.videoSize,
                gpuRecording.videoCodec
            )
        }
        lastRecordingSelection = selection
        applyVideoBitrateValue(
            recommendedBitrateForSelection(
                selection = selection,
                fps = recording.fps,
                codec = selection.codec
            )
        )
    }

    private fun buildRecordingSceneMetadata(
        encoderSize: Size,
        selection: ResolvedRecordingSelection?,
        scene: com.neoproc.neoproccamx.pipeline.SceneAnalysis?,
        recommendation: com.neoproc.neoproccamx.pipeline.CaptureRecommendation?
    ): String {
        val requestedSizeForPolicy = selection?.requestedSize ?: encoderSize
        val sourceResolutionPlan = resolveRecordingSourcePlan(requestedSizeForPolicy)
        val parityPlan = activeRecordingPipelineParityPlan()
        val manualColorPlan = currentManualVideoColorSelectionPlan()
        val processingReport = activePixelProcessingRealityReport(parityPlan)
        val noiseMetrics = currentSceneRenderProfile?.noiseMetrics ?: VideoNoiseMetrics.fromScene(scene)
        val v20MotionRisk = (scene?.motionScore ?: 0f).coerceIn(0f, 1f)
        val v20NoiseRisk = maxOf(
            noiseMetrics.lumaNoise,
            noiseMetrics.chromaNoise,
            noiseMetrics.frameToFrameRgbVariance
        ).coerceIn(0f, 1f)
        val noiseReductionSignature = currentSceneRenderProfile?.noiseReductionSignature.orEmpty()
        val detailMetadata = VideoDetailSharpeningPolicy.recordingEvidenceMetadata(
            previewMetadata = currentSceneRenderProfile?.detailSharpeningMetadata.orEmpty(),
            recordingGpuFrameImportValidated = processingReport.recordingUsesCustomGpu
        )
        return buildString {
            append("mode=")
            append(
                CameraRecordingModeMetadataPolicy.resolveModeLabel(
                    cinemaMode = effectiveRecordingCinemaMode(parityPlan),
                    rawEnabled = parityPlan.encoderRawEnabled,
                    logEnabled = parityPlan.encoderLogEnabled,
                    hdrMode = parityPlan.encoderHdrMode,
                    manualControlsVisible = _manualControlsVisible.value
                )
            )
            append(";cameraLevel=")
            append(camera.hardwareLevelName)
            append(";highEnd=")
            append(camera.highEndHardwareSupported)
            append(";fullSensorMP=")
            append(String.format(Locale.US, "%.1f", sourceResolutionPlan.fullSensorMegapixels))
            append(";cineSourceMP=")
            append(String.format(Locale.US, "%.1f", sourceResolutionPlan.cineSourceMegapixels))
            append(";readoutMP=")
            append(String.format(Locale.US, "%.1f", sourceResolutionPlan.preferredReadoutMegapixels))
            append(";fullReadoutForced=")
            append(sourceResolutionPlan.fullSensorReadoutForced)
            append(";rawSource=full_sensor")
            append(";size=")
            append("${encoderSize.width}x${encoderSize.height}")
            selection?.let {
                append(";requestedSize=")
                append("${it.requestedSize.width}x${it.requestedSize.height}")
                append(";sourceSize=")
                append("${it.sourceSize.width}x${it.sourceSize.height}")
                append(";cameraExact=")
                append(it.cameraExactMatch)
                append(";encoderExact=")
                append(it.encoderExactMatch)
                append(";upscaledToRequested=")
                append(it.upscaledToRequested)
            }
            append(";rawCompression=")
            append(_rawVideoCompression.value.name)
            append(";profile=")
            append(_colorProfile.value)
            append(";selectedColorSpace=")
            append(manualColorPlan.selectedColorSpace)
            append(";selectedColorScience=")
            append(manualColorPlan.selectedColorScience)
            append(";colorSelectionSource=")
            append(manualColorPlan.colorSelectionSource)
            append(";manualColorSelectionReady=")
            append(manualColorPlan.readyForRecording)
            append(";previewRecordingParityStatus=")
            append(parityPlan.parityCertificate.status.name)
            append(";previewRecordingParityBlocked=")
            append(parityPlan.parityCertificate.recordingBlocked)
            append(";strictVulkanParityCertified=")
            append(parityPlan.parityCertificate.strictVulkanParityCertified)
            append(";previewPipelineSignature=")
            append(parityPlan.sharedDecision.previewSignature)
            append(";recordingPipelineSignature=")
            append(parityPlan.sharedDecision.recordingSignature)
            append(";previewQualitySignature=")
            append(parityPlan.sharedDecision.previewQualitySignature)
            append(";recordingQualitySignature=")
            append(parityPlan.sharedDecision.recordingQualitySignature)
            append(";parityCertificateSignature=")
            append(parityPlan.parityCertificate.certificateSignature)
            append(";parityFeedPath=")
            append(parityPlan.feedPath.name)
            append(";camera2DirectFallback=")
            append(parityPlan.parityCertificate.camera2DirectFallback)
            append(";pixelProcessingSignature=")
            append(processingReport.reportSignature)
            append(";recordingUsesCustomGpu=")
            append(processingReport.recordingUsesCustomGpu)
            append(";customProcessingBypassed=")
            append(processingReport.camera2DirectBypassesCustomProcessing)
            append(";realProcessingStages=")
            append(processingReport.realStageNames.joinToString(separator = ","))
            append(";plannedOnlyProcessingStages=")
            append(processingReport.plannedOnlyStageNames.joinToString(separator = ","))
            append(";unsupportedProcessingStages=")
            append(processingReport.unsupportedStageNames.joinToString(separator = ","))
            buildRecordingRuntimeEvidenceMetadata(
                parityPlan = parityPlan,
                processingReport = processingReport
            ).forEach { (key, value) ->
                append(";")
                append(key)
                append("=")
                append(value)
            }
            debugValidationLabel?.let {
                append(";validation=")
                append(it)
            }
            append(";log=")
            append(_logOn.value)
            append(";simulated10bit=")
            append(gpuRecording.simulatedTenBitFallbackActive)
            append(";faceDetect=")
            append(prefs.faceDetectionEnabled)
            append(";thermalLabel=")
            append(currentThermalLabel())
            append(";thermalThrottled=")
            append(_uiStateFlow.value.thermalThrottled)
            append(";dropEstimate=")
            append(_cinemaRuntimeStats.value.dropEstimate)
            append(";v20CameraSceneRiskVersion=V20")
            append(";v20MotionRisk=")
            append(String.format(Locale.US, "%.3f", v20MotionRisk))
            append(";v20NoiseRisk=")
            append(String.format(Locale.US, "%.3f", v20NoiseRisk))
            append(";v20AntiMotionBlurEnabled=")
            append(v20MotionRisk >= 0.58f)
            noiseMetrics.toMetadata().forEach { (key, value) ->
                append(";")
                append(key)
                append("=")
                append(value)
            }
            append(";noiseReductionSignature=")
            append(noiseReductionSignature)
            detailMetadata.forEach { (key, value) ->
                append(";")
                append(key)
                append("=")
                append(value)
            }
            scene?.let {
                append(";avgLuma=")
                append(String.format(java.util.Locale.US, "%.3f", it.averageLuma))
                append(";lux=")
                append(String.format(java.util.Locale.US, "%.1f", it.estimatedLux))
                append(";motion=")
                append(String.format(java.util.Locale.US, "%.3f", it.motionScore))
                append(";edge=")
                append(String.format(java.util.Locale.US, "%.3f", it.edgeDensity))
                append(";highlight=")
                append(String.format(java.util.Locale.US, "%.3f", it.highlightRatio))
                append(";dynamicRange=")
                append(String.format(java.util.Locale.US, "%.3f", it.dynamicRangeScore))
                val hdr10Meta = com.neoproc.neoproccamx.recording.HdrImagingMath.estimateHdr10StaticMetadata(
                    averageLuma = it.averageLuma.toDouble(),
                    highlightRatio = it.highlightRatio.toDouble(),
                    dynamicRangeScore = it.dynamicRangeScore.toDouble()
                )
                append(";hdrMaxCLL=")
                append(String.format(java.util.Locale.US, "%.0f", hdr10Meta.maxCllNits))
                append(";hdrMaxFALL=")
                append(String.format(java.util.Locale.US, "%.0f", hdr10Meta.maxFallNits))
                append(";hdrMasterPeak=")
                append(String.format(java.util.Locale.US, "%.0f", hdr10Meta.masteringPeakNits))
            }
            recommendation?.let {
                append(";strategy=")
                append(
                    when {
                        it.shouldUseHdrFusion -> "HDR_FUSION"
                        it.shouldUseFrameStacking -> "FRAME_STACK"
                        else -> "SINGLE"
                    }
                )
                append(";isoCeiling=")
                append(it.isoCeiling)
                append(";exposureNs=")
                append(it.targetExposureNs)
                append(";stack=")
                append(it.stackFrameCount)
                append(";tnr=")
                append(it.temporalNoiseReduction)
            }
        }
    }

    private fun buildRecordingRuntimeEvidenceMetadata(): Map<String, String> {
        val parityPlan = activeRecordingPipelineParityPlan()
        return buildRecordingRuntimeEvidenceMetadata(
            parityPlan = parityPlan,
            processingReport = activePixelProcessingRealityReport(parityPlan)
        )
    }

    private fun buildRecordingRuntimeEvidenceMetadata(
        parityPlan: RecordingPipelineParityPlan,
        processingReport: PixelProcessingRealityReport
    ): Map<String, String> {
        val stats = vulkanImportStats.value
        val validatedEvidence = lastValidatedGpuRecordingEvidence
        val validatedGpuImport = validatedEvidence.importedFrames > 0L
        val recordingUsesCustomGpu =
            processingReport.recordingUsesCustomGpu || validatedGpuImport
        val customProcessingBypassed =
            processingReport.camera2DirectBypassesCustomProcessing && !validatedGpuImport
        val frameImportEnabled = stats.frameImportEnabled || validatedEvidence.frameImportEnabled
        val encoderSurfaceBound =
            stats.encoderSurfaceBound ||
                stats.offscreenEglEncoderSurfaceBound ||
                validatedEvidence.encoderSurfaceBound
        val preservedHardwareImportFrames = maxOf(stats.sessionImportedFrames, validatedEvidence.importedFrames)
        val sessionImportedFrames = maxOf(
            preservedHardwareImportFrames,
            stats.offscreenEglSubmittedFrames
        )
        val offscreenPath = parityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER ||
            stats.offscreenAhbEglEncoderActive ||
            stats.offscreenEglSubmittedFrames > 0L
        val gpuValidated =
            recordingUsesCustomGpu &&
                !customProcessingBypassed &&
                encoderSurfaceBound &&
                sessionImportedFrames > 0L
        val zeroCopyValidated = gpuValidated && frameImportEnabled
        val enhancementUniforms = lastGpuEnhancementUniforms
        val p010SourceTelemetry = pipeline.rawYuvImageSinkTelemetry()
        val liveEncoderSurfaceStatus = vulkanRuntime.currentEncoderSurfaceStatus
        val validatedEncoderSurfaceCurrent =
            hasCurrentRecordingEncoderSurfaceEvidence()
        val encoderSurfaceEvidenceApplicable =
            parityPlan.feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ||
                activeRecordingFeedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ||
                liveEncoderSurfaceStatus.bound ||
                validatedEncoderSurfaceCurrent
        val encoderSurfaceEvidence = when {
            liveEncoderSurfaceStatus.bound -> liveEncoderSurfaceStatus
            validatedEncoderSurfaceCurrent -> VulkanEncoderSurfaceStatus(
                bound = validatedEvidence.encoderSurfaceBoundAtValidation,
                format = validatedEvidence.encoderSurfaceFormat,
                bitDepth = validatedEvidence.encoderSurfaceBitDepth,
                precision = com.neoproc.neoproccamx.rendering.VulkanEncoderSurfaceStatusParser
                    .parsePrecision(validatedEvidence.encoderSurfacePrecision),
                precisionFallback = validatedEvidence.encoderSurfacePrecisionFallback,
                colorSpace = validatedEvidence.encoderSurfaceColorSpace,
                deliveryMode = validatedEvidence.encoderSurfaceDeliveryMode,
                deliveryCompatible =
                    validatedEvidence.encoderSurfaceDeliveryCompatible &&
                        validatedEvidence.encoderSurfacePrecisionProven,
                sourceFormat = validatedEvidence.encoderSourceFormat,
                sourceBitDepth = validatedEvidence.encoderSourceBitDepth,
                sourcePrecision =
                    com.neoproc.neoproccamx.rendering.VulkanEncoderSurfaceStatusParser
                        .parsePrecision(validatedEvidence.encoderSourcePrecision),
                sourcePrecisionProven = validatedEvidence.encoderSourcePrecisionProven
            )
            else -> VulkanEncoderSurfaceStatus.UNBOUND
        }
        val capabilitySummary = vulkanRuntime.capabilitySummary.value
        fun capabilityToken(key: String): String =
            Regex("""(?:^|\s)${Regex.escape(key)}=([^\s]+)""")
                .find(capabilitySummary)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        val memoryStats = memoryProfiler.captureSnapshot()
        val thermalLabel = currentThermalLabel()
        val manualColorPlan = currentManualVideoColorSelectionPlan()
        val debayerStats = vulkanRuntime.cinemaDebayer.getStats()
        val realtimeDispatch = pipeline.realtimeDispatchTelemetry()
        val aiFaces = _aiFaces.value
        val aiDepth = _aiDepthData.value
        val aiSubject = _aiPrimarySubject.value
        val aiSubjectMask = _aiSubjectMask.value
        val aiObserved = hasObservedAiFrameProcessing()
        val recordDevelopLightMaster =
            RecordDevelopLivePipelinePolicy.active(
                VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
            )
        return linkedMapOf(
            "recordingUsesCustomGpu" to recordingUsesCustomGpu.toString(),
            "customProcessingBypassed" to customProcessingBypassed.toString(),
            "camera2DirectFallback" to parityPlan.parityCertificate.camera2DirectFallback.toString(),
            "parityFeedPath" to parityPlan.feedPath.name,
            "previewRecordingParityStatus" to parityPlan.parityCertificate.status.name,
            "previewRecordingParityFailureCodes" to
                parityPlan.parityCertificate.failureCodes.joinToString(separator = ","),
            "previewRecordingParityBlocked" to parityPlan.parityCertificate.recordingBlocked.toString(),
            "strictVulkanParityCertified" to parityPlan.parityCertificate.strictVulkanParityCertified.toString(),
            "previewQualitySignature" to parityPlan.sharedDecision.previewQualitySignature,
            "recordingQualitySignature" to parityPlan.sharedDecision.recordingQualitySignature,
            "renderGraphShaderSha256" to parityPlan.parityCertificate.renderGraphShaderSha256,
            "renderGraphLutSha256" to parityPlan.parityCertificate.renderGraphLutSha256,
            "renderGraphStagesSignature" to parityPlan.parityCertificate.renderGraphStagesSignature,
            "selectedColorSpace" to manualColorPlan.selectedColorSpace,
            "selectedColorScience" to manualColorPlan.selectedColorScience,
            "colorSelectionSource" to manualColorPlan.colorSelectionSource,
            "manualColorSelectionReady" to manualColorPlan.readyForRecording.toString(),
            "p010SourceImageCallbacks" to p010SourceTelemetry.sourceImageCallbacks.toString(),
            "p010SourceOwnershipTransferred" to
                p010SourceTelemetry.ownershipTransferred.toString(),
            "p010SourceSinkDeclined" to p010SourceTelemetry.sinkDeclined.toString(),
            "p010SourceUnsupportedImages" to p010SourceTelemetry.unsupportedImages.toString(),
            "vulkanFrameImportEnabled" to frameImportEnabled.toString(),
            "vulkanPreviewSurfaceBound" to stats.previewSurfaceBound.toString(),
            "vulkanEncoderSurfaceBound" to encoderSurfaceBound.toString(),
            "vulkanSessionImportedFrames" to sessionImportedFrames.toString(),
            "vulkanSessionFailedImports" to stats.sessionFailedImports.toString(),
            "vulkanConsecutiveFailedImports" to stats.consecutiveFailedImports.toString(),
            "offscreenAhbEglEncoderActive" to stats.offscreenAhbEglEncoderActive.toString(),
            "offscreenEglEncoderSurfaceBound" to stats.offscreenEglEncoderSurfaceBound.toString(),
            "offscreenAhbImportedFrames" to stats.sessionImportedFrames.toString(),
            "offscreenEglSubmittedFrames" to stats.offscreenEglSubmittedFrames.toString(),
            "offscreenEglFailedFrames" to stats.offscreenEglFailedFrames.toString(),
            "offscreenEglSwapBuffers" to (stats.offscreenEglSubmittedFrames > 0L).toString(),
            "offscreenEglPresentationTimeSet" to stats.offscreenEglPresentationTimeSet.toString(),
            "realtimeDispatchAcceptedFrames" to realtimeDispatch.acceptedFrames.toString(),
            "realtimeDispatchDroppedFrames" to realtimeDispatch.droppedFrames.toString(),
            "realtimeDispatchRejectedFrames" to realtimeDispatch.rejectedFrames.toString(),
            "realtimeDispatchQueuedFrames" to realtimeDispatch.queuedFrames.toString(),
            "realtimeDispatchAverageQueueLatencyNs" to
                realtimeDispatch.averageQueueLatencyNs.toString(),
            "realtimeDispatchStrictNoConflation" to
                pipeline.realtimeDispatchStrictNoConflation().toString(),
            "vulkanValidationPreserved" to (validatedEvidence.importedFrames > 0L).toString(),
            "vulkanValidationElapsedMs" to validatedEvidence.validatedAtElapsedMs.toString(),
            "vulkanSyncPolicy" to capabilityToken("syncPolicy"),
            "vulkanQueueSubmit" to capabilityToken("queueSubmit"),
            "vulkanAhbUsage" to "GPU_SAMPLED_IMAGE|VIDEO_ENCODE",
            "vulkanAverageComputeMs" to
                (vulkanRuntime.currentMeasuredAverageComputeDurationMs?.toString() ?: ""),
            "stage2CopyCountEvidenceProven" to "false",
            "stage2PoolSummary" to (
                "vulkanFrameImportEnabled=$frameImportEnabled;" +
                    "sessionImportedFrames=$sessionImportedFrames;" +
                    "offscreenSubmitted=${stats.offscreenEglSubmittedFrames}"
                ),
            "singularityThermalStatus" to thermalLabel,
            "singularityMemoryStatus" to
                "level=${memoryStats.warningLevel};usageMb=${memoryStats.totalUsageMb};utilization=${memoryStats.utilizationPercent};oomReserveMb=${memoryStats.oomReserveFreeMb}",
            "vulkanEncoderSurfaceEvidenceApplicable" to
                encoderSurfaceEvidenceApplicable.toString(),
            "vulkanEncoderSurfaceBoundAtValidation" to
                encoderSurfaceEvidence.bound.toString(),
            "vulkanEncoderSurfaceFormat" to encoderSurfaceEvidence.format,
            "vulkanEncoderSurfaceBitDepth" to encoderSurfaceEvidence.bitDepth.toString(),
            "vulkanEncoderSurfacePrecision" to encoderSurfaceEvidence.precision.name,
            "vulkanEncoderSurfacePrecisionFallback" to
                encoderSurfaceEvidence.precisionFallback.toString(),
            "vulkanEncoderSurfaceColorSpace" to encoderSurfaceEvidence.colorSpace,
            "vulkanEncoderSurfaceDeliveryMode" to encoderSurfaceEvidence.deliveryMode,
            "vulkanEncoderSurfaceDeliveryCompatible" to
                encoderSurfaceEvidence.deliveryCompatible.toString(),
            "vulkanEncoderSurfacePrecisionProven" to
                encoderSurfaceEvidence.precisionProven.toString(),
            "vulkanEncoderSourceFormat" to encoderSurfaceEvidence.sourceFormat,
            "vulkanEncoderSourceBitDepth" to encoderSurfaceEvidence.sourceBitDepth.toString(),
            "vulkanEncoderSourcePrecision" to encoderSurfaceEvidence.sourcePrecision.name,
            "vulkanEncoderSourcePrecisionProven" to
                encoderSurfaceEvidence.sourcePrecisionProven.toString(),
            "vulkanEncodedProcessedFrames" to if (gpuValidated) {
                sessionImportedFrames.toString()
            } else {
                "0"
            },
            "gpuProcessingValidated" to gpuValidated.toString(),
            "zeroCopyValidated" to zeroCopyValidated.toString(),
            "gpuProcessingPath" to if (recordingUsesCustomGpu && offscreenPath) {
                "VULKAN_OFFSCREEN_AHB_EGL_ENCODER"
            } else if (recordingUsesCustomGpu) {
                "VULKAN_INTERNAL_HARDWARE_IMPORT_ENCODER"
            } else if (parityPlan.feedPath == RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE) {
                parityPlan.feedPath.name
            } else if (customProcessingBypassed) {
                "CAMERA2_ISP_ENCODED_FALLBACK"
            } else {
                parityPlan.feedPath.name
            },
            "zeroCopyEvidencePath" to if (zeroCopyValidated && offscreenPath) {
                "offscreenAhbEgl:submittedFrames=${stats.offscreenEglSubmittedFrames}:swapBuffers=${stats.offscreenEglSubmittedFrames > 0L}"
            } else if (zeroCopyValidated) {
                "vulkanImportStats:sessionImportedFrames=$sessionImportedFrames"
            } else {
                ""
            },
            "enhancementUniformSignature" to enhancementUniforms?.signature.orEmpty(),
            "previewEnhancementUniformSignature" to enhancementUniforms?.signature.orEmpty(),
            "recordingEnhancementUniformSignature" to enhancementUniforms?.signature.orEmpty(),
            "enhancementTemporalBlend" to (enhancementUniforms?.temporalBlend ?: 0f).toString(),
            "enhancementSpatialDenoise" to (enhancementUniforms?.spatialDenoise ?: 0f).toString(),
            "enhancementChromaDenoise" to (enhancementUniforms?.chromaDenoise ?: 0f).toString(),
            "enhancementSharpening" to (enhancementUniforms?.sharpening ?: 0f).toString(),
            "enhancementSubjectDetailGain" to (enhancementUniforms?.subjectDetailGain ?: 0f).toString(),
            "enhancementDetailProfile" to when (enhancementUniforms?.detailProfileId) {
                1f -> VideoDetailProfile.FOCUSED_DETAIL.name
                2f -> VideoDetailProfile.NATURAL_DETAIL.name
                3f -> VideoDetailProfile.EXTREME_DETAIL.name
                else -> "LEGACY"
            },
            "recordDevelopModeEnabled" to RecordDevelopModeFlag.enabled.toString(),
            "recordDevelopLightMaster" to recordDevelopLightMaster.toString(),
            "recordDevelopLiveColorProcessing" to if (recordDevelopLightMaster) {
                "FLAT_10BIT_MASTER_ONLY"
            } else {
                "LIVE_GPU_DEVELOP"
            },
            "recordDevelopOfflineDevelopRequired" to recordDevelopLightMaster.toString(),
            "recordDevelopOfflineOutputFormat" to if (recordDevelopLightMaster) {
                "R210_444"
            } else {
                ""
            },
            "recordDevelopOfflineEnhanceProfile" to if (recordDevelopLightMaster) {
                "ELITE_SNCE_TEMPORAL_DENOISE_DETAIL"
            } else {
                ""
            },
            "v20ZeroGrainClarityActive" to
                (enhancementUniforms?.v20ZeroGrainClarityActive ?: false).toString(),
            "v20ZeroGrainLock" to
                (enhancementUniforms?.v20ZeroGrainLock ?: 0f).toString(),
            "v20RgbGrainQuarantine" to
                (enhancementUniforms?.v20RgbGrainQuarantine ?: 0f).toString(),
            "v20MotionBlurRejection" to
                (enhancementUniforms?.v20MotionBlurRejection ?: 0f).toString(),
            "v20MicroDetailHonesty" to
                (enhancementUniforms?.v20MicroDetailHonesty ?: 0f).toString(),
            "v20ZeroGrainClaritySignature" to
                enhancementUniforms?.v20ZeroGrainClaritySignature.orEmpty(),
            "dualGainFusionEnabled" to
                (currentSceneRenderProfile?.dualGainFusionEnabled == true).toString(),
            "dualGainMotionMaskEnabled" to
                (currentSceneRenderProfile?.dualGainMotionMaskEnabled == true).toString(),
            "stage3RealProcessingStages" to
                processingReport.realStageNames.joinToString(separator = ","),
            "stage3CinemaDebayerPipelineActive" to debayerStats.pipelineActive.toString(),
            "stage3CinemaDebayeredFrames" to debayerStats.debayeredFrames.toString(),
            "stage3CinemaDebayerFailedFrames" to debayerStats.failedDebayers.toString(),
            "stage3CinemaDebayerAverageMs" to debayerStats.averageDebayerTimeMs.toString(),
            "stage3AiFrameResultObserved" to aiObserved.toString(),
            "stage3AiFaceCount" to aiFaces.size.toString(),
            "stage3AiEyeLandmarkCount" to
                aiFaces.sumOf { face ->
                    (if (face.leftEye != null) 1 else 0) + (if (face.rightEye != null) 1 else 0)
                }.toString(),
            "stage3AiPrimarySubjectPresent" to (aiSubject != null).toString(),
            "stage3AiPrimarySubjectConfidence" to (aiSubject?.confidence ?: 0f).toString(),
            "stage3AiPrimarySubjectStability" to (aiSubject?.stabilityScore ?: 0f).toString(),
            "stage3AiSubjectMaskPresent" to (aiSubjectMask != null).toString(),
            "stage3AiSubjectMaskConfidence" to (aiSubjectMask?.intensity ?: 0f).toString(),
            "stage3AiSegmentationPresent" to (aiSubjectMask != null).toString(),
            "stage3AiDepthMapPresent" to (aiDepth != null).toString(),
            "stage3AiDepthConfidenceAvailable" to (aiDepth?.confidenceMap != null).toString(),
            "stage3AiDepthTemporallyRefined" to (aiDepth?.temporallyRefined == true).toString(),
            "stage3AiSemanticTemporalStabilized" to
                (
                    aiDepth?.temporallyRefined == true ||
                        (aiSubject?.stabilityScore ?: 0f) > 0.5f
                    ).toString()
        ).apply {
            putAll(camera2FrameLineageRuntimeMetadata())
            putAll(camera.singularityCaptureStage2Metadata())
            if (customProcessingBypassed) {
                putAll(camera.processedVideoQualityEvidence())
            }
        }
    }

    private fun resolveRecordingSourcePlan(requestedOutput: Size): RecordingSourceResolutionPlan {
        val plan = RecordingSourceResolutionPolicy.resolvePlan(
            requestedOutput = requestedOutput,
            pixelArraySize = camera.pixelArraySize,
            maxRawSize = camera.maxRawSize,
            fallbackSensorSize = camera.fullSensorSize()
        )
        if (_uiMode.value != AppMode.VIDEO) return plan
        return plan.copy(
            deliveryFormat = if (_cinemaVideoMode.value == CinemaVideoMode.RAW) {
                "RAW_SENSOR"
            } else if (plan.fullSensorReadoutForced) {
                "FULL_SENSOR_READOUT"
            } else {
                plan.deliveryFormat
            },
            appliesCineSourcePolicy =
                _cinemaVideoMode.value == CinemaVideoMode.RAW || plan.appliesCineSourcePolicy
        )
    }

    private fun resolveRecordingReadoutOverrideSize(
        selection: ResolvedRecordingSelection,
        sourcePlan: RecordingSourceResolutionPlan = resolveRecordingSourcePlan(selection.requestedSize)
    ): Size? {
        return when {
            _uiMode.value != AppMode.VIDEO -> null
            _cinemaVideoMode.value == CinemaVideoMode.RAW -> {
                // Resolve the RAW capture tier fresh for this recording and size the readout to the
                // selected tier: the un-binned MAXIMUM_SENSOR size when viable, else the active-array
                // fallback. The same cached decision drives SENSOR_PIXEL_MODE at RAW record start.
                val tier = resolveRawCaptureTier(forceRefresh = true)
                tier.rawSize?.let { Size(it.width, it.height) } ?: sourcePlan.fullSensorSize
            }
            sourcePlan.appliesCineSourcePolicy ->
                if (sourcePlan.fullSensorReadoutForced) {
                    sourcePlan.fullSensorSize
                } else {
                    sourcePlan.cineSourceSize
                }
            else -> null
        }
    }

    // ── MAXIMUM_SENSOR RAW tier resolution ────────────────────────────────────
    // Resolves the RAW capture tier (max-res burst vs full active-array) from the user preference,
    // the device capability, and live viability (thermal / storage / memory). Cached so the readout
    // size (resolveRecordingReadoutOverrideSize) and the SENSOR_PIXEL_MODE engagement (RAW record
    // start) always agree within one recording. See RawCaptureResolutionModePolicy.
    @Volatile
    private var cachedRawCaptureTier: ResolvedRawCaptureTier? = null

    private val rawCaptureTierUiState = RawCaptureTierUiState()
    /** Live "RAW Tier (active)" label; recomputed on camera/lens switch, app start, and mode changes. */
    val rawCaptureTierStatus = rawCaptureTierUiState.status

    // Pure recompute from the current capability + live viability + selected mode. No caching or
    // session side effect — safe to call on capability/selection changes without restarting capture.
    private fun computeRawCaptureTier(): ResolvedRawCaptureTier {
        val activeArray = camera.activeArrayRawSize
        val maxRes = camera.maxResolutionRawSize
        val rawCapability = RawSensorMaxResCapabilityPolicy.resolve(
            activeArrayRawSize = activeArray?.let { CameraStreamSize(it.width, it.height) },
            maxResMapRawSizes = listOfNotNull(maxRes?.let { CameraStreamSize(it.width, it.height) })
        )
        // The device-level ultra-high-res sensor capability is the hard gate for the max-res tier.
        val capability = if (camera.ultraHighResRawSupported) {
            rawCapability
        } else {
            rawCapability.copy(maxResAvailable = false, maxResRawSize = null)
        }
        val estPerFrameBytes = maxRes?.let { it.width.toLong() * it.height.toLong() * 2L } ?: 0L
        val availableMemBytes = runCatching {
            memoryProfiler.captureSnapshot().oomReserveFreeMb.toLong() * 1024L * 1024L
        }.getOrDefault(0L)
        val viability = RawMaxResViabilityPolicy.resolve(
            estPerFrameBytes = estPerFrameBytes,
            freeStorageBytes = availableRecordingStorageBytes(),
            availableMemBytes = availableMemBytes,
            thermal = RawMaxResViabilityPolicy.thermalLevelFromLabel(currentThermalLabel())
        )
        val plan = RawCaptureResolutionModePolicy.resolve(_rawResolutionMode.value, capability, viability)
        NeoLogger.i(
            "CameraViewModel",
            "RAW tier resolved requested=${_rawResolutionMode.value} tier=${plan.tier} " +
                "size=${plan.rawSize?.let { "${it.width}x${it.height}" } ?: "none"} " +
                "maxResPixelMode=${plan.engageMaxResPixelMode} fellBack=${plan.fellBack} reason=${plan.reason}"
        )
        return plan
    }

    // Recording path: resolve once and cache so the readout size (resolveRecordingReadoutOverrideSize)
    // and the SENSOR_PIXEL_MODE engagement (RAW record start) stay in lockstep for one recording.
    private fun resolveRawCaptureTier(forceRefresh: Boolean = false): ResolvedRawCaptureTier {
        if (!forceRefresh) cachedRawCaptureTier?.let { return it }
        return computeRawCaptureTier().also { cachedRawCaptureTier = it }
    }

    /**
     * Recompute the "RAW Tier (active)" status and publish it to [rawCaptureTierStatus] observers.
     * Reads only already-populated capability fields + live viability signals — never restarts the
     * camera session. Call on camera/lens switch, app start, capability re-query, and mode changes.
     */
    private fun refreshRawCaptureTierStatus() {
        rawCaptureTierUiState.publish(rawCaptureTierStatusLabel())
    }

    private fun availableRecordingStorageBytes(): Long = runCatching {
        val app = getApplication<Application>()
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /** Human-readable active RAW tier + resolution for the Settings status row. */
    fun rawCaptureTierStatusLabel(): String {
        // During an active RAW recording the cached decision is ground truth for what is being
        // captured; otherwise recompute fresh so the indicator reflects current capability/selection.
        val tier = cachedRawCaptureTier ?: computeRawCaptureTier()
        val size = tier.rawSize?.let { "${it.width}×${it.height}" } ?: "—"
        val label = when (tier.tier) {
            RawCaptureTier.MAXIMUM_SENSOR -> "MAX SENSOR"
            RawCaptureTier.FULL_ACTIVE_ARRAY -> "FULL ARRAY"
        }
        return if (tier.fellBack && _rawResolutionMode.value != RawCaptureResolutionMode.FULL_ACTIVE_ARRAY) {
            "$label • $size (auto)"
        } else {
            "$label • $size"
        }
    }

    private fun clearRecordingReadoutOverride() {
        camera.setRecordingReadoutOverrideSize(null)
        camera.setRecordingEncoderTargetSize(null)
    }

    private fun alignEven(value: Int): Int =
        (value and 1.inv()).coerceAtLeast(2)

    private fun megapixelLabel(megapixels: Float): String =
        CinemaDisplayUtils.megapixelLabel(megapixels)

    private fun Size.normalizeForOrientation(): Size =
        RecordingSelectionPolicy.normalizeForOrientation(this)

    fun onPreviewFpsSample(fps: Float) {
        lastPreviewFpsSample = fps
        evaluatePreviewPerformanceStability(fps)
        applyRuntimePerformanceHints(fps)
        updateCinemaRuntimeStats(fps)
    }

    private fun runtimePerformanceFpsHint(previewFps: Float): Float {
        val baseFps = previewFps.takeIf { it > 0f } ?: recording.fps.toFloat()
        val cap = thermalAnalysisFpsCap
        return if (cap != null) {
            minOf(baseFps, cap)
        } else {
            baseFps
        }
    }

    private fun applyRuntimePerformanceHints(previewFps: Float) {
        val recordingGraphPrearmed = prearmedRecordingSurface != null && shouldKeepRecordingSurfacePrearmed()
        val highLoadOrRecording =
            recordingGraphPrearmed ||
            recordingStartInProgress.get() ||
            gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused ||
                gpuRecordState.value is GpuRecordingState.Stopping ||
                cameraState.value == CameraState.Recording ||
                isHighLoadPreviewMode()
        // X3/R3: when not recording / not high-load, bound the analysis cadence hint to the 25fps
        // preview cadence so idle analysis stays within the 40ms preview budget. minOf guarantees
        // this never RAISES a pre-existing lower cap (e.g. a thermal analysis cap).
        val cadenceBoundedPreviewFps =
            if (highLoadOrRecording) previewFps else minOf(previewFps, 25f)
        val fpsHint = runtimePerformanceFpsHint(cadenceBoundedPreviewFps)
        aiCameraPipeline.updateRuntimePerformance(
            previewFps = fpsHint,
            recording = highLoadOrRecording
        )
        pipeline.setRuntimePerformanceHint(previewFps = fpsHint, recording = highLoadOrRecording)
        LUTProcessor.setRuntimeQualityHint(previewFps = fpsHint, recording = highLoadOrRecording)
    }

    private fun applyRecordingCriticalRuntimeHints(
        recordingActive: Boolean,
        previewFps: Float = lastPreviewFpsSample.takeIf { it > 0f }
            ?: HighEndQualityPolicy.targetPreviewFps.toFloat()
    ) {
        val fpsHint = runtimePerformanceFpsHint(previewFps.coerceAtLeast(1f))
        aiCameraPipeline.updateRuntimePerformance(
            previewFps = fpsHint,
            recording = recordingActive
        )
        pipeline.setRuntimePerformanceHint(previewFps = fpsHint, recording = recordingActive)
        LUTProcessor.setRuntimeQualityHint(previewFps = fpsHint, recording = recordingActive)
    }

    private fun isHighLoadPreviewMode(): Boolean {
        return currentPreviewPerformanceLimits().highLoadPreviewMode
    }

    private fun syncPreviewPerformanceMode() {
        val visualHighLoad = isHighLoadPreviewMode()
        val highLoadChanged = lastAppliedHighLoadPreviewMode != visualHighLoad
        if (highLoadChanged) {
            lastAppliedHighLoadPreviewMode = visualHighLoad
            updatePreviewView { setHighLoadPreviewMode(visualHighLoad) }
        }
        val aiStateChanged = applyAiTrackingPreferences()
        if (highLoadChanged || aiStateChanged) {
            applyFrameGeometry(camera.frameGeometry.value)
        }
    }

    private suspend fun syncPreviewPerformanceModeOnMain() {
        runOnMain { syncPreviewPerformanceMode() }
    }

    private fun applyAiTrackingPreferences(): Boolean {
        val aiAssistEnabled = effectiveAiEnhancementEnabled()
        val subjectMaskEnabled = false
        val faceTrackingEnabled = prefs.faceDetectionEnabled || prefs.eyeAutofocusEnabled
        val eyeTrackingEnabled = prefs.eyeAutofocusEnabled
        val recordingOrHighLoad =
            recordingStartInProgress.get() ||
            gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused ||
                gpuRecordState.value is GpuRecordingState.Stopping ||
                cameraState.value == CameraState.Recording ||
                isHighLoadPreviewMode()
        val desiredState = RealtimeAiLoadPolicy.resolve(
            startupBlocked = startupFeatureGateActive || startupAiRampActive,
            aiAssistEnabled = aiAssistEnabled,
            recordingOrHighLoad = recordingOrHighLoad,
            faceTrackingEnabled = faceTrackingEnabled,
            eyeTrackingEnabled = eyeTrackingEnabled,
            subjectMaskEnabled = subjectMaskEnabled,
            cinemaAutoLockActive = cinemaAutoLockActive
        )
        if (desiredState == lastAppliedAiTrackingState) return false
        lastAppliedAiTrackingState = desiredState
        camera.setFaceDetectionEnabled(desiredState.faceDetectionEnabled)
        aiCameraPipeline.updateFaceTrackingEnabled(desiredState.faceDetectionEnabled)
        aiCameraPipeline.updatePoseTrackingEnabled(desiredState.poseTrackingEnabled)
        aiCameraPipeline.updateSegmentationEnabled(desiredState.segmentationEnabled)
        aiCameraPipeline.updateEnhancementEnabled(desiredState.enhancementEnabled)
        aiCameraPipeline.updateEyeTrackingEnabled(desiredState.eyeTrackingEnabled)
        aiCameraPipeline.updateDepthEnabled(desiredState.depthEnabled)
        aiCameraPipeline.updateSceneDetectionEnabled(desiredState.sceneDetectionEnabled)
        aiAfCoordinator.setEnabled(desiredState.aiAutoFocusEnabled)
        return true
    }

    private suspend fun awaitFreshPreviewSessionBeforeRecording(
        timeoutMs: Long = 20_000L,
        forceRebuild: Boolean = false,
        rebuildReaders: Boolean = false,
        waitForHardwareImportActivity: Boolean = false
    ): Boolean {
        val cameraStateSnapshot = cameraState.value
        if (cameraStateSnapshot != CameraState.Streaming &&
            cameraStateSnapshot != CameraState.Open &&
            cameraStateSnapshot != CameraState.Recording) {
            return true
        }
        val refreshed = CompletableDeferred<Unit>()
        val pendingGlPreviewReleaseSurface =
            previewSurface?.takeIf { glPreviewSurfaceReleasePending && previewSurface === it }
        camera.resetPreviewActivityTracking()
        camera.refreshOutputsAndSession(rebuildReaders = rebuildReaders, forceRebuild = forceRebuild) {
            pendingGlPreviewReleaseSurface?.let { surface ->
                runOnMainIfNeeded {
                    completeGlPreviewCompositorHandoffAfterCameraRelease(surface)
                }
            }
            refreshed.complete(Unit)
        }
        return withTimeoutOrNull(timeoutMs) {
            if (waitForHardwareImportActivity) {
                var waitedMs = 0L
                var loggedSlowImportWait = false
                val maxFrameWaitMs =
                    ProcessedGpuRecordingRequirementPolicy.HARDWARE_IMPORT_ACTIVITY_WAIT_MS
                while (!refreshed.isCompleted) {
                    val stats = vulkanImportStats.value
                    if (stats.sessionImportedFrames + stats.sessionFailedImports > 0L) {
                        if (stats.sessionImportedFrames <= 0L) {
                            Log.w(
                                "CameraViewModel",
                                "awaitFreshPreviewSession: hardware-import stream is active " +
                                    "before session callback but Vulkan has not accepted a frame yet " +
                                    "failedImports=${stats.sessionFailedImports}"
                            )
                        } else {
                            Log.i(
                                "CameraViewModel",
                                "awaitFreshPreviewSession: accepting live hardware-import activity " +
                                    "before session callback importedFrames=${stats.sessionImportedFrames}"
                            )
                        }
                        return@withTimeoutOrNull true
                    }
                    if (!loggedSlowImportWait && waitedMs >= maxFrameWaitMs) {
                        loggedSlowImportWait = true
                        Log.w(
                            "CameraViewModel",
                            "awaitFreshPreviewSession: waiting past ${maxFrameWaitMs}ms " +
                                "for hardware-import activity before session callback"
                        )
                    }
                    delay(25L)
                    waitedMs += 25L
                }
            }
            refreshed.await()
            if (waitForHardwareImportActivity) {
                var waitedMs = 0L
                val maxFrameWaitMs =
                    ProcessedGpuRecordingRequirementPolicy.HARDWARE_IMPORT_ACTIVITY_WAIT_MS
                while (true) {
                    val stats = vulkanImportStats.value
                    if (stats.sessionImportedFrames + stats.sessionFailedImports > 0L) {
                        if (stats.sessionImportedFrames <= 0L) {
                            Log.w(
                                "CameraViewModel",
                                "awaitFreshPreviewSession: hardware-import stream is active " +
                                    "but Vulkan has not accepted a frame yet " +
                                    "failedImports=${stats.sessionFailedImports}"
                            )
                        }
                        return@withTimeoutOrNull true
                    }
                    if (waitedMs >= maxFrameWaitMs) {
                        Log.e(
                            "CameraViewModel",
                            "awaitFreshPreviewSession: hidden hardware-import stream produced " +
                                "no frames after ${maxFrameWaitMs}ms"
                        )
                        return@withTimeoutOrNull false
                    }
                    delay(25L)
                    waitedMs += 25L
                }
            }
            var waitedMs = 0L
            val maxFrameWaitMs = 4000L
            while (!camera.hasRecentPreviewActivity()) {
                if (waitedMs >= maxFrameWaitMs) {
                    Log.w("CameraViewModel", "awaitFreshPreviewSession: session configured but " +
                        "no frames received after ${maxFrameWaitMs}ms - proceeding with recording")
                    break
                }
                delay(25L)
                waitedMs += 25L
            }
            true
        } ?: run {
            Log.e("CameraViewModel", "awaitFreshPreviewSession: TIMEOUT after ${timeoutMs}ms " +
                "- camera state=${cameraState.value}")
            false
        }
    }

    private suspend fun cleanupFailedRecordingStart(
        message: String,
        rawVideoEnabled: Boolean,
        refreshSession: Boolean,
        stopCameraVideo: Boolean,
        reportError: Boolean = true
    ) {
        Log.e("CameraViewModel", "Recording start cleanup: $message")
        recordingReadyForClipTiming = false
        freezeCamera2FrameLineageSession()
        updatePreviewViewAwait {
            setEncoderFeedEnabled(false)
            setEncoderSurface(null)
        }
        vulkanRuntime.setFrameImportEnabled(false)
        vulkanRuntime.setEncoderSurface(null)
        vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)
        pipeline.setDedicatedHardwareImportEnabled(false)
        pipeline.setRawYuvImageSink(null)
        pipeline.setRawYuvFrameSink(null)
        camera.setInternalRecordingHardwareImportEnabled(false)
        camera.setHardwareImportP010SourceEnabled(false)
        camera.setPreviewContinuityRecordSurfaceEnabled(false)
        camera.setHardwareImportTargetSize(null)
        camera.setNpv10TenBitCaptureEnabled(false)
        if (stopCameraVideo) {
            try {
                camera.stopVideoCapture()
            } catch (t: Throwable) {
                handleCameraError(
                    throwable = t,
                    operation = "camera.stopVideoCapture",
                    userVisibleMessage = "Camera cleanup failed while recovering recording startup."
                )
            }
        }
        try {
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            camera.setRecordSurface(null)
            prearmedRecordingSurface = null
            prearmedRecordingSurfaceAttachedToSession = false
            prearmedRecordingSignature = null
            prearmedRecordingFeedPath = null
        } catch (t: Throwable) {
            handleCameraError(
                throwable = t,
                operation = "camera.setRecordSurface(null)",
                userVisibleMessage = "Camera cleanup failed while recovering recording startup."
            )
        }
        runOnMain { applyRecordingOutputAspectOverride(null) }
        clearRecordingReadoutOverride()
        activeRecordingFeedPath = null
        resetPreviewPerformanceState()
        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        if (reportError && gpuRecordState.value !is GpuRecordingState.Error) {
            gpuRecording.reportExternalError(message)
        }
        if (refreshSession) {
            try {
                camera.refreshOutputsAndSession()
            } catch (t: Throwable) {
                handleCameraError(
                    throwable = t,
                    operation = "camera.refreshOutputsAndSession",
                    userVisibleMessage = "Preview session reset failed during recording recovery."
                )
            }
        }
        runOnMain {
            Log.i("CameraViewModel", "Recording cleanup: restoring normal preview binding")
            refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
        }
        syncCameraPreviewLookControls()
        releaseCinemaAutoLock(resetCameraState = true, reason = "recording_start_cleanup")
        if (rawVideoEnabled || rawVideoCallbacksInstalled) {
            try {
                rawVideoManager.stopCapture()
            } catch (t: Throwable) {
                handleCameraError(
                    throwable = t,
                    operation = "rawVideoManager.stopCapture",
                    userVisibleMessage = "RAW pipeline cleanup failed during recording recovery."
                )
            }
            camera.setRawVideoCaptureEnabled(false)
            camera.setRecordingSensorPixelModeMaximumResolution(false)
            cachedRawCaptureTier = null
            // Keep the RAW callbacks installed if we're still in RAW preview (seamless
            // record -> preview-debayer handoff); restore only when leaving RAW.
            reconcileRawVideoCallbacks()
            viewModelScope.launch(Dispatchers.Default) {
                delay(250L)
                try {
                    camera.drainRawReaderSynchronously()
                } catch (t: Throwable) {
                    handleCameraError(
                        throwable = t,
                        operation = "camera.drainRawReaderSynchronously",
                        userVisibleMessage = "RAW hardware buffers could not be drained after recovery."
                    )
                }
            }
        } else {
            camera.setRawVideoCaptureEnabled(false)
            camera.setRecordingSensorPixelModeMaximumResolution(false)
            cachedRawCaptureTier = null
        }
        syncActivePreviewFrameRate()
        syncPreviewPerformanceModeOnMain()
    }

    private suspend fun awaitNextVulkanImportStatsOrTimeout(
        previous: VulkanImportStats,
        deadlineElapsedMs: Long,
        maxIdleWaitMs: Long = STRICT_GPU_VALIDATION_IDLE_RECHECK_MS
    ): VulkanImportStats {
        val remainingMs = deadlineElapsedMs - SystemClock.elapsedRealtime()
        if (remainingMs <= 0L) return vulkanImportStats.value
        val waitMs = minOf(remainingMs, maxIdleWaitMs).coerceAtLeast(1L)
        return withTimeoutOrNull(waitMs) {
            vulkanImportStats.first { it != previous }
        } ?: vulkanImportStats.value
    }

    private suspend fun awaitPrearmedOffscreenHardwareImportActivity(
        timeoutMs: Long =
            ProcessedGpuRecordingRequirementPolicy.PREARMED_OFFSCREEN_IMPORT_ACTIVITY_WAIT_MS
    ): Boolean {
        val deadlineElapsedMs = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1L)
        var stats = vulkanImportStats.value
        while (
            SystemClock.elapsedRealtime() < deadlineElapsedMs &&
                gpuRecordState.value is GpuRecordingState.Recording
        ) {
            val validationStats = stats.toProcessedGpuRecordingValidationStats()
            val sourcePrecisionProven =
                vulkanRuntime.currentEncoderSurfaceStatus.sourcePrecisionProven
            if (
                validationStats.frameImportEnabled &&
                validationStats.encoderSurfaceBound &&
                validationStats.importedFrames > 0L &&
                validationStats.offscreenPresentationTimeSet &&
                sourcePrecisionProven
            ) {
                Log.i(
                    "CameraViewModel",
                    "Pre-armed offscreen AHB EGL P010 source proved live " +
                        "importedFrames=${validationStats.importedFrames} " +
                        "offscreenSubmitted=${stats.offscreenEglSubmittedFrames}"
                )
                return true
            }
            stats = awaitNextVulkanImportStatsOrTimeout(stats, deadlineElapsedMs)
        }
        Log.w(
            "CameraViewModel",
            "Pre-armed offscreen AHB EGL P010 source produced no fresh proven frame within ${timeoutMs}ms"
        )
        return false
    }

    private suspend fun awaitFirstProcessedGpuRecordingFrame(
        requireOffscreenSourceProof: Boolean = false
    ): Boolean {
        val firstFrameDeadlineElapsedMs =
            SystemClock.elapsedRealtime() +
                ProcessedGpuRecordingRequirementPolicy.FIRST_IMPORTED_FRAME_TIMEOUT_MS
        var stats = vulkanImportStats.value
        while (
            SystemClock.elapsedRealtime() < firstFrameDeadlineElapsedMs &&
                gpuRecordState.value is GpuRecordingState.Recording
        ) {
            val validationStats = stats.toProcessedGpuRecordingValidationStats()
            val decision = ProcessedGpuRecordingRequirementPolicy.validateRunning(
                frameImportEnabled = validationStats.frameImportEnabled,
                encoderSurfaceBound = validationStats.encoderSurfaceBound,
                importedFrames = validationStats.importedFrames
            )
            val offscreenSourceProven =
                !requireOffscreenSourceProof ||
                    (
                        validationStats.offscreenPresentationTimeSet &&
                            vulkanRuntime.currentEncoderSurfaceStatus.sourcePrecisionProven
                        )
            if (decision.validated && offscreenSourceProven) {
                preserveValidatedGpuRecordingEvidence(stats)
                Log.i(
                    "CameraViewModel",
                    "GPU processed recording first frame proven: " +
                        "importedFrames=${validationStats.importedFrames} " +
                        "encoderBound=${validationStats.encoderSurfaceBound} " +
                        "offscreenActive=${validationStats.offscreenPathActive} " +
                        "offscreenSubmitted=${stats.offscreenEglSubmittedFrames} " +
                        "offscreenPresentationTimeSet=${validationStats.offscreenPresentationTimeSet}"
                )
                return true
            }
            stats = awaitNextVulkanImportStatsOrTimeout(stats, firstFrameDeadlineElapsedMs)
        }
        val finalStats = vulkanImportStats.value
        val validationStats = finalStats.toProcessedGpuRecordingValidationStats()
        Log.e(
            "CameraViewModel",
            "GPU processed recording first-frame validation failed: " +
                "importEnabled=${validationStats.frameImportEnabled} " +
                "encoderBound=${validationStats.encoderSurfaceBound} " +
                "importedFrames=${validationStats.importedFrames} " +
                "offscreenActive=${validationStats.offscreenPathActive} " +
                "offscreenSubmitted=${finalStats.offscreenEglSubmittedFrames} " +
                "offscreenPresentationTimeSet=${validationStats.offscreenPresentationTimeSet} " +
                "sourcePrecisionProven=${vulkanRuntime.currentEncoderSurfaceStatus.sourcePrecisionProven}"
        )
        return false
    }

    private fun launchStrictProcessedGpuSustainedValidation(expectedFeedPath: RecordingFeedPath) {
        strictProcessedGpuValidationJob?.cancel()
        strictProcessedGpuValidationJob = viewModelScope.launch(Dispatchers.IO) {
            val validated = awaitValidatedProcessedGpuRecordingFrames(requireSustainedTargetFps = true)
            if (validated) {
                val encoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
                val parityPlan = activeRecordingPipelineParityPlan(
                    actualFeedPathOverride = expectedFeedPath
                )
                gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata(
                    encoderSize = encoderSize,
                    selection = lastRecordingSelection,
                    scene = pipeline.sceneAnalysis.value,
                    recommendation = pipeline.captureRecommendation.value
                )
                logRecordingPipelineParityPlan(
                    parityPlan,
                    reason = "offscreen-ahb-egl-encoder-sustained-validated",
                    force = true
                )
                markGpuRecordingReadyForClipTiming()
                Log.i(
                    "CameraViewModel",
                    "Strict processed GPU sustained validation completed feed=$expectedFeedPath fps=${recording.fps}"
                )
                return@launch
            }
            if (
                activeRecordingFeedPath == expectedFeedPath &&
                (
                    gpuRecordState.value is GpuRecordingState.Recording ||
                        gpuRecordState.value is GpuRecordingState.Paused
                    )
            ) {
                val message =
                    "Strict processed GPU sustained ${recording.fps} fps proof failed; keeping active clip saveable and reporting telemetry."
                if (!recordingReadyForClipTiming) {
                    markGpuRecordingReadyForClipTiming()
                }
                Log.e("CameraViewModel", message)
                setHardwareWarning(message)
            }
        }
    }

    private suspend fun awaitValidatedProcessedGpuRecordingFrames(
        requireSustainedTargetFps: Boolean = false
    ): Boolean {
        val firstFrameDeadlineElapsedMs =
            SystemClock.elapsedRealtime() +
                ProcessedGpuRecordingRequirementPolicy.FIRST_IMPORTED_FRAME_TIMEOUT_MS
        var firstFrameObserved = false
        var stats = vulkanImportStats.value
        while (
            SystemClock.elapsedRealtime() < firstFrameDeadlineElapsedMs &&
                gpuRecordState.value is GpuRecordingState.Recording
        ) {
            val validationStats = stats.toProcessedGpuRecordingValidationStats()
            val decision = ProcessedGpuRecordingRequirementPolicy.validateRunning(
                frameImportEnabled = validationStats.frameImportEnabled,
                encoderSurfaceBound = validationStats.encoderSurfaceBound,
                importedFrames = validationStats.importedFrames
            )
            if (decision.validated) {
                firstFrameObserved = true
                break
            }
            stats = awaitNextVulkanImportStatsOrTimeout(stats, firstFrameDeadlineElapsedMs)
        }
        if (!firstFrameObserved) {
            val finalStats = vulkanImportStats.value
            val validationStats = finalStats.toProcessedGpuRecordingValidationStats()
            Log.e(
                "CameraViewModel",
                "GPU processed recording validation failed: importEnabled=${validationStats.frameImportEnabled} " +
                    "encoderBound=${validationStats.encoderSurfaceBound} " +
                    "importedFrames=${validationStats.importedFrames} " +
                    "offscreenActive=${validationStats.offscreenPathActive} " +
                    "offscreenSubmitted=${finalStats.offscreenEglSubmittedFrames} " +
                    "offscreenPresentationTimeSet=${validationStats.offscreenPresentationTimeSet}"
            )
            return false
        }

        val sustainedDeadlineElapsedMs =
            SystemClock.elapsedRealtime() +
                ProcessedGpuRecordingRequirementPolicy.SUSTAINED_IMPORTED_FRAME_TIMEOUT_MS
        var lastFailureMessage: String? = null
        val sustainedImportSamples = mutableListOf<Pair<Long, Long>>()
        while (
            SystemClock.elapsedRealtime() < sustainedDeadlineElapsedMs &&
                gpuRecordState.value is GpuRecordingState.Recording
        ) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val validationStats = stats.toProcessedGpuRecordingValidationStats()
            if (
                sustainedImportSamples.isNotEmpty() &&
                validationStats.importedFrames < sustainedImportSamples.last().second
            ) {
                sustainedImportSamples.clear()
            }
            sustainedImportSamples += nowElapsedMs to validationStats.importedFrames
            while (
                sustainedImportSamples.size > 1 &&
                nowElapsedMs - sustainedImportSamples[1].first >=
                    ProcessedGpuRecordingRequirementPolicy.SUSTAINED_IMPORT_OBSERVATION_MS
            ) {
                sustainedImportSamples.removeAt(0)
            }
            val sustainedWindowStart = sustainedImportSamples.firstOrNull()
            val decision = ProcessedGpuRecordingRequirementPolicy.validateSustainedRunningWindow(
                frameImportEnabled = validationStats.frameImportEnabled,
                encoderSurfaceBound = validationStats.encoderSurfaceBound,
                importedFrames = validationStats.importedFrames,
                importedFramesAtWindowStart = sustainedWindowStart?.second ?: validationStats.importedFrames,
                windowStartElapsedMs = sustainedWindowStart?.first ?: nowElapsedMs,
                lastSuccessfulImportElapsedMs = validationStats.lastSuccessfulImportElapsedMs,
                currentElapsedRealtimeMs = nowElapsedMs,
                targetFps = recording.fps
            )
            if (decision.validated) {
                val strictOffscreenSourceProven =
                    !requireSustainedTargetFps ||
                        activeRecordingFeedPath != RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER ||
                        (
                            validationStats.offscreenPresentationTimeSet &&
                                vulkanRuntime.currentEncoderSurfaceStatus.sourcePrecisionProven
                            )
                if (!strictOffscreenSourceProven) {
                    lastFailureMessage =
                        "Offscreen EGL P010 source precision proof is still warming up."
                    stats = awaitNextVulkanImportStatsOrTimeout(stats, sustainedDeadlineElapsedMs)
                    continue
                }
                preserveValidatedGpuRecordingEvidence(stats)
                Log.i(
                    "CameraViewModel",
                    "GPU processed recording validated: importedFrames=${validationStats.importedFrames} " +
                        "sustainedWindowFrames=" +
                        "${validationStats.importedFrames - (sustainedWindowStart?.second ?: validationStats.importedFrames)} " +
                        "encoderBound=${validationStats.encoderSurfaceBound} " +
                        "offscreenActive=${validationStats.offscreenPathActive} " +
                        "offscreenSubmitted=${stats.offscreenEglSubmittedFrames} " +
                        "estimatedFps=${vulkanRuntime.getEstimatedFps()}"
                )
                return true
            }
            lastFailureMessage = decision.failureMessage
            stats = awaitNextVulkanImportStatsOrTimeout(stats, sustainedDeadlineElapsedMs)
        }
        val finalStats = vulkanImportStats.value
        val validationStats = finalStats.toProcessedGpuRecordingValidationStats()
        if (ProcessedGpuRecordingRequirementPolicy.isThroughputFailure(lastFailureMessage)) {
            if (requireSustainedTargetFps) {
                markVulkanRecordingThroughputFailure(lastFailureMessage)
                Log.e(
                    "CameraViewModel",
                    "GPU processed recording strict validation failed: sustained target FPS not proven. " +
                        "importEnabled=${validationStats.frameImportEnabled} " +
                        "encoderBound=${validationStats.encoderSurfaceBound} " +
                        "importedFrames=${validationStats.importedFrames} " +
                        "firstImportMs=${validationStats.firstSuccessfulImportElapsedMs} " +
                        "lastImportMs=${validationStats.lastSuccessfulImportElapsedMs} " +
                        "offscreenActive=${validationStats.offscreenPathActive} " +
                        "offscreenSubmitted=${finalStats.offscreenEglSubmittedFrames} " +
                        "estimatedFps=${vulkanRuntime.getEstimatedFps()} " +
                        "targetFps=${recording.fps} reason=$lastFailureMessage"
                )
                return false
            }
            val startupContinuityDecision = ProcessedGpuRecordingRequirementPolicy.validateNearTargetStartupRunning(
                frameImportEnabled = validationStats.frameImportEnabled,
                encoderSurfaceBound = validationStats.encoderSurfaceBound,
                importedFrames = validationStats.importedFrames,
                firstSuccessfulImportElapsedMs = validationStats.firstSuccessfulImportElapsedMs,
                lastSuccessfulImportElapsedMs = validationStats.lastSuccessfulImportElapsedMs,
                currentElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                targetFps = recording.fps
            )
            if (startupContinuityDecision.validated) {
                preserveValidatedGpuRecordingEvidence(finalStats)
                Log.w(
                    "CameraViewModel",
                    "GPU processed recording startup accepted with near-target live import cadence: " +
                        "importEnabled=${validationStats.frameImportEnabled} " +
                        "encoderBound=${validationStats.encoderSurfaceBound} " +
                        "importedFrames=${validationStats.importedFrames} " +
                        "firstImportMs=${validationStats.firstSuccessfulImportElapsedMs} " +
                        "lastImportMs=${validationStats.lastSuccessfulImportElapsedMs} " +
                        "offscreenActive=${validationStats.offscreenPathActive} " +
                        "offscreenSubmitted=${finalStats.offscreenEglSubmittedFrames} " +
                        "estimatedFps=${vulkanRuntime.getEstimatedFps()} " +
                        "sustainedReason=$lastFailureMessage"
                )
                return true
            }
            // FPS-GATE ALWAYS-SAVE (default OFF): the ONLY failure is a throughput/fps shortfall (thermal),
            // yet a genuine clip already exists on the encoder (frames imported, surface bound). Rather than
            // abort + delete, ACCEPT and finalize at the achieved fps (real per-frame PTS already carry the
            // true cadence). Never accepts a broken clip (0 frames / unbound fail the policy).
            if (
                com.neoproc.neoproccamx.rendering.AlwaysSaveAtAchievedFpsFlag.enabled &&
                AlwaysSaveAtAchievedFpsPolicy.canAcceptAtAchievedFps(
                    frameImportEnabled = validationStats.frameImportEnabled,
                    encoderSurfaceBound = validationStats.encoderSurfaceBound,
                    importedFrames = validationStats.importedFrames,
                    firstSuccessfulImportElapsedMs = validationStats.firstSuccessfulImportElapsedMs,
                    lastSuccessfulImportElapsedMs = validationStats.lastSuccessfulImportElapsedMs
                )
            ) {
                preserveValidatedGpuRecordingEvidence(finalStats)
                Log.w(
                    "CameraViewModel",
                    "GPU processed recording ACCEPTED at achieved fps (throughput safety net): " +
                        "importedFrames=${validationStats.importedFrames} " +
                        "encoderBound=${validationStats.encoderSurfaceBound} " +
                        "offscreenActive=${validationStats.offscreenPathActive} " +
                        "offscreenSubmitted=${finalStats.offscreenEglSubmittedFrames} " +
                        "achievedFps=${vulkanRuntime.getEstimatedFps()} targetFps=${recording.fps} " +
                        "reason=$lastFailureMessage"
                )
                return true
            }
            markVulkanRecordingThroughputFailure(lastFailureMessage)
            Log.e(
                "CameraViewModel",
                "GPU processed recording validation failed: import throughput below recording floor. " +
                    "importEnabled=${validationStats.frameImportEnabled} " +
                    "encoderBound=${validationStats.encoderSurfaceBound} " +
                    "importedFrames=${validationStats.importedFrames} " +
                    "firstImportMs=${validationStats.firstSuccessfulImportElapsedMs} " +
                    "lastImportMs=${validationStats.lastSuccessfulImportElapsedMs} " +
                    "offscreenActive=${validationStats.offscreenPathActive} " +
                    "offscreenSubmitted=${finalStats.offscreenEglSubmittedFrames} " +
                    "estimatedFps=${vulkanRuntime.getEstimatedFps()} " +
                    "reason=$lastFailureMessage"
            )
            return false
        }
        if (requireSustainedTargetFps) {
            Log.e(
                "CameraViewModel",
                "GPU processed recording strict validation failed: sustained target FPS evidence missing. " +
                    "importEnabled=${validationStats.frameImportEnabled} " +
                    "encoderBound=${validationStats.encoderSurfaceBound} " +
                    "importedFrames=${validationStats.importedFrames} " +
                    "firstImportMs=${validationStats.firstSuccessfulImportElapsedMs} " +
                    "lastImportMs=${validationStats.lastSuccessfulImportElapsedMs} " +
                    "offscreenActive=${validationStats.offscreenPathActive} " +
                    "offscreenSubmitted=${finalStats.offscreenEglSubmittedFrames} " +
                    "offscreenPresentationTimeSet=${validationStats.offscreenPresentationTimeSet} " +
                    "estimatedFps=${vulkanRuntime.getEstimatedFps()} " +
                    "targetFps=${recording.fps} " +
                    "reason=${lastFailureMessage ?: "sustained validation timed out"}"
            )
            return false
        }
        val startupContinuityDecision = ProcessedGpuRecordingRequirementPolicy.validateStartupContinuity(
            frameImportEnabled = validationStats.frameImportEnabled,
            encoderSurfaceBound = validationStats.encoderSurfaceBound,
            importedFrames = validationStats.importedFrames,
            lastSuccessfulImportElapsedMs = validationStats.lastSuccessfulImportElapsedMs,
            currentElapsedRealtimeMs = SystemClock.elapsedRealtime()
        )
        if (startupContinuityDecision.validated) {
            preserveValidatedGpuRecordingEvidence(finalStats)
            Log.w(
                    "CameraViewModel",
                    "GPU processed recording startup accepted with live import continuity: " +
                    "importEnabled=${validationStats.frameImportEnabled} " +
                    "encoderBound=${validationStats.encoderSurfaceBound} " +
                    "importedFrames=${validationStats.importedFrames} " +
                    "firstImportMs=${validationStats.firstSuccessfulImportElapsedMs} " +
                    "lastImportMs=${validationStats.lastSuccessfulImportElapsedMs} " +
                    "offscreenActive=${validationStats.offscreenPathActive} " +
                    "offscreenSubmitted=${finalStats.offscreenEglSubmittedFrames} " +
                    "estimatedFps=${vulkanRuntime.getEstimatedFps()} " +
                    "sustainedReason=${lastFailureMessage ?: "sustained validation timed out"}"
            )
            return true
        }
        Log.e(
            "CameraViewModel",
            "GPU processed recording validation failed: importEnabled=${validationStats.frameImportEnabled} " +
                "encoderBound=${validationStats.encoderSurfaceBound} " +
                "importedFrames=${validationStats.importedFrames} " +
                "firstImportMs=${validationStats.firstSuccessfulImportElapsedMs} " +
                "lastImportMs=${validationStats.lastSuccessfulImportElapsedMs} " +
                "offscreenActive=${validationStats.offscreenPathActive} " +
                "offscreenSubmitted=${finalStats.offscreenEglSubmittedFrames} " +
                "offscreenPresentationTimeSet=${validationStats.offscreenPresentationTimeSet} " +
                "estimatedFps=${vulkanRuntime.getEstimatedFps()} " +
                "reason=${startupContinuityDecision.failureMessage ?: lastFailureMessage ?: "sustained validation timed out"}"
        )
        return false
    }

    private fun preserveValidatedGpuRecordingEvidence(stats: VulkanImportStats) {
        val validationStats = stats.toProcessedGpuRecordingValidationStats()
        if (!validationStats.frameImportEnabled ||
            !validationStats.encoderSurfaceBound ||
            validationStats.importedFrames <= 0L
        ) {
            return
        }
        if (validationStats.importedFrames < lastValidatedGpuRecordingEvidence.importedFrames) {
            return
        }
        val encoderSurfaceStatus = vulkanRuntime.currentEncoderSurfaceStatus
        lastValidatedGpuRecordingEvidence = ValidatedGpuRecordingEvidence(
            frameImportEnabled = validationStats.frameImportEnabled,
            encoderSurfaceBound = validationStats.encoderSurfaceBound,
            importedFrames = validationStats.importedFrames,
            validatedAtElapsedMs = SystemClock.elapsedRealtime(),
            recordingSignature = currentVulkanRecordingThroughputSignature(),
            encoderSurfaceBoundAtValidation = encoderSurfaceStatus.bound,
            encoderSurfaceFormat = encoderSurfaceStatus.format,
            encoderSurfaceBitDepth = encoderSurfaceStatus.bitDepth,
            encoderSurfacePrecision = encoderSurfaceStatus.precision.name,
            encoderSurfacePrecisionFallback = encoderSurfaceStatus.precisionFallback,
            encoderSurfaceColorSpace = encoderSurfaceStatus.colorSpace,
            encoderSurfaceDeliveryMode = encoderSurfaceStatus.deliveryMode,
            encoderSurfaceDeliveryCompatible = encoderSurfaceStatus.deliveryCompatible,
            encoderSurfacePrecisionProven = encoderSurfaceStatus.precisionProven,
            encoderSourceFormat = encoderSurfaceStatus.sourceFormat,
            encoderSourceBitDepth = encoderSurfaceStatus.sourceBitDepth,
            encoderSourcePrecision = encoderSurfaceStatus.sourcePrecision.name,
            encoderSourcePrecisionProven = encoderSurfaceStatus.sourcePrecisionProven
        )
        vulkanRecordingThroughputBlockedSignature = ""
        vulkanRecordingThroughputBlockedUntilElapsedMs = 0L
    }

    private fun applyCinemaModeSelection(
        mode: CinemaVideoMode,
        persist: Boolean = true,
        refreshSession: Boolean = true
    ) {
        val previousMode = _cinemaVideoMode.value
        val previousPipelineSignature = activeVideoModePipelineContract().sessionSignature
        val requestedMode = normalizeNpv10CinemaMode(mode)
        val normalizedMode = RecordingCapabilityPolicy.normalizeCinemaMode(
            requestedMode = requestedMode,
            rawSupported = camera.supportsRaw
        )
        _cinemaVideoMode.value = normalizedMode
        if (_uiMode.value == AppMode.VIDEO) {
            when (normalizedMode) {
                CinemaVideoMode.PRO -> {
                    _rawOn.value = false
                    _logOn.value = false
                    _rawVideoCompression.value = normalizeRawCompressionForMode(_rawVideoCompression.value, normalizedMode)
                    if (_lutEnabled.value) {
                        setLutEnabled(false)
                    }
                }
                CinemaVideoMode.LOG -> {
                    _rawOn.value = false
                    _logOn.value = true
                    if (_videoHdrMode.value != "OFF" || _videoHdrOn.value) {
                        _videoHdrMode.value = "OFF"
                        _videoHdrOn.value = false
                    }
                    _rawVideoCompression.value = normalizeRawCompressionForMode(_rawVideoCompression.value, normalizedMode)
                    if (_videoHdrMode.value != "OFF" && recording.codec != VideoCodecType.HEVC) {
                        setVideoCodec(VideoCodecType.HEVC)
                    }
                }
                CinemaVideoMode.RAW -> {
                    _rawOn.value = camera.supportsRaw
                    _logOn.value = false
                    if (_videoHdrMode.value != "OFF" || _videoHdrOn.value) {
                        _videoHdrMode.value = "OFF"
                        _videoHdrOn.value = false
                    }
                    _rawVideoCompression.value = normalizeRawCompressionForMode(_rawVideoCompression.value, normalizedMode)
                    rawVideoManager.compressionMode = _rawVideoCompression.value
                    if (recording.fps > RAW_MODE_MAX_FPS) {
                        setFPS(recording.fps)
                    }
                    enforceRawVideoResolutionCapIfNeeded()
                }
            }
            syncRecordingCodecAndBitDepthContract(cinemaMode = normalizedMode)
            colorScience.logEnabled = if (_uiMode.value == AppMode.VIDEO) {
                isVideoLogPreviewActive()
            } else {
                _logOn.value
            }
            syncEffectiveRecordingColorProfile()
        }
        videoFeatureState = videoFeatureState.copy(
            rawEnabled = normalizedMode == CinemaVideoMode.RAW,
            logEnabled = normalizedMode == CinemaVideoMode.LOG,
            cinemaMode = normalizedMode,
            hdrEnabled = _videoHdrOn.value,
            hdrMode = _videoHdrMode.value,
            colorSpace = _videoColorSpace.value,
            rawCompression = _rawVideoCompression.value
        )
        if (persist) {
            prefs.videoMode = normalizedMode.name
            if (_uiMode.value == AppMode.VIDEO) {
                prefs.rawEnabled = _rawOn.value
                prefs.logEnabled = _logOn.value
                prefs.videoHdrMode = _videoHdrMode.value
                prefs.videoColorSpace = _videoColorSpace.value
                prefs.rawVideoCompression = _rawVideoCompression.value.name
            }
        }
        enforceVideoMeteringDefaultIfNeeded(cinemaMode = normalizedMode)
        applyAutomaticVideoBitrateIfNeeded(force = true)
        enforceBeyondImaxConstraints(normalizedMode)
        if (normalizedMode == CinemaVideoMode.PRO) {
            releaseCinemaAutoLock(resetCameraState = true, reason = "cinema_mode_pro")
        }
        // Activate the Vulkan Cinema-Debayer compute pipeline only when RAW
        // preview is active; deactivate for all other modes to save GPU power.
        val rawCinemaPreviewActive = _uiMode.value == AppMode.VIDEO && normalizedMode == CinemaVideoMode.RAW
        vulkanRuntime.setCinemaDebayerActive(rawCinemaPreviewActive)
        // Stage 1 (RAW debayer preview): feed the RAW_SENSOR stream into the debayer during RAW preview
        // — adds the RAW reader to the preview session + repeating-request target — and arm the RAW
        // frame callbacks. reconcile keeps callbacks installed if a recording is active. Off elsewhere.
        camera.setRawPreviewCaptureEnabled(rawCinemaPreviewActive)
        reconcileRawVideoCallbacks()
        releaseRawPreviewMonitorForModeExit(previousMode, normalizedMode)
        updateCinemaRuntimeStats(lastPreviewFpsSample)
        if (_uiMode.value == AppMode.VIDEO) {
            syncCameraPreviewLookControls()
            pushVulkanPreviewRenderConfig(force = true)
        }
        // FIX: Invalidate LUT cache to force regeneration when switching modes
        cachedPreviewLutRequest = null
        refreshPreviewCompositorState(rebuildLut = true)

        // Session rebuild is required when:
        //  - Entering/leaving RAW (adds/removes RAW reader)
        //  - PRO ↔ LOG (SDR ↔ HLG10 dynamic range profile change)
        //  - LOG ↔ HDR (HLG10 ↔ HDR10/DV profile change)
        //  - Any mode switch that changes the OutputConfiguration dynamic range.
        // The refresh is gated by sessionRefreshPending CAS in refreshOutputsAndSession
        // to avoid double-refreshes when syncCameraHdrDynamicRangeMode already triggered one.
        if (refreshSession) {
            refreshSessionForModeSwitchIfIdle(
                previousMode = previousMode,
                currentMode = normalizedMode,
                previousPipelineSignature = previousPipelineSignature,
                currentPipelineSignature = activeVideoModePipelineContract().sessionSignature
            )
        }
        syncModeDomainFromLegacy()
    }

    private fun deriveCinemaModeFromFlags(): CinemaVideoMode =
        activeRecordingPipelineParityPlan().let { parityPlan ->
            RecordingCapabilityPolicy.resolveSessionPlan(
                requestedCodec = recording.codec,
                av1Supported = supportsAv1Fast(),
                hdrRequested = parityPlan.hdrRequestedForSessionPlan,
                rawRequested = parityPlan.rawRequestedForSessionPlan,
                rawSupported = camera.supportsRaw,
                logEnabled = parityPlan.logEnabledForSessionPlan,
                tenBitRequested = parityPlan.tenBitRequestedForSessionPlan
            ).cinemaMode
        }

    fun setCinemaVideoMode(mode: CinemaVideoMode) {
        val targetMode = normalizeNpv10CinemaMode(mode)
        modeController.setCinemaVideoMode(targetMode)
        applyCinemaModeSelection(targetMode)
        syncModeDomainFromLegacy()
    }

    private fun normalizeNpv10CinemaMode(mode: CinemaVideoMode): CinemaVideoMode = when {
        // LOG is convert-only. RAW remains available and records through the separate NPRAW path.
        mode == CinemaVideoMode.LOG && isNpv10LiveLogBlocked() ->
            CinemaVideoMode.PRO
        else -> mode
    }

    private fun isNpv10LiveLogBlocked(): Boolean =
        _uiMode.value == AppMode.VIDEO && _uncompressedCinemaPipeline.value

    private fun isBeyondImaxMode(mode: CinemaVideoMode): Boolean =
        mode == CinemaVideoMode.RAW

    private fun isBeyondImaxActive(): Boolean =
        _uiMode.value == AppMode.VIDEO && isBeyondImaxMode(_cinemaVideoMode.value)

    private fun enforceBeyondImaxConstraints(mode: CinemaVideoMode) {
        if (_uiMode.value != AppMode.VIDEO) return
        val constraints = BeyondImaxPreviewConstraintPolicy.resolve(
            uiMode = _uiMode.value,
            cinemaMode = mode,
            currentAspect = _previewAspect.value
        )
        if (_previewAspect.value != constraints.previewAspect) {
            applyPreviewAspectRatio(constraints.previewAspect, persist = true)
        }
        if (constraints.resetZoomToOne && pendingZoomRatio != 1f) {
            pendingZoomRatio = 1f
            camera.setZoom(1f)
        }
    }

    fun setRawVideoCompressionMode(mode: RawCompressionMode) {
        modeController.setRawVideoCompressionMode(mode)
        setRawVideoCompressionModeInternal(mode)
        syncModeDomainFromLegacy()
    }

    private fun setRawVideoCompressionModeInternal(mode: RawCompressionMode) {
        val resolvedMode = normalizeRawCompressionForMode(mode)
        _rawVideoCompression.value = resolvedMode
        rawVideoManager.compressionMode = resolvedMode
        prefs.rawVideoCompression = resolvedMode.name
        videoFeatureState = videoFeatureState.copy(rawCompression = resolvedMode)
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }

    /**
     * Selects the RAW capture-resolution tier: AUTO (default — max sensor when viable, else full
     * active array), MAXIMUM_SENSOR, or FULL_ACTIVE_ARRAY. Persists and re-resolves so the next
     * recording reflects the choice immediately. See [RawCaptureResolutionModePolicy].
     */
    fun setRawResolutionMode(mode: RawCaptureResolutionMode) {
        if (_rawResolutionMode.value == mode) return
        _rawResolutionMode.value = mode
        prefs.rawResolutionMode = mode
        refreshRawCaptureTierStatus()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }

    private fun updateCinemaRuntimeStats(previewFps: Float) {
        statsEngine.updateCinemaRuntimeStats(previewFps)
        refreshBackgroundRestrictionWarning()
        refreshMemoryWarning()
    }
    
    private fun refreshMemoryWarning() {
        val recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused
        val warningMessage = memoryProfiler.resolveMemoryWarning(recordingActive)
        val currentMessage = _uiStateFlow.value.hardwareWarning
        val currentIsMemoryWarning = memoryProfiler.isMemoryWarning(currentMessage)
        
        when {
            warningMessage != null && (currentMessage == null || currentIsMemoryWarning) -> {
                setHardwareWarning(warningMessage)
            }
            warningMessage == null && currentIsMemoryWarning -> {
                setHardwareWarning(null)
            }
        }
    }

    private fun updateShutterRuleGuidance() {
        val warning = resolveShutterRuleGuidance()
        val current = _uiStateFlow.value
        if (current.shutterGuidance != warning) {
            updateState { it.copy(shutterGuidance = warning) }
        }
    }

    private fun resolveShutterRuleGuidance(): String? =
        CinemaDisplayUtils.resolveShutterRuleGuidance(
            isVideoMode = _uiMode.value == AppMode.VIDEO,
            fps = recording.fps,
            currentExposureNs = cameraParams.value.exposureNs
        )

    private fun handleCameraError(
        throwable: Throwable,
        operation: String,
        userVisibleMessage: String? = null
    ) {
        Log.e("CameraViewModel", "CRITICAL_HW_FAILURE: $operation", throwable)
        val resolvedMessage = userVisibleMessage ?: when {
            operation.contains("thermal", ignoreCase = true) ||
                throwable.message?.contains("thermal", ignoreCase = true) == true ->
                "Thermal Throttling: Bitrate Reduced"
            throwable.message?.contains("timeout", ignoreCase = true) == true ->
                "Camera hardware timeout reached; using a safe fallback."
            else -> "Camera hardware limit reached."
        }
        setHardwareWarning(resolvedMessage)
    }

    private fun setHardwareWarning(message: String?) {
        val current = _uiStateFlow.value
        if (current.hardwareWarning == message) return
        updateState { it.copy(hardwareWarning = message) }
    }

    private fun refreshBackgroundRestrictionWarning() {
        val app = getApplication<Application>()
        val warningMessage = BackgroundRestrictionPolicy.resolveRuntimeWarning(
            context = app,
            recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused
        )
        val currentMessage = _uiStateFlow.value.hardwareWarning
        val currentIsBackgroundWarning =
            BackgroundRestrictionPolicy.isBackgroundRestrictionWarning(currentMessage)
        when {
            warningMessage != null && (currentMessage == null || currentIsBackgroundWarning) -> {
                setHardwareWarning(warningMessage)
            }
            warningMessage == null && currentIsBackgroundWarning -> {
                setHardwareWarning(null)
            }
        }
    }

    private suspend fun awaitGpuRecordingState(timeoutMs: Long = 1_200L): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            when (val currentState = gpuRecordState.value) {
                GpuRecordingState.Recording -> return true
                is GpuRecordingState.Error -> {
                    Log.e("CameraViewModel", "awaitGpuRecordingState: recorder error: ${currentState.message}")
                    return false
                }
                else -> delay(25L)
            }
        }
        Log.w("CameraViewModel", "awaitGpuRecordingState: timed out. Current state: ${gpuRecordState.value}")
        return gpuRecordState.value is GpuRecordingState.Recording
    }

    private suspend fun awaitCameraRecordingState(timeoutMs: Long = 1_500L): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            when (val currentState = cameraState.value) {
                CameraState.Recording -> return true
                CameraState.Closed,
                CameraState.Closing -> {
                    Log.w("CameraViewModel", "awaitCameraRecordingState: camera is closing/closed")
                    return false
                }
                is CameraState.Error -> {
                    Log.e("CameraViewModel", "awaitCameraRecordingState: camera error: ${currentState.msg}")
                    return false
                }
                else -> delay(25L)
            }
        }
        Log.w("CameraViewModel", "awaitCameraRecordingState: timed out. Current state: ${cameraState.value}")
        return cameraState.value == CameraState.Recording
    }

    private fun formatStorageLabel(freeBytes: Long, remainingMinutes: Int?): String =
        CinemaDisplayUtils.formatStorageLabel(freeBytes, remainingMinutes)

    private fun currentThermalLabel(): String =
        SystemThermalStatusReader.label(getApplication<Application>())

    private fun currentThermalStatusCode(): Int? =
        SystemThermalStatusReader.status(getApplication<Application>())

    private fun strictHighEndThermalAdmissionFailure(): String? {
        if (!(HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly)) return null
        val status = currentThermalStatusCode()
        val label = currentThermalLabel()
        val emergencyThermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && status != null) {
            status == PowerManager.THERMAL_STATUS_EMERGENCY ||
                status == PowerManager.THERMAL_STATUS_SHUTDOWN
        } else {
            label.contains("emergency", ignoreCase = true) ||
                label.contains("shutdown", ignoreCase = true)
        }
        if (!emergencyThermal) return null
        return "Strict high-end recording refused: thermal=$label is emergency/shutdown; " +
            "cool the device before starting ${recording.width}x${recording.height}@${recording.fps}fps"
    }

    private fun maybeApplyThermalGuard(thermalLabel: String) {
        val recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused
        if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) {
            gpuRecording.thermalEncoderComplexity = 10
            if (thermalBitrateReductionApplied && isAutomaticBitrateMode(_videoBitrateMode.value)) {
                applyAutomaticVideoBitrateIfNeeded(force = true)
                if (recordingActive && gpuRecording.updateActiveEncoderBitrate(recording.bitrate)) {
                    recording.bitrate = gpuRecording.activeOutputBitrateBitsPerSecond()
                }
            }
            thermalBitrateReductionApplied = false
            val hadPreviewGuard = thermalPreviewMaxLongEdge != null || thermalPreviewMaxArea != null
            val hadAnalysisCap = thermalAnalysisFpsCap != null
            thermalAnalysisFpsCap = null
            thermalPreviewMaxLongEdge = null
            thermalPreviewMaxArea = null
            if (_uiStateFlow.value.hardwareWarning?.startsWith("Thermal Throttling: Bitrate Reduced") == true) {
                setHardwareWarning(null)
            }
            if (hadAnalysisCap) {
                applyRuntimePerformanceHints(lastPreviewFpsSample)
            }
            if (hadPreviewGuard && !recordingActive) {
                refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
            }
            return
        }

        val powerManager = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalDecision = thermalPolicyResolver.resolve(
            thermalLabel = thermalLabel,
            recordingActive = recordingActive,
            powerManager = powerManager
        )
        val kalmanDecision = thermalPolicyResolver.kalmanDecision
        gpuRecording.thermalEncoderComplexity = kalmanDecision.encoderComplexity
        val previousCap = thermalAnalysisFpsCap
        thermalAnalysisFpsCap = thermalDecision.analysisFpsCap.toFloat()
        if (previousCap != thermalAnalysisFpsCap) {
            applyRuntimePerformanceHints(lastPreviewFpsSample)
        }

        // CP-QV15: feed the adaptive thermal signal into the live OmniClarity motion stack (gated, default
        // off). Placed BEFORE the cool-device early-return so a return to a cool state re-pushes the native
        // defaults (self-restoring). On a cool/moderate device (severity < SEVERE) this pushes refine 8 /
        // alphas 0.0/0.9 — bit-for-bit unchanged; under severe heat it sheds refine iters + temporal blend.
        if (OmniClarityMotionTuningFlag.enabled) {
            vulkanRuntime.updateOmniClarityMotionTuning(
                thermalThrottling = OmniClarityMotionTuningResolver.shouldThrottleForThermal(
                    thermalDecision.thermalSeverity
                )
            )
        }

        if (!thermalDecision.shouldApplyPreviewGuard) {
            if (thermalBitrateReductionApplied && isAutomaticBitrateMode(_videoBitrateMode.value)) {
                applyAutomaticVideoBitrateIfNeeded(force = true)
                if (recordingActive && gpuRecording.updateActiveEncoderBitrate(recording.bitrate)) {
                    recording.bitrate = gpuRecording.activeOutputBitrateBitsPerSecond()
                }
            }
            thermalBitrateReductionApplied = false
            if (_uiStateFlow.value.hardwareWarning?.startsWith("Thermal Throttling: Bitrate Reduced") == true) {
                setHardwareWarning(null)
            }
            if (thermalPreviewMaxLongEdge != null || thermalPreviewMaxArea != null) {
                thermalPreviewMaxLongEdge = null
                thermalPreviewMaxArea = null
                refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
            }
            return
        }

        if (recordingActive && !thermalBitrateReductionApplied && isAutomaticBitrateMode(_videoBitrateMode.value)) {
            val reduced = applyThermalBitrateReduction()
            val feedback = ThermalRuntimePolicy.resolveBitrateMitigationFeedback(reduced)
            if (feedback.warningMessage != null) {
                setHardwareWarning(
                    appendThermalCooldownEstimate(
                        warningMessage = feedback.warningMessage,
                        cooldownEstimateSec = kalmanDecision.cooldownEstimateSec
                    )
                )
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastThermalMitigationAtMs < 15_000L) return
        lastThermalMitigationAtMs = now
        val changed = thermalPreviewMaxLongEdge != THERMAL_GUARD_PREVIEW_MAX_LONG_EDGE ||
            thermalPreviewMaxArea != THERMAL_GUARD_PREVIEW_MAX_AREA
        thermalPreviewMaxLongEdge = THERMAL_GUARD_PREVIEW_MAX_LONG_EDGE
        thermalPreviewMaxArea = THERMAL_GUARD_PREVIEW_MAX_AREA
        if (changed && !recordingActive) {
            refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
        }
    }

    private fun applyThermalBitrateReduction(): Boolean {
        val width = recording.width.coerceAtLeast(1)
        val height = recording.height.coerceAtLeast(1)
        val floor = RecordingManager.minimumBitrateFloorFor(width, height)
        val current = recording.bitrate.coerceAtLeast(floor)
        val reduced = (current * THERMAL_BITRATE_REDUCTION_FACTOR).roundToInt().coerceAtLeast(floor)
        if (reduced >= current) return false
        if (!gpuRecording.updateActiveEncoderBitrate(reduced)) return false
        recording.bitrate = gpuRecording.activeOutputBitrateBitsPerSecond()
        thermalBitrateReductionApplied = true
        return true
    }

    private fun appendThermalCooldownEstimate(
        warningMessage: String,
        cooldownEstimateSec: Int?
    ): String {
        return ThermalCooldownEstimatePolicy.append(
            warningMessage = warningMessage,
            cooldownEstimateSec = cooldownEstimateSec
        )
    }

    // -------------------------------------------------------------------------
    // CLEANUP
    // -------------------------------------------------------------------------

    private fun handleAICameraFrameResult(result: AICameraFrameResult) {
        val mapped = AiFrameSensorMappingPolicy.map(result)
        _aiFaces.value = mapped.faces
        _aiDepthData.value = mapped.depthData
        _aiPrimaryFocus.value = mapped.primaryFocus
        _aiPrimarySubject.value = mapped.primarySubject
        _aiSubjectMask.value = mapped.subjectMask
        _aiSceneType.value = mapped.sceneType
        _aiHdrSuggested.value = mapped.hdrSuggested
        _aiLowLightSuggested.value = mapped.lowLightSuggested
        _aiEnhancementResult.value = mapped.enhancementResult
    }

    private fun enqueueAICameraFrameResult(result: AICameraFrameResult) {
        pendingAICameraFrameResult.set(result)
        scheduleAICameraFrameResultDrain()
    }

    private fun scheduleAICameraFrameResultDrain() {
        if (!aiFrameResultDrainScheduled.compareAndSet(false, true)) {
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    val latestResult = pendingAICameraFrameResult.getAndSet(null) ?: break
                    handleAICameraFrameResult(latestResult)
                }
            } finally {
                aiFrameResultDrainScheduled.set(false)
                if (pendingAICameraFrameResult.get() != null) {
                    scheduleAICameraFrameResultDrain()
                }
            }
        }
    }

    private fun installRawVideoCallbacks() {
        if (rawVideoCallbacksInstalled || !camera.supportsRaw) return
        savedCaptureResultCallback = camera.onCaptureResult
        camera.onRawReady = { reader -> rawVideoManager.onRawFrameAvailable(reader) }
        camera.onCaptureResult = { result ->
            savedCaptureResultCallback?.invoke(result)
            rawVideoManager.onCaptureResult(result)
        }
        // Wire RAW preview debayer pipeline: when not recording, feed RAW_SENSOR
        // frames into the Vulkan Cinema-Debayer for a professional preview path.
        enableIdleRawPreviewProcessor()
        // Record-time live monitor: while recording, the idle Image path above is starved (every RAW frame
        // goes to disk), so RawVideoManager instead hands us a COPY of each already-persisted frame's bytes.
        rawVideoManager.recordingPreviewBytesProcessor = { buffer, descriptor, rowStrideBytes, decimate ->
            val ok = vulkanRuntime.submitRawSensorBytes(buffer, rowStrideBytes, decimate, descriptor)
            if (ok) presentDebayerPreviewFrame()
        }
        // Arm the debayer-monitor present ONLY while in RAW (guaranteed on every RAW entry, including
        // cold-start restore). Disarmed in restoreRawVideoCallbacks so a stale debayer frame can never
        // leak its orientation into another mode's preview after leaving RAW.
        runCatching { previewGlCompositor.setDebayerPresentEnabled(true) }
        rawVideoCallbacksInstalled = true
    }

    private fun enableIdleRawPreviewProcessor() {
        rawVideoManager.rawPreviewProcessor = { image, descriptor ->
            val ok = vulkanRuntime.submitRawSensorImage(image, descriptor)
            // STAGE 2 present: on a successful debayer dispatch, push the tonemapped RGBA8 monitor to
            // the GL compositor so the visible preview is the genuine sensor debayer (not the ISP image).
            if (ok) presentDebayerPreviewFrame()
            ok
        }
    }

    private fun restoreRawVideoCallbacks() {
        if (!rawVideoCallbacksInstalled) return
        camera.onRawReady = null
        camera.onCaptureResult = savedCaptureResultCallback
        savedCaptureResultCallback = null
        rawVideoManager.rawPreviewProcessor = null
        rawVideoManager.recordingPreviewBytesProcessor = null
        // Disarm the debayer-monitor present on RAW exit (co-located with the callback lifecycle so it
        // fires on every RAW->other-mode switch, closing the cross-mode orientation leak).
        runCatching { previewGlCompositor.setDebayerPresentEnabled(false) }
        rawVideoCallbacksInstalled = false
    }

    /**
     * The RAW frame callbacks (onRawReady + the preview debayer processor) are needed whenever the app
     * is in VIDEO+RAW — that covers both live RAW preview (Stage 1 debayer feed) and RAW recording
     * (mode is RAW while recording). Keying on the mode alone means a record-stop that stays in RAW
     * preview KEEPS the callbacks (seamless record→preview-debayer handoff), while a PRO recording stop
     * (mode != RAW) correctly restores. Mode changes are blocked mid-record, so this never strips
     * callbacks out from under an active recording.
     */
    private fun rawVideoCallbacksNeeded(): Boolean =
        _uiMode.value == AppMode.VIDEO && _cinemaVideoMode.value == CinemaVideoMode.RAW

    private fun reconcileRawVideoCallbacks() {
        if (rawVideoCallbacksNeeded()) {
            installRawVideoCallbacks()
        } else {
            restoreRawVideoCallbacks()
            // Leaving RAW: stop presenting the debayer monitor so the compositor returns to the camera path.
            runCatching { previewGlCompositor.clearDebayerPresent() }
        }
    }

    private val rawDebayerPreviewPresenter =
        RawDebayerPreviewPresenter(vulkanRuntime, previewGlCompositor)

    /** Copy the latest tonemapped RGBA8 debayer monitor from the bridge and hand it to the GL compositor. */
    private fun presentDebayerPreviewFrame() = rawDebayerPreviewPresenter.present()

    override fun onCleared() {
        super.onCleared()
        // Release process-global flag callbacks + the thermal listener (each captures `this`) so this dead
        // ViewModel's object graph is freed at teardown instead of lingering until the next launch overwrites
        // the single onChanged slots.
        CameraViewModelGlobalCallbacks.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatusListener?.let { listener ->
                thermalPowerManager?.removeThermalStatusListener(listener)
            }
        }
        thermalStatusListener = null
        thermalPowerManager = null
        actionChannel.close()
        previewLutSyncJob?.cancel()
        aiWarmupJob?.cancel()
        recordingCapabilityWarmupJob?.cancel()
        startupCoordinator.cancelAll()
        startupDeferredRestoreJob?.cancel()
        refreshPreferencesJob?.cancel()
        stopNativePreviewWatchdog()
        stopNativePreviewRetry()
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        previewSurface = null
        manualControlDispatcher.shutdown()
        stopRecordingForegroundService()
        releaseCinemaAutoLock(resetCameraState = false, reason = "on_cleared")
        clearRecordingReadoutOverride()
        npv10Recording.release()
        gpuRecording.destroy()
        rawVideoManager.destroy()
        vulkanRuntime.setFrameLineageSubmissionListener(null)
        stillCapture.destroy()
        camera.destroy()
        pipeline.destroy()
        aiAfCoordinator.destroy()
        aiCameraPipeline.close()
        vulkanRuntime.close()
    }

    private fun buildVulkanPreviewTransform(geometry: FrameGeometry?): FloatArray {
        val previewView = previewViewRef
        val displayAspect = VulkanPreviewDisplayAspectPolicy.resolve(
            viewWidth = previewView?.width ?: 0,
            viewHeight = previewView?.height ?: 0,
            resolvedPreviewAspect = previewView?.getResolvedDisplayAspect()
        )
        return VulkanPreviewTransformEngine.buildTransformMatrix(
            geometry = geometry,
            displayAspect = displayAspect,
            fallbackContentAspect = currentPreviewStreamAspectRatio,
            resolvedDisplayContentAspect = previewView?.getResolvedDisplayAspect()
        )
    }

    private fun shouldUseNativeVulkanPreview(): Boolean =
        nativeVisiblePreviewActive

    private fun buildVulkanPreviewGrade(
        renderContract: VulkanPreviewRenderContract? = null
    ): FloatArray =
        activeVideoFormatDeliveryState().let { deliveryState ->
            val previewLookState = activeVideoPreviewLookState()
            val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
            buildVulkanGrade(
                neutralVideoPreview = videoSurface && deliveryState.neutralPreview,
                truthfulColorDelivery = videoSurface && deliveryState.truthfulColorDelivery,
                previewLookState = previewLookState,
                renderContract = renderContract
            )
        }

    private fun buildVulkanEncoderGrade(): FloatArray =
        activeVideoFormatDeliveryState().let { deliveryState ->
            val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
            buildVulkanGrade(
                neutralVideoPreview = false,
                deliveryParityBias = false,
                highLoadPreviewOverride = false,
                truthfulColorDelivery = videoSurface && deliveryState.truthfulColorDelivery
            )
        }

    private fun buildVulkanGrade(
        neutralVideoPreview: Boolean,
        deliveryParityBias: Boolean = false,
        highLoadPreviewOverride: Boolean? = null,
        truthfulColorDelivery: Boolean = false,
        previewLookState: VideoFormatPreviewLookState = VideoFormatPreviewLookState(),
        renderContract: VulkanPreviewRenderContract? = null
    ): FloatArray {
        val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
        val deliveryState = if (videoSurface) {
            activeVideoFormatDeliveryState()
        } else {
            VideoFormatDeliveryState()
        }
        val highLoad = highLoadPreviewOverride ?: isHighLoadPreviewMode()
        val videoHdrRenderActive = if (neutralVideoPreview) {
            false
        } else {
            videoSurface &&
                deliveryState.effectiveHdrMode != "OFF" &&
                isSafeVideoHdrPreviewActive()
        }
        val photoHdrPreviewActive = false
        val adjustments = PreviewRenderTuning.stabilizeForNeutralVideoPreview(
            adjustments = PreviewRenderTuning.buildGradeAdjustments(
            profile = currentSceneRenderProfile ?: SceneRenderProfile(
                temporalBlend = 0f,
                spatialDenoise = 0f,
                chromaDenoise = 0f,
                shadowBoost = 0f,
                sharpening = 0f,
                warmth = 0f,
                contrast = 1f,
                saturation = 1f,
                highlightCompression = 0f,
                logStrength = 0f,
                ditherStrength = 0f,
                videoHdrFusionStrength = 0f
            ),
            highLoadPreviewMode = highLoad,
            lutEnabled = _lutEnabled.value &&
                !(renderContract?.nativePreviewLutEnabled ?: nativePreviewLutEnabled) &&
                !neutralVideoPreview &&
                !truthfulColorDelivery,
            lutSummary = null
            ),
            neutralVideoPreview = neutralVideoPreview
        )
        val fallbackPreviewMonitoring = previewLookState.displayReferredMonitoring || (
            videoSurface &&
                deliveryState.isCinemaDeliveryMode &&
                !truthfulColorDelivery &&
                !neutralVideoPreview
        )
        val isPreviewMonitoring =
            renderContract?.previewMonitoringEnabled ?: fallbackPreviewMonitoring
        val logCaptureActive =
            renderContract?.logCaptureActive ?: (previewLookState.logEnabled || if (videoSurface) {
                deliveryState.logVideoActive
            } else {
                _logOn.value
            })
        val rawPreviewMonitoringActive = renderContract?.let {
            it.rawVideoActive && it.previewMonitoringEnabled
        } ?: (previewLookState.rawEnabled || (
            videoSurface &&
                deliveryState.rawVideoActive &&
                !truthfulColorDelivery &&
                !neutralVideoPreview
            ))
        val rawVideoDelivery =
            renderContract?.rawVideoDelivery ?: (
                truthfulColorDelivery &&
                    videoSurface &&
                    deliveryState.rawVideoActive
                )
        val normalizedHdrMode = renderContract?.normalizedHdrMode ?: when {
            previewLookState.hdrEnabled && previewLookState.hdrMode != "OFF" -> previewLookState.hdrMode
            videoSurface -> deliveryState.effectiveHdrMode
            else -> "OFF"
        }
        val neoHdrColorCritical =
            normalizedHdrMode.equals("NDR10+", ignoreCase = true) ||
                normalizedHdrMode.equals("NIVUKXVISION", ignoreCase = true)
        val measuredNdrNivukxCorrection =
            if (neutralVideoPreview) {
                NdrNivukxMeasuredCorrectionPolicy.resolve("OFF")
            } else {
                NdrNivukxMeasuredCorrectionPolicy.resolve(normalizedHdrMode)
            }
        val toneDeliveryMode = renderContract?.toneDeliveryMode ?: VulkanToneDeliveryMode.resolve(
            previewLutEnabled = nativePreviewLutEnabled && !truthfulColorDelivery,
            truthfulColorDelivery = truthfulColorDelivery,
            rawVideoDelivery = rawVideoDelivery,
            rawVideoActive = previewLookState.rawEnabled || deliveryState.rawVideoActive,
            logCaptureActive = logCaptureActive,
            normalizedHdrMode = normalizedHdrMode,
            logCurve = if (logCaptureActive) {
                NeoLogProfilePolicy.resolve(_colorProfile.value, logEnabled = true).curve
            } else {
                NeoLogCurve.NONE
            },
            previewMonitoringEnabled = isPreviewMonitoring
        )
        val deliveryVideoToneBias =
            deliveryParityBias &&
                videoSurface &&
                (logCaptureActive || deliveryState.effectiveHdrMode != "OFF")
        val uniforms = VulkanGradeUniformPolicy.resolve(
            VulkanGradeUniformInput(
                neutralVideoPreview = neutralVideoPreview,
                deliveryVideoToneBias = deliveryVideoToneBias,
                videoSurface = videoSurface,
                deliveryHdrMode = deliveryState.effectiveHdrMode,
                logCaptureActive = logCaptureActive,
                sceneRenderProfile = currentSceneRenderProfile,
                hdrRenderActive = videoHdrRenderActive || photoHdrPreviewActive,
                rawPreviewMonitoringActive = rawPreviewMonitoringActive,
                neoHdrColorCritical = neoHdrColorCritical,
                adjustments = adjustments,
                previewMonitoringEnabled = isPreviewMonitoring,
                rawVideoDelivery = rawVideoDelivery,
                normalizedHdrMode = normalizedHdrMode,
                truthfulColorDelivery = truthfulColorDelivery,
                measuredNdrNivukxCorrection = measuredNdrNivukxCorrection,
                sdrQualityIntent = _sdrQualityIntent.value,
                toneDeliveryMode = toneDeliveryMode,
                nativePreviewLutSize = nativePreviewLutSize
            )
        )
        val isApexSharp = toneDeliveryMode.shaderModeFlag in 49.5f..50.5f
        val isApexZero = toneDeliveryMode.shaderModeFlag in 50.5f..51.5f
        val isApexCinema = toneDeliveryMode.shaderModeFlag in 51.5f..52.5f

        if (isApexSharp || isApexZero || isApexCinema) {
            val apexMode = when {
                isApexSharp -> ApexMode.SHARP
                isApexZero -> ApexMode.ZERO
                else -> ApexMode.CINEMA
            }
            apexPipelineState.isRecording =
                gpuRecordState.value is GpuRecordingState.Recording ||
                    gpuRecordState.value is GpuRecordingState.Paused ||
                    cameraState.value == CameraState.Recording
            apexPipelineState.setMode(apexMode)
            ApexPipelineOrchestrator.injectApexUniforms(
                uniforms,
                apexPipelineState.currentParams,
                apexPipelineState.currentMetrics
            )
        }

        // SNCE v2.0 HVC: bounded, fail-safe perceptual nudge (residual WB + Hunt saturation).
        // Applied last so it rides on top of the resolved grade (and Apex when active); the
        // Vulkan bridge re-clamps the result, so it can neither crash nor blow out colour.
        snceColorEngine.injectIntoPreviewGrade(uniforms)

        return uniforms
    }

    private fun shouldSkipSessionRefreshForModeSwitch(): Boolean =
        gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping ||
            cameraState.value == CameraState.Recording

    private fun refreshSessionForModeSwitchIfIdle(
        previousMode: CinemaVideoMode,
        currentMode: CinemaVideoMode,
        previousPipelineSignature: String? = null,
        currentPipelineSignature: String? = null,
        hdrToHdrSameTransport: Boolean = false
    ) {
        val decision = CinemaModeSessionRefreshPolicy.resolve(
            previousMode = previousMode,
            currentMode = currentMode,
            videoModeActive = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value),
            hdrMode = _videoHdrMode.value,
            captureActive = shouldSkipSessionRefreshForModeSwitch(),
            previousPipelineSignature = previousPipelineSignature,
            currentPipelineSignature = currentPipelineSignature,
            hdrToHdrSameTransport = hdrToHdrSameTransport
        )
        if (!decision.shouldRefresh) return
        camera.refreshOutputsAndSession(
            rebuildReaders = decision.rebuildReaders,
            forceRebuild = decision.forceRebuild
        )
    }

    private fun isSafeVideoHdrPreviewActive(): Boolean {
        val parityPlan = activeRecordingPipelineParityPlan()
        return VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value) &&
            !isVideoFormatNeutralPreviewEnabled() &&
            RecordingHdrVideoLookPolicy.resolveLookProfile(
                mime = RecordingCodecCatalog.mime(
                    RecordingCapabilityPolicy.resolveRecordingCodec(
                        requestedCodec = recording.codec,
                        av1Supported = supportsAv1Fast(),
                        hdrRequested = parityPlan.hdrRequestedForSessionPlan,
                        rawVideoRequested = parityPlan.rawRequestedForSessionPlan,
                        tenBitRequested = parityPlan.tenBitRequestedForSessionPlan
                    )
                ),
                videoHdrMode = activeVideoFormatDeliveryState().effectiveHdrMode,
                videoColorSpace = parityPlan.encoderColorSpace
            ).colorEncoding.colorTransfer != android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO &&
            !gpuRecording.simulatedTenBitFallbackActive
    }

    private fun startRecordingForegroundService() {
        val app = getApplication<Application>()
        try {
            ContextCompat.startForegroundService(
                app,
                RecordingForegroundService.buildIntent(app)
            )
        } catch (e: SecurityException) {
            Log.w("CameraViewModel", "Failed to start recording foreground service: permission denied", e)
        } catch (e: IllegalStateException) {
            Log.w("CameraViewModel", "Failed to start recording foreground service: illegal state", e)
        }
    }

    private fun stopRecordingForegroundService() {
        val app = getApplication<Application>()
        try {
            RecordingForegroundService.stop(app)
        } catch (e: SecurityException) {
            Log.w("CameraViewModel", "Failed to stop recording foreground service: permission denied", e)
        } catch (e: IllegalStateException) {
            Log.w("CameraViewModel", "Failed to stop recording foreground service: illegal state", e)
        }
    }

    private fun restoreAIAutoFocusFromPrefs() {
        aiFocusResumeJob?.cancel()
        val remainingHoldMs = remainingTapToFocusHoldMs()
        if (focusExposureLockState == null && remainingHoldMs > 0L) {
            aiFocusResumeJob = viewModelScope.launch {
                delay(remainingHoldMs)
                restoreAIAutoFocusFromPrefs()
            }
            return
        }
        if (focusExposureLockState == null && cameraParams.value.focusDist < 0f && !camera.isAeAwbLocked()) {
            manualFocusOverride.value = null
            activePeakingRegion.value = null
            tapToFocusHoldUntilElapsedMs = 0L
        }
        // A direct aiAfCoordinator.setEnabled(false) (setFocusDist / lockFocusAndExposure /
        // suspendAIAutoFocusTemporarily) desyncs the memoized AI-tracking cache from the
        // coordinator; invalidate it here so applyAiTrackingPreferences() cannot short-circuit
        // and leave AI-AF stuck OFF after a tap / manual-focus / lock restore cycle.
        lastAppliedAiTrackingState = null
        applyAiTrackingPreferences()
    }

    private fun scheduleCenterAutoPrime(delayMs: Long = StartupSmoothnessPolicy.autoPrimeDelayMs()) {
        autoFocusPrimeJob?.cancel()
        autoFocusPrimeJob = viewModelScope.launch {
            delay(maxOf(delayMs, remainingTapToFocusHoldMs()))
            if (manualFocusOverride.value != null || focusExposureLockState != null) return@launch
            if (cameraParams.value.focusDist >= 0f || camera.isAeAwbLocked()) return@launch
            if (cameraState.value == CameraState.Streaming || cameraState.value == CameraState.Open) {
                camera.triggerTapAF(0.5f, 0.5f)
            }
        }
    }

    private fun suspendAIAutoFocusTemporarily(resumeAfterMs: Long = TAP_TO_FOCUS_HOLD_MS) {
        tapToFocusHoldUntilElapsedMs =
            maxOf(tapToFocusHoldUntilElapsedMs, SystemClock.elapsedRealtime() + resumeAfterMs)
        aiFocusResumeJob?.cancel()
        aiAfCoordinator.setEnabled(false, clearMeteringRegionWhenDisabling = false)
        aiFocusResumeJob = viewModelScope.launch {
            delay(resumeAfterMs)
            restoreAIAutoFocusFromPrefs()
        }
    }

    private fun remainingTapToFocusHoldMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long =
        (tapToFocusHoldUntilElapsedMs - nowElapsedMs).coerceAtLeast(0L)
}
