package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioStartupPolicyTest {

    @Test
    fun `audio startup waits briefly before allowing video only start`() {
        val startNs = 1_000_000_000L
        val deadlineNs = RecordingAudioStartupPolicy.audioTrackDeadlineNs(
            recordingStartNs = startNs,
            audioEnabled = true
        )

        assertFalse(
            RecordingAudioStartupPolicy.allowVideoOnlyMuxerStart(
                audioEnabled = true,
                audioTrackIndex = -1,
                isRecording = true,
                nowNs = startNs + 100_000_000L,
                audioTrackDeadlineNs = deadlineNs
            )
        )
    }

    @Test
    fun `audio startup allows video only start once grace period expires`() {
        val startNs = 1_000_000_000L
        val deadlineNs = RecordingAudioStartupPolicy.audioTrackDeadlineNs(
            recordingStartNs = startNs,
            audioEnabled = true
        )

        assertTrue(
            RecordingAudioStartupPolicy.allowVideoOnlyMuxerStart(
                audioEnabled = true,
                audioTrackIndex = -1,
                isRecording = true,
                nowNs = deadlineNs,
                audioTrackDeadlineNs = deadlineNs
            )
        )
    }

    @Test
    fun `audio startup primes one AAC frame worth of pcm`() {
        assertEquals(4096, RecordingAudioStartupPolicy.primingPcmBytes())
    }
}
