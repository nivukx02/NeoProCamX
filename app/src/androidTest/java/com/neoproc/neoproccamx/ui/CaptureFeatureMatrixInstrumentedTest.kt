package com.neoproc.neoproccamx.ui

import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureFeatureMatrixInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun proModeReselection_preservesEnabledUserLutIntent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }

            assertMatrixSettles(failureMessage = { "Pro mode selection erased enabled LUT intent" }) {
                var preserved = false
                scenario.onActivity {
                    vm.setMode(AppMode.VIDEO)
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setLutProfile("Neutral Film")
                    vm.setLutEnabled(true)
                    // Reapplying the selected mode exercises the exact state-loss branch without
                    // relying on a different mode's unrelated LOG/RAW constraints.
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    preserved = vm.cinemaVideoMode.value == CinemaVideoMode.PRO && vm.isLutEnabled
                }
                preserved
            }

            scenario.onActivity { vm.setLutEnabled(false) }
        }
    }

    @Test
    fun videoFeatureMatrix_appliesHdrRawAcrossAvailableLensesWithoutModeDrift() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity ->
                vm = activity.vm
            }

            // Exercise HDR and RAW as two valid, sequential production states. RAW+HDR is deliberately
            // rejected by the settings invariant, so asking for both at once would test the obsolete
            // implicit-downgrade behaviour instead of the fail-closed contract proven by LQ-015.
            // switchFacing() re-applies preferences on a background coroutine, so a single set-then-
            // assert races that reset; instead we set-and-verify, and the caller polls until it settles.
            var hdrRawFailure = "HDR/RAW matrix was not evaluated"
            fun tryHdrState(): Boolean {
                var ok = false
                scenario.onActivity {
                    vm.setMode(AppMode.VIDEO)
                    vm.setPreviewAspectRatio(PreviewAspectRatio.AUTO)
                    vm.setRawEnabled(false)
                    vm.setVideoHdrEnabled(true)
                    ok = vm.uiMode.value == AppMode.VIDEO &&
                        vm.previewAspect.value == PreviewAspectRatio.AUTO &&
                        vm.videoHdrEnabledFlow.value &&
                        vm.videoHdrMode.value != "OFF" &&
                        !vm.rawOnFlow.value &&
                        vm.cinemaVideoMode.value == CinemaVideoMode.PRO
                    if (!ok) {
                        hdrRawFailure = "HDR state did not settle: mode=${vm.uiMode.value} " +
                            "aspect=${vm.previewAspect.value} hdr=${vm.videoHdrMode.value} " +
                            "hdrEnabled=${vm.videoHdrEnabledFlow.value} raw=${vm.rawOnFlow.value} " +
                            "cinema=${vm.cinemaVideoMode.value}"
                    }
                }
                return ok
            }

            fun tryHdrDisabledState(): Boolean {
                var ok = false
                scenario.onActivity {
                    vm.setVideoHdrEnabled(false)
                    ok = !vm.videoHdrEnabledFlow.value &&
                        vm.videoHdrMode.value == "OFF" &&
                        !vm.rawOnFlow.value
                    if (!ok) {
                        hdrRawFailure = "HDR disable did not settle before RAW admission: " +
                            "hdr=${vm.videoHdrMode.value} hdrEnabled=${vm.videoHdrEnabledFlow.value} " +
                            "raw=${vm.rawOnFlow.value} cinema=${vm.cinemaVideoMode.value}"
                    }
                }
                return ok
            }

            fun tryRawState(): Boolean {
                var ok = false
                scenario.onActivity {
                    // Reassert OFF while polling so a delayed preference replay cannot reintroduce
                    // HDR between the disable acknowledgement and RAW admission.
                    vm.setVideoHdrEnabled(false)
                    vm.setRawEnabled(true)
                    ok = if (vm.camera.supportsRaw) {
                        vm.uiMode.value == AppMode.VIDEO &&
                            vm.previewAspect.value == PreviewAspectRatio.AUTO &&
                            !vm.videoHdrEnabledFlow.value &&
                            vm.videoHdrMode.value == "OFF" &&
                            vm.rawOnFlow.value &&
                            vm.cinemaVideoMode.value == CinemaVideoMode.RAW
                    } else {
                        vm.uiMode.value == AppMode.VIDEO &&
                            vm.previewAspect.value == PreviewAspectRatio.AUTO &&
                            !vm.videoHdrEnabledFlow.value &&
                            vm.videoHdrMode.value == "OFF" &&
                            !vm.rawOnFlow.value &&
                            vm.cinemaVideoMode.value == CinemaVideoMode.PRO
                    }
                    if (!ok) {
                        hdrRawFailure = "RAW state did not settle: supportsRaw=${vm.camera.supportsRaw} " +
                            "mode=${vm.uiMode.value} aspect=${vm.previewAspect.value} " +
                            "hdr=${vm.videoHdrMode.value} hdrEnabled=${vm.videoHdrEnabledFlow.value} " +
                            "raw=${vm.rawOnFlow.value} cinema=${vm.cinemaVideoMode.value}"
                    }
                }
                return ok
            }

            fun verifyHdrThenRawMatrix() {
                assertMatrixSettles(failureMessage = { hdrRawFailure }) { tryHdrState() }
                assertMatrixSettles(failureMessage = { hdrRawFailure }) { tryHdrDisabledState() }
                assertMatrixSettles(failureMessage = { hdrRawFailure }) { tryRawState() }
            }

            verifyHdrThenRawMatrix()

            var hasFront = false
            scenario.onActivity {
                hasFront = vm.camera.hasCameraFacing(CameraCharacteristics.LENS_FACING_FRONT)
            }
            if (hasFront) {
                switchAndAwaitFacing(scenario, vm, CameraCharacteristics.LENS_FACING_FRONT)
                verifyHdrThenRawMatrix()
                switchAndAwaitFacing(scenario, vm, CameraCharacteristics.LENS_FACING_BACK)
            }

            scenario.onActivity {
                vm.setVideoHdrEnabled(false)
                vm.setRawEnabled(false)
            }
            assertMatrixSettles {
                var ok = false
                scenario.onActivity {
                    ok = !vm.videoHdrEnabledFlow.value && !vm.rawOnFlow.value
                }
                ok
            }
        }
    }

    @Test
    fun videoFeatureMatrix_appliesHdrLogLutAcrossAvailableLensesWithoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity ->
                vm = activity.vm
            }

            val hdrModes = listOf("OFF", "HLG", "HDR10", "HDR10+")
            var logLutFailure = "HDR/LOG/LUT matrix was not evaluated"

            fun tryLogLutMatrix(): Boolean {
                var ok = false
                scenario.onActivity {
                    vm.setMode(AppMode.VIDEO)
                    vm.setRawEnabled(false)
                    vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                    vm.setColorProfile("Rec.709")
                    vm.setLutProfile("Neutral Film")
                    vm.setPreviewAspectRatio(PreviewAspectRatio.AUTO)
                    ok = hdrModes.all { hdrMode ->
                        vm.setLogEnabled(hdrMode != "OFF")
                        vm.setLutEnabled(true)
                        vm.setVideoHdrMode(hdrMode)
                        val expectedHdrMode = if (hdrMode == "OFF") "OFF" else "NDR10+"
                        val matches = vm.uiMode.value == AppMode.VIDEO &&
                            vm.previewAspect.value == PreviewAspectRatio.AUTO &&
                            vm.videoHdrMode.value == expectedHdrMode &&
                            !vm.logEnabledFlow.value &&
                            vm.cinemaVideoMode.value == CinemaVideoMode.PRO &&
                            vm.isLutEnabled
                        if (!matches) {
                            logLutFailure = "requestedHdr=$hdrMode expectedHdr=$expectedHdrMode " +
                                "actualMode=${vm.uiMode.value} actualAspect=${vm.previewAspect.value} " +
                                "actualHdr=${vm.videoHdrMode.value} actualLog=${vm.logEnabledFlow.value} " +
                                "actualCinema=${vm.cinemaVideoMode.value} actualLut=${vm.isLutEnabled}"
                        }
                        matches
                    }
                }
                return ok
            }

            assertMatrixSettles(failureMessage = { logLutFailure }) { tryLogLutMatrix() }

            var hasFront = false
            scenario.onActivity {
                hasFront = vm.camera.hasCameraFacing(CameraCharacteristics.LENS_FACING_FRONT)
            }
            if (hasFront) {
                switchAndAwaitFacing(scenario, vm, CameraCharacteristics.LENS_FACING_FRONT)
                assertMatrixSettles(failureMessage = { logLutFailure }) { tryLogLutMatrix() }
                switchAndAwaitFacing(scenario, vm, CameraCharacteristics.LENS_FACING_BACK)
            }
        }
    }

    /** Poll [attempt] (which sets modes and returns whether the resulting state matches) until it
     *  holds or the timeout elapses — robust to the one-time async preference re-apply after a facing
     *  switch. Fails honestly if the contract never settles (a real behavioral difference). */
    private fun assertMatrixSettles(
        timeoutMs: Long = 6_000L,
        failureMessage: () -> String = { "Capture feature matrix did not settle" },
        attempt: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var ok = attempt()
        while (!ok && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(150L)
            ok = attempt()
        }
        assertTrue(failureMessage(), ok)
    }

    /** switchFacing() restarts the camera on a background coroutine and re-applies preferences, so
     *  facing (and the settled matrix) are not observable the instant the call returns. Trigger the
     *  switch and await the real facing change + a brief settle before asserting. */
    private fun switchAndAwaitFacing(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        target: Int,
        timeoutMs: Long = 8_000L
    ) {
        scenario.onActivity { vm.switchFacing() }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var reached = false
        while (SystemClock.elapsedRealtime() < deadline && !reached) {
            scenario.onActivity { reached = vm.camera.facing == target }
            if (!reached) {
                SystemClock.sleep(100L)
            }
        }
        scenario.onActivity { assertEquals(target, vm.camera.facing) }
        // Let the switch coroutine's applyPreferences()/openCamera() settle before the matrix is set.
        SystemClock.sleep(400L)
    }
}
