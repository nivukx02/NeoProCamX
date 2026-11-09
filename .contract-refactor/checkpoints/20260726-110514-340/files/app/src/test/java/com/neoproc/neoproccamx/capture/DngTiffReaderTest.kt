package com.neoproc.neoproccamx.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Field-equality test for the DNG colour-tag **re-read** path: build a DNG byte stream that
 * carries the §"DNG color exactness" tags at known values, parse it back with [DngTiffReader],
 * and assert exact field-equality. This locks the half of the contract that is verifiable on
 * the JVM; the native `DngCreator` write side is covered by the instrumented round-trip test.
 */
class DngTiffReaderTest {

    // Known reference values written into the synthetic DNG.
    private val blackLevel = 64L
    private val whiteLevel = 16383L
    private val colorMatrix1 = doubleArrayOf(1.0, -0.30, 0.05, -0.20, 1.10, -0.10, 0.00, -0.25, 1.20)
    private val colorMatrix2 = doubleArrayOf(0.95, -0.28, 0.04, -0.18, 1.08, -0.09, 0.01, -0.24, 1.18)
    private val forwardMatrix1 = doubleArrayOf(0.45, 0.35, 0.15, 0.22, 0.71, 0.07, 0.02, 0.10, 0.80)
    private val forwardMatrix2 = doubleArrayOf(0.46, 0.34, 0.15, 0.21, 0.72, 0.07, 0.02, 0.11, 0.79)
    private val illuminant1 = 17 // Standard A
    private val illuminant2 = 21 // D65
    private val noiseProfile = doubleArrayOf(1.0e-4, 2.0e-5)

    @Test
    fun `reads colour tags with field equality (little endian)`() = assertRoundTrip(ByteOrder.LITTLE_ENDIAN, subIfd = false)

    @Test
    fun `reads colour tags with field equality (big endian)`() = assertRoundTrip(ByteOrder.BIG_ENDIAN, subIfd = false)

    @Test
    fun `follows a sub-IFD to find the colour tags`() = assertRoundTrip(ByteOrder.LITTLE_ENDIAN, subIfd = true)

    @Test
    fun `present tags set matches the required contract`() {
        val tags = DngTiffReader.read(buildDng(ByteOrder.LITTLE_ENDIAN, subIfd = false))
        assertTrue(tags.presentTags().containsAll(RawDngMetadataContract.REQUIRED))
        assertEquals(
            RawDngMetadataContract.evaluate(tags.presentTags()).isComplete,
            true
        )
    }

    @Test
    fun `rejects a non-dng byte stream`() {
        try {
            DngTiffReader.read(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))
            fail("expected a format exception")
        } catch (e: DngTiffReader.DngFormatException) {
            assertTrue(e.message!!.contains("byte-order"))
        }
    }

    @Test
    fun `rejects a too-small stream`() {
        try {
            DngTiffReader.read(byteArrayOf('I'.code.toByte(), 'I'.code.toByte()))
            fail("expected a format exception")
        } catch (e: DngTiffReader.DngFormatException) {
            assertTrue(e.message!!.contains("too small"))
        }
    }

    private fun assertRoundTrip(order: ByteOrder, subIfd: Boolean) {
        val tags = DngTiffReader.read(buildDng(order, subIfd))
        assertEquals(whiteLevel, tags.whiteLevel)
        assertNotNull(tags.blackLevel)
        assertEquals(blackLevel.toDouble(), tags.blackLevel!![0], 0.0)
        assertMatrix(colorMatrix1, tags.colorMatrix1)
        assertMatrix(colorMatrix2, tags.colorMatrix2)
        assertMatrix(forwardMatrix1, tags.forwardMatrix1)
        assertMatrix(forwardMatrix2, tags.forwardMatrix2)
        assertEquals(illuminant1, tags.calibrationIlluminant1)
        assertEquals(illuminant2, tags.calibrationIlluminant2)
        val actualNoiseProfile = requireNotNull(tags.noiseProfile)
        assertEquals(noiseProfile[0], actualNoiseProfile[0], 1e-9)
        assertEquals(noiseProfile[1], actualNoiseProfile[1], 1e-9)
    }

    private fun assertMatrix(expected: DoubleArray, actual: DoubleArray?) {
        assertNotNull(actual)
        assertEquals(expected.size, actual!!.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-3)
    }

    // ── Synthetic DNG/TIFF builder ───────────────────────────────────────────────

    private fun buildDng(order: ByteOrder, subIfd: Boolean): ByteArray {
        // (tag, type, count, valueBytes) — values pre-encoded in `order`.
        val entries = sortedMapOf<Int, Triple<Int, Int, ByteArray>>()
        fun put(tag: Int, type: Int, count: Int, value: ByteArray) { entries[tag] = Triple(type, count, value) }

        put(50714, 4, 1, longVal(order, blackLevel))                 // BlackLevel  LONG
        put(50717, 4, 1, longVal(order, whiteLevel))                 // WhiteLevel  LONG
        put(50721, 10, 9, srationals(order, colorMatrix1))           // ColorMatrix1
        put(50722, 10, 9, srationals(order, colorMatrix2))           // ColorMatrix2
        put(50778, 3, 1, shortVal(order, illuminant1))               // CalibrationIlluminant1
        put(50779, 3, 1, shortVal(order, illuminant2))               // CalibrationIlluminant2
        put(50964, 10, 9, srationals(order, forwardMatrix1))         // ForwardMatrix1
        put(50965, 10, 9, srationals(order, forwardMatrix2))         // ForwardMatrix2
        put(51041, 12, 2, doubles(order, noiseProfile))              // NoiseProfile DOUBLE

        return if (subIfd) buildWithSubIfd(order, entries) else buildSingleIfd(order, entries, ifdOffset = 8)
    }

    private fun buildSingleIfd(
        order: ByteOrder,
        entries: Map<Int, Triple<Int, Int, ByteArray>>,
        ifdOffset: Int,
        nextIfd: Int = 0
    ): ByteArray {
        val n = entries.size
        val dataStart = ifdOffset + 2 + 12 * n + 4
        var dataPtr = dataStart
        val offsets = HashMap<Int, Int>()
        for ((tag, tv) in entries) if (tv.third.size > 4) { offsets[tag] = dataPtr; dataPtr += tv.third.size }

        val bb = ByteBuffer.allocate(dataPtr).order(order)
        // header
        val mark = if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte()
        bb.put(0, mark); bb.put(1, mark)
        bb.putShort(2, 42.toShort())
        bb.putInt(4, ifdOffset)
        // ifd
        bb.putShort(ifdOffset, n.toShort())
        var e = ifdOffset + 2
        for ((tag, tv) in entries) {
            val (type, count, value) = tv
            bb.putShort(e, tag.toShort())
            bb.putShort(e + 2, type.toShort())
            bb.putInt(e + 4, count)
            if (value.size <= 4) {
                for (k in 0 until 4) bb.put(e + 8 + k, if (k < value.size) value[k] else 0)
            } else {
                bb.putInt(e + 8, offsets.getValue(tag))
            }
            e += 12
        }
        bb.putInt(e, nextIfd)
        // out-of-line data
        for ((tag, tv) in entries) if (tv.third.size > 4) {
            val off = offsets.getValue(tag)
            for (k in tv.third.indices) bb.put(off + k, tv.third[k])
        }
        return bb.array()
    }

    /** Place the colour tags in a sub-IFD referenced from IFD0 (SubIFDs tag 330). */
    private fun buildWithSubIfd(order: ByteOrder, entries: Map<Int, Triple<Int, Int, ByteArray>>): ByteArray {
        // IFD0 holds a single SubIFDs entry pointing at the colour IFD that follows it.
        val ifd0Offset = 8
        val ifd0Size = 2 + 12 * 1 + 4
        val subIfdOffset = ifd0Offset + ifd0Size
        val subDng = buildSingleIfd(order, entries, ifdOffset = subIfdOffset)

        // Splice IFD0 (with SubIFDs -> subIfdOffset) in front of the sub-IFD bytes.
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(subIfdOffset).order(order)
        val mark = if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte()
        head.put(0, mark); head.put(1, mark)
        head.putShort(2, 42.toShort())
        head.putInt(4, ifd0Offset)
        head.putShort(ifd0Offset, 1.toShort()) // one entry
        head.putShort(ifd0Offset + 2, TAG_SUB_IFDS.toShort())
        head.putShort(ifd0Offset + 4, 4.toShort()) // LONG
        head.putInt(ifd0Offset + 6, 1)   // count
        head.putInt(ifd0Offset + 10, subIfdOffset) // value = offset to sub-IFD
        head.putInt(ifd0Offset + 14, 0)  // next IFD
        out.write(head.array())
        // subDng already starts with a header; we only need its bytes from subIfdOffset onward.
        out.write(subDng, subIfdOffset, subDng.size - subIfdOffset)
        return out.toByteArray()
    }

    private fun shortVal(order: ByteOrder, v: Int): ByteArray =
        ByteBuffer.allocate(2).order(order).putShort(v.toShort()).array()

    private fun longVal(order: ByteOrder, v: Long): ByteArray =
        ByteBuffer.allocate(4).order(order).putInt(v.toInt()).array()

    private fun srationals(order: ByteOrder, values: DoubleArray): ByteArray {
        val bb = ByteBuffer.allocate(values.size * 8).order(order)
        for (v in values) {
            bb.putInt(Math.round(v * 10000.0).toInt())
            bb.putInt(10000)
        }
        return bb.array()
    }

    private fun doubles(order: ByteOrder, values: DoubleArray): ByteArray {
        val bb = ByteBuffer.allocate(values.size * 8).order(order)
        for (v in values) bb.putDouble(v)
        return bb.array()
    }

    private companion object {
        const val TAG_SUB_IFDS = 330
    }
}
