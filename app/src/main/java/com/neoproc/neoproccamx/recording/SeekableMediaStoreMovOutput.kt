package com.neoproc.neoproccamx.recording

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transactional seekable MOV destination backed by a pending MediaStore item.
 *
 * QuickTime requires backward seeks to finalize `mdat`/`moov`. Opening `/proc/self/fd/<fd>` gives the
 * existing file-based writers that capability without staging and copying the complete MOV a second
 * time. [openOrNull] performs a real seek/truncate preflight; unsupported providers return null before
 * conversion starts and retain the established cache-file fallback.
 */
internal class SeekableMediaStoreMovOutput private constructor(
    private val resolver: ContentResolver,
    val uri: Uri,
    private val descriptor: ParcelFileDescriptor,
    val outputFile: File,
    val displayName: String,
    private val pending: Boolean
) : AutoCloseable {
    private val committed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    /** Stable per-row identity for diagnostics/certificates; unlike displayName it cannot collide. */
    val transactionId: String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    fun lengthBytes(): Long {
        check(!closed.get()) { "MediaStore MOV destination is closed" }
        return Os.fstat(descriptor.fileDescriptor).st_size.coerceAtLeast(0L)
    }

    fun commit(): Uri {
        ConversionCancellationContext.throwIfCancellationRequested()
        check(!closed.get()) { "MediaStore MOV destination is already closed" }
        check(!committed.get()) { "MediaStore MOV destination is already committed" }
        try {
            Os.fsync(descriptor.fileDescriptor)
            val bytes = lengthBytes()
            check(bytes > 0L) { "Refusing to publish an empty MOV" }
            descriptor.close()
            if (pending) {
                ConversionCancellationContext.throwIfCancellationRequested()
                val updated = resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1_000L)
                    },
                    null,
                    null
                )
                check(updated > 0) { "Failed to publish pending MediaStore MOV" }
            }
            committed.set(true)
            closed.set(true)
            return uri
        } catch (failure: Throwable) {
            runCatching { descriptor.close() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            runCatching { deletePendingRowStrict() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            closed.set(true)
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val cleanupFailures = buildList {
            runCatching { descriptor.close() }.exceptionOrNull()?.let(::add)
            if (!committed.get()) {
                runCatching { deletePendingRowStrict() }.exceptionOrNull()?.let(::add)
            }
        }
        if (cleanupFailures.isNotEmpty()) {
            val failure = IllegalStateException("Failed to roll back pending MediaStore MOV $uri")
            cleanupFailures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun deletePendingRowStrict() {
        val deletedRows = resolver.delete(uri, null, null)
        check(deletedRows > 0) { "MediaStore did not delete pending MOV $uri" }
    }

    companion object {
        fun openOrNull(
            resolver: ContentResolver,
            displayName: String,
            relativePath: String = "${Environment.DIRECTORY_MOVIES}/CamX",
            expectedOutputBytes: Long? = null
        ): SeekableMediaStoreMovOutput? {
            ConversionCancellationContext.throwIfCancellationRequested()
            ConversionStorageAdmissionPolicy.requireAdmission(
                availableBytes = ConversionStorageAdmissionPolicy.externalAvailableBytes(),
                expectedOutputBytes = expectedOutputBytes?.coerceAtLeast(0L) ?: 0L,
                label = "direct MediaStore MOV"
            )
            val nowMs = System.currentTimeMillis()
            val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/quicktime")
                put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
                put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1_000L)
                put(MediaStore.Video.Media.DATE_MODIFIED, nowMs / 1_000L)
                if (pending) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            var descriptor: ParcelFileDescriptor? = null
            return try {
                descriptor = resolver.openFileDescriptor(uri, "rw")
                    ?: error("MediaStore did not provide a seekable file descriptor")
                val procFile = File("/proc/self/fd/${descriptor.fd}")
                // Exercise the exact path and operations used by QuickTimeMovFileWriter before expensive
                // decode/develop starts. This prevents an unsupported provider from causing a double run.
                RandomAccessFile(procFile, "rw").use { probe ->
                    probe.setLength(0L)
                    probe.seek(0L)
                }
                SeekableMediaStoreMovOutput(
                    resolver = resolver,
                    uri = uri,
                    descriptor = descriptor,
                    outputFile = procFile,
                    displayName = displayName,
                    pending = pending
                )
            } catch (openFailure: Throwable) {
                val cleanupFailures = buildList {
                    runCatching { descriptor?.close() }.exceptionOrNull()?.let(::add)
                    runCatching {
                        val deletedRows = resolver.delete(uri, null, null)
                        check(deletedRows > 0) { "MediaStore did not delete failed MOV row $uri" }
                    }.exceptionOrNull()?.let(::add)
                }
                if (cleanupFailures.isNotEmpty()) {
                    cleanupFailures.forEach(openFailure::addSuppressed)
                    throw IllegalStateException(
                        "Failed to roll back unsupported MediaStore MOV destination $uri",
                        openFailure
                    )
                }
                null
            }
        }
    }
}
