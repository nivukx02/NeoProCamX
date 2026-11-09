package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRecordSurfaceRefreshPolicyTest {

    @Test
    fun `recording surface attach can be staged without immediate session rebuild`() {
        val decision = CameraRecordSurfaceRefreshPolicy.resolve(
            surfaceAttached = true,
            refreshSessionRequested = false
        )

        assertFalse(decision.refreshSessionImmediately)
        assertFalse(decision.clearRecordingEncoderTargets)
    }

    @Test
    fun `normal recording surface attach still refreshes when requested`() {
        val decision = CameraRecordSurfaceRefreshPolicy.resolve(
            surfaceAttached = true,
            refreshSessionRequested = true
        )

        assertTrue(decision.refreshSessionImmediately)
        assertFalse(decision.clearRecordingEncoderTargets)
    }

    @Test
    fun `clearing recording surface resets encoder targets without an immediate rebuild`() {
        val decision = CameraRecordSurfaceRefreshPolicy.resolve(
            surfaceAttached = false,
            refreshSessionRequested = true
        )

        assertFalse(decision.refreshSessionImmediately)
        assertTrue(decision.clearRecordingEncoderTargets)
    }
}
