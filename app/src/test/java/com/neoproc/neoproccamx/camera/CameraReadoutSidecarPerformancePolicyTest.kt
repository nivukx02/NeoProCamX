package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraReadoutSidecarPerformancePolicyTest {

    @Test
    fun `encoded recording disables yuv sidecar to preserve isp recording quality`() {
        val mode = CameraReadoutSidecarPerformancePolicy.resolvePreferredMode(
            targetFps = 60,
            rawVideoCaptureEnabled = false,
            recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            supportedYuvSizes = listOf(
                CameraStreamSize(4096, 3072),
                CameraStreamSize(3840, 2160)
            )
        )

        assertEquals(CameraReadoutSidecarMode.DISABLED, mode)
    }

    @Test
    fun `encoded recording without explicit readout also disables yuv sidecar`() {
        val mode = CameraReadoutSidecarPerformancePolicy.resolvePreferredMode(
            targetFps = 30,
            rawVideoCaptureEnabled = false,
            recordingReadoutOverrideSize = null,
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            supportedYuvSizes = listOf(
                CameraStreamSize(4096, 3072),
                CameraStreamSize(3840, 2160)
            )
        )

        assertEquals(CameraReadoutSidecarMode.DISABLED, mode)
    }

    @Test
    fun `raw capture keeps maximum sensor sidecar`() {
        val mode = CameraReadoutSidecarPerformancePolicy.resolvePreferredMode(
            targetFps = 60,
            rawVideoCaptureEnabled = true,
            recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            supportedYuvSizes = listOf(CameraStreamSize(3840, 2160))
        )

        assertEquals(CameraReadoutSidecarMode.MAXIMUM, mode)
    }
}
