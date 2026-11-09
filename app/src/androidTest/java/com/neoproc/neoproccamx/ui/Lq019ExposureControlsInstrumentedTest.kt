package com.neoproc.neoproccamx.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import com.neoproc.neoproccamx.camera.Camera2RequestTag
import com.neoproc.neoproccamx.camera.CameraState
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
 * Physical Camera2 proof for LQ-019 / CP-115..119.
 *
 * Every assertion is made against the request attached to a [TotalCaptureResult] and the matching
 * result metadata. This prevents a preference, label, or queued UI command from being mistaken for
 * a control that the active camera session actually accepted. User preferences and temporary camera
 * mutations are restored even when a checkpoint fails.
 *
 * The test establishes functional control correctness and bounded temporal behavior. It deliberately
 * does not claim comparative IMAX/telescope image quality, which requires a calibrated optical lab.
 */
@RunWith(AndroidJUnit4::class)
class Lq019ExposureControlsInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun camera2IsoShutterAndEv_areAppliedClampedAndAtomicAcrossModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalPreferences = snapshotPreferences(context)
        val stages = mutableListOf<JSONObject>()
        val failures = mutableListOf<String>()
        val checkpoints = linkedMapOf<String, JSONObject>()
        val ledger = CaptureLedger()
        val startedElapsedMs = SystemClock.elapsedRealtime()
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: CameraViewModel? = null
        var captureHook: CaptureHook? = null
        var failure: Throwable? = null

        markStage(stages, startedElapsedMs, "PREFERENCES_SNAPSHOTTED")
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            vm = readVm(scenario)
            awaitStreaming(requireNotNull(vm))
            configureMode(scenario, requireNotNull(vm), ExposureMode.PRO)
            captureHook = installCaptureHook(requireNotNull(vm), ledger)
            markStage(stages, startedElapsedMs, "CAMERA2_OBSERVER_ATTACHED")

            val autoIso = exerciseAutoIso(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-115"] = autoIso
            if (!autoIso.optBoolean("passed")) failures += "CP-115 auto ISO authority/recovery failed"
            markStage(stages, startedElapsedMs, "CP115_AUTO_ISO_CAPTURED")

            val manualIso = exerciseManualIsoBoundaries(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-116"] = manualIso
            if (!manualIso.optBoolean("passed")) failures += "CP-116 manual ISO response/clamp failed"
            markStage(stages, startedElapsedMs, "CP116_MANUAL_ISO_CAPTURED")

            val shutter = exerciseShutter(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-117"] = shutter
            if (!shutter.optBoolean("passed")) failures += "CP-117 auto/manual shutter failed"
            markStage(stages, startedElapsedMs, "CP117_SHUTTER_CAPTURED")

            val ev = exerciseEvResponse(context, scenario, requireNotNull(vm), ledger)
            checkpoints["CP-118"] = ev
            if (!ev.optBoolean("passed")) failures += "CP-118 EV response/highlight safety failed"
            markStage(stages, startedElapsedMs, "CP118_EV_CAPTURED")

            val combined = exerciseCombinedControlsAcrossModes(scenario, requireNotNull(vm), ledger)
            checkpoints["CP-119"] = combined
            if (!combined.optBoolean("passed")) failures += "CP-119 combined-control atomicity failed"
            markStage(stages, startedElapsedMs, "CP119_COMBINED_CONTROLS_CAPTURED")

            captureScreenshot(context, "lq019-final-preview.png")
            markStage(stages, startedElapsedMs, "FINAL_PREVIEW_CAPTURED")
            if (failures.isNotEmpty()) throw AssertionError(failures.joinToString(" | "))
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
                        activeVm.setRawEnabled(false)
                        activeVm.setCinemaVideoMode(CinemaVideoMode.PRO)
                        activeVm.setVideoHdrMode("OFF")
                        activeVm.setLogEnabled(false)
                        restoreCaptureHook(activeVm, captureHook)
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

    private fun configureMode(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        mode: ExposureMode
    ) {
        val priorTimestamp = vm.liveResult.value?.timestampNs ?: 0L
        scenario.onActivity {
            vm.setMode(AppMode.VIDEO)
            vm.setFlash(FlashMode.OFF)
            vm.camera.setAeAwbLock(false)
            vm.setVideoHdrMode("OFF")
            vm.setLogEnabled(false)
            when (mode) {
                ExposureMode.VIDEO -> {
                    vm.setRawEnabled(false)
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
                    if (vm.isManualControlsVisible) vm.toggleManualControls()
                    vm.applyManualControlsForPreset(CameraModePreset.VIDEO)
                }
                ExposureMode.PRO -> {
                    vm.setRawEnabled(false)
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setSdrQualityIntent(SdrQualityIntent.PRO)
                    if (!vm.isManualControlsVisible) vm.toggleManualControls()
                    vm.applyManualControlsForPreset(CameraModePreset.PRO)
                }
                ExposureMode.RAW -> {
                    vm.setCinemaVideoMode(CinemaVideoMode.RAW)
                    vm.setRawEnabled(true)
                    if (!vm.isManualControlsVisible) vm.toggleManualControls()
                    vm.applyManualControlsForPreset(CameraModePreset.RAW_MODE)
                }
            }
            vm.setEV(0)
            vm.setAutoISO()
            vm.setAutoExposure()
            vm.setAutoWB()
        }
        check(awaitCondition(MODE_TIMEOUT_MS) {
            val modeMatches = when (mode) {
                ExposureMode.VIDEO, ExposureMode.PRO ->
                    vm.cinemaVideoMode.value == CinemaVideoMode.PRO && !vm.rawOnFlow.value
                ExposureMode.RAW ->
                    vm.cinemaVideoMode.value == CinemaVideoMode.RAW && vm.rawOnFlow.value
            }
            modeMatches && vm.uiMode.value == AppMode.VIDEO &&
                vm.cameraState.value is CameraState.Streaming &&
                (vm.liveResult.value?.timestampNs ?: 0L) > priorTimestamp
        }) { "$mode did not reach a fresh streaming session" }
        SystemClock.sleep(MODE_SETTLE_MS)
    }

    private fun exerciseAutoIso(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        val range = vm.camera.effectiveIsoRange
        val liveExposure = (vm.liveResult.value?.exposureNs ?: 10_000_000L)
            .coerceIn(vm.camera.exposureRange.lower, vm.camera.exposureRange.upper)
        val manualIso = max(800, range.lower).coerceAtMost(range.upper)
        val manualMark = ledger.beginPhase("CP115_MANUAL_PREREQUISITE")
        scenario.onActivity {
            vm.setEV(0)
            vm.setExposure(liveExposure)
            vm.setISO(manualIso)
        }
        val manualFrames = awaitMatchingFrames(
            ledger,
            manualMark,
            MIN_SETTLED_FRAMES,
            MANUAL_RESPONSE_TIMEOUT_MS
        ) { frame ->
            frame.requestAeMode == CaptureRequest.CONTROL_AE_MODE_OFF &&
                frame.requestSensorIso != null && frame.resultIso > 0 &&
                frame.requestExposureNs != null && frame.resultExposureNs > 0L
        }

        val autoMark = ledger.beginPhase("CP115_AUTO_RESTORE")
        scenario.onActivity {
            vm.setAutoISO()
            vm.setAutoExposure()
        }
        val firstAuto = awaitFrameMatch(ledger, autoMark, AUTO_RESPONSE_TIMEOUT_MS) { it.isAutoAeFrame() }
        val autoFrames = awaitMatchingFrames(
            ledger,
            autoMark,
            MIN_EVIDENCE_FRAMES,
            FRAME_WINDOW_TIMEOUT_MS
        ) { it.isAutoAeFrame() }
        val latencyMs = firstAuto?.latencyMs(autoMark)
        val products = autoFrames.map(FrameSample::exposureProduct)
        val pumpingRatio = coefficientOfRange(products)
        val passed = manualFrames.size >= MIN_SETTLED_FRAMES &&
            firstAuto != null && autoFrames.size >= MIN_EVIDENCE_FRAMES &&
            timestampsStrictlyIncrease(autoFrames) &&
            vm.cameraParams.value.iso < 0 &&
            vm.cameraParams.value.exposureNs < 0L &&
            (latencyMs ?: Double.MAX_VALUE) <= CONTROL_RESPONSE_LIMIT_MS &&
            pumpingRatio <= MAX_AUTO_EXPOSURE_RANGE_RATIO
        return JSONObject()
            .put("passed", passed)
            .put("threshold", "Manual authority removed; >=8 monotonic AUTO AE frames within 1500 ms; bounded fixed-scene exposure-product range")
            .put("effectiveIsoRange", "${range.lower}..${range.upper}")
            .put("manualFrameCount", manualFrames.size)
            .put("autoFrameCount", autoFrames.size)
            .put("autoLatencyMs", latencyMs ?: JSONObject.NULL)
            .put("autoExposureProductRangeRatio", pumpingRatio)
            .put("samples", JSONArray(autoFrames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
    }

    private fun exerciseManualIsoBoundaries(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        scenario.onActivity {
            vm.setEV(0)
            vm.setAutoISO()
            vm.setAutoExposure()
        }
        awaitStableFrames(vm)
        val effectiveRange = vm.camera.effectiveIsoRange
        val sensorRange = vm.camera.isoRange
        val boostRange = vm.camera.postRawSensitivityBoostRange
        val midpoint = ((effectiveRange.lower.toLong() + effectiveRange.upper.toLong()) / 2L).toInt()
        val probes = listOf(
            // Negative ISO is the public AUTO sentinel, so use the smallest positive value to
            // exercise a genuine below-range manual request without accidentally selecting AUTO.
            IsoProbe("BELOW_MIN_CLAMP", (effectiveRange.lower - 1).coerceAtLeast(1)),
            IsoProbe("MID", midpoint),
            IsoProbe("ABOVE_MAX_CLAMP", (effectiveRange.upper.toLong() + 10_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        )
        val neutralIso = (vm.liveResult.value?.iso ?: sensorRange.lower).coerceAtLeast(1)
        val neutralExposure = (vm.liveResult.value?.exposureNs ?: 10_000_000L).coerceAtLeast(1L)
        val neutralProduct = neutralIso.toDouble() * neutralExposure.toDouble()
        val results = JSONArray()
        var allPassed = true

        probes.forEachIndexed { index, probe ->
            val expectedEffectiveIso = probe.inputIso.coerceIn(effectiveRange.lower, effectiveRange.upper)
            val expected = resolveEffectiveIso(
                requestedIso = expectedEffectiveIso,
                sensorLower = sensorRange.lower,
                sensorUpper = sensorRange.upper,
                boostLower = boostRange.lower,
                boostUpper = boostRange.upper
            )
            val pairedExposure = (neutralProduct / expected.effectiveIso.toDouble()).toLong()
                .coerceIn(vm.camera.exposureRange.lower, minOf(vm.camera.exposureRange.upper, MAX_MANUAL_EXPOSURE_NS))
            val mark = ledger.beginPhase("CP116_${index}_${probe.label}")
            scenario.onActivity {
                vm.setExposure(pairedExposure)
                vm.setISO(probe.inputIso)
            }
            val matching = awaitMatchingFrames(
                ledger,
                mark,
                MIN_SETTLED_FRAMES,
                MANUAL_RESPONSE_TIMEOUT_MS
            ) { it.matchesEffectiveIso(expected) && it.hasValidManualExposureEnvelope() }
            val phaseSamples = ledger.framesFor(mark)
            val first = matching.firstOrNull()
            val latencyMs = first?.latencyMs(mark)
            val pointPassed = matching.size >= MIN_SETTLED_FRAMES &&
                timestampsStrictlyIncrease(matching) &&
                (latencyMs ?: Double.MAX_VALUE) <= MANUAL_RESPONSE_LIMIT_MS
            allPassed = allPassed && pointPassed
            results.put(JSONObject()
                .put("label", probe.label)
                .put("inputIso", probe.inputIso)
                .put("expectedClampedEffectiveIso", expected.effectiveIso)
                .put("expectedSensorIso", expected.sensorIso)
                .put("expectedPostRawBoost", expected.postRawBoost)
                .put("pairedExposureNs", pairedExposure)
                .put("latencyMs", latencyMs ?: JSONObject.NULL)
                .put("matchingFrames", matching.size)
                .put("observedFrames", phaseSamples.size)
                .put("passed", pointPassed)
                .put("samples", JSONArray(matching.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
                .put(
                    "phaseSamples",
                    JSONArray(phaseSamples.takeLast(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson))
                ))
        }
        return JSONObject()
            .put("passed", allPassed)
            .put("threshold", "Below-min, midpoint and above-max inputs must clamp deterministically; Camera2 request/result ISO plus post-RAW boost must match for >=5 monotonic AE-OFF frames with a valid positive exposure/frame-duration envelope")
            .put("sensorIsoRange", "${sensorRange.lower}..${sensorRange.upper}")
            .put("postRawBoostRange", "${boostRange.lower}..${boostRange.upper}")
            .put("effectiveIsoRange", "${effectiveRange.lower}..${effectiveRange.upper}")
            .put("points", results)
    }

    private fun exerciseShutter(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        scenario.onActivity {
            vm.setEV(0)
            vm.setAutoISO()
            vm.setAutoExposure()
        }
        awaitStableFrames(vm)
        val exposureRange = vm.camera.exposureRange
        val fixedIso = (vm.liveResult.value?.iso ?: 400)
            .coerceIn(vm.camera.isoRange.lower, vm.camera.isoRange.upper)
        val candidates = listOf(FAST_SHUTTER_NS, MID_SHUTTER_NS, SLOW_SHUTTER_NS)
            .map { it.coerceIn(exposureRange.lower, exposureRange.upper) }
            .distinct()
        val points = JSONArray()
        var manualPassed = candidates.size >= 3
        candidates.forEachIndexed { index, requestedExposure ->
            val mark = ledger.beginPhase("CP117_MANUAL_$index")
            scenario.onActivity {
                vm.setISO(fixedIso)
                vm.setExposure(requestedExposure)
            }
            val frames = awaitMatchingFrames(
                ledger,
                mark,
                MIN_SETTLED_FRAMES,
                MANUAL_RESPONSE_TIMEOUT_MS
            ) { it.matchesExposure(requestedExposure) && it.requestSensorIso == fixedIso }
            val first = frames.firstOrNull()
            val maxGapMs = maximumSensorGapMs(frames)
            val cadenceSafe = frames.all { frame ->
                val duration = frame.resultFrameDurationNs.takeIf { it > 0L }
                    ?: frame.requestFrameDurationNs
                duration == null || duration >= frame.resultExposureNs
            }
            val pointPassed = frames.size >= MIN_SETTLED_FRAMES &&
                timestampsStrictlyIncrease(frames) && cadenceSafe &&
                maxGapMs <= MAX_CAPTURE_RESULT_GAP_MS &&
                (first?.latencyMs(mark) ?: Double.MAX_VALUE) <= MANUAL_RESPONSE_LIMIT_MS
            manualPassed = manualPassed && pointPassed
            points.put(JSONObject()
                .put("requestedExposureNs", requestedExposure)
                .put("matchingFrames", frames.size)
                .put("latencyMs", first?.latencyMs(mark) ?: JSONObject.NULL)
                .put("maximumCaptureResultGapMs", maxGapMs)
                .put("cadenceSafe", cadenceSafe)
                .put("passed", pointPassed)
                .put("samples", JSONArray(frames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson))))
        }

        val autoMark = ledger.beginPhase("CP117_AUTO_SHUTTER_RESTORE")
        scenario.onActivity {
            vm.setAutoExposure()
            vm.setAutoISO()
        }
        val auto = awaitFrameMatch(ledger, autoMark, AUTO_RESPONSE_TIMEOUT_MS) { it.isAutoAeFrame() }
        val autoFrames = awaitMatchingFrames(
            ledger,
            autoMark,
            MIN_EVIDENCE_FRAMES,
            FRAME_WINDOW_TIMEOUT_MS
        ) { it.isAutoAeFrame() }
        val autoPassed = auto != null && autoFrames.size >= MIN_EVIDENCE_FRAMES &&
            timestampsStrictlyIncrease(autoFrames) &&
            (auto.latencyMs(autoMark)) <= CONTROL_RESPONSE_LIMIT_MS
        return JSONObject()
            .put("passed", manualPassed && autoPassed)
            .put("threshold", "Fast/mid/slow manual shutter request=result with exposure <= frame duration, bounded capture gaps, then AUTO restoration within 1500 ms")
            .put("exposureRangeNs", "${exposureRange.lower}..${exposureRange.upper}")
            .put("fixedSensorIso", fixedIso)
            .put("manualPoints", points)
            .put("autoRestorePassed", autoPassed)
            .put("autoRestoreLatencyMs", auto?.latencyMs(autoMark) ?: JSONObject.NULL)
            .put("autoSamples", JSONArray(autoFrames.take(EVIDENCE_SAMPLE_LIMIT).map(FrameSample::toJson)))
    }

    private fun exerciseEvResponse(
        context: Context,
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        scenario.onActivity {
            vm.setAutoISO()
            vm.setAutoExposure()
            vm.setEV(0)
        }
        awaitStableFrames(vm)
        val supported = EV_SEQUENCE.map { it.coerceIn(vm.camera.evRange.lower, vm.camera.evRange.upper) }
            .distinct()
        val points = mutableListOf<EvPoint>()
        supported.forEach { requestedEv ->
            val mark = ledger.beginPhase("CP118_EV_${if (requestedEv < 0) "M" else "P"}${abs(requestedEv)}")
            scenario.onActivity { vm.setEV(requestedEv) }
            val frames = awaitMatchingFrames(
                ledger,
                mark,
                MIN_EV_FRAMES,
                EV_SETTLE_TIMEOUT_MS
            ) { frame ->
                frame.requestAeMode != CaptureRequest.CONTROL_AE_MODE_OFF &&
                    frame.requestEv == requestedEv && frame.resultEv == requestedEv &&
                    frame.resultIso > 0 && frame.resultExposureNs > 0L
            }
            SystemClock.sleep(EV_IMAGE_SETTLE_MS)
            val imageMetric = capturePreviewMetric(context, "lq019-ev-${requestedEv}.png")
            val settled = frames.takeLast(MIN_SETTLED_FRAMES)
            points += EvPoint(
                requestedEv = requestedEv,
                frames = settled,
                latencyMs = frames.firstOrNull()?.latencyMs(mark),
                medianExposureProduct = median(settled.map(FrameSample::exposureProduct)),
                image = imageMetric
            )
        }
        val metadataMonotonic = points.zipWithNext().all { (lower, higher) ->
            higher.medianExposureProduct >= lower.medianExposureProduct * EV_MONOTONIC_TOLERANCE
        }
        val endpointLumaResponsive = if (points.size >= 2) {
            points.last().image.meanLuma >= points.first().image.meanLuma + MIN_EV_ENDPOINT_LUMA_DELTA
        } else false
        val noWhiteout = points.all { it.image.clippedFraction <= MAX_PREVIEW_CLIPPED_FRACTION }
        val requestResultPassed = points.size == supported.size && points.all { point ->
            point.frames.size >= MIN_SETTLED_FRAMES &&
                timestampsStrictlyIncrease(point.frames) &&
                (point.latencyMs ?: Double.MAX_VALUE) <= CONTROL_RESPONSE_LIMIT_MS
        }
        scenario.onActivity { vm.setEV(0) }
        val passed = supported.size >= 3 && requestResultPassed && metadataMonotonic &&
            endpointLumaResponsive && noWhiteout
        return JSONObject()
            .put("passed", passed)
            .put("threshold", "EV -2..+2 (within advertised range) request=result, exposure product monotonic, positive endpoint preview-luma response, clipped fraction <=95%")
            .put("advertisedRange", "${vm.camera.evRange.lower}..${vm.camera.evRange.upper}")
            .put("requestResultPassed", requestResultPassed)
            .put("metadataMonotonic", metadataMonotonic)
            .put("endpointLumaResponsive", endpointLumaResponsive)
            .put("noWhiteout", noWhiteout)
            .put("points", JSONArray(points.map(EvPoint::toJson)))
    }

    private fun exerciseCombinedControlsAcrossModes(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        ledger: CaptureLedger
    ): JSONObject {
        val modes = JSONArray()
        var allPassed = true
        ExposureMode.entries.forEach { mode ->
            configureMode(scenario, vm, mode)
            val isoRange = vm.camera.effectiveIsoRange
            val exposureRange = vm.camera.exposureRange
            val combinations = listOf(
                CombinedProbe(
                    label = "A",
                    kelvin = 4_300,
                    tint = -6,
                    iso = max(200, isoRange.lower).coerceAtMost(isoRange.upper),
                    exposureNs = 5_000_000L.coerceIn(exposureRange.lower, exposureRange.upper),
                    ev = (-1).coerceIn(vm.camera.evRange.lower, vm.camera.evRange.upper)
                ),
                CombinedProbe(
                    label = "B",
                    kelvin = 6_500,
                    tint = 7,
                    iso = max(800, isoRange.lower).coerceAtMost(isoRange.upper),
                    exposureNs = 10_000_000L.coerceIn(exposureRange.lower, exposureRange.upper),
                    ev = 1.coerceIn(vm.camera.evRange.lower, vm.camera.evRange.upper)
                )
            )
            val combinationEvidence = JSONArray()
            val signatures = mutableListOf<String>()
            var modePassed = true
            combinations.forEach { probe ->
                val expectedIso = resolveEffectiveIso(
                    requestedIso = probe.iso,
                    sensorLower = vm.camera.isoRange.lower,
                    sensorUpper = vm.camera.isoRange.upper,
                    boostLower = vm.camera.postRawSensitivityBoostRange.lower,
                    boostUpper = vm.camera.postRawSensitivityBoostRange.upper
                )
                val mark = ledger.beginPhase("CP119_${mode.name}_${probe.label}")
                scenario.onActivity {
                    vm.setWB(probe.kelvin, probe.tint)
                    vm.setISO(probe.iso)
                    vm.setExposure(probe.exposureNs)
                    vm.setEV(probe.ev)
                }
                val frames = awaitMatchingFrames(
                    ledger,
                    mark,
                    MIN_SETTLED_FRAMES,
                    COMBINED_RESPONSE_TIMEOUT_MS
                ) { frame ->
                    frame.matchesEffectiveIso(expectedIso) &&
                        frame.matchesExposure(probe.exposureNs) &&
                        frame.requestEv == 0 &&
                        frame.whiteBalanceMatches(mode)
                }
                val settledRevision = frames.lastOrNull()?.requestRevision
                val settledFrames = frames.takeLast(MIN_SETTLED_FRAMES)
                // Preview requests do not carry Camera2RequestTag on every vendor. When a revision
                // is absent, five byte-equivalent control payloads are the reproducible coherence
                // proof instead of incorrectly failing a valid HAL request.
                val coherent = if (settledRevision != null) {
                    settledFrames.all { it.requestRevision == settledRevision }
                } else {
                    settledFrames.size >= MIN_SETTLED_FRAMES &&
                        settledFrames.map(FrameSample::controlSignature).distinct().size == 1
                }
                val paramsRetained = vm.cameraParams.value.let { params ->
                    params.iso == probe.iso &&
                        params.exposureNs == probe.exposureNs &&
                        params.wbKelvin == probe.kelvin &&
                        params.ev == probe.ev
                }
                val latest = frames.lastOrNull()
                val signature = latest?.controlSignature().orEmpty()
                signatures += signature
                val pointPassed = frames.size >= MIN_SETTLED_FRAMES && coherent &&
                    paramsRetained && latest != null &&
                    maximumSensorGapMs(settledFrames) <= MAX_CAPTURE_RESULT_GAP_MS
                modePassed = modePassed && pointPassed
                combinationEvidence.put(JSONObject()
                    .put("label", probe.label)
                    .put("requestedKelvin", probe.kelvin)
                    .put("requestedTint", probe.tint)
                    .put("requestedIso", probe.iso)
                    .put("requestedExposureNs", probe.exposureNs)
                    .put("requestedEv", probe.ev)
                    .put("fullManualEvResolution", "ISO_AND_SHUTTER_AUTHORITATIVE_EV_RETAINED_AS_UI_INTENT")
                    .put("whiteBalanceTransport", if (mode == ExposureMode.RAW) {
                        "RAW_SENSOR_MASTER_ADAPTIVE_METADATA_NO_PIXEL_MUTATION"
                    } else {
                        "CAMERA2_MANUAL_GAINS_AND_MATRIX"
                    })
                    .put("matchingFrames", frames.size)
                    .put("settledRequestRevision", settledRevision ?: JSONObject.NULL)
                    .put("coherentSingleRevision", coherent)
                    .put("viewModelStateRetained", paramsRetained)
                    .put("controlSignature", signature)
                    .put("passed", pointPassed)
                    .put("samples", JSONArray(settledFrames.map(FrameSample::toJson))))
            }
            val distinct = signatures.size == combinations.size && signatures.toSet().size == signatures.size
            modePassed = modePassed && distinct
            allPassed = allPassed && modePassed
            modes.put(JSONObject()
                .put("mode", mode.name)
                .put("distinctCombinedPayloads", distinct)
                .put("passed", modePassed)
                .put("combinations", combinationEvidence))
        }
        return JSONObject()
            .put("passed", allPassed)
            .put("threshold", "Video/Pro/Raw each retain two distinct WB+ISO+shutter+EV intents in one stable Camera2 payload with no exposure auto takeover or capture gap >750 ms; Raw preserves sensor-master WB metadata instead of mutating RAW pixels")
            .put("modes", modes)
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
        val bitmap = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "Could not capture $name" }
        val file = File(evidenceDirectory(context), name)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val left = (bitmap.width * 0.18f).roundToInt()
        val right = (bitmap.width * 0.82f).roundToInt()
        val top = (bitmap.height * 0.16f).roundToInt()
        val bottom = (bitmap.height * 0.62f).roundToInt()
        var sum = 0.0
        var count = 0
        var clipped = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                val luma = (0.2126 * Color.red(pixel) +
                    0.7152 * Color.green(pixel) +
                    0.0722 * Color.blue(pixel)) / 255.0
                sum += luma
                count++
                if (luma >= CLIPPED_LUMA) clipped++
                x += IMAGE_SAMPLE_STEP
            }
            y += IMAGE_SAMPLE_STEP
        }
        bitmap.recycle()
        return PreviewMetric(
            fileName = name,
            meanLuma = if (count > 0) sum / count.toDouble() else 0.0,
            clippedFraction = if (count > 0) clipped.toDouble() / count.toDouble() else 1.0,
            sampledPixels = count
        )
    }

    private fun captureScreenshot(context: Context, name: String) {
        val bitmap = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "Could not capture LQ-019 final screenshot" }
        File(evidenceDirectory(context), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
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
        val allPassed = REQUIRED_CHECKPOINTS.all { checkpoints[it]?.optBoolean("passed") == true }
        val report = JSONObject()
            .put("schema", "neo-qa-lq019-exposure-controls/v1")
            .put("checkpoint", "LQ-019")
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
            .put("checkpoints", JSONObject(checkpoints))
            .put("stages", JSONArray(stages))
            .put("qualityClaim", "Camera2 ISO/shutter/EV correctness, stability and preview whiteout safety only; no unmeasured IMAX/telescope equivalence claim")
            .put("passed", allPassed && failure == null)
            .put("failure", failure?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL)
        val directory = evidenceDirectory(context)
        val temporary = File(directory, "lq019.json.tmp")
        val destination = File(directory, "lq019.json")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        if (destination.exists()) check(destination.delete()) { "Could not replace prior LQ-019 evidence" }
        check(temporary.renameTo(destination)) { "Could not atomically publish LQ-019 evidence" }
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
        check(editor.commit()) { "Could not restore user preferences after LQ-019" }
    }

    private fun markStage(stages: MutableList<JSONObject>, startedElapsedMs: Long, name: String) {
        stages += JSONObject()
            .put("name", name)
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedElapsedMs)
    }

    private fun resolveEffectiveIso(
        requestedIso: Int,
        sensorLower: Int,
        sensorUpper: Int,
        boostLower: Int,
        boostUpper: Int
    ): ExpectedIso {
        val normalizedBoostLower = minOf(boostLower, boostUpper).coerceAtLeast(1)
        val normalizedBoostUpper = maxOf(boostLower, boostUpper).coerceAtLeast(normalizedBoostLower)
        val defaultBoost = 100.coerceIn(normalizedBoostLower, normalizedBoostUpper)
        val maxEffective = ((sensorUpper.toLong() * normalizedBoostUpper) / 100L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val effectiveRequest = requestedIso.coerceIn(sensorLower, maxEffective)
        if (effectiveRequest <= sensorUpper) {
            return ExpectedIso(effectiveRequest, defaultBoost, effectiveRequest)
        }
        val boost = (((effectiveRequest.toLong() * 100L) + sensorUpper - 1L) / sensorUpper)
            .coerceIn(normalizedBoostLower.toLong(), normalizedBoostUpper.toLong()).toInt()
        return ExpectedIso(
            sensorIso = sensorUpper,
            postRawBoost = boost,
            effectiveIso = ((sensorUpper.toLong() * boost) / 100L).toInt()
        )
    }

    private fun timestampsStrictlyIncrease(samples: List<FrameSample>): Boolean =
        samples.size >= 2 && samples.zipWithNext().all { (first, second) ->
            second.sensorTimestampNs > first.sensorTimestampNs && second.frameNumber > first.frameNumber
        }

    private fun maximumSensorGapMs(samples: List<FrameSample>): Double =
        samples.zipWithNext().maxOfOrNull { (first, second) ->
            (second.sensorTimestampNs - first.sensorTimestampNs) / 1_000_000.0
        } ?: 0.0

    private fun coefficientOfRange(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val median = median(values)
        if (median <= 0.0) return Double.POSITIVE_INFINITY
        return ((values.maxOrNull() ?: median) - (values.minOrNull() ?: median)) / median
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
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

    private enum class ExposureMode { VIDEO, PRO, RAW }

    private data class CaptureHook(
        val previous: ((TotalCaptureResult) -> Unit)?,
        val observer: (TotalCaptureResult) -> Unit
    )

    private data class PhaseMark(
        val name: String,
        val startedElapsedNs: Long,
        val generation: Long
    )

    private data class IsoProbe(val label: String, val inputIso: Int)

    private data class ExpectedIso(
        val sensorIso: Int,
        val postRawBoost: Int,
        val effectiveIso: Int
    )

    private data class CombinedProbe(
        val label: String,
        val kelvin: Int,
        val tint: Int,
        val iso: Int,
        val exposureNs: Long,
        val ev: Int
    )

    private data class PreviewMetric(
        val fileName: String,
        val meanLuma: Double,
        val clippedFraction: Double,
        val sampledPixels: Int
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("file", fileName)
            .put("meanLuma", meanLuma)
            .put("clippedFraction", clippedFraction)
            .put("sampledPixels", sampledPixels)
    }

    private data class EvPoint(
        val requestedEv: Int,
        val frames: List<FrameSample>,
        val latencyMs: Double?,
        val medianExposureProduct: Double,
        val image: PreviewMetric
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("requestedEv", requestedEv)
            .put("matchingFrames", frames.size)
            .put("latencyMs", latencyMs ?: JSONObject.NULL)
            .put("medianExposureProduct", medianExposureProduct)
            .put("preview", image.toJson())
            .put("samples", JSONArray(frames.map(FrameSample::toJson)))
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
        val requestAePriorityMode: Int?,
        val requestSensorIso: Int?,
        val requestPostRawBoost: Int?,
        val requestExposureNs: Long?,
        val requestFrameDurationNs: Long?,
        val requestEv: Int?,
        val requestAwbMode: Int?,
        val requestColorMode: Int?,
        val requestCct: Int?,
        val requestTint: Int?,
        val requestGains: FloatArray?,
        val requestCcm: FloatArray?,
        val resultAeMode: Int?,
        val resultAeState: Int?,
        val resultAwbMode: Int?,
        val resultGains: FloatArray?,
        val resultCcm: FloatArray?,
        val resultIso: Int,
        val resultPostRawBoost: Int?,
        val resultExposureNs: Long,
        val resultFrameDurationNs: Long,
        val resultEv: Int?
    ) {
        val exposureProduct: Double
            get() = resultIso.toDouble() * resultExposureNs.toDouble() *
                ((resultPostRawBoost ?: 100).toDouble() / 100.0)

        fun latencyMs(mark: PhaseMark): Double =
            (callbackElapsedNs - mark.startedElapsedNs) / 1_000_000.0

        fun isAutoAeFrame(): Boolean =
            requestAeMode != null && requestAeMode != CaptureRequest.CONTROL_AE_MODE_OFF &&
                requestSensorIso == null && requestExposureNs == null &&
                resultIso > 0 && resultExposureNs > 0L &&
                resultAeState in setOf(
                    CaptureResult.CONTROL_AE_STATE_SEARCHING,
                    CaptureResult.CONTROL_AE_STATE_CONVERGED,
                    CaptureResult.CONTROL_AE_STATE_LOCKED,
                    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                )

        fun matchesEffectiveIso(expected: ExpectedIso): Boolean =
            requestAeMode == CaptureRequest.CONTROL_AE_MODE_OFF &&
                requestSensorIso == expected.sensorIso &&
                (requestPostRawBoost ?: 100) == expected.postRawBoost &&
                resultIso == expected.sensorIso &&
                (resultPostRawBoost ?: requestPostRawBoost ?: 100) == expected.postRawBoost

        fun matchesExposure(expectedNs: Long): Boolean {
            val request = requestExposureNs ?: return false
            val tolerance = max(EXPOSURE_ABSOLUTE_TOLERANCE_NS, (expectedNs * EXPOSURE_RELATIVE_TOLERANCE).toLong())
            return requestAeMode == CaptureRequest.CONTROL_AE_MODE_OFF &&
                abs(request - expectedNs) <= tolerance &&
                abs(resultExposureNs - request) <= tolerance &&
                resultExposureNs > 0L
        }

        fun hasValidManualExposureEnvelope(): Boolean {
            val requestExposure = requestExposureNs ?: return false
            val requestFrameDuration = requestFrameDurationNs ?: return false
            return requestAeMode == CaptureRequest.CONTROL_AE_MODE_OFF &&
                requestExposure > 0L && resultExposureNs > 0L &&
                requestFrameDuration > 0L && resultFrameDurationNs > 0L &&
                requestExposure <= requestFrameDuration &&
                resultExposureNs <= resultFrameDurationNs
        }

        fun whiteBalanceMatches(mode: ExposureMode): Boolean = when (mode) {
            ExposureMode.RAW ->
                requestAwbMode != null && requestAwbMode != CameraMetadata.CONTROL_AWB_MODE_OFF &&
                    resultAwbMode != null && resultAwbMode != CameraMetadata.CONTROL_AWB_MODE_OFF &&
                    resultGains?.size == 4 && resultCcm?.size == 9
            ExposureMode.VIDEO, ExposureMode.PRO ->
                requestAwbMode == CameraMetadata.CONTROL_AWB_MODE_OFF &&
                    (requestColorMode == CameraMetadata.COLOR_CORRECTION_MODE_CCT ||
                        (requestGains?.size == 4 && requestCcm?.size == 9))
        }

        fun controlSignature(): String = listOf(
            requestAwbMode,
            requestColorMode,
            requestCct,
            requestTint,
            requestGains?.joinToString(","),
            requestCcm?.joinToString(","),
            requestSensorIso,
            requestPostRawBoost,
            requestExposureNs,
            requestEv
        ).joinToString("|")

        fun toJson(): JSONObject = JSONObject()
            .put("phase", phase)
            .put("frameNumber", frameNumber)
            .put("sensorTimestampNs", sensorTimestampNs)
            .put("sessionGeneration", sessionGeneration ?: JSONObject.NULL)
            .put("requestRevision", requestRevision ?: JSONObject.NULL)
            .put("requestAeMode", requestAeMode ?: JSONObject.NULL)
            .put("requestAePriorityMode", requestAePriorityMode ?: JSONObject.NULL)
            .put("requestSensorIso", requestSensorIso ?: JSONObject.NULL)
            .put("requestPostRawBoost", requestPostRawBoost ?: JSONObject.NULL)
            .put("requestExposureNs", requestExposureNs ?: JSONObject.NULL)
            .put("requestFrameDurationNs", requestFrameDurationNs ?: JSONObject.NULL)
            .put("requestEv", requestEv ?: JSONObject.NULL)
            .put("requestAwbMode", requestAwbMode ?: JSONObject.NULL)
            .put("requestColorMode", requestColorMode ?: JSONObject.NULL)
            .put("requestCct", requestCct ?: JSONObject.NULL)
            .put("requestTint", requestTint ?: JSONObject.NULL)
            .put("requestGains", requestGains?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("requestCcm", requestCcm?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("resultAeMode", resultAeMode ?: JSONObject.NULL)
            .put("resultAeState", resultAeState ?: JSONObject.NULL)
            .put("resultAwbMode", resultAwbMode ?: JSONObject.NULL)
            .put("resultGains", resultGains?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("resultCcm", resultCcm?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("resultIso", resultIso)
            .put("resultPostRawBoost", resultPostRawBoost ?: JSONObject.NULL)
            .put("resultExposureNs", resultExposureNs)
            .put("resultFrameDurationNs", resultFrameDurationNs)
            .put("resultEv", resultEv ?: JSONObject.NULL)

    }

    private class CaptureLedger {
        private val phase = AtomicReference(PhaseMark("BOOT", SystemClock.elapsedRealtimeNanos(), 0L))
        val frames = ConcurrentLinkedQueue<FrameSample>()

        fun beginPhase(name: String): PhaseMark {
            val next = PhaseMark(name, SystemClock.elapsedRealtimeNanos(), phase.get().generation + 1L)
            phase.set(next)
            return next
        }

        @Suppress("DEPRECATION")
        fun record(result: TotalCaptureResult) {
            val current = phase.get()
            val request = result.request
            val tag = request.tag as? Camera2RequestTag
            frames += FrameSample(
                phase = current.name,
                generation = current.generation,
                callbackElapsedNs = SystemClock.elapsedRealtimeNanos(),
                frameNumber = result.frameNumber,
                sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                sessionGeneration = tag?.sessionGeneration,
                requestRevision = tag?.requestRevision,
                requestAeMode = request.get(CaptureRequest.CONTROL_AE_MODE),
                requestAePriorityMode = if (Build.VERSION.SDK_INT >= 36) {
                    request.get(CaptureRequest.CONTROL_AE_PRIORITY_MODE)
                } else null,
                requestSensorIso = request.get(CaptureRequest.SENSOR_SENSITIVITY),
                requestPostRawBoost = request.get(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST),
                requestExposureNs = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME),
                requestFrameDurationNs = request.get(CaptureRequest.SENSOR_FRAME_DURATION),
                requestEv = request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION),
                requestAwbMode = request.get(CaptureRequest.CONTROL_AWB_MODE),
                requestColorMode = request.get(CaptureRequest.COLOR_CORRECTION_MODE),
                requestCct = if (Build.VERSION.SDK_INT >= 36) {
                    request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE)
                } else null,
                requestTint = if (Build.VERSION.SDK_INT >= 36) {
                    request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TINT)
                } else null,
                requestGains = request.get(CaptureRequest.COLOR_CORRECTION_GAINS)?.toArray(),
                requestCcm = request.get(CaptureRequest.COLOR_CORRECTION_TRANSFORM)?.toArray(),
                resultAeMode = result.get(CaptureResult.CONTROL_AE_MODE),
                resultAeState = result.get(CaptureResult.CONTROL_AE_STATE),
                resultAwbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
                resultGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.toArray(),
                resultCcm = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toArray(),
                resultIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                resultPostRawBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST),
                resultExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                resultFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
                resultEv = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
            )
        }

        fun framesFor(mark: PhaseMark): List<FrameSample> =
            frames.filter { it.generation == mark.generation && it.callbackElapsedNs >= mark.startedElapsedNs }

        private fun android.hardware.camera2.params.RggbChannelVector.toArray(): FloatArray =
            floatArrayOf(red, greenEven, greenOdd, blue)

        private fun android.hardware.camera2.params.ColorSpaceTransform.toArray(): FloatArray =
            FloatArray(9) { index -> getElement(index % 3, index / 3).toFloat() }
    }

    private companion object {
        const val EVIDENCE_DIRECTORY = "lq019"
        const val POLL_INTERVAL_MS = 40L
        const val SESSION_TIMEOUT_MS = 20_000L
        const val MODE_TIMEOUT_MS = 35_000L
        const val MODE_SETTLE_MS = 1_000L
        const val STABILITY_SETTLE_MS = 800L
        const val AUTO_RESPONSE_TIMEOUT_MS = 5_000L
        const val MANUAL_RESPONSE_TIMEOUT_MS = 8_000L
        const val COMBINED_RESPONSE_TIMEOUT_MS = 10_000L
        const val FRAME_WINDOW_TIMEOUT_MS = 5_000L
        const val EV_SETTLE_TIMEOUT_MS = 7_000L
        const val EV_IMAGE_SETTLE_MS = 700L
        const val CONTROL_RESPONSE_LIMIT_MS = 1_500.0
        const val MANUAL_RESPONSE_LIMIT_MS = 2_000.0
        const val MIN_EVIDENCE_FRAMES = 8
        const val MIN_SETTLED_FRAMES = 5
        const val MIN_EV_FRAMES = 10
        const val EVIDENCE_SAMPLE_LIMIT = 10
        const val FAST_SHUTTER_NS = 1_000_000L
        const val MID_SHUTTER_NS = 10_000_000L
        const val SLOW_SHUTTER_NS = 30_000_000L
        const val MAX_MANUAL_EXPOSURE_NS = 30_000_000L
        const val MAX_CAPTURE_RESULT_GAP_MS = 750.0
        const val MAX_AUTO_EXPOSURE_RANGE_RATIO = 1.50
        const val EV_MONOTONIC_TOLERANCE = 0.80
        const val MIN_EV_ENDPOINT_LUMA_DELTA = 0.005
        const val MAX_PREVIEW_CLIPPED_FRACTION = 0.95
        const val CLIPPED_LUMA = 0.985
        const val IMAGE_SAMPLE_STEP = 8
        const val EXPOSURE_ABSOLUTE_TOLERANCE_NS = 50_000L
        const val EXPOSURE_RELATIVE_TOLERANCE = 0.01
        val EV_SEQUENCE = listOf(-2, -1, 0, 1, 2)
        val REQUIRED_CHECKPOINTS = listOf("CP-115", "CP-116", "CP-117", "CP-118", "CP-119")
    }
}
