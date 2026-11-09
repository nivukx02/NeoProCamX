package com.neoproc.neoproccamx.ai

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f02 / CP93 — gate defaults ON (activated 2026-07-03); OFF restores bit-for-bit legacy. */
class FaceTrackerHardeningFlagTest {

    @After
    fun reset() = FaceTrackerHardeningFlag.set(true)

    @Test
    fun defaultsOnAndToggles() {
        assertTrue(FaceTrackerHardeningFlag.enabled)
        FaceTrackerHardeningFlag.set(false)
        assertFalse(FaceTrackerHardeningFlag.enabled)
        FaceTrackerHardeningFlag.set(true)
        assertTrue(FaceTrackerHardeningFlag.enabled)
    }
}
