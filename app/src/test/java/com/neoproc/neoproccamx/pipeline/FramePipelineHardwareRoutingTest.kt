package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FramePipelineHardwareRoutingTest {

    @Test
    fun `pre-roll frame is retired when committed clip epoch opens`() {
        val fence = HardwareRecordingAdmissionFence()
        val initialRuntime = fence.beginRuntimeRecording()
        assertTrue(fence.isCurrent(initialRuntime))
        assertTrue(fence.isLossEligible(initialRuntime))

        val preparation = fence.beginClipPreparation()
        assertTrue(preparation.recordingActive)
        assertEquals(0L, preparation.lossAccountingEpoch)
        assertFalse(fence.isCurrent(initialRuntime))
        assertFalse(fence.isLossEligible(preparation))

        val committed = requireNotNull(fence.commitClipIngress())
        assertTrue(committed.lossAccountingEpoch > 0L)
        assertFalse(fence.isCurrent(preparation))
        assertFalse(fence.isLossEligible(preparation))
        assertTrue(fence.isCurrent(committed))
        assertTrue(fence.isLossEligible(committed))
    }

    @Test
    fun `stopped clip cannot charge a late prepared frame to a later recording`() {
        val fence = HardwareRecordingAdmissionFence()
        val firstClip = fence.beginRuntimeRecording()
        fence.endRuntimeRecording()
        val secondClip = fence.beginRuntimeRecording()

        assertFalse(fence.isCurrent(firstClip))
        assertFalse(fence.isLossEligible(firstClip))
        assertTrue(fence.isCurrent(secondClip))
        assertTrue(fence.isLossEligible(secondClip))
        assertTrue(secondClip.lossAccountingEpoch > firstClip.lossAccountingEpoch)
    }

    @Test
    fun `two stage preparation pool is bounded and reuses its NIV99 payloads`() {
        val pool = PreparedHardwareFramePool<String>(capacity = 2)
        val first = requireNotNull(pool.acquire(timeoutMs = 0L))
        val second = requireNotNull(pool.acquire(timeoutMs = 0L))

        assertEquals(2, pool.inFlightCount())
        assertEquals(2, pool.maximumInFlightCount())
        assertEquals(null, pool.acquire(timeoutMs = 0L))

        assertTrue(pool.recycle(first))
        val reused = requireNotNull(pool.acquire(timeoutMs = 0L))
        assertTrue(first === reused)
        assertTrue(pool.recycle(reused))
        assertFalse(pool.recycle(reused))
        assertTrue(pool.recycle(second))
        assertEquals(0, pool.inFlightCount())
    }

    @Test(timeout = 10_000L)
    fun `two stage preparation pool never exceeds its fixed window under contention`() {
        val pool = PreparedHardwareFramePool<String>(capacity = 2)
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(8)
        val observedMaximum = AtomicInteger(0)
        try {
            repeat(8) {
                workers.execute {
                    start.await()
                    repeat(50) {
                        val slot = requireNotNull(pool.acquire(timeoutMs = 2_000L))
                        val active = pool.inFlightCount()
                        observedMaximum.accumulateAndGet(active, ::maxOf)
                        Thread.yield()
                        assertTrue(pool.recycle(slot))
                    }
                    finished.countDown()
                }
            }
            start.countDown()
            assertTrue(finished.await(8L, TimeUnit.SECONDS))
            assertTrue(observedMaximum.get() in 1..2)
            assertEquals(2, pool.maximumInFlightCount())
            assertEquals(0, pool.inFlightCount())
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `metadata preparation overlaps only when live metadata processing is enabled`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
        ).readText()
        val preparation = source
            .substringAfter("private fun submitHardwareDispatchFrame(")
            .substringBefore("private fun prepareNiv99TuningForHardwareFrame(")
        val metadata = source
            .substringAfter("private fun prepareNiv99TuningForHardwareFrame(")
            .substringBefore("private fun scheduleHardwareGpuDispatchDrain()")
        val gpu = source
            .substringAfter("private fun submitPreparedHardwareFrame(")
            .substringBefore("private fun markHardwareFrameNotSubmitted(")

        assertTrue(source.contains("PreparedHardwareFramePool<Image>(HARDWARE_PREPARATION_WINDOW)"))
        assertTrue(source.contains("HARDWARE_PREPARATION_WINDOW = 2"))
        assertTrue(source.contains("ConcurrentLinkedQueue<PreparedHardwareFrameSlot<Image>>()"))
        assertTrue(source.contains("hardwareGpuSubmitExecutor"))
        assertTrue(preparation.contains("prepareNiv99TuningForHardwareFrame("))
        assertTrue(preparation.contains("NeoZeroCopyFrameRailPolicy.route(liveNiv99Enabled)"))
        assertTrue(preparation.contains(
            "if (frameRailRoute == NeoFrameRailSubmissionRoute.BOUNDED_METADATA_OVERLAP)"
        ))
        assertTrue(preparation.contains("preparedHardwareFrames.offer(slot)"))
        assertTrue(preparation.contains("hardwareOverlappedSubmitCounter.incrementAndGet()"))
        assertTrue(preparation.contains("hardwareImmediateSubmitCounter.incrementAndGet()"))
        assertTrue(preparation.contains("submitPreparedHardwareFrame(slot)"))
        assertTrue(
            preparation.indexOf("NeoZeroCopyFrameRailPolicy.route(liveNiv99Enabled)") <
                preparation.indexOf("preparedHardwareFrames.offer(slot)")
        )
        assertTrue(
            preparation.indexOf("preparedHardwareFrames.offer(slot)") <
                preparation.indexOf("submitPreparedHardwareFrame(slot)")
        )
        assertTrue(metadata.contains("niv99LiveCapture.awaitTuningForImage("))
        assertTrue(gpu.contains("submitTrackedImageToVulkan("))
        assertFalse(gpu.contains("awaitTuningForImage("))
        assertTrue(source.contains("preparedHardwareFramePool.inFlightCount()"))
        assertTrue(source.contains("hardwareGpuSubmitExecutor.shutdownNow()"))
        assertTrue(source.contains("discardQueuedPreparedHardwareFrames("))
        assertTrue(source.contains("camera-session-changed-before-gpu-submit"))
    }

    @Test
    fun `preview occupancy cannot reject recording and recording dequeue stays FIFO`() {
        val closed = mutableListOf<String>()
        val queues = HardwareFrameDispatchQueues<String>(
            capacityPerDomain = 2,
            acquireGuardNs = 1L,
            closeFrame = { it.closeOnce() },
            allowsPreviewStaleFrameConflation = true
        )
        fun frame(name: String, route: HardwareImportRoute) = RoutedHardwareFrame(
            resource = name,
            route = route,
            closeResource = { closed += it }
        )

        val previewOne = frame("preview-one", HardwareImportRoute.PREVIEW)
        val previewTwo = frame("preview-two", HardwareImportRoute.PREVIEW)
        val recordingOne = frame("recording-one", HardwareImportRoute.RECORDING)
        val recordingTwo = frame("recording-two", HardwareImportRoute.RECORDING)
        assertTrue(queues.offer(previewOne, 1L, 1L).accepted)
        assertTrue(queues.offer(previewTwo, 2L, 2L).accepted)

        // The preview domain is full, but it has no admission authority over recording.
        assertTrue(queues.offer(recordingOne, 3L, 3L).accepted)
        assertTrue(queues.offer(recordingTwo, 4L, 4L).accepted)
        assertEquals(4, queues.queuedFrameCount())

        assertEquals("recording-one", queues.pollRecording(5L)?.frame?.resource)
        assertEquals("recording-two", queues.pollRecording(5L)?.frame?.resource)
        assertEquals("preview-one", queues.pollPreview(5L)?.frame?.resource)
        assertEquals("preview-two", queues.pollPreview(5L)?.frame?.resource)
        assertTrue(closed.isEmpty())
        assertEquals(4L, queues.telemetry().queueLatencySamples)
        assertEquals(2L, queues.telemetry().averageQueueLatencyNs)

        listOf(previewOne, previewTwo, recordingOne, recordingTwo).forEach { frame ->
            assertTrue(frame.closeOnce())
            assertFalse(frame.closeOnce())
        }
        assertEquals(
            setOf("preview-one", "preview-two", "recording-one", "recording-two"),
            closed.toSet()
        )
        assertEquals(4, closed.size)
    }

    @Test
    fun `mixed start stop routing never conflates recording frames`() {
        val closed = mutableListOf<String>()
        val queues = HardwareFrameDispatchQueues<String>(
            capacityPerDomain = 2,
            acquireGuardNs = 1L,
            closeFrame = { it.closeOnce() },
            allowsPreviewStaleFrameConflation = true
        )
        fun frame(name: String, route: HardwareImportRoute) = RoutedHardwareFrame(
            resource = name,
            route = route,
            closeResource = { closed += it }
        )
        val recordingOne = frame("recording-one", HardwareImportRoute.RECORDING)
        val transitionPreview = frame("transition-preview", HardwareImportRoute.PREVIEW)
        val recordingTwo = frame("recording-two", HardwareImportRoute.RECORDING)
        val recordingOverflow = frame("recording-overflow", HardwareImportRoute.RECORDING)

        assertTrue(queues.offer(recordingOne, 10L, 10L).accepted)
        assertTrue(queues.offer(transitionPreview, 11L, 11L).accepted)
        assertTrue(queues.offer(recordingTwo, 12L, 12L).accepted)
        val rejected = queues.offer(recordingOverflow, 13L, 100L)

        assertFalse(rejected.accepted)
        assertTrue(rejected.reason.contains("strict-no-conflation-backpressure"))
        assertEquals("recording-one", queues.pollRecording(101L)?.frame?.resource)
        assertEquals("recording-two", queues.pollRecording(101L)?.frame?.resource)
        assertEquals("transition-preview", queues.pollPreview(101L)?.frame?.resource)
        assertTrue(closed.isEmpty())
        assertEquals(0L, queues.telemetry().droppedFrames)
        assertEquals(1L, queues.telemetry().rejectedFrames)
        assertEquals(3L, queues.telemetry().queueLatencySamples)
        assertEquals(90L, queues.telemetry().averageQueueLatencyNs)
        listOf(recordingOne, transitionPreview, recordingTwo, recordingOverflow).forEach {
            assertTrue(it.closeOnce())
            assertFalse(it.closeOnce())
        }
        assertEquals(4, closed.size)
    }

    @Test
    fun `accepted frame keeps routing snapshot across recording state changes`() {
        var recordingActive = true
        val frame = RoutedHardwareFrame(
            resource = "frame",
            route = HardwareImportRoute.capture(recordingActive),
            closeResource = {}
        )

        recordingActive = false

        assertFalse(recordingActive)
        assertEquals(HardwareImportRoute.RECORDING, frame.route)
        assertTrue(frame.route.recordingPath)
        assertEquals(FrameDomain.ENCODER, frame.route.domain)
    }

    @Test
    fun `hardware frame queue and close ownership can each be claimed exactly once`() {
        val closeCalls = AtomicInteger(0)
        val frame = RoutedHardwareFrame(
            resource = "frame",
            route = HardwareImportRoute.PREVIEW,
            closeResource = { closeCalls.incrementAndGet() }
        )
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(64)
        val queueClaims = AtomicInteger(0)
        val closeClaims = AtomicInteger(0)
        try {
            repeat(64) {
                workers.execute {
                    start.await()
                    if (frame.leaveQueue()) queueClaims.incrementAndGet()
                    if (frame.closeOnce()) closeClaims.incrementAndGet()
                    finished.countDown()
                }
            }
            start.countDown()

            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, queueClaims.get())
            assertEquals(1, closeClaims.get())
            assertEquals(1, closeCalls.get())
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `manager captures route before offer and capture callback never waits for stop clear`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
        ).readText()
        val enqueueBody = source.substringAfter("private fun enqueueHardwareImage(image: Image)")
            .substringBefore("private fun releaseHardwareQueueMembership")
        val drainBody = source.substringAfter("private fun drainHardwareReader(")
            .substringBefore("private fun deferHardwareReader")
        val dispatchBody = source.substringAfter("private fun drainHardwareDispatchQueue()")
            .substringBefore("private fun submitTrackedImageToVulkan")
        val stopBody = source.substringAfter("fun stop()")
            .substringBefore("fun destroy()")

        assertTrue(enqueueBody.indexOf("hardwareRecordingAdmissionFence.capture()") >= 0)
        assertTrue(enqueueBody.indexOf("HardwareImportRoute.capture(recordingAdmission.recordingActive)") <
            enqueueBody.indexOf("hardwareFrameDispatchQueues.offer("))
        assertTrue(enqueueBody.contains("recordingAdmission = recordingAdmission"))
        assertTrue(source.contains("HardwareFrameDispatchQueues<Image>"))
        assertTrue(enqueueBody.contains("hardwareFrameDispatchQueues.offer("))
        assertFalse(enqueueBody.contains("allowStaleFrameConflation"))
        assertTrue(drainBody.contains("hardwareReaderAcquisitionGate.tryEnterListener()"))
        assertTrue(drainBody.contains("hardwareReaderAcquisitionGate.enterDeferred()"))
        assertFalse(drainBody.contains("synchronized(hardwareReaderAcquisitionGate)"))
        assertTrue(stopBody.contains("hardwareReaderAcquisitionGate.withLifecycleBarrier"))
        assertTrue(stopBody.indexOf("hardwareReaderAcquisitionGate.withLifecycleBarrier") <
            stopBody.indexOf("hardwareFrameDispatchQueues.clear()"))
        assertFalse(dispatchBody.contains("hardwareReaderAcquisitionGate"))
        assertTrue(dispatchBody.contains("val route = routedFrame.route"))
        assertTrue(dispatchBody.contains("recordingPath = route.recordingPath"))
        assertFalse(dispatchBody.contains("recordingPath = runtimeRecordingHint"))
        assertTrue(dispatchBody.indexOf("hardwareFrameDispatchQueues.pollRecording(nowNs)") <
            dispatchBody.indexOf("hardwareFrameDispatchQueues.pollPreview(nowNs)"))
        assertTrue(source.contains("hardwareFrameDispatchQueues.queuedFrameCount() + activeSubmit"))
    }

    @Test
    fun `hardware frame carries one admission sensor record through dispatch and skip`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
        ).readText()
        val enqueueBody = source.substringAfter("private fun enqueueHardwareImage(image: Image)")
            .substringBefore("private fun releaseHardwareQueueMembership")
        val skipBody = source.substringAfter("private fun skipHardwarePreviewFrame(")
            .substringBefore("private fun submitHardwareDispatchFrame(")
        val submitBody = source.substringAfter("private fun submitHardwareDispatchFrame(")
            .substringBefore("private fun submitTrackedImageToVulkan(")

        assertTrue(enqueueBody.contains("val sensorRecord = try"))
        assertTrue(enqueueBody.contains("sensorRecord = sensorRecord"))
        assertTrue(skipBody.contains("queued.frame.sensorRecord"))
        assertTrue(submitBody.contains("routedFrame.sensorRecord"))
        assertFalse(skipBody.contains("sensorFramePipeline.onImageAvailable("))
        assertFalse(submitBody.contains("sensorFramePipeline.onImageAvailable("))
    }
}
