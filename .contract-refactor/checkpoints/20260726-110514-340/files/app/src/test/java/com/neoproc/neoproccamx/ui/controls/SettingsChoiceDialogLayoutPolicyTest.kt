package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChoiceDialogLayoutPolicyTest {

    @Test
    fun strictHighEndChoiceSheetMatchesReferenceWidth() {
        val width = SettingsChoiceDialogLayoutPolicy.resolveWidthPx(
            screenWidthPx = 1080,
            density = 3f,
            strictHighEnd = true
        )

        assertTrue(width in 780..796)
    }

    @Test
    fun choiceSheetIsCappedOnLargeDisplays() {
        val width = SettingsChoiceDialogLayoutPolicy.resolveWidthPx(
            screenWidthPx = 2400,
            density = 3f,
            strictHighEnd = true
        )

        assertEquals(1020, width)
    }
}
