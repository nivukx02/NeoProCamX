package com.neoproc.neoproccamx.pipeline

import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * GRADE-SAFE elite-detail pass for the offline RAW→NeoLog develop, operating on a full-frame LINEAR
 * ACEScg plane (interleaved r,g,b per pixel) in place, BEFORE exposure + NeoLog encode.
 *
 * Three bounded stages — the "OmniClarity / NRLI-detail / denoise + sharpen" the RAW-LOG converter was
 * asked to gain, but DETAIL only (no creative colour):
 *   1. edge-aware denoise  — a 3×3 luminance-guided bilateral blend (smooths flats, spares edges);
 *   2. detail sharpen      — a fine luma unsharp mask (recovers micro-texture);
 *   3. OmniClarity clarity — a broad luma band-pass (midtone local contrast).
 *
 * Sharpen + clarity are applied COLOUR-PRESERVINGLY (scale RGB by the new/old luminance ratio, ratio
 * clamped to [MIN_LUMA_FACTOR, MAX_LUMA_FACTOR]) so hue/saturation are untouched and there is no ringing
 * blow-up; denoise blends per channel by at most [DENOISE_STRENGTH]. Every stage is bounded and negatives
 * are floored to 0, so the pass can only clean/sharpen — never clip highlights or invent colour. It runs
 * in scene-linear (the correct domain for spatial filtering), and because a channel that is exactly 0 stays
 * 0 (blend/scale of 0 is 0), a single-channel test image keeps its other channels black.
 *
 * Pure and deterministic (a function of the plane + dimensions only). No-op — leaving the plane
 * byte-identical — when [RawDevelopEliteDetailFlag] is OFF or the frame is smaller than the 3×3 kernel.
 */
internal object RawDevelopEliteDetailPolicy {

    /** Persistent scratch for repeated same-resolution playback/develop calls. */
    internal class ReusableWorkspace {
        private var sourceLuma = FloatArray(0)
        private var denoisedLuma = FloatArray(0)
        private var rowA = FloatArray(0)
        private var rowB = FloatArray(0)

        fun sourceLuma(size: Int): FloatArray =
            sourceLuma.takeIf { it.size == size } ?: FloatArray(size).also { sourceLuma = it }

        fun denoisedLuma(size: Int): FloatArray =
            denoisedLuma.takeIf { it.size == size } ?: FloatArray(size).also { denoisedLuma = it }

        fun rowA(size: Int): FloatArray =
            rowA.takeIf { it.size == size } ?: FloatArray(size).also { rowA = it }

        fun rowB(size: Int): FloatArray =
            rowB.takeIf { it.size == size } ?: FloatArray(size).also { rowB = it }
    }

    // ACEScg / AP1 luminance weights.
    private const val LUMA_R = 0.2722287168f
    private const val LUMA_G = 0.6740817658f
    private const val LUMA_B = 0.0536895174f

    /** Max fraction blended toward the edge-aware neighbourhood mean in flat regions. */
    private const val DENOISE_STRENGTH = 0.35f
    /** Luminance-difference scale (scene-linear) for the bilateral range weight. */
    private const val DENOISE_LUMA_SIGMA = 0.05f
    /**
     * Fine-detail unsharp gain. Kept deliberately LOW because this is a flat, gradeable LOG master: baking
     * strong acutance here would (a) amplify grain right after only a mild denoise and (b) double-sharpen
     * once a finisher sharpens downstream. Cinema-balanced at 0.30 (lowered from 0.45).
     */
    private const val SHARPEN_AMOUNT = 0.30f
    /** Broad midtone local-contrast (clarity) gain. */
    private const val CLARITY_AMOUNT = 0.18f

    private const val MIN_LUMA_FACTOR = 0.5f
    private const val MAX_LUMA_FACTOR = 2.0f
    private const val EPS = 1e-5f

    fun enhanceInPlace(
        acescg: FloatArray,
        width: Int,
        height: Int,
        workspace: ReusableWorkspace? = null
    ) {
        if (!RawDevelopEliteDetailFlag.enabled) return
        enhanceInPlaceForced(acescg, width, height, workspace)
    }

    /**
     * Bounded-memory form for the offline Vulkan develop path. The ACEScg plane remains in its direct
     * JNI buffer; only five scanlines are allocated, independent of frame height. The row-retirement
     * rules preserve the same neighbourhood and operation order as [enhanceInPlaceForced], so moving a
     * 4K frame off the Java heap changes storage only, not the detail algorithm or its strength.
     */
    fun enhanceDirectInPlace(acescg: FloatBuffer, width: Int, height: Int) {
        if (!RawDevelopEliteDetailFlag.enabled) return
        enhanceDirectInPlaceForced(acescg, width, height)
    }

    internal fun enhanceDirectInPlaceForced(
        acescg: FloatBuffer,
        width: Int,
        height: Int
    ) {
        if (width < 3 || height < 3) return
        val count = Math.multiplyExact(Math.multiplyExact(width, height), 3)
        require(!acescg.isReadOnly) { "ACEScg direct plane must be writable" }
        require(acescg.remaining() >= count) { "acescg direct plane too small for ${width}x$height" }
        val plane = acescg.slice()

        // Pass 1: edge-aware denoise. Delay committing row y until row y+1 has been evaluated, because
        // that is the last output row whose 3x3 source neighbourhood can reference row y.
        val denoiseRowA = FloatArray(width * 3)
        val denoiseRowB = FloatArray(width * 3)
        var previousRow = denoiseRowA
        var currentRow = denoiseRowB
        var previousRowIndex = -1
        var hasPreviousRow = false
        val invTwoSigma2 = 1f / (2f * DENOISE_LUMA_SIGMA * DENOISE_LUMA_SIGMA)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val lc = directLuma(plane, y * width + x)
                var wsum = 0f
                var sr = 0f
                var sg = 0f
                var sb = 0f
                val yLo = if (y > 0) y - 1 else 0
                val yHi = if (y < height - 1) y + 1 else height - 1
                val xLo = if (x > 0) x - 1 else 0
                val xHi = if (x < width - 1) x + 1 else width - 1
                for (yy in yLo..yHi) {
                    val row = yy * width
                    for (xx in xLo..xHi) {
                        val pixel = row + xx
                        val ld = lc - directLuma(plane, pixel)
                        val weight = exp(-(ld * ld) * invTwoSigma2)
                        val offset = pixel * 3
                        wsum += weight
                        sr += plane.get(offset) * weight
                        sg += plane.get(offset + 1) * weight
                        sb += plane.get(offset + 2) * weight
                    }
                }
                val sourceOffset = (y * width + x) * 3
                val rowOffset = x * 3
                if (wsum > EPS) {
                    val inv = 1f / wsum
                    val r = plane.get(sourceOffset)
                    val g = plane.get(sourceOffset + 1)
                    val b = plane.get(sourceOffset + 2)
                    currentRow[rowOffset] = r + (sr * inv - r) * DENOISE_STRENGTH
                    currentRow[rowOffset + 1] = g + (sg * inv - g) * DENOISE_STRENGTH
                    currentRow[rowOffset + 2] = b + (sb * inv - b) * DENOISE_STRENGTH
                } else {
                    currentRow[rowOffset] = plane.get(sourceOffset)
                    currentRow[rowOffset + 1] = plane.get(sourceOffset + 1)
                    currentRow[rowOffset + 2] = plane.get(sourceOffset + 2)
                }
            }
            if (hasPreviousRow) {
                putDirectRgbRow(plane, previousRowIndex, previousRow, width)
            }
            previousRowIndex = y
            val swap = previousRow
            previousRow = currentRow
            currentRow = swap
            hasPreviousRow = true
        }
        if (hasPreviousRow) putDirectRgbRow(plane, previousRowIndex, previousRow, width)

        // Pass 2: two 3x3 luma blurs followed by the colour-preserving detail/clarity scale. Three
        // precomputed blur rows are sufficient; compute row y+2 before modifying row y.
        var previousBlur: FloatArray? = null
        var currentBlur = FloatArray(width).also { fillDirectBoxBlurRow(plane, width, height, 0, it) }
        var nextBlur = FloatArray(width).also { fillDirectBoxBlurRow(plane, width, height, 1, it) }
        val initialSpare = FloatArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = y * width + x
                val base = directLuma(plane, pixel)
                if (base <= EPS) continue
                val detailFine = base - currentBlur[x]
                var broadSum = 0f
                var broadCount = 0
                val yLo = if (y > 0) y - 1 else 0
                val yHi = if (y < height - 1) y + 1 else height - 1
                val xLo = if (x > 0) x - 1 else 0
                val xHi = if (x < width - 1) x + 1 else width - 1
                for (yy in yLo..yHi) {
                    val row = when {
                        yy < y -> requireNotNull(previousBlur)
                        yy == y -> currentBlur
                        else -> nextBlur
                    }
                    for (xx in xLo..xHi) {
                        broadSum += row[xx]
                        broadCount++
                    }
                }
                val detailBroad = currentBlur[x] - broadSum / broadCount
                val newLuma = base + SHARPEN_AMOUNT * detailFine + CLARITY_AMOUNT * detailBroad
                val factor = (newLuma / base).coerceIn(MIN_LUMA_FACTOR, MAX_LUMA_FACTOR)
                val offset = pixel * 3
                val r = plane.get(offset) * factor
                val g = plane.get(offset + 1) * factor
                val b = plane.get(offset + 2) * factor
                plane.put(offset, if (r > 0f) r else 0f)
                plane.put(offset + 1, if (g > 0f) g else 0f)
                plane.put(offset + 2, if (b > 0f) b else 0f)
            }
            if (y + 1 < height) {
                val recycled = previousBlur ?: initialSpare
                previousBlur = currentBlur
                currentBlur = nextBlur
                nextBlur = recycled
                if (y + 2 < height) {
                    fillDirectBoxBlurRow(plane, width, height, y + 2, nextBlur)
                }
            }
        }
    }

    /**
     * The flag-free math, exposed for deterministic unit testing of the detail behaviour itself.
     * Requires [acescg] to hold at least width*height*3 interleaved floats.
     */
    internal fun enhanceInPlaceForced(
        acescg: FloatArray,
        width: Int,
        height: Int,
        workspace: ReusableWorkspace? = null
    ) {
        if (width < 3 || height < 3) return
        val count = width * height * 3
        require(acescg.size >= count) { "acescg plane too small for ${width}x$height" }

        val pixelCount = width * height
        val sourceLuma = workspace?.sourceLuma(pixelCount) ?: FloatArray(pixelCount)
        for (i in sourceLuma.indices) {
            val o = i * 3
            sourceLuma[i] = luma(acescg[o], acescg[o + 1], acescg[o + 2])
        }
        val denoisedLuma = workspace?.denoisedLuma(pixelCount) ?: FloatArray(pixelCount)
        edgeAwareDenoiseInPlace(acescg, sourceLuma, denoisedLuma, width, height, workspace)

        // Reuse the source-luma buffer as blurSmall to avoid one more full-frame allocation.
        box3x3Into(denoisedLuma, sourceLuma, width, height)
        detailAndClarityStreaming(acescg, denoisedLuma, sourceLuma, width, height)
    }

    private fun luma(r: Float, g: Float, b: Float): Float = LUMA_R * r + LUMA_G * g + LUMA_B * b

    private fun directLuma(plane: FloatBuffer, pixel: Int): Float {
        val offset = pixel * 3
        return luma(plane.get(offset), plane.get(offset + 1), plane.get(offset + 2))
    }

    private fun putDirectRgbRow(
        plane: FloatBuffer,
        rowIndex: Int,
        row: FloatArray,
        width: Int
    ) {
        val destination = rowIndex * width * 3
        for (component in row.indices) plane.put(destination + component, row[component])
    }

    private fun fillDirectBoxBlurRow(
        plane: FloatBuffer,
        width: Int,
        height: Int,
        y: Int,
        output: FloatArray
    ) {
        val yLo = if (y > 0) y - 1 else 0
        val yHi = if (y < height - 1) y + 1 else height - 1
        for (x in 0 until width) {
            val xLo = if (x > 0) x - 1 else 0
            val xHi = if (x < width - 1) x + 1 else width - 1
            var sum = 0f
            var count = 0
            for (yy in yLo..yHi) {
                val row = yy * width
                for (xx in xLo..xHi) {
                    sum += directLuma(plane, row + xx)
                    count++
                }
            }
            output[x] = sum / count
        }
    }

    /**
     * 3×3 luminance-guided bilateral, blended toward the source by [DENOISE_STRENGTH].
     *
     * The old implementation materialised a second full RGB plane. This version writes rows back only
     * after their original neighbours are no longer needed, so the math is unchanged but memory stays
     * bounded to two row buffers plus luma planes.
     */
    private fun edgeAwareDenoiseInPlace(
        srcAndDst: FloatArray,
        sourceLuma: FloatArray,
        denoisedLuma: FloatArray,
        width: Int,
        height: Int,
        workspace: ReusableWorkspace?
    ) {
        val invTwoSigma2 = 1f / (2f * DENOISE_LUMA_SIGMA * DENOISE_LUMA_SIGMA)
        var previousRowIndex = -1
        val rowSize = width * 3
        var previousRow = workspace?.rowA(rowSize) ?: FloatArray(rowSize)
        var currentRow = workspace?.rowB(rowSize) ?: FloatArray(rowSize)
        var hasPreviousRow = false
        for (y in 0 until height) {
            for (x in 0 until width) {
                val ci = y * width + x
                val lc = sourceLuma[ci]
                var wsum = 0f
                var sr = 0f
                var sg = 0f
                var sb = 0f
                val yLo = if (y > 0) y - 1 else 0
                val yHi = if (y < height - 1) y + 1 else height - 1
                val xLo = if (x > 0) x - 1 else 0
                val xHi = if (x < width - 1) x + 1 else width - 1
                for (yy in yLo..yHi) {
                    val rowN = yy * width
                    for (xx in xLo..xHi) {
                        val ni = rowN + xx
                        val ld = lc - sourceLuma[ni]
                        val wgt = exp(-(ld * ld) * invTwoSigma2)
                        val no = ni * 3
                        wsum += wgt
                        sr += srcAndDst[no] * wgt
                        sg += srcAndDst[no + 1] * wgt
                        sb += srcAndDst[no + 2] * wgt
                    }
                }
                val so = ci * 3
                val ro = x * 3
                if (wsum > EPS) {
                    val inv = 1f / wsum
                    currentRow[ro] = srcAndDst[so] + (sr * inv - srcAndDst[so]) * DENOISE_STRENGTH
                    currentRow[ro + 1] = srcAndDst[so + 1] + (sg * inv - srcAndDst[so + 1]) * DENOISE_STRENGTH
                    currentRow[ro + 2] = srcAndDst[so + 2] + (sb * inv - srcAndDst[so + 2]) * DENOISE_STRENGTH
                } else {
                    currentRow[ro] = srcAndDst[so]
                    currentRow[ro + 1] = srcAndDst[so + 1]
                    currentRow[ro + 2] = srcAndDst[so + 2]
                }
            }
            if (hasPreviousRow) {
                writeDenoisedRow(srcAndDst, denoisedLuma, previousRowIndex, previousRow, width)
            }
            previousRowIndex = y
            val swap = previousRow
            previousRow = currentRow
            currentRow = swap
            hasPreviousRow = true
        }
        if (hasPreviousRow) {
            writeDenoisedRow(srcAndDst, denoisedLuma, previousRowIndex, previousRow, width)
        }
    }

    private fun writeDenoisedRow(
        dst: FloatArray,
        denoisedLuma: FloatArray,
        rowIndex: Int,
        row: FloatArray,
        width: Int
    ) {
        val dstOffset = rowIndex * width * 3
        System.arraycopy(row, 0, dst, dstOffset, width * 3)
        val lumaOffset = rowIndex * width
        for (x in 0 until width) {
            val o = x * 3
            denoisedLuma[lumaOffset + x] = luma(row[o], row[o + 1], row[o + 2])
        }
    }

    /** Colour-preserving luma unsharp (fine) + band-pass clarity (broad), applied to [plane] in place. */
    private fun detailAndClarityStreaming(
        plane: FloatArray,
        denoisedLuma: FloatArray,
        blurSmall: FloatArray,
        width: Int,
        height: Int
    ) {
        val n = width * height
        for (i in 0 until n) {
            val base = denoisedLuma[i]
            if (base <= EPS) continue // black stays black; nothing to scale
            val detailFine = denoisedLuma[i] - blurSmall[i]
            val detailBroad = blurSmall[i] - box3x3At(blurSmall, i % width, i / width, width, height)
            val newLuma = base + SHARPEN_AMOUNT * detailFine + CLARITY_AMOUNT * detailBroad
            val factor = (newLuma / base).coerceIn(MIN_LUMA_FACTOR, MAX_LUMA_FACTOR)
            val o = i * 3
            val r = plane[o] * factor
            val g = plane[o + 1] * factor
            val b = plane[o + 2] * factor
            plane[o] = if (r > 0f) r else 0f
            plane[o + 1] = if (g > 0f) g else 0f
            plane[o + 2] = if (b > 0f) b else 0f
        }
    }

    /** 3×3 box blur with clamp-to-edge borders. Writes into [dst]. */
    private fun box3x3Into(src: FloatArray, dst: FloatArray, width: Int, height: Int) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                dst[y * width + x] = box3x3At(src, x, y, width, height)
            }
        }
    }

    private fun box3x3At(src: FloatArray, x: Int, y: Int, width: Int, height: Int): Float {
        val yLo = if (y > 0) y - 1 else 0
        val yHi = if (y < height - 1) y + 1 else height - 1
        val xLo = if (x > 0) x - 1 else 0
        val xHi = if (x < width - 1) x + 1 else width - 1
        var sum = 0f
        var cnt = 0
        for (yy in yLo..yHi) {
            val rowN = yy * width
            for (xx in xLo..xHi) {
                sum += src[rowN + xx]
                cnt++
            }
        }
        return sum / cnt
    }
}
