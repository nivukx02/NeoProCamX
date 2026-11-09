package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveThermalKalmanFilterTest {

    @Test
    fun `high thermal pressure reports cooldown estimate`() {
        val filter = PredictiveThermalKalmanFilter()

        filter.updateWithMeasurement(0.95f)
        val decision = filter.updateWithMeasurement(0.95f)

        assertTrue(decision.mitigationActive)
        assertNotNull(decision.cooldownEstimateSec)
        assertTrue(decision.cooldownEstimateSec!! > 0)
    }

    @Test
    fun `cooling trajectory reduces cooldown estimate`() {
        val filter = PredictiveThermalKalmanFilter()

        filter.updateWithMeasurement(0.90f)
        val hotDecision = filter.updateWithMeasurement(0.90f)
        filter.updateWithMeasurement(0.45f)
        val coolingDecision = filter.updateWithMeasurement(0.25f)

        val hotEstimate = hotDecision.cooldownEstimateSec ?: Int.MAX_VALUE
        val coolEstimate = coolingDecision.cooldownEstimateSec ?: 0
        assertTrue(coolEstimate <= hotEstimate)
        assertTrue(coolingDecision.trend != PredictiveThermalKalmanFilter.ThermalTrend.HEATING)
    }

    @Test
    fun `diagnostic summary includes trend and cooldown`() {
        val filter = PredictiveThermalKalmanFilter()

        filter.updateWithMeasurement(0.80f)
        val summary = filter.diagnosticSummary()

        assertTrue(summary.contains("trend="))
        assertTrue(summary.contains("cooldown="))
    }
}
