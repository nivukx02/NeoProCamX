package com.neoproc.neoproccamx.ui.controls

internal data class SettingsChoiceVisual(
    val dialogSurfaceColor: Int,
    val dialogStrokeColor: Int,
    val unselectedRowColor: Int,
    val unselectedStrokeColor: Int,
    val selectedRowColor: Int,
    val selectedStrokeColor: Int,
    val rowRadiusDp: Float,
    val optionTextSizeSp: Float,
    val optionMetaTextSizeSp: Float,
    val selectedTextBold: Boolean
)

internal object SettingsChoiceVisualPolicy {
    fun resolve(): SettingsChoiceVisual =
        SettingsChoiceVisual(
            dialogSurfaceColor = 0xEF11151A.toInt(),
            dialogStrokeColor = 0x4AFFFFFF,
            unselectedRowColor = 0x4AFFFFFF,
            unselectedStrokeColor = 0x4AFFFFFF,
            selectedRowColor = 0x33B46CFF,
            selectedStrokeColor = 0xFFB46CFF.toInt(),
            rowRadiusDp = 22f,
            optionTextSizeSp = 15f,
            optionMetaTextSizeSp = 12f,
            selectedTextBold = true
        )
}
