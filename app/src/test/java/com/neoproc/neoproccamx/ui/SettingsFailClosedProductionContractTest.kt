package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level guard that prevents LQ-015 from regressing back to post-mutation sanitization. */
class SettingsFailClosedProductionContractTest {

    private val viewModelSource =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    private val settingsFragmentSource =
        File("src/main/java/com/neoproc/neoproccamx/ui/controls/VideoSettingsFragment.kt").readText()
    private val mainActivitySource =
        File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()

    @Test
    fun everyQualityCriticalPublicSetterRunsTheInvariantGateBeforeMutation() {
        assertGatePrecedes("fun setFPS(fps: Int)", "fun applyCinematicBundle()", "recording.fps =")
        assertGatePrecedes("fun setVideoResolution(size: Size)", "private fun sanitizeRequestedVideoResolutionForCinemaMode", "invalidatePendingPreferenceRestore()")
        assertGatePrecedes("fun setVideoBitrate(bps: Int)", "fun setVideoBitrateMode", "prefs.videoBitrateMode =")
        assertGatePrecedes("fun setVideoBitrateMode(mode: VideoBitrateMode)", "private fun syncRecordingBitrateMode", "prefs.videoBitrateMode =")
        assertGatePrecedes("fun setVideoCodec(c: VideoCodecType)", "fun setTenBitRecordingEnabled", "prefs.videoCodec =")
        assertGatePrecedes("fun setTenBitRecordingEnabled(enabled: Boolean)", "fun setAudioSrc", "prefs.tenBitRecordingEnabled =")
        assertGatePrecedes("fun setLogEnabled(on: Boolean)", "fun setSaturation", "invalidatePendingPreferenceRestore()")
        assertGatePrecedes("fun toggleRaw()", "fun setRawEnabled", "invalidatePendingPreferenceRestore()")
        assertGatePrecedes("fun setRawEnabled(enabled: Boolean)", "fun setFaceDetectionEnabled", "invalidatePendingPreferenceRestore()")
        assertGatePrecedes("fun setVideoHdrMode(mode: String)", "// -------------------------------------------------------------------------\n    // CAMERA SWITCH", "invalidatePendingPreferenceRestore()")
        assertGatePrecedes("fun setCinemaVideoMode(mode: CinemaVideoMode)", "private fun normalizeNpv10CinemaMode", "invalidatePendingPreferenceRestore()")
        assertGatePrecedes("fun setRawVideoCompressionMode(mode: RawCompressionMode)", "private fun setRawVideoCompressionModeInternal", "modeController.setRawVideoCompressionMode")
    }

    @Test
    fun rejectionIsMonotonicVisibleAndForcesSettingsToAuthoritativeState() {
        assertTrue(viewModelSource.contains("SETTINGS_INVARIANT_REJECTED"))
        assertTrue(viewModelSource.contains("_settingsRejection.value = notice"))
        assertTrue(viewModelSource.contains("setHardwareWarning(displayMessage)"))
        assertTrue(settingsFragmentSource.contains("viewModel.settingsRejection.collect"))
        assertTrue(settingsFragmentSource.contains("renderContent()"))
        assertTrue(settingsFragmentSource.contains("SETTINGS_REJECTION_BANNER_TAG"))
        assertTrue(settingsFragmentSource.contains("ACCESSIBILITY_LIVE_REGION_ASSERTIVE"))
    }

    @Test
    fun modeRailExplicitlyRetiresXdrBeforeEnteringLogOrRaw() {
        val activation = mainActivitySource
            .substringAfter("private fun activateModePreset(preset: CameraModePreset)")
            .substringBefore("private fun updatePresetTapTargets")
        val logBranch = activation.substringAfter("check(preset == CameraModePreset.LOG_VIDEO)")
            .substringBefore("val fpsPreservation")
        val rawBranch = activation.substringAfter("check(preset == CameraModePreset.RAW_MODE)")
            .substringBefore("markSwitchCheckpoint(\"raw-depth\")")
        assertTrue(logBranch.indexOf("vm.setVideoHdrMode(\"OFF\")") < logBranch.indexOf("vm.setCinemaVideoMode(CinemaVideoMode.LOG)"))
        assertTrue(rawBranch.indexOf("vm.setVideoHdrMode(\"OFF\")") < rawBranch.indexOf("vm.setCinemaVideoMode(CinemaVideoMode.RAW)"))
    }

    private fun assertGatePrecedes(signature: String, nextSignature: String, firstMutation: String) {
        val start = viewModelSource.lastIndexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val bodyStart = start + signature.length
        val end = viewModelSource.indexOf(nextSignature, bodyStart).takeIf { it >= 0 }
            ?: viewModelSource.length
        val body = viewModelSource.substring(bodyStart, end)
        val gateIndex = body.indexOf("admitSettingsCommit(")
        val mutationIndex = body.indexOf(firstMutation)
        assertTrue("$signature must contain the fail-closed gate", gateIndex >= 0)
        assertTrue("$signature must contain its expected mutation marker", mutationIndex >= 0)
        assertTrue("$signature must validate before mutation", gateIndex < mutationIndex)
    }
}
