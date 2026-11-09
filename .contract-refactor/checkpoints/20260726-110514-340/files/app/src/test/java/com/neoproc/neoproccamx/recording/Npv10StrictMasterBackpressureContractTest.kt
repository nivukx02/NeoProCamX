package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * s6-f04 strict-master no-silent-drop contract (test-only). The live FAIL_STRICT_MASTER path does
 * NOT throw — it drops + errors + returns WITHOUT writing the frame; this proves an overflow frame
 * can never be written into the master, and that a master built from the in-budget frames has no
 * hole and a clean session hash.
 */
class Npv10StrictMasterBackpressureContractTest {

    private val managerSource: String = listOf(
        "src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt",
        "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
    ).map(::File).firstOrNull { it.exists() }!!.readText()

    @Test
    fun managerOverflowBranchDropsAndErrorsWithoutWritingFrame() {
        val overflowBranch = managerSource
            .substringAfter("Npv10FrameBackpressureAction.FAIL_STRICT_MASTER")
            .substringBefore("writerExecutor.execute")
        val failureHelper = managerSource
            .substringAfter("private fun failStrictMasterBacklog()")
            .substringBefore("@Synchronized\n    fun pause()")
        assertTrue(overflowBranch.contains("pendingFrames.decrementAndGet()"))
        assertTrue(overflowBranch.contains("droppedFrames.incrementAndGet()"))
        assertTrue(overflowBranch.contains("failStrictMasterBacklog()"))
        assertTrue(failureHelper.contains("GpuRecordingState.Error("))
        assertTrue(overflowBranch.contains("return"))
        // The overflow frame is never written: writeFrame is not reachable in the strict branch.
        assertFalse(overflowBranch.contains("activeWriter.writeFrame"))
    }

    @Test
    fun writerThreadUsesUrgentDisplayPriorityForSustainedPrivateMasterWrites() {
        assertTrue(managerSource.contains("Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)"))
    }

    @Test
    fun transientWriterPressureBlocksForCapacityBeforeStrictFailure() {
        assertTrue(managerSource.contains("Npv10InFlightBufferPolicy.pendingFrames(config.width, config.height)"))
        assertTrue(managerSource.contains("Semaphore(activeMaxPendingFrames, true)"))
        assertTrue(managerSource.contains("frameSlots.tryAcquire("))
        assertTrue(managerSource.contains("PENDING_FRAME_BACKPRESSURE_TIMEOUT_MS"))
        assertTrue(managerSource.contains("frameSlots.release()"))
        assertTrue(managerSource.contains("after bounded backpressure"))
    }

    @Test
    fun masterBuiltWithInBudgetFramesHasNoHoleAndCleanHash() {
        // Mirror the manager withholding the overflow frame: build a master from only the frames
        // that were accepted; the result must be a complete, hash-verifiable master (no partial/hole).
        val root = Files.createTempDirectory("npv10-strict-nohole").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            exactSelectedResolution = true
        )
        val master = Npv10MasterStore(root).createMasterFile(config)
        val writer = Npv10FileWriter(master, config)
        writer.use {
            it.writeFrame(frame(1_000_000L))
            it.writeFrame(frame(42_666_666L))
            it.writeFrame(frame(84_333_333L))
        }
        val audit = Npv10QualityGate.auditMasterFile(
            masterFile = master,
            expectedFrameCount = writer.frameCount,
            expectedPayloadBytes = writer.payloadBytesWritten
        )
        assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
        assertEquals(3L, audit.frameCount)
        assertTrue(audit.sessionHashCheckPass)
        assertTrue(audit.byteCountValid)
        root.deleteRecursively()
    }

    private fun frame(timestamp: Long) =
        com.neoproc.neoproccamx.pipeline.RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = tenBitPlane(16, 32, 64, 128),
            uBytes = tenBitPlane(512),
            vBytes = tenBitPlane(512),
            yRowStride = 4,
            yPixelStride = 2,
            uRowStride = 2,
            vRowStride = 2,
            uPixelStride = 2,
            vPixelStride = 2,
            timestamp = timestamp,
            bitDepth = 10
        )

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
