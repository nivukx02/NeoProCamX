package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAvIntegrityPolicyTest {
    @Test
    fun `requested audio fails closed when its committed track is absent`() {
        val audit = RecordingAvIntegrityPolicy.audit(
            audioRequested = true,
            videoTrackIndex = 0,
            audioTrackIndex = -1,
            videoSampleCount = 250,
            audioSampleCount = 0,
            firstVideoPtsUs = 0,
            lastVideoPtsUs = 9_960_000,
            firstAudioPtsUs = -1,
            lastAudioPtsUs = -1,
            videoFps = 25
        )

        assertFalse(audit.passed)
        assertTrue(audit.failureReason.orEmpty().contains("no audio track"))
    }

    @Test
    fun `requested audio fails closed when track has no committed samples`() {
        val audit = RecordingAvIntegrityPolicy.audit(
            audioRequested = true,
            videoTrackIndex = 0,
            audioTrackIndex = 1,
            videoSampleCount = 250,
            audioSampleCount = 0,
            firstVideoPtsUs = 0,
            lastVideoPtsUs = 9_960_000,
            firstAudioPtsUs = -1,
            lastAudioPtsUs = -1,
            videoFps = 25
        )

        assertFalse(audit.passed)
        assertTrue(audit.failureReason.orEmpty().contains("no audio samples"))
    }

    @Test
    fun `committed timelines inside strict offset and drift limits pass`() {
        val audit = RecordingAvIntegrityPolicy.audit(
            audioRequested = true,
            videoTrackIndex = 0,
            audioTrackIndex = 1,
            videoSampleCount = 250,
            audioSampleCount = 469,
            firstVideoPtsUs = 0,
            lastVideoPtsUs = 9_960_000,
            firstAudioPtsUs = 10_000,
            lastAudioPtsUs = 9_989_000,
            videoFps = 25
        )

        assertTrue(audit.passed)
        assertEquals(10_000L, audit.startOffsetUs)
        assertTrue(audit.durationDriftUs <= RecordingAvIntegrityPolicy.MAXIMUM_DURATION_DRIFT_US)
    }

    @Test
    fun `duration drift beyond limit fails`() {
        val audit = RecordingAvIntegrityPolicy.audit(
            audioRequested = true,
            videoTrackIndex = 0,
            audioTrackIndex = 1,
            videoSampleCount = 250,
            audioSampleCount = 450,
            firstVideoPtsUs = 0,
            lastVideoPtsUs = 9_960_000,
            firstAudioPtsUs = 0,
            lastAudioPtsUs = 9_900_000,
            videoFps = 25
        )

        assertFalse(audit.passed)
        assertTrue(audit.failureReason.orEmpty().contains("duration drift"))
    }

    @Test
    fun `intentional video only recording does not require audio evidence`() {
        val audit = RecordingAvIntegrityPolicy.audit(
            audioRequested = false,
            videoTrackIndex = 0,
            audioTrackIndex = -1,
            videoSampleCount = 250,
            audioSampleCount = 0,
            firstVideoPtsUs = 0,
            lastVideoPtsUs = 9_960_000,
            firstAudioPtsUs = -1,
            lastAudioPtsUs = -1,
            videoFps = 25
        )

        assertTrue(audit.passed)
    }

    @Test
    fun `certificate reports committed timeline separately from transient producer lead lag`() {
        val audit = RecordingAvIntegrityPolicy.audit(
            audioRequested = true,
            videoTrackIndex = 0,
            audioTrackIndex = 1,
            videoSampleCount = 1_334,
            audioSampleCount = 2_502,
            firstVideoPtsUs = 0L,
            lastVideoPtsUs = 53_320_000L,
            firstAudioPtsUs = 5_334L,
            lastAudioPtsUs = 53_341_335L,
            videoFps = 25
        )

        val metadata = RecordingAvIntegrityPolicy.toCertificateMetadata(
            audit = audit,
            transientProducerLeadLagMs = 144L
        )

        assertTrue(audit.passed)
        assertEquals("true", metadata["recordingCommittedAvAuditPassed"])
        assertEquals("144", metadata["recordingTransientProducerLeadLagMaxMs"])
        assertEquals(6L, RecordingAvIntegrityPolicy.maximumCommittedTimingErrorMs(audit))
    }
}
