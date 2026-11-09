package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s7-f10 / CP9A — pure-Float hardening primitives. Hand-computable (no RectF), exact.
 */
class PredictiveTrackerHardeningTest {

    @Test
    fun `mahalanobis gate rejects outlier beyond threshold and accepts inlier`() {
        // d = sqrt(0.02^2 / 0.01) = sqrt(0.04) = 0.2 < 3 -> inlier
        assertTrue(PredictiveTrackerHardening.isInnovationInlier(0.02f, 0f, 0.01f, 0.01f, 3.0f))
        // d = sqrt(0.5^2 / 0.01) = sqrt(25) = 5 > 3 -> outlier
        assertFalse(PredictiveTrackerHardening.isInnovationInlier(0.5f, 0f, 0.01f, 0.01f, 3.0f))
    }

    @Test
    fun `mahalanobis distance floors degenerate covariance without NaN or Inf`() {
        val d = PredictiveTrackerHardening.mahalanobisDistance(0.02f, 0f, 0f, 0f)
        // covariance floored to 0.001 -> d = sqrt(0.0004 / 0.001) = sqrt(0.4)
        assertTrue(d.isFinite())
        assertEquals(Math.sqrt(0.4).toFloat(), d, 1e-4f)
    }

    @Test
    fun `coast decay is monotonic and bounded`() {
        assertEquals(0.82f, PredictiveTrackerHardening.decayConfidence(1.0f, 0.82f), 1e-6f)
        var c = 1.0f
        repeat(20) {
            val next = PredictiveTrackerHardening.decayConfidence(c, 0.82f)
            assertTrue("non-increasing", next <= c)
            assertTrue("in [0,1]", next in 0f..1f)
            c = next
        }
    }

    @Test
    fun `coast exhausts exactly at budget`() {
        assertFalse(PredictiveTrackerHardening.coastExhausted(7, 8))
        assertTrue(PredictiveTrackerHardening.coastExhausted(8, 8))
        assertFalse(PredictiveTrackerHardening.coastExhausted(8, 10))
    }
}
