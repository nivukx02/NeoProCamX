package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class FrameAnalysisMathTest {

    @Test
    fun computeSceneStats_buildsHistogramAverageAndWaveform() {
        val luma = byteArrayOf(
            0, 64,
            (-128).toByte(), (-1).toByte()
        )

        val stats = FrameAnalysisMath.computeSceneStats(
            width = 2,
            height = 2,
            luma = luma,
            maxStatsLongEdge = 320,
            waveformEnabled = true
        )

        assertEquals(1, stats.histogram[0])
        assertEquals(1, stats.histogram[64])
        assertEquals(1, stats.histogram[128])
        assertEquals(1, stats.histogram[255])
        assertEquals((0f + 64f + 128f + 255f) / 4f / 255f, stats.avgLuma, 0.0001f)
        assertEquals(64, stats.waveformColumns?.size)
        assertEquals(64, stats.waveformColumns?.get(0))
        assertEquals(159, stats.waveformColumns?.get(32))
    }

    private fun yuvFrame(width: Int, height: Int, y: ByteArray, cr: ByteArray): RawYuvFrame =
        RawYuvFrame(
            width = width,
            height = height,
            yBytes = y,
            uBytes = ByteArray((width / 2) * (height / 2)) { (-128).toByte() /* 128 = neutral Cb */ },
            vBytes = cr,
            yRowStride = width,
            yPixelStride = 1,
            uRowStride = width / 2,
            vRowStride = width / 2,
            uPixelStride = 1,
            vPixelStride = 1,
            timestamp = 0L,
            bitDepth = 8
        )

    @Test
    fun computeRedHighlightClipFraction_flagsBrightWarmPixelsNotNeutrals() {
        // 2x2 bright frame, one shared chroma sample. Cr=240 (strongly red) + bright Y => reconstructed
        // R = 200 + 1.47*(240-128) ~= 364 -> above the 245 clip code for all four pixels.
        val redClip = FrameAnalysisMath.computeRedHighlightClipFraction(
            frame = yuvFrame(2, 2, byteArrayOf(-56, -56, -56, -56), byteArrayOf(-16)), // 200, Cr=240
            maxStatsLongEdge = 320
        )
        assertEquals(1f, redClip, 1e-4f)

        // Same brightness but NEUTRAL chroma (Cr=128) => R ~= 200, below clip -> zero.
        val neutral = FrameAnalysisMath.computeRedHighlightClipFraction(
            frame = yuvFrame(2, 2, byteArrayOf(-56, -56, -56, -56), byteArrayOf(-128)), // 200, Cr=128
            maxStatsLongEdge = 320
        )
        assertEquals(0f, neutral, 1e-4f)
    }

    @Test
    fun computeRedHighlightClipFraction_detectsVideoRangeRedNearClip() {
        // Y=170, Cr=173 reconstructs R≈236: clipped at nominal video white (235) even though luma is
        // moderate. This is the exact case a luma-only NDR exposure meter misses.
        val fraction = FrameAnalysisMath.computeRedHighlightClipFraction(
            frame = yuvFrame(
                2,
                2,
                byteArrayOf((-86).toByte(), (-86).toByte(), (-86).toByte(), (-86).toByte()),
                byteArrayOf((-83).toByte())
            ),
            maxStatsLongEdge = 320
        )
        assertEquals(1f, fraction, 1e-4f)
    }

    @Test
    fun computeRedHighlightClipFraction_reactsBeforeHardRedClip() {
        // Limited-range reconstruction is about 96% red: below nominal code 235 but above the
        // proactive code-224 pressure point, leaving Camera2 enough latency to save the channel.
        val fraction = FrameAnalysisMath.computeRedHighlightClipFraction(
            frame = yuvFrame(
                2,
                2,
                byteArrayOf((-86).toByte(), (-86).toByte(), (-86).toByte(), (-86).toByte()), // Y=170
                byteArrayOf((-89).toByte()) // Cr=167
            ),
            maxStatsLongEdge = 320
        )
        assertEquals(1f, fraction, 1e-4f)
    }

    @Test
    fun computeRedHighlightClipFraction_supportsP010LimitedRange() {
        fun p010(vararg codes: Int): ByteArray = ByteArray(codes.size * 2).also { out ->
            codes.forEachIndexed { index, code ->
                val word = code.coerceIn(0, 1023) shl 6
                out[index * 2] = (word and 0xFF).toByte()
                out[index * 2 + 1] = ((word ushr 8) and 0xFF).toByte()
            }
        }
        val tenBit = RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = p010(680, 680, 680, 680), // 8-bit limited equivalent Y=170
            uBytes = p010(512),
            vBytes = p010(668), // 8-bit limited equivalent Cr=167
            yRowStride = 4,
            yPixelStride = 2,
            uRowStride = 2,
            vRowStride = 2,
            uPixelStride = 2,
            vPixelStride = 2,
            timestamp = 0L,
            bitDepth = 10
        )
        assertEquals(1f, FrameAnalysisMath.computeRedHighlightClipFraction(tenBit, 320), 1e-4f)
    }

    @Test
    fun computeRedHighlightClipFraction_returnsZeroForUnsupportedDepthOrEmpty() {
        val unsupported = yuvFrame(2, 2, byteArrayOf(-56, -56, -56, -56), byteArrayOf(-16))
            .copy(bitDepth = 12)
        assertEquals(0f, FrameAnalysisMath.computeRedHighlightClipFraction(unsupported, 320), 1e-6f)
        val empty = yuvFrame(2, 2, ByteArray(0), ByteArray(0))
        assertEquals(0f, FrameAnalysisMath.computeRedHighlightClipFraction(empty, 320), 1e-6f)
    }

    @Test
    fun buildFocusPeakingMask_detectsSharpEdgeAcrossFrame() {
        val luma = byteArrayOf(
            0, 0, 0,
            0, (-1).toByte(), (-1).toByte(),
            0, (-1).toByte(), (-1).toByte()
        )

        val mask = FrameAnalysisMath.buildFocusPeakingMask(
            width = 3,
            height = 3,
            luma = luma,
            maxMaskLongEdge = 320,
            peakingIntensity = 1f
        )

        assertEquals(3, mask.maskWidth)
        assertEquals(3, mask.maskHeight)
        assertTrue((mask.alphaMask[4].toInt() and 0xFF) > 0)
    }

    @Test
    fun buildFocusPeakingMask_focusRegionPrioritizesSelectedArea() {
        val luma = byteArrayOf(
            0, 0, (-1).toByte(), (-1).toByte(), (-1).toByte(),
            0, 0, (-1).toByte(), (-1).toByte(), (-1).toByte(),
            0, 0, (-1).toByte(), (-1).toByte(), (-1).toByte(),
            0, 0, (-1).toByte(), (-1).toByte(), (-1).toByte(),
            0, 0, (-1).toByte(), (-1).toByte(), (-1).toByte()
        )

        val fullMask = FrameAnalysisMath.buildFocusPeakingMask(
            width = 5,
            height = 5,
            luma = luma,
            maxMaskLongEdge = 320,
            peakingIntensity = 1f
        )
        val focusedMask = FrameAnalysisMath.buildFocusPeakingMask(
            width = 5,
            height = 5,
            luma = luma,
            maxMaskLongEdge = 320,
            peakingIntensity = 1f,
            focusRegion = Triple(0.5f, 0.5f, 0.15f)
        )

        val centerIndex = 2 * 5 + 2
        val sideIndex = 2 * 5 + 1
        val focusedCenter = focusedMask.alphaMask[centerIndex].toInt() and 0xFF
        val focusedSide = focusedMask.alphaMask[sideIndex].toInt() and 0xFF
        val unfocusedSide = fullMask.alphaMask[sideIndex].toInt() and 0xFF

        assertTrue(focusedCenter > 0)
        assertTrue(focusedCenter >= focusedSide)
        assertTrue(focusedSide < unfocusedSide)
    }

    @Test
    fun buildZebraMask_marksPixelsAboveThreshold() {
        val luma = byteArrayOf(
            10, 20,
            (-1).toByte(), 100
        )

        val mask = FrameAnalysisMath.buildZebraMask(
            width = 2,
            height = 2,
            luma = luma,
            maxMaskLongEdge = 320,
            zebraThreshold = 0.9f
        )

        assertEquals(0, mask.alphaMask[0].toInt() and 0xFF)
        assertEquals(0, mask.alphaMask[1].toInt() and 0xFF)
        assertEquals(0x7F, mask.alphaMask[2].toInt() and 0xFF)
        assertEquals(0, mask.alphaMask[3].toInt() and 0xFF)
    }

    @Test
    fun buildFocusPeakingMask_strictModeRespectsRequestedMaskCeiling() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val width = 1920
            val height = 1440
            val luma = ByteArray(width * height)

            val mask = FrameAnalysisMath.buildFocusPeakingMask(
                width = width,
                height = height,
                luma = luma,
                maxMaskLongEdge = 384,
                peakingIntensity = 0.8f
            )

            assertTrue(max(mask.maskWidth, mask.maskHeight) <= 384)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
