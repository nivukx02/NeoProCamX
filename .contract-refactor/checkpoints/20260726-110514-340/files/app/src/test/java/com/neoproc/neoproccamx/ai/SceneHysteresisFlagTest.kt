package com.neoproc.neoproccamx.ai

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f07 / CP97 — gate defaults ON (activated 2026-07-03); OFF restores bit-for-bit legacy. */
class SceneHysteresisFlagTest {

    @After
    fun reset() = SceneHysteresisFlag.set(true)

    @Test
    fun defaultsOnAndToggles() {
        assertTrue(SceneHysteresisFlag.enabled)
        SceneHysteresisFlag.set(false)
        assertFalse(SceneHysteresisFlag.enabled)
        SceneHysteresisFlag.set(true)
        assertTrue(SceneHysteresisFlag.enabled)
    }
}
