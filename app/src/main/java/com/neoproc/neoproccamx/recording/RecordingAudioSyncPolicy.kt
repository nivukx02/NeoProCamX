package com.neoproc.neoproccamx.recording

import android.os.Process
import java.nio.ByteBuffer
import java.util.ArrayDeque

internal object RecordingAudioSyncPolicy {
    const val AUDIO_THREAD_PRIORITY = Process.THREAD_PRIORITY_URGENT_AUDIO
    const val EOS_DRAIN_TIMEOUT_US = 750_000L
    const val PCM_STAGING_STOP_DRAIN_TIMEOUT_US = 750_000L
    const val SYNCHRONIZED_START_STAGING_DURATION_US = 2_500_000L
    const val VIDEO_HORIZON_WAIT_TIMEOUT_US = 2_000_000L
    const val TERMINAL_AUDIO_COVERAGE_TIMEOUT_US = 1_000_000L
    const val TERMINAL_AUDIO_COVERAGE_POLL_MS = 2L
    const val TERMINAL_AUDIO_COVERAGE_HARD_TIMEOUT_US = 3_000_000L

    private const val MICROSECONDS_PER_SECOND = 1_000_000L
    private const val AAC_LC_FRAME_SAMPLES = 1_024L
    private const val PCM_STAGING_TARGET_DURATION_US = 500_000L
    private const val MIN_PCM_STAGING_PACKETS = 4
    private const val MAX_PCM_STAGING_PACKETS = 64

    internal enum class AudioMuxHorizonDecision {
        ADMIT,
        WAIT_FOR_VIDEO,
        DISCARD_TERMINAL_TAIL
    }

    internal enum class TerminalAudioCoverageDecision {
        WAIT_FOR_VIDEO_EOS,
        CAPTURE_MORE_AUDIO,
        COVERED
    }

    internal data class TerminalAudioCoverage(
        val decision: TerminalAudioCoverageDecision,
        val videoEndPtsUs: Long,
        val audioEndPtsUs: Long,
        val minimumRequiredAudioEndPtsUs: Long
    )

    internal enum class TerminalAudioWaitDecision {
        CONTINUE,
        COVERED,
        STALLED,
        DEADLINE_EXCEEDED
    }

    /**
     * A progress deadline, not permission to retime either track. On the physical device the
     * former fixed one-second stop timer expired while real AAC was still advancing, leaving
     * 224 ms of missing terminal coverage. Each newly drained access unit may refresh the idle
     * deadline, but the absolute limit cannot move. Dead codecs and missing video EOS remain
     * bounded; publication still uses the independent, unchanged committed A/V integrity gate.
     */
    internal class TerminalAudioProgressDeadline(
        private val idleTimeoutUs: Long = TERMINAL_AUDIO_COVERAGE_TIMEOUT_US,
        private val hardTimeoutUs: Long = TERMINAL_AUDIO_COVERAGE_HARD_TIMEOUT_US
    ) {
        private var latestAudioEndPtsUs = -1L
        private var lastProgressElapsedUs = 0L
        private var videoHorizonObserved = false
        private var lastObservedElapsedUs = 0L

        init {
            require(idleTimeoutUs > 0L)
            require(hardTimeoutUs >= idleTimeoutUs)
        }

        fun observe(coverage: TerminalAudioCoverage, elapsedUs: Long): TerminalAudioWaitDecision {
            // A broken clock cannot extend a recording shutdown indefinitely.
            if (elapsedUs < lastObservedElapsedUs || elapsedUs < 0L) {
                return TerminalAudioWaitDecision.DEADLINE_EXCEEDED
            }
            lastObservedElapsedUs = elapsedUs
            if (coverage.decision == TerminalAudioCoverageDecision.COVERED) {
                return TerminalAudioWaitDecision.COVERED
            }
            if (elapsedUs >= hardTimeoutUs) {
                return TerminalAudioWaitDecision.DEADLINE_EXCEEDED
            }
            if (coverage.decision == TerminalAudioCoverageDecision.WAIT_FOR_VIDEO_EOS) {
                return if (elapsedUs < VIDEO_HORIZON_WAIT_TIMEOUT_US) {
                    TerminalAudioWaitDecision.CONTINUE
                } else {
                    TerminalAudioWaitDecision.STALLED
                }
            }
            if (!videoHorizonObserved || coverage.audioEndPtsUs > latestAudioEndPtsUs) {
                videoHorizonObserved = true
                latestAudioEndPtsUs = maxOf(latestAudioEndPtsUs, coverage.audioEndPtsUs)
                lastProgressElapsedUs = elapsedUs
            }
            return if (elapsedUs - lastProgressElapsedUs < idleTimeoutUs) {
                TerminalAudioWaitDecision.CONTINUE
            } else {
                TerminalAudioWaitDecision.STALLED
            }
        }
    }

    /** Single-worker scratch result; valid until the next [PcmStagingBuffer.writeHeadTo] call. */
    internal class StagedPcmWrite {
        var bytes: Int = 0
            private set
        var captureCompletedNs: Long = 0L
            private set
        var capturedPacketBytes: Int = 0
            private set

        fun update(bytes: Int, captureCompletedNs: Long, capturedPacketBytes: Int) {
            this.bytes = bytes
            this.captureCompletedNs = captureCompletedNs
            this.capturedPacketBytes = capturedPacketBytes
        }
    }

    internal data class PcmStagingSnapshot(
        val queuedPackets: Int,
        val queuedBytes: Int,
        val peakQueuedBytes: Int,
        val capacityBytes: Int,
        val codecInputUnavailableCount: Long,
        val captureReadDeferralCount: Long,
        val rejectedPacketCount: Long
    )

    internal data class PcmBoundaryTrimResult(
        val discardedBytes: Long,
        val retainedBytes: Int,
        val retainedPackets: Int
    )

    /**
     * Bounded, allocation-stable PCM staging between AudioRecord and MediaCodec.
     *
     * AudioRecord owns the source buffer only until the next read, so accepted PCM is copied once
     * into a preallocated slot. A codec-input miss leaves the head packet and its capture timestamp
     * untouched for the next attempt; it never advances the audio timeline. This queue is owned by
     * the dedicated audio worker and deliberately has no locks on its hot path.
     */
    internal class PcmStagingBuffer(
        packetCapacity: Int,
        private val packetByteCapacity: Int
    ) {
        private class Packet(val storage: ByteArray) {
            var size: Int = 0
            var captureCompletedNs: Long = 0L
            var offset: Int = 0

            fun prepare(bytes: Int, capturedAtNs: Long) {
                size = bytes
                captureCompletedNs = capturedAtNs
                offset = 0
            }

            fun recycle() {
                size = 0
                captureCompletedNs = 0L
                offset = 0
            }
        }

        private val boundedPacketCapacity = packetCapacity.coerceIn(
            MIN_PCM_STAGING_PACKETS,
            MAX_PCM_STAGING_PACKETS
        )
        private val availablePackets = ArrayDeque<Packet>(boundedPacketCapacity)
        private val packets = ArrayDeque<Packet>(boundedPacketCapacity)
        private val stagedWrite = StagedPcmWrite()
        private var queuedByteCount = 0
        private var peakQueuedByteCount = 0
        private var codecInputUnavailableCount = 0L
        private var captureReadDeferralCount = 0L
        private var rejectedPacketCount = 0L

        init {
            require(packetByteCapacity > 0) { "packetByteCapacity must be positive" }
            repeat(boundedPacketCapacity) {
                availablePackets.addLast(Packet(ByteArray(packetByteCapacity)))
            }
        }

        val isEmpty: Boolean
            get() = packets.isEmpty()

        fun canAccept(bytes: Int): Boolean =
            bytes in 1..packetByteCapacity && availablePackets.isNotEmpty()

        fun offer(source: ByteBuffer, bytes: Int, captureCompletedNs: Long): Boolean {
            if (!canAccept(bytes) || source.remaining() < bytes) {
                rejectedPacketCount++
                return false
            }
            val packet = availablePackets.removeFirst()
            val originalPosition = source.position()
            source.get(packet.storage, 0, bytes)
            source.position(originalPosition)
            packet.prepare(bytes = bytes, capturedAtNs = captureCompletedNs)
            packets.addLast(packet)
            queuedByteCount += bytes
            peakQueuedByteCount = maxOf(peakQueuedByteCount, queuedByteCount)
            return true
        }

        /** Copies, but does not commit, the FIFO head into a codec input buffer. */
        fun writeHeadTo(destination: ByteBuffer, bytesPerPcmFrame: Int): StagedPcmWrite? {
            val packet = packets.peekFirst() ?: return null
            val frameBytes = bytesPerPcmFrame.coerceAtLeast(1)
            val availableBytes = minOf(packet.size - packet.offset, destination.remaining())
            val alignedBytes = availableBytes - (availableBytes % frameBytes)
            if (alignedBytes <= 0) return null
            destination.put(packet.storage, packet.offset, alignedBytes)
            stagedWrite.update(
                bytes = alignedBytes,
                captureCompletedNs = packet.captureCompletedNs,
                capturedPacketBytes = packet.size - packet.offset
            )
            return stagedWrite
        }

        /** Advances the FIFO only after MediaCodec accepted the corresponding bytes. */
        fun commitHead(bytes: Int) {
            val packet = packets.peekFirst() ?: return
            val committed = bytes.coerceIn(0, packet.size - packet.offset)
            packet.offset += committed
            queuedByteCount -= committed
            if (packet.offset == packet.size) {
                packets.removeFirst()
                packet.recycle()
                availablePackets.addLast(packet)
            }
        }

        fun markCodecInputUnavailable() {
            codecInputUnavailableCount++
        }

        fun markCaptureReadDeferred() {
            captureReadDeferralCount++
        }

        /**
         * Removes microphone samples captured before the first video frame that was actually
         * accepted as the clip's random-access boundary. Capture is allowed to warm up while the
         * encoder proves that boundary, but setup-window sound must never be submitted to AAC.
         *
         * [Packet.captureCompletedNs] is the monotonic time immediately after AudioRecord returned
         * the packet. PCM frame count therefore gives a bounded estimate of the packet's start. A
         * packet crossing the boundary is trimmed at an interleaved-frame boundary rather than
         * discarding a complete 20-40 ms capture block.
         */
        fun discardBeforeCaptureBoundary(
            captureBoundaryNs: Long,
            sampleRate: Int,
            channelCount: Int,
            bytesPerSample: Int
        ): PcmBoundaryTrimResult {
            require(captureBoundaryNs > 0L) { "captureBoundaryNs must be positive" }
            val bytesPerFrame = saturatingMultiply(
                channelCount.coerceAtLeast(1).toLong(),
                bytesPerSample.coerceAtLeast(1).toLong()
            ).coerceAtLeast(1L)
            var discardedBytes = 0L

            while (packets.isNotEmpty()) {
                val packet = requireNotNull(packets.peekFirst())
                val remainingBytes = (packet.size - packet.offset).coerceAtLeast(0)
                val remainingFrames = remainingBytes.toLong() / bytesPerFrame
                val remainingDurationNs = durationNsForFrames(remainingFrames, sampleRate)
                val remainingStartNs =
                    (packet.captureCompletedNs - remainingDurationNs).coerceAtLeast(0L)

                if (packet.captureCompletedNs <= captureBoundaryNs || remainingFrames <= 0L) {
                    packets.removeFirst()
                    queuedByteCount -= remainingBytes
                    discardedBytes = saturatingAdd(discardedBytes, remainingBytes.toLong())
                    packet.recycle()
                    availablePackets.addLast(packet)
                    continue
                }

                if (remainingStartNs < captureBoundaryNs) {
                    val framesToDiscard = framesForDurationNsCeil(
                        durationNs = captureBoundaryNs - remainingStartNs,
                        sampleRate = sampleRate
                    ).coerceAtMost(remainingFrames)
                    val bytesToDiscard = (framesToDiscard * bytesPerFrame)
                        .coerceAtMost(remainingBytes.toLong())
                        .toInt()
                    packet.offset += bytesToDiscard
                    queuedByteCount -= bytesToDiscard
                    discardedBytes = saturatingAdd(discardedBytes, bytesToDiscard.toLong())
                    if (packet.offset >= packet.size) {
                        packets.removeFirst()
                        packet.recycle()
                        availablePackets.addLast(packet)
                        continue
                    }
                }
                break
            }

            return PcmBoundaryTrimResult(
                discardedBytes = discardedBytes,
                retainedBytes = queuedByteCount,
                retainedPackets = packets.size
            )
        }

        fun clear() {
            while (packets.isNotEmpty()) {
                packets.removeFirst().also { packet ->
                    packet.recycle()
                    availablePackets.addLast(packet)
                }
            }
            queuedByteCount = 0
        }

        fun snapshot(): PcmStagingSnapshot = PcmStagingSnapshot(
            queuedPackets = packets.size,
            queuedBytes = queuedByteCount,
            peakQueuedBytes = peakQueuedByteCount,
            capacityBytes = boundedPacketCapacity * packetByteCapacity,
            codecInputUnavailableCount = codecInputUnavailableCount,
            captureReadDeferralCount = captureReadDeferralCount,
            rejectedPacketCount = rejectedPacketCount
        )
    }

    fun recommendedPcmStagingPacketCount(
        packetBytes: Int,
        sampleRate: Int,
        channelCount: Int,
        bytesPerSample: Int,
        targetDurationUs: Long = PCM_STAGING_TARGET_DURATION_US
    ): Int {
        val safePacketBytes = packetBytes.coerceAtLeast(1).toLong()
        val bytesPerSecond = saturatingMultiply(
            saturatingMultiply(
                sampleRate.coerceAtLeast(1).toLong(),
                channelCount.coerceAtLeast(1).toLong()
            ),
            bytesPerSample.coerceAtLeast(1).toLong()
        )
        val targetNumerator = saturatingMultiply(
            bytesPerSecond,
            targetDurationUs.coerceAtLeast(1L)
        )
        val targetBytes = saturatingAdd(targetNumerator, MICROSECONDS_PER_SECOND - 1L) /
            MICROSECONDS_PER_SECOND
        val packets = saturatingAdd(targetBytes, safePacketBytes - 1L) / safePacketBytes
        return packets
            .coerceIn(MIN_PCM_STAGING_PACKETS.toLong(), MAX_PCM_STAGING_PACKETS.toLong())
            .toInt()
    }

    /**
     * Builds encoder PTS from captured PCM frame count. The first buffer is
     * anchored to the shared recording clock; later buffers advance only when
     * their PCM was successfully queued.
     */
    internal class Timeline(
        private val recordingStartNs: Long,
        private val sampleRate: Int,
        private val channelCount: Int,
        private val bytesPerSample: Int,
        initialSubmittedFrames: Long = 0L,
        private val anchorFirstPcmToTimelineStart: Boolean = false
    ) {
        private val bytesPerFrame = saturatingMultiply(
            channelCount.coerceAtLeast(1).toLong(),
            bytesPerSample.coerceAtLeast(1).toLong()
        ).coerceAtLeast(1L)
        private val minimumStartPtsUs =
            durationUsForFrames(initialSubmittedFrames.coerceAtLeast(0L), sampleRate)
        private var firstPcmPtsUs: Long? = null
        private var queuedPcmFrames = 0L

        fun presentationTimeUs(readCompletedNs: Long, bytesRead: Int): Long {
            val pcmFrames = frameCount(bytesRead)
            val startPtsUs = firstPcmPtsUs ?: run {
                if (anchorFirstPcmToTimelineStart) {
                    // The synchronized path has already trimmed its PCM FIFO against the accepted
                    // video sensor boundary. Timestamping that retained packet from a later blocking
                    // AudioRecord completion would create a hole at the head of the MOV timeline.
                    minimumStartPtsUs
                } else {
                    val readCompletedUs =
                        (readCompletedNs - recordingStartNs).coerceAtLeast(0L) / 1_000L
                    val capturedDurationUs = durationUsForFrames(pcmFrames, sampleRate)
                    maxOf(
                        minimumStartPtsUs,
                        (readCompletedUs - capturedDurationUs).coerceAtLeast(0L)
                    )
                }.also { firstPcmPtsUs = it }
            }
            return saturatingAdd(startPtsUs, durationUsForFrames(queuedPcmFrames, sampleRate))
        }

        fun commitQueuedBytes(bytesRead: Int) {
            queuedPcmFrames = saturatingAdd(queuedPcmFrames, frameCount(bytesRead))
        }

        fun endPresentationTimeUs(): Long {
            val startPtsUs = firstPcmPtsUs ?: minimumStartPtsUs
            return saturatingAdd(startPtsUs, durationUsForFrames(queuedPcmFrames, sampleRate))
        }

        private fun frameCount(bytesRead: Int): Long =
            bytesRead.coerceAtLeast(0).toLong() / bytesPerFrame
    }

    fun shouldContinueEosDrain(eosSeen: Boolean, elapsedUs: Long): Boolean =
        !eosSeen && elapsedUs.coerceAtLeast(0L) < EOS_DRAIN_TIMEOUT_US

    /**
     * Keeps physical microphone capture alive through the encoder's final video access unit.
     *
     * Surface encoders can emit several already-accepted frames after input EOS. Stopping
     * [android.media.AudioRecord] before that drain completes shortens the committed audio track
     * even though neither producer dropped data. This policy waits for the immutable video horizon,
     * then requires real AAC coverage to reach that horizon within the strict publication tolerance.
     * It never fabricates silence, stretches timestamps, or relaxes the integrity threshold.
     */
    fun terminalAudioCoverage(
        latestVideoPtsUs: Long,
        latestAudioPtsUs: Long,
        videoFps: Int,
        audioSampleRate: Int,
        maximumDurationDriftUs: Long,
        videoDrainComplete: Boolean
    ): TerminalAudioCoverage {
        if (!videoDrainComplete) {
            return TerminalAudioCoverage(
                decision = TerminalAudioCoverageDecision.WAIT_FOR_VIDEO_EOS,
                videoEndPtsUs = -1L,
                audioEndPtsUs = -1L,
                minimumRequiredAudioEndPtsUs = -1L
            )
        }

        if (latestVideoPtsUs < 0L || videoFps <= 0 || audioSampleRate <= 0) {
            return TerminalAudioCoverage(
                decision = TerminalAudioCoverageDecision.CAPTURE_MORE_AUDIO,
                videoEndPtsUs = -1L,
                audioEndPtsUs = -1L,
                minimumRequiredAudioEndPtsUs = -1L
            )
        }

        val videoEndPtsUs = saturatingAdd(
            latestVideoPtsUs,
            MICROSECONDS_PER_SECOND / videoFps.toLong()
        )
        val audioEndPtsUs = if (latestAudioPtsUs >= 0L) {
            saturatingAdd(
                latestAudioPtsUs,
                durationUsForFrames(AAC_LC_FRAME_SAMPLES, audioSampleRate)
            )
        } else {
            -1L
        }
        val minimumRequiredAudioEndPtsUs =
            (videoEndPtsUs - maximumDurationDriftUs.coerceAtLeast(0L)).coerceAtLeast(0L)
        val covered = audioEndPtsUs >= minimumRequiredAudioEndPtsUs
        return TerminalAudioCoverage(
            decision = if (covered) {
                TerminalAudioCoverageDecision.COVERED
            } else {
                TerminalAudioCoverageDecision.CAPTURE_MORE_AUDIO
            },
            videoEndPtsUs = videoEndPtsUs,
            audioEndPtsUs = audioEndPtsUs,
            minimumRequiredAudioEndPtsUs = minimumRequiredAudioEndPtsUs
        )
    }

    /**
     * Serializes AAC publication behind the video timeline without changing either stream's PTS.
     * While video is live, a temporarily leading AAC access unit waits for the next video frame.
     * Once video EOS is proven, only access units extending beyond the allowed A/V drift are
     * discarded; audio that overlaps the recorded image timeline is always retained.
     */
    fun audioMuxHorizonDecision(
        audioSamplePtsUs: Long,
        latestVideoPtsUs: Long,
        videoFps: Int,
        audioSampleRate: Int,
        maximumDurationDriftUs: Long,
        recordingActive: Boolean,
        videoDrainComplete: Boolean
    ): AudioMuxHorizonDecision {
        // The single silent AAC primer establishes track format and timestamp zero before the first
        // camera frame is admitted. It must not wait for video because video ingress deliberately
        // waits for microphone readiness; every non-zero (real capture) access unit remains gated.
        if (latestVideoPtsUs < 0L && audioSamplePtsUs <= 0L && recordingActive) {
            return AudioMuxHorizonDecision.ADMIT
        }
        if (latestVideoPtsUs >= 0L && videoFps > 0 && audioSampleRate > 0) {
            val videoEndPtsUs = saturatingAdd(
                latestVideoPtsUs,
                MICROSECONDS_PER_SECOND / videoFps.toLong()
            )
            val audioEndPtsUs = saturatingAdd(
                audioSamplePtsUs.coerceAtLeast(0L),
                durationUsForFrames(AAC_LC_FRAME_SAMPLES, audioSampleRate)
            )
            if (audioEndPtsUs <= saturatingAdd(
                    videoEndPtsUs,
                    maximumDurationDriftUs.coerceAtLeast(0L)
                )
            ) {
                return AudioMuxHorizonDecision.ADMIT
            }
        }
        return if (recordingActive || !videoDrainComplete) {
            AudioMuxHorizonDecision.WAIT_FOR_VIDEO
        } else {
            AudioMuxHorizonDecision.DISCARD_TERMINAL_TAIL
        }
    }

    fun monotonicAacPresentationTimeUs(
        candidatePtsUs: Long,
        previousPtsUs: Long,
        sampleRate: Int
    ): Long {
        val safeCandidate = candidatePtsUs.coerceAtLeast(0L)
        if (previousPtsUs < 0L) return safeCandidate
        val minimumStepUs = durationUsForFrames(AAC_LC_FRAME_SAMPLES, sampleRate)
            .coerceAtLeast(1L)
        return maxOf(safeCandidate, saturatingAdd(previousPtsUs, minimumStepUs))
    }

    fun durationUsForFrames(frameCount: Long, sampleRate: Int): Long {
        val safeRate = sampleRate.coerceAtLeast(1).toLong()
        val safeFrames = frameCount.coerceAtLeast(0L)
        val wholeSeconds = safeFrames / safeRate
        if (wholeSeconds > Long.MAX_VALUE / MICROSECONDS_PER_SECOND) return Long.MAX_VALUE
        val wholeUs = wholeSeconds * MICROSECONDS_PER_SECOND
        val remainderUs = (safeFrames % safeRate) * MICROSECONDS_PER_SECOND / safeRate
        return saturatingAdd(wholeUs, remainderUs)
    }

    private fun durationNsForFrames(frameCount: Long, sampleRate: Int): Long {
        val safeRate = sampleRate.coerceAtLeast(1).toLong()
        val safeFrames = frameCount.coerceAtLeast(0L)
        val wholeSeconds = safeFrames / safeRate
        if (wholeSeconds > Long.MAX_VALUE / 1_000_000_000L) return Long.MAX_VALUE
        val wholeNs = wholeSeconds * 1_000_000_000L
        val remainderNs = (safeFrames % safeRate) * 1_000_000_000L / safeRate
        return saturatingAdd(wholeNs, remainderNs)
    }

    private fun framesForDurationNsCeil(durationNs: Long, sampleRate: Int): Long {
        val safeDurationNs = durationNs.coerceAtLeast(0L)
        val safeRate = sampleRate.coerceAtLeast(1).toLong()
        val wholeSeconds = safeDurationNs / 1_000_000_000L
        if (wholeSeconds > Long.MAX_VALUE / safeRate) return Long.MAX_VALUE
        val wholeFrames = wholeSeconds * safeRate
        val remainderNs = safeDurationNs % 1_000_000_000L
        val remainderNumerator = saturatingMultiply(remainderNs, safeRate)
        val remainderFrames = saturatingAdd(remainderNumerator, 999_999_999L) / 1_000_000_000L
        return saturatingAdd(wholeFrames, remainderFrames)
    }

    private fun saturatingMultiply(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (left < 0L || right < 0L) return Long.MAX_VALUE
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    fun threadSignature(): String =
        "audio-capture-thread=THREAD_PRIORITY_URGENT_AUDIO|" +
            "clock=shared-recording-origin|pts=sample-count-timeline|" +
            "pcm-staging=bounded-timestamp-preserving|" +
            "eos-drain-timeout-us=$EOS_DRAIN_TIMEOUT_US"
}
