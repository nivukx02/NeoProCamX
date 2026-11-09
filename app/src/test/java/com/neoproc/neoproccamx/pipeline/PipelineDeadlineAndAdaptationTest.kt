package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineDeadlineAndAdaptationTest {
    @Test
    fun `recording evidence survives stop-time camera session generation change`() {
        var nowNs = 1_000_000_000L
        val pipeline = SensorCentricFramePipeline(
            targetFps = 25.0,
            monotonicClockNs = { nowNs }
        )
        pipeline.beginSession(
            sessionGeneration = 1L,
            alignment = CameraImuClockAlignment.UNAVAILABLE,
            calibration = null
        )
        pipeline.beginRecordingEvidenceWindow()
        assertFalse(pipeline.onRecordedFrame(1_000_000_000L))
        pipeline.onImageAvailable(1_000_000_000L, nowNs)
        assertTrue(pipeline.onRecordedFrame(1_000_000_000L))
        pipeline.onImageAvailable(1_040_000_000L, nowNs + 40_000_000L)

        pipeline.beginSession(
            sessionGeneration = 2L,
            alignment = CameraImuClockAlignment.UNAVAILABLE,
            calibration = null,
            preserveRecordingEvidence = true
        )

        nowNs += 40_000_000L
        assertTrue(pipeline.onRecordedFrame(1_040_000_000L))
        val snapshot = pipeline.diagnostics()
        assertEquals(2L, snapshot.recordedFrames)
        assertEquals(1_000_000_000L, snapshot.recordingFirstSensorTimestampNs)
        assertEquals(1_040_000_000L, snapshot.recordingLastSensorTimestampNs)
        assertEquals(25.0, snapshot.recordingSourceFps, 0.0001)
    }

    @Test
    fun `post-tail stale recording frame cannot add loss while retained evidence accepts mux commit`() {
        var nowNs = 1_000_000_000L
        val pipeline = SensorCentricFramePipeline(
            targetFps = 25.0,
            monotonicClockNs = { nowNs }
        )
        try {
            pipeline.beginSession(
                sessionGeneration = 11L,
                alignment = CameraImuClockAlignment.UNAVAILABLE,
                calibration = null
            )
            pipeline.beginRecordingEvidenceWindow()
            val first = pipeline.onImageAvailable(900_000_000L, nowNs)
            assertTrue(pipeline.onRecordedFrame(900_000_000L))
            assertTrue(pipeline.markRecordingDrop(first, "live-encoder-rejection"))

            val acceptedTail = pipeline.onImageAvailable(940_000_000L, nowNs + 40_000_000L)
            pipeline.sealRecordingLossAccounting()
            pipeline.beginSession(
                sessionGeneration = 12L,
                alignment = CameraImuClockAlignment.UNAVAILABLE,
                calibration = null,
                preserveRecordingEvidence = true
            )

            assertTrue(pipeline.onRecordedFrame(940_000_000L))
            assertFalse(
                pipeline.markRecordingDrop(
                    acceptedTail,
                    "camera-session-changed-after-prepare"
                )
            )
            val snapshot = pipeline.diagnostics()
            assertEquals(2L, snapshot.recordedFrames)
            assertEquals(1L, snapshot.explicitDroppedRecordingFrames)
            assertEquals("live-encoder-rejection", snapshot.firstExplicitRecordingDropReason)
            assertEquals(25.0, snapshot.recordingSourceFps, 0.0001)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun captureGapPreviewSkipAndRecordingLossRemainIndependent() {
        var nowNs = 1_000_000_000L
        val monitor = PipelineDeadlineMonitor(targetFps = 30.0, monotonicClockNs = { nowNs })
        monitor.recordCapture(identity(frameNumber = 10L, sensorTimestampNs = 1_000_000_000L))
        monitor.recordCapture(identity(frameNumber = 12L, sensorTimestampNs = 1_066_666_666L))
        monitor.recordPreviewSkipped(stale = true)

        val beforeRecordingDrop = monitor.snapshot()
        assertEquals(1L, beforeRecordingDrop.droppedCaptureFrames)
        assertEquals(1L, beforeRecordingDrop.skippedPreviewFrames)
        assertEquals(1L, beforeRecordingDrop.stalePreviewFrames)
        assertEquals(0L, beforeRecordingDrop.droppedRecordingFrames)

        monitor.recordRecordingDrop()
        assertEquals(1L, monitor.snapshot().droppedRecordingFrames)
    }

    @Test
    fun frameNumberGapDoesNotInventLossWhenTimestampCadenceIsContinuous() {
        val monitor = PipelineDeadlineMonitor(targetFps = 25.0) { 2_000_000_000L }
        monitor.recordCapture(identity(frameNumber = 10L, sensorTimestampNs = 1_000_000_000L))
        monitor.recordCapture(identity(frameNumber = 12L, sensorTimestampNs = 1_040_000_000L))

        assertEquals(0L, monitor.snapshot().droppedCaptureFrames)
    }

    @Test
    fun compensatedRecordingTimestampJitterIsVisibleButIsNotReportedAsFrameLoss() {
        val monitor = PipelineDeadlineMonitor(targetFps = 25.0) { 2_000_000_000L }
        monitor.recordRecordingCompleted(identity(frameNumber = 10L, sensorTimestampNs = 1_000_000_000L))
        // One callback arrives 21 ms late, which the former per-interval rounding called a drop.
        monitor.recordRecordingCompleted(identity(frameNumber = 11L, sensorTimestampNs = 1_061_000_000L))
        // The next callback repays the scheduling phase error; all three source frames exist.
        monitor.recordRecordingCompleted(identity(frameNumber = 12L, sensorTimestampNs = 1_080_000_000L))
        monitor.recordRecordingCompleted(identity(frameNumber = 13L, sensorTimestampNs = 1_120_000_000L))

        val snapshot = monitor.snapshot()
        assertEquals(1L, snapshot.inferredRecordingTimestampGapFrames)
        assertEquals(1L, snapshot.recordingTimestampGapEvents)
        assertEquals(0L, snapshot.unrecoveredRecordingTimestampGapFrames)
        assertEquals(0L, snapshot.explicitDroppedRecordingFrames)
        assertEquals(0L, snapshot.droppedRecordingFrames)
        assertEquals(25.0, snapshot.recordingSourceFps, 0.0001)
    }

    @Test
    fun unrecoveredRecordingTimestampGapAndExplicitDropRemainIndependent() {
        val monitor = PipelineDeadlineMonitor(targetFps = 25.0) { 2_000_000_000L }
        monitor.recordRecordingCompleted(identity(frameNumber = 10L, sensorTimestampNs = 1_000_000_000L))
        monitor.recordRecordingCompleted(identity(frameNumber = 12L, sensorTimestampNs = 1_080_000_000L))
        monitor.recordRecordingCompleted(identity(frameNumber = 13L, sensorTimestampNs = 1_120_000_000L))
        monitor.recordRecordingCompleted(identity(frameNumber = 14L, sensorTimestampNs = 1_160_000_000L))
        monitor.recordRecordingDrop(reason = "protected-queue-full")

        val snapshot = monitor.snapshot()
        assertEquals(1L, snapshot.inferredRecordingTimestampGapFrames)
        assertEquals(1L, snapshot.unrecoveredRecordingTimestampGapFrames)
        assertEquals(1L, snapshot.explicitDroppedRecordingFrames)
        assertEquals(2L, snapshot.droppedRecordingFrames)
        assertEquals("protected-queue-full", snapshot.firstExplicitRecordingDropReason)
        assertEquals(1_000_000_000L, snapshot.firstRecordingGapPreviousTimestampNs)
        assertEquals(1_080_000_000L, snapshot.firstRecordingGapTimestampNs)
    }

    @Test
    fun `assigned session transition resets sequence without inventing gap`() {
        val monitor = PipelineDeadlineMonitor(targetFps = 25.0) { 4_000_000_000L }
        monitor.recordCapture(identity(frameNumber = 500L, sensorTimestampNs = 3_000_000_000L))
        monitor.recordCapture(
            identity(frameNumber = 1L, sensorTimestampNs = 100_000_000L).copy(
                sessionGeneration = 2L
            )
        )

        assertEquals(0L, monitor.snapshot().droppedCaptureFrames)
        assertEquals(2L, monitor.snapshot().capturedFrames)
    }

    @Test
    fun stageDeadlineExposesQueueWaitProcessingAndLateOwner() {
        var nowNs = 100L
        val monitor = PipelineDeadlineMonitor(60.0) { nowNs }
        val token = monitor.beginStage(
            stage = PipelineStage.ENCODER,
            identity = identity(1L, 50L),
            queueEnteredAtNs = 60L,
            deadlineNs = 150L,
            queueDepth = 3,
            startedAtNs = 100L
        )
        nowNs = 170L
        monitor.finishStage(token, success = true)

        val encoder = monitor.snapshot().stageTiming.getValue(PipelineStage.ENCODER)
        assertEquals(70L, encoder.averageProcessingNs)
        assertEquals(40L, encoder.averageQueueWaitNs)
        assertEquals(1L, encoder.missedDeadlines)
        assertEquals(3, encoder.maximumQueueDepth)
    }

    @Test
    fun stageCompletionDoesNotOverwriteNewerLiveQueueDepth() {
        val monitor = PipelineDeadlineMonitor(60.0) { 100L }
        val token = monitor.beginStage(
            stage = PipelineStage.PREVIEW,
            identity = identity(1L, 50L),
            queueEnteredAtNs = 60L,
            deadlineNs = 1_000L,
            queueDepth = 1,
            startedAtNs = 100L
        )
        monitor.setQueueDepth(PipelineStage.PREVIEW, 4)

        monitor.finishStage(token, success = true, endedAtNs = 120L)

        val preview = monitor.snapshot().stageTiming.getValue(PipelineStage.PREVIEW)
        assertEquals(4, preview.currentQueueDepth)
        assertEquals(4, preview.maximumQueueDepth)
    }

    @Test
    fun outOfOrderPresentationDoesNotInflateMeasuredFps() {
        val monitor = PipelineDeadlineMonitor(30.0) { 1_200_000_000L }
        monitor.recordPreviewReady(1_000_000_000L)
        monitor.recordPreviewReady(1_200_000_000L)
        monitor.recordPreviewReady(1_100_000_000L)

        assertEquals(5.0, monitor.snapshot().previewFps, 0.0001)
    }

    @Test
    fun liveFpsWindowForgetsEarlySessionCadenceAndExposesCurrentStall() {
        var nowNs = 1_000_000_000L
        val monitor = PipelineDeadlineMonitor(30.0) { nowNs }
        repeat(61) { index ->
            val timestamp = 1_000_000_000L + index * 33_333_333L
            nowNs = timestamp
            monitor.recordPreviewReady(timestamp)
        }
        assertEquals(30.0, monitor.snapshot().previewFps, 0.1)

        nowNs += 1_000_000_000L
        val stalledFps = monitor.snapshot().previewFps
        assertTrue("stalled live FPS was $stalledFps", stalledFps < 20.0)

        nowNs += 2_100_000_000L
        assertEquals(0.0, monitor.snapshot().previewFps, 0.0)
    }

    @Test
    fun criticalThermalReducesSecondaryWorkWithoutChangingRecordingRequest() {
        val monitor = PipelineDeadlineMonitor(60.0) { 1_000L }
        monitor.updateResources(
            PipelineResourceSample(
                measuredAtNs = 1_000L,
                thermal = TimedThermalSample(
                    timestampNs = 1_000L,
                    state = PipelineThermalState.CRITICAL,
                    platformStatus = 4
                )
            )
        )
        val decision = AdaptivePipelinePolicy.resolve(
            PipelineLoadSignal(
                diagnostics = monitor.snapshot(),
                encoderLatencyBudgetNs = 16_666_667L,
                previewLatencyBudgetNs = 33_333_334L
            )
        )

        assertTrue(decision.level.rank >= SecondaryDegradationLevel.SKIP_STALE_PREVIEW.rank)
        assertTrue(decision.aiCadenceDivisor > 1)
        assertTrue(decision.recordingProtected)
        assertTrue(decision.requestedRecordingQualityUnchanged)
        assertFalse(decision.reasons.isEmpty())
    }

    @Test
    fun storageQueuePressureDegradesSecondaryWorkWithoutChangingRecordingQuality() {
        val monitor = PipelineDeadlineMonitor(60.0) { 1_000L }
        monitor.updateResources(
            PipelineResourceSample(
                measuredAtNs = 1_000L,
                storageQueuePressure = 0.80f
            )
        )

        val decision = AdaptivePipelinePolicy.resolve(
            PipelineLoadSignal(
                diagnostics = monitor.snapshot(),
                encoderLatencyBudgetNs = 16_666_667L,
                previewLatencyBudgetNs = 33_333_334L
            )
        )

        assertEquals(SecondaryDegradationLevel.LIGHTWEIGHT_PREVIEW_AND_DEFER_VFX, decision.level)
        assertTrue(decision.recordingProtected)
        assertTrue(decision.requestedRecordingQualityUnchanged)
        assertTrue(decision.reasons.contains("storage-queue-critical"))
    }

    @Test
    fun recordingWorkerTimingsJoinExactSensorIdentityWithoutConflatingStages() {
        var nowNs = 1_000_000_000L
        val pipeline = SensorCentricFramePipeline(targetFps = 30.0, monotonicClockNs = { nowNs })
        try {
            pipeline.beginSession(
                sessionGeneration = 7L,
                alignment = CameraImuClockAlignment.UNAVAILABLE,
                calibration = null
            )
            val sensorTimestampNs = 900_000_000L
            pipeline.onImageAvailable(sensorTimestampNs = sensorTimestampNs, arrivedAtNs = nowNs)

            assertTrue(
                pipeline.onRecordingStageEvent(
                    sensorTimestampNs = sensorTimestampNs,
                    stage = PipelineStage.STORAGE,
                    queueEnteredAtNs = 1_010_000_000L,
                    startedAtNs = 1_020_000_000L,
                    endedAtNs = 1_025_000_000L,
                    queueDepthAtStart = 3,
                    currentQueueDepth = 2,
                    success = true
                )
            )

            val storage = pipeline.diagnostics().stageTiming.getValue(PipelineStage.STORAGE)
            assertEquals(1L, storage.samples)
            assertEquals(5_000_000L, storage.averageProcessingNs)
            assertEquals(10_000_000L, storage.averageQueueWaitNs)
            assertEquals(2, storage.currentQueueDepth)
            assertEquals(3, storage.maximumQueueDepth)
            assertEquals(0L, storage.failures)
        } finally {
            nowNs += 1L
            pipeline.close()
        }
    }

    private fun identity(frameNumber: Long, sensorTimestampNs: Long) = FrameIdentity(
        sessionGeneration = 1L,
        ingressSequence = frameNumber,
        frameNumber = frameNumber,
        sensorTimestampNs = sensorTimestampNs,
        source = FrameIdentitySource.CAMERA2_CAPTURE_RESULT
    )
}
