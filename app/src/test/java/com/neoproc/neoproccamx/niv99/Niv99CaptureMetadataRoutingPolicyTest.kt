package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Test

class Niv99CaptureMetadataRoutingPolicyTest {
    @Test
    fun `sensor-linear domains preserve complete RAW metadata`() {
        listOf(
            Niv99SignalDomain.SENSOR_LINEAR_RAW,
            Niv99SignalDomain.SENSOR_LINEAR_DEMOSAICED
        ).forEach { domain ->
            assertEquals(
                Niv99CaptureMetadataDepth.SENSOR_LINEAR,
                Niv99CaptureMetadataRoutingPolicy.resolve(domain)
            )
        }
    }

    @Test
    fun `processed video domains never read RAW-only dynamic metadata`() {
        listOf(
            Niv99SignalDomain.LINEAR_RGB,
            Niv99SignalDomain.P010_YUV,
            Niv99SignalDomain.DISPLAY_RGB
        ).forEach { domain ->
            assertEquals(
                Niv99CaptureMetadataDepth.PROCESSED,
                Niv99CaptureMetadataRoutingPolicy.resolve(domain)
            )
        }
    }
}
