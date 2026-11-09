package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualAuxControlLabelPolicyTest {
    @Test
    fun meteringLabel_keepsCompactTextForOnAndOffStates() {
        assertEquals("Meter", ManualAuxControlLabelPolicy.meteringLabel(enabled = true))
        assertEquals("Meter", ManualAuxControlLabelPolicy.meteringLabel(enabled = false))
    }
}
