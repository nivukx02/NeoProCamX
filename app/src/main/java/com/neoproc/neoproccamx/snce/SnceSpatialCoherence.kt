package com.neoproc.neoproccamx.snce

/**
 * SNCE v2.0 HVC — Stage 08 (ASCE): adaptive spatial colour coherence.
 *
 * Keeps colour spatially coherent the way the eye expects: edge-preserving chroma
 * diffusion via a guided filter (directive 08.1), colour-banding elimination on smooth
 * regions (08.2), and sky-gradient perceptual shaping (08.3). Operates on a downsampled
 * proxy; the guided filter is the He-Sun-Tang O(1) box formulation.
 */
object SnceSpatialCoherence {

    // ------------------------------------------------------------------
    // Box mean (separable, O(1) per pixel via running sums) and guided filter.
    // ------------------------------------------------------------------
    fun boxMean(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val r = radius.coerceAtLeast(1)
        val temp = FloatArray(width * height)
        // horizontal running sum
        for (y in 0 until height) {
            val row = y * width
            var acc = 0f
            for (x in 0 until (r).coerceAtMost(width)) acc += src[row + x]
            for (x in 0 until width) {
                val add = x + r
                val sub = x - r - 1
                if (add < width) acc += src[row + add]
                if (sub >= 0) acc -= src[row + sub]
                val lo = (x - r).coerceAtLeast(0)
                val hi = (x + r).coerceAtMost(width - 1)
                temp[row + x] = acc / (hi - lo + 1)
            }
        }
        val out = FloatArray(width * height)
        for (x in 0 until width) {
            var acc = 0f
            for (y in 0 until (r).coerceAtMost(height)) acc += temp[y * width + x]
            for (y in 0 until height) {
                val add = y + r
                val sub = y - r - 1
                if (add < height) acc += temp[add * width + x]
                if (sub >= 0) acc -= temp[sub * width + x]
                val lo = (y - r).coerceAtLeast(0)
                val hi = (y + r).coerceAtMost(height - 1)
                out[y * width + x] = acc / (hi - lo + 1)
            }
        }
        return out
    }

    /** Guided filter: edge-preserving smoothing of [input] guided by [guide] (He et al. 2010). */
    fun guidedFilter(
        guide: FloatArray,
        input: FloatArray,
        width: Int,
        height: Int,
        radius: Int = 12,
        epsilon: Float = 0.005f
    ): FloatArray {
        val n = width * height
        val meanI = boxMean(guide, width, height, radius)
        val meanP = boxMean(input, width, height, radius)
        val corrI = boxMean(FloatArray(n) { guide[it] * guide[it] }, width, height, radius)
        val corrIP = boxMean(FloatArray(n) { guide[it] * input[it] }, width, height, radius)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val covIP = corrIP[i] - meanI[i] * meanP[i]
            a[i] = covIP / (varI + epsilon)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxMean(a, width, height, radius)
        val meanB = boxMean(b, width, height, radius)
        return FloatArray(n) { meanA[it] * guide[it] + meanB[it] }
    }

    /**
     * Edge-aware chroma diffusion (directive 08.1): smooth chroma where the luminance guide
     * is flat, keep it sharp at high-contrast edges. [luma] guides; [chroma] is smoothed.
     */
    fun coherentChroma(
        luma: FloatArray,
        chroma: FloatArray,
        width: Int,
        height: Int,
        radius: Int = 12,
        epsilon: Float = 0.005f,
        maxBlend: Float = 0.60f
    ): FloatArray {
        val smooth = guidedFilter(luma, chroma, width, height, radius, epsilon)
        val grad = gradientMagnitude(luma, width, height)
        val maxGrad = (grad.maxOrNull() ?: 0f).coerceAtLeast(SnceColorPrimitives.EPS)
        return FloatArray(width * height) { i ->
            val edge = grad[i] / maxGrad
            val alpha = (1f - edge) * maxBlend
            chroma[i] * (1f - alpha) + smooth[i] * alpha
        }
    }

    private fun gradientMagnitude(plane: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val xm = (x - 1).coerceAtLeast(0); val xp = (x + 1).coerceAtMost(width - 1)
            val ym = (y - 1).coerceAtLeast(0); val yp = (y + 1).coerceAtMost(height - 1)
            val gx = plane[y * width + xp] - plane[y * width + xm]
            val gy = plane[yp * width + x] - plane[ym * width + x]
            out[y * width + x] = kotlin.math.sqrt(gx * gx + gy * gy)
        }
        return out
    }

    /**
     * Colour-banding elimination (directive 08.2): a row whose hue is almost constant but
     * differs sharply from its neighbours is interpolated from rows +/-2 away.
     */
    fun eliminateBanding(
        hueRow: FloatArray,
        width: Int,
        height: Int,
        flatStdDegrees: Float = 1.5f,
        jumpDegrees: Float = 3.0f
    ): FloatArray {
        val out = hueRow.copyOf()
        for (y in 2 until height - 2) {
            val mean = rowMean(hueRow, width, y)
            val std = rowStd(hueRow, width, y, mean)
            val meanPrev = rowMean(hueRow, width, y - 1)
            val meanNext = rowMean(hueRow, width, y + 1)
            if (std < flatStdDegrees &&
                (kotlin.math.abs(mean - meanPrev) > jumpDegrees || kotlin.math.abs(mean - meanNext) > jumpDegrees)
            ) {
                for (x in 0 until width) {
                    out[y * width + x] = 0.5f * (hueRow[(y - 2) * width + x] + hueRow[(y + 2) * width + x])
                }
            }
        }
        return out
    }

    private fun rowMean(plane: FloatArray, width: Int, y: Int): Float {
        var s = 0f
        for (x in 0 until width) s += plane[y * width + x]
        return s / width
    }

    private fun rowStd(plane: FloatArray, width: Int, y: Int, mean: Float): Float {
        var s = 0f
        for (x in 0 until width) {
            val d = plane[y * width + x] - mean
            s += d * d
        }
        return kotlin.math.sqrt(s / width)
    }

    /**
     * Sky-gradient perceptual enhancement (directive 08.3): blend sky pixels toward the
     * natural zenith->horizon hue/chroma gradient. [elevationNorm] is 1 at the zenith, 0 at
     * the horizon; [skyConfidence] gates the blend.
     */
    fun skyGradient(
        hue: Float,
        chroma: Float,
        elevationNorm: Float,
        skyConfidence: Float,
        blend: Float = 0.35f
    ): Pair<Float, Float> {
        val e = elevationNorm.coerceIn(0f, 1f)
        val naturalHue = 220f - 25f * (1f - e)
        val naturalChroma = 48f - 20f * kotlin.math.sqrt(1f - e)
        val w = (blend * skyConfidence.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        return (hue + (naturalHue - hue) * w) to (chroma + (naturalChroma - chroma) * w)
    }
}
