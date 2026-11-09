package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.recording.HdrImagingMath

/**
 * NdrNivukxDeliveryEngine — the single canonical per-pixel NDR10+ / NIVUKX Vision
 * DELIVERY engine: linear BT.2020 RGB → PQ (ST.2084) BT.2020 R'G'B' code values.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * WHY THIS EXISTS
 * ════════════════════════════════════════════════════════════════════════════
 * NDR10+ and NIVUKX Vision are original NeoProCamX HDR profiles. Their tone/color
 * transfer is defined host-golden in [NdrToneOperator], but until now NOTHING live
 * consumed it in full RGB: the record/export shader applied the "look" to a GRAY
 * axis (r=g=b=Y), which collapses every color-volume operation to a no-op, so the
 * delivered image was a luma tone-curve over an HLG signal — i.e. effectively plain
 * HLG, not the profile's own color science.
 *
 * This object is the ONE place a full-color, nit-accurate PQ delivery is defined,
 * so the record shader and the preview compositor can each MATCH it (preview ==
 * record by construction). It is pure JVM (host-testable) and only composes the
 * already-golden [NdrToneOperator] transfer with the tested PQ primitives in
 * [HdrImagingMath] — it invents no new math.
 *
 * Delivery pipeline (per pixel):
 *   1. [NdrToneOperator.mapRgbHuePreserving] — hue-constant, luma-preserving tone
 *      shaping with radial highlight desaturation (1.0 == diffuse-white reference).
 *   2. luma of the shaped RGB → absolute display nits via
 *      [NdrToneOperator.signalToDisplayNits] (BT.2408 203-nit diffuse-white anchor,
 *      C1 exponential shoulder asymptoting to the mode's master peak).
 *   3. scale the shaped RGB to that nit level (hue preserved) and PQ-encode each
 *      channel with [HdrImagingMath.pqEncodeFromNits].
 *
 * The result is a genuine PQ HDR signal carrying the profile's OWN color rendering
 * — NOT HLG, and NOT a bare luma curve.
 */
object NdrNivukxDeliveryEngine {

    /** Version tag for CPU↔GPU parity / metadata. */
    const val VERSION = "NDR-NIVUKX-DELIVERY-v1"

    private const val EPS = 1e-8

    enum class Mode { NDR10_PLUS, NIVUKX_VISION }

    private fun paramsFor(mode: Mode): NdrToneOperator.Params = when (mode) {
        Mode.NDR10_PLUS -> NdrToneOperator.ndr10Plus()
        Mode.NIVUKX_VISION -> NdrToneOperator.nivukxVision()
    }

    /** Master target peak nits the delivery shoulder asymptotes to (NDR 1420, NIVUKX 1680). */
    fun targetPeakNits(mode: Mode): Double = paramsFor(mode).peakNits

    /**
     * Linear BT.2020 RGB (scene/display-referred, 1.0 == diffuse-white reference)
     * → PQ ST.2084 BT.2020 R'G'B' code values in [0,1], hue-constant and
     * nit-accurate. Black maps to [0,0,0]; the diffuse-white triple (1,1,1) maps
     * to `pqEncodeFromNits(203)` on every channel.
     */
    fun deliverPq(
        rLinear: Double,
        gLinear: Double,
        bLinear: Double,
        mode: Mode,
        warmSkinRedTame: Double = 0.0
    ): DoubleArray {
        val p = paramsFor(mode)
        val (tr, tg, tb) = NdrToneOperator.mapRgbHuePreserving(rLinear, gLinear, bLinear, p, warmSkinRedTame)
        val signalLuma = NdrToneOperator.luma(tr, tg, tb)
        if (signalLuma <= EPS) return doubleArrayOf(0.0, 0.0, 0.0)
        // tone-signal luma (1.0 == diffuse white) → absolute display nits, then a
        // single hue-preserving scale so every channel lands in the same nit domain.
        val nits = NdrToneOperator.signalToDisplayNits(signalLuma, p)
        val scale = nits / signalLuma
        return doubleArrayOf(
            HdrImagingMath.pqEncodeFromNits(tr * scale),
            HdrImagingMath.pqEncodeFromNits(tg * scale),
            HdrImagingMath.pqEncodeFromNits(tb * scale)
        )
    }

    /**
     * Convenience: full linear-BT.2020-RGB → PQ delivery quantized to N-bit code
     * values (default 10-bit, the P010 / HDR10 encoder depth). Returns integer
     * code values in [0, (1<<bits)-1].
     */
    fun deliverPqCodes(
        rLinear: Double,
        gLinear: Double,
        bLinear: Double,
        mode: Mode,
        bits: Int = 10,
        warmSkinRedTame: Double = 0.0
    ): IntArray {
        val pq = deliverPq(rLinear, gLinear, bLinear, mode, warmSkinRedTame)
        return IntArray(3) { HdrImagingMath.quantize(pq[it], bits) }
    }
}
