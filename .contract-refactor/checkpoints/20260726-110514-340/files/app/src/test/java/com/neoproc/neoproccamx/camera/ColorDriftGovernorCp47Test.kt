package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * s2-f04 / CP47 — the confidence-gated dynamic white-point ceiling. Default (full confidence)
 * reproduces today's budget bit-for-bit; a low-confidence estimate tightens the ceiling so the
 * governor moves less. Also locks the flicker-reduction and tracking properties at the governor level.
 */
class ColorDriftGovernorCp47Test {

    private val budget = 0.0060f

    // CP47 ceiling curve: monotone, bounded, full at confidence 1.
    @Test
    fun `confidence ceiling scale is monotone and bounded with full budget at full confidence`() {
        assertEquals(1f, ColorDriftGovernor.confidenceCeilingScale(1f), 0f)
        assertEquals(ColorDriftGovernor.CEILING_CONFIDENCE_FLOOR, ColorDriftGovernor.confidenceCeilingScale(0f), 0f)
        var prev = -1f
        for (i in 0..20) {
            val s = ColorDriftGovernor.confidenceCeilingScale(i / 20f)
            assertTrue("scale in [floor,1]", s in ColorDriftGovernor.CEILING_CONFIDENCE_FLOOR..1f)
            assertTrue("monotone non-decreasing", s >= prev - 1e-7f)
            prev = s
        }
        // out-of-range inputs are clamped, never widen past 1.
        assertEquals(1f, ColorDriftGovernor.confidenceCeilingScale(5f), 0f)
        assertEquals(ColorDriftGovernor.CEILING_CONFIDENCE_FLOOR, ColorDriftGovernor.confidenceCeilingScale(-3f), 0f)
    }

    // NO-DOWNGRADE: the default accept() is identical to accept(confidence = 1f) over a sequence.
    @Test
    fun `default confidence reproduces today's trajectory exactly`() {
        val seq = listOf(
            floatArrayOf(1f, 1f, 1f, 1f),
            floatArrayOf(1.4f, 1f, 1f, 1.6f),
            floatArrayOf(1f, 1f, 1f, 3f),
            floatArrayOf(2.2f, 1f, 1f, 1.1f),
            floatArrayOf(1.3f, 1f, 1f, 1.5f)
        )
        val today = ColorDriftGovernor()
        val full = ColorDriftGovernor()
        seq.forEach { g ->
            today.accept(g[0], g[1], g[2], g[3], budget)
            full.accept(g[0], g[1], g[2], g[3], budget, confidence = 1f)
            assertEquals(today.stabilizedWhitePoint, full.stabilizedWhitePoint)
        }
    }

    // Low confidence tightens the ceiling: the same big jump moves less, bounded by the scaled budget.
    @Test
    fun `low confidence moves the white point less than full confidence`() {
        fun jumpDrift(confidence: Float): Float {
            val g = ColorDriftGovernor()
            g.accept(1f, 1f, 1f, 1f, budget)                  // settle on D65
            return g.accept(1f, 1f, 1f, 3f, budget, confidence)!!.appliedDriftUv
        }
        val full = jumpDrift(1f)
        val low = jumpDrift(0.3f)
        assertTrue("low confidence must move less ($low < $full)", low < full)
        assertTrue("low-confidence drift bounded by scaled budget",
            low <= budget * ColorDriftGovernor.confidenceCeilingScale(0.3f) + 1e-6f)
    }

    // FLICKER REDUCTION (§4.2): governed neutral u'v' σ is a fraction of the raw measured σ.
    @Test
    fun `governing reduces temporal chroma sigma on a jittery neutral scene`() {
        val rawU = ArrayList<Float>(); val rawV = ArrayList<Float>()
        val govU = ArrayList<Float>(); val govV = ArrayList<Float>()
        val gov = ColorDriftGovernor()
        // deterministic small jitter around a steady warm white
        for (i in 0 until 40) {
            val j = if (i % 2 == 0) 0.06f else -0.06f
            val red = 1.4f + j; val blue = 1.6f - j
            val raw = WhiteBalanceChromaticity.rggbGainsToUv(red, 1f, 1f, blue)!!
            rawU += raw.u; rawV += raw.v
            val st = gov.accept(red, 1f, 1f, blue, budget)!!.stabilized
            govU += st.u; govV += st.v
        }
        // compare the second half (after settling) to avoid the first-frame adopt transient
        val rawSigma = sigma(rawU.drop(5)) + sigma(rawV.drop(5))
        val govSigma = sigma(govU.drop(5)) + sigma(govV.drop(5))
        assertTrue("governed σ ($govSigma) must be ≤ half the raw σ ($rawSigma)", govSigma <= 0.5f * rawSigma)
    }

    // TRACKING (§4.3): a genuine step change still converges within a bounded number of frames.
    @Test
    fun `a genuine illuminant step still converges and does not lag forever`() {
        val gov = ColorDriftGovernor()
        gov.accept(1f, 1f, 1f, 1f, budget) // settle on D65
        val target = WhiteBalanceChromaticity.rggbGainsToUv(1f, 1f, 1f, 2.2f)!!
        var convergedAt = -1
        for (frame in 0 until 200) {
            val r = gov.accept(1f, 1f, 1f, 2.2f, budget)!!
            if (r.converged) { convergedAt = frame; break }
        }
        assertTrue("must converge to a real step change (within 200 frames)", convergedAt in 0 until 200)
        val end = gov.stabilizedWhitePoint!!
        val d = sqrt((end.u - target.u) * (end.u - target.u) + (end.v - target.v) * (end.v - target.v))
        assertTrue("settled near the new illuminant", d <= 1e-3f)
    }

    private fun sigma(xs: List<Float>): Float {
        val mean = xs.average().toFloat()
        return sqrt(xs.map { (it - mean) * (it - mean) }.average().toFloat())
    }
}
