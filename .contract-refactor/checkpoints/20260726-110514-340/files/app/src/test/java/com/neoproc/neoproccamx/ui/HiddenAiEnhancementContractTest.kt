package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HiddenAiEnhancementContractTest {

    @Test
    fun aiEnhancementRemainsImplementedButProductLockedOffAndHiddenFromSettings() {
        val preferences = File("src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt").readText()
        val highEndFocusAiPack = File("src/main/java/com/neoproc/neoproccamx/ui/HighEndFocusAiPackPolicy.kt").readText()
        val settingsFactory = File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").readText()
        val settingsMeta = File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionMeta.kt").readText()
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()

        assertTrue(preferences.contains("const val KEY_AI_ENHANCEMENT"))
        assertTrue(preferences.contains("const val AI_ENHANCEMENT_SETTINGS_ENABLED = false"))
        assertTrue(preferences.contains("const val AI_ENHANCEMENT_DEFAULT_ENABLED = false"))
        assertTrue(preferences.contains("enforceHiddenAiEnhancementOff()"))
        assertTrue(preferences.contains("migrateFaceEyeDefaultsOffIfNeeded()"))
        assertTrue(preferences.contains("putBoolean(KEY_AI_ENHANCEMENT, false)"))
        assertTrue(preferences.contains("putBoolean(KEY_FACE_DETECT, false)"))
        assertTrue(preferences.contains("putBoolean(KEY_EYE_AF, false)"))
        assertTrue(preferences.contains("AI_ENHANCEMENT_SETTINGS_ENABLED &&"))
        assertTrue(preferences.contains("putBoolean(KEY_AI_ENHANCEMENT, AI_ENHANCEMENT_SETTINGS_ENABLED && v)"))
        assertFalse(preferences.contains("putBoolean(KEY_AI_ENHANCEMENT, true)"))

        assertTrue(highEndFocusAiPack.contains("aiEnhancementEnabled = PreferencesManager.AI_ENHANCEMENT_SETTINGS_ENABLED"))
        assertTrue(highEndFocusAiPack.contains("faceDetectionEnabled = false"))
        assertTrue(highEndFocusAiPack.contains("eyeAutofocusEnabled = false"))
        assertTrue(settingsFactory.contains("if (PreferencesManager.AI_ENHANCEMENT_SETTINGS_ENABLED)"))
        assertFalse(settingsMeta.contains("camera.aiEnhancement"))
        assertTrue(viewModel.contains("PreferencesManager.AI_ENHANCEMENT_SETTINGS_ENABLED && preferred"))
    }
}
