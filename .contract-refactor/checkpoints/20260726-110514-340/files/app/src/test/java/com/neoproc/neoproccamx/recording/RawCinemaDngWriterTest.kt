package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.capture.DngTiffReader
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `header starts with a valid little-endian TIFF signature`() {
        val bytes = RawCinemaDngWriter.frameToBytes(mosaic(), meta())
        assertEquals('I'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals(42, bytes[2].toInt() and 0xFF) // magic low byte
        assertEquals(0, bytes[3].toInt() and 0xFF)
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
