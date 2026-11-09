package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.CameraImuClockAlignment
import com.neoproc.neoproccamx.pipeline.SensorCentricFramePipeline
import com.neoproc.neoproccamx.recording.GpuRecordingState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceLoadPolicyTest {
    @Test
    fun `all clip ownership phases including finalization retain recording protection`() {
        listOf(
            GpuRecordingState.Starting,
            GpuRecordingState.Recording,
            GpuRecordingState.Paused,
            GpuRecordingState.Stopping,
            GpuRecordingState.Finalizing
        ).forEach { state ->
            assertTrue("Lost recording protection in $state", RuntimePerformanceLoadPolicy.isRecordingCritical(state))
        }
        assertFalse(RuntimePerformanceLoadPolicy.isRecordingCritical(GpuRecordingState.Idle))
        assertFalse(RuntimePerformanceLoadPolicy.isRecordingCritical(GpuRecordingState.Ready))
        assertFalse(RuntimePerformanceLoadPolicy.isRecordingCritical(GpuRecordingState.Error("test failure")))
    }

    @Test
    fun `view model uses shared state classification before updating pipeline runtime hints`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
            .substringAfter("private fun applyRuntimePerformanceHints(previewFps: Float)")
            .substringBefore("private fun applyRecordingCriticalRuntimeHints(")
        val classification = source.indexOf("RuntimePerformanceLoadPolicy.isRecordingCritical(gpuRecordState.value)")
        val resolve = source.indexOf("RuntimePerformanceLoadPolicy.resolve(")
        val pipelineHint = source.indexOf("pipeline.setRuntimePerformanceHint(")
        assertTrue(classification >= 0 && classification < resolve)
        assertTrue(resolve < pipelineHint)
        assertTrue(source.contains("gpuRecordingCritical = gpuRecordingCritical"))
        assertTrue(source.contains("recording = load.recordingCritical"))
    }

    @Test
    fun `finalizing preview rebuild retains actual source frames and late encoder tail`() {
        val pipeline = SensorCentricFramePipeline(targetFps = 25.0, monotonicClockNs = { 2_000_000_000L })
        try {
            pipeline.beginSession(1L, CameraImuClockAlignment.UNAVAILABLE, null)
            pipeline.beginRecordingEvidenceWindow()
            pipeline.onImageAvailable(1_000_000_000L)
            assertTrue(pipeline.onRecordedFrame(1_000_000_000L))
            pipeline.onImageAvailable(1_040_000_000L)
            pipeline.sealRecordingLossAccounting()
            val load = RuntimePerformanceLoadPolicy.resolve(
                recordingGraphPrearmed = false,
                recordingStarting = false,
                gpuRecordingCritical = RuntimePerformanceLoadPolicy.isRecordingCritical(GpuRecordingState.Finalizing),
                cameraRecording = false,
                highLoadPreview = false
            )
            pipeline.beginSession(2L, CameraImuClockAlignment.UNAVAILABLE, null, load.recordingCritical)
            assertTrue(pipeline.onRecordedFrame(1_040_000_000L))
            pipeline.beginSession(3L, CameraImuClockAlignment.UNAVAILABLE, null, load.recordingCritical)
            val finalEvidence = pipeline.diagnostics()
            assertEquals(2L, finalEvidence.recordedFrames)
            assertEquals(25.0, finalEvidence.recordingSourceFps, 0.0001)
            assertEquals(0L, finalEvidence.droppedRecordingFrames)
            assertEquals(1_000_000_000L, finalEvidence.recordingFirstSensorTimestampNs)
            assertEquals(1_040_000_000L, finalEvidence.recordingLastSensorTimestampNs)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `finalizing preserves genuine failures and next recording starts its own evidence window`() {
        val pipeline = SensorCentricFramePipeline(targetFps = 25.0, monotonicClockNs = { 2_000_000_000L })
        try {
            pipeline.beginSession(1L, CameraImuClockAlignment.UNAVAILABLE, null)
            pipeline.beginRecordingEvidenceWindow()
            val first = pipeline.onImageAvailable(1_000_000_000L)
            assertTrue(pipeline.onRecordedFrame(1_000_000_000L))
            pipeline.onImageAvailable(1_080_000_000L)
            assertTrue(pipeline.onRecordedFrame(1_080_000_000L))
            // Exhaust the existing jitter-recovery lookahead; this gap is not a delayed callback
            // repaid by a short next interval. The unchanged loss estimator must confirm it.
            for (timestamp in listOf(1_120_000_000L, 1_160_000_000L)) {
                pipeline.onImageAvailable(timestamp)
                assertTrue(pipeline.onRecordedFrame(timestamp))
            }
            assertTrue(pipeline.markRecordingDrop(first, "real-live-input-rejection"))
            pipeline.sealRecordingLossAccounting()
            pipeline.beginSession(
                2L, CameraImuClockAlignment.UNAVAILABLE, null,
                RuntimePerformanceLoadPolicy.isRecordingCritical(GpuRecordingState.Finalizing)
            )
            val failed = pipeline.diagnostics()
            assertEquals(4L, failed.recordedFrames)
            assertEquals(1L, failed.unrecoveredRecordingTimestampGapFrames)
            assertEquals(1L, failed.explicitDroppedRecordingFrames)
            assertEquals("real-live-input-rejection", failed.firstExplicitRecordingDropReason)
            assertFalse(pipeline.markRecordingDrop(null, "late-preview-is-not-clip-loss"))

            pipeline.beginRecordingEvidenceWindow()
            assertEquals(0L, pipeline.diagnostics().recordedFrames)
            assertEquals(0L, pipeline.diagnostics().droppedRecordingFrames)
            pipeline.onImageAvailable(2_000_000_000L)
            assertTrue(pipeline.onRecordedFrame(2_000_000_000L))
            assertEquals(1L, pipeline.diagnostics().recordedFrames)
            assertEquals(2_000_000_000L, pipeline.diagnostics().recordingFirstSensorTimestampNs)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `high-load idle preview raises workload classification without impersonating recording`() {
        val result = RuntimePerformanceLoadPolicy.resolve(
            recordingGraphPrearmed = false,
            recordingStarting = false,
            gpuRecordingCritical = false,
            cameraRecording = false,
            highLoadPreview = true
        )

        assertTrue(result.highLoadOrRecording)
        assertFalse(result.recordingCritical)
    }

    @Test
    fun `prearmed surface is workload pressure but not an active recording`() {
        val result = RuntimePerformanceLoadPolicy.resolve(
            recordingGraphPrearmed = true,
            recordingStarting = false,
            gpuRecordingCritical = false,
            cameraRecording = false,
            highLoadPreview = false
        )

        assertTrue(result.highLoadOrRecording)
        assertFalse(result.recordingCritical)
    }

    @Test
    fun `every active recording phase is recording critical`() {
        listOf(
            RuntimePerformanceLoadPolicy.resolve(false, true, false, false, false),
            RuntimePerformanceLoadPolicy.resolve(false, false, true, false, false),
            RuntimePerformanceLoadPolicy.resolve(false, false, false, true, false)
        ).forEach { result ->
            assertTrue(result.recordingCritical)
            assertTrue(result.highLoadOrRecording)
        }
    }

    @Test
    fun `ordinary idle preview is neither high load nor recording critical`() {
        val result = RuntimePerformanceLoadPolicy.resolve(false, false, false, false, false)

        assertFalse(result.recordingCritical)
        assertFalse(result.highLoadOrRecording)
    }
}
