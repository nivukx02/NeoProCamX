package com.neoproc.neoproccamx.recording

import java.io.Closeable

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-THEN-DEVELOP — Phase 2 decode-reader seam
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The develop converter ([RecordDevelopMovConverter]) decodes the recorded HEVC `.mov` intermediate into
 * P010 frames through THIS interface, never against a concrete `MediaCodec`. That dependency direction is
 * the whole point of the seam (mirrors [EliteVisionDecodedFrameProvider] /
 * [EliteVisionMediaCodecDecodeProvider]):
 *
 *   • the real Android decode ([RecordDevelopMediaCodecP010Reader]) carries `MediaExtractor` / `MediaCodec`
 *     / `ImageReader` and is DEVICE-ONLY — `MediaCodec` is not mockable and Robolectric is banned, so it is
 *     never constructed in the pure-JVM unit suite; and
 *   • [InMemoryP010FrameReader] feeds hand-built canonical P010 frames so the entire converter loop
 *     (parallel develop, ordering, backpressure, v210/r210 packing, colour, audit) is exercised on the JVM.
 *
 * FRAME CONTRACT — every [RecordDevelopP010Frame.p010] is the CANONICAL MSB-justified P010 payload the rest
 * of the codebase expects (16-bit little-endian samples, the 10 valid bits in [15:6], low 6 bits zero):
 *   • luma  = `width * height` samples, row-major (full resolution);
 *   • chroma = interleaved Cb,Cr pairs at `((width+1)/2) * ((height+1)/2)` half-resolution positions,
 *     laid out immediately after the luma plane.
 * This is exactly the layout [RecordDevelopP010ColorIngress] samples and that
 * [com.neoproc.neoproccamx.recording.Npv10MovConverter]'s P010 helpers use, so the device decoder must
 * normalise a vendor `Image` into it (see [RecordDevelopMediaCodecP010Reader]).
 */
internal interface RecordDevelopP010FrameReader : Closeable {
    /** Geometry + timing of the decoded stream. Available before the first [readNextFrame]. */
    val format: RecordDevelopDecodedFormat

    /** Pull the next decoded frame in presentation order, or null at end-of-stream. */
    fun readNextFrame(): RecordDevelopP010Frame?
}

/**
 * Decoded-stream geometry. [storageBitDepth] is the decoded sample bit depth (10 for P010); it is checked
 * against the sidecar and the develop rejects anything but 10-bit.
 */
internal data class RecordDevelopDecodedFormat(
    val width: Int,
    val height: Int,
    val fps: Int,
    val storageBitDepth: Int = 10
)

/**
 * One decoded frame. [index] is the 0-based decode order; [presentationTimeUs] is the container PTS (≤0
 * ⇒ the converter derives an even cadence from [RecordDevelopDecodedFormat.fps]).
 */
internal data class RecordDevelopP010Frame(
    val index: Long,
    val presentationTimeUs: Long,
    val width: Int,
    val height: Int,
    val p010: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordDevelopP010Frame) return false
        return index == other.index &&
            presentationTimeUs == other.presentationTimeUs &&
            width == other.width &&
            height == other.height &&
            p010.contentEquals(other.p010)
    }

    override fun hashCode(): Int {
        var result = index.hashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + p010.contentHashCode()
        return result
    }
}

/**
 * Pure JVM reader over an in-memory list of canonical P010 frames — the unit-test seam (mirrors
 * [EliteVisionByteBufferFrameProvider] living in `main/` with no Android import). Yields frames in list
 * order exactly once; [close] is a no-op.
 */
internal class InMemoryP010FrameReader(
    frames: List<RecordDevelopP010Frame>,
    override val format: RecordDevelopDecodedFormat
) : RecordDevelopP010FrameReader {
    private val iterator = frames.iterator()

    override fun readNextFrame(): RecordDevelopP010Frame? =
        if (iterator.hasNext()) iterator.next() else null

    override fun close() { /* nothing to release */ }
}
