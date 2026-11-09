package com.neoproc.neoproccamx.apex

import kotlin.math.log2

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TASK 10 — NIVUKX APEX QUALITY MEASUREMENT
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Computes runtime quality metrics (SNR, highlight clip rate, detail metrics)
 * for the NPV12 metadata sidecar writer.
 */
object ApexQualityMeasurement {

    /**
     * Compute quality frame metrics based on scene analysis and performance.
     */
    fun computeQualityMetrics(
        metrics: SceneMetrics,
        frameDropCount: Int,
        processingTimeMs: Float
    ): Npv12SidecarWriter.QualityFrameMetrics {
        // CP-Q2: prefer the REAL measured luma SNR (flattest-tile noise estimate from the frame's
        // Y plane) over the ISO heuristic. Fall back to the ISO model only when no measurement is
        // available (NaN) or it is non-finite. The measured value is clamped to the same reporting
        // band as the heuristic so downstream sidecar consumers see a consistent range.
        val measured = metrics.measuredLumaSnrDb
        val snrDb = if (measured.isFinite() && measured > 0f) {
            measured.coerceIn(10f, 60f)
        } else {
            // Estimate SNR from ISO and shadow ratio (simplified model)
            // Base SNR at ISO 100 is ~42dB. Drops ~3dB per ISO doubling.
            val baseSnr = 42f
            val isoFactor = log2((metrics.iso.coerceAtLeast(100) / 100f).toDouble()).toFloat() * 3f
            val shadowPenalty = metrics.shadowRatio * 4f
            (baseSnr - isoFactor - shadowPenalty).coerceIn(10f, 45f)
        }

        return Npv12SidecarWriter.QualityFrameMetrics(
            snrDb = snrDb,
            highlightClipRate = metrics.highlightRatio * 0.15f, // heuristic estimation
            frameDropCount = frameDropCount,
            processingTimeMs = processingTimeMs
        )
    }

    /**
     * Compute detail metrics for sidecar.
     */
    fun computeDetailMetrics(
        metrics: SceneMetrics,
        params: ApexProfileParams
    ): Npv12SidecarWriter.DetailMetrics {
        val opticalSharpScore = (1f - metrics.motionScore) * (1f - metrics.noiseRisk)
        return Npv12SidecarWriter.DetailMetrics(
            opticalSharpScore = opticalSharpScore.coerceIn(0f, 1f),
            d1Mean = params.detailGain1,
            d2Mean = params.detailGain2,
            d3Mean = params.detailGain3,
            haloSuppressionApplied = params.detailGain1 > 0f,
            noiseMaskActive = metrics.noiseRisk > 0.3f
        )
    }
}
