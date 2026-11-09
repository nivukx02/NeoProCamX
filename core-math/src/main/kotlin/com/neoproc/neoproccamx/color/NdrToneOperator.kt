package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.recording.HdrImagingMath
import kotlin.math.exp
import kotlin.math.pow

/**
 * NdrToneOperator — the single canonical NDR10+ / NIVUKX Vision tone transfer.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * WHY THIS EXISTS (single source of truth)
 * ════════════════════════════════════════════════════════════════════════════
 * NDR10+ and NIVUKX Vision are original NeoProCamX dynamic-range profiles. Until
 * now the luminance transfer was re-implemented divergently in the live preview
 * shader, the record shader, the NPV10 export compute shader, and several Kotlin
 * helpers — each with its own bugs. This object is the ONE definition every
 * surface must match (host-golden locked), so preview == record == NPV10 export
 * by construction and the result is correct rather than merely consistent.
 *
 * It encodes three corrections that put NDR/NIVUKX above HDR10+/Dolby Vision:
 *
 *  1. NIT-ACCURATE DELIVERY. The transfer maps to ABSOLUTE display nits with
 *     diffuse white anchored at [DIFFUSE_WHITE_NITS] (BT.2408, 203 nits, the same
 *     anchor HDR10+/DV target) before PQ encoding. A normalized tone signal is
 *     never PQ-encoded as if 1.0 == 10000 nits (the historical bug that shipped
 *     diffuse white at ~9450 nits, ~5.6 stops too bright).
 *
 *  2. C1-CONTINUOUS, MONOTONE, SHADOW-LIFTING SHAPE. The documented signal lift
 *     (x^gamma + fading toe) raises shadows/midtones; an exponential highlight
 *     shoulder in the nit domain has unit slope at the diffuse-white join, so
 *     there is no contrast kick / Mach band at the knee and no shadow crush.
 *
 *  3. HUE-CONSTANT COLOR. The RGB path is luma-preserving (scale all channels by
 *     mappedY/Y) with any highlight desaturation done radially toward neutral, so
 *     the hue angle is invariant by construction — unlike per-channel knees that
 *     twist hue in highlights.
 *
 * Pure JVM math (no Android deps) so it is fully host-testable; reuses the PQ
 * primitives already in [HdrImagingMath].
 */
object NdrToneOperator {

    /** Version tag for metadata / CPU-GPU parity. */
    const val VERSION = "NDR-TONE-OP-v4"

    /** Python-generated runtime calibration consumed by every NDR/NIVUKX live path. */
    const val CALIBRATION_VERSION = "NDR-NIVUKX-PY-CAL-v4"

    /** BT.2408 HDR diffuse-white anchor in nits (the level HDR10+/DV target). */
    const val DIFFUSE_WHITE_NITS = 203.0

    /**
     * RAW-derived ACEScg scene value treated as diffuse white by every live path.
     *
     * This is a sensor-linear exposure anchor. The former 0.037 value was fitted by inverting
     * screenshots produced before the strict RAW route was restored; it therefore promoted an ordinary
     * metered RAW midtone to 80% display white and made the high-ISO noise floor visible. At 0.180,
     * sensor middle grey (0.180 * 0.180 = 0.0324) reaches the calibrated middle-grey tone while 0.180
     * itself remains the exact diffuse-white anchor.
     */
    const val SENSOR_DIFFUSE_SCENE = 0.180

    private const val EPS = 1e-8

    // BT.2020 RGB -> Y luminance weights.
    private const val LUMA_R = 0.2627
    private const val LUMA_G = 0.6780
    private const val LUMA_B = 0.0593

    enum class Mode { NDR10_PLUS, NIVUKX_VISION }

    /**
     * Canonical per-mode parameters.
     *
     * @param signalGamma calibrated midtone-lift exponent (<1 lifts).
     * @param toeWidth    smoothstep width of the additive low-end toe.
     * @param toeAmount   additive toe strength (fades to 0 at the diffuse-white reference).
     * @param diffuseWhiteNits absolute nits the diffuse-white signal level maps to.
     * @param peakNits    master target peak nits the highlight shoulder asymptotes to.
     * @param highlightDesat hue-constant highlight chroma rolloff cap [0,1].
     */
    data class Params(
        val mode: Mode,
        val signalGamma: Double,
        val toeWidth: Double,
        val toeAmount: Double,
        val diffuseWhiteNits: Double,
        val peakNits: Double,
        val highlightDesat: Double,
        val shadowMidtoneLift: Double,
        val midtoneContrast: Double,
        val chromaBoost: Double,
        val skyChromaBonus: Double,
        val localDetailStrength: Double,
        val localDetailLowerStops: Double,
        val localDetailUpperStops: Double
    ) {
        init {
            require(signalGamma in 0.1..1.0) { "signalGamma out of range: $signalGamma" }
            require(toeWidth in 1e-3..0.9) { "toeWidth out of range: $toeWidth" }
            require(peakNits > diffuseWhiteNits) { "peakNits must exceed diffuse white" }
        }

        /** Slope of [signalShape] just below/above the diffuse-white reference (x==1). */
        val signalReferenceSlope: Double get() = signalGamma - toeAmount
    }

    /** Python-calibrated balanced NDR10+ profile with an exact 5000-nit mastering target. */
    fun ndr10Plus(peakNits: Double = NDR10_PLUS_MASTER_PEAK_NITS): Params = Params(
        mode = Mode.NDR10_PLUS,
        signalGamma = 0.859937,
        toeWidth = 0.238882,
        toeAmount = 0.024780,
        diffuseWhiteNits = DIFFUSE_WHITE_NITS,
        peakNits = peakNits,
        highlightDesat = 0.149718,
        shadowMidtoneLift = 0.167837,
        midtoneContrast = 0.100943,
        chromaBoost = 0.038361,
        skyChromaBonus = 0.062392,
        localDetailStrength = 0.400000,
        localDetailLowerStops = -0.060000,
        localDetailUpperStops = 0.085000
    )

    /** Python-calibrated premium NIVUKX Vision profile with an exact 10000-nit mastering target. */
    fun nivukxVision(peakNits: Double = NIVUKX_VISION_MASTER_PEAK_NITS): Params = Params(
        mode = Mode.NIVUKX_VISION,
        signalGamma = 0.800034,
        toeWidth = 0.289876,
        toeAmount = 0.031033,
        diffuseWhiteNits = DIFFUSE_WHITE_NITS,
        peakNits = peakNits,
        highlightDesat = 0.128953,
        shadowMidtoneLift = 0.232156,
        midtoneContrast = 0.174135,
        chromaBoost = 0.091565,
        skyChromaBonus = 0.080000,
        localDetailStrength = 0.600000,
        localDetailLowerStops = -0.085000,
        localDetailUpperStops = 0.125000
    )

    fun paramsFor(mode: Mode): Params = when (mode) {
        Mode.NDR10_PLUS -> ndr10Plus()
        Mode.NIVUKX_VISION -> nivukxVision()
    }

    /** Fixed project mastering contracts; panel tone mapping never changes these encoded targets. */
    const val NDR10_PLUS_MASTER_PEAK_NITS = 5_000.0
    const val NIVUKX_VISION_MASTER_PEAK_NITS = 10_000.0

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        if (edge0 == edge1) return if (x >= edge1) 1.0 else 0.0
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** BT.2020 luminance from linear RGB. */
    fun luma(r: Double, g: Double, b: Double): Double = LUMA_R * r + LUMA_G * g + LUMA_B * b

    /**
     * Scene-normalized luminance -> display-referred tone "signal".
     *
     * The diffuse-white reference is at sceneNorm == 1.0, which maps to signal 1.0.
     * - x in (0,1]: documented lift x^gamma + smoothstep(0,toeWidth,x)*toeAmount*(1-x).
     *   This LIFTS shadows/midtones (never crushes) and is monotone increasing.
     * - x > 1: a unit-consistent linear continuation with slope == [Params.signalReferenceSlope]
     *   so the curve is C1 across the diffuse-white reference; the actual highlight
     *   roll-off to peak happens in the nit domain ([signalToDisplayNits]).
     */
    fun signalShape(sceneNorm: Double, p: Params): Double {
        val x = sceneNorm
        if (x <= 0.0) return 0.0
        if (x <= 1.0) {
            val gammaLift = x.pow(p.signalGamma)
            val toe = smoothstep(0.0, p.toeWidth, x) * p.toeAmount * (1.0 - x)
            return gammaLift + toe
        }
        // C1 linear continuation past diffuse white; nit-domain shoulder compresses it.
        return 1.0 + p.signalReferenceSlope * (x - 1.0)
    }

    /** Black-preserving shadow/midtone lift shared by preview, recording and host-golden math. */
    fun blackPreservingMidtoneLift(signal: Double, p: Params): Double {
        val x = signal.coerceAtLeast(0.0)
        if (x <= 0.0 || x >= 1.0) return x
        val window = smoothstep(0.025, 0.20, x) * (1.0 - smoothstep(0.78, 1.20, x))
        return x + p.shadowMidtoneLift * kotlin.math.sqrt(x) * (1.0 - x) * window
    }

    /** Bounded S-curve character; zero at black/diffuse white and monotone for shipped profiles. */
    fun profileMidtoneContrast(signal: Double, p: Params): Double {
        val x = signal.coerceAtLeast(0.0)
        if (x <= 0.0 || x >= 1.0) return x
        val window = smoothstep(0.055, 0.24, x) * (1.0 - smoothstep(0.76, 1.0, x))
        val signedContrast = (x - 0.50) * 4.0 * x * (1.0 - x)
        return (x + p.midtoneContrast * signedContrast * window).coerceAtLeast(0.0)
    }

    /**
     * Tone signal -> absolute display nits, diffuse-white-anchored with a C1
     * exponential highlight shoulder asymptoting to [Params.peakNits].
     *
     * signal 1.0 -> exactly [Params.diffuseWhiteNits]; signal 0.5 -> half of it;
     * large speculars approach but never exceed peakNits. Slope is continuous
     * (== diffuseWhiteNits) at the signal==1 join.
     */
    fun signalToDisplayNits(signal: Double, p: Params): Double {
        val s = signal.coerceAtLeast(0.0)
        val nitsRaw = s * p.diffuseWhiteNits
        if (nitsRaw <= p.diffuseWhiteNits) return nitsRaw
        val range = p.peakNits - p.diffuseWhiteNits
        val rolled = p.diffuseWhiteNits +
            range * (1.0 - exp(-(nitsRaw - p.diffuseWhiteNits) / range))
        return rolled.coerceAtMost(p.peakNits)
    }

    /** Scene-normalized luminance -> absolute display nits (full canonical luma transfer). */
    fun displayNits(sceneNorm: Double, p: Params): Double =
        signalToDisplayNits(
            profileMidtoneContrast(blackPreservingMidtoneLift(signalShape(sceneNorm, p), p), p),
            p
        )

    /** Scene-normalized luminance -> PQ (ST.2084) code in [0,1], nit-accurate. */
    fun encodePq(sceneNorm: Double, p: Params): Double =
        HdrImagingMath.pqEncodeFromNits(displayNits(sceneNorm, p))

    /**
     * Hue-constant RGB transfer. Returns display-referred tone-signal-space RGB
     * (1.0 == diffuse-white level), luma-preserving with radial highlight
     * desaturation toward neutral so the hue angle is invariant.
     */
    fun mapRgbHuePreserving(
        r: Double,
        g: Double,
        b: Double,
        p: Params,
        warmSkinRedTame: Double = 0.0
    ): Triple<Double, Double, Double> {
        val mapped = DoubleArray(3)
        mapRgbHuePreservingInto(r, g, b, p, warmSkinRedTame, mapped)
        return Triple(mapped[0], mapped[1], mapped[2])
    }

    /**
     * Allocation-free form of [mapRgbHuePreserving] for full-resolution RAW/NDR conversion. The
     * mathematical path is identical; only caller-owned storage replaces the per-pixel [Triple].
     */
    fun mapRgbHuePreservingInto(
        r: Double,
        g: Double,
        b: Double,
        p: Params,
        warmSkinRedTame: Double = 0.0,
        out: DoubleArray,
        offset: Int = 0
    ): DoubleArray {
        require(offset >= 0 && offset + 2 < out.size) { "NDR RGB output requires three values" }
        val sr = r.coerceAtLeast(0.0)
        val sg = g.coerceAtLeast(0.0)
        val sb = b.coerceAtLeast(0.0)
        val y = luma(sr, sg, sb).coerceAtLeast(EPS)
        val mappedY = profileMidtoneContrast(
            blackPreservingMidtoneLift(signalShape(y, p), p),
            p
        )
        val scale = mappedY / y
        var outR = sr * scale
        var outG = sg * scale
        var outB = sb * scale
        // Bounded midtone colour-volume character. Chroma is scaled radially around BT.2020 luma,
        // preserving hue direction and luminance. Skin receives reduced authority; coherent sky blue
        // receives only the profile's small explicit bonus.
        val safeGreen = sg.coerceAtLeast(EPS)
        val redGreenRatio = sr / safeGreen
        val blueGreenRatio = sb / safeGreen
        val peak = maxOf(outR, outG, outB)
        val floor = minOf(outR, outG, outB)
        val relativeChroma = (peak - floor) / maxOf(mappedY, 0.04)
        val colorConfidence = smoothstep(0.035, 0.22, relativeChroma)
        val skin = smoothstep(1.02, 1.16, redGreenRatio) *
            (1.0 - smoothstep(1.58, 2.10, redGreenRatio)) *
            (1.0 - smoothstep(0.98, 1.18, blueGreenRatio))
        val sky = smoothstep(1.03, 1.22, blueGreenRatio) *
            (1.0 - smoothstep(1.00, 1.22, redGreenRatio))
        val tonalWindow = smoothstep(0.035, 0.16, mappedY) *
            (1.0 - smoothstep(0.88, 1.65, mappedY))
        val skinProtection = 1.0 + (0.45 - 1.0) * skin
        val chromaGain = 1.0 +
            (p.chromaBoost + p.skyChromaBonus * sky) *
            colorConfidence * tonalWindow * skinProtection
        outR = mappedY + (outR - mappedY) * chromaGain
        outG = mappedY + (outG - mappedY) * chromaGain
        outB = mappedY + (outB - mappedY) * chromaGain
        // Radial highlight desaturation toward neutral (mappedY): preserves hue angle.
        val desat = (p.highlightDesat * smoothstep(0.55, 1.0, mappedY)).coerceIn(0.0, p.highlightDesat)
        if (desat > 0.0) {
            outR = mappedY + (outR - mappedY) * (1.0 - desat)
            outG = mappedY + (outG - mappedY) * (1.0 - desat)
            outB = mappedY + (outB - mappedY) * (1.0 - desat)
        }
        // ── NDR-owned warm/red overflow reconstruction ───────────────────────────────────────────
        // The camera can clip the red channel first on skin, wood and other warm neutral surfaces.
        // The previous minimum-channel gate then mistook those pixels for saturated red objects and
        // skipped the correction exactly where it was needed. Classify by channel RATIOS instead:
        //   * moderate R/G + useful B/G => skin / warm neutral candidate;
        //   * extreme R/G              => true saturated red, protected;
        //   * very low B/G             => true orange/red object, protected.
        // Reconstruct only the red overflow and a missing-blue floor, then restore the original BT.2020
        // luminance. This removes the cast without a global red gain, broad desaturation, exposure loss,
        // or changes to genuine red/orange objects. 0.0 remains an exact no-op.
        if (warmSkinRedTame > 0.0) {
            val oy = luma(outR, outG, outB).coerceAtLeast(EPS)
            // Classify from the pre-tone source. Highlight desaturation can otherwise add enough blue
            // to a true orange object that it falsely enters the warm-neutral gate.
            val safeGreen = sg.coerceAtLeast(EPS)
            val redGreenRatio = sr / safeGreen
            val blueGreenRatio = sb / safeGreen
            val moderateRed = smoothstep(1.18, 1.55, redGreenRatio) *
                (1.0 - smoothstep(2.80, 4.00, redGreenRatio))
            val bluePresent = smoothstep(0.28, 0.55, blueGreenRatio) *
                (1.0 - smoothstep(1.08, 1.35, blueGreenRatio))
            val exposureWindow = smoothstep(0.04, 0.16, mappedY) *
                (1.0 - smoothstep(6.5, 8.0, mappedY))
            val redOverflow = smoothstep(1.30, 2.10, redGreenRatio)
            val w = (
                warmSkinRedTame * moderateRed * bluePresent * exposureWindow *
                    (0.45 + 0.55 * redOverflow)
                ).coerceIn(0.0, warmSkinRedTame)
            if (w > 0.0) {
                outR += (minOf(outR, outG * 1.42) - outR) * w
                outB += (maxOf(outB, outG * 0.64) - outB) * (w * 0.68)
                val correctedY = luma(outR, outG, outB).coerceAtLeast(EPS)
                val lumaRestore = oy / correctedY
                outR *= lumaRestore
                outG *= lumaRestore
                outB *= lumaRestore
            }
        }
        out[offset] = outR.coerceAtLeast(0.0)
        out[offset + 1] = outG.coerceAtLeast(0.0)
        out[offset + 2] = outB.coerceAtLeast(0.0)
        return out
    }
}
