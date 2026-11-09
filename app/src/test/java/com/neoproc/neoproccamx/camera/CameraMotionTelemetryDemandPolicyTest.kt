package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMotionTelemetryDemandPolicyTest {

    @Test
    fun dormantPixelConsumerDoesNotRunFastestSensorStream() {
        assertFalse(CameraMotionTelemetryDemandPolicy.shouldCollect(false))
    }

    @Test
    fun enabledPixelConsumerImmediatelyAdmitsFastestSensorStream() {
        assertTrue(CameraMotionTelemetryDemandPolicy.shouldCollect(true))
    }
}
