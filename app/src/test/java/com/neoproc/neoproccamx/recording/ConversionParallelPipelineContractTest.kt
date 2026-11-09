package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Contract for the CPU+GPU parallel offline converters: the NPV10 CPU-parallel export is
 * order-preserving and deterministic (byte-identical run-to-run despite concurrent workers), the fast
 * NPRAW frame counter matches the real frame count (so the ETA total is exact), and the source keeps
 * both the GPU-sequential path and the bounded CPU-parallel fallback.
 */
class ConversionParallelPipelineContractTest {

    @Test
    fun npv10ParallelCpuExportIsDeterministicAndOrderedAcrossRuns() {
        // On the JVM the native Vulkan accelerator is absent, so this exercises the CPU-parallel path.
        val src = Files.createTempFile("npv10-det-src", ".npv10").toFile()
        writeDistinctNpv10Master(src, frames = 6)
        val outA = Files.createTempFile("npv10-det-a", ".mov").toFile()
        val outB = Files.createTempFile("npv10-det-b", ".mov").toFile()

        val progress = mutableListOf<Int>()
        val auditA = Npv10MovConverter.convertWithAudit(src, outA, onFrame = { progress.add(it) })
        val auditB = Npv10MovConverter.convertWithAudit(src, outB)

        assertEquals(6L, auditA.frameCount)
        assertEquals(6L, auditB.frameCount)
        assertEquals(Npv10AuditStatus.PASS, auditA.auditStatus)
        // Progress fires once per frame, in monotonically increasing order (reorder buffer + writer).
        assertEquals((1..6).toList(), progress)
        // The muxer zeroes header timestamps, so identical inputs must yield byte-identical outputs —
        // proving the concurrent CPU workers neither reorder frames nor introduce nondeterminism.
        assertArrayEquals(
            "parallel CPU export must be byte-identical run to run",
            Files.readAllBytes(outA.toPath()),
            Files.readAllBytes(outB.toPath())
        )
    }

    @Test
    fun nprawFrameCounterMatchesActualFrameCount() {
        val w = 4
        val h = 4
        val stream = ByteArrayOutputStream().apply {
            repeat(7) { f ->
                write(
                    NprawFrameCodec.encodeFrame(
                        mode = RawCompressionMode.HIGH_COMPRESSION,
                        rawSamples = IntArray(w * h) { 100 + it + f },
                        width = w, height = h, rowStride = w * 2, pixelStride = 2,
                        timestamp = (f + 1).toLong(), originalSize = w * h * 2, sensorWhiteLevel = 1000
                    ).bytes
                )
            }
        }.toByteArray()
        assertEquals(7, NprawFrameCodec.countFrames(ByteArrayInputStream(stream)))
        assertEquals(0, NprawFrameCodec.countFrames(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun sourceKeepsGpuSequentialPathAndAddsBoundedCpuParallelFallback() {
        val npv10 = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val raw = File("src/main/java/com/neoproc/neoproccamx/recording/RawToNeoLogMovConverter.kt").readText()
        val codec = File("src/main/java/com/neoproc/neoproccamx/pipeline/NprawFrameCodec.kt").readText()

        assertTrue(npv10.contains("newFixedThreadPoolContext(1, \"npv10-gpu\")"))
        assertTrue(npv10.contains("newFixedThreadPoolContext(workers, \"npv10-cpu\")"))
        assertTrue(npv10.contains("runParallelCpuExport"))
        assertTrue(npv10.contains("ConversionParallelismPolicy.workerCount"))
        assertTrue(raw.contains("fun convertStreamParallel"))
        assertTrue(codec.contains("fun readRawFrameBytes"))
        assertTrue(codec.contains("fun countFrames"))
    }

    @Test
    fun rawParallelGpuDecisionIsResolvedFromFrameZeroBeforeWorkersStart() {
        val raw = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RawToNeoLogMovConverter.kt"
        ).readText()
        val parallelBody = raw.substringAfter("internal fun convertStreamParallelTimed")
            .substringBefore("private fun <T> awaitNext")
        val firstRead = parallelBody.indexOf("val firstBytes")
        val parityDecision = parallelBody.indexOf("resolveGpuPath(firstPrepared")
        val workerPool = parallelBody.indexOf("newFixedThreadPool")

        assertTrue("parallel converter must read chronological frame zero", firstRead >= 0)
        assertTrue("frame-zero parity decision must happen before worker scheduling", parityDecision > firstRead)
        assertTrue("workers must start only after frame-zero parity is fixed", workerPool > parityDecision)
        assertTrue("the scheduling-dependent lazy decision state must be absent", !raw.contains("GpuDecisionState"))
    }

    private fun writeDistinctNpv10Master(file: File, frames: Int) {
        Npv10FileWriter(
            outputFile = file,
            config = Npv10SessionConfig(
                width = 2,
                height = 2,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE,
                startedAtEpochMs = 1L
            )
        ).use { writer ->
            for (f in 0 until frames) {
                writer.writeFrame(
                    RawYuvFrame(
                        width = 2,
                        height = 2,
                        yBytes = tenBitPlane(40 + f, 80 + f, 120 + f, 160 + f),
                        uBytes = tenBitPlane(500 + f * 7),
                        vBytes = tenBitPlane(520 - f * 7),
                        yRowStride = 4,
                        yPixelStride = 2,
                        uRowStride = 2,
                        vRowStride = 2,
                        uPixelStride = 2,
                        vPixelStride = 2,
                        timestamp = 1_000_000_000L + f * 41_666_667L,
                        bitDepth = 10
                    )
                )
            }
        }
    }

    /** 10-bit little-endian planar samples (matches Npv10FileWriter's RawYuvFrame plane layout). */
    private fun tenBitPlane(vararg samples: Int): ByteArray {
        val output = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.coerceIn(0, 1023)
            output[index * 2] = (value and 0xFF).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0x03).toByte()
        }
        return output
    }
}
