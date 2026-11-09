package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRateSyncTelemetryGateContractTest {
    @Test
    fun telemetryGateConflatesManyRequestsIntoOneVsyncCallback() {
        var scheduled: (() -> Unit)? = null
        var calls = 0
        val gate = VSyncTelemetryGate(
            onVSync = { calls++ },
            frameScheduler = { callback -> scheduled = callback }
        )

        assertFalse(gate.isFrameScheduled)
        gate.requestFrame()
        gate.requestFrame()
        gate.requestFrame()

        assertTrue(gate.isFrameScheduled)
        assertEquals(0, calls)
        scheduled?.invoke()
        assertFalse(gate.isFrameScheduled)
        assertEquals(1, calls)
    }
}
