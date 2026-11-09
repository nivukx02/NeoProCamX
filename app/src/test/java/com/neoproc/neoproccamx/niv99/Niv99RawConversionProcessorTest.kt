package com.neoproc.neoproccamx.niv99

import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99RawConversionProcessorTest {
    @Test
    fun `owned processing is pixel identical to immutable processing across temporal frames`() {
        val metadata = metadata(17, 13)
        val copyProcessor = Niv99RawConversionProcessor(
            metadata,
            Niv99OfflineConversionPolicy.rawToNivukxVision()
        )
        val ownedProcessor = Niv99RawConversionProcessor(
            metadata,
            Niv99OfflineConversionPolicy.rawToNivukxVision()
        )
        val durationNs = 1_000_000_000L / metadata.fps

        repeat(3) { frame ->
            val source = IntArray(metadata.width * metadata.height) { index ->
                val x = index % metadata.width
                val y = index / metadata.width
                (metadata.blackLevel + 80 + x * 37 + y * 19 + frame * 7)
                    .coerceAtMost(metadata.whiteLevel)
            }.also {
                if (frame == 1) it[6 * metadata.width + 8] = metadata.whiteLevel - 50
            }
            val immutableInput = source.copyOf()
            val ownedInput = source.copyOf()
            val timestamp = 2_000_000_000L + frame.toLong() * durationNs

            val copied = copyProcessor.process(immutableInput, frame.toLong(), timestamp)
            val owned = ownedProcessor.processOwned(ownedInput, frame.toLong(), timestamp)

            assertArrayEquals("owned frame $frame must preserve the existing NIV99 pixels", copied.samples, owned.samples)
            assertTrue("owned path must return transferred storage", owned.samples === ownedInput)
            assertArrayEquals("immutable API must preserve its source", source, immutableInput)
            assertEquals(copied.evidence.fullFrameSamples, owned.evidence.fullFrameSamples)
            assertEquals(copied.evidence.spatiallyFilteredSamples, owned.evidence.spatiallyFilteredSamples)
            assertEquals(copied.evidence.temporallyFusedSamples, owned.evidence.temporallyFusedSamples)
            assertEquals(copied.evidence.correctedDefects, owned.evidence.correctedDefects)
            assertEquals(copied.evidence.historyUsed, owned.evidence.historyUsed)
            assertEquals(copied.evidence.historyReset, owned.evidence.historyReset)
        }
    }

    @Test
    fun `processing uses a full frame copy corrects defects and preserves RAW master`() {
        val metadata = metadata(9, 9)
        val source = IntArray(81) { 500 }
        val defectIndex = 4 * metadata.width + 4
        source[defectIndex] = 2_000
        val before = source.copyOf()

        val result = Niv99RawConversionProcessor.processSingleFrame(
            source = source,
            metadata = metadata,
            plan = Niv99OfflineConversionPolicy.rawToLog()
        )

        assertArrayEquals(before, source)
        assertFalse(source === result.samples)
        assertEquals(source.size, result.evidence.fullFrameSamples)
        assertEquals(500, result.samples[defectIndex])
        assertTrue(result.evidence.correctedDefects >= 1)
        assertTrue(result.evidence.sourceSamplesPreserved)
        assertTrue(result.evidence.capturedWhiteBalancePreserved)
    }

    @Test
    fun `highlight and frame boundary samples are not softened`() {
        val metadata = metadata(9, 9)
        val source = IntArray(81) { 500 }
        val highlightIndex = 4 * metadata.width + 4
        source[highlightIndex] = metadata.whiteLevel
        source[0] = 777

        val result = Niv99RawConversionProcessor.processSingleFrame(
            source,
            metadata,
            Niv99OfflineConversionPolicy.rawToNdr10Plus()
        )

        assertEquals(metadata.whiteLevel, result.samples[highlightIndex])
        assertEquals(777, result.samples[0])
    }

    @Test
    fun `temporal history is bounded ordered and reset by discontinuity or scene cut`() {
        val metadata = metadata(10, 10)
        val processor = Niv99RawConversionProcessor(
            metadata,
            Niv99OfflineConversionPolicy.rawToNivukxVision()
        )
        val durationNs = 1_000_000_000L / metadata.fps

        val first = processor.process(IntArray(100) { 600 }, 0L, 1_000_000_000L)
        val second = processor.process(IntArray(100) { 606 }, 1L, 1_000_000_000L + durationNs)
        val discontinuous = processor.process(IntArray(100) { 608 }, 4L, 1_000_000_000L + durationNs * 4)
        val sceneCut = processor.process(IntArray(100) { 2_000 }, 5L, 1_000_000_000L + durationNs * 5)

        assertTrue(first.evidence.historyReset)
        assertFalse(first.evidence.historyUsed)
        assertTrue(second.evidence.historyUsed)
        assertTrue(second.evidence.temporallyFusedSamples > 0)
        assertTrue(discontinuous.evidence.historyReset)
        assertFalse(discontinuous.evidence.historyUsed)
        assertTrue(sceneCut.evidence.historyReset)
        assertFalse(sceneCut.evidence.historyUsed)
    }

    @Test
    fun `frame evidence contains route generation frame and sensor timestamp provenance`() {
        val metadata = metadata(8, 8)
        val processor = Niv99RawConversionProcessor(
            metadata = metadata,
            plan = Niv99OfflineConversionPolicy.rawToRawDeveloped(),
            sessionGeneration = 73L
        )
        val result = processor.process(IntArray(64) { 500 }, 19L, 987_654_321L)

        assertEquals(Niv99OfflineConversionRoute.RAW_TO_RAW_DEVELOPED, result.evidence.route)
        assertEquals(73L, result.evidence.sessionGeneration)
        assertEquals(19L, result.evidence.frameNumber)
        assertEquals(987_654_321L, result.evidence.sensorTimestampNs)
        assertTrue(result.evidence.processingTimeNs >= 0L)
    }

    @Test
    fun `conversion evidence reports the backend that actually delivered each frame`() {
        val metadata = metadata(8, 8)
        val plan = Niv99OfflineConversionPolicy.rawToLog()
        val processor = Niv99RawConversionProcessor(metadata, plan)
        val accumulator = Niv99RawConversionEvidenceAccumulator(plan)
        val durationNs = 1_000_000_000L / metadata.fps

        accumulator.addGpu(
            processor.process(IntArray(64) { 500 }, 0L, 1_000_000_000L).evidence
        )
        accumulator.addCpu(
            processor.process(IntArray(64) { 503 }, 1L, 1_000_000_000L + durationNs).evidence
        )

        val evidence = accumulator.evidence()
        assertEquals(2L, evidence.processedFrames)
        assertEquals(1L, evidence.gpuProcessedFrames)
        assertEquals(1L, evidence.cpuProcessedFrames)
        assertEquals("1", evidence.metadata()["niv99RawGpuProcessedFrames"])
        assertEquals("1", evidence.metadata()["niv99RawCpuProcessedFrames"])
    }

    private fun metadata(width: Int, height: Int): RawDevelopMetadata = RawDevelopMetadata(
        width = width,
        height = height,
        storageBitDepth = 12,
        sensorWhiteLevel = 4_095,
        blackLevel = 64,
        whiteLevel = 4_095,
        bayerPatternValue = 0,
        whiteBalanceR = 2.1f,
        whiteBalanceGr = 1f,
        whiteBalanceGb = 1f,
        whiteBalanceB = 1.8f,
        exposureTimeNs = 10_000_000L,
        analogGain = 4f,
        digitalGain = 1f,
        noiseS = 0.00008f,
        noiseO = 0.000004f,
        sensorToXyz = DEFAULT_SENSOR_TO_XYZ,
        fps = 25,
        colorProfileTag = "NIV99-TEST",
        blackLevelRggb = floatArrayOf(64f, 64f, 64f, 64f)
    )
}
