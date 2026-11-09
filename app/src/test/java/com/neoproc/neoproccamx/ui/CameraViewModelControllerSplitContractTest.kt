package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraViewModelControllerSplitContractTest {
    private val uiSourceDir = File("src/main/java/com/neoproc/neoproccamx/ui")
    private val viewModelSource: String by lazy {
        File(uiSourceDir, "CameraViewModel.kt").readText()
    }
    private val normalizedViewModelSource: String by lazy {
        viewModelSource.replace("\r\n", "\n")
    }

    @Test
    fun `view model responsibilities are split into domain controllers`() {
        listOf(
            "CameraModeController.kt",
            "CameraRecordingController.kt",
            "CameraPreviewController.kt",
            "CameraSettingsController.kt"
        ).forEach { filename ->
            assertTrue(
                "$filename must exist so CameraViewModel does not keep every domain inline",
                File(uiSourceDir, filename).isFile
            )
        }
    }

    @Test
    fun `recording evidence model is split out of camera view model`() {
        assertTrue(
            "ValidatedGpuRecordingEvidence must live in its own file because it is recording route evidence, not UI facade logic.",
            File(uiSourceDir, "ValidatedGpuRecordingEvidence.kt").isFile
        )
        assertFalse(
            "CameraViewModel must not declare the recording evidence model inline.",
            viewModelSource.contains("private data class ValidatedGpuRecordingEvidence")
        )
    }

    @Test
    fun `raw capture stop result is split out of camera view model`() {
        assertTrue(
            "RawCaptureStopResult must live in its own file because it is a recording stop result model.",
            File(uiSourceDir, "RawCaptureStopResult.kt").isFile
        )
        assertFalse(
            "CameraViewModel must not declare RawCaptureStopResult inline.",
            viewModelSource.contains("private data class RawCaptureStopResult")
        )
    }

    @Test
    fun `camera2 frame lineage worker and certificate state are split out of camera view model`() {
        assertTrue(
            "Camera2 frame-lineage FIFO and certificate state must have one focused lifecycle owner.",
            File(uiSourceDir, "Camera2FrameLineageCoordinator.kt").isFile
        )
        assertTrue(viewModelSource.contains("camera2FrameLineage.recordCaptureResult("))
        assertTrue(viewModelSource.contains("camera2FrameLineage.drainAndShutdownForTeardown("))
        assertFalse(viewModelSource.contains("private val camera2FrameLineageLock = Any()"))
        assertFalse(viewModelSource.contains("private fun scheduleCamera2LineageDrain()"))
    }

    @Test
    fun `camera view model facade delegates representative mode recording preview and settings actions`() {
        mapOf(
            "modeController.setMode(" to "setMode should delegate to CameraModeController",
            "modeController.setCinemaVideoMode(" to "setCinemaVideoMode should delegate to CameraModeController",
            "recordingStopRestartLifecycle.requestStart()" to
                "startRecording should delegate to the authoritative recording lifecycle",
            "recordingController.stopRecording()" to "stopRecording should delegate to CameraRecordingController",
            "previewController.syncFromLegacy(" to
                "setPreviewAspectRatio should synchronize CameraPreviewController without rebuilding Camera2",
            "previewController.previewSourceAspect(" to "previewSourceAspect should delegate to CameraPreviewController",
            "settingsController.setGridMode(" to "setGridMode should delegate to CameraSettingsController",
            "settingsController.setNoiseReductionEnabled(" to "setNoiseReductionEnabled should delegate to CameraSettingsController"
        ).forEach { (snippet, message) ->
            assertTrue(message, viewModelSource.contains(snippet))
        }

        val startAdmission = viewModelSource
            .substringAfter("private fun claimRecordingStartAdmission(): Boolean {")
            .substringBefore("private fun beginClaimedRecordingStart()")
        assertTrue(
            "recording admission must use the authoritative backend state",
            startAdmission.contains("RecordingStartBackendAdmissionPolicy.allowsStart(") &&
                startAdmission.contains("hasActiveRecordingBackend()")
        )
        assertFalse(
            "an asynchronously projected controller state must not veto retained restart replay",
            startAdmission.contains("recordingController.startRecording()")
        )
    }

    @Test
    fun `hidden ai enhancement is product locked off while noise reduction remains authoritative`() {
        assertTrue(
            normalizedViewModelSource.contains(
                "private fun effectiveAiEnhancementEnabled(preferred: Boolean = prefs.aiEnhancementEnabled): Boolean =\n" +
                    "        PreferencesManager.AI_ENHANCEMENT_SETTINGS_ENABLED && preferred"
            )
        )
        assertTrue(
            normalizedViewModelSource.contains(
                "private fun effectiveNoiseReductionEnabled(preferred: Boolean = prefs.noiseReductionEnabled): Boolean =\n" +
                    "        preferred"
            )
        )
    }

    @Test
    fun `video output selection is normalized for orientation and not cropped by preview aspect`() {
        assertTrue(
            viewModelSource.contains(
                "normalizeRecordingRequest = { size -> size.normalizeForOrientation() }"
            )
        )
        assertFalse(viewModelSource.contains("normalizeRecordingRequest = ::aspectAdjustedRecordingRequest"))
    }

    @Test
    fun `resolution fallback does not overwrite the requested recording selection`() {
        val applyConfigBlock = viewModelSource
            .substringAfter("private fun applyResolvedRecordingConfig(")
            .substringBefore("private fun shouldEnforceNativeRecordingResolution")
        val directSelectionBlock = viewModelSource
            .substringAfter("fun setVideoResolution(size: Size)")
            .substringBefore("private fun sanitizeRequestedVideoResolutionForCinemaMode(")

        assertTrue(viewModelSource.contains("selectedVideoSize = requested"))
        assertFalse(applyConfigBlock.contains("prefs.videoSize ="))
        assertTrue(directSelectionBlock.contains("prefs.videoSize = resolvedSelection.requestedSize"))
        assertFalse(viewModelSource.contains("selectedVideoSize = effectiveRequestedSize"))
    }

    @Test
    fun `cold start restores persisted recording resolution authority before deferred resolution`() {
        val startupBlock = viewModelSource
            .substringAfter("private fun applyStartupPreferences()")
            .substringBefore("private fun publishStartupPreferencesApplied()")

        assertTrue(
            startupBlock.contains(
                "recordingResolutionSelectionAuthority.restorePersistedIfUnclaimed(startupVideoSize)"
            )
        )
    }

    @Test
    fun `async preference restore cannot overwrite a user recording resolution change`() {
        val applyPreferencesBlock = viewModelSource
            .substringAfter("private fun applyPreferences(")
            .substringBefore("private fun restorePersistedManualControls()")
        val setVideoResolutionBlock = viewModelSource
            .substringAfter("fun setVideoResolution(size: Size)")
            .substringBefore("private fun sanitizeRequestedVideoResolutionForCinemaMode(")

        assertTrue(viewModelSource.contains("private val preferencesRefreshOwnership = LatestRestoreOwnership()"))
        assertTrue(viewModelSource.contains("private val recordingSelectionMutationLock = Any()"))
        assertTrue(applyPreferencesBlock.contains("val requestedSizeAtResolve = signature.videoSize.normalizeForOrientation()"))
        assertTrue(applyPreferencesBlock.contains("preferencesRefreshOwnership.commitIfCurrent("))
        assertTrue(applyPreferencesBlock.contains("currentPreferencesRefreshSignature() == signature"))
        assertTrue(applyPreferencesBlock.contains("synchronized(recordingSelectionMutationLock)"))
        assertTrue(applyPreferencesBlock.contains("Skipped stale preference state restore"))
        assertTrue(setVideoResolutionBlock.contains("invalidatePendingPreferenceRestore()"))
        assertTrue(setVideoResolutionBlock.contains("synchronized(recordingSelectionMutationLock)"))
    }

    @Test
    fun `async preference restore cannot replay stale hdr after user selection`() {
        val applyPreferencesBlock = viewModelSource
            .substringAfter("private fun applyPreferences(")
            .substringBefore("private fun restorePersistedManualControls()")
        val setVideoHdrModeBlock = viewModelSource
            .substringAfter("fun setVideoHdrMode(mode: String)")
            .substringBefore("// -------------------------------------------------------------------------\r\n    // CAMERA SWITCH")

        assertTrue(setVideoHdrModeBlock.contains("invalidatePendingPreferenceRestore()"))
        assertTrue(applyPreferencesBlock.contains("preferencesRefreshOwnership.commitIfCurrent("))
        assertFalse(applyPreferencesBlock.contains("restoreVideoFeatures(persist = false)"))
    }

    @Test
    fun `recording prearm keeps the selected output size independent of preview framing`() {
        val preferredRecordingSizeBlock = viewModelSource
            .substringAfter("private fun preferredRecordingSize(): Size")
            .substringBefore("private fun preferredRecordingAnalysisSize(")

        assertTrue(
            preferredRecordingSizeBlock.contains(
                "(selectedVideoSize ?: prefs.videoSize).normalizeForOrientation()"
            )
        )
        assertFalse(preferredRecordingSizeBlock.contains("outputAspectRatio()"))
        assertFalse(preferredRecordingSizeBlock.contains("selectedMatchesAspect"))
    }
}
