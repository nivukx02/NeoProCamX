package com.neoproc.neoproccamx.ui

import android.Manifest
import android.os.SystemClock
import android.util.Size
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.Npv10ContainerPolicy
import com.neoproc.neoproccamx.recording.ProjectNdrRecordedArtifactVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Production checkpoint for the complete project-owned sensor-linear NIVUKX route.
 *
 * This deliberately refuses every resolution fallback. The finalized artifact must contain at
 * least 300 real HEVC Main10/PQ/BT.2020 samples at 4096x3072 and the stop-time certificate must
 * prove matching Camera2 -> processed preview -> encoder lineage with no runtime drops.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class NivukxFullResolutionSoakInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun fullResolutionNivukx_recordsAtLeast300StrictlyVerifiedFrames() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
        val npv10Directory = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val npv10Before = npv10Directory.listFiles().orEmpty().map(File::getName).toSet()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }
            awaitCameraStreaming(vm)

            val exactSize = Size(EXPECTED_WIDTH, EXPECTED_HEIGHT)
            scenario.onActivity {
                assertTrue(
                    "Device does not expose strict 4096x3072 recording: ${vm.camera.videoSizes.joinToString()}",
                    vm.camera.videoSizes.any { it == exactSize }
                )
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
                vm.setPreviewAspectRatio(PreviewAspectRatio.FOUR_THREE)
                // Resolution is intentionally the final geometry mutation. This prevents an older
                // asynchronous mode/aspect restore from becoming the test's effective selection.
                vm.setVideoResolution(exactSize)
                vm.setDebugValidationLabel(VALIDATION_LABEL)
            }

            awaitCondition(45_000L, "Strict 4096x3072 NIVUKX selection did not settle") {
                vm.uiMode.value == AppMode.VIDEO &&
                    vm.cinemaVideoMode.value == CinemaVideoMode.PRO &&
                    !vm.rawOnFlow.value &&
                    !vm.logEnabledFlow.value &&
                    vm.videoHdrMode.value == NIVUKX_HDR_MODE &&
                    vm.videoColorSpace.value == NIVUKX_COLOR_SPACE &&
                    vm.colorProfileFlow.value == NIVUKX_PROFILE &&
                    vm.uncompressedCinemaPipelineEnabled.value &&
                    vm.gpuRecording.videoWidth == EXPECTED_WIDTH &&
                    vm.gpuRecording.videoHeight == EXPECTED_HEIGHT
            }
            assertFalse(
                "NIVUKX Vision incorrectly selected the vendor Camera2 P010/NPV10 master route",
                vm.usesNpv10CapturePipelineForCurrentMode()
            )
            awaitFreshCameraSession(scenario, vm)

            scenario.onActivity { vm.startRecording() }
            awaitRecording(vm)
            assertEquals(
                "NIVUKX Vision did not activate the project-owned GPU recording transport",
                "AHB_GPU_COMPATIBILITY",
                vm.activeRecordingTransportHudLabel()
            )
            SystemClock.sleep(RECORDING_DURATION_MS)
            scenario.onActivity { vm.stopRecording() }
            awaitNotRecording(vm)

            val outputPath = requireNotNull(vm.gpuRecording.lastOutputPath) {
                "Strict NIVUKX soak produced no finalized output path"
            }
            val clip = ProjectNdrRecordedArtifactVerifier.publishedClipForOutputPath(
                context = context,
                outputPath = outputPath
            )
            val certificate = ProjectNdrRecordedArtifactVerifier.awaitCertificate(
                context = context,
                recordedFileName = clip.displayName,
                expectedProfile = NIVUKX_PROFILE
            )
            val verification = ProjectNdrRecordedArtifactVerifier.verify(
                context = context,
                certificate = certificate,
                clip = clip,
                expectedProfile = NIVUKX_PROFILE,
                expectedValidationLabel = VALIDATION_LABEL,
                expectedWidth = EXPECTED_WIDTH,
                expectedHeight = EXPECTED_HEIGHT,
                minimumFrameCount = MINIMUM_VERIFIED_FRAMES
            )
            assertTrue(
                "Strict NIVUKX soak finalized only ${verification.frameCount} frames",
                verification.frameCount >= MINIMUM_VERIFIED_FRAMES
            )

            val unexpectedNpv10 = npv10Directory.listFiles().orEmpty().filter { candidate ->
                candidate.name !in npv10Before
            }
            assertTrue(
                "Strict NIVUKX soak created an NPV10 private master: " +
                    unexpectedNpv10.joinToString { it.name },
                unexpectedNpv10.isEmpty()
            )
            awaitCameraStreaming(vm)
        }
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
            "Strict NIVUKX RAW_SENSOR session rebuild did not complete",
            refreshed.await(45L, TimeUnit.SECONDS)
        )
        awaitCondition(15_000L, "Strict NIVUKX session produced no fresh preview data") {
            vm.cameraState.value == CameraState.Streaming && vm.camera.hasRecentPreviewActivity()
        }
        assertEquals(EXPECTED_WIDTH, vm.gpuRecording.videoWidth)
        assertEquals(EXPECTED_HEIGHT, vm.gpuRecording.videoHeight)
    }

    private fun awaitCameraStreaming(vm: CameraViewModel, timeoutMs: Long = 45_000L) =
        awaitCondition(timeoutMs, "Camera did not return to streaming: ${vm.cameraState.value}") {
            vm.cameraState.value == CameraState.Streaming
        }

    private fun awaitRecording(vm: CameraViewModel) {
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        var startObserved = false
        var lastState: GpuRecordingState = vm.gpuRecordState.value
        while (SystemClock.elapsedRealtime() < deadline) {
            lastState = vm.gpuRecordState.value
            when (lastState) {
                is GpuRecordingState.Recording -> return
                is GpuRecordingState.Starting -> startObserved = true
                is GpuRecordingState.Error -> throw AssertionError(lastState.message)
                is GpuRecordingState.Stopping -> if (startObserved) {
                    throw AssertionError("Strict NIVUKX recording stopped before becoming active")
                }
                is GpuRecordingState.Idle -> if (startObserved) {
                    throw AssertionError(
                        "Strict NIVUKX recording returned to Idle before becoming active: " +
                            (vm.uiState.value.hardwareWarning ?: "no hardware warning")
                    )
                }
                else -> Unit
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Strict NIVUKX recording did not start: $lastState")
    }

    private fun awaitNotRecording(vm: CameraViewModel) =
        awaitCondition(90_000L, "Strict NIVUKX recording did not stop: ${vm.gpuRecordState.value}") {
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
            SystemClock.sleep(POLL_INTERVAL_MS)
            satisfied = condition()
        }
        assertTrue(message, satisfied)
    }

    private companion object {
        const val NIVUKX_HDR_MODE = "NIVUKXVISION"
        const val NIVUKX_COLOR_SPACE = "Rec.2020-NIVUKX"
        const val NIVUKX_PROFILE = "NIVUKX Vision"
        const val VALIDATION_LABEL = "e2e_nivukx_4096x3072_soak"
        const val EXPECTED_WIDTH = 4096
        const val EXPECTED_HEIGHT = 3072
        const val TARGET_FPS = 25
        const val MINIMUM_VERIFIED_FRAMES = 300
        const val RECORDING_DURATION_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
