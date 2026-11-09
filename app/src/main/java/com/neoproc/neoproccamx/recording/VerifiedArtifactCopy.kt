package com.neoproc.neoproccamx.recording

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Byte-count and SHA-256 evidence for a finalized artifact copy. */
internal data class VerifiedArtifactCopyEvidence(
    val bytes: Long,
    val sha256: String
)

/**
 * Copies with the established large-buffer path and then supports an independent read-back measurement.
 * Equality of both measurements proves that MediaStore contains the already-inspected source bytes.
 */
internal object VerifiedArtifactCopy {
    fun copyAndMeasure(
        input: InputStream,
        output: OutputStream,
        shouldContinue: () -> Boolean = { true }
    ): VerifiedArtifactCopyEvidence = copyAndMeasure(input, output, shouldContinue, null)

    fun copyAndMeasure(
        input: InputStream,
        output: OutputStream,
        shouldContinue: () -> Boolean,
        backgroundIo: RecordingPublicationIoGate?
    ): VerifiedArtifactCopyEvidence {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(
            if (backgroundIo == null) RecordDevelopPublishCopyPolicy.bufferBytes
            else minOf(RecordDevelopPublishCopyPolicy.bufferBytes, RecordingPublicationIoGate.MAX_BACKGROUND_CHUNK_BYTES)
        )
        var bytes = 0L
        while (true) {
            if (!shouldContinue()) throw CancellationException("Artifact copy cancelled")
            val read = ioChunk(backgroundIo, shouldContinue) {
                input.read(buffer).also { count ->
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        bytes = Math.addExact(bytes, count.toLong())
                    }
                }
            }
            if (read < 0) break
            if (read == 0) continue
        }
        ioChunk(backgroundIo, shouldContinue) { output.flush() }
        return VerifiedArtifactCopyEvidence(bytes, digest.digest().toHex())
    }

    fun measure(
        input: InputStream,
        shouldContinue: () -> Boolean = { true }
    ): VerifiedArtifactCopyEvidence = measure(input, shouldContinue, null)

    fun measure(
        input: InputStream,
        shouldContinue: () -> Boolean,
        backgroundIo: RecordingPublicationIoGate?
    ): VerifiedArtifactCopyEvidence {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(MEASURE_BUFFER_BYTES)
        var bytes = 0L
        while (true) {
            if (!shouldContinue()) throw CancellationException("Artifact verification cancelled")
            val read = ioChunk(backgroundIo, shouldContinue) {
                input.read(buffer).also { count ->
                    if (count > 0) {
                        digest.update(buffer, 0, count)
                        bytes = Math.addExact(bytes, count.toLong())
                    }
                }
            }
            if (read < 0) break
            if (read == 0) continue
        }
        return VerifiedArtifactCopyEvidence(bytes, digest.digest().toHex())
    }

    fun requireExact(
        source: VerifiedArtifactCopyEvidence,
        destination: VerifiedArtifactCopyEvidence,
        label: String
    ) {
        check(source == destination) {
            "$label copy verification failed: source=$source destination=$destination"
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun <T> ioChunk(
        gate: RecordingPublicationIoGate?,
        shouldContinue: () -> Boolean,
        operation: () -> T
    ): T = if (gate == null) operation() else gate.backgroundChunk(shouldContinue, operation)

    private const val MEASURE_BUFFER_BYTES = 1 * 1024 * 1024
}
