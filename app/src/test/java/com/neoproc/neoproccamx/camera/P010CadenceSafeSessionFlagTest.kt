package com.neoproc.neoproccamx.camera

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P010CadenceSafeSessionFlagTest {

    @After
    fun restoreProductionDefault() {
        P010CadenceSafeSessionFlag.set(true)
    }

    @Test
    fun `cadence-safe P010 session is enabled by default`() {
        assertTrue(P010CadenceSafeSessionFlag.enabled)
    }

    @Test
    fun `device diagnosis can reversibly restore the legacy graph`() {
        P010CadenceSafeSessionFlag.set(false)
        assertFalse(P010CadenceSafeSessionFlag.enabled)
        P010CadenceSafeSessionFlag.set(true)
        assertTrue(P010CadenceSafeSessionFlag.enabled)
    }
}
