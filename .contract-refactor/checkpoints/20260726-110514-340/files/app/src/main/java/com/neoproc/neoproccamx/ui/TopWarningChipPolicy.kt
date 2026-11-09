package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier

internal data class TopWarningChipState(
    val visible: Boolean,
    val text: String
)

internal object TopWarningChipPolicy {
    fun topStatusVisible(
        controlsVisible: Boolean,
        warningVisible: Boolean,
        recording: Boolean = false
    ): Boolean = controlsVisible || warningVisible || recording

    fun resolve(
        hardwareWarning: String?,
        shutterGuidance: String?,
        thermalTier: HighEndRuntimeTier,
        thermalThrottled: Boolean
    ): TopWarningChipState {
        val explicitWarning = explicitDisplayWarning(hardwareWarning, shutterGuidance)
        if (explicitWarning != null) {
            return TopWarningChipState(visible = true, text = explicitWarning)
        }

        val thermalText = thermalWarningText(thermalTier, thermalThrottled)
        return TopWarningChipState(
            visible = thermalText.isNotEmpty(),
            text = thermalText
        )
    }

    fun explicitDisplayWarning(
        hardwareWarning: String?,
        shutterGuidance: String?
    ): String? = listOf(hardwareWarning, shutterGuidance)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull { !isBypassCapabilityDisplay(it) }

    private fun thermalWarningText(
        tier: HighEndRuntimeTier,
        throttled: Boolean
    ): String = when {
        tier == HighEndRuntimeTier.BASIC -> "EMERGENCY THERMAL"
        tier == HighEndRuntimeTier.RECOVERY -> "CRITICAL THERMAL"
        tier == HighEndRuntimeTier.STANDARD && throttled -> "THERMAL THROTTLING"
        throttled -> "THERMAL WARNING"
        tier == HighEndRuntimeTier.BALANCED -> "ELEVATED TEMPERATURE"
        else -> ""
    }

    private fun isBypassCapabilityDisplay(text: String): Boolean =
        text.trim().startsWith("Bypass capability:", ignoreCase = true)
}
