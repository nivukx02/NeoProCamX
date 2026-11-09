package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.BuildConfig

import android.Manifest
import android.app.Application
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
import android.util.Log
import android.util.Size
import android.view.Surface
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
import com.neoproc.neoproccamx.apex.ApexProfileParams
import com.neoproc.neoproccamx.apex.ApexPipelineState
import com.neoproc.neoproccamx.apex.ApexRuntimeSceneMapper
import com.neoproc.neoproccamx.camera.CameraBackgroundClosePolicy
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.camera.CameraRepeatingReadinessSnapshot
import com.neoproc.neoproccamx.camera.CameraRecordingCadenceAdmissionGate
import com.neoproc.neoproccamx.camera.CameraRecordingCadenceAdmissionSnapshot
import com.neoproc.neoproccamx.camera.CameraRect
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import com.neoproc.neoproccamx.camera.Npv10ProducerStartupLeaseHandle
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.camera.FlashMode
import com.neoproc.neoproccamx.camera.LiveResult
import com.neoproc.neoproccamx.camera.RecordingStopPreviewPolicy
import com.neoproc.neoproccamx.camera.P010StopBoundaryCleanupResult
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
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.color.VideoProfileIntentPolicy
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.StartupSmoothnessPolicy
import com.neoproc.neoproccamx.pipeline.AICameraPipeline
import com.neoproc.neoproccamx.pipeline.FramePipelineManager
import com.neoproc.neoproccamx.pipeline.HardwareImportSourceIngressTelemetry
import com.neoproc.neoproccamx.pipeline.PipelineThermalState
import com.neoproc.neoproccamx.pipeline.RawVideoManager
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingFailure
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingIngressPolicy
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrSessionGenerationPolicy
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.pipeline.SceneRenderProfileEngine
import com.neoproc.neoproccamx.pipeline.VideoDetailSharpeningPolicy
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.SubjectDetailEnhancementPolicy
import com.neoproc.neoproccamx.pipeline.SubjectDetailEnhancementRequest
import com.neoproc.neoproccamx.pipeline.TimedThermalSample
import com.neoproc.neoproccamx.recording.*
import com.neoproc.neoproccamx.camera.CameraStreamSize
import com.neoproc.neoproccamx.camera.RawCaptureResolutionMode
import com.neoproc.neoproccamx.camera.RawCaptureResolutionModePolicy
import com.neoproc.neoproccamx.camera.RawCaptureTier
import com.neoproc.neoproccamx.camera.RawMaxResViabilityPolicy
import com.neoproc.neoproccamx.camera.RawSensorMaxResCapabilityPolicy
import com.neoproc.neoproccamx.camera.ResolvedRawCaptureTier
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.monitor.NPV10_NATIVE_P010_FEED_PATH
import com.neoproc.neoproccamx.niv99.Niv99ExecutionBoundaryPolicy
import com.neoproc.neoproccamx.ui.RecordingSourceResolutionPlan
import com.neoproc.neoproccamx.ui.RecordingSourceResolutionPolicy
import com.neoproc.neoproccamx.rendering.*
import com.neoproc.neoproccamx.rendering.RecordDevelopLivePipelinePolicy
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.ui.controls.CinemaManualControlPolicy
import com.neoproc.neoproccamx.ui.controls.CinemaManualMeteringMode
import com.neoproc.neoproccamx.ui.controls.SettingsCommit
import com.neoproc.neoproccamx.ui.controls.SettingsInvariantResolver
import com.neoproc.neoproccamx.ui.controls.SettingsInvariantResult
import com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalog
import com.neoproc.neoproccamx.ui.controls.SettingsRejectionNotice
import com.neoproc.neoproccamx.ui.overlays.GridMode
import com.neoproc.neoproccamx.utils.PreferencesManager
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import com.neoproc.neoproccamx.pipeline.MemoryProfiler
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
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
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.NPV10_MAX_STARTUP_CADENCE_PASSES
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.NPV10_SESSION_READY_TIMEOUT_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.NPV10_P010_STOP_BOUNDARY_TIMEOUT_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.TAP_TO_FOCUS_HOLD_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.THERMAL_BITRATE_REDUCTION_FACTOR
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Recording startup is owned by backend state, not by the asynchronously projected UI state.
 * Keeping this decision pure makes a stop-to-restart handoff immune to a delayed `Stopping`
 * projection while still rejecting a genuinely active RAW, NPV10, or MediaCodec backend.
 */
internal object RecordingStartBackendAdmissionPolicy {
    fun allowsStart(authoritativeBackendActive: Boolean): Boolean =
        !authoritativeBackendActive
}

private const val RECORDING_STOP_ORIGIN_TAG = "RecordingStopOrigin"

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
        videoDetailProfileProvider = { prefs.videoDetailProfile },
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
    private val nativeResourceRetirement = NativeResourceRetirementCoordinator()
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
    private val npv10AudioRecording = Npv10AudioSidecarRecorder()
    private val recordingStopOriginTracker = RecordingStopOriginTracker(
        elapsedRealtimeMs = SystemClock::elapsedRealtime
    )
    @Volatile private var npv10AudioRequiredForActiveSession = false
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
    private val recordingSurfacePrearmAfterModePresentation = AtomicBoolean(false)
    private var recordingSurfacePrearmRetryJob: Job? = null
    private val recordingSurfacePreparationCoordinator =
        RecordingSurfacePreparationCoordinator()
    private val viewModelTeardownStarted = AtomicBoolean(false)
    private val recordingStopRestartLifecycle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RecordingStopRestartLifecycle(
            scope = viewModelScope,
            claimStartAdmission = ::claimRecordingStartAdmission,
            beginClaimedStart = ::beginClaimedRecordingStart,
            hasActiveBackend = ::hasActiveRecordingBackend,
            requestBackendStopAdmission = { recordingController.stopRecording() },
            beginBackendStop = ::stopRecordingInternal,
            setTransitionState = { recordingTransitionState.value = it },
            replayDeferredLifecycleRefreshIfSafe = ::replayDeferredLifecyclePreferenceRefreshIfSafe,
            publishWarning = ::setHardwareWarning,
            teardownStarted = viewModelTeardownStarted::get
        )
    }
    private val sensorLinearNdrRecordingFailure =
        AtomicReference<SensorLinearNdrRecordingFailure?>(null)
    private val sensorLinearNdrRecordingAbortScheduled = AtomicBoolean(false)
    private val sensorLinearNdrRecordingLifecycle = SensorLinearNdrRecordingLifecycleCoordinator(
        camera = camera,
        rawVideoManager = rawVideoManager,
        vulkanRuntime = vulkanRuntime,
        targetFps = { recording.fps },
        failure = sensorLinearNdrRecordingFailure,
        abortScheduled = sensorLinearNdrRecordingAbortScheduled,
        publishWarning = { message -> runOnMainIfNeeded { setHardwareWarning(message) } },
        requestRecordingStop = {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                requestRecordingStop(
                    origin = RecordingStopOrigin.SENSOR_LINEAR_PIPELINE_FAILURE,
                    detail = "sensor-linear NDR lifecycle requested stop"
                )
            }
        }
    )
    @Volatile private var npv10CapturePrearmedSignature: String? = null
    @Volatile private var npv10CaptureRefreshSignature: String? = null
    val thermalPolicyResolver = ThermalRuntimePolicyResolver()
    private val memoryProfiler = MemoryProfiler(app)
    @Volatile private var latestMemorySnapshot: MemoryProfiler.MemoryStats? = null
    @Volatile private var latestRecordingStorageBytes = 0L
    @Volatile private var latestRawThermalLabel = "Unknown"
    @Volatile private var recordingThermalIsolationActive = false
    private val runtimeResourceTelemetry = SensorPipelineRuntimeResourceCoordinator(
        scope = viewModelScope,
        memoryProfiler = memoryProfiler,
        pipeline = pipeline,
        recording = gpuRecording,
        rawVideo = rawVideoManager,
        recordingActive = {
            gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused
        },
        suppressNonessentialTelemetry = { recordingThermalIsolationActive },
        availableStorageBytes = ::availableRecordingStorageBytes,
        thermalLabel = ::currentThermalLabel,
        onRefresh = { refresh ->
            refresh.memory?.let { snapshot ->
                latestMemorySnapshot = snapshot
                healthMemUsageMb = snapshot.totalUsageMb
                healthMemUtilPct = snapshot.utilizationPercent
            }
            val previewAdaptationChanged =
                sensorPipelineSimplifyPreviewEffects != refresh.simplifyPreviewEffects
            sensorPipelineSimplifyPreviewEffects = refresh.simplifyPreviewEffects
            if (refresh.availableStorageBytes > 0L) {
                latestRecordingStorageBytes = refresh.availableStorageBytes
            }
            latestRawThermalLabel = refresh.thermalLabel
            if (refresh.memory != null || previewAdaptationChanged) {
                withContext(Dispatchers.Main.immediate) {
                    refresh.memory?.let(::applyMemoryWarningSnapshot)
                    if (previewAdaptationChanged) syncPreviewPerformanceMode()
                }
            }
        }
    )
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
            recordingStopRestartLifecycle.isStarting || recordingStopRestartLifecycle.isStopInProgress ||
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
    private val neoFusionConversionEngine by lazy {
        com.neoproc.neoproccamx.tessera.NeoFusionConversionEngine(
            com.neoproc.neoproccamx.tessera.TesseraRuntimeProvider.runtime()
        )
    }
    private val mediaConversionCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CameraMediaConversionCoordinator(
            application = getApplication(),
            conversionIsolation = conversionIsolation,
            conversionEngine = neoFusionConversionEngine,
            npv10Recording = npv10Recording,
            recordingManager = gpuRecording,
            rawVideoManager = rawVideoManager,
            colorScience = colorScience,
            onRecordingSaved = ::notifyRecordingSaved
        )
    }

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
        prefs = prefs
    )
    private val recordingController = CameraRecordingController(
        recordingManager = gpuRecording
    )
    private val previewController = CameraPreviewController(
        cameraController = camera,
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
    private val previewSurfaceRetirement = PreviewSurfaceRetirementCoordinator<Surface>()
    private val previewDisplayPresentation = PreviewDisplayPresentationCoordinator(
        capacity = CameraViewModelConstants.PREVIEW_DISPLAY_PRESENT_LINEAGE_CAPACITY,
        currentSessionGeneration = { camera.sessionGeneration.get() },
        currentPreviewSurface = { previewSurface },
        isOutputSurfaceValid = { surface ->
            runCatching { surface.isValid }.getOrDefault(false)
        }
    )
    private val previewDisplayPublishGate = PreviewPresentationFpsEpochGate()
    private val previewPixelRouteTelemetry = PreviewPixelRouteTelemetry()
    private val _previewDisplayPresentEvents = MutableSharedFlow<PreviewDisplayPresentEvent>(
        replay = 1,
        extraBufferCapacity = CameraViewModelConstants.PREVIEW_DISPLAY_PRESENT_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    @Volatile private var previewViewHolder: WeakReference<CameraVulkanPreviewView>? = null
    private var previewViewRef: CameraVulkanPreviewView?
        get() = previewViewHolder?.get()
        set(value) {
            previewViewHolder = value?.let { WeakReference(it) }
        }
    private val thermalStatusCoordinator: CameraThermalStatusCoordinator
    @Volatile private var currentSceneRenderProfile: SceneRenderProfile? = null
    @Volatile private var selectedVideoSize: Size? = null
    private val modeSwitchStandbyTarget = ModeSwitchStandbyTargetState(
        prefs.videoSize.normalizeForOrientation().let { CameraStreamSize(it.width, it.height) }
    )
    @Volatile private var lastRecordingSelection: ResolvedRecordingSelection? = null
    @Volatile private var boundPreviewSize: Size? = null
    @Volatile private var currentRotationDegrees: Int = 0
    // The replaying geometry flow can emit from the earlier constructor init block before the
    // persistent HDR compositor declared later in this class has been constructed.
    @Volatile private var projectOwnedNdrOrientationTargetReady = false
    @Volatile private var currentPreviewStreamAspectRatio: Float? = null
    @Volatile private var closeCameraAfterRecordingStop = false
    @Volatile private var cameraCloseJob: Job? = null
    private val settingsCameraLifecycle = CameraSettingsSuspensionState()
    @Volatile private var settingsPreviewPresentationRetained = false
    @Volatile private var recordingPreviewDetachInFlight = false
    @Volatile private var recordingPreviewDetachGeneration = 0L
    @Volatile private var pendingRecordingPreviewSurface: Surface? = null
    @Volatile private var recordingPreviewSurfaceSafelyDetached = false
    private val recordingPreviewWindow by lazy {
        RecordingPreviewWindowCoordinator<Surface>(
            runOnMain = ::runOnMainIfNeeded,
            canRetain = { persistentPreviewPipelineActive && persistentPreviewOutputRouter.isRunning() &&
                !nativeVisiblePreviewActive && strictPreviewRuntimeFailureCoordinator.isPersistentRouteHealthy() },
            recordingActive = ::isRecordingOrStopping,
            detachWindow = { done ->
                persistentPreviewOutputRouter.detachVisibleOutputForRecording { done(it.started, it.detail) }
            },
            attachWindow = { surface, done ->
                val size = boundPreviewSize ?: preferredPreviewSize()
                previewViewRef?.setStreamGeometry(size.width, size.height, camera.sensorOrientation())
                persistentPreviewOutputRouter.attachVisibleOutputForRecording(surface, size.width, size.height) {
                    done(it.started, it.detail)
                }
            },
            onRetained = {
                stopNativePreviewWatchdog()
                stopNativePreviewRetry()
                glPreviewBindingResyncJob?.cancel()
                glPreviewBindingResyncJob = null
                previewSurface = null
                updatePreviewDisplayPresentSurface(null)
            },
            onRestored = { surface ->
                previewSurface = surface
                persistentPreviewVisibleSurface = surface
                persistentPreviewInputSize = boundPreviewSize ?: preferredPreviewSize()
                updatePreviewDisplayPresentSurface(surface)
                closeCameraAfterRecordingStop = false
            },
            onFailure = { detail ->
                setHardwareWarning("Recording preview could not be restored: $detail")
                requestRecordingStop(RecordingStopOrigin.PIPELINE_FAILURE, "recording-preview-window-handoff: $detail")
            },
            onEvidence = { marker -> NeoLogger.i("CameraViewModel", marker) }
        )
    }
    @Volatile private var recordingPreviewRebindInProgress = false
    @Volatile private var recordingOutputAspectRatioOverride: Float? = null
    @Volatile private var activeRecordingFeedPath: RecordingFeedPath? = null
    @Volatile private var lastValidatedGpuRecordingEvidence = ValidatedGpuRecordingEvidence.EMPTY
    @Volatile private var vulkanRecordingThroughputBlockedUntilElapsedMs = 0L
    @Volatile private var vulkanRecordingThroughputBlockedSignature = ""
    @Volatile private var vulkanRecordingDeviceBlockLogged = false
    @Volatile private var rejectedVideoModeAdmission: VideoModePipelineContract? = null
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
    @Volatile private var sensorPipelineSimplifyPreviewEffects = false
    @Volatile private var pendingZoomRatio: Float? = null
    @Volatile private var debugValidationLabel: String? = null
    @Volatile private var previewColorGainState = floatArrayOf(1f, 1f, 1f, 0.12f)
    private val nativePreviewLutState = AtomicReference(NativePreviewLutSnapshot())
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
    @Volatile private var lastNiv99AnalysisRequestSignature: String? = null
    private val deliveryContractCache = AtomicSnapshotCache<ActiveDeliveryContractCacheKey, VideoFormatDeliveryState>()
    private val videoModeContractCache = AtomicSnapshotCache<ActiveDeliveryContractCacheKey, VideoModePipelineContract>()
    private val videoPipelineContractCache = AtomicSnapshotCache<VideoPipelineGraphRequest, VideoPipelineContract>()
    private val physicalCameraIdentityCache = PhysicalCameraGeometryIdentityCache()
    private val vulkanContractCache = AtomicSnapshotCache<ActiveVulkanContractCacheKey, VulkanPreviewRenderContract>()
    @Volatile private var cachedPreviewLut: Pair<FloatArray, Int>? = null
    @Volatile private var cachedPreviewLutRequest: PreviewLutRequest? = null
    private val pendingPreviewLutRequest = AtomicReference<PreviewLutRequest?>(null)
    private val previewLutPublicationSequence = AtomicLong(0L)
    private val recordingSafePreviewLutPublicationGate =
        RecordingSafePreviewLutPublicationGate()
    // WYSIWYG parity: the EXACT creative cube the offscreen-EGL recorder bakes into the SDR/PRO file
    // (film-print look, default-ON). Captured in syncGpuLutState; used as the preview compositor's
    // fallback cube when the neutral SDR/PRO preview would otherwise forward null (the "gray" gap).
    @Volatile private var activeSdrRecordCreativeLut: Pair<FloatArray, Int>? = null
    @Volatile private var activeSdrVideoColorParityEligible = false
    @Volatile private var softwarePreviewLutOwnerRequired = false
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
    @Volatile private var nativePreviewPersistentRetirementPending = false
    @Volatile private var directPreviewSurfaceActive = false
    @Volatile private var cameraOpenRequestInFlight = false
    @Volatile private var pendingGeometrySyncAfterLayout = false
    @Volatile private var lastAppliedFrameGeometry: FrameGeometry? = null
    @Volatile private var lastAppliedNativeCompositorActive: Boolean? = null
    private var nativePreviewWatchdogJob: Job? = null
    private var nativePreviewRetryJob: Job? = null
    private var nativePreviewHandoffBindJob: Job? = null
    private var nativePreviewHandoffSurface: Surface? = null
    private var aiWarmupJob: Job? = null
    private var recordingCapabilityWarmupJob: Job? = null
    @Volatile private var cachedAv1Supported = false
    @Volatile private var av1SupportResolved = false
    private val recordingCodecCapabilityRefresh = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private var videoFeatureState = VideoFeatureState()
    private var aiFocusResumeJob: Job? = null
    private var autoFocusPrimeJob: Job? = null
    private var focusExposureLockState: FocusExposureLockState? = null
    @Volatile private var cinemaAutoLockCandidateStartElapsedMs: Long = 0L
    @Volatile private var cinemaAutoLockActive = false
    @Volatile private var cinemaAutoLockPreviousAeAwbLock = false
    @Volatile private var cinemaAutoLockPreviousFocusDistance = -1f
    @Volatile private var tapToFocusHoldUntilElapsedMs: Long = 0L
    private val rawPreviewPresentationCoordinator: RawPreviewPresentationCoordinator by lazy {
        RawPreviewPresentationCoordinator(
            viewModelScope, camera, rawVideoManager, vulkanRuntime, previewGlCompositor,
            hdr10PqPreviewCompositor,
            stillCapture::expectsRawImage, stillCapture::onRawImageAvailable,
            { resolveRawPreviewPresentationPath() == RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER },
            { isSensorLinearNdrMode() },
            { activeNdrPeakPlan(appliedProfile = null) },
            { reason -> "reason=$reason;ui=${_uiMode.value.name};cinema=${_cinemaVideoMode.value.name};" +
                "ndr=${_videoHdrMode.value};camera=${camera.currentCameraId}" },
            { awaitVulkanPreviewRenderConfig(force = true) },
            onEpochBegan = { epoch, reason ->
                persistentPreviewLifecycle.onPresentationEpochBegan(epoch, reason)
            }
        )
    }
    private val camera2FrameLineage = Camera2FrameLineageCoordinator(
        onCaptureDequeued = ::recordPreviewDisplayCaptureLineage
    )
    private var startupDeferredRestoreJob: Job? = null
    private var refreshPreferencesJob: Job? = null
    private val preferencesRefreshOwnership = LatestRestoreOwnership()
    private val preferencesRefreshDeferredByRecording = AtomicBoolean(false)
    private val recordingSelectionMutationLock = Any()
    private val recordingResolutionSelectionAuthority = RecordingResolutionSelectionAuthority()
    private val processedRecordingSelectionCache = ProcessedRecordingSelectionCache()
    @Volatile private var lastAppliedAiTrackingState: AppliedAiTrackingState? = null
    @Volatile private var lastAppliedHighLoadPreviewMode: Boolean? = null
    @Volatile private var lastAppliedPreferencesSignature: PreferencesRefreshSignature? = null
    private val startupPreferencesReady = CompletableDeferred<Unit>()
    @Volatile private var startupFeatureGateActive = true
    @Volatile private var startupAiRampActive = true
    @Volatile private var startupDirectPreviewActive = true
    @Volatile private var startupPreferencesApplied = false
    @Volatile private var openCameraAfterStartupPreferences = false
    @Volatile private var lastCinemaRuntimeSystemRefreshElapsedMs = 0L
    @Volatile private var cachedCinemaRuntimeFreeBytes = 0L
    @Volatile private var cachedCinemaRuntimeThermalLabel = "Unknown"
    @Volatile private var cinemaRuntimeSystemStatsFetchInFlight = false
    @Volatile private var thermalBitrateReductionApplied = false
    @Volatile private var cachedResolutionStatusLabelKey: ResolutionStatusCacheKey? = null
    @Volatile private var cachedResolutionStatusLabelValue = ""
    private val manualControlDispatcher = CameraManualControlDispatcher()
    private val modeSwitchPreviewWorkCoalescer = ModeSwitchPreviewWorkCoalescer()
    private val modeSwitchSessionTransaction = ModeSwitchSessionTransaction()
    private val modeSwitchCriticalDepth = AtomicInteger(0)
    private val modeSwitchFailureRecoveryScheduled = AtomicBoolean(false)
    private var modeSwitchFlushInProgress = false
    private val modeSwitchPreviewSizingCache = ModeSwitchPreviewSizingCache()
    private var modeSwitchEffectiveColorSyncPending = false
    private var modeSwitchCameraLookSyncPending = false
    private var modeSwitchVulkanConfigPending = false
    private var modeSwitchVulkanConfigForcePending = false
    private val vulkanRenderProfileAuthority =
        VulkanRenderProfileAuthorityCoordinator(initialGeneration = 1L, initialHdrMode = "OFF")
    private val vulkanRenderRequestSequence = AtomicLong(0L)
    private val ndrRenderProfileRevisionSequencer = NdrRenderProfileRevisionSequencer()
    private val vulkanPreviewConfigCoordinator by lazy {
        LatestWinsSerialWorkCoordinator(
            merge = { pending: DeferredVulkanPreviewConfigRequest, incoming ->
                val newest = when {
                    incoming.profileGeneration > pending.profileGeneration -> incoming
                    incoming.profileGeneration < pending.profileGeneration -> pending
                    incoming.requestSequence >= pending.requestSequence -> incoming
                    else -> pending
                }
                newest.copy(force = pending.force || incoming.force)
            },
            schedule = { drain ->
                viewModelScope.launch(Dispatchers.Default) {
                    // Render packets are not allowed to observe constructor defaults. The complete
                    // persisted Video/Pro/RAW + HDR state is committed before this gate opens.
                    startupPreferencesReady.await()
                    drain()
                }
            },
            execute = { request ->
                pushVulkanPreviewRenderConfig(
                    geometry = request.geometry,
                    force = request.force,
                    executeOnWorker = true,
                    expectedRenderState = request.renderState
                )
            },
            onFailure = { failure ->
                Log.e("CameraViewModel", "Serialized Vulkan preview config push failed", failure)
            }
        )
    }

    private fun currentVulkanRenderProfileGeneration(): Long =
        vulkanRenderProfileAuthority.snapshot().generation

    private fun nextVulkanRenderRequestSequence(): Long =
        vulkanRenderRequestSequence.updateAndGet { current ->
            check(current < Long.MAX_VALUE) { "Vulkan render request sequence exhausted" }
            current + 1L
        }

    private fun nextPreviewLutPublicationSequence(): Long =
        previewLutPublicationSequence.updateAndGet { current ->
            check(current < Long.MAX_VALUE) { "Preview LUT publication sequence exhausted" }
            current + 1L
        }

    private fun currentVulkanRenderProfileAuthority():
        VulkanRenderProfileAuthorityCoordinator.Snapshot =
        vulkanRenderProfileAuthority.snapshot()

    /**
     * Invalidates every queued/in-flight render packet before a mode/profile authority mutation.
     * VulkanRuntimeManager mirrors the token and rejects stale native writes, so an older PRO/NDR
     * request can never overwrite a newer RAW/LOG/OFF selection.
     */
    private fun advanceVulkanRenderProfileGeneration(
        reason: String,
        committedHdrMode: String? = null,
        authorityMutation: (Long) -> Unit = {},
        nativeGenerationMutation: (Long) -> Unit = {}
    ): Long {
        var advancingGeneration = currentVulkanRenderProfileGeneration()
        return vulkanRenderProfileAuthority.advance(
            hdrMode = committedHdrMode,
            retireNativeGeneration = { generation ->
                advancingGeneration = generation
                vulkanRuntime.advanceRenderProfileGeneration(generation)
                nativeGenerationMutation(generation)
            },
            commitAuthorityState = {
                authorityMutation(advancingGeneration)
            },
            commitHdrMode = { hdrMode ->
                _videoHdrMode.value = hdrMode
            }
        ).also { authority ->
            Log.i(
                "CameraViewModel",
                "NDR_RENDER_GENERATION_ADVANCED generation=${authority.generation} " +
                    "hdr=${authority.hdrMode} reason=$reason"
            )
        }.generation
    }

    private fun isVulkanRenderProfileGenerationCurrent(
        generation: Long,
        hdrModeAuthority: String? = null
    ): Boolean =
        vulkanRenderProfileAuthority.isCurrent(generation, hdrModeAuthority) &&
            vulkanRuntime.isRenderProfileGenerationCurrent(generation)

    private fun commitIfCurrentVulkanRenderProfileGeneration(
        generation: Long,
        hdrModeAuthority: String? = null,
        block: () -> Unit
    ): Boolean = vulkanRenderProfileAuthority.commitIfCurrent(
        generation = generation,
        hdrMode = hdrModeAuthority,
        externalAuthorityCurrent = {
            vulkanRuntime.isRenderProfileGenerationCurrent(generation)
        },
        block = block
    )
    private val previewLutCoordinator by lazy {
        LatestWinsSerialResultCoordinator<PreviewLutResolutionWork, ResolvedPreviewAndRecordLuts>(
            scheduleResolve = { drain -> viewModelScope.launch(Dispatchers.Default) { drain() } },
            scheduleApply = ::runOnMainIfNeeded,
            resolve = { work ->
                val recordCandidate = SdrCreativeLutSelectionPolicy.resolveRecordCandidateLazy(
                    isSdrRecordDelivery = work.recordRequest.isSdrRecordDelivery,
                    recordDevelopLightMaster = work.recordRequest.recordDevelopLightMaster,
                    fullColorEnabled = work.recordRequest.fullColorRecord,
                    userCreativeLutEnabled = work.recordRequest.userCreativeLutEnabled,
                    calibratedFallbackEnabled = work.recordRequest.calibratedFallbackEnabled,
                    fullColorCandidate = { colorScience.getPreviewLutFloatArray(recording = true) },
                    userCreativeCandidate = { colorScience.getLutFloatArray() },
                    calibratedFallbackCandidate = { FilmPrintLookRecordFlag.recordLut }
                )
                val recordLut = RecordDevelopLivePipelinePolicy.recordCreativeLut(
                    work.recordRequest.videoSurface,
                    recordCandidate
                )
                val previewLut = if (work.reuseCachedResult) work.cachedResult
                else if (work.request.neutralVideoPreview) null
                else colorScience.getPreviewLutFloatArray(
                    previewFps = work.previewFpsHint,
                    recording = work.recordingActive
                )
                ResolvedPreviewAndRecordLuts(previewLut = previewLut, recordLut = recordLut)
            },
            apply = { work, result ->
                applyResolvedSdrRecordLut(work.recordRequest, result.recordLut)
                cachedPreviewLut = result.previewLut
                cachedPreviewLutRequest = work.request
                pendingPreviewLutRequest.compareAndSet(work.request, null)
                applyResolvedPreviewLut(work.publicationSequence, result.previewLut)
            },
            onFailure = { work, failure ->
                pendingPreviewLutRequest.compareAndSet(work.request, null)
                Log.e("CameraViewModel", "Serialized preview LUT resolve failed", failure)
            }
        )
    }
    private var modeSwitchAutomaticBitratePending = false
    private var modeSwitchAutomaticBitrateForcePending = false
    private var modeSwitchRuntimeStatsPending = false
    private var modeSwitchRuntimeStatsPreviewFps = 0f
    private var modeSwitchRuntimeStatsJob: Job? = null
    private var aiTransitionReleaseJob: Job? = null

    // UI State
    private val _uiMode    = MutableStateFlow(AppMode.VIDEO)
    val uiMode: StateFlow<AppMode> = _uiMode
    private val _sdrQualityIntent = MutableStateFlow(SdrQualityIntent.NORMAL)

    private val _rawOn     = MutableStateFlow(false)
    val rawOnFlow: StateFlow<Boolean> = _rawOn

    private val _gridMode  = MutableStateFlow(GridMode.NONE)
    val gridMode: StateFlow<GridMode> = _gridMode

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
    private val _sdrConversionPipeline = MutableStateFlow(prefs.sdrConversionPipelineEnabled)
    val sdrConversionPipelineEnabled: StateFlow<Boolean> = _sdrConversionPipeline.asStateFlow()

    private val _cinemaRuntimeStats = MutableStateFlow(CinemaRuntimeStats())
    val cinemaRuntimeStats: StateFlow<CinemaRuntimeStats> = _cinemaRuntimeStats

    /**
     * Honest, plain transport label for the active capture or finalization path. NPV10 owns a native
     * P010 ImageReader rather than a MediaCodec feed path, so it is identified explicitly instead of
     * being left unresolved or being mislabeled as an encoder transport.
     */
    fun activeRecordingTransportHudLabel(): String? {
        if (npv10P010TransportVisible()) return "P010"
        return when (activeRecordingFeedPath) {
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER -> "P010"
            RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
            RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE -> "ENCODER_SURFACE_DIRECT"
            RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
            RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER -> "AHB_GPU_COMPATIBILITY"
            null -> null
        }
    }

    private fun npv10P010TransportVisible(): Boolean =
        when (npv10Recording.state.value) {
            GpuRecordingState.Recording,
            GpuRecordingState.Paused,
            GpuRecordingState.Finalizing -> true
            else -> false
        }

    private fun activeRecordingFeedPathHealthLabel(): String? =
        if (npv10P010TransportVisible()) {
            NPV10_NATIVE_P010_FEED_PATH
        } else {
            activeRecordingFeedPath?.name
        }

    private val _uiStateFlow = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiStateFlow.asStateFlow()
    private val settingsRejectionSequence = AtomicLong(0L)
    private val _settingsRejection = MutableStateFlow<SettingsRejectionNotice?>(null)
    val settingsRejection: StateFlow<SettingsRejectionNotice?> = _settingsRejection.asStateFlow()
    @Volatile private var lastSettingsInvariantWarning: String? = null
    private val actionChannel = Channel<UiAction>(
        capacity = CameraUiResponsivenessPolicy.UI_ACTION_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    private val _previewAspect = MutableStateFlow(PreviewAspectRatio.FOUR_THREE)
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

    // Sub-systems
    val cameraState:      StateFlow<CameraState>      = camera.state
    private val foregroundRecovery = CameraForegroundRecoveryCoordinator(
        scope = viewModelScope,
        cameraState = cameraState,
        hasPreviewSurface = { previewSurface != null },
        closeInFlight = { cameraCloseJob?.isActive == true },
        recordingOrStopping = ::isRecordingOrStopping,
        recoverStalePreview = { runOnMain { resumeCameraForForeground() } }
    )
    private val recordingForegroundProtection = RecordingForegroundProtectionCoordinator(
        application = getApplication(),
        scope = viewModelScope,
        onProtectionFailure = {
            setHardwareWarning("Recording stopped: Android could not guarantee background capture safety.")
            if (recordingStopRestartLifecycle.isStarting || isRecordingOrStopping()) {
                requestRecordingStop(
                    origin = RecordingStopOrigin.FOREGROUND_SERVICE_FAILURE,
                    detail = "Android foreground recording protection unavailable"
                )
            }
        }
    )
    private val failedRecordingStartRecovery = FailedRecordingStartRecoveryCoordinator(
        scope = viewModelScope,
        actions = FailedRecordingStartRecoveryActions(
            backendFailureMessage = { (gpuRecording.state.value as? GpuRecordingState.Error)?.message },
            publishTransitionFailure = { recordingTransitionState.value = GpuRecordingState.Error(it) },
            stopForegroundProtection = recordingForegroundProtection::stop,
            reportRecorderFailure = gpuRecording::reportExternalErrorAfterOwnershipRetired,
            discardPreparedSession = gpuRecording::discardPreparedSession, releasePersistentInputSurface = gpuRecording::releasePersistentInputSurface,
            refreshCameraSession = { camera.refreshOutputsAndSession(allowIdenticalRecoveryRebuild = true, reason = "recording-error-recovery") },
            restorePreviewBinding = { applyRecordingOutputAspectOverride(null); Log.i("CameraViewModel", "Recording cleanup: restoring normal preview binding"); refreshPreviewPipelineIfNeeded(forceSessionRefresh = true) },
            syncCameraPreviewLook = ::syncCameraPreviewLookControls, reconcileRawPreview = ::reconcileRawPreviewPipeline,
            releaseCinemaAutoLock = { releaseCinemaAutoLock(true, "recording_start_cleanup") },
            syncPreviewFrameRate = ::syncActivePreviewFrameRate, syncPreviewPerformance = ::syncPreviewPerformanceMode,
            reportCleanupError = { failure, operation, userMessage -> handleCameraError(failure, operation, userMessage) }
        )
    )
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
    val previewDisplayPresentEvents: SharedFlow<PreviewDisplayPresentEvent> =
        _previewDisplayPresentEvents.asSharedFlow()
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
    // A buffered channel is a lifecycle-safe one-shot queue: completion while the Activity is STOPPED
    // remains pending, but an already-consumed save is never replayed as a duplicate banner on resume.
    private val recordingSavedPathChannel = Channel<String>(capacity = Channel.BUFFERED)
    val recordingSavedPath: Flow<String> = recordingSavedPathChannel.receiveAsFlow()
    val captureEvents: SharedFlow<CaptureEvent> = stillCapture.events

    private fun notifyRecordingSaved(path: String) {
        val normalized = path.trim()
        if (normalized.isEmpty()) return
        if (recordingSavedPathChannel.trySend(normalized).isFailure) {
            viewModelScope.launch { recordingSavedPathChannel.send(normalized) }
        }
    }

    // Pipeline outputs
    val frameGeometry: StateFlow<FrameGeometry?> = camera.frameGeometry
    val avgLuma:   StateFlow<Float>     = pipeline.avgLuma
    val sceneAnalysis = pipeline.sceneAnalysis
    val captureRecommendation = pipeline.captureRecommendation
    val vulkanSupported: StateFlow<Boolean> = vulkanRuntime.supported
    val vulkanCapabilitySummary: StateFlow<String> = vulkanRuntime.capabilitySummary
    val vulkanImportStats: StateFlow<VulkanImportStats> = vulkanRuntime.importStats

    private val healthFrameDeliveryLiveness = FrameDeliveryLivenessTracker()
    @Volatile private var healthLastComputeDispatches: Long = -1L
    @Volatile private var healthMemUsageMb: Int? = null
    @Volatile private var healthMemUtilPct: Int? = null

    /**
     * Refresh expensive process/system telemetry away from the UI and camera callback threads.
     * Debug.getPss(), ActivityManager.getMemoryInfo(), and StatFs can each block for several display
     * frames on vendor builds. Consumers use the latest immutable snapshot; capture quality and RAW
     * viability decisions are unchanged once the first background sample arrives.
     */
    private fun requestRuntimeResourceTelemetryRefresh(
        nowMs: Long = SystemClock.elapsedRealtime(),
        force: Boolean = false
    ) = runtimeResourceTelemetry.request(nowMs, force)

    fun buildHealthSnapshot(nowMs: Long): com.neoproc.neoproccamx.monitor.RuntimeSignalSnapshot {
        val camState = cameraState.value
        val recState = gpuRecordState.value
        val stats = vulkanImportStats.value
        val stats2 = cinemaRuntimeStats.value
        val recording = recState is GpuRecordingState.Recording || recState is GpuRecordingState.Paused

        val frameStampNs = liveResult.value?.timestampNs ?: 0L
        val frameLiveness = healthFrameDeliveryLiveness.update(
            FrameDeliveryLivenessTracker.Input(
                nowElapsedMs = nowMs,
                sessionGeneration = camera.sessionGeneration.get(),
                cameraStreaming =
                    camState is CameraState.Streaming || camState is CameraState.Recording,
                sessionRefreshPending = camera.sessionRefreshPending.get(),
                captureResultTimestampNs = frameStampNs,
                sessionImportedFrames = stats.sessionImportedFrames
            )
        )
        val fps = stats2.previewFps
        val lastDelivery = frameLiveness.lastFrameDeliveryElapsedMs
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
        val feedPathName = activeRecordingFeedPathHealthLabel()
        // NPV10 and BYTEBUFFER_P010 own genuine camera P010 ImageReaders. GPU/direct arms source an
        // encoder surface instead, so no app-owned P010 source depth may be asserted for those routes.
        val p010SrcDepth = when {
            feedPathName == NPV10_NATIVE_P010_FEED_PATH -> npv10Recording.activeSourceBitDepth()
            recording && feedPathName?.startsWith("BYTEBUFFER_P010") == true -> 10
            else -> null
        }

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

        // ---- Rich diagnostics: cached background memory sampling -------------------------------
        // The health loop runs on Main every 150 ms. It may request a refresh, but never performs
        // Debug.getPss()/ActivityManager work itself.
        requestRuntimeResourceTelemetryRefresh(nowMs)

        return com.neoproc.neoproccamx.monitor.RuntimeSignalSnapshot(
            sampleElapsedMs = nowMs,
            cameraOpen = cameraOpen,
            frameDeliveryExpected = frameLiveness.frameDeliveryExpected,
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
            ispRawRequestActive = camera.isIspBypassReadbackApplicable(),
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
        return camera.requestIspBypassReadbackRearm()
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
        _aiEnhancement.value = effectiveAiEnhancementEnabled(state.aiEnhancement)
        _aiMotionCompensation.value = state.aiMotionCompensation
        _uncompressedCinemaPipeline.value = true
        _sdrConversionPipeline.value = state.sdrConversionPipeline
        gpuRecording.sdrConversionPipelineEnabled = state.sdrConversionPipeline
        _videoDetailProfile.value = state.videoDetailProfile
        gpuRecording.videoDetailProfile = _videoDetailProfile.value
        _manualControlsVisible.value = state.manualControlsVisible
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
            _aiEnhancement.map { it as Any? },
            _aiMotionCompensation.map { it as Any? },
            _uncompressedCinemaPipeline.map { it as Any? },
            _sdrConversionPipeline.map { it as Any? },
            _videoDetailProfile.map { it as Any? },
            _manualControlsVisible.map { it as Any? }
        )
        viewModelScope.launch(Dispatchers.Default) {
            combine(settingsSources) { values ->
                SettingsDomainState(
                    gridMode = values[0] as GridMode,
                    aiEnhancement = values[1] as Boolean,
                    aiMotionCompensation = values[2] as Boolean,
                    uncompressedCinemaPipeline = values[3] as Boolean,
                    sdrConversionPipeline = values[4] as Boolean,
                    videoDetailProfile = values[5] as VideoDetailProfile,
                    manualControlsVisible = values[6] as Boolean
                )
            }.collect { state ->
                settingsController.syncFromLegacy(
                    gridMode = state.gridMode,
                    aiEnhancement = state.aiEnhancement,
                    aiMotionCompensation = state.aiMotionCompensation,
                    uncompressedCinemaPipeline = state.uncompressedCinemaPipeline,
                    sdrConversionPipeline = state.sdrConversionPipeline,
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

        val recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused
        val photoPlan = StillPhotoCaptureModePolicy.resolve(
            rawVideoModeActive = isRawVideoModeActive(),
            logVideoModeActive = isVideoLogPreviewActive(),
            effectiveHdrMode = activeVideoFormatDeliveryState().effectiveHdrMode,
            recordingActive = recordingActive,
            rawSupported = camera.supportsRaw,
            photoHdrMode = prefs.photoHdrMode
        )
        when (photoPlan.route) {
            StillPhotoCaptureModePolicy.Route.RAW_DNG_COMPANION -> {
                // RAW_SENSOR is shared by the idle RAW monitor, RAW video, and DNG capture. Install the
                // multiplexer before submitting the request so the still frame cannot be consumed by the
                // video owner and silently disappear.
                installRawVideoCallbacks()
                stillCapture.captureSingle(raw = photoPlan.requestRawCompanion)
            }

            StillPhotoCaptureModePolicy.Route.NDR_BRACKET,
            StillPhotoCaptureModePolicy.Route.USER_HDR_BRACKET ->
                stillCapture.captureHDR(rawCompanion = false, hdrMode = photoPlan.hdrMode)

            StillPhotoCaptureModePolicy.Route.NDR_VIDEO_SNAPSHOT,
            StillPhotoCaptureModePolicy.Route.LOG_SINGLE,
            StillPhotoCaptureModePolicy.Route.SDR_SINGLE ->
                stillCapture.captureSingle(raw = false)
        }
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
        RecordingTerminalFailureCleanupObserver(
            viewModelScope, gpuRecording, npv10Recording, viewModelTeardownStarted::get,
            ::setHardwareWarning, ::requestRecordingStop
        ).start()
        startStartupBootstrap()
        startSnceColorEngine()
        gpuRecording.runtimeEvidenceMetadataProvider = ::buildRecordingRuntimeEvidenceMetadata
        npv10Recording.runtimeEvidenceMetadataProvider = ::buildRecordingRuntimeEvidenceMetadata
        // The NPV10 master is captured bit-exactly inside the frame deadline. NIV99 owns only the
        // later full-frame conversion boundary and must never execute on this live writer path.
        npv10Recording.setNiv99FrameProcessor(null)
        gpuRecording.recordedFrameCommitListener = { sensorTimestampNs ->
            pipeline.onRecordedFrame(sensorTimestampNs)
        }
        SensorPipelineIntegration.bindRecordingStages(gpuRecording, pipeline)
        rawVideoManager.persistedFrameCommitListener = { sensorTimestampNs ->
            pipeline.onRecordedFrame(sensorTimestampNs)
        }
        rawVideoManager.recordingAudioFailureListener = { failure ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                setHardwareWarning("RAW microphone capture failed: ${failure.message}")
                requestRecordingStop(
                    origin = RecordingStopOrigin.RAW_AUDIO_FAILURE,
                    detail = failure.message ?: "RAW AAC capture failed"
                )
            }
        }
        rawVideoManager.continuityBoundaryStopListener = { boundary ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val message =
                    "RAW recording finalized safely after ${boundary.acceptedFrames} frames: " +
                        boundary.message
                Log.w("CameraViewModel", message)
                setHardwareWarning(message)
                requestRecordingStop(
                    origin = RecordingStopOrigin.RAW_CONTINUITY_BOUNDARY,
                    detail = "reason=${boundary.reason};acceptedFrames=${boundary.acceptedFrames};" +
                        "maximumFrames=${boundary.maximumFrames}"
                )
            }
        }
        
        thermalStatusCoordinator = CameraThermalStatusCoordinator(app) { event ->
            val thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    app.getSystemService(PowerManager::class.java)
                        ?.getThermalHeadroom(0)
                        ?.takeIf { value -> value.isFinite() }
                }.getOrNull()
            } else null
            pipeline.onThermalSample(
                TimedThermalSample(
                    timestampNs = SystemClock.elapsedRealtimeNanos(),
                    state = PipelineThermalState.fromAndroidStatus(event.platformStatus),
                    platformStatus = event.platformStatus,
                    headroom = thermalHeadroom,
                    // Android's public thermal status API exposes no physical camera temperature.
                    temperatureCelsius = null
                )
            )
            latestRawThermalLabel = event.tier.name
            updateState {
                it.copy(thermalTier = event.tier, thermalThrottled = event.throttled)
            }
            camera.setThermalResourceCeiling(event.thermalState.resourceCeiling)
            // The presentation tier (for example PROFESSIONAL/SUSTAINED) is not a thermal severity
            // label. Feed the exact Android status mapping to the runtime policy so moderate and
            // critical heat cannot be misclassified as a cool device.
            maybeApplyThermalGuard(event.runtimePolicyLabel)
            Log.i(
                "CameraViewModel",
                "Thermal status change: status=${event.platformStatus} tier=${event.tier}"
            )
        }
        thermalStatusCoordinator.start()
        requestRuntimeResourceTelemetryRefresh(force = true)
        pipeline.connectAICameraPipeline(aiCameraPipeline)
        pipeline.connectVulkanRuntime(vulkanRuntime)
        pipeline.setDedicatedHardwareImportEnabled(false)
        vulkanRuntime.setFrameImportEnabled(false)
        vulkanRuntime.advanceRenderProfileGeneration(currentVulkanRenderProfileGeneration())
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
            // Initialization and preference restoration run concurrently. The first native write is
            // one complete immutable packet built only after restored runtime authority is available.
            startupPreferencesReady.await()
            runOnMain {
                pushVulkanPreviewRenderConfig(force = true)
                refreshPreviewPipelineIfNeeded()
            }
        }
        viewModelScope.launch {
            camera.frameGeometry.collect { geometry ->
                applyFrameGeometry(geometry)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            camera.previewFrameTimestamps.collect {
                foregroundRecovery.onPreviewFrame()
            }
        }

        // Wire: Camera YUV -> pipeline (pipeline extracts bytes, closes image, dispatches)
        camera.onYuvFrame = { reader -> pipeline.enqueue(reader) }
        camera.onHardwareFrame = { reader -> pipeline.enqueueHardware(reader) }
        npv10Recording.persistedFrameCommitListener = ::recordNpv10FrameLineageEncoderSubmission
        val readerCloseCoordinator = CameraReaderCloseCoordinator(pipeline, stillCapture, rawVideoManager)
        camera.closeReadersInsideFramePipelineBarrier = readerCloseCoordinator::closeReaders
        camera.onCaptureResult = { result ->
            stillCapture.onCaptureResult(result)
        }
        // Keep the coordinator lazy through ViewModel construction. Its compositor dependencies are
        // declared after this init block and are not initialized until construction continues; taking
        // a bound method reference here would force the lazy delegate against JVM null defaults.
        camera.onRawCaptureResultWithSessionGeneration = { result, sessionGeneration ->
            rawPreviewPresentationCoordinator.onCaptureResult(result, sessionGeneration)
        }
        // Live Camera2 metadata is intentionally not routed into NIV99. Offline conversion reads
        // the immutable capture sidecar instead, so metadata work cannot delay a recording frame.
        camera.onNiv99CaptureResultWithSessionGeneration = null
        camera.onCaptureResultWithSessionGeneration = captureResult@{ result, sessionGeneration ->
            if (!SensorLinearNdrSessionGenerationPolicy.isCurrent(
                    frameSessionGeneration = sessionGeneration,
                    currentSessionGeneration = camera.sessionGeneration.get()
                )
            ) return@captureResult
            if (prearmedRecordingFeedPath == RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE ||
                prearmedRecordingFeedPath == RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
            ) {
                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let(
                    gpuRecording::registerDirectCameraSurfaceFrame
                )
            }
            try {
                pipeline.onCaptureResult(result, sessionGeneration)
            } catch (exception: Exception) {
                // Frame diagnostics are observational and must never break still-capture metadata
                // delivery or the Camera2 repeating callback on a malformed vendor result.
                Log.e(
                    "CameraViewModel",
                    "Sensor-frame diagnostics rejected a Camera2 capture result",
                    exception
                )
            }
            // Diagnostic lineage is snapshotted last and drained on its bounded single worker. Neither
            // preview-display reconciliation nor evidence aggregation may delay RAW pairing or frame ID.
            recordCamera2FrameLineageCaptureResult(result, sessionGeneration)
        }
        camera.onMotionSensorSample = { sensorType, timestampNs, x, y, z ->
            when (sensorType) {
                android.hardware.Sensor.TYPE_GYROSCOPE ->
                    pipeline.onGyroscopeSample(timestampNs, x, y, z)
                android.hardware.Sensor.TYPE_ACCELEROMETER ->
                    pipeline.onAccelerometerSample(timestampNs, x, y, z)
            }
        }
        camera.onSessionGenerationAdvanced = { generation ->
            rebaseCamera2FrameLineageSessionIfActive(generation)
            onPreviewDisplaySessionGenerationAdvanced()
            beginSensorPipelineSession(generation)
            rawPreviewPresentationCoordinator.onSessionGenerationAdvanced(generation)
        }
        camera.onSessionGenerationConfigured = { rawPreviewPresentationCoordinator.onSessionGenerationConfigured(it) }
        vulkanRuntime.setFrameLineageSubmissionListener lineage@{ submission ->
            if (submission.cameraSessionGeneration >= 0L &&
                !SensorLinearNdrSessionGenerationPolicy.isCurrent(
                    frameSessionGeneration = submission.cameraSessionGeneration,
                    currentSessionGeneration = camera.sessionGeneration.get()
                )
            ) return@lineage
            recordCamera2FrameLineageSubmission(submission)
            if (submission.encoderSubmitted) {
                gpuRecording.registerSubmittedEncoderFrame(
                    sensorTimestampNs = submission.sensorTimestampNs,
                    encoderPresentationTimeNs = submission.presentationTimeNs
                )
            }
        }
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
            pipeline.ndrExposureMetrics.collectLatest { metrics ->
                camera.setHdrExposureSceneMetrics(
                    averageLuma = metrics.averageLuma,
                    highlightClipFraction = metrics.highlightClipFraction,
                    redHighlightClipFraction = metrics.redHighlightClipFraction,
                    pqHighlightPressureFraction = metrics.pqHighlightPressureFraction,
                    pqRedHighlightPressureFraction = metrics.pqRedHighlightPressureFraction,
                    sourcePqCandidate = metrics.sourcePqCandidate,
                    analysisValid = metrics.valid,
                    measuredAtElapsedRealtimeMs = metrics.measuredAtElapsedRealtimeMs,
                    sourceSessionGeneration = metrics.sessionGeneration
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
                liveResult,
                pipeline.aiMotionCompensationEnabled,
                recordingCodecCapabilityRefresh
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
                val authoritativeColorProfile = resolveGpuColorProfile(
                    deliveryState.effectiveHdrMode, videoColorSpace, colorProfile
                )
                val effectiveProfile =
                    if (neutralVideoPreview || deliveryState.rawVideoActive) {
                        "Natural"
                    } else "${authoritativeColorProfile.colorSpaceName} ${authoritativeColorProfile.colorScienceName}"
                val effectiveLogEnabled = if (neutralVideoPreview) false else deliveryState.logVideoActive
                val effectiveHdrEnabled = if (neutralVideoPreview) {
                    false
                } else {
                    hdrOn || deliveryState.effectiveHdrMode != "OFF"
                }
                val simulatedTenBitFallback =
                    VideoRenderProfileQualityHintPolicy.isVideoSurface(mode) &&
                        deliveryState.tenBitActive &&
                        !supports10BitWithoutCodecProbeBlocking()
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
                    aiMotionCompensationEnabled = values[16] as? Boolean ?: false
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

    }

    // -------------------------------------------------------------------------
    // PREVIEW SURFACE WIRING
    // -------------------------------------------------------------------------

    /**
     * Called once from MainActivity after the preview view is inflated.
     * Connects the pipeline to GL (for requestRender) and AI.
     */
    private fun GpuRecordingState.blocksLifecyclePreferenceMutation(): Boolean =
        this is GpuRecordingState.Starting ||
            this is GpuRecordingState.Recording ||
            this is GpuRecordingState.Paused ||
            this is GpuRecordingState.Stopping ||
            this is GpuRecordingState.Finalizing

    private fun lifecyclePreferenceMutationBlockedByRecording(): Boolean =
        recordingStopRestartLifecycle.isStarting ||
            recordingStopRestartLifecycle.isStopInProgress ||
            cameraState.value == CameraState.Recording ||
            recordingTransitionState.value.blocksLifecyclePreferenceMutation() ||
            rawCustomRecordingState.value.blocksLifecyclePreferenceMutation() ||
            npv10Recording.hasActiveSession() ||
            npv10Recording.state.value.blocksLifecyclePreferenceMutation() ||
            gpuRecording.state.value.blocksLifecyclePreferenceMutation()

    private fun deferLifecyclePreferenceRefresh(reason: String) {
        preferencesRefreshDeferredByRecording.set(true)
        refreshPreferencesJob?.cancel()
        preferencesRefreshOwnership.invalidate()
        Log.i("CameraViewModel", "Lifecycle preference refresh deferred: $reason")
    }

    private fun replayDeferredLifecyclePreferenceRefreshIfSafe() {
        runOnMainIfNeeded {
            if (lifecyclePreferenceMutationBlockedByRecording() ||
                viewModelTeardownStarted.get()
            ) return@runOnMainIfNeeded
            replayDeferredPreviewLutPublicationIfSafe()
            if (!preferencesRefreshDeferredByRecording.get()) return@runOnMainIfNeeded
            if (!preferencesRefreshDeferredByRecording.compareAndSet(true, false)) {
                return@runOnMainIfNeeded
            }
            Log.i("CameraViewModel", "Replaying deferred lifecycle preference refresh after recording")
            refreshPreferences()
        }
    }

    fun refreshPreferences() {
        if (lifecyclePreferenceMutationBlockedByRecording()) {
            deferLifecyclePreferenceRefresh("recording/start/stop owns Camera2 session")
            return
        }
        refreshPreferencesJob?.cancel()
        val signature = currentPreferencesRefreshSignature()
        if (signature == lastAppliedPreferencesSignature) return
        val lease = preferencesRefreshOwnership.begin()
        refreshPreferencesJob = viewModelScope.launch(Dispatchers.Default) {
            applyPreferences(signature = signature, lease = lease)
        }
    }

    private fun invalidatePendingPreferenceRestore() {
        preferencesRefreshOwnership.invalidate()
    }

    /**
     * Builds the complete user-facing recording-settings transaction before any preference, runtime,
     * Camera2 session, encoder, or render-generation mutation is allowed to occur.
     */
    private fun proposedSettingsCommit(
        mode: CinemaVideoMode = _cinemaVideoMode.value,
        hdrMode: String = _videoHdrMode.value,
        bitrateMode: VideoBitrateMode = _videoBitrateMode.value,
        rawCompression: RawCompressionMode = _rawVideoCompression.value,
        fps: Int = recording.fps,
        codec: VideoCodecType = recording.codec,
        resolution: Size = selectedVideoSize ?: Size(
            recording.width.coerceAtLeast(1),
            recording.height.coerceAtLeast(1)
        ),
        customBitrateBps: Int = prefs.videoBitrate,
        encodedTenBitRequested: Boolean = mode == CinemaVideoMode.RAW ||
            resolveVideoRecordingTenBitRequested(cinemaMode = mode, hdrMode = hdrMode)
    ): SettingsCommit = SettingsCommit(
        mode = mode,
        hdrMode = hdrMode,
        bitrateMode = bitrateMode,
        rawCompression = rawCompression,
        fps = fps,
        codec = codec,
        width = resolution.width,
        height = resolution.height,
        customBitrateBps = customBitrateBps,
        encodedTenBitRequested = encodedTenBitRequested
    )

    /**
     * Single fail-closed gate for settings mutations. Rejections publish a monotonic, accessible
     * reason while deliberately leaving preferences, runtime fields, render generations and Camera2
     * session ownership untouched.
     */
    private fun admitSettingsCommit(commit: SettingsCommit, source: String): Boolean =
        when (val result = SettingsInvariantResolver.validate(commit)) {
            SettingsInvariantResult.Valid -> {
                clearSettingsInvariantRejection()
                true
            }
            is SettingsInvariantResult.Rejected -> rejectSettingsRequest(
                ruleId = result.ruleId,
                reason = result.message,
                source = source,
                commit = commit
            )
        }

    private fun rejectSettingsRequest(
        ruleId: String,
        reason: String,
        source: String,
        commit: SettingsCommit = proposedSettingsCommit()
    ): Boolean {
        val displayMessage = "Setting not applied: $reason. Current quality settings were kept."
        val notice = SettingsRejectionNotice(
            sequence = settingsRejectionSequence.incrementAndGet(),
            ruleId = ruleId,
            source = source,
            message = displayMessage
        )
        _settingsRejection.value = notice
        lastSettingsInvariantWarning = displayMessage
        Log.w(
            "CameraViewModel",
            "SETTINGS_INVARIANT_REJECTED rule=$ruleId source=$source " +
                "mode=${commit.mode.name} hdr=${commit.hdrMode} fps=${commit.fps} " +
                "size=${commit.width}x${commit.height} codec=${commit.codec.name} " +
                "bitrateMode=${commit.bitrateMode.name} bitrate=${commit.customBitrateBps} " +
                "rawCompression=${commit.rawCompression.name} reason=$reason"
        )
        setHardwareWarning(displayMessage)
        return false
    }

    private fun clearSettingsInvariantRejection() {
        val previousWarning = lastSettingsInvariantWarning
        _settingsRejection.value = null
        lastSettingsInvariantWarning = null
        if (previousWarning != null && _uiStateFlow.value.hardwareWarning == previousWarning) {
            setHardwareWarning(null)
        }
    }

    fun dismissSettingsRejection() {
        clearSettingsInvariantRejection()
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
            sdrConversionPipelineEnabled = prefs.sdrConversionPipelineEnabled,
            noiseReductionEnabled = effectiveNoiseReductionEnabled(),
            stabilizationMode = prefs.stabilizationMode,
            lastIso = prefs.lastISO,
            lastShutterNs = prefs.lastShutterNs,
            lastWhiteBalanceKelvin = prefs.lastWBKelvin,
            lastWhiteBalanceTint = prefs.lastWBTint,
            lastFocusDistance = prefs.lastFocusDistance,
            lastExposureCompensation = prefs.lastEV,
            rawEnabled = prefs.rawEnabled,
            gridMode = prefs.gridMode,
            logEnabled = prefs.logEnabled,
            videoHdrMode = prefs.videoHdrMode,
            videoColorSpace = prefs.videoColorSpace,
            videoDetailProfile = prefs.videoDetailProfile,
            videoMode = prefs.videoMode,
            rawVideoCompression = prefs.rawVideoCompression,
            rawResolutionMode = prefs.rawResolutionMode,
            videoBitrateMode = prefs.videoBitrateMode,
            colorProfile = prefs.colorProfile,
            lutEnabled = prefs.lutEnabled,
            lutProfile = prefs.lutProfile,
            customLutPath = prefs.customLutPath,
            aiEnhancementEnabled = effectiveAiEnhancementEnabled(),
            aiMotionCompensationEnabled = prefs.aiMotionCompensationEnabled,
            previewAspectRatio = prefs.previewAspectRatio,
            safeAreaGuidesEnabled = prefs.safeAreaGuidesEnabled,
            saturation = prefs.saturation,
            contrast = prefs.contrast,
            brightness = prefs.brightness,
            faceDetectionEnabled = prefs.faceDetectionEnabled,
            eyeAutofocusEnabled = prefs.eyeAutofocusEnabled
        )

    private fun applyPreferences(
        signature: PreferencesRefreshSignature = currentPreferencesRefreshSignature(),
        force: Boolean = false,
        lease: LatestRestoreOwnership.Lease = preferencesRefreshOwnership.begin()
    ): Boolean {
        if (lifecyclePreferenceMutationBlockedByRecording()) {
            deferLifecyclePreferenceRefresh("recording became active before restore commit")
            return false
        }
        if (!force && signature == lastAppliedPreferencesSignature) return true
        val restoredUiMode = signature.uiMode
        val wantsCustomLut = signature.lutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true)
        val desiredGpuCodec = RecordingCapabilityPolicy.resolveRequestedCodec(
            requested = VideoRecordingContractPolicy.resolveRequestedCodec(
                runCatching { VideoCodecType.valueOf(signature.videoCodec.uppercase(Locale.US)) }
                    .getOrDefault(VideoCodecType.HEVC)
            ),
            av1Supported = supportsAv1Fast()
        )
        val requestedSizeAtResolve = signature.videoSize.normalizeForOrientation()
        val resolutionCompatibleFps = SettingsOptionCatalog.selectedFpsForResolution(
            currentFps = signature.videoFps,
            size = requestedSizeAtResolve
        )
        val resolvedSelection = resolveRecordingSelection(
            preferred = requestedSizeAtResolve,
            preferredCodec = desiredGpuCodec,
            requestedFps = resolutionCompatibleFps
        )
        val desiredAudio = audioSourceFromPref(signature.audioSource)

        val previewFrameRatePlan =
            RecordingPreviewFrameRatePolicy.resolve(resolvedSelection, resolutionCompatibleFps)
        val normalizedVideoHdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(signature.videoHdrMode)
        val requestedCinemaMode = CinemaVideoMode.fromName(signature.videoMode)
        val restoredVideoMode = VideoModeRestorePolicy.resolve(
            requestedHdrMode = normalizedVideoHdrMode,
            hdrRequested = normalizedVideoHdrMode != "OFF",
            uiMode = restoredUiMode,
            requestedCinemaMode = requestedCinemaMode,
            rawSupported = camera.supportsRaw
        )
        val normalizedVideoColorSpace = ManualVideoColorSelectionPolicy
            .normalizeColorSpace(signature.videoColorSpace)
            .takeUnless { it == ManualVideoColorSelectionPolicy.UNSELECTED }
            ?: signature.videoColorSpace
        val restoredPipelineAdmission = resolveRequestedVideoModeAdmission(
            cinemaMode = restoredVideoMode.cinemaMode,
            requestedHdrMode = restoredVideoMode.hdrMode,
            preferredColorSpace = normalizedVideoColorSpace
        )
        val restoredModeAdmitted = restoredVideoMode.cinemaModeAdmission.accepted &&
            restoredPipelineAdmission.admissionState == VideoPipelineAdmissionState.ADMITTED
        val restoredCinemaMode = if (restoredModeAdmitted) {
            normalizeNpv10CinemaMode(restoredVideoMode.cinemaMode)
        } else {
            CinemaVideoMode.PRO
        }
        val restoredHdrMode = if (restoredModeAdmitted) restoredVideoMode.hdrMode else "OFF"
        val restoredHdrEnabled = restoredModeAdmitted && restoredVideoMode.hdrEnabled
        val restoredRawEnabled = restoredModeAdmitted && restoredVideoMode.rawEnabled
        val restoredLogEnabled = restoredModeAdmitted && restoredVideoMode.logEnabled
        val restoredVideoColorSpace = if (restoredModeAdmitted) {
            normalizedVideoColorSpace
        } else {
            restoredPipelineAdmission.effectiveColorSpace
        }
        val restoredRawCompression = normalizeRawCompressionForMode(
            RawCompressionMode.fromName(signature.rawVideoCompression),
            restoredCinemaMode
        )
        var restoredLutProfile = signature.lutProfile
        var restoredLutEnabled = false

        val committed = preferencesRefreshOwnership.commitIfCurrent(
            lease = lease,
            additionalValidation = {
                currentPreferencesRefreshSignature() == signature &&
                    !lifecyclePreferenceMutationBlockedByRecording()
            }
        ) {
            val customLutLoaded = restorePersistedCustomLut(loadContent = wantsCustomLut)
            restoredLutProfile = when {
                signature.lutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true) && !customLutLoaded ->
                    "Neutral Film"
                else -> signature.lutProfile
            }
            restoredLutEnabled = signature.lutEnabled &&
                (!restoredLutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true) || customLutLoaded)

            prefs.lastUiMode = restoredUiMode.name
            if (prefs.videoFps != previewFrameRatePlan.encoderFps) {
                prefs.videoFps = previewFrameRatePlan.encoderFps
            }
            if (prefs.videoHdrMode != normalizedVideoHdrMode) {
                prefs.videoHdrMode = normalizedVideoHdrMode
            }
            if (prefs.videoColorSpace != normalizedVideoColorSpace) {
                prefs.videoColorSpace = normalizedVideoColorSpace
            }
            advanceVulkanRenderProfileGeneration(
                reason = "preferences-refresh-commit",
                committedHdrMode = restoredHdrMode,
                authorityMutation = {
                    _uiMode.value = restoredUiMode
                    syncRecordingBitrateMode(signature.videoBitrateMode)
                    recording.fps = previewFrameRatePlan.encoderFps
                    recording.bitrate = signature.videoBitrate
                    recording.audioSource = desiredAudio
                    _videoDetailProfile.value = signature.videoDetailProfile
                    gpuRecording.videoDetailProfile = signature.videoDetailProfile
                    synchronized(recordingSelectionMutationLock) {
                        applyResolvedRecordingConfig(
                            resolvedSelection = resolvedSelection,
                            fps = previewFrameRatePlan.encoderFps,
                            bitrate = signature.videoBitrate,
                            audioSource = desiredAudio
                        )
                    }
                    _rawOn.value = restoredRawEnabled
                    _uncompressedCinemaPipeline.value = true
                    _sdrConversionPipeline.value = signature.sdrConversionPipelineEnabled
                    gpuRecording.sdrConversionPipelineEnabled =
                        signature.sdrConversionPipelineEnabled
                    _aiMotionCompensation.value = signature.aiMotionCompensationEnabled
                    _gridMode.value = gridModeFromPref(signature.gridMode)
                    _safeAreaGuides.value = signature.safeAreaGuidesEnabled
                    _logOn.value = restoredLogEnabled
                    _videoColorSpace.value = restoredVideoColorSpace
                    // Normal processed recording stays active; RAW remains an independent NPRAW path.
                    _cinemaVideoMode.value = restoredCinemaMode
                    _rawVideoCompression.value = restoredRawCompression
                    _rawResolutionMode.value = signature.rawResolutionMode
                    _videoHdrOn.value = restoredHdrEnabled
                    _colorProfile.value = signature.colorProfile
                    _lutEnabled.value = restoredLutEnabled
                    _lutProfile.value = restoredLutProfile
                    _aiEnhancement.value = signature.aiEnhancementEnabled
                    _previewAspect.value = signature.previewAspectRatio
                    colorScience.saturation = signature.saturation
                    colorScience.contrast = signature.contrast
                    colorScience.brightness = signature.brightness
                    videoFeatureState = VideoFeatureState(
                        rawEnabled = restoredRawEnabled,
                        logEnabled = restoredLogEnabled,
                        hdrEnabled = restoredHdrEnabled,
                        aspect = signature.previewAspectRatio,
                        colorProfile = signature.colorProfile,
                        lutEnabled = restoredLutEnabled,
                        lutProfile = restoredLutProfile,
                        colorSpace = restoredVideoColorSpace,
                        hdrMode = restoredHdrMode,
                        cinemaMode = restoredCinemaMode,
                        rawCompression = restoredRawCompression
                    )
                }
            )
        }
        if (!committed) {
            if (lifecyclePreferenceMutationBlockedByRecording()) {
                deferLifecyclePreferenceRefresh("recording won restore ownership before mutation")
                return false
            }
            Log.i(
                "CameraViewModel",
                "Skipped stale preference state restore requested=" +
                    "${requestedSizeAtResolve.width}x${requestedSizeAtResolve.height} " +
                    "hdr=${signature.videoHdrMode} currentHdr=${prefs.videoHdrMode}"
            )
            refreshPreferences()
            return false
        }

        syncRecordingCodecAndBitDepthContract(cinemaMode = _cinemaVideoMode.value)
        camera.setNoiseReductionEnabled(effectiveNoiseReductionEnabled())
        camera.setStabilizationMode(prefs.stabilizationMode)
        restorePersistedManualControls()
        syncActivePreviewFrameRate()
        camera.setVideoDetailProfile(_videoDetailProfile.value)
        pipeline.setAiMotionCompensationEnabled(_aiMotionCompensation.value)
        applyAiTrackingPreferences()

        // Apply cinema mode last since it drives RAW/LOG flags and may force HDR off for fidelity modes.
        applyCinemaModeSelection(_cinemaVideoMode.value, persist = false)
        if (!restoredModeAdmitted) {
            rejectVideoModeAdmission(
                contract = restoredPipelineAdmission,
                source = "preferences-restore",
                persistRequestedCinemaMode = true
            )
        }
        enforceVideoMeteringDefaultIfNeeded()
        applyColorPipelineState(
            colorProfile = _colorProfile.value,
            lutEnabled = _lutEnabled.value,
            lutProfile = _lutProfile.value,
            logEnabled = _logOn.value
        )
        gpuRecording.videoColorSpace = _videoColorSpace.value
        syncEffectiveRecordingColorProfile()
        rawVideoManager.compressionMode = _rawVideoCompression.value
        syncCameraPreviewLookControls()
        syncPreviewAnalysisFeed()
        refreshPreviewCompositorState()
        applyAutomaticVideoBitrateIfNeeded(force = true)
        configureModeSwitchStandbyAnalysisOutput()
        syncGeometryTargets()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
        val stillOwned = preferencesRefreshOwnership.commitIfCurrent(lease) {
            lastAppliedPreferencesSignature = currentPreferencesRefreshSignature()
        }
        return stillOwned
    }

    private fun restorePersistedManualControls() {
        val restorePlan = resolvePersistedManualControlPlan()
        CameraManualControlStateSyncPolicy.applyRestorePlan(restorePlan)
        if (_manualControlsVisible.value) {
            applyProManualControlPlanToCamera(restorePlan)
        } else {
            applyTemporaryAutomaticControlsToCamera()
        }
    }

    private fun resolvePersistedManualControlPlan(): ManualControlRestorePlan =
        CameraManualControlStateSyncPolicy.resolveRestorePlan(
            persistedIso = prefs.lastISO,
            isoRange = camera.effectiveIsoRange,
            persistedExposureNs = prefs.lastShutterNs,
            exposureRange = camera.exposureRange,
            persistedWhiteBalanceKelvin = prefs.lastWBKelvin,
            whiteBalanceRange = camera.manualWhiteBalanceRange,
            persistedWhiteBalanceTint = prefs.lastWBTint,
            persistedFocusDistance = prefs.lastFocusDistance,
            maximumFocusDistance = camera.minFocusDist,
            persistedExposureCompensation = prefs.lastEV
        )

    private fun applyProManualControlPlanToCamera(restorePlan: ManualControlRestorePlan) {
        unlockFocusAndExposure()
        if (camera.supportsManualSensor) {
            when (val iso = restorePlan.iso) {
                is ManualControlValue.Manual -> camera.setISO(iso.value.toInt())
                ManualControlValue.Auto -> camera.setAutoISO()
            }
            when (val exposure = restorePlan.exposureNs) {
                is ManualControlValue.Manual -> camera.setExposure(exposure.value.toLong())
                ManualControlValue.Auto -> camera.setAutoExposure()
            }
        }
        when (val whiteBalance = restorePlan.whiteBalanceKelvin) {
            is ManualControlValue.Manual -> camera.setWB(whiteBalance.value.toInt(), prefs.lastWBTint)
            ManualControlValue.Auto -> camera.setAutoWB()
        }
        when (val focus = restorePlan.focusDistance) {
            is ManualControlValue.Manual -> applyManualFocusRuntime(focus.toFloat())
            ManualControlValue.Auto -> applyAutomaticFocusRuntime()
        }
        val ev = (restorePlan.exposureCompensation as? ManualControlValue.Manual)?.toInt() ?: 0
        camera.setEV(ev)
        updateShutterRuleGuidance()
    }

    private fun applyTemporaryAutomaticControlsToCamera() {
        unlockFocusAndExposure()
        if (camera.supportsManualSensor) {
            camera.setAutoISO()
            camera.setAutoExposure()
        }
        camera.setAutoWB()
        applyAutomaticFocusRuntime()
        camera.setEV(0)
        updateShutterRuleGuidance()
    }

    private fun supportedVideoSize(
        preferred: Size,
        codec: GpuVideoCodec = gpuRecording.videoCodec
    ): Size = resolveRecordingSelection(preferred, codec).size

    private fun resolveRecordingSelection(
        preferred: Size,
        preferredCodec: GpuVideoCodec = gpuRecording.videoCodec,
        explicitResolutionSelection: Boolean = recordingResolutionSelectionAuthority.owns(preferred),
        requestedFps: Int = prefs.videoFps
    ): ResolvedRecordingSelection {
        val graphContract = activeVideoPipelineContract()
        val parityPlan = activeRecordingPipelineParityPlan(graphContract)
        val normalizedPreferred = preferred.normalizeForOrientation()
        val availableSizes = availableVideoSizes()
        val pixelArraySize = camera.pixelArraySize
        val maximumRawSize = camera.maxRawSize
        val fallbackSensorSize = camera.fullSensorSize()
        val selectedAspectRatio = selectedRecordingAspectRatio()
        val av1Supported = supportsAv1Fast()
        val resolvedFps = resolveContractRequestedFps(requestedFps)
        val selectedRawStream = if (
            parityPlan.rawRequestedForSessionPlan || graphContract.cinemaMode == CinemaVideoMode.RAW
        ) {
            resolveRawCaptureTier().rawSize?.let { Size(it.width, it.height) }
        } else null
        val request = CameraRecordingSelectionRequest(
            preferred = normalizedPreferred,
            preferredCodec = preferredCodec,
            availableSizes = availableSizes,
            pixelArraySize = pixelArraySize,
            maxRawSize = maximumRawSize,
            fallbackSensorSize = fallbackSensorSize,
            uiMode = _uiMode.value,
            selectedOutputAspectRatio = selectedAspectRatio,
            explicitResolutionSelection = explicitResolutionSelection,
            cinemaMode = graphContract.cinemaMode,
            rawEnabled = parityPlan.rawRequestedForSessionPlan,
            rawSupported = camera.supportsRaw,
            rawStreamSizes = camera.rawSizes,
            selectedRawStreamSize = selectedRawStream,
            av1Supported = av1Supported,
            hdrRequested = parityPlan.hdrRequestedForSessionPlan,
            tenBitRequested = parityPlan.tenBitRequestedForSessionPlan,
            fps = resolvedFps,
            normalizeRecordingRequest = { size -> size.normalizeForOrientation() },
            resolveSupportedSize = { size, codec, fps ->
                gpuRecording.resolveSupportedSize(size, codec, fps)
            }
        )
        val fingerprint = ProcessedRecordingSelectionFingerprint(
            cameraId = camera.currentCameraId,
            requestedWidth = normalizedPreferred.width,
            requestedHeight = normalizedPreferred.height,
            preferredCodec = preferredCodec,
            availableSizeSignature = availableSizes.map { size ->
                ProcessedRecordingSelectionFingerprint.sizeSignature(size.width, size.height)
            },
            pixelArraySizeSignature = pixelArraySize?.let { size ->
                ProcessedRecordingSelectionFingerprint.sizeSignature(size.width, size.height)
            },
            maximumRawSizeSignature = maximumRawSize?.let { size ->
                ProcessedRecordingSelectionFingerprint.sizeSignature(size.width, size.height)
            },
            fallbackSensorSizeSignature = ProcessedRecordingSelectionFingerprint.sizeSignature(
                fallbackSensorSize.width,
                fallbackSensorSize.height
            ),
            uiMode = request.uiMode,
            cinemaMode = request.cinemaMode,
            rawEnabled = request.rawEnabled,
            selectedOutputAspectRatioBits = selectedAspectRatio?.toRawBits(),
            explicitResolutionSelection = explicitResolutionSelection,
            rawSupported = request.rawSupported,
            av1Supported = av1Supported,
            av1CapabilityResolved = av1SupportResolved,
            cachedTenBitSupport = gpuRecording.cachedSupports10Bit(preferredCodec),
            hdrRequested = request.hdrRequested,
            tenBitRequested = request.tenBitRequested,
            fps = resolvedFps,
            highEndEnabled = HighEndQualityPolicy.enabled,
            strictHighEndOnly = HighEndQualityPolicy.strictHighEndOnly,
            fullSensorForced = HighEndQualityPolicy.isFullSensorForced(),
            deviceBuildFingerprint = Build.FINGERPRINT.orEmpty()
        )
        val lookup = processedRecordingSelectionCache.resolve(
            fingerprint = fingerprint,
            processedMode = !request.rawEnabled && request.cinemaMode != CinemaVideoMode.RAW,
            reuseAllowed = modeSwitchCriticalDepth.get() > 0 || modeSwitchFlushInProgress,
            resolver = { CameraRecordingSelectionCoordinator.resolve(request) }
        )
        if (lookup.cacheHit) {
            Log.i(
                "CameraViewModel",
                "Processed recording selection cache hit during mode switch: " +
                    "camera=${fingerprint.cameraId} requested=${fingerprint.requestedWidth}x" +
                    "${fingerprint.requestedHeight} fps=${fingerprint.fps} codec=${fingerprint.preferredCodec}"
            )
        }
        return lookup.selection
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

    /**
     * Fail closed before a processed-video size/FPS setting mutates preferences or runtime state.
     * The camera cadence and a hardware HEVC Main10 encoder with P010 input must both accept the
     * exact pair. Unsupported requests remain visible through [settingsRejection] instead of being
     * silently relabelled as a lower cadence or resolution.
     */
    private fun admitExactProcessedRecordingSizeRate(
        commit: SettingsCommit,
        source: String
    ): Boolean {
        if (_uiMode.value != AppMode.VIDEO || commit.mode == CinemaVideoMode.RAW) return true
        val size = Size(commit.width, commit.height).normalizeForOrientation()
        val cameraAccepted = camera.supportsExactRecordingFps(size, commit.fps)
        val encoderAccepted = RecordingCodecSupportProbe.supportsHevcMain10SizeAndRate(
            width = size.width,
            height = size.height,
            fps = commit.fps
        )
        if (cameraAccepted && encoderAccepted) return true
        val unavailable = buildList {
            if (!cameraAccepted) add("camera cadence")
            if (!encoderAccepted) add("hardware HEVC Main10/P010 encoder")
        }.joinToString(separator = " and ")
        return rejectSettingsRequest(
            ruleId = "EXACT_MAIN10_SIZE_RATE_UNSUPPORTED",
            reason = "${size.width}x${size.height} at ${commit.fps} FPS is not accepted by the $unavailable",
            source = source,
            commit = commit
        )
    }

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

    private fun activeDeliveryContractCacheKey(
        requestedHdrMode: String = _videoHdrMode.value,
        characteristics: CameraCharacteristics? = camera.characteristics
    ): ActiveDeliveryContractCacheKey {
        return ActiveDeliveryContractCacheKey(
            appMode = _uiMode.value,
            cinemaMode = _cinemaVideoMode.value,
            rawVideoActive = isRawVideoModeActive(),
            logEnabled = _logOn.value,
            requestedHdrMode = requestedHdrMode,
            preferredColorSpace = _videoColorSpace.value,
            tenBitActive = gpuRecording.tenBitEnabled || resolveVideoRecordingTenBitRequested(
                cinemaMode = _cinemaVideoMode.value,
                hdrMode = requestedHdrMode
            ),
            cameraId = camera.currentCameraId,
            characteristicsIdentity = System.identityHashCode(characteristics),
            gpuTenBitSupported = supports10BitWithoutCodecProbeBlocking(),
            width = recording.width,
            height = recording.height,
            fps = recording.fps
        )
    }

    private fun activeVideoFormatDeliveryState(
        requestedHdrMode: String = _videoHdrMode.value
    ): VideoFormatDeliveryState {
        val characteristics = camera.characteristics
        val key = activeDeliveryContractCacheKey(requestedHdrMode, characteristics)
        return resolveVideoFormatDeliveryState(key, characteristics)
    }

    private fun resolveVideoFormatDeliveryState(
        key: ActiveDeliveryContractCacheKey,
        characteristics: CameraCharacteristics?
    ): VideoFormatDeliveryState {
        return deliveryContractCache.resolve(key) {
            VideoFormatDeliveryPolicy.resolve(
                uiMode = key.appMode,
                cinemaMode = key.cinemaMode,
                rawVideoActive = key.rawVideoActive,
                logEnabled = key.logEnabled,
                requestedHdrMode = key.requestedHdrMode,
                preferredColorSpace = key.preferredColorSpace,
                tenBitActive = key.tenBitActive,
                characteristics = characteristics,
                gpu10BitSupported = key.gpuTenBitSupported,
                width = key.width,
                height = key.height,
                fps = key.fps
            )
        }
    }

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
        val sensorLinearSource = rawVideoActive || isSensorLinearNdrMode(requestedHdrMode)
        val sourceSize = if (sensorLinearSource) {
            camera.fullSensorSize().normalizeForOrientation()
        } else {
            preferredVideoSourceSize()
        }
        val gpuTenBitSupported = supports10BitWithoutCodecProbeBlocking()
        val request = VideoPipelineGraphRequest(
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
                gpu10BitSupported = gpuTenBitSupported,
                hardwareCapabilities = VideoModePipelineContractPolicy.resolveHardwareCapabilities(
                    characteristics = camera.characteristics,
                    gpu10BitSupported = gpuTenBitSupported
                ),
                physicalCameraGeometryIdentity = activePhysicalCameraGeometryIdentity(),
                sourceWidth = sourceSize.width,
                sourceHeight = sourceSize.height
            )
        return videoPipelineContractCache.resolve(request) {
            VideoPipelineGraphController.resolve(request)
        }
    }

    private fun activePhysicalCameraGeometryIdentity(): String {
        val characteristics = camera.characteristics
        val cameraId = camera.currentCameraId
        return physicalCameraIdentityCache.resolve(characteristics, cameraId)
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
            supports10BitWithoutCodecProbeBlocking(GpuVideoCodec.HEVC)

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
            _cinemaVideoMode.value.name,
            _videoDetailProfile.value.name
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
        val lutSnapshot = nativePreviewLutState.get()
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
            lutSnapshot.enabled.toString(),
            lutSnapshot.size.toString(),
            colorScience,
            _colorProfile.value
        ).joinToString(separator = ":")
        val recordingLutSignature = listOf(
            "lut",
            lutSnapshot.enabled.toString(),
            lutSnapshot.size.toString(),
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
        // CameraController's generation is the authoritative Camera2 session identity carried by
        // every capture-result callback. A recording-local elapsed-time token cannot be compared to
        // that identity: doing so rejects every frame before it reaches the lineage accumulator.
        val generation = camera.sessionGeneration.get()
        camera2FrameLineage.begin(generation, parityPlan.feedPath, parityPlan.paritySignature)
    }

    /**
     * Recording setup can legitimately replace the pre-armed Camera2 session after the shutter is
     * pressed. Capture callbacks carry the replacement generation, so retaining the pre-arm generation
     * would reject every real recording frame. Rebase only the observational certificate; recording,
     * preview, and encoder ownership are untouched. Any evidence from the retired generation is counted
     * explicitly instead of being mixed into the new session's strict certificate.
     */
    private fun rebaseCamera2FrameLineageSessionIfActive(generation: Long) {
        camera2FrameLineage.rebaseIfActive(generation)
    }

    private fun freezeCamera2FrameLineageSession() {
        camera2FrameLineage.freeze()
    }

    /**
     * Publishes the terminal, frozen Camera2 lineage into the description consumed by encoder
     * finalization. Recording-start metadata is intentionally provisional: the strict lineage
     * window cannot be complete until real preview and encoder submissions have been observed.
     * Refresh while the encoder surface and active feed identity are still intact so final evidence
     * describes this clip rather than the short startup-validation window.
     */
    private fun refreshRecordingSceneMetadataForFinalization() {
        gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata(
            encoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight),
            selection = lastRecordingSelection,
            scene = pipeline.sceneAnalysis.value,
            recommendation = pipeline.captureRecommendation.value
        )
    }

    /**
     * Closes the recording-monitor admission epoch and proves that every accepted preview token has
     * crossed EGL, native acknowledgement, and lineage publication before the lineage certificate is
     * frozen. A monitor failure is a clip-quality failure even when ownership retired safely: the last
     * encoded frame cannot be retried, silently omitted from preview evidence, or certified as healthy.
     */
    private fun drainSensorLinearNdrRecordingPreviewBoundary(
        boundary: String
    ): SensorLinearNdrPreviewStopBoundary {
        var drainFailure: Throwable? = null
        val quiesced = try {
            vulkanRuntime.drainSensorLinearNdrRecordingPreview(requireHealthy = false)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            drainFailure = failure
            false
        }
        val snapshot = vulkanRuntime.sensorLinearNdrRecordingPreviewWorkerSnapshot()
        val defect = when {
            drainFailure != null ->
                "preview drain threw ${drainFailure.javaClass.simpleName}: ${drainFailure.message.orEmpty()}"
            !quiesced -> "preview worker/native token ring did not prove quiescence"
            snapshot.outstandingTasks != 0 ->
                "preview worker retained ${snapshot.outstandingTasks} accepted task(s)"
            snapshot.lastFailure != null -> "preview worker failed: ${snapshot.lastFailure}"
            else -> null
        }
        if (defect != null) {
            val reason = "NDR recording preview boundary '$boundary' failed: $defect"
            sensorLinearNdrRecordingFailure.compareAndSet(
                null,
                SensorLinearNdrRecordingFailure(reason = reason, cause = drainFailure)
            )
            Log.e("CameraViewModel", reason, drainFailure)
        }
        return SensorLinearNdrPreviewStopBoundary(
            quiesced = quiesced && snapshot.outstandingTasks == 0,
            failureReason = defect
        )
    }

    private fun beginSensorPipelineSession(generation: Long) {
        SensorPipelineIntegration.beginSession(generation, camera, pipeline)
    }

    private fun recordCamera2FrameLineageCaptureResult(
        result: TotalCaptureResult,
        captureSessionGeneration: Long
    ) {
        camera2FrameLineage.recordCaptureResult(result, captureSessionGeneration)
    }

    private fun drainAndShutdownCamera2LineageForTeardown(timeoutMs: Long = 3_000L): Boolean {
        return camera2FrameLineage.drainAndShutdownForTeardown(timeoutMs)
    }

    private fun recordCamera2FrameLineageSubmission(
        submission: VulkanFrameLineageSubmission
    ) {
        camera2FrameLineage.recordSubmission(submission, camera.sessionGeneration.get())
    }

    private fun recordNpv10FrameLineageEncoderSubmission(sensorTimestampNs: Long) {
        camera2FrameLineage.recordEncoderSubmission(sensorTimestampNs)
    }

    private fun recordPreviewDisplayCaptureLineage(
        sensorTimestampNs: Long,
        sessionGeneration: Long
    ) {
        previewDisplayPublishGate.runExclusive {
            if (sessionGeneration != camera.sessionGeneration.get()) return@runExclusive
            recordPreviewPixelRouteStage(
                stage = PreviewPixelRouteStage.CAMERA2_CAPTURE,
                sensorTimestampNs = sensorTimestampNs
            )
            val acceptedPresentation = previewDisplayPresentation.recordCapture(
                sensorTimestampNs = sensorTimestampNs,
                sessionGeneration = sessionGeneration
            )
            acceptedPresentation?.let(::publishPreviewDisplayPresent)
        }
    }

    private fun onPreviewDisplaySessionGenerationAdvanced() {
        previewDisplayPublishGate.runExclusive {
            previewDisplayPresentation.onSessionGenerationAdvanced()
            previewPixelRouteTelemetry.reset(camera.sessionGeneration.get())
        }
    }

    private fun updatePreviewDisplayPresentSurface(surface: Surface?) =
        previewDisplayPresentation.updateSurface(surface)

    fun previewDisplayPresentSnapshot(): PreviewDisplayPresentSnapshot =
        previewDisplayPresentation.snapshot()

    private fun recordCompositedPreviewFrameLineageSubmission(
        sensorTimestampNs: Long,
        outputSurface: Surface,
        visibleRouteStage: PreviewPixelRouteStage,
        presentationTimestampNs: Long
    ) {
        if (sensorTimestampNs <= 0L) return
        val event = previewDisplayPresentation.recordCompositedPresentation(
            sensorTimestampNs = sensorTimestampNs,
            outputSurface = outputSurface,
            presentationTimestampNs = presentationTimestampNs,
            visibleRouteStage = visibleRouteStage
        )
        event?.let(::publishPreviewDisplayPresent)
    }

    private fun recordPreviewPixelRouteStage(
        stage: PreviewPixelRouteStage,
        sensorTimestampNs: Long,
        observedElapsedNs: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        val observation = previewPixelRouteTelemetry.record(
            stage = stage,
            sensorTimestampNs = sensorTimestampNs,
            observedElapsedNs = observedElapsedNs
        ) ?: return
        if (observation.sensorGapNs > 60_000_000L || observation.observedGapNs > 60_000_000L) {
            Log.w(
                "PreviewPixelRoute",
                "PREVIEW_PIXEL_GAP stage=${stage.name} count=${observation.snapshot.count} " +
                    "sensorGapMs=${observation.sensorGapNs / 1_000_000.0} " +
                    "observedGapMs=${observation.observedGapNs / 1_000_000.0} " +
                    "sensorTimestampNs=$sensorTimestampNs observedElapsedNs=$observedElapsedNs"
            )
        }
        if (!observation.logMilestone) return
        val snapshot = observation.snapshot
        Log.i(
            "PreviewPixelRoute",
            "PREVIEW_PIXEL_ROUTE stage=${stage.name} count=${snapshot.count} " +
                "measuredFps=${snapshot.measuredFps} " +
                "duplicates=${snapshot.duplicateSensorTimestamps} " +
                "regressions=${snapshot.regressedSensorTimestamps} " +
                "maxSensorGapMs=${snapshot.maxSensorGapNs / 1_000_000.0} " +
                "sensorGapsOver60Ms=${snapshot.sensorGapsOver60Ms} " +
                "observedDuplicates=${snapshot.duplicateObservedTimestamps} " +
                "observedRegressions=${snapshot.regressedObservedTimestamps} " +
                "maxObservedGapMs=${snapshot.maxObservedGapNs / 1_000_000.0} " +
                "observedGapsOver60Ms=${snapshot.observedGapsOver60Ms}"
        )
    }

    private fun publishPreviewDisplayPresent(accepted: AcceptedPreviewDisplayPresent) {
        // This callback is emitted after a successful visible-window swap, not merely after input or
        // preview submission. Both diagnostics and UI readiness therefore share the same ground truth.
        val event = accepted.event
        previewDisplayPublishGate.publishIfAccepted(
            event = event,
            snapshot = previewDisplayPresentation::snapshot
        ) {
            recordPreviewPixelRouteStage(
                stage = accepted.visibleRouteStage,
                sensorTimestampNs = event.sensorTimestampNs,
                observedElapsedNs = event.presentationTimestampNs
            )
            pipeline.onDisplayedFrame(event.sensorTimestampNs)
            _previewDisplayPresentEvents.tryEmit(event)
            camera2FrameLineage.recordPreviewPresentation(
                event.sensorTimestampNs,
                event.presentationTimestampNs
            )
            resumeRecordingPrearmAfterModePresentation()
        }
    }

    /**
     * Starts codec pre-arm only after the replacement mode has produced a verified visible frame.
     * Reader topology remains part of the atomic mode transaction; delaying only MediaCodec
     * allocation prevents it from competing with Camera2 session creation and first-frame delivery.
     */
    private fun resumeRecordingPrearmAfterModePresentation() {
        if (!recordingSurfacePrearmAfterModePresentation.get() ||
            modeSwitchCriticalDepth.get() > 0 ||
            camera.sessionRefreshPending.get()
        ) return
        if (!recordingSurfacePrearmAfterModePresentation.compareAndSet(true, false)) return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (viewModelTeardownStarted.get() || _uiMode.value != AppMode.VIDEO) return@launch
            requestRecordingSurfacePrearm(forceSessionRefresh = false)
        }
    }

    private fun camera2FrameLineageRuntimeMetadata(): Map<String, String> =
        camera2FrameLineage.runtimeMetadata() + previewPixelRouteTelemetry.runtimeMetadata()

    private fun activePixelProcessingRealityReport(
        parityPlan: RecordingPipelineParityPlan = activeRecordingPipelineParityPlan()
    ): PixelProcessingRealityReport {
        val renderContract = activeVulkanPreviewRenderContract()
        val importStats = vulkanImportStats.value
        val validationStats = importStats.toProcessedGpuRecordingValidationStats()
        val directPreviewSwap =
            previewPixelRouteTelemetry.snapshot(PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP)
        val routedPreviewSwap =
            previewPixelRouteTelemetry.snapshot(PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP)
        val previewFramePresented =
            (nativeVisiblePreviewActive && importStats.previewSurfaceBound &&
                importStats.sessionImportedFrames > 0L) ||
                directPreviewSwap.count > 0L ||
                routedPreviewSwap.count > 0L
        val recordingFramePresented =
            validationStats.frameImportEnabled &&
                validationStats.encoderSurfaceBound &&
                validationStats.importedFrames > 0L &&
                validationStats.offscreenPresentationTimeSet
        return PixelProcessingRealityPolicy.resolve(
            PixelProcessingRealityRequest(
                parityPlan = parityPlan,
                nativePreviewActive = nativeVisiblePreviewActive,
                vulkanRuntimeSupported = vulkanSupported.value,
                aiFrameProcessingActive = hasObservedAiFrameProcessing(),
                multiFrameVideoProcessingActive = false,
                previewGpuFramePresentationValidated = previewFramePresented,
                recordingGpuFrameImportValidated =
                    validationStats.frameImportEnabled &&
                        validationStats.encoderSurfaceBound &&
                        validationStats.importedFrames > 0L,
                recordingGpuFramePresentationValidated = recordingFramePresented,
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

    private fun activeVideoModePipelineContract(): VideoModePipelineContract {
        val key = activeDeliveryContractCacheKey()
        return videoModeContractCache.resolve(key) {
            VideoModePipelineContractPolicy.resolve(
                uiMode = key.appMode,
                cinemaMode = key.cinemaMode,
                rawVideoActive = key.rawVideoActive,
                logEnabled = key.logEnabled,
                requestedHdrMode = key.requestedHdrMode,
                preferredColorSpace = key.preferredColorSpace,
                tenBitActive = key.tenBitActive,
                characteristics = camera.characteristics,
                gpu10BitSupported = key.gpuTenBitSupported,
                width = key.width,
                height = key.height,
                fps = key.fps
            )
        }
    }

    private fun resolveRequestedVideoModeAdmission(
        cinemaMode: CinemaVideoMode,
        requestedHdrMode: String = _videoHdrMode.value,
        preferredColorSpace: String = _videoColorSpace.value
    ): VideoModePipelineContract =
        VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = cinemaMode,
            rawVideoActive = cinemaMode == CinemaVideoMode.RAW,
            logEnabled = cinemaMode == CinemaVideoMode.LOG,
            requestedHdrMode = requestedHdrMode,
            preferredColorSpace = preferredColorSpace,
            tenBitActive = cinemaMode != CinemaVideoMode.RAW,
            characteristics = camera.characteristics,
            gpu10BitSupported = supports10BitWithoutCodecProbeBlocking(),
            width = recording.width,
            height = recording.height,
            fps = recording.fps
        )

    private fun rejectVideoModeAdmission(
        contract: VideoModePipelineContract,
        source: String,
        persistRequestedCinemaMode: Boolean = false
    ): Boolean {
        rejectedVideoModeAdmission = contract
        if (persistRequestedCinemaMode) {
            prefs.videoMode = contract.requestedCinemaMode.name
        }
        val message = contract.admissionFailureMessage
            ?: "Requested ${contract.requestedCinemaMode.name}/${contract.requestedHdrMode} video mode is unavailable; active capture state was not changed."
        Log.w("CameraViewModel", "Video mode admission rejected source=$source message=$message")
        setHardwareWarning(message)
        return false
    }

    private fun clearRejectedVideoModeAdmission() {
        val previousMessage = rejectedVideoModeAdmission?.admissionFailureMessage
        rejectedVideoModeAdmission = null
        if (previousMessage != null && _uiStateFlow.value.hardwareWarning == previousMessage) {
            setHardwareWarning(null)
        }
    }

    private fun validateActiveVideoModeAdmissionForRecording(): String? {
        if (!StrictPreviewAdmissionPolicy.recordingAllowed(
                _uiMode.value,
                strictPreviewAdmissionState
            )
        ) {
            val failure = strictPreviewOutputFailure
            return if (strictPreviewAdmissionState == StrictPreviewAdmissionState.FAILED) {
                val reason = failure?.failure?.name ?: "UNKNOWN"
                "Strict 10-bit visible preview admission failed ($reason); recording is blocked " +
                    "until the requested preview route is proven without an 8-bit fallback."
            } else {
                "Strict 10-bit visible preview admission is still pending; recording is blocked " +
                    "until a >=10-bit output route is proven active."
            }
        }
        rejectedVideoModeAdmission?.let { rejected ->
            return rejected.admissionFailureMessage
                ?: "The last requested video mode was rejected; select a supported 10-bit mode before recording."
        }
        val activeAdmission = activeVideoModePipelineContract()
        if (!activeAdmission.recordingAllowed) {
            return activeAdmission.admissionFailureMessage
                ?: activeAdmission.validationWarnings.joinToString(separator = " ")
                    .ifBlank { "Strict 10-bit video admission is not satisfied; recording is blocked." }
        }
        return null
    }

    private fun activeVideoPreviewLookState(
        deliveryState: VideoFormatDeliveryState = activeVideoFormatDeliveryState()
    ): VideoFormatPreviewLookState =
        if (VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)) {
            VideoFormatPreviewLookPolicy.resolve(deliveryState)
        } else {
            VideoFormatPreviewLookState()
        }

    private fun activeVulkanPreviewRenderContract(
        requestedHdrMode: String = _videoHdrMode.value
    ): VulkanPreviewRenderContract {
        val characteristics = camera.characteristics
        val deliveryKey = activeDeliveryContractCacheKey(requestedHdrMode, characteristics)
        val deliveryState = resolveVideoFormatDeliveryState(deliveryKey, characteristics)
        val lutSnapshot = nativePreviewLutState.get()
        val previewLookState = activeVideoPreviewLookState(deliveryState)
        val logCurve = if (previewLookState.logEnabled || deliveryState.logVideoActive) {
            NeoLogProfilePolicy.resolve(_colorProfile.value, logEnabled = true).curve
        } else {
            NeoLogCurve.NONE
        }
        val cinemaDebayerActive = resolveRawPreviewPresentationPath() ==
            RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER
        val highLoadPreviewActive = isHighLoadPreviewMode()
        val previewFpsHint = lastPreviewFpsSample.takeIf { it > 0f }
            ?: VIDEO_IDLE_PREVIEW_FPS.toFloat()
        val cameraPreviewHdrTransportMode =
            camera.describeHdrDynamicRangeState().previewTransportMode
        val key = ActiveVulkanContractCacheKey(
            deliveryKey = deliveryKey,
            nativePreviewLutEnabled = lutSnapshot.enabled,
            nativePreviewLutSize = lutSnapshot.size,
            colorProfile = _colorProfile.value,
            cinemaDebayerActive = cinemaDebayerActive,
            highLoadPreviewActive = highLoadPreviewActive,
            previewFpsHint = previewFpsHint,
            cameraPreviewHdrTransportMode = cameraPreviewHdrTransportMode.toString()
        )
        return vulkanContractCache.resolve(key) {
            VulkanPreviewRenderContractPolicy.resolve(
                pipelineContract = deliveryState.pipelineContract,
                previewLookState = previewLookState,
                nativePreviewLutEnabled = lutSnapshot.enabled,
                nativePreviewLutSize = lutSnapshot.size,
                logCurve = logCurve,
                cinemaDebayerActive = cinemaDebayerActive,
                highLoadPreviewActive = highLoadPreviewActive,
                previewFpsHint = previewFpsHint,
                cameraPreviewHdrTransportMode = cameraPreviewHdrTransportMode
            )
        }
    }

    private fun resolveVulkanPreviewRenderContract(
        renderState: VulkanRenderRequestSnapshot,
        deliveryState: VideoFormatDeliveryState
    ): VulkanPreviewRenderContract {
        val previewLookState = if (renderState.videoSurface) {
            VideoFormatPreviewLookPolicy.resolve(deliveryState)
        } else {
            VideoFormatPreviewLookState()
        }
        val logCurve = if (previewLookState.logEnabled || deliveryState.logVideoActive) {
            NeoLogProfilePolicy.resolve(renderState.colorProfile, logEnabled = true).curve
        } else {
            NeoLogCurve.NONE
        }
        val lut = renderState.nativePreviewLut
        val key = ActiveVulkanContractCacheKey(
            deliveryKey = renderState.deliveryKey,
            nativePreviewLutEnabled = lut.enabled,
            nativePreviewLutSize = lut.size,
            colorProfile = renderState.colorProfile,
            cinemaDebayerActive = renderState.cinemaDebayerActive,
            highLoadPreviewActive = renderState.highLoadPreviewActive,
            previewFpsHint = renderState.previewFpsHint,
            cameraPreviewHdrTransportMode = renderState.cameraPreviewHdrTransportMode.toString()
        )
        return vulkanContractCache.resolve(key) {
            VulkanPreviewRenderContractPolicy.resolve(
                pipelineContract = deliveryState.pipelineContract,
                previewLookState = previewLookState,
                nativePreviewLutEnabled = lut.enabled,
                nativePreviewLutSize = lut.size,
                logCurve = logCurve,
                cinemaDebayerActive = renderState.cinemaDebayerActive,
                highLoadPreviewActive = renderState.highLoadPreviewActive,
                previewFpsHint = renderState.previewFpsHint,
                cameraPreviewHdrTransportMode = renderState.cameraPreviewHdrTransportMode
            )
        }
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
        if (modeSwitchCriticalDepth.get() > 0 && !modeSwitchFlushInProgress) {
            modeSwitchEffectiveColorSyncPending = true
            return
        }
        val graphContract = activeVideoPipelineContract()
        val parityPlan = activeRecordingPipelineParityPlan(graphContract)
        val deliveryState = graphContract.deliveryState
        val hdrState = camera.describeHdrDynamicRangeState()
        val projectOwnedPqTransportActive = ProjectOwnedPqRecordingTransportPolicy.isActive(
            sensorLinearNdrActive = isSensorLinearNdrMode(deliveryState.effectiveHdrMode),
            deliveryKind = parityPlan.deliveryKind,
            exactTenBitTransportRequested = parityPlan.tenBitRequestedForSessionPlan,
            encoderHdrMode = parityPlan.encoderHdrMode,
            encoderColorSpace = parityPlan.encoderColorSpace,
            activeHdrMode = deliveryState.effectiveHdrMode,
            activeColorSpace = deliveryState.effectiveColorSpace,
            feedPath = parityPlan.feedPath
        )
        val recordingTransport = when (parityPlan.deliveryKind) {
            VideoDeliveryKind.HDR -> VideoHdrRecordingTransportPolicy.resolve(
                requestedHdrMode = deliveryState.effectiveHdrMode,
                requestedColorSpace = deliveryState.effectiveColorSpace,
                hdrState = hdrState,
                projectOwnedPqTransportActive = projectOwnedPqTransportActive
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
                    "projectOwnedPq=$projectOwnedPqTransportActive " +
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
        if (modeSwitchCriticalDepth.get() > 0 && Looper.myLooper() == Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.Default) {
                logVideoPipelineGraphContract(contract, reason, force)
            }
            return
        }
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
        if (modeSwitchCriticalDepth.get() > 0 && Looper.myLooper() == Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.Default) {
                logRecordingPipelineParityPlan(plan, reason, force)
            }
            return
        }
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
            "previewReal=${report.previewRealStageNames.joinToString(separator = ",")} " +
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
        if (modeSwitchCriticalDepth.get() > 0 && Looper.myLooper() == Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.Default) {
                logCamera2SessionGraphParityPlan(plan, reason, force)
            }
            return
        }
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
        gpuRecording.reportExternalErrorRetainingResources(message)
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

    private val AI_WARMUP_IDLE_POLL_MS = 50L
    private val AI_WARMUP_STABLE_PREVIEW_MS = 500L

    private fun scheduleAiWarmUp(
        delayMs: Long = StartupSmoothnessPolicy.aiWarmupDelayMs(),
        allowDuringStartupAiRamp: Boolean = false
    ) {
        aiWarmupJob?.cancel()
        aiWarmupJob = viewModelScope.launch(Dispatchers.Default) {
            delay(delayMs)
            var stableSinceElapsedMs = 0L
            while (isActive) {
                val recordingCritical =
                    recordingStopRestartLifecycle.isStarting ||
                        recordingStopRestartLifecycle.isStopInProgress ||
                        cameraState.value == CameraState.Recording
                val transitionCritical =
                    modeSwitchCriticalDepth.get() > 0 ||
                        camera.sessionRefreshPending.get() ||
                        recordingSurfacePrearmInFlight.get() ||
                        recordingCritical ||
                        startupFeatureGateActive ||
                        (!allowDuringStartupAiRamp && startupAiRampActive)
                if (transitionCritical) {
                    stableSinceElapsedMs = 0L
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (stableSinceElapsedMs == 0L) stableSinceElapsedMs = now
                    if (now - stableSinceElapsedMs >= AI_WARMUP_STABLE_PREVIEW_MS) break
                }
                delay(AI_WARMUP_IDLE_POLL_MS)
            }
            when (cameraState.value) {
                CameraState.Streaming -> Unit
                else -> return@launch
            }
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
            recordingCodecCapabilityRefresh.emit(Unit)
        }
    }

    private fun supports10BitWithoutCodecProbeBlocking(
        codec: GpuVideoCodec = gpuRecording.videoCodec
    ): Boolean = gpuRecording.cachedSupports10Bit(codec) ?: RecordingCodecCatalog.supports10Bit(codec)

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
        // A persisted recording size is user intent, just like a direct selection in this process.
        // Rehydrate its authority before deferred capability resolution so an independent preview
        // aspect cannot rewrite 3840x2160 to a 4:3 stream during cold-start restoration.
        recordingResolutionSelectionAuthority.restorePersistedIfUnclaimed(startupVideoSize)
        updateModeSwitchStandbyRequestedSize(startupVideoSize)
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
        val startupPipelineAdmission = resolveRequestedVideoModeAdmission(
            cinemaMode = restoredVideoMode.cinemaMode,
            requestedHdrMode = restoredVideoMode.hdrMode,
            preferredColorSpace = normalizedVideoColorSpace
        )
        val startupModeAdmitted = restoredVideoMode.cinemaModeAdmission.accepted &&
            startupPipelineAdmission.admissionState == VideoPipelineAdmissionState.ADMITTED
        val restoredCinemaMode = if (startupModeAdmitted) {
            normalizeNpv10CinemaMode(restoredVideoMode.cinemaMode)
        } else {
            CinemaVideoMode.PRO
        }
        val restoredHdrMode = if (startupModeAdmitted) restoredVideoMode.hdrMode else "OFF"
        val restoredHdrEnabled = startupModeAdmitted && restoredVideoMode.hdrEnabled
        val restoredRawEnabled = startupModeAdmitted && restoredVideoMode.rawEnabled
        val restoredLogEnabled = startupModeAdmitted && restoredVideoMode.logEnabled
        val restoredVideoColorSpace = if (startupModeAdmitted) {
            normalizedVideoColorSpace
        } else {
            startupPipelineAdmission.effectiveColorSpace
        }
        val hasCustomLutPath = !prefs.customLutPath.isNullOrBlank()
        val wantsCustomLut = prefs.lutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true)
        val restoredLutProfile = when {
            wantsCustomLut && !hasCustomLutPath -> "Neutral Film"
            else -> prefs.lutProfile
        }
        val restoredLutEnabled = prefs.lutEnabled &&
            (!restoredLutProfile.equals(CUSTOM_LUT_PROFILE, ignoreCase = true) || hasCustomLutPath)
        val startupVideoFps = SettingsOptionCatalog.selectedFpsForResolution(
            currentFps = resolveContractRequestedFps(prefs.videoFps, restoredCinemaMode),
            size = startupVideoSize
        )

        syncRecordingBitrateMode(prefs.videoBitrateMode)
        recording.width = startupVideoSize.width
        recording.height = startupVideoSize.height
        recording.fps = startupVideoFps
        recording.bitrate = prefs.videoBitrate
        recording.audioSource = desiredAudio
        recording.codec = RecordingCapabilityPolicy.toVideoCodecType(preferredCodec)
        selectedVideoSize = startupVideoSize
        lastRecordingSelection = null

        gpuRecording.videoWidth = startupVideoSize.width
        gpuRecording.videoHeight = startupVideoSize.height
        gpuRecording.videoFps = startupVideoFps
        gpuRecording.videoBitrate = prefs.videoBitrate
        gpuRecording.audioSource = desiredAudio
        gpuRecording.videoCodec = preferredCodec
        gpuRecording.videoColorSpace = restoredVideoColorSpace
        gpuRecording.videoColorProfile = prefs.colorProfile
        gpuRecording.videoLogEnabled = restoredLogEnabled
        gpuRecording.videoHdrMode = restoredHdrMode
        gpuRecording.cinemaMode = restoredCinemaMode
        gpuRecording.rawVideoEnabled = restoredRawEnabled
        gpuRecording.sdrConversionPipelineEnabled = prefs.sdrConversionPipelineEnabled
        if (prefs.videoFps != startupVideoFps) {
            prefs.videoFps = startupVideoFps
            Log.i(
                "CameraViewModel",
                "Normalized persisted recording cadence to ${startupVideoFps}fps for " +
                    "${startupVideoSize.width}x${startupVideoSize.height}"
            )
        }
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
        camera.maximumResolutionStillEnabled = prefs.maxPhotoResolutionEnabled
        camera.setNoiseReductionEnabled(effectiveNoiseReductionEnabled())
        camera.setStabilizationMode(prefs.stabilizationMode)

        advanceVulkanRenderProfileGeneration("startup-preferences-begin")
        _rawOn.value = restoredRawEnabled
        _gridMode.value = gridModeFromPref(prefs.gridMode)
        _logOn.value = restoredLogEnabled
        _videoColorSpace.value = restoredVideoColorSpace
        advanceVulkanRenderProfileGeneration(
            reason = "startup-preferences-commit",
            committedHdrMode = restoredHdrMode
        )
        // Normal processed recording stays active; RAW remains an independent NPRAW sensor path.
        _cinemaVideoMode.value = restoredCinemaMode
        _rawVideoCompression.value = normalizeRawCompressionForMode(
            RawCompressionMode.fromName(prefs.rawVideoCompression),
            restoredCinemaMode
        )
        _rawResolutionMode.value = prefs.rawResolutionMode
        syncRecordingBitrateMode(prefs.videoBitrateMode)
        _videoHdrOn.value = restoredHdrEnabled
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
            rawEnabled = restoredRawEnabled,
            logEnabled = restoredLogEnabled,
            hdrEnabled = restoredHdrEnabled,
            aspect = _previewAspect.value,
            colorProfile = _colorProfile.value,
            lutEnabled = _lutEnabled.value,
            lutProfile = _lutProfile.value,
            colorSpace = _videoColorSpace.value,
            hdrMode = restoredHdrMode,
            cinemaMode = restoredCinemaMode,
            rawCompression = _rawVideoCompression.value
        )
        applyCinemaModeSelection(restoredCinemaMode, persist = false)
        if (!startupModeAdmitted) {
            rejectVideoModeAdmission(
                contract = startupPipelineAdmission,
                source = "startup-restore",
                persistRequestedCinemaMode = true
            )
        }
        enforceVideoMeteringDefaultIfNeeded()

        applyColorPipelineState(
            colorProfile = prefs.colorProfile,
            lutEnabled = restoredLutEnabled,
            lutProfile = restoredLutProfile,
            logEnabled = _logOn.value
        )
        syncCameraPreviewLookControls()
        syncPreviewAnalysisFeed()
        applyAiTrackingPreferences()
        configureModeSwitchStandbyAnalysisOutput()
        syncGeometryTargets()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
        publishStartupPreferencesApplied()
    }

    private fun publishStartupPreferencesApplied() {
        startupPreferencesApplied = true
        startupPreferencesReady.complete(Unit)
        runOnMainIfNeeded {
            val surface = previewSurface
            if (surface != null && runCatching { surface.isValid }.getOrDefault(false)) {
                // First and only cold-start preparation now uses the persisted aspect, NDR profile,
                // cinema mode and detail policy. No temporary 4:3/NDR10+ EGL graph is constructed.
                bindPreviewSurface(surface)
            }
            if (openCameraAfterStartupPreferences) {
                openCameraAfterStartupPreferences = false
                openCamera()
            }
        }
    }

    private fun completeStartupFastPath() {
        if (!startupFeatureGateActive) return
        startupFeatureGateActive = false
        syncPreviewAnalysisFeed()
        startupAiRampActive = true
        startupDeferredRestoreJob?.cancel()
        scheduleStartupNativePreviewRelease()
        scheduleAiWarmUp(allowDuringStartupAiRamp = true)
        val restoreSignature = currentPreferencesRefreshSignature()
        val restoreLease = preferencesRefreshOwnership.begin()
        startupDeferredRestoreJob = viewModelScope.launch(Dispatchers.Default) {
            if (!applyPreferences(signature = restoreSignature, force = true, lease = restoreLease)) {
                return@launch
            }
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
                if (persistentPreviewPipelineActive || persistentPreviewLifecycle.owner != null) {
                    disablePersistentPreviewPipeline(releaseInputs = true)
                } else {
                    releaseHdr10PqPreviewCompositorIfActive()
                }
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
        syncPreviewViewport(previewView)
        lastAppliedHighLoadPreviewMode = null
        syncPreviewPerformanceMode()
    }

    fun onSurfaceReady(surface: Surface?) {
        Log.d("CameraViewModel", "onSurfaceReady: $surface")
        if (surface == null) {
            handlePreviewSurfaceUnavailable()
            return
        }
        if (previewSurfaceRetirement.deferReplacement(surface)) {
            Log.i(
                "CameraViewModel",
                "Deferring replacement preview bind until Camera2 releases the retired surface"
            )
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
            handlePreviewSurfaceUnavailable()
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
        if (settingsCameraLifecycle.isSuspended()) {
            Log.d("CameraViewModel", "openCamera ignored while Settings owns the opaque foreground")
            return
        }
        if (!startupPreferencesApplied) {
            openCameraAfterStartupPreferences = true
            startStartupBootstrap()
            Log.i("CameraViewModel", "Camera open waiting for persisted startup authority")
            return
        }
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
        // A foreground Surface can begin a new persistent-preview preparation while the previous
        // Activity's asynchronous Camera2 close still owns the compositor teardown. Always join that
        // close first. Its completion is allowed to retire the old EGL producers and may invalidate
        // the provisional preparation; the joined retry below will bind a fresh generation.
        if (persistentPreviewPreparation.inProgress) {
            openCameraAfterPersistentPreviewPreparation = true
            Log.i("CameraViewModel", "Camera open waiting for off-main persistent preview preparation")
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
        val settingsFastResume = settingsCameraLifecycle.consumeFastResume()
        lastAppliedAiTrackingState = null
        lastAppliedHighLoadPreviewMode = null
        startStartupBootstrap()
        if (surface == null) {
            Log.w("CameraViewModel", "openCamera skipped: previewSurface is NULL")
            return
        }
        startupCoordinator.prepareOpen(settingsFastResume)
        startupDeferredRestoreJob?.cancel()
        startupDirectPreviewActive = !settingsFastResume
        startupFeatureGateActive = !settingsFastResume
        startupAiRampActive = !settingsFastResume
        if (settingsFastResume) Log.i("CameraViewModel", "Settings fast resume bypassing cold-start ramp")
        bindPreviewSurface(surface)
        // bindPreviewSurface() can start the persistent SDR/PQ router for a newly resumed Surface.
        // Do not race Camera2 session creation against that off-main EGL transaction. Its completion
        // callback re-enters openCamera() after the exact current-generation outputs are registered.
        if (persistentPreviewPreparation.inProgress) {
            openCameraAfterPersistentPreviewPreparation = true
            Log.i("CameraViewModel", "Camera open waiting for preview preparation started during bind")
            return
        }
        settingsPreviewPresentationRetained = false

        syncGeometryTargets()
        configureModeSwitchStandbyAnalysisOutput()
        camera.startBackground()
        pipeline.start()
        primeNpv10CaptureGraphForOpen()
        if (VideoColdOpenHardwareImportSessionFlag.enabled) {
            primeOffscreenHardwareImportSessionGraph(reason = "camera-open")
        }
        cameraOpenRequestInFlight = true
        
        com.neoproc.neoproccamx.utils.BootTrace.mark("camera.open.requested")
        viewModelScope.launch(Dispatchers.Default) {
            camera.openCamera(
                onReady  = {
                    com.neoproc.neoproccamx.utils.BootTrace.mark("camera.session.ready")
                    cameraOpenRequestInFlight = false
                    if (settingsCameraLifecycle.isSuspended()) {
                        // A settings tap can race a CameraManager open callback. The close job owns the
                        // definitive teardown; do not schedule AF/encoder/session work behind Settings.
                        Log.i("CameraViewModel", "Ignoring late camera-ready work during Settings suspension")
                    } else {
                        scheduleCenterAutoPrime()
                        if (VideoColdOpenHardwareImportSessionFlag.enabled) {
                            Log.i(
                                "CameraViewModel",
                                "Cold-open hardware-import-in-session prearm enabled; exact current-session " +
                                    "membership will gate any follow-up refresh"
                            )
                        }
                        requestRecordingSurfacePrearm(
                            forceSessionRefresh = !settingsFastResume ||
                                (_uiMode.value == AppMode.VIDEO && !isRecordingSurfacePrearmedForDebugStart())
                        )
                        scheduleRecordingSurfacePrearmRetune()
                        // Capabilities are populated now (camera open / switch / lens switch / app start);
                        // recompute the RAW-tier indicator without touching the capture session.
                        refreshRawCaptureTierStatus()
                    }
                },
                onError  = {
                    cameraOpenRequestInFlight = false
                    /* cameraState.Error is already set */
                }
            )
        }
    }

    fun resumeCameraForForeground() {
        if (settingsCameraLifecycle.isSuspended()) {
            Log.d("CameraViewModel", "foreground camera resume deferred while Settings is visible")
            return
        }
        if (recordingPreviewWindow.retained && isRecordingOrStopping()) {
            closeCameraAfterRecordingStop = false
            previewViewRef?.getPreviewSurface()?.takeIf { it.isValid }?.let {
                recordingPreviewWindow.restore(it)
            }
            return
        }
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
            recordingOrStopping = recordingOrStopping,
            previewFresh = foregroundRecovery.isPreviewFresh()
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
            decision.shouldVerifyActiveSession -> foregroundRecovery.scheduleHealthCheck()
            decision.shouldOpenNow -> openCamera()
            surface == null -> Log.w("CameraViewModel", "foreground resume skipped: previewSurface is NULL")
            else -> Unit
        }
    }

    private fun canRetainPreviewPresentationForSettings(): Boolean =
        previewSurface != null &&
            !nativeVisiblePreviewActive &&
            !nativePreviewHandoffPending &&
            !glPreviewSurfaceReleasePending &&
            !hdr10PqPreviewEngageInProgress &&
            !glPreviewCompositorEngageInProgress &&
            !glPreviewInputPrepareInProgress &&
            persistentPreviewLifecycle.targetOwner == null &&
            glPreviewHandoffBindJob?.isActive != true &&
            glPreviewHandoffFallbackJob?.isActive != true &&
            glPreviewBindingResyncJob?.isActive != true &&
            (
                persistentPreviewPipelineActive ||
                    glPreviewCompositorActive ||
                    hdr10PqPreviewCompositorActive
                )

    private fun releasePreviewPresentationResources() {
        if (persistentPreviewPipelineActive ||
            persistentPreviewLifecycle.owner != null ||
            persistentPreviewPreparation.inProgress
        ) {
            disablePersistentPreviewPipeline(releaseInputs = true)
        }
        if (glPreviewCompositorActive) {
            previewGlCompositor.releaseAsync()
            glPreviewCompositorActive = false
        }
        releaseHdr10PqPreviewCompositorIfActive()
        settingsPreviewPresentationRetained = false
    }

    private fun prepareCameraCloseState(retainPreviewPresentationForSettings: Boolean = false): Boolean {
        recordingPreviewWindow.reset()
        foregroundRecovery.cancel()
        closeCameraAfterRecordingStop = false
        cameraOpenRequestInFlight = false
        aiWarmupJob?.cancel()
        aiFocusResumeJob?.cancel()
        autoFocusPrimeJob?.cancel()
        startupCoordinator.cancelSessionJobs()
        startupDeferredRestoreJob?.cancel()
        persistentPreviewPreparation.invalidate(resetFailure = true)
        resetStrictPreviewAdmissionForLifecycle()
        strictPreviewOutputFailure = null
        openCameraAfterPersistentPreviewPreparation = false
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
        lastPreviewFpsSample = 0f
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        directPreviewSurfaceActive = false
        applyRecordingOutputAspectOverride(null)
        previewViewRef?.setNativeCompositorActive(false)
        vulkanRuntime.setFrameImportEnabled(false)
        vulkanRuntime.setPreviewSurface(null)
        val retainWarmPresentation =
            retainPreviewPresentationForSettings && canRetainPreviewPresentationForSettings()
        if (retainWarmPresentation) {
            // Camera2, readers, analysis, AI dispatch, and frame import are still fully stopped. Stable
            // EGL contexts/SurfaceTextures are idle with no producer, so retaining them consumes no frame
            // processing while avoiding shader/context recreation when Settings is dismissed.
            if (persistentPreviewPipelineActive) {
                persistentPreviewPresentation.update(visibleOwner = null)
            }
            settingsPreviewPresentationRetained = true
            Log.i("CameraViewModel", "Settings camera close retaining idle preview presentation contexts")
        } else {
            if (persistentPreviewPipelineActive || persistentPreviewLifecycle.owner != null) {
                persistentPreviewPresentation.update(visibleOwner = null)
                disablePersistentPreviewPipeline(releaseInputs = false)
            }
            settingsPreviewPresentationRetained = false
        }
        glPreviewEngageRetries = 0
        glPreviewEngageRetryScheduled = false
        pipeline.stop()
        return !retainWarmPresentation
    }

    fun suspendCameraForSettings() {
        if (settingsCameraLifecycle.suspend()) {
            Log.i("CameraViewModel", "Settings acquired camera suspension")
        }
        closeCameraInternal(retainPreviewPresentationForSettings = true)
    }

    fun isCameraSuspendedForSettings(): Boolean = settingsCameraLifecycle.isSuspended()

    fun resumeCameraAfterSettings() {
        if (!settingsCameraLifecycle.resume()) return
        Log.i("CameraViewModel", "Settings released camera suspension; reopening immediately")
        resumeCameraForForeground()
    }

    fun closeCamera() = closeCameraInternal(retainPreviewPresentationForSettings = false)

    private fun closeCameraInternal(retainPreviewPresentationForSettings: Boolean) {
        if (!retainPreviewPresentationForSettings && settingsPreviewPresentationRetained) {
            // Settings may still cover the UI when the whole app backgrounds. In that case release the
            // warm, idle presentation contexts too; background ownership must retain no display resources.
            releasePreviewPresentationResources()
        }
        if (cameraCloseJob?.isActive == true || cameraState.value == CameraState.Closed) return
        Log.i("CameraViewModel", "closeCamera requested owner=${System.identityHashCode(this).toString(16)} state=${cameraState.value}")
        val releasePresentationAfterClose = prepareCameraCloseState(retainPreviewPresentationForSettings)
        cameraCloseJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                camera.closeCamera()
            } catch (error: Exception) {
                Log.e("CameraViewModel", "Camera close failed", error)
            } finally {
                var presentationReleaseStarted = false
                withContext(Dispatchers.Main.immediate) {
                    if (releasePresentationAfterClose || previewSurfaceRetirement.currentTicket() != null) {
                        releasePreviewPresentationResources()
                        presentationReleaseStarted = true
                    }
                }
                camera.stopBackground()
                withContext(Dispatchers.Main.immediate) {
                    val retirementTicket = previewSurfaceRetirement.currentTicket()
                    if (retirementTicket != null) {
                        if (!presentationReleaseStarted) releasePreviewPresentationResources()
                        completePreviewSurfaceRetirement(retirementTicket)
                    }
                }
                cameraCloseJob = null
            }
        }
        // Keep the display surface reference so Vulkan can reuse it after the next openCamera().
    }
    fun handleRecordingWindowFocusChanged(hasFocus: Boolean) =
        CameraPreviewLifecycleDispatcher.recordingWindowFocus(
            hasFocus, ::isRecordingOrStopping, persistentPreviewOutputRouter::setRecordingWindowOccluded
        )

    fun handleAppBackgrounded() {
        val decision = CameraBackgroundClosePolicy.resolve(
            isRecordingActive = gpuRecordState.value is GpuRecordingState.Starting || gpuRecordState.value is GpuRecordingState.Recording ||
                gpuRecordState.value is GpuRecordingState.Paused,
            isRecordingStopping = gpuRecordState.value is GpuRecordingState.Stopping
        )
        CameraPreviewLifecycleDispatcher.background(
            decision, deferClose = { closeCameraAfterRecordingStop = it },
            retainDisplayInputs = { recordingPreviewWindow.retain() },
            stopRecording = { requestRecordingStop(RecordingStopOrigin.APP_BACKGROUND, "activity moved to background while recording") },
            closeCamera = ::closeCamera
        )
    }

    private fun isRecordingOrStopping(): Boolean =
        CameraPreviewLifecycleDispatcher.recordingOwnsWindow(gpuRecordState.value, cameraState.value)

    private fun handlePreviewSurfaceUnavailable() {
        if (isRecordingOrStopping()) {
            clearPreviewSurfaceForActiveRecording()
            return
        }
        val retirementTicket = clearPreviewSurface()
        if (cameraState.value == CameraState.Closed) {
            // No Camera2 owner remains, so compositor queues can retire immediately.
            releasePreviewPresentationResources()
            completePreviewSurfaceRetirement(retirementTicket)
            return
        }
        if (cameraCloseJob?.isActive != true &&
            cameraState.value != CameraState.Closing &&
            cameraState.value != CameraState.Closed
        ) {
            closeCamera()
        }
    }

    private fun idleRecordingSurfacePrearmSkipReason(): String? {
        if (_uiMode.value != AppMode.VIDEO) return "not-video-mode"
        if (recordingStopRestartLifecycle.isStarting) return "recording-starting"
        if (cameraState.value == CameraState.Recording) return "camera-recording"
        if (_cinemaVideoMode.value == CinemaVideoMode.RAW) {
            return "custom-npraw-packet-stream-does-not-use-mediacodec"
        }
        if (shouldUseUncompressedCinemaPipeline()) {
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
        if (parityPlan.feedPath == RecordingFeedPath.BYTEBUFFER_P010_ENCODER) {
            return "bytebuffer-p010-does-not-use-codec-surface"
        }
        return when {
            parityPlan.encoderRawEnabled -> "raw-recording-prepares-on-record-start"
            parityPlan.encoderLogEnabled -> "log-recording-prepares-on-record-start"
            parityPlan.encoderHdrMode != "OFF" &&
                !isSensorLinearNdrMode(parityPlan.encoderHdrMode) ->
                "hdr-recording-prepares-on-record-start"
            else -> null
        }
    }

    private fun shouldKeepRecordingSurfacePrearmed(): Boolean =
        idleRecordingSurfacePrearmSkipReason() == null

    /**
     * Publishes the final native-P010 source topology while the cold-open/mode-switch Camera2
     * transaction still owns session construction. Encoder preparation remains asynchronous; only
     * the reader topology is made authoritative here so it cannot trigger a second session rebuild.
     */
    private fun primeOffscreenHardwareImportSessionGraph(reason: String): Boolean {
        val parityPlan = activeRecordingPipelineParityPlan()
        val encoderSize = preferredRecordingSize()
        val p010SourceSize = encoderSize.normalizeForOrientation()
        val sourceSupported = camera.supportsHardwareImportP010Source(
            p010SourceSize,
            recording.fps
        )
        if (!VideoSessionHardwareImportPrimingPolicy.shouldPrime(
                featureEnabled = VideoColdOpenHardwareImportSessionFlag.enabled,
                videoModeActive = _uiMode.value == AppMode.VIDEO,
                idlePrearmEligible = shouldKeepRecordingSurfacePrearmed(),
                offscreenHardwareImportFeed =
                    parityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
                sensorLinearNdr = isSensorLinearNdrMode(parityPlan.encoderHdrMode),
                nativeP010SourceSupported = sourceSupported
            )
        ) return false

        camera.setPreviewContinuityRecordSurfaceEnabled(false)
        camera.setRecordSurface(null, refreshSession = false)
        camera.setRecordingEncoderTargetSize(encoderSize)
        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        camera.setHardwareImportTargetSize(p010SourceSize)
        camera.setInternalRecordingHardwareImportEnabled(true)
        camera.setHardwareImportP010SourceEnabled(true)
        pipeline.setDedicatedHardwareImportEnabled(true)
        vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
        vulkanRuntime.setFrameImportEnabled(true)
        Log.i(
            "CameraViewModel",
            "Primed P010 hardware-import source in authoritative session transaction " +
                "reason=$reason size=${p010SourceSize.width}x${p010SourceSize.height}@${recording.fps}"
        )
        return true
    }

    private fun requestRecordingSurfacePrearm(forceSessionRefresh: Boolean = false) {
        if (shouldUseUncompressedCinemaPipeline()) {
            if (!releasePrearmedRecordingSurface(refreshSession = false)) return
            requestNpv10CapturePrearm(forceSessionRefresh)
            return
        }
        val cleanupStartNs = SystemClock.elapsedRealtimeNanos()
        npv10CapturePrearmedSignature = null
        // Retain the manager-owned physical extent across modes; release() owns terminal cleanup.
        camera.setNpv10TenBitCaptureEnabled(false)
        val npv10DisableEndNs = SystemClock.elapsedRealtimeNanos()
        val prearmSkipReason = idleRecordingSurfacePrearmSkipReason()
        if (prearmSkipReason != null) {
            recordingSurfacePrearmAfterModePresentation.set(false)
            recordingSurfacePrearmForcedRefreshRequested.set(false)
            recordingSurfacePrearmRetryJob?.cancel()
            recordingSurfacePrearmRetryJob = null
            Log.i(
                "CameraViewModel",
                "Idle recording surface prearm skipped: $prearmSkipReason; encoder prepares on record start"
            )
            if (!releasePrearmedRecordingSurface(refreshSession = forceSessionRefresh)) {
                Log.e(
                    "CameraViewModel",
                    "Idle recording pre-arm cleanup retained encoder ownership: $prearmSkipReason"
                )
                return
            }
            val releaseEndNs = SystemClock.elapsedRealtimeNanos()
            NeoLogger.i(
                "CameraViewModel",
                "RecordingPrearmSkipTiming reason=$prearmSkipReason total=" +
                    "${(releaseEndNs - cleanupStartNs) / 1_000_000.0}ms " +
                    "reservation=retained " +
                    "npv10-disable=${(npv10DisableEndNs - cleanupStartNs) / 1_000_000.0}ms " +
                    "release=${(releaseEndNs - npv10DisableEndNs) / 1_000_000.0}ms"
            )
            return
        }
        val sourceGraphPrimedInModeSwitch = if (modeSwitchCriticalDepth.get() > 0) {
            // Run synchronously while CameraController.beginBatchUpdate() still owns the transition.
            // Its one batch flush can then create the processed session with the final P010 source.
            primeOffscreenHardwareImportSessionGraph(reason = "mode-switch")
        } else {
            false
        }
        if (prearmedRecordingSurface != null &&
            (prearmedRecordingSurfaceAttachedToSession || !forceSessionRefresh)
        ) {
            val parityPlan = activeRecordingPipelineParityPlan()
            val expectedSignature = currentRecordingPrearmSignature(parityPlan)
            val reusable = isCurrentPrearmReusableForPlan(parityPlan, expectedSignature)
            val offscreenP010SourceRequiresProof =
                parityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER &&
                    !isSensorLinearNdrMode(parityPlan.encoderHdrMode)
            val sourceConfigured = !offscreenP010SourceRequiresProof ||
                camera.isHardwareImportP010SessionConfigured(
                    Size(expectedSignature.width, expectedSignature.height).normalizeForOrientation()
                )
            if (reusable && sourceConfigured) {
                if (offscreenP010SourceRequiresProof) {
                    prearmedRecordingSurfaceAttachedToSession = true
                }
                return
            }
            Log.i(
                "CameraViewModel",
                "Pre-armed recording graph requires reconciliation; refreshing " +
                    "attached=$prearmedRecordingSurfaceAttachedToSession " +
                    "feed=$prearmedRecordingFeedPath expectedFeed=${parityPlan.feedPath} " +
                    "reusable=$reusable sourceConfigured=$sourceConfigured " +
                    "prepared=$prearmedRecordingSignature expected=$expectedSignature"
            )
        }
        if (sourceGraphPrimedInModeSwitch) {
            // The full-quality P010/AHardwareBuffer topology is already owned by this mode batch.
            // Defer only physical codec allocation until the first verified presentation so it does
            // not compete with the HAL's RAW -> processed replacement-session critical path.
            recordingSurfacePrearmAfterModePresentation.set(true)
            return
        }
        if (!recordingSurfacePrearmInFlight.compareAndSet(false, true)) {
            if (forceSessionRefresh) {
                recordingSurfacePrearmForcedRefreshRequested.set(true)
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val deferredForcedRefresh =
                    recordingSurfacePrearmForcedRefreshRequested.getAndSet(false)
                // The mode-switch batch already owns the authoritative rebuild and now contains the
                // exact P010 topology. Let that transaction settle, then prove membership; issuing a
                // second forced refresh here recreated the observed ~1.6 s RAW->Video gap.
                val refreshSession =
                    (forceSessionRefresh && !sourceGraphPrimedInModeSwitch) || deferredForcedRefresh
                recordingSurfacePreparationCoordinator.runPrearmTransaction {
                    prearmRecordingSurfaceNow(refreshSession = refreshSession)
                }
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
        if (recordingStopRestartLifecycle.isStarting || recordingStopRestartLifecycle.isStopInProgress ||
            cameraState.value != CameraState.Streaming
        ) return
        val requestedFps = recording.fps.coerceAtLeast(1)
        camera.setNpv10TenBitCaptureEnabled(true)
        camera.setRawVideoCaptureEnabled(false)
        val requestedSize = preferredRecordingSize()
        val plan = try {
            resolveNpv10CaptureSourcePlanForStableStandby(requestedSize)
        } catch (error: RuntimeException) {
            // Idle pre-arm is an optimization, not permission to crash a mode/settings change.
            // Preserve the strict master contract: clear every stale P010 pre-arm marker and leave
            // the output disabled instead of substituting another resolution or an 8-bit stream.
            npv10CapturePrearmedSignature = null
            npv10CaptureRefreshSignature = null
            camera.setNpv10TenBitCaptureEnabled(false)
            val message =
                "Strict native 10-bit pre-arm is unavailable for " +
                    "${requestedSize.width}x${requestedSize.height}; no resolution or 8-bit fallback was used."
            Log.w("CameraViewModel", "$message ${error.message.orEmpty()}", error)
            setHardwareWarning(message)
            return
        }
        if (!plan.nativeTenBitCapture) return
        npv10Recording.allowIdlePhysicalReservationPreparation()
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
        camera.refreshOutputsAndSession(
            rebuildReaders = true,
            forceRebuild = true,
            reason = "npv10-idle-prearm"
        ) {
            if (camera.isNpv10TenBitSessionConfigured(plan.size)) {
                npv10CapturePrearmedSignature = signature
                Log.i("CameraViewModel", "Pre-armed native P010 packet capture session $signature")
            }
            npv10CaptureRefreshSignature = null
        }
    }

    private fun primeNpv10CaptureGraphForOpen() {
        val requestedFps = recording.fps.coerceAtLeast(1)
        camera.setFPS(requestedFps)
        if (!shouldUseUncompressedCinemaPipeline()) return
        camera.setNpv10TenBitCaptureEnabled(true)
        camera.setRawVideoCaptureEnabled(false)
        camera.setNpv10CaptureTargetSize(preferredRecordingSize())
        if (camera.characteristics == null) return
        runCatching { resolveNpv10CaptureSourcePlanForStableStandby(preferredRecordingSize()) }
            .getOrNull()
            ?.takeIf { it.nativeTenBitCapture }
            ?.let { plan ->
                camera.setNpv10CaptureTargetSize(plan.size)
                npv10CapturePrearmedSignature = npv10CapturePrearmSignature(plan.size, requestedFps)
            }
    }

    private fun configureModeSwitchStandbyAnalysisOutput() {
        if (_uiMode.value != AppMode.VIDEO) {
            camera.setModeSwitchStandbyAnalysisOutput(size = null, tenBit = false)
            return
        }
        val target = modeSwitchStandbyTarget.activeSize()
        camera.setModeSwitchStandbyAnalysisOutput(
            size = Size(target.width, target.height),
            tenBit = true
        )
    }

    private fun updateModeSwitchStandbyRequestedSize(size: Size) {
        modeSwitchStandbyTarget.updateRequestedSize(
            CameraStreamSize(size.width, size.height)
        )
    }

    private fun resolveNpv10CaptureSourcePlanForStableStandby(
        selectedResolution: Size,
        rawModeActive: Boolean = false
    ): Npv10CaptureSourcePlan {
        val requested = selectedResolution.normalizeForOrientation()
        val plan = camera.resolveNpv10CaptureSourcePlan(requested, rawModeActive)
        if (plan.nativeTenBitCapture) {
            val target = modeSwitchStandbyTarget.promoteExactP010Size(
                requestedSizeAtResolve = CameraStreamSize(requested.width, requested.height),
                exactSize = CameraStreamSize(plan.size.width, plan.size.height)
            )
            if (_uiMode.value == AppMode.VIDEO) {
                camera.setModeSwitchStandbyAnalysisOutput(
                    size = Size(target.width, target.height),
                    tenBit = true
                )
            }
        }
        return plan
    }
    private fun npv10CapturePrearmSignature(size: Size, fps: Int): String =
        "${size.width}x${size.height}@$fps:P010_10"
    fun prearmRecordingSurfaceForSelectedVideoSettings(forceSessionRefresh: Boolean = true) {
        if (_uiMode.value != AppMode.VIDEO) return
        requestRecordingSurfacePrearm(forceSessionRefresh = forceSessionRefresh)
    }

    fun isRecordingSurfacePrearmedForDebugStart(): Boolean {
        if (_uiMode.value != AppMode.VIDEO) return false
        // Surface metadata is published before native bind so teardown can retain ownership safely.
        // It is not readiness proof until the complete serialized transaction has exited.
        if (recordingSurfacePrearmInFlight.get()) return false
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
        val rawActive =
            rawCustomRecordingState.value is GpuRecordingState.Recording ||
                rawCustomRecordingState.value is GpuRecordingState.Paused
        val npv10Active =
            npv10Recording.state.value is GpuRecordingState.Recording ||
                npv10Recording.state.value is GpuRecordingState.Paused
        when (DebugClipTimingOwnerPolicy.resolve(rawActive, npv10Active)) {
            DebugClipTimingOwner.NPRAW -> Log.i(
                "CameraViewModel",
                "Debug clip timing retained by NPRAW first-durable-frame evidence"
            )
            DebugClipTimingOwner.NPV10 -> Log.i(
                "CameraViewModel",
                "Debug clip timing retained by NPV10 first-durable-frame evidence"
            )
            DebugClipTimingOwner.GPU_ENCODER -> gpuRecording.markClipTimingStartForEvidence()
        }
    }

    private fun prepareGpuRecordingForPrearmedVideoSession(): Boolean =
        prepareGpuRecordingForPrearmedVideoSession(
            feedPathLease = activeRecordingPipelineParityPlan().feedPath
        )

    /**
     * Prepares one codec session against one immutable recording-feed owner.
     *
     * Runtime capability telemetry can change while MediaCodec allocates its input Surface. Without
     * this lease the first half of pre-arm could select the core Vulkan owner and the second half
     * could select offscreen EGL, connecting the same BufferQueue producer to both native APIs.
     */
    private fun prepareGpuRecordingForPrearmedVideoSession(
        feedPathLease: RecordingFeedPath
    ): Boolean {
        if (_uiMode.value != AppMode.VIDEO) return false
        val app = getApplication<Application>()
        val prearmResolutionClaim = recordingResolutionSelectionAuthority.snapshot()
            ?: recordingResolutionSelectionAuthority.restorePersistedIfUnclaimed(
                preferredRecordingSize()
            )
        val plannedAudioEnabled =
            recording.audioSource != AudioSrc.NONE &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val initialGraphContract = activeVideoPipelineContract()
        val initialParityPlan = activeRecordingPipelineParityPlan(
            graphContract = initialGraphContract,
            actualFeedPathOverride = feedPathLease
        )
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
        val plannedCodec = if (highPrecisionRoutePlanned) {
            GpuVideoCodec.HEVC
        } else {
            sessionPlan.codec
        }
        val recordingSelection = resolveRecordingSelection(
            preferred = Size(prearmResolutionClaim.width, prearmResolutionClaim.height),
            preferredCodec = plannedCodec,
            explicitResolutionSelection = true,
            requestedFps = recording.fps
        )
        val resolvedBitrate = resolveConfiguredBitrate(
            selection = recordingSelection,
            fps = recording.fps,
            codec = recordingSelection.codec
        )
        val committed = synchronized(recordingSelectionMutationLock) {
            recordingResolutionSelectionAuthority.commitIfCurrent(prearmResolutionClaim) {
                gpuRecording.audioEnabled = plannedAudioEnabled
                gpuRecording.videoCodec = plannedCodec
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
                                "$sustainableGpuFps for " +
                                "${preparedEncoderSize.width}x${preparedEncoderSize.height} " +
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
                    CameraViewModelConstants.FORCE_UNIVERSAL_TEN_BIT_PROCESSED &&
                        !initialParityPlan.rawRequestedForSessionPlan &&
                        gpuRecording.supports10Bit() -> true
                    else -> sessionPlan.tenBitPipelineEnabled
                }
                syncEffectiveRecordingColorProfile()
                val preparedParityPlan = activeRecordingPipelineParityPlan(
                    actualFeedPathOverride = feedPathLease
                )
                gpuRecording.cinemaMode = effectiveRecordingCinemaMode(preparedParityPlan)
                gpuRecording.rawVideoEnabled = preparedParityPlan.encoderRawEnabled
                gpuRecording.videoLogEnabled = preparedParityPlan.encoderLogEnabled
                gpuRecording.videoHdrMode = preparedParityPlan.encoderHdrMode
                gpuRecording.videoColorSpace = preparedParityPlan.encoderColorSpace
                logRecordingPipelineParityPlan(preparedParityPlan, reason = "prearm")
            }
        }
        if (!committed) {
            // A direct settings choice advanced while this IO resolver was running. Do not let its
            // obsolete geometry overwrite the user's newer selection; the coalesced request owns
            // the next complete pre-arm transaction.
            recordingSurfacePrearmForcedRefreshRequested.set(true)
            Log.i(
                "CameraViewModel",
                "Recording prearm stale-resolution commit rejected generation=" +
                    "${prearmResolutionClaim.generation} " +
                    "resolved=${recordingSelection.requestedSize.width}x" +
                    "${recordingSelection.requestedSize.height} current=" +
                    recordingResolutionSelectionAuthority.snapshot()
            )
            return false
        }
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
        val sensorLinearNdr = isSensorLinearNdrMode(parityPlan.encoderHdrMode)
        val inputProfile = if (sensorLinearNdr) {
            null
        } else {
            camera.describeHdrDynamicRangeState().previewProfile
        }
        val bound = vulkanRuntime.setOffscreenAhbEglEncoderSurface(
            surface = surface,
            width = encoderSize.width,
            height = encoderSize.height,
            preferredDeliveryMode = deliveryMode,
            outputMirrorPlan = camera.recordOutputMirrorPlan("prearm-offscreen"),
            inputDynamicRangeProfile = inputProfile,
            ndrPeakPlan = activeNdrPeakPlan(inputProfile)
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

    /**
     * Bounded, fail-closed detach proof for the codec-owned offscreen EGL surface.
     *
     * Callers must not discard the prepared/active recorder or clear their last Surface owner unless
     * this returns true. Each runtime attempt already performs a bounded exact-fence drain; retries
     * cover a fence that signals immediately after the preceding deadline without weakening ownership.
     */
    private fun detachOffscreenAhbEglEncoderSurfaceWithProof(reason: String): Boolean {
        var lastFailure: Throwable? = null
        val detached = NativeResourceRetirementRetryPolicy.retire { attemptNumber ->
            val acknowledged = try {
                vulkanRuntime.detachOffscreenAhbEglEncoderSurfaceWithProof()
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                lastFailure = failure
                false
            }
            if (!acknowledged) {
                Log.w(
                    "CameraViewModel",
                    "Offscreen encoder detach not acknowledged reason=$reason " +
                        "attempt=$attemptNumber/${NativeResourceRetirementRetryPolicy.RAW_MANAGER_MAX_ATTEMPTS}",
                    lastFailure
                )
            }
            acknowledged
        }
        if (!detached) {
            val message =
                "GPU encoder surface retirement was not proven ($reason); " +
                    "codec and Surface ownership were retained for a safe retry."
            Log.e("CameraViewModel", message, lastFailure)
            setHardwareWarning(message)
        }
        return detached
    }

    /**
     * Bounded, fail-closed detach proof for the core Vulkan encoder target.
     *
     * This target is independent from the offscreen AHB/EGL bridge. Acknowledging one does not prove
     * that the other released its ANativeWindow, so every codec-destruction boundary proves both.
     */
    private fun detachInternalEncoderSurfaceWithProof(reason: String): Boolean {
        var lastFailure: Throwable? = null
        val detached = NativeResourceRetirementRetryPolicy.retire { attemptNumber ->
            val acknowledged = try {
                vulkanRuntime.detachInternalEncoderSurfaceWithProof()
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                lastFailure = failure
                false
            }
            if (!acknowledged) {
                Log.w(
                    "CameraViewModel",
                    "Core Vulkan encoder detach not acknowledged reason=$reason " +
                        "attempt=$attemptNumber/${NativeResourceRetirementRetryPolicy.RAW_MANAGER_MAX_ATTEMPTS}",
                    lastFailure
                )
            }
            acknowledged
        }
        if (!detached) {
            val message =
                "Core Vulkan encoder surface retirement was not proven ($reason); " +
                    "codec and Surface ownership were retained for a safe retry."
            Log.e("CameraViewModel", message, lastFailure)
            setHardwareWarning(message)
        }
        return detached
    }

    /** Bounded proof that neither GPU recording route retains the codec input Surface. */
    private fun detachAllGpuEncoderSurfacesWithProof(reason: String): Boolean {
        var lastFailure: Throwable? = null
        val detached = NativeResourceRetirementRetryPolicy.retire { attemptNumber ->
            val acknowledged = try {
                vulkanRuntime.detachAllEncoderSurfacesWithProof()
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                lastFailure = failure
                false
            }
            if (!acknowledged) {
                Log.w(
                    "CameraViewModel",
                    "All GPU encoder detach was not acknowledged reason=$reason " +
                        "attempt=$attemptNumber/${NativeResourceRetirementRetryPolicy.RAW_MANAGER_MAX_ATTEMPTS}",
                    lastFailure
                )
            }
            acknowledged
        }
        if (!detached) {
            val message =
                "GPU encoder ownership retirement was not proven ($reason); " +
                    "codec and Surface ownership were retained for a safe retry."
            Log.e("CameraViewModel", message, lastFailure)
            setHardwareWarning(message)
        }
        return detached
    }

    private fun prearmOffscreenHardwareImportSource(
        encoderSize: Size,
        refreshSession: Boolean
    ): Boolean {
        if (isSensorLinearNdrMode()) {
            return prearmSensorLinearNdrSource(encoderSize, refreshSession)
        }
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
        camera.setHardwareImportTargetSize(p010SourceSize)
        camera.setInternalRecordingHardwareImportEnabled(true)
        camera.setHardwareImportP010SourceEnabled(true)
        pipeline.setDedicatedHardwareImportEnabled(true)
        vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
        vulkanRuntime.setFrameImportEnabled(true)
        if (camera.isHardwareImportP010SessionConfigured(p010SourceSize)) {
            Log.i(
                "CameraViewModel",
                "Pre-armed offscreen AHB EGL source already belongs to the exact current Camera2 " +
                    "session ${p010SourceSize.width}x${p010SourceSize.height}; refresh skipped"
            )
            runOnMainIfNeeded {
                syncGlPreviewCompositorBinding()
                scheduleGlPreviewCompositorBindingResync("offscreen-p010-existing-session")
            }
            return true
        }
        if (camera.sessionRefreshPending.get()) {
            Log.i(
                "CameraViewModel",
                "Pre-armed offscreen AHB EGL source awaiting the authoritative Camera2 rebuild; " +
                    "no trailing refresh enqueued"
            )
            scheduleRecordingSurfacePrearmRetry()
            return false
        }
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
            forceRebuild = true,
            reason = "recording-surface-prearm"
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

    private fun prearmSensorLinearNdrSource(
        encoderSize: Size,
        refreshSession: Boolean
    ): Boolean {
        val rawSize = resolveSensorLinearNdrRawSize(encoderSize)
        if (!camera.supportsRaw || !camera.supportsManualSensor || !vulkanSupported.value || rawSize == null) {
            Log.w(
                "CameraViewModel",
                "Sensor-linear NDR prearm unavailable raw=${camera.supportsRaw} " +
                    "manual=${camera.supportsManualSensor} vulkan=${vulkanSupported.value} " +
                    "rawSize=${rawSize?.let { "${it.width}x${it.height}" } ?: "none"}"
            )
            return false
        }
        camera.setPreviewContinuityRecordSurfaceEnabled(false)
        camera.setRecordSurface(null, refreshSession = false)
        camera.setRecordingEncoderTargetSize(encoderSize)
        camera.setRecordingReadoutOverrideSize(rawSize)
        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        camera.setHardwareImportTargetSize(null)
        camera.setInternalRecordingHardwareImportEnabled(false)
        camera.setHardwareImportP010SourceEnabled(false)
        camera.setNpv10TenBitCaptureEnabled(false)
        camera.setRawVideoCaptureEnabled(false)
        camera.setSensorLinearNdrCaptureEnabled(true)
        pipeline.setDedicatedHardwareImportEnabled(false)
        vulkanRuntime.setSensorLinearNdrCaptureEnabled(true)
        vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
        vulkanRuntime.setFrameImportEnabled(false)
        reconcileRawPreviewPipeline()
        if (!refreshSession) {
            scheduleRecordingSurfacePrearmRetry()
            return false
        }
        val refreshed = CountDownLatch(1)
        camera.refreshOutputsAndSession(
            rebuildReaders = true,
            forceRebuild = true,
            reason = "sensor-linear-ndr-prearm"
        ) {
            refreshed.countDown()
        }
        val attached = refreshed.await(5, TimeUnit.SECONDS)
        if (attached) {
            Log.i(
                "CameraViewModel",
                "Pre-armed RAW_SENSOR ${rawSize.width}x${rawSize.height} -> ACEScg FP16 NDR source"
            )
        } else {
            Log.w("CameraViewModel", "Sensor-linear NDR RAW source prearm was not confirmed")
            scheduleRecordingSurfacePrearmRetry()
        }
        return attached
    }

    private fun prearmRecordingSurfaceNow(refreshSession: Boolean): Boolean {
        if (!shouldKeepRecordingSurfacePrearmed()) return false
        // Freeze the selected native Surface owner for this complete serialized transaction. Route
        // capability state may evolve, but it takes effect only after this transaction has either
        // published one coherent owner or fully retired the old owner.
        val prearmFeedPathLease = activeRecordingPipelineParityPlan().feedPath
        val prearmParityPlan = activeRecordingPipelineParityPlan(
            actualFeedPathOverride = prearmFeedPathLease
        )
        if (prearmFeedPathLease == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ||
            prearmFeedPathLease == RecordingFeedPath.BYTEBUFFER_P010_ENCODER
        ) {
            Log.i(
                "CameraViewModel",
                "Recording prearm route changed to a non-prearmable owner; transaction deferred " +
                    "feed=$prearmFeedPathLease"
            )
            if (prearmedRecordingSurface != null) {
                releasePrearmedRecordingSurface(
                    refreshSession = refreshSession,
                    preparationOwner = true
                )
            }
            return false
        }
        if (prearmedRecordingSurface != null && gpuRecordState.value is GpuRecordingState.Ready) {
            val expectedSignature = currentRecordingPrearmSignature(prearmParityPlan)
            if (!isCurrentPrearmReusableForPlan(prearmParityPlan, expectedSignature)) {
                Log.i(
                    "CameraViewModel",
                    "Pre-armed recording surface or owner stale; rebuilding prepared encoder " +
                        "preparedFeed=$prearmedRecordingFeedPath requestedFeed=$prearmFeedPathLease"
                )
                if (!releasePrearmedRecordingSurface(
                        refreshSession = true,
                        preparationOwner = true
                    )
                ) return false
            } else {
                val parityPlan = prearmParityPlan
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
                        val sourceAttached = if (isSensorLinearNdrMode(parityPlan.encoderHdrMode)) {
                            prearmedRecordingSurfaceAttachedToSession ||
                                prearmSensorLinearNdrSource(
                                    encoderSize = encoderSize,
                                    refreshSession = refreshSession
                                )
                        } else {
                            // Never trust the cached attachment bit for P010: a RAW session rebuild can
                            // retire the exact reader Surface while the codec surface remains reusable.
                            prearmOffscreenHardwareImportSource(
                                encoderSize = encoderSize,
                                refreshSession = refreshSession
                            )
                        }
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
                    if (!releasePrearmedRecordingSurface(
                            refreshSession = false,
                            preparationOwner = true
                        )) return false
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
                        forceRebuild = prearmAttachPlan.refreshAction != Camera2SessionRefreshAction.NONE,
                        reason = "recording-surface-prearm-attach"
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
            if (!detachAllGpuEncoderSurfacesWithProof("ready-prearm-replacement")) {
                return false
            }
            gpuRecording.discardPreparedSession()
        }
        if (!prepareGpuRecordingForPrearmedVideoSession(prearmFeedPathLease)) return false
        if (prearmParityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
            gpuRecording.setVideoInputMode(RecordingVideoInputMode.SURFACE)
            gpuRecording.setRealtimeSurfaceCadenceEnabled(false)
            gpuRecording.setPersistentInputSurfaceAllowed(false)
            val surface = gpuRecording.prepare() ?: run {
                prearmedRecordingSignature = null
                prearmedRecordingFeedPath = null
                return false
            }
            // Publish the caller-side owner before native bind. If bind/detach proof fails, later
            // prearm or lifecycle retirement can retry without losing the exact codec Surface.
            prearmedRecordingSurface = surface
            prearmedRecordingFeedPath = prearmParityPlan.feedPath
            prearmedRecordingSignature = currentRecordingPrearmSignature(prearmParityPlan)
            if (recordingStopRestartLifecycle.isStarting) {
                if (!releasePrearmedRecordingSurface(
                        refreshSession = false,
                        preparationOwner = true
                    )) return false
                Log.i(
                    "CameraViewModel",
                    "Pre-armed offscreen encoder surface discarded because recording start owns encoder configuration"
                )
                return false
            }
            if (!prebindOffscreenAhbEglEncoderSurface(surface, prearmParityPlan)) {
                if (!releasePrearmedRecordingSurface(
                        refreshSession = false,
                        preparationOwner = true
                    )) return false
                return false
            }
            val prearmedEncoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)
            if (!isSensorLinearNdrMode(prearmParityPlan.encoderHdrMode) &&
                !camera.supportsHardwareImportP010Source(
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
                    if (isSensorLinearNdrMode(prearmParityPlan.encoderHdrMode)) {
                        "Pre-armed offscreen AHB EGL encoder surface prepared; " +
                            "RAW_SENSOR ACEScg FP16 source attached before record start"
                    } else {
                        "Pre-armed offscreen AHB EGL encoder surface prepared; " +
                            "P010 hardware-import source attached before record start"
                    }
                } else {
                    if (isSensorLinearNdrMode(prearmParityPlan.encoderHdrMode)) {
                        "Pre-armed offscreen AHB EGL encoder surface prepared; " +
                            "RAW_SENSOR ACEScg FP16 source attachment pending retry"
                    } else {
                        "Pre-armed offscreen AHB EGL encoder surface prepared; " +
                            "P010 hardware-import source attachment pending retry"
                    }
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
        if (recordingStopRestartLifecycle.isStarting) {
            if (!detachAllGpuEncoderSurfacesWithProof("start-owned-prearm-discard")) {
                return false
            }
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
            if (!detachAllGpuEncoderSurfacesWithProof("persistent-prearm-mismatch")) {
                return false
            }
            gpuRecording.discardPreparedSession()
            prearmedRecordingSignature = null
            prearmedRecordingFeedPath = null
            return false
        }
        prearmedRecordingSurface = surface
        val parityPlan = prearmParityPlan
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
                    forceRebuild = prearmAttachPlan.refreshAction != Camera2SessionRefreshAction.NONE,
                    reason = "recording-surface-prearm-refresh"
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
        if (recordingSurfacePrearmRetryJob?.isActive == true) return
        recordingSurfacePrearmRetryJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500L)
            recordingSurfacePrearmRetryJob = null
            if (!shouldKeepRecordingSurfacePrearmed()) return@launch
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
                        if (releasePrearmedRecordingSurface(refreshSession = true)) {
                            requestRecordingSurfacePrearm(forceSessionRefresh = true)
                        }
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

    private fun detachPrearmedCamera2SurfaceWithProof(
        feedPath: RecordingFeedPath?
    ): Boolean {
        if (!prearmedRecordingSurfaceAttachedToSession) return true
        if (feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER ||
            feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
        ) {
            // For GPU routes this flag describes the P010/RAW source output, not a Camera2 target
            // backed by the codec Surface. GPU ownership is proven independently below.
            return true
        }
        val previousSessionClosed = CountDownLatch(1)
        return try {
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            camera.setRecordSurface(null, refreshSession = false)
            camera.refreshOutputsAndSession(
                rebuildReaders = false,
                forceRebuild = true,
                reason = "prearmed-recording-surface-detach",
                onPreviousSessionClosed = { previousSessionClosed.countDown() }
            )
            val detached = previousSessionClosed.await(1_000L, TimeUnit.MILLISECONDS)
            if (!detached) {
                val message =
                    "Pre-armed Camera2 encoder surface detach was not proven; recorder retained."
                Log.e("CameraViewModel", message)
                setHardwareWarning(message)
            }
            detached
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            Log.e("CameraViewModel", "Pre-armed Camera2 surface detach failed", failure)
            false
        }
    }

    private fun releasePrearmedRecordingSurface(
        refreshSession: Boolean,
        preparationOwner: Boolean = false,
        preserveOffscreenCameraSource: Boolean = false
    ): Boolean {
        if (recordingStopRestartLifecycle.isStarting && !preparationOwner) {
            Log.i(
                "CameraViewModel",
                "Deferred pre-armed encoder release because recording start owns preparation"
            )
            return false
        }
        if (prearmedRecordingSurface == null) return true
        if (gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping) {
            return false
        }
        val releasedFeedPath = prearmedRecordingFeedPath
        val preserveSensorLinearPreview =
            releasedFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER &&
                isSensorLinearNdrMode()
        if (!detachPrearmedCamera2SurfaceWithProof(releasedFeedPath)) {
            return false
        }
        if (!detachAllGpuEncoderSurfacesWithProof("prearmed-surface-release")) {
            return false
        }
        prearmedRecordingSurface = null
        prearmedRecordingSignature = null
        prearmedRecordingFeedPath = null
        if ((releasedFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER ||
                releasedFeedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER) &&
            !preserveOffscreenCameraSource
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
                    if (preserveSensorLinearPreview) {
                        camera.refreshRepeatingRequest()
                    } else {
                        camera.refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
                    }
                }
            } else if (releasedFeedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER) {
                camera.setRecordSurface(null, refreshSession = refreshSession)
            }
        }
        // For OFFSCREEN_AHB_EGL_ENCODER this flag represents the Camera2 P010/RAW producer, not
        // codec-Surface membership. Preserve the proven producer while replacing only a stale
        // codec owner; subsequent recording admission revalidates exact size/format/session truth.
        prearmedRecordingSurfaceAttachedToSession = preserveOffscreenCameraSource
        if (gpuRecordState.value is GpuRecordingState.Ready) {
            gpuRecording.discardPreparedSession()
        }
        gpuRecording.releasePersistentInputSurface()
        Log.i("CameraViewModel", "Released pre-armed encoder surface")
        return true
    }
    fun isRecordingAdmissionReadyForStart(): Boolean =
        !stillCapture.blocksRecordingAdmission() &&
            !camera.isStillCaptureSessionRestorationPending() &&
            StrictPreviewAdmissionPolicy.recordingAllowed(
                _uiMode.value,
                strictPreviewAdmissionState
            ) &&
            strictPreviewOutputFailure == null &&
            rejectedVideoModeAdmission == null &&
            activeVideoModePipelineContract().recordingAllowed

    fun startRecording() = recordingStopRestartLifecycle.requestStart()

    /** Called under the startup-handoff lock after the restart coordinator admits this generation. */
    private fun claimRecordingStartAdmission(): Boolean {
        // Revalidate the complete live settings snapshot at the final synchronous start boundary.
        // UI setters and preference restoration already enforce this contract, but this guard prevents
        // a stale/debug/internal mutation from ever starting a 4K stream above the product's 25 fps cap.
        if (!admitSettingsCommit(proposedSettingsCommit(), source = "recording-start")) {
            Log.w("CameraViewModel", "startRecording rejected by the final settings invariant gate")
            return false
        }
        validateActiveVideoModeAdmissionForRecording()?.let { message ->
            Log.w("CameraViewModel", "startRecording rejected by strict video-mode admission: $message")
            setHardwareWarning(message)
            gpuRecording.reportExternalErrorRetainingResources(message)
            return false
        }
        if (!isRecordingAdmissionReadyForStart()) {
            Log.i(
                "CameraViewModel",
                "startRecording ignored while still buffers/session restoration are in flight"
            )
            return false
        }
        if (!RecordingStartBackendAdmissionPolicy.allowsStart(hasActiveRecordingBackend())) {
            Log.i("CameraViewModel", "startRecording ignored: authoritative backend already active")
            return false
        }
        return true
    }

    private fun beginClaimedRecordingStart() {
        // Recording owns the exact Camera2 graph from this point. Cancel and invalidate any lifecycle
        // restore that was computed before shutter admission; a foreground resume will be replayed once
        // the recording/stop owner retires.
        if (refreshPreferencesJob?.isActive == true) {
            preferencesRefreshDeferredByRecording.set(true)
        }
        refreshPreferencesJob?.cancel()
        invalidatePendingPreferenceRestore()
        val stopSession = recordingStopOriginTracker.beginSession(debugValidationLabel)
        Log.i(
            RECORDING_STOP_ORIGIN_TAG,
            "RECORDING_STOP_SESSION generation=${stopSession.sessionGeneration} " +
                "label=${stopSession.validationLabel}"
        )
        recordingTransitionState.value = GpuRecordingState.Starting
        startRecordingInternal()
    }
    private fun startRecordingInternal() {
        closeCameraAfterRecordingStop = false
        // stopRecordingInternal after a successful save so it applies to exactly one clip.
        gpuRecording.pendingCustomRecordingName = if (prefs.customRecordingNameEnabled) {
            RecordingFileNamePolicy.sanitizeCustomName(prefs.customRecordingName).ifEmpty { null }
        } else null
        // Install try/finally undispatched before a rapid second shutter press can cancel startup.
        val startExecutionGate = CompletableDeferred<Unit>()
        val startJob = viewModelScope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.UNDISPATCHED
        ) {
            var rawVideoEnabled = false
            var attemptedCameraVideoStart = false
            var recordingRuntimeHintActive = false
            var recordingSessionStarted = false
            val recordingStartElapsedMs = SystemClock.elapsedRealtime()
            try {
                startExecutionGate.await()
                recordingStopRestartLifecycle.awaitPublicationIdleBeforeStart()
                // tryBeginStart() published recording ownership before this coroutine was launched.
                // Wait for a pre-arm transaction that already owned RecordingManager to finish its
                // prepare/discard cleanup before start reads or mutates the shared encoder session.
                recordingSurfacePreparationCoordinator.awaitPrearmCompletion()
                ensureActive()
                if (rawCustomRecordingState.value is GpuRecordingState.Error) {
                    rawCustomRecordingState.value = GpuRecordingState.Idle
                }
                if (hasActiveRecordingBackend()) {
                    Log.w("CameraViewModel", "startRecording ignored: backend already active")
                    return@launch
                }
                if (cameraState.value != CameraState.Streaming) {
                    Log.w("CameraViewModel", "startRecording ignored: cameraState=${cameraState.value}")
                    gpuRecording.reportExternalErrorRetainingResources(
                        "Camera preview is not ready for recording"
                    )
                    return@launch
                }
                if (!ensureManualVideoColorSelectionReadyForRecording()) {
                    return@launch
                }
                validateActiveVideoModeAdmissionForRecording()?.let { message ->
                    Log.w("CameraViewModel", "Recording rejected by strict video-mode admission: $message")
                    setHardwareWarning(message)
                    gpuRecording.reportExternalErrorRetainingResources(message)
                    return@launch
                }
                strictHighEndThermalAdmissionFailure()?.let { message ->
                    Log.w("CameraViewModel", message)
                    setHardwareWarning(message)
                    gpuRecording.reportExternalErrorRetainingResources(message)
                    return@launch
                }
                // Clear a stale refused-attempt error before RAW/NPV10 owns the aggregate state.
                if (gpuRecording.state.value is GpuRecordingState.Error) {
                    val staleCameraDetach = detachRecordingSurfaceBeforeEncoderStop(
                        allowPersistentPrearmRetention = false
                    )
                    if (staleCameraDetach is RecordingOperationOutcome.Failure) {
                        Log.e(
                            "CameraViewModel",
                            "Stale recorder error retained because Camera2 detach is unproven: " +
                                staleCameraDetach.reason,
                            staleCameraDetach.cause
                        )
                        return@launch
                    }
                    if (!detachAllGpuEncoderSurfacesWithProof("stale-error-retry")) {
                        return@launch
                    }
                    gpuRecording.discardPreparedSession()
                    Log.i("CameraViewModel", "Cleared stale recording error before admitted retry")
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
                val recordingSize = preferredRecordingSize()
                if (shouldUseCustomRawRecordingPipeline()) {
                    val started = startCustomRawRecordingSession(recordingSize)
                    if (started) {
                        recordingSessionStarted = true
                        ensureActive()
                    } else if (!recordingStopRestartLifecycle.isCancellationRequested) {
                        gpuRecording.reportExternalErrorRetainingResources(
                            "Custom RAW packet recording could not start"
                        )
                    }
                    return@launch
                }
                if (shouldUseUncompressedCinemaPipeline()) {
                    val started = startNpv10RecordingSession(recordingSize)
                    if (started) {
                        recordingSessionStarted = true
                        ensureActive()
                    } else if (!recordingStopRestartLifecycle.isCancellationRequested) {
                        gpuRecording.reportExternalErrorRetainingResources(
                            "Custom packed-10 NPV10 recording could not start"
                        )
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
                // Freeze native Surface ownership for this complete recording-start transaction.
                // Runtime health telemetry may change during codec/session setup; a different route
                // is admitted only by a later transaction after proof-detaching the current owner.
                val recordingFeedPathLease = initialParityPlan.feedPath
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
                val previewBindingParityPlan = activeRecordingPipelineParityPlan(
                    graphContract = previewBindingGraphContract,
                    actualFeedPathOverride = recordingFeedPathLease
                )
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
                    // Use P010 only where the encoder proves 10-bit support; RAW remains separate.
                    CameraViewModelConstants.FORCE_UNIVERSAL_TEN_BIT_PROCESSED &&
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
                val recordingParityPlan = activeRecordingPipelineParityPlan(
                    actualFeedPathOverride = recordingFeedPathLease
                )
                val sensorLinearNdrInput = isSensorLinearNdrMode(recordingParityPlan.encoderHdrMode)
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
                // Every processed route uses the same proven clip boundary. Limiting synchronized
                // microphone trim to two GPU paths let surface/P010 routes start audio from setup
                // time and made mode-dependent A/V offsets. Audio-disabled sessions remain
                // unaffected because RecordingManager's readiness gate is conditional on intent.
                gpuRecording.setClipTimingSynchronizedAudioEnabled(gpuRecording.audioEnabled)
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
                        prearmedRecordingFeedPath == recordingParityPlan.feedPath &&
                        RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                        recordingStateReady = gpuRecordState.value is GpuRecordingState.Ready,
                        surfaceAvailable = prearmedRecordingSurface != null,
                        preparedSignature = prearmedRecordingSignature,
                        expectedSignature = expectedPrearmSignature
                    )
                val reusableOffscreenPrearmedSurface =
                    offscreenAhbEglUsesCodecOwnedSurface &&
                        !byteBufferP010Input &&
                        prearmedRecordingFeedPath == recordingParityPlan.feedPath &&
                        RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                            recordingStateReady = gpuRecordState.value is GpuRecordingState.Ready,
                            surfaceAvailable = prearmedRecordingSurface != null,
                            preparedSignature = prearmedRecordingSignature,
                            expectedSignature = expectedPrearmSignature
                        )
                // The codec-owned Surface and the Camera2 P010 producer have independent
                // lifetimes. A scene/metadata change may legitimately invalidate only the codec
                // pre-arm signature while the exact full-resolution P010 reader is still a member
                // of the authoritative Camera2 session. Coupling both owners forced an unnecessary
                // session rebuild after recordingActive became true, producing a visible preview
                // freeze and a real sensor-timestamp gap at the head of otherwise healthy clips.
                // Prove the source directly from CameraController's current-session registry before
                // retiring a stale codec Surface; never infer source ownership from that Surface.
                val exactOffscreenP010SourceAlreadyInSession =
                    offscreenAhbEglUsesCodecOwnedSurface &&
                        !sensorLinearNdrInput &&
                        camera.isHardwareImportP010SessionConfigured(
                            preparedEncoderSize.normalizeForOrientation()
                        )
                val offscreenSourceAlreadyInSession =
                    if (sensorLinearNdrInput) {
                        reusableOffscreenPrearmedSurface &&
                            prearmedRecordingSurfaceAttachedToSession
                    } else {
                        exactOffscreenP010SourceAlreadyInSession
                    }
                if (internalHardwareImportUsesCodecOwnedSurface && prearmedRecordingSurface != null) {
                    Log.i(
                        "CameraViewModel",
                        "Recording start: retiring persistent pre-arm for codec-owned Vulkan hardware-import surface"
                    )
                    if (!releasePrearmedRecordingSurface(
                        refreshSession = false,
                        preparationOwner = true
                    )) return@launch
                } else if (byteBufferP010Input && prearmedRecordingSurface != null) {
                    Log.i(
                        "CameraViewModel",
                        "Recording start: retiring persistent pre-arm for non-prearmed recording input"
                    )
                    if (!releasePrearmedRecordingSurface(
                        refreshSession = false,
                        preparationOwner = true
                    )) return@launch
                }
                if (gpuRecordState.value is GpuRecordingState.Ready &&
                    !reusablePrearmedSurface &&
                    !reusableOffscreenPrearmedSurface
                ) {
                    if (!releasePrearmedRecordingSurface(
                        refreshSession = false,
                        preparationOwner = true,
                        preserveOffscreenCameraSource =
                            exactOffscreenP010SourceAlreadyInSession
                    )) return@launch
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
                suspend fun clearInternalRecordingFeed(): Boolean {
                    updatePreviewViewAwait {
                        setEncoderFeedEnabled(false)
                        setEncoderSurface(null)
                    }
                    vulkanRuntime.setFrameImportEnabled(false)
                    if (!detachAllGpuEncoderSurfacesWithProof("recording-route-transition")) {
                        return false
                    }
                    pipeline.setDedicatedHardwareImportEnabled(false)
                    camera.setInternalRecordingHardwareImportEnabled(false)
                    camera.setHardwareImportP010SourceEnabled(false)
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    camera.setHardwareImportTargetSize(null)
                    return true
                }

                val rawCaptureFpsSnapshot = recording.fps.coerceIn(1, 240)
                fun startRawCaptureAfterRecordingSessionReady() {
                    if (!rawVideoEnabled ||
                        rawVideoManager.state.value is com.neoproc.neoproccamx.pipeline.RawVideoState.Capturing
                    ) {
                        return
                    }
                    rawVideoManager.startCapture(rawCaptureFpsSnapshot)
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
                    if (!clearInternalRecordingFeed()) return false
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
                    if (!clearInternalRecordingFeed()) return false
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
                    // Re-read the live session after encoder start. If the HAL lost the required
                    // profile, abort and clean up; never continue a clip on STANDARD/8-bit transport.
                    val hdrState = camera.describeHdrDynamicRangeState()
                    val hdrActive = activeVideoPipelineContract().deliveryState.effectiveHdrMode != "OFF"
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
                        val message =
                            "Recording stopped: the required 10-bit HDR encoder profile was not retained."
                        setHardwareWarning(message)
                        cleanupFailedRecordingStart(
                            message = message,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = true
                        )
                        return false
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
                    pipeline.setDedicatedHardwareImportEnabled(false)
                    camera.setInternalRecordingHardwareImportEnabled(false)
                    camera.setHardwareImportP010SourceEnabled(false)
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    camera.setHardwareImportTargetSize(null)
                    val cameraDetach = detachRecordingSurfaceBeforeEncoderStop(
                        allowPersistentPrearmRetention = false
                    )
                    if (cameraDetach is RecordingOperationOutcome.Failure) {
                        Log.e(
                            "CameraViewModel",
                            "Internal-import fallback retained recorder: ${cameraDetach.reason}",
                            cameraDetach.cause
                        )
                        return false
                    }
                    if (!detachAllGpuEncoderSurfacesWithProof("internal-import-fallback")) {
                        return false
                    }
                    activeRecordingFeedPath = null

                    gpuRecording.reportExternalErrorAfterOwnershipRetired(message)
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
                    val strictMessage = if (sensorLinearNdrInput) {
                        "$message Proprietary NDR/NIVUKX requires RAW_SENSOR -> Vulkan ACEScg FP16 -> " +
                            "offscreen PQ Main10 at the selected size/FPS. Camera2 HLG/PQ/YUV and " +
                            "lower-precision fallback are blocked."
                    } else {
                        "$message Strict hybrid CPU/GPU/P010 recording requires validated " +
                            "offscreen AHB/EGL P010 at the selected FPS; legacy bytebuffer P010 " +
                            "fallback is blocked to avoid FPS and quality downgrade."
                    }
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
                    val ingressStopResult =
                        sensorLinearNdrRecordingLifecycle.stopIngress(drain = true)
                    if (!ingressStopResult.teardownSafe) {
                        Log.e(
                            "CameraViewModel",
                            "Strict NDR start rejection could not drain ordered ingress before surface release"
                        )
                    }
                    vulkanRuntime.setFrameImportEnabled(false)
                    pipeline.setDedicatedHardwareImportEnabled(false)
                    camera.setInternalRecordingHardwareImportEnabled(false)
                    camera.setHardwareImportP010SourceEnabled(false)
                    camera.setPreviewContinuityRecordSurfaceEnabled(false)
                    camera.setHardwareImportTargetSize(null)
                    activeRecordingFeedPath = null
                    lastValidatedGpuRecordingEvidence = ValidatedGpuRecordingEvidence.EMPTY
                    gpuRecording.setRealtimeSurfaceCadenceEnabled(false)
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
                    awaitVulkanPreviewRenderConfig(force = true)
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
                    // This route deliberately processes P010/RAW pre-roll before the encoder gate
                    // opens. Fence it out of clip-loss accounting so a frame prepared by the
                    // outgoing Camera2 generation cannot be charged to the new recording.
                    if (!pipeline.beginHardwareRecordingClipPreparation()) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "Recording admission was retired before offscreen source preparation."
                        )
                    }
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
                    val sensorLinearRawSize = if (sensorLinearNdrInput) {
                        resolveSensorLinearNdrRawSize(preparedEncoderSize)
                    } else {
                        null
                    }
                    if (sensorLinearNdrInput) {
                        val missingCapability = when {
                            !camera.supportsRaw -> "RAW_SENSOR capability is unavailable"
                            !camera.supportsManualSensor -> "MANUAL_SENSOR capability is unavailable"
                            !vulkanSupported.value -> "Vulkan FP16 compute is unavailable"
                            !gpuRecording.supports10Bit() -> "HEVC Main10 encoder input is unavailable"
                            sensorLinearRawSize == null ->
                                "no RAW_SENSOR size covers ${preparedEncoderSize.width}x${preparedEncoderSize.height}"
                            else -> null
                        }
                        if (missingCapability != null) {
                            return rejectOffscreenAhbEglRecordingNoDowngrade(
                                "Sensor-linear NDR recording cannot start: $missingCapability."
                            )
                        }
                    } else {
                        val p010SourceSize = preparedEncoderSize.normalizeForOrientation()
                        if (!camera.supportsHardwareImportP010Source(p010SourceSize, recording.fps)) {
                            val message =
                                "Offscreen EGL P010 hardware-import source is unavailable at " +
                                    "${preparedEncoderSize.width}x${preparedEncoderSize.height}@${recording.fps}fps."
                            Log.e("CameraViewModel", message)
                            return rejectOffscreenAhbEglRecordingNoDowngrade(message)
                        }
                    }
                    if (offscreenSourceAlreadyInSession) {
                        Log.i(
                            "CameraViewModel",
                            if (sensorLinearNdrInput) {
                                "Recording start: pre-armed RAW_SENSOR -> ACEScg FP16 source exists; " +
                                    "strict start will verify live FP16 submission"
                            } else {
                                "Recording start: pre-armed offscreen AHB EGL P010 source exists; " +
                                    "strict start will verify live hardware-import attachment"
                            }
                        )
                    }
                    camera.setRecordSurface(null, refreshSession = false)
                    prearmedRecordingSurface = null
                    prearmedRecordingSurfaceAttachedToSession = offscreenSourceAlreadyInSession
                    prearmedRecordingSignature = null
                    prearmedRecordingFeedPath = null
                    camera.setRecordingEncoderTargetSize(preparedEncoderSize)
                    camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
                    if (sensorLinearNdrInput) {
                        camera.setRecordingReadoutOverrideSize(sensorLinearRawSize)
                        camera.setHardwareImportTargetSize(null)
                        camera.setInternalRecordingHardwareImportEnabled(false)
                        camera.setHardwareImportP010SourceEnabled(false)
                        camera.setNpv10TenBitCaptureEnabled(false)
                        camera.setRawVideoCaptureEnabled(false)
                        pipeline.setDedicatedHardwareImportEnabled(false)
                        camera.setSensorLinearNdrCaptureEnabled(true)
                        vulkanRuntime.setSensorLinearNdrCaptureEnabled(true)
                        installRawVideoCallbacks()
                        vulkanRuntime.setCinemaDebayerActive(true)
                        camera.setRawPreviewCaptureEnabled(true)
                    } else {
                        camera.setHardwareImportTargetSize(preparedEncoderSize)
                        camera.setInternalRecordingHardwareImportEnabled(true)
                        camera.setHardwareImportP010SourceEnabled(true)
                        pipeline.setDedicatedHardwareImportEnabled(true)
                    }
                    if (sensorLinearNdrInput &&
                        !sensorLinearNdrRecordingLifecycle.beginPostSwitchExactPairReadiness()
                    ) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "RAW_SENSOR NDR recording could not begin its exact post-switch frame transaction."
                        )
                    }
                    if (sensorLinearNdrInput) {
                        attemptedCameraVideoStart = true
                        camera.startVideoCapture()
                    } else {
                        // Resolve and install the final processed-recording graph before opening the
                        // clip clock. The temporary state removes idle-only analysis and the inactive
                        // persistent preview without changing the 4K P010 source or quality contract.
                        camera.setProcessedRecordingSessionPreparationActive(true)
                    }
                    vulkanRuntime.setFrameImportEnabled(false)
                    vulkanRuntime.beginPreviewImportSession(
                        preserveActiveOffscreenImport = offscreenSourceAlreadyInSession
                    )
                    awaitVulkanPreviewRenderConfig(force = true)
                    logRecordingPipelineParityPlan(
                        actualOffscreenParityPlan,
                        reason = "offscreen-ahb-egl-encoder",
                        force = true
                    )
                    updatePreviewViewAwait { setEncoderFeedEnabled(false) }
                    val offscreenInputProfile = if (sensorLinearNdrInput) {
                        null
                    } else {
                        camera.describeHdrDynamicRangeState().previewProfile
                    }
                    val encoderBound = vulkanRuntime.setOffscreenAhbEglEncoderSurface(
                        surface = encoderSurface,
                        width = preparedEncoderSize.width,
                        height = preparedEncoderSize.height,
                        preferredDeliveryMode = offscreenDeliveryMode,
                        outputMirrorPlan = camera.recordOutputMirrorPlan("record-start-offscreen"),
                        inputDynamicRangeProfile = offscreenInputProfile,
                        ndrPeakPlan = activeNdrPeakPlan(offscreenInputProfile)
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
                    run {
                        // Wake and prove the configured Main10 GraphicBufferSource before live ingress.
                        // The transfer-invariant black frame samples no camera image, and
                        // RecordingManager discards its encoded access unit. This absorbs the
                        // vendor codec's first-output allocation stall outside the clip clock.
                        vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
                        if (!gpuRecording.beginSurfaceEncoderReadinessPreRoll()) {
                            return rejectOffscreenAhbEglRecordingNoDowngrade(
                                "HEVC Main10 encoder readiness pre-roll could not start."
                            )
                        }
                        if (!vulkanRuntime.submitOffscreenAhbEglEncoderReadinessFrame()) {
                            return rejectOffscreenAhbEglRecordingNoDowngrade(
                                "The offscreen encoder surface could not submit its non-clip readiness frame."
                            )
                        }
                        if (!gpuRecording.awaitSurfaceEncoderReadiness()) {
                            return rejectOffscreenAhbEglRecordingNoDowngrade(
                                "The encoder did not prove its configured HEVC Main10 transport before live ingress."
                            )
                        }
                    }
                    // Keep ordinary P010 in the same readiness phase while its Camera2 source is
                    // proven. Sensor-linear RAW additionally requires an exact post-switch pair.
                    if (!sensorLinearNdrInput) {
                        if (gpuRecording.state.value !is GpuRecordingState.Starting) {
                            val message = (gpuRecordState.value as? GpuRecordingState.Error)?.message
                                ?: "Video encoder left its readiness phase before P010 source proof"
                            cleanupFailedRecordingStart(
                                message = message,
                                rawVideoEnabled = rawVideoEnabled,
                                refreshSession = true,
                                // Sensor-linear capture may already own Camera2 here. Ordinary P010
                                // remains in preparation and is cleared by failed-start cleanup.
                                stopCameraVideo = attemptedCameraVideoStart
                            )
                            return false
                        }
                    }
                    applyRecordingCameraLookControls()
                    if (sensorLinearNdrInput &&
                        !sensorLinearNdrRecordingLifecycle.awaitDemosaicTransition()
                    ) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "RAW demosaic preview work did not quiesce before ordered NDR recording."
                        )
                    }
                    // P010 session validation needs live hardware-import evidence while the graph is
                    // rebuilt. RAW-derived NDR stays disabled until its final session generation is known.
                    // Acquisition may run for source proof, but the MediaCodec surface remains
                    // quarantined until both the current repeating request and clean clip boundary
                    // have committed.
                    vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
                    vulkanRuntime.setFrameImportEnabled(!sensorLinearNdrInput)
                    if (sensorLinearNdrInput) {
                        camera.setHardwareImportTargetSize(null)
                        camera.setHardwareImportP010SourceEnabled(false)
                        camera.setInternalRecordingHardwareImportEnabled(false)
                        if (!offscreenSourceAlreadyInSession) {
                            Log.i(
                                "CameraViewModel",
                                "Recording start: configuring strict RAW_SENSOR ACEScg FP16 source " +
                                    "${sensorLinearRawSize?.width}x${sensorLinearRawSize?.height}"
                            )
                            if (!awaitFreshPreviewSessionBeforeRecording(
                                    timeoutMs =
                                        ProcessedGpuRecordingRequirementPolicy
                                            .INTERNAL_HARDWARE_IMPORT_SESSION_TIMEOUT_MS,
                                    forceRebuild = true,
                                    rebuildReaders = true,
                                    waitForHardwareImportActivity = false
                                )) {
                                return rejectOffscreenAhbEglRecordingNoDowngrade(
                                    "Camera2 RAW_SENSOR session refresh timed out before sensor-linear NDR recording."
                                )
                            }
                        } else {
                            camera.refreshRepeatingRequest()
                        }
                    } else {
                        prearmedRecordingSurfaceAttachedToSession = false
                        Log.i(
                            "CameraViewModel",
                            "Recording start: rebuilding the constrained offscreen P010 session " +
                                "before clip admission prearmed=$offscreenSourceAlreadyInSession"
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
                                "Constrained Camera2 P010 session refresh timed out before offscreen EGL recording start."
                            )
                        }
                        attemptedCameraVideoStart = true
                        camera.startVideoCapture()
                    }
                    if (!awaitCameraRecordingState()) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            if (sensorLinearNdrInput) {
                                "Camera session failed to switch into RAW_SENSOR NDR recording request mode."
                            } else {
                                "Camera session failed to switch into offscreen P010 recording request mode."
                            }
                        )
                    }
                    if (sensorLinearNdrInput) {
                        if (!sensorLinearNdrRecordingLifecycle.awaitPostSwitchExactPairReadiness()) {
                            return rejectOffscreenAhbEglRecordingNoDowngrade(
                                "The final RAW_SENSOR recording session produced no exact post-switch RAW/result pair."
                            )
                        }
                    } else if (offscreenDeliveryMode == OffscreenEglDeliveryMode.SDR) {
                        pipeline.persistHardwareImportLifecycleEvidence(
                            "camera-p010-sdr-source-proof"
                        )
                        vulkanRuntime.persistOffscreenEncoderTransportEvidence(
                            "camera-p010-sdr-source-proof"
                        )
                        if (!awaitCameraP010SdrPrewarm()) {
                            return rejectOffscreenAhbEglRecordingNoDowngrade(
                                "The full-resolution Camera2 P010 SDR GPU path did not finish its " +
                                    "bounded prewarm before clip admission."
                            )
                        }
                    }
                    if (!gpuRecording.commitSurfaceEncoderReadiness()) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "The proven HEVC Main10 pre-roll could not commit a clean clip boundary."
                        )
                    }
                    if (!awaitGpuRecordingState()) {
                        val message = (gpuRecordState.value as? GpuRecordingState.Error)?.message
                            ?: "Video encoder failed to enter recording state after readiness"
                        cleanupFailedRecordingStart(
                            message = message,
                            rawVideoEnabled = rawVideoEnabled,
                            refreshSession = true,
                            stopCameraVideo = attemptedCameraVideoStart
                        )
                        return false
                    }
                    // Arm only after the final Camera2 repeating sequence and a clean encoder IDR
                    // boundary are proven. No stale session frame or codec warm-up access unit can
                    // become frame zero of the recording.
                    pipeline.persistHardwareImportLifecycleEvidence("before-gate-opening")
                    vulkanRuntime.persistOffscreenEncoderTransportEvidence("before-gate-opening")
                    if (!pipeline.commitHardwareRecordingClipIngress {
                            vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(true)
                        }
                    ) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "The clean hardware-import clip boundary could not be committed."
                        )
                    }
                    if (sensorLinearNdrInput && !sensorLinearNdrRecordingLifecycle.startIngress()) {
                        vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "Ordered RAW_SENSOR NDR recording ingress could not be armed for the configured session."
                        )
                    }
                    if (
                        offscreenDeliveryMode == OffscreenEglDeliveryMode.PQ &&
                        !sensorLinearNdrInput
                    ) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "PQ NDR recording requires the project-owned RAW-derived ACEScg FP16 input; " +
                                "Camera2 HLG/PQ/SDR input was rejected."
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
                    if (!gpuRecording.awaitSurfaceEncoderClipKeyFrame()) {
                        return rejectOffscreenAhbEglRecordingNoDowngrade(
                            "The HEVC Main10 encoder did not establish a real-camera keyframe " +
                                "before the bounded clip-start deadline."
                        )
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
                    if (!sensorLinearNdrInput) startRawCaptureAfterRecordingSessionReady()
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
                    if (!clearInternalRecordingFeed()) return false
                    camera.setNpv10TenBitCaptureEnabled(true)
                    val capturePlan = try {
                        resolveNpv10CaptureSourcePlanForStableStandby(
                            preparedEncoderSize.normalizeForOrientation()
                        )
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
                ensureActive()
            } catch (e: CancellationException) {
                if (!recordingStopRestartLifecycle.isCancellationRequested) throw e
                withContext(NonCancellable) {
                    // The common failed-start transaction retires Camera2 and both GPU owners before
                    // touching MediaCodec. Do not stop/discard here: Camera2 may already have accepted
                    // the input Surface even when the active route snapshot has not been published yet.
                    cleanupFailedRecordingStart(
                        message = "Recording start cancelled",
                        rawVideoEnabled = rawVideoEnabled,
                        refreshSession = true,
                        stopCameraVideo = attemptedCameraVideoStart,
                        reportError = false
                    )
                }
                recordingSessionStarted = false
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
                recordingSessionStarted = false
            } finally {
                recordingStopRestartLifecycle.completeStart(recordingSessionStarted) {
                    if (recordingRuntimeHintActive && !recordingSessionStarted) {
                        applyRecordingCriticalRuntimeHints(recordingActive = false)
                        applyAiTrackingPreferences()
                    }
                    RuntimeStageTelemetry.recordDuration(
                        tag = "CameraViewModel",
                        stage = "startRecording.total",
                        durationMs = SystemClock.elapsedRealtime() - recordingStartElapsedMs,
                        slowThresholdMs = SLOW_RECORDING_START_TOTAL_MS
                    )
                }
            }
        }
        recordingStopRestartLifecycle.registerStartupCancellationAction {
            startJob.cancel(CancellationException("Shutter requested stop during startup"))
        }
        startExecutionGate.complete(Unit)
    }

    private fun clearStaleAutofocusRegionForRecordingStart() {
        if (focusExposureLockState != null) return
        if (SystemClock.elapsedRealtime() < tapToFocusHoldUntilElapsedMs) return
        if (cameraParams.value.focusDist >= 0f) return
        manualFocusOverride.value = null
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
                    pauseNpv10AudioVideoTransaction()
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
                    resumeNpv10AudioVideoTransaction()
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

    /**
     * Pauses the AAC lane first and waits for its PCM-boundary acknowledgement before closing video
     * admission. This ordering prevents microphone samples captured during the user's pause from
     * extending the sidecar beyond the constant-frame-rate video programme.
     */
    private fun pauseNpv10AudioVideoTransaction(): Boolean {
        val audioRequired = npv10AudioRequiredForActiveSession
        if (audioRequired && !npv10AudioRecording.pauseAndAwait()) {
            failNpv10AudioVideoTransition(
                operation = "pause",
                reason = "AAC capture did not acknowledge its pause boundary"
            )
            return false
        }
        if (npv10Recording.pause()) return true

        val audioRollbackPassed = !audioRequired || npv10AudioRecording.resumeAndAwait()
        failNpv10AudioVideoTransition(
            operation = "pause",
            reason = if (audioRollbackPassed) {
                "video recorder rejected pause after AAC boundary; audio was restored"
            } else {
                "video recorder rejected pause and AAC rollback could not be acknowledged"
            }
        )
        return false
    }

    /**
     * Opens the ordered NPV10 cadence segment before restarting AAC admission. If either side
     * rejects the transition, video is returned to Paused where possible and the session is stopped
     * through the normal exact-once finalizer rather than continuing with split A/V state.
     */
    private fun resumeNpv10AudioVideoTransaction(): Boolean {
        if (!npv10Recording.resume()) {
            failNpv10AudioVideoTransition(
                operation = "resume",
                reason = "video recorder rejected its ordered resume cadence marker"
            )
            return false
        }
        if (!npv10AudioRequiredForActiveSession || npv10AudioRecording.resumeAndAwait()) {
            return true
        }

        val videoRollbackPassed = npv10Recording.pause()
        failNpv10AudioVideoTransition(
            operation = "resume",
            reason = if (videoRollbackPassed) {
                "AAC capture rejected resume; video was returned to a safe paused boundary"
            } else {
                "AAC capture rejected resume and video rollback could not be established"
            }
        )
        return false
    }

    private fun failNpv10AudioVideoTransition(operation: String, reason: String) {
        val message = "NPV10 synchronized A/V $operation failed closed: $reason"
        Log.e("CameraViewModel", message)
        setHardwareWarning(message)
        // The serialized stop owner preserves exact-once cleanup and refuses publication if the
        // required audio sidecar cannot pass finalization after a transport failure.
        requestRecordingStop(
            origin = RecordingStopOrigin.NPV10_AUDIO_FAILURE,
            detail = "$operation: $reason"
        )
    }

    private fun hasActiveRecordingBackend(): Boolean =
        rawCustomRecordingState.value.isRecordingSessionActive() ||
            npv10Recording.hasActiveSession() || npv10Recording.state.value.isRecordingSessionActive() ||
            gpuRecording.state.value.isRecordingSessionActive()

    fun stopRecording() = requestRecordingStop(
        origin = RecordingStopOrigin.USER_SHUTTER,
        detail = "recording shutter requested stop"
    )

    internal fun stopRecordingFromDebug(reason: String) = requestRecordingStop(
        origin = RecordingStopOrigin.DEBUG_COMMAND,
        detail = reason
    )

    internal fun stopRecordingFromRetainedDebugOwner() {
        if (!BuildConfig.DEBUG || viewModelTeardownStarted.get()) return
        if (!recordingStopRestartLifecycle.isStarting &&
            !isRecordingOrStopping() && !hasActiveRecordingBackend()
        ) {
            Log.i("CameraViewModel", "Retained debug stop acknowledged while recording is idle")
            return
        }
        // Keep the existing serialized stop owner, producer-boundary proof, audio drain and quality
        // certificate path. Only command delivery is independent of the foreground Activity.
        stopRecordingFromDebug("debug action stop_recording")
    }

    private fun requestRecordingStop(origin: RecordingStopOrigin, detail: String) {
        recordRecordingStopOrigin(origin, detail)
        recordingStopRestartLifecycle.requestStop()
    }

    private fun recordRecordingStopOrigin(
        origin: RecordingStopOrigin,
        detail: String
    ): RecordingStopOriginEvidence {
        val result = recordingStopOriginTracker.recordFirst(origin, detail)
        val evidence = result.evidence
        val marker = if (result.newlyRecorded) {
            "RECORDING_STOP_ORIGIN committed=true"
        } else {
            "RECORDING_STOP_ORIGIN committed=false ignored=${origin.name}"
        } +
            " origin=${evidence.origin.name}" +
            " generation=${evidence.sessionGeneration}" +
            " requestedElapsedRealtimeMs=${evidence.requestedElapsedRealtimeMs}" +
            " label=${evidence.validationLabel}" +
            " detail=${evidence.detail}"
        Log.i(RECORDING_STOP_ORIGIN_TAG, marker)
        NeoLogger.i(RECORDING_STOP_ORIGIN_TAG, marker)
        return evidence
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
        if (StrictPreviewAdmissionPolicy.required(_uiMode.value)) {
            val strictInput = if (
                strictPreviewAdmissionState == StrictPreviewAdmissionState.ACTIVE_PERSISTENT &&
                persistentPreviewPipelineActive
            ) {
                when (persistentPreviewLifecycle.owner) {
                    PersistentPreviewOwner.SDR -> previewGlCompositor.inputSurface()
                    PersistentPreviewOwner.HDR -> hdr10PqPreviewCompositor.inputSurface()
                    null -> null
                }
            } else {
                null
            }
            camera.setPreviewSurface(strictInput)
            if (strictInput == null) markStrictPreviewAdmissionPending()
            Log.i(
                "CameraViewModel",
                "Recording stop: strict preview target restored=${strictInput != null}; " +
                    "direct Camera2 visible binding blocked"
            )
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
            recordingStopRestartLifecycle.launchStopOwner {
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
            }
            return
        }
        if (npv10Recording.hasActiveSession() ||
            npv10Recording.state.value is GpuRecordingState.Recording ||
            npv10Recording.state.value is GpuRecordingState.Paused ||
            npv10Recording.state.value is GpuRecordingState.Stopping
        ) {
            recordingReadyForClipTiming = false
            recordingStopRestartLifecycle.launchStopOwner {
                stopNpv10RecordingSession()
            }
            return
        }
        if (gpuRecording.state.value !is GpuRecordingState.Recording &&
            gpuRecording.state.value !is GpuRecordingState.Paused
        ) {
            recordingStopRestartLifecycle.releaseActiveStopOwnerWithoutBackend()
            return
        }
        recordingReadyForClipTiming = false
        recordingStopRestartLifecycle.launchStopOwner {
            var rawVideoPath: String? = null
            var detachedRecordSurfaceBeforeEncoderStop = false
            val recordingStopElapsedMs = SystemClock.elapsedRealtime()
            val feedPath = activeRecordingFeedPath
            val sensorLinearNdrOwnershipWasActive =
                feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER &&
                    vulkanRuntime.isSensorLinearNdrRecordingDemosaicActive()
            var sensorLinearPreviewWorkerQuiesced = !sensorLinearNdrOwnershipWasActive
            var sensorLinearLineageFrozen = false
            var sensorLinearDemosaicReturnedToPreview = !sensorLinearNdrOwnershipWasActive
            var gpuEncoderDetachProven = false
            // Freeze applied input truth before stop/detach clears it so record-develop decodes correctly.
            val recordedHdrInputProfile = camera.describeHdrDynamicRangeState().effectiveRecordingInputProfile
            try {
                strictProcessedGpuValidationJob?.cancel()
                strictProcessedGpuValidationJob = null
                gpuRecording.markStopRequestedForEvidence()
                if (feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
                    Log.i("CameraViewModel", "Recording stop: keeping offscreen submission active until hardware-import tail drains")
                }
                if (sensorLinearNdrOwnershipWasActive) {
                    try {
                        val ingressStopResult =
                            sensorLinearNdrRecordingLifecycle.stopIngress(drain = true)
                        if (!ingressStopResult.teardownSafe) {
                            val failure = SensorLinearNdrRecordingFailure(
                                reason = "Ordered NDR recording tail did not drain before encoder shutdown"
                            )
                            sensorLinearNdrRecordingFailure.compareAndSet(null, failure)
                            handleCameraError(
                                throwable = Exception(failure.reason),
                                operation = "rawVideoManager.stopSensorLinearNdrRecordingIngress",
                                userVisibleMessage = "NDR recording stopped because its protected frame tail could not drain."
                            )
                        } else if (ingressStopResult.ingressFailed) {
                            sensorLinearNdrRecordingFailure.compareAndSet(
                                null,
                                SensorLinearNdrRecordingFailure(
                                    reason = "Strict NDR clip-quality ingress failure was reported before stop completed"
                                )
                            )
                            Log.w(
                                "CameraViewModel",
                                "NDR clip had already failed a strict ingress gate; protected ownership drained safely"
                            )
                        }
                    } finally {
                        try {
                            // Encoder acceptance is irreversible, but its independently downsampled
                            // preview token can still be crossing EGL. Drain that ordered domain before
                            // freezing the certificate so the last accepted preview lineage is included.
                            val previewBoundary =
                                drainSensorLinearNdrRecordingPreviewBoundary("normal-stop")
                            sensorLinearPreviewWorkerQuiesced = previewBoundary.quiesced
                        } finally {
                            freezeCamera2FrameLineageSession()
                            sensorLinearLineageFrozen = true
                        }
                    }
                } else {
                    freezeCamera2FrameLineageSession()
                    sensorLinearLineageFrozen = true
                }
                refreshRecordingSceneMetadataForFinalization()
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
                        deferHardwareSourceRebuildUntilAfterEncoderStop = true
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
                        // Every feed can inherit stale Camera2/core/offscreen ownership from a route
                        // transition. Never reclaim MediaCodec merely because the currently selected
                        // route is not the owner that failed to detach.
                        val warning =
                            "Recording stop is waiting for safe encoder-surface retirement; " +
                                "the active recorder was retained."
                        Log.e(
                            "CameraViewModel",
                            "$warning ${detachBeforeStopResult.reason}",
                            detachBeforeStopResult.cause
                        )
                        setHardwareWarning(warning)
                        return@launchStopOwner
                    }
                }
                gpuEncoderDetachProven =
                    detachAllGpuEncoderSurfacesWithProof("recording-stop-preflight")
                if (!gpuEncoderDetachProven) return@launchStopOwner
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
                sensorLinearNdrRecordingFailure.get()?.let { failure ->
                    gpuRecording.reportFatalPipelineFailure(
                        "Sensor-linear NDR recording pipeline failed: ${failure.reason}"
                    )
                }
                recordingTransitionState.value = GpuRecordingState.Finalizing
                val path = gpuRecording.stop()
                Log.i("CameraViewModel", "GPU recording stopped -> $path")
                if (sensorLinearNdrOwnershipWasActive) {
                    // EGL detach was proven before MediaCodec EOS. Only now may the RAW master leave
                    // RECORDING ownership; reopening preview earlier could target a retiring clip.
                    sensorLinearDemosaicReturnedToPreview =
                        sensorLinearNdrRecordingLifecycle.releaseDemosaicOwnership()
                    if (!sensorLinearDemosaicReturnedToPreview) {
                        sensorLinearNdrRecordingFailure.compareAndSet(
                            null,
                            SensorLinearNdrRecordingFailure(
                                reason = "Native NDR demosaic ownership did not quiesce after encoder detach"
                            )
                        )
                    }
                }
                val sensorLinearFailure = sensorLinearNdrRecordingFailure.get()
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
                            // The flat intermediate holds camera codes; decode them with the frozen applied profile.
                            // This follows per-session Camera2 truth, never a process-global transfer assumption.
                            captureTransferIsPq = NdrInputTransportPolicy.isPqProfile(recordedHdrInputProfile),
                            orientationHintDegrees = gpuRecording.orientationHintDegrees
                        )
                        com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.write(savedDevelopMasterFile, meta)
                        Log.i("CameraViewModel", "record-develop sidecar written -> ${savedDevelopMasterFile.name}")
                    }.onFailure { t ->
                        Log.w("CameraViewModel", "record-develop sidecar write failed", t)
                    }
                }
                val savedPath = if (sensorLinearFailure == null) {
                    path ?: savedDevelopMasterFile?.absolutePath
                } else {
                    null
                }
                if (sensorLinearFailure == null && savedDevelopMasterFile != null) {
                    // Keep the private master authoritative for on-phone conversion, then publish a
                    // byte-verified MediaStore.Files replica for ordinary USB File Transfer/MTP.
                    // The scheduler rejects a missing/corrupt .devcolor companion and never exposes
                    // a partial bundle.
                    com.neoproc.neoproccamx.recording.CinemaCamMtpPublicationScheduler.publishDevelop(
                        getApplication(),
                        savedDevelopMasterFile
                    )
                }
                if (savedPath != null) {
                    notifyRecordingSaved(savedPath)
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
                } else if (sensorLinearFailure != null) {
                    val failureMessage = "NDR recording was not certified: ${sensorLinearFailure.reason}"
                    Log.e(
                        "CameraViewModel",
                        "$failureMessage partialOutput=${path ?: savedDevelopMasterFile?.absolutePath ?: "none"}",
                        sensorLinearFailure.cause
                    )
                    setHardwareWarning(failureMessage)
                    gpuRecording.reportExternalErrorRetainingResources(failureMessage)
                }
                if (rawVideoPath != null) {
                    Log.i("CameraViewModel", "RAW video sequence saved -> $rawVideoPath")
                }
            } catch (e: Exception) {
                if (gpuEncoderDetachProven) {
                    handleCameraError(
                        throwable = e,
                        operation = "stopRecording",
                        userVisibleMessage = "Video stop failed because a camera hardware limit was reached."
                    )
                    if (gpuRecordState.value !is GpuRecordingState.Error) {
                        gpuRecording.reportExternalErrorAfterOwnershipRetired(
                            "Stopping video failed: ${e.message ?: "unexpected error"}"
                        )
                    }
                } else {
                    val warning =
                        "Recording resources were retained because GPU encoder detach was not proven."
                    Log.e("CameraViewModel", warning, e)
                    setHardwareWarning(warning)
                }
            } finally {
                try {
                    val ingressStopResult =
                        sensorLinearNdrRecordingLifecycle.stopIngress(drain = true)
                    if (!ingressStopResult.teardownSafe) {
                        Log.e(
                            "CameraViewModel",
                            "NDR recording ingress cleanup could not drain before surface release"
                        )
                    }
                    if (sensorLinearNdrOwnershipWasActive && ingressStopResult.ingressFailed) {
                        sensorLinearNdrRecordingFailure.compareAndSet(
                            null,
                            SensorLinearNdrRecordingFailure(
                                reason = "Strict NDR clip-quality ingress failure remained latched during stop cleanup"
                            )
                        )
                    }
                    if (sensorLinearNdrOwnershipWasActive && !sensorLinearPreviewWorkerQuiesced) {
                        val previewBoundary =
                            drainSensorLinearNdrRecordingPreviewBoundary("normal-stop-cleanup")
                        sensorLinearPreviewWorkerQuiesced = previewBoundary.quiesced
                    }
                    if (sensorLinearNdrOwnershipWasActive && !sensorLinearLineageFrozen) {
                        freezeCamera2FrameLineageSession()
                        sensorLinearLineageFrozen = true
                    }
                    if (!gpuEncoderDetachProven) {
                        // Do not cross the destructive boundary. In particular, keep foreground
                        // protection, MediaCodec, its Surface, demosaic ownership, and route identity
                        // alive for a later bounded retry or the process-lifetime retirement lease.
                        Log.e(
                            "CameraViewModel",
                            "Recording stop cleanup quarantined until offscreen EGL detach is proven"
                        )
                        return@launchStopOwner
                    }
                    stopRecordingForegroundService()
                    updatePreviewViewAwait {
                        setEncoderFeedEnabled(false)
                        setEncoderSurface(null)
                    }
                    if (!detachAllGpuEncoderSurfacesWithProof("normal-stop-cleanup")) {
                        Log.e(
                            "CameraViewModel",
                            "Recording stop cleanup retained codec ownership because GPU detach is unproven"
                        )
                        return@launchStopOwner
                    }
                    val demosaicReturnedToPreview =
                        sensorLinearNdrRecordingLifecycle.releaseDemosaicOwnership()
                    if (sensorLinearNdrOwnershipWasActive) {
                        sensorLinearDemosaicReturnedToPreview = demosaicReturnedToPreview
                    }
                    if (!demosaicReturnedToPreview) {
                        Log.e(
                            "CameraViewModel",
                            "NDR demosaic ownership remained pending during recording cleanup"
                        )
                    }
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
                    gpuRecording.setClipTimingSynchronizedAudioEnabled(false)
                    gpuRecording.setRealtimeSurfaceCadenceEnabled(false)
                    activeRecordingFeedPath = null
                    reconcileRawPreviewPipeline()
                    if (sensorLinearNdrOwnershipWasActive &&
                        sensorLinearPreviewWorkerQuiesced &&
                        sensorLinearLineageFrozen &&
                        sensorLinearDemosaicReturnedToPreview &&
                        !viewModelTeardownStarted.get()
                    ) {
                        if (!vulkanRuntime.resumeSensorLinearNdrPreviewAfterDrain()) {
                            // A surface rebuild may still be pending; its committed PREVIEW epoch will
                            // perform the same guarded resume. Never reopen while ownership is RECORDING.
                            Log.i(
                                "CameraViewModel",
                                "NDR preview worker remains paused until its restored surface commits"
                            )
                        }
                    }
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
                    if (closeCameraAfterRecordingStop && gpuEncoderDetachProven) {
                        runOnMain { closeCamera() }
                    } else if (closeCameraAfterRecordingStop) {
                        Log.e(
                            "CameraViewModel",
                            "Camera close deferred because the encoder Surface remains quarantined"
                        )
                    }
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
    /** Strict processed recording uses custom packed-10 unless RAW-derived proprietary NDR owns pixels. */
    private fun shouldUseUncompressedCinemaPipeline(): Boolean =
        Npv10RecordingRoutePolicy.shouldUseCamera2P010Master(
            uiMode = _uiMode.value,
            cinemaMode = _cinemaVideoMode.value,
            rawEnabled = _rawOn.value,
            logEnabled = _logOn.value,
            hdrMode = _videoHdrMode.value,
            uncompressedCinemaPipelineEnabled = _uncompressedCinemaPipeline.value,
            sdrConversionPipelineEnabled = _sdrConversionPipeline.value
        )

    /** Debug automation must use the exact same route authority as the recording shutter. */
    internal fun usesNpv10CapturePipelineForCurrentMode(): Boolean =
        shouldUseUncompressedCinemaPipeline()

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
                if (!releasePrearmedRecordingSurface(
                    refreshSession = false,
                    preparationOwner = true
                )) return false
            }
            // RAW and NPV10 use different lossless containers but share the same physical volume.
            // Retire only NPV10's unused idle extent before NPRAW admission; keeping both large
            // extents reserved can make a valid 4K25 RAW start fail despite sufficient real space.
            npv10Recording.retireIdlePhysicalReservationForCompetingPipeline()
            require(camera.supportsRaw) { "The selected camera does not expose RAW_SENSOR video data" }
            val normalizedSize = recordingSize.normalizeForOrientation()
            val admittedRawSelection = resolveRecordingSelection(
                normalizedSize, explicitResolutionSelection = true
            )
            require(!admittedRawSelection.requiresMediaEncoder && admittedRawSelection.exactOutputSupported) {
                "Selected RAW tier cannot preserve ${normalizedSize.width}x${normalizedSize.height} exactly"
            }
            val requestedFps = resolveContractRequestedFps(recording.fps).coerceAtLeast(1)
            applyPreRecordingPerformanceCaps(normalizedSize)
            applyRecordingCriticalRuntimeHints(
                recordingActive = true,
                previewFps = requestedFps.toFloat()
            )
            camera.setRawPreviewCaptureEnabled(false)
            vulkanRuntime.setCinemaDebayerActive(false)
            installRawVideoCallbacks()
            rawVideoManager.compressionMode = RawCompressionMode.LOSSLESS_REALTIME
            camera.setNpv10TenBitCaptureEnabled(false)
            camera.setRecordSurface(null, refreshSession = false)
            camera.setRecordingEncoderTargetSize(null)
            camera.setHardwareImportTargetSize(null)
            camera.setInternalRecordingHardwareImportEnabled(false)
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            camera.setAnalysisTargetSize(preferredRecordingAnalysisSize(normalizedSize))
            camera.setFPS(requestedFps)
            // The tier was resolved and its reader prearmed on RAW-mode entry. Reuse it verbatim so
            // recording start changes only repeating-request targets.
            val rawTier = resolveRawCaptureTier()
            val rawReadoutSize = rawTier.rawSize?.let { Size(it.width, it.height) }
                ?: error("The selected RAW tier has no HAL RAW_SENSOR stream")
            camera.setRecordingReadoutOverrideSize(rawReadoutSize)
            camera.setRecordingSensorPixelModeMaximumResolution(rawTier.engageMaxResPixelMode)
            rawVideoManager.sensorPixelModeMaximumResolutionUsed = rawTier.engageMaxResPixelMode
            rawVideoManager.orientationHintDegrees = camera.videoOrientationHint()
            camera.setRawVideoCaptureEnabled(true)
            camera.setRawVideoPrearmEnabled(true)
            startRecordingForegroundService()
            rawVideoManager.prepareCaptureAudio(
                if (ContextCompat.checkSelfPermission(
                        getApplication<Application>(), Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) recording.audioSource else AudioSrc.NONE
            )
            rawVideoManager.beginCaptureCadenceWarmup()
            // Enter recording before rebuilding so RawSensorReaderDepthPolicy allocates sustained depth.
            camera.startVideoCapture()
            cameraRecordingStarted = true
            if (!awaitFreshPreviewSessionBeforeRecording(
                    timeoutMs = 20_000L,
                    forceRebuild = false,
                    rebuildReaders = false
                )
            ) {
                error("RAW Camera2 session did not configure")
            }
            if (!awaitStableCameraCadenceBeforeRecording(requestedFps)) {
                error(
                    "RAW Camera2 cadence did not stabilize at ${requestedFps}fps before writer admission"
                )
            }
            // Preallocation starts here; packet packing and pwrite remain on RawVideoManager workers.
            rawVideoManager.startCapture(requestedFps)
            rawVideoManager.endCaptureCadenceWarmup()
            rawCaptureStarted = true
            if (!awaitCameraRecordingState()) {
                error("Camera did not enter custom RAW recording state")
            }
            if (!awaitFirstCustomRawPacket()) {
                error("Custom RAW recorder did not persist a camera packet")
            }
            rawVideoManager.startCaptureAudio()
            currentCoroutineContext().ensureActive()
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
            val sensorWhiteLevel = camera.characteristics
                ?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
                ?.coerceIn(1, 0xFFFF)
                ?: 0x0FFF
            val rawStorageBits = NprawFrameCodec.realtimeLosslessStorageBitDepth(sensorWhiteLevel)
            val sensorCodeBits = NprawFrameCodec.realtimeSensorCodeBitDepth(sensorWhiteLevel)
            Log.i(
                "CameraViewModel",
                "Custom RAW packet recording started readout=${rawReadoutSize.width}x${rawReadoutSize.height} " +
                    "fps=$requestedFps codec=NPRAW_DIRECT_SENSOR_CODE_EXACT mediaCodec=false " +
                    "sourceTransportBits=${NprawFrameCodec.REALTIME_SOURCE_TRANSPORT_BIT_DEPTH} " +
                    "sensorCodeBits=$sensorCodeBits sensorWhiteLevel=$sensorWhiteLevel " +
                    "storageBits=$rawStorageBits storageLayout=PACKED_$rawStorageBits " +
                    "sensorCodesBitExact=true maxResolutionPixelMode=${rawTier.engageMaxResPixelMode}"
            )
            true
        } catch (t: Throwable) {
            rawVideoManager.endCaptureCadenceWarmup()
            runCatching { rawVideoManager.abortCaptureAudio(t) }.onFailure(t::addSuppressed)
            val cancelledByShutter =
                t is CancellationException && recordingStopRestartLifecycle.isCancellationRequested
            rawCustomDurationJob?.cancel()
            rawCustomDurationJob = null
            if (cameraRecordingStarted) runCatching { camera.stopVideoCapture() }
            if (rawCaptureStarted) runCatching { rawVideoManager.stopCapture() }
            camera.setRawVideoCaptureEnabled(false)
            camera.setRecordingSensorPixelModeMaximumResolution(false)
            camera.setRecordingReadoutOverrideSize(null)
            stopRecordingForegroundService()
            rawCustomRecordingState.value = if (cancelledByShutter) {
                GpuRecordingState.Idle
            } else {
                GpuRecordingState.Error(t.message ?: "Custom RAW recording failed")
            }
            reconcileRawPreviewPipeline()
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

    private suspend fun awaitStableCameraCadenceBeforeRecording(
        targetFps: Int,
        timeoutMs: Long = 8_000L
    ): Boolean = awaitStableCameraCadenceEvidence(targetFps, timeoutMs).admitted

    private suspend fun awaitStableCameraCadenceEvidence(
        targetFps: Int,
        timeoutMs: Long = 8_000L
    ): CameraRecordingCadenceAdmissionSnapshot {
        val gate = CameraRecordingCadenceAdmissionGate(targetFps)
        var latest = gate.snapshot()
        val admitted = withTimeoutOrNull(timeoutMs) {
            camera.previewFrameTimestamps.first { sensorTimestampNs ->
                latest = gate.observe(sensorTimestampNs)
                latest.admitted
            }
            true
        } == true
        val result = latest
        val level = if (admitted) Log.INFO else Log.ERROR
        Log.println(
            level,
            "CameraViewModel",
            "RECORDING_CADENCE_ADMISSION admitted=$admitted targetFps=$targetFps " +
                "observedFrames=${result.observedFrames} " +
                "consecutiveIntervals=${result.consecutiveIntervals} " +
                "rejectedIntervals=${result.rejectedIntervals} " +
                "measuredFps=${String.format(Locale.US, "%.3f", result.measuredFps)} " +
                "lastGapMs=${result.lastGapNs / 1_000_000.0} " +
                "largestGapMs=${result.largestGapNs / 1_000_000.0}"
        )
        return result
    }
    private suspend fun awaitFirstCustomRawPacket(timeoutMs: Long = 12_000L): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            if (rawVideoManager.writtenFrameCount() > 0 &&
                rawVideoManager.firstPersistedTimestampNs() > 0L
            ) return true
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
        var stopFailure: Throwable? = null
        val path = try {
            // Close the recorder gate and drain accepted packets while the existing Camera2 session keeps
            // streaming. The RAW output remains configured; only its repeating target changes below.
            recordingTransitionState.value = GpuRecordingState.Finalizing
            rawVideoManager.stopCapture()
        } catch (t: Throwable) {
            stopFailure = t
            null
        } finally {
            stopRecordingForegroundService()
            applyRecordingCriticalRuntimeHints(recordingActive = false)
            camera.setRawVideoCaptureEnabled(false)
            // Keep the exact reader size, depth, and pixel mode prearmed while RAW mode remains selected.
            camera.setRawVideoPrearmEnabled(true)
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
            camera.setInternalRecordingHardwareImportEnabled(false)
            camera.setPreviewContinuityRecordSurfaceEnabled(false)
            camera.setHardwareImportTargetSize(null)
        }
        rawCustomRecordingDuration.value = 0L
        rawCustomRecordingAccumulatedMs = 0L
        rawCustomRecordingStartedElapsedMs = 0L
        rawCustomRecordingState.value = stopFailure?.let {
            GpuRecordingState.Error(it.message ?: "Custom RAW stop failed")
        } ?: GpuRecordingState.Idle
        runCatching { camera.stopVideoCapture(retargetRepeating = false) }.onFailure { error ->
            if (stopFailure == null) stopFailure = error
        }
        if (stopFailure != null) {
            rawCustomRecordingState.value = GpuRecordingState.Error(
                stopFailure.message ?: "Custom RAW stop failed"
            )
        }
        reconcileRawPreviewPipeline()
        camera.refreshRepeatingRequest()
        // Re-evaluate display sizing, but let the session fingerprint prove that RAW start/stop needs no
        // surface replacement. The camera request was already retargeted by stopVideoCapture().
        refreshPreviewPipelineIfNeeded(forceSessionRefresh = false)
        syncCameraPreviewLookControls()
        syncActivePreviewFrameRate()
        syncPreviewPerformanceModeOnMain()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
        path?.let {
            notifyRecordingSaved(it)
            Log.i("CameraViewModel", "Custom RAW packet stream saved -> $it")
        }
        stopFailure?.let { throw it }
    }
    private suspend fun startNpv10RecordingSession(recordingSize: Size): Boolean {
        val normalizedRecordingSize = recordingSize.normalizeForOrientation()
        var npv10WriterStarted = false
        var npv10ImageSinkMayBeAttached = false
        var npv10LineageMayBeStarted = false
        var npv10Camera2ProducerMayBeActive = false
        var npv10ProducerStartupLease: Npv10ProducerStartupLeaseHandle? = null
        return try {
            check(Npv10PixelColorMetadataPolicy.supportsAuthoritativeImageDataSpace(Build.VERSION.SDK_INT)) {
                "NPV10 requires Android 13 or newer for per-frame Image.dataSpace inspection; " +
                    "a HAL that omits dataspace remains explicitly unverified without blocking " +
                    "exact-session ten-bit capture"
            }
            check(!isSensorLinearNdrMode()) {
                "Sensor-linear NDR must use the project-owned RAW to ACEScg recording pipeline; " +
                    "vendor Camera2 P010 cannot be published as proprietary NDR"
            }
            if (prearmedRecordingSurface != null || gpuRecording.state.value is GpuRecordingState.Ready) {
                if (!releasePrearmedRecordingSurface(
                    refreshSession = false,
                    preparationOwner = true
                )) return false
            }
            // Release only the unused NPRAW pre-arm extent before NPV10 expands its own strict
            // 30-second reservation. No active RAW writer, resolution, depth, cadence, or bitrate is
            // changed by this handoff.
            rawVideoManager.retireIdleCaptureStorageForCompetingPipeline()
            camera.setNpv10TenBitCaptureEnabled(true)
            camera.setRawVideoCaptureEnabled(false)
            val initialCapturePlan =
                resolveNpv10CaptureSourcePlanForStableStandby(normalizedRecordingSize)
            val analysisSize = initialCapturePlan.size
            val requestedFps = recording.fps.also { fps ->
                require(fps > 0) { "NPV10 recording FPS must be positive; requested=$fps" }
            }
            com.neoproc.neoproccamx.recording.Npv10DmcCadencePolicy
                .evaluate(analysisSize.width, analysisSize.height, requestedFps)
                .requireAccepted()
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
            val npv10AudioRequired =
                recording.audioSource != AudioSrc.NONE &&
                    ContextCompat.checkSelfPermission(
                        getApplication<Application>(),
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
            // Establish the foreground/microphone resources before the Camera2 cadence proof. Codec2
            // and audio-HAL creation can otherwise create a post-frame-one scheduling discontinuity in
            // a lossless 4K P010 master even while every writer stage remains within its deadline.
            // Npv10AudioSidecarRecorder keeps this pre-roll private; start() discards it and anchors the
            // durable AAC timeline to the exact first persisted video sensor timestamp.
            startRecordingForegroundService()
            if (npv10AudioRequired) {
                npv10AudioRecording.prepare(recording.audioSource)
            }
            // Do not open the master timeline until the exact P010 reader is part of a completed
            // Camera2 session. Previously a stale pre-arm could let the writer accept one old-session
            // frame, then wait through a 300-600 ms reader rebuild; the clip reported zero queue drops
            // but its sensor timeline contained a large startup cadence hole.
            require(awaitNpv10P010SessionReady(activeSize, requestedFps)) {
                "NPV10 P010 session did not settle at ${activeSize.width}x${activeSize.height}@$requestedFps"
            }
            pipeline.setRawYuvFrameSink(null)
            pipeline.setRawYuvImageSink(null)
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
            check(capturePrearmed) {
                "NPV10 exact P010 session changed after readiness proof; refusing post-start rebuild"
            }
            camera.resetPreviewActivityTracking()
            // Producer activation comes before writer/sink ownership. This excludes idle/pre-arm
            // frames and proves that the exact recording request is producing current-generation
            // P010 before a master timeline can exist.
            npv10Camera2ProducerMayBeActive = true
            val producerStartupLease = camera.startNpv10VideoCaptureWithProducerLease()
            npv10ProducerStartupLease = producerStartupLease
            val startupAuthorityResult = awaitNpv10P010ProducerReady(
                activeSize,
                requestedFps,
                activeCapturePlan.imageFormat,
                startupLeaseValid = { !producerStartupLease.isRevoked }
            )
            check(startupAuthorityResult.accepted) {
                "NPV10 recording P010 producer did not prove an exact current-generation frame at " +
                    "${activeSize.width}x${activeSize.height}@$requestedFps: " +
                    startupAuthorityResult.failureReason
            }
            val initialStartupAuthority = requireNotNull(startupAuthorityResult.authority)
            val stabilizedProducer = stabilizeNpv10ProducerBeforeWriter(
                size = activeSize,
                fps = requestedFps,
                imageFormat = activeCapturePlan.imageFormat,
                startupLease = producerStartupLease,
                initialAuthority = initialStartupAuthority
            )
            val startupAuthority = stabilizedProducer.authority
            currentCoroutineContext().ensureActive()
            check(!producerStartupLease.isRevoked) { "NPV10 Camera2 startup lease was revoked before writer creation" }
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
                packetCodec = Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2,
                exactSelectedResolution = activeCapturePlan.exactSelectedResolution,
                selectedColorSpace = manualColorPlan.selectedColorSpace,
                selectedColorScience = manualColorPlan.selectedColorScience,
                colorSelectionSource = manualColorPlan.colorSelectionSource,
                manualColorSelectionRequired = manualColorPlan.manualColorSelectionRequired,
                rawSensorCapture = false,
                sensorPixelModeMaximumResolution = false,
                // Keep the lossless NPV10 recorder inside its 4K/25 frame budget. NIV99 owns the
                // derived conversion, while capture writes the original ten-bit P010 samples.
                niv99ProcessingRequired = false,
                directMappedCommitRequired = true,
                validationLabel = debugValidationLabel.orEmpty(),
                featureMetadata = buildNpv10FeatureMetadata(
                    selectedSize = normalizedRecordingSize,
                    capturePlan = activeCapturePlan,
                    requestedFps = requestedFps,
                    manualColorPlan = manualColorPlan,
                    recordingModeLabel = recordingModeLabel,
                    startupAuthority = startupAuthority,
                    cadenceAuthority = stabilizedProducer.cadence,
                    startupReplayCount = stabilizedProducer.replayCount,
                    audioStoredInBundle = npv10AudioRequired
                ),
                // Timestamp the master only after Camera2 proves the authoritative producer. Idle
                // pre-arm time and producer-retarget latency are never charged to clip cadence.
                startedAtEpochMs = System.currentTimeMillis()
            )
            currentCoroutineContext().ensureActive()
            check(!producerStartupLease.isRevoked) { "NPV10 Camera2 startup lease was revoked before writer start" }
            val output = npv10Recording.start(config)
            npv10WriterStarted = true
            npv10LineageMayBeStarted = true
            beginCamera2FrameLineageSession(activeRecordingPipelineParityPlan())
            val startupIngress = Npv10RecordingStartupIngress(
                recorder = npv10Recording,
                startupLease = producerStartupLease,
                expectedWidth = activeSize.width,
                expectedHeight = activeSize.height,
                expectedImageFormat = activeCapturePlan.imageFormat,
                minimumProducerTimestampNs = startupAuthority.producerProof.firstFrameSensorTimestampNs
            )
            currentCoroutineContext().ensureActive()
            check(!producerStartupLease.isRevoked) { "NPV10 Camera2 startup lease was revoked before image-sink attachment" }
            // Set before invoking the setter so a partial/throwing attachment is still detached by
            // startup abort. Once this flag is set, cleanup must retain the exact P010 stop fence.
            npv10ImageSinkMayBeAttached = true
            pipeline.setRawYuvImageSinkAtExactIngressBoundary(sink = startupIngress::onImage)
            if (!awaitNpv10FirstWrittenFrame(startupAuthority, startupIngress)) {
                abortNpv10Startup(
                    reason = "first persisted frame was not proven before startup timeout",
                    writerStarted = npv10WriterStarted,
                    imageSinkMayBeAttached = npv10ImageSinkMayBeAttached,
                    lineageMayBeStarted = npv10LineageMayBeStarted,
                    camera2ProducerMayBeActive = npv10Camera2ProducerMayBeActive
                )
                camera.setNpv10TenBitCaptureEnabled(false)
                camera.setRawVideoCaptureEnabled(false)
                camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
                camera.refreshRepeatingRequest()
                return false
            }
            if (npv10AudioRequired) {
                val firstVideoSensorTimestampNs =
                    requireNotNull(npv10Recording.firstDurableFrameSensorTimestampNs()) {
                        "NPV10 durable first-frame timestamp disappeared before audio start"
                    }
                npv10AudioRecording.start(
                    master = output,
                    firstVideoSensorTimestampNs = firstVideoSensorTimestampNs,
                    audioSource = recording.audioSource
                )
                npv10AudioRequiredForActiveSession = true
            } else {
                npv10AudioRequiredForActiveSession = false
            }
            // The exact request has now crossed Camera2 proof, a ten-interval cadence window, sink
            // attachment and durable writer admission. A late user/external or autonomous control
            // mutation fails this unpublished startup closed. Every settled autonomous update was
            // already replayed under the retained lease and re-proven before writer attachment, so this
            // commit never replaces the Camera2 repeating request after frame one.
            currentCoroutineContext().ensureActive()
            val leaseCommit = withTimeout(NPV10_SESSION_READY_TIMEOUT_MS) {
                producerStartupLease.commitAfterCadenceStabilization()
            }
            check(leaseCommit.committed) { leaseCommit.reason }
            npv10ProducerStartupLease = null
            syncActivePreviewFrameRate()
            syncPreviewPerformanceModeOnMain()
            recordingReadyForClipTiming = true
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
                t is CancellationException && recordingStopRestartLifecycle.isCancellationRequested
            abortNpv10Startup(
                reason = if (cancelledByShutter) {
                    "startup cancelled by shutter after producer activation"
                } else {
                    "startup failed: ${t.message ?: t.javaClass.simpleName}"
                },
                writerStarted = npv10WriterStarted,
                imageSinkMayBeAttached = npv10ImageSinkMayBeAttached,
                lineageMayBeStarted = npv10LineageMayBeStarted,
                camera2ProducerMayBeActive = npv10Camera2ProducerMayBeActive
            )
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
            if (npv10Camera2ProducerMayBeActive) camera.refreshRepeatingRequest()
            false
        } finally {
            npv10ProducerStartupLease?.let { lease ->
                runCatching { lease.close() }
                    .onFailure { Log.e("CameraViewModel", "NPV10 producer startup lease release failed", it) }
            }
        }
    }

    /**
     * Fail-closed cleanup for every NPV10 startup exit after producer activation begins.
     *
     * Before sink attachment the writer owns no Camera2 image, so ordinary producer stop is both
     * sufficient and required. Once sink attachment may have occurred, only the exact Camera2 marker
     * fence may detach it. Startup never publishes a clip: cancellation, timeout, or any failed
     * qualification poisons durability first.
     */
    private fun abortNpv10Startup(
        reason: String,
        writerStarted: Boolean,
        imageSinkMayBeAttached: Boolean,
        lineageMayBeStarted: Boolean,
        camera2ProducerMayBeActive: Boolean
    ) {
        val recorderActive = writerStarted || npv10Recording.hasActiveSession()
        if (!recorderActive && !imageSinkMayBeAttached && !camera2ProducerMayBeActive) {
            npv10AudioRecording.abort()
            npv10AudioRequiredForActiveSession = false
            stopRecordingForegroundService()
            return
        }

        val poisonReason = "NPV10 startup aborted before verified publication: $reason"
        if (recorderActive) npv10Recording.poisonStopBoundaryFailure(poisonReason)
        var imageIngressSeal: AutoCloseable? = null
        var sinkDetached = !imageSinkMayBeAttached

        fun sealDetachAndBeginFinalization(timeoutMs: Long): P010StopBoundaryCleanupResult {
            if (sinkDetached) {
                if (lineageMayBeStarted) freezeCamera2FrameLineageSession()
                val finalizationStarted = !recorderActive || npv10Recording.beginFinalization()
                if (recorderActive && finalizationStarted) {
                    recordingTransitionState.value = GpuRecordingState.Finalizing
                }
                return P010StopBoundaryCleanupResult(
                    sinkDetached = true,
                    finalizationStarted = finalizationStarted
                )
            }
            var ingressQuiescent = false
            var cleanupFailure: Throwable? = null
            try {
                imageIngressSeal = pipeline.sealImageIngressForReaderClose()
                ingressQuiescent = pipeline.awaitIdleBeforeReaderClose(
                    timeoutMs = timeoutMs.coerceAtLeast(1L)
                )
            } catch (failure: Throwable) {
                cleanupFailure = failure
            } finally {
                // If sealing/idle proof fails, publication is already poisoned. Detach so neither a
                // failed fence nor a later repeating replay can target a writer entering finalization.
                pipeline.setRawYuvImageSink(null)
                pipeline.setRawYuvFrameSink(null)
                if (lineageMayBeStarted) freezeCamera2FrameLineageSession()
                sinkDetached = true
            }
            if (!ingressQuiescent && recorderActive) {
                npv10Recording.poisonStopBoundaryFailure(
                    "$poisonReason; pipeline ingress did not quiesce" +
                        cleanupFailure?.let { ": ${it.message}" }.orEmpty()
                )
            }
            val finalizationStarted = !recorderActive || npv10Recording.beginFinalization()
            if (recorderActive && finalizationStarted) {
                recordingTransitionState.value = GpuRecordingState.Finalizing
            }
            return P010StopBoundaryCleanupResult(
                sinkDetached = true,
                finalizationStarted = finalizationStarted && ingressQuiescent
            )
        }

        try {
            if (camera2ProducerMayBeActive && imageSinkMayBeAttached) {
                val exactCaptureStop = camera.stopNpv10P010CaptureAtExactBoundary(
                    timeoutMs = NPV10_P010_STOP_BOUNDARY_TIMEOUT_MS,
                    proveMarkerAccepted = { reader, markerTimestampNs, remainingMs ->
                        pipeline.proveRawYuvImageStopBoundary(
                            reader = reader,
                            markerTimestampNs = markerTimestampNs,
                            timeoutMs = remainingMs
                        )
                    },
                    establishProducerBoundary = npv10Recording::acknowledgeExactProducerBoundary,
                    poisonRecorder = { boundaryFailure ->
                        npv10Recording.poisonStopBoundaryFailure(
                            "$poisonReason; P010 boundary violation: $boundaryFailure"
                        )
                    },
                    sealDetachAndBeginFinalization = ::sealDetachAndBeginFinalization
                )
                if (!exactCaptureStop) {
                    npv10Recording.poisonStopBoundaryFailure(
                        "$poisonReason; exact P010 producer-stop boundary was not proven"
                    )
                }
            } else {
                if (camera2ProducerMayBeActive) {
                    // No callback sink can own a frame yet. Retarget through the ordinary Camera2
                    // lifecycle instead of manufacturing an exact writer boundary that cannot exist.
                    runCatching { camera.stopVideoCapture() }
                        .onFailure { stopFailure ->
                            if (recorderActive) {
                                npv10Recording.poisonStopBoundaryFailure(
                                    "$poisonReason; ordinary pre-sink producer stop failed: " +
                                        (stopFailure.message ?: stopFailure.javaClass.simpleName)
                                )
                            }
                            Log.e(
                                "CameraViewModel",
                                "NPV10 pre-sink producer stop failed during startup abort",
                                stopFailure
                            )
                        }
                }
                // CameraRepeatingRequestTargetPolicy keeps the prearmed P010 output untargeted until
                // recordingActive; without an attached writer sink no exact marker is appropriate.
                sealDetachAndBeginFinalization(NPV10_P010_STOP_BOUNDARY_TIMEOUT_MS)
            }

            if (recorderActive && !npv10Recording.awaitAcceptedFrameDrain()) {
                npv10Recording.poisonStopBoundaryFailure(
                    "$poisonReason; accepted P010 work did not drain"
                )
            }
            val capturedMaster = if (recorderActive) {
                runCatching { npv10Recording.stop() }
                    .onFailure { Log.e("CameraViewModel", "NPV10 startup-abort finalization failed", it) }
                    .getOrNull()
            } else {
                null
            }
            capturedMaster?.let {
                Log.w(
                    "CameraViewModel",
                    "NPV10 failed-start master preserved privately and not published -> ${it.absolutePath}"
                )
            }
        } catch (cleanupFailure: Throwable) {
            if (recorderActive) {
                npv10Recording.poisonStopBoundaryFailure(
                    "$poisonReason; cleanup failed: " +
                        (cleanupFailure.message ?: cleanupFailure.javaClass.simpleName)
                )
            }
            if (!sinkDetached) {
                runCatching { sealDetachAndBeginFinalization(1_000L) }
                    .onFailure { cleanupFailure.addSuppressed(it) }
            }
            if (recorderActive) {
                runCatching { npv10Recording.stop() }
                    .onFailure { cleanupFailure.addSuppressed(it) }
            }
            Log.e("CameraViewModel", "NPV10 startup-abort cleanup failed closed", cleanupFailure)
        } finally {
            npv10AudioRecording.abort()
            npv10AudioRequiredForActiveSession = false
            imageIngressSeal?.close()
            npv10Recording.resetCancelledStartError()
            stopRecordingForegroundService()
        }
    }

    /** Proves both the recording request and a post-activation, timestamp-joined P010 image. */
    private suspend fun awaitNpv10P010ProducerReady(
        size: Size,
        fps: Int,
        imageFormat: Int,
        startupLeaseValid: () -> Boolean,
        minimumPublicationGenerationExclusive: Long? = null,
        timeoutMs: Long = NPV10_SESSION_READY_TIMEOUT_MS
    ): Npv10RecordingStartupAuthorityResult =
        Npv10RecordingStartupAuthorityCoordinator.await(
            size.width, size.height, imageFormat, timeoutMs,
            producerProof = {
                camera.npv10TenBitProducerProof(size, fps)?.takeIf { proof ->
                    minimumPublicationGenerationExclusive == null ||
                        proof.publicationGeneration > minimumPublicationGenerationExclusive
                }
            },
            pixelEvidence = pipeline::rawYuvPixelAuthorityTelemetry,
            startupLeaseValid = startupLeaseValid
        )

    private data class Npv10StabilizedProducer(
        val authority: Npv10RecordingStartupAuthority,
        val cadence: CameraRecordingCadenceAdmissionSnapshot,
        val replayCount: Int
    )

    /**
     * Settles deferred AE/AWB/focus publications before the durable master timeline begins.
     * Every authorized replay is rejoined to an exact P010 pixel and must sustain ten target-rate
     * sensor intervals. No resolution, bit depth, bitrate, or image data is reduced by this gate.
     */
    private suspend fun stabilizeNpv10ProducerBeforeWriter(
        size: Size,
        fps: Int,
        imageFormat: Int,
        startupLease: Npv10ProducerStartupLeaseHandle,
        initialAuthority: Npv10RecordingStartupAuthority
    ): Npv10StabilizedProducer {
        var authority = initialAuthority
        var replayCount = 0
        repeat(NPV10_MAX_STARTUP_CADENCE_PASSES) { pass ->
            currentCoroutineContext().ensureActive()
            check(!startupLease.isRevoked) {
                "NPV10 producer startup lease was revoked during cadence stabilization"
            }

            if (startupLease.replayDeferredRefreshIfPending()) {
                replayCount += 1
                val previousPublication = authority.producerProof.publicationGeneration
                val replayAuthority = awaitNpv10P010ProducerReady(
                    size = size,
                    fps = fps,
                    imageFormat = imageFormat,
                    startupLeaseValid = { !startupLease.isRevoked },
                    minimumPublicationGenerationExclusive = previousPublication
                )
                check(replayAuthority.accepted) {
                    "NPV10 deferred control replay did not restore exact P010 authority: " +
                        replayAuthority.failureReason
                }
                authority = requireNotNull(replayAuthority.authority)
            }

            val cadence = awaitStableCameraCadenceEvidence(fps)
            check(cadence.admitted && !startupLease.isRevoked) {
                "NPV10 producer cadence did not stabilize at ${fps}fps before writer admission"
            }

            if (startupLease.replayDeferredRefreshIfPending()) {
                replayCount += 1
                val previousPublication = authority.producerProof.publicationGeneration
                val replayAuthority = awaitNpv10P010ProducerReady(
                    size = size,
                    fps = fps,
                    imageFormat = imageFormat,
                    startupLeaseValid = { !startupLease.isRevoked },
                    minimumPublicationGenerationExclusive = previousPublication
                )
                check(replayAuthority.accepted) {
                    "NPV10 post-cadence control replay lost exact P010 authority: " +
                        replayAuthority.failureReason
                }
                authority = requireNotNull(replayAuthority.authority)
                return@repeat
            }

            val finalAuthority = awaitNpv10P010ProducerReady(
                size = size,
                fps = fps,
                imageFormat = imageFormat,
                startupLeaseValid = { !startupLease.isRevoked }
            )
            check(finalAuthority.accepted) {
                "NPV10 final producer authority was not stable: ${finalAuthority.failureReason}"
            }
            val verifiedAuthority = requireNotNull(finalAuthority.authority)
            if (verifiedAuthority.producerProof != authority.producerProof) {
                authority = verifiedAuthority
                return@repeat
            }
            if (startupLease.replayDeferredRefreshIfPending()) {
                replayCount += 1
                val previousPublication = verifiedAuthority.producerProof.publicationGeneration
                val replayAuthority = awaitNpv10P010ProducerReady(
                    size = size,
                    fps = fps,
                    imageFormat = imageFormat,
                    startupLeaseValid = { !startupLease.isRevoked },
                    minimumPublicationGenerationExclusive = previousPublication
                )
                check(replayAuthority.accepted) {
                    "NPV10 final control replay lost exact P010 authority: " +
                        replayAuthority.failureReason
                }
                authority = requireNotNull(replayAuthority.authority)
                return@repeat
            }

            Log.i(
                "CameraViewModel",
                "NPV10_STARTUP_CADENCE_STABLE pass=${pass + 1} replayCount=$replayCount " +
                    "frames=${cadence.observedFrames} intervals=${cadence.consecutiveIntervals} " +
                    "measuredFps=${String.format(Locale.US, "%.3f", cadence.measuredFps)} " +
                    "largestGapMs=${cadence.largestGapNs / 1_000_000.0}"
            )
            return Npv10StabilizedProducer(
                authority = verifiedAuthority,
                cadence = cadence,
                replayCount = replayCount
            )
        }
        error(
            "NPV10 controls did not settle within $NPV10_MAX_STARTUP_CADENCE_PASSES cadence passes"
        )
    }

    private suspend fun awaitNpv10P010SessionReady(size: Size, fps: Int): Boolean {
        val signature = npv10CapturePrearmSignature(size, fps)
        val previousRefreshSettled = withTimeoutOrNull(NPV10_SESSION_READY_TIMEOUT_MS) {
            while (camera.sessionRefreshPending.get()) delay(25L)
            true
        } ?: false
        if (!previousRefreshSettled) {
            Log.e("CameraViewModel", "Timed out waiting for previous NPV10 session refresh")
            return false
        }
        if (camera.isNpv10TenBitSessionConfigured(size)) {
            npv10CapturePrearmedSignature = signature
            return true
        }

        val refreshed = CompletableDeferred<Unit>()
        npv10CaptureRefreshSignature = signature
        camera.refreshOutputsAndSession(
            rebuildReaders = true,
            forceRebuild = true,
            reason = "npv10-start-await-prearm"
        ) {
            if (camera.isNpv10TenBitSessionConfigured(size)) {
                npv10CapturePrearmedSignature = signature
            }
            npv10CaptureRefreshSignature = null
            refreshed.complete(Unit)
        }
        val configured = withTimeoutOrNull(NPV10_SESSION_READY_TIMEOUT_MS) {
            refreshed.await()
            while (!camera.isNpv10TenBitSessionConfigured(size)) delay(25L)
            true
        } ?: false
        if (!configured) {
            npv10CaptureRefreshSignature = null
            Log.e(
                "CameraViewModel",
                "NPV10 exact P010 session readiness timed out " +
                    "size=${size.width}x${size.height} fps=$fps"
            )
        }
        return configured
    }

    private fun requestNpv10P010SessionRefresh(capturePrearmed: Boolean, signature: String, size: Size) {
        if (capturePrearmed) {
            Log.i("CameraViewModel", "NPV10 start reused ready/in-flight native P010 Camera2 session")
        } else {
            npv10CaptureRefreshSignature = signature
            camera.refreshOutputsAndSession(
                rebuildReaders = true,
                forceRebuild = true,
                reason = "npv10-record-start"
            ) {
                if (camera.isNpv10TenBitSessionConfigured(size)) npv10CapturePrearmedSignature = signature
                npv10CaptureRefreshSignature = null
                Log.i("CameraViewModel", "NPV10 P010 recording session refresh configured")
            }
        }
    }
    private suspend fun awaitNpv10FirstWrittenFrame(
        startupAuthority: Npv10RecordingStartupAuthority,
        startupIngress: Npv10RecordingStartupIngress,
        timeoutMs: Long = 12_000L
    ): Boolean {
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
            npv10Recording.firstDurableFrameResult(
                minimumProducerTimestampNs =
                    startupAuthority.producerProof.firstFrameSensorTimestampNs,
                firstSinkTimestampNs = startupIngress.firstSinkTimestampNs()
            )?.let { result ->
                return result.fold(
                    onSuccess = { true },
                    onFailure = { Log.e("CameraViewModel", "NPV10 frame-1 durability proof failed", it); false }
                )
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
        val audioTimelineWasPaused = npv10Recording.state.value is GpuRecordingState.Paused
        var previewRestored = false
        var pipelineIngressQuiescent = false
        var imageIngressSeal: AutoCloseable? = null
        try {
            val captureDetachStartedMs = SystemClock.elapsedRealtime()
            // Keep an active AAC lane capturing through Camera2's ordered stop-marker transaction.
            // The exact video duration is unknowable until that fence and every accepted P010 frame
            // have drained. Audio finalization below retains only original, complete AAC packets at
            // the nearest strict 20 ms boundary; it never fabricates or stretches a terminal tail.
            val exactCaptureStop = camera.stopNpv10P010CaptureAtExactBoundary(
                timeoutMs = NPV10_P010_STOP_BOUNDARY_TIMEOUT_MS,
                proveMarkerAccepted = { reader, markerTimestampNs, remainingMs ->
                    pipeline.proveRawYuvImageStopBoundary(
                        reader = reader,
                        markerTimestampNs = markerTimestampNs,
                        timeoutMs = remainingMs
                    )
                },
                establishProducerBoundary = npv10Recording::acknowledgeExactProducerBoundary,
                poisonRecorder = { message ->
                    npv10Recording.poisonStopBoundaryFailure(
                        "NPV10 P010 stop boundary violation: $message"
                    )
                },
                sealDetachAndBeginFinalization = { remainingMs ->
                    // Only the exact marker proof above may close admission. Keep this ordering:
                    // seal callbacks -> prove callback ownership idle -> detach sinks -> finalizing.
                    var cleanupFailure: Throwable? = null
                    try {
                        val seal = pipeline.sealImageIngressForReaderClose()
                        imageIngressSeal = seal
                        pipelineIngressQuiescent = pipeline.awaitIdleBeforeReaderClose(
                            timeoutMs = remainingMs.coerceAtLeast(1L)
                        )
                    } catch (failure: Throwable) {
                        cleanupFailure = failure
                        pipelineIngressQuiescent = false
                    } finally {
                        // The controller releases/replays the fence after this callback. Detach even
                        // when sealing or idle proof fails so replay can never target the live sink.
                        pipeline.setRawYuvImageSink(null)
                        pipeline.setRawYuvFrameSink(null)
                        freezeCamera2FrameLineageSession()
                    }
                    if (!pipelineIngressQuiescent) {
                        npv10Recording.poisonStopBoundaryFailure(
                            "NPV10 pipeline ingress did not quiesce at the P010 stop boundary" +
                                cleanupFailure?.let { ": ${it.message}" }.orEmpty()
                        )
                    }
                    val finalizationStarted = npv10Recording.beginFinalization()
                    if (finalizationStarted) {
                        recordingTransitionState.value = GpuRecordingState.Finalizing
                    }
                    P010StopBoundaryCleanupResult(
                        sinkDetached = true,
                        finalizationStarted = finalizationStarted && pipelineIngressQuiescent
                    )
                }
            )
            check(exactCaptureStop) {
                "NPV10 exact P010 producer-stop boundary failed; verified publication is poisoned"
            }
            check(npv10Recording.awaitAcceptedFrameDrain()) {
                "NPV10 could not release every accepted P010 Image before reader restoration"
            }
            if (npv10AudioRequiredForActiveSession) {
                val videoFrameCount = npv10Recording.writtenFrameCount()
                val videoFps = requireNotNull(npv10Recording.activeFrameRate()) {
                    "NPV10 active frame rate disappeared before A/V finalization"
                }
                val videoDurationUs = Npv10ExportCadencePolicy.endPresentationTimeUs(
                    frameCount = videoFrameCount,
                    fps = videoFps
                )
                val audioAudit = requireNotNull(
                    npv10AudioRecording.stopAndFinalizeAlignedToDuration(
                        targetDurationUs = videoDurationUs,
                        maximumAllowedDeltaUs =
                            Npv10AudioVideoDurationPolicy.MAXIMUM_ALLOWED_DELTA_US,
                        captureUntilAligned = !audioTimelineWasPaused
                    )
                ) {
                    "NPV10 audio was required but produced no durable sidecar"
                }
                check(audioAudit.packetCount > 0L && audioAudit.monotonicPts && audioAudit.crcValidated) {
                    "NPV10 audio sidecar failed its timeline/integrity gate: $audioAudit"
                }
                val audioVideoAudit = Npv10AudioVideoDurationPolicy.audit(
                    videoFrameCount = videoFrameCount,
                    videoFps = videoFps,
                    audioDurationUs = audioAudit.finalPresentationTimeUs
                )
                check(audioVideoAudit.passed) {
                    "NPV10 A/V duration mismatch exceeded " +
                        "${audioVideoAudit.maximumAllowedDeltaUs}us: $audioVideoAudit"
                }
                Log.i(
                    "CameraViewModel",
                    "NPV10 synchronized audio finalized packets=${audioAudit.packetCount} " +
                        "pts=${audioAudit.firstPresentationTimeUs}..${audioAudit.finalPresentationTimeUs} " +
                        "videoDurationUs=${audioVideoAudit.videoDurationUs} " +
                        "deltaUs=${audioVideoAudit.absoluteDeltaUs}"
                )
            }
            RuntimeStageTelemetry.recordDuration(
                tag = "CameraViewModel",
                stage = "stopRecording.npv10CaptureDetach",
                durationMs = SystemClock.elapsedRealtime() - captureDetachStartedMs,
                slowThresholdMs = SLOW_RECORDING_STOP_TOTAL_MS
            )
            if (!viewModelTeardownStarted.get()) {
                val previewRestoreStartedMs = SystemClock.elapsedRealtime()
                restorePreviewAfterNpv10CaptureStop()
                previewRestored = true
                // The old sink is detached and every accepted writer lease is drained. Reader-close
                // work owns its own nested barrier, so normal preview ingress can resume while the
                // private master, evidence, and certificate finish durably in the background.
                imageIngressSeal?.close()
                RuntimeStageTelemetry.recordDuration(
                    tag = "CameraViewModel",
                    stage = "stopRecording.npv10PreviewRestore",
                    durationMs = SystemClock.elapsedRealtime() - previewRestoreStartedMs,
                    slowThresholdMs = SLOW_RECORDING_STOP_TOTAL_MS
                )
            }
            val writerFinalizationStartedMs = SystemClock.elapsedRealtime()
            val output = npv10Recording.stop()
            RuntimeStageTelemetry.recordDuration(
                tag = "CameraViewModel",
                stage = "stopRecording.npv10WriterFinalization",
                durationMs = SystemClock.elapsedRealtime() - writerFinalizationStartedMs,
                slowThresholdMs = SLOW_RECORDING_STOP_TOTAL_MS
            )
            if (output != null && npv10Recording.lastStopPassedDurabilityAudit()) {
                // NPV10 remains the verified private source of truth. A background transactional
                // replica makes the exact master (+ required AAC companion) visible to CamX Studio
                // over standard MTP without enabling USB debugging.
                com.neoproc.neoproccamx.recording.CinemaCamMtpPublicationScheduler.publishNpv10(
                    getApplication(),
                    output
                )
                notifyRecordingSaved(output.absolutePath)
                Log.i("CameraViewModel", "NPV10 private master saved -> ${output.absolutePath}")
            } else if (output != null) {
                val failure = npv10Recording.lastStopDurabilityFailure()
                    ?: "post-record durability audit did not pass"
                Log.e(
                    "CameraViewModel",
                    "NPV10 failed master preserved privately and not announced as saved: " +
                        "path=${output.absolutePath} reason=$failure"
                )
                throw IllegalStateException(
                    "NPV10 master was preserved for recovery but failed publication: $failure"
                )
            } else {
                throw IllegalStateException("NPV10 finalization produced no durable master")
            }
        } catch (t: Throwable) {
            // Emergency cleanup remains fail-closed if an unexpected exception escaped before the
            // camera transaction installed its seal/detach callback.
            npv10Recording.poisonStopBoundaryFailure(
                "NPV10 stop failed before verified publication: " +
                    (t.message ?: t.javaClass.simpleName)
            )
            if (npv10AudioRequiredForActiveSession) {
                npv10AudioRecording.preserveAfterVideoFailure(t)
            }
            if (imageIngressSeal == null) {
                imageIngressSeal = pipeline.sealImageIngressForReaderClose()
                pipelineIngressQuiescent = pipeline.awaitIdleBeforeReaderClose(timeoutMs = 1_000L)
                pipeline.setRawYuvImageSink(null)
                pipeline.setRawYuvFrameSink(null)
                if (npv10Recording.beginFinalization()) {
                    recordingTransitionState.value = GpuRecordingState.Finalizing
                }
            }
            // Failed recordings need the same drain -> restore -> unseal ordering as normal stops.
            // Do not hold preview ingress closed for the full private-master CRC/SHA audit below.
            // The failed producer boundary remains poisoned; restoring preview never certifies or
            // publishes this recording, releases its finalization owner, or lowers any quality gate.
            runCatching {
                previewRestored = Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
                    alreadyRestored = previewRestored,
                    ingressQuiescent = pipelineIngressQuiescent,
                    teardownStarted = viewModelTeardownStarted::get,
                    drainAcceptedFrames = npv10Recording::awaitAcceptedFrameDrain,
                    restorePreview = ::restorePreviewAfterNpv10CaptureStop,
                    releaseIngress = {
                        imageIngressSeal?.close()
                        imageIngressSeal = null
                    }
                )
            }.onFailure { cleanupFailure -> t.addSuppressed(cleanupFailure) }
            runCatching { npv10Recording.stop() }
                .onFailure { cleanupFailure -> t.addSuppressed(cleanupFailure) }
            handleCameraError(
                throwable = t,
                operation = "stopNpv10Recording",
                userVisibleMessage = "Custom packed-10 NPV10 stop failed; cleanup is continuing."
            )
        } finally {
            npv10AudioRequiredForActiveSession = false
            // Keep foreground execution and the non-reference-counted wake lock until every accepted
            // frame, container tail, integrity record, and quality certificate is durable. Stopping
            // protection earlier allowed Android sleep/teardown during finalization after an error.
            stopRecordingForegroundService()
            if (!previewRestored && pipelineIngressQuiescent && !viewModelTeardownStarted.get()) {
                runCatching { restorePreviewAfterNpv10CaptureStop() }
                    .onFailure { restoreFailure ->
                        Log.e("CameraViewModel", "NPV10 preview restore failed during stop cleanup", restoreFailure)
                    }
            }
            imageIngressSeal?.close()
            RuntimeStageTelemetry.recordDuration(
                tag = "CameraViewModel",
                stage = "stopRecording.npv10",
                durationMs = SystemClock.elapsedRealtime() - recordingStopElapsedMs,
                slowThresholdMs = SLOW_RECORDING_STOP_TOTAL_MS
            )
        }
    }
    private suspend fun restorePreviewAfterNpv10CaptureStop() {
        if (viewModelTeardownStarted.get()) return
        applyRecordingCriticalRuntimeHints(recordingActive = false)
        camera.setRawVideoCaptureEnabled(false)
        clearRecordingReadoutOverride()
        val keepNpv10Prearmed = shouldUseUncompressedCinemaPipeline()
        camera.setNpv10TenBitCaptureEnabled(keepNpv10Prearmed)
        if (keepNpv10Prearmed) {
            resolveNpv10CaptureSourcePlanForStableStandby(preferredRecordingSize()).size.also { size ->
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
        if (keepNpv10Prearmed) {
            // The exact P010 stop fence deliberately used stopRepeating() without retargeting the
            // settled session. An unchanged prearmed graph does not enter the session-refresh path,
            // so restart its repeating request explicitly instead of waiting for the health watchdog.
            camera.refreshRepeatingRequest()
        }
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
        recordingModeLabel: String,
        startupAuthority: Npv10RecordingStartupAuthority,
        cadenceAuthority: CameraRecordingCadenceAdmissionSnapshot,
        startupReplayCount: Int,
        audioStoredInBundle: Boolean
    ): Map<String, String> {
        val previewLookState = activeVideoPreviewLookState()
        val niv99Snapshot = pipeline.niv99LiveCaptureSnapshot()
        val noiseMetrics = currentSceneRenderProfile?.noiseMetrics ?: VideoNoiseMetrics.EMPTY
        val normalizedHdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(_videoHdrMode.value)
        val bt2100PqActive = previewLookState.hdrEnabled &&
            Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrMode)
        val hlgActive = previewLookState.hdrEnabled &&
            previewLookState.hdrMode.equals(Bt2100TransportPolicy.LEGACY_HLG_MODE, ignoreCase = true)
        val wideGamutSdr = !previewLookState.hdrEnabled &&
            !previewLookState.logEnabled &&
            isWideGamutVideoSpace(manualColorPlan.selectedColorSpace)
        val cameraDynamicRangeMode = activeVideoModePipelineContract().cameraDynamicRangeMode
        val manualWhiteBalanceActive =
            CameraManualControlStateSyncPolicy.currentWhiteBalanceKelvin().isManual ||
                CameraManualControlStateSyncPolicy.currentWhiteBalanceTint().isManual
        val awbLockActive = camera.isAeAwbLocked()
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
            "niv99Engine" to "NIV99",
            "niv99DefaultEnabled" to
                Niv99ExecutionBoundaryPolicy.ANY_LIVE_PROCESSING_ENABLED.toString(),
            "niv99ExecutionBoundary" to Niv99ExecutionBoundaryPolicy.TECHNOLOGY_ID,
            "niv99LiveCaptureMetadataEnabled" to
                Niv99ExecutionBoundaryPolicy.LIVE_CAPTURE_METADATA_ENABLED.toString(),
            "niv99LiveAnalysisEnabled" to
                Niv99ExecutionBoundaryPolicy.LIVE_ANALYSIS_ENABLED.toString(),
            "niv99LivePreviewPixelProcessingEnabled" to
                Niv99ExecutionBoundaryPolicy.LIVE_PREVIEW_PIXEL_PROCESSING_ENABLED.toString(),
            "niv99LiveRecordingPixelProcessingEnabled" to
                Niv99ExecutionBoundaryPolicy.LIVE_RECORDING_PIXEL_PROCESSING_ENABLED.toString(),
            "niv99OfflineConversionEnabled" to
                Niv99ExecutionBoundaryPolicy.OFFLINE_CONVERSION_ENABLED.toString(),
            "niv99RecordingRequired" to "false",
            "niv99RecordingPixelPath" to "BIT_EXACT_P010_MASTER",
            "niv99OfflineConversionRequired" to "true",
            "niv99OfflineConversionPixelPath" to "NIV99_P010_FULL_FRAME_V1",
            "niv99UntreatedFallbackAllowed" to "false",
            "niv99TemporalFusion" to "DISABLED_ALIGNMENT_UNPROVEN",
            "niv99ManualWbAuthority" to "ABSOLUTE",
            "niv99AwbLockAuthority" to "ABSOLUTE",
            "manualWhiteBalanceActive" to manualWhiteBalanceActive.toString(),
            "awbLockActive" to awbLockActive.toString(),
            "whiteBalanceAuthority" to if (manualWhiteBalanceActive) {
                "MANUAL"
            } else if (awbLockActive) {
                "AWB_LOCK"
            } else {
                "AUTO"
            },
            "niv99InitialProcessingStatus" to niv99Snapshot.processingStatus,
            "niv99InitialCameraId" to niv99Snapshot.configuredCameraId,
            "niv99InitialSessionGeneration" to niv99Snapshot.configuredGeneration.toString(),
            "niv99InitialPreviewPixelAppliedFrames" to
                niv99Snapshot.previewPixelAppliedFrames.toString(),
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
            "npv10StartupCadenceAdmitted" to cadenceAuthority.admitted.toString(),
            "npv10StartupCadenceObservedFrames" to cadenceAuthority.observedFrames.toString(),
            "npv10StartupCadenceConsecutiveIntervals" to
                cadenceAuthority.consecutiveIntervals.toString(),
            "npv10StartupCadenceRejectedIntervals" to
                cadenceAuthority.rejectedIntervals.toString(),
            "npv10StartupCadenceMeasuredFps" to
                String.format(Locale.US, "%.6f", cadenceAuthority.measuredFps),
            "npv10StartupCadenceLastGapNs" to cadenceAuthority.lastGapNs.toString(),
            "npv10StartupCadenceLargestGapNs" to cadenceAuthority.largestGapNs.toString(),
            "npv10StartupControlReplayCount" to startupReplayCount.toString(),
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
            "cameraDynamicRangeMode" to cameraDynamicRangeMode.name,
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
            "manualIso" to prefs.lastISO.toString(),
            "manualExposureNs" to prefs.lastShutterNs.toString(),
            "manualWhiteBalanceKelvin" to prefs.lastWBKelvin.toString(),
            "audioSourceSetting" to recording.audioSource.name,
            "audioStoredInNpv10" to "false",
            "audioStoredInNpv10Bundle" to audioStoredInBundle.toString(),
            "audioTimelinePolicy" to if (audioStoredInBundle) {
                "CAMERA_FIRST_SENSOR_TIMESTAMP_TO_AAC_SAMPLE_COUNT"
            } else {
                "DISABLED_OR_PERMISSION_UNAVAILABLE"
            },
            "codecSetting" to recording.codec.name,
            "bitrateModeSetting" to _videoBitrateMode.value.name,
            "bitrateSetting" to recording.bitrate.toString()
        ).apply {
            putAll(manualColorPlan.toMetadata())
            // Requested display intent remains above; these fields alone describe stored P010 codes.
            putAll(startupAuthority.toMetadata())
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
        if (recordingStopRestartLifecycle.isStopInProgress) {
            // A second shutter during cancellation/finalization is a restart intent, not another
            // stop. The generation coordinator coalesces repeated taps and replays exactly once.
            startRecording()
            return
        }
        if (recordingStopRestartLifecycle.isStarting) {
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

    private suspend fun detachRecordingSurfaceBeforeEncoderStop(
        allowPersistentPrearmRetention: Boolean = true
    ): RecordingOperationOutcome {
        return try {
            val previousSessionClosed = CompletableDeferred<Unit>()
            val keepPrearmedSessionSurface =
                allowPersistentPrearmRetention &&
                    RecordingPersistentInputSurfacePolicy.shouldSkipSessionRebuildForPrearmedSurface(
                    surfaceMatches = prearmedRecordingSurface != null,
                    surfaceAttachedToCameraSession = prearmedRecordingSurfaceAttachedToSession
                )
            updatePreviewViewAwait { setEncoderFeedEnabled(false) }
            if (!detachAllGpuEncoderSurfacesWithProof("camera2-recording-stop")) {
                return RecordingOperationOutcome.Failure(
                    operation = "camera.detachRecordSurfaceBeforeEncoderStop",
                    reason = "GPU encoder detach was not acknowledged; " +
                        "codec and Surface ownership remain retained"
                )
            }
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
                camera.refreshOutputsAndSession(
                    rebuildReaders = false,
                    forceRebuild = true,
                    reason = "recording-surface-detach",
                    onPreviousSessionClosed = { previousSessionClosed.complete(Unit) }
                )
                val completed = withTimeoutOrNull(750L) {
                    previousSessionClosed.await()
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
    }

    private suspend fun detachInternalRecordingFeedBeforeEncoderStop(
        deferHardwareSourceRebuildUntilAfterEncoderStop: Boolean = false
    ): RecordingOperationOutcome {
        var imageIngressSeal: AutoCloseable? = null
        return try {
            // Keep the idle observation stable through the complete encoder-detach transaction.
            // Without this seal, a synchronous ImageReader callback can acquire one more image after
            // awaitIdleBeforeReaderClose() succeeds but before the offscreen submission gate closes.
            imageIngressSeal = pipeline.sealImageIngressForReaderClose()
            pipeline.persistHardwareImportLifecycleEvidence("pre-stop-detach")
            vulkanRuntime.persistOffscreenEncoderTransportEvidence("pre-stop-detach")
            preserveValidatedGpuRecordingEvidence(vulkanImportStats.value)
            // The ingress seal blocks every new synchronous ImageReader acquisition. Keep the P010
            // route and its precision contract active until work accepted before that seal has crossed
            // the EGL encoder. Demoting the route before this drain made that final P010 image enter the
            // generic 8-bit path and fail strict frame-integrity validation.
            var hardwareDispatcherIdle = pipeline.awaitIdleBeforeReaderClose(
                timeoutMs = if (deferHardwareSourceRebuildUntilAfterEncoderStop) 2_000L else 750L
            )
            if (!hardwareDispatcherIdle) {
                Log.w(
                    "CameraViewModel",
                    "Recording stop: hardware-import dispatcher still busy before encoder EOS"
                )
                if (deferHardwareSourceRebuildUntilAfterEncoderStop && RecordStopSubmitQuiesceFlag.enabled) {
                    val submitQuiesced = pipeline.awaitHardwareSubmitQuiescent(
                        timeoutMs = RecordStopSubmitQuiesceFlag.QUIESCE_TIMEOUT_MS
                    )
                    hardwareDispatcherIdle = submitQuiesced && pipeline.awaitIdleBeforeReaderClose(
                        timeoutMs = 750L
                    )
                    if (!hardwareDispatcherIdle) {
                        Log.w(
                            "CameraViewModel",
                            "Recording stop: native hardware submit still in flight after bounded quiesce poll"
                        )
                    }
                }
            }
            if (!hardwareDispatcherIdle) {
                return RecordingOperationOutcome.Failure(
                    operation = "internalHardwareImport.drainBeforeEncoderStop",
                    reason = "sealed hardware-import work did not quiesce; encoder ownership was retained"
                )
            }
            // No callback can enter while the seal is held and every pre-seal frame is now committed.
            // Freeze explicit clip-loss admission at this exact tail boundary. Keep accumulated
            // evidence and retired-generation frame identities alive for late MediaCodec/muxer commits.
            pipeline.sealRecordingLossAccounting()
            // Close the encoder admission gate before changing the Camera2/P010 routing contract.
            if (deferHardwareSourceRebuildUntilAfterEncoderStop) {
                vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
            }
            pipeline.setDedicatedHardwareImportEnabled(false)
            camera.setInternalRecordingHardwareImportEnabled(false)
            camera.setHardwareImportP010SourceEnabled(false)
            camera.setHardwareImportTargetSize(null)
            val hardwareSourceDetached = if (deferHardwareSourceRebuildUntilAfterEncoderStop) {
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
            pipeline.persistHardwareImportLifecycleEvidence("post-tail-drain")
            vulkanRuntime.persistOffscreenEncoderTransportEvidence("post-tail-drain")
            vulkanRuntime.setFrameImportEnabled(false)
            val gpuEncoderDetached =
                detachAllGpuEncoderSurfacesWithProof(
                    if (deferHardwareSourceRebuildUntilAfterEncoderStop) {
                        "recording-stop"
                    } else {
                        "internal-feed-detach"
                    }
                )
            if (!gpuEncoderDetached) {
                RecordingOperationOutcome.Failure(
                    operation = "internalHardwareImport.detachGpuEncodersBeforeStop",
                    reason = "core/offscreen GPU encoder detach was not acknowledged; " +
                        "codec and Surface ownership remain retained"
                )
            } else {
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
            }
        } catch (e: Exception) {
            RecordingOperationOutcome.Failure(
                operation = "internalHardwareImport.detachBeforeEncoderStop",
                reason = e.message ?: "unknown internal recording feed detach error",
                cause = e
            )
        } finally {
            imageIngressSeal?.close()
        }
    }

    private fun stopRawCaptureIfNeeded(): RawCaptureStopResult {
        if (!rawPreviewPresentationCoordinator.callbacksActive ||
            rawVideoManager.state.value !is com.neoproc.neoproccamx.pipeline.RawVideoState.Capturing
        ) {
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

    /**
     * Applies a preset's effective Camera2 control state without treating a rail/lifecycle change as
     * a user edit. Pro restores durable intent; other capture presets receive temporary Auto while
     * the saved Pro values and authoritative manual snapshot remain untouched.
     */
    internal fun applyManualControlsForPreset(preset: CameraModePreset) {
        invalidatePendingPreferenceRestore()
        when (ManualControlPresetRuntimePolicy.applicationFor(preset)) {
            ManualControlPresetApplication.RESTORE_PRO_INTENT -> {
                val restorePlan = resolvePersistedManualControlPlan()
                CameraManualControlStateSyncPolicy.applyRestorePlan(restorePlan)
                applyProManualControlPlanToCamera(restorePlan)
            }
            ManualControlPresetApplication.TEMPORARY_AUTO ->
                applyTemporaryAutomaticControlsToCamera()
        }
    }

    fun setISO(iso: Int) {
        invalidatePendingPreferenceRestore()
        val resolvedIso = CameraManualControlStateSyncPolicy.clampIso(iso, camera.effectiveIsoRange)
        CameraManualControlStateSyncPolicy.setIso(resolvedIso, camera.effectiveIsoRange)
        prefs.lastISO = resolvedIso
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_ISO_KEY) {
            camera.setISO(resolvedIso)
        }
    }
    fun setAutoISO() {
        invalidatePendingPreferenceRestore()
        CameraManualControlStateSyncPolicy.setIsoAuto()
        prefs.lastISO = -1
        dispatchManualControlUpdate { camera.setAutoISO() }
    }
    fun setExposure(ns: Long) {
        invalidatePendingPreferenceRestore()
        val resolvedExposure = CameraManualControlStateSyncPolicy.clampExposureNs(ns, camera.exposureRange)
        CameraManualControlStateSyncPolicy.setExposureNs(resolvedExposure, camera.exposureRange)
        prefs.lastShutterNs = resolvedExposure
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_EXPOSURE_KEY) {
            camera.setExposure(resolvedExposure)
        }
        updateShutterRuleGuidance()
    }
    fun setAutoExposure() {
        invalidatePendingPreferenceRestore()
        CameraManualControlStateSyncPolicy.setExposureAuto()
        prefs.lastShutterNs = -1L
        dispatchManualControlUpdate { camera.setAutoExposure() }
        updateShutterRuleGuidance()
    }
    fun setWB(kelvin: Int) = setWB(kelvin, prefs.lastWBTint)

    /** Manual white balance with an explicit green-magenta tint (P0.1 Pro-Control accuracy). */
    fun setWB(kelvin: Int, tint: Int) {
        invalidatePendingPreferenceRestore()
        val resolvedKelvin = CameraManualControlStateSyncPolicy.clampWhiteBalanceKelvin(
            kelvin,
            camera.manualWhiteBalanceRange
        )
        CameraManualControlStateSyncPolicy.setWhiteBalanceKelvin(resolvedKelvin)
        CameraManualControlStateSyncPolicy.setWhiteBalanceTint(tint)
        val resolvedTint =
            (CameraManualControlStateSyncPolicy.currentWhiteBalanceTint() as ManualControlValue.Manual)
                .toInt()
        prefs.lastWBKelvin = resolvedKelvin
        prefs.lastWBTint = resolvedTint
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_WHITE_BALANCE_KEY) {
            camera.setWB(resolvedKelvin, resolvedTint)
        }
    }
    fun setAutoWB() {
        invalidatePendingPreferenceRestore()
        CameraManualControlStateSyncPolicy.setWhiteBalanceAuto()
        prefs.lastWBKelvin = -1
        prefs.lastWBTint = 0
        dispatchManualControlUpdate { camera.setAutoWB() }
    }
    fun setFocusDist(d: Float)  {
        invalidatePendingPreferenceRestore()
        CameraManualControlStateSyncPolicy.setFocusDistance(d)
        val resolvedDistance =
            (CameraManualControlStateSyncPolicy.currentFocusDistance() as ManualControlValue.Manual)
                .toFloat()
        prefs.lastFocusDistance = resolvedDistance
        applyManualFocusRuntime(resolvedDistance)
    }
    fun setAutoFocus()          {
        invalidatePendingPreferenceRestore()
        CameraManualControlStateSyncPolicy.setFocusAuto()
        prefs.lastFocusDistance = -1f
        applyAutomaticFocusRuntime()
    }

    private fun applyManualFocusRuntime(distance: Float) {
        releaseCinemaAutoLock(resetCameraState = false, reason = "manual_focus_distance")
        aiFocusResumeJob?.cancel()
        autoFocusPrimeJob?.cancel()
        tapToFocusHoldUntilElapsedMs = 0L
        aiAfCoordinator.setEnabled(false)
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_FOCUS_DISTANCE_KEY) {
            camera.clearFocusRegion()
            camera.setFocusDist(distance)
        }
    }

    private fun applyAutomaticFocusRuntime() {
        releaseCinemaAutoLock(resetCameraState = false, reason = "manual_autofocus")
        tapToFocusHoldUntilElapsedMs = 0L
        manualFocusOverride.value = null
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
        invalidatePendingPreferenceRestore()
        CameraManualControlStateSyncPolicy.setExposureCompensation(ev.toFloat())
        val resolvedEv =
            (CameraManualControlStateSyncPolicy.currentExposureCompensation() as ManualControlValue.Manual)
                .toInt()
        prefs.lastEV = resolvedEv
        dispatchLatestManualControlUpdate(CameraUiResponsivenessPolicy.MANUAL_EV_KEY) {
            camera.setEV(resolvedEv)
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
        val proposedCommit = proposedSettingsCommit(fps = fps)
        if (!admitSettingsCommit(proposedCommit, source = "frame-rate")) return
        if (!admitExactProcessedRecordingSizeRate(proposedCommit, source = "frame-rate")) return
        val requestedFps = resolveContractRequestedFps(fps)
        val frameRatePlan = RecordingPreviewFrameRatePolicy.resolve(lastRecordingSelection, requestedFps)
        if (requestedFps != fps || frameRatePlan.encoderFps != fps) {
            rejectSettingsRequest(
                ruleId = "FPS_COMBINATION_CANNOT_BE_PRESERVED",
                reason = "The active resolution and pipeline cannot preserve exactly $fps FPS",
                source = "frame-rate",
                commit = proposedCommit
            )
            return
        }
        invalidatePendingPreferenceRestore()
        recording.fps = frameRatePlan.encoderFps
        gpuRecording.videoFps = frameRatePlan.encoderFps
        previewViewRef?.setEncoderFrameRate(frameRatePlan.encoderFps)
        prefs.videoFps = frameRatePlan.encoderFps
        syncTaskAwareLensSelectionHint()
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
        val normalizedRequestedSize = size.normalizeForOrientation()
        val previousFps = recording.fps
        val resolutionCompatibleFps = SettingsOptionCatalog.selectedFpsForResolution(
            currentFps = previousFps,
            size = normalizedRequestedSize
        )
        val proposedCommit = proposedSettingsCommit(
            resolution = normalizedRequestedSize,
            fps = resolutionCompatibleFps
        )
        if (!admitSettingsCommit(proposedCommit, source = "video-resolution")) return
        val sanitizedSize = sanitizeRequestedVideoResolutionForCinemaMode(normalizedRequestedSize)
        if (sanitizedSize != normalizedRequestedSize) {
            rejectSettingsRequest(
                ruleId = "RESOLUTION_REQUIRES_SILENT_SUBSTITUTION",
                reason = "${normalizedRequestedSize.width}x${normalizedRequestedSize.height} cannot be applied exactly in the active mode",
                source = "video-resolution",
                commit = proposedCommit
            )
            return
        }
        val advertisedSizes = (
            if (proposedCommit.mode == CinemaVideoMode.RAW) camera.rawSizes else availableVideoSizes()
        ).map { it.normalizeForOrientation() }.toSet()
        if (advertisedSizes.isNotEmpty() && normalizedRequestedSize !in advertisedSizes) {
            rejectSettingsRequest(
                ruleId = "RESOLUTION_NOT_ADVERTISED_BY_CAMERA",
                reason = "${normalizedRequestedSize.width}x${normalizedRequestedSize.height} is not advertised by the active camera",
                source = "video-resolution",
                commit = proposedCommit
            )
            return
        }
        if (!admitExactProcessedRecordingSizeRate(proposedCommit, source = "video-resolution")) return
        val resolvedSelection = resolveRecordingSelection(
            preferred = sanitizedSize,
            explicitResolutionSelection = true,
            requestedFps = resolutionCompatibleFps
        )
        val resolvedOutput = resolvedSelection.size.normalizeForOrientation()
        if (
            resolvedOutput != normalizedRequestedSize ||
            !resolvedSelection.exactOutputSupported ||
            resolvedSelection.upscaledToRequested
        ) {
            rejectSettingsRequest(
                ruleId = "RESOLUTION_CANNOT_BE_PRESERVED_EXACTLY",
                reason = "${normalizedRequestedSize.width}x${normalizedRequestedSize.height} is not an exact " +
                    if (resolvedSelection.requiresMediaEncoder) "camera and encoder output" else "selected HAL RAW stream",
                source = "video-resolution",
                commit = proposedCommit
            )
            return
        }
        invalidatePendingPreferenceRestore()
        val appliedSize: Size
        synchronized(recordingSelectionMutationLock) {
            recordingResolutionSelectionAuthority.claim(resolvedSelection.requestedSize)
            applyResolvedRecordingConfig(
                resolvedSelection = resolvedSelection,
                fps = resolutionCompatibleFps,
                bitrate = resolveConfiguredBitrate(resolvedSelection, resolutionCompatibleFps),
                audioSource = recording.audioSource
            )
            prefs.videoSize = resolvedSelection.requestedSize
            prefs.videoFps = resolutionCompatibleFps
            prefs.videoCodec = recording.codec.name
            appliedSize = resolvedSelection.requestedSize
        }
        if (resolutionCompatibleFps != previousFps) {
            Log.i(
                "CameraViewModel",
                "Recording cadence changed atomically from ${previousFps}fps to " +
                    "${resolutionCompatibleFps}fps for ${appliedSize.width}x${appliedSize.height}"
            )
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
        configureModeSwitchStandbyAnalysisOutput()
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
    ): Size = CameraRecordingSelectionCoordinator.sanitizeRequestedSize(
        requestedSize, _uiMode.value, mode, ::availableVideoSizes
    )

    private fun enforceRawVideoResolutionCapIfNeeded() {
        if (_uiMode.value != AppMode.VIDEO || _cinemaVideoMode.value != CinemaVideoMode.RAW) return
        val currentRequested = (selectedVideoSize ?: prefs.videoSize).normalizeForOrientation()
        val cappedRequested = sanitizeRequestedVideoResolutionForCinemaMode(
            requestedSize = currentRequested,
            mode = CinemaVideoMode.RAW
        )
        if (cappedRequested != currentRequested) {
            setVideoResolution(cappedRequested)
        }
        // If the size is already RAW-safe, applyCinemaModeSelection() owns the one fingerprinted
        // Camera2 refresh after every RAW/readout flag has settled. Rebinding the unchanged visible
        // preview here only repeats size/owner/EGL work and used to stall the mode-switch frame.
    }
    fun setVideoBitrate(bps: Int)           {
        val proposedCommit = proposedSettingsCommit(
            bitrateMode = VideoBitrateMode.CUSTOM,
            customBitrateBps = bps
        )
        if (!admitSettingsCommit(proposedCommit, source = "video-bitrate")) return
        invalidatePendingPreferenceRestore()
        prefs.videoBitrateMode = VideoBitrateMode.CUSTOM
        syncRecordingBitrateMode(VideoBitrateMode.CUSTOM)
        applyVideoBitrateValue(bps)
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }
    fun setVideoBitrateMode(mode: VideoBitrateMode) {
        val proposedCommit = proposedSettingsCommit(bitrateMode = mode)
        if (!admitSettingsCommit(proposedCommit, source = "video-bitrate-mode")) return
        invalidatePendingPreferenceRestore()
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
        val proposedCommit = proposedSettingsCommit(codec = c)
        if (!admitSettingsCommit(proposedCommit, source = "video-codec")) return
        invalidatePendingPreferenceRestore()
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
        val proposedCommit = proposedSettingsCommit(encodedTenBitRequested = enabled)
        if (!admitSettingsCommit(proposedCommit, source = "video-bit-depth")) return
        invalidatePendingPreferenceRestore()
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
        invalidatePendingPreferenceRestore()
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
        invalidatePendingPreferenceRestore()
        applyPreviewAspectRatio(aspectRatio, persist = true)
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
        syncPreviewAspectPresentationTargets()
        // Preview aspect is presentation metadata, not a Camera2 transport mutation. The preview
        // transport remains sensor-native and the existing Surface stays owned by its current
        // producer; rebuilding it here abandoned the live EGL/Camera2 queue on 1:1 transitions.
        Log.i(
            "LQ22Aspect",
            "aspect=${resolvedAspect.label} transport=${boundPreviewSize?.width}x${boundPreviewSize?.height} " +
                "sessionRebuild=false surfaceValid=${previewSurface?.let { surface ->
                    runCatching { surface.isValid }.getOrDefault(false)
                } ?: false}"
        )
        syncPreviewDomainFromLegacy()
    }

    fun setDeviceRotationDegrees(degrees: Int) {
        val normalizedDegrees = ((degrees % 360) + 360) % 360
        currentRotationDegrees = normalizedDegrees
        // NDR owns a producer queue that is intentionally independent from Camera2/Vulkan. Publish
        // its sampling orientation before the wider geometry transaction so a busy secondary preview
        // domain can never leave the last portrait matrix visible after a physical quarter-turn.
        // applyFrameGeometry() reconciles the same value again after Camera2 publishes new geometry.
        publishProjectOwnedNdrOrientation(
            latestDeviceRotationDegrees = normalizedDegrees,
            geometry = camera.frameGeometry.value
        )
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
        if (modeSwitchCriticalDepth.get() > 0 && !modeSwitchFlushInProgress) {
            modeSwitchPreviewWorkCoalescer.requestCompositorRefresh(rebuildLut = true)
            return
        }
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
        // A neutral Natural profile legitimately returns no combined ColorScience cube. Resolve that
        // through the calibrated project film-print LUT; LOG/RAW/HDR/NDR remain excluded.
        // A strict 65^3 cube can take seconds to generate on a mobile CPU. Capture every decision here,
        // then resolve the exact same full-quality recorder + preview cubes in the serialized worker
        // below. Settings dispatch must never execute cube generation on Android's main thread.
        val recordLutRequest = SdrRecordLutResolutionRequest(
            videoSurface = videoSurface,
            isSdrRecordDelivery = isSdrRecordDelivery,
            recordDevelopLightMaster = recordDevelopLightMaster,
            fullColorRecord = fullColorRecord,
            userCreativeLutEnabled = _lutEnabled.value,
            calibratedFallbackEnabled = FilmPrintLookRecordFlag.enabled
        )
        activeSdrVideoColorParityEligible = isSdrRecordDelivery
        val recordGrade = RecordGradeBaker.bake(
            saturation = if (fullColorRecord) 1f else colorScience.saturation,
            contrast = if (fullColorRecord) 1f else colorScience.contrast,
            warmth = 0f,
            isNeutral = recordDevelopLightMaster || fullColorRecord || colorScience.isNeutralLook() || !isSdrRecordDelivery
        )
        vulkanRuntime.setOffscreenRecordGrade(recordGrade.matrix, recordGrade.contrast)
        if (pendingPreviewLutRequest.getAndSet(request) == request) return
        val reuseCachedResult = request == cachedPreviewLutRequest
        val publicationSequence = nextPreviewLutPublicationSequence()
        previewLutCoordinator.submit(
            PreviewLutResolutionWork(
                request, previewFpsHint, recordingActive, reuseCachedResult,
                cachedPreviewLut.takeIf { reuseCachedResult }, publicationSequence,
                recordLutRequest
            )
        )
    }

    private fun applyResolvedSdrRecordLut(
        request: SdrRecordLutResolutionRequest,
        recordLut: Pair<FloatArray, Int>?
    ) {
        val applicableLut = recordLut.takeIf {
            request.isSdrRecordDelivery && !request.recordDevelopLightMaster
        }
        vulkanRuntime.updateRecordCreativeLut(
            applicableLut?.first,
            applicableLut?.second ?: 0
        )
        // The recorder's exact cube is the WYSIWYG preview authority. Publication occurs before the
        // matching preview cube below, so the compositor can never pair a new preview with an old file look.
        activeSdrRecordCreativeLut = applicableLut
        activeSdrVideoColorParityEligible = request.isSdrRecordDelivery
    }

    private fun previewLutPublicationBlockedByRecording(): Boolean =
        viewModelTeardownStarted.get() ||
            lifecyclePreferenceMutationBlockedByRecording() ||
            vulkanRuntime.isSensorLinearNdrRecordingDemosaicActive()

    private fun applyResolvedPreviewLut(
        publicationSequence: Long,
        lut: Pair<FloatArray, Int>?
    ) {
        if (publicationSequence != previewLutPublicationSequence.get()) {
            Log.i(
                "CameraViewModel",
                "PREVIEW_LUT_PUBLICATION_STALE sequence=$publicationSequence " +
                    "latest=${previewLutPublicationSequence.get()}"
            )
            return
        }
        if (previewLutPublicationBlockedByRecording()) {
            recordingSafePreviewLutPublicationGate.deferLatest(publicationSequence, lut)
            Log.i(
                "CameraViewModel",
                "PREVIEW_LUT_PUBLICATION_DEFERRED sequence=$publicationSequence " +
                    "recording owns render-profile identity"
            )
            return
        }
        recordingSafePreviewLutPublicationGate.discardThrough(publicationSequence)
        if (publicationSequence != previewLutPublicationSequence.get()) return
        if (previewLutPublicationBlockedByRecording()) {
            recordingSafePreviewLutPublicationGate.deferLatest(publicationSequence, lut)
            return
        }
        applyResolvedPreviewLutNow(lut)
    }

    private fun replayDeferredPreviewLutPublicationIfSafe() {
        val latestSequence = previewLutPublicationSequence.get()
        val publication = recordingSafePreviewLutPublicationGate.claimLatestIfSafe(
            blocked = previewLutPublicationBlockedByRecording(),
            latestSequence = latestSequence
        ) ?: return
        Log.i(
            "CameraViewModel",
            "PREVIEW_LUT_PUBLICATION_REPLAYED sequence=${publication.sequence} " +
                "after recording render-profile drain"
        )
        // Re-enter the complete gate: a retained restart or teardown may have claimed ownership
        // after the publication was removed from the deferred slot.
        applyResolvedPreviewLut(publication.sequence, publication.copyForApply())
    }

    private fun applyResolvedPreviewLutNow(lut: Pair<FloatArray, Int>?) {
        val lutData = lut?.first
        val lutSize = lut?.second ?: 0
        val desiredEnabled = lutData != null && lutSize > 1
        val contentHash = if (desiredEnabled) lutData.contentHashCode() else 0
        val previous = nativePreviewLutState.get()
        var publication: NativePreviewLutPublication
        if (
            previous.enabled != desiredEnabled ||
            previous.size != lutSize ||
            previous.contentHash != contentHash
        ) {
            var resolved: NativePreviewLutPublication? = null
            advanceVulkanRenderProfileGeneration(
                reason = "preview-lut-publication",
                authorityMutation = { generation ->
                    val acceptedPublication = checkNotNull(resolved) {
                        "Preview LUT upload result missing for generation $generation"
                    }
                    nativePreviewLutState.set(acceptedPublication.snapshot)
                },
                nativeGenerationMutation = { generation ->
                    val nativeAccepted = vulkanRuntime.updatePreviewLut(
                        lutData = lutData,
                        size = lutSize,
                        renderProfileGeneration = generation
                    )
                    val nativeDisableAccepted = if (
                        desiredEnabled && !nativeAccepted && previous.enabled
                    ) {
                        vulkanRuntime.updatePreviewLut(
                            lutData = null,
                            size = 0,
                            renderProfileGeneration = generation
                        )
                    } else {
                        null
                    }
                    resolved = NativePreviewLutPublicationPolicy.resolve(
                        desiredEnabled = desiredEnabled,
                        desiredSize = lutSize,
                        desiredContentHash = contentHash,
                        renderProfileGeneration = generation,
                        nativeUploadAccepted = nativeAccepted,
                        previousNativeSnapshot = previous,
                        nativeDisableAcceptedAfterFailure = nativeDisableAccepted
                    )
                    if (desiredEnabled && !nativeAccepted) {
                        Log.w(
                            "CameraViewModel",
                            "PREVIEW_LUT_NATIVE_UPLOAD_REJECTED generation=$generation " +
                                "size=$lutSize disableAccepted=$nativeDisableAccepted " +
                                "softwareFallback=${resolved.softwareFallbackRequired}"
                        )
                    }
                }
            )
            publication = checkNotNull(resolved)
        } else {
            val authority = currentVulkanRenderProfileAuthority()
            val nativeAccepted = vulkanRuntime.updatePreviewLut(
                lutData = lutData,
                size = lutSize,
                renderProfileGeneration = authority.generation
            )
            publication = NativePreviewLutPublicationPolicy.resolve(
                desiredEnabled = desiredEnabled,
                desiredSize = lutSize,
                desiredContentHash = contentHash,
                renderProfileGeneration = authority.generation,
                nativeUploadAccepted = nativeAccepted,
                previousNativeSnapshot = previous
            )
            // A same-identity refresh can reveal native context loss. Retire the previously
            // advertised identity immediately; otherwise render-contract resolution suppresses the
            // real software cube while native no longer owns it.
            if (desiredEnabled && !nativeAccepted) {
                var demoted: NativePreviewLutPublication? = null
                advanceVulkanRenderProfileGeneration(
                    reason = "preview-lut-native-refresh-rejected",
                    authorityMutation = { generation ->
                        nativePreviewLutState.set(checkNotNull(demoted).snapshot)
                    },
                    nativeGenerationMutation = { generation ->
                        val nativeDisableAccepted = vulkanRuntime.updatePreviewLut(
                            lutData = null,
                            size = 0,
                            renderProfileGeneration = generation
                        )
                        demoted = NativePreviewLutPublicationPolicy.resolve(
                            desiredEnabled = true,
                            desiredSize = lutSize,
                            desiredContentHash = contentHash,
                            renderProfileGeneration = generation,
                            nativeUploadAccepted = false,
                            previousNativeSnapshot = previous,
                            nativeDisableAcceptedAfterFailure = nativeDisableAccepted
                        )
                    }
                )
                publication = checkNotNull(demoted)
                Log.w(
                        "CameraViewModel",
                        "PREVIEW_LUT_NATIVE_REFRESH_REJECTED size=$lutSize; " +
                            "native=${publication.snapshot.enabled} " +
                            "softwareFallback=${publication.softwareFallbackRequired}"
                )
            } else {
                nativePreviewLutState.set(publication.snapshot)
            }
        }
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
        // Resolve ONE cube so data/size cannot diverge. The recorded SDR cube is authoritative; Natural
        // preview falls back to it, while record-then-develop can still monitor its preview-only grade.
        // Non-SDR video deliveries are rejected here in addition to the compositor shader's SDR gate.
        val previewCube = if (
            publication.softwareFallbackRequired && activeSdrVideoColorParityEligible
        ) {
            lut
        } else {
            SdrCreativeLutSelectionPolicy.resolvePreviewCandidate(
                activeSdrVideoColorParityEligible, parityOn, activeSdrRecordCreativeLut, lut
            )
        }
        previewGlCompositor.setCreativeLut(previewCube?.first, previewCube?.second ?: 0)
        val previousSoftwareOwnerRequired = softwarePreviewLutOwnerRequired
        softwarePreviewLutOwnerRequired = publication.softwareFallbackRequired &&
            activeSdrVideoColorParityEligible && desiredEnabled
        applyFrameGeometry(camera.frameGeometry.value)
        if (previousSoftwareOwnerRequired != softwarePreviewLutOwnerRequired) {
            syncGlPreviewCompositorBinding()
        }
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
        try {
            // The SurfaceView itself is stable during an in-app preset switch. Resolve the final EGL
            // owner inside the Camera2 batch instead of starting another rebuild after a cooldown.
            if (modeSwitchCriticalDepth.incrementAndGet() == 1) {
                advanceVulkanRenderProfileGeneration("mode-switch-batch-begin")
                modeSwitchSessionTransaction.begin(
                    ModeSwitchSessionState(
                        cinemaMode = _cinemaVideoMode.value,
                        pipelineSignature = activeVideoModePipelineContract().sessionSignature,
                        rawReaderGraphActive = resolveRawPreviewPresentationPath() ==
                            RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER,
                        hdrMode = _videoHdrMode.value,
                        logEnabled = _logOn.value
                    )
                )
                aiTransitionReleaseJob?.cancel()
                aiTransitionReleaseJob = null
                aiWarmupJob?.cancel()
                aiCameraPipeline.setRuntimeTransitionCritical(true)
            }
            glPreviewBindingResyncJob?.cancel()
            glPreviewBindingResyncJob = null
            recordingSurfacePrearmRetryJob?.cancel()
            recordingSurfacePrearmRetryJob = null
            recordingSurfacePrearmForcedRefreshRequested.set(false)
            modeSwitchPreviewWorkCoalescer.begin()
        } catch (failure: Throwable) {
            abortModeSwitchPreviewWorkBatch(failure)
            throw failure
        }
    }

    fun endModeSwitchPreviewWorkBatch() {
        if (modeSwitchCriticalDepth.get() <= 0) return
        val flushStartNs = SystemClock.elapsedRealtimeNanos()
        var previousCheckpointNs = flushStartNs
        var flushFailed = false
        val flushTimings = ArrayList<String>(10)
        fun markFlushCheckpoint(label: String) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            flushTimings += "$label=${(nowNs - previousCheckpointNs) / 1_000_000.0}ms"
            previousCheckpointNs = nowNs
        }
        try {
            val flushOutermostBatch = modeSwitchCriticalDepth.get() <= 1
            modeSwitchFlushInProgress = flushOutermostBatch
            modeSwitchPreviewSizingCache.setActive(flushOutermostBatch)
            if (flushOutermostBatch && modeSwitchEffectiveColorSyncPending) {
                modeSwitchEffectiveColorSyncPending = false
                syncEffectiveRecordingColorProfile()
            }
            markFlushCheckpoint("color")
            if (flushOutermostBatch && modeSwitchCameraLookSyncPending) {
                modeSwitchCameraLookSyncPending = false
                syncCameraPreviewLookControls()
            }
            markFlushCheckpoint("camera-look")
            if (flushOutermostBatch && modeSwitchAutomaticBitratePending) {
                val force = modeSwitchAutomaticBitrateForcePending
                modeSwitchAutomaticBitratePending = false
                modeSwitchAutomaticBitrateForcePending = false
                applyAutomaticVideoBitrateIfNeeded(force = force)
            }
            markFlushCheckpoint("bitrate")
            if (flushOutermostBatch && modeSwitchVulkanConfigPending) {
                val force = modeSwitchVulkanConfigForcePending
                modeSwitchVulkanConfigPending = false
                modeSwitchVulkanConfigForcePending = false
                pushVulkanPreviewRenderConfig(force = force)
            }
            markFlushCheckpoint("vulkan-config")
            // Re-evaluate the GL preview compositor against the NOW fully-settled mode state, while the
            // coalescer batch is still open, so its graph mutation joins this batch's single refresh.
            if (flushOutermostBatch) syncGlPreviewCompositorBinding()
            markFlushCheckpoint("gl-binding")
            val flush = modeSwitchPreviewWorkCoalescer.end()
            markFlushCheckpoint("coalescer")
            when (flush.compositorRefresh) {
                PreviewCompositorRefresh.LUT -> refreshPreviewCompositorState(rebuildLut = true)
                PreviewCompositorRefresh.GEOMETRY -> refreshPreviewCompositorState(rebuildLut = false)
                PreviewCompositorRefresh.NONE -> Unit
            }
            markFlushCheckpoint("compositor")
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
            markFlushCheckpoint("preview-pipeline")
            if (flushOutermostBatch) {
                modeSwitchSessionTransaction.finish(
                    ModeSwitchSessionState(
                        cinemaMode = _cinemaVideoMode.value,
                        pipelineSignature = activeVideoModePipelineContract().sessionSignature,
                        rawReaderGraphActive =
                            resolveRawPreviewPresentationPath() == RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER,
                        hdrMode = _videoHdrMode.value,
                        logEnabled = _logOn.value
                    )
                )?.let { request ->
                    refreshSessionForModeSwitchIfIdle(
                        previousMode = request.previous.cinemaMode,
                        currentMode = request.current.cinemaMode,
                        previousPipelineSignature = request.previous.pipelineSignature,
                        currentPipelineSignature = request.current.pipelineSignature,
                        rawReaderGraphChanged = request.previous.rawReaderGraphActive !=
                            request.current.rawReaderGraphActive,
                        auxiliaryRawReaderGraphChanged =
                            request.previous.cinemaMode != CinemaVideoMode.RAW &&
                                request.current.cinemaMode != CinemaVideoMode.RAW &&
                                request.previous.rawReaderGraphActive != request.current.rawReaderGraphActive,
                        hdrToHdrSameTransport = request.hdrToHdrSameTransport
                    )
                }
            }
            markFlushCheckpoint("session-decision")
            if (flushOutermostBatch) rawPreviewPresentationCoordinator.restartIfActive("mode-switch-batch-final")
            if (flushOutermostBatch && modeSwitchRuntimeStatsPending) {
                val previewFps = modeSwitchRuntimeStatsPreviewFps
                modeSwitchRuntimeStatsPending = false
                // Runtime labels, storage estimates and thermal telemetry do not participate in the
                // Camera2 request or visible-surface handoff. Keep them latest-wins and off the gesture
                // frame; the resulting StateFlow update remains thread-safe and reaches the next UI pass.
                modeSwitchRuntimeStatsJob?.cancel()
                modeSwitchRuntimeStatsJob = viewModelScope.launch(Dispatchers.Default) {
                    updateCinemaRuntimeStats(previewFps)
                }
            }
            markFlushCheckpoint("runtime-stats")
        } catch (failure: Throwable) {
            flushFailed = true
            abortModeSwitchPreviewWorkBatch(failure)
            throw failure
        } finally {
            modeSwitchFlushInProgress = false
            modeSwitchPreviewSizingCache.setActive(false)
            val remainingDepth = if (flushFailed) {
                modeSwitchCriticalDepth.set(0)
                0
            } else {
                modeSwitchCriticalDepth.decrementAndGet()
            }
            if (remainingDepth <= 0) {
                if (remainingDepth < 0) modeSwitchCriticalDepth.set(0)
                releaseAiTransitionCriticalWhenPreviewSettles()
            }
            markFlushCheckpoint("release")
            val totalMs = (SystemClock.elapsedRealtimeNanos() - flushStartNs) / 1_000_000.0
            NeoLogger.i(
                "CameraViewModel",
                "ModeSwitchFlushTiming total=${totalMs}ms " +
                    flushTimings.joinToString(separator = " ")
            )
        }
    }

    /**
     * Failure cleanup for a partially flushed mode transition. No stale work or session baseline is
     * allowed to survive into the next mode switch, and recovery is limited to one forced attempt.
     */
    private fun abortModeSwitchPreviewWorkBatch(failure: Throwable) {
        modeSwitchPreviewWorkCoalescer.abort()
        modeSwitchSessionTransaction.abort()
        modeSwitchEffectiveColorSyncPending = false
        modeSwitchCameraLookSyncPending = false
        modeSwitchAutomaticBitratePending = false
        modeSwitchAutomaticBitrateForcePending = false
        modeSwitchVulkanConfigPending = false
        modeSwitchVulkanConfigForcePending = false
        modeSwitchRuntimeStatsPending = false
        modeSwitchRuntimeStatsPreviewFps = 0f
        modeSwitchCriticalDepth.set(0)
        scheduleModeSwitchFailureRecovery(failure)
    }

    private fun scheduleModeSwitchFailureRecovery(originalFailure: Throwable) {
        if (!modeSwitchFailureRecoveryScheduled.compareAndSet(false, true)) return
        try {
            val recoveryJob = viewModelScope.launch(Dispatchers.Main) {
                try {
                    if (viewModelTeardownStarted.get()) return@launch
                    Log.e(
                        "CameraViewModel",
                        "Mode-switch flush failed; forcing one bounded preview/session recovery",
                        originalFailure
                    )
                    refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
                } catch (recoveryFailure: Throwable) {
                    Log.e(
                        "CameraViewModel",
                        "Bounded mode-switch preview/session recovery failed",
                        recoveryFailure
                    )
                }
            }
            recoveryJob.invokeOnCompletion {
                modeSwitchFailureRecoveryScheduled.set(false)
            }
        } catch (schedulingFailure: Throwable) {
            modeSwitchFailureRecoveryScheduled.set(false)
            originalFailure.addSuppressed(schedulingFailure)
        }
    }

    private fun releaseAiTransitionCriticalWhenPreviewSettles() {
        aiTransitionReleaseJob?.cancel()
        aiTransitionReleaseJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            var stableSinceElapsedMs = 0L
            while (isActive) {
                val transitionActive =
                    modeSwitchCriticalDepth.get() > 0 ||
                        camera.sessionRefreshPending.get() ||
                        recordingSurfacePrearmInFlight.get() ||
                        glPreviewSurfaceReleasePending ||
                        nativePreviewHandoffPending ||
                        hdr10PqPreviewEngageInProgress ||
                        glPreviewCompositorEngageInProgress ||
                        glPreviewInputPrepareInProgress ||
                        glPreviewHandoffBindJob?.isActive == true ||
                        glPreviewHandoffFallbackJob?.isActive == true ||
                        nativePreviewHandoffBindJob?.isActive == true ||
                        glPreviewBindingResyncJob?.isActive == true
                if (transitionActive) {
                    stableSinceElapsedMs = 0L
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (stableSinceElapsedMs == 0L) stableSinceElapsedMs = now
                    if (now - stableSinceElapsedMs >= AI_WARMUP_STABLE_PREVIEW_MS) break
                }
                delay(AI_WARMUP_IDLE_POLL_MS)
            }
            aiCameraPipeline.setRuntimeTransitionCritical(false)
            scheduleAiWarmUp(delayMs = AI_WARMUP_STABLE_PREVIEW_MS)
            aiTransitionReleaseJob = null
        }
    }

    // Last Camera2 dynamic-range transport pushed to the session, so a look-only HDR<->HDR switch that
    // keeps the same DynamicRangeProfile does not force a (reddish-flashing) session rebuild.
    private var lastAppliedCameraDynamicRangeMode: HdrDynamicRangeMode? = null

    private fun syncCameraPreviewLookControls() {
        if (modeSwitchCriticalDepth.get() > 0 && !modeSwitchFlushInProgress) {
            modeSwitchCameraLookSyncPending = true
            return
        }
        val syncStartNs = SystemClock.elapsedRealtimeNanos()
        var previousCheckpointNs = syncStartNs
        val syncTimings = ArrayList<String>(10)
        fun markSyncCheckpoint(label: String) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            syncTimings += "$label=${(nowNs - previousCheckpointNs) / 1_000_000.0}ms"
            previousCheckpointNs = nowNs
        }
        val previewLookState = activeVideoPreviewLookState()
        val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
        markSyncCheckpoint("resolve-look")
        camera.setVideoPreviewActive(videoSurface)
        markSyncCheckpoint("video-active")
        camera.setPreviewLookControls(
            logEnabled = previewLookState.logEnabled,
            hdrEnabled = previewLookState.hdrEnabled,
            rawEnabled = previewLookState.rawEnabled
        )
        markSyncCheckpoint("look-setters")
        camera.setCinemaFidelityOverridesEnabled(camera2LowProcessingOverridesActive()).also { camera.setProfessionalVideoControlsActive(_uiMode.value == AppMode.VIDEO && (_sdrQualityIntent.value == SdrQualityIntent.PRO || _manualControlsVisible.value)) }
        markSyncCheckpoint("fidelity")
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
        markSyncCheckpoint("analysis-size")
        syncCameraHdrDynamicRangeMode()
        markSyncCheckpoint("hdr-sync")
        if (cameraState.value == CameraState.Streaming) {
            // Only force a session rebuild when the Camera2 dynamic-range transport actually changed.
            // An NDR10+ -> NIVUKX Vision look switch keeps the same 10-bit carrier and needs no
            // Camera2 rebuild; forcing one can expose a mismatched reddish transition frame.
            requestRecordingSurfacePrearm(forceSessionRefresh = transportChanged)
        }
        markSyncCheckpoint("prearm")
        // The outer mode-switch flush performs one compositor binding after this look has settled.
        if (!modeSwitchFlushInProgress) syncGlPreviewCompositorBinding()
        markSyncCheckpoint("gl-binding")
        val totalMs = (SystemClock.elapsedRealtimeNanos() - syncStartNs) / 1_000_000.0
        NeoLogger.i(
            "CameraViewModel",
            "CameraLookSyncTiming total=${totalMs}ms " + syncTimings.joinToString(separator = " ")
        )
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
        camera.setCinemaFidelityOverridesEnabled(camera2LowProcessingOverridesActive()).also { camera.setProfessionalVideoControlsActive(_uiMode.value == AppMode.VIDEO && (_sdrQualityIntent.value == SdrQualityIntent.PRO || _manualControlsVisible.value)) }
        syncCameraHdrDynamicRangeMode()
    }

    /** Forwards the canonical HDR mode used by the next Camera2 session configuration. */
    private fun syncCameraHdrDynamicRangeMode() {
        val renderProfileGeneration = currentVulkanRenderProfileGeneration()
        val sensorLinearNdr = isSensorLinearNdrMode()
        val mode = if (_uiMode.value == AppMode.VIDEO) {
            activeVideoModePipelineContract().cameraDynamicRangeMode
        } else {
            HdrDynamicRangeMode.SDR
        }
        pipeline.setSourcePqAnalysisEnabled(
            mode == HdrDynamicRangeMode.HDR10 || mode == HdrDynamicRangeMode.HDR10_PLUS
        )
        if (sensorLinearNdr && !primeSensorLinearNdrSessionGraph()) {
            val message =
                "Sensor-linear NDR preview cannot start because no exact RAW_SENSOR readout covers the selected output."
            Log.e("CameraViewModel", message)
            setHardwareWarning(message)
            camera.setSensorLinearNdrCaptureEnabled(false)
            vulkanRuntime.setSensorLinearNdrCaptureEnabled(
                false,
                renderProfileGeneration = renderProfileGeneration
            )
            return
        }
        camera.setSensorLinearNdrCaptureEnabled(sensorLinearNdr)
        vulkanRuntime.setSensorLinearNdrCaptureEnabled(
            sensorLinearNdr,
            renderProfileGeneration = renderProfileGeneration
        )
        reconcileRawPreviewPipeline()
        camera.setHdrDynamicRangeMode(
            mode = mode,
            ndrExposureGovernorEnabled = sensorLinearNdr || analysisFeedRequested()
        )
        // Vulkan display state changes only through the complete generation-owned render packet.
    }

    /**
     * Finalize NDR reader geometry before enabling RAW ownership. Enabling the NDR flag can make
     * CameraController rebuild immediately; resolving the reader later in recording pre-arm briefly
     * creates a full-sensor RAW session and then hot-swaps it to the selected raster. Affected
     * Qualcomm/Vivo providers configure both generations but can abort before the first RAW result.
     * The first preview graph, pre-arm graph, and recording graph now share one exact RAW size.
     */
    private fun primeSensorLinearNdrSessionGraph(): Boolean {
        val parityPlan = activeRecordingPipelineParityPlan()
        val sourceCodec = when (parityPlan.feedPath) {
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER,
            RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER -> GpuVideoCodec.HEVC
            else -> gpuRecording.videoCodec
        }
        val encoderSize = resolveRecordingSelection(
            preferred = preferredRecordingSize(),
            preferredCodec = sourceCodec
        ).size
        val rawSize = resolveSensorLinearNdrRawSize(encoderSize) ?: return false
        camera.setRecordingEncoderTargetSize(encoderSize)
        camera.setRecordingReadoutOverrideSize(rawSize)
        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        Log.i(
            "CameraViewModel",
            "Primed final sensor-linear NDR graph raw=${rawSize.width}x${rawSize.height} " +
                "encoder=${encoderSize.width}x${encoderSize.height} before RAW session ownership"
        )
        return true
    }

    fun setColorProfile(name: String) {
        invalidatePendingPreferenceRestore()
        val normalized = normalizeColorProfileName(name)
        colorScienceSelectedThisSession = true
        prefs.setManualVideoColorScience(normalized)
        if (_colorProfile.value == normalized && videoFeatureState.colorProfile == normalized) {
            return
        }
        advanceVulkanRenderProfileGeneration(
            reason = "color-profile:${_colorProfile.value}->$normalized",
            authorityMutation = { _colorProfile.value = normalized }
        )
        colorScience.loadBuiltInProfile(normalized)
        videoFeatureState = videoFeatureState.copy(colorProfile = normalized)
        // Apply transport intent after the selected profile has become the generation authority.
        // N-Log variants must enter LOG, and NDR10+/NIVUKX must enter their matching PQ transport;
        // syncing the recorder before that transition temporarily replaced NIVUKX with Natural.
        applyVideoProfileIntent(normalized)
        syncEffectiveRecordingColorProfile()
        syncGpuLutState()
        pushVulkanPreviewRenderConfig(force = true)
    }

    fun setVideoColorSpace(name: String) {
        invalidatePendingPreferenceRestore()
        val effectiveColorSpace = ManualVideoColorSelectionPolicy.normalizeColorSpace(name)
        if (effectiveColorSpace == ManualVideoColorSelectionPolicy.UNSELECTED) {
            gpuRecording.reportExternalErrorRetainingResources(
                "Select a valid Video Color Space before recording"
            )
            return
        }
        colorSpaceSelectedThisSession = true
        prefs.setManualVideoColorSpace(effectiveColorSpace)
        val codecUpgradeNeeded = _uiMode.value == AppMode.VIDEO &&
            isWideGamutVideoSpace(effectiveColorSpace) &&
            recording.codec == VideoCodecType.AVC
        if (
            _videoColorSpace.value == effectiveColorSpace &&
            prefs.videoColorSpace == effectiveColorSpace &&
            !codecUpgradeNeeded
        ) {
            return
        }
        advanceVulkanRenderProfileGeneration(
            reason = "video-color-space:${_videoColorSpace.value}->$effectiveColorSpace",
            authorityMutation = {
                _videoColorSpace.value = effectiveColorSpace
                gpuRecording.videoColorSpace = effectiveColorSpace
            }
        )
        if (codecUpgradeNeeded) {
            setVideoCodec(VideoCodecType.HEVC)
        }
        pushVulkanPreviewRenderConfig(force = true)
        val cameraSessionMutationAllowed =
            VideoColorSpaceCameraSessionPolicy.shouldMutateCameraSession(
                sensorLinearNdrActive = isSensorLinearNdrMode()
            )
        // Proprietary NDR is sourced from the existing RAW_SENSOR session and applies color-space
        // changes downstream in the project-owned renderer/encoder. Rebuilding Camera2 here creates
        // an obsolete intermediate RAW graph when a preset then commits its final HDR mode, which can
        // abort affected Qualcomm/Vivo providers. The final HDR transition owns the one required swap.
        if (cameraSessionMutationAllowed) {
            refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
        }
        if (cameraSessionMutationAllowed &&
            _uiMode.value == AppMode.VIDEO && cameraState.value == CameraState.Streaming
        ) {
            requestRecordingSurfacePrearm(forceSessionRefresh = true)
        }
    }

    fun setVideoDetailProfile(profile: VideoDetailProfile) {
        when (
            VideoDetailProfileMutationPolicy.resolve(
                selectedProfile = _videoDetailProfile.value,
                persistedProfile = prefs.videoDetailProfile,
                recorderProfile = gpuRecording.videoDetailProfile,
                requestedProfile = profile,
                recordingLifecycleActive = lifecyclePreferenceMutationBlockedByRecording()
            )
        ) {
            VideoDetailProfileMutationPolicy.Decision.NO_OP -> {
                clearSettingsInvariantRejection()
                return
            }
            VideoDetailProfileMutationPolicy.Decision.REJECT_RECORDING_ACTIVE -> {
                rejectSettingsRequest(
                    ruleId = "DETAIL_PROFILE_RECORDING_LOCK",
                    reason = "Detail profile is locked from recording start through finalization",
                    source = "video-detail-profile"
                )
                return
            }
            VideoDetailProfileMutationPolicy.Decision.APPLY -> Unit
        }
        clearSettingsInvariantRejection()
        invalidatePendingPreferenceRestore()
        settingsController.setVideoDetailProfile(profile)
        if (_videoDetailProfile.value != profile) {
            advanceVulkanRenderProfileGeneration(
                reason = "video-detail:${_videoDetailProfile.value.name}->${profile.name}",
                authorityMutation = { syncSettingsLegacyFromDomain() }
            )
        } else {
            syncSettingsLegacyFromDomain()
        }
        camera.setVideoDetailProfile(profile)
        pushVulkanPreviewRenderConfig(force = true)
        // Extreme Detail has a larger texture/noise-preservation budget. Recompute automatic
        // bitrate before pre-arm so the encoder and the active GPU profile share one contract.
        applyAutomaticVideoBitrateIfNeeded(force = true)
        if (_uiMode.value == AppMode.VIDEO && cameraState.value == CameraState.Streaming) {
            requestRecordingSurfacePrearm(forceSessionRefresh = false)
        }
    }

    internal fun videoDetailProfileRuntimeEvidence(): VideoDetailProfileRuntimeEvidence {
        val uniforms = lastGpuEnhancementUniforms
        return VideoDetailProfileRuntimeEvidence(
            selectedProfile = _videoDetailProfile.value,
            persistedProfile = prefs.videoDetailProfile,
            recorderProfile = gpuRecording.videoDetailProfile,
            gpuProfileId = uniforms?.detailProfileId,
            spatialDenoise = uniforms?.spatialDenoise,
            chromaDenoise = uniforms?.chromaDenoise,
            sharpening = uniforms?.sharpening,
            subjectDetailGain = uniforms?.subjectDetailGain,
            enhancementSignature = uniforms?.signature.orEmpty(),
            throughputSignature = currentVulkanRecordingThroughputSignature(),
            requestedBitrateBps = prefs.videoBitrate,
            appliedBitrateBps = gpuRecording.videoBitrate,
            cameraRequestEvidence = camera.processedVideoQualityEvidence().toMap()
        )
    }

    /**
     * Normal high-quality mode keeps the full-size JPEG surface in the live session (fast, no preview
     * teardown). Maximum-sensor mode is an explicit opt-in because Android requires a dedicated
     * maximum-resolution Camera2 session on many devices and that necessarily adds shutter latency.
     */
    fun setMaximumPhotoResolutionEnabled(enabled: Boolean) {
        invalidatePendingPreferenceRestore()
        prefs.maxPhotoResolutionEnabled = enabled
        camera.maximumResolutionStillEnabled = enabled
    }

    fun loadCustomLUT(file: File) {
        viewModelScope.launch { importCustomLut(file) }
    }

    suspend fun importCustomLut(file: File): Boolean {
        invalidatePendingPreferenceRestore()
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
        invalidatePendingPreferenceRestore()
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
        invalidatePendingPreferenceRestore()
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
        val proposedCompression = if (modeTarget == CinemaVideoMode.RAW) {
            RawVideoContractPolicy.UNCOMPRESSED_12_BIT_MODE
        } else {
            _rawVideoCompression.value
        }
        if (!admitSettingsCommit(
                proposedSettingsCommit(mode = modeTarget, rawCompression = proposedCompression),
                source = "log-enabled"
            )
        ) return
        invalidatePendingPreferenceRestore()
        if (_logOn.value == on && _cinemaVideoMode.value == modeTarget) {
            clearRejectedVideoModeAdmission()
            if (prefs.logEnabled != on) prefs.logEnabled = on
            return
        }
        if (applyCinemaModeSelection(modeTarget)) {
            modeController.setCinemaVideoMode(modeTarget)
        }
    }
    fun setSaturation(v: Float) {
        invalidatePendingPreferenceRestore()
        prefs.saturation = v
        colorScience.saturation = v
        refreshPreviewCompositorState(rebuildLut = true)
    }

    fun setContrast(v: Float) {
        invalidatePendingPreferenceRestore()
        prefs.contrast = v
        colorScience.contrast = v
        refreshPreviewCompositorState(rebuildLut = true)
    }

    fun setBrightness(v: Float) {
        invalidatePendingPreferenceRestore()
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
        invalidatePendingPreferenceRestore()
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
        invalidatePendingPreferenceRestore()
        prefs.stabilizationMode = mode
        camera.setStabilizationMode(mode)
        // Stabilization must come from the same Camera2 stream used by preview and recording.
        // The old subject-following Surface transform affected only the monitor and falsely changed
        // framing, so keep the presentation transform neutral for every hardware stabilization mode.
        previewViewRef?.setStabilizationStrength(0f)
        previewViewRef?.setStabilizationAnchor(0.5f, 0.5f)
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

    val isSdrConversionPipelineEnabled: Boolean
        get() = _sdrConversionPipeline.value

    val isLogEnabled: Boolean
        get() = _logOn.value

    val isRawEnabled: Boolean
        get() = _rawOn.value

    val isVideoHdrEnabled: Boolean
        get() = _videoHdrOn.value

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

    // Settings-media conversion is isolated from camera/session ownership in a dedicated coordinator.
    // These stable ViewModel entry points remain the UI contract.
    fun listNpv10MastersForConversion(): List<Npv10MasterClip> =
        mediaConversionCoordinator.listNpv10Masters()

    fun listNpv10MastersForConversion(
        kind: RecordDevelopMasterKind
    ): List<Npv10MasterClip> = mediaConversionCoordinator.listNpv10Masters(kind)

    fun <T> runWithExclusiveConversionResources(
        cancellation: ConversionCancellationSignal = ConversionCancellationSignal(),
        work: () -> T
    ): T = mediaConversionCoordinator.runExclusive(cancellation, work)

    fun convertNpv10MasterToMov(
        clip: Npv10MasterClip,
        onProgress: ConversionProgressListener? = null
    ): String = mediaConversionCoordinator.convertNpv10MasterToMov(
        clip,
        Npv10MovConverter.OutputFormat.V210_422,
        onProgress
    )

    fun convertNpv10MasterToMov(
        clip: Npv10MasterClip,
        outputFormat: Npv10MovConverter.OutputFormat,
        onProgress: ConversionProgressListener? = null
    ): String = mediaConversionCoordinator.convertNpv10MasterToMov(clip, outputFormat, onProgress)

    fun renderNpv10ThumbnailArgb(clip: Npv10MasterClip): ThumbnailImage? =
        mediaConversionCoordinator.renderNpv10ThumbnailArgb(clip)

    fun deleteNpv10Master(clip: Npv10MasterClip): Boolean =
        mediaConversionCoordinator.deleteNpv10Master(clip)

    fun listRecordDevelopMastersForConversion(
        kind: RecordDevelopMasterKind
    ): List<RecordDevelopMasterClip> = mediaConversionCoordinator.listRecordDevelopMasters(kind)

    fun deleteRecordDevelopMaster(clip: RecordDevelopMasterClip): Boolean =
        mediaConversionCoordinator.deleteRecordDevelopMaster(clip)

    fun hasAnyRecordDevelopMasters(): Boolean =
        mediaConversionCoordinator.hasAnyRecordDevelopMasters()

    fun convertRecordDevelopMaster(
        clip: RecordDevelopMasterClip,
        compatV210: Boolean = false,
        onProgress: ConversionProgressListener? = null
    ): String {
        @Suppress("UNUSED_VARIABLE")
        val compatibilityRequestRetainedForApiStability = compatV210
        return mediaConversionCoordinator.convertRecordDevelopMaster(
            clip,
            RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV,
            onProgress
        )
    }

    internal fun convertRecordDevelopMaster(
        clip: RecordDevelopMasterClip,
        deliveryFormat: RecordDevelopDeliveryFormat,
        onProgress: ConversionProgressListener? = null
    ): String = mediaConversionCoordinator.convertRecordDevelopMaster(
        clip,
        deliveryFormat,
        onProgress
    )

    fun renderRecordDevelopThumbnailArgb(clip: RecordDevelopMasterClip): ThumbnailImage? =
        mediaConversionCoordinator.renderRecordDevelopThumbnailArgb(clip)

    fun listRawClipsForConversion(): List<RawClipMediaStoreDiscovery.RawClipRef> =
        mediaConversionCoordinator.listRawClips()

    fun renderRawClipThumbnailArgb(
        clip: RawClipMediaStoreDiscovery.RawClipRef
    ): ThumbnailImage? = mediaConversionCoordinator.renderRawClipThumbnailArgb(clip)

    fun convertRawClipToMov(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        variant: com.neoproc.neoproccamx.core.NeoLogCurve.Variant =
            com.neoproc.neoproccamx.core.NeoLogCurve.Variant.BASE,
        format: RawToNeoLogMovConverter.OutputFormat =
            RawToNeoLogMovConverter.OutputFormat.MASTER_444,
        onProgress: ConversionProgressListener? = null
    ): String = mediaConversionCoordinator.convertRawClipToMov(clip, variant, format, onProgress)

    fun convertRawClipToNdrMov(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        deliveryProfile: RawToNeoLogMovConverter.DeliveryProfile,
        format: RawToNeoLogMovConverter.OutputFormat =
            RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420,
        onProgress: ConversionProgressListener? = null
    ): String = mediaConversionCoordinator.convertRawClipToNdrMov(
        clip,
        deliveryProfile,
        format,
        onProgress
    )

    fun convertRawClipToCinemaDng(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        onProgress: ConversionProgressListener? = null
    ): String = mediaConversionCoordinator.convertRawClipToCinemaDng(clip, onProgress)

    fun deleteRawClip(clip: RawClipMediaStoreDiscovery.RawClipRef): Boolean =
        mediaConversionCoordinator.deleteRawClip(clip)
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
        val enableRawVideo = _cinemaVideoMode.value != CinemaVideoMode.RAW || !_rawOn.value
        val targetMode = if (enableRawVideo) {
                CinemaVideoMode.RAW
            } else if (_logOn.value) {
                CinemaVideoMode.LOG
            } else {
                CinemaVideoMode.PRO
            }
        val proposedCompression = if (targetMode == CinemaVideoMode.RAW) {
            RawVideoContractPolicy.UNCOMPRESSED_12_BIT_MODE
        } else {
            _rawVideoCompression.value
        }
        if (!admitSettingsCommit(
                proposedSettingsCommit(mode = targetMode, rawCompression = proposedCompression),
                source = "toggle-raw"
            )
        ) return
        if (!camera.supportsRaw) {
            rejectVideoModeAdmission(
                contract = resolveRequestedVideoModeAdmission(CinemaVideoMode.RAW),
                source = "toggle-raw"
            )
            return
        }
        invalidatePendingPreferenceRestore()
        if (applyCinemaModeSelection(targetMode)) {
            modeController.setCinemaVideoMode(targetMode)
        }
        syncModeDomainFromLegacy()
    }
    fun setRawEnabled(enabled: Boolean) {
        val modeTarget = if (enabled) {
            CinemaVideoMode.RAW
        } else if (_logOn.value) {
            CinemaVideoMode.LOG
        } else {
            CinemaVideoMode.PRO
        }
        val proposedCompression = if (modeTarget == CinemaVideoMode.RAW) {
            RawVideoContractPolicy.UNCOMPRESSED_12_BIT_MODE
        } else {
            _rawVideoCompression.value
        }
        if (!admitSettingsCommit(
                proposedSettingsCommit(mode = modeTarget, rawCompression = proposedCompression),
                source = "set-raw-enabled"
            )
        ) return
        if (enabled && !camera.supportsRaw) {
            rejectVideoModeAdmission(
                contract = resolveRequestedVideoModeAdmission(CinemaVideoMode.RAW),
                source = "set-raw-enabled"
            )
            return
        }
        invalidatePendingPreferenceRestore()
        if (_cinemaVideoMode.value == modeTarget && _rawOn.value == enabled) {
            clearRejectedVideoModeAdmission()
            return
        }
        if (applyCinemaModeSelection(modeTarget)) {
            modeController.setCinemaVideoMode(modeTarget)
        }
        syncModeDomainFromLegacy()
    }
    fun setFaceDetectionEnabled(enabled: Boolean) {
        invalidatePendingPreferenceRestore()
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
        invalidatePendingPreferenceRestore()
        settingsController.setEyeAutofocusEnabled(enabled)
        applyAiTrackingPreferences()
    }

    fun setAiEnhancementEnabled(enabled: Boolean) {
        invalidatePendingPreferenceRestore()
        settingsController.setAiEnhancementEnabled(effectiveAiEnhancementEnabled(enabled))
        syncSettingsLegacyFromDomain()
        applyAiTrackingPreferences()
    }

    fun setAiMotionCompensationEnabled(enabled: Boolean) {
        invalidatePendingPreferenceRestore()
        settingsController.setAiMotionCompensationEnabled(enabled)
        syncSettingsLegacyFromDomain()
    }

    fun setNoiseReductionEnabled(enabled: Boolean) {
        invalidatePendingPreferenceRestore()
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
        invalidatePendingPreferenceRestore()
        if (_uncompressedCinemaPipeline.value) return
        settingsController.setUncompressedCinemaPipelineEnabled(true)
        syncSettingsLegacyFromDomain()
        refreshPreviewPipelineIfNeeded(forceSessionRefresh = false)
    }

    fun setSdrConversionPipelineEnabled(enabled: Boolean) {
        invalidatePendingPreferenceRestore()
        if (_sdrConversionPipeline.value == enabled) return
        settingsController.setSdrConversionPipelineEnabled(enabled)
        syncSettingsLegacyFromDomain()
        if (_uiMode.value == AppMode.VIDEO &&
            cameraState.value == CameraState.Streaming &&
            !lifecyclePreferenceMutationBlockedByRecording()
        ) {
            requestRecordingSurfacePrearm(forceSessionRefresh = true)
        }
    }

    fun setGridMode(mode: GridMode) {
        invalidatePendingPreferenceRestore()
        settingsController.setGridMode(mode)
        syncSettingsLegacyFromDomain()
    }

    fun cycleGrid() {
        invalidatePendingPreferenceRestore()
        settingsController.cycleGrid()
        syncSettingsLegacyFromDomain()
    }

    fun setSafeAreaGuidesEnabled(enabled: Boolean) {
        invalidatePendingPreferenceRestore()
        prefs.safeAreaGuidesEnabled = enabled
        _safeAreaGuides.value = enabled
    }

    fun toggleManualControls() {
        settingsController.toggleManualControls()
        syncSettingsLegacyFromDomain()
        syncCameraPreviewLookControls()
    }

    fun setMode(m: AppMode) {
        invalidatePendingPreferenceRestore()
        modeController.setMode(m)
        val committedMode = modeController.domainState.value.activeMode
        if (_uiMode.value != committedMode) {
            advanceVulkanRenderProfileGeneration(
                reason = "app-mode:${_uiMode.value.name}->${committedMode.name}",
                authorityMutation = { _uiMode.value = committedMode }
            )
        }
        syncTaskAwareLensSelectionHint()
        syncModeDomainFromLegacy()
    }

    private fun syncTaskAwareLensSelectionHint() {
        val hint = if (_uiMode.value == AppMode.VIDEO) {
            com.neoproc.neoproccamx.camera.LensSelectionTaskHint.Video(
                requireHighFps = recording.fps >= 60
            )
        } else {
            com.neoproc.neoproccamx.camera.LensSelectionTaskHint.None
        }
        camera.setLensSelectionTaskHint(hint)
    }

    internal fun setSdrQualityIntent(intent: SdrQualityIntent) {
        if (_sdrQualityIntent.value == intent) return
        advanceVulkanRenderProfileGeneration(
            reason = "sdr-quality:${_sdrQualityIntent.value.name}->${intent.name}",
            authorityMutation = { _sdrQualityIntent.value = intent }
        )
        pushVulkanPreviewRenderConfig(force = true).also { camera.setProfessionalVideoControlsActive(_uiMode.value == AppMode.VIDEO && (intent == SdrQualityIntent.PRO || _manualControlsVisible.value)) }
    }

    fun toggleHdrMode() {
        setVideoHdrEnabled(!_videoHdrOn.value)
    }

    fun setVideoHdrEnabled(enabled: Boolean) {
        if (_uiMode.value != AppMode.VIDEO) return
        setVideoHdrMode(if (enabled) preferredVideoHdrMode else "OFF")
    }

    /** Last user-selected NDR look, retained while another rail mode temporarily makes HDR inactive. */
    val preferredVideoHdrMode: String
        get() = VideoNdrProfileMemoryPolicy.resolvePreferred(
            storedPreferred = prefs.preferredNdrProfile,
            activeHdrMode = _videoHdrMode.value
        )

    fun setVideoHdrMode(mode: String) {
        val normalizedRequest = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(mode)
        if (normalizedRequest == "OFF" && !mode.trim().equals("OFF", ignoreCase = true)) {
            rejectSettingsRequest(
                ruleId = "HDR_MODE_NOT_RECOGNIZED",
                reason = "The requested XDR mode '$mode' is not recognized",
                source = "video-hdr-mode"
            )
            return
        }
        // Resolve the complete atomic transition before validating it. An XDR request made while
        // LOG/RAW is active intentionally transitions the cinema mode to PRO; validating against
        // the old mode would reject that valid final state and leave HDR OFF with LOG still active.
        val transition = VideoHdrModeTransitionPolicy.resolve(
            requestedMode = normalizedRequest,
            uiMode = _uiMode.value,
            currentCinemaMode = _cinemaVideoMode.value
        )
        val proposedCinemaMode = transition.cinemaModeOverride ?: _cinemaVideoMode.value
        val proposedCommit = proposedSettingsCommit(
            mode = proposedCinemaMode,
            hdrMode = transition.normalizedHdrMode
        )
        if (!admitSettingsCommit(proposedCommit, source = "video-hdr-mode")) return
        invalidatePendingPreferenceRestore()
        val presetBatchActive = modeSwitchCriticalDepth.get() > 0
        val previousCinemaMode = _cinemaVideoMode.value
        val previousPipelineSignature = if (presetBatchActive) null else
            activeVideoModePipelineContract().sessionSignature
        val previousHdrUiMode = _videoHdrMode.value
        val previousSensorLinearNdr = isSensorLinearNdrMode(previousHdrUiMode)
        val normalized = transition.normalizedHdrMode
        val pipelineAdmission = resolveRequestedVideoModeAdmission(
            cinemaMode = proposedCinemaMode,
            requestedHdrMode = normalized
        )
        if (pipelineAdmission.admissionState != VideoPipelineAdmissionState.ADMITTED) {
            rejectVideoModeAdmission(
                contract = pipelineAdmission,
                source = "set-hdr-mode:$normalized"
            )
            return
        }
        clearRejectedVideoModeAdmission()
        if (
            transition.cinemaModeOverride == null &&
            _videoHdrMode.value == normalized &&
            _videoHdrOn.value == (normalized != "OFF") &&
            (normalized == "OFF" || isWideGamutVideoSpace(_videoColorSpace.value))
        ) {
            if (prefs.videoHdrMode != normalized) prefs.videoHdrMode = normalized
            if (normalized != "OFF") {
                prefs.preferredNdrProfile = VideoNdrProfileMemoryPolicy.remember(
                    storedPreferred = prefs.preferredNdrProfile,
                    selectedHdrMode = normalized
                )
            }
            return
        }
        advanceVulkanRenderProfileGeneration("ndr-profile:$previousHdrUiMode->$normalized")
        val rawPresentationTransitionEpoch = if (previousHdrUiMode != normalized)
            rawPreviewPresentationCoordinator.beginIfActive("ndr-profile:$previousHdrUiMode->$normalized")
        else null
        transition.cinemaModeOverride?.let { overrideMode ->
            // Normalize the backing cinema mode first, then refresh the session once
            // after the final HDR state is in place so preview does not bounce.
            applyCinemaModeSelection(
                mode = overrideMode,
                persist = false,
                refreshSession = false
            )
        }
        // applyCinemaModeSelection() can enqueue a PRO packet while normalizing RAW/LOG first. Give
        // the final NDR selection a newer token so that intermediate packet is unconditionally stale.
        advanceVulkanRenderProfileGeneration(
            reason = "ndr-profile-final:$previousHdrUiMode->$normalized",
            committedHdrMode = normalized
        )
        // The complete render packet below commits correction, display and enhancement atomically.
        prefs.videoHdrMode = normalized
        if (normalized != "OFF") {
            prefs.preferredNdrProfile = VideoNdrProfileMemoryPolicy.remember(
                storedPreferred = prefs.preferredNdrProfile,
                selectedHdrMode = normalized
            )
        }
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
        // NDR10+ <-> NIVUKX Vision share the same interoperable 10-bit transport while retaining their
        // own RAW-derived ACEScg rendering. Detect the unchanged carrier so the session refresh below is
        // skipped and the preview cannot flash a mismatched transition frame during a needless rebuild.
        val hdrToHdrSameTransport = if (presetBatchActive) {
            false
        } else {
            val previousHdrTransport = HdrDynamicRangeMode.fromUiMode(previousHdrUiMode, prefs.logEnabled)
            val currentHdrTransport = HdrDynamicRangeMode.fromUiMode(normalized, prefs.logEnabled)
            previousHdrTransport == currentHdrTransport && previousHdrTransport != HdrDynamicRangeMode.SDR
        }
        if (presetBatchActive) {
            modeSwitchSessionTransaction.requestRefresh()
        } else {
            refreshSessionForModeSwitchIfIdle(
                previousMode = previousCinemaMode,
                currentMode = _cinemaVideoMode.value,
                previousPipelineSignature = previousPipelineSignature,
                currentPipelineSignature = activeVideoModePipelineContract().sessionSignature,
                auxiliaryRawReaderGraphChanged =
                    previousSensorLinearNdr != isSensorLinearNdrMode(normalized),
                hdrToHdrSameTransport = hdrToHdrSameTransport
            )
        }
        rawPresentationTransitionEpoch?.let {
            rawPreviewPresentationCoordinator.scheduleCommit(it, reason = "ndr-profile:$previousHdrUiMode->$normalized")
        }
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
        previewView?.setPresentationAspectRatios(
            displayContentAspectRatio = framingAspect,
            viewportAspectRatio = displayAspect,
            fillViewport = shouldFillPreviewViewport()
        )
        previewView?.setEncoderContentAspectRatioOverride(framingAspect)
        syncPreviewViewport(previewView)
        applyFrameGeometry(camera.frameGeometry.value)
        syncPreviewPerformanceMode()
    }

    /**
     * Applies a preview-aspect selection without rebuilding the immutable Vulkan colour/quality
     * packet. Aspect selection changes presentation framing and future output-crop intent only;
     * it does not change the selected recording resolution, codec, FPS, bitrate, colour science,
     * or frame geometry. Re-resolving those domains on the UI thread made every settings change
     * miss one or more display deadlines even though the Camera2 transport stayed unchanged.
     */
    private fun syncPreviewAspectPresentationTargets() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMainIfNeeded { syncPreviewAspectPresentationTargets() }
            return
        }
        val previewView = previewViewRef
        val viewportWidth = previewView?.width ?: 0
        val viewportHeight = previewView?.height ?: 0
        val sensorAspect = currentPreviewStreamAspectRatio ?: basePreviewStreamAspectRatio()
        val cropAspect = outputAspectRatio()
        SensorCropEngine.setFullSensorAspectRatio(camera.sensorBaseAspectRatio())
        val framingAspect = cropAspect ?: sensorAspect
        val displayAspect = SensorCropEngine.displayAspectForBounds(
            framingAspect,
            viewportWidth,
            viewportHeight
        ) ?: framingAspect

        camera.targetOutputAspectRatio = cropAspect
        previewView?.setPresentationAspectRatios(
            displayContentAspectRatio = framingAspect,
            viewportAspectRatio = displayAspect,
            fillViewport = shouldFillPreviewViewport()
        )
        previewView?.setEncoderContentAspectRatioOverride(framingAspect)
        syncPreviewViewport(previewView)
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
        val debayerOrientOn = com.neoproc.neoproccamx.rendering.RawDebayerPreviewOrientationParityFlag.enabled
        val debayerTransform = RawDebayerPreviewTransformPolicy.resolve(
            geometry = geometry,
            sensorOrientationDegrees = geometry?.sensorOrientationDegrees ?: camera.sensorOrientation(),
            deviceRotationDegrees = geometry?.deviceRotationDegrees ?: camera.deviceRotationDegrees,
            lensFacing = geometry?.lensFacing ?: camera.facing,
            surfacePresentationOwnsGeometry = false
        )
        runCatching {
            previewGlCompositor.setDebayerOrientation(
                if (debayerOrientOn) debayerTransform.rotationDegrees else 0,
                debayerOrientOn && debayerTransform.mirrored
            )
        }
        publishProjectOwnedNdrOrientation(
            latestDeviceRotationDegrees = currentRotationDegrees,
            geometry = geometry
        )
    }

    /**
     * Publishes only the project-owned NDR sampling transform. Camera2, Vulkan, RAW presentation,
     * recording orientation and every colour/grade stage retain their existing ownership.
     */
    private fun publishProjectOwnedNdrOrientation(
        latestDeviceRotationDegrees: Int,
        geometry: FrameGeometry?
    ) {
        if (!projectOwnedNdrOrientationTargetReady) return
        val ndrTransform =
            com.neoproc.neoproccamx.rendering.ProjectOwnedNdrPreviewTransformPolicy.resolve(
                sensorOrientationDegrees = geometry?.sensorOrientationDegrees
                    ?: camera.sensorOrientation(),
                latestDeviceRotationDegrees = latestDeviceRotationDegrees,
                lensFacing = geometry?.lensFacing ?: camera.facing
            )
        // NDR-only fixed-presentation rule requested for the portrait-locked camera UI. Every
        // physical quadrant resolves to the proven zero-degree project-owned sampling path.
        hdr10PqPreviewCompositor.setProjectOwnedNdrOrientation(
            rotationDegrees = ndrTransform.rotationDegrees,
            mirrored = ndrTransform.mirrored
        )
    }

    private fun captureDeferredVulkanPreviewConfigRequest(
        geometry: FrameGeometry?,
        force: Boolean
    ): DeferredVulkanPreviewConfigRequest? =
        vulkanRenderProfileAuthority.captureIfCurrent(
            externalAuthorityCurrent = { authority ->
                vulkanRuntime.isRenderProfileGenerationCurrent(authority.generation)
            }
        ) { authority ->
            val requestSequence = nextVulkanRenderRequestSequence()
            val appMode = _uiMode.value
            val cinemaMode = _cinemaVideoMode.value
            val rawEnabled = _rawOn.value
            val rawVideoActive = appMode == AppMode.VIDEO &&
                cinemaMode == CinemaVideoMode.RAW && rawEnabled
            val logEnabled = _logOn.value
            val preferredColorSpace = _videoColorSpace.value
            val characteristics = camera.characteristics
            val recordingWidth = recording.width
            val recordingHeight = recording.height
            val recordingFps = recording.fps
            val gpuTenBitSupported = supports10BitWithoutCodecProbeBlocking()
            val deliveryKey = ActiveDeliveryContractCacheKey(
                appMode = appMode,
                cinemaMode = cinemaMode,
                rawVideoActive = rawVideoActive,
                logEnabled = logEnabled,
                requestedHdrMode = authority.hdrMode,
                preferredColorSpace = preferredColorSpace,
                tenBitActive = gpuRecording.tenBitEnabled ||
                    resolveVideoRecordingTenBitRequested(cinemaMode, authority.hdrMode),
                cameraId = camera.currentCameraId,
                characteristicsIdentity = System.identityHashCode(characteristics),
                gpuTenBitSupported = gpuTenBitSupported,
                width = recordingWidth,
                height = recordingHeight,
                fps = recordingFps
            )
            val recordingState = gpuRecordState.value
            val recordingActive = recordingState is GpuRecordingState.Recording ||
                recordingState is GpuRecordingState.Paused ||
                recordingState is GpuRecordingState.Stopping ||
                cameraState.value == CameraState.Recording
            val enhancementResult = _aiEnhancementResult.value
            val cameraParameterSnapshot = cameraParams.value
            val captureResultSnapshot = camera.captureResult
            val sensorNoiseModelSnapshot = camera.sensorNoiseModel
            val sourceRenderSize = preferredVideoSourceSize()
            val outputRenderSize = Size(
                gpuRecording.videoWidth.coerceAtLeast(recordingWidth).coerceAtLeast(1),
                gpuRecording.videoHeight.coerceAtLeast(recordingHeight).coerceAtLeast(1)
            ).normalizeForOrientation()
            val encoderTransform = geometry?.encoderUvTransformMatrix() ?: floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
            )
            val subjectMask = _aiSubjectMask.value?.let { mask ->
                mask.copy(alphaMask = mask.alphaMask.copyOf())
            }
            DeferredVulkanPreviewConfigRequest(
                geometry = geometry,
                force = force,
                renderState = VulkanRenderRequestSnapshot(
                    authority = authority,
                    requestSequence = requestSequence,
                    geometry = geometry,
                    videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(appMode),
                    deliveryKey = deliveryKey,
                    cameraCharacteristics = characteristics,
                    nativePreviewLut = nativePreviewLutState.get(),
                    colorProfile = _colorProfile.value,
                    lutEnabled = _lutEnabled.value,
                    lutProfile = _lutProfile.value,
                    sdrQualityIntent = _sdrQualityIntent.value,
                    highLoadPreviewActive = isHighLoadPreviewMode(),
                    previewFpsHint = lastPreviewFpsSample.takeIf { it > 0f }
                        ?: VIDEO_IDLE_PREVIEW_FPS.toFloat(),
                    cameraPreviewHdrTransportMode =
                        camera.describeHdrDynamicRangeState().previewTransportMode,
                    cinemaDebayerActive = resolveRawPreviewPresentationPath() ==
                        RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER,
                    sceneRenderProfile = currentSceneRenderProfile,
                    sceneType = _aiSceneType.value,
                    aiEnhancementEnabled = effectiveAiEnhancementEnabled(),
                    aiMeasuredPerceptualSharpness = enhancementResult?.sharpnessScore ?: 0f,
                    aiMeasuredNoiseLevel = enhancementResult?.estimatedNoiseLevel ?: 0f,
                    aiMotionCompensationEnabled = _aiMotionCompensation.value,
                    videoDetailProfile = _videoDetailProfile.value,
                    recordingState = recordingState,
                    recordingActive = recordingActive,
                    simulatedTenBitFallback = gpuRecording.simulatedTenBitFallbackActive,
                    requestedCodec = recording.codec,
                    av1Supported = av1SupportResolved && cachedAv1Supported,
                    outputRenderSize = outputRenderSize,
                    sourceRenderSize = sourceRenderSize,
                    sensorNoiseModel = sensorNoiseModelSnapshot,
                    sensorIso = captureResultSnapshot?.iso?.takeIf { it > 0 }
                        ?: cameraParameterSnapshot.iso.takeIf { it > 0 }
                        ?: 100,
                    exposureCompensation = cameraParameterSnapshot.ev,
                    subjectDetailMask = subjectMask,
                    snce = snceColorEngine.snapshot(),
                    apex = apexPipelineState.snapshot(),
                    previousColorGain = previewColorGainState.copyOf(),
                    previewTransform = buildVulkanPreviewTransform(geometry),
                    encoderTransform = encoderTransform
                )
            )
        }

    private fun enqueueVulkanPreviewRenderConfig(geometry: FrameGeometry?, force: Boolean) {
        val request = captureDeferredVulkanPreviewConfigRequest(geometry, force)
        if (request == null) {
            Log.i("CameraViewModel", "NDR_RENDER_CONFIG_CAPTURE_REJECTED stage=enqueue")
            return
        }
        vulkanPreviewConfigCoordinator.submit(request)
    }

    private suspend fun awaitVulkanPreviewRenderConfig(
        geometry: FrameGeometry? = camera.frameGeometry.value,
        force: Boolean = false
    ) {
        val request = captureDeferredVulkanPreviewConfigRequest(geometry, force)
        if (request == null) {
            Log.i("CameraViewModel", "NDR_RENDER_CONFIG_CAPTURE_REJECTED stage=await")
            return
        }
        vulkanPreviewConfigCoordinator.submitAndAwait(request)
    }

    private fun pushVulkanPreviewRenderConfig(
        geometry: FrameGeometry? = camera.frameGeometry.value,
        force: Boolean = false,
        executeOnWorker: Boolean = false,
        expectedRenderState: VulkanRenderRequestSnapshot? = null
    ) {
        if (!executeOnWorker && modeSwitchCriticalDepth.get() > 0 && !modeSwitchFlushInProgress) {
            modeSwitchVulkanConfigPending = true
            modeSwitchVulkanConfigForcePending = modeSwitchVulkanConfigForcePending || force
            return
        }
        // Grade/evidence calculation and swapchain updates can take tens of milliseconds. Route every
        // routine request through one latest-wins drain so UI, scene-analysis, and geometry workers do
        // not fan out or wait on a shared monitor. Recording start awaits the same coordinator before
        // enabling its encoder feed, preserving first-frame configuration ordering.
        if (!executeOnWorker) {
            enqueueVulkanPreviewRenderConfig(geometry, force)
            return
        }
        val renderState = expectedRenderState
            ?: captureDeferredVulkanPreviewConfigRequest(geometry, force)?.renderState
            ?: return
        val profileGeneration = renderState.authority.generation
        val hdrModeAuthority = renderState.authority.hdrMode
        if (!isVulkanRenderProfileGenerationCurrent(profileGeneration, hdrModeAuthority)) {
            Log.i(
                "CameraViewModel",
                "NDR_RENDER_CONFIG_STALE_REJECTED requested=$profileGeneration " +
                    "active=${currentVulkanRenderProfileGeneration()} stage=before-resolve"
            )
            return
        }
        val videoSurface = renderState.videoSurface
        val authoritativeDeliveryState = resolveVideoFormatDeliveryState(
            key = renderState.deliveryKey,
            characteristics = renderState.cameraCharacteristics
        )
        val renderContract = resolveVulkanPreviewRenderContract(
            renderState = renderState,
            deliveryState = authoritativeDeliveryState
        )
        // The active StateFlow is the only runtime HDR authority. Persisted preferences are input to
        // the atomic startup restore, never a render-stage fallback; likewise, a stale cached render
        // contract cannot reactivate a profile that the UI/runtime state has already turned OFF.
        val ndrRuntimeEligible =
            videoSurface &&
                renderState.deliveryKey.appMode == AppMode.VIDEO &&
                renderState.deliveryKey.cinemaMode == CinemaVideoMode.PRO &&
                !renderState.deliveryKey.rawVideoActive &&
                !renderState.deliveryKey.logEnabled &&
                !renderContract.rawVideoActive &&
                !renderContract.logCaptureActive
        val runtimeNormalizedHdrMode = VideoHdrRuntimeAuthorityPolicy.resolve(
            runtimeEligible = ndrRuntimeEligible,
            activeHdrMode = hdrModeAuthority
        )
        val selectedNdrCorrection =
            NdrNivukxMeasuredCorrectionPolicy.resolve(runtimeNormalizedHdrMode)
        val authoritativeColorProfile = resolveGpuColorProfile(
            hdrMode = runtimeNormalizedHdrMode,
            colorSpace = renderState.deliveryKey.preferredColorSpace,
            colorScience = renderState.colorProfile
        )
        val neoColorScienceEnabled = videoSurface
        val neoHdrMappingEnabled = ndrRuntimeEligible && runtimeNormalizedHdrMode != "OFF"
        val nivukxVision =
            runtimeNormalizedHdrMode.equals("NIVUKXVISION", ignoreCase = true)
        val ndrNivukxMeasuredCorrection = if (!ndrRuntimeEligible) {
            NdrNivukxMeasuredCorrectionPolicy.resolve("OFF")
        } else {
            selectedNdrCorrection.takeIf {
                it.mode != com.neoproc.neoproccamx.rendering.NdrNivukxMeasuredMode.OFF
            } ?: NdrNivukxMeasuredCorrectionPolicy.resolve(runtimeNormalizedHdrMode)
        }
        val signatureChanged = renderContract.signature != lastVulkanPreviewRenderSignature
        val previewGrade = buildVulkanPreviewGrade(
            renderContract = renderContract,
            deliveryState = authoritativeDeliveryState,
            renderState = renderState
        )
        val encoderGradeCandidate = VulkanPreviewRecordingParityPolicy.resolveEncoderGrade(
            previewGrade = previewGrade,
            encoderGrade = buildVulkanEncoderGrade(
                deliveryState = authoritativeDeliveryState,
                renderState = renderState
            ),
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
            colorSpaceName = authoritativeColorProfile.colorSpaceName,
            colorScienceName = authoritativeColorProfile.colorScienceName,
            measuredCorrection = ndrNivukxMeasuredCorrection,
            normalizedHdrMode = runtimeNormalizedHdrMode
        ).pack()
        val encoderColorScienceCandidate = VulkanColorScienceUniformPolicy.resolve(
            enabled = neoColorScienceEnabled,
            hdrMappingEnabled = neoHdrMappingEnabled,
            logDeliveryEnabled = renderContract.logCaptureActive,
            nivukxVision = nivukxVision,
            hdrKnee = if (nivukxVision) 0.55f else 0.59f,
            hdrShoulder = if (nivukxVision) 4.12f else 3.72f,
            volumeCompression = if (nivukxVision) 0.64f else 0.54f,
            colorSpaceName = authoritativeColorProfile.colorSpaceName,
            colorScienceName = authoritativeColorProfile.colorScienceName,
            measuredCorrection = ndrNivukxMeasuredCorrection,
            normalizedHdrMode = runtimeNormalizedHdrMode
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
        val previewGradeSha256Candidate =
            CameraViewModelRenderIdentityPolicy.colorUniformSha256(previewGrade)
        val recordingGradeSha256Candidate =
            CameraViewModelRenderIdentityPolicy.colorUniformSha256(encoderGrade)
        val previewColorUniformSha256Candidate =
            CameraViewModelRenderIdentityPolicy.colorUniformSha256(previewColorScience)
        val recordingColorUniformSha256Candidate =
            CameraViewModelRenderIdentityPolicy.colorUniformSha256(encoderColorScience)
        val monitorDeliveryColorUniformDifferenceAllowedCandidate =
            VulkanPreviewRecordingParityPolicy.isAllowedMonitorDeliveryColorUniformDelta(
                previewColorScience = previewColorScience,
                recordingColorScience = encoderColorScience
            )
        if (!isVulkanRenderProfileGenerationCurrent(profileGeneration, hdrModeAuthority)) {
            Log.i(
                "CameraViewModel",
                "NDR_RENDER_CONFIG_STALE_REJECTED requested=$profileGeneration " +
                    "active=${currentVulkanRenderProfileGeneration()} stage=before-runtime-commit"
            )
            return
        }
        val profile = renderState.sceneRenderProfile
            ?: buildFallbackGpuSceneRenderProfile(renderContract, renderState)
        val colorSpaceName = authoritativeColorProfile.colorSpaceName
        val colorScienceName = authoritativeColorProfile.colorScienceName
        val forceNormalSdrRealtimeLock = renderState.videoSurface &&
            runtimeNormalizedHdrMode == "OFF" &&
            !renderContract.logCaptureActive &&
            !renderState.deliveryKey.rawVideoActive
        val signalFamily = if (forceNormalSdrRealtimeLock) {
            GpuSignalFamily.SDR
        } else {
            GpuSignalFamilyResolver.resolve(
                mode = renderState.deliveryKey.appMode.name,
                logEnabled = renderContract.logCaptureActive,
                hdrEnabled = runtimeNormalizedHdrMode != "OFF",
                rawEnabled = renderContract.rawVideoActive,
                profile = "$colorSpaceName $colorScienceName"
            )
        }
        val sensorNoiseModel = renderState.sensorNoiseModel
        // The UI ISO is a request/control value and remains Auto (-1) in normal NDR operation.
        // Use the authoritative per-frame Camera2 readback whenever it exists so startup/high-ISO
        // frames receive the correct protection and converged low-ISO frames retain real texture.
        val enhancementSensorIso = renderState.sensorIso
        val enhancementUniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = signalFamily,
            colorSpaceName = colorSpaceName,
            colorScienceName = colorScienceName,
            activeTransport = null,
            iso = enhancementSensorIso.toFloat(),
            noiseMetric = profile.noiseMetrics.lumaNoise,
            sensorShotNoiseCoefficient = sensorNoiseModel.shotNoiseCoefficient,
            sensorReadNoiseCoefficient = sensorNoiseModel.readNoiseCoefficient,
            sensorNoiseModelPerFrame = sensorNoiseModel.isPerFrameMeasurement,
            videoDetailProfile = renderState.videoDetailProfile,
            subjectDetailPlan = profile.subjectDetailPlan,
            forceNormalSdrRealtimeLock = forceNormalSdrRealtimeLock,
            sdrQualityIntent = if (forceNormalSdrRealtimeLock) {
                renderState.sdrQualityIntent
            } else {
                null
            },
            // F-AIENH: when AI Enhancement is ON, route the measured enhancement (perceptual
            // sharpness deficit + estimated noise) into the record sharpen/denoise uniforms.
            // Null result -> zero boost (identity), so OFF is byte-identical to today.
            aiRecordEnhancementActive = renderState.aiEnhancementEnabled,
            aiMeasuredPerceptualSharpness = renderState.aiMeasuredPerceptualSharpness,
            aiMeasuredNoiseLevel = renderState.aiMeasuredNoiseLevel,
            normalizedHdrMode = runtimeNormalizedHdrMode
        )
        val liveGpuEnhancementUniforms =
            RecordDevelopLivePipelinePolicy.enhancementUniforms(videoSurface, enhancementUniforms)
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
            liveEnhanceArray[11] = renderState.snce.p010SaturationMul()
            liveEnhanceArray[9] = renderState.snce.p010WarmthBias()
        }
        val previewTransform = renderState.previewTransform
        val encoderTransform = renderState.encoderTransform
        val colorGainCandidate = PreviewColorGainPolicy.resolve(
            renderState = renderState,
            deliveryState = authoritativeDeliveryState,
            safeVideoHdrPreviewActive = isSafeVideoHdrPreviewActive(
                renderState = renderState,
                deliveryState = authoritativeDeliveryState
            )
        )
        val colorGain = colorGainCandidate.uniforms
        val subjectDetailMask = renderState.subjectDetailMask.takeIf {
            !RecordDevelopLivePipelinePolicy.active(videoSurface) &&
                renderState.videoDetailProfile == VideoDetailProfile.FOCUSED_DETAIL &&
                profile.subjectDetailPlan?.subjectDetailApplied == true
        }
        val apexModeCandidate = when (renderContract.toneDeliveryMode.shaderModeFlag) {
            in 49.5f..50.5f -> ApexMode.SHARP
            in 50.5f..51.5f -> ApexMode.ZERO
            in 51.5f..52.5f -> ApexMode.CINEMA
            else -> null
        }
        val renderProfilePacketCandidate = NdrRenderProfilePacket(
            profileGeneration = profileGeneration,
            configRevision = NdrRenderProfileRevisionSequencer.UNASSIGNED_REVISION,
            correction = ndrNivukxMeasuredCorrection,
            enhancementUniforms = liveGpuEnhancementUniforms,
            nativeRenderConfig = VulkanNativeRenderProfileConfig(
                hdrDynamicRangeMode = renderContract.swapchainDynamicRangeMode,
                // X2/R1: request a 10-bit packed sRGB DISPLAY swapchain for SDR preview
                // (Normal/Pro/Log). Native ignores this preference for HDR display targets.
                preferTenBitSdrDisplay =
                    HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly,
                renderConfig = VulkanRenderConfig(
                    previewTransform = previewTransform,
                    encoderTransform = encoderTransform,
                    colorGain = colorGain,
                    previewGrade = previewGrade,
                    encoderGrade = encoderGrade,
                    previewColorScience = previewColorScience,
                    encoderColorScience = encoderColorScience
                )
            ),
            subjectDetailMask = subjectDetailMask
        )
        lateinit var renderProfilePacket: NdrRenderProfilePacket
        var renderProfilePacketStaged = false
        val stagingAuthorityCommitted = commitIfCurrentVulkanRenderProfileGeneration(
            generation = profileGeneration,
            hdrModeAuthority = hdrModeAuthority
        ) {
            // Publish every native-visible member of one immutable authority while transitions are
            // excluded. The lock order is authority -> Vulkan generation, identical to advance().
            renderProfilePacket = ndrRenderProfileRevisionSequencer.assign(
                renderProfilePacketCandidate
            )
            renderProfilePacketStaged = NdrRenderProfileLifecycleCoordinator.commitForFrameIngress(
                vulkanRuntime,
                renderProfilePacket,
                ::currentVulkanRenderProfileGeneration
            )
        }
        if (!stagingAuthorityCommitted || !renderProfilePacketStaged) {
            Log.i(
                "CameraViewModel",
                "NDR_RENDER_CONFIG_STALE_REJECTED requested=$profileGeneration " +
                    "active=${currentVulkanRenderProfileGeneration()} " +
                    "stage=atomic-native-stage authority=$stagingAuthorityCommitted " +
                    "profilePacketStaged=$renderProfilePacketStaged"
            )
            return
        }

        // Precision proof may query native diagnostics or schedule Main-thread fallback. It must run
        // after native staging but before finalization and outside every authority/native lock.
        if (!revalidateStrictNativePreviewForProfile(renderProfilePacket.identity)) {
            Log.i(
                "CameraViewModel",
                "NDR_RENDER_CONFIG_PRECISION_DEFERRED identity=${renderProfilePacket.identity}; " +
                    "frame ingress retained"
            )
            return
        }

        var renderProfilePacketFinalized = false
        val finalizationAuthorityCommitted = commitIfCurrentVulkanRenderProfileGeneration(
            generation = profileGeneration,
            hdrModeAuthority = hdrModeAuthority
        ) {
            renderProfilePacketFinalized = NdrRenderProfileLifecycleCoordinator.finalizeFrameIngress(
                vulkanRuntime,
                renderProfilePacket,
                ::currentVulkanRenderProfileGeneration
            ) beforeIngress@{
                // Non-blocking route-epoch recheck inside the final publication boundary closes the
                // proof/finalize TOCTOU window without waiting on Main or entering EGL owner locks.
                if (!strictPreviewProfileProofStillCurrent(renderProfilePacket.identity)) {
                    return@beforeIngress false
                }
                lastPreviewGradeSha256 = previewGradeSha256Candidate
                lastRecordingGradeSha256 = recordingGradeSha256Candidate
                lastPreviewColorUniformSha256 = previewColorUniformSha256Candidate
                lastRecordingColorUniformSha256 = recordingColorUniformSha256Candidate
                lastMonitorDeliveryColorUniformDifferenceAllowed =
                    monitorDeliveryColorUniformDifferenceAllowedCandidate
                previewColorGainState = colorGainCandidate.committedState.copyOf()
                apexModeCandidate?.let { mode ->
                    apexPipelineState.publishCommittedMode(mode, renderState.recordingActive)
                }
                lastGpuEnhancementUniforms = liveGpuEnhancementUniforms
                gpuRecording.setLiveEnhanceUniforms(
                    RecordDevelopLivePipelinePolicy.liveEnhanceUniforms(videoSurface, liveEnhanceArray)
                )
                if (force || signatureChanged) {
                    lastVulkanPreviewRenderSignature = renderContract.signature
                }
                true
            }
        }
        if (!finalizationAuthorityCommitted || !renderProfilePacketFinalized) {
            Log.i(
                "CameraViewModel",
                "NDR_RENDER_CONFIG_STALE_REJECTED identity=${renderProfilePacket.identity} " +
                    "active=${currentVulkanRenderProfileGeneration()} " +
                    "stage=atomic-native-finalize authority=$finalizationAuthorityCommitted " +
                    "profilePacketFinalized=$renderProfilePacketFinalized"
            )
            return
        }
    }

    private fun resolveGpuColorProfile(hdrMode: String, colorSpace: String = _videoColorSpace.value, colorScience: String = _colorProfile.value) =
        GpuAuthoritativeColorProfilePolicy.resolve(hdrMode, colorSpace, colorScience)

    private fun buildFallbackGpuSceneRenderProfile(
        renderContract: VulkanPreviewRenderContract,
        renderState: VulkanRenderRequestSnapshot? = null
    ): SceneRenderProfile {
        val outputRenderSize = renderState?.outputRenderSize ?: Size(
            gpuRecording.videoWidth.coerceAtLeast(recording.width).coerceAtLeast(1),
            gpuRecording.videoHeight.coerceAtLeast(recording.height).coerceAtLeast(1)
        ).normalizeForOrientation()
        val sourceRenderSize = renderState?.sourceRenderSize ?: preferredVideoSourceSize()
        val authoritativeColorProfile = resolveGpuColorProfile(
            hdrMode = renderContract.normalizedHdrMode,
            colorSpace = renderState?.deliveryKey?.preferredColorSpace ?: _videoColorSpace.value,
            colorScience = renderState?.colorProfile ?: _colorProfile.value
        )
        return renderProfileEngine.build(
            scene = null,
            recommendation = null,
            logEnabled = renderContract.logCaptureActive,
            hdrEnabled = renderContract.normalizedHdrMode != "OFF",
            colorProfile = "${authoritativeColorProfile.colorSpaceName} " +
                authoritativeColorProfile.colorScienceName,
            aiEnhancementEnabled = renderState?.aiEnhancementEnabled ?: effectiveAiEnhancementEnabled(),
            simulatedTenBitFallback = renderState?.simulatedTenBitFallback
                ?: gpuRecording.simulatedTenBitFallbackActive,
            lutEnabled = renderState?.lutEnabled ?: _lutEnabled.value,
            lutProfile = renderState?.lutProfile ?: _lutProfile.value,
            hdrMode = renderContract.normalizedHdrMode,
            previewFps = renderState?.previewFpsHint
                ?: lastPreviewFpsSample.takeIf { it > 0f }
                ?: recording.fps.toFloat(),
            recording = VideoRenderProfileQualityHintPolicy.useRecordingGradeRenderProfile(
                appMode = renderState?.deliveryKey?.appMode ?: _uiMode.value,
                recordingState = renderState?.recordingState ?: gpuRecordState.value
            ),
            outputWidth = outputRenderSize.width,
            outputHeight = outputRenderSize.height,
            sourceWidth = sourceRenderSize.width,
            sourceHeight = sourceRenderSize.height,
            videoDetailProfile = renderState?.videoDetailProfile ?: _videoDetailProfile.value,
            subjectDetailPlan = null,
            aiMotionCompensationEnabled = renderState?.aiMotionCompensationEnabled
                ?: _aiMotionCompensation.value
        )
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

    private fun isSensorLinearNdrMode(hdrMode: String = _videoHdrMode.value): Boolean =
        SensorLinearNdrModePolicy.isActive(
            _uiMode.value, _cinemaVideoMode.value, _rawOn.value, _logOn.value, hdrMode
        )

    /** True when a processed YUV scene meter is needed; sensor-linear NDR never requests vendor YUV. */
    private fun analysisFeedRequested(): Boolean =
        VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value) &&
            !isSensorLinearNdrMode() &&
            !_rawOn.value &&
            !isRawVideoModeActive() &&
            (Niv99ExecutionBoundaryPolicy.LIVE_ANALYSIS_ENABLED ||
                (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly))

    /** Keep one pre-sized analysis stream attached while a sensor exposure governor requires it. */
    private fun syncPreviewAnalysisFeed() {
        val requested = analysisFeedRequested()
        val signature = buildString {
            append("requested=").append(requested)
            append(";ui=").append(_uiMode.value.name)
            append(";cinema=").append(_cinemaVideoMode.value.name)
            append(";raw=").append(_rawOn.value)
            append(";rawVideo=").append(isRawVideoModeActive())
            append(";sensorLinearNdr=").append(isSensorLinearNdrMode())
            append(";niv99LiveAnalysis=").append(
                Niv99ExecutionBoundaryPolicy.LIVE_ANALYSIS_ENABLED
            )
        }
        if (signature != lastNiv99AnalysisRequestSignature) {
            lastNiv99AnalysisRequestSignature = signature
            NeoLogger.i("CameraViewModel", "NIV99_ANALYSIS_REQUEST $signature")
        }
        if (requested) {
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        }
        camera.setPreviewAnalysisRequested(requested)
    }

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
        if (softwarePreviewLutOwnerRequired) return false
        if (startupDirectPreviewActive || startupFeatureGateActive) {
            return false
        }
        if (
            !nativeVisiblePreviewActive &&
            !nativePreviewHandoffPending &&
            !directPreviewSurfaceActive &&
            !cameraOwnsVisiblePreviewSurface(surface) &&
            !persistentPreviewPipelineActive
        ) {
            // Native acquisition is permitted only from a proven current owner. This keeps startup
            // fail-visible while still allowing the ordered persistent-router handoff below.
            return false
        }
        if (nativePreviewRetryRequiresSurfaceReset && surface != null && surface === previewSurface) {
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
            hasPresentationFeedback = surface != null,
            niv99VideoPixelEligible =
                VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value) &&
                    !isRawVideoModeActive() &&
                    !isSensorLinearNdrMode()
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
        nativePreviewPersistentRetirementPending = false
        nativePreviewHandoffSurface = null
        nativePreviewHandoffBindJob?.cancel()
        nativePreviewHandoffBindJob = null
    }

    private fun persistentPreviewOwnerMustRetire(surface: Surface): Boolean =
        nativePreviewPersistentRetirementPending ||
            persistentPreviewPipelineActive ||
            persistentPreviewPreparation.inProgress ||
            persistentPreviewVisibleSurface === surface ||
            persistentPreviewOutputRouter.isRunning()

    /**
     * Begins an ordered producer transfer from the persistent EGL router to Vulkan. Camera2 targets
     * are unregistered immediately, but the EGL producers stay alive until the old Camera2 session
     * has closed. This preserves the last valid frame and prevents two APIs from owning the same
     * SurfaceView concurrently.
     */
    private fun beginNativePreviewHandoffFromPersistent(surface: Surface) {
        if (nativePreviewHandoffPending && nativePreviewHandoffSurface === surface) return
        stopNativePreviewWatchdog()
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        directPreviewSurfaceActive = false
        nativePreviewSurfaceResizePending = false
        nativePreviewCameraReleaseConfirmed = false
        nativePreviewPersistentRetirementPending = true
        nativePreviewHandoffPending = true
        nativePreviewHandoffSurface = surface
        disablePersistentPreviewPipeline(releaseInputs = false)
        Log.i(
            "CameraViewModel",
            "Visible NIV99 Vulkan handoff waiting for Camera2 release of persistent preview inputs"
        )
        if (cameraState.value == CameraState.Closed) {
            completeNativePreviewHandoffAfterCameraRelease(preferNativePreviewOverride = true)
        }
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
        if (nativePreviewHandoffBindJob?.isActive == true) return
        val mustRetirePersistentOwner = nativePreviewPersistentRetirementPending
        val persistentRetirementJob = if (mustRetirePersistentOwner) {
            disablePersistentPreviewPipeline(releaseInputs = true)
        } else {
            null
        }
        nativePreviewHandoffBindJob = viewModelScope.launch {
            persistentRetirementJob?.join()
            if (!nativePreviewHandoffPending || previewSurface !== pendingSurface) {
                nativePreviewHandoffBindJob = null
                return@launch
            }
            if (mustRetirePersistentOwner && !persistentPreviewPreparation.resourcesFullyRetired()) {
                Log.e(
                    "CameraViewModel",
                    "Visible NIV99 Vulkan handoff rejected: persistent EGL owner did not retire"
                )
                nativePreviewHandoffBindJob = null
                clearNativePreviewHandoffState()
                rejectStrictPreviewOutput(
                    PersistentPreviewOutputRouter.StartResult.failure(
                        PersistentPreviewOutputRouter.StartFailure.RUNTIME_EGL_FAILURE,
                        "Persistent preview resources did not retire before the NIV99 Vulkan handoff"
                    )
                )
                return@launch
            }
            nativePreviewPersistentRetirementPending = false
            nativePreviewCameraReleaseConfirmed = true
            delay(VulkanVisiblePreviewPolicy.SURFACE_HANDOFF_SETTLE_MS)
            if (!nativePreviewHandoffPending || previewSurface !== pendingSurface) {
                nativePreviewHandoffBindJob = null
                return@launch
            }
            Log.i("CameraViewModel", "Visible NIV99 Vulkan handoff acquired released SurfaceView")
            bindPreviewSurface(
                surface = pendingSurface,
                refreshSession = false,
                preferNativePreviewOverride = preferNativePreviewOverride
            )
            if (!nativeVisiblePreviewActive && directPreviewSurfaceActive) {
                camera.refreshOutputsAndSession(
                    forceRebuild = true,
                    reason = "native-preview-handoff-bind"
                )
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
            val pqMonitorHandoffRequested = shouldCompositeHdr10PqPreview()
            if (recordingStartOrActiveBlocksVisiblePreviewRebind()) {
                glPreviewVisibleSurfaceReleaseConfirmed = false
                camera.setPreviewSurface(surface)
                Log.i("CameraViewModel", "GL preview compositor handoff skipped during recording")
                glPreviewHandoffBindJob = null
                return@launch
            }
            glPreviewVisibleSurfaceReleaseConfirmed = true
            bindPreviewSurface(surface, refreshSession = false, preferNativePreviewOverride = false)
            if (
                pqMonitorHandoffRequested &&
                hdr10PqPreviewCompositorActive &&
                hdr10PqPreviewCompositor.inputSurface() === camera.getPreviewSurface()
            ) {
                // The PQ monitor cannot pre-create its camera input until EGL owns the released visible
                // SurfaceView. The first hot-swap above therefore detached Camera2 from the SurfaceView;
                // this second, reader-preserving swap attaches Camera2 to the compositor input. Without
                // it NDR stayed on the direct red/clipped platform-PQ preview even though EGL started.
                camera.refreshOutputsAndSession(
                    rebuildReaders = false,
                    forceRebuild = true,
                    reason = "pq-preview-input-bind"
                )
                Log.i(
                    "CameraViewModel",
                    "NDR PQ preview input attached after Camera2 released visible SurfaceView"
                )
            } else if (!glPreviewCompositorActive && !hdr10PqPreviewCompositorActive) {
                camera.refreshOutputsAndSession(
                    forceRebuild = true,
                    reason = "gl-preview-handoff-bind"
                )
            }
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
                camera.refreshOutputsAndSession(
                    forceRebuild = true,
                    reason = "gl-preview-handoff-fallback"
                )
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
        markStrictPreviewAdmissionPending()
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

    private val previewGlCompositor = PreviewGlCompositor()
    private var glPreviewCompositorActive = false
    // Strict 10-bit BT.2020-PQ WYSIWYG preview.
    private val hdr10PqPreviewCompositor = Hdr10PqPreviewCompositor()
    private val persistentPreviewPresentation = PersistentPreviewPresentationCoordinator(previewGlCompositor, hdr10PqPreviewCompositor)
    private var hdr10PqPreviewCompositorActive = false
    // Stable SurfaceView owner; mode changes select between same-size full-quality child outputs.
    private val persistentPreviewOutputRouter = PersistentPreviewOutputRouter()
    private val strictPreviewRuntimeFailureCoordinator: StrictPreviewRuntimeFailureCoordinator =
        StrictPreviewRuntimeFailureCoordinator(
            router = persistentPreviewOutputRouter,
            sdr = previewGlCompositor,
            hdr = hdr10PqPreviewCompositor,
            runOnOwnerThread = ::runOnMainIfNeeded,
            onFailureDetected = {
                strictNativePreviewProfileCoordinator.onRouteChanged()
                strictPreviewAdmissionState = StrictPreviewAdmissionState.FAILED
            },
            onFailure = ::handleStrictPreviewRuntimeFailure
        )
    private val persistentPreviewPreparation = PersistentPreviewPreparationCoordinator(
        scope = viewModelScope,
        camera = camera,
        router = persistentPreviewOutputRouter,
        sdr = previewGlCompositor,
        hdr = hdr10PqPreviewCompositor,
        presentation = persistentPreviewPresentation,
        runtimeFailure = strictPreviewRuntimeFailureCoordinator
    )
    @Volatile private var persistentPreviewPipelineActive = false
    @Volatile private var persistentPreviewVisibleSurface: Surface? = null
    private var persistentPreviewInputSize: Size? = null
    @Volatile private var strictPreviewAdmissionState = StrictPreviewAdmissionState.PENDING
    private val strictNativePreviewProfileCoordinator: StrictNativePreviewProfileCoordinator by
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StrictNativePreviewProfileCoordinator(
            StrictNativePreviewProfileCoordinator.Runtime(
                routeSnapshot = {
                    StrictNativePreviewProfileCoordinator.RouteSnapshot(
                        appMode = _uiMode.value,
                        admissionState = strictPreviewAdmissionState,
                        persistentRouteHealthy = persistentPreviewPipelineActive &&
                            strictPreviewRuntimeFailureCoordinator.isPersistentRouteHealthy(),
                        nativeRouteActive = nativeVisiblePreviewActive
                    )
                },
                isIdentityCurrent = vulkanRuntime::isRenderProfileIdentityCurrent,
                refreshCapabilitySummary = vulkanRuntime::requestCapabilitySummaryRefresh,
                capabilitySummary = { vulkanRuntime.capabilitySummary.value },
                runOnOwnerThread = ::runOnMainIfNeeded,
                detachExactNativeSurface =
                    vulkanRuntime::detachPreviewSurfaceForRenderProfileIdentity,
                onNativeRouteDetached = { fallback ->
                    val surface = previewSurface
                    nativeVisiblePreviewActive = false
                    nativeVisiblePreviewConfirmed = false
                    previewViewRef?.setNativeCompositorActive(false)
                    markStrictPreviewAdmissionPending()
                    if (surface != null) {
                        markNativePreviewFallback(
                            surface = surface,
                            reason = "Native preview lost >=10-bit precision after render identity " +
                                "${fallback.identity} (format=${fallback.nativeFormat}); " +
                                "trying strict persistent output",
                            requiresSurfaceReset = true
                        )
                        bindPreviewSurface(surface, preferNativePreviewOverride = false)
                        enqueueVulkanPreviewRenderConfig(camera.frameGeometry.value, force = true)
                    }
                },
                isGenerationCurrent = ::isVulkanRenderProfileGenerationCurrent,
                replayLatestProfile = {
                    enqueueVulkanPreviewRenderConfig(camera.frameGeometry.value, force = true)
                },
                invalidateFrameIngress =
                    vulkanRuntime::invalidateNdrFrameIngressForPreviewRouteChange,
                frameIngressEpoch = vulkanRuntime::currentNdrFrameIngressEpoch
            )
        )
    }
    @Volatile private var strictPreviewOutputFailure: PersistentPreviewOutputRouter.StartResult? = null
    private var openCameraAfterPersistentPreviewPreparation = false
    private val persistentPreviewLifecycle: PersistentPreviewLifecycleCoordinator<Surface> by lazy {
        PersistentPreviewLifecycleCoordinator<Surface>(
            scope = viewModelScope,
            runOnMain = ::runOnMainIfNeeded,
            pipelineActive = { persistentPreviewPipelineActive },
            sensorLinearNdrMode = ::isSensorLinearNdrMode,
            currentPresentationEpoch = { rawPreviewPresentationCoordinator.epoch },
            presentationEpochCurrent = rawVideoManager::isPreviewPresentationEpochCurrent,
            inputForOwner = { owner -> when (owner) {
                PersistentPreviewOwner.SDR -> previewGlCompositor.inputSurface()
                PersistentPreviewOwner.HDR -> hdr10PqPreviewCompositor.inputSurface()
            } },
            beginCameraHandoff = camera::beginPersistentPreviewHandoff,
            cancelCameraHandoff = camera::cancelPersistentPreviewHandoff,
            completeCameraHandoff = camera::completePersistentPreviewHandoff,
            selectOutput = { owner -> persistentPreviewOutputRouter.selectSource(owner.routerSource) },
            updatePresentation = persistentPreviewPresentation::update,
            onHandoffCommitted = { owner ->
                glPreviewCompositorActive = owner == PersistentPreviewOwner.SDR
                hdr10PqPreviewCompositorActive = owner == PersistentPreviewOwner.HDR
                glPreviewVisibleSurfaceReleaseConfirmed = true
            }
        )
    }
    init {
        // The preview compositor retains its general GPU quality stages, but NIV99 is deliberately
        // absent from the live frame path. Converted derivatives apply NIV99 after capture instead.
        previewGlCompositor.setNiv99FrameProcessor(null)
        NdrRenderProfileLifecycleCoordinator.observeMutationReadyIdentities(
            viewModelScope,
            vulkanRuntime.ndrRenderProfileMutationReadyIdentity,
            ::isVulkanRenderProfileGenerationCurrent
        ) {
            // The recorder has retired the exact old-profile Vulkan/EGL ownership. Rebuild from the
            // latest immutable authority; the revision sequencer suppresses duplicate replacements.
            pushVulkanPreviewRenderConfig(force = true)
        }
        NdrRenderProfileLifecycleCoordinator.observeStagedGenerations(
            viewModelScope, vulkanRuntime.ndrRenderProfileStagedGeneration,
            ::isVulkanRenderProfileGenerationCurrent
        ) {
            // Cold-start replay can stage native state after the original request returned. Capture a
            // fresh immutable request for the current generation so owner state is finalized too.
            pushVulkanPreviewRenderConfig(force = true)
        }
        NdrRenderProfileLifecycleCoordinator.observeReadyGenerations(
            viewModelScope, vulkanRuntime.ndrRenderProfileReadyGeneration,
            ::isVulkanRenderProfileGenerationCurrent, ::isSensorLinearNdrMode
        ) { rawPreviewPresentationCoordinator.onNdrRenderProfileReady(it) }
        projectOwnedNdrOrientationTargetReady = true
        publishProjectOwnedNdrOrientation(
            latestDeviceRotationDegrees = currentRotationDegrees,
            geometry = camera.frameGeometry.value
        )
        previewGlCompositor.setOnFramePresentedListener { timestampNs, outputSurface, presentationTimestampNs ->
            when {
                outputSurface === previewSurface -> {
                    recordCompositedPreviewFrameLineageSubmission(
                        timestampNs,
                        outputSurface,
                        PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP,
                        presentationTimestampNs
                    )
                }
                outputSurface === persistentPreviewOutputRouter.sdrOutputSurface() -> {
                    recordPreviewPixelRouteStage(
                        PreviewPixelRouteStage.SDR_CHILD_SWAP,
                        timestampNs
                    )
                    persistentPreviewOutputRouter.registerChildFramePresented(
                        PersistentPreviewOutputRouter.SOURCE_SDR,
                        timestampNs
                    )
                }
            }
        }
        hdr10PqPreviewCompositor.setOnFramePresentedListener { timestampNs, outputSurface, presentationTimestampNs ->
            when {
                outputSurface === previewSurface -> {
                    recordCompositedPreviewFrameLineageSubmission(
                        timestampNs,
                        outputSurface,
                        PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP,
                        presentationTimestampNs
                    )
                }
                outputSurface === persistentPreviewOutputRouter.hdrOutputSurface() -> {
                    recordPreviewPixelRouteStage(
                        PreviewPixelRouteStage.HDR_CHILD_SWAP,
                        timestampNs
                    )
                    persistentPreviewOutputRouter.registerChildFramePresented(
                        PersistentPreviewOutputRouter.SOURCE_HDR,
                        timestampNs
                    )
                }
            }
        }
        persistentPreviewOutputRouter.setOnFramePresentedListener { timestampNs, outputSurface, presentationTimestampNs ->
            if (outputSurface === previewSurface) {
                recordCompositedPreviewFrameLineageSubmission(
                    timestampNs,
                    outputSurface,
                    PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP,
                    presentationTimestampNs
                )
            }
        }
        previewGlCompositor.setOnInputFrameListener { timestampNs ->
            persistentPreviewLifecycle.onInputFrame(PersistentPreviewOwner.SDR, timestampNs)
        }
        hdr10PqPreviewCompositor.setOnInputFrameListener { timestampNs, presentationEpoch ->
            persistentPreviewLifecycle.onInputFrame(
                inputOwner = PersistentPreviewOwner.HDR,
                timestampNs = timestampNs,
                projectOwnedEpoch = presentationEpoch
            )
        }
        hdr10PqPreviewCompositor.setOnProjectOwnedFramePresentedListener { presentationEpoch, timestampNs ->
            persistentPreviewLifecycle.onProjectOwnedFramePresented(presentationEpoch, timestampNs)
        }
    }
    // The real 10-bit PQ EGL engage runs off-main and is guarded against duplicate launches.
    @Volatile private var hdr10PqPreviewEngageInProgress = false
    @Volatile private var glPreviewCompositorEngageInProgress = false
    @Volatile private var glPreviewInputPrepareInProgress = false
    private var glPreviewSurfaceReleasePending = false
    private var glPreviewVisibleSurfaceReleaseConfirmed = false
    private var glPreviewHandoffBindJob: Job? = null
    private var glPreviewHandoffFallbackJob: Job? = null
    private var glPreviewBindingResyncJob: Job? = null
    private val GL_PREVIEW_MAX_ENGAGE_RETRIES = 4
    private val GL_PREVIEW_ENGAGE_RETRY_DELAY_MS = 1_200L
    private val GL_PREVIEW_STARTUP_OWNER_COOLDOWN_MS = 1_500L
    private var glPreviewOwnerCooldownUntilElapsedMs = SystemClock.elapsedRealtime() + GL_PREVIEW_STARTUP_OWNER_COOLDOWN_MS
    // Bounded self-heal for a cross-HDR engage while the SurfaceView holder is settling.
    private var glPreviewEngageRetries = 0
    private var glPreviewEngageRetryScheduled = false
    private val GL_PREVIEW_CAMERA2_RELEASE_SETTLE_MS = 0L
    private val GL_PREVIEW_CAMERA2_RELEASE_FALLBACK_MS = GL_PREVIEW_CAMERA2_RELEASE_SETTLE_MS + 1_500L

    private fun activeNdrPqDeliveryMode(): Int = NdrPreviewRenderPolicy.deliveryMode(_uiMode.value, _logOn.value, isRawVideoModeActive(), currentVideoHdrMode)
    private fun activeNdrPeakPlan(appliedProfile: Long?): NdrPeakCapabilityPolicy.Plan = NdrPreviewRenderPolicy.peakPlan(getApplication<Application>(), activeNdrPqDeliveryMode().coerceAtLeast(Hdr10PqPreviewCompositor.MODE_NDR10_PLUS), appliedProfile)

    /**
     * The stable visible owner is itself a verified RGBA1010102 BT.2020-PQ surface. SDR children are
     * reference-mapped into that carrier and HDR children remain native PQ; no 8-bit router is allowed.
     */
    private fun persistentPreviewPipelineEligible(preferNativePreview: Boolean): Boolean =
        !preferNativePreview &&
            com.neoproc.neoproccamx.rendering.PersistentPreviewPipelineFlag.enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            GlVisiblePreviewPolicy.enabled &&
            Hdr10PqPreviewPolicy.enabled

    private fun desiredPersistentPreviewOwner(): PersistentPreviewOwner =
        if (shouldCompositeHdr10PqPreview()) PersistentPreviewOwner.HDR else PersistentPreviewOwner.SDR

    private fun desiredPreviewPipelineOwner(preferNativePreview: Boolean): PreviewPipelineOwner {
        if (softwarePreviewLutOwnerRequired && !shouldCompositeHdr10PqPreview()) {
            return if (persistentPreviewPipelineEligible(preferNativePreview = false)) {
                PreviewPipelineOwner.PERSISTENT_SDR
            } else {
                PreviewPipelineOwner.GL_SDR
            }
        }
        if (preferNativePreview) return PreviewPipelineOwner.NATIVE
        if (persistentPreviewPipelineEligible(preferNativePreview = false)) {
            return when (persistentPreviewLifecycle.readyOwner(desiredPersistentPreviewOwner())) {
                PersistentPreviewOwner.SDR -> PreviewPipelineOwner.PERSISTENT_SDR
                PersistentPreviewOwner.HDR -> PreviewPipelineOwner.PERSISTENT_HDR
            }
        }
        return when {
            shouldCompositeHdr10PqPreview() -> PreviewPipelineOwner.GL_HDR
            resolveGlPreviewGrade() != GlVisiblePreviewPolicy.PreviewGrade.IDENTITY -> PreviewPipelineOwner.GL_SDR
            else -> PreviewPipelineOwner.DIRECT
        }
    }

    private fun currentPreviewPipelineOwner(): PreviewPipelineOwner = when {
        nativeVisiblePreviewActive -> PreviewPipelineOwner.NATIVE
        persistentPreviewPipelineActive && persistentPreviewLifecycle.owner == PersistentPreviewOwner.SDR ->
            PreviewPipelineOwner.PERSISTENT_SDR
        persistentPreviewPipelineActive && persistentPreviewLifecycle.owner == PersistentPreviewOwner.HDR ->
            PreviewPipelineOwner.PERSISTENT_HDR
        hdr10PqPreviewCompositorActive -> PreviewPipelineOwner.GL_HDR
        glPreviewCompositorActive -> PreviewPipelineOwner.GL_SDR
        directPreviewSurfaceActive || cameraOwnsVisiblePreviewSurface(previewSurface) -> PreviewPipelineOwner.DIRECT
        else -> PreviewPipelineOwner.UNKNOWN
    }

    private fun disablePersistentPreviewPipeline(releaseInputs: Boolean): Job? {
        strictPreviewRuntimeFailureCoordinator.deactivateRoute()
        persistentPreviewLifecycle.reset(releaseOwner = releaseInputs)
        val retirementJob = persistentPreviewPreparation.detach(releaseInputs)
        persistentPreviewPipelineActive = false
        persistentPreviewVisibleSurface = null
        persistentPreviewInputSize = null
        if (releaseInputs) {
            glPreviewCompositorActive = false
            hdr10PqPreviewCompositorActive = false
        }
        return retirementJob
    }

    private fun persistentPreviewRenderState(): PersistentPreviewPreparationCoordinator.RenderState {
        return NdrPreviewRenderPolicy.persistentRenderState(
            context = getApplication<Application>(),
            hdrState = camera.describeHdrDynamicRangeState(),
            owner = persistentPreviewLifecycle.readyOwner(desiredPersistentPreviewOwner()),
            gradeCode = glPreviewGradeCode(resolveGlPreviewGrade()),
            requestedPqMode = activeNdrPqDeliveryMode()
        )
    }

    private fun rejectStrictPreviewOutput(result: PersistentPreviewOutputRouter.StartResult) {
        val rejection = StrictPreviewAdmissionPolicy.rejection(result)
        strictNativePreviewProfileCoordinator.onRouteChanged()
        strictPreviewRuntimeFailureCoordinator.deactivateRoute()
        strictPreviewAdmissionState = StrictPreviewAdmissionState.FAILED
        strictPreviewOutputFailure = rejection.result
        directPreviewSurfaceActive = false
        camera.setPreviewSurface(null)
        setHardwareWarning(rejection.warning)
    }

    private fun handleStrictPreviewRuntimeFailure(failure: PreviewRuntimeFailure) {
        val runtimeFailure = StrictPreviewAdmissionPolicy.runtimeFailure(failure)
        Log.e("CameraViewModel", "Strict preview runtime failure: ${runtimeFailure.detail}")
        val retirementGeneration = persistentPreviewPreparation.rejectRuntimeFailure()
        // Phase 1: unregister targets but keep every EGL consumer alive until Camera2 proves the
        // outgoing session is closed. Releasing a still-configured BufferQueue here can fault the
        // HAL or the encoder request that shares the session.
        disablePersistentPreviewPipeline(releaseInputs = false)
        glPreviewCompositorActive = false
        hdr10PqPreviewCompositorActive = false
        rejectStrictPreviewOutput(runtimeFailure)
        // Clearing controller fields alone does not change an already-issued repeating request.
        // Re-submit against the same configured session so Camera2 immediately stops feeding the
        // failed child queues while an active recording target, if any, remains undisturbed.
        camera.refreshRepeatingRequest()
        val releaseFailedRoute = strictPreviewRuntimeFailureCoordinator.ownerThreadRetirement(
            isCurrent = {
                persistentPreviewPreparation.isRuntimeFailureRetirementCurrent(retirementGeneration)
            },
            onRelease = { disablePersistentPreviewPipeline(releaseInputs = true) }
        )
        when (cameraState.value) {
            CameraState.Closed -> releaseFailedRoute()
            CameraState.Closing,
            is CameraState.Error -> Log.w(
                "CameraViewModel",
                "Strict preview queues quarantined until camera/session close acknowledgement"
            )
            else -> camera.refreshOutputsAndSession(
                rebuildReaders = false,
                forceRebuild = true,
                reason = "strict-preview-runtime-failure",
                onPreviousSessionClosed = releaseFailedRoute
            )
        }
    }

    private fun markStrictPreviewAdmissionPending() {
        val required = StrictPreviewAdmissionPolicy.required(_uiMode.value)
        // Preserve a render-thread failure epoch until its owner-thread retirement runs.
        if (required &&
            (strictPreviewAdmissionState == StrictPreviewAdmissionState.FAILED || persistentPreviewPreparation.failed)
        ) return
        if (required) strictNativePreviewProfileCoordinator.onRouteChanged()
        strictPreviewRuntimeFailureCoordinator.deactivateRoute()
        if (required) {
            strictPreviewAdmissionState = StrictPreviewAdmissionState.PENDING
        }
    }

    private fun resetStrictPreviewAdmissionForLifecycle() {
        strictNativePreviewProfileCoordinator.onRouteChanged()
        strictPreviewRuntimeFailureCoordinator.deactivateRoute()
        strictPreviewAdmissionState = StrictPreviewAdmissionState.PENDING
    }

    private fun activateStrictPreviewAdmission(state: StrictPreviewAdmissionState) {
        require(state.active) { "Strict preview activation requires a proven active route" }
        strictNativePreviewProfileCoordinator.onRouteChanged()
        if (state == StrictPreviewAdmissionState.ACTIVE_PERSISTENT) {
            // Re-arm a retained Settings route; an active failed route remains failed.
            strictPreviewRuntimeFailureCoordinator.activatePersistentRoute()
            if (!strictPreviewRuntimeFailureCoordinator.isPersistentRouteHealthy()) {
                strictPreviewAdmissionState = StrictPreviewAdmissionState.FAILED
                return
            }
        } else {
            strictPreviewRuntimeFailureCoordinator.deactivateRoute()
        }
        strictPreviewAdmissionState = state
        strictPreviewOutputFailure = null
        if (_uiStateFlow.value.hardwareWarning?.startsWith(
                "Strict 10-bit BT.2020-PQ preview is unavailable") == true) {
            setHardwareWarning(null)
        }
    }

    /** A packet replacement may rebuild Vulkan's swapchain and silently select RGBA8 again. */
    private fun strictPreviewProfileProofStillCurrent(
        identity: NdrRenderProfileIdentity
    ): Boolean = strictNativePreviewProfileCoordinator.proofStillCurrent(identity)

    private fun revalidateStrictNativePreviewForProfile(
        identity: NdrRenderProfileIdentity
    ): Boolean = strictNativePreviewProfileCoordinator.revalidate(identity)

    private fun schedulePersistentPreviewPipelinePreparation(
        visibleSurface: Surface,
        previewSize: Size
    ) {
        if (persistentPreviewPipelineActive) return
        if (persistentPreviewPreparation.inProgress) return
        markStrictPreviewAdmissionPending()
        // Proof never transfers across EGL/router outputs, even if Android reuses the Surface object.
        persistentPreviewLifecycle.invalidateSurface()
        persistentPreviewPreparation.schedule(
            request = PersistentPreviewPreparationCoordinator.Request(
                visibleSurface = visibleSurface,
                previewSize = previewSize,
                initialState = persistentPreviewRenderState()
            ),
            isCurrent = {
                previewSurface === visibleSurface &&
                    runCatching { visibleSurface.isValid }.getOrDefault(false)
            },
            currentState = ::persistentPreviewRenderState,
            onActivated = { activation ->
                val healthy = strictPreviewRuntimeFailureCoordinator.isPersistentRouteHealthy()
                if (healthy) {
                    val owner = activation.owner
                    persistentPreviewPipelineActive = true
                    persistentPreviewVisibleSurface = activation.visibleSurface
                    persistentPreviewInputSize = activation.previewSize
                    persistentPreviewLifecycle.activateOwner(owner)
                    glPreviewCompositorActive = owner == PersistentPreviewOwner.SDR
                    hdr10PqPreviewCompositorActive = owner == PersistentPreviewOwner.HDR
                    glPreviewVisibleSurfaceReleaseConfirmed = true
                    directPreviewSurfaceActive = false
                    activateStrictPreviewAdmission(StrictPreviewAdmissionState.ACTIVE_PERSISTENT)
                    // A request staged while strict output was pending deliberately kept ingress
                    // closed. Replay the latest immutable authority only after the persistent route
                    // proves active; this request can now finalize without an 8-bit exposure window.
                    enqueueVulkanPreviewRenderConfig(camera.frameGeometry.value, force = true)
                }
                healthy &&
                    strictPreviewRuntimeFailureCoordinator.isPersistentRouteHealthy() &&
                    strictPreviewAdmissionState == StrictPreviewAdmissionState.ACTIVE_PERSISTENT
            },
            onStrictOutputRejected = ::rejectStrictPreviewOutput,
            onFinished = {
                val latestSurface = previewSurface
                if (!persistentPreviewPreparation.failed &&
                    !persistentPreviewPipelineActive &&
                    !settingsCameraLifecycle.isSuspended() &&
                    latestSurface != null &&
                    runCatching { latestSurface.isValid }.getOrDefault(false)
                ) {
                    // A surface/profile refresh may supersede the transaction while EGL is starting.
                    // Coalesce onto the latest authority instead of converting that benign race into a
                    // sticky strict-output failure. schedule() is latest-wins and remains fail-closed.
                    schedulePersistentPreviewPipelinePreparation(
                        visibleSurface = latestSurface,
                        previewSize = preferredPreviewSize()
                    )
                }
                if (!persistentPreviewPreparation.inProgress) {
                    val openPending = openCameraAfterPersistentPreviewPreparation
                    openCameraAfterPersistentPreviewPreparation = false
                    if (openPending) openCamera()
                }
            }
        )
    }

    private fun applyPersistentPreviewPipeline(
        visibleSurface: Surface,
        previewSize: Size,
        preferNativePreview: Boolean
    ): Boolean {
        val strictOutputRequired = StrictPreviewAdmissionPolicy.required(_uiMode.value)
        if (strictOutputRequired &&
            strictPreviewAdmissionState == StrictPreviewAdmissionState.FAILED
        ) return true
        if (!persistentPreviewPipelineEligible(preferNativePreview)) {
            if (persistentPreviewPipelineActive || persistentPreviewPreparation.inProgress) {
                disablePersistentPreviewPipeline(releaseInputs = true)
            }
            if (preferNativePreview && strictOutputRequired) {
                // Native is only a request at this point. Its actual swapchain format is proved after
                // binding; keep recording closed until that proof or the persistent fallback succeeds.
                markStrictPreviewAdmissionPending()
                return false
            }
            if (!preferNativePreview &&
                (strictOutputRequired || shouldCompositeHdr10PqPreview())
            ) {
                rejectStrictPreviewOutput(
                    PersistentPreviewOutputRouter.StartResult.failure(
                        PersistentPreviewOutputRouter.StartFailure.TEN_BIT_CONFIG_UNAVAILABLE,
                        "The strict persistent PQ output is not eligible on this runtime"
                    )
                )
                return true
            }
            return false
        }

        if (persistentPreviewPipelineActive &&
            (persistentPreviewVisibleSurface !== visibleSurface || persistentPreviewInputSize != previewSize)
        ) {
            disablePersistentPreviewPipeline(releaseInputs = true)
        }

        if (!persistentPreviewPipelineActive) {
            // A typed failure is sticky until the camera-close lifecycle explicitly resets the
            // preparation coordinator. Do not rewrite FAILED as PENDING on incidental rebinds.
            if (persistentPreviewPreparation.failed) return true
            // Re-entrant surface/profile refreshes must not deactivate the route gate between off-main
            // EGL creation and main-thread activation. The running transaction already re-reads the
            // latest render state; its completion callback coalesces a genuinely stale surface request.
            if (persistentPreviewPreparation.inProgress) return true
            markStrictPreviewAdmissionPending()
            schedulePersistentPreviewPipelinePreparation(visibleSurface, previewSize)
            return true
        }

        val renderState = persistentPreviewRenderState()
        val desiredOwner = renderState.owner
        val sdrInput = previewGlCompositor.inputSurface()
        val hdrInput = hdr10PqPreviewCompositor.inputSurface()
        val warmRenderersAvailable =
            persistentPreviewOutputRouter.isRunning() &&
                previewGlCompositor.isRunning() &&
                hdr10PqPreviewCompositor.isRunning() &&
                sdrInput != null &&
                hdrInput != null
        val cameraOutputsAvailable = camera.persistentPreviewOutputsAvailable() ||
            (warmRenderersAvailable && camera.setPersistentPreviewOutputs(sdrInput, hdrInput)).also {
                if (it && settingsPreviewPresentationRetained) {
                    Log.i(
                        "CameraViewModel",
                        "Settings resume re-registered retained SDR/PQ preview inputs"
                    )
                }
            }
        val preparedPipelineAvailable =
            warmRenderersAvailable && cameraOutputsAvailable
        if (!preparedPipelineAvailable) {
            Log.e(
                "CameraViewModel",
                "Prepared persistent preview pipeline became unavailable; direct fallback blocked"
            )
            disablePersistentPreviewPipeline(releaseInputs = true)
            rejectStrictPreviewOutput(
                PersistentPreviewOutputRouter.StartResult.failure(
                    PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                    "A previously prepared strict preview child or Camera2 output became unavailable"
                )
            )
            return true
        }
        activateStrictPreviewAdmission(StrictPreviewAdmissionState.ACTIVE_PERSISTENT)

        // Preparation owns all EGL creation. Live mode changes update only shader state and the
        // stable router selection, keeping both Camera2 targets and the visible BufferQueue intact.
        previewGlCompositor.setGrade(renderState.gradeCode)
        hdr10PqPreviewCompositor.setInputProfile(renderState.hdrProfile)
        hdr10PqPreviewCompositor.setMode(renderState.pqMode)
        hdr10PqPreviewCompositor.setPeakPlan(renderState.peakPlan)

        if (persistentPreviewLifecycle.owner == null) {
            persistentPreviewPresentation.update(desiredOwner)
            if (!persistentPreviewOutputRouter.selectSource(desiredOwner.routerSource)) {
                disablePersistentPreviewPipeline(releaseInputs = true)
                rejectStrictPreviewOutput(
                    PersistentPreviewOutputRouter.StartResult.failure(
                        PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                        "Strict preview router rejected the initial visible source"
                    )
                )
                return true
            }
            persistentPreviewLifecycle.adoptOwner(desiredOwner)
            glPreviewCompositorActive = desiredOwner == PersistentPreviewOwner.SDR
            hdr10PqPreviewCompositorActive = desiredOwner == PersistentPreviewOwner.HDR
            camera.setPreviewSurface(if (desiredOwner == PersistentPreviewOwner.SDR) sdrInput else hdrInput)
            glPreviewVisibleSurfaceReleaseConfirmed = true
            Log.i("CameraViewModel", "Persistent preview pipeline attached initial owner=$desiredOwner")
            return true
        }

        if (persistentPreviewLifecycle.owner == desiredOwner) {
            if (persistentPreviewLifecycle.targetOwner != null) persistentPreviewLifecycle.cancelPendingHandoff()
            persistentPreviewPresentation.update(desiredOwner)
            if (!persistentPreviewOutputRouter.selectSource(desiredOwner.routerSource)) {
                disablePersistentPreviewPipeline(releaseInputs = true)
                rejectStrictPreviewOutput(
                    PersistentPreviewOutputRouter.StartResult.failure(
                        PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                        "Strict preview router lost its active visible source"
                    )
                )
                return true
            }
            camera.setPreviewSurface(if (desiredOwner == PersistentPreviewOwner.SDR) sdrInput else hdrInput)
            glPreviewCompositorActive = desiredOwner == PersistentPreviewOwner.SDR
            hdr10PqPreviewCompositorActive = desiredOwner == PersistentPreviewOwner.HDR
            return true
        }

        if (cameraState.value == CameraState.Closed) {
            // Settings can change SDR/NDR/RAW ownership while Camera2 is fully released. No live-session
            // warmup is needed in that state: route the already-warm compositor before open so the very
            // first returned frame has the selected mode's correct monitor transform.
            persistentPreviewPresentation.update(desiredOwner)
            if (!persistentPreviewOutputRouter.selectSource(desiredOwner.routerSource)) {
                disablePersistentPreviewPipeline(releaseInputs = true)
                rejectStrictPreviewOutput(
                    PersistentPreviewOutputRouter.StartResult.failure(
                        PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                        "Strict preview router rejected the closed-camera owner transition"
                    )
                )
                return true
            }
            persistentPreviewLifecycle.adoptOwner(desiredOwner)
            glPreviewCompositorActive = desiredOwner == PersistentPreviewOwner.SDR
            hdr10PqPreviewCompositorActive = desiredOwner == PersistentPreviewOwner.HDR
            camera.setPreviewSurface(if (desiredOwner == PersistentPreviewOwner.SDR) sdrInput else hdrInput)
            Log.i("CameraViewModel", "Settings return selected warm preview owner=$desiredOwner before Camera2 open")
            return true
        }

        persistentPreviewLifecycle.beginHandoff(desiredOwner)
        return true
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
            hdr10PqPreviewCompositor.releaseAsync()
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
            nativeVulkanPreviewActive = nativeVisiblePreviewActive,
            softwareLutOwnerRequired = softwarePreviewLutOwnerRequired
        )

    private fun glPreviewGradeCode(grade: GlVisiblePreviewPolicy.PreviewGrade): Int =
        when (grade) {
            GlVisiblePreviewPolicy.PreviewGrade.NEOLOG -> PreviewGlCompositor.GRADE_NEOLOG
            GlVisiblePreviewPolicy.PreviewGrade.RAW_MONITOR -> PreviewGlCompositor.GRADE_RAW_MONITOR
            GlVisiblePreviewPolicy.PreviewGrade.SDR_ENHANCE -> PreviewGlCompositor.GRADE_SDR_ENHANCE
            GlVisiblePreviewPolicy.PreviewGrade.IDENTITY -> PreviewGlCompositor.GRADE_IDENTITY
        }

    private fun recordingStartOrActiveBlocksVisiblePreviewRebind(): Boolean {
        if (recordingStopRestartLifecycle.isStarting) return true
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
        val feedPath = activeRecordingFeedPath ?: if (recordingStopRestartLifecycle.isStarting) {
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
        // The persistent pair is already part of the standing recording-capable Camera2 graph and
        // its stable router owns the visible window. Releasing either child here would invalidate a
        // configured session surface and recreate exactly the preview freeze this path avoids.
        if (persistentPreviewPipelineActive && persistentPreviewOutputRouter.isRunning()) {
            Log.i(
                "CameraViewModel",
                "Persistent preview pair retained through ${feedPath.name} recording session"
            )
            return
        }
        val releasedSdrCompositor = glPreviewCompositorActive || previewGlCompositor.isRunning()
        if (releasedSdrCompositor) {
            previewGlCompositor.releaseAsync()
            glPreviewCompositorActive = false
        }
        // Preserve the isolated native-PQ monitor; it never owns the encoder surface.
        val preservePqMonitor =
            hdr10PqPreviewCompositorActive && hdr10PqPreviewCompositor.isRunning()
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

    /** Clears the RAW 2D layer while retaining the live EGL/OES owner for a zero-churn mode handoff. */
    private fun releaseRawPreviewMonitorForModeExit(
        previousMode: CinemaVideoMode,
        currentMode: CinemaVideoMode
    ) {
        if (previousMode != CinemaVideoMode.RAW || currentMode == CinemaVideoMode.RAW) return
        runCatching { previewGlCompositor.clearDebayerPresent() }
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
        if (previewGlCompositor.isReleasing() || hdr10PqPreviewCompositor.isReleasing()) return false
        if (camera.sessionRefreshPending.get()) return false
        if (recordingStartOrActiveBlocksVisiblePreviewRebind()) return false
        if (glPreviewSurfaceReleasePending) return false
        if (!glPreviewVisibleSurfaceReleaseConfirmed) return false
        if (cameraOwnsVisiblePreviewSurface(visibleSurface)) return false
        return true
    }

    private fun applyGlPreviewCompositorTarget(visibleSurface: Surface, previewSize: Size): Boolean {
        clearStoppedVisibleGlPreviewCompositorOwnership()
        // Gated 10-bit BT.2020-PQ WYSIWYG path (default OFF). When engaged it OWNS the preview
        // surface (its own isolated 10-bit EGL context); the 8-bit compositor is released. Fully
        // no-op when the gate is off — pqMode stays -1 and control falls through unchanged.
        val hdrDynamicRangeState = camera.describeHdrDynamicRangeState()
        val appliedPreviewProfile = NdrInputTransportPolicy.effectivePreviewProfile(
            appliedPreviewProfile = hdrDynamicRangeState.previewProfile,
            resolvedProfileForCurrentMode = hdrDynamicRangeState.resolvedProfileForCurrentMode,
            sessionConfigured = hdrDynamicRangeState.sessionConfigured,
            appliedProfileTruthCurrent = hdrDynamicRangeState.appliedProfileTruthCurrent
        )
        val pqMode = if (
            shouldCompositeHdr10PqPreview() &&
            NdrPreviewInputAdmissionPolicy.canPrepareFromCameraProfile(appliedPreviewProfile)
        ) {
            activeNdrPqDeliveryMode()
        } else {
            -1
        }
        if (pqMode >= 0) {
            val ndrPeakPlan = activeNdrPeakPlan(appliedPreviewProfile)
            if (glPreviewCompositorActive) {
                previewGlCompositor.releaseAsync()
                glPreviewCompositorActive = false
            }
            if (hdr10PqPreviewCompositorActive && hdr10PqPreviewCompositor.isRunning()) {
                if (cameraOwnsVisiblePreviewSurface(visibleSurface)) {
                    releaseHdr10PqPreviewCompositorIfActive()
                    glPreviewVisibleSurfaceReleaseConfirmed = false
                    scheduleGlPreviewEngageRetry()
                    return false
                }
                hdr10PqPreviewCompositor.setInputProfile(appliedPreviewProfile)
                hdr10PqPreviewCompositor.setMode(pqMode)
                hdr10PqPreviewCompositor.setPeakPlan(ndrPeakPlan)
                rawPreviewPresentationCoordinator.onNdrCompositorReady()
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
            // Build EGL off-main; completion explicitly rebinds Camera2 to the new input surface.
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
                            appliedPreviewProfile,
                            ndrPeakPlan,
                            publishInitialBlack = false
                            )
                        ) {
                            hdr10PqPreviewCompositor.inputSurface()
                        } else {
                            null
                        }
                    }
                    if (pqInput != null) {
                        // The surface can change during EGL creation; never bind a stale input.
                        val engageSurfaceStillCurrent =
                            previewSurface === visibleSurface &&
                                runCatching { visibleSurface.isValid }.getOrDefault(false) &&
                                cameraPreviewReadyForGlPreviewCompositor()
                        if (engageSurfaceStillCurrent) {
                            glPreviewEngageRetries = 0
                            hdr10PqPreviewCompositorActive = true
                            hdr10PqPreviewEngageInProgress = false
                            rawPreviewPresentationCoordinator.onNdrCompositorReady()
                            bindPreviewSurface(visibleSurface, refreshSession = false)
                            camera.refreshOutputsAndSession(
                                rebuildReaders = false,
                                forceRebuild = true,
                                reason = "async-hdr-preview-input-bind"
                            )
                            Log.i(
                                "CameraViewModel",
                                "HDR10 PQ preview compositor engaged (async) mode=$engageMode ${engageWidth}x${engageHeight}"
                            )
                        } else {
                            hdr10PqPreviewCompositor.releaseAsync()
                            hdr10PqPreviewCompositorActive = false
                            Log.w(
                                "CameraViewModel",
                                "HDR10 PQ preview compositor engage discarded: preview surface changed during async EGL build"
                            )
                        }
                    } else {
                        // Keep direct preview and retry only within the bounded engage policy.
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
                previewGlCompositor.releaseAsync()
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
                previewGlCompositor.releaseAsync()
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
            previewGlCompositor.releaseAsync()
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
        if (!glPreviewCompositorEngageInProgress) {
            glPreviewCompositorEngageInProgress = true
            val engageWidth = previewSize.width
            val engageHeight = previewSize.height
            val engageGrade = glPreviewGradeCode(grade)
            viewModelScope.launch {
                val input = withContext(Dispatchers.Default) {
                    if (previewGlCompositor.start(
                            visibleSurface,
                            engageWidth,
                            engageHeight,
                            engageGrade
                        )
                    ) previewGlCompositor.inputSurface() else null
                }
                val surfaceStillCurrent =
                    previewSurface === visibleSurface &&
                        runCatching { visibleSurface.isValid }.getOrDefault(false) &&
                        cameraPreviewReadyForGlPreviewCompositor()
                if (input != null && surfaceStillCurrent) {
                    glPreviewEngageRetries = 0
                    glPreviewCompositorActive = true
                    glPreviewVisibleSurfaceReleaseConfirmed = false
                    glPreviewCompositorEngageInProgress = false
                    bindPreviewSurface(visibleSurface)
                    Log.i(
                        "CameraViewModel",
                        "GL preview compositor engaged asynchronously grade=$grade ${engageWidth}x${engageHeight}"
                    )
                } else {
                    previewGlCompositor.releaseAsync()
                    glPreviewCompositorActive = false
                    glPreviewVisibleSurfaceReleaseConfirmed = false
                    glPreviewCompositorEngageInProgress = false
                    // Direct preview remains valid; the bounded retry can self-heal after reconfiguration.
                    scheduleGlPreviewEngageRetry()
                }
            }
        }
        return false
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
                glPreviewCompositorEngageInProgress ||
                glPreviewInputPrepareInProgress ||
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
    private fun beginPersistentPreviewModeHandoffIfPossible(): Boolean {
        if (!persistentPreviewPipelineActive) return false
        if (previewSurface == null) return false
        val previewSize = persistentPreviewInputSize ?: return false
        if (boundPreviewSize != previewSize) return false

        // Both full-quality camera inputs are already configured in the standing session. A mode
        // change therefore needs only a repeating-request retarget and a warm-frame output swap; it
        // must not re-run size selection, EGL input preparation, or the complete binding graph.
        val sdrInput = previewGlCompositor.inputSurface() ?: return false
        val hdrInput = hdr10PqPreviewCompositor.inputSurface() ?: return false
        val renderState = persistentPreviewRenderState()
        val desiredOwner = renderState.owner
        previewGlCompositor.setGrade(renderState.gradeCode)
        hdr10PqPreviewCompositor.setInputProfile(renderState.hdrProfile)
        hdr10PqPreviewCompositor.setMode(renderState.pqMode)
        hdr10PqPreviewCompositor.setPeakPlan(renderState.peakPlan)

        if (persistentPreviewLifecycle.owner == desiredOwner) {
            persistentPreviewOutputRouter.selectSource(desiredOwner.routerSource)
            camera.setPreviewSurface(
                if (desiredOwner == PersistentPreviewOwner.SDR) sdrInput else hdrInput
            )
            glPreviewCompositorActive = desiredOwner == PersistentPreviewOwner.SDR
            hdr10PqPreviewCompositorActive = desiredOwner == PersistentPreviewOwner.HDR
            return true
        }
        persistentPreviewLifecycle.beginHandoff(desiredOwner)
        return true
    }

    private fun syncGlPreviewCompositorBinding() {
        if (settingsCameraLifecycle.isSuspended()) return
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
            if (beginPersistentPreviewModeHandoffIfPossible()) return
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
        if (beginPersistentPreviewModeHandoffIfPossible()) return
        refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)
    }

    private fun applyPreviewPipelineBinding(
        surface: Surface,
        previewSize: Size,
        preferNativePreview: Boolean
    ): Boolean {
        var nativeAttemptStillEligible = preferNativePreview
        if (nativeAttemptStillEligible) {
            markStrictPreviewAdmissionPending()
            val handoffPendingForSurface =
                nativePreviewHandoffPending && nativePreviewHandoffSurface === surface
            val persistentOwnerMustRetire = persistentPreviewOwnerMustRetire(surface)
            val directSurfaceOwnedByCamera =
                !handoffPendingForSurface &&
                    !persistentOwnerMustRetire &&
                    cameraOwnsVisiblePreviewSurface(surface)
            camera.setPreviewSurface(null)
            camera.setHardwareImportTargetSize(previewSize)
            camera.setInternalRecordingHardwareImportEnabled(true)
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize(previewSize, nativePreview = true))
            pipeline.setDedicatedHardwareImportEnabled(true)
            vulkanRuntime.beginPreviewImportSession()
            vulkanRuntime.setFrameImportEnabled(true)
            if (persistentOwnerMustRetire && !nativePreviewCameraReleaseConfirmed) {
                beginNativePreviewHandoffFromPersistent(surface)
                return false
            }
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
            val nativePrecision = VulkanPreviewSurfaceStatusParser.parse(
                vulkanRuntime.capabilitySummary.value
            )
            if (nativeBound && nativePrecision.strictTenBitOrBetterProven) {
                clearNativePreviewHandoffState()
                directPreviewSurfaceActive = false
                nativeVisiblePreviewActive = true
                nativeVisiblePreviewConfirmed = false
                activateStrictPreviewAdmission(StrictPreviewAdmissionState.ACTIVE_NATIVE)
                startNativePreviewWatchdog(surface)
                return true
            }
            if (nativeBound) {
                // Native accepted the window but selected its documented RGBA8/unknown fallback.
                // Detach it before the same binding transaction attempts the strict RGB10_A2 router.
                vulkanRuntime.setPreviewSurface(null)
                clearNativePreviewHandoffState()
                nativeVisiblePreviewActive = false
                nativeVisiblePreviewConfirmed = false
                previewViewRef?.setNativeCompositorActive(false)
                markNativePreviewFallback(
                    surface = surface,
                    reason = "Native preview precision was not >=10-bit " +
                        "(format=${nativePrecision.format}); trying strict persistent output",
                    requiresSurfaceReset = true
                )
                nativeAttemptStillEligible = false
            }
            val failedAfterHandoff = handoffPendingForSurface
            if (!nativeBound) {
                if (failedAfterHandoff) {
                    clearNativePreviewHandoffState()
                }
                markNativePreviewFallback(
                    surface = surface,
                    reason = if (failedAfterHandoff) {
                        "Visible Vulkan preview bind failed after direct preview release"
                    } else {
                        "Visible Vulkan preview bind failed; trying strict persistent output"
                    },
                    requiresSurfaceReset = !failedAfterHandoff
                )
                nativeAttemptStillEligible = false
            }
        }

        stopNativePreviewWatchdog()
        clearNativePreviewHandoffState()
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        directPreviewSurfaceActive = false
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
        if (applyPersistentPreviewPipeline(surface, previewSize, nativeAttemptStillEligible)) {
            directPreviewSurfaceActive = false
            return false
        }
        if (StrictPreviewAdmissionPolicy.required(_uiMode.value)) {
            rejectStrictPreviewOutput(
                PersistentPreviewOutputRouter.StartResult.failure(
                    PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                    "No proven >=10-bit native or persistent preview route remained"
                )
            )
            return false
        }
        directPreviewSurfaceActive = true
        val requestedSdrGrade = resolveGlPreviewGrade()
        val sdrMonitorHandoffRequested = requestedSdrGrade != GlVisiblePreviewPolicy.PreviewGrade.IDENTITY
        val pqMonitorHandoffRequested = shouldCompositeHdr10PqPreview()
        val visibleMonitorHandoffRequested =
            sdrMonitorHandoffRequested || pqMonitorHandoffRequested
        val compositorOwnerSwap =
            (sdrMonitorHandoffRequested && hdr10PqPreviewCompositorActive) ||
                (pqMonitorHandoffRequested && glPreviewCompositorActive)
        if (compositorOwnerSwap && !cameraOwnsVisiblePreviewSurface(surface)) {
            // Camera2 already targets the outgoing compositor's private SurfaceTexture, not the
            // visible SurfaceView. Release that EGL owner and acquire the final owner directly; a
            // detach-to-direct intermediate session only creates freezes and dataspace churn.
            if (glPreviewCompositorActive) {
                previewGlCompositor.releaseAsync()
                glPreviewCompositorActive = false
            }
            if (hdr10PqPreviewCompositorActive) {
                releaseHdr10PqPreviewCompositorIfActive()
            }
            glPreviewSurfaceReleasePending = false
            glPreviewVisibleSurfaceReleaseConfirmed = true
            glPreviewHandoffFallbackJob?.cancel()
            glPreviewHandoffFallbackJob = null
        }
        val requestedMonitorAlreadyActive =
            if (pqMonitorHandoffRequested) hdr10PqPreviewCompositorActive else glPreviewCompositorActive
        val replacingVisibleCompositor =
            (sdrMonitorHandoffRequested && hdr10PqPreviewCompositorActive) ||
                (pqMonitorHandoffRequested && glPreviewCompositorActive)
        val glPreviewNeedsReleasedVisibleSurface = visibleMonitorHandoffRequested &&
            !requestedMonitorAlreadyActive &&
            (replacingVisibleCompositor || !glPreviewVisibleSurfaceReleaseConfirmed) &&
            cameraPreviewReadyForGlPreviewCompositor() &&
            !recordingStartOrActiveBlocksVisiblePreviewRebind()
        if (glPreviewNeedsReleasedVisibleSurface) {
            if (!glPreviewSurfaceReleasePending) {
                if (pqMonitorHandoffRequested) {
                    // Unlike the SDR monitor, the PQ compositor cannot create its SurfaceTexture input
                    // before it has acquired the visible EGL output. First hot-swap Camera2 to the
                    // still/analysis-only graph; the release callback starts EGL and performs the final
                    // input attach. Capture resolution/FPS/bit depth and the encoder graph are unchanged.
                    if (glPreviewCompositorActive) {
                        previewGlCompositor.releaseAsync()
                        glPreviewCompositorActive = false
                    }
                    glPreviewSurfaceReleasePending = true
                    glPreviewVisibleSurfaceReleaseConfirmed = false
                    camera.setPreviewSurface(null)
                    scheduleGlPreviewCompositorHandoffFallback(surface)
                } else {
                    val grade = requestedSdrGrade
                    val warmInput = previewGlCompositor
                        .takeIf { !shouldCompositeHdr10PqPreview() }
                        ?.inputSurface()
                    if (warmInput == null) {
                        if (!glPreviewInputPrepareInProgress) {
                            glPreviewInputPrepareInProgress = true
                            val prepareWidth = previewSize.width
                            val prepareHeight = previewSize.height
                            val prepareGrade = glPreviewGradeCode(grade)
                            viewModelScope.launch {
                                val prepared = withContext(Dispatchers.Default) {
                                    previewGlCompositor.prepareInput(
                                        prepareWidth,
                                        prepareHeight,
                                        prepareGrade
                                    )
                                }
                                glPreviewInputPrepareInProgress = false
                                if (prepared && previewSurface === surface) {
                                    bindPreviewSurface(surface)
                                } else if (!prepared) {
                                    scheduleGlPreviewEngageRetry()
                                }
                            }
                        }
                        camera.setPreviewSurface(surface)
                        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize(previewSize, nativePreview = false))
                        return false
                    }
                    glPreviewSurfaceReleasePending = true
                    glPreviewVisibleSurfaceReleaseConfirmed = false
                    camera.setPreviewSurface(warmInput)
                    scheduleGlPreviewCompositorHandoffFallback(surface)
                }
            }
            camera.setAnalysisTargetSize(preferredPreviewAnalysisSize(previewSize, nativePreview = false))
            return false
        }
        if (glPreviewSurfaceReleasePending && previewSurface === surface && !glPreviewCompositorActive) {
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
            if (!glPreviewCompositorEngageInProgress && !hdr10PqPreviewEngageInProgress) {
                camera.setPreviewSurface(surface)
            }
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
        if (!startupPreferencesApplied) {
            previewSurface = surface
            updatePreviewDisplayPresentSurface(surface)
            Log.i(
                "CameraViewModel",
                "Deferring first preview preparation until persisted mode/aspect authority is applied"
            )
            return
        }
        if (recordingPreviewWindow.retained && isRecordingOrStopping()) {
            recordingPreviewWindow.restore(surface)
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
        updatePreviewDisplayPresentSurface(surface)
        // Publish the selected stream geometry to SurfaceHolder before any native or persistent
        // EGL owner queries the window. On a cold mode/aspect restore, the Surface can still expose
        // the previous/default buffer size (for example 1920x1440 while the selected transport is
        // 1280x960). Binding first turns that harmless BLAST transaction lag into a sticky strict
        // preview failure and leaves recording admission closed indefinitely. setFixedSize is
        // asynchronous, so PersistentPreviewOutputRouter's bounded geometry-settle loop remains the
        // second half of this handoff; this ordering gives it the correct target transaction to wait
        // for without accepting a stretched or mismatched surface.
        previewViewRef?.setStreamGeometry(
            previewSize.width,
            previewSize.height,
            camera.sensorOrientation()
        )
        val activeNativePreview = applyPreviewPipelineBinding(
            surface = surface,
            previewSize = previewSize,
            preferNativePreview =
                preferNativePreviewOverride ?: shouldAttemptNativeVulkanPreview(surface)
        )
        val glPreviewReleasePendingForSurface =
            glPreviewSurfaceReleasePending && previewSurface === surface
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
                            reason = "visible-native-preview-handoff",
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
                            reason = "visible-gl-preview-handoff",
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
        if (settingsCameraLifecycle.isSuspended()) return
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
        val desiredOwner = desiredPreviewPipelineOwner(desiredNativePreview)
        val currentOwner = currentPreviewPipelineOwner()
        val pipelineChanged = desiredOwner != currentOwner
        val handoffInProgress =
            (nativePreviewHandoffPending && nativePreviewHandoffSurface === surface) ||
                (glPreviewSurfaceReleasePending && previewSurface === surface) ||
                persistentPreviewPreparation.inProgress ||
                persistentPreviewLifecycle.targetOwner != null ||
                hdr10PqPreviewEngageInProgress ||
                glPreviewCompositorEngageInProgress ||
                glPreviewInputPrepareInProgress

        if (!PreviewPipelineRebindPolicy.shouldRebind(sizeChanged, pipelineChanged, handoffInProgress)) {
            if (!forceSessionRefresh || deferSessionRefresh) return
            camera.refreshOutputsAndSession()
            return
        }

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
            if (appliedGlPreviewReleasePending) {
                scheduleGlPreviewCompositorHandoffFallback(surface)
                camera.refreshOutputsAndSession(
                    forceRebuild = true,
                    reason = "deferred-gl-preview-release",
                    onPreviousSessionClosed = { runOnMainIfNeeded {
                        completeGlPreviewCompositorHandoffAfterCameraRelease(surface)
                    } }
                )
            }
            return
        }
        when (cameraState.value) {
            CameraState.Open,
            CameraState.Streaming,
            CameraState.Recording -> {
                if (appliedHandoffPending) {
                    camera.refreshOutputsAndSession(
                        forceRebuild = true,
                        reason = "preview-pipeline-native-handoff",
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
                        reason = "preview-pipeline-gl-handoff",
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

    private fun evaluatePreviewPerformanceStability(measuredFps: Float) {
        val recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused ||
            gpuRecordState.value is GpuRecordingState.Stopping
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
            refreshPreviewPipelineForAdaptiveLimitChange()
        }
    }

    /**
     * Adaptive preview limits may change while the already-bound transport still satisfies them.
     * Keep this refresh idempotent so a thermal/performance update cannot force a Surface/Camera2
     * generation change unless preferredPreviewSize() or the pipeline owner actually changed.
     */
    private fun refreshPreviewPipelineForAdaptiveLimitChange() {
        refreshPreviewPipelineIfNeeded(forceSessionRefresh = false)
    }

    private fun clearPreviewSurface(): PreviewSurfaceRetirementTicket {
        val retirementTicket = previewSurfaceRetirement.begin()
        markStrictPreviewAdmissionPending()
        stopNativePreviewWatchdog()
        stopNativePreviewRetry()
        clearNativePreviewHandoffState()
        glPreviewBindingResyncJob?.cancel()
        glPreviewBindingResyncJob = null
        resetPreviewPerformanceState()
        previewSurface = null
        updatePreviewDisplayPresentSurface(null)
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
        if (persistentPreviewPipelineActive ||
            persistentPreviewLifecycle.owner != null ||
            persistentPreviewPreparation.inProgress
        ) {
            // Phase 1 only: remove these targets from the next Camera2 graph while retaining their
            // BufferQueues. CameraDevice.close() acknowledgement is the ownership boundary that makes
            // destroying the EGL producers safe; closeCameraInternal performs phase 2 afterwards.
            disablePersistentPreviewPipeline(releaseInputs = false)
        }
        camera.setPreviewSurface(null)
        return retirementTicket
    }

    private fun completePreviewSurfaceRetirement(ticket: PreviewSurfaceRetirementTicket) {
        CameraPreviewLifecycleDispatcher.bindReleasedReplacement(
            previewSurfaceRetirement, ticket, isValid = { it.isValid }, bind = ::onSurfaceReady
        )
    }

    private fun clearPreviewSurfaceForActiveRecording() {
        if (recordingPreviewWindow.retain()) return
        if (recordingPreviewDetachInFlight) return
        markStrictPreviewAdmissionPending()
        val detachGeneration = recordingPreviewDetachGeneration + 1L
        recordingPreviewDetachGeneration = detachGeneration
        recordingPreviewDetachInFlight = true
        recordingPreviewSurfaceSafelyDetached = false
        pendingRecordingPreviewSurface = null
        val persistentPreviewWasAttached =
            persistentPreviewPipelineActive ||
                persistentPreviewLifecycle.owner != null ||
                persistentPreviewPreparation.inProgress
        stopNativePreviewWatchdog()
        stopNativePreviewRetry()
        clearNativePreviewHandoffState()
        glPreviewBindingResyncJob?.cancel()
        glPreviewBindingResyncJob = null
        previewSurface = null
        updatePreviewDisplayPresentSurface(null)
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
        if (persistentPreviewWasAttached) {
            // Unregister both standing preview outputs before Camera2 builds the recording-only
            // session. Their EGL consumers stay alive until the previous session is confirmed closed.
            disablePersistentPreviewPipeline(releaseInputs = false)
        }
        camera.setPreviewSurface(null)
        val completeDetach = {
            runOnMainIfNeeded {
                if (recordingPreviewDetachGeneration != detachGeneration) {
                    return@runOnMainIfNeeded
                }
                if (persistentPreviewWasAttached) {
                    disablePersistentPreviewPipeline(releaseInputs = true)
                } else {
                    if (glPreviewCompositorActive) {
                        previewGlCompositor.releaseAsync()
                        glPreviewCompositorActive = false
                    }
                    releaseHdr10PqPreviewCompositorIfActive()
                }
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
                reason = "preview-compositor-detach",
                onPreviousSessionClosed = completeDetach
            )
            else -> completeDetach()
        }
    }

    private fun preferredPreviewSize(): Size = modeSwitchPreviewSizingCache.previewSize {
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
        // Do not couple the Camera2/EGL transport allocation to a display-only framing choice.
        // A sensor-native stream retains the widest source coverage; the presentation layer performs
        // an aspect-preserving center crop for Auto/1:1/4:3/16:9 without a Surface teardown.
        val targetAspect = currentPreviewTransportAspect()
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
                    return@previewSize sourceMatch
                }
            }
        }
        val cappedCandidates = sizes.filter {
            maxOf(it.width, it.height) <= appliedMaxLongEdge && (it.width * it.height) <= appliedMaxArea
        }
        val candidates = if (cappedCandidates.isNotEmpty()) cappedCandidates else sizes
        chooseSizeForAspect(candidates, targetAspect)
            ?: candidates.maxByOrNull { it.width * it.height }
            ?: CameraPreviewSessionSizingPolicy.defaultStreamPreviewFallback()
    }

    private fun currentPreviewTransportAspect(): Float = PreviewTransportAspectPolicy.resolve(
            sensorAspect = camera.sensorBaseAspectRatio(),
            currentStreamAspect = currentPreviewStreamAspectRatio
        )

    private fun mobileDisplayPreviewBudget(): Size = ModeSwitchPreviewSizingCache.mobileDisplayBudget(getApplication<Application>().resources.displayMetrics)

    private fun preferredPreviewAnalysisSize(
        previewSize: Size = preferredPreviewSize(),
        nativePreview: Boolean = shouldUseNativeVulkanPreview()
    ): Size = modeSwitchPreviewSizingCache.analysisSize(previewSize, nativePreview) {
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
        chooseSizeForAspect(candidates, targetAspect)
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
        val effectiveBitrate = if (!resolvedSelection.requiresMediaEncoder) bitrate else gpuRecording.resolveSupportedBitrate(
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
        updateModeSwitchStandbyRequestedSize(requested)
        // Persistence belongs exclusively to the direct settings action in setVideoResolution().
        // Startup, pre-arm and record-start re-resolution may select a temporary hardware output,
        // but must never rewrite the user's requested recording resolution.
        lastRecordingSelection = resolvedSelection
        recording.width = output.width
        recording.height = output.height
        recording.fps = effectiveFps
        syncTaskAwareLensSelectionHint()
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
                "selectionBackend=${resolvedSelection.backend} " +
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
        if (modeSwitchCriticalDepth.get() > 0 && !modeSwitchFlushInProgress) {
            modeSwitchAutomaticBitratePending = true
            modeSwitchAutomaticBitrateForcePending = modeSwitchAutomaticBitrateForcePending || force
            return
        }
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
        return RecordingSceneMetadataFormatter.format(
            RecordingSceneMetadataFormatter.Input(
                encoderSize = encoderSize,
                selection = selection,
                modeLabel = CameraRecordingModeMetadataPolicy.resolveModeLabel(
                    cinemaMode = effectiveRecordingCinemaMode(parityPlan),
                    rawEnabled = parityPlan.encoderRawEnabled,
                    logEnabled = parityPlan.encoderLogEnabled,
                    hdrMode = parityPlan.encoderHdrMode,
                    manualControlsVisible = _manualControlsVisible.value
                ),
                cameraLevel = camera.hardwareLevelName,
                highEndHardwareSupported = camera.highEndHardwareSupported,
                sourceResolutionPlan = sourceResolutionPlan,
                rawCompression = _rawVideoCompression.value.name,
                colorProfile = _colorProfile.value,
                manualColorPlan = manualColorPlan,
                parityPlan = parityPlan,
                processingReport = processingReport,
                runtimeEvidence = buildRecordingRuntimeEvidenceMetadata(parityPlan, processingReport),
                debugValidationLabel = debugValidationLabel,
                logEnabled = _logOn.value,
                simulatedTenBitFallbackActive = gpuRecording.simulatedTenBitFallbackActive,
                faceDetectionEnabled = prefs.faceDetectionEnabled,
                thermalLabel = currentThermalLabel(),
                thermalThrottled = _uiStateFlow.value.thermalThrottled,
                dropEstimate = _cinemaRuntimeStats.value.dropEstimate,
                motionRisk = v20MotionRisk,
                noiseRisk = v20NoiseRisk,
                noiseMetrics = noiseMetrics,
                noiseReductionSignature = noiseReductionSignature,
                detailMetadata = detailMetadata,
                scene = scene,
                recommendation = recommendation
            )
        )
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
        return RecordingRuntimeEvidenceFormatter.format(
            RecordingRuntimeEvidenceFormatter.Input(
                parityPlan = parityPlan,
                processingReport = processingReport,
                stats = stats,
                validatedEvidence = validatedEvidence,
                enhancementUniforms = lastGpuEnhancementUniforms,
                p010SourceTelemetry = pipeline.rawYuvImageSinkTelemetry(),
                liveEncoderSurfaceStatus = vulkanRuntime.currentEncoderSurfaceStatus,
                validatedEncoderSurfaceCurrent = hasCurrentRecordingEncoderSurfaceEvidence(),
                activeRecordingFeedPath = activeRecordingFeedPath,
                capabilitySummary = vulkanRuntime.capabilitySummary.value,
                memoryStats = latestMemorySnapshot ?: memoryProfiler.captureSnapshot(),
                thermalLabel = currentThermalLabel(),
                manualColorPlan = currentManualVideoColorSelectionPlan(),
                debayerStats = vulkanRuntime.cinemaDebayer.getStats(),
                realtimeDispatch = pipeline.realtimeDispatchTelemetry(),
                realtimeDispatchStrictNoConflation = pipeline.realtimeDispatchStrictNoConflation(),
                averageComputeMs = vulkanRuntime.currentMeasuredAverageComputeDurationMs,
                aiFaces = _aiFaces.value,
                aiDepth = _aiDepthData.value,
                aiSubject = _aiPrimarySubject.value,
                aiSubjectMask = _aiSubjectMask.value,
                aiObserved = hasObservedAiFrameProcessing(),
                recordDevelopLightMaster = RecordDevelopLivePipelinePolicy.active(
                    VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)
                ),
                recordDevelopModeEnabled = RecordDevelopModeFlag.enabled,
                stopOriginEvidence = recordingStopOriginTracker.snapshot(),
                dualGainFusionEnabled = currentSceneRenderProfile?.dualGainFusionEnabled == true,
                dualGainMotionMaskEnabled = currentSceneRenderProfile?.dualGainMotionMaskEnabled == true
            )
        ).apply {
            putAll(SensorPipelineIntegration.runtimeEvidence(pipeline))
            putAll(camera2FrameLineageRuntimeMetadata())
            putAll(camera.recordFrameDeliverySnapshot().toCompactMetadataMap())
            putAll(camera.singularityCaptureStage2Metadata())
            if (processingReport.camera2DirectBypassesCustomProcessing &&
                validatedEvidence.importedFrames <= 0L
            ) {
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
            isSensorLinearNdrMode() -> resolveSensorLinearNdrRawSize(selection.requestedSize)
            _cinemaVideoMode.value == CinemaVideoMode.RAW -> {
                // Reuse the exact tier selected when RAW mode prearmed its reader. Re-resolving here
                // can change reader geometry between mode entry and record start, forcing a second
                // Camera2 session and a visible preview pause.
                val tier = resolveRawCaptureTier(forceRefresh = cachedRawCaptureTier == null)
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

    /**
     * Select the smallest native RAW_SENSOR stream that fully covers the encoded raster. This avoids
     * upscaling while also avoiding needless maximum-resolution RAW bandwidth. No eligible size means
     * the strict sensor-linear NDR path is unsupported at the requested output resolution.
     */
    private fun resolveSensorLinearNdrRawSize(encoderSize: Size): Size? {
        val requested = encoderSize.normalizeForOrientation()
        return camera.rawSizes
            .asSequence()
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { "${it.width}x${it.height}" }
            .map { raw -> raw to raw.normalizeForOrientation() }
            .filter { (_, normalized) ->
                normalized.width >= requested.width && normalized.height >= requested.height
            }
            .minWithOrNull(
                compareBy<Pair<Size, Size>> {
                    it.second.width.toLong() * it.second.height.toLong()
                }.thenBy {
                    val rawAspect = it.second.width.toFloat() / it.second.height.coerceAtLeast(1)
                    val requestedAspect = requested.width.toFloat() / requested.height.coerceAtLeast(1)
                    abs(rawAspect - requestedAspect)
                }
            )
            ?.first
    }

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
        // Resource probes are warmed and refreshed on Dispatchers.IO. RAW mode entry only consumes
        // the latest immutable values, so selecting the maximum-quality sensor tier never blocks a
        // preview frame on Debug.getPss(), ActivityManager, or StatFs.
        requestRuntimeResourceTelemetryRefresh()
        val availableMemBytes = latestMemorySnapshot
            ?.oomReserveFreeMb
            ?.toLong()
            ?.times(1024L * 1024L)
            ?: 0L
        val viability = RawMaxResViabilityPolicy.resolve(
            estPerFrameBytes = estPerFrameBytes,
            freeStorageBytes = latestRecordingStorageBytes,
            availableMemBytes = availableMemBytes,
            thermal = RawMaxResViabilityPolicy.thermalLevelFromLabel(latestRawThermalLabel)
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

    private fun alignEven(value: Int): Int = (value and 1.inv()).coerceAtLeast(2)

    private fun megapixelLabel(megapixels: Float): String = CinemaDisplayUtils.megapixelLabel(megapixels)

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
        // The terminal certificate is written while Finalizing, after Camera2 may restore preview.
        // Preserve the same evidence/protection window until that phase has actually completed.
        val gpuRecordingCritical = RuntimePerformanceLoadPolicy.isRecordingCritical(gpuRecordState.value)
        val load = RuntimePerformanceLoadPolicy.resolve(
            recordingGraphPrearmed = recordingGraphPrearmed,
            recordingStarting = recordingStopRestartLifecycle.isStarting,
            gpuRecordingCritical = gpuRecordingCritical,
            cameraRecording = cameraState.value == CameraState.Recording,
            highLoadPreview = isHighLoadPreviewMode()
        )
        // X3/R3: when not recording / not high-load, bound the analysis cadence hint to the 25fps
        // preview cadence so idle analysis stays within the 40ms preview budget. minOf guarantees
        // this never RAISES a pre-existing lower cap (e.g. a thermal analysis cap).
        val cadenceBoundedPreviewFps =
            if (load.highLoadOrRecording) previewFps else minOf(previewFps, 25f)
        val fpsHint = runtimePerformanceFpsHint(cadenceBoundedPreviewFps)
        aiCameraPipeline.updateRuntimePerformance(previewFps = fpsHint, recording = load.recordingCritical)
        // Capture/deadline accounting follows the requested sensor cadence. A thermal or load-based
        // AI hint must never lower this value and conceal real capture/encoder deadline misses.
        pipeline.setSensorTargetFps(recording.fps.toFloat().coerceAtLeast(1f))
        pipeline.setRuntimePerformanceHint(previewFps = fpsHint, recording = load.recordingCritical)
        LUTProcessor.setRuntimeQualityHint(previewFps = fpsHint, recording = load.recordingCritical)
        previewGlCompositor.setRecordingPriorityActive(load.recordingCritical)
    }

    private fun applyRecordingCriticalRuntimeHints(
        recordingActive: Boolean,
        previewFps: Float = lastPreviewFpsSample.takeIf { it > 0f }
            ?: HighEndQualityPolicy.targetPreviewFps.toFloat()
    ) {
        val thermalDecision = if (recordingActive) {
            val powerManager =
                getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager
            thermalPolicyResolver.resolve(
                thermalLabel = currentThermalLabel(),
                recordingActive = true,
                powerManager = powerManager,
                strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
            )
        } else {
            null
        }
        applyRecordingThermalIsolation(
            recordingActive = recordingActive,
            thermalSeverity = thermalDecision?.thermalSeverity ?: 0
        )
        val fpsHint = runtimePerformanceFpsHint(previewFps.coerceAtLeast(1f))
        aiCameraPipeline.updateRuntimePerformance(
            previewFps = fpsHint,
            recording = recordingActive
        )
        pipeline.setSensorTargetFps(recording.fps.toFloat().coerceAtLeast(1f))
        pipeline.setRuntimePerformanceHint(previewFps = fpsHint, recording = recordingActive)
        LUTProcessor.setRuntimeQualityHint(previewFps = fpsHint, recording = recordingActive)
        previewGlCompositor.setRecordingPriorityActive(recordingActive)
    }

    private fun isHighLoadPreviewMode(): Boolean {
        return sensorPipelineSimplifyPreviewEffects ||
            currentPreviewPerformanceLimits().highLoadPreviewMode
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
            recordingStopRestartLifecycle.isStarting ||
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
            cinemaAutoLockActive = cinemaAutoLockActive,
            thermalIsolationActive = recordingThermalIsolationActive
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
        if (desiredState.requiresModelWarmup()) scheduleAiWarmUp()
        return true
    }

    private data class CurrentRepeatingHardwareImportSourceProof(
        val repeating: CameraRepeatingReadinessSnapshot,
        val method: HardwareImportSourceProofMethod,
        val timestampDomainsCompatible: Boolean
    )

    private data class CurrentRepeatingHardwareImportSourceProofEvaluation(
        val firstFrameBoundary: HardwareImportRepeatingFirstFrameBoundary?,
        val proof: CurrentRepeatingHardwareImportSourceProof?
    )

    /**
     * Accepts source ingress only when it is ordered after the first capture result from the exact
     * currently-published repeating request. On conforming devices, sensor timestamps provide the
     * order directly. If Camera2 and ImageReader expose different sensor clocks, the fallback is a
     * source-counter boundary captured only after that exact request's first callback is observed.
     */
    private fun currentRepeatingHardwareImportSourceProof(
        ingress: HardwareImportSourceIngressTelemetry,
        baselineSourceImageCount: Long,
        expectedSessionGeneration: Long,
        existingFirstFrameBoundary: HardwareImportRepeatingFirstFrameBoundary?
    ): CurrentRepeatingHardwareImportSourceProofEvaluation {
        val repeating = camera.currentRepeatingReadinessSnapshot()
        val evaluation = HardwareImportSourceProofPolicy.evaluate(
            ingress = ingress,
            baselineSourceImageCount = baselineSourceImageCount,
            expectedSessionGeneration = expectedSessionGeneration,
            repeating = repeating,
            existingFirstFrameBoundary = existingFirstFrameBoundary
        )
        val proofMethod = evaluation.proofMethod
            ?: return CurrentRepeatingHardwareImportSourceProofEvaluation(
                firstFrameBoundary = evaluation.firstFrameBoundary,
                proof = null
            )

        // Re-read after evaluating ingress. A concurrent repeating-request replacement must not
        // inherit proof from the sequence that was current at the beginning of this check.
        val confirmedRepeating = camera.currentRepeatingReadinessSnapshot()
        if (confirmedRepeating != repeating || repeating == null) {
            return CurrentRepeatingHardwareImportSourceProofEvaluation(null, null)
        }
        return CurrentRepeatingHardwareImportSourceProofEvaluation(
            firstFrameBoundary = evaluation.firstFrameBoundary,
            proof = CurrentRepeatingHardwareImportSourceProof(
                repeating = repeating,
                method = proofMethod,
                timestampDomainsCompatible =
                    evaluation.timestampDomainsCompatible == true
            )
        )
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
        camera.refreshOutputsAndSession(
            rebuildReaders = rebuildReaders,
            forceRebuild = forceRebuild,
            reason = if (forceRebuild) "preview-pipeline-force" else "preview-pipeline-refresh"
        ) {
            pendingGlPreviewReleaseSurface?.let { surface ->
                runOnMainIfNeeded {
                    completeGlPreviewCompositorHandoffAfterCameraRelease(surface)
                }
            }
            refreshed.complete(Unit)
        }
        return withTimeoutOrNull(timeoutMs) {
            refreshed.await()
            if (waitForHardwareImportActivity) {
                // Frames from the outgoing hot-swap session can arrive while the replacement is
                // still configuring. Treating one of those frames as recording-ready starts the
                // clip clock before the inevitable handoff gap and falsely reports dropped frames.
                // Snapshot source ingress at the configured callback and require a later ImageReader
                // acquisition. This evidence is intentionally upstream of the closed encoder gate;
                // Vulkan import counters cannot advance while that quarantine is doing its job.
                val configuredSessionGeneration = camera.sessionGeneration.get()
                val sourceIngressAtSessionConfigured =
                    pipeline.hardwareImportSourceIngressTelemetry()
                val maxFrameWaitMs =
                    ProcessedGpuRecordingRequirementPolicy.HARDWARE_IMPORT_ACTIVITY_WAIT_MS
                val activityWaitStartedElapsedMs = SystemClock.elapsedRealtime()
                var observedRepeatingPublicationGeneration = Long.MIN_VALUE
                var repeatingFirstFrameBoundary:
                    HardwareImportRepeatingFirstFrameBoundary? = null
                var repeatingProofDeadlineElapsedMs =
                    activityWaitStartedElapsedMs + maxFrameWaitMs
                while (true) {
                    if (camera.sessionGeneration.get() != configuredSessionGeneration) {
                        Log.e(
                            "CameraViewModel",
                            "awaitFreshPreviewSession: Camera2 generation changed while awaiting " +
                                "hardware-import source ingress expected=$configuredSessionGeneration " +
                                "actual=${camera.sessionGeneration.get()}"
                        )
                        return@withTimeoutOrNull false
                    }
                    val currentRepeating = camera.currentRepeatingReadinessSnapshot()
                    if (currentRepeating?.sessionGeneration == configuredSessionGeneration &&
                        currentRepeating.publicationGeneration !=
                        observedRepeatingPublicationGeneration
                    ) {
                        val previousPublication = observedRepeatingPublicationGeneration
                        observedRepeatingPublicationGeneration =
                            currentRepeating.publicationGeneration
                        repeatingFirstFrameBoundary = null
                        // Camera controls such as the deliberate post-rebuild AWB lock can replace
                        // a correctly-targeted repeating request near the original one-second
                        // boundary. Give that exact new sequence its own bounded proof window. The
                        // surrounding withTimeoutOrNull remains the authoritative overall startup
                        // deadline, so continuous request churn still fails closed.
                        repeatingProofDeadlineElapsedMs =
                            SystemClock.elapsedRealtime() + maxFrameWaitMs
                        if (previousPublication != Long.MIN_VALUE) {
                            Log.i(
                                "CameraViewModel",
                                "awaitFreshPreviewSession: repeating request replaced while " +
                                    "awaiting source proof previousPublication=$previousPublication " +
                                    "currentPublication=${currentRepeating.publicationGeneration} " +
                                    "sequence=${currentRepeating.sequenceId}; proof window restarted"
                            )
                        }
                    }
                    val ingress = pipeline.hardwareImportSourceIngressTelemetry()
                    val sourceProofEvaluation = currentRepeatingHardwareImportSourceProof(
                        ingress = ingress,
                        baselineSourceImageCount =
                            sourceIngressAtSessionConfigured.sourceImageCount,
                        expectedSessionGeneration = configuredSessionGeneration,
                        existingFirstFrameBoundary = repeatingFirstFrameBoundary
                    )
                    repeatingFirstFrameBoundary = sourceProofEvaluation.firstFrameBoundary
                    val repeatingProof = sourceProofEvaluation.proof
                    if (repeatingProof != null) {
                        Log.i(
                            "CameraViewModel",
                            "awaitFreshPreviewSession: post-refresh hardware-import source frame proven " +
                                "proofMethod=${repeatingProof.method} " +
                                "timestampDomainsCompatible=" +
                                "${repeatingProof.timestampDomainsCompatible} " +
                                "sourceFrames=${ingress.sourceImageCount} " +
                                "baseline=${sourceIngressAtSessionConfigured.sourceImageCount} " +
                                "sessionGeneration=${ingress.sessionGeneration} " +
                                "repeatingPublication=" +
                                "${repeatingProof.repeating.publicationGeneration} " +
                                "repeatingSequence=${repeatingProof.repeating.sequenceId} " +
                                "firstRepeatingSensorTimestampNs=" +
                                "${repeatingProof.repeating.firstFrameSensorTimestampNs} " +
                                "sensorTimestampNs=${ingress.sensorTimestampNs} " +
                                "format=${ingress.imageFormat} ${ingress.width}x${ingress.height}"
                        )
                        return@withTimeoutOrNull true
                    }
                    if (SystemClock.elapsedRealtime() >= repeatingProofDeadlineElapsedMs) {
                        // The capture-result callback and ImageReader callback run on different
                        // threads. Either can publish between the proof read above and this
                        // deadline check. Re-read both atomically enough for the existing exact
                        // repeating-sequence revalidation before declaring a timeout.
                        val finalIngress = pipeline.hardwareImportSourceIngressTelemetry()
                        val finalSourceProofEvaluation = currentRepeatingHardwareImportSourceProof(
                            ingress = finalIngress,
                            baselineSourceImageCount =
                                sourceIngressAtSessionConfigured.sourceImageCount,
                            expectedSessionGeneration = configuredSessionGeneration,
                            existingFirstFrameBoundary = repeatingFirstFrameBoundary
                        )
                        repeatingFirstFrameBoundary =
                            finalSourceProofEvaluation.firstFrameBoundary
                        val finalRepeatingProof = finalSourceProofEvaluation.proof
                        if (finalRepeatingProof != null) {
                            Log.i(
                                "CameraViewModel",
                                "awaitFreshPreviewSession: post-refresh hardware-import source " +
                                    "frame proven at proof-window boundary " +
                                    "proofMethod=${finalRepeatingProof.method} " +
                                    "timestampDomainsCompatible=" +
                                    "${finalRepeatingProof.timestampDomainsCompatible} " +
                                    "sourceFrames=${finalIngress.sourceImageCount} " +
                                    "baseline=${sourceIngressAtSessionConfigured.sourceImageCount} " +
                                    "sessionGeneration=${finalIngress.sessionGeneration} " +
                                    "repeatingPublication=" +
                                    "${finalRepeatingProof.repeating.publicationGeneration} " +
                                    "repeatingSequence=" +
                                    "${finalRepeatingProof.repeating.sequenceId} " +
                                    "firstRepeatingSensorTimestampNs=" +
                                    "${finalRepeatingProof.repeating.firstFrameSensorTimestampNs} " +
                                    "sensorTimestampNs=${finalIngress.sensorTimestampNs}"
                            )
                            return@withTimeoutOrNull true
                        }
                        val finalRepeating = camera.currentRepeatingReadinessSnapshot()
                        if (finalRepeating?.sessionGeneration == configuredSessionGeneration &&
                            finalRepeating.publicationGeneration !=
                            observedRepeatingPublicationGeneration
                        ) {
                            // A replacement won the race with the timeout check. Let the next loop
                            // iteration observe it and install its own bounded proof deadline.
                            continue
                        }
                        Log.e(
                            "CameraViewModel",
                            "awaitFreshPreviewSession: hidden hardware-import stream produced " +
                                "no post-refresh source frames after ${maxFrameWaitMs}ms " +
                                "totalWaitMs=${SystemClock.elapsedRealtime() - activityWaitStartedElapsedMs} " +
                                "baseline=${sourceIngressAtSessionConfigured.sourceImageCount} " +
                                "currentIngress=$finalIngress " +
                                "expectedGeneration=$configuredSessionGeneration " +
                                "currentRepeating=$finalRepeating"
                        )
                        return@withTimeoutOrNull false
                    }
                    delay(25L)
                }
            }
            var waitedMs = 0L
            val maxFrameWaitMs = 4000L
            while (!camera.hasRecentPreviewActivity()) {
                if (waitedMs >= maxFrameWaitMs) {
                    Log.e("CameraViewModel", "awaitFreshPreviewSession: session configured but " +
                        "no frames received after ${maxFrameWaitMs}ms - recording admission rejected")
                    return@withTimeoutOrNull false
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
        val terminalFailureMessage = failedRecordingStartRecovery.terminalize(message, reportError)
        val sensorLinearNdrOwnershipWasActive =
            vulkanRuntime.isSensorLinearNdrRecordingDemosaicActive()
        var sensorLinearPreviewWorkerQuiesced = !sensorLinearNdrOwnershipWasActive
        var sensorLinearDemosaicReturnedToPreview = !sensorLinearNdrOwnershipWasActive
        recordingReadyForClipTiming = false
        camera.setProcessedRecordingSessionPreparationActive(false)
        strictProcessedGpuValidationJob?.cancel()
        strictProcessedGpuValidationJob = null
        failedRecordingStartRecovery.runSuspendingStep(
            "stopSensorLinearNdrRecordingIngress",
            "NDR recording ingress cleanup failed during startup recovery."
        ) {
            val ingressStopResult =
                sensorLinearNdrRecordingLifecycle.stopIngress(drain = true)
            if (!ingressStopResult.teardownSafe) {
                Log.e("CameraViewModel", "NDR ingress failed to drain during recording-start cleanup")
            }
            if (sensorLinearNdrOwnershipWasActive && ingressStopResult.ingressFailed) {
                sensorLinearNdrRecordingFailure.compareAndSet(
                    null,
                    SensorLinearNdrRecordingFailure(
                        reason = "Strict NDR clip-quality ingress failure was reported during recording-start recovery"
                    )
                )
            }
        }
        if (sensorLinearNdrOwnershipWasActive) {
            failedRecordingStartRecovery.runSuspendingStep(
                "drainSensorLinearNdrRecordingPreview",
                "NDR preview ownership failed to drain during startup recovery."
            ) {
                val previewBoundary =
                    drainSensorLinearNdrRecordingPreviewBoundary("startup-failure")
                sensorLinearPreviewWorkerQuiesced = previewBoundary.quiesced
            }
        }
        // Both full-resolution ingress and the independent monitor-token domain are now terminal.
        // Freeze before encoder/native teardown so no accepted preview lineage is discarded.
        freezeCamera2FrameLineageSession()
        failedRecordingStartRecovery.runSuspendingStep(
            "preview.detachRecordingEncoder",
            "Encoder preview detachment failed during recording recovery."
        ) {
            updatePreviewViewAwait {
                setEncoderFeedEnabled(false)
                setEncoderSurface(null)
            }
        }
        if (stopCameraVideo) {
            when (val cameraStop = stopCameraVideoCaptureSafely()) {
                RecordingOperationOutcome.Success -> Unit
                is RecordingOperationOutcome.Failure -> handleCameraError(
                    throwable = cameraStop.cause ?: Exception(cameraStop.reason),
                    operation = cameraStop.operation,
                    userVisibleMessage = "Camera cleanup failed while recovering recording startup."
                )
            }
        }
        when (val cameraDetach = detachRecordingSurfaceBeforeEncoderStop(
            allowPersistentPrearmRetention = false
        )) {
            RecordingOperationOutcome.Success -> Unit
            is RecordingOperationOutcome.Failure -> {
                handleCameraError(
                    throwable = cameraDetach.cause ?: Exception(cameraDetach.reason),
                    operation = cameraDetach.operation,
                    userVisibleMessage =
                        "Camera encoder surface could not be retired during recording recovery."
                )
                Log.e(
                    "CameraViewModel",
                    "Failed-start cleanup quarantined before codec release because Camera2 detach is unproven"
                )
                return
            }
        }
        vulkanRuntime.setFrameImportEnabled(false)
        if (!detachAllGpuEncoderSurfacesWithProof("failed-start-cleanup")) {
            // This is an ownership boundary, not a recoverable cosmetic failure. Releasing the
            // recorder, persistent Surface, or RAW demosaic while either GPU bridge may still
            // reference the codec Surface can cause a native use-after-free. Keep the exact owners.
            Log.e(
                "CameraViewModel",
                "Failed-start cleanup quarantined before codec release because GPU detach is unproven"
            )
            return
        }
        failedRecordingStartRecovery.runStep(
            "releaseSensorLinearNdrRecordingDemosaicOwnership",
            "NDR demosaic cleanup failed during recording recovery."
        ) {
            sensorLinearDemosaicReturnedToPreview =
                sensorLinearNdrRecordingLifecycle.releaseDemosaicOwnership()
            if (!sensorLinearDemosaicReturnedToPreview) {
                Log.e("CameraViewModel", "NDR demosaic ownership remained pending after start cleanup")
            }
        }
        if (sensorLinearNdrOwnershipWasActive &&
            sensorLinearPreviewWorkerQuiesced &&
            sensorLinearDemosaicReturnedToPreview &&
            !viewModelTeardownStarted.get()
        ) {
            if (!vulkanRuntime.resumeSensorLinearNdrPreviewAfterDrain()) {
                Log.i(
                    "CameraViewModel",
                    "NDR preview worker will resume when startup recovery commits its preview surface"
                )
            }
        }
        pipeline.setDedicatedHardwareImportEnabled(false)
        pipeline.setRawYuvImageSink(null)
        pipeline.setRawYuvFrameSink(null)
        camera.setInternalRecordingHardwareImportEnabled(false)
        camera.setHardwareImportP010SourceEnabled(false)
        camera.setPreviewContinuityRecordSurfaceEnabled(false)
        camera.setHardwareImportTargetSize(null)
        camera.setNpv10TenBitCaptureEnabled(false)
        clearRecordingReadoutOverride()
        activeRecordingFeedPath = null
        resetPreviewPerformanceState()
        camera.setAnalysisTargetSize(preferredPreviewAnalysisSize())
        failedRecordingStartRecovery.releaseRecorder(terminalFailureMessage)
        val drainRawReader = rawVideoEnabled || rawPreviewPresentationCoordinator.callbacksActive
        if (drainRawReader && rawVideoManager.state.value is com.neoproc.neoproccamx.pipeline.RawVideoState.Capturing) {
            failedRecordingStartRecovery.runStep(
                "rawVideoManager.stopCapture",
                "RAW pipeline cleanup failed during recording recovery."
            ) {
                rawVideoManager.stopCapture()
            }
        }
        camera.setRawVideoCaptureEnabled(false)
        camera.setRecordingSensorPixelModeMaximumResolution(false)
        cachedRawCaptureTier = null
        if (drainRawReader) {
            viewModelScope.launch(Dispatchers.Default) {
                delay(250L)
                failedRecordingStartRecovery.runStep(
                    "camera.drainRawReaderSynchronously",
                    "RAW hardware buffers could not be drained after recovery."
                ) {
                    camera.drainRawReaderSynchronously()
                }
            }
        }
        failedRecordingStartRecovery.schedulePreviewRecovery(refreshSession)
    }

    private suspend fun awaitNextVulkanImportStatsOrTimeout(
        previous: VulkanImportStats,
        deadlineElapsedMs: Long,
        maxIdleWaitMs: Long = CameraViewModelConstants.STRICT_GPU_VALIDATION_IDLE_RECHECK_MS
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
        val expectedSessionGeneration = camera.sessionGeneration.get()
        val sourceIngressAtWaitStart = pipeline.hardwareImportSourceIngressTelemetry()
        var repeatingFirstFrameBoundary: HardwareImportRepeatingFirstFrameBoundary? = null
        while (
            SystemClock.elapsedRealtime() < deadlineElapsedMs &&
                when (gpuRecording.state.value) {
                    GpuRecordingState.Starting,
                    is GpuRecordingState.Recording -> true
                    else -> false
                }
            ) {
            if (camera.sessionGeneration.get() != expectedSessionGeneration) return false
            val ingress = pipeline.hardwareImportSourceIngressTelemetry()
            val sourceProofEvaluation = currentRepeatingHardwareImportSourceProof(
                ingress = ingress,
                baselineSourceImageCount = sourceIngressAtWaitStart.sourceImageCount,
                expectedSessionGeneration = expectedSessionGeneration,
                existingFirstFrameBoundary = repeatingFirstFrameBoundary
            )
            repeatingFirstFrameBoundary = sourceProofEvaluation.firstFrameBoundary
            val repeatingProof = sourceProofEvaluation.proof
            if (repeatingProof != null) {
                Log.i(
                    "CameraViewModel",
                    "Pre-armed offscreen AHB EGL P010 source proved live upstream of encoder gate " +
                        "proofMethod=${repeatingProof.method} " +
                        "timestampDomainsCompatible=" +
                        "${repeatingProof.timestampDomainsCompatible} " +
                        "sourceFrames=${ingress.sourceImageCount} " +
                        "baseline=${sourceIngressAtWaitStart.sourceImageCount} " +
                        "sessionGeneration=$expectedSessionGeneration " +
                        "repeatingPublication=" +
                        "${repeatingProof.repeating.publicationGeneration} " +
                        "repeatingSequence=${repeatingProof.repeating.sequenceId} " +
                        "firstRepeatingSensorTimestampNs=" +
                        "${repeatingProof.repeating.firstFrameSensorTimestampNs} " +
                        "sensorTimestampNs=${ingress.sensorTimestampNs}"
                )
                return true
            }
            delay(25L)
        }
        Log.w(
            "CameraViewModel",
            "Pre-armed offscreen AHB EGL P010 source produced no current-repeating frame " +
                "within ${timeoutMs}ms expectedGeneration=$expectedSessionGeneration " +
                "currentIngress=${pipeline.hardwareImportSourceIngressTelemetry()} " +
                "currentRepeating=${camera.currentRepeatingReadinessSnapshot()}"
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
                // Do not read gpuRecordState here: recordingTransitionState masks it as Starting
                // until this first-frame proof returns, which otherwise creates a circular gate.
                gpuRecording.state.value is GpuRecordingState.Recording
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
                pipeline.persistHardwareImportLifecycleEvidence("first-processed-frame")
                vulkanRuntime.persistOffscreenEncoderTransportEvidence("first-processed-frame")
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

    /**
     * Waits only for the gate-closed Camera2 P010 SDR path. The producer keeps running while this
     * coroutine yields; each arriving frame exercises the exact full-resolution live shader without
     * presenting to MediaCodec. A timeout rejects start instead of admitting a predictably cold
     * frame or selecting a lower-quality route.
     */
    private suspend fun awaitCameraP010SdrPrewarm(
        timeoutMs: Long =
            ProcessedGpuRecordingRequirementPolicy.CAMERA_P010_SDR_PREWARM_TIMEOUT_MS
    ): Boolean {
        val deadlineElapsedMs = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1L)
        var snapshot = vulkanRuntime.cameraP010SdrPrewarmSnapshot()
        while (
            !snapshot.terminal &&
                SystemClock.elapsedRealtime() < deadlineElapsedMs &&
                when (gpuRecording.state.value) {
                    GpuRecordingState.Starting,
                    is GpuRecordingState.Recording -> true
                    else -> false
                }
        ) {
            delay(10L)
            snapshot = vulkanRuntime.cameraP010SdrPrewarmSnapshot()
        }
        vulkanRuntime.persistOffscreenEncoderTransportEvidence(
            if (snapshot.complete) "camera-p010-sdr-prewarm-ready" else "camera-p010-sdr-prewarm-failed"
        )
        if (snapshot.complete) return true
        Log.e(
            "CameraViewModel",
            "Camera2 P010 SDR prewarm unavailable attempts=${snapshot.attempts} " +
                "successes=${snapshot.successes} failures=${snapshot.failures} " +
                "distinctBuffers=${snapshot.distinctBufferIdentities} " +
                "bypass=${snapshot.bypassReason ?: "timeout"} " +
                "foreignFrames=${snapshot.ignoredForeignSourceFrames} " +
                "lastForeign=${snapshot.lastIgnoredForeignSourceReason ?: "none"} " +
                "timeoutMs=$timeoutMs"
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
                    gpuRecording.state.value is GpuRecordingState.Recording ||
                        gpuRecording.state.value is GpuRecordingState.Paused
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
                gpuRecording.state.value is GpuRecordingState.Recording
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
                gpuRecording.state.value is GpuRecordingState.Recording
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
    ): Boolean {
        val requestedMode = normalizeNpv10CinemaMode(mode)
        val capabilityAdmission = RecordingCapabilityPolicy.resolveCinemaModeAdmission(
            requestedMode = requestedMode,
            rawSupported = camera.supportsRaw
        )
        val pipelineAdmission = resolveRequestedVideoModeAdmission(
            cinemaMode = requestedMode,
            requestedHdrMode = _videoHdrMode.value
        )
        if (!capabilityAdmission.accepted ||
            pipelineAdmission.admissionState != VideoPipelineAdmissionState.ADMITTED
        ) {
            return rejectVideoModeAdmission(
                contract = pipelineAdmission,
                source = "cinema-mode:${requestedMode.name}",
                persistRequestedCinemaMode = persist
            )
        }
        clearRejectedVideoModeAdmission()
        val presetBatchActive = modeSwitchCriticalDepth.get() > 0
        val previousMode = _cinemaVideoMode.value
        val previousRawReaderGraphActive = if (presetBatchActive) false else
            resolveRawPreviewPresentationPath() == RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER
        val previousPipelineSignature = if (presetBatchActive) null else
            activeVideoModePipelineContract().sessionSignature
        val normalizedMode = capabilityAdmission.requestedMode
        val rawPresentationTransitionEpoch = if (previousMode != normalizedMode)
            rawPreviewPresentationCoordinator.beginIfActive(
                "cinema-mode:${previousMode.name}->${normalizedMode.name}"
            ) else null
        val videoModeActive = _uiMode.value == AppMode.VIDEO
        val committedCinemaHdrMode = if (
            videoModeActive && normalizedMode != CinemaVideoMode.PRO
        ) "OFF" else _videoHdrMode.value
        val committedRawEnabled = if (videoModeActive) {
            normalizedMode == CinemaVideoMode.RAW && camera.supportsRaw
        } else {
            _rawOn.value
        }
        val committedLogEnabled = if (videoModeActive) {
            normalizedMode == CinemaVideoMode.LOG
        } else {
            _logOn.value
        }
        val committedRawCompression = if (videoModeActive) {
            normalizeRawCompressionForMode(_rawVideoCompression.value, normalizedMode)
        } else {
            _rawVideoCompression.value
        }
        if (videoModeActive && normalizedMode == CinemaVideoMode.RAW) {
            if (recording.fps > RAW_MODE_MAX_FPS) setFPS(recording.fps)
            enforceRawVideoResolutionCapIfNeeded()
        }
        advanceVulkanRenderProfileGeneration(
            reason = "cinema-mode-commit:${previousMode.name}->${normalizedMode.name}",
            committedHdrMode = committedCinemaHdrMode,
            authorityMutation = {
                _cinemaVideoMode.value = normalizedMode
                if (videoModeActive) {
                    _rawOn.value = committedRawEnabled
                    _logOn.value = committedLogEnabled
                    _rawVideoCompression.value = committedRawCompression
                    if (normalizedMode != CinemaVideoMode.PRO) _videoHdrOn.value = false
                }
                videoFeatureState = videoFeatureState.copy(
                    rawEnabled = normalizedMode == CinemaVideoMode.RAW,
                    logEnabled = normalizedMode == CinemaVideoMode.LOG,
                    cinemaMode = normalizedMode,
                    hdrEnabled = if (committedCinemaHdrMode == "OFF") false else _videoHdrOn.value,
                    hdrMode = committedCinemaHdrMode,
                    colorSpace = _videoColorSpace.value,
                    rawCompression = committedRawCompression
                )
            }
        )
        if (videoModeActive) {
            // The decision above reads the committed RAW/processed state. Without this transition
            // synchronization a RAW-started session retained its P010/YUV reader as membership-only
            // standby after entering Video: capture metadata and shader packets continued, but no
            // full-frame evidence ever reached NIV99. CameraController coalesces this target change
            // with the surrounding mode-switch transaction, so it does not add a second rebuild.
            syncPreviewAnalysisFeed()
        }
        if (videoModeActive) {
            if (normalizedMode == CinemaVideoMode.RAW) {
                rawVideoManager.compressionMode = committedRawCompression
            }
            syncRecordingCodecAndBitDepthContract(cinemaMode = normalizedMode)
            restoreProcessedRecordingSelectionAfterRawExitIfNeeded(
                previousMode = previousMode,
                currentMode = normalizedMode
            )
            colorScience.logEnabled = isVideoLogPreviewActive()
            syncEffectiveRecordingColorProfile()
        }
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
        // Mode selection never starts sensor-master capture. RAW mode does, however, configure the exact
        // final full-depth reader as an UNTARGETED session output. Recording start/stop can then retarget
        // that surface immediately without a black-frame CameraCaptureSession replacement.
        if (!rawCaptureLifecycleActive()) camera.setRawVideoCaptureEnabled(false)
        configureRawVideoPrearmForMode(normalizedMode)
        reconcileRawPreviewPipeline()
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
            if (presetBatchActive) {
                modeSwitchSessionTransaction.requestRefresh()
            } else {
                refreshSessionForModeSwitchIfIdle(
                    previousMode = previousMode,
                    currentMode = normalizedMode,
                    previousPipelineSignature = previousPipelineSignature,
                    currentPipelineSignature = activeVideoModePipelineContract().sessionSignature,
                    rawReaderGraphChanged =
                        ((previousMode == CinemaVideoMode.RAW) != (normalizedMode == CinemaVideoMode.RAW)) ||
                            previousRawReaderGraphActive !=
                            (resolveRawPreviewPresentationPath() == RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER)
                )
            }
        }
        rawPresentationTransitionEpoch?.let {
            rawPreviewPresentationCoordinator.scheduleCommit(
                it, reason = "cinema-mode:${previousMode.name}->${normalizedMode.name}"
            )
        }
        syncModeDomainFromLegacy()
        return true
    }

    /**
     * A RAW session can legitimately use the full physical sensor as its runtime request even when
     * that geometry is not exposed as a processed P010 stream. On exit, restore and re-resolve the
     * user's processed-video selection before pre-arm or recording start can observe the RAW-only
     * dimensions. This keeps the highest exact supported processed output and never substitutes an
     * 8-bit path, a lower frame rate, or a persisted lower-quality preference.
     */
    private fun restoreProcessedRecordingSelectionAfterRawExitIfNeeded(
        previousMode: CinemaVideoMode,
        currentMode: CinemaVideoMode
    ) {
        if (!CameraRecordingSelectionCoordinator.shouldRestoreProcessedSelectionAfterModeChange(
                previousMode = previousMode,
                currentMode = currentMode
            )
        ) return

        val persistedProcessedSize = prefs.videoSize.normalizeForOrientation()
        val resolvedSelection = resolveRecordingSelection(
            preferred = persistedProcessedSize,
            preferredCodec = gpuRecording.videoCodec,
            explicitResolutionSelection = true,
            requestedFps = recording.fps
        )
        val resolvedBitrate = resolveConfiguredBitrate(
            selection = resolvedSelection,
            fps = recording.fps,
            codec = resolvedSelection.codec
        )
        applyResolvedRecordingConfig(
            resolvedSelection = resolvedSelection,
            fps = recording.fps,
            bitrate = resolvedBitrate,
            audioSource = recording.audioSource
        )
        Log.i(
            "CameraViewModel",
            "Restored processed recording selection after RAW exit: " +
                "persisted=${persistedProcessedSize.width}x${persistedProcessedSize.height} " +
                "resolved=${resolvedSelection.size.width}x${resolvedSelection.size.height}"
        )
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
        val proposedCompression = if (targetMode == CinemaVideoMode.RAW) {
            RawVideoContractPolicy.UNCOMPRESSED_12_BIT_MODE
        } else {
            _rawVideoCompression.value
        }
        if (!admitSettingsCommit(
                proposedSettingsCommit(mode = targetMode, rawCompression = proposedCompression),
                source = "cinema-video-mode"
            )
        ) return
        invalidatePendingPreferenceRestore()
        val capabilityAdmission = RecordingCapabilityPolicy.resolveCinemaModeAdmission(
            requestedMode = targetMode,
            rawSupported = camera.supportsRaw
        )
        val pipelineAdmission = resolveRequestedVideoModeAdmission(targetMode)
        if (!capabilityAdmission.accepted ||
            pipelineAdmission.admissionState != VideoPipelineAdmissionState.ADMITTED
        ) {
            rejectVideoModeAdmission(
                contract = pipelineAdmission,
                source = "set-cinema-mode:${targetMode.name}"
            )
            return
        }
        val admittedMode = capabilityAdmission.requestedMode
        val targetRawEnabled = admittedMode == CinemaVideoMode.RAW
        val targetLogEnabled = admittedMode == CinemaVideoMode.LOG
        if (
            _cinemaVideoMode.value == admittedMode &&
            _rawOn.value == targetRawEnabled &&
            _logOn.value == targetLogEnabled
        ) {
            // Preset activation commonly reaches the same final cinema mode through setRawEnabled()
            // first. Reapplying it repeats capability resolution, bitrate selection, pre-arm work and
            // a Camera2 refresh request without changing a single output or quality setting.
            clearRejectedVideoModeAdmission()
            modeController.setCinemaVideoMode(admittedMode)
            if (prefs.videoMode != admittedMode.name) prefs.videoMode = admittedMode.name
            syncModeDomainFromLegacy()
            return
        }
        if (applyCinemaModeSelection(admittedMode)) {
            modeController.setCinemaVideoMode(admittedMode)
        }
        syncModeDomainFromLegacy()
    }

    // Mode identity is user-visible capture intent. Capability checks may reject an unsupported
    // transport at record start, but must never silently turn LOG into PRO.
    private fun normalizeNpv10CinemaMode(mode: CinemaVideoMode): CinemaVideoMode = mode

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
        val proposedCommit = proposedSettingsCommit(rawCompression = mode)
        if (!admitSettingsCommit(proposedCommit, source = "raw-video-compression")) return
        invalidatePendingPreferenceRestore()
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
        invalidatePendingPreferenceRestore()
        if (_rawResolutionMode.value == mode) return
        _rawResolutionMode.value = mode
        prefs.rawResolutionMode = mode
        refreshRawCaptureTierStatus()
        updateCinemaRuntimeStats(lastPreviewFpsSample)
    }

    private fun updateCinemaRuntimeStats(previewFps: Float) {
        if (modeSwitchCriticalDepth.get() > 0 && !modeSwitchFlushInProgress) {
            modeSwitchRuntimeStatsPending = true
            modeSwitchRuntimeStatsPreviewFps = previewFps
            return
        }
        statsEngine.updateCinemaRuntimeStats(previewFps)
        refreshMemoryWarning()
    }
    
    private fun refreshMemoryWarning() {
        val recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused
        if (!recordingActive) {
            applyMemoryWarningSnapshot(null)
            requestRuntimeResourceTelemetryRefresh()
            return
        }
        latestMemorySnapshot?.let(::applyMemoryWarningSnapshot)
        requestRuntimeResourceTelemetryRefresh()
    }

    private fun applyMemoryWarningSnapshot(snapshot: MemoryProfiler.MemoryStats?) {
        val recordingActive = gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Paused
        val warningMessage = snapshot?.let {
            memoryProfiler.resolveMemoryWarning(recordingActive = recordingActive, snapshot = it)
        }
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

    private suspend fun awaitGpuRecordingState(timeoutMs: Long = 1_200L): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            // recordingTransitionState intentionally remains Starting until the complete startup
            // handoff. Reading the aggregate state here therefore masks both the synchronous
            // Recording result and every precise codec Error. The recorder is authoritative for
            // this backend-specific admission fence.
            when (val currentState = gpuRecording.state.value) {
                GpuRecordingState.Recording -> return true
                is GpuRecordingState.Error -> {
                    Log.e("CameraViewModel", "awaitGpuRecordingState: recorder error: ${currentState.message}")
                    return false
                }
                else -> delay(25L)
            }
        }
        Log.w(
            "CameraViewModel",
            "awaitGpuRecordingState: timed out. Backend state: ${gpuRecording.state.value}"
        )
        return gpuRecording.state.value is GpuRecordingState.Recording
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
        val recordingActive = recordingStopRestartLifecycle.isStarting ||
            gpuRecordState.value.isRecordingSessionActive()
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        val powerManager = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalDecision = thermalPolicyResolver.resolve(
            thermalLabel = thermalLabel,
            recordingActive = recordingActive,
            powerManager = powerManager,
            strictHighEnd = strictHighEnd
        )
        applyRecordingThermalIsolation(
            recordingActive = recordingActive,
            thermalSeverity = thermalDecision.thermalSeverity
        )
        if (thermalDecision.shouldStopStrictHighEndRecording) {
            val emergencyWarning = "Recording stopped: emergency thermal limit reached; " +
                "strict high-end quality was preserved. Cool the device before recording again."
            Log.e("CameraViewModel", emergencyWarning)
            setHardwareWarning(emergencyWarning)
            requestRecordingStop(
                origin = RecordingStopOrigin.THERMAL_EMERGENCY,
                detail = "thermal=$thermalLabel platformStatus=${thermalDecision.thermalSeverity}"
            )
            return
        }
        val kalmanDecision = thermalPolicyResolver.kalmanDecision
        // Strict mode protects the requested recording configuration. Thermal mitigation therefore
        // starts with AI/analytics and preview, never encoder complexity, bitrate, quality, or FPS.
        gpuRecording.thermalEncoderComplexity = if (strictHighEnd) 10 else kalmanDecision.encoderComplexity
        val previousCap = thermalAnalysisFpsCap
        thermalAnalysisFpsCap = thermalDecision.analysisFpsCap.toFloat()
        if (previousCap != thermalAnalysisFpsCap) {
            applyRuntimePerformanceHints(lastPreviewFpsSample)
        }

        val preserveRecordingQuality = strictHighEnd || !thermalDecision.shouldThrottleBitrate
        if (preserveRecordingQuality && thermalBitrateReductionApplied &&
            isAutomaticBitrateMode(_videoBitrateMode.value)
        ) {
            applyAutomaticVideoBitrateIfNeeded(force = true)
            if (recordingActive && gpuRecording.updateActiveEncoderBitrate(recording.bitrate)) {
                recording.bitrate = gpuRecording.activeOutputBitrateBitsPerSecond()
            }
        }
        if (preserveRecordingQuality) {
            thermalBitrateReductionApplied = false
            if (_uiStateFlow.value.hardwareWarning?.startsWith("Thermal Throttling: Bitrate Reduced") == true) {
                setHardwareWarning(null)
            }
        }

        // CP-QV15: this tuning is shared by preview and record-develop. Never lower it during an active
        // recording merely to cool the preview; secondary cadence/resolution guards carry that load.
        // Outside recording it remains self-restoring when the device cools.
        if (OmniClarityMotionTuningFlag.enabled) {
            vulkanRuntime.updateOmniClarityMotionTuning(
                thermalThrottling = !recordingActive &&
                    OmniClarityMotionTuningResolver.shouldThrottleForThermal(
                        thermalDecision.thermalSeverity
                    )
            )
        }

        if (!thermalDecision.shouldApplyPreviewGuard) {
            if (!preserveRecordingQuality && thermalBitrateReductionApplied &&
                isAutomaticBitrateMode(_videoBitrateMode.value)
            ) {
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
                refreshPreviewPipelineForAdaptiveLimitChange()
            }
            return
        }

        if (recordingActive && thermalDecision.shouldThrottleBitrate &&
            !thermalBitrateReductionApplied && isAutomaticBitrateMode(_videoBitrateMode.value)
        ) {
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
        val previewCap = CameraPreviewSessionSizingPolicy.resolveAspectAwareRuntimeCap(
            maxLongEdge = thermalDecision.previewMaxLongEdge,
            targetAspect = currentPreviewTransportAspect()
        )
        val changed = previewCap.differsFrom(
            currentMaxLongEdge = thermalPreviewMaxLongEdge,
            currentMaxArea = thermalPreviewMaxArea
        )
        thermalPreviewMaxLongEdge = previewCap.maxLongEdge
        thermalPreviewMaxArea = previewCap.maxArea
        if (changed && !recordingActive) {
            refreshPreviewPipelineForAdaptiveLimitChange()
        }
    }

    private fun applyRecordingThermalIsolation(
        recordingActive: Boolean,
        thermalSeverity: Int
    ) {
        val isolation = RecordingThermalIsolationPolicy.resolve(
            recordingActive = recordingActive,
            thermalSeverity = thermalSeverity
        )
        val changed = recordingThermalIsolationActive != isolation.active
        recordingThermalIsolationActive = isolation.active
        pipeline.setNonessentialAnalysisSuspendedForRecording(isolation.suspendSceneAnalysis)
        camera.setRecordingThermalAnalysisIsolationEnabled(isolation.suspendCameraAnalysisTarget)
        if (changed) {
            applyAiTrackingPreferences()
            Log.i(
                "RecordingThermalIsolation",
                "RECORDING_THERMAL_ISOLATION active=${isolation.active} " +
                    "reason=${isolation.reason} aiSuspended=${isolation.suspendAiInference} " +
                    "sceneSuspended=${isolation.suspendSceneAnalysis} " +
                    "cameraAnalysisTargetSuspended=${isolation.suspendCameraAnalysisTarget} " +
                    "telemetrySuppressed=${isolation.suppressProcessMemoryTelemetry} " +
                    "recordingQualityUnchanged=true"
            )
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

    private fun installRawVideoCallbacks() = rawPreviewPresentationCoordinator.installCallbacks()

    private fun rawCaptureLifecycleActive(): Boolean =
        rawCustomRecordingState.value is GpuRecordingState.Ready ||
            rawCustomRecordingState.value is GpuRecordingState.Starting ||
            rawCustomRecordingState.value is GpuRecordingState.Recording ||
            rawCustomRecordingState.value is GpuRecordingState.Paused ||
            rawCustomRecordingState.value is GpuRecordingState.Stopping ||
            cameraState.value == CameraState.Recording

    private fun resolveRawPreviewPresentationPath(): RawPreviewPresentationPolicy.Path {
        if (isSensorLinearNdrMode()) return RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER
        if (camera.characteristics == null) return RawPreviewPresentationPolicy.Path.CAMERA_MONITOR
        val sensorSize = listOfNotNull(camera.maxRawSize, camera.pixelArraySize, camera.fullSensorSize())
            .maxBy { it.width.toLong() * it.height.toLong() }
        return RawPreviewPresentationPolicy.resolve(
            rawModeActive = _uiMode.value == AppMode.VIDEO &&
                _cinemaVideoMode.value == CinemaVideoMode.RAW,
            recordingActive = rawCaptureLifecycleActive(),
            sensorWidth = sensorSize.width,
            sensorHeight = sensorSize.height
        )
    }

    private fun configureRawVideoPrearmForMode(mode: CinemaVideoMode) {
        if (rawCaptureLifecycleActive()) return
        val enable = _uiMode.value == AppMode.VIDEO &&
            mode == CinemaVideoMode.RAW &&
            camera.supportsRaw
        if (!enable) {
            camera.setRawVideoPrearmEnabled(false)
            camera.setRecordingSensorPixelModeMaximumResolution(false)
            camera.setRecordingReadoutOverrideSize(null)
            return
        }

        // Resolve once at mode entry. This same cached tier is consumed at record start, so reader size,
        // depth, and SENSOR_PIXEL_MODE cannot diverge and force a second session configuration.
        val tier = resolveRawCaptureTier(forceRefresh = cachedRawCaptureTier == null)
        val rawSize = tier.rawSize?.let { Size(it.width, it.height) } ?: camera.fullSensorSize()
        camera.setNpv10TenBitCaptureEnabled(false)
        camera.setRecordingReadoutOverrideSize(rawSize)
        camera.setRecordingSensorPixelModeMaximumResolution(tier.engageMaxResPixelMode)
        rawVideoManager.sensorPixelModeMaximumResolutionUsed = tier.engageMaxResPixelMode
        rawVideoManager.orientationHintDegrees = camera.videoOrientationHint()
        camera.setRawVideoPrearmEnabled(true)
        rawVideoManager.allowIdleCaptureStoragePreparation()
        rawVideoManager.prepareCaptureStorageAsync(RawCompressionMode.LOSSLESS_REALTIME)
    }

    private fun rawVideoCallbacksNeeded(): Boolean =
        isSensorLinearNdrMode() || RawVideoCallbackOwnershipPolicy.callbacksNeeded(
            videoUiActive = _uiMode.value == AppMode.VIDEO,
            rawModeActive = _cinemaVideoMode.value == CinemaVideoMode.RAW
        )

    private fun reconcileRawPreviewPipeline() {
        val sensorDebayer =
            resolveRawPreviewPresentationPath() == RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER
        val pathChanged = rawPreviewPresentationCoordinator.updatePathActive(
            sensorDebayer, if (sensorDebayer) "sensor-debayer-path-enabled" else "sensor-debayer-path-disabled"
        )
        vulkanRuntime.setCinemaDebayerActive(sensorDebayer)
        camera.setRawPreviewCaptureEnabled(sensorDebayer)
        rawPreviewPresentationCoordinator.reconcileCallbacks(rawVideoCallbacksNeeded())
        if (sensorDebayer && pathChanged)
            rawPreviewPresentationCoordinator.scheduleCommit(reason = "sensor-debayer-path-enabled")
    }

    override fun onCleared() {
        RetainedRecordingStopCommands.unbind(this)
        // Close pre-arm admission before viewModelScope cancellation. The retirement lease then waits
        // for the one transaction that may already own RecordingManager before any detach proof.
        val recordingPrearmTeardownSeal =
            recordingSurfacePreparationCoordinator.sealForTeardown()
        if (recordingStopRestartLifecycle.isStarting || isRecordingOrStopping()) {
            recordRecordingStopOrigin(
                origin = RecordingStopOrigin.VIEW_MODEL_TEARDOWN,
                detail = "CameraViewModel teardown began while recording ownership was active"
            )
        }
        viewModelTeardownStarted.set(true)
        recordingSafePreviewLutPublicationGate.close()
        if (recordingStopRestartLifecycle.cancelForTeardown()) {
            Log.i("CameraViewModel", "Cancelled retained recording restart during ViewModel teardown")
        }
        // onCleared cannot block the main thread for the five-second Camera2 marker transaction.
        // Atomically poison only an OPEN producer boundary. If exact stop already established it,
        // teardown cannot downgrade that valid clip; if teardown wins, publication stays poisoned.
        npv10Recording.poisonUnprovenProducerBoundaryFailure(
            "NPV10 ViewModel teardown began before an exact P010 producer boundary was proven"
        )
        super.onCleared()
        // Release process-global flag callbacks + the thermal listener (each captures `this`) so this dead
        // ViewModel's object graph is freed at teardown instead of lingering until the next launch overwrites
        // the single onChanged slots.
        CameraViewModelGlobalCallbacks.clear()
        thermalStatusCoordinator.close()
        actionChannel.close()
        recordingSavedPathChannel.close()
        foregroundRecovery.cancel()
        aiWarmupJob?.cancel()
        aiTransitionReleaseJob?.cancel()
        // Cut Camera2/UI admission synchronously, but transfer physical native teardown to a daemon
        // retirement lease. ImageReader.close() invalidates every acquired Image, and MediaCodec/Surface
        // destruction is unsafe while a recorder worker is inside JNI; neither operation may run here.
        rawVideoManager.requestSensorLinearNdrRecordingIngressTeardown()
        persistentPreviewLifecycle.close()
        val retireNativeRawPreview = rawPreviewPresentationCoordinator.retireCallbacksForTeardown()
        camera.onYuvFrame = null
        camera.onHardwareFrame = null
        camera.onCaptureResult = null
        camera.onRawCaptureResultWithSessionGeneration = null
        camera.onNiv99CaptureResultWithSessionGeneration = null
        camera.onCaptureResultWithSessionGeneration = null
        camera.onMotionSensorSample = null
        camera.onSessionGenerationAdvanced = null
        camera.onSessionGenerationConfigured = null
        camera.onCameraPipelineWarning = null
        strictPreviewRuntimeFailureCoordinator.close()
        previewGlCompositor.setNiv99FrameProcessor(null)
        previewGlCompositor.setOnFramePresentedListener(null)
        previewGlCompositor.setOnInputFrameListener(null)
        hdr10PqPreviewCompositor.setOnFramePresentedListener(null)
        hdr10PqPreviewCompositor.setOnInputFrameListener(null)
        hdr10PqPreviewCompositor.setOnProjectOwnedFramePresentedListener(null)
        persistentPreviewOutputRouter.setOnFramePresentedListener(null)
        modeSwitchRuntimeStatsJob?.cancel()
        recordingCapabilityWarmupJob?.cancel()
        startupCoordinator.cancelAll()
        startupDeferredRestoreJob?.cancel()
        refreshPreferencesJob?.cancel()
        stopNativePreviewWatchdog()
        stopNativePreviewRetry()
        nativeVisiblePreviewActive = false
        nativeVisiblePreviewConfirmed = false
        previewSurface = null
        previewViewRef?.setOnRenderInvalidationListener(null)
        previewViewRef = null
        updatePreviewDisplayPresentSurface(null)
        manualControlDispatcher.shutdown()
        releaseCinemaAutoLock(resetCameraState = false, reason = "on_cleared")
        clearRecordingReadoutOverride()
        gpuRecording.recordedFrameCommitListener = null
        SensorPipelineIntegration.unbindRecordingStages(gpuRecording)
        rawVideoManager.persistedFrameCommitListener = null
        rawVideoManager.recordingAudioFailureListener = null
        rawVideoManager.continuityBoundaryStopListener = null
        npv10Recording.persistedFrameCommitListener = null
        gpuRecording.runtimeEvidenceMetadataProvider = null
        npv10Recording.runtimeEvidenceMetadataProvider = null
        pipeline.setRawYuvImageSink(null)
        pipeline.setRawYuvFrameSink(null)
        aiCameraPipeline.detachListenerForTeardown()
        stillCapture.detachRuntimeProvidersForTeardown()

        val retirementLease = CameraNativeResourceRetirementLease(
            { recordingStopRestartLifecycle.isStopInProgress }, recordingPrearmTeardownSeal,
            recordingForegroundProtection::stop,
            rawVideoManager, vulkanRuntime,
            {
                // The retirement worker first drains full-resolution ingress and every accepted
                // recording-preview token. Only then may the final preview lineage be frozen and its
                // callback detached; detaching here synchronously would discard a valid terminal frame.
                check(drainAndShutdownCamera2LineageForTeardown()) {
                    "Camera2 capture-result lineage did not drain before certificate freeze"
                }
                freezeCamera2FrameLineageSession()
                vulkanRuntime.setFrameLineageSubmissionListener(null)
            },
            retireNativeRawPreview,
            {
                npv10AudioRecording.release()
                npv10Recording.release()
            },
            gpuRecording::destroy, stillCapture::destroy,
            camera::destroyWithRetirementProof, pipeline::destroy,
            aiAfCoordinator::destroy, aiCameraPipeline::close,
            recordingStopRestartLifecycle::releasePublicationIsolationAfterRetirement
        )
        val retirementScheduled = nativeResourceRetirement.schedule(
            "CameraViewModel.onCleared",
            retirementLease,
            retirementLease::retire
        )
        if (!retirementScheduled) {
            stopRecordingForegroundService()
            Log.e(
                "CameraViewModel",
                "Unable to schedule native resource retirement; resources intentionally quarantined"
            )
        }
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
        renderContract: VulkanPreviewRenderContract? = null,
        deliveryState: VideoFormatDeliveryState,
        renderState: VulkanRenderRequestSnapshot
    ): FloatArray = buildVulkanGrade(
        neutralVideoPreview = renderState.videoSurface && deliveryState.neutralPreview,
        truthfulColorDelivery = renderState.videoSurface && deliveryState.truthfulColorDelivery,
        previewLookState = if (renderState.videoSurface) {
            VideoFormatPreviewLookPolicy.resolve(deliveryState)
        } else {
            VideoFormatPreviewLookState()
        },
        renderContract = renderContract,
        deliveryState = deliveryState,
        renderState = renderState
    )

    private fun buildVulkanEncoderGrade(
        deliveryState: VideoFormatDeliveryState,
        renderState: VulkanRenderRequestSnapshot
    ): FloatArray = buildVulkanGrade(
        neutralVideoPreview = false,
        deliveryParityBias = false,
        highLoadPreviewOverride = false,
        truthfulColorDelivery = renderState.videoSurface && deliveryState.truthfulColorDelivery,
        deliveryState = deliveryState,
        renderState = renderState
    )

    private fun buildVulkanGrade(
        neutralVideoPreview: Boolean,
        deliveryParityBias: Boolean = false,
        highLoadPreviewOverride: Boolean? = null,
        truthfulColorDelivery: Boolean = false,
        previewLookState: VideoFormatPreviewLookState = VideoFormatPreviewLookState(),
        renderContract: VulkanPreviewRenderContract? = null,
        deliveryState: VideoFormatDeliveryState,
        renderState: VulkanRenderRequestSnapshot
    ): FloatArray {
        val videoSurface = renderState.videoSurface
        val result = CameraVulkanGradeBuilder.build(
            CameraVulkanGradeRequest(
                neutralVideoPreview = neutralVideoPreview,
                deliveryParityBias = deliveryParityBias,
                highLoadPreview = highLoadPreviewOverride ?: renderState.highLoadPreviewActive,
                truthfulColorDelivery = truthfulColorDelivery,
                previewLookState = previewLookState,
                renderContract = renderContract,
                videoSurface = videoSurface,
                hdrRenderActive = !neutralVideoPreview && videoSurface &&
                    deliveryState.effectiveHdrMode != "OFF" &&
                    isSafeVideoHdrPreviewActive(renderState, deliveryState),
                deliveryState = deliveryState,
                lutEnabled = renderState.lutEnabled,
                nativePreviewLutEnabled = renderState.nativePreviewLut.enabled,
                nativePreviewLutSize = renderState.nativePreviewLut.size,
                sceneRenderProfile = renderState.sceneRenderProfile,
                sdrQualityIntent = renderState.sdrQualityIntent,
                colorProfile = renderState.colorProfile,
                fallbackLogEnabled = renderState.deliveryKey.logEnabled
            )
        )
        result.apexMode?.let { apexMode ->
            val metrics = renderState.apex.metrics
            ApexPipelineOrchestrator.injectApexUniforms(
                result.uniforms,
                ApexProfileParams.compute(metrics, apexMode),
                metrics
            )
        }
        renderState.snce.injectIntoPreviewGrade(result.uniforms)
        return result.uniforms
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
        hdrToHdrSameTransport: Boolean = false,
        rawReaderGraphChanged: Boolean = true,
        auxiliaryRawReaderGraphChanged: Boolean = false
    ) {
        val decision = CinemaModeSessionRefreshPolicy.resolve(
            previousMode = previousMode,
            currentMode = currentMode,
            videoModeActive = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value),
            hdrMode = _videoHdrMode.value,
            captureActive = shouldSkipSessionRefreshForModeSwitch(),
            previousPipelineSignature = previousPipelineSignature,
            currentPipelineSignature = currentPipelineSignature,
            rawReaderGraphChanged = rawReaderGraphChanged,
            auxiliaryRawReaderGraphChanged = auxiliaryRawReaderGraphChanged,
            hdrToHdrSameTransport = hdrToHdrSameTransport
        )
        if (!decision.shouldRefresh) return if (decision.shouldRetargetRepeatingRequest) camera.refreshRepeatingRequest() else Unit
        camera.refreshOutputsAndSession(
            rebuildReaders = decision.rebuildReaders,
            forceRebuild = decision.forceRebuild,
            reason = "mode-switch:${previousMode.name}->${currentMode.name}"
        )
    }

    private fun isSafeVideoHdrPreviewActive(
        videoSurface: Boolean,
        deliveryState: VideoFormatDeliveryState
    ): Boolean = SafeVideoHdrPreviewPolicy.isActive(
        eligibleVideoPreview = videoSurface && !deliveryState.neutralPreview,
        requestedCodec = recording.codec,
        av1Supported = supportsAv1Fast(),
        pipelineContract = deliveryState.pipelineContract,
        effectiveHdrMode = deliveryState.effectiveHdrMode,
        simulatedTenBitFallback = gpuRecording.simulatedTenBitFallbackActive
    )

    private fun isSafeVideoHdrPreviewActive(
        renderState: VulkanRenderRequestSnapshot,
        deliveryState: VideoFormatDeliveryState
    ): Boolean = SafeVideoHdrPreviewPolicy.isActive(
        eligibleVideoPreview = renderState.videoSurface && !deliveryState.neutralPreview,
        requestedCodec = renderState.requestedCodec,
        av1Supported = renderState.av1Supported,
        pipelineContract = deliveryState.pipelineContract,
        effectiveHdrMode = deliveryState.effectiveHdrMode,
        simulatedTenBitFallback = renderState.simulatedTenBitFallback
    )

    private fun startRecordingForegroundService() = recordingForegroundProtection.start()
    private fun stopRecordingForegroundService() = recordingForegroundProtection.stop()

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
