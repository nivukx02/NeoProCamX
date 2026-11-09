package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrRecordingStopResultTest {
    @Test
    fun `failed clip can drain completely without becoming successful`() {
        val result = SensorLinearNdrRecordingStopResult(
            teardownSafe = true,
            ingressFailed = true
        )

        assertTrue(result.teardownSafe)
        assertTrue(result.ingressFailed)
    }

    @Test
    fun `genuine ownership timeout remains unsafe and failed`() {
        val result = SensorLinearNdrRecordingStopResult(
            teardownSafe = false,
            ingressFailed = true
        )

        assertFalse(result.teardownSafe)
        assertTrue(result.ingressFailed)
    }

    @Test
    fun `repeated stop observation is idempotent`() {
        val first = SensorLinearNdrRecordingStopResult(
            teardownSafe = true,
            ingressFailed = true
        )
        val repeated = SensorLinearNdrRecordingStopResult(
            teardownSafe = first.teardownSafe,
            ingressFailed = first.ingressFailed
        )

        assertEquals(first, repeated)
    }
}
