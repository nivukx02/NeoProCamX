package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalCameraFallbackPolicyTest {

    @Test
    fun `hard failure falls back immediately`() {
        val decision = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.HARD_FAILURE,
            elapsedMs = 0L
        )

        assertTrue(decision.shouldFallback)
        assertEquals(0L, decision.requiredElapsedMs)
        assertEquals("Camera hard failure", decision.reason)
    }

    @Test
    fun `severe lag waits three seconds`() {
        val early = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.SEVERE_LAG,
            elapsedMs = 2999L
        )
        val ready = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.SEVERE_LAG,
            elapsedMs = 3000L
        )

        assertFalse(early.shouldFallback)
        assertTrue(ready.shouldFallback)
        assertEquals(3000L, ready.requiredElapsedMs)
    }

    @Test
    fun `encoder pressure waits ten seconds`() {
        val decision = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.ENCODER_PRESSURE,
            elapsedMs = 10000L
        )

        assertTrue(decision.shouldFallback)
        assertEquals(10000L, decision.requiredElapsedMs)
    }

    @Test
    fun `thermal degradation waits thirty seconds`() {
        val decision = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.THERMAL_DEGRADATION,
            elapsedMs = 30000L
        )

        assertTrue(decision.shouldFallback)
        assertEquals(30000L, decision.requiredElapsedMs)
    }
}
