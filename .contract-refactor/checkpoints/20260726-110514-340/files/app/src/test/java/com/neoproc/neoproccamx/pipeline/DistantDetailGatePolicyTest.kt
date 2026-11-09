package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.pipeline.DistantDetailGatePolicy.DistantDetailGateDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-QV5 / F05 — locks the distant-detail gate decision. The DEFAULT operating point must leave
 * every gate OFF with tuning floats equal to the shader spec-constant DEFAULTS (no-downgrade lock);
 * high ISO / low SNR must engage the noise-aware rolloff; Extreme-Detail + a distant-detail request
 * must engage BOTH the halo guard and the guided edge-tighten.
 */
class DistantDetailGatePolicyTest {

    @Test
    fun defaultInputProducesBaselineAllGatesOffAtShaderDefaults() {
        val d = DistantDetailGatePolicy.resolve(
            iso = 100,
            extremeDetail = false,
            snrDb = 48f,
            distantDetailRequested = false
        )
        assertFalse("noise-aware must be OFF by default", d.detailNoiseAware)
        assertFalse("halo guard must be OFF by default", d.detailHaloGuard)
        assertFalse("edge-tighten must be OFF by default", d.guidedEdgeTighten)
        // Tuning floats must equal the shader spec-constant defaults, bit-for-bit.
        assertEquals(DistantDetailGatePolicy.DEFAULT_NOISE_SIGMA, d.noiseSigma, 0f)
        assertEquals(DistantDetailGatePolicy.DEFAULT_EDGE_HI, d.edgeHi, 0f)
        assertEquals(DistantDetailGatePolicy.DEFAULT_TIGHTEN_K, d.tightenK, 0f)
        assertTrue("default must be the no-downgrade baseline", d.isBaseline)
        assertEquals(DistantDetailGateDecision.BASELINE, d)
    }

    @Test
    fun highIsoEngagesNoiseAwareOnly() {
        val d = DistantDetailGatePolicy.resolve(
            iso = 6400,
            extremeDetail = false,
            snrDb = 48f,
            distantDetailRequested = false
        )
        assertTrue("high ISO must engage the noise-aware rolloff", d.detailNoiseAware)
        assertFalse("high ISO alone must not engage the halo guard", d.detailHaloGuard)
        assertFalse("high ISO alone must not engage edge-tighten", d.guidedEdgeTighten)
        assertFalse("an engaged gate is not the baseline", d.isBaseline)
    }

    @Test
    fun lowSnrEngagesNoiseAwareEvenAtLowIso() {
        val d = DistantDetailGatePolicy.resolve(
            iso = 100,
            extremeDetail = false,
            snrDb = 18f,
            distantDetailRequested = false
        )
        assertTrue("low SNR must engage the noise-aware rolloff", d.detailNoiseAware)
        assertFalse(d.detailHaloGuard)
        assertFalse(d.guidedEdgeTighten)
    }

    @Test
    fun nonFiniteSnrIsTreatedAsHealthyNotNoisy() {
        val d = DistantDetailGatePolicy.resolve(
            iso = 100,
            extremeDetail = false,
            snrDb = Float.NaN,
            distantDetailRequested = false
        )
        assertFalse("a missing SNR must not fabricate a gate", d.detailNoiseAware)
        assertTrue(d.isBaseline)
    }

    @Test
    fun extremeDetailWithDistantRequestEngagesHaloGuardAndEdgeTighten() {
        val d = DistantDetailGatePolicy.resolve(
            iso = 100,
            extremeDetail = true,
            snrDb = 48f,
            distantDetailRequested = true
        )
        assertTrue("distant-detail elite must engage the halo guard", d.detailHaloGuard)
        assertTrue("distant-detail elite must engage the guided edge-tighten", d.guidedEdgeTighten)
        assertFalse("clean footage must not engage the noise-aware rolloff", d.detailNoiseAware)
        // Even with gates ON, the tuning floats stay at the shader defaults.
        assertEquals(DistantDetailGatePolicy.DEFAULT_EDGE_HI, d.edgeHi, 0f)
        assertEquals(DistantDetailGatePolicy.DEFAULT_TIGHTEN_K, d.tightenK, 0f)
    }

    @Test
    fun extremeDetailWithoutDistantRequestStaysBaseline() {
        val d = DistantDetailGatePolicy.resolve(
            iso = 100,
            extremeDetail = true,
            snrDb = 48f,
            distantDetailRequested = false
        )
        assertFalse("Extreme-Detail alone must not flip the halo guard", d.detailHaloGuard)
        assertFalse("Extreme-Detail alone must not flip edge-tighten", d.guidedEdgeTighten)
        assertTrue(d.isBaseline)
    }

    @Test
    fun distantRequestWithoutExtremeDetailStaysBaseline() {
        val d = DistantDetailGatePolicy.resolve(
            iso = 100,
            extremeDetail = false,
            snrDb = 48f,
            distantDetailRequested = true
        )
        assertFalse("a distant request without Extreme-Detail must not flip the halo guard", d.detailHaloGuard)
        assertFalse("a distant request without Extreme-Detail must not flip edge-tighten", d.guidedEdgeTighten)
        assertTrue(d.isBaseline)
    }

    @Test
    fun highIsoPlusDistantEliteEngagesAllThreeGates() {
        val d = DistantDetailGatePolicy.resolve(
            iso = 6400,
            extremeDetail = true,
            snrDb = 20f,
            distantDetailRequested = true
        )
        assertTrue(d.detailNoiseAware)
        assertTrue(d.detailHaloGuard)
        assertTrue(d.guidedEdgeTighten)
    }
}
