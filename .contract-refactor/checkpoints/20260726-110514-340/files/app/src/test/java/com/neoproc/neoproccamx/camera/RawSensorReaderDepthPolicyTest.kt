package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSensorReaderDepthPolicyTest {

    @Test
    fun `recording depth keeps twelve megapixel raw sustainable without old oversized queue`() {
        val plan = RawSensorReaderDepthPolicy.resolve(
            rawSize = CameraStreamSize(4096, 3072),
            recordingActive = true
        )

        assertEquals(6, plan.maxImages)
        assertTrue(plan.estimatedReaderBytes < 200L * 1024L * 1024L)
        assertTrue(plan.reason.contains("sustained RAW recording"))
    }

    @Test
    fun `recording depth protects memory for ultra high resolution raw`() {
        val plan = RawSensorReaderDepthPolicy.resolve(
            rawSize = CameraStreamSize(8192, 6144),
            recordingActive = true
        )

        assertEquals(3, plan.maxImages)
        assertTrue(plan.estimatedFrameBytes > 90L * 1024L * 1024L)
        assertTrue(plan.reason.contains("ultra-high-resolution"))
    }

    @Test
    fun `preview still companion raw uses smaller queue than recording`() {
        val plan = RawSensorReaderDepthPolicy.resolve(
            rawSize = CameraStreamSize(4096, 3072),
            recordingActive = false
        )

        assertEquals(4, plan.maxImages)
        assertTrue(plan.estimatedReaderBytes < 130L * 1024L * 1024L)
        assertTrue(plan.reason.contains("still companion"))
    }
}
