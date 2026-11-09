package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawRecordingContinuityPolicyTest {
    @Test
    fun `storage budget preserves reserve and container headroom exactly`() {
        val availableBytes = 20L * GIB
        val safetyReserveBytes = 2L * GIB
        val packetBytes = 25_165_894L

        val budget = RawRecordingContinuityPolicy.resolveStorageBudget(
            availableBytes = availableBytes,
            safetyReserveBytes = safetyReserveBytes,
            framePacketBytes = packetBytes,
            targetFps = 25
        )

        val expectedWritable = availableBytes - safetyReserveBytes - 16L * MIB
        assertEquals(expectedWritable, budget.writableBytes)
        assertEquals(expectedWritable / packetBytes, budget.maximumFrames)
        assertEquals(budget.maximumFrames * 1_000L / 25L, budget.maximumDurationMs)
    }

    @Test
    fun `frame admission ends exactly before exceeding the storage backed frame count`() {
        assertFalse(RawRecordingContinuityPolicy.shouldStopBeforeNextFrame(99L, 100L))
        assertTrue(RawRecordingContinuityPolicy.shouldStopBeforeNextFrame(100L, 100L))
        assertTrue(RawRecordingContinuityPolicy.shouldStopAfterAcceptedFrame(100L, 100L))
        assertEquals(2_500L, RawRecordingContinuityPolicy.projectedEndBytes(9L, 250L))
    }

    @Test
    fun `adaptive byte admission preserves compressed history and worst case in flight headroom`() {
        val projected = RawRecordingContinuityPolicy.projectedAdaptiveEndBytes(
            acceptedFileBytes = 700L,
            pendingUnencodedFrames = 1,
            framePacketBytes = 100L
        )

        assertEquals(900L, projected)
        assertFalse(
            RawRecordingContinuityPolicy.shouldStopBeforeAdaptivePacket(
                projectedEndBytes = projected,
                writableBytes = 900L
            )
        )
        assertTrue(
            RawRecordingContinuityPolicy.shouldStopBeforeAdaptivePacket(
                projectedEndBytes = 901L,
                writableBytes = 900L
            )
        )
        assertEquals(
            13L,
            RawRecordingContinuityPolicy.conservativeMaximumFramesFromCurrentBytes(
                acceptedFrames = 10L,
                acceptedFileBytes = 700L,
                writableBytes = 1_000L,
                framePacketBytes = 100L
            )
        )
    }

    @Test
    fun `protected reader boundary stops before queue rejection`() {
        val readerMaxImages = 16
        val retainedBudget = RawVideoBackpressurePolicy.maxRetainedOpenImages(readerMaxImages)

        assertFalse(
            RawRecordingContinuityPolicy.shouldStopForBackpressure(
                readerMaxImages = readerMaxImages,
                queuedImages = retainedBudget - 2,
                activeImageOwners = 0
            )
        )
        assertTrue(
            RawRecordingContinuityPolicy.shouldStopForBackpressure(
                readerMaxImages = readerMaxImages,
                queuedImages = retainedBudget - 1,
                activeImageOwners = 0
            )
        )
    }

    @Test
    fun `thermal four ownership burst retains two frame recovery margin`() {
        val readerMaxImages = 16

        assertFalse(
            RawRecordingContinuityPolicy.shouldStopForBackpressure(
                readerMaxImages = readerMaxImages,
                queuedImages = 6,
                activeImageOwners = 5
            )
        )
        assertTrue(
            RawRecordingContinuityPolicy.shouldStopForBackpressure(
                readerMaxImages = readerMaxImages,
                queuedImages = 8,
                activeImageOwners = 5
            )
        )
        assertEquals(
            9,
            RawVideoBackpressurePolicy.resolveSafeQueueCapacity(
                requestedQueueCapacity = 16,
                readerMaxImages = readerMaxImages,
                reservedActiveWrites = 5
            )
        )
        assertEquals(
            13,
            RawVideoBackpressurePolicy.resolveSafeQueueCapacity(
                requestedQueueCapacity = 16,
                readerMaxImages = readerMaxImages,
                reservedActiveWrites = 1
            )
        )
    }

    @Test
    fun `manager routes a continuity boundary through serialized app stop without a drop`() {
        val manager = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        ).readText()
        val viewModel = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()

        val boundary = manager.substringAfter(
            "private fun stopBeforeRawFrameAtContinuityBoundaryIfRequired"
        ).substringBefore("private fun dispatchContinuityBoundaryStop")
        assertTrue(boundary.contains("closeRawWriterAdmission()"))
        assertFalse(boundary.contains("markDroppedFrame()"))
        assertTrue(boundary.contains("writer.acceptedFileBytes"))
        assertTrue(boundary.contains("projectedAdaptiveEndBytes"))
        assertFalse(boundary.contains("shouldStopBeforeNextFrame("))
        assertTrue(manager.contains("continuityBoundaryStopListener"))
        assertTrue(viewModel.contains("rawVideoManager.continuityBoundaryStopListener"))
        assertTrue(viewModel.contains("stopRecording()"))
    }

    @Test
    fun `manager counts each dequeued Camera2 image once across borrowed storage handoff`() {
        val manager = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        ).readText()

        val boundary = manager.substringAfter(
            "private fun stopBeforeRawFrameAtContinuityBoundaryIfRequired"
        ).substringBefore("private fun dispatchContinuityBoundaryStop")
        val ingress = manager.substringAfter("private fun onRawFrameAvailable")
            .substringBefore("private fun onSensorLinearNdrFrameAvailable")
        val writeLoop = manager.substringAfter("private fun writeLoop")
            .substringBefore("private fun tapRecordingPreview")

        assertTrue(boundary.contains("val activeImageOwners = ownedWriterImages.get()"))
        assertFalse(boundary.contains("activeWrites.get() + writer.borrowedPayloadWriteCount"))
        assertTrue(ingress.contains("activeWrites = ownedWriterImages.get()"))
        assertTrue(writeLoop.contains("ownedWriterImages.incrementAndGet()"))
        assertTrue(writeLoop.contains("ownedWriterImages.decrementAndGet()"))
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * 1024L * 1024L
    }
}
