package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawDevelopEliteDetailPolicy
import com.neoproc.neoproccamx.pipeline.RawExposureNormalizationPolicy

/**
 * Packs the RAW->NeoLog converter's shared LINEAR ACEScg seam directly into the final MOV sample.
 *
 * This intentionally mirrors RawNeoLogDevelop.finishFromAcescg() + RawTenBitPacker.pack(), but avoids
 * materialising the full interleaved 10-bit RGB ShortArray. For a 4096x3072 frame that removes about
 * 72 MiB of Java heap without changing the NeoLog curve, dither, elite-detail pass, or container layout.
 */
internal object RawAcescgTenBitPacker {
    private const val MAX10 = 1023
    private const val QUANTIZE_DITHER_LSB = 0.5f

    private const val KR = 0.2627
    private const val KB = 0.0593
    private const val KG = 1.0 - KR - KB

    private const val LUMA_MIN = 64.0
    private const val LUMA_MAX = 940.0
    private const val CHROMA_MAX = 960.0
    private const val CHROMA_ZERO = 512.0

    fun pack(
        acescg: FloatArray,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        outputBitDepth: Int = 10,
        format: RawTenBitPacker.Format
    ): ByteArray {
        require(outputBitDepth == 10) { "Raw MOV packing requires 10-bit output" }
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(acescg.size >= width * height * 3) {
            "acescg plane too small for ${width}x$height"
        }
        RawDevelopEliteDetailPolicy.enhanceInPlace(acescg, width, height)
        return when (format) {
            RawTenBitPacker.Format.R210_444 -> packR210(acescg, width, height, variant, exposureStops)
            RawTenBitPacker.Format.V210_422 -> packV210(acescg, width, height, variant, exposureStops)
        }
    }

    private fun packR210(
        acescg: FloatArray,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        exposureStops: Float
    ): ByteArray {
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, width)
        val out = ByteArray(stride * height)
        val expScale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        for (y in 0 until height) {
            var o = y * stride
            val row = y * width
            for (x in 0 until width) {
                val i = (row + x) * 3
                val r = quantize10(acescg[i], expScale, variant, i)
                val g = quantize10(acescg[i + 1], expScale, variant, i + 1)
                val b = quantize10(acescg[i + 2], expScale, variant, i + 2)
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

    private fun packV210(
        acescg: FloatArray,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        exposureStops: Float
    ): ByteArray {
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, width)
        val out = ByteArray(stride * height)
        val expScale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        val luma = IntArray(width)
        val cb = IntArray(width)
        val cr = IntArray(width)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = (row + x) * 3
                val rf = quantize10(acescg[i], expScale, variant, i) / MAX10.toDouble()
                val gf = quantize10(acescg[i + 1], expScale, variant, i + 1) / MAX10.toDouble()
                val bf = quantize10(acescg[i + 2], expScale, variant, i + 2) / MAX10.toDouble()
                val yp = KR * rf + KG * gf + KB * bf
                luma[x] = clamp10((yp * (LUMA_MAX - LUMA_MIN) + LUMA_MIN).toInt())
                val cbv = (bf - yp) / (2.0 * (1.0 - KB))
                val crv = (rf - yp) / (2.0 * (1.0 - KR))
                cb[x] = clamp10((cbv * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO).toInt())
                cr[x] = clamp10((crv * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO).toInt())
            }
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

    private fun quantize10(
        linearAcescg: Float,
        expScale: Float,
        variant: NeoLogCurve.Variant,
        index: Int
    ): Int {
        val scaled = linearAcescg * expScale
        val base = NeoLogCurve.quantize(scaled, variant, 10)
        return if (base in 1 until MAX10) {
            NeoLogCurve.quantize(scaled, variant, 10, QUANTIZE_DITHER_LSB * tpdfUnit(index))
        } else {
            base
        }
    }

    private fun writeWordLe(out: ByteArray, o: Int, word: Int) {
        out[o] = word.toByte()
        out[o + 1] = (word ushr 8).toByte()
        out[o + 2] = (word ushr 16).toByte()
        out[o + 3] = (word ushr 24).toByte()
    }

    private fun clamp10(v: Int): Int = if (v < 0) 0 else if (v > MAX10) MAX10 else v

    private fun tpdfUnit(index: Int): Float = hashUnit(index * 2) - hashUnit(index * 2 + 1)

    private fun hashUnit(n: Int): Float {
        var h = n * -0x61c88647
        h = h xor (h ushr 16)
        h *= -0x7ee3623b
        h = h xor (h ushr 13)
        h *= -0x3361d2af
        h = h xor (h ushr 16)
        return (h ushr 8).toFloat() / (1 shl 24).toFloat()
    }
}
