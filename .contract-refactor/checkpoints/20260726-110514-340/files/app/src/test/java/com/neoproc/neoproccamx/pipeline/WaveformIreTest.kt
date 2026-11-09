package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f01 — the waveform sample→plot-height mapping. Flag-off must equal today's `sample/255f`
 * exactly; the legal-range IRE mapping puts code 16→0 IRE and 235→100 IRE monotonically.
 */
class WaveformIreTest {

    @Test
    fun defaultFlagOffMatchesLegacyLinearMapping() {
        for (s in 0..255) {
            assertEquals("s=$s", s / 255f, HudIreScale.normalizedFromCode8(s), 0f)
        }
    }

    @Test
    fun legalRangeRampEndpointsMapTo0And100Ire() {
        assertEquals(0f, HudIreScale.legalRangeIre(16), 1f)
        assertEquals(100f, HudIreScale.legalRangeIre(235), 1f)
        // 50% legal-range code (~125) is near 50 IRE.
        assertTrue("midpoint near 50 IRE", kotlin.math.abs(HudIreScale.legalRangeIre(125) - 49.77f) < 2f)
    }
}
