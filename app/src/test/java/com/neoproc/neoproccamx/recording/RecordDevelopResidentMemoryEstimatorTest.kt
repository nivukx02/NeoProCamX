package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordDevelopResidentMemoryEstimatorTest {

    private val width = 3264
    private val height = 2448
    private val p010Bytes = width.toLong() * height.toLong() * 3L
    private val temporalReserveBytes = p010Bytes
    private val deviceHeapBytes = 256L * 1024L * 1024L
    private val guardedBudgetBytes = 174_483_046L

    @Test
    fun `full-size enhanced CPU estimate covers the five-plane enhancement peak`() {
        val resident = RecordDevelopMovConverter.estimateResidentBytesPerFrame(
            width = width,
            height = height,
            enhanceEnabled = true,
            outputFormat = RecordDevelopOutputFormat.R210_444,
            gpuDevelopAvailable = false,
            packedParityDiagnosticsEnabled = false
        )

        assertEquals(119_854_080L, resident)
        assertEquals(
            guardedBudgetBytes,
            RecordDevelopPipelineOverlapPolicy.effectiveMemoryBudget(
                requestedBudgetBytes = ConversionParallelismPolicy.DEFAULT_MEMORY_BUDGET_BYTES,
                maxMemoryBytes = deviceHeapBytes
            )
        )
        assertTrue(
            RecordDevelopPipelineOverlapPolicy.canFitOneFrame(
                residentBytesPerFrame = resident,
                fixedReserveBytes = temporalReserveBytes,
                memoryBudgetBytes = guardedBudgetBytes
            )
        )
        assertEquals(
            1,
            RecordDevelopPipelineOverlapPolicy.frameCapacity(
                residentBytesPerFrame = resident,
                fixedReserveBytes = temporalReserveBytes,
                memoryBudgetBytes = guardedBudgetBytes
            )
        )
    }

    @Test
    fun `full-size GPU parity peak is shed when CPU reference still fits`() {
        val cpuResident = RecordDevelopMovConverter.estimateResidentBytesPerFrame(
            width = width,
            height = height,
            enhanceEnabled = true,
            outputFormat = RecordDevelopOutputFormat.R210_444,
            gpuDevelopAvailable = false,
            packedParityDiagnosticsEnabled = false
        )
        val gpuResident = RecordDevelopMovConverter.estimateResidentBytesPerFrame(
            width = width,
            height = height,
            enhanceEnabled = true,
            outputFormat = RecordDevelopOutputFormat.R210_444,
            gpuDevelopAvailable = true,
            packedParityDiagnosticsEnabled = false
        )

        assertEquals(168_579_072L, gpuResident)
        assertTrue(cpuResident + temporalReserveBytes <= guardedBudgetBytes)
        assertFalse(gpuResident + temporalReserveBytes <= guardedBudgetBytes)
    }

    @Test
    fun `diagnostic parity accounting includes both packed samples and default packer codes`() {
        val normal = RecordDevelopMovConverter.estimateResidentBytesPerFrame(
            width = width,
            height = height,
            enhanceEnabled = true,
            outputFormat = RecordDevelopOutputFormat.R210_444,
            gpuDevelopAvailable = true,
            packedParityDiagnosticsEnabled = false
        )
        val diagnostic = RecordDevelopMovConverter.estimateResidentBytesPerFrame(
            width = width,
            height = height,
            enhanceEnabled = true,
            outputFormat = RecordDevelopOutputFormat.R210_444,
            gpuDevelopAvailable = true,
            packedParityDiagnosticsEnabled = true
        )
        val r210Bytes = width.toLong() * height.toLong() * 4L
        val rgb10Codes = width.toLong() * height.toLong() * 3L * Short.SIZE_BYTES

        assertEquals(r210Bytes * 2L + rgb10Codes, diagnostic - normal)
    }

    @Test
    fun `HEVC estimate also accounts for the full enhancement peak`() {
        val resident = RecordDevelopMovConverter.estimateResidentBytesPerFrameForHevc(
            width = width,
            height = height,
            enhanceEnabled = true,
            gpuDevelopAvailable = false,
            packedParityDiagnosticsEnabled = false
        )

        assertEquals(119_854_080L, resident)
        assertTrue(resident + temporalReserveBytes <= guardedBudgetBytes)
    }
}
