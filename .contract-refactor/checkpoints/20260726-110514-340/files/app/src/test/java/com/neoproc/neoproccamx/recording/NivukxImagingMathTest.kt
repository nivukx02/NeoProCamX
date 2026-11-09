package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

class NivukxImagingMathTest {

    @Test
    fun percentileAnchorsResolveOrderedToeKneeShoulder() {
        val luminance = listOf(
            0.02, 0.04, 0.08, 0.12, 0.20, 0.35, 0.55, 0.80,
            1.10, 2.0, 6.0, 12.0, 48.0, 120.0, 350.0, 900.0,
            1_800.0, 3_600.0, 7_200.0, 9_200.0
        )

        val anchors = NivukxImagingMath.resolveToneAnchors(
            luminanceNits = luminance,
            peakNits = 10_000.0
        )

        assertTrue(anchors.p10 <= anchors.p50)
        assertTrue(anchors.p50 <= anchors.p90)
        assertTrue(anchors.p90 <= anchors.p99)
        assertTrue(anchors.toe < anchors.p50)
        assertTrue(anchors.knee > anchors.p50)
        assertTrue(anchors.shoulder > anchors.knee)
        // gammaToe is now concave (<1) so the toe LIFTS rather than crushes.
        assertTrue(anchors.gammaToe in 0.83..0.93)
        assertTrue(anchors.kappa in 1.0..2.5)
    }

    @Test
    fun toeBranchLiftsShadowsNeverCrushes() {
        val anchors = NivukxImagingMath.resolveToneAnchors(
            luminanceNits = listOf(
                0.02, 0.05, 0.1, 0.3, 1.0, 5.0, 40.0, 200.0, 900.0, 3_000.0, 9_000.0
            ),
            peakNits = 10_000.0
        )
        // Concave toe (gammaToe < 1): every toe-region output must be >= identity.
        var l = anchors.toe * 0.05
        while (l <= anchors.toe + 1e-12) {
            val mapped = NivukxImagingMath.toneMapNits(l, anchors)
            assertTrue("toe must lift, not crush, at l=$l: $mapped", mapped >= l - 1e-9)
            l += anchors.toe * 0.05
        }
        // Join is continuous: at l == toe the toe branch returns exactly toe.
        assertEquals(anchors.toe, NivukxImagingMath.toneMapNits(anchors.toe, anchors), 1e-9)
    }

    @Test
    fun kneeCeilingTracksMasterPeakNotHardcoded4000() {
        val anchors = NivukxImagingMath.resolveToneAnchors(
            luminanceNits = listOf(
                100.0, 500.0, 2_000.0, 5_000.0, 7_000.0, 8_000.0, 9_000.0, 9_500.0
            ),
            peakNits = 10_000.0
        )
        // A bright 10k-nit master must keep its knee above the old hard 4000 cap.
        assertTrue("knee crushed at legacy 4000 cap: ${anchors.knee}", anchors.knee > 4_000.0)
        assertTrue(anchors.knee <= 10_000.0 * 0.85 + 1e-6)
        assertTrue(anchors.shoulder > anchors.knee)
    }

    @Test
    fun threeAnchorToneMapIsMonotonicAndRollsHighlightsWithoutFlatClip() {
        val anchors = NivukxImagingMath.ToneAnchors(
            p01 = 0.02,
            p10 = 0.15,
            p50 = 80.0,
            p90 = 900.0,
            p99 = 4_000.0,
            p999 = 6_000.0,
            p9999 = 8_000.0,
            toe = 0.08,
            knee = 1_985.0,
            shoulder = 7_000.0,
            gammaToe = 1.12,
            kappa = 1.7,
            peakNits = 10_000.0
        )

        val mapped = listOf(0.02, 0.08, 100.0, 1_985.0, 3_000.0, 6_000.0, 9_000.0)
            .map { NivukxImagingMath.toneMapNits(it, anchors) }

        mapped.zipWithNext().forEach { (a, b) ->
            assertTrue("tone map must be monotonic: $a <= $b", a <= b)
        }
        assertEquals(100.0, mapped[2], 1e-6)
        assertEquals(1_985.0, mapped[3], 1e-6)
        assertTrue(mapped[4] < 3_000.0)
        assertTrue(mapped[5] > mapped[4])
        assertTrue(mapped.last() <= anchors.shoulder)
    }

    @Test
    fun pqRoundTripPreservesNitsForBt2100Transport() {
        val encoded = NivukxImagingMath.pqEncodeFromNits(1_000.0)
        val decoded = NivukxImagingMath.pqDecodeToNits(encoded)

        assertEquals(1_000.0, decoded, 1.0)
    }

    @Test
    fun radialJzAzBzCompressionPreservesHueAngleAndLightness() {
        val original = NivukxImagingMath.JzAzBz(jz = 0.82, az = 0.42, bz = 0.31)
        val compressed = NivukxImagingMath.compressChromaRadially(
            color = original,
            chromaBoundary = 0.40,
            softKneeOnset = 0.90
        )

        val originalHue = atan2(original.bz, original.az)
        val compressedHue = atan2(compressed.bz, compressed.az)

        assertEquals(original.jz, compressed.jz, 1e-9)
        assertEquals(originalHue, compressedHue, 1e-9)
        assertTrue(NivukxImagingMath.chroma(compressed) <= 0.40 + 1e-9)
        assertTrue(NivukxImagingMath.chroma(compressed) < NivukxImagingMath.chroma(original))
    }
}
