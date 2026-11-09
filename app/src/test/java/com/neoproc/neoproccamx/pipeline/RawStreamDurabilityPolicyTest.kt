package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.RecordingPreallocationMethod
import com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy
import com.neoproc.neoproccamx.recording.RecordingPreallocationResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawStreamDurabilityPolicyTest {
    private val durable = RecordingPreallocationResult(
        plan = RecordingPreallocationPolicy.raw(20L * 1024L * 1024L * 1024L),
        method = RecordingPreallocationMethod.POSIX_FALLOCATE,
        reservedBytes = 10L * 1024L * 1024L * 1024L,
        committedBytes = 4096L,
        reservationTrimmed = true,
        synced = true
    )

    @Test
    fun `only an exact durable zero loss stream can publish`() {
        assertTrue(decision().publicationSafe)
        assertFalse(decision(preallocation = durable.copy(synced = false)).publicationSafe)
        assertFalse(decision(preallocation = durable.copy(committedBytes = 4095L)).publicationSafe)
        assertFalse(decision(writeOps = 0L).publicationSafe)
        assertFalse(decision(droppedWrites = 1L).publicationSafe)
        assertFalse(decision(applicationDroppedFrames = 1).publicationSafe)
        assertFalse(decision(queuedWrites = 1).publicationSafe)
        assertFalse(decision(activeWrites = 1).publicationSafe)
        assertFalse(decision(persistedFrames = 0).publicationSafe)
    }

    @Test
    fun `strict stream requires physical reservation while durable non strict stream remains valid`() {
        val noReservation = durable.copy(
            method = RecordingPreallocationMethod.NONE,
            reservedBytes = 0L
        )
        assertFalse(decision(preallocation = noReservation).publicationSafe)
        assertTrue(
            decision(
                preallocation = noReservation,
                physicalReservationRequired = false
            ).publicationSafe
        )
    }

    @Test
    fun `descriptor retirement and durability proof precede MediaStore publication`() {
        val engine = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/AsyncDirectIOEngine.kt"
        ).readText()
        val manager = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        ).readText()
        val close = engine.substringAfter("override fun close()").substringBefore("private fun awaitWriterThreadTermination")
        val stop = manager.substringAfter("private fun stopCaptureLocked()").substringBefore("fun pauseCapture")

        assertTrue(close.contains("check(awaitWriterThreadTermination())"))
        assertTrue(close.indexOf("check(awaitWriterThreadTermination())") < close.indexOf("finalizeCommittedLength()"))
        assertFalse(engine.contains("closing fd anyway"))
        assertTrue(engine.contains("recordDurabilityFailure(\"Unable to finalize RAW committed length"))
        assertTrue(stop.contains("RawStreamDurabilityPolicy.evaluate("))
        assertTrue(stop.indexOf("RawStreamDurabilityPolicy.evaluate(") < stop.indexOf("publishPendingBinary(it)"))
        assertTrue(stop.contains("NPRAW publication refused"))
    }

    private fun decision(
        writerPresent: Boolean = true,
        physicalReservationRequired: Boolean = true,
        preallocation: RecordingPreallocationResult = durable,
        persistedFrames: Int = 1,
        bytesWritten: Long = 4096L,
        writeOps: Long = 1L,
        droppedWrites: Long = 0L,
        applicationDroppedFrames: Int = 0,
        queuedWrites: Int = 0,
        activeWrites: Int = 0
    ) = RawStreamDurabilityPolicy.evaluate(
        writerPresent = writerPresent,
        physicalReservationRequired = physicalReservationRequired,
        preallocation = preallocation,
        persistedFrames = persistedFrames,
        bytesWritten = bytesWritten,
        writeOps = writeOps,
        droppedWrites = droppedWrites,
        applicationDroppedFrames = applicationDroppedFrames,
        queuedWrites = queuedWrites,
        activeWrites = activeWrites
    )
}
