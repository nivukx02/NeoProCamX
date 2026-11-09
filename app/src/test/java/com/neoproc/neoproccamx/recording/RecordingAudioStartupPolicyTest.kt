package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioStartupPolicyTest {

    @Test
    fun `requested audio keeps container closed until audio track exists`() {
        assertFalse(
            RecordingAudioStartupPolicy.requiredTracksReady(
                videoTrackIndex = 0,
                audioEnabled = true,
                audioTrackIndex = -1
            )
        )
    }

    @Test
    fun `requested audio admits container only after both tracks exist`() {
        assertTrue(
            RecordingAudioStartupPolicy.requiredTracksReady(
                videoTrackIndex = 0,
                audioEnabled = true,
                audioTrackIndex = 1
            )
        )
    }

    @Test
    fun `intentional video only recording needs only video track`() {
        assertTrue(
            RecordingAudioStartupPolicy.requiredTracksReady(
                videoTrackIndex = 0,
                audioEnabled = false,
                audioTrackIndex = -1
            )
        )
    }

    @Test
    fun `audio track never compensates for missing video track`() {
        assertFalse(
            RecordingAudioStartupPolicy.requiredTracksReady(
                videoTrackIndex = -1,
                audioEnabled = true,
                audioTrackIndex = 0
            )
        )
    }

    @Test
    fun `audio startup primes one AAC frame worth of pcm`() {
        assertEquals(4096, RecordingAudioStartupPolicy.primingPcmBytes())
    }
}
