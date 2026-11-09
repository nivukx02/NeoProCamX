package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics

/**
 * s6-f06 — measures preview=record=export pixel parity as the worst pairwise ColorChecker ΔE2000
 * (the real metric, not a Boolean), using the shipped [ImageQualityMetrics.colorCheckerDeltaE2000].
 * Pure / offline; consumed only when [TriSurfaceParityFlag] is enabled and a real tri-surface frame
 * producer is available. Honest NOT_PROVEN ([notProven]) when tri-surface pixels are absent — never
 * a synthesized PASS.
 */
internal data class TriSurfaceParityMeasurement(
    val previewVsRecordMean: Double,
    val previewVsRecordMax: Double,
    val previewVsExportMean: Double,
    val previewVsExportMax: Double,
    val recordVsExportMean: Double,
    val recordVsExportMax: Double,
    val worstMean: Double,
    val worstMax: Double,
    val withinTolerance: Boolean,
    val measured: Boolean
)

internal object TriSurfaceParityPolicy {

    /** Mirrors the live gate's ColorChecker ΔE2000 ceilings (BeyondImaxEliteProfile tier). */
    const val MAX_TRI_SURFACE_DELTAE2000_MEAN: Double = 1.3
    const val MAX_TRI_SURFACE_DELTAE2000_MAX: Double = 3.0

    fun notProven(): TriSurfaceParityMeasurement =
        TriSurfaceParityMeasurement(
            previewVsRecordMean = Double.NaN,
            previewVsRecordMax = Double.NaN,
            previewVsExportMean = Double.NaN,
            previewVsExportMax = Double.NaN,
            recordVsExportMean = Double.NaN,
            recordVsExportMax = Double.NaN,
            worstMean = Double.NaN,
            worstMax = Double.NaN,
            withinTolerance = false,
            measured = false
        )

    /**
     * Worst pairwise ΔE2000 across the three surfaces. Returns [notProven] when any surface's Lab
     * list is empty or the three differ in length (guards the metric's require()-based contract).
     */
    fun measure(
        previewLab: List<FloatArray>,
        recordLab: List<FloatArray>,
        exportLab: List<FloatArray>,
        toleranceMean: Double = MAX_TRI_SURFACE_DELTAE2000_MEAN,
        toleranceMax: Double = MAX_TRI_SURFACE_DELTAE2000_MAX
    ): TriSurfaceParityMeasurement {
        if (previewLab.isEmpty() ||
            recordLab.size != previewLab.size ||
            exportLab.size != previewLab.size
        ) {
            return notProven()
        }
        val (prMean, prMax) = ImageQualityMetrics.colorCheckerDeltaE2000(previewLab, recordLab)
        val (peMean, peMax) = ImageQualityMetrics.colorCheckerDeltaE2000(previewLab, exportLab)
        val (reMean, reMax) = ImageQualityMetrics.colorCheckerDeltaE2000(recordLab, exportLab)
        val worstMean = maxOf(prMean, peMean, reMean)
        val worstMax = maxOf(prMax, peMax, reMax)
        return TriSurfaceParityMeasurement(
            previewVsRecordMean = prMean,
            previewVsRecordMax = prMax,
            previewVsExportMean = peMean,
            previewVsExportMax = peMax,
            recordVsExportMean = reMean,
            recordVsExportMax = reMax,
            worstMean = worstMean,
            worstMax = worstMax,
            withinTolerance = worstMean <= toleranceMean && worstMax <= toleranceMax,
            measured = true
        )
    }
}
