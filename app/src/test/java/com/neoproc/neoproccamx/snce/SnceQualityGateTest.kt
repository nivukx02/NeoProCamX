package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceQualityGateTest {

    // Defaults are genuinely elite-level errors: the directive's Part IX score is strict
    // (every non-zero error subtracts), so only near-zero deltas clear the >=98 elite bar.
    private fun metrics(
        dEMean: Float = 0.05f,
        dEMax: Float = 0.2f,
        hueRmse: Float = 0.1f,
        chromaRmse: Float = 0.05f,
        skinDeltaE: Float = 0.05f,
        temporalDeltaE: Float = 0.01f,
        angularDeltaE: Float = 0.02f,
        gamutP3: Float = 1.0f,
        gamut2020: Float = 0.95f
    ) = SnceQualityGate.FrameMetrics(
        dEMean, dEMax, hueRmse, chromaRmse, skinDeltaE, temporalDeltaE, angularDeltaE, gamutP3, gamut2020
    )

    @Test
    fun `a near-perfect frame scores elite and passes every gate`() {
        val r = SnceQualityGate.evaluate(metrics())
        assertEquals(SnceQualityGate.Tier.ELITE, r.tier)
        assertTrue(r.snceScore >= 98f)
        assertTrue(r.passDeltaE && r.passTemporal && r.passAngular && r.passGamut)
        assertEquals(SnceQualityGate.AdaptiveAction.NONE, r.directive.action)
        assertTrue(!r.directive.reprocess)
    }

    @Test
    fun `scores stay within 0 to 100`() {
        val terrible = metrics(
            dEMean = 20f, dEMax = 40f, hueRmse = 30f, chromaRmse = 20f,
            skinDeltaE = 10f, temporalDeltaE = 5f, angularDeltaE = 10f, gamutP3 = 0f, gamut2020 = 0f
        )
        val r = SnceQualityGate.evaluate(terrible)
        assertTrue(r.snceScore in 0f..100f)
        assertTrue(r.pcqs in 0f..100f)
        assertEquals(SnceQualityGate.Tier.FAIL, r.tier)
    }

    @Test
    fun `failed frame requests reprocessing until the iteration cap`() {
        val bad = metrics(dEMean = 4f, dEMax = 6f)
        val first = SnceQualityGate.evaluate(bad, iteration = 0)
        val third = SnceQualityGate.evaluate(bad, iteration = 2)
        assertTrue("first pass reprocesses", first.directive.reprocess)
        assertTrue("capped after two iterations", !third.directive.reprocess)
    }

    @Test
    fun `hue-dominated failure strengthens CAM16 and memory`() {
        val r = SnceQualityGate.evaluate(
            metrics(dEMean = 1.0f, dEMax = 1.5f, hueRmse = 4f, chromaRmse = 0.5f, temporalDeltaE = 0.1f, angularDeltaE = 0.2f)
        )
        assertEquals(SnceQualityGate.Tier.FAIL, r.tier)
        assertEquals(SnceQualityGate.AdaptiveAction.STRENGTHEN_CAM16_MEMORY, r.directive.action)
    }

    @Test
    fun `temporal-dominated failure reduces AWB alpha`() {
        val r = SnceQualityGate.evaluate(
            metrics(dEMean = 0.5f, dEMax = 1.0f, hueRmse = 0.4f, temporalDeltaE = 0.7f, angularDeltaE = 0.2f)
        )
        assertEquals(SnceQualityGate.AdaptiveAction.REDUCE_AWB_ALPHA, r.directive.action)
        assertTrue("temporal gate fails", !r.passTemporal)
    }

    @Test
    fun `gamut gate fails when P3 coverage is short`() {
        val r = SnceQualityGate.evaluate(metrics(gamutP3 = 0.80f))
        assertTrue(!r.passGamut)
    }
}
