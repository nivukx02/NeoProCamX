package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import kotlin.math.ceil
import kotlin.math.pow

class FrameAnalysisMathTest {

    private fun p010(vararg codes: Int): ByteArray = ByteArray(codes.size * 2).also { out ->
        codes.forEachIndexed { index, code ->
            val word = code.coerceIn(0, 1023) shl 6
            out[index * 2] = (word and 0xFF).toByte()
            out[index * 2 + 1] = ((word ushr 8) and 0xFF).toByte()
        }
    }

    private fun pqSignalForNits(nits: Float): Float {
        val normalized = (nits.coerceIn(0f, 10_000f) / 10_000f).toDouble()
        val powered = normalized.pow(0.1593017578125)
        return ((0.8359375 + 18.8515625 * powered) /
            (1.0 + 18.6875 * powered)).pow(78.84375).toFloat()
    }

    private fun limitedPqCodeForNits(nits: Float): Int =
        ceil(64f + 876f * pqSignalForNits(nits)).toInt().coerceIn(64, 940)

    private fun p010Frame(yCode: Int, uCode: Int = 512, vCode: Int = 512): RawYuvFrame =
        RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = p010(yCode, yCode, yCode, yCode),
            uBytes = p010(uCode),
            vBytes = p010(vCode),
            yRowStride = 4,
            yPixelStride = 2,
            uRowStride = 2,
            vRowStride = 2,
            uPixelStride = 2,
            vPixelStride = 2,
            timestamp = 0L,
            bitDepth = 10
        )

    @Test
    fun computeSceneStats_buildsInternalHistogramAndAverage() {
        val luma = byteArrayOf(0, 64, (-128).toByte(), (-1).toByte())
        val stats = FrameAnalysisMath.computeSceneStats(
            width = 2,
            height = 2,
            luma = luma,
            maxStatsLongEdge = 320
        )

        assertEquals(1, stats.histogram[0])
        assertEquals(1, stats.histogram[64])
        assertEquals(1, stats.histogram[128])
        assertEquals(1, stats.histogram[255])
        assertEquals((0f + 64f + 128f + 255f) / 4f / 255f, stats.avgLuma, 0.0001f)
    }

    private fun yuvFrame(width: Int, height: Int, y: ByteArray, cr: ByteArray): RawYuvFrame =
        RawYuvFrame(
            width = width,
            height = height,
            yBytes = y,
            uBytes = ByteArray((width / 2) * (height / 2)) { (-128).toByte() },
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
        val redClip = FrameAnalysisMath.computeRedHighlightClipFraction(
            frame = yuvFrame(2, 2, byteArrayOf(-56, -56, -56, -56), byteArrayOf(-16)),
            maxStatsLongEdge = 320
        )
        assertEquals(1f, redClip, 1e-4f)

        val neutral = FrameAnalysisMath.computeRedHighlightClipFraction(
            frame = yuvFrame(2, 2, byteArrayOf(-56, -56, -56, -56), byteArrayOf(-128)),
            maxStatsLongEdge = 320
        )
        assertEquals(0f, neutral, 1e-4f)
    }

    @Test
    fun computeRedHighlightClipFraction_detectsVideoRangeRedNearClip() {
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
        val fraction = FrameAnalysisMath.computeRedHighlightClipFraction(
            frame = yuvFrame(
                2,
                2,
                byteArrayOf((-86).toByte(), (-86).toByte(), (-86).toByte(), (-86).toByte()),
                byteArrayOf((-89).toByte())
            ),
            maxStatsLongEdge = 320
        )
        assertEquals(1f, fraction, 1e-4f)
    }

    @Test
    fun computeRedHighlightClipFraction_supportsP010LimitedRange() {
        val tenBit = RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = p010(680, 680, 680, 680),
            uBytes = p010(512),
            vBytes = p010(668),
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
    fun computePqHighlightPressure_flagsNeutralAtOneThousandNits() {
        val pressure = FrameAnalysisMath.computePqHighlightPressure(
            frame = p010Frame(yCode = limitedPqCodeForNits(1_000f)),
            maxStatsLongEdge = 320
        )

        assertEquals(1f, pressure.luminanceFraction, 1e-6f)
        assertEquals(1f, pressure.redChannelFraction, 1e-6f)
    }

    @Test
    fun computePqHighlightPressure_flagsWarmRedChannelBeforeLuminance() {
        val diffuseSignal = pqSignalForNits(203f)
        val redThresholdSignal = pqSignalForNits(1_000f)
        val cr = (redThresholdSignal - diffuseSignal) / 1.4746f
        val warmCrCode = ceil(512f + 896f * cr).toInt()
        val pressure = FrameAnalysisMath.computePqHighlightPressure(
            frame = p010Frame(
                yCode = limitedPqCodeForNits(203f),
                vCode = warmCrCode
            ),
            maxStatsLongEdge = 320
        )

        assertEquals(0f, pressure.luminanceFraction, 1e-6f)
        assertEquals(1f, pressure.redChannelFraction, 1e-6f)
    }

    @Test
    fun computePqHighlightPressure_doesNotFlagDiffuseWhite() {
        val pressure = FrameAnalysisMath.computePqHighlightPressure(
            frame = p010Frame(yCode = limitedPqCodeForNits(203f)),
            maxStatsLongEdge = 320
        )

        assertEquals(0f, pressure.luminanceFraction, 1e-6f)
        assertEquals(0f, pressure.redChannelFraction, 1e-6f)
    }

    @Test
    fun computePqHighlightPressure_returnsZeroForUnsupportedOrEmptyFrames() {
        val unsupported = FrameAnalysisMath.computePqHighlightPressure(
            frame = p010Frame(yCode = limitedPqCodeForNits(1_000f)).copy(bitDepth = 12),
            maxStatsLongEdge = 320
        )
        assertEquals(0f, unsupported.luminanceFraction, 1e-6f)
        assertEquals(0f, unsupported.redChannelFraction, 1e-6f)

        val empty = FrameAnalysisMath.computePqHighlightPressure(
            frame = p010Frame(yCode = 64).copy(
                yBytes = ByteArray(0),
                uBytes = ByteArray(0),
                vBytes = ByteArray(0)
            ),
            maxStatsLongEdge = 320
        )
        assertEquals(0f, empty.luminanceFraction, 1e-6f)
        assertEquals(0f, empty.redChannelFraction, 1e-6f)
    }

    @Test
    fun exactLookupAndCombinedTraversalMatchGenericEquationsForEverySample() {
        val width = 64
        val height = 48
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val random = Random(0x4E504351L)
        val y = ByteArray(width * height).also(random::nextBytes)
        val u = ByteArray(chromaWidth * chromaHeight).also(random::nextBytes)
        val v = ByteArray(chromaWidth * chromaHeight).also(random::nextBytes)
        val compact = RawYuvFrame(
            width = width,
            height = height,
            yBytes = y,
            uBytes = u,
            vBytes = v,
            yRowStride = width,
            yPixelStride = 1,
            uRowStride = chromaWidth,
            vRowStride = chromaWidth,
            uPixelStride = 1,
            vPixelStride = 1,
            timestamp = 0L,
            bitDepth = 8
        )
        val paddedYStride = width + 3
        val paddedChromaStride = chromaWidth + 2
        val paddedY = ByteArray(paddedYStride * height)
        val paddedU = ByteArray(paddedChromaStride * chromaHeight)
        val paddedV = ByteArray(paddedChromaStride * chromaHeight)
        repeat(height) { row ->
            System.arraycopy(y, row * width, paddedY, row * paddedYStride, width)
        }
        repeat(chromaHeight) { row ->
            System.arraycopy(u, row * chromaWidth, paddedU, row * paddedChromaStride, chromaWidth)
            System.arraycopy(v, row * chromaWidth, paddedV, row * paddedChromaStride, chromaWidth)
        }
        val genericLayout = compact.copy(
            yBytes = paddedY,
            uBytes = paddedU,
            vBytes = paddedV,
            yRowStride = paddedYStride,
            uRowStride = paddedChromaStride,
            vRowStride = paddedChromaStride
        )

        FrameAnalysisMath.warmExactPqTables()
        listOf(203f, 1_000f, 4_000f, 10_000f).forEach { threshold ->
            val optimized = FrameAnalysisMath.computePqHighlightPressure(compact, width, threshold)
            val generic = FrameAnalysisMath.computePqHighlightPressure(genericLayout, width, threshold)
            assertEquals(generic.luminanceFraction, optimized.luminanceFraction, 0f)
            assertEquals(generic.redChannelFraction, optimized.redChannelFraction, 0f)
        }
        val combined = FrameAnalysisMath.computeHighlightMetrics(compact, width, sourcePqCandidate = true)
        assertEquals(
            FrameAnalysisMath.computeRedHighlightClipFraction(compact, width),
            combined.redClipFraction,
            0f
        )
        assertEquals(
            FrameAnalysisMath.computePqHighlightPressure(genericLayout, width),
            combined.pqPressure
        )

        // SDR uses the compact red-only Y+V traversal. Pin it independently so skipping the unused
        // U plane and PQ reconstruction can never change metering samples or clip classification.
        for (maxStatsLongEdge in listOf(1, 7, width)) {
            for (redClipCode in listOf(16, 173, 224, 235)) {
                val optimizedRedOnly = FrameAnalysisMath.computeHighlightMetrics(
                    frame = compact,
                    maxStatsLongEdge = maxStatsLongEdge,
                    sourcePqCandidate = false,
                    redClipCode = redClipCode
                )
                val genericRedOnly = FrameAnalysisMath.computeRedHighlightClipFraction(
                    frame = genericLayout,
                    maxStatsLongEdge = maxStatsLongEdge,
                    redClipCode = redClipCode
                )
                assertEquals(genericRedOnly, optimizedRedOnly.redClipFraction, 0f)
                assertEquals(PqHighlightPressure(0f, 0f), optimizedRedOnly.pqPressure)
            }
        }
    }
}
