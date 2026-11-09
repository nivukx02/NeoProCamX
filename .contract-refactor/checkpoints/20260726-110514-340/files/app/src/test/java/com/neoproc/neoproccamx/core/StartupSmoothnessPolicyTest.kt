package com.neoproc.neoproccamx.core

import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSmoothnessPolicyTest {

    @Test
    fun `startup timing budgets stay under one second`() {
        assertTrue(StartupSmoothnessPolicy.featureGateTimeoutMs() <= 1_000L)
        assertTrue(StartupSmoothnessPolicy.autoPrimeDelayMs() <= 300L)
        assertTrue(StartupSmoothnessPolicy.nativePreviewReleaseDelayMs() <= 400L)
        assertTrue(StartupSmoothnessPolicy.aiRampDelayMs(strictHighEnd = false) <= 500L)
        assertTrue(StartupSmoothnessPolicy.aiRampDelayMs(strictHighEnd = true) <= 350L)
        assertTrue(StartupSmoothnessPolicy.aiWarmupDelayMs() in 1_000L..2_000L)
        assertTrue(StartupSmoothnessPolicy.aiWarmupStageDelayMs() in 150L..350L)
    }

    @Test
    fun `strict high end startup ramp is not slower than standard mode`() {
        assertTrue(
            StartupSmoothnessPolicy.aiRampDelayMs(strictHighEnd = true) <=
                StartupSmoothnessPolicy.aiRampDelayMs(strictHighEnd = false)
        )
    }
}
