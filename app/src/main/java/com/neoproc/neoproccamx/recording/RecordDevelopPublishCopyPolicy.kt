package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopPublishCopyPolicy — the byte buffer size used when the finished uncompressed develop `.mov`
 * (R210 4:4:4 / V210 4:2:2, multiple GB) is copied from its app-cache temp file into the final MediaStore
 * entry for the gallery.
 *
 * WHY THIS EXISTS: the publish step streams the whole file with `InputStream.copyTo(OutputStream)`, which
 * defaults to an **8 KiB** buffer. Copying a ~3.5 GB uncompressed clip through 8 KiB chunks into a
 * MediaStore/FUSE-backed output stream means ~450k tiny writes across the FUSE boundary — the dominant
 * cost of the "convert takes forever" tail (the develop compute itself is GPU/multi-core; the mux write is
 * a single large syscall per frame). Copying with a large buffer transfers the SAME bytes in far fewer,
 * bigger writes, so it is a pure throughput win with a BYTE-IDENTICAL output file — no quality, format, or
 * functional change whatsoever.
 *
 * NO-DOWNGRADE / REVERSIBLE: default ON with a large buffer; set [bufferBytes] (e.g. via
 * `debug.record_develop_publish_copy_kb`) back to [DEFAULT_COPY_BUFFER_BYTES] or to 8 KiB to reproduce the
 * original behaviour exactly. The value only changes how the copy is chunked, never the bytes written.
 */
object RecordDevelopPublishCopyPolicy {

    /** 8 MiB — large enough to make MediaStore/FUSE writes efficient, tiny next to a multi-GB clip. */
    const val DEFAULT_COPY_BUFFER_BYTES: Int = 8 * 1024 * 1024

    /** Never smaller than the JDK default (8 KiB) so we never regress below the stock copy chunk. */
    const val MIN_COPY_BUFFER_BYTES: Int = 8 * 1024

    /** Guard against an accidental huge allocation from a bad debug value. */
    const val MAX_COPY_BUFFER_BYTES: Int = 64 * 1024 * 1024

    @Volatile
    var bufferBytes: Int = DEFAULT_COPY_BUFFER_BYTES
        private set

    /** Set the copy buffer size (coerced into the safe range). Reversible; process-global. */
    fun set(bytes: Int) {
        bufferBytes = bytes.coerceIn(MIN_COPY_BUFFER_BYTES, MAX_COPY_BUFFER_BYTES)
    }

    /** Restore the default large buffer. */
    fun reset() {
        bufferBytes = DEFAULT_COPY_BUFFER_BYTES
    }

    /**
     * Stream [input] → [output] using the current large [bufferBytes] chunk. Byte-for-byte identical to
     * `input.copyTo(output)`, only chunked bigger so a multi-GB MediaStore/FUSE publish isn't throttled by
     * ~450k tiny 8 KiB writes. Returns the number of bytes copied.
     */
    fun copyWithLargeBuffer(input: java.io.InputStream, output: java.io.OutputStream): Long =
        input.copyTo(output, bufferBytes)
}
