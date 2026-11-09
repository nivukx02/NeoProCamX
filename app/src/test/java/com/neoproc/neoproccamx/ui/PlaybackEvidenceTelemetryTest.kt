package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEvidenceTelemetryTest {

    @Test
    fun `cadence snapshot measures presented PTS progress without inventing frames`() {
        var nowNs = 10L
        val session = PlaybackEvidenceSession(
            sessionId = 7L,
            path = PlaybackEvidencePath.ENCODED_MASTER,
            sourceSha256 = "a".repeat(64),
            clockNs = { nowNs }
        )
        session.recordFormat(3840, 2160, 30f, "video/hevc")
        session.recordPresentation(0L, 1_000_000_000L)
        session.recordPresentation(33_333L, 1_033_333_333L)
        session.recordPresentation(66_666L, 1_066_666_666L)
        session.recordPresentation(99_999L, 1_099_999_999L)

        val snapshot = session.snapshot()
        assertEquals(4L, snapshot.presentationCallbacks)
        assertEquals(99_999L, snapshot.ptsProgressUs)
        assertEquals(99_999_999L, snapshot.presentationSpanNs)
        assertTrue(snapshot.measuredPresentationFpsMilli in 29_999L..30_001L)
        assertEquals(0L, snapshot.repeatedPtsFrames)
        assertEquals(0L, snapshot.ptsRegressionCount)
        assertFalse(snapshot.finalized)
    }

    @Test
    fun `intentional discontinuity resets comparison while real repeats regressions and skips remain visible`() {
        val session = newSession()
        session.recordPrivateFrameScheduled(0)
        session.recordPrivateFrameScheduled(1)
        session.recordPrivateFrameScheduled(4)
        session.recordPresentation(0L, 100L)
        session.recordPresentation(0L, 200L)
        session.recordDiscontinuity()
        session.recordPresentation(1_000_000L, 300L)
        session.recordPresentation(900_000L, 400L)
        session.recordUiConflation(2L)
        session.recordDroppedFrameEvent(3)

        val snapshot = session.snapshot()
        assertEquals(2L, snapshot.schedulerSkippedFrames)
        assertEquals(2L, snapshot.uiConflatedFrames)
        assertEquals(3L, snapshot.droppedFrameEvents)
        assertEquals(1L, snapshot.repeatedPtsFrames)
        assertEquals(1L, snapshot.ptsRegressionCount)
        assertEquals(1L, snapshot.discontinuityCount)
        assertEquals(7L, snapshot.effectiveDroppedFrames)
    }

    @Test
    fun `repeat discontinuity arriving after wrapped frame clears provisional regression`() {
        val session = newSession()
        session.recordPresentation(0L, 100L)
        session.recordPresentation(40_000L, 200L)
        session.recordPresentation(80_000L, 300L)
        session.recordPresentation(0L, 400L)
        assertEquals(1L, session.snapshot().ptsRegressionCount)
        assertEquals(200L, session.snapshot().presentationSpanNs)

        session.recordDiscontinuity()

        assertEquals(0L, session.snapshot().ptsRegressionCount)
        assertEquals(1L, session.snapshot().discontinuityCount)
        assertEquals(200L, session.snapshot().presentationSpanNs)
    }

    @Test
    fun `preroll callbacks remain visible without contaminating active cadence`() {
        val session = newSession()
        session.recordPrerollPresentation()
        session.recordPrerollPresentation()
        session.recordPresentation(0L, 1_000_000_000L)
        session.recordPresentation(40_000L, 1_040_000_000L)

        val snapshot = session.snapshot()
        assertEquals(2L, snapshot.prerollPresentationCallbacks)
        assertEquals(2L, snapshot.presentationCallbacks)
        assertEquals(40_000_000L, snapshot.presentationSpanNs)
        assertEquals(25_000L, snapshot.measuredPresentationFpsMilli)
    }

    @Test
    fun `finish waits for exact resource retirement and completes once`() {
        var nowNs = 1L
        val finalized = mutableListOf<PlaybackEvidenceSnapshot>()
        val session = PlaybackEvidenceSession(
            sessionId = 9L,
            path = PlaybackEvidencePath.PRIVATE_NPRAW_MASTER,
            sourceSha256 = "b".repeat(64),
            clockNs = { nowNs },
            onFinalized = finalized::add
        )
        session.acquireResource("worker")
        session.acquireResource("bitmap")
        session.recordDecoderInitialized("raw-develop")
        session.requestFinish("activity_destroyed")

        assertTrue(session.snapshot().finishRequested)
        assertFalse(session.snapshot().finalized)
        assertEquals("bitmap,worker", session.snapshot().activeResourceNames)
        session.releaseResource("bitmap")
        session.recordDecoderReleased("raw-develop")
        assertFalse(session.snapshot().finalized)
        nowNs = 50L
        session.releaseResource("worker")

        assertEquals(1, finalized.size)
        assertTrue(finalized.single().finalized)
        assertTrue(finalized.single().resourceReleaseComplete)
        assertEquals(2L, finalized.single().resourcesAcquired)
        assertEquals(2L, finalized.single().resourcesReleased)
    }

    @Test
    fun `finish cannot seal before initialized decoder release`() {
        val finalized = mutableListOf<PlaybackEvidenceSnapshot>()
        val session = PlaybackEvidenceSession(
            sessionId = 10L,
            path = PlaybackEvidencePath.ENCODED_MASTER,
            sourceSha256 = "d".repeat(64),
            clockNs = { 2L },
            onFinalized = finalized::add
        )
        session.recordDecoderInitialized("decoder")
        session.requestFinish("released")

        assertFalse(session.snapshot().finalized)
        assertTrue(finalized.isEmpty())

        session.recordDecoderReleased("decoder")

        assertTrue(session.snapshot().finalized)
        assertEquals(1, finalized.size)
        assertTrue(finalized.single().resourceReleaseComplete)
    }

    @Test
    fun `encoded presentation cannot seal before decoder lifecycle evidence`() {
        val session = PlaybackEvidenceSession(
            sessionId = 11L,
            path = PlaybackEvidencePath.ENCODED_MASTER,
            sourceSha256 = "1".repeat(64),
            clockNs = { 2L }
        )
        session.recordPresentation(0L, 100L)
        session.requestFinish("released")
        assertFalse(session.snapshot().finalized)

        session.recordDecoderInitialized("decoder")
        assertFalse(session.snapshot().finalized)
        session.recordDecoderReleased("decoder")
        assertTrue(session.snapshot().finalized)
    }

    @Test
    fun `evidence identifies content digest separately from URI fallback`() {
        val content = PlaybackEvidenceSession(
            sessionId = 12L,
            path = PlaybackEvidencePath.ENCODED_MASTER,
            sourceSha256 = "e".repeat(64),
            sourceDigestKind = PlaybackSourceDigestKind.CONTENT_BYTES_SHA256,
            clockNs = { 3L }
        ).snapshot()
        val fallback = PlaybackEvidenceSession(
            sessionId = 13L,
            path = PlaybackEvidencePath.ENCODED_MASTER,
            sourceSha256 = "f".repeat(64),
            sourceDigestKind = PlaybackSourceDigestKind.URI_IDENTITY_SHA256,
            clockNs = { 3L }
        ).snapshot()

        assertEquals(PlaybackSourceDigestKind.CONTENT_BYTES_SHA256, content.sourceDigestKind)
        assertEquals(PlaybackSourceDigestKind.URI_IDENTITY_SHA256, fallback.sourceDigestKind)
        assertTrue(content.toEvidenceLine().contains("sourceDigestKind=CONTENT_BYTES_SHA256"))
        assertTrue(fallback.toEvidenceLine().contains("sourceDigestKind=URI_IDENTITY_SHA256"))
    }

    @Test
    fun `unexpected release is retained as a cleanup failure`() {
        val session = newSession()
        session.releaseResource("not-acquired")
        session.requestFinish("test")

        val snapshot = session.snapshot()
        assertTrue(snapshot.finalized)
        assertEquals(1L, snapshot.unexpectedResourceReleaseCount)
        assertFalse(snapshot.resourceReleaseComplete)
        assertTrue(snapshot.toEvidenceLine().contains("unexpectedReleases=1"))
        assertTrue(snapshot.toEvidenceLine().contains("releaseComplete=false"))
    }

    @Test
    fun `buffering duration excludes ready playback and seals as milliseconds`() {
        var nowNs = 1_000_000_000L
        val session = PlaybackEvidenceSession(
            sessionId = 11L,
            path = PlaybackEvidencePath.NIVUKX_ENCODED_MASTER,
            sourceSha256 = "c".repeat(64),
            clockNs = { nowNs }
        )
        session.recordPlaybackState(androidx.media3.common.Player.STATE_BUFFERING, nowNs)
        nowNs += 125_000_000L
        session.recordPlaybackState(androidx.media3.common.Player.STATE_READY, nowNs)
        nowNs += 1_000_000_000L
        session.recordPlaybackState(androidx.media3.common.Player.STATE_READY, nowNs)

        val snapshot = session.snapshot()
        assertEquals(1L, snapshot.bufferingCount)
        assertEquals(125L, snapshot.bufferingDurationMs)
    }

    private fun newSession(): PlaybackEvidenceSession = PlaybackEvidenceSession(
        sessionId = 1L,
        path = PlaybackEvidencePath.PRIVATE_NPV10_MASTER,
        sourceSha256 = "0".repeat(64),
        clockNs = { 1L }
    )
}
