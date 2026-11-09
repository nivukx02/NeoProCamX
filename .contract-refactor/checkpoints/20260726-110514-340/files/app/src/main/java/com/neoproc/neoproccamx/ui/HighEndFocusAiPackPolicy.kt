package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.utils.PreferencesManager

data class HighEndFocusAiPack(
    val aiEnhancementEnabled: Boolean,
    val noiseReductionEnabled: Boolean,
    val faceDetectionEnabled: Boolean,
    val eyeAutofocusEnabled: Boolean
)

object HighEndFocusAiPackPolicy {
    fun resolve(): HighEndFocusAiPack =
        HighEndFocusAiPack(
            aiEnhancementEnabled = PreferencesManager.AI_ENHANCEMENT_SETTINGS_ENABLED,
            noiseReductionEnabled = true,
            faceDetectionEnabled = false,
            eyeAutofocusEnabled = false
        )
}
