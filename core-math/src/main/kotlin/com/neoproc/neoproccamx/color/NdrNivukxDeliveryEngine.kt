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

    /** Immutable per-export plan so full-resolution conversion never rebuilds tone parameters per pixel. */
    @ConsistentCopyVisibility
    data class PreparedDelivery internal constructor(
        val mode: Mode,
        val targetPeakNits: Double,
        internal val params: NdrToneOperator.Params
    )

    private fun paramsFor(mode: Mode, targetPeakNits: Double? = null): NdrToneOperator.Params = when (mode) {
        Mode.NDR10_PLUS -> NdrToneOperator.ndr10Plus(resolveTargetPeak(mode, targetPeakNits))
        Mode.NIVUKX_VISION -> NdrToneOperator.nivukxVision(resolveTargetPeak(mode, targetPeakNits))
    }

    private fun defaultTargetPeak(mode: Mode): Double = when (mode) {
        Mode.NDR10_PLUS -> NdrToneOperator.NDR10_PLUS_MASTER_PEAK_NITS
        Mode.NIVUKX_VISION -> NdrToneOperator.NIVUKX_VISION_MASTER_PEAK_NITS
    }

    /**
     * Resolve the proprietary NDR mastering target. The target is an encoded-pixel contract, not a
     * physical-panel capability: NDR10+ is always 5000 nits and NIVUKX Vision is always 10000 nits.
     * Lower-nit displays tone-map the preview independently and must never relabel the master.
     */
    fun resolveTargetPeak(
        mode: Mode,
        @Suppress("UNUSED_PARAMETER") requestedPeakNits: Double?
    ): Double = defaultTargetPeak(mode)

    /** Master target peak nits the delivery shoulder asymptotes to. */
    fun targetPeakNits(mode: Mode, requestedPeakNits: Double? = null): Double =
        paramsFor(mode, requestedPeakNits).peakNits

    fun prepare(mode: Mode, requestedPeakNits: Double? = null): PreparedDelivery {
        val params = paramsFor(mode, requestedPeakNits)
        return PreparedDelivery(mode = mode, targetPeakNits = params.peakNits, params = params)
    }

    /**
     * Allocation-free ACEScg/AP1 scene-linear to Rec.2020 scene-linear matrix used by the live NDR
     * preview and recording shaders. It preserves the neutral axis and performs no tone mapping.
     */
    fun acesCgToLinearBt2020Into(
        r: Double,
        g: Double,
        b: Double,
        out: DoubleArray,
        offset: Int = 0
    ): DoubleArray {
        require(offset >= 0 && offset + 2 < out.size) { "BT.2020 output requires three values" }
        out[offset] = 1.02582 * r - 0.02005 * g - 0.00577 * b
        out[offset + 1] = -0.00386 * r + 1.01040 * g - 0.00654 * b
        out[offset + 2] = -0.01784 * r - 0.07404 * g + 1.09188 * b
        return out
    }

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
        warmSkinRedTame: Double = 0.0,
        targetPeakNits: Double? = null
    ): DoubleArray {
        val out = DoubleArray(3)
        deliverPqInto(
            rLinear = rLinear,
            gLinear = gLinear,
            bLinear = bLinear,
            delivery = prepare(mode, targetPeakNits),
            warmSkinRedTame = warmSkinRedTame,
            out = out,
            scratch = DoubleArray(3)
        )
        return out
    }

    /** Allocation-free canonical PQ delivery for full-resolution frame converters. */
    fun deliverPqInto(
        rLinear: Double,
        gLinear: Double,
        bLinear: Double,
        delivery: PreparedDelivery,
        warmSkinRedTame: Double = 0.0,
        out: DoubleArray,
        offset: Int = 0,
        scratch: DoubleArray
    ): DoubleArray {
        require(offset >= 0 && offset + 2 < out.size) { "NDR PQ output requires three values" }
        require(scratch.size >= 3) { "NDR delivery scratch requires three values" }
        val p = delivery.params
        NdrToneOperator.mapRgbHuePreservingInto(
            rLinear,
            gLinear,
            bLinear,
            p,
            warmSkinRedTame,
            scratch,
            0
        )
        val signalLuma = NdrToneOperator.luma(scratch[0], scratch[1], scratch[2])
        if (signalLuma <= EPS) {
            out[offset] = 0.0
            out[offset + 1] = 0.0
            out[offset + 2] = 0.0
            return out
        }
        val nits = NdrToneOperator.signalToDisplayNits(signalLuma, p)
        val scale = nits / signalLuma
        scratch[0] *= scale
        scratch[1] *= scale
        scratch[2] *= scale
        compressToPeakColorVolumeInPlace(scratch, 0, nits, p.peakNits)
        out[offset] = HdrImagingMath.pqEncodeFromNits(scratch[0])
        out[offset + 1] = HdrImagingMath.pqEncodeFromNits(scratch[1])
        out[offset + 2] = HdrImagingMath.pqEncodeFromNits(scratch[2])
        return out
    }
        // tone-signal luma (1.0 == diffuse white) → absolute display nits, then a
        // single hue-preserving scale so every channel lands in the same nit domain.

    /**
     * Radially compress only color-volume overflow whose strongest channel would exceed the master
     * peak. Scaling chroma around the pixel luminance preserves luminance and hue direction while
     * preventing red (or any other channel) from clipping before the luma meter notices it.
     */
    internal fun compressToPeakColorVolume(
        rNits: Double,
        gNits: Double,
        bNits: Double,
        luminanceNits: Double,
        peakNits: Double
    ): DoubleArray {
        val rgb = doubleArrayOf(rNits, gNits, bNits)
        compressToPeakColorVolumeInPlace(rgb, 0, luminanceNits, peakNits)
        return rgb
    }

    private fun compressToPeakColorVolumeInPlace(
        rgb: DoubleArray,
        offset: Int,
        luminanceNits: Double,
        peakNits: Double
    ) {
        val peak = peakNits.coerceIn(NdrToneOperator.DIFFUSE_WHITE_NITS, MAX_TARGET_PEAK_NITS)
        val y = luminanceNits.coerceIn(0.0, peak)
        rgb[offset] = rgb[offset].coerceAtLeast(0.0)
        rgb[offset + 1] = rgb[offset + 1].coerceAtLeast(0.0)
        rgb[offset + 2] = rgb[offset + 2].coerceAtLeast(0.0)
        val hi = maxOf(rgb[offset], rgb[offset + 1], rgb[offset + 2])
        if (hi <= peak || hi <= y + EPS) return
        val chromaScale = ((peak - y) / (hi - y)).coerceIn(0.0, 1.0)
        for (channel in 0..2) {
            rgb[offset + channel] =
                (y + (rgb[offset + channel] - y) * chromaScale).coerceIn(0.0, peak)
        }
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
        warmSkinRedTame: Double = 0.0,
        targetPeakNits: Double? = null
    ): IntArray {
        val out = IntArray(3)
        deliverPqCodesInto(
            rLinear = rLinear,
            gLinear = gLinear,
            bLinear = bLinear,
            delivery = prepare(mode, targetPeakNits),
            bits = bits,
            warmSkinRedTame = warmSkinRedTame,
            out = out,
            scratch = DoubleArray(6)
        )
        return out
    }

    /** Allocation-free quantized delivery using a caller-owned six-double scratch buffer. */
    fun deliverPqCodesInto(
        rLinear: Double,
        gLinear: Double,
        bLinear: Double,
        delivery: PreparedDelivery,
        bits: Int = 10,
        warmSkinRedTame: Double = 0.0,
        out: IntArray,
        offset: Int = 0,
        scratch: DoubleArray
    ): IntArray {
        require(bits in 8..16) { "Unsupported NDR delivery bit depth: $bits" }
        require(offset >= 0 && offset + 2 < out.size) { "NDR code output requires three values" }
        require(scratch.size >= 6) { "NDR code delivery scratch requires six values" }
        deliverPqInto(
            rLinear = rLinear,
            gLinear = gLinear,
            bLinear = bLinear,
            delivery = delivery,
            warmSkinRedTame = warmSkinRedTame,
            out = scratch,
            offset = 3,
            scratch = scratch
        )
        out[offset] = HdrImagingMath.quantize(scratch[3], bits)
        out[offset + 1] = HdrImagingMath.quantize(scratch[4], bits)
        out[offset + 2] = HdrImagingMath.quantize(scratch[5], bits)
        return out
    }

    private const val MAX_TARGET_PEAK_NITS = NdrToneOperator.NIVUKX_VISION_MASTER_PEAK_NITS
}
