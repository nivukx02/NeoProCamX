package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingMuxerBackpressureLifecycleContractTest {
    @Test
    fun `strict muxer admission is deadline and condition based rather than an infinite retry loop`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RecordingMuxerSampleQueue.kt"
        ).readText()

        assertTrue(source.contains("maxStrictBackpressureWaitMs"))
        assertTrue(source.contains("System.nanoTime()"))
        assertTrue(source.contains("pendingByteBudgetChanged.awaitNanos(remainingNs)"))
        assertTrue(source.contains("STRICT_BACKPRESSURE_TIMEOUT"))
        assertTrue(source.contains("resetGeneration"))
        assertTrue(source.contains("awaitPacket(expectedGeneration"))
        assertTrue(source.contains("expectedGeneration != resetGeneration"))
        assertFalse(source.contains("while (true)"))
    }

    @Test
    fun `recording manager closes admission first and turns queue or writer failure into session error`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt"
        ).readText()

        assertTrue(source.contains("retireMuxerWriter(reason = \"release\")"))
        assertTrue(source.contains("recordMuxerTermination(muxerSampleQueue.close(), reason = reason)"))
        assertTrue(source.contains("expectedGeneration = writerGeneration"))
        assertTrue(source.contains("catch (e: CancellationException)"))
        assertTrue(source.contains("scheduleMuxerTerminalFailureCleanup"))
        assertTrue(source.contains("RecordingMuxerQueueFailureReason.STRICT_BACKPRESSURE_TIMEOUT"))
        assertTrue(source.contains("_state.value = GpuRecordingState.Error(message)"))
        assertTrue(source.contains("strictNoDrop = true"))
    }

    @Test
    fun `backend finalization retires muxer writer before salvage close or abort`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt"
        ).readText()

        val retireIndex = source.indexOf("retireMuxerWriter(reason = \"stop_finalize\")")
        val finalizeIndex = source.indexOf("// Finalize the active recording container.")
        assertTrue(retireIndex >= 0)
        assertTrue(finalizeIndex > retireIndex)
        assertTrue(source.contains("muxerSampleQueue.isCurrentGeneration(writerGeneration)"))
    }
}
