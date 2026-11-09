package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiWarningToastPolicyTest {

    @Test
    fun `shows first non blank warning`() {
        val decision = UiWarningToastPolicy.resolve(
            nextWarning = "Bypass capability: RAW TRUE RAW",
            lastShownWarning = null
        )

        assertTrue(decision.showToast)
        assertEquals("Bypass capability: RAW TRUE RAW", decision.lastShownWarning)
    }

    @Test
    fun `suppresses duplicate warning emissions`() {
        val decision = UiWarningToastPolicy.resolve(
            nextWarning = "Bypass capability: RAW TRUE RAW",
            lastShownWarning = "Bypass capability: RAW TRUE RAW"
        )

        assertFalse(decision.showToast)
        assertEquals("Bypass capability: RAW TRUE RAW", decision.lastShownWarning)
    }

    @Test
    fun `blank warning clears last shown warning`() {
        val decision = UiWarningToastPolicy.resolve(
            nextWarning = null,
            lastShownWarning = "Bypass capability: RAW TRUE RAW"
        )

        assertFalse(decision.showToast)
        assertEquals(null, decision.lastShownWarning)
    }
}
