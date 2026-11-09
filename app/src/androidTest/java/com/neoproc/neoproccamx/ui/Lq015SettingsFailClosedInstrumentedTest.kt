package com.neoproc.neoproccamx.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
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
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.ui.controls.SettingsSectionFactory
import com.neoproc.neoproccamx.ui.controls.VideoSettingsFragment
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

/** Physical-device proof for LQ-015 / CP-099: invalid settings fail closed without substitution. */
@RunWith(AndroidJUnit4::class)
class Lq015SettingsFailClosedInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun invalidSettingsMatrix_rejectsBeforeMutationAndShowsTruthfulReason() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalPreferences = snapshotPreferences(context)
        val results = mutableListOf<MatrixResult>()
        var failure: Throwable? = null

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                awaitStreaming(scenario)

                results += executeAtomicHdrTransitionCase(
                    scenario, context, "raw-xdr10", "NDR10+",
                    setup = ::establishRawBaseline,
                    request = { it.setVideoHdrMode("NDR10+") }
                )
                results += executeAtomicHdrTransitionCase(
                    scenario, context, "raw-nivukx", "NIVUKXVISION",
                    setup = ::establishRawBaseline,
                    request = { it.setVideoHdrMode("NIVUKXVISION") }
                )
                results += executeRejectedCase(
                    scenario, context, "xdr-raw-mode", "RAW_EXCLUDES_HDR",
                    setup = ::establishXdrBaseline,
                    request = { it.setCinemaVideoMode(CinemaVideoMode.RAW) }
                )
                results += executeRejectedCase(
                    scenario, context, "xdr-raw-toggle", "RAW_EXCLUDES_HDR",
                    setup = ::establishXdrBaseline,
                    request = { it.setRawEnabled(true) }
                )
                results += executeAtomicHdrTransitionCase(
                    scenario, context, "log-xdr", "NDR10+",
                    setup = ::establishLogBaseline,
                    request = { it.setVideoHdrMode("NDR10+") }
                )
                results += executeRejectedCase(
                    scenario, context, "raw-invalid-compression", "RAW_PINS_UNCOMPRESSED_12BIT",
                    setup = ::establishRawBaseline,
                    request = { it.setRawVideoCompressionMode(RawCompressionMode.OFF) }
                )
                results += executeRejectedCase(
                    scenario, context, "fps-zero", "FPS_MUST_BE_POSITIVE",
                    setup = ::establishProBaseline,
                    request = { it.setFPS(0) }
                )
                results += executeRejectedCase(
                    scenario, context, "fps-above-limit", "FPS_EXCEEDS_PRODUCT_LIMIT",
                    setup = ::establishProBaseline,
                    request = { it.setFPS(120) }
                )
                results += executeRejectedCase(
                    scenario, context, "fps-unverified", "FPS_NOT_A_VERIFIED_CADENCE",
                    setup = ::establishProBaseline,
                    request = { it.setFPS(26) }
                )
                results += executeRejectedCase(
                    scenario, context, "codec-avc", "STRICT_MAIN10_REQUIRES_HEVC",
                    setup = ::establishProBaseline,
                    request = { it.setVideoCodec(VideoCodecType.AVC) }
                )
                results += executeRejectedCase(
                    scenario, context, "codec-av1", "STRICT_MAIN10_REQUIRES_HEVC",
                    setup = ::establishProBaseline,
                    request = { it.setVideoCodec(VideoCodecType.AV1) }
                )
                results += executeRejectedCase(
                    scenario, context, "resolution-zero", "RESOLUTION_MUST_BE_POSITIVE",
                    setup = ::establishProBaseline,
                    request = { it.setVideoResolution(Size(0, 2160)) }
                )
                results += executeRejectedCase(
                    scenario, context, "resolution-over-limit", "RESOLUTION_EXCEEDS_PRODUCT_LIMIT",
                    setup = ::establishProBaseline,
                    request = { it.setVideoResolution(Size(20_000, 10_000)) }
                )
                results += executeRejectedCase(
                    scenario, context, "resolution-not-advertised", "RESOLUTION_NOT_ADVERTISED_BY_CAMERA",
                    setup = ::establishProBaseline,
                    request = { it.setVideoResolution(Size(1234, 567)) }
                )
                results += executeRejectedCase(
                    scenario, context, "bitrate-zero", "CUSTOM_BITRATE_MUST_BE_POSITIVE",
                    setup = ::establishProBaseline,
                    request = { it.setVideoBitrate(0) }
                )
                results += executeRejectedCase(
                    scenario, context, "bitrate-over-limit", "CUSTOM_BITRATE_EXCEEDS_PRODUCT_LIMIT",
                    setup = ::establishProBaseline,
                    request = { it.setVideoBitrate(2_000_000_001) }
                )
                results += executeRejectedCase(
                    scenario, context, "disable-processed-ten-bit", "STRICT_PROCESSED_VIDEO_REQUIRES_10_BIT",
                    setup = ::establishProBaseline,
                    request = { it.setTenBitRecordingEnabled(false) }
                )
                results += executeRejectedCase(
                    scenario, context, "unknown-xdr-mode", "HDR_MODE_NOT_RECOGNIZED",
                    setup = ::establishProBaseline,
                    request = { it.setVideoHdrMode("UNKNOWN_DYNAMIC_RANGE") }
                )

                val uiResult = executeRealUiAtomicTransitionCase(scenario, context)
                results += uiResult
                assertEquals("LQ-015 matrix row count", EXPECTED_MATRIX_ROWS, results.size)
                assertTrue(
                    "LQ-015 failures: " + results.filterNot { it.passed }.joinToString { row ->
                            "${row.id}(expectation=${row.expectation},rule=${row.observedRule}," +
                            "state=${row.stateContractMet},prefs=${row.preferencesContractMet}," +
                            "session=${row.sessionContractMet},feedback=${row.feedbackContractMet}," +
                            "camera=${row.cameraHealthy})"
                    },
                    results.all { it.passed }
                )
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            writeEvidence(context, results, failure)
            restorePreferences(context, originalPreferences)
        }
    }

    private fun executeRejectedCase(
        scenario: ActivityScenario<MainActivity>,
        context: Context,
        id: String,
        expectedRule: String,
        setup: (CameraViewModel) -> Unit,
        request: (CameraViewModel) -> Unit
    ): MatrixResult {
        scenario.onActivity { activity ->
            activity.vm.dismissSettingsRejection()
            setup(activity.vm)
        }
        awaitStreaming(scenario)
        awaitStableSessionGeneration(scenario)
        val beforeState = readVm(scenario, ::snapshotRuntime)
        val beforePreferences = snapshotPreferences(context)
        val priorSequence = readVm(scenario) { it.settingsRejection.value?.sequence ?: 0L }

        scenario.onActivity { request(it.vm) }
        val rejected = awaitCondition(REJECTION_TIMEOUT_MS) {
            readVm(scenario) { vm ->
                vm.settingsRejection.value?.let { notice ->
                    notice.sequence > priorSequence && notice.ruleId == expectedRule
                } == true
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(REJECTION_SETTLE_MS)

        val afterState = readVm(scenario, ::snapshotRuntime)
        val afterPreferences = snapshotPreferences(context)
        val notice = readVm(scenario) { it.settingsRejection.value }
        val userMessage = readVm(scenario) { it.uiState.value.hardwareWarning }
        return MatrixResult(
            id = id,
            expectation = "REJECT_UNCHANGED",
            expectedRule = expectedRule,
            observedRule = notice?.ruleId,
            message = notice?.message,
            stateContractMet = beforeState == afterState,
            preferencesContractMet = beforePreferences == afterPreferences,
            sessionContractMet = beforeState.sessionGeneration == afterState.sessionGeneration,
            feedbackContractMet = !userMessage.isNullOrBlank() && userMessage == notice?.message,
            cameraHealthy = afterState.cameraState == CameraState.Streaming.javaClass.simpleName,
            passed = rejected && beforeState == afterState && beforePreferences == afterPreferences &&
                beforeState.sessionGeneration == afterState.sessionGeneration &&
                !userMessage.isNullOrBlank() && userMessage == notice?.message &&
                afterState.cameraState == CameraState.Streaming.javaClass.simpleName
        )
    }

    /**
     * XDR selection while RAW or LOG is active is not an illegal final state. The product's
     * committed transition policy changes the cinema mode to PRO and applies XDR as one atomic
     * transaction. Verify that final state directly so this fail-closed suite cannot mistake a
     * deliberate, legal mode transition for an invariant bypass.
     */
    private fun executeAtomicHdrTransitionCase(
        scenario: ActivityScenario<MainActivity>,
        context: Context,
        id: String,
        expectedHdrMode: String,
        setup: (CameraViewModel) -> Unit,
        request: (CameraViewModel) -> Unit
    ): MatrixResult {
        scenario.onActivity { activity ->
            activity.vm.dismissSettingsRejection()
            setup(activity.vm)
        }
        awaitStreaming(scenario)
        awaitStableSessionGeneration(scenario)
        val beforeState = readVm(scenario, ::snapshotRuntime)
        val beforePreferences = snapshotPreferences(context)
        val priorSequence = readVm(scenario) { it.settingsRejection.value?.sequence ?: 0L }

        scenario.onActivity { request(it.vm) }
        val transitioned = awaitCondition(SESSION_TIMEOUT_MS) {
            readVm(scenario) { vm ->
                val state = snapshotRuntime(vm)
                state.cinemaMode == CinemaVideoMode.PRO.name &&
                    !state.rawEnabled && !state.logEnabled &&
                    state.hdrMode == expectedHdrMode &&
                    state.recordingCodec == VideoCodecType.HEVC.name &&
                    state.encoderCodec == VideoCodecType.HEVC.name &&
                    state.encodedTenBit
            }
        }
        awaitStreaming(scenario)
        awaitStableSessionGeneration(scenario)

        val afterState = readVm(scenario, ::snapshotRuntime)
        val afterPreferences = snapshotPreferences(context)
        val notice = readVm(scenario) { it.settingsRejection.value }
        val userMessage = readVm(scenario) { it.uiState.value.hardwareWarning }
        val noNewRejection = notice == null || notice.sequence <= priorSequence
        val stateContractMet = transitioned && beforeState != afterState &&
            afterState.cinemaMode == CinemaVideoMode.PRO.name &&
            !afterState.rawEnabled && !afterState.logEnabled &&
            afterState.hdrMode == expectedHdrMode && afterState.encodedTenBit
        val preferencesContractMet = beforePreferences != afterPreferences
        val sessionContractMet = afterState.sessionGeneration >= beforeState.sessionGeneration
        val feedbackContractMet = noNewRejection &&
            (userMessage == null || !userMessage.startsWith("Setting not applied:"))
        val cameraHealthy = afterState.cameraState == CameraState.Streaming.javaClass.simpleName
        return MatrixResult(
            id = id,
            expectation = "ATOMIC_TO_PRO_HDR",
            expectedRule = null,
            observedRule = notice?.takeIf { it.sequence > priorSequence }?.ruleId,
            message = notice?.takeIf { it.sequence > priorSequence }?.message,
            stateContractMet = stateContractMet,
            preferencesContractMet = preferencesContractMet,
            sessionContractMet = sessionContractMet,
            feedbackContractMet = feedbackContractMet,
            cameraHealthy = cameraHealthy,
            passed = stateContractMet && preferencesContractMet && sessionContractMet &&
                feedbackContractMet && cameraHealthy
        )
    }

    private fun executeRealUiAtomicTransitionCase(
        scenario: ActivityScenario<MainActivity>,
        context: Context
    ): MatrixResult {
        scenario.onActivity { activity ->
            activity.vm.dismissSettingsRejection()
            establishRawBaseline(activity.vm)
        }
        awaitStreaming(scenario)
        openSettingsFromViewBounds(scenario)
        assertTrue("Settings sheet did not open", awaitCondition(UI_TIMEOUT_MS) {
            readActivity(scenario) { settingsRoot(it) != null }
        })
        assertTrue("Settings lifecycle boundary did not close Camera2", awaitCondition(SESSION_TIMEOUT_MS) {
            readVm(scenario) { it.cameraState.value == CameraState.Closed }
        })

        val beforeState = readVm(scenario, ::snapshotRuntime)
        val beforePreferences = snapshotPreferences(context)
        val priorSequence = readVm(scenario) { it.settingsRejection.value?.sequence ?: 0L }
        clickAccessibilityText("XDR Mode")
        clickAccessibilityText("NIVUKX VISION")

        val transitioned = awaitCondition(SESSION_TIMEOUT_MS) {
            readVm(scenario) { vm ->
                val state = snapshotRuntime(vm)
                state.cinemaMode == CinemaVideoMode.PRO.name &&
                    !state.rawEnabled && !state.logEnabled &&
                    state.hdrMode == "NIVUKXVISION" && state.encodedTenBit
            }
        }
        val authoritativeLabelApplied = awaitCondition(UI_TIMEOUT_MS) {
            readActivity(scenario) { activity ->
                selectedSettingValue(activity, "XDR Mode") == "NIVUKX VISION"
            }
        }
        val bannerVisible = readActivity(scenario) { activity ->
            settingsRoot(activity)
                ?.findViewWithTag<TextView>(VideoSettingsFragment.SETTINGS_REJECTION_BANNER_TAG)
                ?.let { it.isShown && it.text.toString().startsWith("Setting not applied:") } == true
        }
        val afterState = readVm(scenario, ::snapshotRuntime)
        val afterPreferences = snapshotPreferences(context)
        val notice = readVm(scenario) { it.settingsRejection.value }
        captureScreenshot("lq015-atomic-xdr-transition.png")
        writeAccessibilityTree(context, "lq015-atomic-xdr-transition-ui-tree.txt")

        val noNewRejection = notice == null || notice.sequence <= priorSequence
        val stateContractMet = transitioned && beforeState != afterState &&
            afterState.cinemaMode == CinemaVideoMode.PRO.name &&
            !afterState.rawEnabled && !afterState.logEnabled &&
            afterState.hdrMode == "NIVUKXVISION" && afterState.encodedTenBit
        val preferencesContractMet = beforePreferences != afterPreferences
        val sessionContractMet = beforeState.sessionGeneration == afterState.sessionGeneration
        val feedbackContractMet = authoritativeLabelApplied && !bannerVisible && noNewRejection
        val cameraHealthy = afterState.cameraState == CameraState.Closed.javaClass.simpleName
        return MatrixResult(
            id = "raw-nivukx-real-settings-ui",
            expectation = "ATOMIC_TO_PRO_HDR_FROM_UI",
            expectedRule = null,
            observedRule = notice?.takeIf { it.sequence > priorSequence }?.ruleId,
            message = notice?.takeIf { it.sequence > priorSequence }?.message,
            stateContractMet = stateContractMet,
            preferencesContractMet = preferencesContractMet,
            sessionContractMet = sessionContractMet,
            feedbackContractMet = feedbackContractMet,
            cameraHealthy = cameraHealthy,
            passed = stateContractMet && preferencesContractMet && sessionContractMet &&
                feedbackContractMet && cameraHealthy
        )
    }

    private fun establishProBaseline(vm: CameraViewModel) {
        vm.setVideoHdrMode("OFF")
        vm.setCinemaVideoMode(CinemaVideoMode.PRO)
        vm.setVideoCodec(VideoCodecType.HEVC)
        vm.setTenBitRecordingEnabled(true)
        vm.setFPS(25)
        vm.setVideoBitrate(240_000_000)
        vm.setVideoBitrateMode(VideoBitrateMode.CUSTOM)
    }

    private fun establishRawBaseline(vm: CameraViewModel) {
        establishProBaseline(vm)
        vm.setCinemaVideoMode(CinemaVideoMode.RAW)
    }

    private fun establishLogBaseline(vm: CameraViewModel) {
        establishProBaseline(vm)
        vm.setCinemaVideoMode(CinemaVideoMode.LOG)
    }

    private fun establishXdrBaseline(vm: CameraViewModel) {
        establishProBaseline(vm)
        vm.setVideoColorSpace("Rec.2020")
        vm.setVideoHdrMode("NDR10+")
    }

    private fun snapshotRuntime(vm: CameraViewModel): RuntimeSnapshot = RuntimeSnapshot(
        cinemaMode = vm.cinemaVideoMode.value.name,
        rawEnabled = vm.rawOnFlow.value,
        logEnabled = vm.logEnabledFlow.value,
        hdrMode = vm.videoHdrMode.value,
        rawCompression = vm.rawVideoCompression.value.name,
        recordingFps = vm.recording.fps,
        encoderFps = vm.gpuRecording.videoFps,
        recordingCodec = vm.recording.codec.name,
        encoderCodec = vm.gpuRecording.videoCodec.name,
        encodedTenBit = vm.gpuRecording.tenBitEnabled,
        width = vm.gpuRecording.videoWidth,
        height = vm.gpuRecording.videoHeight,
        bitrateMode = vm.videoBitrateMode.value.name,
        requestedBitrate = vm.requestedVideoBitrateBitsPerSecond(),
        sessionGeneration = vm.camera.sessionGeneration.get(),
        cameraState = vm.cameraState.value.javaClass.simpleName
    )

    private fun awaitStreaming(scenario: ActivityScenario<MainActivity>) {
        assertTrue("Camera did not reach Streaming", awaitCondition(SESSION_TIMEOUT_MS) {
            readVm(scenario) { it.cameraState.value == CameraState.Streaming }
        })
    }

    private fun awaitStableSessionGeneration(scenario: ActivityScenario<MainActivity>) {
        var previous = readVm(scenario) { it.camera.sessionGeneration.get() }
        var stableSinceMs = SystemClock.elapsedRealtime()
        val deadline = SystemClock.elapsedRealtime() + SESSION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
            val state = readVm(scenario) {
                Triple(
                    it.camera.sessionGeneration.get(),
                    it.camera.sessionRefreshPending.get(),
                    it.cameraState.value
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (state.first != previous || state.second || state.third != CameraState.Streaming) {
                previous = state.first
                stableSinceMs = now
                continue
            }
            if (now - stableSinceMs >= GENERATION_STABILITY_MS) return
        }
        throw AssertionError("Camera session did not become idle and stable")
    }

    private fun openSettingsFromViewBounds(scenario: ActivityScenario<MainActivity>) {
        val bounds = readActivity(scenario) { activity ->
            listOf(R.id.btnModePro, R.id.btnSettings)
                .asSequence()
                .map { activity.findViewById<View>(it) }
                .filter { it.isShown && it.isEnabled }
                .mapNotNull { view ->
                    Rect().takeIf { rect ->
                        view.getGlobalVisibleRect(rect) && !rect.isEmpty
                    }
                }
                .firstOrNull()
                ?: throw AssertionError("No enabled Settings control has visible UI-tree bounds")
        }
        assertTrue("Settings tap injection failed", injectTap(bounds))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun clickAccessibilityText(text: String) {
        var node = waitForAccessibilityText(text)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        node = waitForAccessibilityText(text)
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && (!clickable.isClickable || !clickable.isEnabled)) {
            clickable = clickable.parent
        }
        val owner = clickable ?: throw AssertionError("No clickable accessibility owner for '$text'")
        val bounds = Rect()
        owner.getBoundsInScreen(bounds)
        assertTrue("Accessibility node '$text' has empty bounds", !bounds.isEmpty)
        assertTrue("Tap injection failed for '$text'", injectTap(bounds))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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

    private fun selectedSettingValue(activity: MainActivity, title: String): String? {
        val root = settingsRoot(activity) ?: return null
        val titleView = descendants(root).filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == title } ?: return null
        var owner: View? = titleView
        while (owner != null && owner !== root) {
            val value = descendants(owner).filterIsInstance<TextView>()
                .firstOrNull { it.tag == "settingValue" || it.tag == SettingsSectionFactory.FLASH_VALUE_TAG }
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

    private fun captureScreenshot(fileName: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = evidenceDirectory(context)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("Unable to capture LQ-015 screenshot")
        File(directory, fileName).outputStream().use { output ->
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun writeAccessibilityTree(context: Context, fileName: String) {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: throw AssertionError("Accessibility root is unavailable")
        val output = StringBuilder()
        fun append(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect().also(node::getBoundsInScreen)
            output.append("  ".repeat(depth))
                .append(node.className).append(" text=").append(node.text)
                .append(" description=").append(node.contentDescription)
                .append(" visible=").append(node.isVisibleToUser)
                .append(" bounds=").append(bounds).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let { append(it, depth + 1) }
        }
        append(root, 0)
        File(evidenceDirectory(context), fileName).writeText(output.toString(), Charsets.UTF_8)
    }

    private fun snapshotPreferences(context: Context): Map<String, Any?> =
        context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE).all
            .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }

    private fun restorePreferences(context: Context, values: Map<String, Any?>) {
        val editor = context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear()
        values.forEach { (key, value) -> putPreference(editor, key, value) }
        assertTrue("Could not restore user preferences after LQ-015", editor.commit())
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        if (value is Boolean) editor.putBoolean(key, value)
        else if (value is Int) editor.putInt(key, value)
        else if (value is Long) editor.putLong(key, value)
        else if (value is Float) editor.putFloat(key, value)
        else if (value is String) editor.putString(key, value)
        else if (value is Set<*>) editor.putStringSet(key, value.filterIsInstance<String>().toSet())
    }

    private fun writeEvidence(context: Context, results: List<MatrixResult>, failure: Throwable?) {
        val rows = JSONArray().apply { results.forEach { put(it.toJson()) } }
        val report = JSONObject()
            .put("schema", "neo-qa-lq015-settings-fail-closed/v1")
            .put("checkpoint", "LQ-015")
            .put("canonicalCheckpoint", "CP-099")
            .put("capturedUtcMs", System.currentTimeMillis())
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("api", Build.VERSION.SDK_INT))
            .put("expectedRows", EXPECTED_MATRIX_ROWS)
            .put("measuredRows", results.size)
            .put("passed", failure == null && results.size == EXPECTED_MATRIX_ROWS && results.all { it.passed })
            .put("failure", failure?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL)
            .put("results", rows)
        val directory = evidenceDirectory(context)
        val temporary = File(directory, "matrix.json.tmp")
        val destination = File(directory, "matrix.json")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        if (destination.exists()) destination.delete()
        assertTrue("Could not atomically publish LQ-015 evidence", temporary.renameTo(destination))
    }

    private fun evidenceDirectory(context: Context): File =
        requireNotNull(context.getExternalFilesDir(EVIDENCE_DIRECTORY)).apply { mkdirs() }

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

    private data class RuntimeSnapshot(
        val cinemaMode: String,
        val rawEnabled: Boolean,
        val logEnabled: Boolean,
        val hdrMode: String,
        val rawCompression: String,
        val recordingFps: Int,
        val encoderFps: Int,
        val recordingCodec: String,
        val encoderCodec: String,
        val encodedTenBit: Boolean,
        val width: Int,
        val height: Int,
        val bitrateMode: String,
        val requestedBitrate: Int,
        val sessionGeneration: Long,
        val cameraState: String
    )

    private data class MatrixResult(
        val id: String,
        val expectation: String,
        val expectedRule: String?,
        val observedRule: String?,
        val message: String?,
        val stateContractMet: Boolean,
        val preferencesContractMet: Boolean,
        val sessionContractMet: Boolean,
        val feedbackContractMet: Boolean,
        val cameraHealthy: Boolean,
        val passed: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("expectation", expectation)
            .put("expectedRule", expectedRule ?: JSONObject.NULL)
            .put("observedRule", observedRule ?: JSONObject.NULL)
            .put("message", message ?: JSONObject.NULL)
            .put("stateContractMet", stateContractMet)
            .put("preferencesContractMet", preferencesContractMet)
            .put("sessionContractMet", sessionContractMet)
            .put("feedbackContractMet", feedbackContractMet)
            .put("cameraHealthy", cameraHealthy)
            .put("passed", passed)
    }

    private companion object {
        const val EXPECTED_MATRIX_ROWS = 19
        const val EVIDENCE_DIRECTORY = "neo-qa/lq015"
        const val SESSION_TIMEOUT_MS = 25_000L
        const val UI_TIMEOUT_MS = 5_000L
        const val REJECTION_TIMEOUT_MS = 2_000L
        const val REJECTION_SETTLE_MS = 250L
        const val GENERATION_STABILITY_MS = 1_500L
        const val POLL_MS = 25L
    }
}
