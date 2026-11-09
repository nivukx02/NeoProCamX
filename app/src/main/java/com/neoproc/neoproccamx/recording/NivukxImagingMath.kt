package com.neoproc.neoproccamx.recording

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

internal object NivukxImagingMath {
    private const val PQ_M1 = 2610.0 / 16384.0
    private const val PQ_M2 = 2523.0 / 32.0
    private const val PQ_C1 = 3424.0 / 4096.0
    private const val PQ_C2 = 2413.0 / 128.0
    private const val PQ_C3 = 2392.0 / 128.0
    private const val PQ_PEAK_NITS = 10_000.0

    data class ToneAnchors(
        val p01: Double,
        val p10: Double,
        val p50: Double,
        val p90: Double,
        val p99: Double,
        val p999: Double,
        val p9999: Double,
        val toe: Double,
        val knee: Double,
        val shoulder: Double,
        val gammaToe: Double,
        val kappa: Double,
        val peakNits: Double
    )

    data class JzAzBz(
        val jz: Double,
        val az: Double,
        val bz: Double
    )

    fun resolveToneAnchors(
        luminanceNits: List<Double>,
        peakNits: Double
    ): ToneAnchors {
        val clean = luminanceNits
            .filter { it.isFinite() && it >= 0.0 }
            .ifEmpty { listOf(0.0001, 0.18, 100.0, peakNits.coerceAtLeast(1.0)) }
            .sorted()
        val cappedPeak = peakNits.coerceIn(1.0, PQ_PEAK_NITS)
        val p01 = percentile(clean, 0.01).coerceIn(0.0, cappedPeak)
        val p10 = percentile(clean, 0.10).coerceIn(0.0, cappedPeak)
        val p50 = percentile(clean, 0.50).coerceIn(0.0, cappedPeak)
        val p90 = percentile(clean, 0.90).coerceIn(0.0, cappedPeak)
        val p99 = percentile(clean, 0.99).coerceIn(0.0, cappedPeak)
        val p999 = percentile(clean, 0.999).coerceIn(0.0, cappedPeak)
        val p9999 = percentile(clean, 0.9999).coerceIn(0.0, cappedPeak)
        val toeUpper = (p50 - 0.1).coerceAtLeast(0.0001)
        val toe = (0.7 * p01 + 0.3 * p10).coerceIn(0.0001, toeUpper)
        // Knee ceiling tracks the actual master peak (was a hard 4000 cap that
        // crushed the entire bright range of a 10000-nit master and emitted a
        // too-low nivukxDrDefaultKneeNits in the sidecar).
        val kneeCeiling = (cappedPeak * 0.85).coerceAtLeast(p50 + 1.0)
        val knee = (0.65 * p90 + 0.35 * p99)
            .coerceIn((p50 + 1.0).coerceAtMost(kneeCeiling), kneeCeiling)
        val shoulder = (0.5 * p999 + 0.5 * p9999)
            .coerceIn((knee + 1.0).coerceAtMost(cappedPeak), cappedPeak)
        // Sub-unity (concave) toe gamma LIFTS shadows; the old [1.05,1.20] range
        // was convex and CRUSHED them (the opposite of the documented lift). More
        // available shadow detail (higher p10/p50) => stronger lift (lower gamma).
        val gammaToe = (0.93 - (p10 / max(p50, 0.0001)).coerceIn(0.0, 1.0) * 0.10)
            .coerceIn(0.83, 0.93)
        val highlightSpan = (p99 - p90).coerceAtLeast(1.0)
        val kappa = (1.0 + (highlightSpan / cappedPeak).coerceIn(0.0, 1.0) * 1.5)
            .coerceIn(1.0, 2.5)

        return ToneAnchors(
            p01 = p01,
            p10 = p10,
            p50 = p50,
            p90 = p90,
            p99 = p99,
            p999 = p999,
            p9999 = p9999,
            toe = toe,
            knee = knee,
            shoulder = shoulder,
            gammaToe = gammaToe,
            kappa = kappa,
            peakNits = cappedPeak
        )
    }

    fun toneMapNits(luminanceNits: Double, anchors: ToneAnchors): Double {
        val l = luminanceNits.coerceIn(0.0, anchors.peakNits)
        if (l <= anchors.toe) {
            val normalized = (l / anchors.toe.coerceAtLeast(0.0001)).coerceIn(0.0, 1.0)
            // Concave toe lift (gammaToe < 1 => output >= identity, never crush).
            val lifted = anchors.toe * normalized.pow(anchors.gammaToe)
            // C1 join: fade the lift back to unit slope as l -> toe, so there is no
            // slope break into the linear midsection.
            val joinBlend = smoothstep(0.55, 1.0, normalized)
            return lifted * (1.0 - joinBlend) + l * joinBlend
        }
        if (l <= anchors.knee) return l

        val shoulderSpan = (anchors.shoulder - anchors.knee).coerceAtLeast(1.0)
        val compressionWidth = shoulderSpan * anchors.kappa.coerceAtLeast(1.0)
        return anchors.knee + shoulderSpan * tanh((l - anchors.knee) / compressionWidth)
    }

    fun pqEncodeFromNits(luminanceNits: Double): Double {
        val normalized = (luminanceNits / PQ_PEAK_NITS).coerceIn(0.0, 1.0)
        val y = normalized.pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y)).pow(PQ_M2)
    }

    fun pqDecodeToNits(code: Double): Double {
        val n = code.coerceIn(0.0, 1.0).pow(1.0 / PQ_M2)
        val numerator = max(n - PQ_C1, 0.0)
        val denominator = max(PQ_C2 - PQ_C3 * n, 1e-12)
        return (numerator / denominator).pow(1.0 / PQ_M1) * PQ_PEAK_NITS
    }

    fun chroma(color: JzAzBz): Double =
        kotlin.math.hypot(color.az, color.bz)

    fun hueRadians(color: JzAzBz): Double =
        atan2(color.bz, color.az)

    fun compressChromaRadially(
        color: JzAzBz,
        chromaBoundary: Double,
        softKneeOnset: Double
    ): JzAzBz {
        val boundary = chromaBoundary.coerceAtLeast(1e-9)
        val originalChroma = chroma(color)
        if (originalChroma <= 1e-12) return color
        val knee = (boundary * softKneeOnset.coerceIn(0.0, 1.0)).coerceAtMost(boundary)
        val mappedChroma = if (originalChroma <= knee) {
            originalChroma
        } else {
            val over = originalChroma - knee
            val span = (boundary - knee).coerceAtLeast(1e-9)
            knee + span * tanh(over / span)
        }.coerceAtMost(boundary)
        val scale = mappedChroma / originalChroma
        return color.copy(
            az = color.az * scale,
            bz = color.bz * scale
        )
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        if (edge0 == edge1) return if (x >= edge1) 1.0 else 0.0
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted.first()
        val rank = q.coerceIn(0.0, 1.0) * (sorted.lastIndex).toDouble()
        val lower = rank.toInt().coerceIn(0, sorted.lastIndex)
        val upper = min(lower + 1, sorted.lastIndex)
        val t = rank - lower.toDouble()
        return sorted[lower] * (1.0 - t) + sorted[upper] * t
    }

    private fun Double.isFinite(): Boolean =
        !isNaN() && !isInfinite()
}
