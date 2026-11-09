package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineColorParityDiagnosticsTest {
    private fun patches(offset: Float = 0f): List<FloatArray> = listOf(
        floatArrayOf(50f + offset, 0f, 0f),
        floatArrayOf(62f + offset, 18f, 24f),
        floatArrayOf(35f + offset, -12f, -28f)
    )

    private fun request(
        record: List<FloatArray> = patches(),
        export: List<FloatArray> = patches(),
        exportTimestampNs: Long = 1_002_000_000L
    ) = OfflineColorParityRequest(
        recordingId = "clip-001",
        outputDirectory = File("build/test-parity"),
        preview = ColorParityPatchSet("preview-real", 10L, 1_000_000_000L, patches()),
        record = ColorParityPatchSet("record-decoder", 10L, 1_001_000_000L, record),
        export = ColorParityPatchSet("export-decoder", 10L, exportTimestampNs, export),
        maximumTimestampSkewNs = 5_000_000L
    )

    @Test
    fun alignedRealPatchSetsProduceMeasuredParity() {
        val report = OfflineColorParityDiagnostics.analyze(request())

        assertTrue(report.measured)
        assertTrue(report.measurement.withinTolerance)
        assertTrue(report.toJson().contains("\"measured\":true"))
    }

    @Test
    fun staleOrMalformedInputIsHonestlyNotProven() {
        val stale = OfflineColorParityDiagnostics.analyze(
            request(exportTimestampNs = 2_000_000_000L)
        )
        val malformed = OfflineColorParityDiagnostics.analyze(
            request(record = listOf(floatArrayOf(Float.NaN, 0f, 0f)))
        )

        assertFalse(stale.measured)
        assertTrue(stale.reason.contains("TIMESTAMP"))
        assertFalse(malformed.measured)
        assertTrue(malformed.reason.contains("INVALID"))
        assertTrue(stale.toJson().contains("\"worstMax\":null"))
    }
}
