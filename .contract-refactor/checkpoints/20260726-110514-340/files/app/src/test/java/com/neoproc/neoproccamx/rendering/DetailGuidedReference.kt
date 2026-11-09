package com.neoproc.neoproccamx.rendering

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * s4-f02 / CP24 — host-side faithful reference of `detail_preservation.comp` and
 * `guided_upsample.comp`, including the new no-downgrade gates. Ports the shader math line-for-line
 * so [DetailGuidedEliteTest] can lock the detail mask + joint-bilateral upsample on the JVM with NO
 * device. Default (gates off) reproduces today's output; the gates only switch on the elite paths.
 */
object DetailGuidedReference {

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private val SOBEL_X = arrayOf(
        floatArrayOf(-1f, 0f, 1f), floatArrayOf(-2f, 0f, 2f), floatArrayOf(-1f, 0f, 1f)
    )
    private val SOBEL_Y = arrayOf(
        floatArrayOf(-1f, -2f, -1f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 2f, 1f)
    )

    private fun luma709(r: Float, g: Float, b: Float) = r * 0.299f + g * 0.587f + b * 0.114f

    private fun lumaAt(rgb: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, w - 1); val cy = y.coerceIn(0, h - 1)
        val i = (cy * w + cx) * 3
        return luma709(rgb[i], rgb[i + 1], rgb[i + 2])
    }

    /** Port of detail_preservation.comp main() — returns the detail-mask value at ([px],[py]). */
    fun detailMask(
        rgb: FloatArray, w: Int, h: Int, px: Int, py: Int,
        textureThreshold: Float = 0.005f, edgeTolerance: Float = 3.5f,
        noiseAware: Boolean = false, noiseSigma: Float = 0.04f,
        haloGuard: Boolean = false, edgeHi: Float = 0.10f
    ): Float {
        var jXX = 0f; var jYY = 0f; var jXY = 0f
        var sumL = 0f; var sumL2 = 0f
        var gradX = 0f; var gradY = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val luma = lumaAt(rgb, w, h, px + dx, py + dy)
            val wX = SOBEL_X[dy + 1][dx + 1]; val wY = SOBEL_Y[dy + 1][dx + 1]
            val gX = luma * wX; val gY = luma * wY
            jXX += gX * gX; jYY += gY * gY; jXY += gX * gY
            sumL += luma; sumL2 += luma * luma
            gradX += gX; gradY += gY
        }
        val trace = jXX + jYY
        val det = jXX * jYY - jXY * jXY
        val diff = sqrt(max(0f, trace * trace - 4f * det))
        val lambda1 = 0.5f * (trace + diff)
        val lambda2 = 0.5f * (trace - diff)
        var mask = 0f
        if (lambda2 > textureThreshold) {
            val aniso = lambda1 / (lambda2 + 1e-6f)
            if (aniso < edgeTolerance) mask = smoothstep(textureThreshold, textureThreshold * 4f, lambda2)
        }
        if (noiseAware) {
            val localVar = max(sumL2 / 9f - (sumL / 9f) * (sumL / 9f), 0f)
            val noiseFloor = noiseSigma * noiseSigma
            mask *= smoothstep(noiseFloor * 2f, noiseFloor * 6f, localVar)
        }
        if (haloGuard) {
            val gradMag = sqrt(gradX * gradX + gradY * gradY)
            mask *= (1f - smoothstep(edgeHi, edgeHi * 3f, gradMag))
        }
        return mask
    }

    private fun gaussian(x: Float, sigma: Float) = exp(-(x * x) / (2f * sigma * sigma))

    /** Port of guided_upsample.comp main() — returns the upsampled value at full-res ([fx],[fy]). */
    fun guidedUpsample(
        guideRgb: FloatArray, gw: Int, gh: Int,
        low: FloatArray, lw: Int, lh: Int,
        fx: Int, fy: Int,
        sigmaS: Float = 3.0f, sigmaR: Float = 0.1f, scale: Int = 4,
        edgeTighten: Boolean = false, tightenK: Float = 8.0f
    ): Float {
        val guideLuma = lumaAt(guideRgb, gw, gh, fx, fy)
        var effSigmaR = sigmaR
        if (edgeTighten) {
            val gx = abs(lumaAt(guideRgb, gw, gh, fx + 1, fy) - lumaAt(guideRgb, gw, gh, fx - 1, fy))
            val gy = abs(lumaAt(guideRgb, gw, gh, fx, fy + 1) - lumaAt(guideRgb, gw, gh, fx, fy - 1))
            val gmag = sqrt(gx * gx + gy * gy)
            effSigmaR = sigmaR / (1f + tightenK * gmag)
        }
        val lowX = fx / scale; val lowY = fy / scale
        val radius = (sigmaS * 2f).toInt()
        var sumVal = 0f; var sumW = 0f
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val nlx = lowX + dx; val nly = lowY + dy
            if (nlx < 0 || nlx >= lw || nly < 0 || nly >= lh) continue
            val nfx = nlx * scale; val nfy = nly * scale
            val nGuide = lumaAt(guideRgb, gw, gh, nfx, nfy)
            val v = low[nly * lw + nlx]
            val distS = sqrt(((fx - nfx) * (fx - nfx) + (fy - nfy) * (fy - nfy)).toFloat())
            val distR = abs(guideLuma - nGuide)
            val w = gaussian(distS, sigmaS * scale) * gaussian(distR, effSigmaR)
            sumVal += v * w; sumW += w
        }
        return if (sumW > 0f) sumVal / sumW else low[lowY.coerceIn(0, lh - 1) * lw + lowX.coerceIn(0, lw - 1)]
    }

    /** Naive box/bilinear upsample (no guide) — the bleed-prone baseline the joint filter beats. */
    fun naiveUpsample(low: FloatArray, lw: Int, lh: Int, fx: Int, fy: Int, scale: Int): Float {
        val lowX = (fx / scale).coerceIn(0, lw - 1); val lowY = (fy / scale).coerceIn(0, lh - 1)
        return low[lowY * lw + lowX]
    }
}
