package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingEncoderCadencePolicyTest {

    @Test
    fun `sub-second realtime gop is promoted to sustainable one-second cadence`() {
        val plan = RecordingEncoderCadencePolicy.resolve(
            requestedIFrameIntervalSeconds = 0.10f,
            sdkInt = 36,
            surfaceInput = true
        )

        assertEquals(1f, plan.iFrameIntervalSeconds, 0.0001f)
        assertNull(plan.intraRefreshPeriodFrames)
        assertEquals(0, plan.allowFrameDrop)
    }

    @Test
    fun `true all-intra request remains all-intra`() {
        val plan = RecordingEncoderCadencePolicy.resolve(
            requestedIFrameIntervalSeconds = 0f,
            sdkInt = 36,
            surfaceInput = true
        )

        assertEquals(0f, plan.iFrameIntervalSeconds, 0.0001f)
        assertNull(plan.intraRefreshPeriodFrames)
    }

    @Test
    fun `frame-drop prohibition is only attached to supported surface input`() {
        assertNull(
            RecordingEncoderCadencePolicy.resolve(1f, sdkInt = 30, surfaceInput = true)
                .allowFrameDrop
        )
        assertNull(
            RecordingEncoderCadencePolicy.resolve(1f, sdkInt = 36, surfaceInput = false)
                .allowFrameDrop
        )
    }
}
