package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStopFinalizationPolicyTest {

    @Test
    fun closeBackendOnlyWhenMuxerStartedAndTrackExists() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = true,
            videoTrackIndex = 0,
            pendingSamples = 0,
            writerActive = false
        )

        assertFalse(decision.waitForWriter)
        assertTrue(decision.closeBackend)
        assertFalse(decision.abortBackend)
    }

    @Test
    fun abortBackendWhenMuxerNeverStarted() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = false,
            videoTrackIndex = -1,
            pendingSamples = 0,
            writerActive = false
        )

        assertFalse(decision.waitForWriter)
        assertFalse(decision.closeBackend)
        assertTrue(decision.abortBackend)
    }

    @Test
    fun abortBackendWhenNoVideoSamplesWereEncoded() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = true,
            videoTrackIndex = 0,
            pendingSamples = 0,
            writerActive = false,
            encodedVideoSamples = 0
        )

        assertFalse(decision.waitForWriter)
        assertFalse(decision.closeBackend)
        assertTrue(decision.abortBackend)
    }

    @Test
    fun closeBackendWhenStrictOffscreenFrameCountFallsBelowTargetButSamplesExist() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = true,
            videoTrackIndex = 0,
            pendingSamples = 0,
            writerActive = false,
            encodedVideoSamples = 167,
            strictFrameCountRequired = true,
            strictMinimumVideoSamples = 248
        )

        assertFalse(decision.waitForWriter)
        assertTrue(decision.closeBackend)
        assertFalse(decision.abortBackend)
    }

    @Test
    fun closeBackendWhenStrictOffscreenFrameCountMeetsTarget() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = true,
            videoTrackIndex = 0,
            pendingSamples = 0,
            writerActive = false,
            encodedVideoSamples = 250,
            strictFrameCountRequired = true,
            strictMinimumVideoSamples = 248
        )

        assertFalse(decision.waitForWriter)
        assertTrue(decision.closeBackend)
        assertFalse(decision.abortBackend)
    }

    @Test
    fun nonStrictFrameShortfallKeepsLegacyCloseBehavior() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = true,
            videoTrackIndex = 0,
            pendingSamples = 0,
            writerActive = false,
            encodedVideoSamples = 167,
            strictFrameCountRequired = false,
            strictMinimumVideoSamples = 248
        )

        assertFalse(decision.waitForWriter)
        assertTrue(decision.closeBackend)
        assertFalse(decision.abortBackend)
    }

    @Test
    fun pendingSamplesRequireWriterWaitBeforeClose() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = true,
            videoTrackIndex = 0,
            pendingSamples = 4,
            writerActive = true
        )

        assertTrue(decision.waitForWriter)
        assertFalse(decision.closeBackend)
        assertFalse(decision.abortBackend)
    }

    @Test
    fun writerStillActiveRequiresWaitEvenWithoutPendingSamples() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = true,
            videoTrackIndex = 0,
            pendingSamples = 0,
            writerActive = true
        )

        assertTrue(decision.waitForWriter)
        assertFalse(decision.closeBackend)
        assertFalse(decision.abortBackend)
    }

    @Test
    fun exposesBoundedStopBudgets() {
        assertEquals(250L, RecordingStopFinalizationPolicy.TRACK_WAIT_BUDGET_MS)
        assertEquals(350L, RecordingStopFinalizationPolicy.CODEC_DRAIN_BUDGET_MS)
        assertEquals(350L, RecordingStopFinalizationPolicy.MUXER_QUEUE_DRAIN_BUDGET_MS)
        assertEquals(8_000L, RecordingStopFinalizationPolicy.WRITER_COMPLETION_BUDGET_MS)
    }
}
