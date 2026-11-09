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
    fun `thermal recording isolation removes optional yuv target without touching encoded source`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = false,
            previewAnalysisRequested = true,
            recordingThermalAnalysisIsolationEnabled = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1280, 960),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertFalse(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
    }

    @Test
    fun `thermal recording isolation never removes an uncompressed writer target`() {
        val before = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = true,
            previewAnalysisRequested = true,
            recordingThermalAnalysisIsolationEnabled = false,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(4096, 3072),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )
        val after = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = true,
            previewAnalysisRequested = true,
            recordingThermalAnalysisIsolationEnabled = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(4096, 3072),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertFalse(after.includeRawSensor)
        assertTrue(after.includeYuvReadout)
        assertFalse(CameraRepeatingRequestRetargetPolicy.requiresRepublish(before, after))
    }

    @Test
    fun `thermal recording isolation never republishes a mandatory raw target`() {
        val before = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            previewAnalysisRequested = true,
            recordingThermalAnalysisIsolationEnabled = false,
            rawVideoCaptureEnabled = true,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1280, 960),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )
        val after = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            previewAnalysisRequested = true,
            recordingThermalAnalysisIsolationEnabled = true,
            rawVideoCaptureEnabled = true,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1280, 960),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertTrue(after.includeRawSensor)
        assertFalse(after.includeYuvReadout)
        assertFalse(CameraRepeatingRequestRetargetPolicy.requiresRepublish(before, after))
    }

    @Test
    fun `thermal recording isolation republishes when encoded optional analysis target is removed`() {
        val before = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = false,
            previewAnalysisRequested = true,
            recordingThermalAnalysisIsolationEnabled = false,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1280, 960),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )
        val after = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = false,
            previewAnalysisRequested = true,
            recordingThermalAnalysisIsolationEnabled = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1280, 960),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertTrue(before.includeYuvReadout)
        assertFalse(after.includeYuvReadout)
        assertTrue(CameraRepeatingRequestRetargetPolicy.requiresRepublish(before, after))
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

        // No YUV analysis stream in session -> preview-only graph, no analysis target.
        assertFalse(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
    }

    @Test
    fun `preview targets the yuv reader when internal scene analysis is in session`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = false,
            previewAnalysisRequested = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1280, 720),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        // A live YUV analysis output during preview must be targeted so the CPU pipeline
        // Internal NDR exposure/highlight analysis receives frames. Never pulls in RAW.
        assertFalse(plan.includeRawSensor)
        assertTrue(plan.includeYuvReadout)
    }

    @Test
    fun `raw preview keeps configured p010 standby out of repeating request`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = false,
            rawVideoCaptureEnabled = false,
            rawPreviewActive = true,
            hasRawReader = true,
            hasYuvReader = true,
            yuvReaderConfiguredAsStandby = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(4096, 3072),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertTrue(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
    }

    @Test
    fun `ndr analysis activates the preconfigured p010 standby target`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = false,
            previewAnalysisRequested = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = true,
            yuvReaderConfiguredAsStandby = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(4096, 3072),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertFalse(plan.includeRawSensor)
        assertTrue(plan.includeYuvReadout)
    }

    @Test
    fun `npv10 prearm keeps the preconfigured p010 target idle until recording`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = false,
            uncompressedReadoutRecording = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = true,
            yuvReaderConfiguredAsStandby = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(4096, 3072),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertFalse(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
    }

    @Test
    fun `niv99 analysis activates an npv10 prearmed p010 target during video preview`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = false,
            uncompressedReadoutRecording = true,
            previewAnalysisRequested = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = true,
            yuvReaderConfiguredAsStandby = false,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertFalse(plan.includeRawSensor)
        assertTrue(plan.includeYuvReadout)
    }

    @Test
    fun `sensor linear ndr preview keeps prearmed p010 idle while raw remains live`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = false,
            uncompressedReadoutRecording = true,
            rawVideoCaptureEnabled = false,
            sensorLinearNdrCaptureEnabled = true,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = Size(4096, 3072),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertTrue(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
    }

    @Test
    fun `sensor linear ndr rejects stale npv10 target while raw remains live`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = true,
            rawVideoCaptureEnabled = false,
            sensorLinearNdrCaptureEnabled = true,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = Size(4096, 2304),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertTrue(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
    }

    @Test
    fun `sensor linear ndr without npv10 remains raw only`() {
        val plan = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = false,
            uncompressedReadoutRecording = false,
            rawVideoCaptureEnabled = false,
            sensorLinearNdrCaptureEnabled = true,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = Size(4096, 2304),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
        )

        assertTrue(plan.includeRawSensor)
        assertFalse(plan.includeYuvReadout)
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
