package com.neoproc.neoproccamx.color

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

internal object NivukxVisionMath {
    private const val EPSILON = 1e-12

    data class Ap1Rgb(val r: Double, val g: Double, val b: Double)
    data class Xyz(val x: Double, val y: Double, val z: Double)
    data class JzAzBz(val jz: Double, val az: Double, val bz: Double)
    data class SceneParameters(val omega: Double, val gammaCompress: Double)
    data class ChromaBoundaryLut(
        val jzSamples: Int,
        val hueSamples: Int,
        val values: DoubleArray
    ) {
        fun valueAt(jzIndex: Int, hueIndex: Int): Double =
            values[jzIndex.coerceIn(0, jzSamples - 1) * hueSamples + hueIndex.coerceIn(0, hueSamples - 1)]
    }

    fun ap1ToXyz(rgb: Ap1Rgb): Xyz =
        Xyz(
            x = 0.6624541811 * rgb.r + 0.1340042065 * rgb.g + 0.1561876870 * rgb.b,
            y = 0.2722287168 * rgb.r + 0.6740817658 * rgb.g + 0.0536895174 * rgb.b,
            z = -0.0055746495 * rgb.r + 0.0040607335 * rgb.g + 1.0103391003 * rgb.b
        )

    fun xyzToAp1(xyz: Xyz): Ap1Rgb =
        Ap1Rgb(
            r = 1.6410233797 * xyz.x - 0.3248032942 * xyz.y - 0.2364246952 * xyz.z,
            g = -0.6636628587 * xyz.x + 1.6153315917 * xyz.y + 0.0167563477 * xyz.z,
            b = 0.0117218943 * xyz.x - 0.0082844420 * xyz.y + 0.9883948585 * xyz.z
        )

    fun xyzToJzAzBz(xyz: Xyz): JzAzBz {
        val xp = 1.15 * xyz.x - 0.15 * xyz.z
        val yp = 0.66 * xyz.y + 0.34 * xyz.x
        val l = 0.41478972 * xp + 0.579999 * yp + 0.0146480 * xyz.z
        val m = -0.2015100 * xp + 1.120649 * yp + 0.0531008 * xyz.z
        val s = -0.0166008 * xp + 0.264800 * yp + 0.6684799 * xyz.z
        val lp = jzNonlinear(l)
        val mp = jzNonlinear(m)
        val sp = jzNonlinear(s)
        val iz = 0.5 * (lp + mp)
        val jz = ((1.0 - 0.56) * iz) / (1.0 - 0.56 * iz) - 1.6295499532821566e-11
        return JzAzBz(
            jz = jz.coerceAtLeast(0.0),
            az = 3.524000 * lp - 4.066708 * mp + 0.542708 * sp,
            bz = 0.199076 * lp + 1.096799 * mp - 1.295875 * sp
        )
    }

    fun jzAzBzToXyz(color: JzAzBz): Xyz {
        val iz = (color.jz + 1.6295499532821566e-11) /
            (1.0 - 0.56 + 0.56 * (color.jz + 1.6295499532821566e-11))
        val lp = iz + 0.138605043271539 * color.az + 0.0580473161561189 * color.bz
        val mp = iz - 0.138605043271539 * color.az - 0.0580473161561189 * color.bz
        val sp = iz - 0.096019242026319 * color.az - 0.811891896056039 * color.bz
        val l = jzInverseNonlinear(lp)
        val m = jzInverseNonlinear(mp)
        val s = jzInverseNonlinear(sp)
        val xp = 1.9242264357876067 * l - 1.0047923125953657 * m + 0.037651404030618 * s
        val yp = 0.3503167620949991 * l + 0.7264811939316552 * m - 0.065384422948085 * s
        val z = -0.0909828109828475 * l - 0.3127282905230739 * m + 1.5227665613052603 * s
        val x = (xp + 0.15 * z) / 1.15
        val y = (yp - 0.34 * x) / 0.66
        return Xyz(x, y, z)
    }

    fun ap1ToJzAzBz(rgb: Ap1Rgb): JzAzBz =
        xyzToJzAzBz(ap1ToXyz(rgb))

    fun jzAzBzToAp1(color: JzAzBz): Ap1Rgb =
        xyzToAp1(jzAzBzToXyz(color))

    fun chroma(color: JzAzBz): Double =
        hypot(color.az, color.bz)

    fun hueRadians(color: JzAzBz): Double =
        atan2(color.bz, color.az)

    fun resolveSceneParameters(
        p50: Double,
        p99: Double,
        lPeak: Double = 10_000.0
    ): SceneParameters {
        val peak = lPeak.coerceAtLeast(1.0)
        return SceneParameters(
            omega = (0.5 + 0.4 * p99.coerceAtLeast(0.0) / peak).coerceIn(0.5, 1.2),
            gammaCompress = (0.25 + 0.6 * ((p99 - p50).coerceAtLeast(0.0) / peak)).coerceIn(0.25, 0.85)
        )
    }

    fun rhoForJz(jz: Double, p50: Double, p99: Double): Double =
        0.85 + 0.10 * smoothstep(p50, p99, jz)

    fun compressRadialChroma(
        color: JzAzBz,
        cMax: Double,
        p50: Double,
        p99: Double,
        lPeak: Double
    ): JzAzBz {
        val originalChroma = chroma(color)
        if (originalChroma <= EPSILON) return color
        val normalizedJz = color.jz * lPeak.coerceAtLeast(1.0)
        val boundary = cMax.coerceAtLeast(EPSILON)
        val rho = rhoForJz(normalizedJz, p50, p99).coerceIn(0.0, 0.999999)
        val knee = rho * boundary
        val mappedChroma = if (originalChroma <= knee) {
            originalChroma
        } else {
            val span = ((1.0 - rho) * boundary).coerceAtLeast(EPSILON)
            knee + span * tanh((originalChroma - knee) / span)
        }.coerceAtMost(boundary)
        val scale = mappedChroma / originalChroma
        return color.copy(az = color.az * scale, bz = color.bz * scale)
    }

    fun nearWhiteChromaScale(rgb: Ap1Rgb): Double {
        val m = minOf(1.0 - rgb.r, 1.0 - rgb.g, 1.0 - rgb.b)
        val s = smoothstep(0.02, 0.10, m)
        return 0.15 + 0.85 * s
    }

    fun applyNearWhiteGuard(color: JzAzBz, rgb: Ap1Rgb): JzAzBz {
        val scale = nearWhiteChromaScale(rgb)
        return color.copy(az = color.az * scale, bz = color.bz * scale)
    }

    fun defaultChromaBoundaryLut(): ChromaBoundaryLut {
        val jzSamples = 33
        val hueSamples = 33
        val values = DoubleArray(jzSamples * hueSamples) { index ->
            val j = index / hueSamples
            val h = index % hueSamples
            val jz = j.toDouble() / (jzSamples - 1).toDouble()
            val hue = h.toDouble() / (hueSamples - 1).toDouble()
            (0.20 + 0.14 * (1.0 - jz).pow(0.65) + 0.035 * sin(hue * Math.PI * 2.0).coerceAtLeast(0.0))
                .coerceIn(0.16, 0.42)
        }
        return ChromaBoundaryLut(jzSamples, hueSamples, values)
    }

    fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        if (edge0 == edge1) return if (x < edge0) 0.0 else 1.0
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun jzNonlinear(value: Double): Double {
        val v = max(value, 0.0) / 10_000.0
        val n = (3424.0 / 4096.0 + (2413.0 / 128.0) * v.pow(2610.0 / 16384.0)) /
            (1.0 + (2392.0 / 128.0) * v.pow(2610.0 / 16384.0))
        return n.pow(1.7 * 2523.0 / 32.0)
    }

    private fun jzInverseNonlinear(value: Double): Double {
        val n = value.coerceIn(0.0, 1.0).pow(1.0 / (1.7 * 2523.0 / 32.0))
        val numerator = max(n - 3424.0 / 4096.0, 0.0)
        val denominator = max(2413.0 / 128.0 - (2392.0 / 128.0) * n, EPSILON)
        return (numerator / denominator).pow(16384.0 / 2610.0) * 10_000.0
    }

    internal fun Double.format6(): String =
        String.format(Locale.US, "%.6f", this)

    internal object NLogs12 {
        const val A = 250.0
        const val B = 0.0078125
        val C: Double = log10(B)
        val D: Double = log10(A + B) - C
        const val L_PEAK = 10_000.0

        fun encode(luminanceNits: Double): Double {
            val normalized = (luminanceNits / L_PEAK).coerceIn(0.0, 1.0)
            return ((log10(A * normalized + B) - C) / D).coerceIn(0.0, 1.0)
        }

        fun decode(code: Double): Double {
            val x = code.coerceIn(0.0, 1.0)
            val normalized = ((10.0.pow(x * D + C) - B) / A).coerceIn(0.0, 1.0)
            return normalized * L_PEAK
        }
    }
}
