package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.pipeline.RawExposureNormalizationPolicy
import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop

/** A small opaque ARGB_8888 image (row-major [argb] ints) ready to hand to Bitmap.createBitmap. */
data class ThumbnailImage(val width: Int, val height: Int, val argb: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThumbnailImage) return false
        return width == other.width && height == other.height && argb.contentEquals(other.argb)
    }
    override fun hashCode(): Int = (width * 31 + height) * 31 + argb.contentHashCode()
}

/**
 * Raw12ThumbnailRenderer — renders a small "identify this clip" thumbnail from a RAW clip's FIRST
 * frame by running the exact NeoLog develop and downscaling to an ARGB image. Pure (no Android), so
 * the develop→downscale is unit-testable; the caller wraps [ThumbnailImage.argb] in a Bitmap
 * (Bitmap.createBitmap(argb, w, h, ARGB_8888)).
 *
 * The develop runs at 10-bit (the NeoLog quantiser supports 10/12/16 only) and is shifted to 8-bit
 * for display. Large mosaics are Bayer-preserving downsampled before develop so opening Settings does
 * not allocate a full-resolution RGB float working set.
 */
object Raw12ThumbnailRenderer {

    const val DEFAULT_MAX_LONG_EDGE = 256

    fun render(
        firstFrameSamples: IntArray,
        meta: RawDevelopMetadata,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
        variant: NeoLogCurve.Variant = NeoLogCurve.Variant.BASE,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS
    ): ThumbnailImage {
        require(maxLongEdge >= 2) { "RAW thumbnail maxLongEdge must be at least 2" }
        val reduced = reduceBayerForThumbnail(firstFrameSamples, meta, maxLongEdge)
        val developed = RawNeoLogDevelop.develop(
            reduced.samples,
            reduced.meta,
            variant,
            exposureStops,
            outputBitDepth = 10
        )
        val out = IntArray(developed.width * developed.height)
        for (y in 0 until developed.height) {
            for (x in 0 until developed.width) {
                val i = (y * developed.width + x) * 3
                // 10-bit code -> 8-bit channel for display.
                val r = (developed.rgb[i].toInt() and 0x3FF) shr 2
                val g = (developed.rgb[i + 1].toInt() and 0x3FF) shr 2
                val b = (developed.rgb[i + 2].toInt() and 0x3FF) shr 2
                out[y * developed.width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return ThumbnailImage(developed.width, developed.height, out)
    }

    internal data class ReducedBayer(
        val samples: IntArray,
        val meta: RawDevelopMetadata
    )

    internal fun reduceBayerForThumbnail(
        samples: IntArray,
        meta: RawDevelopMetadata,
        maxLongEdge: Int
    ): ReducedBayer {
        require(samples.size >= meta.width * meta.height) { "RAW thumbnail frame is truncated" }
        val longEdge = maxOf(meta.width, meta.height)
        if (longEdge <= maxLongEdge) return ReducedBayer(samples, meta)

        val scale = (longEdge + maxLongEdge - 1) / maxLongEdge
        val reducedWidth = (meta.width / scale).coerceAtLeast(2).and(-2)
        val reducedHeight = (meta.height / scale).coerceAtLeast(2).and(-2)
        val reduced = IntArray(reducedWidth * reducedHeight)
        val cellStride = scale * 2
        for (y in 0 until reducedHeight) {
            val sourceY = ((y / 2) * cellStride + (y and 1)).coerceAtMost(meta.height - 1)
            for (x in 0 until reducedWidth) {
                val sourceX = ((x / 2) * cellStride + (x and 1)).coerceAtMost(meta.width - 1)
                reduced[y * reducedWidth + x] = samples[sourceY * meta.width + sourceX]
            }
        }
        return ReducedBayer(
            samples = reduced,
            meta = meta.copy(width = reducedWidth, height = reducedHeight)
        )
    }
}
