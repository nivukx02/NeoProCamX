package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChoiceVisualPolicyTest {

    @Test
    fun choiceSheetUsesSameGlassSurfaceFamilyAsHomeCamera() {
        val visual = SettingsChoiceVisualPolicy.resolve()

        assertEquals(0xEF11151A.toInt(), visual.dialogSurfaceColor)
        assertEquals(0x4AFFFFFF, visual.unselectedRowColor)
        assertEquals(0x4AFFFFFF, visual.unselectedStrokeColor)
    }

    @Test
    fun choiceSheetFontAndShapeMatchSettingsRows() {
        val visual = SettingsChoiceVisualPolicy.resolve()

        assertEquals(22f, visual.rowRadiusDp, 0.001f)
        assertEquals(15f, visual.optionTextSizeSp, 0.001f)
        assertTrue(visual.selectedTextBold)
    }

    @Test
    fun toggleGlowBrightensOnlyTheActiveState() {
        val off = SettingsToggleGlowPolicy.resolve(checked = false)
        val on = SettingsToggleGlowPolicy.resolve(checked = true)

        assertFalse(off.glowEnabled)
        assertTrue(on.glowEnabled)
        assertEquals(0xFF9D50F5.toInt(), on.trackColor)
        assertEquals(0xFFD2A9FF.toInt(), on.rowStrokeColor)
        assertEquals(0x3DB46CFF, on.rowFillColor)
        assertTrue(on.rowGlowAlpha > off.rowGlowAlpha)
        assertTrue(on.rowStrokeColor != off.rowStrokeColor)
    }
}
