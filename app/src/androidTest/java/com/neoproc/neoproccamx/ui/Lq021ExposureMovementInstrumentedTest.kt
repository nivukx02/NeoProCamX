package com.neoproc.neoproccamx.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.camera.Camera2RequestTag
import com.neoproc.neoproccamx.camera.CameraPreviewAntibandingPolicy
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.camera.CameraVideoStabilizationRequestPlan
import com.neoproc.neoproccamx.camera.CameraVideoStabilizationRequestPolicy
import com.neoproc.neoproccamx.camera.FlashMode
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.StabilizationMode
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
import kotlin.math.sqrt

/**
 * Physical-device proof for LQ-021 / CP-124..129.
 *
 * The test observes the exact request attached to every [TotalCaptureResult]. A UI selection is never
 * treated as proof that the HAL accepted exposure, anti-banding, zoom, lens or stabilization state.
 * CP-124/125/126/129 additionally require real gyroscope motion and real preview pixels; a stationary
 * phone or an insufficient bright/dim scene is reported as BLOCKED rather than mislabeled PASS.
 *
 * This validates functional camera behavior and clipping/cadence bounds on the connected device. It
 * deliberately makes no comparative IMAX/telescope optical-quality claim, which requires a calibrated
 * chart, controlled illuminants, motion rig and reference-camera measurements.
 */
@RunWith(AndroidJUnit4::class)
class Lq021ExposureMovementInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun exposureZoomAntibandingAndStabilization_remainCoherentDuringPhysicalMovement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalPreferences = snapshotPreferences(context)
        val stages = mutableListOf<JSONObject>()
        val checkpoints = linkedMapOf<String, JSONObject>()
        val functionalFailures = mutableListOf<String>()
        val ledger = CaptureLedger()
        val startedElapsedMs = SystemClock.elapsedRealtime()
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: CameraViewModel? = null
        var captureHook: CaptureHook? = null
        var motionHook: MotionHook? = null
        var originalStabilization = StabilizationMode.OFF
        var failure: Throwable? = null
        val wakeLock = acquireTestWakeLock(context)

        markStage(stages, startedElapsedMs, "DISPLAY_WAKE_LOCK_ACQUIRED")
        markStage(stages, startedElapsedMs, "PREFERENCES_SNAPSHOTTED")
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            vm = readVm(scenario)
            originalStabilization = requireNotNull(vm).stabilizationMode
            awaitStreaming(requireNotNull(vm))
            configureMode(scenario, requireNotNull(vm), TestMode.VIDEO)
            captureHook = installCaptureHook(requireNotNull(vm), ledger)
            motionHook = installMotionHook(requireNotNull(vm), ledger)
            markStage(stages, startedElapsedMs, "CAMERA2_AND_GYRO_OBSERVERS_ATTACHED")

            val movement = exercisePhysicalMovementAndExposure(
                context = context,
                scenario = scenario,
                vm = requireNotNull(vm),
                ledger = ledger
            )
            checkpoints["CP-124"] = movement.cp124
            checkpoints["CP-125"] = movement.cp125
            checkpoints["CP-126"] = movement.cp126
            listOf("CP-124" to movement.cp124, "CP-125" to movement.cp125, "CP-126" to movement.cp126)
                .filter { (_, value) -> value.optString("status") == "FAIL" }
                .forEach { (id, _) -> functionalFailures += "$id movement/exposure contract failed" }
            markStage(stages, startedElapsedMs, "CP124_TO_CP126_MOVEMENT_EXPOSURE_CAPTURED")

            val antibanding = exerciseAntibandingAcrossModes(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-127"] = antibanding
            if (antibanding.optString("status") == "FAIL") {
                functionalFailures += "CP-127 anti-banding request/result contract failed"
            }
            markStage(stages, startedElapsedMs, "CP127_ANTIBANDING_CAPTURED")

            val zoom = exerciseZoomAndLensContinuity(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-128"] = zoom
            if (zoom.optString("status") == "FAIL") {
                functionalFailures += "CP-128 zoom/lens continuity contract failed"
            }
            markStage(stages, startedElapsedMs, "CP128_ZOOM_LENS_CAPTURED")

            val stabilization = exerciseStabilizationAcrossModes(
                scenario = scenario,
                vm = requireNotNull(vm),
                ledger = ledger,
                movement = movement
            )
            checkpoints["CP-129"] = stabilization
            if (stabilization.optString("status") == "FAIL") {
                functionalFailures += "CP-129 stabilization request/result contract failed"
            }
            markStage(stages, startedElapsedMs, "CP129_STABILIZATION_CAPTURED")

            capturePreviewMetric(context, "lq021-final-preview.png")
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
                        activeVm.setFlash(FlashMode.OFF)
                        activeVm.camera.setAeAwbLock(false)
                        activeVm.setEV(0)
                        activeVm.setAutoISO()
                        activeVm.setAutoExposure()
                        activeVm.setAutoWB()
                        activeVm.setAutoFocus()
                        activeVm.setZoomPreset(1f)
                        activeVm.setStabilizationMode(originalStabilization)
                        activeVm.setRawEnabled(false)
                        activeVm.setCinemaVideoMode(CinemaVideoMode.PRO)
                        activeVm.setVideoHdrMode("OFF")
                        activeVm.setLogEnabled(false)
                        restoreCaptureHook(activeVm, captureHook)
                        restoreMotionHook(activeVm, motionHook)
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
                    gyroSampleCount = ledger.gyroSamples.size,
                    failure = failure ?: cleanupFailure
                )
            }.onFailure { cleanupFailure = cleanupFailure ?: it }
            runCatching {
                if (wakeLock.isHeld) wakeLock.release()
            }.onFailure { cleanupFailure = cleanupFailure ?: it }
            if (failure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun exercisePhysicalMovementAndExposure(
        context: Context,
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): MovementEvidence {
        configureMode(scenario, vm, TestMode.VIDEO)
        val requestedStabilization = bestAvailableStabilization(vm)
        scenario.onActivity {
            vm.setStabilizationMode(requestedStabilization)
            vm.camera.setAeAwbLock(false)
            vm.setEV(0)
            vm.setAutoISO()
            vm.setAutoExposure()
            vm.setAutoWB()
            vm.setAutoFocus()
        }
        SystemClock.sleep(CONTROL_SETTLE_MS)
        val mark = ledger.beginPhase("CP124_126_PHYSICAL_BRIGHT_DIM_PAN")
        scenario.onActivity { activity ->
            Toast.makeText(
                activity,
                "LQ-21: slowly pan between a bright area and a dim area until this test finishes",
                Toast.LENGTH_LONG
            ).show()
        }

        val previewMetrics = mutableListOf<PreviewMetric>()
        repeat(MOVEMENT_SCREENSHOT_COUNT) { index ->
            if (index > 0) SystemClock.sleep(MOVEMENT_SCREENSHOT_INTERVAL_MS)
            previewMetrics += capturePreviewMetric(context, "lq021-movement-$index.png")
        }
        val frames = ledger.framesFor(mark)
        val gyro = ledger.gyroFor(mark)
        val integratedAngleRad = integratedGyroAngleRad(gyro)
        val maxAngularRate = gyro.maxOfOrNull { it.angularRateRadPerSec } ?: 0.0
        val physicalMotionDetected = integratedAngleRad >= MIN_INTEGRATED_PAN_RADIANS ||
            maxAngularRate >= MIN_PEAK_PAN_RAD_PER_SEC
        val autoFrames = frames.filter {
            it.requestAeMode != CaptureRequest.CONTROL_AE_MODE_OFF &&
                it.resultIso > 0 && it.resultExposureNs > 0L && it.sensorTimestampNs > 0L
        }
        val continuousAfFrames = frames.filter {
            it.requestAfMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ||
                it.requestAfMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        val maximumGapMs = maximumSensorGapMs(autoFrames)
        val monotonic = timestampsStrictlyIncrease(autoFrames)
        val noWhiteout = previewMetrics.none(PreviewMetric::isFullFrameWhiteout)
        val noBlackout = previewMetrics.none(PreviewMetric::isFullFrameBlackout)
        val exposureProducts = autoFrames.map(FrameSample::exposureProduct).filter { it > 0.0 }
        val exposureProductRatio = positiveRangeRatio(exposureProducts)
        val lumaSpan = (previewMetrics.maxOfOrNull { it.meanLuma } ?: 0.0) -
            (previewMetrics.minOfOrNull { it.meanLuma } ?: 0.0)
        val maximumClippedFraction = previewMetrics.maxOfOrNull { it.clippedFraction } ?: 1.0
        val highlightTargetObserved = maximumClippedFraction >= MIN_HIGHLIGHT_TARGET_FRACTION ||
            (previewMetrics.maxOfOrNull { it.p95Luma } ?: 0.0) >= MIN_HIGHLIGHT_TARGET_P95
        val brightDimSweepObserved = exposureProductRatio >= MIN_EXPOSURE_PRODUCT_RANGE_RATIO ||
            lumaSpan >= MIN_PREVIEW_LUMA_SPAN
        val motionCorePassed = autoFrames.size >= MIN_MOVEMENT_FRAMES && monotonic &&
            maximumGapMs <= MAX_CAPTURE_RESULT_GAP_MS &&
            continuousAfFrames.size >= MIN_MOVEMENT_FRAMES && noWhiteout
        val cp124Status = when {
            !physicalMotionDetected -> "BLOCKED_MOTION"
            !motionCorePassed -> "FAIL"
            else -> "PASS"
        }
        val cp125Status = when {
            !physicalMotionDetected -> "BLOCKED_MOTION"
            !motionCorePassed -> "FAIL"
            !highlightTargetObserved -> "BLOCKED_HIGHLIGHT_TARGET"
            else -> "PASS"
        }
        val cp126Status = when {
            !physicalMotionDetected -> "BLOCKED_MOTION"
            !motionCorePassed || !noBlackout -> "FAIL"
            !brightDimSweepObserved -> "BLOCKED_SCENE_RANGE"
            else -> "PASS"
        }
        val points = previewMetrics.map { metric ->
            val nearest = autoFrames.minByOrNull { abs(it.callbackElapsedNs - metric.capturedElapsedNs) }
            metric.toJson().put("nearestCameraFrame", nearest?.toJson() ?: JSONObject.NULL)
        }
        val common = JSONObject()
            .put("gyroSamples", gyro.size)
            .put("integratedAngleRad", integratedAngleRad)
            .put("maxAngularRateRadPerSec", maxAngularRate)
            .put("physicalMotionDetected", physicalMotionDetected)
            .put("cameraFrames", frames.size)
            .put("validAutoExposureFrames", autoFrames.size)
            .put("continuousAutofocusFrames", continuousAfFrames.size)
            .put("monotonicSensorTimestamps", monotonic)
            .put("maximumCaptureResultGapMs", maximumGapMs)
            .put("exposureProductRangeRatio", exposureProductRatio)
            .put("previewLumaSpan", lumaSpan)
            .put("maximumPreviewClippedFraction", maximumClippedFraction)
            .put("noFullFrameWhiteout", noWhiteout)
            .put("noFullFrameBlackout", noBlackout)
            .put("highlightTargetObserved", highlightTargetObserved)
            .put("brightDimSweepObserved", brightDimSweepObserved)
            .put("requestedStabilization", requestedStabilization.name)
            .put("previewSamples", JSONArray(points))
            .put("cameraSamples", JSONArray(autoFrames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
        val cp124 = copyJson(common)
            .put("status", cp124Status)
            .put("functionalPassed", cp124Status != "FAIL")
            .put("threshold", "Gyro-confirmed pan; >=24 monotonic AUTO-AE/continuous-AF frames; no result gap >750 ms; no preview region with >=90% near-white samples")
            .put("blocker", blockerFor(cp124Status))
        val cp125 = copyJson(common)
            .put("status", cp125Status)
            .put("functionalPassed", cp125Status != "FAIL")
            .put("threshold", "Real highlight target plus gyro-confirmed pan, live AUTO exposure and no full-frame clipping/whiteout")
            .put("blocker", blockerFor(cp125Status))
        val cp126 = copyJson(common)
            .put("status", cp126Status)
            .put("functionalPassed", cp126Status != "FAIL")
            .put("threshold", "Real bright/dim sweep with exposure-product ratio >=1.5 or preview-luma span >=0.10, no whiteout/blackout and continuous Camera2 cadence")
            .put("blocker", blockerFor(cp126Status))
        return MovementEvidence(
            mark = mark,
            physicalMotionDetected = physicalMotionDetected,
            requestedStabilization = requestedStabilization,
            cp124 = cp124,
            cp125 = cp125,
            cp126 = cp126
        )
    }

    private fun exerciseAntibandingAcrossModes(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        val available = vm.camera.characteristics?.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES
        ) ?: IntArray(0)
        val expected = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = available,
            requestedPreviewFps = TEST_FPS,
            videoMode = true,
            usingManualExposure = false,
            powerGridCountryCode = "IN",
            powerGridTimeZoneId = "Asia/Kolkata"
        )
        if (expected == null) {
            return JSONObject()
                .put("status", "BLOCKED_HARDWARE")
                .put("functionalPassed", true)
                .put("availableModes", JSONArray(available.toList()))
                .put("blocker", "Active camera advertises no anti-banding request mode")
        }
        val modes = JSONArray()
        var requestContractPassed = true
        var resultMetadataComplete = true
        TestMode.entries.forEach { mode ->
            configureMode(scenario, vm, mode)
            val mark = ledger.beginPhase("CP127_${mode.name}_25FPS")
            val requestedFrames = awaitMatchingFrames(
                ledger,
                mark,
                MIN_CONTROL_FRAMES,
                CONTROL_RESPONSE_TIMEOUT_MS
            ) { it.requestAntibandingMode == expected }
            val resultFrames = requestedFrames.filter { it.resultAntibandingMode == expected }
            val requestPassed = requestedFrames.size >= MIN_CONTROL_FRAMES &&
                timestampsStrictlyIncrease(requestedFrames) &&
                maximumSensorGapMs(requestedFrames) <= MAX_CAPTURE_RESULT_GAP_MS
            val resultComplete = resultFrames.size >= MIN_CONTROL_FRAMES
            requestContractPassed = requestContractPassed && requestPassed
            resultMetadataComplete = resultMetadataComplete && resultComplete
            modes.put(JSONObject()
                .put("mode", mode.name)
                .put("expectedAntibandingMode", expected)
                .put("requestPassed", requestPassed)
                .put("resultMetadataComplete", resultComplete)
                .put("requestedFrames", requestedFrames.size)
                .put("matchingResultFrames", resultFrames.size)
                .put("maximumCaptureResultGapMs", maximumSensorGapMs(requestedFrames))
                .put("samples", JSONArray(requestedFrames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson))))
        }
        val status = when {
            !requestContractPassed -> "FAIL"
            !resultMetadataComplete -> "BLOCKED_RESULT_METADATA"
            else -> "PASS"
        }
        return JSONObject()
            .put("status", status)
            .put("functionalPassed", requestContractPassed)
            .put("availableModes", JSONArray(available.toList()))
            .put("expectedMode", expected)
            .put("threshold", "Video/Pro/Raw at 25 FPS must request India-safe 50 Hz anti-banding for >=8 monotonic frames per mode; any available result metadata must agree")
            .put("modes", modes)
            .put("blocker", blockerFor(status))
            .put("opticalCaveat", "Request/result proves HAL anti-banding authority; calibrated 50 Hz/LED light is required to quantify residual rolling bands")
    }

    private fun exerciseZoomAndLensContinuity(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        configureMode(scenario, vm, TestMode.VIDEO)
        val characteristics = vm.camera.characteristics
        val capabilities = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: IntArray(0)
        val logicalMultiCamera = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        )
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics?.physicalCameraIds.orEmpty()
        } else {
            emptySet()
        }
        val probes = zoomProbes(vm.camera.minZoom, vm.camera.maxZoom)
        if (probes.size < 2) {
            return JSONObject()
                .put("status", "BLOCKED_HARDWARE")
                .put("functionalPassed", true)
                .put("minZoom", vm.camera.minZoom)
                .put("maxZoom", vm.camera.maxZoom)
                .put("blocker", "Active camera exposes no usable zoom range")
        }
        val points = JSONArray()
        val observedPhysicalIds = linkedSetOf<String>()
        var allPointsPassed = true
        probes.forEachIndexed { index, requestedZoom ->
            val mark = ledger.beginPhase("CP128_ZOOM_${index}_${zoomLabel(requestedZoom)}")
            scenario.onActivity { vm.setZoomPreset(requestedZoom) }
            val matching = awaitMatchingFrames(
                ledger,
                mark,
                MIN_CONTROL_FRAMES,
                ZOOM_RESPONSE_TIMEOUT_MS
            ) { frame -> frame.matchesZoom(requestedZoom) }
            val phaseFrames = ledger.framesFor(mark)
            phaseFrames.mapNotNullTo(observedPhysicalIds) { it.activePhysicalCameraId }
            val latencyMs = matching.firstOrNull()?.latencyMs(mark)
            val pointPassed = matching.size >= MIN_CONTROL_FRAMES &&
                timestampsStrictlyIncrease(matching) &&
                maximumSensorGapMs(matching) <= MAX_CAPTURE_RESULT_GAP_MS &&
                (latencyMs ?: Double.MAX_VALUE) <= MAX_CONTROL_LATENCY_MS
            allPointsPassed = allPointsPassed && pointPassed
            points.put(JSONObject()
                .put("requestedZoom", requestedZoom)
                .put("passed", pointPassed)
                .put("latencyMs", latencyMs ?: JSONObject.NULL)
                .put("matchingFrames", matching.size)
                .put("observedFrames", phaseFrames.size)
                .put("physicalCameraIds", JSONArray(phaseFrames.mapNotNull { it.activePhysicalCameraId }.distinct()))
                .put("focalLengthsMm", JSONArray(phaseFrames.mapNotNull { it.resultFocalLength }.distinct()))
                .put("maximumCaptureResultGapMs", maximumSensorGapMs(matching))
                .put("samples", JSONArray(matching.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson))))
        }
        scenario.onActivity { vm.setZoomPreset(1f) }
        val lensMetadataStatus = when {
            !logicalMultiCamera || physicalIds.size <= 1 -> "NOT_APPLICABLE_SINGLE_PHYSICAL_CAMERA"
            observedPhysicalIds.isEmpty() -> "BLOCKED_RESULT_METADATA"
            observedPhysicalIds.size < 2 -> "BLOCKED_LENS_SWITCH_TARGET"
            else -> "PASS"
        }
        val status = when {
            !allPointsPassed -> "FAIL"
            lensMetadataStatus.startsWith("BLOCKED") -> lensMetadataStatus
            else -> "PASS"
        }
        return JSONObject()
            .put("status", status)
            .put("functionalPassed", allPointsPassed)
            .put("threshold", ">=8 monotonic Camera2 request/result frames at each zoom probe, applied ratio within 0.03x, response <=1500 ms and no result gap >750 ms; logical multi-camera additionally requires attributable physical-lens evidence")
            .put("logicalMultiCamera", logicalMultiCamera)
            .put("advertisedPhysicalCameraIds", JSONArray(physicalIds.toList()))
            .put("observedPhysicalCameraIds", JSONArray(observedPhysicalIds.toList()))
            .put("lensMetadataStatus", lensMetadataStatus)
            .put("minZoom", vm.camera.minZoom)
            .put("maxZoom", vm.camera.maxZoom)
            .put("points", points)
            .put("blocker", blockerFor(status))
    }

    private fun exerciseStabilizationAcrossModes(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger,
        movement: MovementEvidence
    ): JSONObject {
        val supportsOis = vm.camera.supportsOIS
        val supportsVideoStabilization = vm.camera.supportsVideoStabilization
        val supportsPreviewStabilization = vm.camera.supportsPreviewStabilization
        val requestedModes = buildList {
            add(StabilizationMode.OFF)
            if (supportsOis) add(StabilizationMode.OIS)
            if (supportsVideoStabilization) add(StabilizationMode.EIS)
            if (supportsOis || supportsVideoStabilization) add(StabilizationMode.HYBRID)
        }.distinct()
        val modes = JSONArray()
        var allProcessedPointsPassed = true
        listOf(TestMode.VIDEO, TestMode.PRO).forEach { cameraMode ->
            configureMode(scenario, vm, cameraMode)
            requestedModes.forEach { requested ->
                val expected = CameraVideoStabilizationRequestPolicy.resolve(
                    requestedMode = requested,
                    supportsOis = supportsOis,
                    supportsVideoStabilization = supportsVideoStabilization,
                    rawVideoCaptureEnabled = false,
                    supportsPreviewStabilization = supportsPreviewStabilization
                )
                val mark = ledger.beginPhase("CP129_${cameraMode.name}_${requested.name}")
                scenario.onActivity { vm.setStabilizationMode(requested) }
                val matching = awaitMatchingFrames(
                    ledger,
                    mark,
                    MIN_CONTROL_FRAMES,
                    CONTROL_RESPONSE_TIMEOUT_MS
                ) { it.matchesStabilization(expected) }
                val latencyMs = matching.firstOrNull()?.latencyMs(mark)
                val pointPassed = matching.size >= MIN_CONTROL_FRAMES &&
                    timestampsStrictlyIncrease(matching) &&
                    maximumSensorGapMs(matching) <= MAX_CAPTURE_RESULT_GAP_MS &&
                    (latencyMs ?: Double.MAX_VALUE) <= MAX_CONTROL_LATENCY_MS
                allProcessedPointsPassed = allProcessedPointsPassed && pointPassed
                modes.put(stabilizationPoint(cameraMode, requested, expected, matching, mark, pointPassed))
            }
        }

        configureMode(scenario, vm, TestMode.RAW)
        val rawExpected = CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = StabilizationMode.HYBRID,
            supportsOis = supportsOis,
            supportsVideoStabilization = supportsVideoStabilization,
            rawVideoCaptureEnabled = true,
            supportsPreviewStabilization = supportsPreviewStabilization
        )
        val rawMark = ledger.beginPhase("CP129_RAW_SENSOR_FIDELITY")
        scenario.onActivity { vm.setStabilizationMode(StabilizationMode.HYBRID) }
        val rawMatching = awaitMatchingFrames(
            ledger,
            rawMark,
            MIN_CONTROL_FRAMES,
            CONTROL_RESPONSE_TIMEOUT_MS
        ) { it.matchesStabilization(rawExpected) }
        val rawPassed = rawMatching.size >= MIN_CONTROL_FRAMES && timestampsStrictlyIncrease(rawMatching)
        modes.put(stabilizationPoint(
            cameraMode = TestMode.RAW,
            requested = StabilizationMode.HYBRID,
            expected = rawExpected,
            matching = rawMatching,
            mark = rawMark,
            passed = rawPassed
        ).put("rawFidelityRule", "Stabilization is intentionally OFF for RAW_SENSOR samples"))

        val movementFrames = ledger.framesFor(movement.mark)
        val movementPlan = CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = movement.requestedStabilization,
            supportsOis = supportsOis,
            supportsVideoStabilization = supportsVideoStabilization,
            rawVideoCaptureEnabled = false,
            supportsPreviewStabilization = supportsPreviewStabilization
        )
        val engagedDuringMovement = movementFrames.count { it.matchesStabilization(movementPlan) }
        val functionalPassed = allProcessedPointsPassed && rawPassed
        val hasStabilizationHardware = supportsOis || supportsVideoStabilization
        val status = when {
            !functionalPassed -> "FAIL"
            !hasStabilizationHardware -> "BLOCKED_HARDWARE"
            !movement.physicalMotionDetected -> "BLOCKED_MOTION"
            engagedDuringMovement < MIN_MOVEMENT_FRAMES -> "FAIL"
            else -> "PASS"
        }
        return JSONObject()
            .put("status", status)
            .put("functionalPassed", functionalPassed)
            .put("supportsOis", supportsOis)
            .put("supportsVideoStabilization", supportsVideoStabilization)
            .put("supportsPreviewStabilization", supportsPreviewStabilization)
            .put("movementRequestedMode", movement.requestedStabilization.name)
            .put("movementResolvedMode", movementPlan.resolvedMode.name)
            .put("gyroMotionDetected", movement.physicalMotionDetected)
            .put("matchingStabilizedMovementFrames", engagedDuringMovement)
            .put("threshold", "Every supported OFF/OIS/EIS/HYBRID selection must reach matching Camera2 request/result state in Video and Pro; RAW must remain unstabilized; the selected HAL mode must remain active for >=24 frames during gyro-confirmed motion")
            .put("modes", modes)
            .put("blocker", blockerFor(status))
            .put("opticalCaveat", "This proves real HAL engagement during motion, not calibrated shake-reduction stops; the latter requires a repeatable motion rig and chart")
    }

    private fun stabilizationPoint(
        cameraMode: TestMode,
        requested: StabilizationMode,
        expected: CameraVideoStabilizationRequestPlan,
        matching: List<FrameSample>,
        mark: PhaseMark,
        passed: Boolean
    ): JSONObject = JSONObject()
        .put("cameraMode", cameraMode.name)
        .put("requestedMode", requested.name)
        .put("resolvedMode", expected.resolvedMode.name)
        .put("expectedOisMode", expected.oisMode ?: JSONObject.NULL)
        .put("expectedVideoStabilizationMode", expected.videoStabilizationMode ?: JSONObject.NULL)
        .put("passed", passed)
        .put("matchingFrames", matching.size)
        .put("latencyMs", matching.firstOrNull()?.latencyMs(mark) ?: JSONObject.NULL)
        .put("maximumCaptureResultGapMs", maximumSensorGapMs(matching))
        .put("samples", JSONArray(matching.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))

    private fun configureMode(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        mode: TestMode
    ) {
        val priorTimestamp = vm.liveResult.value?.timestampNs ?: 0L
        scenario.onActivity {
            vm.setMode(AppMode.VIDEO)
            vm.setFlash(FlashMode.OFF)
            vm.camera.setAeAwbLock(false)
            vm.setVideoHdrMode("OFF")
            vm.setLogEnabled(false)
            when (mode) {
                TestMode.VIDEO -> {
                    vm.setRawEnabled(false)
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
                    vm.applyManualControlsForPreset(CameraModePreset.VIDEO)
                }
                TestMode.PRO -> {
                    vm.setRawEnabled(false)
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setSdrQualityIntent(SdrQualityIntent.PRO)
                    vm.applyManualControlsForPreset(CameraModePreset.PRO)
                }
                TestMode.RAW -> {
                    vm.setCinemaVideoMode(CinemaVideoMode.RAW)
                    vm.setRawEnabled(true)
                    vm.applyManualControlsForPreset(CameraModePreset.RAW_MODE)
                }
            }
            vm.setFPS(TEST_FPS)
            vm.setEV(0)
            vm.setAutoISO()
            vm.setAutoExposure()
            vm.setAutoWB()
            vm.setAutoFocus()
        }
        check(awaitCondition(MODE_TIMEOUT_MS) {
            val modeMatches = when (mode) {
                TestMode.VIDEO, TestMode.PRO ->
                    vm.cinemaVideoMode.value == CinemaVideoMode.PRO && !vm.rawOnFlow.value
                TestMode.RAW ->
                    vm.cinemaVideoMode.value == CinemaVideoMode.RAW && vm.rawOnFlow.value
            }
            modeMatches && vm.uiMode.value == AppMode.VIDEO &&
                vm.cameraState.value is CameraState.Streaming &&
                (vm.liveResult.value?.timestampNs ?: 0L) > priorTimestamp
        }) { "$mode did not reach a fresh streaming session" }
        SystemClock.sleep(MODE_SETTLE_MS)
    }

    private fun bestAvailableStabilization(vm: CameraViewModel): StabilizationMode = when {
        vm.camera.supportsOIS && vm.camera.supportsVideoStabilization -> StabilizationMode.HYBRID
        vm.camera.supportsVideoStabilization -> StabilizationMode.EIS
        vm.camera.supportsOIS -> StabilizationMode.OIS
        else -> StabilizationMode.OFF
    }

    private fun zoomProbes(minZoom: Float, maxZoom: Float): List<Float> {
        val low = minOf(minZoom, maxZoom).takeIf { it.isFinite() && it > 0f } ?: 1f
        val high = maxOf(minZoom, maxZoom).takeIf { it.isFinite() && it >= low } ?: low
        val midpoint = sqrt(low.toDouble() * high.toDouble()).toFloat()
        return listOf(low, 1f, midpoint, 2f, 3f, minOf(5f, high), high)
            .map { it.coerceIn(low, high) }
            .distinctBy { (it * 1_000f).roundToInt() }
            .sorted()
    }

    private fun zoomLabel(zoom: Float): String = (zoom * 100f).roundToInt().toString()

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

    private fun capturePreviewMetric(context: Context, name: String): PreviewMetric {
        val capturedElapsedNs = SystemClock.elapsedRealtimeNanos()
        val bitmap = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "Could not capture $name" }
        File(evidenceDirectory(context), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val left = (bitmap.width * 0.18f).roundToInt()
        val right = (bitmap.width * 0.82f).roundToInt()
        val top = (bitmap.height * 0.16f).roundToInt()
        val bottom = (bitmap.height * 0.62f).roundToInt()
        val luma = mutableListOf<Double>()
        var clipped = 0
        var black = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                val value = (0.2126 * Color.red(pixel) +
                    0.7152 * Color.green(pixel) +
                    0.0722 * Color.blue(pixel)) / 255.0
                luma += value
                if (value >= CLIPPED_LUMA) clipped++
                if (value <= BLACK_LUMA) black++
                x += IMAGE_SAMPLE_STEP
            }
            y += IMAGE_SAMPLE_STEP
        }
        bitmap.recycle()
        val sorted = luma.sorted()
        val count = sorted.size
        return PreviewMetric(
            fileName = name,
            capturedElapsedNs = capturedElapsedNs,
            meanLuma = if (count > 0) sorted.average() else 0.0,
            p05Luma = percentile(sorted, 0.05),
            p95Luma = percentile(sorted, 0.95),
            clippedFraction = if (count > 0) clipped.toDouble() / count else 1.0,
            blackFraction = if (count > 0) black.toDouble() / count else 1.0,
            sampledPixels = count
        )
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.lastIndex) * fraction.coerceIn(0.0, 1.0)).roundToInt()
        return sorted[index]
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
        val statuses = REQUIRED_CHECKPOINTS.associateWith {
            checkpoints[it]?.optString("status", "PENDING") ?: "PENDING"
        }
        val report = JSONObject()
            .put("schema", "neo-qa-lq021-exposure-movement/v1")
            .put("checkpoint", "LQ-021")
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
            .put("statuses", JSONObject(statuses))
            .put("checkpoints", JSONObject(checkpoints))
            .put("stages", JSONArray(stages))
            .put("functionalTestPassed", failure == null)
            .put("canonicalPass", REQUIRED_CHECKPOINTS.all { statuses[it] == "PASS" })
            .put("qualityClaim", "Real Camera2/gyro/preview-pixel behavior on this candidate only; no unmeasured IMAX/telescope optical-quality equivalence claim")
            .put("failure", failure?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL)
        val directory = evidenceDirectory(context)
        val temporary = File(directory, "lq021.json.tmp")
        val destination = File(directory, "lq021.json")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        if (destination.exists()) check(destination.delete()) { "Could not replace prior LQ-021 evidence" }
        check(temporary.renameTo(destination)) { "Could not atomically publish LQ-021 evidence" }
    }

    private fun evidenceDirectory(context: Context): File =
        requireNotNull(context.getExternalFilesDir(EVIDENCE_DIRECTORY)).apply { mkdirs() }

    @Suppress("DEPRECATION")
    private fun acquireTestWakeLock(context: Context): PowerManager.WakeLock {
        val power = context.getSystemService(PowerManager::class.java)
        return power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "$TEST_TAG:physical-device-proof"
        ).apply {
            setReferenceCounted(false)
            acquire(TEST_WAKE_LOCK_TIMEOUT_MS)
        }
    }

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
        check(editor.commit()) { "Could not restore user preferences after LQ-021" }
    }

    private fun markStage(stages: MutableList<JSONObject>, startedElapsedMs: Long, name: String) {
        val elapsedMs = SystemClock.elapsedRealtime() - startedElapsedMs
        stages += JSONObject()
            .put("name", name)
            .put("elapsedMs", elapsedMs)
        Log.i(TEST_TAG, "stage=$name elapsedMs=$elapsedMs")
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

    private fun integratedGyroAngleRad(samples: List<GyroSample>): Double {
        if (samples.size < 2) return 0.0
        return samples.sortedBy { it.timestampNs }.zipWithNext().sumOf { (first, second) ->
            val dtSeconds = ((second.timestampNs - first.timestampNs).coerceIn(0L, 100_000_000L)) /
                1_000_000_000.0
            ((first.angularRateRadPerSec + second.angularRateRadPerSec) * 0.5) * dtSeconds
        }
    }

    private fun timestampsStrictlyIncrease(frames: List<FrameSample>): Boolean =
        frames.size >= 2 && frames.zipWithNext().all { (first, second) ->
            second.sensorTimestampNs > first.sensorTimestampNs && second.frameNumber > first.frameNumber
        }

    private fun maximumSensorGapMs(frames: List<FrameSample>): Double =
        frames.zipWithNext().maxOfOrNull { (first, second) ->
            (second.sensorTimestampNs - first.sensorTimestampNs) / 1_000_000.0
        } ?: 0.0

    private fun positiveRangeRatio(values: List<Double>): Double {
        val valid = values.filter { it.isFinite() && it > 0.0 }
        if (valid.size < 2) return 1.0
        return (valid.maxOrNull() ?: 1.0) / (valid.minOrNull() ?: 1.0)
    }

    private fun blockerFor(status: String): Any = when (status) {
        "PASS" -> JSONObject.NULL
        "FAIL" -> "Measured functional threshold failed; inspect samples and logcat"
        "BLOCKED_MOTION" -> "No gyro-confirmed physical pan occurred during the prompted device window"
        "BLOCKED_HIGHLIGHT_TARGET" -> "No attributable bright/highlight target entered the preview during the device window"
        "BLOCKED_SCENE_RANGE" -> "The observed scene did not span a sufficient bright/dim exposure range"
        "BLOCKED_HARDWARE" -> "Required capability is not advertised by the active camera"
        "BLOCKED_RESULT_METADATA" -> "HAL accepted the request but omitted attributable result metadata"
        "BLOCKED_LENS_SWITCH_TARGET" -> "Logical camera exposed lens metadata but this zoom sweep did not activate two physical IDs"
        else -> status
    }

    private fun copyJson(source: JSONObject): JSONObject = JSONObject(source.toString())

    private enum class TestMode { VIDEO, PRO, RAW }

    private data class MovementEvidence(
        val mark: PhaseMark,
        val physicalMotionDetected: Boolean,
        val requestedStabilization: StabilizationMode,
        val cp124: JSONObject,
        val cp125: JSONObject,
        val cp126: JSONObject
    )

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

    private data class PreviewMetric(
        val fileName: String,
        val capturedElapsedNs: Long,
        val meanLuma: Double,
        val p05Luma: Double,
        val p95Luma: Double,
        val clippedFraction: Double,
        val blackFraction: Double,
        val sampledPixels: Int
    ) {
        val isFullFrameWhiteout: Boolean
            get() = meanLuma >= WHITEOUT_MEAN_LUMA && clippedFraction >= MAX_WHITEOUT_FRACTION
        val isFullFrameBlackout: Boolean
            get() = meanLuma <= BLACKOUT_MEAN_LUMA && blackFraction >= MAX_BLACKOUT_FRACTION

        fun toJson(): JSONObject = JSONObject()
            .put("file", fileName)
            .put("capturedElapsedNs", capturedElapsedNs)
            .put("meanLuma", meanLuma)
            .put("p05Luma", p05Luma)
            .put("p95Luma", p95Luma)
            .put("clippedFraction", clippedFraction)
            .put("blackFraction", blackFraction)
            .put("sampledPixels", sampledPixels)
            .put("fullFrameWhiteout", isFullFrameWhiteout)
            .put("fullFrameBlackout", isFullFrameBlackout)
    }

    private data class FrameSample(
        val phase: String,
        val generation: Long,
        val callbackElapsedNs: Long,
        val frameNumber: Long,
        val sensorTimestampNs: Long,
        val sessionGeneration: Long?,
        val requestRevision: Long?,
        val requestAeMode: Int?,
        val requestEv: Int?,
        val requestAntibandingMode: Int?,
        val requestFpsLower: Int?,
        val requestFpsUpper: Int?,
        val requestAfMode: Int?,
        val requestZoomRatio: Float?,
        val requestCropRegion: Rect?,
        val requestOisMode: Int?,
        val requestVideoStabilizationMode: Int?,
        val resultAeState: Int?,
        val resultEv: Int?,
        val resultAntibandingMode: Int?,
        val resultAfMode: Int?,
        val resultAfState: Int?,
        val resultIso: Int,
        val resultExposureNs: Long,
        val resultFrameDurationNs: Long,
        val resultZoomRatio: Float?,
        val resultCropRegion: Rect?,
        val activePhysicalCameraId: String?,
        val resultFocalLength: Float?,
        val resultOisMode: Int?,
        val resultVideoStabilizationMode: Int?
    ) {
        val exposureProduct: Double
            get() = resultIso.toDouble() * resultExposureNs.toDouble()

        fun latencyMs(mark: PhaseMark): Double =
            (callbackElapsedNs - mark.startedElapsedNs) / 1_000_000.0

        fun matchesZoom(expected: Float): Boolean {
            val requestMatches = requestZoomRatio?.let { abs(it - expected) <= ZOOM_REQUEST_TOLERANCE }
                ?: cropRepresentsZoom(requestCropRegion, expected)
            val resultMatches = resultZoomRatio?.let { abs(it - expected) <= ZOOM_RESULT_TOLERANCE }
                ?: cropRepresentsZoom(resultCropRegion, expected)
            return requestMatches && resultMatches
        }

        fun matchesStabilization(expected: CameraVideoStabilizationRequestPlan): Boolean {
            val requestOisMatches = expected.oisMode == null || requestOisMode == expected.oisMode
            val requestVideoMatches = expected.videoStabilizationMode == null ||
                requestVideoStabilizationMode == expected.videoStabilizationMode
            val resultOisMatches = expected.oisMode == null || resultOisMode == null ||
                resultOisMode == expected.oisMode
            val resultVideoMatches = expected.videoStabilizationMode == null ||
                resultVideoStabilizationMode == null ||
                resultVideoStabilizationMode == expected.videoStabilizationMode
            return requestOisMatches && requestVideoMatches && resultOisMatches && resultVideoMatches
        }

        private fun cropRepresentsZoom(crop: Rect?, expected: Float): Boolean {
            if (crop == null || expected < 1f) return false
            return crop.width() > 0 && crop.height() > 0
        }

        fun toJson(): JSONObject = JSONObject()
            .put("phase", phase)
            .put("frameNumber", frameNumber)
            .put("sensorTimestampNs", sensorTimestampNs)
            .put("sessionGeneration", sessionGeneration ?: JSONObject.NULL)
            .put("requestRevision", requestRevision ?: JSONObject.NULL)
            .put("requestAeMode", requestAeMode ?: JSONObject.NULL)
            .put("requestEv", requestEv ?: JSONObject.NULL)
            .put("requestAntibandingMode", requestAntibandingMode ?: JSONObject.NULL)
            .put("requestFpsLower", requestFpsLower ?: JSONObject.NULL)
            .put("requestFpsUpper", requestFpsUpper ?: JSONObject.NULL)
            .put("requestAfMode", requestAfMode ?: JSONObject.NULL)
            .put("requestZoomRatio", requestZoomRatio ?: JSONObject.NULL)
            .put("requestCropRegion", requestCropRegion?.flattenToString() ?: JSONObject.NULL)
            .put("requestOisMode", requestOisMode ?: JSONObject.NULL)
            .put("requestVideoStabilizationMode", requestVideoStabilizationMode ?: JSONObject.NULL)
            .put("resultAeState", resultAeState ?: JSONObject.NULL)
            .put("resultEv", resultEv ?: JSONObject.NULL)
            .put("resultAntibandingMode", resultAntibandingMode ?: JSONObject.NULL)
            .put("resultAfMode", resultAfMode ?: JSONObject.NULL)
            .put("resultAfState", resultAfState ?: JSONObject.NULL)
            .put("resultIso", resultIso)
            .put("resultExposureNs", resultExposureNs)
            .put("resultFrameDurationNs", resultFrameDurationNs)
            .put("resultZoomRatio", resultZoomRatio ?: JSONObject.NULL)
            .put("resultCropRegion", resultCropRegion?.flattenToString() ?: JSONObject.NULL)
            .put("activePhysicalCameraId", activePhysicalCameraId ?: JSONObject.NULL)
            .put("resultFocalLength", resultFocalLength ?: JSONObject.NULL)
            .put("resultOisMode", resultOisMode ?: JSONObject.NULL)
            .put("resultVideoStabilizationMode", resultVideoStabilizationMode ?: JSONObject.NULL)
    }

    private class CaptureLedger {
        private val phase = AtomicReference(PhaseMark("BOOT", SystemClock.elapsedRealtimeNanos(), 0L))
        val frames = ConcurrentLinkedQueue<FrameSample>()
        val gyroSamples = ConcurrentLinkedQueue<GyroSample>()

        fun beginPhase(name: String): PhaseMark {
            val next = PhaseMark(name, SystemClock.elapsedRealtimeNanos(), phase.get().generation + 1L)
            phase.set(next)
            return next
        }

        fun record(result: TotalCaptureResult) {
            val current = phase.get()
            val request = result.request
            val tag = request.tag as? Camera2RequestTag
            val requestedFps = request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
            frames += FrameSample(
                phase = current.name,
                generation = current.generation,
                callbackElapsedNs = SystemClock.elapsedRealtimeNanos(),
                frameNumber = result.frameNumber,
                sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                sessionGeneration = tag?.sessionGeneration,
                requestRevision = tag?.requestRevision,
                requestAeMode = request.get(CaptureRequest.CONTROL_AE_MODE),
                requestEv = request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION),
                requestAntibandingMode = request.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE),
                requestFpsLower = requestedFps?.lower,
                requestFpsUpper = requestedFps?.upper,
                requestAfMode = request.get(CaptureRequest.CONTROL_AF_MODE),
                requestZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    request.get(CaptureRequest.CONTROL_ZOOM_RATIO)
                } else null,
                requestCropRegion = request.get(CaptureRequest.SCALER_CROP_REGION),
                requestOisMode = request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE),
                requestVideoStabilizationMode = request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE),
                resultAeState = result.get(CaptureResult.CONTROL_AE_STATE),
                resultEv = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
                resultAntibandingMode = result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
                resultAfMode = result.get(CaptureResult.CONTROL_AF_MODE),
                resultAfState = result.get(CaptureResult.CONTROL_AF_STATE),
                resultIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                resultExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                resultFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
                resultZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                } else null,
                resultCropRegion = result.get(CaptureResult.SCALER_CROP_REGION),
                activePhysicalCameraId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                } else null,
                resultFocalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH),
                resultOisMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
                resultVideoStabilizationMode = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
            )
        }

        fun recordGyro(timestampNs: Long, x: Float, y: Float, z: Float) {
            val current = phase.get()
            gyroSamples += GyroSample(current.name, current.generation, timestampNs, x, y, z)
        }

        fun framesFor(mark: PhaseMark): List<FrameSample> = frames.filter {
            it.generation == mark.generation && it.callbackElapsedNs >= mark.startedElapsedNs
        }

        fun gyroFor(mark: PhaseMark): List<GyroSample> =
            gyroSamples.filter { it.generation == mark.generation }
    }

    private companion object {
        const val EVIDENCE_DIRECTORY = "lq021"
        const val TEST_TAG = "NeoQaLq021"
        const val TEST_WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 1_000L
        const val POLL_INTERVAL_MS = 40L
        const val SESSION_TIMEOUT_MS = 25_000L
        const val MODE_TIMEOUT_MS = 45_000L
        const val MODE_SETTLE_MS = 1_000L
        const val CONTROL_SETTLE_MS = 800L
        const val CONTROL_RESPONSE_TIMEOUT_MS = 8_000L
        const val ZOOM_RESPONSE_TIMEOUT_MS = 10_000L
        const val MAX_CONTROL_LATENCY_MS = 1_500.0
        const val MAX_CAPTURE_RESULT_GAP_MS = 750.0
        const val TEST_FPS = 25
        const val MIN_CONTROL_FRAMES = 8
        const val MIN_MOVEMENT_FRAMES = 24
        const val EVIDENCE_SAMPLE_LIMIT = 14
        const val MOVEMENT_SCREENSHOT_COUNT = 7
        const val MOVEMENT_SCREENSHOT_INTERVAL_MS = 1_800L
        const val MIN_INTEGRATED_PAN_RADIANS = 0.20
        const val MIN_PEAK_PAN_RAD_PER_SEC = 0.35
        const val MIN_HIGHLIGHT_TARGET_FRACTION = 0.002
        const val MIN_HIGHLIGHT_TARGET_P95 = 0.70
        const val MIN_EXPOSURE_PRODUCT_RANGE_RATIO = 1.50
        const val MIN_PREVIEW_LUMA_SPAN = 0.10
        const val CLIPPED_LUMA = 0.985
        const val BLACK_LUMA = 0.015
        const val WHITEOUT_MEAN_LUMA = 0.97
        const val BLACKOUT_MEAN_LUMA = 0.03
        const val MAX_WHITEOUT_FRACTION = 0.90
        const val MAX_BLACKOUT_FRACTION = 0.90
        const val IMAGE_SAMPLE_STEP = 8
        const val ZOOM_REQUEST_TOLERANCE = 0.02f
        const val ZOOM_RESULT_TOLERANCE = 0.03f
        val REQUIRED_CHECKPOINTS = listOf("CP-124", "CP-125", "CP-126", "CP-127", "CP-128", "CP-129")
    }
}
