package com.neoproc.neoproccamx.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.utils.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Physical-device proof for LQ-016 / CP-100..103 detail-profile activation and clip safety. */
@RunWith(AndroidJUnit4::class)
class Lq016DetailProfilesInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun threeProfiles_applyDistinctBoundedRuntimeContracts_andLockDuringRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalPreferences = snapshotPreferences(context)
        val records = mutableListOf<ProfileResult>()
        var lockResult: RecordingLockResult? = null
        var failure: Throwable? = null
        var recordingMayBeActive = false

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: CameraViewModel
                scenario.onActivity { activity ->
                    vm = activity.vm
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                try {
                    awaitStreaming(vm)
                    scenario.onActivity {
                        configureStableProcessedBaseline(vm)
                    }
                    awaitStreaming(vm)
                    awaitStableCameraSession(vm)

                    val profileOrder = listOf(
                        VideoDetailProfile.NATURAL_DETAIL,
                        VideoDetailProfile.EXTREME_DETAIL,
                        VideoDetailProfile.FOCUSED_DETAIL
                    )
                    profileOrder.forEach { profile ->
                        records += applyProfileThroughSettingsUi(scenario, vm, context, profile)
                    }

                    assertEquals("LQ-016 must expose exactly three production detail profiles", 3, records.size)
                    assertEquals(
                        "Every production detail profile must have a distinct GPU uniform signature",
                        3,
                        records.map { it.enhancementSignature }.toSet().size
                    )
                    assertEquals(
                        "Every production detail profile must have a distinct throughput signature",
                        3,
                        records.map { it.throughputSignature }.toSet().size
                    )
                    assertTrue(
                        "One or more profile contracts did not become active: " +
                            records.filterNot { it.passed }.joinToString { it.profile },
                        records.all { it.passed }
                    )

                    scenario.onActivity {
                        vm.dismissSettingsRejection()
                        vm.setVideoDetailProfile(VideoDetailProfile.FOCUSED_DETAIL)
                    }
                    awaitProfile(vm, VideoDetailProfile.FOCUSED_DETAIL)
                    awaitStableCameraSession(vm)
                    scenario.onActivity { vm.startRecording() }
                    recordingMayBeActive = true
                    awaitRecording(vm)

                    val before = vm.videoDetailProfileRuntimeEvidence()
                    val sessionBefore = vm.camera.sessionGeneration.get()
                    val rejectionSequenceBefore = vm.settingsRejection.value?.sequence ?: 0L
                    scenario.onActivity { vm.setVideoDetailProfile(VideoDetailProfile.EXTREME_DETAIL) }
                    assertTrue("Recording-time profile mutation was not rejected", awaitCondition(REJECTION_TIMEOUT_MS) {
                        vm.settingsRejection.value?.let { notice ->
                            notice.sequence > rejectionSequenceBefore &&
                                notice.ruleId == RECORDING_LOCK_RULE
                        } == true
                    })
                    val recordingEvidenceStartedMs = SystemClock.elapsedRealtime()
                    assertTrue(
                        "Recording or preview did not remain live for the LQ-016 evidence window",
                        holdRecordingEvidenceWindow(vm, RECORDING_EVIDENCE_WINDOW_MS)
                    )
                    val recordingEvidenceWindowMs =
                        SystemClock.elapsedRealtime() - recordingEvidenceStartedMs
                    val after = vm.videoDetailProfileRuntimeEvidence()
                    val notice = vm.settingsRejection.value
                    val warning = vm.uiState.value.hardwareWarning
                    val remainedRecording = vm.gpuRecordState.value is GpuRecordingState.Recording
                    lockResult = RecordingLockResult(
                        requestedProfile = VideoDetailProfile.EXTREME_DETAIL.name,
                        retainedProfile = after.selectedProfile.name,
                        observedRule = notice?.ruleId,
                        visibleReason = warning == notice?.message && !warning.isNullOrBlank(),
                        stateUnchanged = before == after,
                        sessionUnchanged = sessionBefore == vm.camera.sessionGeneration.get(),
                        recordingContinued = remainedRecording,
                        recordingEvidenceWindowMs = recordingEvidenceWindowMs,
                        passed = notice?.ruleId == RECORDING_LOCK_RULE &&
                            warning == notice.message && before == after &&
                            sessionBefore == vm.camera.sessionGeneration.get() && remainedRecording &&
                            recordingEvidenceWindowMs >= RECORDING_EVIDENCE_WINDOW_MS
                    )
                    captureScreenshot(context, "recording-profile-lock.png")
                    assertTrue("Detail-profile recording lock did not preserve the active clip", lockResult.passed)

                    scenario.onActivity { vm.stopRecording() }
                    awaitNotRecording(vm)
                    recordingMayBeActive = false
                    assertTrue("Camera did not recover after LQ-016 clip", awaitCondition(SESSION_TIMEOUT_MS) {
                        vm.cameraState.value == CameraState.Streaming && vm.camera.hasRecentPreviewActivity()
                    })
                } finally {
                    if (recordingMayBeActive) {
                        runCatching { scenario.onActivity { vm.stopRecording() } }
                        runCatching { awaitNotRecording(vm) }
                        recordingMayBeActive = false
                    }
                }
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            writeEvidence(context, records, lockResult, failure)
            restorePreferences(context, originalPreferences)
        }
    }

    private fun configureStableProcessedBaseline(vm: CameraViewModel) {
        vm.setMode(AppMode.VIDEO)
        vm.setRawEnabled(false)
        vm.setLogEnabled(false)
        vm.setCinemaVideoMode(CinemaVideoMode.PRO)
        vm.setVideoHdrMode("OFF")
        vm.setVideoColorSpace("Rec.709")
        vm.setColorProfile("Natural")
        vm.setVideoCodec(VideoCodecType.HEVC)
        vm.setTenBitRecordingEnabled(true)
        vm.setLutEnabled(false)
        vm.setVideoBitrateMode(VideoBitrateMode.AUTO)
        vm.setVideoResolution(resolveTestSize(vm))
        vm.setFPS(25)
        vm.setDebugValidationLabel("lq016_detail_profiles")
    }

    private fun resolveTestSize(vm: CameraViewModel): Size =
        vm.camera.videoSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: vm.camera.videoSizes
                .filter { it.width > 0 && it.height > 0 }
                .minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: throw AssertionError("The camera exposes no recording size")

    private fun applyProfileThroughSettingsUi(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        context: Context,
        profile: VideoDetailProfile
    ): ProfileResult {
        openSettingsFromViewBounds(scenario)
        assertTrue("Settings sheet did not open", awaitCondition(UI_TIMEOUT_MS) {
            readActivity(scenario) { settingsRoot(it) != null }
        })
        clickAccessibilityText("Detail Profile")
        clickAccessibilityText(profile.displayName.uppercase(Locale.US))
        assertTrue("Detail Profile UI did not reflect ${profile.displayName}", awaitCondition(UI_TIMEOUT_MS) {
            readActivity(scenario) { selectedSettingValue(it, "Detail Profile") } ==
                profile.displayName.uppercase(Locale.US)
        })
        captureScreenshot(context, "${profile.name.lowercase(Locale.US)}-setting.png")
        assertTrue(
            "Settings sheet did not close for ${profile.displayName}",
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        )
        assertTrue("Settings sheet remained visible", awaitCondition(UI_TIMEOUT_MS) {
            readActivity(scenario) { settingsRoot(it) == null }
        })
        awaitStreaming(vm)
        awaitStableCameraSession(vm)
        val evidence = awaitProfile(vm, profile)
        SystemClock.sleep(PROFILE_VISUAL_SETTLE_MS)
        val visual = readActivity(scenario) { activity ->
            capturePreviewMetrics(context, activity, profile)
        }
        val expectedId = when (profile) {
            VideoDetailProfile.FOCUSED_DETAIL -> 1f
            VideoDetailProfile.NATURAL_DETAIL -> 2f
            VideoDetailProfile.EXTREME_DETAIL -> 3f
        }
        val cameraProfile = evidence.cameraRequestEvidence["unifiedVideoControlDetailProfile"]
        val finiteAndBounded = listOfNotNull(
            evidence.spatialDenoise,
            evidence.chromaDenoise,
            evidence.sharpening,
            evidence.subjectDetailGain
        ).all { it.isFinite() && it in 0f..1f }
        val passed = evidence.selectedProfile == profile &&
            evidence.persistedProfile == profile &&
            evidence.recorderProfile == profile &&
            evidence.gpuProfileId == expectedId &&
            evidence.enhancementSignature.contains("detailProfile=${profile.name}") &&
            evidence.throughputSignature.endsWith(profile.name) &&
            evidence.requestedBitrateBps > 0 && evidence.appliedBitrateBps > 0 &&
            cameraProfile == profile.name && finiteAndBounded && visual.valid
        return ProfileResult(
            profile = profile.name,
            gpuProfileId = evidence.gpuProfileId,
            spatialDenoise = evidence.spatialDenoise,
            chromaDenoise = evidence.chromaDenoise,
            sharpening = evidence.sharpening,
            subjectDetailGain = evidence.subjectDetailGain,
            enhancementSignature = evidence.enhancementSignature,
            throughputSignature = evidence.throughputSignature,
            requestedBitrateBps = evidence.requestedBitrateBps,
            appliedBitrateBps = evidence.appliedBitrateBps,
            cameraProfile = cameraProfile,
            previewMeanLuma = visual.meanLuma,
            previewEdgeEnergy = visual.edgeEnergy,
            previewHighFrequencyResidual = visual.highFrequencyResidual,
            previewNonBlackFraction = visual.nonBlackFraction,
            passed = passed
        )
    }

    private fun awaitProfile(
        vm: CameraViewModel,
        profile: VideoDetailProfile
    ): VideoDetailProfileRuntimeEvidence {
        val expectedId = when (profile) {
            VideoDetailProfile.FOCUSED_DETAIL -> 1f
            VideoDetailProfile.NATURAL_DETAIL -> 2f
            VideoDetailProfile.EXTREME_DETAIL -> 3f
        }
        assertTrue("${profile.displayName} did not reach every runtime owner", awaitCondition(PROFILE_TIMEOUT_MS) {
            val evidence = vm.videoDetailProfileRuntimeEvidence()
            evidence.selectedProfile == profile &&
                evidence.persistedProfile == profile &&
                evidence.recorderProfile == profile &&
                evidence.gpuProfileId == expectedId &&
                evidence.enhancementSignature.contains("detailProfile=${profile.name}") &&
                evidence.cameraRequestEvidence["unifiedVideoControlDetailProfile"] == profile.name
        })
        return vm.videoDetailProfileRuntimeEvidence()
    }

    private fun awaitStreaming(vm: CameraViewModel) {
        assertTrue("Camera did not reach a live preview", awaitCondition(SESSION_TIMEOUT_MS) {
            vm.cameraState.value == CameraState.Streaming && vm.camera.hasRecentPreviewActivity()
        })
    }

    private fun awaitStableCameraSession(vm: CameraViewModel) {
        var generation = vm.camera.sessionGeneration.get()
        var stableSince = SystemClock.elapsedRealtime()
        val deadline = SystemClock.elapsedRealtime() + SESSION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
            val next = vm.camera.sessionGeneration.get()
            if (next != generation || vm.camera.sessionRefreshPending.get()) {
                generation = next
                stableSince = SystemClock.elapsedRealtime()
            } else if (SystemClock.elapsedRealtime() - stableSince >= SESSION_STABLE_MS) {
                return
            }
        }
        throw AssertionError("Camera session did not stabilize")
    }

    private fun awaitRecording(vm: CameraViewModel) {
        assertTrue("LQ-016 recording did not start", awaitCondition(RECORD_START_TIMEOUT_MS) {
            when (val state = vm.gpuRecordState.value) {
                is GpuRecordingState.Recording -> true
                is GpuRecordingState.Error -> throw AssertionError(state.message)
                else -> false
            }
        })
    }

    private fun awaitNotRecording(vm: CameraViewModel) {
        assertTrue("LQ-016 recording did not finalize", awaitCondition(RECORD_STOP_TIMEOUT_MS) {
            when (val state = vm.gpuRecordState.value) {
                is GpuRecordingState.Starting,
                is GpuRecordingState.Recording,
                is GpuRecordingState.Paused,
                is GpuRecordingState.Stopping,
                is GpuRecordingState.Finalizing -> false
                is GpuRecordingState.Error -> throw AssertionError(state.message)
                else -> true
            }
        })
    }

    private fun holdRecordingEvidenceWindow(vm: CameraViewModel, durationMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val state = vm.gpuRecordState.value) {
                is GpuRecordingState.Recording -> Unit
                is GpuRecordingState.Error -> throw AssertionError(state.message)
                else -> return false
            }
            if (vm.cameraState.value !in setOf(CameraState.Streaming, CameraState.Recording) ||
                !vm.camera.hasRecentPreviewActivity()
            ) {
                return false
            }
            SystemClock.sleep(RECORDING_CONTINUITY_POLL_MS)
        }
        return vm.gpuRecordState.value is GpuRecordingState.Recording &&
            vm.cameraState.value in setOf(CameraState.Streaming, CameraState.Recording) &&
            vm.camera.hasRecentPreviewActivity()
    }

    private fun openSettingsFromViewBounds(scenario: ActivityScenario<MainActivity>) {
        val bounds = readActivity(scenario) { activity ->
            listOf(R.id.btnSettings, R.id.btnModePro)
                .asSequence()
                .map { activity.findViewById<View>(it) }
                .filter { it.isShown && it.isEnabled }
                .mapNotNull { view -> Rect().takeIf { view.getGlobalVisibleRect(it) && !it.isEmpty } }
                .firstOrNull()
                ?: throw AssertionError("No enabled Settings control has visible UI bounds")
        }
        assertTrue("Settings tap injection failed", injectTap(bounds))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun clickAccessibilityText(text: String) {
        var node = waitForAccessibilityText(text)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        node = waitForAccessibilityText(text)
        var owner: AccessibilityNodeInfo? = node
        while (owner != null && (!owner.isClickable || !owner.isEnabled)) owner = owner.parent
        val clickable = owner ?: throw AssertionError("No clickable accessibility owner for '$text'")
        val bounds = Rect().also(clickable::getBoundsInScreen)
        assertTrue("Accessibility bounds are empty for '$text'", !bounds.isEmpty)
        assertTrue("Tap injection failed for '$text'", injectTap(bounds))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForAccessibilityText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString() == text }
            if (node != null) return node
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("Accessibility text '$text' did not appear")
    }

    private fun injectTap(bounds: Rect): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0
        )
        val up = MotionEvent.obtain(
            downTime, downTime + 30L, MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0
        )
        return try {
            automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun selectedSettingValue(activity: MainActivity, title: String): String? {
        val root = settingsRoot(activity) ?: return null
        val titleView = descendants(root).filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == title } ?: return null
        var owner: View? = titleView
        while (owner != null && owner !== root) {
            val value = descendants(owner).filterIsInstance<TextView>()
                .firstOrNull { it.tag == "settingValue" }
            if (value != null) return value.text?.toString()
            owner = owner.parent as? View
        }
        return null
    }

    private fun settingsRoot(activity: MainActivity): View? =
        (activity.supportFragmentManager.findFragmentByTag(CameraSettingsSheetFragment.TAG)
            as? CameraSettingsSheetFragment)?.view

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    private fun capturePreviewMetrics(
        context: Context,
        activity: MainActivity,
        profile: VideoDetailProfile
    ): PreviewMetrics {
        val full = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("Unable to capture ${profile.displayName} preview")
        val bounds = Rect()
        assertTrue("Preview has no visible bounds", activity.findViewById<View>(R.id.glSurface)
            .getGlobalVisibleRect(bounds) && !bounds.isEmpty)
        val left = bounds.left.coerceIn(0, full.width - 1)
        val top = bounds.top.coerceIn(0, full.height - 1)
        val right = bounds.right.coerceIn(left + 1, full.width)
        val bottom = bounds.bottom.coerceIn(top + 1, full.height)
        val crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
        File(evidenceDirectory(context), "${profile.name.lowercase(Locale.US)}-preview.png")
            .outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val marginX = (crop.width * 0.18f).toInt().coerceAtLeast(2)
        val marginY = (crop.height * 0.18f).toInt().coerceAtLeast(2)
        var samples = 0L
        var nonBlack = 0L
        var lumaSum = 0.0
        var edgeSum = 0.0
        var residualSum = 0.0
        var y = marginY + SAMPLE_STEP
        while (y < crop.height - marginY - SAMPLE_STEP) {
            var x = marginX + SAMPLE_STEP
            while (x < crop.width - marginX - SAMPLE_STEP) {
                val center = luma(crop.getPixel(x, y))
                val rightLuma = luma(crop.getPixel(x + SAMPLE_STEP, y))
                val downLuma = luma(crop.getPixel(x, y + SAMPLE_STEP))
                val leftLuma = luma(crop.getPixel(x - SAMPLE_STEP, y))
                val upLuma = luma(crop.getPixel(x, y - SAMPLE_STEP))
                val localMean = (rightLuma + downLuma + leftLuma + upLuma) * 0.25
                lumaSum += center
                edgeSum += (abs(center - rightLuma) + abs(center - downLuma)) * 0.5
                residualSum += abs(center - localMean)
                if (center > BLACK_LUMA_THRESHOLD) nonBlack++
                samples++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        crop.recycle()
        full.recycle()
        if (samples == 0L) return PreviewMetrics.INVALID
        val metrics = PreviewMetrics(
            meanLuma = lumaSum / samples,
            edgeEnergy = edgeSum / samples,
            highFrequencyResidual = residualSum / samples,
            nonBlackFraction = nonBlack.toDouble() / samples,
            valid = nonBlack.toDouble() / samples >= MIN_NON_BLACK_FRACTION
        )
        return metrics
    }

    private fun luma(pixel: Int): Double =
        (0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)) / 255.0

    private fun captureScreenshot(context: Context, fileName: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("Unable to capture LQ-016 screenshot")
        File(evidenceDirectory(context), fileName).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun snapshotPreferences(context: Context): Map<String, Any?> =
        context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE).all
            .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }

    private fun restorePreferences(context: Context, values: Map<String, Any?>) {
        val editor = context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear()
        values.forEach { (key, value) -> putPreference(editor, key, value) }
        assertTrue("Could not restore preferences after LQ-016", editor.commit())
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        if (value is Boolean) editor.putBoolean(key, value)
        else if (value is Int) editor.putInt(key, value)
        else if (value is Long) editor.putLong(key, value)
        else if (value is Float) editor.putFloat(key, value)
        else if (value is String) editor.putString(key, value)
        else if (value is Set<*>) editor.putStringSet(key, value.filterIsInstance<String>().toSet())
    }

    private fun writeEvidence(
        context: Context,
        profiles: List<ProfileResult>,
        recordingLock: RecordingLockResult?,
        failure: Throwable?
    ) {
        val report = JSONObject()
            .put("schema", "neo-qa-lq016-detail-profiles/v1")
            .put("checkpoint", "LQ-016")
            .put("canonicalCheckpoints", JSONArray(listOf("CP-100", "CP-101", "CP-102", "CP-103")))
            .put("capturedUtcMs", System.currentTimeMillis())
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("api", Build.VERSION.SDK_INT))
            .put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
            .put("recordingLock", recordingLock?.toJson() ?: JSONObject.NULL)
            .put("physicalChartQualification", "REQUIRES_CONTROLLED_CHART_REVIEW")
            .put("passed", failure == null && profiles.size == 3 && profiles.all { it.passed } && recordingLock?.passed == true)
            .put("failure", failure?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL)
        val directory = evidenceDirectory(context)
        val temporary = File(directory, "lq016.json.tmp")
        val destination = File(directory, "lq016.json")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        if (destination.exists()) destination.delete()
        assertTrue("Could not atomically publish LQ-016 evidence", temporary.renameTo(destination))
    }

    private fun evidenceDirectory(context: Context): File =
        requireNotNull(context.getExternalFilesDir(EVIDENCE_DIRECTORY)).apply { mkdirs() }

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

    private data class PreviewMetrics(
        val meanLuma: Double,
        val edgeEnergy: Double,
        val highFrequencyResidual: Double,
        val nonBlackFraction: Double,
        val valid: Boolean
    ) {
        companion object {
            val INVALID = PreviewMetrics(0.0, 0.0, 0.0, 0.0, false)
        }
    }

    private data class ProfileResult(
        val profile: String,
        val gpuProfileId: Float?,
        val spatialDenoise: Float?,
        val chromaDenoise: Float?,
        val sharpening: Float?,
        val subjectDetailGain: Float?,
        val enhancementSignature: String,
        val throughputSignature: String,
        val requestedBitrateBps: Int,
        val appliedBitrateBps: Int,
        val cameraProfile: String?,
        val previewMeanLuma: Double,
        val previewEdgeEnergy: Double,
        val previewHighFrequencyResidual: Double,
        val previewNonBlackFraction: Double,
        val passed: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("profile", profile)
            .put("gpuProfileId", gpuProfileId ?: JSONObject.NULL)
            .put("spatialDenoise", spatialDenoise ?: JSONObject.NULL)
            .put("chromaDenoise", chromaDenoise ?: JSONObject.NULL)
            .put("sharpening", sharpening ?: JSONObject.NULL)
            .put("subjectDetailGain", subjectDetailGain ?: JSONObject.NULL)
            .put("enhancementSignature", enhancementSignature)
            .put("throughputSignature", throughputSignature)
            .put("requestedBitrateBps", requestedBitrateBps)
            .put("appliedBitrateBps", appliedBitrateBps)
            .put("cameraProfile", cameraProfile ?: JSONObject.NULL)
            .put("previewMeanLuma", previewMeanLuma)
            .put("previewEdgeEnergy", previewEdgeEnergy)
            .put("previewHighFrequencyResidual", previewHighFrequencyResidual)
            .put("previewNonBlackFraction", previewNonBlackFraction)
            .put("passed", passed)
    }

    private data class RecordingLockResult(
        val requestedProfile: String,
        val retainedProfile: String,
        val observedRule: String?,
        val visibleReason: Boolean,
        val stateUnchanged: Boolean,
        val sessionUnchanged: Boolean,
        val recordingContinued: Boolean,
        val recordingEvidenceWindowMs: Long,
        val passed: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("requestedProfile", requestedProfile)
            .put("retainedProfile", retainedProfile)
            .put("observedRule", observedRule ?: JSONObject.NULL)
            .put("visibleReason", visibleReason)
            .put("stateUnchanged", stateUnchanged)
            .put("sessionUnchanged", sessionUnchanged)
            .put("recordingContinued", recordingContinued)
            .put("recordingEvidenceWindowMs", recordingEvidenceWindowMs)
            .put("passed", passed)
    }

    private companion object {
        const val EVIDENCE_DIRECTORY = "neo-qa/lq016"
        const val RECORDING_LOCK_RULE = "DETAIL_PROFILE_RECORDING_LOCK"
        const val UI_TIMEOUT_MS = 8_000L
        const val PROFILE_TIMEOUT_MS = 20_000L
        const val SESSION_TIMEOUT_MS = 30_000L
        const val RECORD_START_TIMEOUT_MS = 45_000L
        const val RECORD_STOP_TIMEOUT_MS = 90_000L
        const val REJECTION_TIMEOUT_MS = 3_000L
        const val SESSION_STABLE_MS = 1_200L
        const val RECORDING_EVIDENCE_WINDOW_MS = 4_500L
        const val RECORDING_CONTINUITY_POLL_MS = 100L
        const val PROFILE_VISUAL_SETTLE_MS = 750L
        const val POLL_MS = 25L
        const val SAMPLE_STEP = 4
        const val BLACK_LUMA_THRESHOLD = 0.015
        const val MIN_NON_BLACK_FRACTION = 0.05
    }
}
