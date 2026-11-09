package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.NeoLogCurve
import java.io.File
import java.util.Locale
import kotlin.math.pow

/**
 * Emits a NeoLog(ACEScg) → Rec.709 display `.cube` LUT beside a converted NeoLog `.mov`.
 *
 * The RAW→NeoLog develop encodes **linear ACEScg (AP1 primaries)** through the NeoLog transfer
 * ([RawNeoLogDevelop] → [NeoLogCurve.quantize]). That is a genuine wide-gamut log MASTER — maximum
 * grading latitude — but opened directly in a player it is interpreted as ordinary video and reads
 * "flat / oddly colourful" (log tone treated as gamma, AP1 gamut treated as the container's primaries).
 *
 * This writes the standard professional companion: a viewing LUT the user drops onto the clip in any
 * NLE (DaVinci/Premiere/FCP) to get a correct Rec.709 image. The MASTER is never modified (no quality
 * downgrade); the LUT only provides the display transform:
 *
 *   NeoLog code → (per channel) linear ACEScg → ACEScg→linear Rec.709 (AP1→Rec.709, D60→D65) → sRGB OETF
 *
 * The AP1→Rec.709 matrix is the canonical ACEScg→sRGB/Rec.709 primaries matrix (CAT02 D60→D65 baked in).
 */
internal object NeoLogRec709LutWriter {

    private const val LUT_SIZE = 33

    // Canonical ACEScg (AP1, ACES ~D60 white) → linear Rec.709/sRGB (D65), CAT02-adapted. Standard,
    // widely published values (e.g. the ACES "ACEScg to sRGB" output transform primaries matrix).
    private val ACESCG_TO_LINEAR_REC709 = arrayOf(
        doubleArrayOf(1.70505, -0.62179, -0.08326),
        doubleArrayOf(-0.13026, 1.14080, -0.01055),
        doubleArrayOf(-0.02400, -0.12897, 1.15297)
    )

    /** sRGB / Rec.709-ish OETF (IEC 61966-2-1). Rec.709 encode is visually equivalent for a viewing LUT. */
    private fun encodeSrgb(linear: Double): Double {
        val v = linear.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
    }

    private fun row(m: DoubleArray, r: Double, g: Double, b: Double) = m[0] * r + m[1] * g + m[2] * b

    /**
     * Writes `<movBaseName>_NeoLog_to_Rec709.cube` next to [movOutputPath]. Returns the file, or null if
     * writing fails (never throws — the sidecar is a convenience and must not fail the conversion).
     */
    fun writeSidecar(
        movOutputPath: String,
        variant: NeoLogCurve.Variant = NeoLogCurve.Variant.BASE
    ): File? = runCatching {
        val mov = File(movOutputPath)
        val base = mov.name.substringBeforeLast('.')
        val out = File(mov.parentFile, "${base}_NeoLog_to_Rec709.cube")
        out.bufferedWriter().use { w ->
            w.appendLine("TITLE \"NeoProCamX NeoLog (ACEScg) -> Rec.709\"")
            w.appendLine("LUT_3D_SIZE $LUT_SIZE")
            w.appendLine("DOMAIN_MIN 0.0 0.0 0.0")
            w.appendLine("DOMAIN_MAX 1.0 1.0 1.0")
            val last = (LUT_SIZE - 1).toDouble()
            // .cube ordering: red changes fastest, then green, then blue.
            for (bi in 0 until LUT_SIZE) {
                val bLog = bi / last
                val bLin = NeoLogCurve.decode(bLog.toFloat(), variant).toDouble()
                for (gi in 0 until LUT_SIZE) {
                    val gLog = gi / last
                    val gLin = NeoLogCurve.decode(gLog.toFloat(), variant).toDouble()
                    for (ri in 0 until LUT_SIZE) {
                        val rLog = ri / last
                        val rLin = NeoLogCurve.decode(rLog.toFloat(), variant).toDouble()
                        val lr = row(ACESCG_TO_LINEAR_REC709[0], rLin, gLin, bLin)
                        val lg = row(ACESCG_TO_LINEAR_REC709[1], rLin, gLin, bLin)
                        val lb = row(ACESCG_TO_LINEAR_REC709[2], rLin, gLin, bLin)
                        val or = encodeSrgb(lr)
                        val og = encodeSrgb(lg)
                        val ob = encodeSrgb(lb)
                        w.appendLine("${fmt(or)} ${fmt(og)} ${fmt(ob)}")
                    }
                }
            }
        }
        out
    }.getOrNull()

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v.coerceIn(0.0, 1.0))
}
