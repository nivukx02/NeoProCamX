package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSessionHardwareImportPrimingPolicyTest {
    @Test
    fun `eligible processed video primes native P010 source`() {
        assertTrue(decide())
    }

    @Test
    fun `RAW sensor and unsupported P010 paths remain fail closed`() {
        assertFalse(decide(sensorLinearNdr = true))
        assertFalse(decide(nativeP010SourceSupported = false))
    }

    @Test
    fun `non video and non offscreen paths are not mutated`() {
        assertFalse(decide(videoModeActive = false))
        assertFalse(decide(idlePrearmEligible = false))
        assertFalse(decide(offscreenHardwareImportFeed = false))
        assertFalse(decide(featureEnabled = false))
    }

    private fun decide(
        featureEnabled: Boolean = true,
        videoModeActive: Boolean = true,
        idlePrearmEligible: Boolean = true,
        offscreenHardwareImportFeed: Boolean = true,
        sensorLinearNdr: Boolean = false,
        nativeP010SourceSupported: Boolean = true
    ): Boolean = VideoSessionHardwareImportPrimingPolicy.shouldPrime(
        featureEnabled = featureEnabled,
        videoModeActive = videoModeActive,
        idlePrearmEligible = idlePrearmEligible,
        offscreenHardwareImportFeed = offscreenHardwareImportFeed,
        sensorLinearNdr = sensorLinearNdr,
        nativeP010SourceSupported = nativeP010SourceSupported
    )
}
