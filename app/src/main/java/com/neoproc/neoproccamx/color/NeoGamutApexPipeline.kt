package com.neoproc.neoproccamx.color

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class DualIlluminantSensorMatrices(
    val colorMatrix1: FloatArray,
    val colorMatrix2: FloatArray,
    val cct1Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_A,
    val cct2Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
) {
    init {
        require(colorMatrix1.size == 9) { "ColorMatrix1 must be 3x3" }
        require(colorMatrix2.size == 9) { "ColorMatrix2 must be 3x3" }
    }
}

data class ApexSceneMetadata(
    val cctKelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_D65,
    val lux: Float = 400f,
    val metaConfidence: Float = 1f,
    val surroundF: Float = ApexColorConfig.SURROUND_AVERAGE
)

data class FusedWhiteBalance(
    val white: FloatArray,
    val mlWeight: Float,
    val sensorWeight: Float,
    val grayEdgeWeight: Float
)

object NeoGamutApexPipeline {
    fun miredInterpolationWeight(
        cctKelvin: Float,
        cct1Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_A,
        cct2Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
    ): Float {
        if (!cctKelvin.isFinite() || cctKelvin <= ApexColorConfig.EPSILON) return 0f
        val mired = 1_000_000f / cctKelvin
        val mired1 = 1_000_000f / cct1Kelvin
        val mired2 = 1_000_000f / cct2Kelvin
        return ((mired - mired2) / (mired1 - mired2).coerceAtLeast(ApexColorConfig.EPSILON))
            .coerceIn(0f, 1f)
    }

    fun interpolateSensorToXyz(
        colorMatrix1: FloatArray,
        colorMatrix2: FloatArray,
        cctKelvin: Float,
        cct1Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_A,
        cct2Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
    ): FloatArray {
        require(colorMatrix1.size == 9 && colorMatrix2.size == 9) {
            "Dual-illuminant color matrices must be 3x3"
        }
        val t = miredInterpolationWeight(cctKelvin, cct1Kelvin, cct2Kelvin)
        return FloatArray(9) { index ->
            colorMatrix2[index] * (1f - t) + colorMatrix1[index] * t
        }
    }

    fun readDualIlluminantSensorMatrices(
        characteristics: CameraCharacteristics?
    ): DualIlluminantSensorMatrices? {
        if (characteristics == null) return null
        val matrix1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            ?.toFloatArray3x3()
            ?: return null
        val matrix2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
            ?.toFloatArray3x3()
            ?: matrix1.copyOf()
        val cct1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
            ?.let(::referenceIlluminantToKelvin)
            ?: ApexColorConfig.DUAL_ILLUMINANT_CCT_A
        val cct2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
            ?.toInt()
            ?.let(::referenceIlluminantToKelvin)
            ?: ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
        return DualIlluminantSensorMatrices(matrix1, matrix2, cct1, cct2)
    }

    fun fuseWhiteBalance(
        mlWhite: FloatArray,
        sensorWhite: FloatArray,
        grayEdgeWhite: FloatArray,
        mlConfidence: Float
    ): FusedWhiteBalance {
        val ml = (mlConfidence.coerceIn(0f, 1f) * ApexColorConfig.ML_WB_WEIGHT_SCALE)
        val sensor = ApexColorConfig.SENSOR_WB_WEIGHT
        val gray = ApexColorConfig.GRAY_EDGE_WB_WEIGHT
        val sum = (ml + sensor + gray).coerceAtLeast(ApexColorConfig.EPSILON)
        val mlWeight = ml / sum
        val sensorWeight = sensor / sum
        val grayWeight = gray / sum
        val white = FloatArray(3) { index ->
            safeComponent(mlWhite, index) * mlWeight +
                safeComponent(sensorWhite, index) * sensorWeight +
                safeComponent(grayEdgeWhite, index) * grayWeight
        }
        return FusedWhiteBalance(
            white = normalizeWhite(white),
            mlWeight = mlWeight,
            sensorWeight = sensorWeight,
            grayEdgeWeight = grayWeight
        )
    }

    fun temporalWhiteBalanceEma(
        previousWhite: FloatArray,
        currentWhite: FloatArray,
        alpha: Float = ApexColorConfig.ML_WB_TEMPORAL_EMA_ALPHA
    ): FloatArray {
        val t = alpha.coerceIn(0f, 1f)
        return normalizeWhite(
            FloatArray(3) { index ->
                safeComponent(previousWhite, index) * (1f - t) +
                    safeComponent(currentWhite, index) * t
            }
        )
    }

    fun protectMemoryHue(
        hueDegrees: Float,
        proposedHueDegrees: Float,
        memoryClass: NeoGamutMemoryClass
    ): Float {
        if (memoryClass == NeoGamutMemoryClass.NONE) return wrapHueDegrees(proposedHueDegrees)
        val hue = wrapHueDegrees(hueDegrees)
        val proposed = wrapHueDegrees(proposedHueDegrees)
        val range = when (memoryClass) {
            NeoGamutMemoryClass.SKIN -> 47f to 55f
            NeoGamutMemoryClass.SKY -> 255f to 270f
            NeoGamutMemoryClass.FOLIAGE -> 130f to 150f
            NeoGamutMemoryClass.NONE -> 0f to 360f
        }
        if (!hue.isInHueRange(range)) return proposed
        val delta = shortestHueDelta(proposed, hue)
            .coerceIn(-ApexColorConfig.MEMORY_HUE_MAX_DRIFT_DEGREES, ApexColorConfig.MEMORY_HUE_MAX_DRIFT_DEGREES)
        return wrapHueDegrees(hue + delta)
    }

    fun processForOutput(
        rgb: AcesRgb,
        pipeline: NeoGamutPipeline,
        target: NeoWideGamut2.TargetGamut,
        sceneMetadata: ApexSceneMetadata = ApexSceneMetadata()
    ): AcesRgb {
        if (pipeline == NeoGamutPipeline.V1) {
            return NeoWideGamut2.neoToTargetRgb(rgb, target, compress = true)
        }
        val ap1 = AcesColorEngine.neoWideGamutToAcesAp1(rgb)
        val compressed = compressAp1Gamut(ap1)
        val toned = toneMapChromaPreserving(compressed, sceneMetadata)
        val neo = AcesColorEngine.acesAp1ToNeoWideGamut(toned)
        return NeoWideGamut2.neoToTargetRgb(neo, target, compress = true)
    }

    fun buildSensorToAcesAp1Matrix(
        matrices: DualIlluminantSensorMatrices,
        metadata: ApexSceneMetadata
    ): FloatArray {
        val sensorToXyz = interpolateSensorToXyz(
            colorMatrix1 = matrices.colorMatrix1,
            colorMatrix2 = matrices.colorMatrix2,
            cctKelvin = metadata.cctKelvin,
            cct1Kelvin = matrices.cct1Kelvin,
            cct2Kelvin = matrices.cct2Kelvin
        )
        val adapted = NeoGamutCat16Math.calculateCat16MatrixCiecam16(
            sourceX = NeoGamutCat16Math.kelvinToCieXy(metadata.cctKelvin).first,
            sourceY = NeoGamutCat16Math.kelvinToCieXy(metadata.cctKelvin).second,
            targetX = ApexColorConfig.WhitePoint.D65.x,
            targetY = ApexColorConfig.WhitePoint.D65.y,
            lux = metadata.lux,
            surroundF = metadata.surroundF,
            metaConfidence = metadata.metaConfidence
        )
        val xyzD65 = NeoGamutCat16Math.multiply3x3(adapted, sensorToXyz)
        val d65ToD55 = NeoGamutCat16Math.calculateCat16Matrix(
            ApexColorConfig.WhitePoint.D65.x,
            ApexColorConfig.WhitePoint.D65.y,
            NeoGamutCat16Math.D55_X,
            NeoGamutCat16Math.D55_Y
        )
        val sensorToNeo = NeoGamutCat16Math.multiply3x3(NeoGamutCat16Math.XYZ_TO_NEO, NeoGamutCat16Math.multiply3x3(d65ToD55, xyzD65))
        val neoBasis = arrayOf(
            AcesColorEngine.neoWideGamutToAcesAp1(AcesRgb(sensorToNeo[0], sensorToNeo[3], sensorToNeo[6])),
            AcesColorEngine.neoWideGamutToAcesAp1(AcesRgb(sensorToNeo[1], sensorToNeo[4], sensorToNeo[7])),
            AcesColorEngine.neoWideGamutToAcesAp1(AcesRgb(sensorToNeo[2], sensorToNeo[5], sensorToNeo[8]))
        )
        return floatArrayOf(
            neoBasis[0].r, neoBasis[1].r, neoBasis[2].r,
            neoBasis[0].g, neoBasis[1].g, neoBasis[2].g,
            neoBasis[0].b, neoBasis[1].b, neoBasis[2].b
        )
    }

    // ── CP11: correct sensor→XYZ→ACEScg from DNG SENSOR_FORWARD_MATRIX ────────────────────────
    //
    // DNG/Camera2 conventions (critical, easy to get backwards):
    //   • SENSOR_COLOR_TRANSFORM (DNG ColorMatrix)  : XYZ → reference-sensor RGB  (the INVERSE of
    //     what a debayer needs). [readDualIlluminantSensorMatrices]/[interpolateSensorToXyz] feed
    //     this matrix where a sensor→XYZ matrix is expected; that path is currently DORMANT (no
    //     caller) — do NOT wire it into the debayer as-is or RAW gets a color cast.
    //   • SENSOR_FORWARD_MATRIX (DNG ForwardMatrix) : white-balanced reference-sensor RGB → XYZ(D50),
    //     with the camera neutral mapped to the D50 white point. This IS the correct sensor→XYZ for a
    //     demosaic, PROVIDED per-channel white-balance gains are applied to the RAW first.

    /**
     * Sensor→XYZ(D50) matrix from the two SENSOR_FORWARD_MATRIX values, mired-interpolated by scene
     * [cctKelvin]. Callers must white-balance the RAW (per-channel gains, camera-neutral→D50) BEFORE
     * applying this matrix. Returns a row-major 3x3.
     */
    fun buildSensorToXyzD50FromForward(
        forwardMatrix1: FloatArray,
        forwardMatrix2: FloatArray,
        cctKelvin: Float,
        cct1Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_A,
        cct2Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
    ): FloatArray {
        require(forwardMatrix1.size == 9 && forwardMatrix2.size == 9) {
            "Forward matrices must be 3x3"
        }
        val t = miredInterpolationWeight(cctKelvin, cct1Kelvin, cct2Kelvin)
        return FloatArray(9) { i -> forwardMatrix2[i] * (1f - t) + forwardMatrix1[i] * t }
    }

    /**
     * Constant XYZ(D50) → ACEScg(AP1) matrix: CAT16-adapt D50→D55, into the project Neo Wide Gamut
     * working space, then to ACES AP1. Pair with [buildSensorToXyzD50FromForward] so a demosaic can
     * map white-balanced RAW → XYZ(D50) → ACEScg. Row-major 3x3.
     */
    fun xyzD50ToAcesAp1Matrix(): FloatArray {
        val d50ToD55 = NeoGamutCat16Math.calculateCat16Matrix(
            ApexColorConfig.WhitePoint.D50.x,
            ApexColorConfig.WhitePoint.D50.y,
            NeoGamutCat16Math.D55_X,
            NeoGamutCat16Math.D55_Y
        )
        val xyzToNeo = NeoGamutCat16Math.multiply3x3(NeoGamutCat16Math.XYZ_TO_NEO, d50ToD55)
        val neoBasis = arrayOf(
            AcesColorEngine.neoWideGamutToAcesAp1(AcesRgb(xyzToNeo[0], xyzToNeo[3], xyzToNeo[6])),
            AcesColorEngine.neoWideGamutToAcesAp1(AcesRgb(xyzToNeo[1], xyzToNeo[4], xyzToNeo[7])),
            AcesColorEngine.neoWideGamutToAcesAp1(AcesRgb(xyzToNeo[2], xyzToNeo[5], xyzToNeo[8]))
        )
        return floatArrayOf(
            neoBasis[0].r, neoBasis[1].r, neoBasis[2].r,
            neoBasis[0].g, neoBasis[1].g, neoBasis[2].g,
            neoBasis[0].b, neoBasis[1].b, neoBasis[2].b
        )
    }

    // ── s1-f09: the matrix the GPU demosaic consumes ─────────────────────────────────────────────
    //
    // The demosaic (demosaic_vulkan.comp / s1-f08) computes acescg = M_XYZ_TO_ACESCG · (M_SENSOR_TO_XYZ
    // · sensorRgb). Its M_SENSOR_TO_XYZ leg MUST be the FORWARD path [buildSensorToXyzD50FromForward]
    // (white-balanced reference-sensor RGB → XYZ(D50)) and its M_XYZ_TO_ACESCG leg the constant
    // [xyzD50ToAcesAp1Matrix]. Never the SENSOR_COLOR_TRANSFORM / [buildSensorToAcesAp1Matrix] path —
    // that is the DNG ColorMatrix (XYZ → sensor, the INVERSE) and would ship an inverted colour cast
    // (see the direction-correctness block above, line ~206).

    /**
     * The full forward sensor→ACEScg(AP1) matrix the demosaic UBO's two legs reconstruct:
     * [xyzD50ToAcesAp1Matrix] ∘ [buildSensorToXyzD50FromForward]. Row-major 3×3. This is the single
     * source of truth for "what colour the demosaic produces" — the CP-Q4 neutral-spread gate and the
     * s1-f09 lock-out tests assert against THIS function, so the demosaic can never silently diverge
     * to a parallel (or inverted) transform.
     */
    fun buildDemosaicSensorToAcesCg(
        forwardMatrix1: FloatArray,
        forwardMatrix2: FloatArray,
        cctKelvin: Float,
        cct1Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_A,
        cct2Kelvin: Float = ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
    ): FloatArray = NeoGamutCat16Math.multiply3x3(
        xyzD50ToAcesAp1Matrix(),
        buildSensorToXyzD50FromForward(forwardMatrix1, forwardMatrix2, cctKelvin, cct1Kelvin, cct2Kelvin)
    )

    /**
     * The sensor→XYZ(D50) matrix to ship in the demosaic descriptor's M_SENSOR_TO_XYZ leg: the
     * mired-interpolated [buildSensorToXyzD50FromForward] when the camera advertises real public
     * SENSOR_FORWARD_MATRIX values ([available] && finite), else [fallback] (the identity-safe default
     * that keeps the combined transform neutral — no-downgrade for sensors lacking a forward matrix).
     * Pure; the live wiring (RawVideoManager) calls this so the descriptor never carries the inverted
     * ColorMatrix path.
     */
    fun selectDemosaicSensorToXyz(
        available: Boolean,
        forwardMatrix1: FloatArray,
        forwardMatrix2: FloatArray,
        cctKelvin: Float,
        cct1Kelvin: Float,
        cct2Kelvin: Float,
        fallback: FloatArray
    ): FloatArray {
        if (!available) return fallback
        if (forwardMatrix1.size != 9 || forwardMatrix2.size != 9) return fallback
        if (!forwardMatrix1.all { it.isFinite() } || !forwardMatrix2.all { it.isFinite() }) return fallback
        if (!cctKelvin.isFinite() || cctKelvin <= ApexColorConfig.EPSILON) return fallback
        return buildSensorToXyzD50FromForward(forwardMatrix1, forwardMatrix2, cctKelvin, cct1Kelvin, cct2Kelvin)
    }

    private fun compressAp1Gamut(ap1: AcesRgb): AcesRgb {
        if (isInsideSoftUnitCube(ap1)) return ap1
        val lab = NeoWideGamut2.oklabFromLinearRgb(ap1)
        val hue = atan2(lab.b, lab.a)
        val chroma = sqrt(lab.a * lab.a + lab.b * lab.b)
        if (chroma <= ApexColorConfig.EPSILON) return ap1
        val boundary = estimateHueBoundary(lab.l, hue)
        val knee = boundary * ApexColorConfig.GAMUT_COMPRESSION_KNEE
        val mappedChroma = if (chroma <= knee) {
            chroma
        } else {
            knee + (boundary - knee) *
                kotlin.math.tanh((chroma - knee) / (boundary - knee).coerceAtLeast(ApexColorConfig.EPSILON))
        }
        return NeoWideGamut2.linearRgbFromOklab(
            NeoWideGamut2.Oklab(
                l = lab.l,
                a = cos(hue) * mappedChroma,
                b = sin(hue) * mappedChroma
            )
        )
    }

    private fun toneMapChromaPreserving(ap1: AcesRgb, metadata: ApexSceneMetadata): AcesRgb {
        val lab = NeoWideGamut2.oklabFromLinearRgb(AcesRgb(max(ap1.r, 0f), max(ap1.g, 0f), max(ap1.b, 0f)))
        val hue = atan2(lab.b, lab.a)
        val chroma = sqrt(lab.a * lab.a + lab.b * lab.b)
        val l = lab.l.coerceAtLeast(0f)
        val toe = l * (1f + ApexColorConfig.TONE_MAP_TOE) / (l + ApexColorConfig.TONE_MAP_TOE)
        val shoulder = ln(1f + l * ApexColorConfig.TONE_MAP_WHITE_POINT) /
            ln(1f + ApexColorConfig.TONE_MAP_WHITE_POINT)
        val shoulderT = smoothstep(ApexColorConfig.TONE_MAP_SHOULDER, 1f, l)
        val mappedL = lerp(toe, shoulder, shoulderT).coerceIn(0f, 1.25f)
        val highDesat = smoothstep(ApexColorConfig.TONE_MAP_SHOULDER, 1.12f, l) *
            ApexColorConfig.HIGHLIGHT_DESATURATION
        val surroundLift = when {
            metadata.surroundF <= ApexColorConfig.SURROUND_DARK -> 0.96f
            metadata.surroundF < ApexColorConfig.SURROUND_AVERAGE -> 0.98f
            else -> 1f
        }
        return NeoWideGamut2.linearRgbFromOklab(
            NeoWideGamut2.Oklab(
                l = mappedL,
                a = cos(hue) * chroma * (1f - highDesat) * surroundLift,
                b = sin(hue) * chroma * (1f - highDesat) * surroundLift
            )
        )
    }

    private fun estimateHueBoundary(lightness: Float, hue: Float): Float {
        val lightnessGuard = 0.36f + 0.18f * (1f - abs(lightness.coerceIn(0f, 1f) - 0.5f) * 2f)
        val hueShape = 0.04f * cos(3f * hue) + 0.018f * cos(6f * hue)
        return (lightnessGuard + hueShape).coerceIn(0.08f, 0.62f)
    }

    private fun ColorSpaceTransform.toFloatArray3x3(): FloatArray =
        FloatArray(9) { index ->
            val rational = getElement(index / 3, index % 3)
            rational.numerator.toFloat() / rational.denominator.toFloat().coerceAtLeast(1f)
        }

    private fun referenceIlluminantToKelvin(value: Int): Float =
        when (value) {
            1 -> 5_000f
            9 -> 2_856f
            17 -> 2_856f
            20 -> 5_500f
            21 -> ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
            22 -> 7_500f
            else -> ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
        }

    private fun normalizeWhite(value: FloatArray): FloatArray {
        val y = safeComponent(value, 1).takeIf { abs(it) >= ApexColorConfig.EPSILON } ?: 1f
        return floatArrayOf(
            safeComponent(value, 0) / y,
            1f,
            safeComponent(value, 2) / y
        )
    }

    private fun safeComponent(value: FloatArray, index: Int): Float =
        value.getOrNull(index)
            ?.takeIf { it.isFinite() }
            ?: if (index == 1) 1f else 0f

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(ApexColorConfig.EPSILON))
            .coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun isInsideSoftUnitCube(rgb: AcesRgb): Boolean =
        rgb.r.isFinite() && rgb.g.isFinite() && rgb.b.isFinite() &&
            rgb.r >= 0f && rgb.r <= 1f &&
            rgb.g >= 0f && rgb.g <= 1f &&
            rgb.b >= 0f && rgb.b <= 1f

    private fun wrapHueDegrees(hue: Float): Float {
        var wrapped = hue % 360f
        if (wrapped < 0f) wrapped += 360f
        return wrapped
    }

    private fun Float.isInHueRange(range: Pair<Float, Float>): Boolean =
        this >= min(range.first, range.second) && this <= max(range.first, range.second)

    private fun shortestHueDelta(from: Float, to: Float): Float {
        var delta = from - to
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }
}
