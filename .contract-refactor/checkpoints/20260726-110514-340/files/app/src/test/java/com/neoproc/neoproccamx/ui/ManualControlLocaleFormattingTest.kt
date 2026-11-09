package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ManualControlLocaleFormattingTest {

    @Test
    fun manualControlExposureDisplayKeepsDotDecimalUnderCommaLocale() = withDefaultLocale(Locale.GERMANY) {
        assertEquals("1.5\"", ManualControlUtils.formatExposureNs(1_500_000_000L))
    }

    @Test
    fun stateSyncExposureDisplayKeepsDotDecimalUnderCommaLocale() = withDefaultLocale(Locale.GERMANY) {
        assertEquals("1.5s", CameraManualControlStateSyncPolicy.formatExposureNs(1_500_000_000L))
    }

    @Test
    fun evDisplayKeepsDotDecimalUnderCommaLocale() = withDefaultLocale(Locale.GERMANY) {
        assertEquals("+1.3 EV", ManualControlUtils.formatEv(1.25f))
    }

    @Test
    fun recordingTelemetryDiagnosticsKeepDotDecimalUnderCommaLocale() = withDefaultLocale(Locale.GERMANY) {
        val diagnostic = RecordingSessionTelemetry(
            durationMs = 1_500L,
            fileSizeBytes = 1_572_864L
        ).diagnosticSummary

        assertTrue(diagnostic, diagnostic.contains("duration=1.5s"))
        assertTrue(diagnostic, diagnostic.contains("size=1.5MB"))
        assertFalse(diagnostic, diagnostic.contains("1,5"))
    }

    @Test
    fun recordingResolutionDiagnosticsKeepDotDecimalUnderCommaLocale() = withDefaultLocale(Locale.GERMANY) {
        val plan = RecordingSourceResolutionPlan(
            fullSensorMegapixels = 12.25f,
            cineSourceMegapixels = 8.25f
        )

        assertTrue(plan.shortSummary, plan.shortSummary.contains("(8.3MP)"))
        assertTrue(plan.diagnosticSummary, plan.diagnosticSummary.contains("(12.3MP)"))
        assertFalse(plan.diagnosticSummary, plan.diagnosticSummary.contains(","))
    }

    private inline fun withDefaultLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
