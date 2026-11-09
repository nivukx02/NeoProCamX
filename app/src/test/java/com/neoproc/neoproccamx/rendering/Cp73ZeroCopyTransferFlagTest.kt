package com.neoproc.neoproccamx.rendering

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f11 / CP73 — gate defaults ON, while readiness still requires native capability proof. */
class Cp73ZeroCopyTransferFlagTest {

    @After
    fun reset() = Cp73ZeroCopyTransferFlag.set(true)

    @Test
    fun defaultsOnAndToggles() {
        assertTrue(Cp73ZeroCopyTransferFlag.enabled)
        Cp73ZeroCopyTransferFlag.set(false)
        assertFalse(Cp73ZeroCopyTransferFlag.enabled)
        Cp73ZeroCopyTransferFlag.set(true)
        assertTrue(Cp73ZeroCopyTransferFlag.enabled)
    }
}
