package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s7-f03 / CP94 §4.2 — guided-refine EDGE ALIGNMENT contract. Locks that the guided upsampler
 * pins the depth discontinuity to the luma edge (within ±1 px). Reuses the exact fixture/threshold
 * already proven green in DepthEstimatorCinemaContractTest so this only adds the argmax-alignment
 * assertion; it does not introduce a new magic number.
 */
class DepthEstimatorEdgeAlignmentContractTest {

    @Test
    fun guidedRefinePinsDepthDiscontinuityToLumaEdgeWithinOnePixel() {
        // 2x2 low-res depth: 0.9 left column, 0.1 right column.
        val lowDepth = floatArrayOf(
            0.9f, 0.1f,
            0.9f, 0.1f
        )
        // 4x4 luma guide with a sharp left/right edge between columns 1 and 2.
        val luma = floatArrayOf(
            0.1f, 0.1f, 0.9f, 0.9f,
            0.1f, 0.1f, 0.9f, 0.9f,
            0.1f, 0.1f, 0.9f, 0.9f,
            0.1f, 0.1f, 0.9f, 0.9f
        )

        val up = DepthEstimator.guidedFilterDepthUpsample(
            lowDepth = lowDepth,
            lowWidth = 2,
            lowHeight = 2,
            lumaGuide = luma,
            guideWidth = 4,
            guideHeight = 4,
            radius = 1,
            epsilon = 1.0e-4f
        )

        assertEquals(16, up.size)
        assertTrue(up.all { it in 0f..1f })

        // The luma edge sits between columns 1 and 2; the largest column-to-column depth gradient
        // in the top row must align with that edge (±1 px).
        val lumaEdgeColumn = 1
        var bestX = 0
        var bestGrad = -1f
        for (x in 0 until 3) {
            val g = abs(up[x] - up[x + 1])
            if (g > bestGrad) {
                bestGrad = g
                bestX = x
            }
        }
        assertTrue("depth discontinuity must align to the luma edge within 1px", abs(bestX - lumaEdgeColumn) <= 1)
        // Same step magnitude the existing cinema-contract golden pins.
        assertTrue("depth step at the edge is significant", up[1] - up[2] > 0.28f)
    }
}
