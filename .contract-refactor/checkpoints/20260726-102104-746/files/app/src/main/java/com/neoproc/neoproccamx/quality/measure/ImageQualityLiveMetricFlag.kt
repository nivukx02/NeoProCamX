package com.neoproc.neoproccamx.quality.measure

/**
 * s8-f05 / CP-Q7 — process-global gate for the windowed temporal-flicker-sigma live telemetry.
 *
 * DEFAULT OFF. While [flickerEnabled] is false, SceneAnalyzer never touches its frame-mean ring and
 * always publishes SceneMetrics.measuredTemporalFlickerSigma = Float.NaN — bit-for-bit identical to
 * today (the field's own default). When flipped on (device-gated, after profiling the analysis
 * stride), SceneAnalyzer accumulates per-frame mean luma into a small ring and reports the
 * population std over the recent window via [ImageQualityMetrics.temporalFlickerSigmaFromMeans].
 * The metric is inert telemetry: it is not consumed by any quality gate or profile computation.
 */
internal object ImageQualityLiveMetricFlag {
    @Volatile
    var flickerEnabled: Boolean = false
        private set

    fun set(value: Boolean) {
        flickerEnabled = value
    }
}
