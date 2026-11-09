package com.neoproc.neoproccamx.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.camera.FlashMode
import com.neoproc.neoproccamx.camera.WhiteBalanceChromaticity
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.utils.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Physical-device proof for LQ-018 / CP-111..115.
 *
 * This test observes the exact request carried by each [TotalCaptureResult], rather than treating a
 * UI value as proof that the camera HAL accepted it. It also preserves and restores the user's
 * preferences and every temporary camera-control mutation.
 *
 * CP-113 requires a genuine physical pan. The gyro gate prevents a stationary phone, an orientation
 * API call, or a synthetic host simulation from being mislabeled as a camera-direction test.
 */
@RunWith(AndroidJUnit4::class)
class Lq018WhiteBalanceIsoInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun camera2WhiteBalanceAndAutoIso_areAppliedMeasuredAndStable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalPreferences = snapshotPreferences(context)
        val stages = mutableListOf<JSONObject>()
        val failures = mutableListOf<String>()
        val checkpointResults = linkedMapOf<String, JSONObject>()
        val ledger = CaptureLedger()
        val testStartedElapsedMs = SystemClock.elapsedRealtime()
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: CameraViewModel? = null
        var captureHook: CaptureHook? = null
        var motionHook: MotionHook? = null
        var failure: Throwable? = null

        markStage(stages, testStartedElapsedMs, "PREFERENCES_SNAPSHOTTED")
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            vm = readVm(scenario)
            awaitStreaming(requireNotNull(vm))
            configureProBaseline(scenario, requireNotNull(vm))
            awaitStableFrames(requireNotNull(vm))
            captureHook = installCaptureHook(requireNotNull(vm), ledger)
            motionHook = installMotionHook(requireNotNull(vm), ledger)
            markStage(stages, testStartedElapsedMs, "CAMERA2_OBSERVERS_ATTACHED")

            // CP-111 prerequisite baseline: AUTO must be a genuine HAL auto request with live gains.
            val initialAuto = exerciseAutoWhiteBalance(
                scenario = scenario,
                vm = requireNotNull(vm),
                ledger = ledger,
                phase = "CP111_AUTO_BASELINE"
            )
            markStage(stages, testStartedElapsedMs, "CP111_AUTO_BASELINE_CAPTURED")

            // CP-112: verify two separated CCT/tint points, including exact request payload lineage.
            val manualWhiteBalance = exerciseManualWhiteBalance(
                scenario = scenario,
                vm = requireNotNull(vm),
                ledger = ledger
            )
            checkpointResults["CP-112"] = manualWhiteBalance
            if (!manualWhiteBalance.optBoolean("passed")) {
                failures += "CP-112 manual Kelvin/tint was not accepted as a complete Camera2 payload"
            }
            markStage(stages, testStartedElapsedMs, "CP112_MANUAL_WB_CAPTURED")

            // Return from manual WB to AUTO. This proves response, not merely an already-auto boot state.
            val autoRestore = exerciseAutoWhiteBalance(
                scenario = scenario,
                vm = requireNotNull(vm),
                ledger = ledger,
                phase = "CP111_AUTO_RESTORE"
            )
            val cp111Passed = initialAuto.optBoolean("passed") && autoRestore.optBoolean("passed")
            checkpointResults["CP-111"] = JSONObject()
                .put("passed", cp111Passed)
                .put("threshold", "AUTO request/result with finite gains and >=8 monotonic frames within 1500 ms")
                .put("initial", initialAuto)
                .put("afterManualRestore", autoRestore)
            if (!cp111Passed) failures += "CP-111 automatic WB did not produce a truthful HAL response"
            markStage(stages, testStartedElapsedMs, "CP111_AUTO_RESTORE_CAPTURED")

            // CP-113: a real physical pan is mandatory. The UI prompt is visible for the measurement
            // window; the oracle independently requires gyro motion before it evaluates WB stability.
            val directionStability = exerciseDirectionChangeStability(
                scenario = scenario,
                vm = requireNotNull(vm),
                ledger = ledger
            )
            checkpointResults["CP-113"] = directionStability
            if (!directionStability.optBoolean("passed")) {
                failures += "CP-113 ${directionStability.optString("reason", "WB direction stability failed")}"
            }
            markStage(stages, testStartedElapsedMs, "CP113_DIRECTION_STABILITY_CAPTURED")

            // CP-114: use the adaptive LOG monitor request so automatic SDR shot-lock cannot mask an
            // explicit unlock. Stored/recorded quality is untouched; this only selects a temporary
            // preview request policy and is restored immediately afterwards.
            val lockUnlock = exerciseAwbLockUnlock(
                scenario = scenario,
                vm = requireNotNull(vm),
                ledger = ledger
            )
            checkpointResults["CP-114"] = lockUnlock
            if (!lockUnlock.optBoolean("passed")) {
                failures += "CP-114 AWB lock/unlock did not round-trip through Camera2"
            }
            markStage(stages, testStartedElapsedMs, "CP114_LOCK_UNLOCK_CAPTURED")

            // CP-115: prove authority transfer from a manual ISO request back to platform AUTO AE.
            val autoIso = exerciseAutoIso(
                scenario = scenario,
                vm = requireNotNull(vm),
                ledger = ledger
            )
            checkpointResults["CP-115"] = autoIso
            if (!autoIso.optBoolean("passed")) {
                failures += "CP-115 automatic ISO authority did not recover from manual ISO"
            }
            markStage(stages, testStartedElapsedMs, "CP115_AUTO_ISO_CAPTURED")

            captureScreenshot(context, "lq018-final-preview.png")
            markStage(stages, testStartedElapsedMs, "FINAL_PREVIEW_CAPTURED")
            if (failures.isNotEmpty()) throw AssertionError(failures.joinToString(separator = " | "))
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
                        activeVm.setFlash(FlashMode.OFF)
                        activeVm.camera.setAeAwbLock(false)
                        activeVm.camera.setPreviewLookControls(
                            logEnabled = false,
                            hdrEnabled = false,
                            rawEnabled = false
                        )
                        activeVm.setAutoISO()
                        activeVm.setAutoExposure()
                        activeVm.setAutoWB()
                        restoreCaptureHook(activeVm, captureHook)
                        restoreMotionHook(activeVm, motionHook)
                    }
                }
                markStage(stages, testStartedElapsedMs, "CAMERA_STATE_RESTORED")
            }.onFailure {
                cleanupFailure = cleanupFailure ?: it
            }
            runCatching { scenario?.close() }.onFailure {
                cleanupFailure = cleanupFailure ?: it
            }
            runCatching {
                restorePreferences(context, originalPreferences)
                markStage(stages, testStartedElapsedMs, "PREFERENCES_RESTORED")
            }.onFailure {
                cleanupFailure = cleanupFailure ?: it
            }
            runCatching {
                writeEvidence(
                    context = context,
                    checkpoints = checkpointResults,
                    stages = stages,
                    frameCount = ledger.frames.size,
                    gyroSampleCount = ledger.gyroSamples.size,
                    failure = failure ?: cleanupFailure
                )
            }.onFailure {
                cleanupFailure = cleanupFailure ?: it
            }
            if (failure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun configureProBaseline(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel
    ) {
        scenario.onActivity {
            // The current home rail keeps a single VIDEO surface; the Pro preset is represented by
            // CinemaVideoMode.PRO plus manual-control visibility. AppMode.PRO is a legacy enum value
            // deliberately normalized by CameraModeController and must not be used as an oracle.
            vm.setMode(AppMode.VIDEO)
            vm.setCinemaVideoMode(CinemaVideoMode.PRO)
            if (!vm.isManualControlsVisible) vm.toggleManualControls()
            vm.setSdrQualityIntent(SdrQualityIntent.PRO)
            vm.applyManualControlsForPreset(CameraModePreset.PRO)
            vm.setRawEnabled(false)
            vm.setVideoHdrMode("OFF")
            vm.setLogEnabled(false)
            vm.setFlash(FlashMode.OFF)
            vm.camera.setAeAwbLock(false)
            vm.setAutoExposure()
            vm.setAutoISO()
            vm.setAutoWB()
        }
        check(awaitCondition(SESSION_TIMEOUT_MS) {
            vm.uiMode.value == AppMode.VIDEO &&
                vm.cinemaVideoMode.value == CinemaVideoMode.PRO &&
                vm.cameraState.value is CameraState.Streaming
        }) { "PRO SDR baseline did not become streaming" }
    }

    private fun exerciseAutoWhiteBalance(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger,
        phase: String
    ): JSONObject {
        val mark = ledger.beginPhase(phase)
        scenario.onActivity {
            vm.camera.setAeAwbLock(false)
            vm.setAutoWB()
        }
        val predicate: (FrameSample) -> Boolean = { sample ->
            sample.requestAwbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO &&
                sample.resultAwbMode == CaptureResult.CONTROL_AWB_MODE_AUTO &&
                sample.hasFinitePositiveGains()
        }
        val matched = awaitFrameMatch(ledger, mark, CONTROL_RESPONSE_TIMEOUT_MS, predicate)
        val autoFrames = awaitMatchingFrames(
            ledger = ledger,
            mark = mark,
            minimum = MIN_EVIDENCE_FRAMES,
            timeoutMs = FRAME_WINDOW_TIMEOUT_MS,
            predicate = predicate
        )
        val monotonic = timestampsStrictlyIncrease(autoFrames)
        val statesResponsive = autoFrames.any {
            it.resultAwbState == CaptureResult.CONTROL_AWB_STATE_SEARCHING ||
                it.resultAwbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                it.resultAwbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
        }
        val noStaleManualPayload = autoFrames.all { it.requestCct == null && it.requestTint == null }
        val latencyMs = matched?.let { (it.callbackElapsedNs - mark.startedElapsedNs) / 1_000_000.0 }
        val passed = matched != null &&
            autoFrames.size >= MIN_EVIDENCE_FRAMES &&
            monotonic &&
            statesResponsive &&
            noStaleManualPayload &&
            vm.cameraParams.value.wbKelvin < 0 &&
            (latencyMs ?: Double.MAX_VALUE) <= CONTROL_RESPONSE_LIMIT_MS
        return JSONObject()
            .put("passed", passed)
            .put("phase", phase)
            .put("matchedFrames", autoFrames.size)
            .put("latencyMs", latencyMs ?: JSONObject.NULL)
            .put("timestampMonotonic", monotonic)
            .put("awbStateResponsive", statesResponsive)
            .put("staleManualPayloadAbsent", noStaleManualPayload)
            .put("selectedKelvin", vm.cameraParams.value.wbKelvin)
            .put("samples", JSONArray(autoFrames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
    }

    @Suppress("DEPRECATION")
    private fun exerciseManualWhiteBalance(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        val range = vm.camera.manualWhiteBalanceRange
        val span = (range.upper - range.lower).coerceAtLeast(1)
        val coolKelvin = (range.lower + span / 4).coerceIn(range.lower, range.upper)
        val warmKelvin = (range.upper - span / 4).coerceIn(range.lower, range.upper)
        val coolTint = -90
        val warmTint = 90
        val cool = exerciseManualWhiteBalancePoint(
            scenario = scenario,
            vm = vm,
            ledger = ledger,
            phase = "CP112_COOL_GREEN",
            kelvin = coolKelvin,
            tint = coolTint,
            excludedPayloadSignature = null
        )
        val warm = exerciseManualWhiteBalancePoint(
            scenario = scenario,
            vm = vm,
            ledger = ledger,
            phase = "CP112_WARM_MAGENTA",
            kelvin = warmKelvin,
            tint = warmTint,
            excludedPayloadSignature = cool.optString("payloadSignature").takeIf(String::isNotBlank)
        )
        val coolSignature = cool.optString("payloadSignature")
        val warmSignature = warm.optString("payloadSignature")
        val distinctPayloads = coolSignature.isNotBlank() &&
            warmSignature.isNotBlank() &&
            coolSignature != warmSignature
        return JSONObject()
            .put("passed", cool.optBoolean("passed") && warm.optBoolean("passed") && distinctPayloads)
            .put("threshold", "Two separated Kelvin/tint selections must produce complete, distinct AWB_OFF payloads within 1500 ms")
            .put("hardwareRangeKelvin", "${range.lower}..${range.upper}")
            .put("distinctPayloads", distinctPayloads)
            .put("coolGreen", cool)
            .put("warmMagenta", warm)
    }

    @Suppress("DEPRECATION")
    private fun exerciseManualWhiteBalancePoint(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger,
        phase: String,
        kelvin: Int,
        tint: Int,
        excludedPayloadSignature: String?
    ): JSONObject {
        val expectedPlatformTint = (tint.coerceIn(-150, 150) / 3.0).roundToInt().coerceIn(-50, 50)
        val mark = ledger.beginPhase(phase)
        scenario.onActivity { vm.setWB(kelvin, tint) }
        val predicate: (FrameSample) -> Boolean = { sample ->
            val requestedPointMatches = when (sample.requestColorMode) {
                CameraMetadata.COLOR_CORRECTION_MODE_CCT ->
                    sample.requestCct == kelvin && sample.requestTint == expectedPlatformTint
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX ->
                    sample.requestGains != null && sample.requestCcm != null
                else -> false
            }
            sample.requestAwbMode == CaptureRequest.CONTROL_AWB_MODE_OFF &&
                requestedPointMatches &&
                sample.payloadSignature().isNotBlank() &&
                sample.payloadSignature() != excludedPayloadSignature
        }
        val matched = awaitFrameMatch(ledger, mark, CONTROL_RESPONSE_TIMEOUT_MS, predicate)
        val acceptedSignature = matched?.payloadSignature()
        val matchedFrames = awaitMatchingFrames(
            ledger = ledger,
            mark = mark,
            minimum = MIN_MANUAL_FRAMES,
            timeoutMs = FRAME_WINDOW_TIMEOUT_MS
        ) { sample ->
            predicate(sample) && sample.payloadSignature() == acceptedSignature
        }
        val latencyMs = matched?.let { (it.callbackElapsedNs - mark.startedElapsedNs) / 1_000_000.0 }
        val persisted = PreferencesManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val payloadComplete = matched != null && matched.payloadSignature().isNotBlank()
        val resultAccepted = matchedFrames.any {
            it.resultAwbMode == CaptureResult.CONTROL_AWB_MODE_OFF || it.resultAwbState == CaptureResult.CONTROL_AWB_STATE_INACTIVE
        }
        val passed = matchedFrames.size >= MIN_MANUAL_FRAMES &&
            payloadComplete &&
            resultAccepted &&
            vm.cameraParams.value.wbKelvin == kelvin &&
            persisted.lastWBKelvin == kelvin &&
            persisted.lastWBTint == tint &&
            (latencyMs ?: Double.MAX_VALUE) <= CONTROL_RESPONSE_LIMIT_MS
        return JSONObject()
            .put("passed", passed)
            .put("phase", phase)
            .put("requestedKelvin", kelvin)
            .put("requestedTint", tint)
            .put("expectedPlatformTint", expectedPlatformTint)
            .put("selectedKelvin", vm.cameraParams.value.wbKelvin)
            .put("persistedKelvin", persisted.lastWBKelvin)
            .put("persistedTint", persisted.lastWBTint)
            .put("latencyMs", latencyMs ?: JSONObject.NULL)
            .put("matchedFrames", matchedFrames.size)
            .put("resultAccepted", resultAccepted)
            .put("payloadSignature", matched?.payloadSignature().orEmpty())
            .put("samples", JSONArray(matchedFrames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
    }

    private fun exerciseDirectionChangeStability(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        val mark = ledger.beginPhase("CP113_PHYSICAL_PAN")
        scenario.onActivity { activity ->
            vm.camera.setAeAwbLock(false)
            vm.setAutoWB()
            Toast.makeText(
                activity,
                "LQ-18: slowly pan the phone left-right and toward a second scene",
                Toast.LENGTH_LONG
            ).show()
        }
        val samples = collectPhaseForDuration(ledger, mark, DIRECTION_WINDOW_MS)
        val gyro = ledger.gyroFor(mark)
        val integratedAngleRad = integratedGyroAngleRad(gyro)
        val maxAngularRate = gyro.maxOfOrNull { it.angularRateRadPerSec } ?: 0.0
        val physicalMotionDetected = integratedAngleRad >= MIN_INTEGRATED_PAN_RADIANS ||
            maxAngularRate >= MIN_PEAK_PAN_RAD_PER_SEC
        val autoSamples = samples.filter {
            it.requestAwbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO && it.hasFinitePositiveGains()
        }
        val uv = autoSamples.mapNotNull(FrameSample::uv)
        val adjacentDrift = uv.zipWithNext { first, second ->
            val du = second.first - first.first
            val dv = second.second - first.second
            sqrt((du * du + dv * dv).toDouble())
        }
        val maxDriftUv = adjacentDrift.maxOrNull() ?: Double.NaN
        val maxFrameGapMs = autoSamples.zipWithNext { first, second ->
            (second.sensorTimestampNs - first.sensorTimestampNs) / 1_000_000.0
        }.maxOrNull() ?: Double.NaN
        val allFinite = autoSamples.size == samples.count {
            it.requestAwbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        val passed = physicalMotionDetected &&
            autoSamples.size >= DIRECTION_MIN_FRAMES &&
            allFinite &&
            maxDriftUv.isFinite() && maxDriftUv <= MAX_DIRECTION_FRAME_DRIFT_UV &&
            maxFrameGapMs.isFinite() && maxFrameGapMs <= MAX_CAPTURE_RESULT_GAP_MS
        val reason = when {
            !physicalMotionDetected -> "BLOCKED_NO_GYRO_CONFIRMED_PHYSICAL_PAN"
            autoSamples.size < DIRECTION_MIN_FRAMES -> "INSUFFICIENT_CAMERA2_FRAMES"
            !allFinite -> "NONFINITE_OR_MISSING_WB_GAINS"
            !maxDriftUv.isFinite() || maxDriftUv > MAX_DIRECTION_FRAME_DRIFT_UV -> "WB_DRIFT_EXCEEDED_LIMIT"
            !maxFrameGapMs.isFinite() || maxFrameGapMs > MAX_CAPTURE_RESULT_GAP_MS -> "CAPTURE_RESULT_GAP_EXCEEDED_LIMIT"
            else -> "PASS"
        }
        return JSONObject()
            .put("passed", passed)
            .put("reason", reason)
            .put("threshold", "gyro pan >=0.20 rad integrated or >=0.35 rad/s peak; per-frame WB drift <=0.025 u'v'; capture-result gap <=750 ms")
            .put("gyroSamples", gyro.size)
            .put("integratedAngleRad", integratedAngleRad)
            .put("maxAngularRateRadPerSec", maxAngularRate)
            .put("physicalMotionDetected", physicalMotionDetected)
            .put("cameraFrames", samples.size)
            .put("validAutoWbFrames", autoSamples.size)
            .put("maxAdjacentDriftUv", if (maxDriftUv.isFinite()) maxDriftUv else JSONObject.NULL)
            .put("maxCaptureResultGapMs", if (maxFrameGapMs.isFinite()) maxFrameGapMs else JSONObject.NULL)
            .put("samples", JSONArray(autoSamples.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
    }

    private fun exerciseAwbLockUnlock(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        scenario.onActivity {
            vm.camera.setPreviewLookControls(logEnabled = true, hdrEnabled = false, rawEnabled = false)
            vm.setAutoWB()
            vm.camera.setAeAwbLock(false)
        }
        val adaptiveMark = ledger.beginPhase("CP114_ADAPTIVE_BASELINE")
        val adaptive = awaitFrameMatch(ledger, adaptiveMark, CONTROL_RESPONSE_TIMEOUT_MS) {
            it.requestAwbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO && it.requestAwbLock == false
        }

        val lockMark = ledger.beginPhase("CP114_LOCK")
        scenario.onActivity { vm.camera.setAeAwbLock(true) }
        val locked = awaitFrameMatch(ledger, lockMark, CONTROL_RESPONSE_TIMEOUT_MS) {
            it.requestAwbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO &&
                it.requestAwbLock == true && it.resultAwbLock == true
        }

        val unlockMark = ledger.beginPhase("CP114_UNLOCK")
        scenario.onActivity { vm.camera.setAeAwbLock(false) }
        val unlocked = awaitFrameMatch(ledger, unlockMark, CONTROL_RESPONSE_TIMEOUT_MS) {
            it.requestAwbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO &&
                it.requestAwbLock == false && it.resultAwbLock == false
        }
        val unlockFrames = awaitPhaseFrames(ledger, unlockMark, MIN_EVIDENCE_FRAMES, FRAME_WINDOW_TIMEOUT_MS)
        scenario.onActivity {
            vm.camera.setPreviewLookControls(logEnabled = false, hdrEnabled = false, rawEnabled = false)
        }
        val lockLatencyMs = locked?.let { (it.callbackElapsedNs - lockMark.startedElapsedNs) / 1_000_000.0 }
        val unlockLatencyMs = unlocked?.let { (it.callbackElapsedNs - unlockMark.startedElapsedNs) / 1_000_000.0 }
        val passed = adaptive != null && locked != null && unlocked != null &&
            unlockFrames.size >= MIN_EVIDENCE_FRAMES &&
            timestampsStrictlyIncrease(unlockFrames) &&
            (lockLatencyMs ?: Double.MAX_VALUE) <= CONTROL_RESPONSE_LIMIT_MS &&
            (unlockLatencyMs ?: Double.MAX_VALUE) <= CONTROL_RESPONSE_LIMIT_MS
        return JSONObject()
            .put("passed", passed)
            .put("threshold", "Explicit AUTO-AWB lock and unlock must be present in request and result within 1500 ms with uninterrupted frames")
            .put("adaptiveObserved", adaptive != null)
            .put("lockObserved", locked != null)
            .put("unlockObserved", unlocked != null)
            .put("lockLatencyMs", lockLatencyMs ?: JSONObject.NULL)
            .put("unlockLatencyMs", unlockLatencyMs ?: JSONObject.NULL)
            .put("unlockFrameCount", unlockFrames.size)
            .put("lockedSample", locked?.toJson() ?: JSONObject.NULL)
            .put("unlockedSample", unlocked?.toJson() ?: JSONObject.NULL)
    }

    private fun exerciseAutoIso(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        scenario.onActivity {
            vm.setAutoExposure()
            vm.setAutoISO()
            if (!vm.isManualControlsVisible) vm.toggleManualControls()
            vm.setSdrQualityIntent(SdrQualityIntent.PRO)
            vm.applyManualControlsForPreset(CameraModePreset.PRO)
            vm.setAutoExposure()
            vm.setAutoISO()
        }
        awaitStableFrames(vm)
        val range = vm.camera.effectiveIsoRange
        val requestedManualIso = maxOf(range.lower, 800).coerceAtMost(range.upper)
        val requestedManualExposureNs = (vm.liveResult.value?.exposureNs ?: 10_000_000L)
            .coerceIn(vm.camera.exposureRange.lower, vm.camera.exposureRange.upper)

        val manualMark = ledger.beginPhase("CP115_MANUAL_ISO_PREREQUISITE")
        scenario.onActivity {
            // Pair ISO with the current metered shutter so this prerequisite proves an actual
            // AE-OFF sensor request on HALs that do not expose Android 16 ISO-priority mode.
            // CP-116 separately exercises partial-manual ISO response and clamping.
            vm.setExposure(requestedManualExposureNs)
            vm.setISO(requestedManualIso)
        }
        val manualPredicate: (FrameSample) -> Boolean = {
            it.requestAeMode == CaptureRequest.CONTROL_AE_MODE_OFF &&
                it.requestSensorIso != null && it.requestSensorIso > 0 &&
                it.resultIso > 0
        }
        val manual = awaitFrameMatch(
            ledger,
            manualMark,
            MANUAL_CONTROL_RESPONSE_TIMEOUT_MS,
            manualPredicate
        )
        val manualFrames = awaitMatchingFrames(
            ledger,
            manualMark,
            MIN_MANUAL_FRAMES,
            MANUAL_CONTROL_RESPONSE_TIMEOUT_MS,
            manualPredicate
        )

        val autoMark = ledger.beginPhase("CP115_AUTO_ISO_RESTORE")
        scenario.onActivity {
            vm.setAutoISO()
            vm.setAutoExposure()
        }
        val autoPredicate: (FrameSample) -> Boolean = {
            it.requestAeMode != CaptureRequest.CONTROL_AE_MODE_OFF &&
                it.requestSensorIso == null &&
                it.resultIso > 0 &&
                (it.resultAeState == CaptureResult.CONTROL_AE_STATE_SEARCHING ||
                    it.resultAeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    it.resultAeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                    it.resultAeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)
        }
        val auto = awaitFrameMatch(ledger, autoMark, CONTROL_RESPONSE_TIMEOUT_MS, autoPredicate)
        val autoFrames = awaitMatchingFrames(
            ledger,
            autoMark,
            MIN_EVIDENCE_FRAMES,
            FRAME_WINDOW_TIMEOUT_MS,
            autoPredicate
        )
        val latencyMs = auto?.let { (it.callbackElapsedNs - autoMark.startedElapsedNs) / 1_000_000.0 }
        val isoValues = autoFrames.map { it.resultIso }
        val passed = manual != null && manualFrames.size >= MIN_MANUAL_FRAMES && auto != null &&
            autoFrames.size >= MIN_EVIDENCE_FRAMES &&
            timestampsStrictlyIncrease(autoFrames) &&
            vm.cameraParams.value.iso < 0 &&
            (latencyMs ?: Double.MAX_VALUE) <= CONTROL_RESPONSE_LIMIT_MS
        return JSONObject()
            .put("passed", passed)
            .put("threshold", "Manual ISO authority must be removed and AUTO AE must report positive metered ISO for >=8 monotonic frames within 1500 ms")
            .put("effectiveIsoRange", "${range.lower}..${range.upper}")
            .put("manualRequestedIso", requestedManualIso)
            .put("manualRequestedExposureNs", requestedManualExposureNs)
            .put("manualObserved", manual != null)
            .put("manualFrameCount", manualFrames.size)
            .put("autoObserved", auto != null)
            .put("autoLatencyMs", latencyMs ?: JSONObject.NULL)
            .put("autoFrameCount", autoFrames.size)
            .put("autoIsoMin", isoValues.minOrNull() ?: JSONObject.NULL)
            .put("autoIsoMax", isoValues.maxOrNull() ?: JSONObject.NULL)
            .put("manualSample", manual?.toJson() ?: JSONObject.NULL)
            .put("autoSamples", JSONArray(autoFrames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
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

    private fun installMotionHook(vm: CameraViewModel, ledger: CaptureLedger): MotionHook {
        val previous = vm.camera.onMotionSensorSample
        lateinit var observer: (Int, Long, Float, Float, Float) -> Unit
        observer = { type, timestampNs, x, y, z ->
            try {
                previous?.invoke(type, timestampNs, x, y, z)
            } finally {
                if (type == Sensor.TYPE_GYROSCOPE) ledger.recordGyro(timestampNs, x, y, z)
            }
        }
        vm.camera.onMotionSensorSample = observer
        return MotionHook(previous, observer)
    }

    private fun restoreMotionHook(vm: CameraViewModel, hook: MotionHook?) {
        if (hook != null && vm.camera.onMotionSensorSample === hook.observer) {
            vm.camera.onMotionSensorSample = hook.previous
        }
    }

    private fun awaitStreaming(vm: CameraViewModel) {
        check(awaitCondition(SESSION_TIMEOUT_MS) {
            vm.cameraState.value is CameraState.Streaming &&
                (vm.liveResult.value?.timestampNs ?: 0L) > 0L
        }) { "Camera never reached streaming with live Camera2 metadata" }
    }

    private fun awaitStableFrames(vm: CameraViewModel) {
        val initialTimestamp = vm.liveResult.value?.timestampNs ?: 0L
        check(awaitCondition(SESSION_TIMEOUT_MS) {
            vm.cameraState.value is CameraState.Streaming &&
                (vm.liveResult.value?.timestampNs ?: 0L) > initialTimestamp
        }) { "Camera2 capture results did not advance" }
        SystemClock.sleep(STABILITY_SETTLE_MS)
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

    private fun awaitPhaseFrames(
        ledger: CaptureLedger,
        mark: PhaseMark,
        minimum: Int,
        timeoutMs: Long
    ): List<FrameSample> {
        awaitCondition(timeoutMs) { ledger.framesFor(mark).size >= minimum }
        return ledger.framesFor(mark)
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

    private fun collectPhaseForDuration(
        ledger: CaptureLedger,
        mark: PhaseMark,
        durationMs: Long
    ): List<FrameSample> {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (true) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) break
            SystemClock.sleep(minOf(POLL_INTERVAL_MS, remainingMs))
        }
        return ledger.framesFor(mark)
    }

    private fun timestampsStrictlyIncrease(samples: List<FrameSample>): Boolean =
        samples.size >= 2 && samples.zipWithNext().all { (first, second) ->
            second.sensorTimestampNs > first.sensorTimestampNs && second.frameNumber > first.frameNumber
        }

    private fun integratedGyroAngleRad(samples: List<GyroSample>): Double {
        if (samples.size < 2) return 0.0
        return samples.sortedBy { it.timestampNs }.zipWithNext().sumOf { (first, second) ->
            val dtSeconds = ((second.timestampNs - first.timestampNs).coerceIn(0L, 100_000_000L)) / 1_000_000_000.0
            ((first.angularRateRadPerSec + second.angularRateRadPerSec) * 0.5) * dtSeconds
        }
    }

    private fun readVm(scenario: ActivityScenario<MainActivity>): CameraViewModel {
        val value = AtomicReference<CameraViewModel>()
        scenario.onActivity { value.set(it.vm) }
        return requireNotNull(value.get())
    }

    private fun captureScreenshot(context: Context, name: String) {
        val screenshot = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "Could not capture LQ-018 screenshot" }
        File(evidenceDirectory(context), name).outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }

    private fun writeEvidence(
        context: Context,
        checkpoints: Map<String, JSONObject>,
        stages: List<JSONObject>,
        frameCount: Int,
        gyroSampleCount: Int,
        failure: Throwable?
    ) {
        val sourceApk = File(context.applicationInfo.sourceDir)
        val storage = StatFs(context.filesDir.absolutePath)
        val power = context.getSystemService(PowerManager::class.java)
        val allPassed = REQUIRED_CHECKPOINTS.all { checkpoints[it]?.optBoolean("passed") == true }
        val report = JSONObject()
            .put("schema", "neo-qa-lq018-white-balance-iso/v1")
            .put("checkpoint", "LQ-018")
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
            .put("gyroSamplesObserved", gyroSampleCount)
            .put("checkpoints", JSONObject(checkpoints))
            .put("stages", JSONArray(stages))
            .put("qualityClaim", "Camera2 control correctness and temporal stability only; no unmeasured IMAX/telescope equivalence claim")
            .put("passed", allPassed && failure == null)
            .put("failure", failure?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL)
        val directory = evidenceDirectory(context)
        val temporary = File(directory, "lq018.json.tmp")
        val destination = File(directory, "lq018.json")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        if (destination.exists()) check(destination.delete()) { "Could not replace prior LQ-018 evidence" }
        check(temporary.renameTo(destination)) { "Could not atomically publish LQ-018 evidence" }
    }

    private fun evidenceDirectory(context: Context): File =
        requireNotNull(context.getExternalFilesDir(EVIDENCE_DIRECTORY)).apply { mkdirs() }

    private fun snapshotPreferences(context: Context): Map<String, Any?> =
        context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE).all
            .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }

    private fun restorePreferences(context: Context, values: Map<String, Any?>) {
        val editor = context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
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
        check(editor.commit()) { "Could not restore user preferences after LQ-018" }
    }

    private fun markStage(
        stages: MutableList<JSONObject>,
        startedElapsedMs: Long,
        name: String
    ) {
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

    private data class CaptureHook(
        val previous: ((TotalCaptureResult) -> Unit)?,
        val observer: (TotalCaptureResult) -> Unit
    )

    private data class MotionHook(
        val previous: ((Int, Long, Float, Float, Float) -> Unit)?,
        val observer: (Int, Long, Float, Float, Float) -> Unit
    )

    private data class PhaseMark(
        val name: String,
        val startedElapsedNs: Long,
        val generation: Long
    )

    private data class GyroSample(
        val phase: String,
        val generation: Long,
        val timestampNs: Long,
        val xRadPerSec: Float,
        val yRadPerSec: Float,
        val zRadPerSec: Float
    ) {
        val angularRateRadPerSec: Double
            get() = sqrt(
                xRadPerSec.toDouble() * xRadPerSec +
                    yRadPerSec.toDouble() * yRadPerSec +
                    zRadPerSec.toDouble() * zRadPerSec
            )
    }

    private data class FrameSample(
        val phase: String,
        val generation: Long,
        val callbackElapsedNs: Long,
        val frameNumber: Long,
        val sensorTimestampNs: Long,
        val requestAwbMode: Int?,
        val requestAwbLock: Boolean?,
        val requestColorMode: Int?,
        val requestCct: Int?,
        val requestTint: Int?,
        val requestGains: FloatArray?,
        val requestCcm: FloatArray?,
        val requestAeMode: Int?,
        val requestSensorIso: Int?,
        val requestPostRawBoost: Int?,
        val resultAwbMode: Int?,
        val resultAwbState: Int?,
        val resultAwbLock: Boolean?,
        val resultColorMode: Int?,
        val resultCct: Int?,
        val resultTint: Int?,
        val resultGains: FloatArray?,
        val resultCcm: FloatArray?,
        val resultAeState: Int?,
        val resultIso: Int,
        val resultExposureNs: Long
    ) {
        fun hasFinitePositiveGains(): Boolean =
            resultGains?.size == 4 && resultGains.all { it.isFinite() && it > 0f }

        fun uv(): Pair<Float, Float>? {
            val gains = resultGains ?: return null
            if (gains.size != 4) return null
            val uv = WhiteBalanceChromaticity.rggbGainsToUv(gains[0], gains[1], gains[2], gains[3])
                ?: return null
            return uv.u to uv.v
        }

        fun payloadSignature(): String = when (requestColorMode) {
            CameraMetadata.COLOR_CORRECTION_MODE_CCT -> "CCT:$requestCct:$requestTint"
            CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX ->
                "MATRIX:${requestGains?.joinToString(",")}:${requestCcm?.joinToString(",")}"
            else -> ""
        }

        fun toJson(): JSONObject = JSONObject()
            .put("phase", phase)
            .put("frameNumber", frameNumber)
            .put("sensorTimestampNs", sensorTimestampNs)
            .put("requestAwbMode", requestAwbMode ?: JSONObject.NULL)
            .put("requestAwbLock", requestAwbLock ?: JSONObject.NULL)
            .put("requestColorMode", requestColorMode ?: JSONObject.NULL)
            .put("requestCct", requestCct ?: JSONObject.NULL)
            .put("requestTint", requestTint ?: JSONObject.NULL)
            .put("requestGains", requestGains?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("requestCcm", requestCcm?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("requestAeMode", requestAeMode ?: JSONObject.NULL)
            .put("requestSensorIso", requestSensorIso ?: JSONObject.NULL)
            .put("requestPostRawBoost", requestPostRawBoost ?: JSONObject.NULL)
            .put("resultAwbMode", resultAwbMode ?: JSONObject.NULL)
            .put("resultAwbState", resultAwbState ?: JSONObject.NULL)
            .put("resultAwbLock", resultAwbLock ?: JSONObject.NULL)
            .put("resultColorMode", resultColorMode ?: JSONObject.NULL)
            .put("resultCct", resultCct ?: JSONObject.NULL)
            .put("resultTint", resultTint ?: JSONObject.NULL)
            .put("resultGains", resultGains?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("resultCcm", resultCcm?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("resultAeState", resultAeState ?: JSONObject.NULL)
            .put("resultIso", resultIso)
            .put("resultExposureNs", resultExposureNs)
    }

    private class CaptureLedger {
        private val phase = AtomicReference(PhaseMark("BOOT", SystemClock.elapsedRealtimeNanos(), 0L))
        val frames = ConcurrentLinkedQueue<FrameSample>()
        val gyroSamples = ConcurrentLinkedQueue<GyroSample>()

        fun beginPhase(name: String): PhaseMark {
            val next = PhaseMark(
                name = name,
                startedElapsedNs = SystemClock.elapsedRealtimeNanos(),
                generation = phase.get().generation + 1L
            )
            phase.set(next)
            return next
        }

        @Suppress("DEPRECATION")
        fun record(result: TotalCaptureResult) {
            val current = phase.get()
            val request = result.request
            frames += FrameSample(
                phase = current.name,
                generation = current.generation,
                callbackElapsedNs = SystemClock.elapsedRealtimeNanos(),
                frameNumber = result.frameNumber,
                sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                requestAwbMode = request.get(CaptureRequest.CONTROL_AWB_MODE),
                requestAwbLock = request.get(CaptureRequest.CONTROL_AWB_LOCK),
                requestColorMode = request.get(CaptureRequest.COLOR_CORRECTION_MODE),
                requestCct = if (Build.VERSION.SDK_INT >= 36) {
                    request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE)
                } else null,
                requestTint = if (Build.VERSION.SDK_INT >= 36) {
                    request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TINT)
                } else null,
                requestGains = request.get(CaptureRequest.COLOR_CORRECTION_GAINS)?.toArray(),
                requestCcm = request.get(CaptureRequest.COLOR_CORRECTION_TRANSFORM)?.toArray(),
                requestAeMode = request.get(CaptureRequest.CONTROL_AE_MODE),
                requestSensorIso = request.get(CaptureRequest.SENSOR_SENSITIVITY),
                requestPostRawBoost = request.get(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST),
                resultAwbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
                resultAwbState = result.get(CaptureResult.CONTROL_AWB_STATE),
                resultAwbLock = result.get(CaptureResult.CONTROL_AWB_LOCK),
                resultColorMode = result.get(CaptureResult.COLOR_CORRECTION_MODE),
                resultCct = if (Build.VERSION.SDK_INT >= 36) {
                    result.get(CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE)
                } else null,
                resultTint = if (Build.VERSION.SDK_INT >= 36) {
                    result.get(CaptureResult.COLOR_CORRECTION_COLOR_TINT)
                } else null,
                resultGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.toArray(),
                resultCcm = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toArray(),
                resultAeState = result.get(CaptureResult.CONTROL_AE_STATE),
                resultIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                resultExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            )
        }

        fun recordGyro(timestampNs: Long, x: Float, y: Float, z: Float) {
            val current = phase.get()
            gyroSamples += GyroSample(current.name, current.generation, timestampNs, x, y, z)
        }

        fun framesFor(mark: PhaseMark): List<FrameSample> =
            frames.filter { it.generation == mark.generation && it.callbackElapsedNs >= mark.startedElapsedNs }

        fun gyroFor(mark: PhaseMark): List<GyroSample> =
            gyroSamples.filter { it.generation == mark.generation }

        private fun android.hardware.camera2.params.RggbChannelVector.toArray(): FloatArray =
            floatArrayOf(red, greenEven, greenOdd, blue)

        private fun android.hardware.camera2.params.ColorSpaceTransform.toArray(): FloatArray =
            FloatArray(9) { index -> getElement(index % 3, index / 3).toFloat() }
    }

    private companion object {
        const val EVIDENCE_DIRECTORY = "lq018"
        const val POLL_INTERVAL_MS = 40L
        const val SESSION_TIMEOUT_MS = 20_000L
        const val CONTROL_RESPONSE_TIMEOUT_MS = 5_000L
        const val MANUAL_CONTROL_RESPONSE_TIMEOUT_MS = 8_000L
        const val FRAME_WINDOW_TIMEOUT_MS = 4_000L
        const val DIRECTION_WINDOW_MS = 12_000L
        const val STABILITY_SETTLE_MS = 800L
        const val CONTROL_RESPONSE_LIMIT_MS = 1_500.0
        const val MIN_EVIDENCE_FRAMES = 8
        const val MIN_MANUAL_FRAMES = 4
        const val DIRECTION_MIN_FRAMES = 24
        const val EVIDENCE_SAMPLE_LIMIT = 12
        const val MIN_INTEGRATED_PAN_RADIANS = 0.20
        const val MIN_PEAK_PAN_RAD_PER_SEC = 0.35
        const val MAX_DIRECTION_FRAME_DRIFT_UV = 0.025
        const val MAX_CAPTURE_RESULT_GAP_MS = 750.0
        val REQUIRED_CHECKPOINTS = listOf("CP-111", "CP-112", "CP-113", "CP-114", "CP-115")
    }
}
