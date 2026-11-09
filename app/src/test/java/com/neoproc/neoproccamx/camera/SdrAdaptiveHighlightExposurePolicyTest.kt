package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrAdaptiveHighlightExposurePolicyTest {
    @Test
    fun `ordinary SDR scene keeps neutral exposure`() {
        assertEquals(
            0f,
            SdrAdaptiveHighlightExposurePolicy.resolveProtectionStops(0.46f, 0.004f, 0f),
            1e-6f
        )
    }

    @Test
    fun `broad highlight clipping increases protection monotonically`() {
        val light = SdrAdaptiveHighlightExposurePolicy.resolveProtectionStops(0.5f, 0.025f)
        val medium = SdrAdaptiveHighlightExposurePolicy.resolveProtectionStops(0.5f, 0.08f)
        val heavy = SdrAdaptiveHighlightExposurePolicy.resolveProtectionStops(0.5f, 0.30f)
        assertTrue(medium < light)
        assertTrue(heavy < medium)
    }

    @Test
    fun `bright scene and red clipping protect before luma clipping`() {
        assertTrue(SdrAdaptiveHighlightExposurePolicy.resolveProtectionStops(0.86f, 0f) < 0f)
        assertTrue(SdrAdaptiveHighlightExposurePolicy.resolveProtectionStops(0.45f, 0f, 0.11f) < 0f)
    }

    @Test
    fun `SDR governor can only darken and remains bounded`() {
        for (luma in listOf(0f, 0.5f, 0.8f, 1f)) {
            for (clip in listOf(0f, 0.02f, 0.2f, 1f)) {
                val stops = SdrAdaptiveHighlightExposurePolicy.resolveProtectionStops(luma, clip, clip)
                assertTrue(stops <= 0f)
                assertTrue(stops >= SdrAdaptiveHighlightExposurePolicy.MAX_PROTECTION_STOPS)
            }
        }
    }

    @Test
    fun `attack is faster than release`() {
        assertEquals(
            -0.72f,
            SdrAdaptiveHighlightExposurePolicy.smoothProtectionStops(0f, -1f),
            1e-6f
        )
        assertEquals(
            -0.90f,
            SdrAdaptiveHighlightExposurePolicy.smoothProtectionStops(-1f, 0f),
            1e-6f
        )
    }

    @Test
    fun `stale measurement releases smoothly to neutral`() {
        val start = SdrAdaptiveHighlightExposurePolicy.LIVE_METRICS_MAX_AGE_MS
        val release = SdrAdaptiveHighlightExposurePolicy.STALE_METRICS_RELEASE_MS
        assertEquals(
            -0.4f,
            SdrAdaptiveHighlightExposurePolicy.resolveAppliedProtectionStops(-0.4f, start),
            1e-6f
        )
        assertEquals(
            -0.2f,
            SdrAdaptiveHighlightExposurePolicy.resolveAppliedProtectionStops(-0.4f, start + release / 2),
            1e-6f
        )
        assertEquals(
            0f,
            SdrAdaptiveHighlightExposurePolicy.resolveAppliedProtectionStops(-0.4f, Long.MAX_VALUE),
            1e-6f
        )
    }
}
