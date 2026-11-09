package com.neoproc.neoproccamx.recording

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * One atomic publication set for offline conversion artifacts.
 *
 * Rows remain pending until every byte has been independently read back and hash-verified. Cancellation,
 * low storage, write failure, verification failure, or publication failure rolls back the complete set,
 * including rows already made visible by a partially completed commit.
 */
internal class TransactionalMediaStoreArtifactSet(
    private val resolver: ContentResolver,
    private val availableBytes: () -> Long = ConversionStorageAdmissionPolicy::externalAvailableBytes,
    private val backgroundIo: RecordingPublicationIoGate? = null
) : AutoCloseable {
    data class StagedArtifact(
        val uri: Uri,
        val label: String,
        val evidence: VerifiedArtifactCopyEvidence
    )

    private val createdUris = mutableListOf<Uri>()
    private val staged = mutableListOf<StagedArtifact>()
    private var terminal = false

    fun stageFile(
        collection: Uri,
        values: ContentValues,
        source: File,
        label: String,
        cancellation: ConversionCancellationSignal
    ): StagedArtifact {
        require(source.isFile && source.length() > 0L) { "$label source is missing or empty" }
        cancellation.throwIfCancellationRequested()
        ConversionStorageAdmissionPolicy.requireAdmission(availableBytes(), source.length(), label)
        val uri = insertPending(collection, values, label)
        return try {
            val sourceEvidence = resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().buffered().use { input ->
                    VerifiedArtifactCopy.copyAndMeasure(
                        input, output, cancellation::shouldContinue, backgroundIo
                    )
                }
            } ?: error("Failed to open pending MediaStore output for $label")
            check(sourceEvidence.bytes == source.length()) {
                "$label source length changed during publication: expected=${source.length()} " +
                    "copied=${sourceEvidence.bytes}"
            }
            verifyReadBack(uri, sourceEvidence, label, cancellation)
        } catch (failure: Throwable) {
            rollback(failure)
            throw failure
        }
    }

    fun stageGenerated(
        collection: Uri,
        values: ContentValues,
        expectedMaximumBytes: Long,
        label: String,
        cancellation: ConversionCancellationSignal,
        writer: (OutputStream) -> Unit
    ): StagedArtifact {
        cancellation.throwIfCancellationRequested()
        ConversionStorageAdmissionPolicy.requireAdmission(
            availableBytes(),
            expectedMaximumBytes.coerceAtLeast(1L),
            label
        )
        val uri = insertPending(collection, values, label)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var measuredBytes = 0L
            resolver.openOutputStream(uri, "w")?.use { rawOutput ->
                val counting = object : OutputStream() {
                    override fun write(value: Int) {
                        cancellation.throwIfCancellationRequested()
                        rawOutput.write(value)
                        measuredBytes = Math.addExact(measuredBytes, 1L)
                    }

                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        cancellation.throwIfCancellationRequested()
                        rawOutput.write(buffer, offset, length)
                        measuredBytes = Math.addExact(measuredBytes, length.toLong())
                    }

                    override fun flush() = rawOutput.flush()
                }
                DigestOutputStream(counting, digest).use(writer)
                rawOutput.flush()
            } ?: error("Failed to open pending MediaStore output for $label")
            check(measuredBytes > 0L) { "$label generated an empty artifact" }
            val sourceEvidence = VerifiedArtifactCopyEvidence(measuredBytes, digest.digest().toHex())
            verifyReadBack(uri, sourceEvidence, label, cancellation)
        } catch (failure: Throwable) {
            rollback(failure)
            throw failure
        }
    }

    fun commit(cancellation: ConversionCancellationSignal): List<Uri> {
        check(!terminal) { "MediaStore artifact transaction is already terminal" }
        check(staged.isNotEmpty()) { "Refusing to commit an empty MediaStore artifact transaction" }
        return try {
            staged.forEach { artifact ->
                cancellation.throwIfCancellationRequested()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updated = resolver.update(
                        artifact.uri,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1_000L)
                        },
                        null,
                        null
                    )
                    check(updated > 0) { "MediaStore did not publish ${artifact.label}" }
                }
            }
            cancellation.throwIfCancellationRequested()
            terminal = true
            staged.map(StagedArtifact::uri)
        } catch (failure: Throwable) {
            rollback(failure)
            throw failure
        }
    }

    override fun close() {
        if (terminal || createdUris.isEmpty()) return
        rollback(null)
    }

    private fun insertPending(collection: Uri, values: ContentValues, label: String): Uri {
        check(!terminal) { "MediaStore artifact transaction is already terminal" }
        val pendingValues = ContentValues(values).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, pendingValues)
            ?: error("Failed to create pending MediaStore row for $label")
        createdUris += uri
        return uri
    }

    private fun verifyReadBack(
        uri: Uri,
        sourceEvidence: VerifiedArtifactCopyEvidence,
        label: String,
        cancellation: ConversionCancellationSignal
    ): StagedArtifact {
        cancellation.throwIfCancellationRequested()
        val destinationEvidence = resolver.openInputStream(uri)?.buffered()?.use { input ->
            VerifiedArtifactCopy.measure(input, cancellation::shouldContinue, backgroundIo)
        } ?: error("Failed to read back pending MediaStore output for $label")
        VerifiedArtifactCopy.requireExact(sourceEvidence, destinationEvidence, label)
        return StagedArtifact(uri, label, destinationEvidence).also(staged::add)
    }

    private fun rollback(primaryFailure: Throwable?) {
        if (terminal) return
        terminal = true
        createdUris.asReversed().distinct().forEach { uri ->
            runCatching {
                val deleted = resolver.delete(uri, null, null)
                check(deleted > 0) { "MediaStore did not roll back artifact $uri" }
            }.exceptionOrNull()?.let { cleanupFailure ->
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure)
                else throw cleanupFailure
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
