package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the RAW clip thumbnail: develops the first frame and downscales to an opaque ARGB
 * image within the requested long-edge, so the Settings clip list can visually identify each clip.
 */
class Raw12ThumbnailRendererTest {

    private fun meta(w: Int, h: Int) = RawDevelopMetadata(
        width = w, height = h, storageBitDepth = 12, sensorWhiteLevel = 1000,
        blackLevel = 0, whiteLevel = 1000, bayerPatternValue = 0,
        whiteBalanceR = 1f, whiteBalanceGr = 1f, whiteBalanceGb = 1f, whiteBalanceB = 1f,
        exposureTimeNs = 0L, analogGain = 1f, digitalGain = 1f, noiseS = 0f, noiseO = 0f,
        sensorToXyz = DEFAULT_SENSOR_TO_XYZ, fps = 30, colorProfileTag = "N-LOG-BASE"
    )

    @Test
    fun downscalesWithinMaxLongEdgeAndIsOpaque() {
        val w = 64; val h = 32
        val thumb = Raw12ThumbnailRenderer.render(
            firstFrameSamples = IntArray(w * h) { 180 },
            meta = meta(w, h),
            maxLongEdge = 16
        )
        assertTrue("long edge <= 16", maxOf(thumb.width, thumb.height) <= 16)
        assertEquals(thumb.width * thumb.height, thumb.argb.size)
        // Fully opaque ARGB.
        assertTrue(thumb.argb.all { (it ushr 24) and 0xFF == 0xFF })
        // 18% gray develops to non-black pixels.
        assertTrue(thumb.argb.any { (it and 0x00FFFFFF) != 0 })
    }

    @Test
    fun smallFrameIsNotUpscaled() {
        val thumb = Raw12ThumbnailRenderer.render(IntArray(8 * 4) { 180 }, meta(8, 4), maxLongEdge = 256)
        assertEquals(8, thumb.width)
        assertEquals(4, thumb.height)
    }

    @Test
    fun reducesMosaicBeforeDevelopAndPreservesBayerParity() {
        val w = 1024
        val h = 768
        val samples = IntArray(w * h) { index ->
            val x = index % w
            val y = index / w
            when ((y and 1) * 2 + (x and 1)) {
                0 -> 100
                1 -> 200
                2 -> 300
                else -> 400
            }
        }

        val reduced = Raw12ThumbnailRenderer.reduceBayerForThumbnail(samples, meta(w, h), maxLongEdge = 128)

        assertEquals(128, reduced.meta.width)
        assertEquals(96, reduced.meta.height)
        assertEquals(128 * 96, reduced.samples.size)
        assertEquals(100, reduced.samples[0])
        assertEquals(200, reduced.samples[1])
        assertEquals(300, reduced.samples[128])
        assertEquals(400, reduced.samples[129])
    }
}
