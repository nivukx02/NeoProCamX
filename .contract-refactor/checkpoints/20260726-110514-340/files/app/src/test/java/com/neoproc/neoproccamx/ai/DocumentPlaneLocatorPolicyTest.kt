package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AF-SD (F11) — pure document-plane locator: centered fallback vs foreground-centroid. */
class DocumentPlaneLocatorPolicyTest {

    private fun leftBiasedSeg(coverage: Float): SegmentationData {
        val w = 8
        val h = 8
        // Left half (ix 0..3) is foreground; right half is background.
        val mask = ByteArray(w * h) { i -> if ((i % w) < 4) 255.toByte() else 0 }
        return SegmentationData(
            maskWidth = w,
            maskHeight = h,
            foregroundMask = mask,
            approximateDepth = FloatArray(w * h),
            foregroundCoverage = coverage,
            subjectDistanceScore = 0.5f
        )
    }

    @Test
    fun nullSegmentation_centeredFallback() {
        val p = DocumentPlaneLocatorPolicy.locate(null, 0.12f)
        assertEquals(0.5f, p.focusX, 1e-4f)
        assertEquals(0.5f, p.focusY, 1e-4f)
        assertEquals(0.5f, p.confidence, 1e-4f)
        assertEquals(0f, p.coverage, 1e-4f)
        assertTrue(p.bounds.left >= 0f && p.bounds.right <= 1f)
    }

    @Test
    fun coverageAboveMin_centroidShiftsLeft() {
        val p = DocumentPlaneLocatorPolicy.locate(leftBiasedSeg(0.5f), 0.12f)
        assertTrue("focus should shift toward the covered left region", p.focusX < 0.45f)
        assertTrue("confidence scales above the fallback 0.5", p.confidence > 0.5f)
        assertEquals(0.5f, p.coverage, 1e-4f)
    }

    @Test
    fun coverageBelowMin_fallsBackCentered() {
        val p = DocumentPlaneLocatorPolicy.locate(leftBiasedSeg(0.05f), 0.12f)
        assertEquals(0.5f, p.focusX, 1e-4f)
        assertEquals(0.5f, p.confidence, 1e-4f)
    }
}
