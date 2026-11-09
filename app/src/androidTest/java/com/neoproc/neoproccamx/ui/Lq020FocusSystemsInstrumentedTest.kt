package com.neoproc.neoproccamx.ui

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.camera.CinematicFocusPullControl
import com.neoproc.neoproccamx.camera.CinematicFocusPullDebugCommands
import com.neoproc.neoproccamx.camera.CinematicFocusPullFlag
import com.neoproc.neoproccamx.camera.FlashMode
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.utils.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Physical Camera2 proof for LQ-020 / CP-120..123.
 *
 * The test observes the request attached to each TotalCaptureResult. It therefore distinguishes an
 * accepted AF/lens command from a UI animation or preference write. Optical resolution equivalence
 * still requires a calibrated near/far/face chart and MTF analysis; absent targets are reported as
 * BLOCKED_TARGET rather than converted into a false PASS.
 */
@RunWith(AndroidJUnit4::class)
class Lq020FocusSystemsInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun allFocusSystems_applyToRealCamera2Requests_withoutCadenceOrAuthorityRegression() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalPreferences = snapshotPreferences(context)
        val originalCinematicFlag = CinematicFocusPullFlag.enabled
        val stages = mutableListOf<JSONObject>()
        val checkpoints = linkedMapOf<String, JSONObject>()
        val functionalFailures = mutableListOf<String>()
        val ledger = CaptureLedger()
        val startedElapsedMs = SystemClock.elapsedRealtime()
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: CameraViewModel? = null
        var hook: CaptureHook? = null
        var failure: Throwable? = null
        var originalFaceDetectionEnabled = false
        var originalEyeAutofocusEnabled = false

        markStage(stages, startedElapsedMs, "PREFERENCES_SNAPSHOTTED")
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            vm = readVm(scenario)
            originalFaceDetectionEnabled = requireNotNull(vm).faceDetectionEnabled
            originalEyeAutofocusEnabled = requireNotNull(vm).eyeAutofocusEnabled
            awaitStreaming(requireNotNull(vm))
            hook = installCaptureHook(requireNotNull(vm), ledger)
            markStage(stages, startedElapsedMs, "CAMERA2_OBSERVER_ATTACHED")

            val continuous = exerciseContinuousAutofocus(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-120"] = continuous
            if (!continuous.optBoolean("functionalPassed")) {
                functionalFailures += "CP-120 continuous-AF request/cadence contract failed"
            }
            markStage(stages, startedElapsedMs, "CP120_CONTINUOUS_AF_CAPTURED")

            val tap = exerciseTapToFocus(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-121"] = tap
            if (!tap.optBoolean("functionalPassed")) {
                functionalFailures += "CP-121 tap-AF trigger/region/restore contract failed"
            }
            markStage(stages, startedElapsedMs, "CP121_TAP_AF_CAPTURED")

            val subject = exerciseSubjectFocus(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-122"] = subject
            if (!subject.optBoolean("engineActivationPassed")) {
                functionalFailures += "CP-122 face/eye engine did not activate in Camera2"
            }
            markStage(stages, startedElapsedMs, "CP122_SUBJECT_AF_CAPTURED")

            val cinematic = exerciseCinematicFocusPull(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-123"] = cinematic
            if (!cinematic.optBoolean("functionalPassed")) {
                functionalFailures += "CP-123 cinematic lens-request progression failed"
            }
            markStage(stages, startedElapsedMs, "CP123_CINEMATIC_PULL_CAPTURED")

            captureScreenshot(context, "lq020-final-preview.png")
            markStage(stages, startedElapsedMs, "FINAL_PREVIEW_CAPTURED")
            if (functionalFailures.isNotEmpty()) {
                throw AssertionError(functionalFailures.joinToString(" | "))
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            var cleanupFailure: Throwable? = null
            runCatching {
                val activeScenario = scenario
                val activeVm = vm
                if (activeScenario != null && activeVm != null) {
                    activeScenario.onActivity {
                        CinematicFocusPullFlag.set(originalCinematicFlag)
                        activeVm.setFaceDetectionEnabled(originalFaceDetectionEnabled)
                        activeVm.setEyeAutofocusEnabled(originalEyeAutofocusEnabled)
                        activeVm.camera.setAeAwbLock(false)
                        activeVm.setAutoFocus()
                        activeVm.setFlash(FlashMode.OFF)
                        activeVm.setRawEnabled(false)
                        activeVm.setCinemaVideoMode(CinemaVideoMode.PRO)
                        restoreCaptureHook(activeVm, hook)
                    }
                }
                markStage(stages, startedElapsedMs, "CAMERA_STATE_RESTORED")
            }.onFailure { cleanupFailure = cleanupFailure ?: it }
            runCatching { scenario?.close() }
                .onFailure { cleanupFailure = cleanupFailure ?: it }
            runCatching {
                restorePreferences(context, originalPreferences)
                markStage(stages, startedElapsedMs, "PREFERENCES_RESTORED")
            }.onFailure { cleanupFailure = cleanupFailure ?: it }
            runCatching {
                writeEvidence(
                    context = context,
                    checkpoints = checkpoints,
                    stages = stages,
                    frameCount = ledger.frames.size,
                    failure = failure ?: cleanupFailure
                )
            }.onFailure { cleanupFailure = cleanupFailure ?: it }
            if (failure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun exerciseContinuousAutofocus(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        val modes = JSONArray()
        var allFunctional = true
        FocusMode.entries.forEach { mode ->
            configureMode(scenario, vm, mode)
            val mark = ledger.beginPhase("CP120_${mode.name}")
            scenario.onActivity { vm.setAutoFocus() }
            val frames = awaitMatchingFrames(ledger, mark, MIN_CONTINUOUS_FRAMES, FRAME_WINDOW_TIMEOUT_MS) {
                it.requestAfMode.isContinuousAf() &&
                    it.resultAfMode.isContinuousAf() &&
                    it.requestFocusDistance == null
            }
            val gapMs = maximumSensorGapMs(frames)
            val modePassed = frames.size >= MIN_CONTINUOUS_FRAMES &&
                timestampsStrictlyIncrease(frames) && gapMs <= MAX_CAPTURE_RESULT_GAP_MS
            allFunctional = allFunctional && modePassed
            modes.put(JSONObject()
                .put("mode", mode.name)
                .put("functionalPassed", modePassed)
                .put("matchingFrames", frames.size)
                .put("maximumCaptureResultGapMs", gapMs)
                .put("afStates", JSONArray(frames.mapNotNull { it.resultAfState }.distinct()))
                .put("samples", JSONArray(frames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson))))
        }
        return JSONObject()
            .put("status", if (allFunctional) "BLOCKED_TARGET" else "FAIL")
            .put("functionalPassed", allFunctional)
            .put("threshold", "Video/Pro/Raw must sustain continuous AF Camera2 authority with >=12 monotonic result frames and no result gap >750 ms")
            .put("modes", modes)
            .put("blocker", if (allFunctional) "Near/far target must be moved through 20 calibrated cycles to certify physical convergence, settle and hunting" else JSONObject.NULL)
    }

    private fun exerciseTapToFocus(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        val modes = JSONArray()
        var allPassed = true
        FocusMode.entries.forEachIndexed { index, mode ->
            configureMode(scenario, vm, mode)
            val mark = ledger.beginPhase("CP121_${mode.name}")
            val x = listOf(0.35f, 0.50f, 0.65f)[index]
            val y = listOf(0.42f, 0.50f, 0.58f)[index]
            scenario.onActivity { vm.tapToFocus(x, y) }
            val trigger = awaitFrameMatch(ledger, mark, TAP_AF_TIMEOUT_MS) {
                it.requestAfTrigger == CaptureRequest.CONTROL_AF_TRIGGER_START
            }
            val terminal = trigger?.let { triggerFrame ->
                awaitFrameMatch(ledger, mark, TAP_AF_TIMEOUT_MS) { sample ->
                    sample.callbackElapsedNs >= triggerFrame.callbackElapsedNs &&
                        sample.resultAfState in TERMINAL_AF_STATES
                }
            }
            val restored = terminal?.let { terminalFrame ->
                awaitFrameMatch(ledger, mark, TAP_AF_TIMEOUT_MS) { sample ->
                    sample.callbackElapsedNs >= terminalFrame.callbackElapsedNs &&
                        sample.requestAfMode.isContinuousAf() &&
                        sample.requestAfTrigger != CaptureRequest.CONTROL_AF_TRIGGER_START
                }
            }
            val regionApplied = vm.camera.maxAfRegions <= 0 ||
                (trigger?.requestAfRegionCount ?: 0) > 0
            val latencyMs = trigger?.latencyMs(mark)
            val passed = trigger != null && terminal != null && restored != null && regionApplied &&
                (latencyMs ?: Double.MAX_VALUE) <= MAX_CONTROL_LATENCY_MS
            allPassed = allPassed && passed
            modes.put(JSONObject()
                .put("mode", mode.name)
                .put("tapNormalized", JSONArray(listOf(x, y)))
                .put("functionalPassed", passed)
                .put("triggerLatencyMs", latencyMs ?: JSONObject.NULL)
                .put("terminalAfState", terminal?.resultAfState ?: JSONObject.NULL)
                .put("afRegionCount", trigger?.requestAfRegionCount ?: 0)
                .put("continuousAfRestored", restored != null)
                .put("trigger", trigger?.toJson() ?: JSONObject.NULL)
                .put("terminal", terminal?.toJson() ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("status", if (allPassed) "PASS" else "FAIL")
            .put("functionalPassed", allPassed)
            .put("threshold", "Each Video/Pro/Raw tap must submit AUTO+AF_TRIGGER_START with a supported metering region, reach a terminal AF state within 1500 ms, then restore continuous AF")
            .put("modes", modes)
            .put("opticalCaveat", "Local MTF improvement requires a calibrated multi-plane chart; this PASS covers the real Camera2 AF transaction")
    }

    private fun exerciseSubjectFocus(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        configureMode(scenario, vm, FocusMode.VIDEO)
        val mark = ledger.beginPhase("CP122_FACE_EYE_SUBJECT")
        scenario.onActivity {
            vm.setFaceDetectionEnabled(true)
            vm.setEyeAutofocusEnabled(true)
        }
        val faceModeFrames = awaitMatchingFrames(ledger, mark, MIN_SUBJECT_FRAMES, SUBJECT_TIMEOUT_MS) {
            (it.requestFaceDetectMode ?: CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) !=
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        }
        var subject = vm.primarySubject.value
        var focus = vm.primaryFocus.value
        awaitCondition(SUBJECT_TIMEOUT_MS) {
            subject = vm.primarySubject.value
            focus = vm.primaryFocus.value
            subject != null && focus != null
        }
        val resultFaceCount = faceModeFrames.maxOfOrNull { it.resultFaceCount } ?: 0
        val targetObserved = subject != null && focus != null
        val engineActivationPassed = faceModeFrames.size >= MIN_SUBJECT_FRAMES
        val status = when {
            !engineActivationPassed -> "FAIL"
            !targetObserved -> "BLOCKED_TARGET"
            else -> "PASS"
        }
        return JSONObject()
            .put("status", status)
            .put("engineActivationPassed", engineActivationPassed)
            .put("targetObserved", targetObserved)
            .put("camera2FaceDetectFrames", faceModeFrames.size)
            .put("camera2FacesObserved", resultFaceCount)
            .put("primarySubjectType", subject?.subjectType?.name ?: JSONObject.NULL)
            .put("primarySubjectCategory", subject?.category?.name ?: JSONObject.NULL)
            .put("primarySubjectConfidence", subject?.confidence ?: JSONObject.NULL)
            .put("primaryFocusType", focus?.type?.name ?: JSONObject.NULL)
            .put("threshold", "Face/eye tracking must be enabled in real Camera2 requests; PASS additionally requires a live target producing a primary subject and focus target")
            .put("blocker", if (status == "BLOCKED_TARGET") "No visible consenting face/eye target was detected during the device window" else JSONObject.NULL)
            .put("samples", JSONArray(faceModeFrames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
    }

    private fun exerciseCinematicFocusPull(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        configureMode(scenario, vm, FocusMode.PRO)
        val maximumDiopters = vm.camera.focusDistanceRange
        val calibration = vm.camera.characteristics?.get(
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION
        )
        if (maximumDiopters <= 0f) {
            return JSONObject()
                .put("status", "BLOCKED_HARDWARE")
                .put("functionalPassed", true)
                .put("maximumFocusDiopters", maximumDiopters)
                .put("blocker", "Active camera reports a fixed-focus lens")
        }
        val markA = max(0.05f, maximumDiopters * 0.15f).coerceAtMost(maximumDiopters)
        val markB = max(markA + maximumDiopters * 0.45f, maximumDiopters * 0.80f)
            .coerceAtMost(maximumDiopters)
        scenario.onActivity {
            vm.camera.setAeAwbLock(true)
            CinematicFocusPullFlag.set(true)
            CinematicFocusPullDebugCommands.setMark(isA = true, diopters = markA)
            CinematicFocusPullDebugCommands.setMark(isA = false, diopters = markB)
            CinematicFocusPullDebugCommands.configure(
                CinematicFocusPullControl.EasingCurve.EASE_IN_OUT,
                CINEMATIC_PULL_DURATION_MS
            )
        }
        SystemClock.sleep(LOCK_SETTLE_MS)
        val mark = ledger.beginPhase("CP123_CINEMATIC_PULL")
        var started = false
        scenario.onActivity { started = CinematicFocusPullDebugCommands.trigger() }
        awaitCondition(CINEMATIC_PULL_TIMEOUT_MS) {
            val distances = ledger.framesFor(mark).mapNotNull { it.requestFocusDistance }
            distances.any { abs(it - markB) <= endpointTolerance(maximumDiopters) }
        }
        val pullFrames = ledger.framesFor(mark).filter {
            it.requestAfMode == CaptureRequest.CONTROL_AF_MODE_OFF &&
                it.requestFocusDistance != null
        }
        val requestDistances = pullFrames.mapNotNull { it.requestFocusDistance }
        val distinctRequests = requestDistances
            .map { (it * 10_000f).roundToInt() }
            .distinct()
        val monotonic = requestDistances.zipWithNext().all { (a, b) ->
            b + monotonicTolerance(maximumDiopters) >= a
        }
        val endpointReached = requestDistances.any {
            abs(it - markB) <= endpointTolerance(maximumDiopters)
        }
        val resultPairs = pullFrames.mapNotNull { sample ->
            val request = sample.requestFocusDistance
            val result = sample.resultFocusDistance
            if (request != null && result != null) request to result else null
        }
        val resultFollowed = resultPairs.count { (request, result) ->
            abs(result - request) <= resultTolerance(maximumDiopters)
        } >= minOf(MIN_RESULT_FOLLOW_FRAMES, resultPairs.size).coerceAtLeast(1)
        val lockedFrames = pullFrames.filter { it.requestAeLock == true && it.requestAwbLock == true }
        val exposureRangeRatio = coefficientOfRange(lockedFrames.map { it.exposureProduct })
        val maximumGapMs = maximumSensorGapMs(pullFrames)
        val functionalPassed = started && pullFrames.size >= MIN_CINEMATIC_FRAMES &&
            distinctRequests.size >= MIN_DISTINCT_PULL_REQUESTS && monotonic && endpointReached &&
            maximumGapMs <= MAX_CAPTURE_RESULT_GAP_MS &&
            lockedFrames.size >= MIN_CINEMATIC_FRAMES && exposureRangeRatio <= MAX_LOCKED_EXPOSURE_RANGE_RATIO
        val physicalReadbackPassed = resultPairs.isNotEmpty() && resultFollowed
        val status = when {
            !functionalPassed -> "FAIL"
            physicalReadbackPassed -> "PASS"
            else -> "BLOCKED_LENS_READBACK"
        }
        return JSONObject()
            .put("status", status)
            .put("functionalPassed", functionalPassed)
            .put("physicalReadbackPassed", physicalReadbackPassed)
            .put("started", started)
            .put("focusDistanceCalibration", calibration ?: JSONObject.NULL)
            .put("maximumFocusDiopters", maximumDiopters)
            .put("markADiopters", markA)
            .put("markBDiopters", markB)
            .put("requestFrameCount", pullFrames.size)
            .put("distinctLensRequests", distinctRequests.size)
            .put("monotonic", monotonic)
            .put("endpointReached", endpointReached)
            .put("resultPairs", resultPairs.size)
            .put("resultFollowed", resultFollowed)
            .put("lensStates", JSONArray(pullFrames.mapNotNull { it.lensState }.distinct()))
            .put("maximumCaptureResultGapMs", maximumGapMs)
            .put("lockedExposureProductRangeRatio", exposureRangeRatio)
            .put("threshold", ">=8 Camera2 frames, >=5 distinct monotonic AF_MODE_OFF lens requests, exact bounded endpoint, no capture-result gap >750 ms, and AE/AWB lock retained")
            .put("blocker", if (status == "BLOCKED_LENS_READBACK") "HAL did not expose attributable lens-position readback; optical chart video is required" else JSONObject.NULL)
            .put("samples", JSONArray(pullFrames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
    }

    private fun configureMode(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        mode: FocusMode
    ) {
        val priorTimestamp = vm.liveResult.value?.timestampNs ?: 0L
        scenario.onActivity {
            vm.setMode(AppMode.VIDEO)
            vm.setFlash(FlashMode.OFF)
            vm.setFaceDetectionEnabled(false)
            vm.setEyeAutofocusEnabled(false)
            vm.setVideoHdrMode("OFF")
            vm.setLogEnabled(false)
            when (mode) {
                FocusMode.VIDEO -> {
                    vm.setRawEnabled(false)
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
                    vm.applyManualControlsForPreset(CameraModePreset.VIDEO)
                }
                FocusMode.PRO -> {
                    vm.setRawEnabled(false)
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setSdrQualityIntent(SdrQualityIntent.PRO)
                    vm.applyManualControlsForPreset(CameraModePreset.PRO)
                }
                FocusMode.RAW -> {
                    vm.setCinemaVideoMode(CinemaVideoMode.RAW)
                    vm.setRawEnabled(true)
                    vm.applyManualControlsForPreset(CameraModePreset.RAW_MODE)
                }
            }
            vm.setAutoFocus()
        }
        check(awaitCondition(MODE_TIMEOUT_MS) {
            val modeMatches = when (mode) {
                FocusMode.VIDEO, FocusMode.PRO ->
                    vm.cinemaVideoMode.value == CinemaVideoMode.PRO && !vm.rawOnFlow.value
                FocusMode.RAW ->
                    vm.cinemaVideoMode.value == CinemaVideoMode.RAW && vm.rawOnFlow.value
            }
            modeMatches && vm.cameraState.value is CameraState.Streaming &&
                (vm.liveResult.value?.timestampNs ?: 0L) > priorTimestamp
        }) { "$mode did not reach a fresh streaming Camera2 session" }
        SystemClock.sleep(MODE_SETTLE_MS)
    }

    private fun installCaptureHook(vm: CameraViewModel, ledger: CaptureLedger): CaptureHook {
        val previous = vm.camera.onCaptureResult
        lateinit var observer: (TotalCaptureResult) -> Unit
        observer = { result ->
            try {
                previous?.invoke(result)
            } finally {
                ledger.record(result)
            }
        }
        vm.camera.onCaptureResult = observer
        return CaptureHook(previous, observer)
    }

    private fun restoreCaptureHook(vm: CameraViewModel, hook: CaptureHook?) {
        if (hook != null && vm.camera.onCaptureResult === hook.observer) {
            vm.camera.onCaptureResult = hook.previous
        }
    }

    private fun awaitStreaming(vm: CameraViewModel) {
        check(awaitCondition(SESSION_TIMEOUT_MS) {
            vm.cameraState.value is CameraState.Streaming &&
                (vm.liveResult.value?.timestampNs ?: 0L) > 0L
        }) { "Camera never reached streaming with live Camera2 metadata" }
    }

    private fun awaitFrameMatch(
        ledger: CaptureLedger,
        mark: PhaseMark,
        timeoutMs: Long,
        predicate: (FrameSample) -> Boolean
    ): FrameSample? {
        var match: FrameSample? = null
        awaitCondition(timeoutMs) {
            match = ledger.framesFor(mark).firstOrNull(predicate)
            match != null
        }
        return match
    }

    private fun awaitMatchingFrames(
        ledger: CaptureLedger,
        mark: PhaseMark,
        minimum: Int,
        timeoutMs: Long,
        predicate: (FrameSample) -> Boolean
    ): List<FrameSample> {
        var matches = emptyList<FrameSample>()
        awaitCondition(timeoutMs) {
            matches = ledger.framesFor(mark).filter(predicate)
            matches.size >= minimum
        }
        return ledger.framesFor(mark).filter(predicate)
    }

    private fun readVm(scenario: ActivityScenario<MainActivity>): CameraViewModel {
        val result = AtomicReference<CameraViewModel>()
        scenario.onActivity { result.set(it.vm) }
        return requireNotNull(result.get())
    }

    private fun captureScreenshot(context: Context, name: String) {
        val bitmap = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "Could not capture LQ-020 final screenshot" }
        File(evidenceDirectory(context), name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun writeEvidence(
        context: Context,
        checkpoints: Map<String, JSONObject>,
        stages: List<JSONObject>,
        frameCount: Int,
        failure: Throwable?
    ) {
        val sourceApk = File(context.applicationInfo.sourceDir)
        val storage = StatFs(context.filesDir.absolutePath)
        val power = context.getSystemService(PowerManager::class.java)
        val statuses = REQUIRED_CHECKPOINTS.associateWith {
            checkpoints[it]?.optString("status", "PENDING") ?: "PENDING"
        }
        val report = JSONObject()
            .put("schema", "neo-qa-lq020-focus-systems/v1")
            .put("checkpoint", "LQ-020")
            .put("canonicalCheckpoints", JSONArray(REQUIRED_CHECKPOINTS))
            .put("capturedUtcMs", System.currentTimeMillis())
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("api", Build.VERSION.SDK_INT)
                .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())))
            .put("admission", JSONObject()
                .put("thermalStatus", power.currentThermalStatus)
                .put("batteryOptimizationIgnored", power.isIgnoringBatteryOptimizations(context.packageName))
                .put("availableStorageBytes", storage.availableBytes))
            .put("apkSha256", sha256(sourceApk))
            .put("captureResultFramesObserved", frameCount)
            .put("statuses", JSONObject(statuses))
            .put("checkpoints", JSONObject(checkpoints))
            .put("stages", JSONArray(stages))
            .put("functionalTestPassed", failure == null)
            .put("canonicalPass", REQUIRED_CHECKPOINTS.all { statuses[it] == "PASS" })
            .put("qualityClaim", "Real Camera2 AF/lens authority, cadence and control stability only; no unmeasured nano/IMAX/telescope optical-resolution equivalence claim")
            .put("failure", failure?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL)
        val directory = evidenceDirectory(context)
        val temporary = File(directory, "lq020.json.tmp")
        val destination = File(directory, "lq020.json")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        if (destination.exists()) check(destination.delete()) { "Could not replace prior LQ-020 evidence" }
        check(temporary.renameTo(destination)) { "Could not atomically publish LQ-020 evidence" }
    }

    private fun evidenceDirectory(context: Context): File =
        requireNotNull(context.getExternalFilesDir(EVIDENCE_DIRECTORY)).apply { mkdirs() }

    private fun snapshotPreferences(context: Context): Map<String, Any?> =
        context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE).all
            .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }

    private fun restorePreferences(context: Context, values: Map<String, Any?>) {
        val editor = context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "Could not restore user preferences after LQ-020" }
    }

    private fun markStage(stages: MutableList<JSONObject>, startedElapsedMs: Long, name: String) {
        stages += JSONObject()
            .put("name", name)
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedElapsedMs)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun Int?.isContinuousAf(): Boolean =
        this == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
            this == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO

    private fun timestampsStrictlyIncrease(frames: List<FrameSample>): Boolean =
        frames.zipWithNext().all { (a, b) -> b.sensorTimestampNs > a.sensorTimestampNs }

    private fun maximumSensorGapMs(frames: List<FrameSample>): Double =
        frames.zipWithNext().maxOfOrNull { (a, b) ->
            (b.sensorTimestampNs - a.sensorTimestampNs) / 1_000_000.0
        } ?: 0.0

    private fun coefficientOfRange(values: List<Double>): Double {
        val valid = values.filter { it.isFinite() && it > 0.0 }
        if (valid.size < 2) return 0.0
        val average = valid.average()
        return if (average <= 0.0) Double.POSITIVE_INFINITY else
            (valid.maxOrNull()!! - valid.minOrNull()!!) / average
    }

    private fun endpointTolerance(maximumDiopters: Float): Float =
        max(0.03f, maximumDiopters * 0.015f)

    private fun monotonicTolerance(maximumDiopters: Float): Float =
        max(0.01f, maximumDiopters * 0.005f)

    private fun resultTolerance(maximumDiopters: Float): Float =
        max(0.10f, maximumDiopters * 0.05f)

    private enum class FocusMode { VIDEO, PRO, RAW }

    private data class CaptureHook(
        val previous: ((TotalCaptureResult) -> Unit)?,
        val observer: (TotalCaptureResult) -> Unit
    )

    private data class PhaseMark(
        val name: String,
        val startedElapsedNs: Long,
        val generation: Long
    )

    private data class FrameSample(
        val phase: String,
        val generation: Long,
        val callbackElapsedNs: Long,
        val frameNumber: Long,
        val sensorTimestampNs: Long,
        val requestAfMode: Int?,
        val requestAfTrigger: Int?,
        val requestAfRegionCount: Int,
        val requestFocusDistance: Float?,
        val requestFaceDetectMode: Int?,
        val requestAeLock: Boolean?,
        val requestAwbLock: Boolean?,
        val resultAfMode: Int?,
        val resultAfState: Int?,
        val resultAfRegionCount: Int,
        val resultFocusDistance: Float?,
        val lensState: Int?,
        val resultFaceCount: Int,
        val resultIso: Int,
        val resultExposureNs: Long
    ) {
        val exposureProduct: Double
            get() = resultIso.toDouble() * resultExposureNs.toDouble()

        fun latencyMs(mark: PhaseMark): Double =
            (callbackElapsedNs - mark.startedElapsedNs) / 1_000_000.0

        fun toJson(): JSONObject = JSONObject()
            .put("phase", phase)
            .put("frameNumber", frameNumber)
            .put("sensorTimestampNs", sensorTimestampNs)
            .put("requestAfMode", requestAfMode ?: JSONObject.NULL)
            .put("requestAfTrigger", requestAfTrigger ?: JSONObject.NULL)
            .put("requestAfRegionCount", requestAfRegionCount)
            .put("requestFocusDistance", requestFocusDistance ?: JSONObject.NULL)
            .put("requestFaceDetectMode", requestFaceDetectMode ?: JSONObject.NULL)
            .put("requestAeLock", requestAeLock ?: JSONObject.NULL)
            .put("requestAwbLock", requestAwbLock ?: JSONObject.NULL)
            .put("resultAfMode", resultAfMode ?: JSONObject.NULL)
            .put("resultAfState", resultAfState ?: JSONObject.NULL)
            .put("resultAfRegionCount", resultAfRegionCount)
            .put("resultFocusDistance", resultFocusDistance ?: JSONObject.NULL)
            .put("lensState", lensState ?: JSONObject.NULL)
            .put("resultFaceCount", resultFaceCount)
            .put("resultIso", resultIso)
            .put("resultExposureNs", resultExposureNs)
    }

    private class CaptureLedger {
        private val phase = AtomicReference(PhaseMark("BOOT", SystemClock.elapsedRealtimeNanos(), 0L))
        val frames = ConcurrentLinkedQueue<FrameSample>()

        fun beginPhase(name: String): PhaseMark {
            val next = PhaseMark(name, SystemClock.elapsedRealtimeNanos(), phase.get().generation + 1L)
            phase.set(next)
            return next
        }

        fun record(result: TotalCaptureResult) {
            val current = phase.get()
            val request = result.request
            frames += FrameSample(
                phase = current.name,
                generation = current.generation,
                callbackElapsedNs = SystemClock.elapsedRealtimeNanos(),
                frameNumber = result.frameNumber,
                sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                requestAfMode = request.get(CaptureRequest.CONTROL_AF_MODE),
                requestAfTrigger = request.get(CaptureRequest.CONTROL_AF_TRIGGER),
                requestAfRegionCount = request.get(CaptureRequest.CONTROL_AF_REGIONS)?.size ?: 0,
                requestFocusDistance = request.get(CaptureRequest.LENS_FOCUS_DISTANCE),
                requestFaceDetectMode = request.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE),
                requestAeLock = request.get(CaptureRequest.CONTROL_AE_LOCK),
                requestAwbLock = request.get(CaptureRequest.CONTROL_AWB_LOCK),
                resultAfMode = result.get(CaptureResult.CONTROL_AF_MODE),
                resultAfState = result.get(CaptureResult.CONTROL_AF_STATE),
                resultAfRegionCount = result.get(CaptureResult.CONTROL_AF_REGIONS)?.size ?: 0,
                resultFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                lensState = result.get(CaptureResult.LENS_STATE),
                resultFaceCount = result.get(CaptureResult.STATISTICS_FACES)?.size ?: 0,
                resultIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                resultExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            )
        }

        fun framesFor(mark: PhaseMark): List<FrameSample> =
            frames.filter { it.generation == mark.generation && it.callbackElapsedNs >= mark.startedElapsedNs }
    }

    private companion object {
        const val EVIDENCE_DIRECTORY = "lq020"
        const val POLL_INTERVAL_MS = 40L
        const val SESSION_TIMEOUT_MS = 25_000L
        const val MODE_TIMEOUT_MS = 40_000L
        const val MODE_SETTLE_MS = 900L
        const val FRAME_WINDOW_TIMEOUT_MS = 6_000L
        const val SUBJECT_TIMEOUT_MS = 8_000L
        const val TAP_AF_TIMEOUT_MS = 3_000L
        const val LOCK_SETTLE_MS = 600L
        const val CINEMATIC_PULL_DURATION_MS = 1_200L
        const val CINEMATIC_PULL_TIMEOUT_MS = 4_000L
        const val MAX_CONTROL_LATENCY_MS = 1_500.0
        const val MAX_CAPTURE_RESULT_GAP_MS = 750.0
        const val MAX_LOCKED_EXPOSURE_RANGE_RATIO = 0.15
        const val MIN_CONTINUOUS_FRAMES = 12
        const val MIN_SUBJECT_FRAMES = 8
        const val MIN_CINEMATIC_FRAMES = 8
        const val MIN_DISTINCT_PULL_REQUESTS = 5
        const val MIN_RESULT_FOLLOW_FRAMES = 4
        const val EVIDENCE_SAMPLE_LIMIT = 16
        val TERMINAL_AF_STATES = setOf(
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        )
        val REQUIRED_CHECKPOINTS = listOf("CP-120", "CP-121", "CP-122", "CP-123")
    }
}
