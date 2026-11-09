package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeSwitchFirstFramePrimePolicyTest {
    @Test
    fun `idle processed to raw boundary primes the current preview`() {
        assertTrue(
            decide(previousRaw = false, nextRaw = true)
        )
    }

    @Test
    fun `idle raw to processed boundary primes the current preview`() {
        assertTrue(
            decide(previousRaw = true, nextRaw = false)
        )
    }

    @Test
    fun `same stream family never adds a one-shot request`() {
        assertFalse(decide(previousRaw = false, nextRaw = false))
        assertFalse(decide(previousRaw = true, nextRaw = true))
    }

    @Test
    fun `cold open recording and isolated still sessions are never primed`() {
        assertFalse(decide(hasPreviousSession = false))
        assertFalse(decide(recordingActive = true))
        assertFalse(decide(videoPreviewActive = false))
        assertFalse(decide(maximumResolutionStillCaptureActive = true))
    }

    @Test
    fun `unrelated session refresh is never classified as a mode boundary`() {
        assertFalse(decide(refreshReason = "preview-watchdog-recovery"))
    }

    private fun decide(
        refreshReason: String = "configuration-change",
        hasPreviousSession: Boolean = true,
        recordingActive: Boolean = false,
        videoPreviewActive: Boolean = true,
        maximumResolutionStillCaptureActive: Boolean = false,
        previousRaw: Boolean = false,
        nextRaw: Boolean = true
    ): Boolean = CameraModeSwitchFirstFramePrimePolicy.shouldPrime(
        refreshReason = refreshReason,
        hasPreviousSession = hasPreviousSession,
        recordingActive = recordingActive,
        videoPreviewActive = videoPreviewActive,
        maximumResolutionStillCaptureActive = maximumResolutionStillCaptureActive,
        previousRawSurfaceActive = previousRaw,
        nextRawSurfaceActive = nextRaw
    )
}
