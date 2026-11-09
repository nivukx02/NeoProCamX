package com.neoproc.neoproccamx.ai

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f09 / CP99 — gate defaults ON (activated 2026-07-04); OFF restores bit-for-bit legacy. */
class EnhancementSignalAdaptiveFlagTest {

    @After
    fun reset() = EnhancementSignalAdaptiveFlag.set(true)

    @Test
    fun defaultsOnAndToggles() {
        assertTrue(EnhancementSignalAdaptiveFlag.enabled)
        EnhancementSignalAdaptiveFlag.set(false)
        assertFalse(EnhancementSignalAdaptiveFlag.enabled)
        EnhancementSignalAdaptiveFlag.set(true)
        assertTrue(EnhancementSignalAdaptiveFlag.enabled)
    }
}
