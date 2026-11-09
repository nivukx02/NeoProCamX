package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.recording.HdrImagingMath
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Host-golden for the ycbcr_preview.frag ICtCp round-trip (linearBt2020ToICtCp /
 * ictcpToLinearBt2020) used by chromaDenoise on the live NDR/NIVUKX path.
 *
 * The forward RGB->LMS matrix was non-energy-conserving (rows summed to
 * 1.05/1.067/1.282) and was NOT the inverse of the inverse function's LMS->RGB,
 * so a forward->inverse round-trip shifted hue/luma on EVERY pixel even with zero
 * denoise (audit rank 4). The corrected rows are the standard BT.2100 Rec2020->LMS
 * (energy-conserving, rows sum to 1.0), a true inverse pair. These goldens prove
 * the fixed round-trip is identity and that the OLD matrix was not.
 */
class IctcpRoundTripParityTest {

    // Corrected forward RGB->LMS (standard BT.2100 Rec.2020->LMS, /4096).
    private val fixedLms = arrayOf(
        doubleArrayOf(1688.0, 2146.0, 262.0),
        doubleArrayOf(683.0, 2951.0, 462.0),
        doubleArrayOf(99.0, 309.0, 3688.0)
    )

    // The old (buggy) forward RGB->LMS rows.
    private val buggyLms = arrayOf(
        doubleArrayOf(1688.0, 2146.0, 467.0),
        doubleArrayOf(1880.0, 2048.0, 444.0),
        doubleArrayOf(756.0, 502.0, 3994.0)
    )

    private fun forward(rgb: DoubleArray, lmsRows: Array<DoubleArray>): DoubleArray {
        val s = DoubleArray(3) { maxOf(rgb[it], 0.0) * 203.0 }
        val lms = DoubleArray(3) { i -> (lmsRows[i][0] * s[0] + lmsRows[i][1] * s[1] + lmsRows[i][2] * s[2]) / 4096.0 }
        val p = DoubleArray(3) { HdrImagingMath.pqEncodeFromNits(lms[it]) } // == previewPqOetf
        val i = 0.5 * p[0] + 0.5 * p[1]
        val ct = (6610.0 * p[0] - 13613.0 * p[1] + 7003.0 * p[2]) / 4096.0
        val cp = (17933.0 * p[0] - 17390.0 * p[1] - 543.0 * p[2]) / 4096.0
        return doubleArrayOf(i, ct, cp)
    }

    private fun inverse(ictcp: DoubleArray): DoubleArray {
        val lp = ictcp[0] + 0.008609 * ictcp[1] + 0.111030 * ictcp[2]
        val mp = ictcp[0] - 0.008609 * ictcp[1] - 0.111030 * ictcp[2]
        val sp = ictcp[0] + 0.560031 * ictcp[1] - 0.320627 * ictcp[2]
        val l = HdrImagingMath.pqDecodeToNits(lp) // == previewPqEotf
        val m = HdrImagingMath.pqDecodeToNits(mp)
        val s = HdrImagingMath.pqDecodeToNits(sp)
        val r = 3.4366 * l - 2.5064 * m + 0.0698 * s
        val g = -0.7913 * l + 1.9836 * m - 0.1923 * s
        val b = -0.0259 * l - 0.0989 * m + 1.1248 * s
        return doubleArrayOf(r / 203.0, g / 203.0, b / 203.0)
    }

    private val samples = listOf(
        doubleArrayOf(0.18, 0.18, 0.18),   // neutral grey
        doubleArrayOf(0.5, 0.5, 0.5),
        doubleArrayOf(0.9, 0.2, 0.15),     // red-ish
        doubleArrayOf(0.15, 0.6, 0.2),     // green-ish
        doubleArrayOf(0.1, 0.2, 0.8),      // blue-ish
        doubleArrayOf(0.7, 0.65, 0.2)      // skin-ish
    )

    @Test
    fun fixedForwardRoundTripsToIdentity() {
        for (rgb in samples) {
            val back = inverse(forward(rgb, fixedLms))
            for (c in 0..2) {
                assertTrue(
                    "ICtCp round-trip not identity at ${rgb.toList()}: ch$c ${back[c]} vs ${rgb[c]}",
                    abs(back[c] - rgb[c]) < 5e-3
                )
            }
        }
    }

    @Test
    fun neutralGreyStaysNeutralAfterRoundTrip() {
        // The bug's tell: a neutral grey came back chromatic (blue lifted).
        val back = inverse(forward(doubleArrayOf(0.5, 0.5, 0.5), fixedLms))
        assertTrue("R!=B on neutral: ${back.toList()}", abs(back[0] - back[2]) < 5e-3)
        assertTrue("R!=G on neutral: ${back.toList()}", abs(back[0] - back[1]) < 5e-3)
    }

    @Test
    fun oldMatrixDidNotRoundTrip() {
        // Document that the previous forward matrix genuinely broke the round-trip
        // (otherwise this fix would be a no-op). Neutral grey drifts noticeably.
        val back = inverse(forward(doubleArrayOf(0.5, 0.5, 0.5), buggyLms))
        val maxDrift = (0..2).maxOf { abs(back[it] - 0.5) }
        assertTrue("old matrix should have broken the round-trip, drift=$maxDrift", maxDrift > 0.02)
    }
}
