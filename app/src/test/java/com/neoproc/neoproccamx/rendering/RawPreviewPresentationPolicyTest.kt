package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class RawPreviewPresentationPolicyTest {
    @Test
    fun ultraHighResolutionRawUsesSmoothCameraMonitor() {
        assertEquals(
            RawPreviewPresentationPolicy.Path.CAMERA_MONITOR,
            RawPreviewPresentationPolicy.resolve(true, false, 8192, 6144)
        )
    }

    @Test
    fun realtimeSizedRawMayUseSensorDebayer() {
        assertEquals(
            RawPreviewPresentationPolicy.Path.SENSOR_DEBAYER,
            RawPreviewPresentationPolicy.resolve(true, false, 4000, 3000)
        )
    }

    @Test
    fun recordingAlwaysReservesComputeForTheRawMaster() {
        assertEquals(
            RawPreviewPresentationPolicy.Path.CAMERA_MONITOR,
            RawPreviewPresentationPolicy.resolve(true, true, 4000, 3000)
        )
    }
}
