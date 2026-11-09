package com.neoproc.neoproccamx.recording

import java.io.File

/**
 * Npv10ThumbnailRenderer — renders a small "identify this clip" thumbnail from an NPV10 master's
 * FIRST frame. NPV10 masters are the compressed 10-bit LOG/HDR video masters (P010 / planar YUV420
 * 10-bit, or a raw-packed Bayer mosaic); this decodes the first frame and downscales it to an ARGB
 * image the settings clip row can wrap in a Bitmap.
 *
 * The YUV→RGB path uses a limited-range BT.709 conversion — approximate for the LOG/HDR content
 * (whose luma is log-encoded), but more than enough to visually distinguish clips. Raw-packed
 * formats fall back to a normalized grayscale of the mosaic. Any failure returns null (the row then
 * shows a text-only label), so a thumbnail never blocks the converter list.
 *
 * The reader uses plain File I/O — safe because NPV10 masters live in app-private storage
 * (filesDir), which is fully readable without scoped-storage indirection.
 */
object Npv10ThumbnailRenderer {

    const val DEFAULT_MAX_LONG_EDGE = 256

    fun render(file: File, maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE): ThumbnailImage? = try {
        Npv10FileReader(file).use { reader ->
            val header = reader.header
            val frame = reader.readNextFrame()
            if (frame == null) null else renderFrame(header, frame, maxLongEdge)
        }
    } catch (t: Throwable) {
        null
    }

    private fun renderFrame(
        header: Npv10FileHeader,
        frame: Npv10FrameRecord,
        maxLongEdge: Int
    ): ThumbnailImage {
        val w = frame.width
        val h = frame.height
        val payload = frame.payload
        val longEdge = maxOf(w, h)
        val scale = if (longEdge <= maxLongEdge) 1 else (longEdge + maxLongEdge - 1) / maxLongEdge
        val dstW = maxOf(1, w / scale)
        val dstH = maxOf(1, h / scale)
        val out = IntArray(dstW * dstH)

        val format = header.payloadFormat
        val ySize = w * h
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val cbCrPlane = ySize + cw * ch

        for (y in 0 until dstH) {
            val sy = (y * scale).coerceAtMost(h - 1)
            for (x in 0 until dstW) {
                val sx = (x * scale).coerceAtMost(w - 1)
                out[y * dstW + x] = when {
                    format.isRawPacked -> {
                        // NPV10 raw-packed words store the sample LEFT-shifted by 6 (the P010
                        // valid-bits shift, per Npv10FramePacker), so the top 10 bits are the
                        // high-order sample. Reading the raw word directly would saturate to white;
                        // normalize the top-10-bit value instead (works for both RAW12 and RAW14).
                        val hi10 = (sample16(payload, sy * w + sx) ushr 6).coerceIn(0, 1023)
                        val g8 = (hi10 * 255) / 1023
                        (0xFF shl 24) or (g8 shl 16) or (g8 shl 8) or g8
                    }
                    format == Npv10PayloadFormat.P010_LE -> {
                        val yv = sample16(payload, sy * w + sx) ushr 6
                        val cIndex = cbCrBase(ySize, (sx / 2), (sy / 2), cw)
                        val cb = sample16(payload, cIndex) ushr 6
                        val cr = sample16(payload, cIndex + 1) ushr 6
                        yuvToArgb(yv, cb, cr)
                    }
                    else -> { // planar YUV420 (10-bit or 8-bit-expanded)
                        val yv = sample16(payload, sy * w + sx) and 0x3FF
                        val ci = (sy / 2) * cw + (sx / 2)
                        val cb = sample16(payload, ySize + ci) and 0x3FF
                        val cr = sample16(payload, cbCrPlane + ci) and 0x3FF
                        yuvToArgb(yv, cb, cr)
                    }
                }
            }
        }
        return ThumbnailImage(dstW, dstH, out)
    }

    /** Interleaved P010 CbCr pair base sample index for a chroma coordinate. */
    private fun cbCrBase(ySize: Int, cx: Int, cy: Int, cw: Int): Int = ySize + (cy * cw + cx) * 2

    private fun sample16(payload: ByteArray, sampleIndex: Int): Int {
        val o = sampleIndex * 2
        if (o < 0 || o + 1 >= payload.size) return 0
        return (payload[o].toInt() and 0xFF) or ((payload[o + 1].toInt() and 0xFF) shl 8)
    }

    /** Limited-range 10-bit BT.709 YCbCr → 8-bit sRGB-ish ARGB. Pure, unit-testable. */
    fun yuvToArgb(y10: Int, cb10: Int, cr10: Int): Int {
        val yl = ((y10.coerceIn(0, 1023) - 64).toFloat()) / 876f
        val uf = ((cb10.coerceIn(0, 1023) - 512).toFloat()) / 896f
        val vf = ((cr10.coerceIn(0, 1023) - 512).toFloat()) / 896f
        val r = yl + 1.5748f * vf
        val g = yl - 0.1873f * uf - 0.4681f * vf
        val b = yl + 1.8556f * uf
        val r8 = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val g8 = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b8 = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
    }
}
