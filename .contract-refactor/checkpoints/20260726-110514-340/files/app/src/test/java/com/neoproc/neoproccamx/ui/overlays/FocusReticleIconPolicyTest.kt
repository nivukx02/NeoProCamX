package com.neoproc.neoproccamx.ui.overlays

import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.ui.FocusAssistVisual
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusReticleIconPolicyTest {

    @Test
    fun drawableRes_mapsEachFocusAssistVisualToItsOnScreenIcon() {
        assertEquals(R.drawable.ic_focus_tap, FocusReticleIconPolicy.drawableRes(FocusAssistVisual.TAP))
        assertEquals(R.drawable.ic_focus_face, FocusReticleIconPolicy.drawableRes(FocusAssistVisual.FACE))
        assertEquals(R.drawable.ic_focus_eye, FocusReticleIconPolicy.drawableRes(FocusAssistVisual.EYE))
    }

    @Test
    fun iconBounds_centersTouchIconInsideFocusBox() {
        val bounds = FocusReticleIconPolicy.iconBounds(100f, 200f, 220f, 320f)

        assertEquals(122.8f, bounds.left, 0.0001f)
        assertEquals(222.8f, bounds.top, 0.0001f)
        assertEquals(197.2f, bounds.right, 0.0001f)
        assertEquals(297.2f, bounds.bottom, 0.0001f)
    }
}
