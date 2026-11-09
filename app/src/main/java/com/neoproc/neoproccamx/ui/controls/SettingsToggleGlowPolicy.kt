package com.neoproc.neoproccamx.ui.controls

internal data class SettingsToggleGlowVisual(
    val glowEnabled: Boolean,
    val rowFillColor: Int,
    val rowStrokeColor: Int,
    val trackColor: Int,
    val thumbColor: Int,
    val rowGlowAlpha: Float
)

internal object SettingsToggleGlowPolicy {
    fun resolve(checked: Boolean): SettingsToggleGlowVisual =
        if (checked) {
            SettingsToggleGlowVisual(
                glowEnabled = true,
                rowFillColor = 0x3DB46CFF,
                rowStrokeColor = 0xFFD2A9FF.toInt(),
                trackColor = 0xFF9D50F5.toInt(),
                thumbColor = 0xFFFFFFFF.toInt(),
                rowGlowAlpha = 0.72f
            )
        } else {
            SettingsToggleGlowVisual(
                glowEnabled = false,
                rowFillColor = 0x2EFFFFFF,
                rowStrokeColor = 0x64FFFFFF,
                trackColor = 0xFF696B70.toInt(),
                thumbColor = 0xFFFFFFFF.toInt(),
                rowGlowAlpha = 0f
            )
        }
}
