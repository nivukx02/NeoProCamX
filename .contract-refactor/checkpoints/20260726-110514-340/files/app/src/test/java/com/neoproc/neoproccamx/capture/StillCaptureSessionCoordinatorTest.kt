package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillCaptureSessionCoordinatorTest {

    @Test
    fun begin_rejectsConcurrentCaptureUntilCleared() {
        val coordinator = StillCaptureSessionCoordinator()

        assertTrue(coordinator.begin(newCapture(id = 1)))
        assertFalse(coordinator.begin(newCapture(id = 2)))

        assertTrue(coordinator.clearIfActive(1))
        assertTrue(coordinator.begin(newCapture(id = 2)))
    }

    @Test
    fun routeJpegFrame_waitsUntilExpectedFrameCount() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(newCapture(id = 7, mode = StillCaptureMode.HDR, expectedJpegs = 3))

        assertNull(coordinator.routeJpegFrame(byteArrayOf(1)))
        assertNull(coordinator.routeJpegFrame(byteArrayOf(2)))

        val batch = coordinator.routeJpegFrame(byteArrayOf(3))
        assertNotNull(batch)
        assertEquals(3 to 3, batch?.progress)
        assertEquals(3, batch?.frames?.size)
        assertArrayEquals(byteArrayOf(1), batch?.frames?.get(0))
        assertArrayEquals(byteArrayOf(3), batch?.frames?.get(2))
    }

    @Test
    fun markPrimarySaved_launchesRawCompanionWhenConfigured() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(
            newCapture(
                id = 4,
                mode = StillCaptureMode.HDR,
                rawRequested = false,
                rawCompanionAfterPrimary = true
            )
        )

        val transition = coordinator.markPrimarySaved(4)

        assertEquals(PrimaryStillCaptureTransition.LAUNCH_RAW_COMPANION, transition)
        val rawCapture = coordinator.beginRawProcessing()
        assertNotNull(rawCapture)
        assertEquals(StillCaptureMode.HDR_RAW_COMPANION, rawCapture?.mode)
    }

    @Test
    fun finishRawProcessing_clearsCaptureAfterPrimarySave() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(newCapture(id = 5, rawRequested = true))

        assertNotNull(coordinator.beginRawProcessing())
        assertEquals(PrimaryStillCaptureTransition.NONE, coordinator.markPrimarySaved(5))
        assertTrue(coordinator.finishRawProcessing(5))
        assertNull(coordinator.activeCaptureId())
    }

    @Test
    fun beginRawProcessing_ignoresCaptureWithoutRawWork() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(newCapture(id = 6, rawRequested = false))

        assertNull(coordinator.beginRawProcessing())
    }

    @Test
    fun strictHdrCapture_normalizesFrameCountToMinimumBracket() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val coordinator = StillCaptureSessionCoordinator()
            coordinator.begin(newCapture(id = 8, mode = StillCaptureMode.HDR, expectedJpegs = 1))

            assertNull(coordinator.routeJpegFrame(byteArrayOf(1)))
            assertNull(coordinator.routeJpegFrame(byteArrayOf(2)))
            val batch = coordinator.routeJpegFrame(byteArrayOf(3))

            assertNotNull(batch)
            assertEquals(3, batch?.frames?.size)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    private fun newCapture(
        id: Int,
        mode: StillCaptureMode = StillCaptureMode.SINGLE,
        expectedJpegs: Int = 1,
        rawRequested: Boolean = false,
        rawCompanionAfterPrimary: Boolean = false
    ) = ActiveStillCapture(
        id = id,
        mode = mode,
        primaryTag = "IMG",
        rawCompanionTag = null,
        debugLabel = null,
        failureLabel = "Photo",
        hdrMode = "OFF",
        expectedJpegs = expectedJpegs,
        rawRequested = rawRequested,
        singleCaptureRawMode = StillCaptureCapturePolicy.SingleCaptureRawMode.JPEG_ONLY,
        processSingleBitmap = false,
        rawCompanionAfterPrimary = rawCompanionAfterPrimary
    )
}
