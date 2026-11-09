package com.neoproc.neoproccamx.recording

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioSyncPolicyTest {

    @Test
    fun cumulativePcmTimelineUsesSharedRecordingOriginWithoutVideoPtsSnapping() {
        val timeline = RecordingAudioSyncPolicy.Timeline(
            recordingStartNs = 1_000_000_000L,
            sampleRate = 48_000,
            channelCount = 2,
            bytesPerSample = 2,
            initialSubmittedFrames = 1_024L
        )

        val firstPtsUs = timeline.presentationTimeUs(
            readCompletedNs = 1_100_000_000L,
            bytesRead = 4_096
        )
        timeline.commitQueuedBytes(4_096)
        val secondPtsUs = timeline.presentationTimeUs(
            readCompletedNs = 9_000_000_000L,
            bytesRead = 4_096
        )
        timeline.commitQueuedBytes(4_096)

        assertEquals(78_667L, firstPtsUs)
        assertEquals(100_000L, secondPtsUs)
        assertEquals(121_333L, timeline.endPresentationTimeUs())
    }

    @Test
    fun timelineAdvancesOnlyAfterPcmWasQueued() {
        val timeline = RecordingAudioSyncPolicy.Timeline(
            recordingStartNs = 5_000_000_000L,
            sampleRate = 48_000,
            channelCount = 2,
            bytesPerSample = 2
        )

        val firstAttemptPtsUs = timeline.presentationTimeUs(
            readCompletedNs = 5_021_333_333L,
            bytesRead = 4_096
        )
        val retryPtsUs = timeline.presentationTimeUs(
            readCompletedNs = 8_000_000_000L,
            bytesRead = 4_096
        )
        timeline.commitQueuedBytes(4_096)

        assertEquals(0L, firstAttemptPtsUs)
        assertEquals(firstAttemptPtsUs, retryPtsUs)
        assertEquals(21_333L, timeline.endPresentationTimeUs())
    }

    @Test
    fun eosDrainSurvivesTransientEmptyDequeuesUntilBoundedDeadline() {
        assertTrue(
            RecordingAudioSyncPolicy.shouldContinueEosDrain(
                eosSeen = false,
                elapsedUs = 500_000L
            )
        )
        assertFalse(
            RecordingAudioSyncPolicy.shouldContinueEosDrain(
                eosSeen = false,
                elapsedUs = RecordingAudioSyncPolicy.EOS_DRAIN_TIMEOUT_US
            )
        )
        assertFalse(
            RecordingAudioSyncPolicy.shouldContinueEosDrain(
                eosSeen = true,
                elapsedUs = 10L
            )
        )
    }

    @Test
    fun urgentAudioThreadPriorityIsPartOfTheContract() {
        assertEquals(Process.THREAD_PRIORITY_URGENT_AUDIO, RecordingAudioSyncPolicy.AUDIO_THREAD_PRIORITY)
        assertTrue(RecordingAudioSyncPolicy.threadSignature().contains("THREAD_PRIORITY_URGENT_AUDIO"))
        assertTrue(RecordingAudioSyncPolicy.threadSignature().contains("sample-count-timeline"))
    }

    @Test
    fun physicalStereoCaptureSignatureExposesExactlyHalfDurationWhenOneOfTwoAacUnitsIsDrained() {
        val stereoPcmBytesPerCaptureRead = 8_192L
        val stereoBytesPerPcmFrame = 2L * 2L
        val pcmFramesPerRead = stereoPcmBytesPerCaptureRead / stereoBytesPerPcmFrame
        val aacAccessUnitsPerRead = pcmFramesPerRead / 1_024L
        val expectedAccessUnits = 660L
        val observedAccessUnits = 330L

        val expectedDurationUs = RecordingAudioSyncPolicy.durationUsForFrames(
            expectedAccessUnits * 1_024L,
            48_000
        )
        val observedDurationUs = RecordingAudioSyncPolicy.durationUsForFrames(
            observedAccessUnits * 1_024L,
            48_000
        )

        assertEquals(2L, aacAccessUnitsPerRead)
        assertEquals(expectedDurationUs / 2L, observedDurationUs)
        assertEquals(7_040_000L, observedDurationUs)
    }
}
