package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiStylePolicyTest {

    @Test
    fun fullScreenSettingsUseDenseCameraPanelSizing() {
        val style = SettingsUiStylePolicy.resolve(fullScreen = true)

        assertTrue(style.rootHorizontalPaddingDp <= 14)
        assertTrue(style.sectionCornerRadiusDp <= 12)
        assertTrue(style.rowVerticalPaddingDp <= 10)
    }

    @Test
    fun settingsRowsMatchChoiceSheetRoundedOptionStyle() {
        val style = SettingsUiStylePolicy.resolve(fullScreen = true)

        assertTrue(style.rowCornerRadiusDp >= 22)
    }
}
