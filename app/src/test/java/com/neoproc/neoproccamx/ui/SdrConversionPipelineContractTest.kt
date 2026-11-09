package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SdrConversionPipelineContractTest {

    @Test
    fun `cinema cam is secondary default off and exposed in record settings`() {
        val preferences = source("utils/PreferencesManager.kt")
        val settings = source("ui/controls/SettingsSectionFactory.kt")

        assertTrue(preferences.contains("const val KEY_SDR_CONVERSION_PIPELINE"))
        assertTrue(preferences.contains("getBooleanCompat(KEY_SDR_CONVERSION_PIPELINE, false)"))
        assertTrue(settings.contains("title = \"Cinema Cam\""))
        assertTrue(settings.contains("subtitle = \"Secondary workflow:"))
        assertTrue(settings.contains("checked = viewModel.isSdrConversionPipelineEnabled"))
        assertTrue(settings.contains("onToggle = viewModel::setSdrConversionPipelineEnabled"))
    }

    @Test
    fun `view model gates npv10 and snapshots direct gallery safeguard`() {
        val viewModel = source("ui/CameraViewModel.kt")
        val manager = source("recording/RecordingManager.kt")

        assertTrue(viewModel.contains("sdrConversionPipelineEnabled = _sdrConversionPipeline.value"))
        assertTrue(viewModel.contains("gpuRecording.sdrConversionPipelineEnabled = state.sdrConversionPipeline"))
        assertTrue(manager.contains("SdrConversionPipelinePolicy.allowsPrivateConversionMaster("))
        assertTrue(manager.contains("privateConversionMasterAllowed"))
    }

    private fun source(relativePath: String): String =
        File("src/main/java/com/neoproc/neoproccamx/$relativePath").readText()
}
