package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.camera.StillCaptureRequestPhase
import com.neoproc.neoproccamx.camera.StillCaptureRequestToken
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
        val tokens = (0 until 3).map { hdrToken(captureId = 7, frameIndex = it) }

        assertTrue(coordinator.markJpegBufferReleased(tokens[0]))
        assertNull(coordinator.routeJpegFrame(tokens[0], byteArrayOf(1)))
        assertTrue(coordinator.markJpegBufferReleased(tokens[1]))
        assertNull(coordinator.routeJpegFrame(tokens[1], byteArrayOf(2)))

        assertTrue(coordinator.markJpegBufferReleased(tokens[2]))
        val batch = coordinator.routeJpegFrame(tokens[2], byteArrayOf(3))
        assertNotNull(batch)
        assertEquals(3 to 3, batch?.progress)
        assertEquals(3, batch?.frames?.size)
        assertArrayEquals(byteArrayOf(1), batch?.frames?.get(0))
        assertArrayEquals(byteArrayOf(3), batch?.frames?.get(2))
    }

    @Test
    fun singleJpegRelease_unblocksRecordingButRetainsStillOwnershipUntilSaved() {
        val coordinator = StillCaptureSessionCoordinator()
        assertTrue(coordinator.begin(newCapture(id = 15)))
        val token = singleToken(captureId = 15)

        assertTrue(coordinator.markJpegBufferReleased(token))

        assertFalse(coordinator.blocksRecordingAdmission())
        assertEquals(15, coordinator.activeCaptureId())
        assertFalse(coordinator.begin(newCapture(id = 16)))
        assertNotNull(coordinator.routeJpegFrame(token, byteArrayOf(1)))
        assertEquals(PrimaryStillCaptureTransition.CLEARED, coordinator.markPrimarySaved(15))
        assertNull(coordinator.activeCaptureId())
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
        val rawCapture = coordinator.beginRawProcessing(rawCompanionToken(captureId = 4))
        assertNotNull(rawCapture)
        assertEquals(StillCaptureMode.HDR_RAW_COMPANION, rawCapture?.mode)
    }

    @Test
    fun finishRawProcessing_clearsCaptureAfterPrimarySave() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(newCapture(id = 5, rawRequested = true))

        assertNotNull(coordinator.beginRawProcessing(singleToken(captureId = 5, expectsRaw = true)))
        assertEquals(PrimaryStillCaptureTransition.NONE, coordinator.markPrimarySaved(5))
        assertTrue(coordinator.finishRawProcessing(5))
        assertNull(coordinator.activeCaptureId())
    }

    @Test
    fun rawCompletesBeforeDelayedJpeg_recordingWaitsOnlyForJpegBufferRelease() {
        val coordinator = StillCaptureSessionCoordinator()
        assertTrue(coordinator.begin(newCapture(id = 9, rawRequested = true)))
        val token = singleToken(captureId = 9, expectsRaw = true)

        assertTrue(coordinator.blocksRecordingAdmission())
        assertNotNull(coordinator.beginRawProcessing(token))
        assertFalse(coordinator.finishRawProcessing(9))

        // DNG processing has released its RAW Image, but the JPEG output from the same Camera2
        // request has not arrived yet. Reconfiguring for recording is not resource-safe.
        assertTrue(coordinator.blocksRecordingAdmission())
        assertEquals(9, coordinator.activeCaptureId())

        assertTrue(coordinator.markJpegBufferReleased(token))
        val jpegBatch = coordinator.routeJpegFrame(token, byteArrayOf(9))

        assertNotNull(jpegBatch)
        // routeJpegFrame is called only after the ImageReader buffer was copied and closed. JPEG
        // processing may now continue without holding recording admission, while activeCaptureId
        // deliberately continues to reject a second still.
        assertFalse(coordinator.blocksRecordingAdmission())
        assertEquals(9, coordinator.activeCaptureId())
        assertFalse(coordinator.begin(newCapture(id = 10)))

        assertEquals(PrimaryStillCaptureTransition.CLEARED, coordinator.markPrimarySaved(9))
        assertNull(coordinator.activeCaptureId())
    }

    @Test
    fun jpegCompletesBeforeRaw_recordingRemainsBlockedUntilRawBufferRelease() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(newCapture(id = 10, rawRequested = true))
        val token = singleToken(captureId = 10, expectsRaw = true)

        assertTrue(coordinator.markJpegBufferReleased(token))
        assertNotNull(coordinator.routeJpegFrame(token, byteArrayOf(1)))
        assertTrue(coordinator.blocksRecordingAdmission())
        assertEquals(PrimaryStillCaptureTransition.NONE, coordinator.markPrimarySaved(10))

        assertNotNull(coordinator.beginRawProcessing(token))
        assertTrue(coordinator.finishRawProcessing(10))

        assertFalse(coordinator.blocksRecordingAdmission())
        assertNull(coordinator.activeCaptureId())
    }

    @Test
    fun hdrProcessing_releasesRecordingGateButRetainsStillCaptureOwnership() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(
            newCapture(
                id = 11,
                mode = StillCaptureMode.HDR,
                expectedJpegs = 3
            )
        )
        val tokens = (0 until 3).map { hdrToken(captureId = 11, frameIndex = it) }

        assertTrue(coordinator.markJpegBufferReleased(tokens[0]))
        assertNull(coordinator.routeJpegFrame(tokens[0], byteArrayOf(1)))
        assertTrue(coordinator.markJpegBufferReleased(tokens[1]))
        assertNull(coordinator.routeJpegFrame(tokens[1], byteArrayOf(2)))
        assertTrue(coordinator.blocksRecordingAdmission())
        assertTrue(coordinator.markJpegBufferReleased(tokens[2]))
        assertFalse(coordinator.blocksRecordingAdmission())
        assertNotNull(coordinator.routeJpegFrame(tokens[2], byteArrayOf(3)))

        assertFalse(coordinator.blocksRecordingAdmission())
        assertEquals(11, coordinator.activeCaptureId())
        assertFalse(coordinator.begin(newCapture(id = 12)))
    }

    @Test
    fun deferredRawCompanion_blocksRecordingUntilCompanionBufferIsReleased() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(
            newCapture(
                id = 12,
                mode = StillCaptureMode.HDR,
                expectedJpegs = 3,
                rawCompanionAfterPrimary = true
            )
        )
        val tokens = (0 until 3).map { hdrToken(captureId = 12, frameIndex = it) }

        assertTrue(coordinator.markJpegBufferReleased(tokens[0]))
        assertNull(coordinator.routeJpegFrame(tokens[0], byteArrayOf(1)))
        assertTrue(coordinator.markJpegBufferReleased(tokens[1]))
        assertNull(coordinator.routeJpegFrame(tokens[1], byteArrayOf(2)))
        assertTrue(coordinator.markJpegBufferReleased(tokens[2]))
        assertNotNull(coordinator.routeJpegFrame(tokens[2], byteArrayOf(3)))
        assertTrue(coordinator.blocksRecordingAdmission())
        assertEquals(
            PrimaryStillCaptureTransition.LAUNCH_RAW_COMPANION,
            coordinator.markPrimarySaved(12)
        )
        assertTrue(coordinator.blocksRecordingAdmission())

        assertNotNull(coordinator.beginRawProcessing(rawCompanionToken(captureId = 12)))
        assertTrue(coordinator.finishRawProcessing(12))
        assertFalse(coordinator.blocksRecordingAdmission())
    }

    @Test
    fun failureOrTimeoutClear_releasesRecordingAdmission() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(newCapture(id = 13, rawRequested = true))

        assertTrue(coordinator.blocksRecordingAdmission())
        assertTrue(coordinator.clearIfActive(13))
        assertFalse(coordinator.blocksRecordingAdmission())

        assertTrue(coordinator.begin(newCapture(id = 14, rawRequested = true)))
        assertTrue(coordinator.blocksRecordingAdmission())
        coordinator.clear()
        assertFalse(coordinator.blocksRecordingAdmission())
    }

    @Test
    fun beginRawProcessing_ignoresCaptureWithoutRawWork() {
        val coordinator = StillCaptureSessionCoordinator()
        coordinator.begin(newCapture(id = 6, rawRequested = false))

        assertNull(coordinator.beginRawProcessing(singleToken(captureId = 6, expectsRaw = true)))
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
            val tokens = (0 until 3).map { hdrToken(captureId = 8, frameIndex = it) }

            assertTrue(coordinator.markJpegBufferReleased(tokens[0]))
            assertNull(coordinator.routeJpegFrame(tokens[0], byteArrayOf(1)))
            assertTrue(coordinator.markJpegBufferReleased(tokens[1]))
            assertNull(coordinator.routeJpegFrame(tokens[1], byteArrayOf(2)))
            assertTrue(coordinator.markJpegBufferReleased(tokens[2]))
            val batch = coordinator.routeJpegFrame(tokens[2], byteArrayOf(3))

            assertNotNull(batch)
            assertEquals(3, batch?.frames?.size)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun staleReleaseAndFrame_cannotMutateReplacementCapture() {
        val coordinator = StillCaptureSessionCoordinator()
        assertTrue(coordinator.begin(newCapture(id = 20)))
        val staleToken = singleToken(captureId = 20)
        assertTrue(coordinator.clearIfActive(20))
        assertTrue(coordinator.begin(newCapture(id = 21)))
        val replacementToken = singleToken(captureId = 21)

        assertFalse(coordinator.markJpegBufferReleased(staleToken))
        assertNull(coordinator.routeJpegFrame(staleToken, byteArrayOf(20)))
        assertTrue(coordinator.blocksRecordingAdmission())

        assertTrue(coordinator.markJpegBufferReleased(replacementToken))
        assertNotNull(coordinator.routeJpegFrame(replacementToken, byteArrayOf(21)))
    }

    @Test
    fun releasedBufferWithoutProcessableBytes_unblocksRecordingButRetainsOwner() {
        val coordinator = StillCaptureSessionCoordinator()
        assertTrue(coordinator.begin(newCapture(id = 22)))
        val token = singleToken(captureId = 22)

        assertTrue(coordinator.markJpegBufferReleased(token))
        assertFalse(coordinator.blocksRecordingAdmission())
        assertNull(coordinator.routeJpegFrame(token, byteArrayOf()))
        assertEquals(22, coordinator.activeCaptureId())
        assertFalse(coordinator.begin(newCapture(id = 23)))
    }

    @Test
    fun timedOutProcessor_cannotBlockOrClearReplacementCapture() {
        val coordinator = StillCaptureSessionCoordinator()
        assertTrue(coordinator.begin(newCapture(id = 24)))
        val oldToken = singleToken(captureId = 24)
        assertTrue(coordinator.markJpegBufferReleased(oldToken))
        val oldBatch = coordinator.routeJpegFrame(oldToken, byteArrayOf(24))
        assertNotNull(oldBatch)
        assertTrue(coordinator.clearIfActive(24))

        assertTrue(coordinator.begin(newCapture(id = 25)))
        val replacementToken = singleToken(captureId = 25)
        assertTrue(coordinator.markJpegBufferReleased(replacementToken))
        assertNotNull(coordinator.routeJpegFrame(replacementToken, byteArrayOf(25)))
        assertEquals(PrimaryStillCaptureTransition.NONE, coordinator.markPrimarySaved(24))
        assertEquals(25, coordinator.activeCaptureId())
    }

    @Test
    fun outOfOrderHdrTokens_areOrderedByFrameIndexAndDuplicatesAreRejected() {
        val coordinator = StillCaptureSessionCoordinator()
        assertTrue(
            coordinator.begin(newCapture(id = 26, mode = StillCaptureMode.HDR, expectedJpegs = 3))
        )
        val first = hdrToken(captureId = 26, frameIndex = 0)
        val middle = hdrToken(captureId = 26, frameIndex = 1)
        val last = hdrToken(captureId = 26, frameIndex = 2)

        assertTrue(coordinator.markJpegBufferReleased(last))
        assertNull(coordinator.routeJpegFrame(last, byteArrayOf(3)))
        assertFalse(coordinator.markJpegBufferReleased(last))
        assertNull(coordinator.routeJpegFrame(last, byteArrayOf(33)))

        assertTrue(coordinator.markJpegBufferReleased(first))
        assertNull(coordinator.routeJpegFrame(first, byteArrayOf(1)))
        assertTrue(coordinator.markJpegBufferReleased(middle))
        val batch = coordinator.routeJpegFrame(middle, byteArrayOf(2))

        assertNotNull(batch)
        assertEquals(3 to 3, batch?.progress)
        assertArrayEquals(byteArrayOf(1), batch?.frames?.get(0))
        assertArrayEquals(byteArrayOf(2), batch?.frames?.get(1))
        assertArrayEquals(byteArrayOf(3), batch?.frames?.get(2))
    }

    @Test
    fun acquiredCameraBuffers_surviveCaptureClearUntilEveryPhysicalBufferIsReleased() {
        val coordinator = StillCaptureSessionCoordinator()
        assertTrue(coordinator.begin(newCapture(id = 27)))

        coordinator.markCameraBufferAcquired()
        coordinator.markCameraBufferAcquired()
        assertTrue(coordinator.clearIfActive(27))

        // Clearing logical request ownership must not pretend that already-acquired ImageReader
        // buffers were closed. Recording/session replacement remains blocked until their real close.
        assertNull(coordinator.activeCaptureId())
        assertTrue(coordinator.blocksRecordingAdmission())

        coordinator.markCameraBufferReleased()
        assertTrue(coordinator.blocksRecordingAdmission())
        coordinator.markCameraBufferReleased()
        assertFalse(coordinator.blocksRecordingAdmission())
    }

    private fun singleToken(
        captureId: Int,
        expectsRaw: Boolean = false
    ) = StillCaptureRequestToken(
        captureId = captureId,
        phase = StillCaptureRequestPhase.SINGLE_PRIMARY,
        frameIndex = 0,
        frameCount = 1,
        expectsJpeg = true,
        expectsRaw = expectsRaw
    )

    private fun hdrToken(
        captureId: Int,
        frameIndex: Int,
        frameCount: Int = 3
    ) = StillCaptureRequestToken(
        captureId = captureId,
        phase = StillCaptureRequestPhase.HDR_BRACKET,
        frameIndex = frameIndex,
        frameCount = frameCount,
        expectsJpeg = true,
        expectsRaw = false
    )

    private fun rawCompanionToken(captureId: Int) = StillCaptureRequestToken(
        captureId = captureId,
        phase = StillCaptureRequestPhase.HDR_RAW_COMPANION,
        frameIndex = 0,
        frameCount = 1,
        expectsJpeg = false,
        expectsRaw = true
    )

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
