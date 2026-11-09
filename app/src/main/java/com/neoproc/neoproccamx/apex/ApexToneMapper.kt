package com.neoproc.neoproccamx.apex

import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TASK 5 — NIVUKX APEX TONE MAPPER
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Complete scene-adaptive perceptual tone mapper with:
 * - Signal shaping (SHARP / ZERO / CINEMA)
 * - C1 toe / midtone / filmic shoulder continuity
 * - Adaptive shadow compression + noise gate
 * - Color-volume preservation (gamut protection)
 * - Saturation preservation final mix
 *
 * All formulas exactly as specified in APEX Sections 04 and 07.
 * CPU reference path — GPU path in apex_tonemap.frag.
 */
object ApexToneMapper {

    private const val EPS = 1e-8f

    /**
     * Full APEX tone mapping pipeline for a single pixel.
     *
     * @param r Linear BT.2020 R (nits)
     * @param g Linear BT.2020 G (nits)
     * @param b Linear BT.2020 B (nits)
     * @param params Current profile parameters
     * @param metrics Current scene metrics
     * @return FloatArray[3] = {R_out, G_out, B_out} in nits, tone-mapped
     */
    fun toneMap(
        r: Float, g: Float, b: Float,
        params: ApexProfileParams,
        metrics: SceneMetrics
    ): FloatArray {
        // ── BT.2020 Luma ──────────────────────────────────────────────────
        // Y = 0.2627 * R + 0.6780 * G + 0.0593 * B
        val y = 0.2627f * r + 0.6780f * g + 0.0593f * b
        val ySafe = max(y, EPS)

        // ── Normalize to scene white ──────────────────────────────────────
        // x = clamp(Y_safe / sceneWhite, 0.0, 1.0)
        val x = (ySafe / max(metrics.sceneWhite, EPS)).coerceIn(0f, 1f)

        // ══════════════════════════════════════════════════════════════════
        // STAGE 1: APEX Signal Shaping
        // Apply S_apex(x) based on active mode
        // ══════════════════════════════════════════════════════════════════
        val xMapped = ICtCpConverter.sApex(x, params.mode)

        // Stage 2 uses the same C1 piecewise perceptual operator as the shader:
        // normalized toe, linear midtone, and Hermite shoulder into target peak.
        val inputNits = xMapped * params.sourcePeakNits
        val mappedNits = mapLumaNits(inputNits, params, metrics)

        // ══════════════════════════════════════════════════════════════════
        // STAGE 3: Luma-Preserving RGB Scale
        //
        // scale = Y_mapped / max(Y, eps)
        // R1 = R * scale
        // G1 = G * scale
        // B1 = B * scale
        // ══════════════════════════════════════════════════════════════════
        val scale = mappedNits / ySafe
        var r1 = r * scale
        var g1 = g * scale
        var b1 = b * scale

        // ══════════════════════════════════════════════════════════════════
        // STAGE 4: Shadow Compression
        //
        // Y_shadow = Y_mapped + shadowLift * smoothstep(0.0, 0.18, 1.0 - x)
        //
        // NOISE GATE: if chromaNoiseRisk > 0.35:
        //     shadowLift *= (1.0 - chromaNoiseRisk)
        // ══════════════════════════════════════════════════════════════════
        var effectiveShadowLift = params.shadowLift
        if (metrics.chromaNoiseRisk > 0.35f) {
            effectiveShadowLift *= (1f - metrics.chromaNoiseRisk)
        }
        val shadowBoost = effectiveShadowLift *
            ICtCpConverter.smoothstepNeo(0f, 0.18f, 1f - x)
        r1 += shadowBoost
        g1 += shadowBoost
        b1 += shadowBoost

        // ══════════════════════════════════════════════════════════════════
        // STAGE 5: Color-Volume Preservation
        //
        // peakScale  = clamp(outputPeakNits / max(inputPeakNits, eps), 0.05, 1.0)
        // chromaKeep = 1.0 - colorVolumeCompression * (1.0 - peakScale)
        //
        // R_out = Y_out + (R_scaled - Y_out) * chromaKeep
        // G_out = Y_out + (G_scaled - Y_out) * chromaKeep
        // B_out = Y_out + (B_scaled - Y_out) * chromaKeep
        // ══════════════════════════════════════════════════════════════════
        val peakScale = (params.targetPeakNits / max(params.sourcePeakNits, EPS))
            .coerceIn(0.05f, 1f)
        val chromaKeep = 1f - params.colorVolumeCompression * (1f - peakScale)

        val yOut = 0.2627f * r1 + 0.6780f * g1 + 0.0593f * b1
        var rOut = yOut + (r1 - yOut) * chromaKeep
        var gOut = yOut + (g1 - yOut) * chromaKeep
        var bOut = yOut + (b1 - yOut) * chromaKeep

        // ══════════════════════════════════════════════════════════════════
        // STAGE 6: Saturation Preservation Final Mix
        //
        // Y_final = 0.2627*R + 0.6780*G + 0.0593*B
        // R_final = Y_final + (R_out - Y_final) * saturationPreservation
        // ══════════════════════════════════════════════════════════════════
        val yFinal = 0.2627f * rOut + 0.6780f * gOut + 0.0593f * bOut
        rOut = yFinal + (rOut - yFinal) * params.saturationPreservation
        gOut = yFinal + (gOut - yFinal) * params.saturationPreservation
        bOut = yFinal + (bOut - yFinal) * params.saturationPreservation

        return floatArrayOf(
            rOut.coerceIn(0f, 65504f),
            gOut.coerceIn(0f, 65504f),
            bOut.coerceIn(0f, 65504f)
        )
    }

    /**
     * C1 continuous APEX v2 luminance operator.
     *
     * It maps source nits into target nits through three zones:
     * toe for shadow separation, linear midtones for optical truth, and a
     * cubic Hermite shoulder that preserves the midtone slope at the knee and
     * gently flattens near the target peak. The output is monotonic and bounded.
     */
    internal fun mapLumaNits(
        inputNits: Float,
        params: ApexProfileParams,
        metrics: SceneMetrics
    ): Float {
        val sourcePeak = max(params.sourcePeakNits, 1f)
        val x = (inputNits / sourcePeak).coerceIn(0f, 1f)
        val toeEnd = (0.018f + params.toeStrength).coerceIn(0.018f, 0.115f)
        val knee = params.kneeRatio.coerceIn(toeEnd + 0.08f, 0.86f)
        val midSlope = params.midtoneSlope.coerceIn(0.82f, 1.22f)
        val toeLift = params.toeStrength.coerceIn(0f, 0.12f)

        val toeEndY = toeMapped(toeEnd, toeEnd, toeLift, midSlope)
        val rawKneeY = toeEndY + (knee - toeEnd) * midSlope
        val kneeY = rawKneeY.coerceIn(knee * 0.72f, min(0.86f, 1f - 0.08f * params.shoulderStrength / 6.2f))

        val y = when {
            x <= toeEnd -> toeMapped(x, toeEnd, toeLift, midSlope)
            x <= knee -> toeEndY + (x - toeEnd) * midSlope
            else -> {
                val span = max(1f - knee, EPS)
                val t = ((x - knee) / span).coerceIn(0f, 1f)
                val availableSlope = (1f - kneeY) / span
                val startSlope = min(midSlope, availableSlope * 1.72f)
                val endSlope = (0.030f / max(params.shoulderStrength, 1f))
                    .coerceIn(0f, 0.018f)
                hermite(
                    t = t,
                    y0 = kneeY,
                    m0 = startSlope * span,
                    y1 = 1f,
                    m1 = endSlope * span
                )
            }
        }

        return (y * params.targetPeakNits)
            .coerceIn(0f, params.targetPeakNits)
    }

    private fun toeMapped(x: Float, toeEnd: Float, toeLift: Float, midSlope: Float): Float {
        if (toeEnd <= EPS) return x
        val t = (x / toeEnd).coerceIn(0f, 1f)
        val y1 = toeEnd * (1f + toeLift * 0.38f)
        val m0 = (1f + toeLift * 0.65f) * toeEnd
        val m1 = midSlope * toeEnd
        return hermite(t, 0f, m0, y1, m1)
    }

    private fun hermite(t: Float, y0: Float, m0: Float, y1: Float, m1: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        return h00 * y0 + h10 * m0 + h01 * y1 + h11 * m1
    }
}
