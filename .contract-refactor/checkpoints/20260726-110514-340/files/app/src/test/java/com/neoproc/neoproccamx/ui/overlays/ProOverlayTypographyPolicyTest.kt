package com.neoproc.neoproccamx.ui.overlays

import org.junit.Assert.assertEquals
import org.junit.Test

class ProOverlayTypographyPolicyTest {

    @Test
    fun labelSpec_usesMediumSansSerifWeight() {
        val spec = ProOverlayTypographyPolicy.labelSpec()

        assertEquals("sans-serif", spec.familyName)
        assertEquals(500, spec.weight)
    }

    @Test
    fun valueAndRecordSpecs_useBoldSansSerifWeight() {
        val value = ProOverlayTypographyPolicy.valueSpec()
        val record = ProOverlayTypographyPolicy.recordTimeSpec()

        assertEquals("sans-serif", value.familyName)
        assertEquals(700, value.weight)
        assertEquals("sans-serif", record.familyName)
        assertEquals(700, record.weight)
    }
}
