package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawExposureNormalizationPolicy
import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop.DevelopedFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Locks continuous NeoLog RGB -> YCbCr -> one final deterministic 10-bit quantization. */
class RawAcescgSingleQuantizationTest {
    private val width = 11
    private val height = 3
    private val variant = NeoLogCurve.Variant.CINEMA
    private val exposureStops = 0.35f
    private val source = FloatArray(width * height * 3) { component ->
        val pixel = component / 3
        when (component % 3) {
            0 -> 0.001f + ((pixel * 37 + 11) % 101) / 37f
            1 -> 0.002f + ((pixel * 19 + 7) % 89) / 43f
            else -> 0.0005f + ((pixel * 53 + 3) % 107) / 41f
        }
    }

    @Test
    fun p010MatchesContinuousRgbSingleFinalQuantizationAndIsDeterministic() {
        val first = RawAcescgTenBitPacker.packP010420(
            acescg = source.copyOf(),
            width = width,
            height = height,
            variant = variant,
            exposureStops = exposureStops,
            detailAlreadyApplied = true
        )
        val second = RawAcescgTenBitPacker.packP010420(
            acescg = source.copyOf(),
            width = width,
            height = height,
            variant = variant,
            exposureStops = exposureStops,
            detailAlreadyApplied = true
        )

        assertArrayEquals(referenceP010(), first)
        assertArrayEquals("TPDF must be deterministic for reproducible conversion", first, second)
        first.indices.step(2).forEach { offset ->
            val word = (first[offset].toInt() and 0xFF) or
                ((first[offset + 1].toInt() and 0xFF) shl 8)
            assertTrue("P010 low padding bits must remain zero", word and 0x3F == 0)
        }
    }

    @Test
    fun v210MatchesContinuousRgbSingleFinalQuantizationInsteadOfLegacyRgbPrequantization() {
        val actual = RawAcescgTenBitPacker.pack(
            acescg = source.copyOf(),
            width = width,
            height = height,
            variant = variant,
            exposureStops = exposureStops,
            outputBitDepth = 10,
            format = RawTenBitPacker.Format.V210_422,
            detailAlreadyApplied = true
        )

        assertArrayEquals(referenceV210(), actual)
        assertFalse(
            "V210 must not reintroduce the legacy RGB-quantize then YCbCr-quantize path",
            legacyDoubleQuantizedV210().contentEquals(actual)
        )
    }

    @Test
    fun v210AveragesSaturatedAlternatingColorAcrossEachRealChromaPair() {
        val alternatingWidth = 6
        val alternating = FloatArray(alternatingWidth * 3) { component ->
            val pixel = component / 3
            when {
                pixel % 2 == 0 && component % 3 == 0 -> 1f
                pixel % 2 == 1 && component % 3 == 2 -> 1f
                else -> 0f
            }
        }
        val payload = RawAcescgTenBitPacker.pack(
            acescg = alternating,
            width = alternatingWidth,
            height = 1,
            variant = NeoLogCurve.Variant.BASE,
            exposureStops = 0f,
            outputBitDepth = 10,
            format = RawTenBitPacker.Format.V210_422,
            detailAlreadyApplied = true
        )
        val red = floatArrayOf(
            NeoLogCurve.encode(1f, NeoLogCurve.Variant.BASE),
            NeoLogCurve.encode(0f, NeoLogCurve.Variant.BASE),
            NeoLogCurve.encode(0f, NeoLogCurve.Variant.BASE)
        )
        val blue = floatArrayOf(
            NeoLogCurve.encode(0f, NeoLogCurve.Variant.BASE),
            NeoLogCurve.encode(0f, NeoLogCurve.Variant.BASE),
            NeoLogCurve.encode(1f, NeoLogCurve.Variant.BASE)
        )
        val redY = luma(red[0], red[1], red[2])
        val blueY = luma(blue[0], blue[1], blue[2])
        val averageCb = (
            (red[2] - redY) / (2.0 * (1.0 - KB)) +
                (blue[2] - blueY) / (2.0 * (1.0 - KB))
            ) / 2.0
        val averageCr = (
            (red[0] - redY) / (2.0 * (1.0 - KR)) +
                (blue[0] - blueY) / (2.0 * (1.0 - KR))
            ) / 2.0
        val firstWord = readWordLe(payload, 0)

        assertEquals(
            finalQuantize(averageCb * CHROMA_SPAN + CHROMA_ZERO, alternatingWidth),
            firstWord and MAX10
        )
        assertEquals(
            finalQuantize(averageCr * CHROMA_SPAN + CHROMA_ZERO, alternatingWidth + 1),
            (firstWord ushr 20) and MAX10
        )
    }

    private fun encodedNeoLog(): FloatArray {
        val scale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        return FloatArray(source.size) { component ->
            NeoLogCurve.encode(source[component] * scale, variant)
        }
    }

    private fun referenceP010(): ByteArray {
        val encoded = encodedNeoLog()
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = width * height * Short.SIZE_BYTES
        val out = ByteArray(lumaBytes + chromaWidth * chromaHeight * 2 * Short.SIZE_BYTES)

        for (pixel in 0 until width * height) {
            val i = pixel * 3
            val y = luma(encoded[i], encoded[i + 1], encoded[i + 2])
            writeP010(out, pixel * Short.SIZE_BYTES, finalQuantize(y * LUMA_SPAN + LUMA_MIN, pixel))
        }

        for (cy in 0 until chromaHeight) {
            for (cx in 0 until chromaWidth) {
                var cb = 0.0
                var cr = 0.0
                var count = 0
                for (dy in 0..1) {
                    val y = cy * 2 + dy
                    if (y >= height) continue
                    for (dx in 0..1) {
                        val x = cx * 2 + dx
                        if (x >= width) continue
                        val i = (y * width + x) * 3
                        val yp = luma(encoded[i], encoded[i + 1], encoded[i + 2])
                        cb += (encoded[i + 2] - yp) / (2.0 * (1.0 - KB))
                        cr += (encoded[i] - yp) / (2.0 * (1.0 - KR))
                        count++
                    }
                }
                val chromaSample = cy * chromaWidth + cx
                val ditherIndex = width * height + chromaSample * 2
                val offset = lumaBytes + chromaSample * 2 * Short.SIZE_BYTES
                writeP010(
                    out,
                    offset,
                    finalQuantize(cb / count * CHROMA_SPAN + CHROMA_ZERO, ditherIndex)
                )
                writeP010(
                    out,
                    offset + Short.SIZE_BYTES,
                    finalQuantize(cr / count * CHROMA_SPAN + CHROMA_ZERO, ditherIndex + 1)
                )
            }
        }
        return out
    }

    private fun referenceV210(): ByteArray {
        val encoded = encodedNeoLog()
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, width)
        val out = ByteArray(stride * height)
        val luma = IntArray(width)
        val cb = IntArray(width)
        val cr = IntArray(width)
        val chromaWidth = (width + 1) / 2
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val pixel = row + x
                val i = pixel * 3
                val yp = luma(encoded[i], encoded[i + 1], encoded[i + 2])
                luma[x] = finalQuantize(yp * LUMA_SPAN + LUMA_MIN, pixel)
            }
            for (x in 0 until width step 2) {
                var cbSum = 0.0
                var crSum = 0.0
                val sampleCount = if (x + 1 < width) 2 else 1
                for (sample in 0 until sampleCount) {
                    val i = (row + x + sample) * 3
                    val yp = luma(encoded[i], encoded[i + 1], encoded[i + 2])
                    cbSum += (encoded[i + 2] - yp) / (2.0 * (1.0 - KB))
                    crSum += (encoded[i] - yp) / (2.0 * (1.0 - KR))
                }
                val ditherIndex = width * height + (y * chromaWidth + x / 2) * 2
                cb[x] = finalQuantize(cbSum / sampleCount * CHROMA_SPAN + CHROMA_ZERO, ditherIndex)
                cr[x] = finalQuantize(crSum / sampleCount * CHROMA_SPAN + CHROMA_ZERO, ditherIndex + 1)
            }
            packV210Row(out, y * stride, luma, cb, cr)
        }
        return out
    }

    private fun legacyDoubleQuantizedV210(): ByteArray {
        val scale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        val rgb = ShortArray(source.size) { component ->
            val linear = source[component] * scale
            val base = NeoLogCurve.quantize(linear, variant, 10)
            if (base in 1 until MAX10) {
                NeoLogCurve.quantize(linear, variant, 10, DITHER_LSB * tpdf(component)).toShort()
            } else {
                base.toShort()
            }
        }
        return RawTenBitPacker.pack(DevelopedFrame(width, height, 10, rgb), RawTenBitPacker.Format.V210_422)
    }

    private fun packV210Row(out: ByteArray, rowOffset: Int, luma: IntArray, cb: IntArray, cr: IntArray) {
        var offset = rowOffset
        var x = 0
        while (x < width) {
            val y0 = luma[x]
            val y1 = luma.getOrElse(x + 1) { y0 }
            val y2 = luma.getOrElse(x + 2) { y1 }
            val y3 = luma.getOrElse(x + 3) { y2 }
            val y4 = luma.getOrElse(x + 4) { y3 }
            val y5 = luma.getOrElse(x + 5) { y4 }
            val cb0 = cb[x]
            val cr0 = cr[x]
            val cb2 = cb.getOrElse(x + 2) { cb0 }
            val cr2 = cr.getOrElse(x + 2) { cr0 }
            val cb4 = cb.getOrElse(x + 4) { cb2 }
            val cr4 = cr.getOrElse(x + 4) { cr2 }
            writeWordLe(out, offset, (cr0 shl 20) or (y0 shl 10) or cb0)
            offset += 4
            writeWordLe(out, offset, (y2 shl 20) or (cb2 shl 10) or y1)
            offset += 4
            writeWordLe(out, offset, (cb4 shl 20) or (y3 shl 10) or cr2)
            offset += 4
            writeWordLe(out, offset, (y5 shl 20) or (cr4 shl 10) or y4)
            offset += 4
            x += 6
        }
    }

    private fun luma(r: Float, g: Float, b: Float): Double =
        KR * r.toDouble() + KG * g.toDouble() + KB * b.toDouble()

    private fun finalQuantize(code: Double, index: Int): Int =
        (code + DITHER_LSB * tpdf(index)).roundToInt().coerceIn(0, MAX10)

    private fun tpdf(index: Int): Float = hashUnit(index * 2) - hashUnit(index * 2 + 1)

    private fun hashUnit(value: Int): Float {
        var hash = value * -0x61c88647
        hash = hash xor (hash ushr 16)
        hash *= -0x7ee3623b
        hash = hash xor (hash ushr 13)
        hash *= -0x3361d2af
        hash = hash xor (hash ushr 16)
        return (hash ushr 8).toFloat() / (1 shl 24).toFloat()
    }

    private fun writeP010(out: ByteArray, offset: Int, code: Int) {
        val word = code.coerceIn(0, MAX10) shl 6
        out[offset] = word.toByte()
        out[offset + 1] = (word ushr 8).toByte()
    }

    private fun writeWordLe(out: ByteArray, offset: Int, word: Int) {
        out[offset] = word.toByte()
        out[offset + 1] = (word ushr 8).toByte()
        out[offset + 2] = (word ushr 16).toByte()
        out[offset + 3] = (word ushr 24).toByte()
    }

    private fun readWordLe(input: ByteArray, offset: Int): Int =
        (input[offset].toInt() and 0xFF) or
            ((input[offset + 1].toInt() and 0xFF) shl 8) or
            ((input[offset + 2].toInt() and 0xFF) shl 16) or
            ((input[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val MAX10 = 1023
        const val DITHER_LSB = 0.5f
        const val KR = 0.2627
        const val KB = 0.0593
        const val KG = 1.0 - KR - KB
        const val LUMA_MIN = 64.0
        const val LUMA_SPAN = 876.0
        const val CHROMA_ZERO = 512.0
        const val CHROMA_SPAN = 896.0
    }
}
