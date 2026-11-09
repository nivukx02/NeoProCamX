package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraDomainControllerCleanArchitectureContractTest {
    private fun source(path: String): String =
        File("src/main/java/com/neoproc/neoproccamx/ui/$path").readText()

    @Test
    fun controllersDoNotDeclareHostInterfacesOrHostFields() {
        val files = listOf(
            "CameraModeController.kt",
            "CameraRecordingController.kt",
            "CameraPreviewController.kt",
            "CameraSettingsController.kt"
        )
        files.forEach { fileName ->
            val text = source(fileName)
            assertFalse("$fileName must not declare a ControllerHost interface", text.contains("ControllerHost"))
            assertFalse("$fileName must not keep a host reference", text.contains("private val host"))
        }
    }

    @Test
    fun controllersExposeAutonomousDomainStateStreams() {
        val mode = source("CameraModeController.kt")
        assertTrue(mode.contains("data class ModeDomainState"))
        assertTrue(mode.contains("val domainState: StateFlow<ModeDomainState>"))
        assertTrue(mode.contains("private val _domainState = MutableStateFlow"))
        assertFalse(mode.contains("CameraController"))
        assertFalse(mode.contains("setRawVideoCaptureEnabled"))
        assertFalse(mode.contains("FramePipelineManager"))
        assertTrue(mode.contains("private val prefs: PreferencesManager"))

        val preview = source("CameraPreviewController.kt")
        assertTrue(preview.contains("data class PreviewDomainState"))
        assertTrue(preview.contains("val domainState: StateFlow<PreviewDomainState>"))
        assertTrue(preview.contains("private val cameraController: CameraController"))
        assertFalse(preview.contains("FramePipelineManager"))
        assertTrue(preview.contains("private val prefs: PreferencesManager"))

        val recording = source("CameraRecordingController.kt")
        assertTrue(recording.contains("data class RecordingDomainState"))
        assertTrue(recording.contains("val domainState: StateFlow<RecordingDomainState>"))
        assertTrue(recording.contains("private val recordingManager: RecordingManager"))
        assertFalse(recording.contains("FramePipelineManager"))
        assertFalse(recording.contains("PreferencesManager"))

        val settings = source("CameraSettingsController.kt")
        assertTrue(settings.contains("data class SettingsDomainState"))
        assertTrue(settings.contains("val domainState: StateFlow<SettingsDomainState>"))
        assertTrue(settings.contains("private val cameraController: CameraController"))
        assertTrue(settings.contains("private val pipelineManager: FramePipelineManager"))
        assertTrue(settings.contains("private val prefs: PreferencesManager"))
    }

    @Test
    fun viewModelWiresControllersDirectlyAndCombinesDomainStreams() {
        val viewModel = source("CameraViewModel.kt")
        assertFalse(viewModel.contains("modeControllerHost = object"))
        assertFalse(viewModel.contains("recordingControllerHost = object"))
        assertFalse(viewModel.contains("previewControllerHost = object"))
        assertFalse(viewModel.contains("settingsControllerHost = object"))
        assertTrue(viewModel.contains("CameraModeController("))
        assertTrue(viewModel.contains("CameraPreviewController("))
        assertTrue(viewModel.contains("CameraRecordingController("))
        assertTrue(viewModel.contains("CameraSettingsController("))
        assertTrue(viewModel.contains("modeController.domainState"))
        assertTrue(viewModel.contains("previewController.domainState"))
        assertTrue(viewModel.contains("recordingController.domainState"))
        assertTrue(viewModel.contains("settingsController.domainState"))
    }

    @Test
    fun videoDetailProfileIsPersistedAndAppliedWithoutChangingColorSelections() {
        val settings = source("CameraSettingsController.kt")
        val viewModel = source("CameraViewModel.kt")
        val preferences = File("src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt").readText()
        val sectionFactory = File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").readText()

        assertTrue(preferences.contains("KEY_VIDEO_DETAIL_PROFILE"))
        assertTrue(preferences.contains("var videoDetailProfile"))
        assertTrue(settings.contains("videoDetailProfile"))
        assertTrue(settings.contains("fun setVideoDetailProfile("))
        assertTrue(viewModel.contains("fun setVideoDetailProfile("))
        assertTrue(viewModel.contains("settingsController.setVideoDetailProfile("))
        assertTrue(sectionFactory.contains("SettingsOptionCatalog.videoDetailProfileChoices"))
        assertTrue(sectionFactory.contains("onSelect = viewModel::setVideoDetailProfile"))
    }
}
