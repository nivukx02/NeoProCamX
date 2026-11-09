package com.neoproc.neoproccamx.recording

import android.os.Process
import java.nio.ByteBuffer
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
    fun synchronizedTimelineStartsImmediatelyAfterThePrimingAccessUnit() {
        val timeline = RecordingAudioSyncPolicy.Timeline(
            recordingStartNs = 1_000_000_000L,
            sampleRate = 48_000,
            channelCount = 2,
            bytesPerSample = 2,
            initialSubmittedFrames = 1_024L,
            anchorFirstPcmToTimelineStart = true
        )

        assertEquals(
            21_333L,
            timeline.presentationTimeUs(
                readCompletedNs = 1_400_000_000L,
                bytesRead = 8_192
            )
        )
        timeline.commitQueuedBytes(8_192)
        assertEquals(63_999L, timeline.endPresentationTimeUs())
    }

    @Test
    fun audioMuxHorizonWaitsWhileLiveAndTrimsOnlyAfterVideoEos() {
        val primer = RecordingAudioSyncPolicy.audioMuxHorizonDecision(
            audioSamplePtsUs = 0L,
            latestVideoPtsUs = -1L,
            videoFps = 25,
            audioSampleRate = 48_000,
            maximumDurationDriftUs = 20_000L,
            recordingActive = true,
            videoDrainComplete = false
        )
        val realAudioBeforeVideo = RecordingAudioSyncPolicy.audioMuxHorizonDecision(
            audioSamplePtsUs = 21_333L,
            latestVideoPtsUs = -1L,
            videoFps = 25,
            audioSampleRate = 48_000,
            maximumDurationDriftUs = 20_000L,
            recordingActive = true,
            videoDrainComplete = false
        )
        val waiting = RecordingAudioSyncPolicy.audioMuxHorizonDecision(
            audioSamplePtsUs = 1_040_000L,
            latestVideoPtsUs = 960_000L,
            videoFps = 25,
            audioSampleRate = 48_000,
            maximumDurationDriftUs = 20_000L,
            recordingActive = true,
            videoDrainComplete = false
        )
        val terminalTrim = RecordingAudioSyncPolicy.audioMuxHorizonDecision(
            audioSamplePtsUs = 1_040_000L,
            latestVideoPtsUs = 960_000L,
            videoFps = 25,
            audioSampleRate = 48_000,
            maximumDurationDriftUs = 20_000L,
            recordingActive = false,
            videoDrainComplete = true
        )
        val admitted = RecordingAudioSyncPolicy.audioMuxHorizonDecision(
            audioSamplePtsUs = 998_000L,
            latestVideoPtsUs = 960_000L,
            videoFps = 25,
            audioSampleRate = 48_000,
            maximumDurationDriftUs = 20_000L,
            recordingActive = false,
            videoDrainComplete = true
        )

        assertEquals(RecordingAudioSyncPolicy.AudioMuxHorizonDecision.ADMIT, primer)
        assertEquals(
            RecordingAudioSyncPolicy.AudioMuxHorizonDecision.WAIT_FOR_VIDEO,
            realAudioBeforeVideo
        )
        assertEquals(RecordingAudioSyncPolicy.AudioMuxHorizonDecision.WAIT_FOR_VIDEO, waiting)
        assertEquals(
            RecordingAudioSyncPolicy.AudioMuxHorizonDecision.DISCARD_TERMINAL_TAIL,
            terminalTrim
        )
        assertEquals(RecordingAudioSyncPolicy.AudioMuxHorizonDecision.ADMIT, admitted)
    }

    @Test
    fun terminalAudioCoverageWaitsForVideoEosThenClosesObservedPhysicalTailGap() {
        val beforeVideoEos = RecordingAudioSyncPolicy.terminalAudioCoverage(
            latestVideoPtsUs = 60_240_000L,
            latestAudioPtsUs = 60_160_000L,
            videoFps = 25,
            audioSampleRate = 48_000,
            maximumDurationDriftUs = 20_000L,
            videoDrainComplete = false
        )
        val observedDeviceGap = RecordingAudioSyncPolicy.terminalAudioCoverage(
            latestVideoPtsUs = 60_240_000L,
            latestAudioPtsUs = 60_160_000L,
            videoFps = 25,
            audioSampleRate = 48_000,
            maximumDurationDriftUs = 20_000L,
            videoDrainComplete = true
        )
        val coveredByRealAacTail = RecordingAudioSyncPolicy.terminalAudioCoverage(
            latestVideoPtsUs = 60_240_000L,
            latestAudioPtsUs = 60_245_332L,
            videoFps = 25,
            audioSampleRate = 48_000,
            maximumDurationDriftUs = 20_000L,
            videoDrainComplete = true
        )

        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioCoverageDecision.WAIT_FOR_VIDEO_EOS,
            beforeVideoEos.decision
        )
        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioCoverageDecision.CAPTURE_MORE_AUDIO,
            observedDeviceGap.decision
        )
        assertEquals(60_280_000L, observedDeviceGap.videoEndPtsUs)
        assertEquals(60_181_333L, observedDeviceGap.audioEndPtsUs)
        assertEquals(60_260_000L, observedDeviceGap.minimumRequiredAudioEndPtsUs)
        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioCoverageDecision.COVERED,
            coveredByRealAacTail.decision
        )
        assertEquals(60_266_665L, coveredByRealAacTail.audioEndPtsUs)
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
    fun terminalAudioProgressCanCoverMeasured224msTailAfterFormerFixedDeadline() {
        val deadline = RecordingAudioSyncPolicy.TerminalAudioProgressDeadline()
        fun coverage(audioPtsUs: Long) = RecordingAudioSyncPolicy.terminalAudioCoverage(
            latestVideoPtsUs = 302_840_000L,
            latestAudioPtsUs = audioPtsUs,
            videoFps = 25,
            audioSampleRate = 48_000,
            maximumDurationDriftUs = RecordingAvIntegrityPolicy.MAXIMUM_DURATION_DRIFT_US,
            videoDrainComplete = true
        )
        val progressing = RecordingAudioSyncPolicy.TerminalAudioWaitDecision.CONTINUE
        assertEquals(progressing, deadline.observe(coverage(301_506_666L), 0L))
        assertEquals(progressing, deadline.observe(coverage(302_006_666L), 500_000L))
        assertEquals(progressing, deadline.observe(coverage(302_506_666L), 1_002_208L))
        assertEquals(progressing, deadline.observe(coverage(302_634_666L), 1_140_000L))
        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioWaitDecision.COVERED,
            deadline.observe(coverage(302_848_000L), 1_400_000L)
        )
        assertEquals(20_000L, RecordingAvIntegrityPolicy.MAXIMUM_DURATION_DRIFT_US)
    }

    @Test
    fun terminalAudioProgressDoesNotKeepAStalledOrRewindingCodecAlive() {
        val deadline = RecordingAudioSyncPolicy.TerminalAudioProgressDeadline()
        val frozen = RecordingAudioSyncPolicy.TerminalAudioCoverage(
            RecordingAudioSyncPolicy.TerminalAudioCoverageDecision.CAPTURE_MORE_AUDIO,
            videoEndPtsUs = 2_000_000L,
            audioEndPtsUs = 1_000_000L,
            minimumRequiredAudioEndPtsUs = 1_980_000L
        )
        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioWaitDecision.CONTINUE,
            deadline.observe(frozen, 0L)
        )
        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioWaitDecision.CONTINUE,
            deadline.observe(frozen.copy(audioEndPtsUs = 900_000L), 500_000L)
        )
        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioWaitDecision.STALLED,
            deadline.observe(frozen, 1_000_000L)
        )
    }

    @Test
    fun terminalAudioProgressHasAnAbsoluteDeadlineEvenWhenEveryPollAdvances() {
        val deadline = RecordingAudioSyncPolicy.TerminalAudioProgressDeadline()
        for (step in 0L..6L) {
            val coverage = RecordingAudioSyncPolicy.TerminalAudioCoverage(
                RecordingAudioSyncPolicy.TerminalAudioCoverageDecision.CAPTURE_MORE_AUDIO,
                videoEndPtsUs = 60_000_000L,
                audioEndPtsUs = step * 100_000L,
                minimumRequiredAudioEndPtsUs = 59_980_000L
            )
            assertEquals(
                if (step == 6L) RecordingAudioSyncPolicy.TerminalAudioWaitDecision.DEADLINE_EXCEEDED
                else RecordingAudioSyncPolicy.TerminalAudioWaitDecision.CONTINUE,
                deadline.observe(coverage, step * 500_000L)
            )
        }
    }

    @Test
    fun terminalVideoEosAndClockFailuresRemainBounded() {
        val pendingVideo = RecordingAudioSyncPolicy.TerminalAudioCoverage(
            RecordingAudioSyncPolicy.TerminalAudioCoverageDecision.WAIT_FOR_VIDEO_EOS,
            -1L, -1L, -1L
        )
        val deadline = RecordingAudioSyncPolicy.TerminalAudioProgressDeadline()
        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioWaitDecision.CONTINUE,
            deadline.observe(pendingVideo, 1_000_000L)
        )
        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioWaitDecision.STALLED,
            deadline.observe(pendingVideo, 2_000_000L)
        )
        assertEquals(
            RecordingAudioSyncPolicy.TerminalAudioWaitDecision.DEADLINE_EXCEEDED,
            deadline.observe(pendingVideo, 1L)
        )
    }

    @Test
    fun urgentAudioThreadPriorityIsPartOfTheContract() {
        assertEquals(Process.THREAD_PRIORITY_URGENT_AUDIO, RecordingAudioSyncPolicy.AUDIO_THREAD_PRIORITY)
        assertTrue(RecordingAudioSyncPolicy.threadSignature().contains("THREAD_PRIORITY_URGENT_AUDIO"))
        assertTrue(RecordingAudioSyncPolicy.threadSignature().contains("sample-count-timeline"))
        assertTrue(RecordingAudioSyncPolicy.threadSignature().contains("bounded-timestamp-preserving"))
    }

    @Test
    fun stagedPcmSurvivesCodecInputMissAndKeepsFifoCaptureIdentity() {
        val staging = RecordingAudioSyncPolicy.PcmStagingBuffer(
            packetCapacity = 4,
            packetByteCapacity = 8
        )
        val first = ByteBuffer.wrap(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
        val second = ByteBuffer.wrap(byteArrayOf(8, 9, 10, 11, 12, 13, 14, 15))

        assertTrue(staging.offer(first, bytes = 8, captureCompletedNs = 100L))
        assertTrue(staging.offer(second, bytes = 8, captureCompletedNs = 200L))
        staging.markCodecInputUnavailable()
        staging.markCodecInputUnavailable()

        val firstCodecBuffer = ByteBuffer.allocate(4)
        val firstWrite = requireNotNull(staging.writeHeadTo(firstCodecBuffer, bytesPerPcmFrame = 4))
        assertEquals(4, firstWrite.bytes)
        assertEquals(100L, firstWrite.captureCompletedNs)
        assertEquals(8, firstWrite.capturedPacketBytes)
        staging.commitHead(firstWrite.bytes)

        val retryCodecBuffer = ByteBuffer.allocate(8)
        val retryWrite = requireNotNull(staging.writeHeadTo(retryCodecBuffer, bytesPerPcmFrame = 4))
        assertEquals(4, retryWrite.bytes)
        assertEquals(100L, retryWrite.captureCompletedNs)
        staging.commitHead(retryWrite.bytes)

        val secondCodecBuffer = ByteBuffer.allocate(8)
        val secondWrite = requireNotNull(staging.writeHeadTo(secondCodecBuffer, bytesPerPcmFrame = 4))
        assertEquals(8, secondWrite.bytes)
        assertEquals(200L, secondWrite.captureCompletedNs)

        val snapshot = staging.snapshot()
        assertEquals(8, snapshot.queuedBytes)
        assertEquals(2L, snapshot.codecInputUnavailableCount)
        assertEquals(16, snapshot.peakQueuedBytes)
    }

    @Test
    fun stagedPcmBackpressureIsBoundedAndMeasuredWithoutEvictingCapturedPackets() {
        val staging = RecordingAudioSyncPolicy.PcmStagingBuffer(
            packetCapacity = 4,
            packetByteCapacity = 8
        )
        repeat(4) { packetIndex ->
            assertTrue(
                staging.offer(
                    ByteBuffer.wrap(ByteArray(8) { packetIndex.toByte() }),
                    bytes = 8,
                    captureCompletedNs = packetIndex.toLong()
                )
            )
        }

        assertFalse(staging.canAccept(8))
        staging.markCaptureReadDeferred()
        assertFalse(
            staging.offer(
                ByteBuffer.wrap(ByteArray(8)),
                bytes = 8,
                captureCompletedNs = 99L
            )
        )

        val snapshot = staging.snapshot()
        assertEquals(4, snapshot.queuedPackets)
        assertEquals(32, snapshot.queuedBytes)
        assertEquals(32, snapshot.capacityBytes)
        assertEquals(1L, snapshot.captureReadDeferralCount)
        assertEquals(1L, snapshot.rejectedPacketCount)
    }

    @Test
    fun synchronizedStartDropsSetupAudioAndFrameAlignsTheCrossingPacket() {
        val staging = RecordingAudioSyncPolicy.PcmStagingBuffer(
            packetCapacity = 4,
            packetByteCapacity = 4_096
        )
        assertTrue(
            staging.offer(
                ByteBuffer.wrap(ByteArray(4_096) { 1 }),
                bytes = 4_096,
                captureCompletedNs = 1_021_333_333L
            )
        )
        assertTrue(
            staging.offer(
                ByteBuffer.wrap(ByteArray(4_096) { 2 }),
                bytes = 4_096,
                captureCompletedNs = 1_042_666_667L
            )
        )

        val trim = staging.discardBeforeCaptureBoundary(
            captureBoundaryNs = 1_032_000_000L,
            sampleRate = 48_000,
            channelCount = 2,
            bytesPerSample = 2
        )

        assertEquals(6_144L, trim.discardedBytes)
        assertEquals(2_048, trim.retainedBytes)
        assertEquals(1, trim.retainedPackets)
        val write = requireNotNull(
            staging.writeHeadTo(ByteBuffer.allocate(4_096), bytesPerPcmFrame = 4)
        )
        assertEquals(2_048, write.bytes)
        assertEquals(2_048, write.capturedPacketBytes)

        val timeline = RecordingAudioSyncPolicy.Timeline(
            recordingStartNs = 1_032_000_000L,
            sampleRate = 48_000,
            channelCount = 2,
            bytesPerSample = 2
        )
        assertEquals(
            0L,
            timeline.presentationTimeUs(
                readCompletedNs = write.captureCompletedNs,
                bytesRead = write.capturedPacketBytes
            )
        )
    }

    @Test
    fun stagingCapacityTracksAudioRateButStaysWithinHardBounds() {
        assertEquals(
            12,
            RecordingAudioSyncPolicy.recommendedPcmStagingPacketCount(
                packetBytes = 8_192,
                sampleRate = 48_000,
                channelCount = 2,
                bytesPerSample = 2
            )
        )
        assertEquals(
            64,
            RecordingAudioSyncPolicy.recommendedPcmStagingPacketCount(
                packetBytes = 1,
                sampleRate = 192_000,
                channelCount = 8,
                bytesPerSample = 4
            )
        )
        assertEquals(
            64,
            RecordingAudioSyncPolicy.recommendedPcmStagingPacketCount(
                packetBytes = 1,
                sampleRate = Int.MAX_VALUE,
                channelCount = Int.MAX_VALUE,
                bytesPerSample = Int.MAX_VALUE
            )
        )
        assertEquals(
            Long.MAX_VALUE,
            RecordingAudioSyncPolicy.durationUsForFrames(
                frameCount = Long.MAX_VALUE,
                sampleRate = 1
            )
        )
        assertEquals(
            59,
            RecordingAudioSyncPolicy.recommendedPcmStagingPacketCount(
                packetBytes = 8_192,
                sampleRate = 48_000,
                channelCount = 2,
                bytesPerSample = 2,
                targetDurationUs =
                    RecordingAudioSyncPolicy.SYNCHRONIZED_START_STAGING_DURATION_US
            )
        )
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
