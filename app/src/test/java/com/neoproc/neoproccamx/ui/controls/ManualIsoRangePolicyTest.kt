package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualIsoRangePolicyTest {

    @Test
    fun supportedValues_followHardwareRangeInsteadOfFullExtendedScale() {
        val values = ManualIsoRangePolicy.supportedValues(100, 3200)

        assertEquals(100, values.first())
        assertEquals(3200, values.last())
        assertFalse(values.contains(4000))
        assertFalse(values.contains(409600))
    }

    @Test
    fun supportedValues_exposesHighIsoWhenHalReportsIt() {
        val values = ManualIsoRangePolicy.supportedValues(50, 12800)

        assertEquals(50, values.first())
        assertEquals(12800, values.last())
        assertTrue(values.contains(6400))
        assertTrue(values.contains(10000))
    }

    @Test
    fun supportedValues_keepsExactEffectiveIsoUpperWhenNotInCinemaTable() {
        val values = ManualIsoRangePolicy.supportedValues(72, 102368)

        assertEquals(72, values.first())
        assertEquals(102368, values.last())
        assertTrue(values.contains(80000))
        assertFalse(values.contains(102400))
    }

    @Test
    fun isoForProgress_clampsToAvailableHardwareValues() {
        val values = ManualIsoRangePolicy.supportedValues(100, 3200)

        assertEquals(100, ManualIsoRangePolicy.isoForProgress(-5, values))
        assertEquals(3200, ManualIsoRangePolicy.isoForProgress(999, values))
    }

    @Test
    fun progressForIso_returnsNearestSupportedStep() {
        val values = ManualIsoRangePolicy.supportedValues(100, 3200)

        assertEquals(0, ManualIsoRangePolicy.progressForIso(80, values))
        assertEquals(values.lastIndex, ManualIsoRangePolicy.progressForIso(409600, values))
        assertEquals(values.indexOf(800), ManualIsoRangePolicy.progressForIso(700, values))
    }
}
