package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualControlLabelFormatterTest {

    @Test
    fun focusLabel_formatsInfinityAndFiniteDistance() {
        assertEquals("INF", ManualControlLabelFormatter.focusLabel(0f))
        assertEquals("2.00m", ManualControlLabelFormatter.focusLabel(0.5f))
    }

    @Test
    fun evLabel_formatsSignedStops() {
        assertEquals("+2 EV", ManualControlLabelFormatter.evLabel(2))
        assertEquals("-3 EV", ManualControlLabelFormatter.evLabel(-3))
    }

    @Test
    fun evIndexLabel_formatsCamera2StepAsStops() {
        assertEquals("+0.7 EV", ManualControlLabelFormatter.evIndexLabel(2, 1f / 3f))
        assertEquals("-0.5 EV", ManualControlLabelFormatter.evIndexLabel(-3, 1f / 6f))
        assertEquals("0 EV", ManualControlLabelFormatter.evIndexLabel(0, 1f / 6f))
    }

    @Test
    fun metricLabels_followEnabledStatePriority() {
        assertEquals(
            "Wave",
            ManualControlLabelFormatter.metricHistogramLabel(
                showHistogram = false,
                showWaveform = true,
                showZebra = true
            )
        )
        assertEquals(
            "Eye AF",
            ManualControlLabelFormatter.metricFocusLabel(
                peakingEnabled = false,
                faceDetectionEnabled = false,
                eyeAutofocusEnabled = true
            )
        )
    }

    @Test
    fun shutterLabel_formatsFractionAndSeconds() {
        assertEquals("1/60", ManualControlLabelFormatter.shutterLabel(16_666_666L))
        assertEquals("1.50s", ManualControlLabelFormatter.shutterLabel(1_500_000_000L))
    }
}
