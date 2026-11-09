package com.neoproc.neoproccamx.recording

import android.media.MediaMetadataRetriever
import android.os.StatFs
import java.io.File

internal object RecordDevelopOutputEstimatePolicy {
    fun estimateOutputBytes(source: File, outputFormat: RecordDevelopOutputFormat): Long = runCatching {
        estimateOutputBytes(source, RecordDevelopDeliveryFormat.fromLegacyOutput(outputFormat))
    }.getOrDefault(0L)

    fun estimateOutputBytes(source: File, deliveryFormat: RecordDevelopDeliveryFormat): Long = runCatching {
        val geometry = Regex("""(\d{3,5})x(\d{3,5})""").find(source.name)
        val width = geometry?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@runCatching 0L
        val height = geometry.groupValues.getOrNull(2)?.toIntOrNull() ?: return@runCatching 0L
        val fps = Regex("""(\d{1,3})fps""")
            .find(source.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 25
        val durationMs = MediaMetadataRetriever().useMetadata(source) {
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }?.coerceAtLeast(1L) ?: 1L
        val frames = ((durationMs * fps) + 999L) / 1000L
        val uncompressed = deliveryFormat.uncompressedFormat
        if (uncompressed != null) {
            RawTenBitPacker.payloadSize(uncompressed.packerFormat, width, height).toLong() * frames
        } else {
            // High-quality HEVC Main10 delivery estimate. Real output depends on scene complexity and encoder.
            val bitsPerPixel = 1.15
            ((width.toLong() * height.toLong() * frames * bitsPerPixel) / 8.0).toLong()
        }
    }.getOrDefault(0L)

    fun usableBytes(path: File): Long = runCatching {
        path.mkdirs()
        StatFs(path.absolutePath).availableBytes
    }.getOrDefault(-1L)

    private inline fun <R> MediaMetadataRetriever.useMetadata(
        file: File,
        block: MediaMetadataRetriever.() -> R
    ): R {
        return try {
            setDataSource(file.absolutePath)
            block()
        } finally {
            runCatching { release() }
        }
    }
}
