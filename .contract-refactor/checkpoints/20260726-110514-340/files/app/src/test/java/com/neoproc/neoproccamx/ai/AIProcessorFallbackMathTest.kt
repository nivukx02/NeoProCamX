package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AIProcessorFallbackMathTest {

    @Test
    fun catmullRomWeight_hasExpectedSplineShape() {
        val processor = AIProcessor()

        assertEquals(1f, processor.catmullRomSplineWeight(0f), 0.0001f)
        assertEquals(0f, processor.catmullRomSplineWeight(2f), 0.0001f)
        assertTrue(processor.catmullRomSplineWeight(0.5f) > processor.catmullRomSplineWeight(1.5f))
    }

    @Test
    fun chromaMahalanobisFallbackProducesSoftForegroundMask() {
        val width = 8
        val height = 8
        val ySize = width * height
        val uvStride = width
        val buffer = ByteBuffer.allocate(ySize + uvStride * height / 2)

        repeat(ySize) { buffer.put(128.toByte()) }
        for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                val insideFaceRegion = x in 1..2 && y in 1..2
                buffer.put(if (insideFaceRegion) 104.toByte() else 32.toByte())
                buffer.put(if (insideFaceRegion) 154.toByte() else 224.toByte())
            }
        }
        buffer.rewind()

        val mask = AIProcessor().segmentChromaFallback(
            yuvBuffer = buffer,
            width = width,
            height = height,
            yStride = width,
            uvStride = uvStride,
            uvPixelStride = 2,
            faceCenterU = 4,
            faceCenterV = 4,
            faceWidth = 4,
            faceHeight = 4
        )

        val center = mask[4 * width + 4].toInt() and 0xFF
        val corner = mask[0].toInt() and 0xFF
        assertTrue("center=$center corner=$corner", center > 180)
        assertTrue("center=$center corner=$corner", corner < 80)
    }

    @Test
    fun radialDepthFallbackIsBoundedAndSubjectWeighted() {
        val depth = AIProcessor().synthesizeRadialDepthFallback(
            width = 16,
            height = 16,
            focusDistanceDiopters = 2f,
            focalLengthMm = 24f,
            faceBounds = null
        )

        val center = depth[8 * 16 + 8]
        val corner = depth[0]
        assertEquals(16 * 16, depth.size)
        assertTrue(depth.all { it in 0f..1f })
        assertTrue("center=$center corner=$corner", center > corner)
    }

    @Test
    fun temporalEmaDenoiseBlendsStationaryLuma() {
        val incoming = byteArrayOf(100, 100, 100, 100)
        val previous = byteArrayOf(50, 50, 50, 50)

        val denoised = AIProcessor().temporalEmaDenoiseLuma(
            incoming = incoming,
            previous = previous,
            width = 2,
            height = 2,
            baseAlpha = 0.5f
        )

        assertEquals(75, denoised[0].toInt() and 0xFF)
        assertEquals(75, denoised[3].toInt() and 0xFF)
    }
}
