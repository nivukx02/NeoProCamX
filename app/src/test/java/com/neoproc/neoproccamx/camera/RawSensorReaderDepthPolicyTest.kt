package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSensorReaderDepthPolicyTest {

    @Test
    fun `recording depth absorbs evidenced 4k25 stall inside native memory guard`() {
        val plan = RawSensorReaderDepthPolicy.resolve(
            rawSize = CameraStreamSize(4096, 3072),
            recordingActive = true
        )

        assertEquals(16, plan.maxImages)
        assertTrue(plan.boundedStallProtected)
        assertEquals(RawSensorReaderDepthPolicy.EVIDENCED_BOUNDED_STALL_NS, plan.boundedStallBudgetNs)
        assertTrue(
            plan.estimatedReaderBytes <=
                RawSensorReaderDepthPolicy.MAX_BOUNDED_STALL_READER_BYTES
        )
        assertTrue(plan.reason.contains("sustained RAW recording"))
        assertTrue(plan.reason.contains("stall protection"))
    }

    @Test
    fun `minimum depth is derived from measured stall cadence and join margin`() {
        assertEquals(16, RawSensorReaderDepthPolicy.minimumBoundedStallDepth(targetFps = 25))
        assertEquals(18, RawSensorReaderDepthPolicy.minimumBoundedStallDepth(targetFps = 30))
    }

    @Test
    fun `recording depth protects memory for ultra high resolution raw`() {
        val plan = RawSensorReaderDepthPolicy.resolve(
            rawSize = CameraStreamSize(8192, 6144),
            recordingActive = true
        )

        assertEquals(3, plan.maxImages)
        assertTrue(!plan.boundedStallProtected)
        assertTrue(plan.estimatedFrameBytes > 90L * 1024L * 1024L)
        assertTrue(plan.reason.contains("ultra-high-resolution"))
    }

    @Test
    fun `recording above one hundred megapixels keeps functional three image minimum`() {
        val plan = RawSensorReaderDepthPolicy.resolve(
            rawSize = CameraStreamSize(12_000, 9_000),
            recordingActive = true
        )

        assertEquals(3, plan.maxImages)
        assertTrue(plan.estimatedFrameBytes > 200L * 1024L * 1024L)
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

    @Test
    fun `qualified mode switch standby keeps sustained raw reader depth`() {
        val sustained = RawSensorReaderDepthPolicy.requiresSustainedCaptureDepth(
            rawVideoPrearmEnabled = false,
            streamGraphMode = CinemaStreamGraphMode.VIDEO_PREVIEW_READY,
            rawRouteReason =
                "$MODE_SWITCH_RAW_STANDBY_REASON (reader retained; P010/YUV surface active)"
        )
        val plan = RawSensorReaderDepthPolicy.resolve(
            rawSize = CameraStreamSize(4096, 3072),
            recordingActive = sustained
        )

        assertTrue(sustained)
        assertEquals(16, plan.maxImages)
        assertTrue(plan.boundedStallProtected)
    }

    @Test
    fun `ordinary still companion does not acquire sustained raw reader depth`() {
        assertTrue(
            !RawSensorReaderDepthPolicy.requiresSustainedCaptureDepth(
                rawVideoPrearmEnabled = false,
                streamGraphMode = CinemaStreamGraphMode.PREVIEW_STILL_READY,
                rawRouteReason = "RAW still companion path"
            )
        )
    }
}
