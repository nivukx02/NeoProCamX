package com.neoproc.neoproccamx.apex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-Q2 — ApexQualityMeasurement must report the REAL measured luma SNR when the scene analyzer
 * supplied one, and only fall back to the ISO-derived heuristic when no measurement is available.
 */
class ApexQualityMeasurementTest {

    @Test
    fun usesMeasuredSnrWhenPresent() {
        // A measured SNR that is deliberately inconsistent with the ISO heuristic (high ISO would
        // imply a LOW heuristic SNR) must win, proving the measured value drives the output.
        val metrics = SceneMetrics(
            iso = 6400,
            shadowRatio = 0.5f,
            measuredLumaSnrDb = 38.5f
        )
        val q = ApexQualityMeasurement.computeQualityMetrics(metrics, frameDropCount = 0, processingTimeMs = 5f)
        assertEquals(38.5f, q.snrDb, 1e-3f)
    }

    @Test
    fun fallsBackToIsoHeuristicWhenUnmeasured() {
        // NaN measurement → ISO heuristic: base 42 - 3*log2(6400/100) - 4*shadowRatio.
        val metrics = SceneMetrics(
            iso = 6400,
            shadowRatio = 0.0f,
            measuredLumaSnrDb = Float.NaN
        )
        val q = ApexQualityMeasurement.computeQualityMetrics(metrics, frameDropCount = 0, processingTimeMs = 5f)
        // 42 - 3*6 = 24 dB (log2(64)=6), no shadow penalty.
        assertEquals(24f, q.snrDb, 0.5f)
    }

    @Test
    fun measuredSnrIsClampedToReportingBand() {
        val metrics = SceneMetrics(iso = 100, measuredLumaSnrDb = 999f)
        val q = ApexQualityMeasurement.computeQualityMetrics(metrics, frameDropCount = 0, processingTimeMs = 1f)
        assertTrue("measured SNR must be clamped to the reporting ceiling, got ${q.snrDb}", q.snrDb <= 60f)
    }

    @Test
    fun nonPositiveMeasurementFallsBackToHeuristic() {
        val metrics = SceneMetrics(iso = 100, shadowRatio = 0f, measuredLumaSnrDb = 0f)
        val q = ApexQualityMeasurement.computeQualityMetrics(metrics, frameDropCount = 0, processingTimeMs = 1f)
        // ISO 100 → log2(1)=0 → base 42, clamped to 45 ceiling of heuristic band.
        assertEquals(42f, q.snrDb, 0.5f)
    }
}
