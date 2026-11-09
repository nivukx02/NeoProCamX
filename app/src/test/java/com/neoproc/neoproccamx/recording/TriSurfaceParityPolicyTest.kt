package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f06 — tri-surface ΔE2000 parity measurement. Expected ΔE2000 values are produced by the real
 * [ImageQualityMetrics.colorCheckerDeltaE2000], never invented.
 */
class TriSurfaceParityPolicyTest {

    private fun checker(aShift: Float = 0f): List<FloatArray> = listOf(
        floatArrayOf(38f, 14f + aShift, 15f),
        floatArrayOf(66f, 13f, 17f),
        floatArrayOf(51f, -10f, -23f),
        floatArrayOf(43f, -16f, 22f)
    )

    @Test
    fun identicalSurfacesMeasureZeroDeltaE2000AndWithinTolerance() {
        val lab = checker()
        val m = TriSurfaceParityPolicy.measure(lab, checker(), checker())
        assertTrue(m.measured)
        assertEquals(0.0, m.worstMax, 1e-6)
        assertEquals(0.0, m.worstMean, 1e-6)
        assertTrue(m.withinTolerance)
    }

    @Test
    fun perturbedExportSurfaceRaisesWorstDeltaAboveToleranceAndFailsWithin() {
        val export = checker(aShift = 30f)
        val expectedMax = ImageQualityMetrics.colorCheckerDeltaE2000(checker(), export).second
        val m = TriSurfaceParityPolicy.measure(checker(), checker(), export)
        assertTrue(m.measured)
        assertEquals(expectedMax, m.worstMax, 1e-9)
        assertTrue("worstMax must exceed tolerance", m.worstMax > TriSurfaceParityPolicy.MAX_TRI_SURFACE_DELTAE2000_MAX)
        assertFalse(m.withinTolerance)
    }

    @Test
    fun notProvenHasMeasuredFalseAndDoesNotSynthesizePass() {
        val np = TriSurfaceParityPolicy.notProven()
        assertFalse(np.measured)
        assertFalse(np.withinTolerance)
    }

    @Test
    fun mismatchedOrEmptyInputsYieldNotProven() {
        assertFalse(TriSurfaceParityPolicy.measure(emptyList(), emptyList(), emptyList()).measured)
        assertFalse(
            TriSurfaceParityPolicy.measure(checker(), checker().drop(1), checker()).measured
        )
    }
}
