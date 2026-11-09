package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraReadoutSidecarFallbackPolicyTest {

    @Test
    fun `max sidecar downgrades to encoder matched yuv when available`() {
        val decision = CameraReadoutSidecarFallbackPolicy.resolve(
            currentMode = CameraReadoutSidecarMode.MAXIMUM,
            recordingActive = true,
            rawVideoCaptureEnabled = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            supportedYuvSizes = listOf(
                CameraStreamSize(4096, 3072),
                CameraStreamSize(3840, 2160)
            )
        )

        assertTrue(decision.shouldRetry)
        assertEquals(CameraReadoutSidecarMode.ENCODER_MATCHED, decision.nextMode)
        assertTrue(decision.warning!!.contains("encoder-matched YUV sidecar"))
    }

    @Test
    fun `encoder matched sidecar disables optional sidecar after another rejection`() {
        val decision = CameraReadoutSidecarFallbackPolicy.resolve(
            currentMode = CameraReadoutSidecarMode.ENCODER_MATCHED,
            recordingActive = true,
            rawVideoCaptureEnabled = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            supportedYuvSizes = listOf(CameraStreamSize(3840, 2160))
        )

        assertTrue(decision.shouldRetry)
        assertEquals(CameraReadoutSidecarMode.DISABLED, decision.nextMode)
        assertTrue(decision.warning!!.contains("encoder-only"))
    }

    @Test
    fun `raw recording does not remove raw sensor sidecar`() {
        val decision = CameraReadoutSidecarFallbackPolicy.resolve(
            currentMode = CameraReadoutSidecarMode.MAXIMUM,
            recordingActive = true,
            rawVideoCaptureEnabled = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            supportedYuvSizes = listOf(CameraStreamSize(3840, 2160))
        )

        assertFalse(decision.shouldRetry)
        assertEquals(null, decision.nextMode)
    }

    @Test
    fun `max sidecar disables directly when encoder matched yuv is unavailable`() {
        val decision = CameraReadoutSidecarFallbackPolicy.resolve(
            currentMode = CameraReadoutSidecarMode.MAXIMUM,
            recordingActive = true,
            rawVideoCaptureEnabled = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            supportedYuvSizes = listOf(CameraStreamSize(4096, 3072))
        )

        assertTrue(decision.shouldRetry)
        assertEquals(CameraReadoutSidecarMode.DISABLED, decision.nextMode)
    }
}
