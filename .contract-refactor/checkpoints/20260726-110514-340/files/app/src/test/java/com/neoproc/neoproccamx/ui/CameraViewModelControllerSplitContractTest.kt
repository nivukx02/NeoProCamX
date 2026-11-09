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
    fun `camera view model facade delegates representative mode recording preview and settings actions`() {
        mapOf(
            "modeController.setMode(" to "setMode should delegate to CameraModeController",
            "modeController.setCinemaVideoMode(" to "setCinemaVideoMode should delegate to CameraModeController",
            "recordingController.startRecording()" to "startRecording should delegate to CameraRecordingController",
            "recordingController.stopRecording()" to "stopRecording should delegate to CameraRecordingController",
            "previewController.setPreviewAspectRatio(" to "setPreviewAspectRatio should delegate to CameraPreviewController",
            "previewController.previewSourceAspect(" to "previewSourceAspect should delegate to CameraPreviewController",
            "settingsController.setHistogramEnabled(" to "setHistogramEnabled should delegate to CameraSettingsController",
            "settingsController.setNoiseReductionEnabled(" to "setNoiseReductionEnabled should delegate to CameraSettingsController"
        ).forEach { (snippet, message) ->
            assertTrue(message, viewModelSource.contains(snippet))
        }
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
        assertTrue(viewModelSource.contains("selectedVideoSize = requested"))
        assertTrue(viewModelSource.contains("prefs.videoSize != requested"))
        assertFalse(viewModelSource.contains("selectedVideoSize = effectiveRequestedSize"))
    }

    @Test
    fun `async preference restore cannot overwrite a user recording resolution change`() {
        val applyPreferencesBlock = viewModelSource
            .substringAfter("private fun applyPreferences(")
            .substringBefore("private fun restorePersistedManualControls()")
        val setVideoResolutionBlock = viewModelSource
            .substringAfter("fun setVideoResolution(size: Size)")
            .substringBefore("private fun sanitizeRequestedVideoResolutionForCinemaMode(")

        assertTrue(viewModelSource.contains("private val recordingSelectionMutationLock = Any()"))
        assertTrue(applyPreferencesBlock.contains("val requestedSizeAtResolve = prefs.videoSize.normalizeForOrientation()"))
        assertTrue(applyPreferencesBlock.contains("synchronized(recordingSelectionMutationLock)"))
        assertTrue(applyPreferencesBlock.contains("prefs.videoSize.normalizeForOrientation() != requestedSizeAtResolve"))
        assertTrue(applyPreferencesBlock.contains("Skipped stale preference resolution restore"))
        assertTrue(setVideoResolutionBlock.contains("synchronized(recordingSelectionMutationLock)"))
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
