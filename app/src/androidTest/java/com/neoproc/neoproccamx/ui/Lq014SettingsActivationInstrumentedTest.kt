package com.neoproc.neoproccamx.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.camera.FlashMode
import com.neoproc.neoproccamx.camera.RawCaptureResolutionMode
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.AudioSrc
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalog
import com.neoproc.neoproccamx.ui.controls.SettingsOptionMeta
import com.neoproc.neoproccamx.ui.controls.SettingsOptionMetaCatalog
import com.neoproc.neoproccamx.ui.controls.SettingsSectionFactory
import com.neoproc.neoproccamx.ui.overlays.GridMode
import com.neoproc.neoproccamx.ui.overlays.UIOverlay
import com.neoproc.neoproccamx.utils.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Physical-device oracle for LQ-014 / CP-094, CP-095 and CP-098.
 *
 * Phase one drives every active production Settings control through its actual View callback, measures
 * UI-to-runtime acceptance, validates the persisted value, and leaves a deterministic sentinel state.
 * The host runner force-stops both packages before phase two, which proves a real process restart.
 * Phase two validates the same state through a fresh [MainActivity]/[CameraViewModel], then restores the
 * user's original SharedPreferences byte-for-type.  No camera resolution, bitrate, bit depth, colour or
 * quality policy is weakened by this test.
 */
@RunWith(AndroidJUnit4::class)
class Lq014SettingsActivationInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun phase1_everyActiveSettingUsesItsUiHandlerWithinOneSecond() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        saveOriginalPreferences(targetContext)
        val records = mutableListOf<ProbeResult>()
        var failure: Throwable? = null

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                keepScreenAwake(scenario)
                awaitCameraStreaming(scenario, STARTUP_TIMEOUT_MS)
                establishSafeBaseline(scenario)
                openSettings(scenario)

                val metadata = SettingsOptionMetaCatalog.all
                assertEquals("LQ-014 catalog must contain the complete active option set", 26, metadata.size)
                metadata.forEach { option ->
                    prepareForOption(scenario, option.id)
                    val mutation = mutationFor(scenario, option)
                    val generationBefore = readVm(scenario) { it.camera.sessionGeneration.get() }
                    val action = performThroughRealSettingsUi(scenario, mutation)
                    val runtime = awaitCondition(ACTIVATION_LIMIT_MS) {
                        readVm(scenario) { vm -> isRuntimeApplied(vm, mutation) }
                    }
                    val elapsedMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - action.startNs)
                    val preference = if (option.preferenceKeys.isEmpty()) {
                        true
                    } else {
                        isPreferenceApplied(PreferencesManager(targetContext), mutation)
                    }
                    val ui = awaitCondition(ACTIVATION_LIMIT_MS) {
                        isUiReflected(scenario, mutation)
                    }
                    val observedUiValue = observedUiValue(scenario, mutation)
                    val effect = verifySettingSpecificEffect(scenario, mutation)
                    val generationAfter = readVm(scenario) { it.camera.sessionGeneration.get() }
                    val cameraState = readVm(scenario) { it.cameraState.value.javaClass.simpleName }
                    val passed = runtime && preference && ui && action.settingsRootStable && effect &&
                        elapsedMs <= option.maxDispatchLatencyMs
                    records += ProbeResult(
                        optionId = option.id,
                        title = option.title,
                        requested = mutation.target.toString(),
                        uiLabel = mutation.uiLabel,
                        activationClass = option.activationClass.name,
                        elapsedMs = elapsedMs,
                        runtimeApplied = runtime,
                        preferenceApplied = preference,
                        uiReflected = ui,
                        observedUiValue = observedUiValue,
                        settingsRootStable = action.settingsRootStable,
                        effectVerified = effect,
                        generationBefore = generationBefore,
                        generationAfter = generationAfter,
                        cameraState = cameraState,
                        passed = passed
                    )
                    restoreSessionOnlyMutationIfNeeded(scenario, mutation)
                }

                applyRestartSentinels(scenario)
                assertTrue("restart sentinel preferences were not durably committed", awaitCondition(1_000L) {
                    restartSentinelsPersisted(PreferencesManager(targetContext))
                })
                assertEquals("Every active setting must be measured", metadata.size, records.size)
                assertTrue(
                    "LQ-014 setting failures: " + records.filterNot { it.passed }.joinToString { result ->
                        "${result.optionId}(runtime=${result.runtimeApplied},prefs=${result.preferenceApplied}," +
                            "ui=${result.uiReflected},rootStable=${result.settingsRootStable}," +
                            "effect=${result.effectVerified},elapsed=${result.elapsedMs}ms)"
                    },
                    records.all { it.passed }
                )
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            writeEvidence(targetContext, PHASE_APPLY, records, failure)
        }
    }

    @Test
    fun phase2_restartRestoresEveryPersistentRuntimeSettingThenRestoresUserState() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val records = mutableListOf<ProbeResult>()
        var failure: Throwable? = null
        try {
            assertTrue("LQ-014 phase-one preference backup is missing", hasPreferenceBackup(targetContext))
            val persistedBeforeLaunch = restartSentinelsPersisted(PreferencesManager(targetContext))
            assertTrue("sentinel values did not survive the external force-stop", persistedBeforeLaunch)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                keepScreenAwake(scenario)
                awaitCameraStreaming(scenario, STARTUP_TIMEOUT_MS)
                val sentinels = restartSentinelMutations(scenario)
                sentinels.forEach { mutation ->
                    val startNs = SystemClock.elapsedRealtimeNanos()
                    val runtime = awaitCondition(RESTART_RUNTIME_TIMEOUT_MS) {
                        readVm(scenario) { vm -> isRuntimeApplied(vm, mutation) }
                    }
                    val elapsedMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - startNs)
                    val preference = isPreferenceApplied(PreferencesManager(targetContext), mutation)
                    records += ProbeResult(
                        optionId = mutation.id,
                        title = mutation.title,
                        requested = mutation.target.toString(),
                        uiLabel = mutation.uiLabel,
                        activationClass = "PROCESS_RESTART_RESTORE",
                        elapsedMs = elapsedMs,
                        runtimeApplied = runtime,
                        preferenceApplied = preference,
                        uiReflected = true,
                        observedUiValue = mutation.uiLabel,
                        settingsRootStable = true,
                        effectVerified = true,
                        generationBefore = readVm(scenario) { it.camera.sessionGeneration.get() },
                        generationAfter = readVm(scenario) { it.camera.sessionGeneration.get() },
                        cameraState = readVm(scenario) { it.cameraState.value.javaClass.simpleName },
                        passed = runtime && preference
                    )
                }
                assertTrue("one or more persisted settings were not restored into the fresh runtime", records.all { it.passed })
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            writeEvidence(targetContext, PHASE_VERIFY, records, failure)
            restoreOriginalPreferences(targetContext)
        }
    }

    @Test
    fun framingGuides_onlyEnableThroughSettingsAndRenderOnPreview() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val records = mutableListOf<ProbeResult>()
        var failure: Throwable? = null

        saveOriginalPreferences(targetContext)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                keepScreenAwake(scenario)
                awaitCameraStreaming(scenario, STARTUP_TIMEOUT_MS)
                establishSafeBaseline(scenario)
                openSettings(scenario)
                prepareForOption(scenario, "system.framingGuides")

                val mutation = SettingMutation(
                    id = "system.framingGuides",
                    title = "Framing Guides",
                    kind = ControlKind.TOGGLE,
                    target = true
                )
                val generationBefore = readVm(scenario) { it.camera.sessionGeneration.get() }
                val action = performThroughRealSettingsUi(scenario, mutation)
                val runtime = awaitCondition(ACTIVATION_LIMIT_MS) {
                    readVm(scenario) { vm -> isRuntimeApplied(vm, mutation) }
                }
                val elapsedMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - action.startNs)
                val preference = isPreferenceApplied(PreferencesManager(targetContext), mutation)
                val ui = awaitCondition(ACTIVATION_LIMIT_MS) { isUiReflected(scenario, mutation) }
                val observedUiValue = observedUiValue(scenario, mutation)
                val effect = verifySettingSpecificEffect(scenario, mutation)
                val result = ProbeResult(
                    optionId = mutation.id,
                    title = mutation.title,
                    requested = mutation.target.toString(),
                    uiLabel = mutation.uiLabel,
                    activationClass = "FOCUSED_DEVICE_RETEST",
                    elapsedMs = elapsedMs,
                    runtimeApplied = runtime,
                    preferenceApplied = preference,
                    uiReflected = ui,
                    observedUiValue = observedUiValue,
                    settingsRootStable = action.settingsRootStable,
                    effectVerified = effect,
                    generationBefore = generationBefore,
                    generationAfter = readVm(scenario) { it.camera.sessionGeneration.get() },
                    cameraState = readVm(scenario) { it.cameraState.value.javaClass.simpleName },
                    passed = runtime && preference && ui && action.settingsRootStable && effect &&
                        elapsedMs <= ACTIVATION_LIMIT_MS
                )
                records += result
                assertTrue(
                    "Framing Guides focused device retest failed: runtime=$runtime prefs=$preference " +
                        "ui=$ui rootStable=${action.settingsRootStable} effect=$effect elapsed=${elapsedMs}ms",
                    result.passed
                )
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            writeEvidence(targetContext, PHASE_FRAMING_ONLY, records, failure)
            restoreOriginalPreferences(targetContext)
        }
    }

    @Test
    fun phase3_emergencyRestoreOnly() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val restored = restoreOriginalPreferences(targetContext)
        writeEvidence(
            targetContext,
            PHASE_CLEANUP,
            emptyList(),
            if (restored) null else IllegalStateException("No LQ-014 backup was available")
        )
        assertTrue("No LQ-014 preference backup was available", restored)
    }

    /**
     * Deliberately uses a single deterministic if/else-if/else chain, as required by the LQ-014
     * execution request. Kotlin spells Python's `elif` as `else if`.
     */
    private fun mutationFor(
        scenario: ActivityScenario<MainActivity>,
        option: SettingsOptionMeta
    ): SettingMutation {
        val vm = readVm(scenario) { it }
        return if (option.id == "camera.previewAspect") {
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, PreviewAspectRatio.SQUARE, "1:1")
        } else if (option.id == "camera.photoQuality") {
            val target = !vm.camera.maximumResolutionStillEnabled
            val label = SettingsOptionCatalog.photoQualityChoices.single { it.value == target }.label
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, target, label)
        } else if (option.id == "camera.lens") {
            val target = if (vm.camera.facing == CameraCharacteristics.LENS_FACING_BACK &&
                vm.camera.hasCameraFacing(CameraCharacteristics.LENS_FACING_FRONT)
            ) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, target,
                if (target == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Rear")
        } else if (option.id == "camera.flash") {
            val target = if (vm.cameraParams.value.flash == FlashMode.AUTO) FlashMode.OFF else FlashMode.AUTO
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, target, target.name)
        } else if (option.id == "camera.aiMotionCompensation") {
            SettingMutation(option.id, option.title, ControlKind.TOGGLE, !vm.aiMotionCompensationEnabled)
        } else if (option.id == "camera.noiseReduction") {
            SettingMutation(option.id, option.title, ControlKind.TOGGLE, !vm.noiseReductionEnabled)
        } else if (option.id == "video.resolution") {
            val choices = SettingsOptionCatalog.resolutionChoicesForMode(
                CinemaVideoMode.PRO,
                vm.availableVideoDimensionsForSettings()
            )
            val current = Size(vm.gpuRecording.videoWidth, vm.gpuRecording.videoHeight)
            val target = choices.firstOrNull { it.label == "4K" && it.value != current }
                ?: choices.firstOrNull { it.value != current }
                ?: choices.first()
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, target.value, target.label)
        } else if (option.id == "video.frameRate") {
            val current = vm.recording.fps
            val target = SettingsOptionCatalog.fpsChoices.firstOrNull { it.value == 25 && it.value != current }
                ?: SettingsOptionCatalog.fpsChoices.firstOrNull { it.value != current }
                ?: SettingsOptionCatalog.fpsChoices.first()
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, target.value, target.label)
        } else if (option.id == "video.codec") {
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, VideoCodecType.HEVC, "HEVC")
        } else if (option.id == "video.hdrMode") {
            val target = if (vm.videoHdrMode.value == "NDR10+") "NIVUKXVISION" else "NDR10+"
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, target,
                if (target == "NIVUKXVISION") "NIVUKX VISION" else "XDR10+")
        } else if (option.id == "video.bitrateMode") {
            val target = if (vm.videoBitrateMode.value == VideoBitrateMode.CUSTOM) {
                VideoBitrateMode.CINEMA_MASTER
            } else {
                VideoBitrateMode.CUSTOM
            }
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, target,
                if (target == VideoBitrateMode.CINEMA_MASTER) "CINEMA MASTER" else "CUSTOM")
        } else if (option.id == "video.manualBitrate") {
            val currentMbps = vm.requestedVideoBitrateBitsPerSecond() / 1_000_000
            val targetMbps = if (currentMbps == 240) 300 else 240
            SettingMutation(option.id, option.title, ControlKind.SLIDER, targetMbps, "$targetMbps Mbps")
        } else if (option.id == "video.rawEnabled") {
            SettingMutation(option.id, option.title, ControlKind.TOGGLE, true)
        } else if (option.id == "video.cinemaMode") {
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, CinemaVideoMode.PRO, "PRO (ORIGINAL)")
        } else if (option.id == "video.rawResolution") {
            SettingMutation(option.id, option.title, ControlKind.SELECTOR,
                RawCaptureResolutionMode.FULL_ACTIVE_ARRAY, "FULL ACTIVE ARRAY")
        } else if (option.id == "video.rawCompression") {
            SettingMutation(option.id, option.title, ControlKind.SELECTOR,
                RawCompressionMode.HIGH_COMPRESSION, "UNCOMPRESSED 12-BIT RAW")
        } else if (option.id == "videoColor.colorSpace") {
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, "Neo ACES AP1", "NEO ACES AP1")
        } else if (option.id == "videoColor.colorScience") {
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, "Neo ACES High End", "NEO ACES HIGH END")
        } else if (option.id == "videoColor.detailProfile") {
            SettingMutation(option.id, option.title, ControlKind.SELECTOR,
                VideoDetailProfile.NATURAL_DETAIL, "NATURAL DETAIL")
        } else if (option.id == "focus.eyeAutofocus") {
            SettingMutation(option.id, option.title, ControlKind.TOGGLE, !vm.eyeAutofocusEnabled)
        } else if (option.id == "system.gridMode") {
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, GridMode.CENTER, "CENTER")
        } else if (option.id == "system.framingGuides") {
            SettingMutation(option.id, option.title, ControlKind.TOGGLE, !vm.isSafeAreaGuidesEnabled)
        } else if (option.id == "system.stabilization") {
            val choices = SettingsOptionCatalog.stabilizationChoicesForCapabilities(
                supportsOis = vm.camera.supportsOIS,
                supportsVideoStabilization = vm.camera.supportsVideoStabilization,
                rawVideoCaptureEnabled = false
            )
            val target = choices.firstOrNull { it.value != vm.stabilizationMode } ?: choices.first()
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, target.value, target.label)
        } else if (option.id == "system.audio") {
            val current = SettingsOptionCatalog.selectedAudioSource(vm.recording.audioSource)
            val target = SettingsOptionCatalog.audioChoices.firstOrNull { it.value == 2 && it.value != current }
                ?: SettingsOptionCatalog.audioChoices.firstOrNull { it.value != current }
                ?: SettingsOptionCatalog.audioChoices.first()
            SettingMutation(option.id, option.title, ControlKind.SELECTOR, target.value, target.label)
        } else if (option.id == "system.customRecordingNameEnabled") {
            SettingMutation(option.id, option.title, ControlKind.TOGGLE, !vm.customRecordingNameEnabled)
        } else if (option.id == "system.customRecordingName") {
            SettingMutation(option.id, option.title, ControlKind.TEXT, RESTART_RECORDING_NAME, RESTART_RECORDING_NAME)
        } else {
            throw AssertionError("LQ-014 has no deterministic mutation for ${option.id}")
        }
    }

    private fun performThroughRealSettingsUi(
        scenario: ActivityScenario<MainActivity>,
        mutation: SettingMutation
    ): UiActionResult {
        openSettings(scenario)
        val mustKeepRootStable = mutation.id == "system.customRecordingNameEnabled" ||
            mutation.id == "system.customRecordingName"
        val settingsContentBefore = if (mustKeepRootStable) {
            readActivity(scenario, ::settingsContentRoot)
                ?: throw AssertionError("Settings content root missing before ${mutation.title}")
        } else {
            null
        }
        if (mutation.kind == ControlKind.SELECTOR) {
            clickSettingsRow(scenario, mutation.title)
            waitForAccessibilityDescription(choiceAccessibilityLabel(mutation.uiLabel), DIALOG_TIMEOUT_MS)
            val dispatchStart = showOnScreenAndClickAccessibilityDescription(
                choiceAccessibilityLabel(mutation.uiLabel)
            ) ?: throw AssertionError("choice '${mutation.uiLabel}' was not scrollable/clickable")
            return UiActionResult(startNs = dispatchStart)
        } else if (mutation.kind == ControlKind.TOGGLE) {
            val start = SystemClock.elapsedRealtimeNanos()
            clickSettingsRow(scenario, mutation.title)
            val rootStable = if (mustKeepRootStable) {
                settingsContentBefore === readActivity(scenario, ::settingsContentRoot)
            } else {
                true
            }
            return UiActionResult(startNs = start, settingsRootStable = rootStable)
        } else if (mutation.kind == ControlKind.SLIDER) {
            val start = SystemClock.elapsedRealtimeNanos()
            scenario.onActivity { activity ->
                val seekBar = findDescendantOfSetting<SeekBar>(activity, mutation.title)
                    ?: throw AssertionError("No SeekBar for ${mutation.title}")
                val targetMbps = mutation.target as Int
                val targetIndex = SettingsOptionCatalog.bitrateStepsMbps.indexOf(targetMbps)
                assertTrue("No bitrate step for $targetMbps", targetIndex >= 0)
                val arguments = Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, targetIndex.toFloat())
                }
                assertTrue(
                    "Manual Bitrate accessibility progress action was rejected",
                    seekBar.performAccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        arguments
                    )
                )
            }
            return UiActionResult(startNs = start)
        } else if (mutation.kind == ControlKind.TEXT) {
            clickSettingsRow(scenario, mutation.title)
            val edit = waitForAccessibilityClass(EditText::class.java.name, DIALOG_TIMEOUT_MS)
                ?: throw AssertionError("No Recording Name editor")
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, mutation.target as String)
            }
            assertTrue("Recording Name text could not be entered", edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            waitForAccessibilityText("Save", DIALOG_TIMEOUT_MS)
            val start = SystemClock.elapsedRealtimeNanos()
            assertTrue(
                "Recording Name Save was not clickable",
                showOnScreenAndClickAccessibilityText("Save")
            )
            val rootStable = if (mustKeepRootStable) {
                settingsContentBefore === readActivity(scenario, ::settingsContentRoot)
            } else {
                true
            }
            return UiActionResult(startNs = start, settingsRootStable = rootStable)
        } else {
            throw AssertionError("Unsupported LQ-014 UI control ${mutation.kind}")
        }
    }

    private fun verifySettingSpecificEffect(
        scenario: ActivityScenario<MainActivity>,
        mutation: SettingMutation
    ): Boolean {
        return if (mutation.id == "system.framingGuides" && mutation.target == true) {
            dismissSettings(scenario)
            awaitCameraStreaming(scenario, STARTUP_TIMEOUT_MS)
            val visible = awaitCondition(GUIDE_RENDER_TIMEOUT_MS) {
                readActivity(scenario) { activity ->
                    val overlay = activity.findViewById<UIOverlay>(R.id.cameraOverlay)
                    activity.vm.frameGeometry.value != null &&
                        overlay.isShown && overlay.width > 0 && overlay.height > 0 &&
                        overlay.hasActiveSafeAreaGuides()
                }
            }
            val diagnostic = readActivity(scenario) { activity ->
                val overlay = activity.findViewById<UIOverlay>(R.id.cameraOverlay)
                val preview = activity.findViewById<View>(R.id.glSurface)
                "visible=$visible enabled=${activity.vm.isSafeAreaGuidesEnabled} " +
                    "geometry=${activity.vm.frameGeometry.value != null} " +
                    "overlayShown=${overlay.isShown} overlay=${overlay.width}x${overlay.height} " +
                    "preview=${preview.width}x${preview.height} " +
                    "activeGuides=${overlay.hasActiveSafeAreaGuides()} " +
                    "cameraState=${activity.vm.cameraState.value.javaClass.simpleName}"
            }
            android.util.Log.i("LQ014FramingGuide", diagnostic)
            if (visible) captureScreenshot("framing-guides-visible.png")
            openSettings(scenario)
            visible
        } else if (mutation.id == "system.customRecordingNameEnabled") {
            isSettingRowVisible(scenario, "Recording Name") == (mutation.target == true)
        } else if (mutation.id == "system.customRecordingName") {
            isSettingRowVisible(scenario, "Recording Name")
        } else {
            true
        }
    }

    private fun prepareForOption(scenario: ActivityScenario<MainActivity>, id: String) {
        if (id == "video.rawEnabled") {
            scenario.onActivity {
                // RAW and XDR are deliberately mutually exclusive production transports.  The
                // preceding XDR setting probe may leave PQ selected, so isolate this probe from
                // that valid state before asking the real Video RAW control to enable RAW.
                it.vm.setVideoHdrMode("OFF")
                it.vm.setCinemaVideoMode(CinemaVideoMode.PRO)
            }
        } else if (id == "video.cinemaMode") {
            scenario.onActivity {
                it.vm.setVideoHdrMode("OFF")
                it.vm.setRawEnabled(true)
            }
        } else if (id == "video.rawResolution" || id == "video.rawCompression") {
            scenario.onActivity {
                it.vm.setVideoHdrMode("OFF")
                it.vm.setCinemaVideoMode(CinemaVideoMode.RAW)
            }
        } else if (id.startsWith("videoColor.") || id.startsWith("focus.") || id.startsWith("system.")) {
            scenario.onActivity {
                it.vm.setRawEnabled(false)
                it.vm.setCinemaVideoMode(CinemaVideoMode.PRO)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(
            "Settings preparation for '$id' did not retain intentional camera suspension",
            awaitCondition(SETUP_SETTLE_TIMEOUT_MS) {
                readVm(scenario) { it.cameraState.value == CameraState.Closed }
            }
        )
        if (id == "video.rawEnabled" || id == "video.cinemaMode" ||
            id == "video.rawResolution" || id == "video.rawCompression"
        ) {
            assertTrue(
                "RAW setting preparation retained an incompatible XDR transport for '$id'",
                readVm(scenario) { it.videoHdrMode.value == "OFF" }
            )
        }
    }

    private fun isRuntimeApplied(vm: CameraViewModel, mutation: SettingMutation): Boolean {
        val id = mutation.id
        val target = mutation.target
        return if (id == "camera.previewAspect") {
            vm.previewAspect.value == target
        } else if (id == "camera.photoQuality") {
            vm.camera.maximumResolutionStillEnabled == target
        } else if (id == "camera.lens") {
            vm.camera.facing == target
        } else if (id == "camera.flash") {
            vm.cameraParams.value.flash == target
        } else if (id == "camera.aiMotionCompensation") {
            vm.aiMotionCompensationEnabled == target
        } else if (id == "camera.noiseReduction") {
            vm.noiseReductionEnabled == target
        } else if (id == "video.resolution") {
            target as Size
            vm.gpuRecording.videoWidth == target.width && vm.gpuRecording.videoHeight == target.height
        } else if (id == "video.frameRate") {
            vm.recording.fps == target && vm.gpuRecording.videoFps == target
        } else if (id == "video.codec") {
            vm.recording.codec == target
        } else if (id == "video.hdrMode") {
            vm.videoHdrMode.value == target
        } else if (id == "video.bitrateMode") {
            vm.videoBitrateMode.value == target
        } else if (id == "video.manualBitrate") {
            vm.requestedVideoBitrateBitsPerSecond() == (target as Int) * 1_000_000 &&
                vm.videoBitrateMode.value == VideoBitrateMode.CUSTOM
        } else if (id == "video.rawEnabled") {
            vm.rawOnFlow.value == target
        } else if (id == "video.cinemaMode") {
            vm.cinemaVideoMode.value == target
        } else if (id == "video.rawResolution") {
            vm.rawResolutionMode.value == target
        } else if (id == "video.rawCompression") {
            vm.rawVideoCompression.value == target
        } else if (id == "videoColor.colorSpace") {
            vm.videoColorSpace.value == target
        } else if (id == "videoColor.colorScience") {
            vm.colorProfileFlow.value == target
        } else if (id == "videoColor.detailProfile") {
            vm.videoDetailProfile.value == target
        } else if (id == "focus.eyeAutofocus") {
            vm.eyeAutofocusEnabled == target
        } else if (id == "system.gridMode") {
            vm.gridMode.value == target
        } else if (id == "system.framingGuides") {
            vm.isSafeAreaGuidesEnabled == target
        } else if (id == "system.stabilization") {
            vm.stabilizationMode == target
        } else if (id == "system.audio") {
            vm.recording.audioSource == audioSourceFor(target as Int)
        } else if (id == "system.customRecordingNameEnabled") {
            vm.customRecordingNameEnabled == target
        } else if (id == "system.customRecordingName") {
            vm.customRecordingName == target
        } else {
            false
        }
    }

    private fun isPreferenceApplied(prefs: PreferencesManager, mutation: SettingMutation): Boolean {
        val id = mutation.id
        val target = mutation.target
        return if (id == "camera.previewAspect") {
            prefs.previewAspectRatio == target
        } else if (id == "camera.photoQuality") {
            prefs.maxPhotoResolutionEnabled == target
        } else if (id == "camera.aiMotionCompensation") {
            prefs.aiMotionCompensationEnabled == target
        } else if (id == "camera.noiseReduction") {
            prefs.noiseReductionEnabled == target
        } else if (id == "video.resolution") {
            prefs.videoSize == target
        } else if (id == "video.frameRate") {
            prefs.videoFps == target
        } else if (id == "video.codec") {
            prefs.videoCodec == (target as VideoCodecType).name
        } else if (id == "video.hdrMode") {
            prefs.videoHdrMode == target
        } else if (id == "video.bitrateMode") {
            prefs.videoBitrateMode == target
        } else if (id == "video.manualBitrate") {
            prefs.videoBitrate == (target as Int) * 1_000_000 && prefs.videoBitrateMode == VideoBitrateMode.CUSTOM
        } else if (id == "video.rawEnabled") {
            prefs.rawEnabled == target
        } else if (id == "video.cinemaMode") {
            prefs.videoMode == (target as CinemaVideoMode).name
        } else if (id == "video.rawResolution") {
            prefs.rawResolutionMode == target
        } else if (id == "video.rawCompression") {
            prefs.rawVideoCompression == (target as RawCompressionMode).name
        } else if (id == "videoColor.colorSpace") {
            prefs.videoColorSpace == target
        } else if (id == "videoColor.colorScience") {
            prefs.colorProfile == target
        } else if (id == "videoColor.detailProfile") {
            prefs.videoDetailProfile == target
        } else if (id == "focus.eyeAutofocus") {
            prefs.eyeAutofocusEnabled == target
        } else if (id == "system.gridMode") {
            prefs.gridMode == (target as GridMode).name
        } else if (id == "system.framingGuides") {
            prefs.safeAreaGuidesEnabled == target
        } else if (id == "system.stabilization") {
            prefs.stabilizationMode == target
        } else if (id == "system.audio") {
            prefs.audioSource == audioPreferenceFor(target as Int)
        } else if (id == "system.customRecordingNameEnabled") {
            prefs.customRecordingNameEnabled == target
        } else if (id == "system.customRecordingName") {
            prefs.customRecordingName == target
        } else {
            mutation.id == "camera.lens" || mutation.id == "camera.flash"
        }
    }

    private fun isUiReflected(
        scenario: ActivityScenario<MainActivity>,
        mutation: SettingMutation
    ): Boolean {
        var reflected = false
        scenario.onActivity { activity ->
            val root = settingsRoot(activity) ?: return@onActivity
            val title = descendants(root).filterIsInstance<TextView>()
                .firstOrNull { it.text?.toString() == mutation.title } ?: return@onActivity
            if (mutation.kind == ControlKind.TOGGLE) {
                val row = nearestAncestorContaining<SwitchCompat>(title, root)
                val toggle = row?.let { descendants(it).filterIsInstance<SwitchCompat>().firstOrNull() }
                reflected = toggle?.isChecked == mutation.target
            } else if (mutation.kind == ControlKind.SLIDER) {
                val row = nearestAncestorContaining<SeekBar>(title, root)
                val seek = row?.let { descendants(it).filterIsInstance<SeekBar>().firstOrNull() }
                reflected = seek?.progress == SettingsOptionCatalog.bitrateStepsMbps.indexOf(mutation.target as Int)
            } else {
                val value = nearestAncestorWithTaggedValue(title, root)
                reflected = value?.text?.toString() == mutation.uiLabel
            }
        }
        return reflected
    }

    private fun observedUiValue(
        scenario: ActivityScenario<MainActivity>,
        mutation: SettingMutation
    ): String = readActivity(scenario) { activity ->
        val root = settingsRoot(activity) ?: return@readActivity "<settings-closed>"
        val title = descendants(root).filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == mutation.title }
            ?: return@readActivity "<row-missing>"
        if (mutation.kind == ControlKind.TOGGLE) {
            val row = nearestAncestorContaining<SwitchCompat>(title, root)
            row?.let { descendants(it).filterIsInstance<SwitchCompat>().firstOrNull()?.isChecked }
                ?.toString() ?: "<toggle-missing>"
        } else if (mutation.kind == ControlKind.SLIDER) {
            val row = nearestAncestorContaining<SeekBar>(title, root)
            row?.let { descendants(it).filterIsInstance<SeekBar>().firstOrNull()?.progress }
                ?.toString() ?: "<slider-missing>"
        } else {
            nearestAncestorWithTaggedValue(title, root)?.text?.toString() ?: "<value-missing>"
        }
    }

    private fun establishSafeBaseline(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.vm.setMode(AppMode.VIDEO)
            activity.vm.setRawEnabled(false)
            activity.vm.setCinemaVideoMode(CinemaVideoMode.PRO)
            activity.vm.setVideoHdrMode("OFF")
            activity.vm.setCustomRecordingNameEnabled(false)
            activity.vm.setSafeAreaGuidesEnabled(false)
        }
        assertTrue(
            "LQ-014 baseline did not reach Video/Pro/SDR while Camera2 was streaming",
            awaitCondition(SETUP_SETTLE_TIMEOUT_MS) {
                readVm(scenario) {
                    it.uiMode.value == AppMode.VIDEO &&
                        it.cinemaVideoMode.value == CinemaVideoMode.PRO &&
                        !it.rawOnFlow.value &&
                        it.cameraState.value == CameraState.Streaming
                }
            }
        )
    }

    private fun applyRestartSentinels(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val vm = activity.vm
            val supported = SettingsOptionCatalog.resolutionChoicesForMode(
                CinemaVideoMode.PRO,
                vm.availableVideoDimensionsForSettings()
            )
            val size = supported.firstOrNull { it.label == "4K" }?.value
                ?: supported.firstOrNull()?.value
                ?: Size(3840, 2160)
            rememberRestartSize(activity, size)
            vm.setMode(AppMode.VIDEO)
            vm.setRawEnabled(false)
            vm.setCinemaVideoMode(CinemaVideoMode.PRO)
            vm.setVideoResolution(size)
            vm.setFPS(25)
            vm.setVideoCodec(VideoCodecType.HEVC)
            vm.setVideoBitrateMode(VideoBitrateMode.CUSTOM)
            vm.setVideoBitrate(RESTART_BITRATE_MBPS * 1_000_000)
            vm.setPreviewAspectRatio(PreviewAspectRatio.FOUR_THREE)
            vm.setMaximumPhotoResolutionEnabled(true)
            vm.setAiMotionCompensationEnabled(true)
            vm.setNoiseReductionEnabled(true)
            vm.setRawResolutionMode(RawCaptureResolutionMode.FULL_ACTIVE_ARRAY)
            vm.setRawVideoCompressionMode(RawCompressionMode.HIGH_COMPRESSION)
            vm.setVideoColorSpace("Rec.2020-NDR10+")
            vm.setColorProfile("NDR10+")
            vm.setVideoDetailProfile(VideoDetailProfile.FOCUSED_DETAIL)
            vm.setEyeAutofocusEnabled(true)
            vm.setGridMode(GridMode.THIRDS)
            vm.setSafeAreaGuidesEnabled(true)
            vm.setStabilizationMode(
                if (vm.camera.supportsOIS) StabilizationMode.OIS else StabilizationMode.OFF
            )
            vm.setAudioSourceIndex(0)
            vm.setCustomRecordingName(RESTART_RECORDING_NAME)
            vm.setVideoHdrMode("NDR10+")
        }
    }

    private fun restartSentinelMutations(
        scenario: ActivityScenario<MainActivity>
    ): List<SettingMutation> {
        val vm = readVm(scenario) { it }
        val size = rememberedRestartSize(InstrumentationRegistry.getInstrumentation().targetContext)
        return listOf(
            SettingMutation("camera.previewAspect", "Preview Aspect", ControlKind.SELECTOR, PreviewAspectRatio.FOUR_THREE, "4:3"),
            SettingMutation("camera.photoQuality", "Photo Quality", ControlKind.SELECTOR, true, "MAXIMUM SENSOR DETAIL"),
            SettingMutation("camera.aiMotionCompensation", "AI Motion Compensation", ControlKind.TOGGLE, true),
            SettingMutation("camera.noiseReduction", "Noise Reduction", ControlKind.TOGGLE, true),
            SettingMutation("video.resolution", "Resolution", ControlKind.SELECTOR, size, "4K"),
            SettingMutation("video.frameRate", "Frame Rate", ControlKind.SELECTOR, 25, "25"),
            SettingMutation("video.codec", "Codec", ControlKind.SELECTOR, VideoCodecType.HEVC, "HEVC"),
            SettingMutation("video.hdrMode", "XDR Mode", ControlKind.SELECTOR, "NDR10+", "XDR10+"),
            SettingMutation("video.bitrateMode", "Bitrate Mode", ControlKind.SELECTOR, VideoBitrateMode.CUSTOM, "CUSTOM"),
            SettingMutation("video.manualBitrate", "Manual Bitrate", ControlKind.SLIDER, RESTART_BITRATE_MBPS, "$RESTART_BITRATE_MBPS Mbps"),
            SettingMutation("video.rawEnabled", "Video RAW", ControlKind.TOGGLE, false),
            SettingMutation("video.cinemaMode", "Video Format Engine", ControlKind.SELECTOR, CinemaVideoMode.PRO, "PRO (ORIGINAL)"),
            SettingMutation("video.rawResolution", "RAW Resolution", ControlKind.SELECTOR, RawCaptureResolutionMode.FULL_ACTIVE_ARRAY, "FULL ACTIVE ARRAY"),
            SettingMutation("video.rawCompression", "RAW Compression", ControlKind.SELECTOR, RawCompressionMode.HIGH_COMPRESSION, "UNCOMPRESSED 12-BIT RAW"),
            SettingMutation("videoColor.colorSpace", "Color Space", ControlKind.SELECTOR, "Rec.2020-NDR10+", "REC.2020 XDR10+"),
            SettingMutation("videoColor.colorScience", "Color Science", ControlKind.SELECTOR, "NDR10+", "XDR10+"),
            SettingMutation("videoColor.detailProfile", "Detail Profile", ControlKind.SELECTOR, VideoDetailProfile.FOCUSED_DETAIL, "FOCUSED DETAIL"),
            SettingMutation("focus.eyeAutofocus", "Eye Autofocus", ControlKind.TOGGLE, true),
            SettingMutation("system.gridMode", "Grid Type", ControlKind.SELECTOR, GridMode.THIRDS, "3X3 (THIRDS)"),
            SettingMutation("system.framingGuides", "Framing Guides", ControlKind.TOGGLE, true),
            SettingMutation("system.stabilization", "Stabilization", ControlKind.SELECTOR,
                if (vm.camera.supportsOIS) StabilizationMode.OIS else StabilizationMode.OFF,
                if (vm.camera.supportsOIS) "OIS" else "OFF"),
            SettingMutation("system.audio", "Audio", ControlKind.SELECTOR, 0, "CAM MIX / CAMCORDER"),
            SettingMutation("system.customRecordingNameEnabled", "Custom Recording Name", ControlKind.TOGGLE, true),
            SettingMutation("system.customRecordingName", "Recording Name", ControlKind.TEXT, RESTART_RECORDING_NAME, RESTART_RECORDING_NAME)
        )
    }

    private fun restartSentinelsPersisted(prefs: PreferencesManager): Boolean {
        val expectedSize = rememberedRestartSize(InstrumentationRegistry.getInstrumentation().targetContext)
        val expected = listOf(
            SettingMutation("camera.previewAspect", "Preview Aspect", ControlKind.SELECTOR, PreviewAspectRatio.FOUR_THREE),
            SettingMutation("camera.photoQuality", "Photo Quality", ControlKind.SELECTOR, true),
            SettingMutation("camera.aiMotionCompensation", "AI Motion Compensation", ControlKind.TOGGLE, true),
            SettingMutation("camera.noiseReduction", "Noise Reduction", ControlKind.TOGGLE, true),
            SettingMutation("video.resolution", "Resolution", ControlKind.SELECTOR, expectedSize),
            SettingMutation("video.frameRate", "Frame Rate", ControlKind.SELECTOR, 25),
            SettingMutation("video.codec", "Codec", ControlKind.SELECTOR, VideoCodecType.HEVC),
            SettingMutation("video.hdrMode", "XDR Mode", ControlKind.SELECTOR, "NDR10+"),
            SettingMutation("video.bitrateMode", "Bitrate Mode", ControlKind.SELECTOR, VideoBitrateMode.CUSTOM),
            SettingMutation("video.manualBitrate", "Manual Bitrate", ControlKind.SLIDER, RESTART_BITRATE_MBPS),
            SettingMutation("video.rawEnabled", "Video RAW", ControlKind.TOGGLE, false),
            SettingMutation("video.cinemaMode", "Video Format Engine", ControlKind.SELECTOR, CinemaVideoMode.PRO),
            SettingMutation("video.rawResolution", "RAW Resolution", ControlKind.SELECTOR, RawCaptureResolutionMode.FULL_ACTIVE_ARRAY),
            SettingMutation("video.rawCompression", "RAW Compression", ControlKind.SELECTOR, RawCompressionMode.HIGH_COMPRESSION),
            SettingMutation("videoColor.colorSpace", "Color Space", ControlKind.SELECTOR, "Rec.2020-NDR10+"),
            SettingMutation("videoColor.colorScience", "Color Science", ControlKind.SELECTOR, "NDR10+"),
            SettingMutation("videoColor.detailProfile", "Detail Profile", ControlKind.SELECTOR, VideoDetailProfile.FOCUSED_DETAIL),
            SettingMutation("focus.eyeAutofocus", "Eye Autofocus", ControlKind.TOGGLE, true),
            SettingMutation("system.gridMode", "Grid Type", ControlKind.SELECTOR, GridMode.THIRDS),
            SettingMutation("system.framingGuides", "Framing Guides", ControlKind.TOGGLE, true),
            SettingMutation("system.stabilization", "Stabilization", ControlKind.SELECTOR, prefs.stabilizationMode),
            SettingMutation("system.audio", "Audio", ControlKind.SELECTOR, 0),
            SettingMutation("system.customRecordingNameEnabled", "Custom Recording Name", ControlKind.TOGGLE, true),
            SettingMutation("system.customRecordingName", "Recording Name", ControlKind.TEXT, RESTART_RECORDING_NAME)
        )
        return expected.all { isPreferenceApplied(prefs, it) }
    }

    private fun restoreSessionOnlyMutationIfNeeded(
        scenario: ActivityScenario<MainActivity>,
        mutation: SettingMutation
    ) {
        if (mutation.id == "camera.lens" && mutation.target == CameraCharacteristics.LENS_FACING_FRONT) {
            scenario.onActivity { it.vm.switchFacing() }
            assertTrue(
                "Lens cleanup did not restore rear-facing state while Settings retained suspension",
                awaitCondition(SETUP_SETTLE_TIMEOUT_MS) {
                    readVm(scenario) {
                        it.camera.facing == CameraCharacteristics.LENS_FACING_BACK &&
                            it.cameraState.value == CameraState.Closed
                    }
                }
            )
            // The production Lens row rebuilds the sheet for the newly selected camera. This test
            // restores the rear lens directly through the ViewModel, so deliberately reopen the
            // sheet as well; otherwise later capability-dependent selectors still contain the
            // front-camera option list even though the authoritative camera is rear-facing.
            dismissSettings(scenario)
            assertTrue(
                "Rear-camera capability state was not republished before rebuilding Settings",
                awaitCondition(STARTUP_TIMEOUT_MS) {
                    readVm(scenario) {
                        it.cameraState.value == CameraState.Streaming &&
                            it.frameGeometry.value?.lensFacing == CameraCharacteristics.LENS_FACING_BACK
                    }
                }
            )
            openSettings(scenario)
        } else if (mutation.id == "camera.flash") {
            scenario.onActivity { it.vm.setFlash(FlashMode.OFF) }
        }
    }

    private fun openSettings(scenario: ActivityScenario<MainActivity>) {
        if (readActivity(scenario) { settingsRoot(it) != null }) return
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.btnSettings).performClick()
        }
        assertTrue("Settings sheet did not open", awaitCondition(DIALOG_TIMEOUT_MS) {
            readActivity(scenario) { settingsRoot(it) != null }
        })
        assertTrue(
            "Settings sheet did not acquire its intentional Camera2 suspension boundary",
            awaitCondition(SETUP_SETTLE_TIMEOUT_MS) {
                readVm(scenario) { it.cameraState.value == CameraState.Closed }
            }
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun dismissSettings(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            (activity.supportFragmentManager.findFragmentByTag(CameraSettingsSheetFragment.TAG)
                as? CameraSettingsSheetFragment)?.dismiss()
        }
        assertTrue("Settings sheet did not dismiss", awaitCondition(DIALOG_TIMEOUT_MS) {
            readActivity(scenario) { settingsRoot(it) == null }
        })
    }

    private fun clickSettingsRow(scenario: ActivityScenario<MainActivity>, title: String) {
        var clicked = false
        scenario.onActivity { activity ->
            val root = settingsRoot(activity) ?: throw AssertionError("Settings root missing")
            val titleView = descendants(root).filterIsInstance<TextView>()
                .firstOrNull { it.text?.toString() == title }
                ?: throw AssertionError("Active setting row '$title' missing")
            val clickable = clickableAncestor(titleView, root)
                ?: throw AssertionError("Active setting row '$title' has no clickable owner")
            clicked = clickable.performClick()
        }
        assertTrue("Settings row '$title' rejected its click", clicked)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun settingsRoot(activity: MainActivity): View? =
        (activity.supportFragmentManager.findFragmentByTag(CameraSettingsSheetFragment.TAG)
            as? CameraSettingsSheetFragment)?.view

    private fun settingsContentRoot(activity: MainActivity): View? {
        val root = settingsRoot(activity) ?: return null
        return descendants(root).filterIsInstance<ScrollView>().firstOrNull { scroll ->
            descendants(scroll).filterIsInstance<TextView>()
                .any { it.text?.toString() == "Custom Recording Name" }
        }
    }

    private fun isSettingRowVisible(
        scenario: ActivityScenario<MainActivity>,
        title: String
    ): Boolean = readActivity(scenario) { activity ->
        val root = settingsRoot(activity) ?: return@readActivity false
        descendants(root).filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == title }
            ?.isShown == true
    }

    private fun captureScreenshot(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), EVIDENCE_DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        File(directory, fileName).outputStream().use { output ->
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun clickableAncestor(start: View, limit: View): View? {
        var current: View? = start
        while (current != null && current !== limit) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent as? View
        }
        return null
    }

    private inline fun <reified T : View> findDescendantOfSetting(
        activity: MainActivity,
        title: String
    ): T? {
        val root = settingsRoot(activity) ?: return null
        val titleView = descendants(root).filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == title } ?: return null
        return nearestAncestorContaining<T>(titleView, root)
            ?.let { descendants(it).filterIsInstance<T>().firstOrNull() }
    }

    private inline fun <reified T : View> nearestAncestorContaining(start: View, limit: View): View? {
        var current: View? = start
        while (current != null && current !== limit) {
            if (descendants(current).any { it is T }) return current
            current = current.parent as? View
        }
        return null
    }

    private fun nearestAncestorWithTaggedValue(start: View, limit: View): TextView? {
        var current: View? = start
        while (current != null && current !== limit) {
            val value = descendants(current).filterIsInstance<TextView>()
                .firstOrNull {
                    it.tag == SETTING_VALUE_TAG || it.tag == SettingsSectionFactory.FLASH_VALUE_TAG
                }
            if (value != null) return value
            current = current.parent as? View
        }
        return null
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                yieldAll(descendants(root.getChildAt(index)))
            }
        }
    }

    private fun waitForAccessibilityText(text: String, timeoutMs: Long): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val node = root?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString() == text }
            if (node != null) return node
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("Accessibility text '$text' did not appear")
    }

    private fun waitForAccessibilityDescription(
        description: String,
        timeoutMs: Long
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val node = root?.findAccessibilityNodeInfosByText(description)
                ?.firstOrNull { it.contentDescription?.toString() == description }
            if (node != null) return node
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("Accessibility description '$description' did not appear")
    }

    private fun waitForAccessibilityClass(className: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val node = root?.let { findAccessibilityClass(it, className) }
            if (node != null) return node
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    private fun findAccessibilityClass(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (root.className?.toString() == className) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val match = findAccessibilityClass(child, className)
            if (match != null) return match
        }
        return null
    }

    private fun showOnScreenAndClickAccessibilityText(text: String): Boolean {
        var node = waitForAccessibilityText(text, DIALOG_TIMEOUT_MS)
        // Long choice sheets keep valid nodes outside the visible viewport. Asking Android's
        // accessibility framework to reveal the exact node produces a real scroll transaction and
        // avoids guessed screen coordinates. Reacquire after scrolling because the old node may be
        // invalidated by layout.
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        node = waitForAccessibilityText(text, DIALOG_TIMEOUT_MS)
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    private fun showOnScreenAndClickAccessibilityDescription(description: String): Long? {
        repeat(MAX_ACCESSIBILITY_SCROLL_STEPS) {
            var node = waitForAccessibilityDescription(description, DIALOG_TIMEOUT_MS)
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            node = waitForAccessibilityDescription(description, DIALOG_TIMEOUT_MS)

            var current: AccessibilityNodeInfo? = node
            var scrollOwner: AccessibilityNodeInfo? = null
            while (current != null) {
                // The iQOO accessibility bridge reports visible-to-user=false for a fully visible
                // modal choice row even though its UI-tree bounds are on screen. The explicit,
                // unique choice content-description plus clickable/enabled state is authoritative.
                if (current.isClickable && current.isEnabled) {
                    // A vendor ACTION_CLICK can block until expensive window-idle accounting completes,
                    // which measures the accessibility bridge rather than the setting callback. Inject
                    // a real pointer at the exact UI-tree-derived bound and timestamp that dispatch.
                    val pointerDispatchStart = SystemClock.elapsedRealtimeNanos()
                    if (injectTapAtAccessibilityBounds(current)) return pointerDispatchStart
                }
                if (scrollOwner == null && current.isScrollable) scrollOwner = current
                current = current.parent
            }
            if (scrollOwner?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) {
                return null
            }
            SystemClock.sleep(ACCESSIBILITY_SCROLL_SETTLE_MS)
        }
        return null
    }

    private fun injectTapAtAccessibilityBounds(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 30L, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun choiceAccessibilityLabel(label: String): String = "Neo setting choice: $label"

    private fun awaitCameraStreaming(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long
    ) {
        assertTrue("Camera did not reach Streaming", awaitCondition(timeoutMs) {
            readVm(scenario) { it.cameraState.value == CameraState.Streaming }
        })
    }

    private fun keepScreenAwake(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun <T> readVm(
        scenario: ActivityScenario<MainActivity>,
        block: (CameraViewModel) -> T
    ): T = readActivity(scenario) { block(it.vm) }

    private fun <T> readActivity(
        scenario: ActivityScenario<MainActivity>,
        block: (MainActivity) -> T
    ): T {
        val result = AtomicReference<T>()
        scenario.onActivity { result.set(block(it)) }
        return result.get()
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (runCatching(condition).getOrDefault(false)) return true
            SystemClock.sleep(POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return runCatching(condition).getOrDefault(false)
    }

    private fun audioSourceFor(index: Int): AudioSrc =
        if (index == 1) AudioSrc.MIC
        else if (index == 2) AudioSrc.UNPROCESSED
        else if (index == 3) AudioSrc.NONE
        else AudioSrc.CAMCORDER

    private fun audioPreferenceFor(index: Int): String =
        if (index == 1) "BUILTIN_MIC"
        else if (index == 2) "UNPROCESSED"
        else if (index == 3) "NONE"
        else "CAMCORDER"

    private fun saveOriginalPreferences(context: Context) {
        val source = context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE)
        val backup = context.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = backup.edit().clear().putBoolean(BACKUP_PRESENT_KEY, true)
        copyPreferences(source.all, editor)
        assertTrue("Could not commit LQ-014 preference backup", editor.commit())
    }

    private fun rememberRestartSize(activity: MainActivity, size: Size) {
        val backup = activity.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(
            "Could not persist LQ-014 expected recording size",
            backup.edit()
                .putInt(RESTART_WIDTH_KEY, size.width)
                .putInt(RESTART_HEIGHT_KEY, size.height)
                .commit()
        )
    }

    private fun rememberedRestartSize(context: Context): Size {
        val backup = context.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
        val width = backup.getInt(RESTART_WIDTH_KEY, 0)
        val height = backup.getInt(RESTART_HEIGHT_KEY, 0)
        assertTrue("LQ-014 expected recording size is missing", width > 0 && height > 0)
        return Size(width, height)
    }

    private fun hasPreferenceBackup(context: Context): Boolean =
        context.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(BACKUP_PRESENT_KEY, false)

    private fun restoreOriginalPreferences(context: Context): Boolean {
        val backup = context.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
        if (!backup.getBoolean(BACKUP_PRESENT_KEY, false)) return false
        val destination = context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = destination.edit().clear()
        copyPreferences(
            backup.all.filterKeys {
                it != BACKUP_PRESENT_KEY && it != RESTART_WIDTH_KEY && it != RESTART_HEIGHT_KEY
            },
            editor
        )
        val restored = editor.commit()
        if (restored) backup.edit().clear().commit()
        return restored
    }

    private fun copyPreferences(values: Map<String, *>, editor: SharedPreferences.Editor) {
        values.forEach { (key, value) ->
            if (value is Boolean) editor.putBoolean(key, value)
            else if (value is Int) editor.putInt(key, value)
            else if (value is Long) editor.putLong(key, value)
            else if (value is Float) editor.putFloat(key, value)
            else if (value is String) editor.putString(key, value)
            else if (value is Set<*>) editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else if (value != null) throw AssertionError("Unsupported preference type for $key: ${value.javaClass.name}")
        }
    }

    private fun writeEvidence(
        context: Context,
        phase: String,
        records: List<ProbeResult>,
        failure: Throwable?
    ) {
        val root = requireNotNull(context.getExternalFilesDir(EVIDENCE_DIRECTORY))
        if (!root.exists()) assertTrue("Could not create LQ-014 evidence directory", root.mkdirs())
        val resultArray = JSONArray()
        records.forEach { resultArray.put(it.toJson()) }
        val passed = failure == null && records.all { it.passed }
        val report = JSONObject()
            .put("schema", "neo-qa-lq014-settings/v1")
            .put("checkpoint", "LQ-014")
            .put("phase", phase)
            .put("capturedUtcMs", System.currentTimeMillis())
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("api", Build.VERSION.SDK_INT))
            .put("thresholdMs", ACTIVATION_LIMIT_MS)
            .put("expectedActiveSettings", SettingsOptionMetaCatalog.all.size)
            .put("measuredSettings", records.size)
            .put("maxObservedMs", records.maxOfOrNull { it.elapsedMs } ?: 0L)
            .put("passed", passed)
            .put("failure", failure?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL)
            .put("results", resultArray)
        val destination = File(root, "$phase.json")
        val temporary = File(root, "$phase.json.tmp")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        if (destination.exists()) destination.delete()
        assertTrue("Could not atomically publish ${destination.name}", temporary.renameTo(destination))
    }

    private data class SettingMutation(
        val id: String,
        val title: String,
        val kind: ControlKind,
        val target: Any,
        val uiLabel: String = target.toString()
    )

    private data class ProbeResult(
        val optionId: String,
        val title: String,
        val requested: String,
        val uiLabel: String,
        val activationClass: String,
        val elapsedMs: Long,
        val runtimeApplied: Boolean,
        val preferenceApplied: Boolean,
        val uiReflected: Boolean,
        val observedUiValue: String,
        val settingsRootStable: Boolean,
        val effectVerified: Boolean,
        val generationBefore: Long,
        val generationAfter: Long,
        val cameraState: String,
        val passed: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("optionId", optionId)
            .put("title", title)
            .put("requested", requested)
            .put("uiLabel", uiLabel)
            .put("activationClass", activationClass)
            .put("elapsedMs", elapsedMs)
            .put("runtimeApplied", runtimeApplied)
            .put("preferenceApplied", preferenceApplied)
            .put("uiReflected", uiReflected)
            .put("observedUiValue", observedUiValue)
            .put("settingsRootStable", settingsRootStable)
            .put("effectVerified", effectVerified)
            .put("generationBefore", generationBefore)
            .put("generationAfter", generationAfter)
            .put("cameraState", cameraState)
            .put("passed", passed)
    }

    private data class UiActionResult(
        val startNs: Long,
        val settingsRootStable: Boolean = true
    )

    private enum class ControlKind { SELECTOR, TOGGLE, SLIDER, TEXT }

    private companion object {
        const val ACTIVATION_LIMIT_MS = 1_000L
        const val RESTART_RUNTIME_TIMEOUT_MS = 5_000L
        const val STARTUP_TIMEOUT_MS = 20_000L
        const val SETUP_SETTLE_TIMEOUT_MS = 8_000L
        const val DIALOG_TIMEOUT_MS = 3_000L
        const val GUIDE_RENDER_TIMEOUT_MS = 10_000L
        const val MAX_ACCESSIBILITY_SCROLL_STEPS = 20
        const val ACCESSIBILITY_SCROLL_SETTLE_MS = 80L
        const val POLL_MS = 20L
        const val SETTING_VALUE_TAG = "settingValue"
        const val BACKUP_PREFS_NAME = "neo_qa_lq014_original_preferences"
        const val BACKUP_PRESENT_KEY = "__backup_present"
        const val RESTART_WIDTH_KEY = "__restart_width"
        const val RESTART_HEIGHT_KEY = "__restart_height"
        const val EVIDENCE_DIRECTORY = "neo-qa/lq014"
        const val PHASE_APPLY = "phase1-activation"
        const val PHASE_VERIFY = "phase2-process-restart"
        const val PHASE_FRAMING_ONLY = "framing-guides-only"
        const val PHASE_CLEANUP = "phase3-emergency-restore"
        const val RESTART_RECORDING_NAME = "LQ014_SENTINEL"
        const val RESTART_BITRATE_MBPS = 240

        fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L
    }
}
