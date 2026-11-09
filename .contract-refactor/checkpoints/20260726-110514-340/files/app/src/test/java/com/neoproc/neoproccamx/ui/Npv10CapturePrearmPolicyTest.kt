package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10CapturePrearmPolicyTest {
    @Test fun `matching configured graph ignores redundant forced refresh`() {
        assertFalse(Npv10CapturePrearmPolicy.shouldRefresh("4k25", "4k25", null, true, true))
    }

    @Test fun `matching in flight graph coalesces duplicate request`() {
        assertFalse(Npv10CapturePrearmPolicy.shouldRefresh("4k25", null, "4k25", false, true))
    }

    @Test fun `changed signature requests refresh`() {
        assertTrue(Npv10CapturePrearmPolicy.shouldRefresh("4k30", "4k25", null, false, false))
    }
}
