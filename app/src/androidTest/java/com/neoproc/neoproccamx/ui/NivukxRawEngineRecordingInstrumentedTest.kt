package com.neoproc.neoproccamx.ui

import android.Manifest
import android.os.SystemClock
import android.util.Size
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.GpuVideoCodec
import com.neoproc.neoproccamx.recording.Npv10ContainerPolicy
import com.neoproc.neoproccamx.recording.ProjectNdrRecordedArtifactVerifier
import com.neoproc.neoproccamx.rendering.EglDecodeOnceFlag
import com.neoproc.neoproccamx.rendering.EglGpuStageTimingFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Independent live-device checkpoint for the NIVUKX Vision sensor-linear record route. */
@RunWith(AndroidJUnit4::class)
class NivukxRawEngineRecordingInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun nivukxVision_recordsRawEngineAcescgAsBt2020PqMain10WithoutNpv10() {
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString(EGL_DECODE_ONCE_ARGUMENT)
            ?.let { rawOverride ->
                require(rawOverride == "true" || rawOverride == "false") {
                    "$EGL_DECODE_ONCE_ARGUMENT must be true or false"
                }
                EglDecodeOnceFlag.set(rawOverride.toBoolean())
            }
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString(EGL_GPU_STAGE_TIMING_ARGUMENT)
            ?.let { rawOverride ->
                require(rawOverride == "true" || rawOverride == "false") {
                    "$EGL_GPU_STAGE_TIMING_ARGUMENT must be true or false"
                }
                EglGpuStageTimingFlag.set(rawOverride.toBoolean())
            }
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
        val npv10Directory = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val npv10Before = npv10Directory.listFiles().orEmpty().map(File::getName).toSet()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            lateinit var expectedOutputSize: Size
            scenario.onActivity { activity -> vm = activity.vm }
            awaitCameraStreaming(vm)

            scenario.onActivity {
                vm.setMode(AppMode.VIDEO)
                vm.setRawEnabled(false)
                vm.setLogEnabled(false)
                vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                vm.setSdrQualityIntent(SdrQualityIntent.PRO)
                vm.setFPS(TARGET_FPS)
                vm.setVideoHdrMode(NIVUKX_HDR_MODE)
                vm.setVideoColorSpace(NIVUKX_COLOR_SPACE)
                vm.setColorProfile(NIVUKX_PROFILE)
                vm.setTenBitRecordingEnabled(true)
                vm.setLutEnabled(false)
                vm.setDebugValidationLabel(VALIDATION_LABEL)
                expectedOutputSize = selectLargestExactHevcOutput(vm)
                vm.setVideoResolution(expectedOutputSize)
            }

            awaitModeSelection(vm, expectedOutputSize)
            assertFalse(
                "NIVUKX Vision incorrectly selected the vendor Camera2 P010/NPV10 master route",
                vm.usesNpv10CapturePipelineForCurrentMode()
            )
            awaitFreshCameraSession(scenario, vm)
            awaitCondition(30_000L, "NIVUKX full-resolution output changed during session refresh") {
                outputConfigurationMatches(vm, expectedOutputSize)
            }

            scenario.onActivity { vm.startRecording() }
            awaitRecording(vm)
            assertOutputConfiguration(vm, expectedOutputSize, "active recording")
            assertEquals(
                "NIVUKX Vision did not activate the project-owned GPU recording transport",
                "AHB_GPU_COMPATIBILITY",
                vm.activeRecordingTransportHudLabel()
            )
            SystemClock.sleep(RECORDING_DURATION_MS)
            scenario.onActivity { vm.stopRecording() }
            awaitNotRecording(vm)

            val outputPath = requireNotNull(vm.gpuRecording.lastOutputPath) {
                "NIVUKX Vision produced no finalized MOV output path"
            }
            val clip = ProjectNdrRecordedArtifactVerifier.publishedClipForOutputPath(
                context,
                outputPath
            )
            val certificate = ProjectNdrRecordedArtifactVerifier.awaitCertificate(
                context = context,
                recordedFileName = clip.displayName,
                expectedProfile = NIVUKX_PROFILE
            )
            ProjectNdrRecordedArtifactVerifier.verify(
                context = context,
                certificate = certificate,
                clip = clip,
                expectedProfile = NIVUKX_PROFILE,
                expectedValidationLabel = VALIDATION_LABEL,
                expectedWidth = expectedOutputSize.width,
                expectedHeight = expectedOutputSize.height,
                minimumFrameCount = MINIMUM_VERIFIED_FRAMES
            )

            val unexpectedNpv10 = npv10Directory.listFiles().orEmpty().filter { candidate ->
                candidate.name !in npv10Before
            }
            assertTrue(
                "NIVUKX Vision created an NPV10 private master: ${unexpectedNpv10.joinToString { it.name }}",
                unexpectedNpv10.isEmpty()
            )
            awaitCameraStreaming(vm)
        }
    }

    private fun selectLargestExactHevcOutput(vm: CameraViewModel): Size {
        val advertised = (vm.camera.recordingSizes + vm.camera.videoSizes)
            .map { size ->
                if (size.width >= size.height) size else Size(size.height, size.width)
            }
            .distinctBy { size -> size.width to size.height }
            .sortedByDescending { size -> size.width.toLong() * size.height.toLong() }
        assertTrue("Device exposes no camera-backed recording sizes", advertised.isNotEmpty())

        // Keep codec resolution honest: a candidate is eligible only when the exact camera-backed
        // dimensions are also accepted by the device's HEVC size/rate capabilities at the test FPS.
        vm.gpuRecording.setCameraSupportedRecordingSizes(advertised)
        val selected = advertised.firstOrNull { candidate ->
            vm.gpuRecording.resolveSupportedSize(candidate, GpuVideoCodec.HEVC, TARGET_FPS) == candidate
        } ?: throw AssertionError(
            "Device exposes no exact camera/HEVC intersection at ${TARGET_FPS}fps: " +
                advertised.joinToString { size -> "${size.width}x${size.height}" }
        )
        assertTrue(
            "NIVUKX full-resolution checkpoint requires native 4K or greater, selected " +
                "${selected.width}x${selected.height}",
            selected.width >= MINIMUM_FULL_RESOLUTION_WIDTH &&
                selected.height >= MINIMUM_FULL_RESOLUTION_HEIGHT
        )
        return selected
    }

    private fun outputConfigurationMatches(vm: CameraViewModel, expected: Size): Boolean =
        vm.recording.width == expected.width &&
            vm.recording.height == expected.height &&
            vm.gpuRecording.videoWidth == expected.width &&
            vm.gpuRecording.videoHeight == expected.height

    private fun awaitModeSelection(vm: CameraViewModel, expected: Size) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (modeSelectionMatches(vm, expected)) return
            SystemClock.sleep(100L)
        }
        throw AssertionError(
            "NIVUKX Vision mode selection did not settle: " +
                "uiMode=${vm.uiMode.value} expected=${AppMode.VIDEO}; " +
                "cinemaMode=${vm.cinemaVideoMode.value} expected=${CinemaVideoMode.PRO}; " +
                "raw=${vm.rawOnFlow.value} expected=false; " +
                "log=${vm.logEnabledFlow.value} expected=false; " +
                "hdr=${vm.videoHdrMode.value} expected=$NIVUKX_HDR_MODE; " +
                "colorSpace=${vm.videoColorSpace.value} expected=$NIVUKX_COLOR_SPACE; " +
                "profile=${vm.colorProfileFlow.value} expected=$NIVUKX_PROFILE; " +
                "uncompressed=${vm.uncompressedCinemaPipelineEnabled.value} expected=true; " +
                "recording=${vm.recording.width}x${vm.recording.height} expected=" +
                "${expected.width}x${expected.height}; " +
                "encoder=${vm.gpuRecording.videoWidth}x${vm.gpuRecording.videoHeight} expected=" +
                "${expected.width}x${expected.height}; " +
                "cameraState=${vm.cameraState.value}; " +
                "sessionRefreshPending=${vm.camera.sessionRefreshPending.get()}; " +
                "gpuState=${vm.gpuRecordState.value}"
        )
    }

    private fun modeSelectionMatches(vm: CameraViewModel, expected: Size): Boolean =
        vm.uiMode.value == AppMode.VIDEO &&
            vm.cinemaVideoMode.value == CinemaVideoMode.PRO &&
            !vm.rawOnFlow.value &&
            !vm.logEnabledFlow.value &&
            vm.videoHdrMode.value == NIVUKX_HDR_MODE &&
            vm.videoColorSpace.value == NIVUKX_COLOR_SPACE &&
            vm.colorProfileFlow.value == NIVUKX_PROFILE &&
            vm.uncompressedCinemaPipelineEnabled.value &&
            outputConfigurationMatches(vm, expected)

    private fun assertOutputConfiguration(vm: CameraViewModel, expected: Size, stage: String) {
        assertEquals("NIVUKX output width changed during $stage", expected.width, vm.recording.width)
        assertEquals("NIVUKX output height changed during $stage", expected.height, vm.recording.height)
        assertEquals("NIVUKX encoder width changed during $stage", expected.width, vm.gpuRecording.videoWidth)
        assertEquals("NIVUKX encoder height changed during $stage", expected.height, vm.gpuRecording.videoHeight)
    }

    private fun awaitFreshCameraSession(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel
    ) {
        awaitCameraStreaming(vm)
        awaitCondition(30_000L, "Previous NIVUKX session refresh did not settle") {
            !vm.camera.sessionRefreshPending.get()
        }
        val refreshed = CountDownLatch(1)
        scenario.onActivity {
            vm.camera.resetPreviewActivityTracking()
            vm.camera.refreshOutputsAndSession(
                rebuildReaders = true,
                forceRebuild = true
            ) { refreshed.countDown() }
        }
        assertTrue(
            "NIVUKX RAW_SENSOR camera-session rebuild did not complete",
            refreshed.await(30L, TimeUnit.SECONDS)
        )
        awaitCondition(10_000L, "NIVUKX camera session produced no fresh preview data") {
            vm.cameraState.value == com.neoproc.neoproccamx.camera.CameraState.Streaming &&
                vm.camera.hasRecentPreviewActivity()
        }
    }

    private fun awaitCameraStreaming(vm: CameraViewModel, timeoutMs: Long = 30_000L) =
        awaitCondition(timeoutMs, "Camera did not return to streaming: ${vm.cameraState.value}") {
            vm.cameraState.value == com.neoproc.neoproccamx.camera.CameraState.Streaming
        }

    private fun awaitRecording(vm: CameraViewModel) {
        val deadline = SystemClock.elapsedRealtime() + 45_000L
        var startObserved = false
        var lastState: GpuRecordingState = vm.gpuRecordState.value
        while (SystemClock.elapsedRealtime() < deadline) {
            lastState = vm.gpuRecordState.value
            when (lastState) {
                is GpuRecordingState.Recording -> return
                is GpuRecordingState.Starting -> startObserved = true
                is GpuRecordingState.Error -> throw AssertionError(lastState.message)
                is GpuRecordingState.Stopping -> if (startObserved) {
                    throw AssertionError(
                        "NIVUKX recording entered Stopping before Recording: " +
                            (vm.uiState.value.hardwareWarning ?: "no hardware warning")
                    )
                }
                is GpuRecordingState.Idle -> if (startObserved) {
                    throw AssertionError(
                        "NIVUKX recording returned to Idle before Recording: " +
                            (vm.uiState.value.hardwareWarning ?: "no hardware warning")
                    )
                }
                else -> Unit
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("NIVUKX recording did not start: $lastState")
    }

    private fun awaitNotRecording(vm: CameraViewModel) =
        awaitCondition(45_000L, "NIVUKX recording did not stop: ${vm.gpuRecordState.value}") {
            when (val state = vm.gpuRecordState.value) {
                is GpuRecordingState.Starting,
                is GpuRecordingState.Recording,
                is GpuRecordingState.Paused,
                is GpuRecordingState.Stopping -> false
                is GpuRecordingState.Error -> throw AssertionError(state.message)
                else -> true
            }
        }

    private fun awaitCondition(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var satisfied = condition()
        while (!satisfied && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100L)
            satisfied = condition()
        }
        assertTrue(message, satisfied)
    }

    private companion object {
        const val NIVUKX_HDR_MODE = "NIVUKXVISION"
        const val NIVUKX_COLOR_SPACE = "Rec.2020-NIVUKX"
        const val NIVUKX_PROFILE = "NIVUKX Vision"
        const val VALIDATION_LABEL = "e2e_nivukx_raw_engine"
        const val TARGET_FPS = 25
        const val RECORDING_DURATION_MS = 3_000L
        const val MINIMUM_VERIFIED_FRAMES = 75
        const val MINIMUM_FULL_RESOLUTION_WIDTH = 3_840
        const val MINIMUM_FULL_RESOLUTION_HEIGHT = 2_160
        const val EGL_DECODE_ONCE_ARGUMENT = "eglDecodeOnce"
        const val EGL_GPU_STAGE_TIMING_ARGUMENT = "eglGpuStageTiming"
    }
}
