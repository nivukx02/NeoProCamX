package com.neoproc.neoproccamx.color

internal enum class CalibrationPatchRole {
    NEUTRAL,
    SHADOW,
    HIGHLIGHT,
    SKIN,
    SKY,
    FOLIAGE,
    SATURATED_RED,
    SATURATED_ORANGE,
    SATURATED_YELLOW,
    SATURATED_GREEN,
    SATURATED_CYAN,
    SATURATED_BLUE,
    TEXTURE
}

internal data class CalibrationRgb(
    val r: Float,
    val g: Float,
    val b: Float
) {
    fun sanitized(): CalibrationRgb =
        CalibrationRgb(r.safeChannel(), g.safeChannel(), b.safeChannel())

    fun toHex(): String {
        val safe = sanitized()
        return "#%02X%02X%02X".format(
            (safe.r * 255f + 0.5f).toInt().coerceIn(0, 255),
            (safe.g * 255f + 0.5f).toInt().coerceIn(0, 255),
            (safe.b * 255f + 0.5f).toInt().coerceIn(0, 255)
        )
    }

    private fun Float.safeChannel(): Float =
        if (isFinite()) coerceIn(0f, 1f) else 0f
}

internal data class CalibrationPatch(
    val id: String,
    val label: String,
    val role: CalibrationPatchRole,
    val row: Int,
    val column: Int,
    val referenceSrgb: CalibrationRgb
)

internal object NdrNivukxCalibrationTarget {
    const val VERSION = "NDR_NIVUKX_TONE_COLOR_REFERENCE_V2"
    const val ROWS = 6
    const val COLUMNS = 8

    val patches: List<CalibrationPatch> = listOf(
        patch("neutral_02", "N02", CalibrationPatchRole.NEUTRAL, 0, 0, 0.02f, 0.02f, 0.02f),
        patch("neutral_08", "N08", CalibrationPatchRole.NEUTRAL, 0, 1, 0.08f, 0.08f, 0.08f),
        patch("neutral_18", "N18", CalibrationPatchRole.NEUTRAL, 0, 2, 0.18f, 0.18f, 0.18f),
        patch("neutral_32", "N32", CalibrationPatchRole.NEUTRAL, 0, 3, 0.32f, 0.32f, 0.32f),
        patch("neutral_50", "N50", CalibrationPatchRole.NEUTRAL, 0, 4, 0.50f, 0.50f, 0.50f),
        patch("neutral_68", "N68", CalibrationPatchRole.NEUTRAL, 0, 5, 0.68f, 0.68f, 0.68f),
        patch("neutral_84", "N84", CalibrationPatchRole.NEUTRAL, 0, 6, 0.84f, 0.84f, 0.84f),
        patch("neutral_96", "N96", CalibrationPatchRole.NEUTRAL, 0, 7, 0.96f, 0.96f, 0.96f),

        patch("shadow_neutral", "SN", CalibrationPatchRole.SHADOW, 1, 0, 0.035f, 0.035f, 0.035f),
        patch("shadow_skin", "SS", CalibrationPatchRole.SHADOW, 1, 1, 0.105f, 0.070f, 0.052f),
        patch("shadow_sky", "SB", CalibrationPatchRole.SHADOW, 1, 2, 0.040f, 0.058f, 0.102f),
        patch("shadow_foliage", "SF", CalibrationPatchRole.SHADOW, 1, 3, 0.042f, 0.078f, 0.036f),
        patch("shadow_red", "SR", CalibrationPatchRole.SHADOW, 1, 4, 0.112f, 0.030f, 0.026f),
        patch("shadow_cyan", "SC", CalibrationPatchRole.SHADOW, 1, 5, 0.026f, 0.090f, 0.095f),
        patch("shadow_warm", "SW", CalibrationPatchRole.SHADOW, 1, 6, 0.110f, 0.078f, 0.042f),
        patch("shadow_cool", "SK", CalibrationPatchRole.SHADOW, 1, 7, 0.045f, 0.055f, 0.095f),

        patch("highlight_cloud", "HC", CalibrationPatchRole.HIGHLIGHT, 2, 0, 0.900f, 0.915f, 0.920f),
        patch("highlight_warm", "HW", CalibrationPatchRole.HIGHLIGHT, 2, 1, 0.970f, 0.850f, 0.640f),
        patch("highlight_skin", "HS", CalibrationPatchRole.HIGHLIGHT, 2, 2, 0.930f, 0.720f, 0.610f),
        patch("highlight_sky", "HB", CalibrationPatchRole.HIGHLIGHT, 2, 3, 0.690f, 0.820f, 0.990f),
        patch("highlight_neutral", "HN", CalibrationPatchRole.HIGHLIGHT, 2, 4, 0.970f, 0.970f, 0.970f),
        patch("highlight_cyan_guard", "HG", CalibrationPatchRole.HIGHLIGHT, 2, 5, 0.720f, 0.950f, 0.940f),
        patch("highlight_red_guard", "HR", CalibrationPatchRole.HIGHLIGHT, 2, 6, 0.980f, 0.560f, 0.500f),
        patch("highlight_yellow", "HY", CalibrationPatchRole.HIGHLIGHT, 2, 7, 0.980f, 0.900f, 0.450f),

        patch("skin_deep", "SD", CalibrationPatchRole.SKIN, 3, 0, 0.360f, 0.210f, 0.145f),
        patch("skin_medium", "SM", CalibrationPatchRole.SKIN, 3, 1, 0.560f, 0.350f, 0.260f),
        patch("skin_light", "SL", CalibrationPatchRole.SKIN, 3, 2, 0.760f, 0.560f, 0.450f),
        patch("skin_warm", "SWM", CalibrationPatchRole.SKIN, 3, 3, 0.690f, 0.410f, 0.270f),
        patch("skin_cool", "SCL", CalibrationPatchRole.SKIN, 3, 4, 0.600f, 0.390f, 0.340f),
        patch("lip_guard", "LG", CalibrationPatchRole.SKIN, 3, 5, 0.620f, 0.225f, 0.245f),
        patch("texture_gray", "TG", CalibrationPatchRole.TEXTURE, 3, 6, 0.430f, 0.430f, 0.430f),
        patch("texture_warm", "TW", CalibrationPatchRole.TEXTURE, 3, 7, 0.480f, 0.390f, 0.300f),

        patch("sky_deep", "BD", CalibrationPatchRole.SKY, 4, 0, 0.145f, 0.310f, 0.760f),
        patch("sky_day", "BY", CalibrationPatchRole.SKY, 4, 1, 0.310f, 0.560f, 0.910f),
        patch("sky_haze", "BH", CalibrationPatchRole.SKY, 4, 2, 0.560f, 0.720f, 0.910f),
        patch("sky_cloud_edge", "BE", CalibrationPatchRole.SKY, 4, 3, 0.720f, 0.820f, 0.910f),
        patch("foliage_dark", "FD", CalibrationPatchRole.FOLIAGE, 4, 4, 0.105f, 0.300f, 0.095f),
        patch("foliage_leaf", "FL", CalibrationPatchRole.FOLIAGE, 4, 5, 0.190f, 0.480f, 0.155f),
        patch("foliage_sun", "FS", CalibrationPatchRole.FOLIAGE, 4, 6, 0.360f, 0.600f, 0.210f),
        patch("foliage_yellow_green", "FY", CalibrationPatchRole.FOLIAGE, 4, 7, 0.530f, 0.650f, 0.190f),

        patch("sat_red", "R", CalibrationPatchRole.SATURATED_RED, 5, 0, 0.880f, 0.050f, 0.040f),
        patch("sat_orange", "O", CalibrationPatchRole.SATURATED_ORANGE, 5, 1, 0.940f, 0.360f, 0.050f),
        patch("sat_yellow", "Y", CalibrationPatchRole.SATURATED_YELLOW, 5, 2, 0.950f, 0.850f, 0.060f),
        patch("sat_green", "G", CalibrationPatchRole.SATURATED_GREEN, 5, 3, 0.060f, 0.760f, 0.090f),
        patch("sat_cyan", "C", CalibrationPatchRole.SATURATED_CYAN, 5, 4, 0.040f, 0.760f, 0.820f),
        patch("sat_blue", "B", CalibrationPatchRole.SATURATED_BLUE, 5, 5, 0.070f, 0.150f, 0.880f),
        patch("sat_magenta", "M", CalibrationPatchRole.SATURATED_RED, 5, 6, 0.780f, 0.060f, 0.680f),
        patch("texture_edges", "TE", CalibrationPatchRole.TEXTURE, 5, 7, 0.520f, 0.520f, 0.520f)
    )

    val patchById: Map<String, CalibrationPatch> = patches.associateBy { it.id }

    private fun patch(
        id: String,
        label: String,
        role: CalibrationPatchRole,
        row: Int,
        column: Int,
        r: Float,
        g: Float,
        b: Float
    ): CalibrationPatch =
        CalibrationPatch(id, label, role, row, column, CalibrationRgb(r, g, b))
}
