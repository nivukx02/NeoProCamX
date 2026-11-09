package com.neoproc.neoproccamx.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.ManualVideoColorSelectionPolicy
import com.neoproc.neoproccamx.recording.RecordingCodecSupportProbe
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalog
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Physical-device proof for LQ-017 / CP-107..110.
 *
 * The test deliberately distinguishes a requested setting from an applied hardware setting. An
 * unsupported resolution/FPS/bitrate is allowed only when it is rejected or visibly capability
 * capped; silently relabelling a different output as the user's selection is a failure.
 */
@RunWith(AndroidJUnit4::class)
class Lq017RecordingSettingsInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun manualColor_bitrate_andResolutionFps_areTruthfulOnPhysicalHardware() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalPreferences = snapshotPreferences(context)
        val preferenceBackup = writePreferenceBackup(context, originalPreferences)
        val stages = mutableListOf<JSONObject>()
        val testStartedElapsedMs = SystemClock.elapsedRealtime()
        val colorSpaceResults = mutableListOf<JSONObject>()
        val colorScienceResults = mutableListOf<JSONObject>()
        val matrixResults = mutableListOf<JSONObject>()
        val failures = mutableListOf<String>()
        var bitrateResult: JSONObject? = null
        var photoGuardResult: JSONObject? = null
        var encoderProbeResult: JSONObject? = null
        var failure: Throwable? = null
        var scenario: ActivityScenario<MainActivity>? = null

        markStage(
            stages,
            testStartedElapsedMs,
            "PREFERENCES_BACKED_UP",
            preferenceBackup.absolutePath
        )

        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val vm = readVm(scenario)
            markStage(stages, testStartedElapsedMs, "ACTIVITY_LAUNCHED")

            // Do not mutate mode, colour, resolution or cadence while Camera2 is still bootstrapping.
            // The first LQ-017 run proved that doing so can race activity teardown and camera-handler
            // creation. Establish a live, generation-stable owner before the first settings commit.
            awaitStreaming(vm)
            awaitStableCameraSession(vm)
            markStage(stages, testStartedElapsedMs, "INITIAL_STREAM_STABLE")

            scenario.onActivity { configureStableBaseline(it.vm) }
            awaitStreaming(vm)
            awaitStableCameraSession(vm)
            markStage(stages, testStartedElapsedMs, "BASELINE_STREAM_STABLE")

            val preferences = PreferencesManager(context)
            val photoQualityBefore = preferences.jpegQuality
            val maximumPhotoBefore = preferences.maxPhotoResolutionEnabled

            SettingsOptionCatalog.videoColorSpaceChoices.forEach { choice ->
                    val expected = ManualVideoColorSelectionPolicy.normalizeColorSpace(choice.value)
                    scenario.onActivity { it.vm.setVideoColorSpace(choice.value) }
                    val reached = awaitCondition(COLOR_TIMEOUT_MS) {
                        vm.currentVideoColorSpace == expected &&
                            preferences.videoColorSpace == expected &&
                            preferences.videoColorSpaceManuallySelected
                    }
                    val runtimeValue = vm.gpuRecording.videoColorSpace
                    val passed = reached && runtimeValue == expected
                    colorSpaceResults += JSONObject()
                        .put("label", choice.label)
                        .put("requested", choice.value)
                        .put("expected", expected)
                        .put("selected", vm.currentVideoColorSpace)
                        .put("persisted", preferences.videoColorSpace)
                        .put("runtime", runtimeValue)
                        .put("manualApproval", preferences.videoColorSpaceManuallySelected)
                        .put("passed", passed)
                    if (!passed) failures += "Color space ${choice.label} did not reach selection, persistence and recorder"
            }
            markStage(stages, testStartedElapsedMs, "COLOR_SPACES_CHARACTERIZED")

            // Reset transport intent between profile selections so each option is tested from the
            // same deterministic SDR/Pro baseline rather than inheriting the prior option's mode.
            SettingsOptionCatalog.videoColorProfileChoices.forEach { choice ->
                    scenario.onActivity {
                        it.vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                        it.vm.setLogEnabled(false)
                        it.vm.setVideoHdrMode("OFF")
                        it.vm.setColorProfile(choice.value)
                    }
                    val expected = ManualVideoColorSelectionPolicy.normalizeColorScience(choice.value)
                    val reached = awaitCondition(COLOR_TIMEOUT_MS) {
                        equivalentColorScience(vm.currentColorProfile, expected) &&
                            equivalentColorScience(preferences.colorProfile, expected) &&
                            preferences.videoColorScienceManuallySelected
                    }
                    val runtimeValue = vm.gpuRecording.videoColorProfile
                    val runtimeCompatible = equivalentColorScience(runtimeValue, expected)
                    val passed = reached && runtimeCompatible
                    colorScienceResults += JSONObject()
                        .put("label", choice.label)
                        .put("requested", choice.value)
                        .put("expected", expected)
                        .put("selected", vm.currentColorProfile)
                        .put("persisted", preferences.colorProfile)
                        .put("runtime", runtimeValue)
                        .put("manualApproval", preferences.videoColorScienceManuallySelected)
                        .put("passed", passed)
                    if (!passed) failures += "Color science ${choice.label} did not reach selection, persistence and recorder"
            }
            markStage(stages, testStartedElapsedMs, "COLOR_SCIENCES_CHARACTERIZED")

            scenario.onActivity { configureStableBaseline(it.vm) }
            awaitStreaming(vm)
            awaitStableCameraSession(vm)
            markStage(stages, testStartedElapsedMs, "MATRIX_BASELINE_STABLE")

            val dimensions = SettingsOptionCatalog.resolutionChoicesForMode(
                mode = CinemaVideoMode.PRO,
                supportedOutputDimensions = vm.availableVideoDimensionsForSettings()
            )
            val exactPairs = mutableListOf<Pair<Size, Int>>()

            dimensions.forEach { resolutionChoice ->
                    val size = resolutionChoice.value
                    val exactFps = SettingsOptionCatalog.fpsChoices
                        .map { it.value }
                        .filter { fps -> exactMain10Support(vm, size, fps) }
                    if (exactFps.isEmpty()) {
                        failures += "${resolutionChoice.label} is advertised without any exact Main10 camera/encoder cadence"
                        matrixResults += JSONObject()
                            .put("resolutionLabel", resolutionChoice.label)
                            .put("width", size.width)
                            .put("height", size.height)
                            .put("passed", false)
                            .put("reason", "NO_EXACT_MAIN10_CADENCE")
                        return@forEach
                    }

                    val transitionFps = exactFps.first()
                    scenario.onActivity {
                        it.vm.dismissSettingsRejection()
                        it.vm.setFPS(transitionFps)
                        it.vm.setVideoResolution(size)
                    }
                    awaitCondition(SETTING_TIMEOUT_MS) {
                        (vm.gpuRecording.videoWidth == size.width && vm.gpuRecording.videoHeight == size.height) ||
                            vm.settingsRejection.value != null
                    }

                    SettingsOptionCatalog.fpsChoices.forEach { fpsChoice ->
                        val fps = fpsChoice.value
                        val exactSupported = exactMain10Support(vm, size, fps)
                        val previousFps = vm.recording.fps
                        val previousSequence = vm.settingsRejection.value?.sequence ?: 0L
                        scenario.onActivity {
                            it.vm.dismissSettingsRejection()
                            it.vm.setFPS(fps)
                        }
                        val observed = awaitCondition(SETTING_TIMEOUT_MS) {
                            if (exactSupported) {
                                vm.recording.fps == fps && vm.gpuRecording.videoFps == fps
                            } else {
                                vm.settingsRejection.value?.sequence?.let { it > previousSequence } == true
                            }
                        }
                        val rejection = vm.settingsRejection.value
                        val exactGeometry = vm.gpuRecording.videoWidth == size.width &&
                            vm.gpuRecording.videoHeight == size.height
                        val statePreservedOnRejection = exactSupported || vm.recording.fps == previousFps
                        val passed = observed && exactGeometry && statePreservedOnRejection
                        matrixResults += JSONObject()
                            .put("resolutionLabel", resolutionChoice.label)
                            .put("width", size.width)
                            .put("height", size.height)
                            .put("requestedFps", fps)
                            .put("exactMain10Supported", exactSupported)
                            .put("appliedWidth", vm.gpuRecording.videoWidth)
                            .put("appliedHeight", vm.gpuRecording.videoHeight)
                            .put("appliedFps", vm.recording.fps)
                            .put("rejectionRule", rejection?.ruleId ?: JSONObject.NULL)
                            .put("statePreservedOnRejection", statePreservedOnRejection)
                            .put("passed", passed)
                        if (!passed) {
                            failures += "${resolutionChoice.label} ${fps}fps was neither applied exactly nor rejected atomically"
                        }
                        if (exactSupported) {
                            exactPairs += size to fps
                        } else if (vm.recording.fps != transitionFps) {
                            scenario.onActivity { it.vm.setFPS(transitionFps) }
                            awaitCondition(SETTING_TIMEOUT_MS) { vm.recording.fps == transitionFps }
                        }
                    }
            }
            markStage(stages, testStartedElapsedMs, "RESOLUTION_FPS_MATRIX_CHARACTERIZED")

            val bitratePair = exactPairs.firstOrNull { (size, fps) ->
                    size.width == 3840 && size.height == 2160 && fps == 30
                } ?: exactPairs.firstOrNull()
                if (bitratePair == null) {
                    failures += "No exact Main10 size/FPS pair was available for bitrate enforcement"
                } else {
                    val (size, fps) = bitratePair
                    scenario.onActivity {
                        it.vm.setFPS(fps)
                        it.vm.setVideoResolution(size)
                    }
                    awaitCondition(SETTING_TIMEOUT_MS) {
                        vm.gpuRecording.videoWidth == size.width &&
                            vm.gpuRecording.videoHeight == size.height &&
                            vm.recording.fps == fps
                    }

                    val bounds = main10HardwareBitrateBounds(size, fps)
                    val encoderUpper = bounds?.second
                    if (bounds == null || encoderUpper == null || encoderUpper <= 0) {
                        failures += "No rate-controlled hardware Main10/P010 bitrate range for ${size.width}x${size.height}@$fps"
                    } else {
                        val withinTarget = SettingsOptionCatalog.bitrateStepsMbps
                            .map { it * 1_000_000 }
                            .filter { it in bounds.first..encoderUpper }
                            .maxOrNull() ?: encoderUpper
                        scenario.onActivity {
                            it.vm.setVideoBitrateMode(VideoBitrateMode.CUSTOM)
                            it.vm.setVideoBitrate(withinTarget)
                        }
                        val withinRequested = vm.requestedVideoBitrateBitsPerSecond()
                        val withinApplied = vm.gpuRecording.videoBitrate
                        val aboveTarget = (encoderUpper.toLong() + 40_000_000L)
                            .coerceAtMost(2_000_000_000L)
                            .toInt()
                        scenario.onActivity { it.vm.setVideoBitrate(aboveTarget) }
                        val aboveRequested = vm.requestedVideoBitrateBitsPerSecond()
                        val aboveApplied = vm.gpuRecording.videoBitrate
                        scenario.onActivity { it.vm.setVideoBitrateMode(VideoBitrateMode.CINEMA_MASTER) }
                        val masterRequested = vm.requestedVideoBitrateBitsPerSecond()
                        val masterApplied = vm.gpuRecording.videoBitrate
                        val passed = withinRequested == withinTarget &&
                            withinApplied == withinTarget &&
                            aboveRequested == aboveTarget &&
                            aboveApplied == encoderUpper &&
                            masterApplied <= encoderUpper &&
                            masterApplied >= bounds.first &&
                            masterRequested >= masterApplied
                        bitrateResult = JSONObject()
                            .put("width", size.width)
                            .put("height", size.height)
                            .put("fps", fps)
                            .put("encoderLowerBps", bounds.first)
                            .put("encoderUpperBps", encoderUpper)
                            .put("withinRequestedBps", withinRequested)
                            .put("withinAppliedBps", withinApplied)
                            .put("aboveRequestedBps", aboveRequested)
                            .put("aboveAppliedBps", aboveApplied)
                            .put("cinemaMasterRequestedBps", masterRequested)
                            .put("cinemaMasterAppliedBps", masterApplied)
                            .put("passed", passed)
                        if (!passed) failures += "Bitrate request/application evidence was not capability-truthful"
                        val probe = configureMain10P010Encoder(size, fps, withinTarget)
                        encoderProbeResult = probe
                        if (!probe.optBoolean("passed")) {
                            failures += "Main10/P010 encoder configure/start probe failed at the admitted bitrate"
                        }
                    }
            }
            markStage(stages, testStartedElapsedMs, "BITRATE_CHARACTERIZED")

            val photoQualityAfter = preferences.jpegQuality
            val maximumPhotoAfter = preferences.maxPhotoResolutionEnabled
            val photoPassed = photoQualityAfter == photoQualityBefore &&
                maximumPhotoAfter == maximumPhotoBefore &&
                photoQualityAfter >= 95
            photoGuardResult = JSONObject()
                .put("jpegQualityBefore", photoQualityBefore)
                .put("jpegQualityAfter", photoQualityAfter)
                .put("maximumSensorBefore", maximumPhotoBefore)
                .put("maximumSensorAfter", maximumPhotoAfter)
                .put("passed", photoPassed)
            if (!photoPassed) failures += "Video settings changed or reduced photo-quality state"

            captureScreenshot(context, "lq017-final-settings-state.png")
            markStage(stages, testStartedElapsedMs, "FINAL_STATE_CAPTURED")
            if (failures.isNotEmpty()) throw AssertionError(failures.joinToString(separator = " | "))
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            val executionFailed = failure != null
            var cleanupFailure: Throwable? = null
            runCatching {
                restorePreferences(context, originalPreferences)
                markStage(stages, testStartedElapsedMs, "PREFERENCES_RESTORED")
            }.onFailure {
                if (cleanupFailure == null) cleanupFailure = it
                if (failure == null) failure = it
                Log.e(TAG, "LQ017 preference restoration failed", it)
            }
            runCatching {
                markStage(stages, testStartedElapsedMs, "EVIDENCE_PUBLICATION_STARTED")
                writeEvidence(
                    context = context,
                    colorSpaces = colorSpaceResults,
                    colorSciences = colorScienceResults,
                    matrix = matrixResults,
                    bitrate = bitrateResult,
                    photoGuard = photoGuardResult,
                    encoderProbe = encoderProbeResult,
                    stages = stages,
                    failure = failure
                )
            }.onFailure {
                if (cleanupFailure == null) cleanupFailure = it
                Log.e(TAG, "LQ017 evidence publication failed", it)
            }
            runCatching { scenario?.close() }.onFailure {
                if (cleanupFailure == null) cleanupFailure = it
                Log.e(TAG, "LQ017 activity close failed", it)
            }
            if (!executionFailed) cleanupFailure?.let { throw it }
        }
    }

    private fun configureStableBaseline(vm: CameraViewModel) {
        vm.setMode(AppMode.VIDEO)
        vm.setRawEnabled(false)
        vm.setCinemaVideoMode(CinemaVideoMode.PRO)
        vm.setVideoHdrMode("OFF")
        vm.setVideoCodec(VideoCodecType.HEVC)
        vm.setTenBitRecordingEnabled(true)
        vm.setVideoColorSpace("Rec.709")
        vm.setColorProfile("Natural")
    }

    private fun exactMain10Support(
        vm: CameraViewModel,
        size: Size,
        fps: Int
    ): Boolean {
        return vm.camera.supportsExactRecordingFps(size, fps) &&
            RecordingCodecSupportProbe.supportsHevcMain10SizeAndRate(
            width = size.width,
            height = size.height,
            fps = fps
        )
    }

    private fun equivalentColorScience(actual: String?, expected: String?): Boolean =
        ManualVideoColorSelectionPolicy.normalizeColorScience(actual) ==
            ManualVideoColorSelectionPolicy.normalizeColorScience(expected)

    private fun main10HardwareBitrateBounds(size: Size, fps: Int): Pair<Int, Int>? {
        val profiles = setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        )
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } }
            .filter { runCatching { it.isHardwareAccelerated && !it.isSoftwareOnly }.getOrDefault(true) }
            .mapNotNull { info ->
                runCatching {
                    val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    val video = capabilities.videoCapabilities ?: return@runCatching null
                    val main10 = capabilities.profileLevels.any { it.profile in profiles }
                    val p010 = capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
                    val rateControl = capabilities.encoderCapabilities?.let { encoder ->
                        encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ||
                            encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    } == true
                    if (main10 && p010 && rateControl &&
                        video.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
                    ) {
                        video.bitrateRange.lower to video.bitrateRange.upper
                    } else null
                }.getOrNull()
            }
            .filterNotNull()
            .maxByOrNull { it.second }
    }

    private fun configureMain10P010Encoder(size: Size, fps: Int, bitrate: Int): JSONObject {
        var codec: MediaCodec? = null
        var started = false
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format)
                ?: throw IllegalStateException("No Main10/P010 encoder accepted the admitted format")
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
            JSONObject()
                .put("codec", codecName)
                .put("width", size.width)
                .put("height", size.height)
                .put("fps", fps)
                .put("bitrateBps", bitrate)
                .put("profile", "HEVCProfileMain10")
                .put("colorFormat", "COLOR_FormatYUVP010")
                .put("passed", true)
        } catch (t: Throwable) {
            JSONObject()
                .put("width", size.width)
                .put("height", size.height)
                .put("fps", fps)
                .put("bitrateBps", bitrate)
                .put("passed", false)
                .put("failure", "${t.javaClass.name}: ${t.message}")
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
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

    private fun captureScreenshot(context: Context, name: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("Unable to capture LQ-017 screenshot")
        File(evidenceDirectory(context), name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun writeEvidence(
        context: Context,
        colorSpaces: List<JSONObject>,
        colorSciences: List<JSONObject>,
        matrix: List<JSONObject>,
        bitrate: JSONObject?,
        photoGuard: JSONObject?,
        encoderProbe: JSONObject?,
        stages: List<JSONObject>,
        failure: Throwable?
    ) {
        val sourceApk = File(context.applicationInfo.sourceDir)
        val passed = failure == null &&
            colorSpaces.isNotEmpty() && colorSpaces.all { it.optBoolean("passed") } &&
            colorSciences.isNotEmpty() && colorSciences.all { it.optBoolean("passed") } &&
            matrix.isNotEmpty() && matrix.all { it.optBoolean("passed") } &&
            bitrate?.optBoolean("passed") == true &&
            photoGuard?.optBoolean("passed") == true &&
            encoderProbe?.optBoolean("passed") == true
        val report = JSONObject()
            .put("schema", "neo-qa-lq017-recording-settings/v1")
            .put("checkpoint", "LQ-017")
            .put("canonicalCheckpoints", JSONArray(listOf("CP-107", "CP-108", "CP-109", "CP-110")))
            .put("capturedUtcMs", System.currentTimeMillis())
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("api", Build.VERSION.SDK_INT))
            .put("apkSha256", sha256(sourceApk))
            .put("colorSpaces", JSONArray(colorSpaces))
            .put("colorSciences", JSONArray(colorSciences))
            .put("resolutionFpsMatrix", JSONArray(matrix))
            .put("bitrate", bitrate ?: JSONObject.NULL)
            .put("main10P010ConfigureProbe", encoderProbe ?: JSONObject.NULL)
            .put("photoQualityNonDowngrade", photoGuard ?: JSONObject.NULL)
            .put("stages", JSONArray(stages))
            .put("physicalImageQualityQualification", "REQUIRES_CONTROLLED_CHART_AND_MOTION_RIG")
            .put("imaxEquivalence", "NOT_CLAIMED_WITHOUT_STANDARDIZED_LAB_MEASUREMENT")
            .put("passed", passed)
            .put("failure", failure?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL)
        val directory = evidenceDirectory(context)
        val temporary = File(directory, "lq017.json.tmp")
        val destination = File(directory, "lq017.json")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        if (destination.exists()) destination.delete()
        check(temporary.renameTo(destination)) { "Could not atomically publish LQ-017 evidence" }
    }

    private fun evidenceDirectory(context: Context): File =
        requireNotNull(context.getExternalFilesDir(EVIDENCE_DIRECTORY)).apply { mkdirs() }

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

    private fun readVm(scenario: ActivityScenario<MainActivity>): CameraViewModel {
        val value = AtomicReference<CameraViewModel>()
        scenario.onActivity { value.set(it.vm) }
        return value.get()
    }

    private fun snapshotPreferences(context: Context): Map<String, Any?> =
        context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE).all
            .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }

    private fun writePreferenceBackup(context: Context, values: Map<String, Any?>): File {
        val entries = JSONArray()
        values.toSortedMap().forEach { (key, value) ->
            val entry = JSONObject().put("key", key)
            when (value) {
                is Boolean -> entry.put("type", "boolean").put("value", value)
                is Int -> entry.put("type", "int").put("value", value)
                is Long -> entry.put("type", "long").put("value", value)
                is Float -> entry.put("type", "float").put("value", value.toDouble())
                is String -> entry.put("type", "string").put("value", value)
                is Set<*> -> entry.put("type", "string_set")
                    .put("value", JSONArray(value.filterIsInstance<String>().sorted()))
                null -> entry.put("type", "null").put("value", JSONObject.NULL)
                else -> error("Unsupported preference type for $key: ${value.javaClass.name}")
            }
            entries.put(entry)
        }
        val document = JSONObject()
            .put("schema", "neo-qa-lq017-preference-backup/v1")
            .put("capturedUtcMs", System.currentTimeMillis())
            .put("entries", entries)
        val directory = evidenceDirectory(context)
        val temporary = File(directory, "preferences-before.json.tmp")
        val destination = File(directory, "preferences-before.json")
        temporary.writeText(document.toString(2), Charsets.UTF_8)
        if (destination.exists()) check(destination.delete()) {
            "Could not replace prior LQ-017 preference backup"
        }
        check(temporary.renameTo(destination)) { "Could not publish LQ-017 preference backup" }
        return destination
    }

    private fun markStage(
        stages: MutableList<JSONObject>,
        startedElapsedMs: Long,
        name: String,
        detail: String? = null
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - startedElapsedMs
        stages += JSONObject()
            .put("name", name)
            .put("elapsedMs", elapsedMs)
            .put("detail", detail ?: JSONObject.NULL)
        Log.i(TAG, "LQ017_STAGE name=$name elapsedMs=$elapsedMs detail=${detail.orEmpty()}")
    }

    private fun restorePreferences(context: Context, values: Map<String, Any?>) {
        val editor = context.getSharedPreferences(PreferencesManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear()
        values.forEach { (key, value) -> putPreference(editor, key, value) }
        check(editor.commit()) { "Could not restore preferences after LQ-017" }
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (runCatching(condition).getOrDefault(false)) return true
            SystemClock.sleep(POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return runCatching(condition).getOrDefault(false)
    }

    private companion object {
        const val EVIDENCE_DIRECTORY = "neo-qa/lq017"
        const val TAG = "NeoQaLq017"
        const val COLOR_TIMEOUT_MS = 8_000L
        const val SETTING_TIMEOUT_MS = 12_000L
        const val SESSION_TIMEOUT_MS = 45_000L
        const val SESSION_STABLE_MS = 1_200L
        const val POLL_MS = 25L
    }
}
