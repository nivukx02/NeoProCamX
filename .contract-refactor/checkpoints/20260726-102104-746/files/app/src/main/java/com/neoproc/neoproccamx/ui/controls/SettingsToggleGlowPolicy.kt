package com.neoproc.neoproccamx.ui.controls

internal data class SettingsToggleGlowVisual(
    val glowEnabled: Boolean,
    val rowFillColor: Int,
    val rowStrokeColor: Int,
    val trackColor: Int,
    val thumbColor: Int,
    val rowGlowAlpha: Float,
    val rowElevationDp: Float
)

internal object SettingsToggleGlowPolicy {
    fun resolve(checked: Boolean): SettingsToggleGlowVisual =
        if (checked) {
            SettingsToggleGlowVisual(
                glowEnabled = true,
                rowFillColor = 0x22141B22,
                rowStrokeColor = 0xCCFFFFFF.toInt(),
                trackColor = 0xFFFFFFFF.toInt(),
                thumbColor = 0xFFFFFFFF.toInt(),
                rowGlowAlpha = 0.5f,
                rowElevationDp = 8f
            )
        } else {
            SettingsToggleGlowVisual(
                glowEnabled = false,
                rowFillColor = 0xB8141B22.toInt(),
                rowStrokeColor = 0x1FFFFFFF,
                trackColor = 0xFF4A4A4F.toInt(),
                thumbColor = 0xFFFFFFFF.toInt(),
                rowGlowAlpha = 0f,
                rowElevationDp = 0f
            )
        }
}
