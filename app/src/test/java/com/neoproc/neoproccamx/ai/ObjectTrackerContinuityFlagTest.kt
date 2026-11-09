package com.neoproc.neoproccamx.ai

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f06 / CP96 — gate defaults ON (activated 2026-07-03); OFF restores bit-for-bit legacy. */
class ObjectTrackerContinuityFlagTest {

    @After
    fun reset() = ObjectTrackerContinuityFlag.set(true)

    @Test
    fun defaultsOnAndToggles() {
        assertTrue(ObjectTrackerContinuityFlag.enabled)
        ObjectTrackerContinuityFlag.set(false)
        assertFalse(ObjectTrackerContinuityFlag.enabled)
        ObjectTrackerContinuityFlag.set(true)
        assertTrue(ObjectTrackerContinuityFlag.enabled)
    }
}
