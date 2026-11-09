package com.neoproc.neoproccamx.camera

/**
 * Converts between Camera2 RGGB auto-white-balance channel gains
 * ([android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS]) and the CIE 1976
 * u'v' chromaticity of the white point those gains neutralize.
 *
 * The color-drift budget ([VideoColorStabilityPlan.maxFrameToFrameColorDriftUv]) and the
 * temporal stabilizer ([VideoColorTemporalStabilityPolicy]) both work in u'v'. The HAL,
 * however, only ever hands us the *measured* white point as an RGGB gain vector. This
 * object is the bridge that lets the stabilizer see and bound that measured signal.
 *
 * ## Model
 * AWB gains are inversely proportional to the illuminant the sensor sees: a channel that
 * reads low needs a high gain to be neutralized. The neutralized white point in linear
 * camera RGB is therefore `(1/rGain, 1/gGain, 1/bGain)` (green averaged across the two
 * Bayer greens). That linear RGB is mapped through the standard Rec.709 / sRGB D65
 * primaries to CIE XYZ and then to u'v'.
 *
 * The transform depends only on channel *ratios*, so it is invariant to the absolute scale
 * of the gain vector — exactly the property a chromaticity coordinate must have. It is also
 * an exact inverse pair: [uvToRggbGains] ∘ [rggbGainsToUv] reproduces the input gains up to
 * the green-normalization the gains are defined against (green gain = 1). Equal gains map to
 * D65 (`u'=0.1978, v'=0.4683`), matching [VideoColorTemporalStabilityPolicy]'s neutral
 * fallback.
 *
 * This is a deterministic, device-independent transform: it is *not* a colorimetric
 * reconstruction of the sensor's true illuminant (that would need the per-device forward
 * matrix). It is a stable, reversible chromaticity space in which frame-to-frame drift is a
 * meaningful, budgetable distance — which is all the temporal stabilizer needs.
 *
 * Thread safety: stateless; safe from any thread.
 */
internal object WhiteBalanceChromaticity {

    // Linear Rec.709 / sRGB (D65) RGB -> CIE XYZ.
    private const val M00 = 0.4124f; private const val M01 = 0.3576f; private const val M02 = 0.1805f
    private const val M10 = 0.2126f; private const val M11 = 0.7152f; private const val M12 = 0.0722f
    private const val M20 = 0.0193f; private const val M21 = 0.1192f; private const val M22 = 0.9505f

    // Inverse of the matrix above: CIE XYZ -> linear Rec.709 / sRGB (D65) RGB.
    private const val I00 = 3.2406f; private const val I01 = -1.5372f; private const val I02 = -0.4986f
    private const val I10 = -0.9689f; private const val I11 = 1.8758f; private const val I12 = 0.0415f
    private const val I20 = 0.0557f; private const val I21 = -0.2040f; private const val I22 = 1.0570f

    /**
     * Maps an RGGB gain vector to the u'v' chromaticity of the white point it neutralizes.
     *
     * Returns `null` when the gains are unusable (non-finite or non-positive), so callers can
     * cleanly skip a garbage capture result rather than poison their temporal state.
     */
    fun rggbGainsToUv(red: Float, greenEven: Float, greenOdd: Float, blue: Float): ColorChromaticity? {
        if (!red.isFinite() || !greenEven.isFinite() || !greenOdd.isFinite() || !blue.isFinite()) return null
        val gMean = (greenEven + greenOdd) * 0.5f
        if (red <= 0f || gMean <= 0f || blue <= 0f) return null

        // White point in linear camera RGB is the reciprocal of the gains.
        val r = 1f / red
        val g = 1f / gMean
        val b = 1f / blue

        val x = M00 * r + M01 * g + M02 * b
        val y = M10 * r + M11 * g + M12 * b
        val z = M20 * r + M21 * g + M22 * b

        val denom = x + 15f * y + 3f * z
        if (denom <= 0f || !denom.isFinite()) return null

        val u = 4f * x / denom
        val v = 9f * y / denom
        if (!u.isFinite() || !v.isFinite()) return null
        return ColorChromaticity(u, v)
    }

    /**
     * Maps a CIE 1976 u'v' chromaticity to CIE xy, or `null` if it cannot be realized. Used to bridge
     * the governor's stabilized white point into the DNG Kelvin+tint domain
     * ([DngWhiteBalanceMath.xyToKelvinTint]) for the device-true shot-stable WB fallback (P1.6).
     */
    fun uvToXy(uv: ColorChromaticity): FloatArray? {
        val u = uv.u
        val v = uv.v
        if (!u.isFinite() || !v.isFinite()) return null
        val d = 6f * u - 16f * v + 12f
        if (d == 0f || !d.isFinite()) return null
        val x = 9f * u / d
        val y = 4f * v / d
        if (!x.isFinite() || !y.isFinite() || y <= 0f) return null
        return floatArrayOf(x, y)
    }

    /**
     * Inverse of [rggbGainsToUv]. Returns green-normalized gains `[r, gEven, gOdd, b]` with the
     * green gain fixed at `1.0` (gains are scale-invariant, so the green reference is the
     * natural normalization). Returns `null` if the chromaticity cannot be realized as positive
     * gains.
     */
    fun uvToRggbGains(uv: ColorChromaticity): FloatArray? {
        val u = uv.u
        val v = uv.v
        if (!u.isFinite() || !v.isFinite()) return null

        // u'v' -> CIE xy.
        val d = 6f * u - 16f * v + 12f
        if (d == 0f || !d.isFinite()) return null
        val x = 9f * u / d
        val y = 4f * v / d
        if (!x.isFinite() || !y.isFinite() || y <= 0f) return null

        // xy (with Y = 1) -> XYZ.
        val bigX = x / y
        val bigY = 1f
        val bigZ = (1f - x - y) / y

        // XYZ -> linear camera RGB.
        val r = I00 * bigX + I01 * bigY + I02 * bigZ
        val g = I10 * bigX + I11 * bigY + I12 * bigZ
        val b = I20 * bigX + I21 * bigY + I22 * bigZ
        if (r <= 0f || g <= 0f || b <= 0f || !r.isFinite() || !g.isFinite() || !b.isFinite()) return null

        // Gains are the reciprocal of the white RGB, normalized to a green gain of 1.0.
        val rGain = g / r
        val bGain = g / b
        if (!rGain.isFinite() || !bGain.isFinite()) return null
        return floatArrayOf(rGain, 1f, 1f, bGain)
    }
}
