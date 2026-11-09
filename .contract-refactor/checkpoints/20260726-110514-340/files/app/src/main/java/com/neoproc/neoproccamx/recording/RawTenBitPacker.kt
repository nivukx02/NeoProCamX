package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop.DevelopedFrame

/**
 * RawTenBitPacker — packs a developed 10-bit RGB frame into an uncompressed 10-bit QuickTime sample
 * payload, in either output format the RAW→NeoLog converter offers:
 *
 *  • [Format.R210_444] — 'r210', 4:4:4 10-bit RGB, no chroma subsampling. The MASTER format: bit-exact
 *    to the develop (no colour conversion, no subsampling). Big-endian uint32 per pixel
 *    (bits 29-20 = R, 19-10 = G, 9-0 = B), rows aligned to 256 bytes (64-pixel groups).
 *
 *  • [Format.V210_422] — 'v210', 4:2:2 10-bit Y′CbCr (BT.2020, limited range). The COMPATIBILITY
 *    format: universally decodable in Resolve/Premiere/FCP, ~⅓ smaller. 6 pixels → 4 little-endian
 *    uint32 words (16 bytes), rows aligned to 128 bytes (48-pixel groups). Getting this alignment and
 *    word order exactly right is what avoids the "undecodable v210" failure.
 *
 * Pure, allocation-bounded, and byte-deterministic so the layout is unit-testable without a device.
 */
object RawTenBitPacker {

    enum class Format { R210_444, V210_422 }

    private const val MAX10 = 1023

    // BT.2020 non-constant-luminance luma coefficients.
    private const val KR = 0.2627
    private const val KB = 0.0593
    private const val KG = 1.0 - KR - KB

    // 10-bit limited ("video") range endpoints.
    private const val LUMA_MIN = 64.0
    private const val LUMA_MAX = 940.0
    private const val CHROMA_MIN = 64.0
    private const val CHROMA_MAX = 960.0
    private const val CHROMA_ZERO = 512.0

    fun rowBytes(format: Format, width: Int): Int = when (format) {
        Format.R210_444 -> ((width + 63) / 64) * 256
        Format.V210_422 -> ((width + 47) / 48) * 128
    }

    fun payloadSize(format: Format, width: Int, height: Int): Int = rowBytes(format, width) * height

    fun pack(frame: DevelopedFrame, format: Format): ByteArray =
        when (format) {
            Format.R210_444 -> packR210(frame.rgb, frame.width, frame.height)
            Format.V210_422 -> packV210(frame.rgb, frame.width, frame.height)
        }

    // ── r210 (4:4:4 RGB) ─────────────────────────────────────────────────────────

    fun packR210(rgb: ShortArray, width: Int, height: Int): ByteArray {
        val stride = rowBytes(Format.R210_444, width)
        val out = ByteArray(stride * height)
        for (y in 0 until height) {
            var o = y * stride
            val row = y * width * 3
            for (x in 0 until width) {
                val i = row + x * 3
                val r = clamp10(rgb[i].toInt())
                val g = clamp10(rgb[i + 1].toInt())
                val b = clamp10(rgb[i + 2].toInt())
                val word = (r shl 20) or (g shl 10) or b
                out[o] = (word ushr 24).toByte()
                out[o + 1] = (word ushr 16).toByte()
                out[o + 2] = (word ushr 8).toByte()
                out[o + 3] = word.toByte()
                o += 4
            }
        }
        return out
    }

    // ── v210 (4:2:2 Y′CbCr) ──────────────────────────────────────────────────────

    fun packV210(rgb: ShortArray, width: Int, height: Int): ByteArray {
        val stride = rowBytes(Format.V210_422, width)
        val out = ByteArray(stride * height)
        val luma = IntArray(width)
        val cb = IntArray(width)
        val cr = IntArray(width)
        for (y in 0 until height) {
            // 1. RGB → full-res 10-bit limited-range Y′CbCr.
            val row = y * width * 3
            for (x in 0 until width) {
                val i = row + x * 3
                val rf = clamp10(rgb[i].toInt()) / MAX10.toDouble()
                val gf = clamp10(rgb[i + 1].toInt()) / MAX10.toDouble()
                val bf = clamp10(rgb[i + 2].toInt()) / MAX10.toDouble()
                val yp = KR * rf + KG * gf + KB * bf
                luma[x] = clamp10((yp * (LUMA_MAX - LUMA_MIN) + LUMA_MIN).toInt())
                val cbv = (bf - yp) / (2.0 * (1.0 - KB))
                val crv = (rf - yp) / (2.0 * (1.0 - KR))
                cb[x] = clamp10((cbv * (CHROMA_MAX - CHROMA_MIN) + CHROMA_ZERO).toInt())
                cr[x] = clamp10((crv * (CHROMA_MAX - CHROMA_MIN) + CHROMA_ZERO).toInt())
            }
            // 2. Pack 6 pixels → 4 LE uint32 words (16 bytes); chroma 4:2:2 co-sited at even columns.
            var o = y * stride
            var x = 0
            while (x < width) {
                val y0 = luma[x]
                val y1 = luma.getOrElse(x + 1) { y0 }
                val y2 = luma.getOrElse(x + 2) { y1 }
                val y3 = luma.getOrElse(x + 3) { y2 }
                val y4 = luma.getOrElse(x + 4) { y3 }
                val y5 = luma.getOrElse(x + 5) { y4 }
                val cb0 = cb[x]; val cr0 = cr[x]
                val cb2 = cb.getOrElse(x + 2) { cb0 }; val cr2 = cr.getOrElse(x + 2) { cr0 }
                val cb4 = cb.getOrElse(x + 4) { cb2 }; val cr4 = cr.getOrElse(x + 4) { cr2 }
                writeWordLe(out, o, (cr0 shl 20) or (y0 shl 10) or cb0); o += 4
                writeWordLe(out, o, (y2 shl 20) or (cb2 shl 10) or y1); o += 4
                writeWordLe(out, o, (cb4 shl 20) or (y3 shl 10) or cr2); o += 4
                writeWordLe(out, o, (y5 shl 20) or (cr4 shl 10) or y4); o += 4
                x += 6
            }
        }
        return out
    }

    private fun writeWordLe(out: ByteArray, o: Int, word: Int) {
        out[o] = word.toByte()
        out[o + 1] = (word ushr 8).toByte()
        out[o + 2] = (word ushr 16).toByte()
        out[o + 3] = (word ushr 24).toByte()
    }

    private fun clamp10(v: Int): Int = if (v < 0) 0 else if (v > MAX10) MAX10 else v
}
