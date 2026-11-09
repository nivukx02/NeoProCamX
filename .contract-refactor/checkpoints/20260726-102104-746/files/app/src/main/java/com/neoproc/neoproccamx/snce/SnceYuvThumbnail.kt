package com.neoproc.neoproccamx.snce

import kotlin.math.pow

/**
 * Builds a small linear-RGB [SnceThumbnail] from a downsampled YUV_420 analysis frame so the
 * SNCE engine can run on the CPU off the render thread. Handles 8-bit and 10-bit (P010-style,
 * 10 bits MSB-aligned in 16) planes. All sampling is clamped/guarded; returns null on bad input
 * (no chroma) rather than throwing.
 */
object SnceYuvThumbnail {

    /** Decoupled view of a pipeline RawYuvFrame (so this package stays independent of pipeline). */
    class Frame(
        val width: Int,
        val height: Int,
        val yBytes: ByteArray,
        val uBytes: ByteArray,
        val vBytes: ByteArray,
        val yRowStride: Int,
        val yPixelStride: Int,
        val uRowStride: Int,
        val vRowStride: Int,
        val uPixelStride: Int,
        val vPixelStride: Int,
        val bitDepth: Int = 8
    )

    fun fromYuv(frame: Frame, targetSize: Int): SnceThumbnail? {
        if (frame.width <= 0 || frame.height <= 0) return null
        if (frame.uBytes.isEmpty() || frame.vBytes.isEmpty()) return null
        val size = targetSize.coerceIn(8, 64)
        val bytesPerSample = if (frame.bitDepth > 8) 2 else 1
        val maxVal = if (bytesPerSample == 2) 1023f else 255f
        // Chroma neutral is the integer mid-point (128 for 8-bit, 512 for 10-bit), NOT maxVal/2,
        // so a truly neutral frame maps to exactly u=v=0 and produces no spurious tint.
        val chromaCenter = if (bytesPerSample == 2) 512f else 128f

        val rgb = FloatArray(size * size * 3)
        var idx = 0
        for (ty in 0 until size) {
            val sy = (ty * frame.height / size).coerceIn(0, frame.height - 1)
            val cy = sy / 2
            for (tx in 0 until size) {
                val sx = (tx * frame.width / size).coerceIn(0, frame.width - 1)
                val cx = sx / 2
                val y = sample(frame.yBytes, sy * frame.yRowStride + sx * frame.yPixelStride, bytesPerSample) / maxVal
                val u = (sample(frame.uBytes, cy * frame.uRowStride + cx * frame.uPixelStride, bytesPerSample) - chromaCenter) / maxVal
                val v = (sample(frame.vBytes, cy * frame.vRowStride + cx * frame.vPixelStride, bytesPerSample) - chromaCenter) / maxVal

                // BT.709 full-range YUV -> RGB (exact matrix is immaterial for chromaticity stats).
                val r = (y + 1.5748f * v).coerceIn(0f, 1f)
                val g = (y - 0.1873f * u - 0.4681f * v).coerceIn(0f, 1f)
                val b = (y + 1.8556f * u).coerceIn(0f, 1f)

                rgb[idx++] = srgbToLinear(r)
                rgb[idx++] = srgbToLinear(g)
                rgb[idx++] = srgbToLinear(b)
            }
        }
        return SnceThumbnail(size, size, rgb)
    }

    private fun sample(data: ByteArray, index: Int, bytesPerSample: Int): Float {
        if (index < 0 || index >= data.size) return 0f
        return if (bytesPerSample == 2) {
            if (index + 1 >= data.size) return 0f
            val lo = data[index].toInt() and 0xFF
            val hi = data[index + 1].toInt() and 0xFF
            // little-endian 16-bit; P010 keeps the 10 significant bits in the MSBs.
            (((hi shl 8) or lo) ushr 6).toFloat()
        } else {
            (data[index].toInt() and 0xFF).toFloat()
        }
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    /**
     * Builds a thumbnail from a packed P010 payload (semi-planar 10-bit-in-16: Y plane of
     * width*height samples, then interleaved U,V at half resolution). Chroma neutral is code 512,
     * matching neo_p010_export_enhance.comp. Returns null on a short/invalid payload.
     */
    fun fromP010Payload(payload: ByteArray, width: Int, height: Int, targetSize: Int): SnceThumbnail? {
        if (width <= 0 || height <= 0) return null
        val size = targetSize.coerceIn(8, 64)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val yPlaneSamples = width * height
        val totalSamples = yPlaneSamples + chromaWidth * chromaHeight * 2
        if (payload.size < totalSamples * 2) return null

        val rgb = FloatArray(size * size * 3)
        var idx = 0
        for (ty in 0 until size) {
            val sy = (ty * height / size).coerceIn(0, height - 1)
            val cy = (sy / 2).coerceIn(0, chromaHeight - 1)
            for (tx in 0 until size) {
                val sx = (tx * width / size).coerceIn(0, width - 1)
                val cx = (sx / 2).coerceIn(0, chromaWidth - 1)
                val ySample = sampleP010(payload, sy * width + sx)
                val chromaBase = yPlaneSamples + (cy * chromaWidth + cx) * 2
                val u = (sampleP010(payload, chromaBase) - 512f) / 1023f
                val v = (sampleP010(payload, chromaBase + 1) - 512f) / 1023f
                val y = ySample / 1023f
                val r = (y + 1.5748f * v).coerceIn(0f, 1f)
                val g = (y - 0.1873f * u - 0.4681f * v).coerceIn(0f, 1f)
                val b = (y + 1.8556f * u).coerceIn(0f, 1f)
                rgb[idx++] = srgbToLinear(r)
                rgb[idx++] = srgbToLinear(g)
                rgb[idx++] = srgbToLinear(b)
            }
        }
        return SnceThumbnail(size, size, rgb)
    }

    private fun sampleP010(payload: ByteArray, sampleIndex: Int): Float {
        val b = sampleIndex * 2
        if (b < 0 || b + 1 >= payload.size) return 0f
        val lo = payload[b].toInt() and 0xFF
        val hi = payload[b + 1].toInt() and 0xFF
        return (((hi shl 8) or lo) ushr 6).toFloat()
    }
}
