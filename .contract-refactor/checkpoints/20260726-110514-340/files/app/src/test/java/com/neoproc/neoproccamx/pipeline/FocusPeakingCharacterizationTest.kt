package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s5-f09 / CP-F10 — golden/characterization lock for the SINGLE live peaking
 * producer (FrameAnalysisMath.buildFocusPeakingMask), the CPU-computed overlay
 * alpha mask consumed by the preview/pipeline. Proves:
 *  - edge-magnitude (Sobel-class) keying: peaking keys on local gradient, NOT luma
 *  - intensity/threshold mapping monotonicity
 *  - subject-mask confinement: a strong edge outside the focus region is suppressed
 *  - NON-DESTRUCTIVE: the source luma buffer is never mutated (recorded/encoded
 *    pixels are bit-for-bit identical peaking-on vs peaking-off)
 */
class FocusPeakingCharacterizationTest {

    private inline fun <T> withStrict(enabled: Boolean, block: () -> T): T {
        val prevEnabled = HighEndQualityPolicy.enabled
        val prevStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = enabled
        HighEndQualityPolicy.strictHighEndOnly = enabled
        try {
            return block()
        } finally {
            HighEndQualityPolicy.enabled = prevEnabled
            HighEndQualityPolicy.strictHighEndOnly = prevStrict
        }
    }

    // 5x5 frame: columns 0..1 dark (0), columns 2..4 bright (255).
    // A single vertical edge between column 1 and column 2.
    private fun verticalEdgeFrame(): ByteArray {
        val b0 = 0.toByte()
        val bF = (-1).toByte() // 255
        return byteArrayOf(
            b0, b0, bF, bF, bF,
            b0, b0, bF, bF, bF,
            b0, b0, bF, bF, bF,
            b0, b0, bF, bF, bF,
            b0, b0, bF, bF, bF
        )
    }

    @Test
    fun `keys on edge magnitude not luma — flat bright cell stays zero while edge saturates`() {
        withStrict(enabled = false) {
            val luma = verticalEdgeFrame()
            val mask = FrameAnalysisMath.buildFocusPeakingMask(
                width = 5,
                height = 5,
                luma = luma,
                maxMaskLongEdge = 320,
                peakingIntensity = 1f
            )
            assertEquals(5, mask.maskWidth)
            assertEquals(5, mask.maskHeight)

            // Edge column (x=1) at the middle row keys strongly.
            val edge = mask.alphaMask[2 * 5 + 1].toInt() and 0xFF
            // Flat-but-BRIGHT interior cell (x=3, all neighbours 255) keys zero —
            // proves keying is on gradient, not brightness.
            val flatBright = mask.alphaMask[2 * 5 + 3].toInt() and 0xFF

            assertEquals("sharp edge must saturate", 255, edge)
            assertEquals("flat bright region must not peak", 0, flatBright)
            assertTrue(edge > flatBright)
        }
    }

    @Test
    fun `golden alpha for the vertical-edge fixture is locked`() {
        withStrict(enabled = false) {
            val mask = FrameAnalysisMath.buildFocusPeakingMask(
                width = 5,
                height = 5,
                luma = verticalEdgeFrame(),
                maxMaskLongEdge = 320,
                peakingIntensity = 1f
            )
            // Interior keying cells (x,y in 1..3). Edge sits at column 1 (gx across
            // cols 0->2) and column 2 has no horizontal gradient in this frame.
            // Locked current output of buildFocusPeakingMask (border row/col are 0).
            val a = IntArray(25) { mask.alphaMask[it].toInt() and 0xFF }
            // Border is always zero.
            for (x in 0 until 5) {
                assertEquals("top border", 0, a[x])
                assertEquals("bottom border", 0, a[4 * 5 + x])
            }
            for (y in 0 until 5) {
                assertEquals("left border", 0, a[y * 5])
                assertEquals("right border", 0, a[y * 5 + 4])
            }
            // Column 1 (the gradient column) saturates for interior rows 1..3.
            assertEquals(255, a[1 * 5 + 1])
            assertEquals(255, a[2 * 5 + 1])
            assertEquals(255, a[3 * 5 + 1])
            // The central-difference gradient straddles the edge (spans x=1 and x=2), so column 2
            // also saturates; only column 3 (and border col 4) is truly flat -> zero.
            assertEquals(255, a[2 * 5 + 2])
            assertEquals(0, a[2 * 5 + 3])
        }
    }

    @Test
    fun `intensity raises response — lower intensity never peaks more than higher`() {
        withStrict(enabled = false) {
            val luma = verticalEdgeFrame()
            val low = FrameAnalysisMath.buildFocusPeakingMask(
                width = 5, height = 5, luma = luma, maxMaskLongEdge = 320,
                peakingIntensity = 0f
            )
            val high = FrameAnalysisMath.buildFocusPeakingMask(
                width = 5, height = 5, luma = luma, maxMaskLongEdge = 320,
                peakingIntensity = 1f
            )
            for (i in 0 until 25) {
                val lo = low.alphaMask[i].toInt() and 0xFF
                val hi = high.alphaMask[i].toInt() and 0xFF
                assertTrue("intensity should be monotonic at $i (lo=$lo hi=$hi)", lo <= hi)
            }
        }
    }

    @Test
    fun `subject-mask confinement — strong edge outside the focus region is suppressed`() {
        withStrict(enabled = false) {
            // 9-wide frame: dark left, bright right -> edge near column 4/5.
            val b0 = 0.toByte()
            val bF = (-1).toByte()
            val row = byteArrayOf(b0, b0, b0, b0, b0, bF, bF, bF, bF)
            val luma = ByteArray(9 * 9)
            for (y in 0 until 9) System.arraycopy(row, 0, luma, y * 9, 9)

            val unconfined = FrameAnalysisMath.buildFocusPeakingMask(
                width = 9, height = 9, luma = luma, maxMaskLongEdge = 320,
                peakingIntensity = 1f
            )
            // Focus region tight on the FAR LEFT (x=0), away from the edge column ~5.
            val confined = FrameAnalysisMath.buildFocusPeakingMask(
                width = 9, height = 9, luma = luma, maxMaskLongEdge = 320,
                peakingIntensity = 1f,
                focusRegion = Triple(0.0f, 0.5f, 0.02f)
            )

            // The edge column (x=5) peaks without confinement…
            val edgeUnconfined = unconfined.alphaMask[4 * 9 + 5].toInt() and 0xFF
            // …and is fully suppressed (alpha 0) once confined far from it.
            val edgeConfined = confined.alphaMask[4 * 9 + 5].toInt() and 0xFF

            assertTrue("edge must peak unconfined", edgeUnconfined > 0)
            assertEquals("edge outside the mask must not peak", 0, edgeConfined)
        }
    }

    @Test
    fun `non-destructive — buildFocusPeakingMask never mutates the source luma`() {
        withStrict(enabled = false) {
            val luma = verticalEdgeFrame()
            val before = luma.copyOf()
            FrameAnalysisMath.buildFocusPeakingMask(
                width = 5, height = 5, luma = luma, maxMaskLongEdge = 320,
                peakingIntensity = 1f,
                focusRegion = Triple(0.5f, 0.5f, 0.2f)
            )
            // Recorded/encoded pixels read this same luma; peaking must be overlay-only.
            assertArrayEquals(
                "peaking must not alter source pixels (peaking-on == peaking-off)",
                before,
                luma
            )
        }
    }

    @Test
    fun `non-destructive holds in strict high-end mode too`() {
        withStrict(enabled = true) {
            val luma = verticalEdgeFrame()
            val before = luma.copyOf()
            FrameAnalysisMath.buildFocusPeakingMask(
                width = 5, height = 5, luma = luma, maxMaskLongEdge = 320,
                peakingIntensity = 0.8f
            )
            assertArrayEquals(before, luma)
        }
    }
}
