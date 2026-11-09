package com.neoproc.neoproccamx.color

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ColorValidationReport(
    val illuminants: List<String>,
    val meanDeltaE2000: Float,
    val maxDeltaE2000: Float,
    val maxNeutralDeltaUv: Float,
    val maxSkinHueDriftDegrees: Float,
    val maxFrameToFrameDeltaUv: Float,
    val pipelineV1BitMatchesLegacy: Boolean,
    val metrics: List<IlluminantValidationMetric>,
    /**
     * CP-Q4 — max R/G/B spread of a white-balanced neutral sensor signal (1,1,1) after the REAL
     * CP11 sensor→XYZ(D50)→ACEScg transform (mired-interpolated DNG forward matrices), across
     * reference CCTs. ~0 means the actual color-pipeline entry preserves neutrals; this validates
     * the real matrices CP10 debayer will consume, not a synthetic tone model.
     */
    val maxSensorToAcesNeutralSpread: Float
)

data class IlluminantValidationMetric(
    val illuminant: String,
    val deltaE2000: Float,
    val neutralDeltaUv: Float
)

object ColorValidationHarness {
    fun validateNeoGamutApex(): ColorValidationReport {
        val illuminants = listOf(
            "A" to ApexColorConfig.WhitePoint.A,
            "TL84" to ApexColorConfig.WhitePoint.TL84,
            "D50" to ApexColorConfig.WhitePoint.D50,
            "D65" to ApexColorConfig.WhitePoint.D65,
            "D75" to ApexColorConfig.WhitePoint.D75
        )
        val target = ApexColorConfig.WhitePoint.D65.xyz
        val targetLab = xyzToLab(target)
        val metrics = illuminants.map { (name, whitePoint) ->
            val matrix = NeoGamutCat16Math.calculateCat16MatrixCiecam16(
                sourceX = whitePoint.x,
                sourceY = whitePoint.y,
                targetX = ApexColorConfig.WhitePoint.D65.x,
                targetY = ApexColorConfig.WhitePoint.D65.y,
                degreeOfAdaptation = 1f
            )
            val adaptedNeutral = NeoGamutCat16Math.multiplyVector(matrix, whitePoint.xyz)
            IlluminantValidationMetric(
                illuminant = name,
                deltaE2000 = deltaE2000(xyzToLab(adaptedNeutral), targetLab),
                neutralDeltaUv = deltaUv(adaptedNeutral, target)
            )
        }
        val skinHueDrift = abs(
            NeoGamutApexPipeline.protectMemoryHue(
                hueDegrees = 52f,
                proposedHueDegrees = 58.5f,
                memoryClass = NeoGamutMemoryClass.SKIN
            ) - 52f
        )
        val stable1 = NeoGamutApexPipeline.temporalWhiteBalanceEma(
            ApexColorConfig.WhitePoint.D65.xyz,
            ApexColorConfig.WhitePoint.D65.xyz
        )
        val stable2 = NeoGamutApexPipeline.temporalWhiteBalanceEma(
            stable1,
            NeoGamutCat16Math.multiplyVector(
                NeoGamutCat16Math.calculateCat16Matrix(
                    sourceX = NeoGamutCat16Math.kelvinToCieXy(6_450f).first,
                    sourceY = NeoGamutCat16Math.kelvinToCieXy(6_450f).second,
                    targetX = ApexColorConfig.WhitePoint.D65.x,
                    targetY = ApexColorConfig.WhitePoint.D65.y
                ),
                ApexColorConfig.WhitePoint.D65.xyz
            )
        )
        val sample = AcesRgb(1.12f, 0.38f, 0.18f)
        val legacy = NeoWideGamut2.neoToTargetRgb(sample, NeoWideGamut2.TargetGamut.REC2020, compress = true)
        val v1 = NeoGamutApexPipeline.processForOutput(
            sample,
            pipeline = NeoGamutPipeline.V1,
            target = NeoWideGamut2.TargetGamut.REC2020
        )

        return ColorValidationReport(
            illuminants = metrics.map { it.illuminant },
            meanDeltaE2000 = metrics.map { it.deltaE2000 }.average().toFloat(),
            maxDeltaE2000 = metrics.maxOf { it.deltaE2000 },
            maxNeutralDeltaUv = metrics.maxOf { it.neutralDeltaUv },
            maxSkinHueDriftDegrees = skinHueDrift,
            maxFrameToFrameDeltaUv = deltaUv(stable1, stable2),
            pipelineV1BitMatchesLegacy = legacy == v1,
            metrics = metrics,
            maxSensorToAcesNeutralSpread = validateSensorToAcesNeutralSpread()
        )
    }

    /**
     * CP-Q4 — drive the REAL CP11 sensor→XYZ(D50)→ACEScg path with two distinct but
     * neutral-preserving DNG forward matrices (row sums == D50 white) and confirm the
     * mired-interpolated transform still maps a white-balanced neutral sensor signal to an
     * achromatic ACEScg triple across the dual-illuminant CCT range. Returns the worst R/G/B spread.
     */
    private fun validateSensorToAcesNeutralSpread(): Float {
        val d50 = ApexColorConfig.WhitePoint.D50.xyz
        // Both matrices map sensor (1,1,1) -> D50 (each row sums to the D50 component), but differ
        // in their intra-row distribution, so interpolation between them is a non-trivial blend.
        val third = floatArrayOf(d50[0] / 3f, d50[1] / 3f, d50[2] / 3f)
        val d = 0.02f
        val fmA = floatArrayOf(
            third[0], third[0], third[0],
            third[1], third[1], third[1],
            third[2], third[2], third[2]
        )
        val fmB = floatArrayOf(
            third[0] + d, third[0] - d, third[0],
            third[1] - d, third[1] + d, third[1],
            third[2], third[2] + d, third[2] - d
        )
        val cctA = ApexColorConfig.DUAL_ILLUMINANT_CCT_A
        val cctD65 = ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
        val ccts = listOf(cctA, (cctA + cctD65) * 0.5f, cctD65)
        val neutralSensor = floatArrayOf(1f, 1f, 1f)
        // s1-f09 — validate the EXACT matrix the GPU demosaic consumes (the forward-path product
        // xyzD50ToAcesAp1Matrix ∘ buildSensorToXyzD50FromForward), not a parallel harness-only copy.
        return ccts.maxOf { cct ->
            val sensorToAp1 = NeoGamutApexPipeline.buildDemosaicSensorToAcesCg(fmA, fmB, cct, cctA, cctD65)
            val ap1 = NeoGamutCat16Math.multiplyVector(sensorToAp1, neutralSensor)
            maxChannelSpread(ap1)
        }
    }

    /**
     * s2-f12 / CP-Q4 — real-pixel intake seam. Runs the REAL [NeoGamutApexPipeline.processForOutput]
     * (the production color chain) over a set of provided neutral scene samples and returns the worst
     * R/G/B spread — the neutral-axis stability of the production transform on real pixels. Synthetic
     * goldens as code today; the same entry consumes owner-captured decoded golden frames later.
     * Measurement-only by construction: it has no live-pixel-path caller and gains none.
     */
    fun maxNeutralSpreadThroughOutput(
        grays: List<Float>,
        pipeline: NeoGamutPipeline = NeoGamutPipeline.V2_APEX,
        target: NeoWideGamut2.TargetGamut = NeoWideGamut2.TargetGamut.REC2020
    ): Float = grays.maxOf { g ->
        val out = NeoGamutApexPipeline.processForOutput(AcesRgb(g, g, g), pipeline, target)
        maxChannelSpread(floatArrayOf(out.r, out.g, out.b))
    }

    private fun maxChannelSpread(v: FloatArray): Float =
        max(max(abs(v[0] - v[1]), abs(v[1] - v[2])), abs(v[0] - v[2]))

    private fun xyzToLab(xyz: FloatArray): FloatArray {
        val white = ApexColorConfig.WhitePoint.D65.xyz
        val x = labPivot(xyz[0] / white[0])
        val y = labPivot(xyz[1] / white[1])
        val z = labPivot(xyz[2] / white[2])
        return floatArrayOf(
            116f * y - 16f,
            500f * (x - y),
            200f * (y - z)
        )
    }

    private fun labPivot(value: Float): Float =
        if (value > 216f / 24_389f) {
            value.toDouble().pow(1.0 / 3.0).toFloat()
        } else {
            (24_389f / 27f * value + 16f) / 116f
        }

    private fun deltaUv(a: FloatArray, b: FloatArray): Float {
        val auv = xyzToUv(a)
        val buv = xyzToUv(b)
        val du = auv.first - buv.first
        val dv = auv.second - buv.second
        return sqrt(du * du + dv * dv)
    }

    private fun xyzToUv(xyz: FloatArray): Pair<Float, Float> {
        val denom = (xyz[0] + 15f * xyz[1] + 3f * xyz[2]).coerceAtLeast(ApexColorConfig.EPSILON)
        return (4f * xyz[0] / denom) to (9f * xyz[1] / denom)
    }

    private fun deltaE2000(lab1: FloatArray, lab2: FloatArray): Float {
        val l1 = lab1[0].toDouble()
        val a1 = lab1[1].toDouble()
        val b1 = lab1[2].toDouble()
        val l2 = lab2[0].toDouble()
        val a2 = lab2[1].toDouble()
        val b2 = lab2[2].toDouble()
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1 + c2) * 0.5
        val cBar7 = cBar.pow(7.0)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + 25.0.pow(7.0))))
        val ap1 = (1.0 + g) * a1
        val ap2 = (1.0 + g) * a2
        val cp1 = sqrt(ap1 * ap1 + b1 * b1)
        val cp2 = sqrt(ap2 * ap2 + b2 * b2)
        val hp1 = hueRadians(b1, ap1)
        val hp2 = hueRadians(b2, ap2)
        val deltaLp = l2 - l1
        val deltaCp = cp2 - cp1
        val dhp = when {
            cp1 * cp2 == 0.0 -> 0.0
            abs(hp2 - hp1) <= PI -> hp2 - hp1
            hp2 <= hp1 -> hp2 - hp1 + 2.0 * PI
            else -> hp2 - hp1 - 2.0 * PI
        }
        val deltaHp = 2.0 * sqrt(cp1 * cp2) * sin(dhp * 0.5)
        val lpBar = (l1 + l2) * 0.5
        val cpBar = (cp1 + cp2) * 0.5
        val hpBar = when {
            cp1 * cp2 == 0.0 -> hp1 + hp2
            abs(hp1 - hp2) <= PI -> (hp1 + hp2) * 0.5
            hp1 + hp2 < 2.0 * PI -> (hp1 + hp2 + 2.0 * PI) * 0.5
            else -> (hp1 + hp2 - 2.0 * PI) * 0.5
        }
        val t = 1.0 -
            0.17 * cos(hpBar - Math.toRadians(30.0)) +
            0.24 * cos(2.0 * hpBar) +
            0.32 * cos(3.0 * hpBar + Math.toRadians(6.0)) -
            0.20 * cos(4.0 * hpBar - Math.toRadians(63.0))
        val deltaTheta = Math.toRadians(30.0) *
            exp(-((Math.toDegrees(hpBar) - 275.0) / 25.0).pow(2.0))
        val rc = 2.0 * sqrt(cpBar.pow(7.0) / (cpBar.pow(7.0) + 25.0.pow(7.0)))
        val sl = 1.0 + (0.015 * (lpBar - 50.0).pow(2.0)) /
            sqrt(20.0 + (lpBar - 50.0).pow(2.0))
        val sc = 1.0 + 0.045 * cpBar
        val sh = 1.0 + 0.015 * cpBar * t
        val rt = -sin(2.0 * deltaTheta) * rc
        val lTerm = deltaLp / sl
        val cTerm = deltaCp / sc
        val hTerm = deltaHp / sh
        return sqrt(lTerm * lTerm + cTerm * cTerm + hTerm * hTerm + rt * cTerm * hTerm).toFloat()
    }

    private fun hueRadians(b: Double, a: Double): Double {
        val hue = atan2(b, a)
        return if (hue >= 0.0) hue else hue + 2.0 * PI
    }
}
