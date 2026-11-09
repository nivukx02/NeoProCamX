package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StrictHevcAdmissionIntegrationContractTest {
    @Test
    fun recordDevelopRejectsUnsupportedHevcBeforeWriterAndWorkers() {
        val source = source("RecordDevelopMovConverter.kt")
        val formatValidation = source.indexOf("require(fmt.storageBitDepth == 10)")
        val compressedGuard = source.indexOf("if (deliveryFormat.compressed)")
        val preflight = source.indexOf("RecordDevelopHevcMovEncoder.requireStrictCapability")
        val writer = source.indexOf("val writer = QuickTimeMovFileWriter")
        val workers = source.indexOf("runHybridHevcDevelop(")

        assertOrdered(formatValidation, compressedGuard, preflight, writer, workers)
    }

    @Test
    fun npv10RejectsUnsupportedHevcBeforeWriterAndWorkers() {
        val source = source("Npv10MovConverter.kt")
        val headerValidation = source.indexOf("validateMovConvertible(header)")
        val hevcOnlyGuard = source.indexOf("if (outputFormat == OutputFormat.HEVC_MAIN10_420)")
        val preflight = source.indexOf("RecordDevelopHevcMovEncoder.requireStrictCapability")
        val writer = source.indexOf("val writer = QuickTimeMovFileWriter")
        val workers = source.indexOf("runParallelCpuExport(")

        assertOrdered(headerValidation, hevcOnlyGuard, preflight, writer, workers)
    }

    private fun source(name: String): String =
        File("src/main/java/com/neoproc/neoproccamx/recording/$name").readText()

    private fun assertOrdered(vararg offsets: Int) {
        assertTrue(
            "Expected every strict-admission integration marker to exist: ${offsets.toList()}",
            offsets.all { it >= 0 }
        )
        assertTrue(
            "Strict HEVC admission must precede writer/worker creation: ${offsets.toList()}",
            offsets.asList().zipWithNext().all { (left, right) -> left < right }
        )
    }
}
