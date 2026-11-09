package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt

internal data class RawSensorCalibrationPlan(
    val blackLevel: Int,
    /** Per-colour black offsets in semantic R, Gr, Gb, B order. */
    val blackLevelRggb: FloatArray,
    val whiteLevel: Int,
    val usedFallbackBlackLevel: Boolean,
    val diagnostics: List<String>
)

internal object RawSensorCalibrationPolicy {
    private const val DEFAULT_WHITE_LEVEL = 4095
    private const val FALLBACK_BLACK_LEVEL_RATIO = 1f / 64f

    fun resolve(
        whiteLevel: Int?,
        dynamicBlackLevel: FloatArray?,
        blackLevelOffsets: IntArray?,
        strictHighEnd: Boolean,
        /** Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT: 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR. */
        bayerPattern: Int = 0
    ): RawSensorCalibrationPlan {
        val safeWhite = (whiteLevel ?: DEFAULT_WHITE_LEVEL).coerceIn(256, 65_535)
        val dynamicRggb = dynamicBlackLevel
            ?.takeIf { values ->
                values.size == 4 && values.all { it.isFinite() && it >= 0f && it < safeWhite.toFloat() }
            }
            // Camera2 SENSOR_DYNAMIC_BLACK_LEVEL is explicitly semantic [R, Gr, Gb, B],
            // independent of SENSOR_INFO_COLOR_FILTER_ARRANGEMENT. Reordering it as a physical
            // 2x2 Bayer tile swaps R/B and both green phases on BGGR/GBRG cameras. That error is most
            // visible after RAW white balance + NDR shadow expansion, where a sub-code black offset
            // becomes a large grey/magenta/green discontinuity at the project-owned preview handoff.
            ?.copyOf()
        val staticRggb = blackLevelOffsets
            ?.takeIf { values -> values.size == 4 && values.all { it >= 0 && it < safeWhite } }
            ?.let { values ->
                reorderCfaTileToRggb(FloatArray(4) { values[it].toFloat() }, bayerPattern)
            }
        val fallbackBlackLevel = (safeWhite * FALLBACK_BLACK_LEVEL_RATIO)
            .roundToInt()
            .coerceIn(1, safeWhite - 1)
        val blackLevelRggb = dynamicRggb ?: staticRggb ?: FloatArray(4) { fallbackBlackLevel.toFloat() }
        val blackLevel = blackLevelRggb.average().roundToInt().coerceIn(0, safeWhite - 1)

        val diagnostics = buildList {
            if (dynamicRggb != null) {
                add("RAW black level calibrated per CFA channel from dynamic per-frame camera metadata")
            } else if (staticRggb != null) {
                add("RAW black level calibrated per CFA channel from camera metadata offsets")
            } else if (strictHighEnd) {
                add("RAW black level using scaled fallback because complete metadata is unavailable")
            } else {
                add("RAW black level using compatibility fallback")
            }
            if (whiteLevel == null) {
                add("RAW white level missing; using default $DEFAULT_WHITE_LEVEL")
            }
        }

        return RawSensorCalibrationPlan(
            blackLevel = blackLevel,
            blackLevelRggb = blackLevelRggb,
            whiteLevel = safeWhite,
            usedFallbackBlackLevel = dynamicRggb == null && staticRggb == null,
            diagnostics = diagnostics
        )
    }

    /**
     * Camera2 [android.hardware.camera2.params.BlackLevelPattern] values are queried in physical 2x2
     * tile order. The shader consumes semantic R/Gr/Gb/B values, so static patterns on non-RGGB
     * sensors must be reordered. SENSOR_DYNAMIC_BLACK_LEVEL does not use this helper because Android
     * already defines that result in semantic [R, Gr, Gb, B] order.
     */
    internal fun reorderCfaTileToRggb(values: FloatArray, bayerPattern: Int): FloatArray {
        require(values.size == 4) { "A CFA black-level tile must contain four values" }
        return when (bayerPattern) {
            1 -> floatArrayOf(values[1], values[0], values[3], values[2]) // GRBG
            2 -> floatArrayOf(values[2], values[3], values[0], values[1]) // GBRG
            3 -> floatArrayOf(values[3], values[2], values[1], values[0]) // BGGR
            else -> values.copyOf() // RGGB
        }
    }
}
