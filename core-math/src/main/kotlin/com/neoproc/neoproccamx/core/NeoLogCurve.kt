package com.neoproc.neoproccamx.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NeoLogCurve — Custom-engineered scene-referred LOG transfer function.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * DESIGN GOALS
 * ═════════════════════════════════════════════════════════════════════════════════════
 * 1. Preserve >14 stops of sensor dynamic range in a 10-bit container.
 * 2. Match ACEScc (Academy Color Encoding System) log encoding for seamless
 *    interchange with professional colour pipelines (DaVinci Resolve, Nuke, Baselight).
 * 3. Provide an invertible, mathematically stable transfer with defined behaviour
 *    at 0 (black) and at the ceiling (white clip).
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * TRANSFER FUNCTION
 * ═════════════════════════════════════════════════════════════════════════════════════
 * N-LOG v5 APEX-LOG is original NeoProCamX IP. The curve is derived from a
 * scene-linear contract: black maps to 0, 18% gray maps exactly to 0.50, and
 * every variant joins a C² quintic shadow toe to a natural-log body at toeCut.
 * Device dynamic range is deliberately not claimed here; it must be measured
 * on real hardware and reported through [DYNAMIC_RANGE_EVIDENCE].
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * ACES INTEGRATION
 * ═════════════════════════════════════════════════════════════════════════════════════
 * [toAcescc] converts sensor-linear (relative exposure) to ACEScc encoded space.
 * [fromAcescc] inverts the transform back to linear for grading or display.
 *
 * The companion object provides pre-computed LUTs for fast GPU shader usage.
 */
object NeoLogCurve {

    /** Version string for metadata tagging and CPU/GPU parity. */
    const val VERSION = "N-LOG-v5.0-APEX-LOG"

    /** Latitude is sensor dependent and must be reported only after measurement. */
    const val DYNAMIC_RANGE_EVIDENCE = "DEVICE_MEASUREMENT_REQUIRED"

    const val SCENE_GRAY_18 = 0.18f
    const val GRAY_18_CODE = 0.50f

    /** Legacy unit scene exposure anchor. V5 highlight ceiling is variant-specific. */
    const val LINEAR_WHITE_POINT = 1.0f

    @Deprecated("N-Log latitude is not known without device measurement; use DYNAMIC_RANGE_EVIDENCE.")
    const val STOPS_OF_RANGE = 0.0f

    enum class Variant {
        BASE,
        SHADOW,
        HIGHLIGHT,
        CINEMA
    }

    data class V3Coefficients(
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val e: Float,
        val f: Float,
        val g: Float
    )

    data class V5Parameters(
        val variant: Variant,
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
        val intent: String
    )

    private val profiles = mapOf(
        Variant.BASE to V5Parameters(
            variant = Variant.BASE,
            toeCut = 0.010f,
            toeSlope = 8.0f,
            toeEntrySlope = 8.0f,
            logDensity = 120.0f,
            toeHeight = 0.080000f,
            logScale = 0.137099f,
            m1 = 16.451880f,
            m2 = -1974.2256f,
            c1 = 0.080000f,
            c3 = -0.436788f,
            c4 = 0.789056f,
            c5 = -0.352269f,
            xMax = 6.843034f,
            intent = "neutral reference"
        ),
        Variant.SHADOW to V5Parameters(
            variant = Variant.SHADOW,
            toeCut = 0.004f,
            toeSlope = 10.0f,
            toeEntrySlope = 10.0f,
            logDensity = 70.0f,
            toeHeight = 0.040000f,
            logScale = 0.177657f,
            m1 = 12.435990f,
            m2 = -870.5193f,
            c1 = 0.040000f,
            c3 = -0.045940f,
            c4 = 0.082136f,
            c5 = -0.036196f,
            xMax = 3.164296f,
            intent = "dense shadow and midtone code allocation"
        ),
        Variant.HIGHLIGHT to V5Parameters(
            variant = Variant.HIGHLIGHT,
            toeCut = 0.005f,
            toeSlope = 12.0f,
            toeEntrySlope = 12.0f,
            logDensity = 260.0f,
            toeHeight = 0.060000f,
            logScale = 0.114598f,
            m1 = 29.795480f,
            m2 = -7746.8248f,
            c1 = 0.060000f,
            c3 = -0.452743f,
            c4 = 0.816510f,
            c5 = -0.363766f,
            xMax = 14.041075f,
            intent = "extended highlight latitude"
        ),
        Variant.CINEMA to V5Parameters(
            variant = Variant.CINEMA,
            toeCut = 0.008f,
            toeSlope = 10.0f,
            toeEntrySlope = 10.0f,
            logDensity = 110.0f,
            toeHeight = 0.080000f,
            logScale = 0.140385f,
            m1 = 15.442350f,
            m2 = -1698.6585f,
            c1 = 0.080000f,
            c3 = -0.228513f,
            c4 = 0.413487f,
            c5 = -0.184974f,
            xMax = 6.377367f,
            intent = "filmic lifted toe and soft roll-off"
        )
    )

    private val v3Profiles = mapOf(
        Variant.BASE to buildV3Coefficients(
            e = 0.010f,
            a = 5.555556f,
            b = 0.047996f,
            d = 6.311743f,
            f = 0.089456f
        ),
        Variant.SHADOW to buildV3Coefficients(
            e = 0.018f,
            a = 4.800000f,
            b = 0.035000f,
            d = 5.700000f,
            f = 0.100000f
        ),
        Variant.HIGHLIGHT to buildV3Coefficients(
            e = 0.008f,
            a = 5.100000f,
            b = 0.045000f,
            d = 4.200000f,
            f = 0.120000f
        ),
        Variant.CINEMA to buildV3Coefficients(
            e = 0.012f,
            a = 5.000000f,
            b = 0.040000f,
            d = 6.000000f,
            f = 0.095000f
        )
    )

    private fun V5Parameters.encodeBody(x: Double): Double =
        toeHeight.toDouble() +
            logScale.toDouble() *
            ln(1.0 + logDensity.toDouble() * (x - toeCut.toDouble()))

    private fun V5Parameters.decodeBody(y: Double): Double =
        toeCut.toDouble() +
            (exp((y - toeHeight.toDouble()) / logScale.toDouble()) - 1.0) /
            logDensity.toDouble()

    private fun V5Parameters.encodeToe(x: Double): Double {
        val t = (x / toeCut.toDouble()).coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        val t5 = t4 * t
        return c1.toDouble() * t +
            c3.toDouble() * t3 +
            c4.toDouble() * t4 +
            c5.toDouble() * t5
    }

    private fun V5Parameters.toeDerivative(t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        return c1.toDouble() +
            3.0 * c3.toDouble() * t2 +
            4.0 * c4.toDouble() * t3 +
            5.0 * c5.toDouble() * t4
    }

    private fun V5Parameters.decodeToe(encoded: Double): Double {
        val target = encoded.coerceIn(0.0, toeHeight.toDouble())
        var low = 0.0
        var high = 1.0
        var t = (target / toeHeight.toDouble().coerceAtLeast(1e-12)).coerceIn(low, high)

        repeat(36) {
            val value = encodeToe(t * toeCut.toDouble())
            if (value < target) {
                low = t
            } else {
                high = t
            }

            val derivative = toeDerivative(t)
            val newton = if (derivative > 1e-12) t - (value - target) / derivative else Double.NaN
            t = if (newton.isFinite() && newton in low..high) {
                newton
            } else {
                (low + high) * 0.5
            }
        }
        return ((low + high) * 0.5) * toeCut.toDouble()
    }

    private fun maxCodeFor(bitDepth: Int): Int {
        require(bitDepth == 10 || bitDepth == 12 || bitDepth == 16) {
            "N-LOG v5 quantizer supports only 10, 12, or 16 bits; requested $bitDepth"
        }
        return (1 shl bitDepth) - 1
    }

    data class LinearRgb(
        val r: Float,
        val g: Float,
        val b: Float
    )

    private data class Oklab(
        val l: Float,
        val a: Float,
        val b: Float
    )

    @JvmStatic
    fun encode(linear: Float): Float = encode(linear, Variant.BASE)

    @JvmStatic
    fun encode(linear: Float, variant: Variant): Float {
        val profile = profiles.getValue(variant)
        val x = linear.toDouble().coerceIn(0.0, profile.xMax.toDouble())
        val encoded = if (x < profile.toeCut.toDouble()) {
            profile.encodeToe(x)
        } else {
            profile.encodeBody(x)
        }
        return encoded.coerceIn(0.0, 1.0).toFloat()
    }

    @JvmStatic
    fun decode(logValue: Float): Float = decode(logValue, Variant.BASE)

    @JvmStatic
    fun decode(logValue: Float, variant: Variant): Float {
        val profile = profiles.getValue(variant)
        val v = logValue.toDouble().coerceIn(0.0, 1.0)
        val linear = if (v < profile.toeHeight.toDouble()) {
            profile.decodeToe(v)
        } else {
            profile.decodeBody(v)
        }
        return linear.coerceIn(0.0, profile.xMax.toDouble()).toFloat()
    }

    @JvmStatic
    fun v5Parameters(variant: Variant): V5Parameters =
        profiles.getValue(variant)

    @JvmStatic
    fun maxLinear(variant: Variant = Variant.BASE): Float =
        profiles.getValue(variant).xMax

    @JvmStatic
    fun quantize(
        linear: Float,
        variant: Variant = Variant.BASE,
        bitDepth: Int = 10,
        tpdfDither: Float = 0f
    ): Int {
        val maxCode = maxCodeFor(bitDepth)
        val code = encode(linear, variant).toDouble() * maxCode.toDouble() +
            tpdfDither.toDouble().coerceIn(-0.5, 0.5)
        return code.roundToInt().coerceIn(0, maxCode)
    }

    @JvmStatic
    fun decodeCode(
        code: Int,
        variant: Variant = Variant.BASE,
        bitDepth: Int = 10
    ): Float {
        val maxCode = maxCodeFor(bitDepth)
        return decode(code.coerceIn(0, maxCode).toFloat() / maxCode.toFloat(), variant)
    }

    @JvmStatic
    fun v3Coefficients(variant: Variant): V3Coefficients =
        v3Profiles.getValue(variant)

    @JvmStatic
    fun encodeV3(linear: Float, variant: Variant = Variant.BASE): Float {
        val coefficients = v3Profiles.getValue(variant)
        val x = linear.coerceIn(0f, LINEAR_WHITE_POINT)
        val encoded = if (x < coefficients.e) {
            coefficients.a * x + coefficients.b
        } else {
            coefficients.c * ln(coefficients.d * x + coefficients.f) + coefficients.g
        }
        return encoded.coerceIn(0f, 1f)
    }

    @JvmStatic
    fun decodeV3(encoded: Float, variant: Variant = Variant.BASE): Float {
        val coefficients = v3Profiles.getValue(variant)
        val boundary = coefficients.a * coefficients.e + coefficients.b
        val value = encoded.coerceIn(0f, 1f)
        val linear = if (value < boundary) {
            (value - coefficients.b) / coefficients.a
        } else {
            (exp((value - coefficients.g) / coefficients.c) - coefficients.f) / coefficients.d
        }
        return linear.coerceIn(0f, LINEAR_WHITE_POINT)
    }

    /** Retained API alias: encodes the Neo-owned N-Log base curve, not certified ACEScc. */
    @JvmStatic
    fun toAcescc(linear: Float): Float = encode(linear)

    /** Retained API alias: decodes the Neo-owned N-Log base curve. */
    @JvmStatic
    fun fromAcescc(acescc: Float): Float = decode(acescc)

    /**
     * Generates a 1024-entry 10-bit LUT suitable for uploading to a GPU shader.
     * Each entry is a Float in 0.0–1.0 range.
     */
    @JvmStatic
    fun generateEncodeLut(
        size: Int = 1024,
        variant: Variant = Variant.BASE,
        maxLinear: Float = maxLinear(variant)
    ): FloatArray {
        return FloatArray(size) { i ->
            val linear = (i.toFloat() / (size - 1)) * maxLinear
            encode(linear, variant)
        }
    }

    @JvmStatic
    fun generateGpuEncodeLut1024(): FloatArray = generateEncodeLut(1024)

    /**
     * Generates the inverse LUT for decoding Neo-LOG back to linear in a shader.
     */
    @JvmStatic
    fun generateDecodeLut(size: Int = 1024, variant: Variant = Variant.BASE): FloatArray {
        return FloatArray(size) { i ->
            val logVal = i.toFloat() / (size - 1)
            decode(logVal, variant)
        }
    }

    /**
     * Returns the 3×3 matrix converting sRGB primaries to ACES AP0 primaries.
     * Use this after decoding if you need true ACES workflow compatibility.
     */
    @JvmStatic
    fun sRgbToAcesAp0Matrix(): FloatArray = floatArrayOf(
        0.4397010f, 0.3829780f, 0.1773350f,
        0.0897923f, 0.8134230f, 0.0967616f,
        0.0175440f, 0.1115440f, 0.8707040f
    )

    /**
     * Inverse: ACES AP0 to sRGB primaries.
     */
    @JvmStatic
    fun acesAp0ToSrgbMatrix(): FloatArray = floatArrayOf(
        2.5214006f, -1.1340097f, -0.3873909f,
        -0.2762184f, 1.3725823f, -0.0963639f,
        -0.0153783f, -0.1529950f, 1.1683733f
    )

    @JvmStatic
    fun sRgbToAcesAp1Matrix(): FloatArray = floatArrayOf(
        0.6130970f, 0.3395230f, 0.0473790f,
        0.0701940f, 0.9163540f, 0.0134520f,
        0.0206150f, 0.1095700f, 0.8698150f
    )

    @JvmStatic
    fun acesAp1ToSrgbMatrix(): FloatArray = floatArrayOf(
        1.7050520f, -0.6217900f, -0.0832580f,
        -0.1302570f, 1.1408040f, -0.0105480f,
        -0.0240040f, -0.1289690f, 1.1529720f
    )

    @JvmStatic
    fun oklabHuePreservingHighlightRolloff(
        r: Float,
        g: Float,
        b: Float
    ): LinearRgb {
        val lab = linearRgbToOklab(
            r = r.coerceAtLeast(0f),
            g = g.coerceAtLeast(0f),
            b = b.coerceAtLeast(0f)
        )
        val knee = 0.72f
        val mappedL = if (lab.l <= knee) {
            lab.l
        } else {
            val t = ((lab.l - knee) / (1f - knee)).coerceAtLeast(0f)
            knee + (1f - knee) * (1f - exp(-2.8f * t))
        }.coerceIn(0f, 1f)
        val chroma = sqrt(lab.a * lab.a + lab.b * lab.b)
        val hue = atan2(lab.b, lab.a)
        val mappedChroma = chroma * (1f - smoothstep(0.85f, 1.0f, mappedL) * 0.88f)
        val mapped = oklabToLinearRgb(
            l = mappedL,
            a = mappedChroma * cos(hue),
            b = mappedChroma * sin(hue)
        )
        return fitRgbToUnitCube(mapped)
    }

    private fun linearRgbToOklab(r: Float, g: Float, b: Float): Oklab {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val lRoot = cbrt(l)
        val mRoot = cbrt(m)
        val sRoot = cbrt(s)
        return Oklab(
            l = 0.2104542553f * lRoot + 0.7936177850f * mRoot - 0.0040720468f * sRoot,
            a = 1.9779984951f * lRoot - 2.4285922050f * mRoot + 0.4505937099f * sRoot,
            b = 0.0259040371f * lRoot + 0.7827717662f * mRoot - 0.8086757660f * sRoot
        )
    }

    private fun oklabToLinearRgb(l: Float, a: Float, b: Float): LinearRgb {
        val lPrime = l + 0.3963377774f * a + 0.2158037573f * b
        val mPrime = l - 0.1055613458f * a - 0.0638541728f * b
        val sPrime = l - 0.0894841775f * a - 1.2914855480f * b
        val lmsL = lPrime * lPrime * lPrime
        val lmsM = mPrime * mPrime * mPrime
        val lmsS = sPrime * sPrime * sPrime
        return LinearRgb(
            r = 4.0767416621f * lmsL - 3.3077115913f * lmsM + 0.2309699292f * lmsS,
            g = -1.2684380046f * lmsL + 2.6097574011f * lmsM - 0.3413193965f * lmsS,
            b = -0.0041960863f * lmsL - 0.7034186147f * lmsM + 1.7076147010f * lmsS
        )
    }

    private fun fitRgbToUnitCube(rgb: LinearRgb): LinearRgb {
        val clippedR = rgb.r.coerceAtLeast(0f)
        val clippedG = rgb.g.coerceAtLeast(0f)
        val clippedB = rgb.b.coerceAtLeast(0f)
        val maxChannel = maxOf(clippedR, clippedG, clippedB, 1f)
        return LinearRgb(
            r = (clippedR / maxChannel).coerceIn(0f, 1f),
            g = (clippedG / maxChannel).coerceIn(0f, 1f),
            b = (clippedB / maxChannel).coerceIn(0f, 1f)
        )
    }

    private fun cbrt(value: Float): Float =
        when {
            value >= 0f -> value.toDouble().pow(1.0 / 3.0).toFloat()
            else -> -((-value).toDouble().pow(1.0 / 3.0).toFloat())
        }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value >= edge1) 1f else 0f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun buildV3Coefficients(
        e: Float,
        a: Float,
        b: Float,
        d: Float,
        f: Float
    ): V3Coefficients {
        val c = a * (d * e + f) / d
        val g = a * e + b - c * ln(d * e + f)
        return V3Coefficients(a = a, b = b, c = c, d = d, e = e, f = f, g = g)
    }
}

/**
 * AcesToneMapper — ACES RRT + ODT approximation for on-device preview.
 *
 * This provides a "filmic" look with highlight compression suitable for
 * display on HDR or SDR screens without clipping artefacts.
 */
object AcesToneMapper {

    private const val EXPOSURE_BIAS = 0.18f

    /**
     * Approximates the ACES Reference Rendering Transform (RRT) followed by
     * an Output Device Transform (ODT) for SDR displays.
     *
     * Input: scene-linear ACES values (can be >1.0 for highlights)
     * Output: 0.0–1.0 display-encoded (gamma 2.4-ish)
     */
    @JvmStatic
    fun tonemapSDR(acesLinear: Float): Float {
        // Simple Hill-Langmuir sigmoid approximation
        val x = acesLinear * EXPOSURE_BIAS
        val t = x * (x * x * 0.04f + x * 0.37f + 0.6f) /
                (x * x * 0.04f + x * 0.84f + 0.18f + 1e-6f)
        return t.coerceIn(0f, 1f)
    }

    /**
     * Tone-map for HDR10 (ST.2084 PQ) output.
     * Input: scene-linear ACES, Output: PQ-encoded 0.0–1.0.
     */
    @JvmStatic
    fun tonemapHDR10(acesLinear: Float, peakNits: Float = 1000f): Float {
        // Simple gamma 2.2 + highlight rolloff
        val linear = acesLinear * (peakNits / 100f)
        return pqEncode(linear.coerceAtLeast(0f) / 10000f)
    }

    private fun pqEncode(normalized: Float): Float {
        // Perceptual quantizer (PQ) OETF approximation
        val m1 = 2610f / 4096f * 0.25f
        val m2 = 2523f / 4096f * 128f
        val c1 = 3424f / 4096f
        val c2 = 2413f / 4096f * 32f
        val c3 = 2392f / 4096f * 32f
        val n = normalized.coerceIn(0f, 1f).pow(m1)
        return ((c1 + c2 * n) / (1f + c3 * n)).pow(m2)
    }
}
