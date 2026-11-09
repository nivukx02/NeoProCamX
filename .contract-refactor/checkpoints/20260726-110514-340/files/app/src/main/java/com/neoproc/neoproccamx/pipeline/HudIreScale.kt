package com.neoproc.neoproccamx.pipeline

import kotlin.math.roundToInt

/**
 * s8-f01 (CP-U1) — scene→IRE mapping shared by the live zebra/waveform HUD producers (and the
 * calibrated false-color band table). Broadcast monitors express exposure in IRE / code-value, not
 * raw 0..255 sRGB; this is the single seam that turns the HUD broadcast-monitor grade.
 *
 * NO-DOWNGRADE: [USE_LEGAL_RANGE_IRE] defaults false, in which case the live wrappers
 * [zebraTriggerCode8] and [normalizedFromCode8] are byte-for-byte identical to the legacy
 * FrameAnalysisMath / WaveformView expressions. Flipping it on (device-gated) recalibrates the HUD to
 * the broadcast legal range (code 16→0 IRE, 235→100 IRE). The legal-range math is exposed as
 * unconditional pure functions ([legalRangeIre]/[ireToLegalCode8]) so it is independently testable.
 * Pure Kotlin only — NO android.graphics (unit tests run with returnDefaultValues=true, where
 * android.graphics.Color returns 0).
 */
internal object HudIreScale {

    /** Default OFF reproduces today's full-range HUD exactly. */
    val USE_LEGAL_RANGE_IRE: Boolean = false

    private const val LEGAL_BLACK = 16f
    private const val LEGAL_WHITE = 235f

    /** Broadcast legal-range 8-bit code (0..255) → IRE (0..100): code 16→0, 235→100, clamped. */
    fun legalRangeIre(code8: Int): Float =
        ((code8 - LEGAL_BLACK) / (LEGAL_WHITE - LEGAL_BLACK) * 100f).coerceIn(0f, 100f)

    /** Inverse of [legalRangeIre]: IRE (0..100) → legal-range 8-bit code, clamped. */
    fun ireToLegalCode8(ire: Float): Int =
        (LEGAL_BLACK + ire / 100f * (LEGAL_WHITE - LEGAL_BLACK)).roundToInt().coerceIn(0, 255)

    /** Full-range mapping: code (0..255) → IRE (0..100) linearly (today's behaviour). */
    fun fullRangeIre(code8: Int): Float = code8 / 255f * 100f

    /** Flag-selected code→IRE used by HUD labels. */
    fun codeToIre(code8: Int): Float =
        if (USE_LEGAL_RANGE_IRE) legalRangeIre(code8) else fullRangeIre(code8)

    /**
     * Zebra trigger code for a 0..1 threshold. DEFAULT (flag off) returns exactly the legacy
     * FrameAnalysisMath expression `(zebraThreshold * 255f).toInt()` (truncation, NOT rounding) so the
     * live zebra alpha is byte-identical; flag on interprets the threshold as a fraction of the IRE
     * scale and maps it onto a legal-range code.
     */
    fun zebraTriggerCode8(zebraThreshold01: Float): Int =
        if (USE_LEGAL_RANGE_IRE) ireToLegalCode8(zebraThreshold01 * 100f) else (zebraThreshold01 * 255f).toInt()

    /**
     * Waveform sample (0..255 code) → normalized plot height 0..1. DEFAULT (flag off) returns exactly
     * the legacy `code8 / 255f` (Float division) so the live waveform trace is pixel-identical.
     */
    fun normalizedFromCode8(code8: Int): Float =
        if (USE_LEGAL_RANGE_IRE) (legalRangeIre(code8) / 100f).coerceIn(0f, 1f) else code8 / 255f

    // ---- false-color band tables --------------------------------------------------------------

    /** Legacy 8-band code-value scale — matches the dormant FalseColorOverlay boundaries exactly. */
    enum class LegacyFalseColorBand { MAGENTA, BLUE, CYAN, GREEN, YELLOW, ORANGE, RED, PINK }

    /** Reproduces FalseColorOverlay.lumaToFalseColor's code-value bands (3/10/26/51/102/153/230). */
    fun bandForCode8(code8: Int): LegacyFalseColorBand = when {
        code8 < 3 -> LegacyFalseColorBand.MAGENTA
        code8 < 10 -> LegacyFalseColorBand.BLUE
        code8 < 26 -> LegacyFalseColorBand.CYAN
        code8 < 51 -> LegacyFalseColorBand.GREEN
        code8 < 102 -> LegacyFalseColorBand.YELLOW
        code8 < 153 -> LegacyFalseColorBand.ORANGE
        code8 < 230 -> LegacyFalseColorBand.RED
        else -> LegacyFalseColorBand.PINK
    }

    /** Calibrated SmallHD-style false-colour exposure scale, expressed in IRE (flag-on path only). */
    enum class FalseColorBand { PURPLE, BLUE, GREEN_18, AQUA, PINK_SKIN, TEAL, YELLOW_NEARCLIP, RED_CLIP }

    data class FalseColorBandSpec(
        val band: FalseColorBand,
        val loIreInclusive: Float,
        val hiIreExclusive: Float
    )

    /**
     * SmallHD-style scale in IRE: purple < 2.5 (crushed black), green 38–42 (18% grey), pink 52–56
     * (skin), yellow 95–100 (near clip), red ≥ 100 (clipped). Intervening bands span the rest.
     */
    val SMALLHD_BANDS: List<FalseColorBandSpec> = listOf(
        FalseColorBandSpec(FalseColorBand.PURPLE, 0f, 2.5f),
        FalseColorBandSpec(FalseColorBand.BLUE, 2.5f, 38f),
        FalseColorBandSpec(FalseColorBand.GREEN_18, 38f, 42f),
        FalseColorBandSpec(FalseColorBand.AQUA, 42f, 52f),
        FalseColorBandSpec(FalseColorBand.PINK_SKIN, 52f, 56f),
        FalseColorBandSpec(FalseColorBand.TEAL, 56f, 95f),
        FalseColorBandSpec(FalseColorBand.YELLOW_NEARCLIP, 95f, 100f),
        FalseColorBandSpec(FalseColorBand.RED_CLIP, 100f, Float.POSITIVE_INFINITY)
    )

    fun bandForIre(ire: Float): FalseColorBand =
        SMALLHD_BANDS.first { ire >= it.loIreInclusive && ire < it.hiIreExclusive }.band
}
