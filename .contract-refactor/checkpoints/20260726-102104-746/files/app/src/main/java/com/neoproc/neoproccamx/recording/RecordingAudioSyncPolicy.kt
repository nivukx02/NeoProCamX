package com.neoproc.neoproccamx.recording

import android.os.Process

internal object RecordingAudioSyncPolicy {
    const val AUDIO_THREAD_PRIORITY = Process.THREAD_PRIORITY_URGENT_AUDIO
    const val EOS_DRAIN_TIMEOUT_US = 750_000L

    private const val MICROSECONDS_PER_SECOND = 1_000_000L
    private const val AAC_LC_FRAME_SAMPLES = 1_024L

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
        initialSubmittedFrames: Long = 0L
    ) {
        private val bytesPerFrame =
            channelCount.coerceAtLeast(1) * bytesPerSample.coerceAtLeast(1)
        private val minimumStartPtsUs =
            durationUsForFrames(initialSubmittedFrames.coerceAtLeast(0L), sampleRate)
        private var firstPcmPtsUs: Long? = null
        private var queuedPcmFrames = 0L

        fun presentationTimeUs(readCompletedNs: Long, bytesRead: Int): Long {
            val pcmFrames = frameCount(bytesRead)
            val startPtsUs = firstPcmPtsUs ?: run {
                val readCompletedUs =
                    (readCompletedNs - recordingStartNs).coerceAtLeast(0L) / 1_000L
                val capturedDurationUs = durationUsForFrames(pcmFrames, sampleRate)
                maxOf(
                    minimumStartPtsUs,
                    (readCompletedUs - capturedDurationUs).coerceAtLeast(0L)
                ).also { firstPcmPtsUs = it }
            }
            return startPtsUs + durationUsForFrames(queuedPcmFrames, sampleRate)
        }

        fun commitQueuedBytes(bytesRead: Int) {
            queuedPcmFrames += frameCount(bytesRead)
        }

        fun endPresentationTimeUs(): Long {
            val startPtsUs = firstPcmPtsUs ?: minimumStartPtsUs
            return startPtsUs + durationUsForFrames(queuedPcmFrames, sampleRate)
        }

        private fun frameCount(bytesRead: Int): Long =
            bytesRead.coerceAtLeast(0).toLong() / bytesPerFrame
    }

    fun shouldContinueEosDrain(eosSeen: Boolean, elapsedUs: Long): Boolean =
        !eosSeen && elapsedUs.coerceAtLeast(0L) < EOS_DRAIN_TIMEOUT_US

    fun monotonicAacPresentationTimeUs(
        candidatePtsUs: Long,
        previousPtsUs: Long,
        sampleRate: Int
    ): Long {
        val safeCandidate = candidatePtsUs.coerceAtLeast(0L)
        if (previousPtsUs < 0L) return safeCandidate
        val minimumStepUs = durationUsForFrames(AAC_LC_FRAME_SAMPLES, sampleRate)
            .coerceAtLeast(1L)
        return maxOf(safeCandidate, previousPtsUs + minimumStepUs)
    }

    fun durationUsForFrames(frameCount: Long, sampleRate: Int): Long {
        val safeRate = sampleRate.coerceAtLeast(1).toLong()
        val safeFrames = frameCount.coerceAtLeast(0L)
        return safeFrames * MICROSECONDS_PER_SECOND / safeRate
    }

    fun threadSignature(): String =
        "audio-capture-thread=THREAD_PRIORITY_URGENT_AUDIO|" +
            "clock=shared-recording-origin|pts=sample-count-timeline|" +
            "eos-drain-timeout-us=$EOS_DRAIN_TIMEOUT_US"
}
