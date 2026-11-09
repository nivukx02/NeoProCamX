package com.neoproc.neoproccamx.camera

import android.util.Size
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRepeatingRequestTargetPolicyTest {

    @Test
    fun `raw recording targets raw reader for sensor frames`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            rawVideoCaptureEnabled = true,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertTrue(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
    }

    @Test
    fun `encoded ndr recording targets admitted yuv analysis reader`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = false,
            previewAnalysisRequested = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertFalse(plan.includeRawSensor)
        assertTrue(plan.includeYuvReadout)
    }

    @Test
    fun `uncompressed recording targets yuv readout so npv10 writer receives frames`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = Size(3840, 2160),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertFalse(plan.includeRawSensor)
        assertTrue(plan.includeYuvReadout)
    }

    @Test
    fun `uncompressed bytebuffer recording targets yuv readout even when sidecar mode is disabled`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.DISABLED
        )

        assertFalse(plan.includeRawSensor)
        assertTrue(plan.includeYuvReadout)
    }

    @Test
    fun `preview without an analysis stream does not target any readout sidecar`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = false,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = false,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        // No YUV analysis stream in session (no HUD monitor enabled) -> preview-only graph, no target.
        assertFalse(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
    }

    @Test
    fun `preview targets the yuv analysis reader when the HUD monitor feed is in session`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = false,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1280, 720),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        // A live YUV analysis output during preview must be targeted so the CPU pipeline
        // (histogram / waveform / focus-peaking / zebra) receives frames. Never pulls in RAW.
        assertFalse(plan.includeRawSensor)
        assertTrue(plan.includeYuvReadout)
    }

    @Test
    fun `encoded recording does not target yuv after session fallback removes reader`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = false,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.DISABLED
        )

        assertFalse(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
    }
}
