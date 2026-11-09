package com.neoproc.neoproccamx.ui

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.util.Log
import android.text.TextUtils
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.WindowManager
import android.widget.Toast
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.neoproc.neoproccamx.NeoProCamXApp
import com.neoproc.neoproccamx.BuildConfig
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.ai.DetectedFace
import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.camera.CameraParams
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.camera.FlashMode
import com.neoproc.neoproccamx.camera.LiveResult
import com.neoproc.neoproccamx.color.ColorScience
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.capture.CaptureEvent
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.databinding.ActivityMainBinding
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.ui.controls.ManualControlsFragment
import com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalog
import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import com.neoproc.neoproccamx.pipeline.FocusPeakingMask
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.ZebraMask
import com.neoproc.neoproccamx.rendering.PreviewContentBoundsResolver
import com.neoproc.neoproccamx.ui.overlays.SafeAreaOverlayEngine
import com.neoproc.neoproccamx.ui.overlays.SafeAreaPreset
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.neoproc.neoproccamx.monitor.ModuleHealthStatus
import com.neoproc.neoproccamx.monitor.MonitoredModule
import com.neoproc.neoproccamx.monitor.RuntimeHealthMonitor
import com.neoproc.neoproccamx.ui.health.HealthChipBinder
import com.neoproc.neoproccamx.ui.health.HealthDiagnosticsDialogFragment
import com.neoproc.neoproccamx.ui.health.HealthDiagnosticsHost
import com.neoproc.neoproccamx.ui.health.HealthEventLoggerImpl
import com.neoproc.neoproccamx.ui.health.HealthRecoveryExecutorImpl
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.lang.ref.WeakReference

@OptIn(FlowPreview::class)
class MainActivity : AppCompatActivity(), CameraHapticPerformer, HealthDiagnosticsHost {

    companion object {
        private const val TAG = "MainActivity"
        // Cold-start splash safety deadline: dismiss the splash by this long even if no preview frame
        // arrives (e.g. camera permission/HW failure), so the logo can never get stuck on screen.
        private const val MAX_SPLASH_HOLD_MS = 2500L
        private const val MANUAL_TAG = "manual_controls"
        private const val FOCUS_EXPOSURE_HOLD_MS = 180L
        private const val FOCUS_HOLD_MOVE_TOLERANCE_DP = 24f
        private const val MODE_ROTARY_SWIPE_DISTANCE_DP = 48f
        private const val DEBUG_ACTION_START_RECORDING = "start_recording"
        private const val DEBUG_ACTION_STOP_RECORDING = "stop_recording"
        private const val DEBUG_ACTION_RECORD_CLIP = "record_clip"
        private const val DEBUG_ACTION_SWITCH_FACING = "switch_facing"
        private const val DEBUG_ACTION_TRIGGER_FOCUS = "trigger_focus"
        private const val DEBUG_AUTOMATION_RETRY_DELAY_MS = 500L
        private const val DEBUG_AUTOMATION_MAX_RETRIES = 120
        private const val DEBUG_RECORDING_STATE_POLL_MS = 250L
        private const val DEBUG_RECORDING_VERIFY_DELAY_MS = 12_000L
        private const val DEBUG_LOG_RECORDING_VERIFY_DELAY_MS = 12_000L
        private const val DEBUG_HDR_RECORDING_VERIFY_DELAY_MS = 24_000L
        private const val DEBUG_HDR_RECORDING_BACKEND_SETTLE_GRACE_MS = 1_200L
        private const val OVERLAY_UPDATE_SAMPLE_MS = 50L
        private const val DIAGNOSTIC_OVERLAY_UPDATE_SAMPLE_MS = 33L
        private const val CAMERA_PARAM_UPDATE_SAMPLE_MS = 33L
        private const val STATUS_CHROME_UPDATE_SAMPLE_MS = 250L
        private const val CRITICAL_PREVIEW_FPS_FRACTION = 0.72f
        private val COLOR_PROFILES = ColorScience.BUILT_IN_PROFILES
        @Volatile
        private var activeInstanceRef: WeakReference<MainActivity>? = null
        private val pendingDebugCommandLock = Any()
        private val pendingDebugCommandIntents: ArrayDeque<Intent> = ArrayDeque()

        fun dispatchDebugCommand(intent: Intent): Boolean {
            val activity = activeInstanceRef?.get() ?: return false
            if (!activity.canAcceptDebugCommands()) return false
            activity.runOnUiThread {
                queueDebugCommand(intent)
                activity.applyQueuedDebugCommandIfAny()
                activity.maybeRunDebugAutomation()
            }
            return true
        }

        fun queueDebugCommand(intent: Intent) {
            synchronized(pendingDebugCommandLock) {
                pendingDebugCommandIntents.addLast(Intent(intent))
            }
        }

        private fun dequeueDebugCommand(): Intent? =
            synchronized(pendingDebugCommandLock) {
                pendingDebugCommandIntents.pollFirst()
            }
    }

    private lateinit var binding: ActivityMainBinding
    internal val vm: CameraViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(application as NeoProCamXApp, CameraViewModelFactory(application))[CameraViewModel::class.java]
    }

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private lateinit var orientationCoordinator: CameraDeviceOrientationCoordinator
    
    // Haptic feedback vibrator
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    private val hapticExecutor: CameraHapticFeedbackExecutor by lazy {
        CameraHapticFeedbackExecutor(binding.root) { vibrator }
    }

    private var observersJob: Job? = null
    private var flashMode = FlashMode.OFF
    private var surfaceReady = false
    private var foregroundResumeCameraRecoveryPending = false
    private var currentRotationDegrees = 0
    private var controlsVisible = true
    private var featuresMenuVisible = false
    private var peakingTrayVisible = false
    private var lastState = CameraUiState()
    // First render must reconcile the monitor panels/overlay props against the layout defaults even
    // when the persisted value equals the seeded CameraUiState default (otherwise a persisted-true
    // monitor stays hidden after relaunch). See CameraUiStateDiffPolicy.shouldApplyBoolean.
    private var firstStateRender = true
    private var pendingHistogramTelemetry: IntArray? = null
    private var pendingWaveformTelemetry: IntArray? = null
    private var pendingZebraMaskTelemetry: ZebraMask? = null
    private var pendingSubjectMaskTelemetry: SubjectMask? = null
    private var pendingPeakingMaskTelemetry: FocusPeakingMask? = null
    private var pendingFacesTelemetry: List<DetectedFace>? = null
    private var pendingLiveResultTelemetry: LiveResult? = null
    private var pendingCameraParamsTelemetry: CameraParams? = null
    private var hasPendingZebraMaskTelemetry = false
    private var hasPendingSubjectMaskTelemetry = false
    private var hasPendingPeakingMaskTelemetry = false
    private var hasPendingLiveResultTelemetry = false
    private var renderedHistogramTelemetry: IntArray? = null
    private var renderedWaveformTelemetry: IntArray? = null
    private var renderedFacesTelemetry: List<DetectedFace>? = null
    private val telemetryVsyncGate: VSyncTelemetryGate by lazy {
        VSyncTelemetryGate(::flushTelemetryOnVSync)
    }
    private var latestLiveResult: LiveResult? = null
    private var zoomHudHideJob: Job? = null
    private var focusHoldJob: Job? = null
    private var focusAdjustJob: Job? = null
    private var focusHoldTriggered = false
    private var focusDownX = 0f
    private var focusDownY = 0f
    private var galleryThumbnailJob: Job? = null
    private var latestGalleryThumbnailUri: Uri? = null
    private var pendingDebugMode: AppMode? = null
    private var pendingDebugPreset: CameraModePreset? = null
    private var pendingDebugAction: String? = null
    private var pendingDebugDurationMs = DebugCommandParser.DEFAULT_DEBUG_DURATION_MS
    private var pendingDebugRaw: Boolean? = null
    private var pendingDebugLog: Boolean? = null
    private var pendingDebugAspect: PreviewAspectRatio? = null
    private var pendingDebugColorProfile: String? = null
    private var pendingDebugLutEnabled: Boolean? = null
    private var pendingDebugLutProfile: String? = null
    private var pendingDebugValidationLabel: String? = null
    private var pendingDebugVideoColorSpace: String? = null
    private var pendingDebugVideoHdrMode: String? = null
    private var pendingDebugVideoResolution: Size? = null
    private var pendingDebugVideoFps: Int? = null
    private var pendingDebugVideoMode: CinemaVideoMode? = null
    private var pendingDebugVideoDetailProfile: VideoDetailProfile? = null
    private var pendingDebugRawCompression: RawCompressionMode? = null
    private var pendingDebugUncompressedCinemaPipeline: Boolean? = null
    private val debugRecordClipCoordinator = DebugRecordClipCoordinator(
        nowMs = SystemClock::elapsedRealtime,
        postDelayed = { task, delayMs -> binding.root.postDelayed(task, delayMs) },
        isCommandPending = { BuildConfig.DEBUG && hasPendingDebugCommand },
        isVideoMode = { vm.uiMode.value == AppMode.VIDEO },
        recordingState = { vm.gpuRecordState.value },
        isRecordingTimingReady = { vm.isRecordingReadyForDebugClipTiming() },
        markTimingStart = { vm.markDebugClipTimingStartForEvidence() },
        stopRecording = { vm.stopRecording() },
        logInfo = { Log.i(TAG, it) },
        logError = { Log.e(TAG, it) },
        onFinished = {
            clearPendingDebugCommandState()
            binding.root.post {
                applyQueuedDebugCommandIfAny()
                maybeRunDebugAutomation()
            }
        }
    )
    private var pendingDebugRecordClipToken: Long? = null
    private var hasPendingDebugCommand = false
    private var debugActionExecuted = false
    private var debugActionScheduled = false
    private var debugConfigAppliedForPendingCommand = false
    private var manualControlsSetupPending = false
    private var debugAutomationRetryCount = 0
    private var debugModeTransitionAtMs = 0L
    private var debugSessionRefreshPending = false
    @Volatile
    private var resumedForDebugCommands = false
    private var hasCompletedInitialResume = false
    private var securityStartupBlocked = false
    private var lastShownUiWarning: String? = null
    private var lastPreviewFpsLogMs = 0L
    private var shutterMorphAnimator: ValueAnimator? = null
    private var shutterCornerRadiusPx = 0f
    private var shutterVisualRecording = false
    private var evSliderTracking = false
    private var lastZoomDispatchMs = 0L
    private var lastGestureNavigateMs = 0L
    private var lastFocusAssistLockVisual = FocusAssistVisual.TAP
    private lateinit var uiRenderScheduler: CameraUiRenderScheduler

    // Dynamic runtime-health monitoring subsystem (see com.neoproc.neoproccamx.monitor + ui/health).
    private val healthMonitor: RuntimeHealthMonitor by lazy {
        RuntimeHealthMonitor(
            recoveryExecutor = HealthRecoveryExecutorImpl(vm),
            logger = HealthEventLoggerImpl()
        )
    }
    private var healthChipBinders: Map<MonitoredModule, HealthChipBinder> = emptyMap()
    override val healthStatusFlow: kotlinx.coroutines.flow.StateFlow<Map<MonitoredModule, ModuleHealthStatus>>
        get() = healthMonitor.status
    private var activeModePreset = CameraModePresetOrderPolicy.defaultPreset()
    private var lastModePresetActivationUptimeMs = 0L
    private var modeSelectorTouchDownX: Float? = null
    private var lastRecordingTimerText: String? = null
    private var pendingStablePreviewSurface: Surface? = null
    // PERF (startup): the Surface instance the camera open has already been scheduled for, so repeated
    // surfaceChanged callbacks for the same Surface don't re-arm (and thus delay) the open.
    private var cameraOpenSurface: Surface? = null
    // Cold-start splash: flips true on the first preview frame so the splash is dismissed exactly when
    // the live preview is visible (set on the UI thread from the preview-fps callback).
    @Volatile private var previewReadyForSplash = false
    private var cachedSurfaceOpenProbeAttempts = 0
    private var cachedSurfaceOpenProbeArmed = false
    private val mainHandler = Handler.createAsync(Looper.getMainLooper())
    private val debugAutomationRetryRunnable = Runnable { maybeRunDebugAutomation() }
    private val saveWatermarkHideRunnable = Runnable { hideSaveWatermark() }
    private val stableSurfaceOpenRunnable = Runnable { openFromStablePreviewSurface() }
    private val cachedSurfaceOpenProbeRunnable = Runnable { probeCachedPreviewSurfaceAfterLayout() }

    private fun enforceStrictHighEndPolicy() {
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        Log.i(TAG, "Strict high-end policy locked for MainActivity runtime")
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = CameraPermissionPolicy.isCameraGranted(
            result = result,
            hasCameraPermission = hasPermission(Manifest.permission.CAMERA)
        )
        if (!cameraGranted) {
            toast("Camera permission is required")
            return@registerForActivityResult
        }
        maybeOpenAdvancedCamera()
    }

    private val lutImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val importedFile = importCustomLutToAppStorage(uri)
            if (importedFile == null) {
                toast("LUT import failed")
                return@launch
            }
            val loaded = vm.importCustomLut(importedFile)
            toast(if (loaded) "Custom LUT loaded" else "LUT import failed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Cold-start splash: the OS draws the logo on black instantly from the launch theme; keep it on
        // screen (masking the boot delay) until the first preview frame renders, with a safety deadline
        // so it can never hang if the camera fails to open. installSplashScreen() must precede super.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        val splashDeadlineMs = SystemClock.elapsedRealtime() + MAX_SPLASH_HOLD_MS
        splashScreen.setKeepOnScreenCondition {
            !previewReadyForSplash && SystemClock.elapsedRealtime() < splashDeadlineMs
        }
        com.neoproc.neoproccamx.utils.BootTrace.mark("activity.onCreate.start")
        enforceStrictHighEndPolicy()
        // Apply product-default flag states once per process. Debug A/B commands can still override them
        // after startup without Activity recreation stomping the current session.
        ProductDefaultsApplier.applyOnce()
        activeInstanceRef = WeakReference(this)

        // CRITICAL PATH - absolute minimum for first frame
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.neoproc.neoproccamx.utils.BootTrace.mark("activity.setContentView.done")
        uiRenderScheduler = CameraUiRenderScheduler(
            framePoster = { runnable -> binding.root.postOnAnimation(runnable) },
            render = { passes ->
                if (CameraUiRenderPass.STATUS_CHROME in passes) {
                    renderStatusChromeNow()
                }
                if (CameraUiRenderPass.TOP_WARNING in passes) {
                    renderTopWarningChip()
                }
            }
        )
        securityStartupBlocked = enforceRuntimeSecurityGate()
        if (securityStartupBlocked) return
        // IMMEDIATE - needed for camera preview and UI responsiveness
        setupWindow()
        setupPreviewSurface()
        setupGestures()
        setupControls()  // Needed for button haptics
        applyDockCaptureRowPosition()
        setupHealthIndicators()
        observeState()   // Needed for UI updates
        readDebugIntent(intent)
        applyQueuedDebugCommandIfAny()
        // DEFERRED - truly non-critical
        binding.root.post {
            setupInsets()
            setupManualControls()
            setupUiPolish()
            if (pendingDebugPreset == null) {
                syncPresetFromState()
            } else {
                renderPresetSelection()
                renderStatusChrome()
            }
            
            // VERIFY MAXIMUM FIDELITY
            if (com.neoproc.neoproccamx.core.HighEndQualityPolicy.isHighEndQualityActive()) {
                Log.i("NeoProCamX", "🚀 MAXIMUM FIDELITY UNLOCKED: " +
                    "Full Sensor=${com.neoproc.neoproccamx.core.HighEndQualityPolicy.isFullSensorForced()}, " +
                    "Strict Mode=TRUE, Scaling=PROHIBITED")
                toast("🚀 MAXIMUM FIDELITY UNLOCKED")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDebugIntent(intent)
        applyQueuedDebugCommandIfAny()
        maybeRunDebugAutomation()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (manualControlsSetupPending) {
            manualControlsSetupPending = false
            setupManualControls()
        }
    }

    override fun onResume() {
        super.onResume()
        com.neoproc.neoproccamx.utils.BootTrace.mark("activity.onResume")
        if (securityStartupBlocked) return
        enforceStrictHighEndPolicy()
        applyPreferredDisplayRefreshRate()
        resumedForDebugCommands = true
        applyQueuedDebugCommandIfAny()
        
        // CRITICAL PATH - camera and orientation first
        if (hasPermission(Manifest.permission.CAMERA)) {
            val shouldRecoverForegroundCamera = hasCompletedInitialResume
            if (shouldRecoverForegroundCamera) {
                foregroundResumeCameraRecoveryPending = true
            }
            maybeOpenAdvancedCamera(fromForegroundResume = shouldRecoverForegroundCamera)
        } else {
            requestPermissions()
        }
        Log.i(TAG, "onResume camera-open gate complete")
        // Re-assert the HDR window colour mode on every foreground. setupWindow() at
        // onCreate and the videoHdrMode/logEnabled observer are the only other apply
        // sites, and a background→foreground StateFlow re-emit of an unchanged HDR
        // value is a diff-less no-op — so without this the window can return showing
        // the 10-bit HLG preview tone-mapped to SDR ("HDR looks SDR"). Forced
        // (auto-skipped while recording); display-side only, so the recorded file is
        // untouched.
        syncWindowHdrColorMode(forceReassert = true)
        Log.i(TAG, "onResume HDR color mode sync complete")
        orientationCoordinator.start()
        Log.i(TAG, "onResume orientation listener enabled")
        Log.i(TAG, "onResume rotation update complete")
        refreshGalleryThumbnail() // GALLERY THUMBNAIL FIX: Load latest capture on resume
        Log.i(TAG, "onResume gallery thumbnail refresh scheduled")
        
        // DEFERRED - truly non-critical
        binding.root.post {
            Log.i(TAG, "onResume deferred block start")
            binding.cameraOverlay.startSensors()
            Log.i(TAG, "onResume deferred sensors started")
            if (hasCompletedInitialResume) {
                vm.refreshPreferences()
                Log.i(TAG, "onResume deferred preferences refreshed")
            } else {
                hasCompletedInitialResume = true
                Log.i(TAG, "onResume deferred initial resume marked complete")
            }
            maybeRunDebugAutomation()
            Log.i(TAG, "onResume deferred debug automation checked")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause entered securityStartupBlocked=$securityStartupBlocked")
        if (securityStartupBlocked) return
        resumedForDebugCommands = false
        binding.cameraOverlay.stopSensors()
        orientationCoordinator.stop()
        binding.root.removeCallbacks(debugAutomationRetryRunnable)
        cancelStableSurfaceCameraOpen()
        cancelCachedSurfaceOpenProbe()
        binding.tvSaveWatermark.removeCallbacks(saveWatermarkHideRunnable)
        if (::uiRenderScheduler.isInitialized) {
            uiRenderScheduler.cancel()
        }
        vm.handleAppBackgrounded()
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop entered")
        // Stop the looping WARNING/ERROR chip animators while backgrounded — the health poll loop is
        // already suspended by repeatOnLifecycle(STARTED), and the next bind on return restarts them.
        healthChipBinders.values.forEach { it.stop() }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy entered bindingInitialized=${::binding.isInitialized}")
        galleryThumbnailJob?.cancel()
        if (::binding.isInitialized) {
            cancelStableSurfaceCameraOpen()
            cancelCachedSurfaceOpenProbe()
        }
        if (::uiRenderScheduler.isInitialized) {
            uiRenderScheduler.cancel()
        }
        healthChipBinders.values.forEach { it.stop() }
        if (activeInstanceRef?.get() === this) {
            activeInstanceRef = null
        }
        super.onDestroy()
    }

    private fun setupHealthIndicators() {
        healthChipBinders = mapOf(
            MonitoredModule.PROV_CORE to HealthChipBinder(binding.chipProvCore),
            MonitoredModule.P010 to HealthChipBinder(binding.chipP010),
            MonitoredModule.LAB to HealthChipBinder(binding.chipLab),
            MonitoredModule.NATIVE to HealthChipBinder(binding.chipNative)
        )
        binding.chipProvCore.setOnClickListener { openHealthDiagnostics(MonitoredModule.PROV_CORE) }
        binding.chipP010.setOnClickListener { openHealthDiagnostics(MonitoredModule.P010) }
        binding.chipLab.setOnClickListener { openHealthDiagnostics(MonitoredModule.LAB) }
        binding.chipNative.setOnClickListener { openHealthDiagnostics(MonitoredModule.NATIVE) }
    }

    private fun renderHealthChips(statuses: Map<MonitoredModule, ModuleHealthStatus>) {
        if (healthChipBinders.isEmpty() || statuses.isEmpty()) return
        if (binding.healthIndicatorRow.visibility != View.VISIBLE) {
            binding.healthIndicatorRow.visibility = View.VISIBLE
        }
        for ((module, binder) in healthChipBinders) {
            statuses[module]?.let(binder::bind)
        }
    }

    private fun openHealthDiagnostics(module: MonitoredModule) {
        if (supportFragmentManager.findFragmentByTag(HealthDiagnosticsDialogFragment.TAG) != null) return
        HealthDiagnosticsDialogFragment.newInstance(module)
            .show(supportFragmentManager, HealthDiagnosticsDialogFragment.TAG)
    }

    private fun enforceRuntimeSecurityGate(): Boolean {
        val app = application as? NeoProCamXApp ?: return false
        // FIX D-10: when async security init is enabled the gate MUST resolve the verdict via the
        // @Synchronized idempotent initialize() (it blocks on the same monitor until the early
        // background tamper/root evaluation finishes and returns the REAL report) — NOT getReport(),
        // whose default ALLOW report would silently pass a not-yet-evaluated tampered/rooted install.
        // Flag OFF keeps the shipped getReport() path (onCreate already ran initialize()).
        val report = if (com.neoproc.neoproccamx.security.AsyncSecurityInitFlag.enabled) {
            app.runtimeSecurityManager.initialize()
        } else {
            app.runtimeSecurityManager.getReport()
        }
        if (!report.shouldBlockStartup) return false
        Log.e(TAG, "Runtime security gate blocked startup reasons=${report.reasons}")
        finishAffinity()
        return true
    }

    private fun canAcceptDebugCommands(): Boolean =
        resumedForDebugCommands && !isFinishing && !isDestroyed

    private fun applyQueuedDebugCommandIfAny() {
        if (hasPendingDebugCommand &&
            (pendingDebugAction == DEBUG_ACTION_RECORD_CLIP ||
                !debugActionExecuted ||
                debugActionScheduled)
        ) return
        dequeueDebugCommand()?.let(::readDebugIntent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            binding.glSurface.post {
                orientationCoordinator.refresh(force = true)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 && (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_POWER)) {
            triggerPrimaryCaptureAction()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.glSurface.post {
            orientationCoordinator.refresh(force = true)
            syncOverlayPreviewArea()
            vm.refreshGeometry()
            // A configuration change (rotation / fold) can reclaim the window's
            // effective extended range without a videoHdrMode diff — re-assert HDR so
            // the preview doesn't return tone-mapped to SDR. Display-side only.
            syncWindowHdrColorMode(forceReassert = true)
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun setupWindow() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        applyPreferredDisplayRefreshRate()
        // Apply the initial HDR-aware window colour mode from the persisted
        // state. The runtime observer in observeState() keeps it in sync as
        // the user toggles HDR / LOG.
        syncWindowHdrColorMode()
    }

    private fun applyPreferredDisplayRefreshRate() {
        val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return

        val bestMode = currentDisplay.supportedModes
            .sortedByDescending { it.refreshRate }
            .firstOrNull { it.refreshRate >= 119f }
            ?: currentDisplay.supportedModes.maxByOrNull { it.refreshRate }
            ?: return

        val attrs = window.attributes
        var changed = false
        if (attrs.preferredDisplayModeId != bestMode.modeId) {
            attrs.preferredDisplayModeId = bestMode.modeId
            changed = true
        }
        if (bestMode.refreshRate > 0f && attrs.preferredRefreshRate != bestMode.refreshRate) {
            attrs.preferredRefreshRate = bestMode.refreshRate
            changed = true
        }
        if (changed) {
            window.attributes = attrs
        }
    }

    /**
     * Resolve the active HDR window colour mode from the current view-model
     * state and push it to the Window. Safe to call from any HDR-affecting
     * setter; idempotent when the mode hasn't changed.
     */
    private fun syncWindowHdrColorMode(forceReassert: Boolean = false) {
        val mode = WindowHdrColorModePolicy.resolveColorMode(
            videoHdrMode = vm.videoHdrMode.value,
            logEnabled = vm.logEnabledFlow.value
        )
        // The forced DEFAULT→HDR bounce re-negotiates the window's extended range.
        // Suppress it while a recording is live so a preview-surface re-negotiation
        // can never perturb the shared preview+encoder repeating request. A live
        // recording already holds a bound HDR window, so re-assertion isn't needed.
        val effectiveForce = forceReassert && !isWindowHdrReassertBlockedByRecording()
        WindowHdrColorModePolicy.apply(window, mode, forceReassert = effectiveForce)
    }

    private fun isWindowHdrReassertBlockedByRecording(): Boolean =
        vm.cameraState.value == CameraState.Recording ||
            vm.gpuRecordState.value is GpuRecordingState.Starting || vm.gpuRecordState.value is GpuRecordingState.Recording ||
            vm.gpuRecordState.value is GpuRecordingState.Paused ||
            vm.gpuRecordState.value is GpuRecordingState.Stopping

    private fun setupInsets() {
        val topControlsPadding = intArrayOf(
            binding.topControlsRow.paddingLeft,
            binding.topControlsRow.paddingTop,
            binding.topControlsRow.paddingRight,
            binding.topControlsRow.paddingBottom
        )
        val topBarPadding = intArrayOf(
            binding.topBarScroll.paddingLeft,
            binding.topBarScroll.paddingTop,
            binding.topBarScroll.paddingRight,
            binding.topBarScroll.paddingBottom
        )
        val bottomBarPadding = intArrayOf(
            binding.bottomBar.paddingLeft,
            binding.bottomBar.paddingTop,
            binding.bottomBar.paddingRight,
            binding.bottomBar.paddingBottom
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topControlsRow.setPadding(
                topControlsPadding[0] + bars.left,
                topControlsPadding[1] + bars.top,
                topControlsPadding[2] + bars.right,
                topControlsPadding[3]
            )

            binding.topBarScroll.setPadding(
                topBarPadding[0] + bars.left,
                topBarPadding[1],
                topBarPadding[2] + bars.right,
                topBarPadding[3]
            )

            binding.bottomBar.setPadding(
                bottomBarPadding[0] + bars.left,
                bottomBarPadding[1],
                bottomBarPadding[2] + bars.right,
                bottomBarPadding[3]
            )

            insets
        }
    }

    private fun setupGestures() {
        // Scale detector for pinch zoom.
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    // RAW/beyond-IMAX mode intentionally hard-locks zoom to 1x (full-sensor readout must
                    // not crop). Previously the pinch was silently ignored; now tell the user why (#27).
                    if (vm.isZoomLocked()) {
                        showTransientZoom(1f)
                        return true
                    }
                    val current = vm.cameraParams.value.zoom
                    val updatedZoom =
                        (current * detector.scaleFactor).coerceIn(vm.camera.minZoom, vm.camera.maxZoom)
                    val now = SystemClock.elapsedRealtime()
                    // Small threshold keeps pinch updates responsive without flooding state changes.
                    if (kotlin.math.abs(updatedZoom - current) < 0.005f && now - lastZoomDispatchMs < 4L) {
                        return true
                    }
                    lastZoomDispatchMs = now
                    vm.setZoom(updatedZoom)
                    showTransientZoom(updatedZoom)
                    return true
                }
            }
        )
        // Preview gestures own camera interaction only; mode rotation is handled by the selector above the shutter.
        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Short animation gives visible double-tap feedback.
                    binding.glSurface.animate()
                        .alpha(0.9f)
                        .setDuration(30L)
                        .withEndAction {
                            binding.glSurface.animate().alpha(1f).setDuration(30L).start()
                        }
                        .start()
                    vm.resetZoom()
                    showTransientZoom(1f)
                    return true
                }

            }
        )

        val holdTolerancePx = resources.displayMetrics.density * FOCUS_HOLD_MOVE_TOLERANCE_DP
        installPreviewTouchListener(holdTolerancePx)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installPreviewTouchListener(holdTolerancePx: Float) {
        val touchListener = View.OnTouchListener { touchedView, event ->
            // Shared preview gesture listener for focus and zoom.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hapticTick()
                    if (featuresMenuVisible) {
                        hideFeatureMenu()
                        return@OnTouchListener true
                    }
                    if (handleExistingFocusUiTouch(event)) {
                        return@OnTouchListener true
                    }
                    scaleDetector.onTouchEvent(event)
                    gestureDetector.onTouchEvent(event)
                    beginTouchFocusGesture(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (handleExistingFocusUiTouch(event)) {
                        return@OnTouchListener true
                    }
                    scaleDetector.onTouchEvent(event)
                    gestureDetector.onTouchEvent(event)
                    continueTouchFocusGesture(event, holdTolerancePx)
                }
                MotionEvent.ACTION_UP -> {
                    if (handleExistingFocusUiTouch(event)) {
                        return@OnTouchListener true
                    }
                    scaleDetector.onTouchEvent(event)
                    gestureDetector.onTouchEvent(event)
                    val tapToFocusHandled = finishTouchFocusGesture(event)
                    if (tapToFocusHandled) touchedView.performClick()
                    return@OnTouchListener true
                }
                MotionEvent.ACTION_CANCEL -> {
                    scaleDetector.onTouchEvent(event)
                    gestureDetector.onTouchEvent(event)
                    cancelTouchFocusGesture()
                }
                else -> {
                    scaleDetector.onTouchEvent(event)
                    gestureDetector.onTouchEvent(event)
                }
            }
            true
        }

        binding.glSurface.setOnTouchListener(touchListener)
        binding.cameraOverlay.setOnTouchListener(touchListener)

        orientationCoordinator = CameraDeviceOrientationCoordinator(this) { update ->
            applyDeviceOrientation(update)
        }
    }

    private fun handleExistingFocusUiTouch(event: MotionEvent): Boolean {
        if (!binding.cameraOverlay.hasFocusUi()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (binding.cameraOverlay.isFocusLockBadgeHit(event.x, event.y)) {
                    binding.cameraOverlay.hideFocusUi()
                    vm.unlockFocusAndExposure()
                    return true
                }
                binding.cameraOverlay.focusEvValueForTouch(event.x, event.y)?.let { value ->
                    evSliderTracking = true
                    applyDisplayExposureValue(value)
                    return true
                }
                if (binding.cameraOverlay.hasLockedFocusUi() &&
                    !binding.cameraOverlay.isFocusUiHit(event.x, event.y)
                ) {
                    binding.cameraOverlay.hideFocusUi()
                    vm.unlockFocusAndExposure()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val evValue = binding.cameraOverlay.focusEvValueForTouch(event.x, event.y)
                if (evSliderTracking || evValue != null) {
                    evSliderTracking = true
                    evValue?.let(::applyDisplayExposureValue)
                    focusHoldJob?.cancel()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (evSliderTracking) {
                    evSliderTracking = false
                    return true
                }
            }
        }
        return false
    }

    // Focus gesture with delayed exposure affordance and hold-to-lock.
    private fun beginTouchFocusGesture(event: MotionEvent) {
        focusDownX = event.x
        focusDownY = event.y
        focusHoldTriggered = false
        cancelFocusGestureJobs()
        
        // The delay separates a simple tap from the exposure adjustment affordance.
        focusAdjustJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            kotlinx.coroutines.delay(FOCUS_EXPOSURE_HOLD_MS)
            if (!scaleDetector.isInProgress && !focusHoldTriggered) {
                binding.cameraOverlay.normalizeTouchPointOrNull(focusDownX, focusDownY)?.let { point ->
                    binding.cameraOverlay.setFocusAssistVisual(FocusAssistVisual.TAP)
                    binding.cameraOverlay.showFocusAt(
                        point.x,
                        point.y,
                        locked = false,
                        showAdjustment = true
                    )
                    syncOverlayFocusExposureValue()
                }
            }
        }
        
        focusHoldJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            kotlinx.coroutines.delay(TouchFocusGesturePolicy.focusLockHoldDelayMs())
            if (!scaleDetector.isInProgress && !evSliderTracking) {
                binding.cameraOverlay.normalizeTouchPointOrNull(focusDownX, focusDownY)?.let { point ->
                    focusHoldTriggered = true
                    binding.cameraOverlay.setFocusAssistVisual(FocusAssistVisual.TAP)
                    binding.cameraOverlay.showFocusAt(
                        point.x,
                        point.y,
                        locked = true,
                        showAdjustment = true
                    )
                    syncOverlayFocusExposureValue()
                    vm.lockFocusAndExposure(point.x, point.y)
                }
            }
        }
    }

    private fun continueTouchFocusGesture(event: MotionEvent, holdTolerancePx: Float) {
        when (
            TouchFocusGesturePolicy.movementEffect(
                deltaX = event.x - focusDownX,
                deltaY = event.y - focusDownY,
                holdTolerancePx = holdTolerancePx,
                isScaling = scaleDetector.isInProgress
            )
        ) {
            FocusTouchMoveEffect.CANCEL_ADJUST_AND_LOCK -> cancelFocusGestureJobs()
            FocusTouchMoveEffect.CANCEL_LOCK_ONLY -> focusHoldJob?.cancel()
            FocusTouchMoveEffect.NONE -> Unit
        }
    }

    private fun cancelTouchFocusGesture() {
        cancelFocusGestureJobs()
        evSliderTracking = false
    }

    private fun finishTouchFocusGesture(event: MotionEvent): Boolean {
        cancelFocusGestureJobs()
        if (evSliderTracking) {
            evSliderTracking = false
            return false
        }
        if (TouchFocusGesturePolicy.shouldIgnoreTapAfterNavigation(
                nowMs = SystemClock.elapsedRealtime(),
                lastGestureNavigateMs = lastGestureNavigateMs
            )
        ) {
            return false
        }
        if (!scaleDetector.isInProgress && !focusHoldTriggered) {
            val point = binding.cameraOverlay.normalizeTouchPointOrNull(event.x, event.y)
                ?: return false
            point.let {
                binding.cameraOverlay.setFocusAssistVisual(FocusAssistVisual.TAP)
                binding.cameraOverlay.showFocusAt(
                    it.x,
                    it.y,
                    locked = false,
                    showAdjustment = true
                )
                syncOverlayFocusExposureValue()
                vm.tapToFocus(it.x, it.y)
            }
            return true
        }
        return false
    }

    private fun cancelFocusGestureJobs() {
        focusAdjustJob?.cancel()
        focusHoldJob?.cancel()
    }

    private fun syncOverlayFocusExposureValue() {
        binding.cameraOverlay.updateFocusExposure(
            vm.cameraParams.value.ev,
            vm.camera.evDisplayRange.lower,
            vm.camera.evDisplayRange.upper
        )
    }

    private fun applyDisplayExposureValue(value: Int) {
        val clamped = TouchFocusGesturePolicy.clampDisplayExposure(
            value = value,
            lower = vm.camera.evDisplayRange.lower,
            upper = vm.camera.evDisplayRange.upper
        )
        if (binding.cameraOverlay.hasLockedFocusUi()) {
            vm.adjustLockedExposureOffset(clamped)
        } else {
            vm.setEV(clamped)
        }
        binding.cameraOverlay.updateFocusExposure(
            clamped,
            vm.camera.evDisplayRange.lower,
            vm.camera.evDisplayRange.upper
        )
    }

    private fun setupPreviewSurface() {
        vm.connectPreviewView(binding.glSurface)
        binding.cameraOverlay.showCropDebug = false
        binding.cameraOverlay.setSubjectAssistModes(
            faceDetectionEnabled = vm.faceDetectionEnabled,
            eyeAutofocusEnabled = vm.eyeAutofocusEnabled
        )
        binding.tvPipelineDebug.visibility = View.GONE
        binding.glSurface.setOnPreviewFpsListener { fps ->
            // The first fps sample means the live preview is rendering — dismiss the cold-start splash now.
            // #15 fix: the battery-optimization exemption is no longer auto-requested on first preview (it
            // used a Play-restricted permission + surprise dialog). FGS + WAKE_LOCK keep recording alive.
            previewReadyForSplash = true
            vm.onPreviewFpsSample(fps)
            maybeEmitPreviewFpsPipelineAlert(fps)
            if (!BuildConfig.DEBUG) return@setOnPreviewFpsListener
            val now = System.currentTimeMillis()
            if (now - lastPreviewFpsLogMs < 1500L) return@setOnPreviewFpsListener
            lastPreviewFpsLogMs = now
            Log.i(
                TAG,
                "previewFps=" +
                    String.format(Locale.US, "%.1f", fps) +
                    " recording=" + (
                        vm.gpuRecordState.value is GpuRecordingState.Recording ||
                            vm.gpuRecordState.value is GpuRecordingState.Paused
                        )
            )
        }
        // ULTRA-FAST surface callback - no runOnUiThread delay
        binding.glSurface.setOnSurfaceReadyListener { surface ->
            Log.i(TAG, "Preview Surface callback surface=$surface valid=${surface?.let { runCatching { it.isValid }.getOrDefault(false) }}")
            if (surface != null) com.neoproc.neoproccamx.utils.BootTrace.mark("preview.surface.ready")
            surfaceReady = surface != null
            vm.onSurfaceReady(surface)
            if (surface != null) {
                // Immediate geometry update
                orientationCoordinator.refresh(force = true)
                vm.refreshGeometry()
                syncOverlayPreviewArea()
                cancelCachedSurfaceOpenProbe()
                // PERF (startup): surfaceChanged fires repeatedly during layout (and the open path's own
                // setFixedSize re-fires it), and each call re-armed the open debounce — pushing the camera
                // open ~600-985ms after the surface was first ready. Latch the open to the Surface
                // INSTANCE: schedule it once per new Surface. Genuine size changes still flow through
                // vm.onSurfaceReady -> bindPreviewSurface (which reconfigures only when actually needed).
                if (cameraOpenSurface !== surface) {
                    cameraOpenSurface = surface
                    Log.i(TAG, "Preview Surface callback scheduling stable camera open")
                    scheduleStableSurfaceCameraOpen(surface)
                }
            } else {
                Log.i(TAG, "Preview Surface callback detached; ViewModel will preserve active recording data outputs")
                cameraOpenSurface = null
                cancelStableSurfaceCameraOpen()
                cancelCachedSurfaceOpenProbe()
            }
        }
        binding.glSurface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncOverlayPreviewArea()
            vm.refreshGeometry()
        }
    }

    // #15 fix: scheduleBatteryOptimizationRequestAfterFirstPreview() removed — the exemption is no longer
    // auto-requested (Play-restricted permission + surprise dialog). BatteryOptimizationExemptionRequester
    // remains available (now the compliant settings-list screen) for an explicit user action if desired.

    private fun setupControls() {
        // Feature toggles with haptic feedback
        binding.btnRaw.setOnClickListener { hapticClick(); vm.dispatch(UiAction.ToggleRaw) }
        binding.btnHdr.setOnClickListener { hapticClick(); vm.dispatch(UiAction.ToggleHdrMode) }
        binding.btnLog.setOnClickListener { hapticClick(); vm.dispatch(UiAction.SetLogEnabled(!vm.isLogEnabled)) }
        // ELITE Rank-2 — long-press LOG engages the one-tap 24p/180° Cinematic bundle (24p + 180° shutter +
        // HYBRID stab + LOG + NDR10+/10-bit, each hardware-gated). Engage-only; releases via auto-exposure.
        binding.btnLog.setOnLongClickListener { hapticClick(); vm.dispatch(UiAction.ApplyCinematicBundle); true }
        binding.btnGrid.setOnClickListener { hapticTick(); vm.dispatch(UiAction.CycleGrid) }
        binding.btnHistogram.setOnClickListener { hapticClick(); vm.dispatch(UiAction.ToggleHist) }
        binding.btnWaveform.setOnClickListener { hapticClick(); vm.dispatch(UiAction.ToggleWaveform) }
        binding.btnZebra.setOnClickListener { hapticClick(); vm.dispatch(UiAction.ToggleZebra) }
        binding.btnPeaking.setOnClickListener { hapticClick(); vm.dispatch(UiAction.TogglePeaking) }

        binding.btnColorProfile.setOnClickListener {
            hapticTick()
            val current = vm.currentColorProfile
            val idx = COLOR_PROFILES.indexOf(current).takeIf { it >= 0 } ?: 0
            val next = COLOR_PROFILES[(idx + 1) % COLOR_PROFILES.size]
            vm.dispatch(UiAction.SetColorProfile(next))
        }

        binding.btnFeatureToggle.setOnClickListener {
            hapticClick()
            vm.dispatch(UiAction.SetAiEnhancementEnabled(!vm.aiEnhancementEnabled))
        }
        binding.btnFeatureToggle.setOnLongClickListener {
            hapticDouble()
            featuresMenuVisible = !featuresMenuVisible
            if (!featuresMenuVisible && !vm.isPeakingEnabled) peakingTrayVisible = false
            applyFeatureMenuVisibility()
            true
        }

        binding.btnPhotoCapture.setOnClickListener { triggerHighQualityPhotoCapture() }

        binding.btnFlash.setOnClickListener {
            hapticClick()
            flashMode = when (flashMode) {
                FlashMode.OFF -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.ON
                FlashMode.ON -> FlashMode.TORCH
                FlashMode.TORCH -> FlashMode.OFF
            }
            vm.setFlash(flashMode)
            renderFlash()
        }

        binding.btnSettings.setOnClickListener {
            handleSettingsSlotAction(binding.btnSettings)
        }
        binding.btnGallery.visibility = View.GONE
        binding.btnSideFocusLock.isClickable = false
        binding.btnSideFocusLock.isFocusable = false

        installModeRotarySwipeControl()
        binding.btnModePro.setOnClickListener {
            handleSettingsSlotAction(binding.btnModePro)
        }
        binding.sbPeakingIntensity.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) vm.dispatch(UiAction.SetPeakingIntensity(progress / 100f))
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            }
        )

        binding.btnShutter.setOnClickListener {
            triggerPrimaryCaptureAction()
        }
        binding.btnShutter.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.shutterCenter.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.shutterPulseRing.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.btnShutter.post {
            val recording = vm.gpuRecordState.value is GpuRecordingState.Recording ||
                vm.gpuRecordState.value is GpuRecordingState.Paused ||
                vm.gpuRecordState.value is GpuRecordingState.Stopping
            updateShutterVisual(recording = recording, animate = false)
        }
    }

    private fun triggerPrimaryCaptureAction() {
        performCameraHaptic(
            if (isRecordingOrStopping()) {
                CameraHapticAction.RECORD_STOP
            } else {
                CameraHapticAction.RECORD_START
            }
        )
        vm.dispatch(UiAction.ToggleRecording)
    }

    private fun triggerHighQualityPhotoCapture() {
        performCameraHaptic(CameraHapticAction.CAPTURE_TRIGGER)
        animateControlPress(binding.btnPhotoCapture)
        if (vm.captureHighQualityPhoto()) {
            binding.btnPhotoCapture.isEnabled = false
            binding.btnPhotoCapture.alpha = 0.62f
        } else {
            performCameraHaptic(CameraHapticAction.REJECTED_ACTION)
            toast(getString(R.string.msg_photo_capture_unavailable))
        }
    }

    private fun handleSettingsSlotAction(view: View) {
        val dockAction = CameraDockButtonStylePolicy.resolve(vm.gpuRecordState.value).settingsAction
        when (dockAction) {
            CameraDockButtonAction.SETTINGS -> {
                hapticClick()
                animateControlPress(view)
                openSettingsSheet()
            }
            CameraDockButtonAction.PAUSE -> {
                performCameraHaptic(CameraHapticAction.RECORD_PAUSE)
                animateControlPress(view)
                vm.dispatch(UiAction.PauseRecording)
            }
            CameraDockButtonAction.RESUME -> {
                performCameraHaptic(CameraHapticAction.RECORD_RESUME)
                animateControlPress(view)
                vm.dispatch(UiAction.ResumeRecording)
            }
        }
    }

    private fun updateShutterVisual(recording: Boolean, animate: Boolean) {
        if (shutterVisualRecording == recording && animate) return
        shutterVisualRecording = recording
        val visualState = CameraRecordingButtonVisualPolicy.resolve(recording)
        binding.shutterCenter.setBackgroundResource(visualState.centerBackgroundRes)
        val drawable = when (val background = binding.shutterCenter.background) {
            is GradientDrawable -> background
            is LayerDrawable -> background.getDrawable(0) as? GradientDrawable
            else -> null
        }
        val idleRadius = binding.shutterCenter.width * 0.5f
        val targetRadius = binding.shutterCenter.width * visualState.cornerRadiusRatio
        val targetScale = visualState.centerScale
        val targetAlpha = visualState.centerAlpha
        binding.shutterOuterRing.visibility = if (visualState.outerRingVisible) View.VISIBLE else View.GONE
        binding.shutterInnerRing.visibility = if (visualState.outerRingVisible) View.VISIBLE else View.GONE
        binding.shutterOuterRing.alpha = visualState.outerRingAlpha
        binding.shutterInnerRing.alpha = visualState.outerRingAlpha

        if (!animate) {
            drawable?.cornerRadius = targetRadius
            shutterCornerRadiusPx = targetRadius
            binding.shutterCenter.scaleX = targetScale
            binding.shutterCenter.scaleY = targetScale
            binding.shutterCenter.alpha = targetAlpha
            if (visualState.pulseVisible) {
                startShutterPulseAnimation()
            } else {
                stopShutterPulseAnimation()
            }
            return
        }

        shutterMorphAnimator?.cancel()
        val startRadius = if (shutterCornerRadiusPx > 0f) shutterCornerRadiusPx else idleRadius
        val motion = CameraMotionPolicy.shutterMorphMotion()
        shutterMorphAnimator = ValueAnimator.ofFloat(startRadius, targetRadius).apply {
            duration = motion.durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val radius = animator.animatedValue as Float
                drawable?.cornerRadius = radius
                shutterCornerRadiusPx = radius
            }
            start()
        }
        binding.shutterCenter.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .alpha(targetAlpha)
            .setDuration(motion.durationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
        if (visualState.pulseVisible) {
            startShutterPulseAnimation()
        } else {
            stopShutterPulseAnimation()
        }
    }

    private fun animateControlPress(view: View) {
        val motion = CameraMotionPolicy.controlPressMotion()
        view.animate().cancel()
        view.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .alpha(0.82f)
            .setDuration(motion.durationMs / 2L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(motion.durationMs)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun startShutterPulseAnimation() {
        binding.shutterPulseRing.animate().cancel()
        binding.shutterPulseRing.visibility = View.VISIBLE
        binding.shutterPulseRing.scaleX = 0.88f
        binding.shutterPulseRing.scaleY = 0.88f
        binding.shutterPulseRing.alpha = 0.82f
        binding.shutterPulseRing.animate()
            .scaleX(1.18f)
            .scaleY(1.18f)
            .alpha(0f)
            .setDuration(1150L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (shutterVisualRecording) {
                    startShutterPulseAnimation()
                }
            }
            .start()
    }

    private fun stopShutterPulseAnimation() {
        binding.shutterPulseRing.animate().cancel()
        binding.shutterPulseRing.visibility = View.GONE
        binding.shutterPulseRing.alpha = 0f
        binding.shutterPulseRing.scaleX = 1f
        binding.shutterPulseRing.scaleY = 1f
    }

    private fun setupUiPolish() {
        listOf(
            binding.btnFlash,
            binding.btnHdr,
            binding.btnFeatureToggle,
            binding.btnRaw,
            binding.btnLog,
            binding.btnGrid,
            binding.btnHistogram,
            binding.btnWaveform,
            binding.btnZebra,
            binding.btnPeaking,
            binding.btnColorProfile,
            binding.btnPhotoCapture,
            binding.btnSettings,
            binding.btnModePro,
            binding.btnModeVideoRail,
            binding.btnModeProRail,
            binding.btnModeHdrScene,
            binding.btnModeLogVideo,
            binding.btnModeRawPreset,
            binding.btnShutter
        ).forEach { installPressFeedback(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installModeRotarySwipeControl() {
        val minimumDistancePx = dp(MODE_ROTARY_SWIPE_DISTANCE_DP)
        binding.modeRailScroll.setOnClickListener { }
        binding.modeRailScroll.setOnTouchListener { view, event ->
            if (!view.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    modeSelectorTouchDownX = event.x
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val step = modeSelectorTouchDownX?.let { downX ->
                        CameraModeRotarySwipePolicy.resolveStep(downX, event.x, minimumDistancePx)
                    } ?: 0
                    modeSelectorTouchDownX = null
                    if (step != 0) {
                        lastGestureNavigateMs = SystemClock.elapsedRealtime()
                        performCameraHaptic(CameraHapticAction.MODE_SWITCH)
                        postModePresetShift(step)
                    } else {
                        val preset = modePresetAtRailTouch(event.x, event.y)
                        if (preset != null && preset != activeModePreset) {
                            lastGestureNavigateMs = SystemClock.elapsedRealtime()
                            performCameraHaptic(CameraHapticAction.MODE_SWITCH)
                            postModePresetActivation(preset)
                        } else {
                            view.performClick()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    modeSelectorTouchDownX = null
                    true
                }
                else -> true
            }
        }
    }

    private fun postModePresetShift(delta: Int) {
        binding.modeRailScroll.post {
            if (!binding.modeRailScroll.isEnabled) return@post
            shiftModePreset(delta)
        }
    }

    private fun postModePresetActivation(preset: CameraModePreset) {
        binding.modeRailScroll.post {
            if (!binding.modeRailScroll.isEnabled || activeModePreset == preset) return@post
            activateModePreset(preset)
        }
    }

    private fun modePresetAtRailTouch(x: Float, y: Float): CameraModePreset? {
        val contentLeft = binding.modeRailContent.left
        val contentTop = binding.modeRailContent.top
        return visibleModeViews()
            .firstOrNull { (view, _) ->
                view.isVisible &&
                    x >= contentLeft + view.left &&
                    x <= contentLeft + view.right &&
                    y >= contentTop + view.top &&
                    y <= contentTop + view.bottom
            }
            ?.second
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installPressFeedback(
        view: View,
        pressedScale: Float = CameraMotionPolicy.pressMotion().pressedScale
    ) {
        val motion = CameraMotionPolicy.pressMotion()
        // Non-consuming listener only animates press state; existing click listeners own click semantics.
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(motion.durationMs)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(motion.durationMs)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }

    private fun animatePanelVisibility(
        view: View,
        visible: Boolean,
        fromTop: Boolean = false,
        settledTranslationY: Float = 0f
    ) {
        val motion = CameraMotionPolicy.panelMotion(fromTop)
        val offset = dp(motion.offsetDp)
        if (!view.isLaidOut) {
            view.visibility = if (visible) View.VISIBLE else View.GONE
            view.alpha = if (visible) 1f else 0f
            view.translationY = settledTranslationY
            return
        }
        if (visible) {
            if (view.isVisible && view.alpha > 0.98f) return
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = settledTranslationY + offset
            view.animate()
                .alpha(1f)
                .translationY(settledTranslationY)
                .setDuration(motion.showDurationMs)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            if (view.visibility != View.VISIBLE) return
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .translationY(settledTranslationY + offset * 0.45f)
                .setDuration(motion.hideDurationMs)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    view.translationY = settledTranslationY
                }
                .start()
        }
    }

    private fun setupManualControls() {
        if (supportFragmentManager.findFragmentByTag(MANUAL_TAG) == null) {
            if (supportFragmentManager.isStateSaved) {
                manualControlsSetupPending = true
                return
            }
            supportFragmentManager.commit {
                replace(binding.manualContainer.id, ManualControlsFragment(), MANUAL_TAG)
            }
        }
    }

    private fun observeState() {
        observersJob?.cancel()
        observersJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Runtime health monitor: sample live pipeline signals every ~150 ms, evaluate the
                    // four module probes, drive recovery, and render the chips. Auto-suspends when the
                    // activity leaves STARTED (repeatOnLifecycle), so it never runs off-screen.
                    while (isActive) {
                        val statuses = healthMonitor.sample(
                            vm.buildHealthSnapshot(SystemClock.elapsedRealtime())
                        )
                        renderHealthChips(statuses)
                        kotlinx.coroutines.delay(150L)
                    }
                }
                launch {
                    vm.frameGeometry.collect { geometry ->
                        renderFrameGeometryDebug(geometry)
                        syncOverlayPreviewArea()
                        refreshSafeAreaOverlays()
                    }
                }
                launch {
                    vm.safeAreaGuidesFlow.collect { refreshSafeAreaOverlays() }
                }
                launch {
                    vm.zebraMask.collect { mask ->
                        pendingZebraMaskTelemetry = mask
                        hasPendingZebraMaskTelemetry = true
                        telemetryVsyncGate.requestFrame()
                    }
                }
                launch {
                    vm.subjectMask.collect { mask ->
                        pendingSubjectMaskTelemetry = mask
                        hasPendingSubjectMaskTelemetry = true
                        telemetryVsyncGate.requestFrame()
                    }
                }
                launch {
                    vm.peakingMask.collect { mask ->
                        pendingPeakingMaskTelemetry = mask
                        hasPendingPeakingMaskTelemetry = true
                        telemetryVsyncGate.requestFrame()
                    }
                }
                launch {
                    vm.previewFrameTimestamps.collect { timestampNs ->
                        binding.glSurface.notifyFramePresented(timestampNs)
                    }
                }
                launch {
                    vm.liveResult.collect { result ->
                        pendingLiveResultTelemetry = result
                        hasPendingLiveResultTelemetry = true
                        telemetryVsyncGate.requestFrame()
                    }
                }
                launch {
                    vm.cameraParams.collect { params ->
                        pendingCameraParamsTelemetry = params
                        telemetryVsyncGate.requestFrame()
                        updateZoomToggleLabel(params.zoom)
                    }
                }
                launch {
                    vm.histogram.collect { hist ->
                        pendingHistogramTelemetry = hist
                        telemetryVsyncGate.requestFrame()
                    }
                }
                launch {
                    vm.waveformColumns.collect { columns ->
                        pendingWaveformTelemetry = columns
                        telemetryVsyncGate.requestFrame()
                    }
                }
                launch {
                    vm.faces.collect { faces ->
                        pendingFacesTelemetry = faces
                        telemetryVsyncGate.requestFrame()
                    }
                }
                launch {
                    vm.focusAssistVisual.collect { visual ->
                        binding.cameraOverlay.setSubjectAssistModes(
                            faceDetectionEnabled = vm.faceDetectionEnabled,
                            eyeAutofocusEnabled = vm.eyeAutofocusEnabled
                        )
                        binding.cameraOverlay.setFocusAssistVisual(visual)
                        renderFocusAssistControl(visual)
                        maybeEmitAiTargetLockHaptic(visual)
                    }
                }
                launch {
                    vm.recordingSavedPath.collect { savedPath ->
                        showVideoSavedWatermark("Video saved")
                        refreshGalleryThumbnail(preferredPath = savedPath, forceReload = true)
                    }
                }
                launch {
                    vm.captureEvents.collect { event ->
                        when (event) {
                            CaptureEvent.Started -> Unit
                            is CaptureEvent.Progress -> Unit
                            is CaptureEvent.Saved -> {
                                binding.btnPhotoCapture.isEnabled = true
                                binding.btnPhotoCapture.alpha = 1f
                                showVideoSavedWatermark(getString(R.string.msg_saved_photo))
                                refreshGalleryThumbnail(forceReload = true)
                            }
                            is CaptureEvent.RawSaved -> Unit
                            is CaptureEvent.Error -> {
                                binding.btnPhotoCapture.isEnabled = true
                                binding.btnPhotoCapture.alpha = 1f
                                toast(event.msg)
                            }
                        }
                    }
                }
                launch {
                    vm.uiState.collect { nextState ->
                        renderState(lastState, nextState)
                        lastState = nextState
                        firstStateRender = false
                    }
                }
                launch {
                    vm.uncompressedCinemaPipelineEnabled.collect {
                        // Processed modes use NPV10 while RAW continues through NPRAW.
                        renderFeatureVisibility(vm.uiMode.value)
                        renderPresetSelection()
                    }
                }
                launch {
                    vm.recDuration.collect { duration ->
                        updateRecordingTimerIfVisible(duration)
                    }
                }
                launch {
                    vm.cinemaRuntimeStats.sample(STATUS_CHROME_UPDATE_SAMPLE_MS).collect {
                        if (it.dropEstimate > 0 || (it.remainingMinutes != null && it.remainingMinutes <= 1)) {
                            performCameraHaptic(CameraHapticAction.CRITICAL_PIPELINE_ALERT)
                        }
                        renderStatusChrome()
                    }
                }
                launch {
                    vm.cameraState.collect { state ->
                        when (state) {
                            CameraState.Streaming -> maybeRunDebugAutomation()
                            is CameraState.Error -> toast(state.msg)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun renderState(prev: CameraUiState, next: CameraUiState) {
        if (CameraUiStateDiffPolicy.objectChanged(prev.uiMode, next.uiMode)) {
            renderMode(next.uiMode)
            syncOverlayPreviewArea()
            renderFeatureVisibility(next.uiMode)
        }
        if (CameraUiStateDiffPolicy.booleanChanged(prev.manualControlsVisible, next.manualControlsVisible)) {
            animatePanelVisibility(binding.manualContainer, next.manualControlsVisible)
            syncPresetFromState()
            renderStatusChrome()
            renderFeatureVisibility(next.uiMode)
        }
        if (CameraUiStateDiffPolicy.booleanChanged(prev.rawEnabled, next.rawEnabled)) {
            binding.btnRaw.isSelected = next.rawEnabled
            syncPresetFromState()
            renderStatusChrome()
        }
        if (CameraUiStateDiffPolicy.booleanChanged(prev.logEnabled, next.logEnabled)) {
            binding.btnSideLog.isSelected = next.logEnabled
            syncPresetFromState()
            renderStatusChrome()
            syncWindowHdrColorMode()
        }
        if (CameraUiStateDiffPolicy.objectChanged(prev.videoHdrMode, next.videoHdrMode)) {
            syncPresetFromState()
            syncWindowHdrColorMode()
        }
        if (CameraUiStateDiffPolicy.objectChanged(prev.colorProfile, next.colorProfile)) {
            binding.btnSideColorProfile.text = next.colorProfile.take(4).uppercase(Locale.US)
            renderStatusChrome()
        }
        if (CameraUiStateDiffPolicy.booleanChanged(prev.aiEnhancement, next.aiEnhancement)) {
            binding.btnFeatureToggle.isSelected = next.aiEnhancement
            applyFeatureMenuVisibility()
        }
        if (CameraUiStateDiffPolicy.shouldApplyBoolean(firstStateRender, prev.showHistogram, next.showHistogram)) {
            binding.btnHistogram.isSelected = next.showHistogram
            binding.cameraOverlay.showHistogram = next.showHistogram
            binding.cameraOverlay.invalidate()
            renderStatusChrome()
        }
        if (CameraUiStateDiffPolicy.shouldApplyBoolean(firstStateRender, prev.showWaveform, next.showWaveform)) {
            binding.btnWaveform.isSelected = next.showWaveform
            animatePanelVisibility(binding.waveformView, next.showWaveform, fromTop = true)
            renderStatusChrome()
        }
        if (CameraUiStateDiffPolicy.shouldApplyBoolean(firstStateRender, prev.showZebra, next.showZebra)) {
            binding.btnZebra.isSelected = next.showZebra
            binding.glSurface.setZebraEnabled(next.showZebra)
            binding.cameraOverlay.setZebraMask(if (next.showZebra) vm.zebraMask.value else null)
            renderStatusChrome()
        }
        if (CameraUiStateDiffPolicy.floatChanged(prev.zebraThreshold, next.zebraThreshold)) {
            binding.glSurface.setZebraThreshold(next.zebraThreshold)
        }
        if (CameraUiStateDiffPolicy.shouldApplyBoolean(firstStateRender, prev.peakingEnabled, next.peakingEnabled)) {
            binding.btnPeaking.isSelected = next.peakingEnabled
            binding.glSurface.setPeakingEnabled(next.peakingEnabled)
            binding.cameraOverlay.setSubjectMask(
                if (next.peakingEnabled && shouldShowAutoSubjectMask()) vm.subjectMask.value else null
            )
            binding.cameraOverlay.setFocusPeakingMask(if (next.peakingEnabled) vm.peakingMask.value else null)
            peakingTrayVisible = next.peakingEnabled
            applyFeatureMenuVisibility()
            renderStatusChrome()
        }
        if (CameraUiStateDiffPolicy.floatChanged(prev.peakingIntensity, next.peakingIntensity)) {
            binding.glSurface.setPeakingIntensity(next.peakingIntensity)
            binding.cameraOverlay.setSubjectMaskLevel(next.peakingIntensity)
            val progress = (next.peakingIntensity * 100f).toInt().coerceIn(0, 100)
            if (binding.sbPeakingIntensity.progress != progress) {
                binding.sbPeakingIntensity.progress = progress
            }
        }
        if (CameraUiStateDiffPolicy.objectChanged(prev.gridMode, next.gridMode)) {
            binding.btnGrid.isSelected = next.gridMode.name != "NONE"
            binding.cameraOverlay.gridMode = next.gridMode
            binding.cameraOverlay.invalidate()
        }
        if (CameraUiStateDiffPolicy.objectChanged(prev.previewAspect, next.previewAspect)) {
            renderAspect(next.previewAspect)
            syncOverlayPreviewArea()
            renderStatusChrome()
        }
        if (CameraUiStateDiffPolicy.objectChanged(prev.gpuRecordState, next.gpuRecordState)) {
            val recording = next.gpuRecordState is GpuRecordingState.Starting || next.gpuRecordState is GpuRecordingState.Recording ||
                next.gpuRecordState is GpuRecordingState.Paused ||
                next.gpuRecordState is GpuRecordingState.Stopping
            val recordingLifecycleActive = recording ||
                next.gpuRecordState is GpuRecordingState.Finalizing
            binding.cameraOverlay.setRecording(recording)
            setControlsVisible(!recordingLifecycleActive)
            updateShutterVisual(recording = recording, animate = true)
            lastRecordingTimerText = null
            if (recording) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            if (next.gpuRecordState is GpuRecordingState.Error) {
                performCameraHaptic(CameraHapticAction.CRITICAL_PIPELINE_ALERT)
                toast(next.gpuRecordState.message)
            }
            renderStatusChrome()
        }

        val warning = TopWarningChipPolicy.explicitDisplayWarning(
            hardwareWarning = next.hardwareWarning,
            shutterGuidance = next.shutterGuidance
        )
        val warningChanged = CameraUiStateDiffPolicy.objectChanged(
            prev.hardwareWarning to prev.shutterGuidance,
            next.hardwareWarning to next.shutterGuidance
        )
        if (warningChanged) {
            val warningDecision = UiWarningToastPolicy.resolve(
                nextWarning = warning,
                lastShownWarning = lastShownUiWarning
            )
            lastShownUiWarning = warningDecision.lastShownWarning
            if (warningDecision.showToast && !warning.isNullOrBlank()) {
                toast(warning)
            }
            renderTopWarningChip(next)
        }
        val thermalAlert = next.thermalThrottled ||
            next.thermalTier == HighEndRuntimeTier.RECOVERY ||
            next.thermalTier == HighEndRuntimeTier.BASIC
        val previousThermalAlert = prev.thermalThrottled ||
            prev.thermalTier == HighEndRuntimeTier.RECOVERY ||
            prev.thermalTier == HighEndRuntimeTier.BASIC
        if (CameraUiStateDiffPolicy.booleanChanged(previousThermalAlert, thermalAlert) && thermalAlert) {
            performCameraHaptic(CameraHapticAction.CRITICAL_PIPELINE_ALERT)
        }
        binding.thermalWarningOverlay.hide()
        applyFeatureMenuVisibility()
    }

    private fun flushTelemetryOnVSync() {
        if (hasPendingZebraMaskTelemetry) {
            binding.cameraOverlay.setZebraMask(
                if (vm.isZebraEnabled) pendingZebraMaskTelemetry else null
            )
            pendingZebraMaskTelemetry = null
            hasPendingZebraMaskTelemetry = false
        }
        if (hasPendingSubjectMaskTelemetry) {
            binding.cameraOverlay.setSubjectMask(
                if (vm.isPeakingEnabled && shouldShowAutoSubjectMask()) pendingSubjectMaskTelemetry else null
            )
            pendingSubjectMaskTelemetry = null
            hasPendingSubjectMaskTelemetry = false
        }
        if (hasPendingPeakingMaskTelemetry) {
            binding.cameraOverlay.setFocusPeakingMask(
                if (vm.isPeakingEnabled) pendingPeakingMaskTelemetry else null
            )
            pendingPeakingMaskTelemetry = null
            hasPendingPeakingMaskTelemetry = false
        }
        if (hasPendingLiveResultTelemetry) {
            val result = pendingLiveResultTelemetry
            latestLiveResult = result
            binding.cameraOverlay.setAFFocused(result?.afFocused == true)
            binding.cameraOverlay.setHardwareProfile(vm.camera.activeProfileName)
            pendingLiveResultTelemetry = null
            hasPendingLiveResultTelemetry = false
        }
        pendingCameraParamsTelemetry?.let { params ->
            binding.cameraOverlay.updateFocusExposure(
                params.ev,
                vm.camera.evDisplayRange.lower,
                vm.camera.evDisplayRange.upper
            )
        }
        pendingFacesTelemetry?.let { faces ->
            binding.cameraOverlay.setSubjectAssistModes(
                faceDetectionEnabled = vm.faceDetectionEnabled,
                eyeAutofocusEnabled = vm.eyeAutofocusEnabled
            )
            if (CameraUiStateDiffPolicy.listChanged(renderedFacesTelemetry, faces)) {
                binding.cameraOverlay.setFaces(faces)
                renderedFacesTelemetry = faces
            }
        }
        pendingHistogramTelemetry?.let { histogram ->
            if (CameraUiStateDiffPolicy.intArrayChanged(renderedHistogramTelemetry, histogram)) {
                binding.cameraOverlay.setHistogram(histogram)
                renderedHistogramTelemetry = histogram
            }
        }
        pendingWaveformTelemetry?.let { waveform ->
            if (CameraUiStateDiffPolicy.intArrayChanged(renderedWaveformTelemetry, waveform)) {
                binding.waveformView.updateFromColumnAverages(waveform)
                renderedWaveformTelemetry = waveform
            }
        }
        pendingCameraParamsTelemetry = null
        pendingFacesTelemetry = null
        pendingHistogramTelemetry = null
        pendingWaveformTelemetry = null
    }

    private fun maybeOpenAdvancedCamera(fromForegroundResume: Boolean = false) {
        if (!ensurePreviewSurfaceReady()) {
            scheduleCachedSurfaceOpenProbe()
            return
        }
        val shouldRunForegroundRecovery =
            fromForegroundResume || foregroundResumeCameraRecoveryPending
        if (shouldRunForegroundRecovery) {
            foregroundResumeCameraRecoveryPending = false
            vm.resumeCameraForForeground()
        } else {
            vm.openCamera()
        }
    }

    private fun ensurePreviewSurfaceReady(): Boolean {
        if (!::binding.isInitialized) return false
        val previewSurface = resolvePreviewSurfaceForCameraOpen()
        if (previewSurface == null) {
            if (!surfaceReady) {
                Log.i(TAG, "Camera open waiting for stable SurfaceHolder callback")
            }
            return false
        }
        val validSurface = runCatching { previewSurface.isValid }.getOrDefault(false)
        val viewHasSize = binding.glSurface.width > 0 && binding.glSurface.height > 0
        val viewAttached = binding.glSurface.isAttachedToWindow
        if (!PreviewSurfaceStartupPolicy.canUseCachedSurfaceForOpen(
                surfaceCallbackDelivered = surfaceReady,
                surfaceValid = validSurface,
                viewAttached = viewAttached,
                viewHasSize = viewHasSize
            )
        ) {
            Log.i(TAG, "Camera open waiting for stable SurfaceHolder callback")
            return false
        }
        if (!validSurface) return false
        if (!surfaceReady) {
            Log.i(TAG, "Camera open recovered valid cached holder surface after layout")
        }
        surfaceReady = true
        vm.onSurfaceReady(previewSurface)
        orientationCoordinator.refresh(force = true)
        vm.refreshGeometry()
        syncOverlayPreviewArea()
        return true
    }

    private fun resolvePreviewSurfaceForCameraOpen(): Surface? {
        binding.glSurface.getPreviewSurface()?.let { return it }
        val callbackSurface = cameraOpenSurface ?: return null
        val callbackSurfaceValid = runCatching { callbackSurface.isValid }.getOrDefault(false)
        if (!PreviewSurfaceStartupPolicy.canUseCallbackSurfaceWhenHolderCacheMissing(
                surfaceCallbackDelivered = surfaceReady,
                callbackSurfaceValid = callbackSurfaceValid
            )
        ) {
            return null
        }
        Log.i(TAG, "Camera open using callback Surface while holder cache is temporarily empty")
        return callbackSurface
    }

    private fun scheduleStableSurfaceCameraOpen(surface: Surface) {
        val validSurface = runCatching { surface.isValid }.getOrDefault(false)
        if (!PreviewSurfaceStartupPolicy.shouldScheduleOpenFromSurfaceCallback(
                surfaceValid = validSurface,
                hasCameraPermission = hasPermission(Manifest.permission.CAMERA)
            )
        ) {
            Log.i(TAG, "Camera open stable surface not scheduled valid=$validSurface")
            return
        }
        pendingStablePreviewSurface = surface
        Log.i(TAG, "Camera open stable surface scheduled valid=$validSurface")
        mainHandler.removeCallbacks(stableSurfaceOpenRunnable)
        mainHandler.postDelayed(
            stableSurfaceOpenRunnable,
            PreviewSurfaceStartupPolicy.STABLE_SURFACE_OPEN_DELAY_MS
        )
    }

    private fun cancelStableSurfaceCameraOpen() {
        pendingStablePreviewSurface = null
        mainHandler.removeCallbacks(stableSurfaceOpenRunnable)
    }

    private fun scheduleCachedSurfaceOpenProbe() {
        if (!::binding.isInitialized || !hasPermission(Manifest.permission.CAMERA)) {
            Log.i(TAG, "Camera open cached holder probe not scheduled: binding/permission unavailable")
            return
        }
        if (!PreviewSurfaceStartupPolicy.shouldScheduleCachedSurfaceProbe(
                cachedSurfaceOpenProbeAttempts,
                cachedSurfaceOpenProbeArmed
            )
        ) {
            Log.i(TAG, "Camera open cached holder probe not scheduled: attempts=$cachedSurfaceOpenProbeAttempts armed=$cachedSurfaceOpenProbeArmed")
            return
        }
        mainHandler.removeCallbacks(cachedSurfaceOpenProbeRunnable)
        cachedSurfaceOpenProbeArmed = true
        Log.i(TAG, "Camera open cached holder probe scheduled attempts=$cachedSurfaceOpenProbeAttempts")
        mainHandler.postDelayed(
            cachedSurfaceOpenProbeRunnable,
            PreviewSurfaceStartupPolicy.CACHED_SURFACE_PROBE_DELAY_MS
        )
    }

    private fun cancelCachedSurfaceOpenProbe() {
        Log.i(TAG, "Camera open cached holder probe cancelled attempts=$cachedSurfaceOpenProbeAttempts armed=$cachedSurfaceOpenProbeArmed")
        cachedSurfaceOpenProbeArmed = false
        cachedSurfaceOpenProbeAttempts = 0
        mainHandler.removeCallbacks(cachedSurfaceOpenProbeRunnable)
    }

    private fun probeCachedPreviewSurfaceAfterLayout() {
        cachedSurfaceOpenProbeArmed = false
        Log.i(TAG, "Camera open cached holder probe running attempts=$cachedSurfaceOpenProbeAttempts surfaceReady=$surfaceReady")
        if (surfaceReady || !hasPermission(Manifest.permission.CAMERA)) {
            cachedSurfaceOpenProbeAttempts = 0
            return
        }
        cachedSurfaceOpenProbeAttempts += 1
        val previewSurface = binding.glSurface.getPreviewSurface()
        if (previewSurface == null) {
            retryCachedSurfaceOpenProbe("holder surface missing")
            return
        }
        val validSurface = runCatching { previewSurface.isValid }.getOrDefault(false)
        val viewHasSize = binding.glSurface.width > 0 && binding.glSurface.height > 0
        val viewAttached = binding.glSurface.isAttachedToWindow
        if (!PreviewSurfaceStartupPolicy.canProbeCachedSurfaceAfterLayout(
                surfaceCallbackDelivered = surfaceReady,
                surfaceValid = validSurface,
                viewAttached = viewAttached,
                viewHasSize = viewHasSize
            )
        ) {
            Log.i(TAG, "Camera open probe skipped: preview surface not layout-stable")
            retryCachedSurfaceOpenProbe("surface not layout-stable")
            return
        }
        Log.i(TAG, "Camera open using delayed stable holder surface probe")
        cachedSurfaceOpenProbeAttempts = 0
        surfaceReady = true
        vm.onSurfaceReady(previewSurface)
        orientationCoordinator.refresh(force = true)
        vm.refreshGeometry()
        syncOverlayPreviewArea()
        scheduleStableSurfaceCameraOpen(previewSurface)
    }

    private fun retryCachedSurfaceOpenProbe(reason: String) {
        if (!PreviewSurfaceStartupPolicy.shouldScheduleCachedSurfaceProbe(
                cachedSurfaceOpenProbeAttempts,
                cachedSurfaceOpenProbeArmed
            )
        ) {
            Log.w(TAG, "Camera open cached holder probe exhausted: $reason")
            return
        }
        Log.i(TAG, "Camera open cached holder probe retry $cachedSurfaceOpenProbeAttempts/" +
            "${PreviewSurfaceStartupPolicy.CACHED_SURFACE_PROBE_MAX_ATTEMPTS}: $reason")
        scheduleCachedSurfaceOpenProbe()
    }

    private fun openFromStablePreviewSurface() {
        val surface = pendingStablePreviewSurface ?: return
        val activeSurface = resolvePreviewSurfaceForCameraOpen()
        val stableSurface =
            surfaceReady &&
                activeSurface === surface &&
                runCatching { surface.isValid }.getOrDefault(false)
        if (!stableSurface) {
            val activeSurfaceValid = activeSurface?.let {
                runCatching { it.isValid }.getOrDefault(false)
            } ?: false
            if (activeSurface != null && activeSurfaceValid) {
                Log.i(TAG, "Camera open recovered current holder surface after stale stability gate")
                pendingStablePreviewSurface = null
                cameraOpenSurface = activeSurface
                surfaceReady = true
                vm.onSurfaceReady(activeSurface)
                orientationCoordinator.refresh(force = true)
                vm.refreshGeometry()
                syncOverlayPreviewArea()
                maybeOpenAdvancedCamera()
                return
            }
            Log.i(TAG, "Camera open skipped: preview surface changed before stability gate surfaceReady=$surfaceReady activeSame=${activeSurface === surface}")
            scheduleCachedSurfaceOpenProbe()
            return
        }
        pendingStablePreviewSurface = null
        Log.i(TAG, "Camera open stable surface accepted")
        maybeOpenAdvancedCamera()
    }

    private fun enqueueDebugIntentIfPresent(intent: Intent?) {
        if (!BuildConfig.DEBUG ||
            intent == null ||
            !DebugCommandIntentReader.isDebugCommandIntent(intent)
        ) {
            return
        }
        val shouldQueueDirectIntent = synchronized(pendingDebugCommandLock) {
            pendingDebugCommandIntents.isEmpty()
        }
        if (shouldQueueDirectIntent) {
            queueDebugCommand(intent)
        }
    }

    private fun readDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) {
            Log.i(TAG, "readDebugIntent ignored empty debug intent; preserving pending automation state")
            return
        }
        val hasDebugExtras = DebugCommandIntentReader.hasDebugCommandExtras(intent)
        if (!hasDebugExtras) {
            Log.i(
                TAG,
                "readDebugIntent ignored DEBUG_COMMAND without automation extras; " +
                    "preserving pending automation state intent=$intent"
            )
            return
        }
        if (!DebugCommandIntentReader.isDebugCommandAction(intent.action)) {
            Log.i(
                TAG,
                "readDebugIntent ignored non-debug action=${intent.action}; " +
                    "preserving pending automation state intent=$intent"
            )
            return
        }
        DebugRuntimeFlagApplier.apply(intent)
        val parsed = DebugCommandIntentReader.readParsedCommand(
            intent = intent,
            normalizeColorProfile = ::normalizeDebugColorProfile,
            normalizeLutProfile = ::normalizeDebugLutProfile
        )
        if (parsed == null) {
            Log.i(
                TAG,
                "readDebugIntent ignored intent without parsed debug command; " +
                    "preserving pending automation state intent=$intent"
            )
            return
        }
        pendingDebugMode = parsed.mode
        pendingDebugPreset = parsed.preset
        pendingDebugAction = parsed.action
        pendingDebugDurationMs = parsed.durationMs
        pendingDebugRaw = parsed.raw
        pendingDebugLog = parsed.log
        pendingDebugAspect = parsed.aspect
        pendingDebugColorProfile = parsed.colorProfile
        pendingDebugLutEnabled = parsed.lutEnabled
        pendingDebugLutProfile = parsed.lutProfile
        pendingDebugValidationLabel = parsed.validationLabel
        pendingDebugVideoColorSpace = parsed.videoColorSpace
        pendingDebugVideoHdrMode = parsed.videoHdrMode
        pendingDebugVideoResolution = parsed.videoResolution
        pendingDebugVideoFps = parsed.videoFps
        pendingDebugVideoMode = parsed.videoMode
        pendingDebugVideoDetailProfile = parsed.videoDetailProfile
        pendingDebugRawCompression = parsed.rawCompression
        pendingDebugUncompressedCinemaPipeline = parsed.uncompressedCinemaPipeline
        pendingDebugRecordClipToken = if (parsed.action == DEBUG_ACTION_RECORD_CLIP) {
            debugRecordClipCoordinator.beginCommand()
        } else {
            debugRecordClipCoordinator.cancelCurrent()
            null
        }
        hasPendingDebugCommand = true
        debugActionExecuted = false
        debugActionScheduled = false
        debugConfigAppliedForPendingCommand = false
        resetDebugAutomationRetryState()
        Log.i(
            TAG,
            "readDebugIntent mode=$pendingDebugMode preset=$pendingDebugPreset action=$pendingDebugAction durationMs=$pendingDebugDurationMs " +
                "raw=$pendingDebugRaw log=$pendingDebugLog aspect=$pendingDebugAspect " +
                "colorProfile=$pendingDebugColorProfile " +
                "lutEnabled=$pendingDebugLutEnabled lutProfile=$pendingDebugLutProfile " +
                "validationLabel=$pendingDebugValidationLabel " +
                "colorSpace=$pendingDebugVideoColorSpace " +
                "hdrMode=$pendingDebugVideoHdrMode resolution=$pendingDebugVideoResolution " +
                "fps=$pendingDebugVideoFps " +
                "videoMode=$pendingDebugVideoMode detailProfile=$pendingDebugVideoDetailProfile " +
                "compression=$pendingDebugRawCompression " +
                "uncompressedCinemaPipeline=$pendingDebugUncompressedCinemaPipeline"
        )
    }

    private fun clearPendingDebugCommandState() {
        pendingDebugMode = null
        pendingDebugPreset = null
        pendingDebugAction = null
        pendingDebugDurationMs = DebugCommandParser.DEFAULT_DEBUG_DURATION_MS
        pendingDebugRaw = null
        pendingDebugLog = null
        pendingDebugAspect = null
        pendingDebugColorProfile = null
        pendingDebugLutEnabled = null
        pendingDebugLutProfile = null
        pendingDebugValidationLabel = null
        pendingDebugVideoColorSpace = null
        pendingDebugVideoHdrMode = null
        pendingDebugVideoResolution = null
        pendingDebugVideoFps = null
        pendingDebugVideoMode = null
        pendingDebugVideoDetailProfile = null
        pendingDebugRawCompression = null
        pendingDebugUncompressedCinemaPipeline = null
        debugRecordClipCoordinator.cancelCurrent()
        pendingDebugRecordClipToken = null
        hasPendingDebugCommand = false
        debugActionExecuted = false
        debugActionScheduled = false
        debugConfigAppliedForPendingCommand = false
        resetDebugAutomationRetryState()
    }

    private fun resetDebugAutomationRetryState() {
        debugAutomationRetryCount = 0
        if (::binding.isInitialized) {
            binding.root.removeCallbacks(debugAutomationRetryRunnable)
        }
    }

    private fun scheduleDebugAutomationRetry(
        reason: String,
        delayMs: Long = DEBUG_AUTOMATION_RETRY_DELAY_MS
    ) {
        if (!BuildConfig.DEBUG ||
            !hasPendingDebugCommand ||
            debugActionExecuted ||
            pendingDebugAction == null ||
            !::binding.isInitialized
        ) {
            return
        }
        if (debugAutomationRetryCount >= DEBUG_AUTOMATION_MAX_RETRIES) {
            Log.w(
                TAG,
                "maybeRunDebugAutomation giving up after $debugAutomationRetryCount retries " +
                    "reason=$reason action=$pendingDebugAction mode=${vm.uiMode.value} " +
                    "state=${vm.cameraState.value}"
            )
            clearPendingDebugCommandState()
            return
        }
        debugAutomationRetryCount += 1
        Log.i(
            TAG,
            "maybeRunDebugAutomation retry=$debugAutomationRetryCount/$DEBUG_AUTOMATION_MAX_RETRIES " +
                "reason=$reason action=$pendingDebugAction mode=${vm.uiMode.value} " +
                "state=${vm.cameraState.value}"
        )
        binding.root.removeCallbacks(debugAutomationRetryRunnable)
        binding.root.postDelayed(debugAutomationRetryRunnable, delayMs)
    }

    private fun ensureDebugAutomationSurfaceReady(): Boolean {
        if (ensurePreviewSurfaceReady()) return true
        val cameraState = vm.cameraState.value
        if (
            cameraState == CameraState.Streaming ||
            cameraState == CameraState.Open ||
            cameraState == CameraState.Recording
        ) {
            return true
        }
        return false
    }

    private fun isDebugAutomationCameraReadyForAction(
        action: String?,
        state: CameraState
    ): Boolean =
        when (action) {
            DEBUG_ACTION_START_RECORDING,
            DEBUG_ACTION_STOP_RECORDING,
            DEBUG_ACTION_RECORD_CLIP ->
                state == CameraState.Streaming || state == CameraState.Recording
            else -> state == CameraState.Streaming
        }

    private fun resolveDebugRecordingVerifyDelayMs(preset: CameraModePreset?): Long =
        when (preset) {
            CameraModePreset.HDR -> DEBUG_HDR_RECORDING_VERIFY_DELAY_MS
            CameraModePreset.LOG_VIDEO -> DEBUG_LOG_RECORDING_VERIFY_DELAY_MS
            else -> DEBUG_RECORDING_VERIFY_DELAY_MS
        }

    private fun resolveDebugRecordClipBackendSettleGraceMs(preset: CameraModePreset?): Long =
        when (preset) {
            CameraModePreset.HDR -> DEBUG_HDR_RECORDING_BACKEND_SETTLE_GRACE_MS
            else -> 0L
        }

    private fun isHighThroughputDebugCapture(label: String?): Boolean {
        val normalized = label?.trim()?.lowercase(Locale.US) ?: return false
        return normalized.startsWith("qa_stress_") || normalized.startsWith("qa_longrun_")
    }

    private fun isDebugAutomationRecordingActive(): Boolean =
        vm.gpuRecordState.value is GpuRecordingState.Starting || vm.gpuRecordState.value is GpuRecordingState.Recording ||
            vm.gpuRecordState.value is GpuRecordingState.Paused ||
            vm.gpuRecordState.value is GpuRecordingState.Stopping

    private fun requestDebugAutomationRecordingStop(reason: String): Boolean {
        return when (vm.gpuRecordState.value) {
            is GpuRecordingState.Recording -> {
                if (vm.uiMode.value == AppMode.VIDEO) {
                    Log.i(TAG, "maybeRunDebugAutomation requesting stopRecording reason=$reason")
                    vm.stopRecording()
                }
                true
            }
            is GpuRecordingState.Paused -> {
                if (vm.uiMode.value == AppMode.VIDEO) {
                    Log.i(TAG, "maybeRunDebugAutomation requesting paused stopRecording reason=$reason")
                    vm.stopRecording()
                }
                true
            }
            is GpuRecordingState.Stopping -> true
            else -> false
        }
    }

    private fun shouldRetryDebugActionUntilCameraReady(action: String): Boolean {
        val state = vm.cameraState.value
        if (isDebugAutomationCameraReadyForAction(action, state)) return false
        if (hasPermission(Manifest.permission.CAMERA)) {
            maybeOpenAdvancedCamera()
        }
        return true
    }

    private fun shouldRetryDebugActionUntilModeSettled(action: String): Boolean {
        val transitionStartedAt = debugModeTransitionAtMs
        if (transitionStartedAt <= 0L) return false
        val elapsedMs = SystemClock.elapsedRealtime() - transitionStartedAt
        val requiredMs = when (action) {
            DEBUG_ACTION_START_RECORDING,
            DEBUG_ACTION_RECORD_CLIP -> 1_400L
            else -> 0L
        }
        return requiredMs > 0L && elapsedMs in 0 until requiredMs
    }

    private fun shouldRefreshDebugSessionAfterModeSwitch(action: String): Boolean {
        if (!debugSessionRefreshPending) return false
        val rebuildReaders = false
        debugSessionRefreshPending = false
        Log.i(
            TAG,
            "maybeRunDebugAutomation refreshing session action=$action rebuildReaders=$rebuildReaders"
        )
        vm.camera.refreshOutputsAndSession(rebuildReaders = rebuildReaders)
        return true
    }

    private fun maybeRunDebugAutomation() {
        if (!BuildConfig.DEBUG || !hasPendingDebugCommand || debugActionExecuted) return
        if (!ensureDebugAutomationSurfaceReady()) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                maybeOpenAdvancedCamera()
            }
            scheduleDebugAutomationRetry("surface_not_ready")
            return
        }
        val requestedAction = pendingDebugAction
        val cameraState = vm.cameraState.value
        if (!isDebugAutomationCameraReadyForAction(requestedAction, cameraState)) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                maybeOpenAdvancedCamera()
            }
            scheduleDebugAutomationRetry("camera_not_streaming")
            return
        }
        val stopRecordingNoOp =
            requestedAction == DEBUG_ACTION_STOP_RECORDING &&
                vm.gpuRecordState.value !is GpuRecordingState.Recording &&
                vm.gpuRecordState.value !is GpuRecordingState.Paused &&
                vm.gpuRecordState.value !is GpuRecordingState.Stopping
        if (stopRecordingNoOp) {
            Log.i(
                TAG,
                "maybeRunDebugAutomation stop_recording is already idle; " +
                    "skipping mode/preset reconfiguration"
            )
        } else if (!debugConfigAppliedForPendingCommand) {
            val modeBeforeAutomation = vm.uiMode.value
            val recordingConfigMayNeedSettle =
                requestedAction == DEBUG_ACTION_RECORD_CLIP ||
                    requestedAction == DEBUG_ACTION_START_RECORDING
            pendingDebugPreset?.let { requestedPreset ->
                if (requestedPreset != activeModePreset) {
                    activateModePreset(requestedPreset)
                }
            }
            pendingDebugMode?.let { requestedMode ->
                if (vm.uiMode.value != requestedMode) {
                    setMode(requestedMode)
                }
            }
            if (vm.uiMode.value != modeBeforeAutomation) {
                debugModeTransitionAtMs = SystemClock.elapsedRealtime()
                debugSessionRefreshPending = true
                Log.i(
                    TAG,
                    "maybeRunDebugAutomation awaiting settled mode " +
                        "from=$modeBeforeAutomation to=${vm.uiMode.value}"
                )
                scheduleDebugAutomationRetry("mode_transition")
                return
            }
            pendingDebugAspect?.let(vm::setPreviewAspectRatio)
            pendingDebugUncompressedCinemaPipeline?.let {
                if (vm.uiMode.value == AppMode.VIDEO) vm.setUncompressedCinemaPipelineEnabled(it)
            }
            pendingDebugRaw?.let(vm::setRawEnabled)
            pendingDebugLog?.let { logOn ->
                vm.setLogEnabled(logOn)
            }
            pendingDebugColorProfile?.let(vm::setColorProfile)
            pendingDebugLutProfile?.let(vm::setLutProfile)
            pendingDebugLutEnabled?.let(vm::setLutEnabled)
            vm.setDebugValidationLabel(pendingDebugValidationLabel)
            pendingDebugVideoColorSpace?.let {
                if (vm.uiMode.value == AppMode.VIDEO) vm.setVideoColorSpace(it)
            }
            pendingDebugVideoHdrMode?.let {
                if (vm.uiMode.value == AppMode.VIDEO) vm.setVideoHdrMode(it)
            }
            pendingDebugVideoResolution?.let {
                if (vm.uiMode.value == AppMode.VIDEO) vm.setVideoResolution(it)
            }
            pendingDebugVideoFps?.let {
                if (vm.uiMode.value == AppMode.VIDEO) vm.setFPS(it)
            }
            pendingDebugVideoMode?.let {
                if (vm.uiMode.value == AppMode.VIDEO) vm.setCinemaVideoMode(it)
            }
            pendingDebugVideoDetailProfile?.let {
                if (vm.uiMode.value == AppMode.VIDEO) vm.setVideoDetailProfile(it)
            }
            pendingDebugRawCompression?.let {
                if (vm.uiMode.value == AppMode.VIDEO) vm.setRawVideoCompressionMode(it)
            }
            debugConfigAppliedForPendingCommand = true
            val recordingConfigTouched =
                vm.uiMode.value == AppMode.VIDEO &&
                    recordingConfigMayNeedSettle &&
                    (
                        pendingDebugRaw != null ||
                            pendingDebugLog != null ||
                            pendingDebugVideoColorSpace != null ||
                            pendingDebugVideoHdrMode != null ||
                            pendingDebugVideoResolution != null ||
                            pendingDebugVideoFps != null ||
                            pendingDebugVideoMode != null ||
                            pendingDebugVideoDetailProfile != null ||
                            pendingDebugRawCompression != null ||
                            pendingDebugUncompressedCinemaPipeline != null
                    )
            if (recordingConfigTouched) {
                debugModeTransitionAtMs = SystemClock.elapsedRealtime()
                debugSessionRefreshPending = true
                Log.i(
                    TAG,
                    "maybeRunDebugAutomation awaiting recording config settle " +
                        "action=$requestedAction resolution=$pendingDebugVideoResolution " +
                        "fps=$pendingDebugVideoFps mode=$pendingDebugVideoMode " +
                        "npv10=$pendingDebugUncompressedCinemaPipeline"
                )
                scheduleDebugAutomationRetry("recording_config_transition", 700L)
                return
            }
        } else {
            Log.i(TAG, "maybeRunDebugAutomation using already-applied debug config")
        }
        Log.i(
            TAG,
            "appliedDebugConfig mode=${vm.uiMode.value} raw=${vm.isRawEnabled} " +
                "log=${vm.isLogEnabled} aspect=${vm.previewAspect.value} " +
                "colorProfile=${vm.currentColorProfile} " +
                "lutEnabled=${vm.isLutEnabled} lutProfile=${vm.currentLutProfile} " +
                "colorSpace=${vm.currentVideoColorSpace} " +
                "hdrMode=${vm.currentVideoHdrMode} videoMode=${vm.cinemaVideoMode.value} " +
                "fps=${vm.gpuRecording.videoFps} detailProfile=${vm.currentVideoDetailProfile} " +
                "compression=${vm.rawVideoCompression.value} " +
                "uncompressedCinemaPipeline=${vm.isUncompressedCinemaPipelineEnabled}"
        )

        val debugUsesNpv10Pipeline =
            vm.uiMode.value == AppMode.VIDEO &&
                vm.isUncompressedCinemaPipelineEnabled &&
                vm.cinemaVideoMode.value != CinemaVideoMode.RAW &&
                vm.cinemaVideoMode.value != CinemaVideoMode.LOG
        val action = pendingDebugAction
        if (action == DEBUG_ACTION_RECORD_CLIP || action == DEBUG_ACTION_START_RECORDING) {
            if (shouldRefreshDebugSessionAfterModeSwitch(action)) {
                debugActionExecuted = false
                scheduleDebugAutomationRetry("${action}_waiting_for_session_refresh", 700L)
                return
            }
            if (shouldRetryDebugActionUntilModeSettled(action)) {
                debugActionExecuted = false
                scheduleDebugAutomationRetry("${action}_waiting_for_config_settle", 500L)
                return
            }
        }
        if (!debugUsesNpv10Pipeline &&
            vm.uiMode.value == AppMode.VIDEO &&
            (action == null ||
                action == DEBUG_ACTION_RECORD_CLIP ||
                action == DEBUG_ACTION_START_RECORDING)
        ) {
            vm.prearmRecordingSurfaceForSelectedVideoSettings(forceSessionRefresh = true)
            if ((action == DEBUG_ACTION_RECORD_CLIP || action == DEBUG_ACTION_START_RECORDING) &&
                !vm.isRecordingSurfacePrearmedForDebugStart()
            ) {
                Log.i(TAG, "debugAction $action waiting for recording surface prearm")
                debugActionExecuted = false
                scheduleDebugAutomationRetry(
                    "${action}_waiting_for_recording_surface_prearm",
                    DEBUG_AUTOMATION_RETRY_DELAY_MS
                )
                return
            }
        }
        val actionPreset = pendingDebugPreset
        val actionValidationLabel = pendingDebugValidationLabel
        val actionDurationMs = pendingDebugDurationMs
        if (action == null) {
            hasPendingDebugCommand = false
            debugActionExecuted = true
            debugActionScheduled = false
            resetDebugAutomationRetryState()
            return
        }
        debugActionExecuted = true
        debugActionScheduled = true
        resetDebugAutomationRetryState()
        Log.i(TAG, "maybeRunDebugAutomation mode=${vm.uiMode.value} action=$action")
        val actionDelayMs = when {
            action == DEBUG_ACTION_RECORD_CLIP ||
                action == DEBUG_ACTION_START_RECORDING -> 1000L
            else -> 700L
        }
        binding.root.postDelayed({
            debugActionScheduled = false
            when (action) {
                DEBUG_ACTION_START_RECORDING -> if (vm.uiMode.value == AppMode.VIDEO) {
                    if (shouldRefreshDebugSessionAfterModeSwitch(action)) {
                        debugActionExecuted = false
                        scheduleDebugAutomationRetry("start_recording_waiting_for_session_refresh", 700L)
                        return@postDelayed
                    }
                    if (shouldRetryDebugActionUntilModeSettled(action)) {
                        debugActionExecuted = false
                        scheduleDebugAutomationRetry("start_recording_waiting_for_mode_settle", 500L)
                        return@postDelayed
                    }
                    if (shouldRetryDebugActionUntilCameraReady(action)) {
                        debugActionExecuted = false
                        scheduleDebugAutomationRetry("start_recording_waiting_for_streaming", 600L)
                        return@postDelayed
                    }
                    if (vm.gpuRecordState.value is GpuRecordingState.Recording ||
                        vm.gpuRecordState.value is GpuRecordingState.Paused) {
                        Log.i(TAG, "debugAction startRecording already active")
                        return@postDelayed
                    }
                    vm.setDebugValidationLabel(actionValidationLabel)
                    Log.i(TAG, "debugAction startRecording")
                    vm.toggleRecording()
                    binding.root.postDelayed({
                        if (vm.uiMode.value == AppMode.VIDEO &&
                            vm.gpuRecordState.value !is GpuRecordingState.Recording
                        ) {
                            debugActionExecuted = false
                            scheduleDebugAutomationRetry("start_recording_not_active", 800L)
                        }
                    }, resolveDebugRecordingVerifyDelayMs(actionPreset))
                } else {
                    debugActionExecuted = false
                    scheduleDebugAutomationRetry("start_recording_waiting_for_video_mode", 600L)
                }
                DEBUG_ACTION_STOP_RECORDING -> {
                    Log.i(TAG, "debugAction stopRecording mode=${vm.uiMode.value}")
                    when (vm.gpuRecordState.value) {
                        is GpuRecordingState.Recording -> vm.stopRecording()
                        is GpuRecordingState.Paused -> vm.stopRecording()
                        is GpuRecordingState.Stopping ->
                            Log.i(TAG, "debugAction stopRecording already stopping")
                        else ->
                            Log.i(TAG, "debugAction stopRecording skipped: state=${vm.gpuRecordState.value}")
                    }
                }
                DEBUG_ACTION_RECORD_CLIP -> if (vm.uiMode.value == AppMode.VIDEO) {
                    if (shouldRefreshDebugSessionAfterModeSwitch(action)) {
                        debugActionExecuted = false
                        scheduleDebugAutomationRetry("record_clip_waiting_for_session_refresh", 700L)
                        return@postDelayed
                    }
                    if (shouldRetryDebugActionUntilModeSettled(action)) {
                        debugActionExecuted = false
                        scheduleDebugAutomationRetry("record_clip_waiting_for_mode_settle", 500L)
                        return@postDelayed
                    }
                    if (shouldRetryDebugActionUntilCameraReady(action)) {
                        debugActionExecuted = false
                        scheduleDebugAutomationRetry("record_clip_waiting_for_streaming", 600L)
                        return@postDelayed
                    }
                    vm.setDebugValidationLabel(actionValidationLabel)
                    val alreadyRecording = vm.gpuRecordState.value is GpuRecordingState.Recording ||
                        vm.gpuRecordState.value is GpuRecordingState.Paused
                    val recordClipToken = pendingDebugRecordClipToken
                    if (recordClipToken == null) {
                        Log.e(TAG, "debugAction recordClip rejected: missing command token")
                        clearPendingDebugCommandState()
                        return@postDelayed
                    }
                    val startClaimed = debugRecordClipCoordinator.tryClaimStart(recordClipToken)
                    if (startClaimed && !alreadyRecording) {
                        Log.i(TAG, "debugAction recordClip start durationMs=$actionDurationMs")
                        vm.toggleRecording()
                    } else if (startClaimed) {
                        Log.i(TAG, "debugAction recordClip start skipped: recording already active")
                    } else {
                        Log.i(TAG, "debugAction recordClip start suppressed: command already issued start")
                    }
                    if (debugRecordClipCoordinator.tryClaimObserver(recordClipToken)) {
                        debugRecordClipCoordinator.scheduleStopAfterRecordingStarts(
                            commandToken = recordClipToken,
                            durationMs = actionDurationMs,
                            verifyDelayMs = resolveDebugRecordingVerifyDelayMs(actionPreset),
                            backendSettleGraceMs = resolveDebugRecordClipBackendSettleGraceMs(actionPreset),
                            pollIntervalMs = DEBUG_RECORDING_STATE_POLL_MS
                        )
                    }
                } else {
                    debugActionExecuted = false
                    scheduleDebugAutomationRetry("record_clip_waiting_for_video_mode", 600L)
                }
                DEBUG_ACTION_SWITCH_FACING -> {
                    Log.i(TAG, "debugAction switchFacing")
                    vm.switchFacing()
                }
                DEBUG_ACTION_TRIGGER_FOCUS -> {
                    Log.i(TAG, "debugAction triggerFocusCenter")
                    vm.tapToFocus(0.5f, 0.5f)
                }
                else -> debugActionExecuted = false
            }
            binding.root.post {
                applyQueuedDebugCommandIfAny()
                maybeRunDebugAutomation()
            }
        }, actionDelayMs)
    }

    private data class LatestGalleryMedia(
        val uri: Uri,
        val dateAddedSeconds: Long
    )

    private fun refreshGalleryThumbnail(preferredPath: String? = null, forceReload: Boolean = false) {
        val explicitRefresh = forceReload || !preferredPath.isNullOrBlank()
        val targetSizePx =
            binding.btnGallery.width
                .coerceAtLeast(binding.btnGallery.height)
                .coerceAtLeast(160)
        galleryThumbnailJob?.cancel()
        galleryThumbnailJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val latest = queryLatestGalleryMedia()
                if (latest == null) {
                    withContext(Dispatchers.Main.immediate) {
                        latestGalleryThumbnailUri = null
                        binding.btnGallery.setImageResource(android.R.drawable.ic_menu_gallery)
                        binding.btnGallery.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                    return@launch
                }
                val alreadyApplied = !explicitRefresh && latestGalleryThumbnailUri == latest.uri
                val thumbnail = if (alreadyApplied) {
                    null
                } else {
                    loadThumbnailBitmap(latest, targetSizePx)
                }
                if (!isActive) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (!isActive) return@withContext
                    latestGalleryThumbnailUri = latest.uri
                    if (thumbnail != null) {
                        binding.btnGallery.animate().cancel()
                        binding.btnGallery.alpha = 0f
                        binding.btnGallery.setImageBitmap(thumbnail)
                        binding.btnGallery.scaleType = ImageView.ScaleType.CENTER_CROP
                        binding.btnGallery.animate()
                            .alpha(1f)
                            .setDuration(140L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh gallery thumbnail", e)
            }
        }
    }

    private fun queryLatestGalleryMedia(): LatestGalleryMedia? {
        val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val image = queryLatestFromCollection(collection = imageCollection)
        val video = queryLatestFromCollection(collection = videoCollection)
        return listOfNotNull(image, video).maxByOrNull { it.dateAddedSeconds }
    }

    private fun queryLatestFromCollection(
        collection: Uri
    ): LatestGalleryMedia? {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val appQuery = CameraMediaLibraryQueryPolicy.mediaStoreRelativePathSelection(
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        fun runQuery(selection: String?, selectionArgs: Array<String>?): LatestGalleryMedia? {
            return contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val dateAdded =
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                LatestGalleryMedia(
                    uri = ContentUris.withAppendedId(collection, id),
                    dateAddedSeconds = dateAdded
                )
            }
        }
        return runCatching {
            runQuery(appQuery.selection, appQuery.selectionArgs) ?: runQuery(null, null)
        }.getOrNull()
    }

    private fun loadThumbnailBitmap(media: LatestGalleryMedia, targetSizePx: Int): Bitmap? {
        return runCatching {
            contentResolver.loadThumbnail(
                media.uri,
                Size(targetSizePx, targetSizePx),
                null
            )
        }.getOrNull()
    }

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    private fun openSettingsSheet() {
        if (supportFragmentManager.findFragmentByTag(CameraSettingsSheetFragment.TAG) != null) return
        CameraSettingsSheetFragment().show(supportFragmentManager, CameraSettingsSheetFragment.TAG)
    }

    fun launchCustomLutImport() {
        lutImportLauncher.launch(arrayOf("*/*", "text/plain", "application/octet-stream"))
    }

    private suspend fun importCustomLutToAppStorage(uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val lutDir = File(filesDir, "luts").apply { mkdirs() }
        val target = File(lutDir, "custom_import.cube")
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            target
        }.getOrNull()
    }

    private fun supportedModeOrder(): List<CameraModePreset> =
        CameraModePresetOrderPolicy.videoRailOrder().filterNot { preset ->
            (preset == CameraModePreset.RAW_MODE && !vm.camera.supportsRaw) ||
                (preset == CameraModePreset.HDR && !com.neoproc.neoproccamx.core.NdrFeatureGate.enabled)
        }

    private fun shiftModePreset(delta: Int) {
        val order = supportedModeOrder()
        val currentIndex = order.indexOf(activeModePreset).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + delta).floorMod(order.size)
        activateModePreset(order[nextIndex])
    }

    private fun visibleModeViews(): List<Pair<TextView, CameraModePreset>> {
        val viewByPreset = mapOf(
            CameraModePreset.VIDEO to binding.btnModeVideoRail,
            CameraModePreset.PRO to binding.btnModeProRail,
            CameraModePreset.HDR to binding.btnModeHdrScene,
            CameraModePreset.LOG_VIDEO to binding.btnModeLogVideo,
            CameraModePreset.RAW_MODE to binding.btnModeRawPreset
        )
        return supportedModeOrder().mapNotNull { preset ->
            viewByPreset[preset]?.let { view -> view to preset }
        }
    }

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

    private fun setManualControlsVisible(visible: Boolean) {
        val current = vm.isManualControlsVisible
        if (current != visible) {
            vm.toggleManualControls()
        }
    }

    private fun activateModePreset(preset: CameraModePreset) {
        val previousPreset = activeModePreset
        val nowUptimeMs = SystemClock.uptimeMillis()
        val modePresetSwitchDecision = CameraModeSwitchPolicy.resolvePresetActivation(
            current = previousPreset,
            requested = preset,
            nowUptimeMs = nowUptimeMs,
            lastAcceptedUptimeMs = lastModePresetActivationUptimeMs
        )
        if (!modePresetSwitchDecision.accepted) {
            Log.i(
                TAG,
                "Mode preset transition throttled: $previousPreset -> $preset " +
                    "nextAllowed=${modePresetSwitchDecision.nextAllowedUptimeMs}"
            )
            return
        }
        lastModePresetActivationUptimeMs = nowUptimeMs
        Log.i(TAG, "Mode preset transition requested: $previousPreset -> $preset")
        activeModePreset = preset
        // Coalesce the burst of setter-triggered camera session refreshes into ONE
        // at the end of this block. Without this, switching presets fires 4-7 nested
        // refreshOutputsAndSession() calls, each tearing the CameraCaptureSession down
        // for 100-500ms — the source of the "preview flicker on mode switch" bug.
        vm.beginModeSwitchPreviewWorkBatch()
        vm.camera.beginBatchUpdate()
        try {
            when (preset) {
                CameraModePreset.VIDEO -> {
                    setManualControlsVisible(false)
                    resetAutoImagingStateForPreset()
                    setMode(AppMode.VIDEO)
                    vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)

                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setRawEnabled(false)
                    vm.setLogEnabled(false)
                    vm.setVideoHdrMode("OFF")
                    vm.setTenBitRecordingEnabled(true)
                    vm.setFPS(25)
                }
                CameraModePreset.PRO -> {
                    setManualControlsVisible(true)
                    resetAutoImagingStateForPreset()
                    setMode(AppMode.VIDEO)
                    vm.setSdrQualityIntent(SdrQualityIntent.PRO)

                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setRawEnabled(false)
                    vm.setLogEnabled(false)
                    vm.setVideoHdrMode("OFF")
                    vm.setTenBitRecordingEnabled(true)
                    vm.setFPS(25)
                }
                CameraModePreset.HDR -> {
                    setManualControlsVisible(false)
                    resetAutoImagingStateForPreset()
                    setMode(AppMode.VIDEO)
                    vm.setRawEnabled(false)
                    vm.setLogEnabled(false)
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setVideoColorSpace("Rec.2020")
                    vm.setVideoHdrMode("NDR10+")
                    vm.setTenBitRecordingEnabled(true)
                    vm.setFPS(25)
                }
                CameraModePreset.LOG_VIDEO -> {
                    setManualControlsVisible(false)
                    resetAutoImagingStateForPreset()
                    setMode(AppMode.VIDEO)
                    vm.setRawEnabled(false)
                    vm.setCinemaVideoMode(CinemaVideoMode.LOG)
                    vm.setVideoHdrMode("OFF")
                    vm.setVideoColorSpace("Rec.2020")
                    vm.setTenBitRecordingEnabled(true)
                    vm.setColorProfile("N-Log")
                }
                CameraModePreset.RAW_MODE -> {
                    setManualControlsVisible(false)
                    resetAutoImagingStateForPreset()
                    setMode(AppMode.VIDEO)
                    vm.setCinemaVideoMode(CinemaVideoMode.RAW)
                    vm.setRawEnabled(true)
                    vm.setLogEnabled(false)
                    vm.setVideoHdrMode("OFF")
                    vm.setVideoColorSpace("Rec.2020")
                    vm.setTenBitRecordingEnabled(false)
                }
            }
            renderPresetSelection()
            animateModeTransition(previousPreset, preset)
            renderStatusChrome()

            syncOverlayPreviewArea()
        } finally {
            // One preview/LUT update and one camera refresh, merged from all calls above, run here.
            try {
                vm.endModeSwitchPreviewWorkBatch()
            } finally {
                vm.camera.endBatchUpdate()
            }
        }
    }

    private fun resetAutoImagingStateForPreset() {
        vm.unlockFocusAndExposure()
        vm.setAutoISO()
        vm.setAutoExposure()
        vm.setAutoWB()
        vm.setAutoFocus()
    }

    private fun syncPresetFromState() {
        activeModePreset = CameraModePresetSelectionPolicy.resolve(
            currentPreset = activeModePreset,
            uiMode = vm.uiMode.value,
            cinemaMode = vm.cinemaVideoMode.value,
            manualControlsVisible = vm.isManualControlsVisible,
            rawEnabled = vm.isRawEnabled,
            logEnabled = vm.isLogEnabled,
            hdrEnabled = vm.isVideoHdrEnabled,
            hdrMode = vm.currentVideoHdrMode
        )
        renderPresetSelection()
    }

    private fun renderPresetSelection() {
        val modeViews = visibleModeViews()
        mapOf(
            CameraModePreset.VIDEO to binding.btnModeVideoRail, CameraModePreset.PRO to binding.btnModeProRail,
            CameraModePreset.HDR to binding.btnModeHdrScene, CameraModePreset.LOG_VIDEO to binding.btnModeLogVideo,
            CameraModePreset.RAW_MODE to binding.btnModeRawPreset
        ).forEach { (preset, view) ->
            if (modeViews.none { it.second == preset }) view.visibility = View.GONE
        }

        val selectedColor = ContextCompat.getColor(this, R.color.text_primary)
        val defaultColor = ContextCompat.getColor(this, R.color.text_secondary_strong)
        val selectedMotion = CameraModeRailMotionPolicy.selectedItemMotion()
        val idleAlpha = CameraModeRailMotionPolicy.idleItemAlpha()
        modeViews.forEach { (view, preset) ->
            val wasSelected = view.isSelected
            val selected = activeModePreset == preset
            view.isSelected = selected
            view.visibility = if (selected) View.VISIBLE else View.GONE
            view.setBackgroundResource(
                if (selected) R.drawable.bg_mode_rail_active else R.drawable.bg_mode_rail_idle
            )
            view.setTextColor(if (selected) selectedColor else defaultColor)
            view.typeface = Typeface.create(
                "sans-serif-medium",
                if (selected) Typeface.BOLD else Typeface.NORMAL
            )
            view.animate().cancel()
            view.translationY = 0f
            view.alpha = if (selected) 1f else idleAlpha
            if (selected && !wasSelected) {
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                view.scaleX = selectedMotion.startScale
                view.scaleY = selectedMotion.startScale
                view.alpha = selectedMotion.startAlpha
                view.translationY = dp(3f)
                view.animate()
                    .scaleX(selectedMotion.endScale)
                    .scaleY(selectedMotion.endScale)
                    .alpha(selectedMotion.endAlpha)
                    .translationY(0f)
                    .setDuration(selectedMotion.durationMs)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { view.setLayerType(View.LAYER_TYPE_NONE, null) }
                    .start()
            } else {
                view.scaleX = if (selected) selectedMotion.endScale else 1f
                view.scaleY = if (selected) selectedMotion.endScale else 1f
            }
        }
    }

    private fun setMode(mode: AppMode) {
        vm.setMode(mode)
    }

    private fun normalizeDebugColorProfile(value: String): String? {
        val canonical = value.trim()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
        if (canonical.isBlank()) return null
        return COLOR_PROFILES.firstOrNull { it.equals(canonical, ignoreCase = true) }
            ?: when (canonical.uppercase(Locale.US)) {
                "N LOG", "NLOG", "N-LOG", "NEOPROC N LOG", "NEOPRO N LOG" -> "N-Log Base"
                "NEO CINEMA CURVE", "NEO CINEMA", "CINEMA CURVE" -> "N-Log Cinema"
                "NEO DEEP CURVE", "NEO DEEP", "DEEP CURVE" -> "N-Log Cinema"
                "EXTERNAL WIDE GAMUT", "WIDEGAMUTRGB", "WIDE GAMUT RGB" -> "Neo Wide Gamut"
                "NEO LEGACY CURVE", "NEO LEGACY", "LEGACY CURVE" -> "N-Log Cinema"
                "NEO FLEX CURVE", "NEO FLEX", "FLEX CURVE" -> "N-Log Cinema"
                "REC709", "REC 709" -> "Rec.709"
                "REC2020", "REC 2020" -> "Rec.2020"
                else -> null
            }
    }

    private fun normalizeDebugLutProfile(value: String): String? {
        val canonical = value.trim()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
        if (canonical.isBlank()) return null
        return ColorScience.BUILT_IN_LUTS.firstOrNull { it.equals(canonical, ignoreCase = true) }
            ?: when (canonical.uppercase(Locale.US)) {
                "NEUTRAL FILM" -> "Neutral Film"
                "WARM CINEMA" -> "Warm Cinema"
                "TEAL ORANGE", "TEAL AND ORANGE", "TEAL&ORANGE" -> "Teal & Orange"
                "EXTERNAL FILM GOLD" -> "Neo Cinema Gold"
                "EXTERNAL 709" -> "Neo 709 Clean"
                "EXTERNAL FILM TO VIDEO" -> "Neo HDR Soft"
                else -> null
            }
    }

    private fun renderMode(mode: AppMode) {
        renderFeatureVisibility(mode)
        renderHdrButton()
        syncPresetFromState()
        renderStatusChrome()
    }

    private fun renderAspect(aspect: PreviewAspectRatio) {
        // Preview aspect is handled by syncOverlayPreviewArea
    }

    private fun renderFeatureVisibility(mode: AppMode) {
        val isVideo = true
        val showCompactTopTools = !vm.isManualControlsVisible
        val rawSupported = vm.camera.supportsRaw

        binding.btnRaw.visibility =
            if (rawSupported) View.VISIBLE else View.GONE
        binding.btnHdr.visibility = View.VISIBLE
        // LOG is convert-only (recorded RAW is developed to NeoLog offline); there is no
        // live LOG capture mode, so the LOG quick-toggle is always hidden.
        binding.btnLog.visibility = View.GONE
        binding.btnColorProfile.visibility = if (showCompactTopTools) View.VISIBLE else View.GONE
        binding.btnGrid.visibility = View.VISIBLE
        binding.btnHistogram.visibility = if (showCompactTopTools) View.VISIBLE else View.GONE
        binding.btnWaveform.visibility = if (showCompactTopTools) View.VISIBLE else View.GONE
        binding.btnZebra.visibility = if (showCompactTopTools) View.VISIBLE else View.GONE
        binding.btnPeaking.visibility = View.VISIBLE
        binding.btnPhotoCapture.visibility = View.VISIBLE

        binding.btnRaw.isEnabled = rawSupported
        binding.btnRaw.alpha = if (binding.btnRaw.isEnabled) 1f else 0.45f
        binding.btnRaw.text = getString(R.string.control_raw)
        binding.btnModeRawPreset.text = getString(R.string.control_raw)
        binding.btnLog.text = getString(R.string.profile_log)
        binding.btnWaveform.text = getString(R.string.control_waveform)
        binding.btnWaveform.isEnabled = true
        // Zebra renders via the CPU overlay-mask path (UIOverlay.setZebraMask), which is independent
        // of the native Vulkan compositor. Keep the control enabled regardless of GPU-overlay support
        // so the feature is usable even when the realtime compositor overlay is unavailable.
        binding.btnZebra.isEnabled = true
        binding.btnWaveform.alpha = 1f
        binding.btnZebra.alpha = 1f
        binding.btnHistogram.isEnabled = true
        binding.btnHistogram.alpha = 1f
        binding.btnPeaking.isEnabled = true
        binding.btnPeaking.alpha = 1f
        binding.btnLog.isEnabled = true
        binding.btnLog.alpha = if (showCompactTopTools) 1f else 0.45f
        binding.btnColorProfile.isEnabled = isVideo
        binding.btnColorProfile.alpha = if (isVideo && showCompactTopTools) 1f else 0.45f
        if (!vm.isHighQualityPhotoCaptureInFlight()) {
            binding.btnPhotoCapture.isEnabled = true
            binding.btnPhotoCapture.alpha = 1f
        }
        binding.btnGallery.visibility = View.GONE
        binding.btnGallery.isEnabled = false
        binding.btnGallery.alpha = 0f
        binding.btnRaw.isSelected = vm.isRawEnabled
        binding.videoLeftRail.visibility = View.GONE
        binding.btnSideFocusLock.visibility = View.GONE
        binding.btnSideFocusLock.isEnabled = false
        binding.btnSideAeLock.visibility = View.GONE
        binding.videoRightRail.visibility = View.GONE
        applyFeatureMenuVisibility()
    }

    private fun shouldShowAutoSubjectMask(): Boolean =
        vm.cameraParams.value.focusDist < 0f // && !vm.camera.isAeAwbLocked() - visibility issue

    private fun renderFlash() {
        binding.btnFlash.text = when (flashMode) {
            FlashMode.OFF -> "OFF"
            FlashMode.AUTO -> "AUTO"
            FlashMode.ON -> "ON"
            FlashMode.TORCH -> "TORCH"
        }
    }

    private fun renderHdrButton() {
        val selected = vm.isVideoHdrEnabled
        binding.btnHdr.isSelected = selected
        binding.btnHdr.text = getString(R.string.mode_hdr)
    }

    private fun renderFocusAssistControl(visual: FocusAssistVisual = vm.focusAssistVisual.value) {
        val (iconRes, contentDescriptionRes) = when (visual) {
            FocusAssistVisual.TAP -> R.drawable.ic_focus_tap to R.string.focus_assist_tap
            FocusAssistVisual.FACE -> R.drawable.ic_focus_face to R.string.focus_assist_face
            FocusAssistVisual.EYE -> R.drawable.ic_focus_eye to R.string.focus_assist_eye
        }
        binding.btnSideFocusLock.text = ""
        binding.btnSideFocusLock.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0)
        binding.btnSideFocusLock.compoundDrawablePadding = 0
        binding.btnSideFocusLock.contentDescription = getString(contentDescriptionRes)
    }

    private fun renderStatusChrome() {
        if (!::uiRenderScheduler.isInitialized) {
            renderStatusChromeNow()
            return
        }
        uiRenderScheduler.request(CameraUiRenderPass.STATUS_CHROME)
    }

    private fun renderStatusChromeNow() {
        val sensorSize = vm.camera.fullSensorSize()
        val resolutionLabel = "${vm.recording.width}x${vm.recording.height}"
        val aspectLabel = vm.previewAspect.value
            .takeUnless { it == PreviewAspectRatio.AUTO }
            ?.label
            ?: CameraPreviewHudFormatter.aspectLabel(sensorSize)
        // CP-QV23 — read-only 35mm-equivalent lens badge from live characteristics (no lens selection).
        val lensLabel = com.neoproc.neoproccamx.camera.CameraPhysicalImagingTraits
            .fromCharacteristics(vm.camera.getCameraCharacteristics())
            .let { traits ->
                com.neoproc.neoproccamx.camera.CameraLensSelectionPolicy.equivalentFocalLength35mm(
                    focalLengthMm = traits.focalLengthMm,
                    sensorWidthMm = traits.sensorWidthMm,
                    sensorHeightMm = traits.sensorHeightMm
                )
            }
            ?.let { com.neoproc.neoproccamx.camera.CameraLensSelectionPolicy.labelForEquivalentFocalLength(it) }
        val cameraStatusText = CameraPreviewHudFormatter.format(
            sensorSize = sensorSize,
            resolution = resolutionLabel,
            aspect = aspectLabel,
            lensLabel = lensLabel
        )
        val recording = isRecordingOrStopping()
        val paused = vm.gpuRecordState.value is GpuRecordingState.Paused
        val statusState = RecordingStatusHudPolicy.resolve(
            recording = recording,
            paused = paused,
            durationMs = vm.recDuration.value,
            cameraStatusText = cameraStatusText
        )
        applyStatusHudState(statusState)
        val importStats = vm.vulkanImportStats.value
        // Name the real active recording transport (P010 / direct encoder surface / GPU import) so
        // the badge is honest and plain instead of the cryptic "CPU"/"UNPROVEN" that read as errors.
        val hudTransportLabel = vm.activeRecordingTransportHudLabel()
        val directHardwareSurface = hudTransportLabel == "ENCODER_SURFACE_DIRECT"
        val nativePathValidated = directHardwareSurface ||
            (importStats.sessionImportedFrames > 0L &&
                (importStats.encoderSurfaceBound || importStats.offscreenEglEncoderSurfaceBound))
        val transportClassification = when {
            hudTransportLabel != null -> hudTransportLabel
            nativePathValidated -> "AHB_GPU_COMPATIBILITY"
            importStats.frameImportEnabled -> "AHB_GPU_COMPATIBILITY"
            else -> "UNPROVEN"
        }
        val stage6StatusText = if (recording) {
            CameraPreviewHudFormatter.formatStage6Status(
                provisional = true,
                qualityClass = "CORE-HONEST",
                transportClassification = transportClassification,
                deviceLabStatus = "NOT_TESTED",
                nativePathValidated = nativePathValidated
            )
        } else {
            ""
        }
        if (CameraUiStateDiffPolicy.textChanged(binding.tvStatusSecondary.text, stage6StatusText)) {
            binding.tvStatusSecondary.text = stage6StatusText
        }
        val showStage6Status = stage6StatusText.isNotBlank()
        if (CameraUiStateDiffPolicy.visibilityChanged(binding.tvStatusSecondary.visibility, showStage6Status)) {
            binding.tvStatusSecondary.visibility = if (showStage6Status) View.VISIBLE else View.GONE
        }
        binding.tvStatusPrimary.ellipsize = TextUtils.TruncateAt.END
        renderFocusAssistControl()
        val dockStyle = CameraDockButtonStylePolicy.resolve(recording)
        val stateDockStyle = CameraDockButtonStylePolicy.resolve(vm.gpuRecordState.value)
        binding.btnModePro.setBackgroundResource(dockStyle.backgroundRes)
        binding.btnModePro.setImageResource(stateDockStyle.settingsIconRes)
        binding.btnModePro.imageTintList = null
        binding.btnModePro.alpha = stateDockStyle.alpha
        binding.btnSettings.setImageResource(stateDockStyle.settingsIconRes)
        binding.btnSettings.imageTintList = null
        val settingsDescription = when (stateDockStyle.settingsAction) {
            CameraDockButtonAction.SETTINGS -> getString(R.string.cd_settings)
            CameraDockButtonAction.PAUSE -> getString(R.string.rec_pause)
            CameraDockButtonAction.RESUME -> getString(R.string.rec_resume)
        }
        binding.btnModePro.contentDescription = settingsDescription
        binding.btnSettings.contentDescription = settingsDescription
        binding.btnPhotoCapture.setBackgroundResource(dockStyle.backgroundRes)
        binding.btnPhotoCapture.setImageResource(R.drawable.ic_photo_capture)
        binding.btnPhotoCapture.imageTintList = null
    }

    private fun applyStatusHudState(statusState: RecordingStatusHudState) {
        if (CameraUiStateDiffPolicy.textChanged(binding.tvStatusPrimary.text, statusState.primaryText)) {
            binding.tvStatusPrimary.text = statusState.primaryText
        }
        if (CameraUiStateDiffPolicy.intChanged(binding.tvStatusPrimary.currentTextColor, statusState.primaryTextColor)) {
            binding.tvStatusPrimary.setTextColor(statusState.primaryTextColor)
        }
        val targetTextStyle = if (statusState.timerActive) Typeface.BOLD else Typeface.NORMAL
        if (binding.tvStatusPrimary.typeface?.style != targetTextStyle) {
            binding.tvStatusPrimary.typeface = Typeface.create("sans-serif-medium", targetTextStyle)
        }
        if (statusState.timerActive) {
            lastRecordingTimerText = statusState.primaryText
        } else {
            lastRecordingTimerText = null
        }
    }

    private fun animateModeTransition(previousPreset: CameraModePreset, nextPreset: CameraModePreset) {
        if (previousPreset == nextPreset || !binding.modeRailContent.isLaidOut) return
        val motion = CameraModeRailMotionPolicy.transitionMotion(
            previous = previousPreset,
            next = nextPreset,
            order = supportedModeOrder()
        )
        if (motion.direction == 0) return
        binding.modeRailContent.animate().cancel()
        binding.modeRailContent.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.modeRailContent.translationX = motion.direction * dp(motion.contentOffsetDp)
        binding.modeRailContent.alpha = motion.startAlpha
        binding.modeRailContent.animate()
            .translationX(0f)
            .alpha(motion.endAlpha)
            .setDuration(motion.durationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { binding.modeRailContent.setLayerType(View.LAYER_TYPE_NONE, null) }
            .start()
    }

    private fun updateRecordingTimerIfVisible(durationMs: Long) {
        if (!isRecordingOrStopping()) return
        val statusState = RecordingStatusHudPolicy.resolve(
            recording = true,
            paused = vm.gpuRecordState.value is GpuRecordingState.Paused,
            durationMs = durationMs,
            cameraStatusText = binding.tvStatusPrimary.text?.toString().orEmpty()
        )
        if (lastRecordingTimerText == statusState.primaryText) return
        applyStatusHudState(statusState)
    }

    private fun isRecordingOrStopping(): Boolean =
        vm.gpuRecordState.value is GpuRecordingState.Recording ||
            vm.gpuRecordState.value is GpuRecordingState.Paused ||
            vm.gpuRecordState.value is GpuRecordingState.Stopping

    private fun maybeEmitPreviewFpsPipelineAlert(previewFps: Float) {
        if (!isRecordingOrStopping() || previewFps <= 0f) return
        val targetFps = vm.cameraParams.value.fps.coerceAtLeast(24).toFloat()
        if (previewFps < targetFps * CRITICAL_PREVIEW_FPS_FRACTION) {
            performCameraHaptic(CameraHapticAction.CRITICAL_PIPELINE_ALERT)
        }
    }

    private fun maybeEmitAiTargetLockHaptic(visual: FocusAssistVisual) {
        val lockAcquired = visual != lastFocusAssistLockVisual &&
            (visual == FocusAssistVisual.FACE || visual == FocusAssistVisual.EYE)
        lastFocusAssistLockVisual = visual
        if (lockAcquired) {
            performCameraHaptic(CameraHapticAction.AI_TARGET_LOCK)
        }
    }

    private fun resolveTopWarningChipState(state: CameraUiState = vm.uiState.value): TopWarningChipState =
        TopWarningChipPolicy.resolve(
            hardwareWarning = state.hardwareWarning,
            shutterGuidance = state.shutterGuidance,
            thermalTier = state.thermalTier,
            thermalThrottled = state.thermalThrottled
        )

    private fun renderTopWarningChip(state: CameraUiState = vm.uiState.value) {
        renderTopWarningChip(resolveTopWarningChipState(state))
    }

    private fun renderTopWarningChip(warningState: TopWarningChipState) {
        if (CameraUiStateDiffPolicy.textChanged(binding.tvTopWarning.text, warningState.text)) {
            binding.tvTopWarning.text = warningState.text
        }
        if (CameraUiStateDiffPolicy.visibilityChanged(binding.topWarningPanel.visibility, false)) {
            binding.topWarningPanel.visibility = View.GONE
        }
        binding.tvTopWarning.ellipsize = TextUtils.TruncateAt.END
    }

    private fun showTransientZoom(zoom: Float) {
        updateZoomToggleLabel(zoom)
        binding.btnZoomToggle.visibility = View.VISIBLE
        zoomHudHideJob?.cancel()
        zoomHudHideJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(850L)
            binding.btnZoomToggle.visibility = View.GONE
        }
    }

    /** Reflect the live zoom ratio on the single zoom button. */
    private fun updateZoomToggleLabel(zoom: Float) {
        binding.btnZoomToggle.text = String.format(Locale.US, "%.1fx", zoom)
    }

    private fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        val modeRailTranslationY = applyDockCaptureRowPosition()
        applyFeatureMenuVisibility()
        binding.modeGroup.animate().cancel()
        binding.modeGroup.visibility = if (visible) View.VISIBLE else View.GONE
        binding.modeGroup.alpha = 1f
        binding.modeGroup.translationY = modeRailTranslationY
        binding.btnZoomToggle.translationY = modeRailTranslationY
        binding.modeGroup.isEnabled = visible
        binding.modeRailScroll.isEnabled = visible
        visibleModeViews().forEach { (view, _) -> view.isEnabled = visible }
        binding.btnModePro.visibility = View.VISIBLE
        binding.btnGallery.visibility = View.GONE
        binding.videoLeftRail.visibility = View.GONE
        binding.btnSideFocusLock.visibility = View.GONE
        binding.btnSideFocusLock.isEnabled = false
        binding.btnSideAeLock.visibility = View.GONE
        binding.videoRightRail.visibility = View.GONE
    }

    private fun applyDockCaptureRowPosition(): Float {
        val modeRailHeightPx = resources.getDimension(R.dimen.mode_rail_height)
        binding.captureControlsRow.translationY = CameraDockLayoutPolicy.captureRowTranslationY(
            controlsVisible = controlsVisible,
            modeRailHeightPx = modeRailHeightPx
        )
        binding.root.post { syncOverlayPreviewArea() }
        return binding.modeGroup.translationY
    }

    private fun applyDeviceOrientation(update: CameraDeviceOrientationUpdate) {
        currentRotationDegrees = update.uiRotationDegrees
        vm.setDeviceRotationDegrees(update.cameraRotationDegrees)
        applyRotatingControlOrientation()
        syncOverlayPreviewArea()
    }

    private fun applyRotatingControlOrientation() {
        val controlRotation = CameraUiRotationPolicy.controlRotationDegrees(currentRotationDegrees)
        val controls = listOf(
            binding.btnSideFocusLock,
            binding.btnSideAeLock
        )
        controls.forEach { control ->
            if (control.rotation == controlRotation) return@forEach
            control.animate().cancel()
            control.animate()
                .rotation(controlRotation)
                .setDuration(140L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        binding.cameraOverlay.setDeviceUiRotationDegrees(currentRotationDegrees)
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            CameraPermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT).toTypedArray()
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showVideoSavedWatermark(message: String) {
        val style = CameraSaveWatermarkStylePolicy.resolve(message)
        val view = binding.tvSaveWatermark
        val motion = CameraMotionPolicy.saveWatermarkMotion()
        view.removeCallbacks(saveWatermarkHideRunnable)
        view.animate().cancel()
        view.text = style.text
        view.setTextColor(style.textColor)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(style.surfaceColor)
            cornerRadius = dp(style.cornerRadiusDp)
            setStroke(dpInt(1f), style.strokeColor)
        }
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = dp(8f)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(motion.durationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
        view.postDelayed(saveWatermarkHideRunnable, style.visibleDurationMs)
    }

    private fun hideSaveWatermark() {
        val view = binding.tvSaveWatermark
        if (view.visibility != View.VISIBLE) return
        val motion = CameraMotionPolicy.saveWatermarkMotion()
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(-dp(6f))
            .setDuration(motion.durationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 0f
                view.translationY = 0f
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            .start()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun dpInt(value: Float): Int = dp(value).toInt().coerceAtLeast(1)
    
    // -------------------------------------------------------------------------
    // HAPTIC FEEDBACK - Professional tactile response
    // -------------------------------------------------------------------------
    
    override fun performCameraHaptic(action: CameraHapticAction) {
        hapticExecutor.perform(action)
    }

    /** Light haptic tick - for UI navigation */
    private fun hapticTick() = performCameraHaptic(CameraHapticAction.NAVIGATION_TICK)

    /** Medium haptic click - for button presses */
    private fun hapticClick() = performCameraHaptic(CameraHapticAction.BUTTON_CLICK)

    /** Double haptic - for mode changes and important actions */
    private fun hapticDouble() = performCameraHaptic(CameraHapticAction.CAMERA_SWITCH)

    /**
     * Recompute and push the open-gate safe-area composition guides onto the overlay. Cleared to null
     * when the toggle is off or the preview geometry/size is not yet known, so the guides only render
     * when they can be mapped correctly. Driven by the frame-geometry and safe-area-toggle flows.
     */
    private fun refreshSafeAreaOverlays() {
        val geometry = vm.frameGeometry.value
        val width = binding.glSurface.width
        val height = binding.glSurface.height
        if (!vm.isSafeAreaGuidesEnabled || geometry == null || width <= 0 || height <= 0) {
            binding.cameraOverlay.setSafeAreaOverlays(null)
            return
        }
        binding.cameraOverlay.setSafeAreaOverlays(
            SafeAreaOverlayEngine.computeOverlays(
                geometry = geometry,
                displayWidth = width,
                displayHeight = height,
                // Only the 2.39:1 anamorphic scope guide; UIOverlay inscribes it into the REAL
                // preview rect (correct per 16:9 / 4:3, and follows portrait/landscape automatically).
                enabledPresets = setOf(SafeAreaPreset.CINEMA_2_39_1)
            )
        )
    }

    private fun syncOverlayPreviewArea() {
        val sourceAspect = vm.previewSourceAspect(binding.glSurface.width, binding.glSurface.height)
        val resolvedPreviewAspect = if (binding.glSurface.width > 0 && binding.glSurface.height > 0) {
            binding.glSurface.getResolvedDisplayAspect().takeIf { it > 0f }
        } else {
            null
        }
        val aspect = resolvedPreviewAspect
            ?: vm.previewAspect.value.resolve(binding.glSurface.width, binding.glSurface.height)
            ?: sourceAspect
        val fillViewport = PreviewViewportFillPolicy.shouldFillViewport(
            appMode = vm.uiMode.value,
            previewAspectRatio = vm.previewAspect.value
        )
        binding.cameraOverlay.setPreviewArea(
            aspect,
            fillViewport = fillViewport,
            sourceAspectRatio = sourceAspect
        )
        applyPreviewAnchoredDockPositions(
            aspect = aspect,
            fillViewport = fillViewport,
            sourceAspectRatio = sourceAspect
        )
        binding.cameraOverlay.showCropDebug = BuildConfig.DEBUG
        binding.cameraOverlay.setPreviewTransform(
            rotationDegrees = binding.glSurface.getEffectivePreviewRotationDegrees(),
            mirrored = binding.glSurface.isCurrentPreviewMirrored()
        )
        binding.cameraOverlay.setDeviceUiRotationDegrees(currentRotationDegrees)
    }

    private fun applyPreviewAnchoredDockPositions(
        aspect: Float?,
        fillViewport: Boolean,
        sourceAspectRatio: Float?
    ) {
        binding.rootLayout.post {
            if (!::binding.isInitialized || isFinishing || isDestroyed) return@post
            val previewWidth = binding.glSurface.width.toFloat()
            val previewHeight = binding.glSurface.height.toFloat()
            val viewportHeight = binding.rootLayout.height.toFloat()
            if (previewWidth <= 0f || previewHeight <= 0f || viewportHeight <= 0f) return@post

            val targetAspect = (aspect ?: sourceAspectRatio)?.takeIf { it.isFinite() && it > 0.01f }
            if (fillViewport || targetAspect == null) {
                binding.topStatusPanel.translationY = 0f
                binding.modeGroup.translationY = CameraDockLayoutPolicy.modeRailTranslationY(
                    controlsVisible = controlsVisible,
                    modeRailHeightPx = resources.getDimension(R.dimen.mode_rail_height)
                )
                binding.btnZoomToggle.translationY = binding.modeGroup.translationY
                binding.bottomBar.translationY = 0f
                return@post
            }

            val previewBounds = PreviewContentBoundsResolver.resolve(
                viewWidth = previewWidth,
                viewHeight = previewHeight,
                targetAspect = targetAspect,
                fillViewport = false
            )
            // Anchor the mode + shutter docks to a FIXED 4:3 preview rectangle so they hold the
            // same on-screen position across every aspect instead of sliding to the bottom when
            // the taller 16:9 preview pushes its bottom edge down. Clamp to the actual bottom so
            // 4:3 (and any shorter/wider aspect) stays exactly where it already sits. The top
            // sensor dock still tracks the real preview top edge, so it rides upward in 16:9.
            val fourThreeAnchorAspect = if (targetAspect < 1f) 3f / 4f else 4f / 3f
            val fourThreeBounds = PreviewContentBoundsResolver.resolve(
                viewWidth = previewWidth,
                viewHeight = previewHeight,
                targetAspect = fourThreeAnchorAspect,
                fillViewport = false
            )
            val glSurfaceTop = binding.glSurface.top
            // Lift the mode + shutter stack slightly upward off the fixed 4:3 bottom edge.
            val lowerStackLiftPx = resources.getDimension(R.dimen.camera_dock_lower_stack_lift)
            val dockAnchorBottomPx = minOf(
                glSurfaceTop + previewBounds.bottom,
                glSurfaceTop + fourThreeBounds.bottom
            ) - lowerStackLiftPx
            // Seat the sensor/aspect dock OUTSIDE the preview — above its top edge with a gap —
            // mirroring how the video-mode dock sits just outside the preview's bottom edge. A
            // negative overlap places the dock's bottom edge `gap` above the preview top.
            val statusOutsidePreviewPx = -resources.getDimension(R.dimen.sensor_dock_preview_gap)
            val plan = CameraDockLayoutPolicy.resolveAnchoredTranslations(
                previewTopPx = glSurfaceTop + previewBounds.top,
                previewBottomPx = dockAnchorBottomPx,
                statusTopPx = binding.topStatusPanel.top.toFloat(),
                statusHeightPx = binding.topStatusPanel.height.toFloat(),
                modeTopPx = binding.modeGroup.top.toFloat(),
                modeHeightPx = binding.modeGroup.height.toFloat(),
                captureTopPx = binding.bottomBar.top.toFloat(),
                captureHeightPx = binding.bottomBar.height.toFloat(),
                viewportHeightPx = viewportHeight,
                statusPreviewOverlapPx = statusOutsidePreviewPx,
                previewModeGapPx = resources.getDimension(R.dimen.mode_rail_preview_gap),
                modeCaptureGapPx = resources.getDimension(R.dimen.camera_dock_mode_gap)
            )

            binding.topStatusPanel.animate().cancel()
            binding.topStatusPanel.alpha = 1f
            binding.topStatusPanel.translationY = plan.statusTranslationY
            binding.modeGroup.translationY = plan.modeTranslationY
            binding.btnZoomToggle.translationY = plan.modeTranslationY
            binding.bottomBar.translationY = plan.captureTranslationY
        }
    }

    private fun renderFrameGeometryDebug(geometry: FrameGeometry?) {
        if (!BuildConfig.DEBUG ||
            geometry == null ||
            !DebugCommandIntentReader.hasDebugActionExtra(intent)
        ) {
            binding.tvPipelineDebug.visibility = View.GONE
            binding.tvPipelineDebug.text = ""
            return
        }
        binding.tvPipelineDebug.visibility = View.VISIBLE
        binding.tvPipelineDebug.text = geometry.debugSummary()
    }

    private fun applyFeatureMenuVisibility() {
        val showPeakingTray = false
        featuresMenuVisible = false
        peakingTrayVisible = false
        animatePanelVisibility(binding.topBarScroll, false, fromTop = true)
        animatePanelVisibility(binding.extraControlsTray, false, fromTop = true)
        animatePanelVisibility(
            binding.topStatusPanel,
            true,
            fromTop = true
        )
        binding.topWarningPanel.animate().cancel()
        binding.topWarningPanel.visibility = View.GONE
        binding.tvPeakingLabel.visibility = if (showPeakingTray) View.VISIBLE else View.GONE
        binding.peakingControls.visibility = if (showPeakingTray) View.VISIBLE else View.GONE
        binding.btnFeatureToggle.isSelected = vm.aiEnhancementEnabled
        binding.btnFeatureToggle.text = getString(R.string.control_ai)
    }

    private fun hideFeatureMenu() {
        if (!featuresMenuVisible && !peakingTrayVisible) return
        featuresMenuVisible = false
        peakingTrayVisible = false
        applyFeatureMenuVisibility()
    }
}
