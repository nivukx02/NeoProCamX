package com.neoproc.neoproccamx.rendering

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * s4-f06 / CP23 — host-side port of the `denoise.comp` `waveletDenoise` soft-threshold stage: the
 * flat-region detail-safe algorithm the live `nivukx_denoise.comp` (s4-f01) does NOT have. Used by
 * [WaveletDetailSafeTest] to PROVE the dormant denoise.comp carries unique value (denoises flats,
 * preserves edges), justifying the CP23 WIRE-eligible verdict over RETIRE.
 */
object WaveletDetailSafeReference {

    private fun at(img: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, w - 1); val cy = y.coerceIn(0, h - 1)
        return img[cy * w + cx]
    }

    /**
     * Single-channel port of denoise.comp::waveletDenoise — 4-neighbour high-pass detail, soft
     * wavelet thresholding (kills sub-threshold noise, keeps above-threshold structure), low-pass
     * reconstruction. [noiseLevel] scales the soft threshold exactly as the shader does.
     */
    fun waveletDenoise(img: FloatArray, w: Int, h: Int, x: Int, y: Int, threshold: Float, noiseLevel: Float): Float {
        val center = at(img, w, h, x, y)
        val n = at(img, w, h, x, y - 1); val s = at(img, w, h, x, y + 1)
        val e = at(img, w, h, x + 1, y); val wst = at(img, w, h, x - 1, y)
        val detailH = center - (e + wst) * 0.5f
        val detailV = center - (n + s) * 0.5f
        val detailStrength = threshold * noiseLevel * 4.0f
        val softH = sign(detailH) * max(abs(detailH) - detailStrength, 0.0f)
        val softV = sign(detailV) * max(abs(detailV) - detailStrength, 0.0f)
        val base = (n + s + e + wst) * 0.25f
        return base + (softH + softV) * 0.5f
    }
}
