package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.roundToInt

class RecordDevelopContinuousYcbcrPackingTest {

    @Test
    fun v210AndP010MatchIndependentContinuousReferencesNotRgbPrequantization() {
        val width = 11
        val height = 3
        val signals = DoubleArray(width * height * 3) { component ->
            val pixel = component / 3
            when (component % 3) {
                0 -> ((pixel * 37 + 11) % 101) / 100.0
                1 -> ((pixel * 19 + 7) % 97) / 96.0
                else -> ((pixel * 53 + 3) % 103) / 102.0
            }
        }
        val prequantized = DoubleArray(signals.size) { index ->
            (signals[index] * MAX10).roundToInt() / MAX10.toDouble()
        }

        val actualV210 = RecordDevelopMovConverter.packContinuousV210ForValidation(
            signals,
            width,
            height
        )
        assertArrayEquals(referenceV210(signals, width, height), actualV210)
        assertFalse(
            "v210 must not restore the RGB-quantize then YCbCr-quantize path",
            referenceV210(prequantized, width, height).contentEquals(actualV210)
        )

        for (bt709 in listOf(true, false)) {
            val actualP010 = RecordDevelopMovConverter.packContinuousP010ForValidation(
                signals,
                width,
                height,
                useBt709Matrix = bt709
            )
            assertArrayEquals(
                "continuous P010 matrix=$bt709",
                referenceP010(signals, width, height, bt709),
                actualP010
            )
            assertFalse(
                "P010 must not restore RGB prequantization matrix=$bt709",
                referenceP010(prequantized, width, height, bt709).contentEquals(actualP010)
            )
        }
    }

    @Test
    fun v210AveragesSaturatedAlternatingColorsAcrossEachHorizontalPair() {
        val width = 6
        val red = doubleArrayOf(1.0, 0.0, 0.0)
        val blue = doubleArrayOf(0.0, 0.0, 1.0)
        val signals = DoubleArray(width * 3)
        for (x in 0 until width) {
            (if ((x and 1) == 0) red else blue).copyInto(signals, x * 3)
        }
        val payload = RecordDevelopMovConverter.packContinuousV210ForValidation(signals, width, 1)
        val firstWord = readWordLe(payload, 0)
        val redChroma = chroma(red, 0, BT2020_KR, BT2020_KB)
        val blueChroma = chroma(blue, 0, BT2020_KR, BT2020_KB)
        val expectedCb = quantizeChroma((redChroma.first + blueChroma.first) / 2.0)
        val expectedCr = quantizeChroma((redChroma.second + blueChroma.second) / 2.0)

        assertEquals(expectedCb, firstWord and MAX10)
        assertEquals(expectedCr, (firstWord ushr 20) and MAX10)
        assertNotEquals("left/even pixel chroma must not replace the pair average", quantizeChroma(redChroma.first), expectedCb)
        assertNotEquals("left/even pixel chroma must not replace the pair average", quantizeChroma(redChroma.second), expectedCr)
    }

    @Test
    fun oddWidthAndHeightKeepEveryRealEdgeSampleWithoutGeometryLoss() {
        val width = 5
        val height = 3
        val signals = DoubleArray(width * height * 3) { component ->
            val pixel = component / 3
            when (component % 3) {
                0 -> 0.07 + pixel * 0.011
                1 -> 0.13 + pixel * 0.009
                else -> 0.21 + pixel * 0.007
            }
        }

        val v210 = RecordDevelopMovConverter.packContinuousV210ForValidation(signals, width, height)
        assertEquals(RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, width) * height, v210.size)
        assertArrayEquals(referenceV210(signals, width, height), v210)
        val lastRowOffset = (height - 1) * RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, width)
        val word2 = readWordLe(v210, lastRowOffset + 8)
        val word3 = readWordLe(v210, lastRowOffset + 12)
        val lastPixel = ((height - 1) * width + width - 1) * 3
        val lastChroma = chroma(signals, lastPixel, BT2020_KR, BT2020_KB)
        assertEquals(quantizeChroma(lastChroma.first), (word2 ushr 20) and MAX10)
        assertEquals(quantizeChroma(lastChroma.second), (word3 ushr 10) and MAX10)

        val p010 = RecordDevelopMovConverter.packContinuousP010ForValidation(
            signals,
            width,
            height,
            useBt709Matrix = false
        )
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        assertEquals(width * height * 2 + chromaWidth * chromaHeight * 4, p010.size)
        assertArrayEquals(referenceP010(signals, width, height, bt709 = false), p010)
        val lastChromaOffset = width * height * 2 + (chromaWidth * chromaHeight - 1) * 4
        assertEquals(quantizeChroma(lastChroma.first), readP010(p010, lastChromaOffset))
        assertEquals(quantizeChroma(lastChroma.second), readP010(p010, lastChromaOffset + 2))
    }

    private fun referenceV210(rgb: DoubleArray, width: Int, height: Int): ByteArray {
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, width)
        val output = ByteArray(stride * height)
        val luma = IntArray(width)
        val cb = IntArray(width)
        val cr = IntArray(width)
        for (y in 0 until height) {
            val rowOffset = y * width * 3
            for (x in 0 until width) {
                luma[x] = quantizeLuma(luma(rgb, rowOffset + x * 3, BT2020_KR, BT2020_KB))
            }
            for (x in 0 until width step 2) {
                val count = if (x + 1 < width) 2 else 1
                var cbSum = 0.0
                var crSum = 0.0
                for (sample in 0 until count) {
                    val value = chroma(rgb, rowOffset + (x + sample) * 3, BT2020_KR, BT2020_KB)
                    cbSum += value.first
                    crSum += value.second
                }
                cb[x] = quantizeChroma(cbSum / count)
                cr[x] = quantizeChroma(crSum / count)
            }
            packV210Row(output, y * stride, width, luma, cb, cr)
        }
        return output
    }

    private fun referenceP010(rgb: DoubleArray, width: Int, height: Int, bt709: Boolean): ByteArray {
        val kr = if (bt709) BT709_KR else BT2020_KR
        val kb = if (bt709) BT709_KB else BT2020_KB
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = width * height * 2
        val output = ByteArray(lumaBytes + chromaWidth * chromaHeight * 4)
        for (pixel in 0 until width * height) {
            writeP010(output, pixel * 2, quantizeLuma(luma(rgb, pixel * 3, kr, kb)))
        }
        for (cy in 0 until chromaHeight) {
            for (cx in 0 until chromaWidth) {
                var cbSum = 0.0
                var crSum = 0.0
                var count = 0
                for (dy in 0..1) {
                    val y = cy * 2 + dy
                    if (y >= height) continue
                    for (dx in 0..1) {
                        val x = cx * 2 + dx
                        if (x >= width) continue
                        val value = chroma(rgb, (y * width + x) * 3, kr, kb)
                        cbSum += value.first
                        crSum += value.second
                        count++
                    }
                }
                val offset = lumaBytes + (cy * chromaWidth + cx) * 4
                writeP010(output, offset, quantizeChroma(cbSum / count))
                writeP010(output, offset + 2, quantizeChroma(crSum / count))
            }
        }
        return output
    }

    private fun packV210Row(
        output: ByteArray,
        rowOffset: Int,
        width: Int,
        luma: IntArray,
        cb: IntArray,
        cr: IntArray
    ) {
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
            writeWordLe(output, offset, (cr0 shl 20) or (y0 shl 10) or cb0)
            offset += 4
            writeWordLe(output, offset, (y2 shl 20) or (cb2 shl 10) or y1)
            offset += 4
            writeWordLe(output, offset, (cb4 shl 20) or (y3 shl 10) or cr2)
            offset += 4
            writeWordLe(output, offset, (y5 shl 20) or (cr4 shl 10) or y4)
            offset += 4
            x += 6
        }
    }

    private fun luma(rgb: DoubleArray, offset: Int, kr: Double, kb: Double): Double =
        kr * rgb[offset] + (1.0 - kr - kb) * rgb[offset + 1] + kb * rgb[offset + 2]

    private fun chroma(rgb: DoubleArray, offset: Int, kr: Double, kb: Double): Pair<Double, Double> {
        val y = luma(rgb, offset, kr, kb)
        return (rgb[offset + 2] - y) / (2.0 * (1.0 - kb)) to
            (rgb[offset] - y) / (2.0 * (1.0 - kr))
    }

    private fun quantizeLuma(value: Double): Int =
        (value * 876.0 + 64.0).roundToInt().coerceIn(0, MAX10)

    private fun quantizeChroma(value: Double): Int =
        (value * 896.0 + 512.0).roundToInt().coerceIn(0, MAX10)

    private fun writeP010(output: ByteArray, offset: Int, code: Int) {
        val word = code.coerceIn(0, MAX10) shl 6
        output[offset] = word.toByte()
        output[offset + 1] = (word ushr 8).toByte()
    }

    private fun readP010(input: ByteArray, offset: Int): Int {
        val word = (input[offset].toInt() and 0xFF) or ((input[offset + 1].toInt() and 0xFF) shl 8)
        return word ushr 6
    }

    private fun writeWordLe(output: ByteArray, offset: Int, word: Int) {
        output[offset] = word.toByte()
        output[offset + 1] = (word ushr 8).toByte()
        output[offset + 2] = (word ushr 16).toByte()
        output[offset + 3] = (word ushr 24).toByte()
    }

    private fun readWordLe(input: ByteArray, offset: Int): Int =
        (input[offset].toInt() and 0xFF) or
            ((input[offset + 1].toInt() and 0xFF) shl 8) or
            ((input[offset + 2].toInt() and 0xFF) shl 16) or
            ((input[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val MAX10 = 1023
        const val BT709_KR = 0.2126
        const val BT709_KB = 0.0722
        const val BT2020_KR = 0.2627
        const val BT2020_KB = 0.0593
    }
}
