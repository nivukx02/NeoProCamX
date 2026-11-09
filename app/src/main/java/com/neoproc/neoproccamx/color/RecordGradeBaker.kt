package com.neoproc.neoproccamx.color

/** Row-major 3x3 grade matrix + pivoted-contrast scalar for the offscreen-EGL record shader. */
internal class RecordGradeSpec(val matrix: FloatArray, val contrast: Float)

/**
 * Bakes a DISPLAY-REFERRED look (luma-preserving saturation + optional warmth) plus a pivoted
 * contrast scalar for the SDR record-grade shader stage.
 *
 * Display-referred (applied to the gamma/HLG-encoded signal) is the standard, correct model for
 * real-time SDR grading and 3D LUTs — it deliberately does NOT perform a linear-light primary
 * rotation (which would be colorimetrically wrong applied to encoded RGB). The app's saturation
 * and contrast are multiplicative with 1.0 == neutral (see ColorScience.isNeutralLook), so a
 * neutral look bakes to the exact identity matrix + contrast 1.0 and recorded pixels are
 * bit-identical. Pure JVM, unit-testable.
 */
internal object RecordGradeBaker {
    private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun bake(saturation: Float, contrast: Float, warmth: Float, isNeutral: Boolean): RecordGradeSpec {
        if (isNeutral) return RecordGradeSpec(identity(), 1f)
        val sat = saturation.coerceIn(0f, 2f)
        val warm = warmth.coerceIn(-1f, 1f)
        // Luma-preserving saturation matrix (row-major): out = mix(luma, in, sat).
        // M[i][j] = lw[j] * (1 - sat) + (i == j ? sat : 0).
        val m = FloatArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                m[i * 3 + j] = LUMA[j] * (1f - sat) + if (i == j) sat else 0f
            }
        }
        // Warmth: gentle per-channel gain (R up / B down), scaling the R and B output rows.
        val rGain = 1f + warm * 0.12f
        val bGain = 1f - warm * 0.12f
        for (j in 0..2) {
            m[0 * 3 + j] *= rGain
            m[2 * 3 + j] *= bGain
        }
        return RecordGradeSpec(m, contrast.coerceIn(0.5f, 2f))
    }
}
