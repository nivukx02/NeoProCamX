package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.capture.DngTiffReader
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream

/**
 * Locks the [RawCinemaDngWriter] byte layout by round-tripping a written DNG frame back through
 * [DngTiffReader] (the same colour-tag re-reader used for the capture-time DNG contract) and by
 * verifying the raw pixel plane is bit-exact. This proves the offline CinemaDNG export carries the
 * neutral-defining metadata a third-party NLE needs — without a device.
 */
class RawCinemaDngWriterTest {

    private val width = 8
    private val height = 6
    private val sensorToXyz = floatArrayOf(
        0.60f, 0.20f, 0.10f,
        0.20f, 0.70f, 0.10f,
        0.05f, 0.15f, 0.90f
    )

    private fun meta() = RawDevelopMetadata(
        width = width,
        height = height,
        storageBitDepth = 12,
        sensorWhiteLevel = 4095,
        blackLevel = 64,
        whiteLevel = 4000,
        bayerPatternValue = 0, // RGGB
        whiteBalanceR = 2.0f,
        whiteBalanceGr = 1.0f,
        whiteBalanceGb = 1.0f,
        whiteBalanceB = 1.5f,
        exposureTimeNs = 8_000_000L,
        analogGain = 1.0f,
        digitalGain = 1.0f,
        noiseS = 0.0f,
        noiseO = 0.0f,
        sensorToXyz = sensorToXyz,
        fps = 24,
        colorProfileTag = "N-LOG-BASE"
    )

    private fun mosaic(): IntArray = IntArray(width * height) { (it * 37 + 100) and 0xFFF }

    @Test
    fun `written DNG re-reads black white illuminant`() {
        val bytes = RawCinemaDngWriter.frameToBytes(mosaic(), meta())
        val tags = DngTiffReader.read(bytes)

        assertEquals(4000L, tags.whiteLevel)
        assertNotNull(tags.blackLevel)
        assertEquals(64.0, tags.blackLevel!![0], 0.0)
        assertEquals(23, tags.calibrationIlluminant1) // D50
        assertNotNull(tags.colorMatrix1)
        assertEquals(9, tags.colorMatrix1!!.size)
    }

    @Test
    fun `ColorMatrix1 is the inverse of sensor to XYZ`() {
        val bytes = RawCinemaDngWriter.frameToBytes(mosaic(), meta())
        val cm1 = DngTiffReader.read(bytes).colorMatrix1!!
        val fwd = DoubleArray(9) { sensorToXyz[it].toDouble() }
        val product = multiply3x3(fwd, cm1) // sensor→XYZ · XYZ→sensor ≈ I
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (i in 0 until 9) {
            assertEquals("element $i", identity[i], product[i], 1e-3)
        }
    }

    @Test
    fun `pixel plane is bit-exact little endian at the tail`() {
        val src = mosaic()
        val bytes = RawCinemaDngWriter.frameToBytes(src, meta())
        val pixelBytes = width * height * 2
        assertTrue(bytes.size > pixelBytes)
        val tail = bytes.copyOfRange(bytes.size - pixelBytes, bytes.size)
        for (i in 0 until width * height) {
            val lo = tail[i * 2].toInt() and 0xFF
            val hi = tail[i * 2 + 1].toInt() and 0xFF
            assertEquals(src[i] and 0xFFFF, lo or (hi shl 8))
        }
    }

    @Test
    fun `pixel plane remains bit exact across multiple writer chunks`() {
        val chunkCrossingWidth = 1024
        val chunkCrossingHeight = 513
        val source = IntArray(chunkCrossingWidth * chunkCrossingHeight) { index ->
            (index * 73 + (index ushr 4) * 19 + 17) and 0xFFF
        }
        val sourceBefore = source.copyOf()
        val bytes = RawCinemaDngWriter.frameToBytes(
            source,
            meta().copy(width = chunkCrossingWidth, height = chunkCrossingHeight)
        )
        val pixelBytes = source.size * 2
        assertTrue(pixelBytes > RawCinemaDngWriter.PIXEL_WRITE_CHUNK_BYTES)
        val pixelOffset = bytes.size - pixelBytes

        source.indices.forEach { index ->
            val offset = pixelOffset + index * 2
            val actual = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            assertEquals("RAW sample $index", source[index], actual)
        }
        assertArrayEquals("DNG export must not mutate the RAW master", sourceBefore, source)
    }

    @Test
    fun `DNG stores four black levels in physical CFA order`() {
        val semanticRggb = floatArrayOf(10.25f, 20.5f, 30.75f, 40f)
        val expectedByPattern = arrayOf(
            doubleArrayOf(10.25, 20.5, 30.75, 40.0),
            doubleArrayOf(20.5, 10.25, 40.0, 30.75),
            doubleArrayOf(30.75, 40.0, 10.25, 20.5),
            doubleArrayOf(40.0, 30.75, 20.5, 10.25)
        )
        for (pattern in 0..3) {
            val bytes = RawCinemaDngWriter.frameToBytes(
                mosaic(),
                meta().copy(bayerPatternValue = pattern, blackLevelRggb = semanticRggb)
            )
            val black = DngTiffReader.read(bytes).blackLevel
            assertNotNull(black)
            assertArrayEquals("Bayer pattern $pattern", expectedByPattern[pattern], black!!, 1e-6)
        }
    }

    @Test
    fun `header starts with a valid little-endian TIFF signature`() {
        val bytes = RawCinemaDngWriter.frameToBytes(mosaic(), meta())
        assertEquals('I'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals(42, bytes[2].toInt() and 0xFF) // magic low byte
        assertEquals(0, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `pixel plane uses large bounded writes instead of one write per row`() {
        val largeWidth = 1024
        val largeHeight = 64
        val largeMeta = meta().copy(width = largeWidth, height = largeHeight)
        val output = CountingOutputStream()

        RawCinemaDngWriter.writeFrame(
            output,
            IntArray(largeWidth * largeHeight) { it and 0xFFFF },
            largeMeta
        )

        // One header write + one 128 KiB pixel write.  The old implementation made 65 writes.
        assertEquals(2, output.writeCalls)
        assertTrue(output.maximumWriteBytes <= RawCinemaDngWriter.PIXEL_WRITE_CHUNK_BYTES)
        assertTrue(output.totalBytes > largeWidth * largeHeight * 2L)
    }

    @Test
    fun `overflowing dimensions fail before header or pixel output`() {
        val output = CountingOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            RawCinemaDngWriter.writeFrame(
                output,
                IntArray(0),
                meta().copy(width = 65_536, height = 65_536)
            )
        }
        assertEquals(0, output.writeCalls)
    }

    private class CountingOutputStream : OutputStream() {
        var writeCalls: Int = 0
            private set
        var maximumWriteBytes: Int = 0
            private set
        var totalBytes: Long = 0L
            private set

        override fun write(value: Int) {
            writeCalls++
            maximumWriteBytes = maxOf(maximumWriteBytes, 1)
            totalBytes++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            writeCalls++
            maximumWriteBytes = maxOf(maximumWriteBytes, length)
            totalBytes += length.toLong()
        }
    }

    private fun multiply3x3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += a[r * 3 + k] * b[k * 3 + c]
                out[r * 3 + c] = s
            }
        }
        return out
    }
}
