package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99TimelineTelemetryTest {
    private val telemetry = Niv99TimelineTelemetry()
    private val snapshot = Niv99TimelineTelemetrySnapshot()

    @Test
    fun `invalid identity cannot create or mutate a timeline`() {
        assertFalse(telemetry.beginTimeline("", 1L))
        assertFalse(telemetry.beginTimeline(CAMERA_ID, -1L))
        assertFalse(telemetry.recordCaptureSeen(CAMERA_ID, GENERATION))
        assertEquals(-1L, telemetry.recordCapturePublished(CAMERA_ID, GENERATION, 1L, 40L))

        telemetry.snapshot(snapshot)
        assertEquals("", snapshot.cameraId)
        assertEquals(-1L, snapshot.sessionGeneration)
        assertEquals(0L, snapshot.captureResultsSeen)
        assertEquals(-1L, snapshot.latestPublishedFrameNumber)
        assertEquals(-1L, snapshot.latestPublishedSensorTimestampNs)
    }

    @Test
    fun `current timeline exposes exact counters and latest published identity`() {
        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION))
        repeat(3) { assertTrue(telemetry.recordCaptureSeen(CAMERA_ID, GENERATION)) }
        assertEquals(
            1L,
            telemetry.recordCapturePublished(CAMERA_ID, GENERATION, 41L, 1_640_000_000L)
        )
        assertEquals(
            2L,
            telemetry.recordCapturePublished(CAMERA_ID, GENERATION, 42L, 1_680_000_000L)
        )
        assertTrue(telemetry.recordCaptureRejected(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordImageMatch(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordImageMiss(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordTuningAccepted(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordTuningRejected(CAMERA_ID, GENERATION))

        telemetry.snapshot(snapshot)
        assertEquals(CAMERA_ID, snapshot.cameraId)
        assertEquals(GENERATION, snapshot.sessionGeneration)
        assertEquals(3L, snapshot.captureResultsSeen)
        assertEquals(2L, snapshot.captureResultsPublished)
        assertEquals(1L, snapshot.captureResultsRejected)
        assertEquals(1L, snapshot.imageMatches)
        assertEquals(1L, snapshot.imageMisses)
        assertEquals(1L, snapshot.tuningAccepted)
        assertEquals(1L, snapshot.tuningRejected)
        assertEquals(0L, snapshot.pixelEligibleTunings)
        assertEquals(0L, snapshot.pixelAppliedFrames)
        assertEquals(0L, snapshot.previewPixelAppliedFrames)
        assertEquals(0L, snapshot.recordingPixelAppliedFrames)
        assertEquals(42L, snapshot.latestPublishedFrameNumber)
        assertEquals(1_680_000_000L, snapshot.latestPublishedSensorTimestampNs)
        assertEquals(0L, snapshot.resetCount)
    }

    @Test
    fun `reaffirming one timeline preserves its counters`() {
        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordCaptureSeen(CAMERA_ID, GENERATION))
        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION))

        telemetry.snapshot(snapshot)
        assertEquals(1L, snapshot.captureResultsSeen)
        assertEquals(0L, snapshot.resetCount)
    }

    @Test
    fun `generation advance resets scoped values and rejects retired callbacks`() {
        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordCaptureSeen(CAMERA_ID, GENERATION))
        assertEquals(
            1L,
            telemetry.recordCapturePublished(CAMERA_ID, GENERATION, 5L, 200_000_000L)
        )
        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION + 1L))

        assertFalse(telemetry.recordCaptureSeen(CAMERA_ID, GENERATION))
        assertFalse(telemetry.recordImageMiss(CAMERA_ID, GENERATION))
        assertEquals(
            -1L,
            telemetry.recordCapturePublished(CAMERA_ID, GENERATION, 6L, 240_000_000L)
        )

        telemetry.snapshot(snapshot)
        assertEquals(GENERATION + 1L, snapshot.sessionGeneration)
        assertEquals(0L, snapshot.captureResultsSeen)
        assertEquals(0L, snapshot.captureResultsPublished)
        assertEquals(0L, snapshot.imageMisses)
        assertEquals(0L, snapshot.pixelEligibleTunings)
        assertEquals(0L, snapshot.pixelAppliedFrames)
        assertEquals(0L, snapshot.pixelRenderRejectedFrames)
        assertEquals(-1L, snapshot.latestPublishedFrameNumber)
        assertEquals(-1L, snapshot.latestPublishedSensorTimestampNs)
        assertEquals(1L, snapshot.resetCount)
    }

    @Test
    fun `retired generation cannot reopen and erase the active timeline`() {
        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION + 1L))
        assertTrue(telemetry.recordCaptureSeen(CAMERA_ID, GENERATION + 1L))
        assertEquals(
            1L,
            telemetry.recordCapturePublished(CAMERA_ID, GENERATION + 1L, 17L, 680_000_000L)
        )

        assertFalse(telemetry.beginTimeline(CAMERA_ID, GENERATION))
        assertFalse(telemetry.recordCaptureSeen(CAMERA_ID, GENERATION))
        telemetry.snapshot(snapshot)

        assertEquals(GENERATION + 1L, snapshot.sessionGeneration)
        assertEquals(1L, snapshot.captureResultsSeen)
        assertEquals(1L, snapshot.captureResultsPublished)
        assertEquals(17L, snapshot.latestPublishedFrameNumber)
        assertEquals(680_000_000L, snapshot.latestPublishedSensorTimestampNs)
        assertEquals(0L, snapshot.resetCount)
    }

    @Test
    fun `camera switch and retirement are explicit resets`() {
        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(telemetry.beginTimeline("rear-tele", GENERATION))
        telemetry.retireTimeline()
        telemetry.retireTimeline()

        telemetry.snapshot(snapshot)
        assertEquals("", snapshot.cameraId)
        assertEquals(-1L, snapshot.sessionGeneration)
        assertEquals(2L, snapshot.resetCount)
    }

    @Test
    fun `pixel application evidence remains an explicit scoped count`() {
        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordTuningAccepted(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordTuningAccepted(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordPixelEligibleTuning(CAMERA_ID, GENERATION))
        assertEquals(1L, telemetry.recordPixelApplied(CAMERA_ID, GENERATION, recordingPath = false))
        assertEquals(2L, telemetry.recordPixelApplied(CAMERA_ID, GENERATION, recordingPath = true))
        assertEquals(-1L, telemetry.recordPixelApplied(
            CAMERA_ID,
            GENERATION + 1L,
            recordingPath = false
        ))
        assertEquals(1L, telemetry.recordPixelRejected(CAMERA_ID, GENERATION, recordingPath = false))
        assertEquals(2L, telemetry.recordPixelRejected(CAMERA_ID, GENERATION, recordingPath = true))

        telemetry.snapshot(snapshot)
        assertEquals(2L, snapshot.tuningAccepted)
        assertEquals(1L, snapshot.pixelEligibleTunings)
        assertEquals(2L, snapshot.pixelAppliedFrames)
        assertEquals(1L, snapshot.previewPixelAppliedFrames)
        assertEquals(1L, snapshot.recordingPixelAppliedFrames)
        assertEquals(2L, snapshot.pixelRenderRejectedFrames)
        assertEquals(1L, snapshot.previewPixelRenderRejectedFrames)
        assertEquals(1L, snapshot.recordingPixelRenderRejectedFrames)
    }

    @Test
    fun `new generation cannot inherit renderer activation from its predecessor`() {
        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(telemetry.recordPixelEligibleTuning(CAMERA_ID, GENERATION))
        assertEquals(1L, telemetry.recordPixelApplied(CAMERA_ID, GENERATION, recordingPath = false))
        assertEquals(1L, telemetry.recordPixelRejected(CAMERA_ID, GENERATION, recordingPath = true))

        assertTrue(telemetry.beginTimeline(CAMERA_ID, GENERATION + 1L))
        telemetry.snapshot(snapshot)

        assertEquals(GENERATION + 1L, snapshot.sessionGeneration)
        assertEquals(0L, snapshot.pixelEligibleTunings)
        assertEquals(0L, snapshot.pixelAppliedFrames)
        assertEquals(0L, snapshot.previewPixelAppliedFrames)
        assertEquals(0L, snapshot.recordingPixelAppliedFrames)
        assertEquals(0L, snapshot.pixelRenderRejectedFrames)
        assertEquals(0L, snapshot.previewPixelRenderRejectedFrames)
        assertEquals(0L, snapshot.recordingPixelRenderRejectedFrames)
    }

    private companion object {
        const val CAMERA_ID = "rear-wide"
        const val GENERATION = 9L
    }
}
