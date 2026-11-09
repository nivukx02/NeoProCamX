package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualVideoBitrateContractTest {
    private val preferences =
        File("src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt").readText()
    private val viewModel =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    private val settings =
        File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt").readText()
    private val settingsSection =
        File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").readText()
    private val videoSettingsFragment =
        File("src/main/java/com/neoproc/neoproccamx/ui/controls/VideoSettingsFragment.kt").readText()
    private val bitratePolicy =
        File("src/main/java/com/neoproc/neoproccamx/recording/RecordingUserBitratePolicy.kt").readText()
    private val encoderPolicy =
        File("src/main/java/com/neoproc/neoproccamx/recording/RecordingVideoEncoderPlanResolver.kt").readText()
    private val viewModelConstants =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModelConstants.kt").readText()

    @Test
    fun `video starts with a user editable strict high end requested bitrate`() {
        assertTrue(preferences.contains("DEFAULT_VIDEO_BITRATE     = 1_968_000_000"))
        assertTrue(preferences.contains("MAX_VIDEO_BITRATE = 2_000_000_000"))
        assertTrue(viewModelConstants.contains("HIGH_END_DEFAULT_MANUAL_BITRATE_BPS = 1_968_000_000"))
        assertTrue(viewModelConstants.contains("HIGH_END_MAX_BITRATE_BPS = 2_000_000_000"))
        assertTrue(viewModelConstants.contains("CINEMA_4K60_MIN_BITRATE_BPS = 1_968_000_000"))
        assertTrue(
            preferences.contains(
                "getStringCompat(KEY_VIDEO_BITRATE_MODE, VideoBitrateMode.CUSTOM.name)"
            )
        )
        assertTrue(settings.contains("820, 1_000, 1_200, 1_600, 1_968, 2_000"))
    }

    @Test
    fun `custom bitrate persists requested target and activates manual encoder policy`() {
        assertTrue(viewModel.contains("gpuRecording.manualBitrateSelectionEnabled"))
        assertTrue(viewModel.contains("prefs.videoBitrate = bps"))
        assertTrue(bitratePolicy.contains("manualSelectionEnabled"))
        assertTrue(encoderPolicy.contains("manualBitrateSelectionEnabled"))
        assertTrue(encoderPolicy.contains("!manualBitrateSelectionEnabled"))
    }

    @Test
    fun `manual bitrate slider displays the selected target rather than a temporary output clamp`() {
        assertTrue(viewModel.contains("fun requestedVideoBitrateBitsPerSecond(): Int"))
        assertTrue(settingsSection.contains("viewModel.requestedVideoBitrateBitsPerSecond()"))
        assertTrue(settingsSection.contains("SettingsRuntimeFormatter.formatBitrateStatusLabel("))
        assertTrue(videoSettingsFragment.contains("SettingsRuntimeFormatter.formatBitrateStatusLabel("))
    }
}
