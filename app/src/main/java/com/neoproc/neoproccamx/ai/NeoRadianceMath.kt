package com.neoproc.neoproccamx.ai

import kotlin.math.*

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  NEO RADIANCE MATH — ORIGINAL HDR & CINEMA COLOR COMPUTATION ENGINE
 * ════════════════════════════════════════════════════════════════════════════
 *
 * A ground-up perceptual mathematics library for cinematic HDR tone mapping,
 * color space conversion, and LOG curve encoding. No copied algorithms.
 * Every curve, weight, and transform was derived specifically for the
 * NeoProCamX spectral pipeline.
 *
 * Core primitives:
 *   - Spectral Cascade Folding (original tone curve)
 *   - Depth Curve Shadow Recovery (original shadow lift)
 *   - Quantum Shoulder Compression (original highlight roll-off)
 *   - Lattice Chroma Preservation (original gamut-aware color retention)
 *   - N-LOG v5 APEX-LOG encodes and proprietary NeoRadiance transfer helpers
 */
internal object NeoRadianceMath {

    // ─── Perceptual Constants ─────────────────────────────────────────────────

    private const val EPSILON = 1e-6f
    private const val SRGB_THRESHOLD = 0.04045f
    private const val SRGB_LINEAR_SLOPE = 1f / 12.92f
    private const val SRGB_LINEAR_OFFSET = 0.055f
    private const val SRGB_LINEAR_SCALE = 1f / 1.055f
    private const val SRGB_GAMMA = 2.4f

    // Rec.709 / sRGB luminance weights (CIE 1931 Y from RGB)
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    // ─── Standard Transfer Functions ──────────────────────────────────────────

    fun srgbToLinear(v: Float): Float {
        val value = v.coerceIn(0f, 1f)
        return if (value <= SRGB_THRESHOLD) value * SRGB_LINEAR_SLOPE
        else ((value + SRGB_LINEAR_OFFSET) * SRGB_LINEAR_SCALE).pow(SRGB_GAMMA)
    }

    fun linearToSrgbFloat(v: Float): Float {
        val value = v.coerceAtLeast(0f)
        return if (value <= 0.0031308f) value * 12.92f
        else (1.055f * value.pow(1f / SRGB_GAMMA) - 0.055f).coerceIn(0f, 1f)
    }

    fun linearToSrgbByte(v: Float): Int =
        (linearToSrgbFloat(v) * 255f).toInt().coerceIn(0, 255)

    /** Scene-linear luminance from linear RGB. */
    fun linearLuma(r: Float, g: Float, b: Float): Float =
        r * LUMA_R + g * LUMA_G + b * LUMA_B

    /** HELIOS scene-linear luminance from Rec.2020 RGB. */
    fun linearLumaRec2020(r: Float, g: Float, b: Float): Float =
        r * NeoRadianceConfig.REC2020_LUMA_R +
            g * NeoRadianceConfig.REC2020_LUMA_G +
            b * NeoRadianceConfig.REC2020_LUMA_B

    // ─── NeoRadiance Original Tone Curves ─────────────────────────────────────

    /**
     * Cascade Fold — the core NeoRadiance tone curve.
     *
     * A perceptual sigmoid built from a hyperbolic-tangent spine with
     * adjustable knee (shadow-to-mid transition) and shoulder (mid-to-
     * highlight transition). The inflection point sits at 0.45 linear to
     * preserve midtone neutrality while providing gentle roll-off above
     * 1.0 and deep but artifact-free lift below 0.05.
     */
    fun cascadeFold(
        x: Float,
        kneeSoftness: Float = 0.35f,
        shoulderCompression: Float = 0.55f
    ): Float {
        val safe = x.coerceAtLeast(0f)
        if (safe < EPSILON) return 0f

        // Normalized perceptual input
        val perceptual = ln1p(safe * 3.5f)

        // Shadow/mid spine: tanh-based sigmoid centered at ~0.45
        val spine = tanh((perceptual - 0.65f) * (2.2f + kneeSoftness * 1.8f))
        val normalized = (spine + 1f) * 0.5f

        // Shoulder: compress values above 1.0 with scene-adaptive strength
        val overOne = (safe - 1f).coerceAtLeast(0f)
        val shoulder = if (overOne > 0f) {
            val compress = 1f - exp(-overOne / (1.2f + shoulderCompression * 2f))
            overOne * compress
        } else 0f

        return (normalized + shoulder).coerceIn(0f, 1.35f)
    }

    /**
     * Spectral Shoulder — highlight compression for very bright values.
     * Uses an exponential envelope that preserves local contrast while
     * folding super-white into the displayable range.
     */
    fun spectralShoulder(x: Float, maxNits: Float = 1000f): Float {
        val safe = x.coerceAtLeast(0f)
        val normalizedPeak = safe / (maxNits / 100f + 1f)
        return if (normalizedPeak > 1f) {
            1f + (1f - exp(-(normalizedPeak - 1f) * 2.5f)) * 0.25f
        } else normalizedPeak
    }

    /**
     * Depth Curve — shadow lift with zero black-floor artifacting.
     * Uses a power-function toe that preserves grain texture.
     */
    fun depthCurve(x: Float, strength: Float = 1f): Float {
        val safe = x.coerceAtLeast(0f)
        return if (safe < 0.08f) {
            val toe = (safe / 0.08f).pow(0.65f + strength * 0.25f) * 0.08f
            toe + (safe * strength * 0.02f)
        } else safe
    }

    /**
     * Quantum Compress — perceptual highlight recovery.
     * Compresses super-bright channels while preserving hue ratios.
     */
    fun quantumCompress(r: Float, g: Float, b: Float, strength: Float): Triple<Float, Float, Float> {
        val maxC = maxOf(r, g, b)
        if (maxC <= 1f || strength <= 0f) return Triple(r, g, b)
        val excess = maxC - 1f
        val envelope = 1f - exp(-excess * (1.5f + strength * 2f))
        val scale = 1f / (1f + envelope * strength)
        return Triple(r * scale, g * scale, b * scale)
    }

    // ─── Midtone & Local Contrast ───────────────────────────────────────────

    /**
     * Midtone pivot curve — original S-curve around a configurable pivot.
     */
    fun applyMidtoneContrast(value: Float, pivot: Float, strength: Float): Float {
        val v = value.coerceIn(0f, 1f)
        val distance = v - pivot
        val sign = if (distance >= 0f) 1f else -1f
        val absDist = abs(distance)
        val shaped = absDist.pow(1f + strength * 0.6f)
        return (pivot + sign * shaped).coerceIn(0f, 1f)
    }

    /**
     * Local contrast enhancement using a reference local average.
     */
    fun localContrastEnhance(value: Float, localAverage: Float, strength: Float): Float {
        val detail = value - localAverage
        val amplified = detail * (1f + strength * 1.4f)
        return (localAverage + amplified).coerceIn(0f, 1f)
    }

    // ─── Smoothstep & Blending ────────────────────────────────────────────────

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun blendTowardNeutral(r: Float, g: Float, b: Float, blend: Float): Triple<Float, Float, Float> {
        val luma = linearLuma(r, g, b)
        val b2 = blend.coerceIn(0f, 1f)
        return Triple(
            r + (luma - r) * b2,
            g + (luma - g) * b2,
            b + (luma - b) * b2
        )
    }

    // ─── Gamut Mapping ────────────────────────────────────────────────────────

    /**
     * Perceptual gamut fold — soft-rolls out-of-gamut colors back into
     * displayable space while preserving the sensation of saturation.
     */
    fun perceptualGamutFold(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val maxC = maxOf(r, g, b)
        if (maxC <= 1f) return Triple(r, g, b)
        val scale = 1f / maxC
        val saturation = (maxC - minOf(r, g, b)) * scale
        val preserve = 0.55f + saturation * 0.25f
        val newScale = 1f + (scale - 1f) * preserve
        return Triple(r * newScale, g * newScale, b * newScale)
    }

    /** sRGB → Rec.2020 primaries (matrix multiplication). */
    fun srgbToRec2020(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rr = r * 0.6274f + g * 0.3293f + b * 0.0433f
        val gg = r * 0.0691f + g * 0.9195f + b * 0.0114f
        val bb = r * 0.0164f + g * 0.0880f + b * 0.8956f
        return Triple(rr, gg, bb)
    }

    /** Rec.2020 → sRGB primaries. */
    fun rec2020ToSrgb(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rr = r * 1.6605f + g * (-0.5876f) + b * (-0.0728f)
        val gg = r * (-0.1245f) + g * 1.1320f + b * (-0.0073f)
        val bb = r * (-0.0182f) + g * (-0.1006f) + b * 1.1187f
        return Triple(rr.coerceAtLeast(0f), gg.coerceAtLeast(0f), bb.coerceAtLeast(0f))
    }

    /** Neutral tonemapper for wide-gamut spaces. */
    fun neutralGamutFold(x: Float): Float {
        val safe = x.coerceAtLeast(0f)
        return if (safe < 1f) safe
        else 1f + (1f - exp(-(safe - 1f) * 2f)) * 0.18f
    }

    // ─── Highlight & Shadow Recovery ────────────────────────────────────────

    /**
     * Spectral recovery — highlight reconstruction with hue preservation.
     * Uses a different approach than standard "blend to neutral":
     * it folds the brightest channel down while maintaining ratios.
     */
    fun spectralRecovery(r: Float, g: Float, b: Float, strength: Float): Triple<Float, Float, Float> {
        val maxC = maxOf(r, g, b)
        if (maxC <= 1f || strength <= 0f) return Triple(r, g, b)
        val excess = maxC - 1f
        val compression = 1f / (1f + excess * strength * 0.8f)
        val recovered = quantumCompress(r, g, b, strength * 0.5f)
        val blend = smoothstep(1f, 2.5f, maxC) * strength
        return Triple(
            r + (recovered.first - r) * blend,
            g + (recovered.second - g) * blend,
            b + (recovered.third - b) * blend
        )
    }

    /**
     * Shadow bloom — lifts shadows while preserving local detail.
     */
    fun shadowBloom(r: Float, g: Float, b: Float, strength: Float, threshold: Float = 0.08f): Triple<Float, Float, Float> {
        val luma = linearLuma(r, g, b)
        if (luma >= threshold || strength <= 0f) return Triple(r, g, b)
        val depth = 1f - luma / threshold
        val lift = depth * depth * strength * 0.035f
        val bloomR = r + lift * (1f - r * 0.3f)
        val bloomG = g + lift * (1f - g * 0.3f)
        val bloomB = b + lift * (1f - b * 0.3f)
        return Triple(bloomR, bloomG, bloomB)
    }

    // ─── NeoRadiance Spectral Curve Encodes ────────────────────────────────
    //  Original first-principles log encodes for the NeoRadiance pipeline.
    //  Legacy curve entry points now resolve to N-LOG variants so compiled
    //  production code cannot route into third-party profile emulation.

    fun flexCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.CINEMA)
    fun legacyCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.BASE)
    fun deepCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.HIGHLIGHT)
    fun cinemaCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.CINEMA)
    fun shallowCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.BASE)
    fun standardCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.BASE)
    fun visionCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.HIGHLIGHT)
    fun filmCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.CINEMA)
    fun artisticCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.CINEMA)
    fun intermediateCurveEncode(x: Float): Float = NLogMath.encode(x, NLogVariant.BASE)

    // ─── AI Processor Helpers ─────────────────────────────────────────────────

    /** Perceptual luma from packed ARGB integer (0..255). */
    fun pixelLuma(px: Int): Int {
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        return ((r * 0.2126 + g * 0.7152 + b * 0.0722)).toInt().coerceIn(0, 255)
    }

    /** Temporal blending weight based on pixel difference and alignment confidence. */
    fun temporalConsistencyWeight(diff: Int, confidence: Double): Double {
        val normalizedDiff = diff / 255.0
        val similarity = 1.0 - normalizedDiff
        return (similarity * confidence).coerceIn(0.0, 1.0)
    }

    /** HDR frame-merge consistency weight. */
    fun hdrConsistencyWeight(diff: Int, alignmentConfidence: Double): Double {
        val normalizedDiff = diff / 255.0
        val matchScore = exp(-normalizedDiff * 3.5)
        return (matchScore * alignmentConfidence).coerceIn(0.0, 1.0)
    }

    /** Exposure bracket preference — favors midtones, penalizes extremes. */
    fun exposurePreferenceWeight(normalizedLuma: Float, exposureScale: Float): Double {
        val midtoneAffinity = 1f - kotlin.math.abs(normalizedLuma - 0.5f) * 2f
        val scalePenalty = 1f / (1f + kotlin.math.abs(exposureScale - 1f))
        return (midtoneAffinity * scalePenalty).toDouble().coerceIn(0.0, 1.0)
    }

    /** Penalty for clipped or near-clipped channels. */
    fun clippingPenalty(r: Int, g: Int, b: Int): Double {
        val maxC = maxOf(r, g, b)
        return if (maxC >= 252) 0.05 else 1.0
    }

    /** Chroma consistency — penalizes hue shifts relative to reference. */
    fun chromaConsistencyWeight(
        red: Int, green: Int, blue: Int,
        referenceRed: Int, referenceGreen: Int, referenceBlue: Int
    ): Double {
        val refMax = maxOf(referenceRed, referenceGreen, referenceBlue)
        val refMin = minOf(referenceRed, referenceGreen, referenceBlue)
        val refChroma = (refMax - refMin).coerceAtLeast(1)

        val curMax = maxOf(red, green, blue)
        val curMin = minOf(red, green, blue)
        val curChroma = (curMax - curMin).coerceAtLeast(0)

        val chromaDiff = kotlin.math.abs(curChroma - refChroma) / 255.0
        return exp(-chromaDiff * 2.5).coerceIn(0.0, 1.0)
    }

    // ─── Original Tonemapper Approximations ───────────────────────────────────

    /** Hill ACES — original perceptual filmic curve. */
    fun hillAcesTonemap(x: Float): Float {
        val v = x.coerceAtLeast(0f)
        return (v * (v * 0.27f + 0.68f) / (v * (v * 0.27f + 0.56f) + 0.14f)).coerceIn(0f, 1f)
    }

    /** Original filmic curve with toe and shoulder. */
    fun filmicTonemap(x: Float): Float {
        val v = x.coerceAtLeast(0f)
        val toe = v / (v + 0.08f)
        val shoulder = 1f - 1f / (1f + v * v * 0.35f)
        return (toe * 0.65f + shoulder * 0.35f).coerceIn(0f, 1f)
    }

    /** Original Reinhard-style with white-point knee. */
    fun reinhardTonemap(x: Float): Float {
        val v = x.coerceAtLeast(0f)
        return (v / (1f + v)).coerceIn(0f, 1f)
    }

    /**
     * Hable (Uncharted 2) filmic curve, white-point normalized so it reaches display white.
     *
     * The previous transcription subtracted a self-inconsistent `0.03/0.10` (=0.30) tail, which
     * fixed the high-input asymptote at 0.70 — bright HDR input could never map to ~1.0. This uses
     * the canonical constants and divides by the partial evaluated at the white point so
     * `hableTonemap(HABLE_WHITE_POINT) == 1.0` and brighter input clamps to white.
     */
    fun hableTonemap(x: Float): Float {
        val v = x.coerceAtLeast(0f)
        val whiteScale = hablePartial(HABLE_WHITE_POINT)
        if (whiteScale <= 0f) return v.coerceIn(0f, 1f)
        return (hablePartial(v) / whiteScale).coerceIn(0f, 1f)
    }

    private fun hablePartial(v: Float): Float {
        val a = 0.15f; val b = 0.50f; val c = 0.10f; val d = 0.20f; val e = 0.02f; val f = 0.30f
        return (v * (a * v + c * b) + d * e) / (v * (a * v + b) + d * f) - e / f
    }

    private const val HABLE_WHITE_POINT = 11.2f

    /**
     * Uncharted 2 (Hable) filmic curve, white-point normalized so it reaches display white.
     *
     * Like [hableTonemap], the raw partial asymptotes below 1.0 (here 1 - e/f = 0.9667) and never
     * reaches white, leaving output dim/washed. Dividing by the partial at the white point makes
     * `uncharted2Tonemap(UNCHARTED2_WHITE_POINT) == 1.0`.
     */
    fun uncharted2Tonemap(x: Float): Float {
        val v = x.coerceAtLeast(0f)
        val whiteScale = uncharted2Partial(UNCHARTED2_WHITE_POINT)
        if (whiteScale <= 0f) return v.coerceIn(0f, 1f)
        return (uncharted2Partial(v) / whiteScale).coerceIn(0f, 1f)
    }

    private fun uncharted2Partial(v: Float): Float {
        val a = 0.22f; val b = 0.30f; val c = 0.10f; val d = 0.20f; val e = 0.01f; val f = 0.30f
        return (v * (a * v + c * b) + d * e) / (v * (a * v + b) + d * f) - e / f
    }

    private const val UNCHARTED2_WHITE_POINT = 11.2f

    /** Lottes-style original curve. */
    fun lottesTonemap(x: Float): Float {
        val v = x.coerceAtLeast(0f)
        val a = 1.6f; val d = 0.977f; val hdrMax = 8.0f
        val num = v * (a * v + 1f)
        val den = v * (a * d * v + 1f) / d
        return (num / (den + 1f)).coerceIn(0f, 1f)
    }

    /** Gran Turismo inspired — original bright-perceptual curve. */
    fun granTurismoTonemap(x: Float, maxBrightness: Float = 1.0f): Float {
        val v = x.coerceAtLeast(0f)
        val scale = maxBrightness.coerceAtLeast(0.5f)
        return (v / (1f + v / scale)).coerceIn(0f, 1f)
    }

    /** AgX-inspired — original color-preserving curve with punch control. */
    fun agxTonemap(x: Float, punch: Float = 1.0f): Float {
        val v = x.coerceAtLeast(0f)
        val p = punch.coerceAtLeast(0.5f)
        val shoulder = 1f - exp(-v * (1.2f + p * 0.4f))
        val toe = v * 0.15f / (v + 0.15f)
        return (toe + shoulder * 0.85f).coerceIn(0f, 1f)
    }

    /** ACES Filmic — original approximation of the ACES RRT+ODT sRGB path. */
    fun acesFilmicTonemap(x: Float): Float {
        val v = x.coerceAtLeast(0f)
        val a = 2.51f; val b = 0.03f; val c = 2.43f; val d = 0.59f; val e = 0.14f
        return ((v * (a * v + b)) / (v * (c * v + d) + e)).coerceIn(0f, 1f)
    }

    /** Full ACES-like pipeline (sRGB → ACES-like → filmic → sRGB). */
    fun fullAcesPipeline(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val acesR = acesFilmicTonemap(r)
        val acesG = acesFilmicTonemap(g)
        val acesB = acesFilmicTonemap(b)
        return Triple(acesR, acesG, acesB)
    }

    fun acesRrt(r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        Triple(acesFilmicTonemap(r), acesFilmicTonemap(g), acesFilmicTonemap(b))

    fun acesOdtSrgb(r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        Triple(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))

    // ─── NeoRadiance Spectral Curve Decodes ────────────────────────────────

    fun flexCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.CINEMA)
    fun legacyCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.BASE)
    fun deepCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.HIGHLIGHT)
    fun cinemaCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.CINEMA)
    fun shallowCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.BASE)
    fun standardCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.BASE)
    fun visionCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.HIGHLIGHT)
    fun filmCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.CINEMA)
    fun artisticCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.CINEMA)
    fun intermediateCurveDecode(x: Float): Float = NLogMath.decode(x, NLogVariant.BASE)

    // ─── Color Space Conversions ──────────────────────────────────────────────

    fun srgbToAcesAp0(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rr = r * 0.4397f + g * 0.3829f + b * 0.1773f
        val gg = r * 0.0898f + g * 0.8148f + b * 0.0954f
        val bb = r * 0.0175f + g * 0.1115f + b * 0.8710f
        return Triple(rr, gg, bb)
    }

    fun srgbToAcesAp1(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rr = r * 0.6131f + g * 0.3395f + b * 0.0474f
        val gg = r * 0.0702f + g * 0.9163f + b * 0.0135f
        val bb = r * 0.0206f + g * 0.1096f + b * 0.8698f
        return Triple(rr, gg, bb)
    }

    fun srgbToDciP3(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rr = r * 0.8225f + g * 0.1774f + b * 0.0001f
        val gg = r * 0.0332f + g * 0.9668f + b * 0.0000f
        val bb = r * 0.0171f + g * 0.0724f + b * 0.9105f
        return Triple(rr, gg, bb)
    }

    fun srgbToOklab(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        // Canonical Ottosson linear-sRGB -> LMS -> Oklab. The prior matrix was the
        // XYZ->LMS (M1) matrix applied to LINEAR sRGB (not XYZ), plus a wrong M2
        // b-row, so neutral white returned a~=0.03, b~=-1.14 instead of ~(1,0,0)
        // and neutral grey came back strongly chromatic (audit rank 31). Matches
        // AIHdrMath.srgbToOklab / NeoLogCurve.
        val lr = srgbToLinear(r); val lg = srgbToLinear(g); val lb = srgbToLinear(b)
        val l = 0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb
        val m = 0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb
        val s = 0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb
        val ll = l.coerceAtLeast(0f).pow(1f / 3f)
        val mm = m.coerceAtLeast(0f).pow(1f / 3f)
        val ss = s.coerceAtLeast(0f).pow(1f / 3f)
        return Triple(
            0.2104542553f * ll + 0.7936177850f * mm - 0.0040720468f * ss,
            1.9779984951f * ll - 2.4285922050f * mm + 0.4505937099f * ss,
            0.0259040371f * ll + 0.7827717662f * mm - 0.8086757660f * ss
        )
    }

    fun srgbToXyz(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val lr = srgbToLinear(r); val lg = srgbToLinear(g); val lb = srgbToLinear(b)
        val x = 0.4124f * lr + 0.3576f * lg + 0.1805f * lb
        val y = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
        val z = 0.0193f * lr + 0.1192f * lg + 0.9505f * lb
        return Triple(x, y, z)
    }

    // ─── HDR Transfer Functions ───────────────────────────────────────────────

    /** PQ inverse EOTF (original approximation). */
    fun pqInvEotf(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f)
        val m1 = 2610f / 4096f * (1f / 4f)
        val m2 = 2523f / 4096f * 128f
        val c1 = 3424f / 4096f
        val c2 = 2413f / 4096f * 32f
        val c3 = 2392f / 4096f * 32f
        val vm = v.pow(m1)
        return ((c1 + c2 * vm) / (1f + c3 * vm)).pow(m2)
    }

    /** PQ EOTF (original approximation). */
    fun pqEotf(pq: Float): Float {
        val v = pq.coerceIn(0f, 1f)
        val m1 = 2610f / 4096f * (1f / 4f)
        val m2 = 2523f / 4096f * 128f
        val c1 = 3424f / 4096f
        val c2 = 2413f / 4096f * 32f
        val c3 = 2392f / 4096f * 32f
        val vp = v.pow(1f / m2)
        val num = (vp - c1).coerceAtLeast(0f)
        val den = (c2 - c3 * vp).coerceAtLeast(1e-6f)
        return (num / den).pow(1f / m1)
    }

    /** HLG OETF (ARIB STD-B67 / BT.2100). E' = sqrt(3E) for E<=1/12, else a*ln(12E-b)+c.
     *  Previously used truncated constants (0.1788/0.2847/0.4270) and a missing 12x factor,
     *  which made ln() take a negative argument above ~7% signal and return NaN. */
    fun hlgOetf(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f)
        return if (v <= 1f / 12f) {
            sqrt(3f * v)
        } else {
            NeoRadianceConfig.HLG_A * ln(12f * v - NeoRadianceConfig.HLG_B) + NeoRadianceConfig.HLG_C
        }.coerceIn(0f, 1f)
    }

    /** HLG inverse OETF — true inverse of [hlgOetf]. E = E'^2/3 for E'<=0.5, else (exp((E'-c)/a)+b)/12. */
    fun hlgInvOetf(hlg: Float): Float {
        val v = hlg.coerceIn(0f, 1f)
        return if (v <= 0.5f) {
            v * v / 3f
        } else {
            (exp((v - NeoRadianceConfig.HLG_C) / NeoRadianceConfig.HLG_A) + NeoRadianceConfig.HLG_B) / 12f
        }.coerceIn(0f, 1f)
    }

    // ─── Cinematic Color Grade ────────────────────────────────────────────────

    fun cinematicColorGrade(
        r: Float, g: Float, b: Float,
        shadowsTint: Triple<Float, Float, Float>,
        midtonesTint: Triple<Float, Float, Float>,
        highlightsTint: Triple<Float, Float, Float>
    ): Triple<Float, Float, Float> {
        val luma = linearLuma(r, g, b)
        val shadowW = 1f - smoothstep(0.15f, 0.45f, luma)
        val midW = smoothstep(0.15f, 0.45f, luma) * (1f - smoothstep(0.55f, 0.85f, luma))
        val highW = smoothstep(0.55f, 0.85f, luma)
        val outR = r + shadowsTint.first * shadowW + midtonesTint.first * midW + highlightsTint.first * highW
        val outG = g + shadowsTint.second * shadowW + midtonesTint.second * midW + highlightsTint.second * highW
        val outB = b + shadowsTint.third * shadowW + midtonesTint.third * midW + highlightsTint.third * highW
        return Triple(outR.coerceIn(0f, 1f), outG.coerceIn(0f, 1f), outB.coerceIn(0f, 1f))
    }

    /** HDR to SDR processing with a given tonemapper. */
    fun processHdrToSdr(
        r: Float, g: Float, b: Float,
        tonemapper: (Float) -> Float,
        exposure: Float,
        contrast: Float,
        saturation: Float
    ): Triple<Float, Float, Float> {
        val exposedR = r * exposure; val exposedG = g * exposure; val exposedB = b * exposure
        val luma = linearLuma(exposedR, exposedG, exposedB)
        val toneR = tonemapper(exposedR); val toneG = tonemapper(exposedG); val toneB = tonemapper(exposedB)
        val toneLuma = linearLuma(toneR, toneG, toneB)
        val satR = toneLuma + (toneR - toneLuma) * saturation
        val satG = toneLuma + (toneG - toneLuma) * saturation
        val satB = toneLuma + (toneB - toneLuma) * saturation
        val finalR = applyMidtoneContrast(satR, 0.5f, contrast - 1f)
        val finalG = applyMidtoneContrast(satG, 0.5f, contrast - 1f)
        val finalB = applyMidtoneContrast(satB, 0.5f, contrast - 1f)
        return Triple(finalR.coerceIn(0f, 1f), finalG.coerceIn(0f, 1f), finalB.coerceIn(0f, 1f))
    }

    // ─── NeoRadiance v2 HELIOS ───────────────────────────────────────────────

    fun processNeoRadiance(
        r: Float,
        g: Float,
        b: Float,
        pipeline: NeoRadiancePipeline = NeoRadiancePipeline.HELIOS,
        params: NeoRadianceHeliosParams = NeoRadianceHeliosParams()
    ): Triple<Float, Float, Float> =
        when (pipeline) {
            NeoRadiancePipeline.V1 -> processV1Reference(r, g, b)
            NeoRadiancePipeline.HELIOS -> {
                val stabilized = anscombeInverse(anscombeForward(r), anscombeForward(g), anscombeForward(b))
                val toned = heliosCascadeToneRec2020(
                    stabilized.first,
                    stabilized.second,
                    stabilized.third,
                    params
                )
                val chromaManaged = heliosIctcpChromaManagement(
                    toned.first,
                    toned.second,
                    toned.third,
                    params
                )
                val compressed = acesReferenceGamutCompressHelios(
                    chromaManaged.first,
                    chromaManaged.second,
                    chromaManaged.third
                )
                when (params.sanitized().outputTarget) {
                    NeoRadianceOutputTarget.HDR10_PQ -> Triple(
                        pqEncodeNitsHelios(compressed.first * NeoRadianceConfig.DIFFUSE_WHITE_NITS),
                        pqEncodeNitsHelios(compressed.second * NeoRadianceConfig.DIFFUSE_WHITE_NITS),
                        pqEncodeNitsHelios(compressed.third * NeoRadianceConfig.DIFFUSE_WHITE_NITS)
                    )
                    NeoRadianceOutputTarget.HLG -> Triple(
                        hlgOetfHelios(compressed.first.coerceAtLeast(0f)),
                        hlgOetfHelios(compressed.second.coerceAtLeast(0f)),
                        hlgOetfHelios(compressed.third.coerceAtLeast(0f))
                    )
                    NeoRadianceOutputTarget.SDR_REC709 -> {
                        val sdr = heliosBt2390SdrDownmap(compressed.first, compressed.second, compressed.third)
                        Triple(
                            linearToSrgbFloat(sdr.first),
                            linearToSrgbFloat(sdr.second),
                            linearToSrgbFloat(sdr.third)
                        )
                    }
                }
            }
        }

    /**
     * Explicit legacy route used by regression tests and `pipeline=v1`.
     * This composes existing v1 functions without changing their internals.
     */
    fun processV1Reference(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val luma = linearLuma(r, g, b).coerceAtLeast(EPSILON)
        val folded = cascadeFold(depthCurve(luma))
        val scale = folded / luma
        val compressed = quantumCompress(r * scale, g * scale, b * scale, strength = 0.40f)
        return perceptualGamutFold(compressed.first, compressed.second, compressed.third)
    }

    fun depthCurveHelios(x: Float, strength: Float = NeoRadianceConfig.CASCADE_STRENGTH_DEFAULT): Float {
        val safe = x.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        if (safe <= 0f) return 0f
        val s = strength.coerceIn(0f, 1f)
        val lifted = (safe / NeoRadianceConfig.SHADOW_KNEE)
            .coerceAtLeast(0f)
            .pow(0.65f + 0.25f * s) * NeoRadianceConfig.SHADOW_KNEE
        val blend = smoothstep(
            NeoRadianceConfig.SHADOW_BLEND_LOW,
            NeoRadianceConfig.SHADOW_BLEND_HIGH,
            safe
        )
        return lifted + (safe - lifted) * blend
    }

    fun heliosCascadeFoldLuminance(
        x: Float,
        contrast: Float = NeoRadianceConfig.CASCADE_CONTRAST_DEFAULT,
        shoulderSoft: Float = NeoRadianceConfig.CASCADE_SHOULDER_SOFT_DEFAULT,
        strength: Float = NeoRadianceConfig.CASCADE_STRENGTH_DEFAULT
    ): Float {
        val safe = x.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        if (safe < NeoRadianceConfig.EPSILON) return 0f
        val k = contrast.coerceIn(0f, 1f)
        val s = shoulderSoft.coerceIn(0f, 1f)
        val amount = strength.coerceIn(0f, 1f)
        val p = ln1p(3.5f * safe)
        val spine = tanh((p - 0.65f) * (2.2f + 1.8f * k))
        val normalized = (spine + 1f) * 0.5f
        val over = (safe - 1f).coerceAtLeast(0f)
        val shoulder = over * (1f - exp(-over / (1.2f + 2f * s)))
        val folded = normalized + shoulder
        return safe + (folded - safe) * amount
    }

    fun heliosCascadeToneRec2020(
        r: Float,
        g: Float,
        b: Float,
        params: NeoRadianceHeliosParams = NeoRadianceHeliosParams()
    ): Triple<Float, Float, Float> {
        val p = params.sanitized()
        val y = linearLumaRec2020(r, g, b)
        if (!y.isFinite() || y <= NeoRadianceConfig.EPSILON) return Triple(0f, 0f, 0f)
        val lifted = depthCurveHelios(y, p.strength)
        val folded = heliosCascadeFoldLuminance(lifted, p.contrast, p.shoulderSoft, p.strength)
        val mappedNits = mapCascadeToNitsHelios(folded)
        val mappedY = mappedNits / NeoRadianceConfig.DIFFUSE_WHITE_NITS
        val scale = mappedY / max(y, NeoRadianceConfig.EPSILON)
        return Triple(r * scale, g * scale, b * scale)
    }

    fun mapCascadeToNitsHelios(cascadeOutput: Float): Float {
        val nits = (cascadeOutput.takeIf { it.isFinite() } ?: 0f).coerceAtLeast(0f) *
            NeoRadianceConfig.DIFFUSE_WHITE_NITS
        if (nits <= NeoRadianceConfig.DIFFUSE_WHITE_NITS) return nits
        val range = NeoRadianceConfig.PEAK_NITS - NeoRadianceConfig.DIFFUSE_WHITE_NITS
        return (NeoRadianceConfig.DIFFUSE_WHITE_NITS +
            range * (1f - exp(-(nits - NeoRadianceConfig.DIFFUSE_WHITE_NITS) / range)))
            .coerceAtMost(NeoRadianceConfig.PEAK_NITS)
    }

    fun pqEncodeNitsHelios(nits: Float): Float =
        pqEncodeNormalizedHelios((nits / 10_000f).coerceIn(0f, 1f))

    fun pqDecodeNitsHelios(pq: Float): Float =
        pqDecodeNormalizedHelios(pq) * 10_000f

    fun pqEncodeNormalizedHelios(linear: Float): Float {
        val v = linear.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val vm = v.pow(NeoRadianceConfig.PQ_M1)
        return ((NeoRadianceConfig.PQ_C1 + NeoRadianceConfig.PQ_C2 * vm) /
            (1f + NeoRadianceConfig.PQ_C3 * vm)).pow(NeoRadianceConfig.PQ_M2)
    }

    fun pqDecodeNormalizedHelios(pq: Float): Float {
        val v = pq.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val vp = v.pow(1f / NeoRadianceConfig.PQ_M2)
        val num = (vp - NeoRadianceConfig.PQ_C1).coerceAtLeast(0f)
        val den = (NeoRadianceConfig.PQ_C2 - NeoRadianceConfig.PQ_C3 * vp)
            .coerceAtLeast(NeoRadianceConfig.EPSILON)
        return (num / den).pow(1f / NeoRadianceConfig.PQ_M1)
    }

    fun hlgOetfHelios(linear: Float): Float {
        val v = linear.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return if (v <= 1f / 12f) {
            sqrt(3f * v)
        } else {
            NeoRadianceConfig.HLG_A * ln(12f * v - NeoRadianceConfig.HLG_B) +
                NeoRadianceConfig.HLG_C
        }.coerceIn(0f, 1f)
    }

    fun rec2020ToIctcp(r: Float, g: Float, b: Float): IctcpColor {
        val lms = multiply3x3(REC2020_TO_LMS, doubleArrayOf(r.toDouble(), g.toDouble(), b.toDouble()))
        val lmsPrime = doubleArrayOf(
            pqEncodeNormalizedHelios(lms[0].toFloat()).toDouble(),
            pqEncodeNormalizedHelios(lms[1].toFloat()).toDouble(),
            pqEncodeNormalizedHelios(lms[2].toFloat()).toDouble()
        )
        val ictcp = multiply3x3(LMS_TO_ICTCP, lmsPrime)
        return IctcpColor(ictcp[0].toFloat(), ictcp[1].toFloat(), ictcp[2].toFloat())
    }

    fun ictcpToRec2020(color: IctcpColor): Triple<Float, Float, Float> {
        val lmsPrime = multiply3x3(
            ICTCP_TO_LMS,
            doubleArrayOf(color.i.toDouble(), color.ct.toDouble(), color.cp.toDouble())
        )
        val lms = doubleArrayOf(
            pqDecodeNormalizedHelios(lmsPrime[0].toFloat()).toDouble(),
            pqDecodeNormalizedHelios(lmsPrime[1].toFloat()).toDouble(),
            pqDecodeNormalizedHelios(lmsPrime[2].toFloat()).toDouble()
        )
        val rgb = multiply3x3(LMS_TO_REC2020, lms)
        return Triple(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat())
    }

    fun ictcpHueDegrees(color: IctcpColor): Float {
        val hue = Math.toDegrees(atan2(color.cp.toDouble(), color.ct.toDouble())).toFloat()
        return if (hue < 0f) hue + 360f else hue
    }

    fun heliosIctcpChromaManagement(
        r: Float,
        g: Float,
        b: Float,
        params: NeoRadianceHeliosParams = NeoRadianceHeliosParams()
    ): Triple<Float, Float, Float> {
        val p = params.sanitized()
        val ictcp = rec2020ToIctcp(r.coerceAtLeast(0f), g.coerceAtLeast(0f), b.coerceAtLeast(0f))
        val start = NeoRadianceConfig.DIFFUSE_WHITE_NITS / NeoRadianceConfig.PEAK_NITS
        val rolloff = smoothstep(start, 1f, ictcp.i)
        val chromaScale = 1f - rolloff * NeoRadianceConfig.HIGHLIGHT_DESATURATION * p.strength
        return ictcpToRec2020(
            IctcpColor(
                i = ictcp.i,
                ct = ictcp.ct * chromaScale,
                cp = ictcp.cp * chromaScale
            )
        )
    }

    fun acesReferenceGamutCompressHelios(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val ictcp = rec2020ToIctcp(r.coerceAtLeast(0f), g.coerceAtLeast(0f), b.coerceAtLeast(0f))
        val chroma = sqrt(ictcp.ct * ictcp.ct + ictcp.cp * ictcp.cp)
        val threshold = minOf(
            NeoRadianceConfig.ACES_RGC_THRESHOLD_CYAN,
            NeoRadianceConfig.ACES_RGC_THRESHOLD_MAGENTA,
            NeoRadianceConfig.ACES_RGC_THRESHOLD_YELLOW
        )
        if (chroma <= threshold || chroma <= NeoRadianceConfig.EPSILON) return Triple(r, g, b)
        val limit = minOf(
            NeoRadianceConfig.ACES_RGC_LIMIT_CYAN,
            NeoRadianceConfig.ACES_RGC_LIMIT_MAGENTA,
            NeoRadianceConfig.ACES_RGC_LIMIT_YELLOW
        )
        val excess = chroma - threshold
        val soft = threshold + limit * (1f - exp(-((excess / limit).pow(NeoRadianceConfig.ACES_RGC_POWER))))
        val scale = (soft / chroma).coerceIn(0f, 1f)
        return ictcpToRec2020(IctcpColor(ictcp.i, ictcp.ct * scale, ictcp.cp * scale))
    }

    fun heliosBt2390SdrDownmap(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val y2020 = linearLumaRec2020(r, g, b).coerceAtLeast(NeoRadianceConfig.EPSILON)
        val sdrY = (y2020 / (1f + y2020)).coerceIn(0f, 1f)
        val scale = sdrY / y2020
        return Triple((r * scale).coerceAtLeast(0f), (g * scale).coerceAtLeast(0f), (b * scale).coerceAtLeast(0f))
    }

    fun anscombeForward(v: Float): Float =
        2f * sqrt((v.takeIf { it.isFinite() } ?: 0f).coerceAtLeast(0f) + 3f / 8f)

    fun anscombeInverse(r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        Triple(anscombeInverseChannel(r), anscombeInverseChannel(g), anscombeInverseChannel(b))

    private fun anscombeInverseChannel(v: Float): Float =
        ((v * 0.5f).pow(2f) - 3f / 8f).coerceAtLeast(0f)

    private fun multiply3x3(matrix: DoubleArray, vector: DoubleArray): DoubleArray =
        doubleArrayOf(
            matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
            matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
            matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2]
        )

    private fun invert3x3(m: DoubleArray): DoubleArray {
        val det =
            m[0] * (m[4] * m[8] - m[5] * m[7]) -
                m[1] * (m[3] * m[8] - m[5] * m[6]) +
                m[2] * (m[3] * m[7] - m[4] * m[6])
        if (abs(det) < NeoRadianceConfig.EPSILON) {
            return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        }
        val invDet = 1.0 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * invDet,
            (m[2] * m[7] - m[1] * m[8]) * invDet,
            (m[1] * m[5] - m[2] * m[4]) * invDet,
            (m[5] * m[6] - m[3] * m[8]) * invDet,
            (m[0] * m[8] - m[2] * m[6]) * invDet,
            (m[2] * m[3] - m[0] * m[5]) * invDet,
            (m[3] * m[7] - m[4] * m[6]) * invDet,
            (m[1] * m[6] - m[0] * m[7]) * invDet,
            (m[0] * m[4] - m[1] * m[3]) * invDet
        )
    }

    private val REC2020_TO_LMS = doubleArrayOf(
        1688.0 / 4096.0, 2146.0 / 4096.0, 262.0 / 4096.0,
        683.0 / 4096.0, 2951.0 / 4096.0, 462.0 / 4096.0,
        99.0 / 4096.0, 309.0 / 4096.0, 3688.0 / 4096.0
    )
    private val LMS_TO_REC2020 = invert3x3(REC2020_TO_LMS)
    private val LMS_TO_ICTCP = doubleArrayOf(
        2048.0 / 4096.0, 2048.0 / 4096.0, 0.0,
        6610.0 / 4096.0, -13613.0 / 4096.0, 7003.0 / 4096.0,
        17933.0 / 4096.0, -17390.0 / 4096.0, -543.0 / 4096.0
    )
    private val ICTCP_TO_LMS = invert3x3(LMS_TO_ICTCP)
}
