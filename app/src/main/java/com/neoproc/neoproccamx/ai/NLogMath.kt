package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.NeoLogCurve as CoreNeoLogCurve

internal enum class NLogVariant(
    val label: String,
    val apexLabel: String
) {
    BASE("N-Log Base", "N-LOG v5 APEX Base"),
    SHADOW("N-Log Shadow", "N-LOG v5 APEX Shadow"),
    HIGHLIGHT("N-Log Highlight", "N-LOG v5 APEX Highlight"),
    CINEMA("N-Log Cinema", "N-LOG v5 APEX Cinema");

    companion object {
        fun fromLabel(label: String?): NLogVariant {
            val key = label.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            return when {
                key.contains("shadow") -> SHADOW
                key.contains("highlight") -> HIGHLIGHT
                key.contains("cinema") -> CINEMA
                else -> BASE
            }
        }
    }
}

/**
 * Original NeoProCamX scene-referred log family.
 *
 * N-Log is intentionally not a vendor LOG clone. The family uses a calibrated
 * low-end toe for shadow code separation and a reversible natural-log body for
 * highlight headroom. The variants adjust allocation without changing the
 * encode/decode contract used by preview LUTs and recording metadata.
 */
internal object NLogMath {
    const val SCENE_GRAY_18: Float = CoreNeoLogCurve.SCENE_GRAY_18
    const val GRAY_18_CODE: Float = CoreNeoLogCurve.GRAY_18_CODE
    const val SKIN_CODE_MIN: Float = 0.52f
    const val SKIN_CODE_MAX: Float = 0.82f

    data class VariantParameters(
        val variant: NLogVariant,
        val toeCut: Float,
        val toeSlope: Float,
        val toeEntrySlope: Float,
        val logDensity: Float,
        val toeHeight: Float,
        val logScale: Float,
        val m1: Float,
        val m2: Float,
        val c1: Float,
        val c3: Float,
        val c4: Float,
        val c5: Float,
        val xMax: Float,
        val intent: String,
        val toeCutRange: ClosedFloatingPointRange<Float>,
        val toeSlopeRange: ClosedFloatingPointRange<Float>,
        val logDensityRange: ClosedFloatingPointRange<Float>
    )

    fun encode(linear: Float): Float = encode(linear, NLogVariant.BASE)

    fun encode(linear: Float, variant: NLogVariant): Float =
        CoreNeoLogCurve.encode(linear, variant.toCoreVariant())

    fun decode(encoded: Float): Float = decode(encoded, NLogVariant.BASE)

    fun decode(encoded: Float, variant: NLogVariant): Float =
        CoreNeoLogCurve.decode(encoded, variant.toCoreVariant())

    fun quantize(
        linear: Float,
        variant: NLogVariant = NLogVariant.BASE,
        bitDepth: Int = 10,
        tpdfDither: Float = 0f
    ): Int =
        CoreNeoLogCurve.quantize(linear, variant.toCoreVariant(), bitDepth, tpdfDither)

    fun decodeCode(
        code: Int,
        variant: NLogVariant = NLogVariant.BASE,
        bitDepth: Int = 10
    ): Float =
        CoreNeoLogCurve.decodeCode(code, variant.toCoreVariant(), bitDepth)

    fun parameters(variant: NLogVariant): VariantParameters {
        val core = CoreNeoLogCurve.v5Parameters(variant.toCoreVariant())
        return VariantParameters(
            variant = variant,
            toeCut = core.toeCut,
            toeSlope = core.toeSlope,
            toeEntrySlope = core.toeEntrySlope,
            logDensity = core.logDensity,
            toeHeight = core.toeHeight,
            logScale = core.logScale,
            m1 = core.m1,
            m2 = core.m2,
            c1 = core.c1,
            c3 = core.c3,
            c4 = core.c4,
            c5 = core.c5,
            xMax = core.xMax,
            intent = core.intent,
            toeCutRange = when (variant) {
                NLogVariant.BASE -> 0.008f..0.012f
                NLogVariant.SHADOW -> 0.003f..0.006f
                NLogVariant.HIGHLIGHT -> 0.0035f..0.006f
                NLogVariant.CINEMA -> 0.006f..0.010f
            },
            toeSlopeRange = when (variant) {
                NLogVariant.BASE -> 7.0f..9.0f
                NLogVariant.SHADOW -> 9.0f..11.0f
                NLogVariant.HIGHLIGHT -> 10.0f..13.0f
                NLogVariant.CINEMA -> 9.0f..11.0f
            },
            logDensityRange = when (variant) {
                NLogVariant.BASE -> 90.0f..140.0f
                NLogVariant.SHADOW -> 50.0f..90.0f
                NLogVariant.HIGHLIGHT -> 220.0f..280.0f
                NLogVariant.CINEMA -> 90.0f..130.0f
            }
        )
    }

    fun labelToVariant(label: String?): NLogVariant =
        NLogVariant.fromLabel(label)

    fun labelForVariant(variant: NLogVariant): String =
        variant.apexLabel

    private fun NLogVariant.toCoreVariant(): CoreNeoLogCurve.Variant =
        when (this) {
            NLogVariant.BASE -> CoreNeoLogCurve.Variant.BASE
            NLogVariant.SHADOW -> CoreNeoLogCurve.Variant.SHADOW
            NLogVariant.HIGHLIGHT -> CoreNeoLogCurve.Variant.HIGHLIGHT
            NLogVariant.CINEMA -> CoreNeoLogCurve.Variant.CINEMA
        }
}
