package com.neoproc.neoproccamx.snce

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * SNCE v2.0 HVC — Stage 05 (CCCM-V4): cortical colour constancy.
 *
 * Two cooperating mechanisms, both operating on a downsampled linear working-RGB proxy
 * (the same [SnceThumbnail] domain as Stage 01) so the cost is bounded:
 *  - Multi-Scale Retinex with colour restoration (MSRCR, directive 05.1) discounts the
 *    illuminant by dividing out a multi-scale local average in log space.
 *  - Semantic-region constancy (directive 05.2) gives each segmented region its own
 *    gray-world illuminant discount and blends it with the global result by confidence.
 *
 * Existing MediaPipe masks (SegmentationEngine) feed [semanticRegionConstancy] via
 * [RegionMask] — that is the wiring the architecture map flagged as missing (segmentation
 * present but decoupled from colour).
 */
object SnceV4ColorConstancy {

    val DEFAULT_SIGMAS = floatArrayOf(15f, 80f, 250f)
    val DEFAULT_WEIGHTS = floatArrayOf(0.25f, 0.40f, 0.35f)

    /** A semantic region: per-pixel mask weight in [0,1] plus a detection confidence. */
    class RegionMask(val mask: FloatArray, val confidence: Float)

    // ------------------------------------------------------------------
    // Separable Gaussian blur on a single channel (clamped edges).
    // ------------------------------------------------------------------
    fun gaussianBlur(channel: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val s = sigma.coerceAtLeast(0.5f)
        val maxRadius = maxOf(width, height)
        val radius = (2.5f * s).roundToInt().coerceIn(1, maxRadius)
        val kernel = FloatArray(2 * radius + 1)
        var sum = 0f
        for (k in -radius..radius) {
            val w = exp(-(k * k) / (2f * s * s))
            kernel[k + radius] = w
            sum += w
        }
        val inv = 1f / sum
        for (i in kernel.indices) kernel[i] *= inv

        val temp = FloatArray(width * height)
        // horizontal
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    val xx = (x + k).coerceIn(0, width - 1)
                    acc += channel[row + xx] * kernel[k + radius]
                }
                temp[row + x] = acc
            }
        }
        val out = FloatArray(width * height)
        // vertical
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    val yy = (y + k).coerceIn(0, height - 1)
                    acc += temp[yy * width + x] * kernel[k + radius]
                }
                out[y * width + x] = acc
            }
        }
        return out
    }

    /** Single-scale retinex: log(c) - log(blur_sigma(c)). */
    fun singleScaleRetinex(channel: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val blurred = gaussianBlur(channel, width, height, sigma)
        return FloatArray(channel.size) {
            SnceColorPrimitives.safeLog(channel[it]) - SnceColorPrimitives.safeLog(blurred[it])
        }
    }

    /** Weighted multi-scale retinex for one channel (directive 05.1). */
    fun multiScaleRetinex(
        channel: FloatArray,
        width: Int,
        height: Int,
        sigmas: FloatArray = DEFAULT_SIGMAS,
        weights: FloatArray = DEFAULT_WEIGHTS
    ): FloatArray {
        val out = FloatArray(channel.size)
        for (s in sigmas.indices) {
            val ssr = singleScaleRetinex(channel, width, height, sigmas[s])
            val w = weights[s]
            for (i in out.indices) out[i] += ssr[i] * w
        }
        return out
    }

    /**
     * MSRCR on interleaved RGB (directive 05.1, with colour restoration to undo the grey-out).
     * Returns interleaved RGB normalised back toward the input mean luminance.
     */
    fun msrcr(
        thumb: SnceThumbnail,
        sigmas: FloatArray = DEFAULT_SIGMAS,
        weights: FloatArray = DEFAULT_WEIGHTS,
        colorRestoreGain: Float = 125f,
        colorRestoreOffset: Float = 6f
    ): FloatArray {
        val n = thumb.pixelCount()
        val r = FloatArray(n) { thumb.rgb[it * 3] }
        val g = FloatArray(n) { thumb.rgb[it * 3 + 1] }
        val b = FloatArray(n) { thumb.rgb[it * 3 + 2] }
        val msrR = multiScaleRetinex(r, thumb.width, thumb.height, sigmas, weights)
        val msrG = multiScaleRetinex(g, thumb.width, thumb.height, sigmas, weights)
        val msrB = multiScaleRetinex(b, thumb.width, thumb.height, sigmas, weights)

        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val sum = (r[i] + g[i] + b[i]).coerceAtLeast(SnceColorPrimitives.EPS)
            // colour restoration function (MSRCR): log-weighted chromatic ratio
            val crfR = SnceColorPrimitives.safeLog(colorRestoreOffset + colorRestoreGain * r[i] / sum)
            val crfG = SnceColorPrimitives.safeLog(colorRestoreOffset + colorRestoreGain * g[i] / sum)
            val crfB = SnceColorPrimitives.safeLog(colorRestoreOffset + colorRestoreGain * b[i] / sum)
            out[i * 3] = msrR[i] * crfR
            out[i * 3 + 1] = msrG[i] * crfG
            out[i * 3 + 2] = msrB[i] * crfB
        }
        return out
    }

    /**
     * Per-region gray-world constancy blended with the global result (directive 05.2).
     * Each region's mean is driven toward neutral (gray-world gain), weighted by its mask
     * and confidence; alphaSemantic caps how much semantic constancy overrides global.
     */
    fun semanticRegionConstancy(
        thumb: SnceThumbnail,
        regions: List<RegionMask>,
        alphaSemantic: Float = 0.60f
    ): FloatArray {
        val n = thumb.pixelCount()
        val out = thumb.rgb.copyOf()
        for (region in regions) {
            if (region.mask.size != n) continue
            // region gray-world mean
            var sr = 0f; var sg = 0f; var sb = 0f; var wsum = 0f
            for (i in 0 until n) {
                val w = region.mask[i]
                if (w <= 0f) continue
                sr += thumb.rgb[i * 3] * w
                sg += thumb.rgb[i * 3 + 1] * w
                sb += thumb.rgb[i * 3 + 2] * w
                wsum += w
            }
            if (wsum < SnceColorPrimitives.EPS) continue
            val mr = sr / wsum; val mg = sg / wsum; val mb = sb / wsum
            val grayLevel = (mr + mg + mb) / 3f
            // gains that pull the region mean toward neutral
            val gainR = grayLevel / mr.coerceAtLeast(SnceColorPrimitives.EPS)
            val gainG = grayLevel / mg.coerceAtLeast(SnceColorPrimitives.EPS)
            val gainB = grayLevel / mb.coerceAtLeast(SnceColorPrimitives.EPS)
            val alpha = (alphaSemantic * region.confidence).coerceIn(0f, 1f)
            for (i in 0 until n) {
                val w = region.mask[i] * alpha
                if (w <= 0f) continue
                out[i * 3] = out[i * 3] * (1f - w) + (thumb.rgb[i * 3] * gainR) * w
                out[i * 3 + 1] = out[i * 3 + 1] * (1f - w) + (thumb.rgb[i * 3 + 1] * gainG) * w
                out[i * 3 + 2] = out[i * 3 + 2] * (1f - w) + (thumb.rgb[i * 3 + 2] * gainB) * w
            }
        }
        return out
    }
}
