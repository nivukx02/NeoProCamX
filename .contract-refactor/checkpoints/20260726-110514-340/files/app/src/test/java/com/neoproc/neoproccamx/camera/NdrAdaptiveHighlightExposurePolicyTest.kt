package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the closed-loop adaptive highlight-protection math: it only ever ADDS negative (darker) bias,
 * deepens with measured clipping/brightness, is capped, and derives a sane clip fraction from a
 * histogram. This is the capture-side fix for blown highlights + overexposure "red skin".
 */
class NdrAdaptiveHighlightExposurePolicyTest {

    @Test
    fun `clean mid scene adds no extra protection`() {
        val stops = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(averageLuma = 0.45f, highlightClipFraction = 0f)
        assertEquals(0f, stops, 1e-6f)
    }

    @Test
    fun `extra bias is never positive (only ever darker)`() {
        for (luma in listOf(0f, 0.3f, 0.6f, 0.75f, 0.9f, 1f)) {
            for (clip in listOf(0f, 0.03f, 0.1f, 0.3f, 1f)) {
                val stops = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(luma, clip)
                assertTrue("stops must be <= 0 (luma=$luma clip=$clip -> $stops)", stops <= 0f)
            }
        }
    }

    @Test
    fun `heavier clipping deepens protection monotonically`() {
        val light = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(0.5f, 0.06f)
        val medium = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(0.5f, 0.15f)
        val heavy = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(0.5f, 0.30f)
        assertTrue(medium < light)
        assertTrue(heavy < medium)
    }

    @Test
    fun `protection never exceeds the MAX_EXTRA_STOPS floor`() {
        val stops = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(averageLuma = 1f, highlightClipFraction = 1f)
        // Deepest tabulated protection is -0.83 stops; it must stay at or above the -1.0 hard floor.
        assertTrue("must not exceed the hard floor", stops >= NdrAdaptiveHighlightExposurePolicy.MAX_EXTRA_STOPS)
        assertEquals(-0.83f, stops, 1e-6f)
    }

    @Test
    fun `a very bright average scene protects even without hard clipping`() {
        val stops = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(averageLuma = 0.85f, highlightClipFraction = 0f)
        assertTrue("bright scene should pull exposure down", stops < 0f)
    }

    @Test
    fun `highlight clip fraction reads the top histogram bins`() {
        // 256-bin histogram: put everything in the very top bin -> ~1.0 in the top 4 bins.
        val hist = IntArray(256)
        hist[255] = 1000
        assertEquals(1f, NdrAdaptiveHighlightExposurePolicy.highlightClipFraction(hist), 1e-4f)

        // Everything in the shadows -> 0 in the top bins.
        val dark = IntArray(256)
        dark[0] = 1000
        assertEquals(0f, NdrAdaptiveHighlightExposurePolicy.highlightClipFraction(dark), 1e-4f)
    }

    @Test
    fun `video range nominal white is counted as clipped highlight data`() {
        val hist = IntArray(256)
        hist[64] = 900
        hist[235] = 100

        assertEquals(0.10f, NdrAdaptiveHighlightExposurePolicy.highlightClipFraction(hist), 1e-4f)
        assertTrue(
            NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(
                averageLuma = 0.45f,
                highlightClipFraction = NdrAdaptiveHighlightExposurePolicy.highlightClipFraction(hist)
            ) < 0f
        )
    }

    @Test
    fun `full range histogram keeps terminal white threshold`() {
        val hist = IntArray(256)
        hist[245] = 900
        hist[255] = 100

        // Full-range evidence at code 255 prevents ordinary code 245 highlights being mislabeled clip.
        assertEquals(0.10f, NdrAdaptiveHighlightExposurePolicy.highlightClipFraction(hist), 1e-4f)
    }

    @Test
    fun `invalid scene metrics fail neutral rather than poisoning exposure`() {
        val stops = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(
            averageLuma = Float.NaN,
            highlightClipFraction = Float.NaN,
            redHighlightClipFraction = Float.NaN
        )
        assertEquals(0f, stops, 1e-6f)
    }

    @Test
    fun `null or empty histogram yields zero clip fraction`() {
        assertEquals(0f, NdrAdaptiveHighlightExposurePolicy.highlightClipFraction(null), 1e-6f)
        assertEquals(0f, NdrAdaptiveHighlightExposurePolicy.highlightClipFraction(IntArray(0)), 1e-6f)
    }

    @Test
    fun `red channel clip pulls exposure down even when luma reads moderate and unclipped`() {
        // The whole point of the RED-aware driver: a warm subject clipping its RED channel while luma
        // is only moderate (luma+clip drivers both read 0) must still trigger protection.
        val lumaBlind = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(
            averageLuma = 0.45f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0f
        )
        val redAware = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(
            averageLuma = 0.45f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0.10f
        )
        assertEquals("no red clip -> no protection", 0f, lumaBlind, 1e-6f)
        assertTrue("red clip must pull exposure down", redAware < 0f)
    }

    @Test
    fun `heavier red clipping deepens protection monotonically`() {
        val light = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(0.4f, 0f, 0.02f)
        val medium = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(0.4f, 0f, 0.05f)
        val heavy = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(0.4f, 0f, 0.20f)
        assertTrue(medium < light)
        assertTrue(heavy < medium)
    }

    @Test
    fun `red clip protection stays within the hard floor and never positive`() {
        val stops = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(1f, 1f, 1f)
        assertTrue(stops >= NdrAdaptiveHighlightExposurePolicy.MAX_EXTRA_STOPS)
        assertTrue(stops <= 0f)
    }

    @Test
    fun `red clip driver is off when its fraction is zero (byte-identical to luma-only path)`() {
        for (luma in listOf(0f, 0.5f, 0.85f)) {
            for (clip in listOf(0f, 0.1f, 0.3f)) {
                val withZeroRed = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(luma, clip, 0f)
                val lumaOnly = NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(luma, clip)
                assertEquals(lumaOnly, withZeroRed, 1e-6f)
            }
        }
    }

    @Test
    fun `highlight attack is faster than release and stays bounded`() {
        val attacked = NdrAdaptiveHighlightExposurePolicy.smoothExtraStops(0f, -1f)
        val released = NdrAdaptiveHighlightExposurePolicy.smoothExtraStops(-1f, 0f)

        assertEquals(-0.60f, attacked, 1e-6f)
        assertEquals(-0.88f, released, 1e-6f)
        assertTrue(attacked in NdrAdaptiveHighlightExposurePolicy.MAX_EXTRA_STOPS..0f)
        assertTrue(released in NdrAdaptiveHighlightExposurePolicy.MAX_EXTRA_STOPS..0f)
    }

    @Test
    fun `stale or missing analysis keeps conservative highlight protection`() {
        assertEquals(
            -0.42f,
            NdrAdaptiveHighlightExposurePolicy.resolveAppliedExtraStops(-0.42f, liveMetricsFresh = true),
            1e-6f
        )
        assertEquals(
            NdrAdaptiveHighlightExposurePolicy.NO_ANALYSIS_FALLBACK_EXTRA_STOPS,
            NdrAdaptiveHighlightExposurePolicy.resolveAppliedExtraStops(-0.42f, liveMetricsFresh = false),
            1e-6f
        )
    }
}
