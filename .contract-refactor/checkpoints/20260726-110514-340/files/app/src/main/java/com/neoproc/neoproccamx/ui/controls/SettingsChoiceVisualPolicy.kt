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
            dialogSurfaceColor = 0xF2141A21.toInt(),
            dialogStrokeColor = 0x1FFFFFFF,
            unselectedRowColor = 0xB8141B22.toInt(),
            unselectedStrokeColor = 0x1FFFFFFF,
            selectedRowColor = 0x33F8CC55,
            selectedStrokeColor = 0xFFF8CC55.toInt(),
            rowRadiusDp = 22f,
            optionTextSizeSp = 15f,
            optionMetaTextSizeSp = 12f,
            selectedTextBold = true
        )
}
