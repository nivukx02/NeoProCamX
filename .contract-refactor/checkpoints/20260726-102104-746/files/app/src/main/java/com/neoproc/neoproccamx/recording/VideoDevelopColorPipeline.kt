package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.AcesColorEngine
import com.neoproc.neoproccamx.color.AcesInputColorSpace
import com.neoproc.neoproccamx.color.AcesOutputColorSpace
import com.neoproc.neoproccamx.color.AcesRgb
import com.neoproc.neoproccamx.snce.SnceAdaptiveLut
import com.neoproc.neoproccamx.snce.SnceCam16
import com.neoproc.neoproccamx.snce.SnceNeuralEnhancement
import com.neoproc.neoproccamx.snce.SnceNrliAnalyticLut
import com.neoproc.neoproccamx.snce.SnceV2Engine

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  VIDEO DEVELOP — ELITE OFFLINE COLOUR PIPELINE  (record-then-develop core)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The heart of the OFFLINE "develop" pass for the record-then-develop engine. The live record lane
 * saves a flat, high-fidelity full-sensor 10-bit intermediate at locked 30 fps (zero drops); this
 * pipeline is what the SDR-Convert / NDR-Convert Settings cards run afterwards to fold in the full
 * elite colour stack that is too heavy for the single-GPU live path:
 *
 *   Stage A — SNCE v2 (14-stage HVC) per-pixel colour-appearance chain: working-RGB → XYZ →
 *             [SnceCam16.forward] → [com.neoproc.neoproccamx.snce.SnceAppearancePhenomena.correctAppearance]
 *             (Hunt / Stevens / Helmholtz–Kohlrausch / Bezold–Brücke / Abney — "SNCE saturation") →
 *             optional per-class NRLI profile → [SnceCam16.inverseFromJch] → working-RGB. Strength-blended.
 *   Stage B — ACES: [AcesColorEngine.toAcesCg] IDT → [AcesColorEngine.applyFittedRrtOdt] RRT+ODT. Only
 *             enabled by the caller on a scene-referred working space (the NDR/HLG-linearised path);
 *             off by default on the display-referred SDR intermediate to avoid double tone-mapping.
 *   Stage C — OmniClarity / NRLI creative look: the analytic FILM-PRINT 33³ show-LUT
 *             ([SnceNrliAnalyticLut.buildFilmPrintLut], bounded ±0.14) sampled tetrahedrally
 *             ([SnceNeuralEnhancement.tetrahedralSampleLut]). Strength-blended, SDR only.
 *
 * NO-DOWNGRADE CONTRACT (test-locked): with every stage disabled ([VideoDevelopColorOptions.isIdentity]),
 * [developPixel] returns its input triplet BIT-FOR-BIT for in-gamut samples — an exact passthrough. Each
 * stage is independently strength-gated with a linear blend, so 0 = passthrough and any value in between is
 * a bounded, monotone dial. The pipeline holds no per-frame mutable state ([SnceV2Engine.processSampleRgb]
 * is pure given the viewing conditions), so instances are safe to fan out across the CPU-parallel develop
 * workers a frame at a time.
 *
 * WORKING SPACE: defaults to Rec.709-linear (the SDR intermediate's space, matching [SnceV2Engine]'s
 * default `rgbToXyz`). The NDR develop passes a Rec.2020→XYZ matrix via [workingRgbToXyz] after
 * linearising HLG. CAM16 assumes a diffuse white near Y≈1; because Stage A is a bounded, strength-blended
 * round-trip it stays well-behaved on display-referred [0,1] input too — it reshapes appearance rather than
 * re-exposing. (Full white-normalisation + the PQ nit-scale tone-map are wired in the NDR converter phase.)
 */
class VideoDevelopColorPipeline(
    val options: VideoDevelopColorOptions = VideoDevelopColorOptions(),
    workingRgbToXyz: FloatArray? = null,
    viewingConditions: SnceCam16.SnceViewingConditions? = null,
    private val sceneLuminanceLA: Float = DEFAULT_SCENE_LA,
    /**
     * White-normalisation factor applied to the working RGB ONLY around Stage A (SNCE), so CAM16 runs at
     * its designed operating point (diffuse white ≈ Y 1.0) even when the intermediate is scene-referred
     * with diffuse white well below 1.0 (the HLG-coherent NDR case: diffuse white ≈ 0.265). The scale is
     * multiplied in before SNCE and divided out immediately after the appearance blend, so the pipeline's
     * OUTPUT SCALE is unchanged and the downstream PQ encode's own `k` normalisation still applies exactly
     * as before. Default `1f` ⇒ Stage A is byte-identical to the legacy path (the no-downgrade contract).
     */
    private val snceWhiteNormalizeScale: Float = 1f,
) {
    /** Per-pixel SNCE reference engine. Default ctor ⇒ Rec.709-linear working space. */
    private val snce: SnceV2Engine =
        if (workingRgbToXyz != null) SnceV2Engine(rgbToXyz = workingRgbToXyz) else SnceV2Engine()

    /** Viewing conditions for CAM16. Supplied per-clip by the converter (from a thumbnail), or a D65 default. */
    private val vc: SnceCam16.SnceViewingConditions =
        viewingConditions ?: defaultViewingConditions(sceneLuminanceLA)

    /** The film-print show-LUT, built ONCE per pipeline (scene-independent, 33³ R-fastest). */
    private val filmLut: SnceAdaptiveLut? =
        if (options.filmPrintStrength > 0f) SnceNrliAnalyticLut.buildFilmPrintLut() else null

    private val acesInput: AcesInputColorSpace = when (options.target) {
        DevelopTarget.SDR_REC709 -> AcesInputColorSpace.REC709
        DevelopTarget.SDR_REC2020, DevelopTarget.NDR_PQ_BT2020 -> AcesInputColorSpace.REC2020
    }
    private val acesOutput: AcesOutputColorSpace = when (options.target) {
        DevelopTarget.SDR_REC709 -> AcesOutputColorSpace.REC709_SDR
        DevelopTarget.SDR_REC2020 -> AcesOutputColorSpace.REC2020_SDR
        DevelopTarget.NDR_PQ_BT2020 -> AcesOutputColorSpace.REC2020_PQ
    }

    /**
     * Develop one working-RGB pixel through the enabled stages, in order A → B → C.
     * Returns a fresh 3-element array. Bit-exact identity when [options] is identity and the input is
     * in-gamut (so the optional output clamp is a no-op).
     */
    fun developPixel(r: Float, g: Float, b: Float): FloatArray {
        var rgb = floatArrayOf(r, g, b)

        // Stage A — SNCE v2 CAM16 colour-appearance (incl. "SNCE saturation"), strength-blended.
        if (options.snceAppearanceStrength > 0f) {
            if (snceWhiteNormalizeScale != 1f) {
                // Normalise diffuse white → ≈1.0 so CAM16 predicts colourfulness at its designed operating
                // point (fixes NDR red over-boost), then divide the scale back out so the OUTPUT scale — and
                // therefore the downstream PQ encode's `k` — is unchanged. Passthrough when strength blends to 0.
                val s = snceWhiteNormalizeScale
                val inv = 1f / s
                val scaled = floatArrayOf(rgb[0] * s, rgb[1] * s, rgb[2] * s)
                val processed = snce.processSampleRgb(scaled, vc, sceneLuminanceLA, options.semanticClass)
                val blended = blend(scaled, processed, options.snceAppearanceStrength)
                rgb = floatArrayOf(blended[0] * inv, blended[1] * inv, blended[2] * inv)
            } else {
                val processed = snce.processSampleRgb(rgb, vc, sceneLuminanceLA, options.semanticClass)
                rgb = blend(rgb, processed, options.snceAppearanceStrength)
            }
        }

        // Stage B — ACES RRT+ODT (scene-referred; caller-gated, off on the display-referred SDR path).
        if (options.acesToneMap) {
            val cg = AcesColorEngine.toAcesCg(AcesRgb(rgb[0], rgb[1], rgb[2]), acesInput)
            val disp = AcesColorEngine.applyFittedRrtOdt(cg, acesOutput)
            rgb = floatArrayOf(disp.r, disp.g, disp.b)
        }

        // Stage C — OmniClarity/NRLI film-print creative look (SDR only), strength-blended.
        if (filmLut != null && options.target != DevelopTarget.NDR_PQ_BT2020) {
            val graded = SnceNeuralEnhancement.tetrahedralSampleLut(filmLut, clamp01(rgb))
            rgb = blend(rgb, graded, options.filmPrintStrength)
        }

        // Camera ISP output can already contain a warm/red skin cast. Correct only skin-like warm
        // midtones, radially toward their own luma; neutrals and saturated red objects remain intact.
        if (options.warmSkinRedTameStrength > 0f && options.target != DevelopTarget.NDR_PQ_BT2020) {
            rgb = tameWarmSkinRed(rgb, options.warmSkinRedTameStrength)
        }

        if (options.clampOutput) {
            rgb[0] = rgb[0].coerceIn(0f, 1f)
            rgb[1] = rgb[1].coerceIn(0f, 1f)
            rgb[2] = rgb[2].coerceIn(0f, 1f)
        }
        return rgb
    }

    /**
     * Develop an interleaved RGB frame (length a multiple of 3) into a fresh array. Pure — safe to call
     * concurrently from different worker threads on different frames.
     */
    fun develop(interleavedRgb: FloatArray): FloatArray {
        require(interleavedRgb.size % 3 == 0) { "interleaved RGB length must be a multiple of 3" }
        val out = FloatArray(interleavedRgb.size)
        var i = 0
        while (i < interleavedRgb.size) {
            val p = developPixel(interleavedRgb[i], interleavedRgb[i + 1], interleavedRgb[i + 2])
            out[i] = p[0]; out[i + 1] = p[1]; out[i + 2] = p[2]
            i += 3
        }
        return out
    }

    private fun blend(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        if (t >= 1f) return b
        if (t <= 0f) return a
        return floatArrayOf(
            a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t
        )
    }

    private fun clamp01(rgb: FloatArray): FloatArray =
        floatArrayOf(rgb[0].coerceIn(0f, 1f), rgb[1].coerceIn(0f, 1f), rgb[2].coerceIn(0f, 1f))

    private fun tameWarmSkinRed(rgb: FloatArray, strength: Float): FloatArray {
        val r = rgb[0].coerceAtLeast(0f)
        val g = rgb[1].coerceAtLeast(0f)
        val b = rgb[2].coerceAtLeast(0f)
        val y = (r * 0.2126f + g * 0.7152f + b * 0.0722f).coerceAtLeast(1e-6f)
        val warm = smoothStep(0.02f, 0.14f, r - g) * smoothStep(0.02f, 0.20f, r - b)
        val midHigh = smoothStep(0.05f, 0.18f, y)
        val skinNotObject = smoothStep(0.10f, 0.26f, minOf(r, g, b))
        val amount = (strength * warm * midHigh * skinNotObject).coerceIn(0f, strength)
        return floatArrayOf(
            y + (r - y) * (1f - amount),
            y + (g - y) * (1f - amount),
            y + (b - y) * (1f - amount)
        )
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun defaultViewingConditions(sceneLa: Float): SnceCam16.SnceViewingConditions =
        SnceCam16.SnceViewingConditions(
            whiteXyz = D65_XYZ.copyOf(),
            adaptingLuminance = sceneLa,
            backgroundLuminance = DEFAULT_BACKGROUND_YB,
            surround = SnceCam16.Surround.AVERAGE
        )

    companion object {
        /** Default scene adapting luminance (cd/m²) when the intermediate carries no measured lux. */
        const val DEFAULT_SCENE_LA: Float = 80f
        const val DEFAULT_BACKGROUND_YB: Float = 20f

        /** CIE 1931 D65 white, normalised to Y = 1. */
        private val D65_XYZ = floatArrayOf(0.95047f, 1.0f, 1.08883f)
    }
}

/** Delivery target of the develop pass; selects the ACES output space + whether the creative LUT applies. */
enum class DevelopTarget {
    /** Flat 10-bit SDR intermediate → graded Rec.709 SDR. */
    SDR_REC709,
    /** SDR develop in the Rec.2020 container. */
    SDR_REC2020,
    /** HLG-coherent HDR intermediate → PQ/ST.2084 BT.2020 (creative SDR LUT suppressed). */
    NDR_PQ_BT2020
}

/**
 * Tunable stage strengths for [VideoDevelopColorPipeline]. Every look-affecting knob is 0-safe: an
 * [isIdentity] configuration guarantees an exact passthrough (the no-downgrade contract). Defaults run the
 * bounded SNCE appearance chain (elite colour, safe) with no creative LUT and no ACES tone-map, so the
 * baseline develop is faithful; the converters raise [filmPrintStrength] / [acesToneMap] per mode.
 */
data class VideoDevelopColorOptions(
    val target: DevelopTarget = DevelopTarget.SDR_REC709,
    val snceAppearanceStrength: Float = 1f,
    val filmPrintStrength: Float = 0f,
    val acesToneMap: Boolean = false,
    val semanticClass: SnceNeuralEnhancement.SemanticClass = SnceNeuralEnhancement.SemanticClass.OTHER,
    val clampOutput: Boolean = true,
    val warmSkinRedTameStrength: Float = 0f,
) {
    init {
        require(snceAppearanceStrength in 0f..1f) { "snceAppearanceStrength must be in [0,1]" }
        require(filmPrintStrength in 0f..1f) { "filmPrintStrength must be in [0,1]" }
        require(warmSkinRedTameStrength in 0f..1f) { "warmSkinRedTameStrength must be in [0,1]" }
    }

    /** True when no look-altering stage is active ⇒ [VideoDevelopColorPipeline.developPixel] is a passthrough. */
    val isIdentity: Boolean
        get() = snceAppearanceStrength == 0f && filmPrintStrength == 0f && !acesToneMap &&
            warmSkinRedTameStrength == 0f
}
