package com.neoproc.neoproccamx.color

/**
 * Typed single source of truth for NeoGamut v2 (APEX) constants. Every constant
 * is tagged as PHYSICAL or AESTHETIC so validation and documentation can
 * distinguish colorimetric equations from look decisions.
 */
object ApexColorConfig {
    const val EPSILON = 0.000001f // PHYSICAL: divide guard for cone responses, xyY, and normalized whites.
    const val MATRIX_DETERMINANT_EPSILON = 0.00001f // PHYSICAL: rejects singular adaptation matrices before shader upload.

    const val DUAL_ILLUMINANT_CCT_A = 2856f // PHYSICAL: DNG ColorMatrix1 reference illuminant A.
    const val DUAL_ILLUMINANT_CCT_D65 = 6500f // PHYSICAL: DNG ColorMatrix2 daylight reference used for D65 interpolation.

    const val SURROUND_AVERAGE = 1.0f // PHYSICAL: CIECAM16 average surround factor F.
    const val SURROUND_DIM = 0.9f // PHYSICAL: CIECAM16 dim surround factor F.
    const val SURROUND_DARK = 0.8f // PHYSICAL: CIECAM16 dark surround factor F.

    const val GAMUT_COMPRESSION_KNEE = 0.85f // AESTHETIC: starts hue-radial compression at 85% of estimated boundary.
    const val TONE_MAP_TOE = 0.18f // AESTHETIC: preserves shadow separation around scene middle gray.
    const val TONE_MAP_SHOULDER = 0.78f // AESTHETIC: starts highlight shoulder before clipping pressure.
    const val TONE_MAP_WHITE_POINT = 11.2f // AESTHETIC: maps scene-linear highlights with an 11.2x white reference.
    const val HIGHLIGHT_DESATURATION = 0.35f // AESTHETIC: reduces saturated highlights without shifting hue.

    const val ML_WB_MAX_MODEL_BYTES = 2_097_152 // PHYSICAL: nonblocking on-device color model size ceiling.
    const val ML_WB_WEIGHT_SCALE = 0.6f // AESTHETIC: trusts ML AWB only in proportion to its confidence.
    const val SENSOR_WB_WEIGHT = 0.3f // PHYSICAL: keeps Camera2 AWB as a stable hardware-informed anchor.
    const val GRAY_EDGE_WB_WEIGHT = 0.1f // PHYSICAL: gray-edge estimate catches neutral scene bias.
    const val ML_WB_TEMPORAL_EMA_ALPHA = 0.15f // AESTHETIC: video WB stability target without hiding real light changes.
    const val MEMORY_HUE_MAX_DRIFT_DEGREES = 2f // AESTHETIC: protects skin, sky, and foliage memory colors.

    val CAT16_M16 = floatArrayOf( // PHYSICAL: standard CIE CAT16 cone response matrix.
        0.401288f, 0.650173f, -0.051461f,
        -0.250268f, 1.204414f, 0.045854f,
        -0.002079f, 0.048952f, 0.953127f
    )

    val CAT16_M16_INVERSE = floatArrayOf( // PHYSICAL: analytic inverse of the standard CAT16 matrix.
        1.86206786f, -1.01125463f, 0.14918677f,
        0.38752654f, 0.62144744f, -0.00897398f,
        -0.01584150f, -0.03412294f, 1.04996444f
    )

    val OKLAB_M1 = floatArrayOf( // PHYSICAL: exact Oklab linear RGB to LMS matrix requested for APEX grading.
        0.4122214708f, 0.5363325363f, 0.0514459929f,
        0.2119034982f, 0.6806995451f, 0.1073969566f,
        0.0883024619f, 0.2817188376f, 0.6299787005f
    )

    val OKLAB_M2 = floatArrayOf( // PHYSICAL: exact Oklab LMS cube-root to Lab matrix requested for APEX grading.
        0.2104542553f, 0.7936177850f, -0.0040720468f,
        1.9779984951f, -2.4285922050f, 0.4505937099f,
        0.0259040371f, 0.7827717662f, -0.8086757660f
    )

    enum class WhitePoint(val x: Float, val y: Float, val cctKelvin: Float) {
        A(0.44757f, 0.40745f, DUAL_ILLUMINANT_CCT_A), // PHYSICAL: CIE illuminant A tungsten validation white.
        TL84(0.38052f, 0.37713f, 4_100f), // PHYSICAL: fluorescent validation white for mixed indoor scenes.
        D50(0.34567f, 0.35850f, 5_003f), // PHYSICAL: CIE D50 print/reference white.
        D55(0.33242f, 0.34743f, 5_500f), // PHYSICAL: legacy Neo Wide Gamut source white.
        D65(0.31272f, 0.32903f, DUAL_ILLUMINANT_CCT_D65), // PHYSICAL: APEX default display/reference target white.
        D75(0.29902f, 0.31485f, 7_500f); // PHYSICAL: daylight validation white for cool scenes.

        val xyz: FloatArray
            get() = whitePointXyz(this)
    }

    fun whitePointXyz(whitePoint: WhitePoint): FloatArray =
        xyToXyz(whitePoint.x, whitePoint.y)

    fun xyToXyz(x: Float, y: Float): FloatArray {
        val safeY = if (kotlin.math.abs(y) < EPSILON) EPSILON else y
        return floatArrayOf(x / safeY, 1f, (1f - x - safeY) / safeY)
    }
}

enum class NeoGamutPipeline {
    V1,
    V2_APEX
}

enum class NeoGamutMemoryClass {
    NONE,
    SKIN,
    SKY,
    FOLIAGE
}
