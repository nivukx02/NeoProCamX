package com.neoproc.neoproccamx.ui.controls

internal data class SettingsUiStyle(
    val rootHorizontalPaddingDp: Int,
    val rootTopPaddingDp: Int,
    val rootBottomPaddingDp: Int,
    val sectionCornerRadiusDp: Int,
    val sectionHorizontalPaddingDp: Int,
    val sectionVerticalPaddingDp: Int,
    val rowVerticalPaddingDp: Int,
    val rowCornerRadiusDp: Int
)

internal object SettingsUiStylePolicy {
    fun resolve(fullScreen: Boolean): SettingsUiStyle =
        if (fullScreen) {
            SettingsUiStyle(
                rootHorizontalPaddingDp = 12,
                rootTopPaddingDp = 10,
                rootBottomPaddingDp = 24,
                sectionCornerRadiusDp = 12,
                sectionHorizontalPaddingDp = 14,
                sectionVerticalPaddingDp = 8,
                rowVerticalPaddingDp = 9,
                rowCornerRadiusDp = 22
            )
        } else {
            SettingsUiStyle(
                rootHorizontalPaddingDp = 22,
                rootTopPaddingDp = 18,
                rootBottomPaddingDp = 26,
                sectionCornerRadiusDp = 12,
                sectionHorizontalPaddingDp = 14,
                sectionVerticalPaddingDp = 8,
                rowVerticalPaddingDp = 9,
                rowCornerRadiusDp = 22
            )
        }
}
