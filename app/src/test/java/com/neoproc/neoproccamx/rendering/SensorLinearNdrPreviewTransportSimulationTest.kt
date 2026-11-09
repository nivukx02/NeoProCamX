package com.neoproc.neoproccamx.rendering

import java.io.File
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrPreviewTransportSimulationTest {
    private data class SimulationResult(
        val encoderLineage: List<Int>,
        val previewLineage: List<Int>,
        val saturatedFrames: List<Int>,
        val capacityRetryFrames: List<Int>,
        val acceptedDispatchNs: Map<Int, Long>,
        val peakPreviewOwners: Int,
        val peakFullResolutionOwners: Int,
        val finalFullResolutionOwners: Int,
        val finalPreviewOwners: Int
    )

    /** Deterministic virtual-time model of the native 3-slot ring plus ordered serial EGL worker. */
    private fun simulate(
        frameCount: Int,
        frameIntervalNs: Long,
        previewServiceNs: Long,
        firstPreviewServiceNs: Long = previewServiceNs,
        retryPollNs: Long = 4_000_000L,
        retryDeadlineNs: Long = 160_000_000L,
        readerMaxImages: Int = 8,
        ringSlots: Int = 3
    ): SimulationResult {
        val encoder = ArrayList<Int>(frameCount)
        val preview = ArrayList<Int>(frameCount)
        val saturated = mutableListOf<Int>()
        val capacityRetries = mutableListOf<Int>()
        val acceptedDispatchNs = linkedMapOf<Int, Long>()
        val previewCompletionTimes = ArrayDeque<Pair<Int, Long>>()
        val fullResolutionOwners = linkedSetOf<Int>()
        var workerAvailableNs = 0L
        var recorderAvailableNs = 0L
        var peakOwners = 0
        var peakFullResolutionOwners = 0

        frameLoop@ for (frame in 0 until frameCount) {
            val arrivalNs = frame.toLong() * frameIntervalNs
            check(fullResolutionOwners.add(frame)) { "duplicate full-resolution owner $frame" }
            peakFullResolutionOwners = maxOf(
                peakFullResolutionOwners,
                fullResolutionOwners.size
            )
            var dispatchNs = maxOf(arrivalNs, recorderAvailableNs)
            fun retireThrough(timeNs: Long) {
                while (previewCompletionTimes.isNotEmpty() &&
                    previewCompletionTimes.first().second <= timeNs
                ) {
                    preview += previewCompletionTimes.removeFirst().first
                }
            }
            retireThrough(dispatchNs)

            val retryStartedNs = dispatchNs
            while (previewCompletionTimes.size >= ringSlots) {
                capacityRetries += frame
                val nextRetryNs = dispatchNs + retryPollNs
                val retryExpired = nextRetryNs - retryStartedNs > retryDeadlineNs
                val readerBudgetExpired = nextRetryNs - arrivalNs >
                    (readerMaxImages - 1).coerceAtLeast(0).toLong() * frameIntervalNs
                if (retryExpired || readerBudgetExpired) {
                    saturated += frame
                    check(fullResolutionOwners.remove(frame)) {
                        "saturated frame lost its full-resolution release permit $frame"
                    }
                    break@frameLoop
                }
                dispatchNs = nextRetryNs
                retireThrough(dispatchNs)
            }
            // RETRY_PENDING returns without JNI or native-tail fabrication. The recorder retains the
            // same frame until a later poll observes a free slot, then dispatches that identity once.
            val serviceNs = if (frame == 0) firstPreviewServiceNs else previewServiceNs
            val completionNs = maxOf(dispatchNs, workerAvailableNs) + serviceNs
            workerAvailableNs = completionNs
            previewCompletionTimes.addLast(frame to completionNs)
            peakOwners = maxOf(peakOwners, previewCompletionTimes.size)
            acceptedDispatchNs[frame] = dispatchNs
            recorderAvailableNs = dispatchNs
            encoder += frame
            check(fullResolutionOwners.remove(frame)) {
                "accepted frame lost its full-resolution release permit $frame"
            }
        }
        while (previewCompletionTimes.isNotEmpty()) {
            preview += previewCompletionTimes.removeFirst().first
        }
        return SimulationResult(
            encoderLineage = encoder,
            previewLineage = preview,
            saturatedFrames = saturated,
            capacityRetryFrames = capacityRetries,
            acceptedDispatchNs = acceptedDispatchNs,
            peakPreviewOwners = peakOwners,
            peakFullResolutionOwners = peakFullResolutionOwners,
            finalFullResolutionOwners = fullResolutionOwners.size,
            finalPreviewOwners = previewCompletionTimes.size
        )
    }

    @Test
    fun `750 frame 25fps contract keeps exact encoder and preview lineage with zero owners`() {
        val result = simulate(
            frameCount = 750,
            frameIntervalNs = 40_000_000L,
            previewServiceNs = 8_000_000L
        )
        val exactLabels = (0 until 750).toList()
        assertEquals(exactLabels, result.encoderLineage)
        assertEquals(exactLabels, result.previewLineage)
        assertTrue(result.saturatedFrames.isEmpty())
        assertTrue(result.peakPreviewOwners <= 3)
        assertTrue(result.peakFullResolutionOwners <= 8)
        assertEquals(0, result.finalFullResolutionOwners)
        assertEquals(0, result.finalPreviewOwners)
    }

    @Test
    fun `preview overload is explicit saturation and never relabels full resolution encoder frames`() {
        val result = simulate(
            frameCount = 12,
            frameIntervalNs = 40_000_000L,
            previewServiceNs = 130_000_000L
        )
        assertTrue(result.saturatedFrames.isNotEmpty())
        val failedFrame = result.saturatedFrames.first()
        assertEquals((0 until failedFrame).toList(), result.encoderLineage)
        assertEquals(result.encoderLineage, result.previewLineage)
        assertTrue(result.peakPreviewOwners <= 3)
        assertTrue(result.peakFullResolutionOwners <= 8)
        assertEquals(0, result.finalFullResolutionOwners)
        assertEquals(result.previewLineage.sorted(), result.previewLineage)
        assertEquals(result.previewLineage.distinct(), result.previewLineage)
    }

    @Test
    fun `observed 11ms startup retirement retries exact fourth frame without JNI duplication`() {
        val result = simulate(
            frameCount = 750,
            frameIntervalNs = 40_000_000L,
            previewServiceNs = 8_000_000L,
            firstPreviewServiceNs = 131_000_000L,
            retryPollNs = 4_000_000L
        )
        val exactLabels = (0 until 750).toList()

        assertTrue(result.saturatedFrames.isEmpty())
        assertEquals(exactLabels, result.encoderLineage)
        assertEquals(exactLabels, result.previewLineage)
        assertEquals(listOf(3, 3, 3), result.capacityRetryFrames)
        assertEquals(132_000_000L, result.acceptedDispatchNs[3])
        assertTrue(result.peakPreviewOwners <= 3)
        assertTrue(result.peakFullResolutionOwners <= 8)
        assertEquals(0, result.finalFullResolutionOwners)
        assertEquals(0, result.finalPreviewOwners)
    }

    @Test
    fun `implementation keeps quality certificate thresholds and full resolution encoder ownership`() {
        fun source(path: String): String = listOf(File(path), File("app/$path"))
            .first(File::isFile)
            .readText()
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val lineage = source(
            "src/main/java/com/neoproc/neoproccamx/camera/Camera2FrameLineageEvidence.kt"
        )
        val publish = runtime
            .substringAfter("private fun publishCompletedSensorLinearNdrRecordingFrame(")
            .substringBefore("private fun enqueueSensorLinearNdrRecordingPreview(")
        val encoder = runtime
            .substringAfter("private fun submitLatestSensorLinearNdrFrame(")
            .substringBefore("fun submitRawSensorBytes(")

        assertTrue(encoder.contains("outputWidth != width || outputHeight != height"))
        assertTrue(publish.contains("return true"))
        assertFalse(publish.contains("return enqueueSensorLinearNdrRecordingPreview"))
        assertTrue(lineage.contains("STRICT_MINIMUM_CAPTURE_IMAGE_TIMESTAMP_MATCH_RATIO = 0.995"))
        assertTrue(lineage.contains("STRICT_MAXIMUM_PREVIEW_ENCODER_SUBMITTED_DIFFERENCE = 1"))
    }
}
