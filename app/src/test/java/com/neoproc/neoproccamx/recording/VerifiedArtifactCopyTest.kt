package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class VerifiedArtifactCopyTest {
    @Test
    fun copiedBytesAndIndependentReadBackProduceIdenticalEvidence() {
        val source = ByteArray(1_100_003) { index -> (index * 31).toByte() }
        val output = ByteArrayOutputStream()

        val copyEvidence = VerifiedArtifactCopy.copyAndMeasure(
            ByteArrayInputStream(source),
            output
        )
        val readBackEvidence = VerifiedArtifactCopy.measure(ByteArrayInputStream(output.toByteArray()))

        VerifiedArtifactCopy.requireExact(copyEvidence, readBackEvidence, "fixture")
        assertEquals(source.size.toLong(), copyEvidence.bytes)
        assertTrue(source.contentEquals(output.toByteArray()))
    }

    @Test(expected = IllegalStateException::class)
    fun oneByteDestinationCorruptionIsRejected() {
        val source = ByteArray(64) { it.toByte() }
        val output = ByteArrayOutputStream()
        val copyEvidence = VerifiedArtifactCopy.copyAndMeasure(ByteArrayInputStream(source), output)
        val corrupted = output.toByteArray().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }

        VerifiedArtifactCopy.requireExact(
            copyEvidence,
            VerifiedArtifactCopy.measure(ByteArrayInputStream(corrupted)),
            "corrupt fixture"
        )
    }

    @Test
    fun cancellationStopsCopyBeforeAnyPartialArtifactCanBeCommitted() {
        val source = ByteArray(RecordDevelopPublishCopyPolicy.DEFAULT_COPY_BUFFER_BYTES * 3) { it.toByte() }
        val checks = AtomicInteger(0)
        val failure = runCatching {
            VerifiedArtifactCopy.copyAndMeasure(
                ByteArrayInputStream(source),
                ByteArrayOutputStream()
            ) { checks.incrementAndGet() < 3 }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(checks.get() >= 3)
    }
}
