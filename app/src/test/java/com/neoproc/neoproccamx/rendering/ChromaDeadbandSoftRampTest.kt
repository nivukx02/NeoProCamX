package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-golden for the soft chroma deadband in neo_p010_export_enhance.comp
 * (applyChromaDeadband, audit rank 28).
 *
 * The shader used to hard-snap every sub-threshold chroma to exactly neutral 512,
 * which printed a visible chroma contour where a smooth desaturated gradient
 * crossed the threshold. The fix multiplies the retain by smoothstep(0,threshold,
 * |delta|), so chroma fades to neutral gradually and joins the above-threshold
 * region continuously (smoothstep == 1 at the threshold).
 */
class ChromaDeadbandSoftRampTest {

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        if (e0 == e1) return if (x >= e1) 1.0 else 0.0
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * Port of the sub-threshold behavior with a NEUTRAL (512) neighbourhood — the isolated-chroma
     * noise case. Here the colour-preserving shader `mix(neighborAverage, chroma, retain)` reduces to
     * `512 + delta*retain`, so this oracle still exactly mirrors the shader for neutral surrounds.
     */
    private fun deadband(chroma: Int, threshold: Int, baseRetain: Double): Int =
        deadband(chroma, neighborAverage = 512, threshold = threshold, baseRetain = baseRetain)

    /**
     * Port of the colour-preserving deadband: blend toward the LOCAL neighbourhood chroma average
     * instead of toward neutral 512, so real colour survives while chroma noise is smoothed.
     * retain==1 keeps the sample; retain==0 falls back to neighborAverage.
     */
    private fun deadband(chroma: Int, neighborAverage: Int, threshold: Int, baseRetain: Double): Int {
        val delta = chroma - 512
        var retain = baseRetain
        if (threshold > 0) retain *= smoothstep(0.0, threshold.toDouble(), kotlin.math.abs(delta).toDouble())
        return (neighborAverage + (chroma - neighborAverage) * retain).coerceIn(0.0, 1023.0).toInt()
    }

    @Test
    fun neutralStaysNeutral() {
        assertEquals(512, deadband(512, 40, 0.9))
    }

    @Test
    fun subThresholdIsNotHardSnappedToNeutral() {
        // The old code returned exactly 512 for any |delta| <= threshold. The ramp
        // must retain SOME chroma just below the threshold (no hard contour).
        val threshold = 40
        val nearThreshold = 512 + 36 // |delta|=36 < 40
        val out = deadband(nearThreshold, threshold, 0.9)
        assertTrue("sub-threshold chroma was hard-snapped to neutral: $out", out > 512)
        assertTrue(out < nearThreshold) // still attenuated
    }

    @Test
    fun rampIsMonotoneAcrossTheBand() {
        val threshold = 60
        var prev = -1
        var c = 512
        while (c <= 512 + 120) {
            val out = deadband(c, threshold, 0.9)
            assertTrue("non-monotone deadband at chroma=$c: $out < $prev", out >= prev)
            prev = out
            c += 1
        }
    }

    @Test
    fun continuousAtThreshold() {
        // At exactly the threshold, smoothstep == 1 so the sub- and above-threshold
        // branches agree (no step).
        val threshold = 50
        val atThreshold = deadband(512 + threshold, threshold, 0.9)
        val expectedFullRetain = (512.0 + threshold * 0.9).toInt()
        assertEquals(expectedFullRetain.toDouble(), atThreshold.toDouble(), 1.0)
    }

    @Test
    fun realColourSurvivesWhenNeighboursAreColoured() {
        // The black-and-white bug: with a very high chromaDenoise the retain floor is ~0.0005, so the
        // old `512 + delta*retain` crushed EVERY chroma sample to neutral gray — even real colour whose
        // neighbourhood is also colourful. Blending toward the coloured neighbourhood average preserves
        // that real colour instead of destroying it.
        val neighbourAverage = 640 // a genuinely coloured local region (delta +128 from neutral)
        val chroma = 648
        val nearZeroRetain = 0.0005
        val out = deadband(chroma, neighbourAverage, threshold = 0, baseRetain = nearZeroRetain)
        // Old behaviour would have returned ~512 (grayscale). New behaviour keeps the local colour.
        assertTrue("real colour was crushed to gray: out=$out", out > 600)
    }

    @Test
    fun isolatedChromaSpikeInNeutralSurroundStillNeutralizes() {
        // Noise rejection must be preserved: an isolated chroma spike whose neighbours are neutral
        // (~512) still collapses to neutral, because the blend target IS the neutral neighbourhood.
        val out = deadband(chroma = 690, neighborAverage = 512, threshold = 0, baseRetain = 0.0005)
        assertTrue("isolated chroma noise was not neutralized: out=$out", kotlin.math.abs(out - 512) <= 1)
    }
}
