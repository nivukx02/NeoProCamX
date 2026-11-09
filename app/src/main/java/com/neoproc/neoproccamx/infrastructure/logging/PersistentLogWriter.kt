package com.neoproc.neoproccamx.infrastructure.logging

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Appends complete log batches through one buffered descriptor per active file.
 *
 * The logger owns this class from a single low-priority storage lane. Keeping the descriptor open
 * avoids repeated external-storage/FUSE open and close transactions during camera preview.
 */
internal class PersistentLogWriter(
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
    private val outputStreamFactory: (File) -> OutputStream = { file ->
        FileOutputStream(file, true)
    }
) : Closeable {
    private var activeFile: File? = null
    private var byteCounter: CountingOutputStream? = null
    private var writer: BufferedWriter? = null

    /**
     * Writes and flushes one already-serialized batch, returning the resulting file size in bytes.
     */
    fun append(file: File, serializedBatch: String): Long {
        require(serializedBatch.isNotEmpty()) { "A log batch must not be empty" }
        ensureOpen(file)

        val activeWriter = checkNotNull(writer)
        activeWriter.write(serializedBatch)
        activeWriter.flush()
        return checkNotNull(byteCounter).totalBytes
    }

    override fun close() {
        val activeWriter = writer
        writer = null
        byteCounter = null
        activeFile = null
        activeWriter?.close()
    }

    private fun ensureOpen(file: File) {
        val absoluteFile = file.absoluteFile
        if (writer != null && activeFile == absoluteFile) return

        close()
        absoluteFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                error("Unable to create log directory: ${parent.absolutePath}")
            }
        }

        val initialSize = if (absoluteFile.exists()) absoluteFile.length() else 0L
        val counter = CountingOutputStream(
            delegate = outputStreamFactory(absoluteFile),
            initialBytes = initialSize
        )
        byteCounter = counter
        writer = BufferedWriter(
            OutputStreamWriter(counter, StandardCharsets.UTF_8),
            bufferSizeBytes
        )
        activeFile = absoluteFile
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
        initialBytes: Long
    ) : FilterOutputStream(delegate) {
        var totalBytes: Long = initialBytes
            private set

        override fun write(value: Int) {
            delegate.write(value)
            totalBytes++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            totalBytes += length
        }
    }

    private companion object {
        private const val DEFAULT_BUFFER_SIZE_BYTES = 64 * 1024
    }
}
