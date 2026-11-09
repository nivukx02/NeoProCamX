package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal object FrameAnalysisMath {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun computeSceneStats(
        width: Int,
        height: Int,
        luma: ByteArray,
        maxStatsLongEdge: Int,
        waveformEnabled: Boolean
    ): SceneStats {
        val histogram = IntArray(256)
        val waveform = if (waveformEnabled) IntArray(64) else null
        val waveformCounts = if (waveformEnabled) IntArray(64) else null
        val effectiveMaxStatsLongEdge = max(1, maxStatsLongEdge)
        val step = max(1, max(width, height) / effectiveMaxStatsLongEdge)
        var lumaSum = 0L
        var sampleCount = 0

        for (y in 0 until height step step) {
            val rowOffset = y * width
            for (x in 0 until width step step) {
                val value = luma[rowOffset + x].toInt() and 0xFF
                histogram[value]++
                lumaSum += value.toLong()
                sampleCount++
                if (waveform != null && waveformCounts != null) {
                    val column = (x * waveform.size / width).coerceIn(0, waveform.lastIndex)
                    waveform[column] += value
                    waveformCounts[column]++
                }
            }
        }

        if (waveform != null && waveformCounts != null) {
            for (index in waveform.indices) {
                val count = waveformCounts[index].coerceAtLeast(1)
                waveform[index] /= count
            }
        }

        val averageLuma =
            if (sampleCount == 0) 0.5f else (lumaSum.toFloat() / sampleCount.toFloat() / 255f)
        return SceneStats(histogram, averageLuma, waveform)
    }

    /**
     * Reconstructed-red pre-clip code on the 8-bit limited-range scale.
     *
     * Code 235 is already clipped nominal white. Meter at code 224 (~95% signal) so Camera2's
     * multi-frame AE latency can lower exposure before the red photosites/YUV channel flatten.
     */
    const val DEFAULT_RED_CLIP_CODE = 224

    private const val LIMITED_Y_BLACK_8 = 16f
    private const val LIMITED_Y_RANGE_8 = 219f
    private const val LIMITED_CHROMA_MID_8 = 128f
    private const val LIMITED_CHROMA_RANGE_8 = 224f
    private const val LIMITED_Y_BLACK_10 = 64f
    private const val LIMITED_Y_RANGE_10 = 876f
    private const val LIMITED_CHROMA_MID_10 = 512f
    private const val LIMITED_CHROMA_RANGE_10 = 896f
    private const val BT2020_RED_FROM_CR = 1.4746f

    /**
     * Fraction of (down-sampled) pixels whose reconstructed RED channel is at/near clipping, 0..1.
     *
     * WHY: [computeSceneStats] builds a LUMA-only histogram, and BT.2020 luma weights red at just
     * 0.2627, so a warm subject can hard-clip its RED channel while luma still reads moderate — the
     * capture highlight-protect exposure (luma-driven) then never reacts and the red channel clips into
     * the shared HDR capture (the NDR/NIVUKX "red skin"). This reconstructs BT.2020-NCL red from the
     * limited-range Y + Cr(V) planes so the capture meter can react to the actual clipping channel, per
     * the ITU-T H.Sup15 lesson that luma Y' is not luminance. Both 8-bit YUV and P010 are accepted;
     * P010 is decoded for metering only and never changes the 10-bit capture/encoder data.
     */
    fun computeRedHighlightClipFraction(
        frame: RawYuvFrame,
        maxStatsLongEdge: Int,
        redClipCode: Int = DEFAULT_RED_CLIP_CODE
    ): Float {
        if (frame.bitDepth != 8 && frame.bitDepth != 10) return 0f
        val luma = frame.yBytes
        val cr = frame.vBytes
        val width = frame.width
        val height = frame.height
        if (luma.isEmpty() || cr.isEmpty() || width <= 0 || height <= 0) return 0f
        val effectiveMaxStatsLongEdge = max(1, maxStatsLongEdge)
        val step = max(1, max(width, height) / effectiveMaxStatsLongEdge)
        var total = 0
        var redClipped = 0
        var y = 0
        while (y < height) {
            val yRow = y * frame.yRowStride
            val crRow = (y / 2) * frame.vRowStride
            var x = 0
            while (x < width) {
                val yIdx = yRow + x * frame.yPixelStride
                val crIdx = crRow + (x / 2) * frame.vPixelStride
                if (yIdx in luma.indices && crIdx in cr.indices) {
                    val yCode = readCode(luma, yIdx, frame.bitDepth)
                    val crCode = readCode(cr, crIdx, frame.bitDepth)
                    if (yCode != null && crCode != null) {
                        val ySignal: Float
                        val crSignal: Float
                        if (frame.bitDepth == 10) {
                            ySignal = (yCode - LIMITED_Y_BLACK_10) / LIMITED_Y_RANGE_10
                            crSignal = (crCode - LIMITED_CHROMA_MID_10) / LIMITED_CHROMA_RANGE_10
                        } else {
                            ySignal = (yCode - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
                            crSignal = (crCode - LIMITED_CHROMA_MID_8) / LIMITED_CHROMA_RANGE_8
                        }
                        val reconstructedRed = ySignal + BT2020_RED_FROM_CR * crSignal
                        val redPreClipSignal =
                            (redClipCode.coerceIn(16, 235) - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
                        total++
                        if (reconstructedRed >= redPreClipSignal) redClipped++
                    }
                }
                x += step
            }
            y += step
        }
        if (total == 0) return 0f
        return (redClipped.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /** Read an 8-bit code or a little-endian, MSB-aligned P010 10-bit code. */
    private fun readCode(bytes: ByteArray, index: Int, bitDepth: Int): Float? {
        if (bitDepth == 8) {
            if (index !in bytes.indices) return null
            return (bytes[index].toInt() and 0xFF).toFloat()
        }
        if (index !in bytes.indices || index + 1 !in bytes.indices) return null
        val lo = bytes[index].toInt() and 0xFF
        val hi = bytes[index + 1].toInt() and 0xFF
        return (((hi shl 8) or lo) ushr 6).coerceIn(0, 1023).toFloat()
    }

    fun buildFocusPeakingMask(
        width: Int,
        height: Int,
        luma: ByteArray,
        maxMaskLongEdge: Int,
        peakingIntensity: Float,
        focusRegion: Triple<Float, Float, Float>? = null
    ): FocusPeakingMask {
        val strictHighEnd = strictHighEndEnabled()
        val effectiveMaxMaskLongEdge = max(1, maxMaskLongEdge)
        val step = max(1, max(width, height) / effectiveMaxMaskLongEdge)
        val maskWidth = max(3, width / step)
        val maskHeight = max(3, height / step)
        val alpha = ByteArray(maskWidth * maskHeight)
        val threshold = if (strictHighEnd) {
            (126f - peakingIntensity * 88f).coerceIn(20f, 126f)
        } else {
            (132f - peakingIntensity * 82f).coerceIn(28f, 132f)
        }
        val regionCenterX = focusRegion?.first?.coerceIn(0f, 1f)?.times(maskWidth - 1)
        val regionCenterY = focusRegion?.second?.coerceIn(0f, 1f)?.times(maskHeight - 1)
        val regionRadius = focusRegion?.third?.coerceIn(0f, 1f)
            ?.times(max(maskWidth, maskHeight).toFloat())
            ?.coerceAtLeast(0.25f)

        for (y in 1 until maskHeight - 1) {
            val srcY = y * step
            for (x in 1 until maskWidth - 1) {
                val srcX = x * step
                val idx = srcY * width + srcX
                val center = luma[idx].toInt() and 0xFF
                val left = luma[idx - step].toInt() and 0xFF
                val right = luma[idx + step].toInt() and 0xFF
                val up = luma[idx - step * width].toInt() and 0xFF
                val down = luma[idx + step * width].toInt() and 0xFF
                val gx = right - left
                val gy = down - up
                val baseMagnitude = max(abs(gx), abs(gy)) + ((abs(gx) + abs(gy)) / 2)
                val localRange = max(max(max(left, right), max(up, down)), center) -
                    minOf(minOf(left, right), minOf(up, down), center)
                val adaptiveThreshold = (threshold - localRange * if (strictHighEnd) 0.28f else 0.22f)
                    .coerceIn(if (strictHighEnd) 14f else 18f, 132f)
                val response = (baseMagnitude - adaptiveThreshold).coerceAtLeast(0f)
                val regionGain = if (
                    regionCenterX != null &&
                    regionCenterY != null &&
                    regionRadius != null
                ) {
                    val dx = x.toFloat() - regionCenterX
                    val dy = y.toFloat() - regionCenterY
                    val normalizedDistance = sqrt(dx * dx + dy * dy) / regionRadius
                    when {
                        normalizedDistance <= 1f -> 1f + (1f - normalizedDistance) *
                            if (strictHighEnd) 0.30f else 0.35f
                        normalizedDistance <= 2f -> (2f - normalizedDistance) *
                            if (strictHighEnd) 0.28f else 0.33f
                        else -> 0f
                    }
                } else {
                    1f
                }
                alpha[y * maskWidth + x] =
                    ((response * (if (strictHighEnd) 3.5f else 3.1f) * regionGain).coerceIn(0f, 255f)).toInt().toByte()
            }
        }

        return FocusPeakingMask(maskWidth, maskHeight, alpha)
    }

    fun buildZebraMask(
        width: Int,
        height: Int,
        luma: ByteArray,
        maxMaskLongEdge: Int,
        zebraThreshold: Float
    ): ZebraMask {
        val strictHighEnd = strictHighEndEnabled()
        val effectiveMaxMaskLongEdge = max(1, maxMaskLongEdge)
        val step = max(1, max(width, height) / effectiveMaxMaskLongEdge)
        val maskWidth = max(2, width / step)
        val maskHeight = max(2, height / step)
        val alpha = ByteArray(maskWidth * maskHeight)
        val threshold = (HudIreScale.zebraTriggerCode8(zebraThreshold) - if (strictHighEnd) 4 else 0).coerceIn(0, 255)

        for (y in 0 until maskHeight) {
            val srcY = y * step
            for (x in 0 until maskWidth) {
                val srcX = x * step
                val value = luma[srcY * width + srcX].toInt() and 0xFF
                alpha[y * maskWidth + x] = if (value >= threshold) 0x7F.toByte() else 0x00.toByte()
            }
        }

        return ZebraMask(maskWidth, maskHeight, alpha)
    }
}
