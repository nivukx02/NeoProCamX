package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRealtimeMetadataPolicyTest {
    @Test
    fun `processed preview and recording suppress observation-only lens shading map`() {
        assertEquals(
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF,
            CameraRealtimeMetadataPolicy.lensShadingMapMode(
                rawSensorActive = false,
                stillCapture = false
            )
        )
    }

    @Test
    fun `raw recording preserves lens shading calibration metadata`() {
        assertEquals(
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
            CameraRealtimeMetadataPolicy.lensShadingMapMode(
                rawSensorActive = true,
                stillCapture = false
            )
        )
    }

    @Test
    fun `still capture preserves calibration metadata`() {
        assertEquals(
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
            CameraRealtimeMetadataPolicy.lensShadingMapMode(
                rawSensorActive = false,
                stillCapture = true
            )
        )
    }
}
