package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalRuntimePolicyTest {

    @Test
    fun `bitrate mitigation feedback surfaces warning without critical escalation`() {
        val feedback = ThermalRuntimePolicy.resolveBitrateMitigationFeedback(bitrateReduced = true)

        assertEquals("Thermal Throttling: Bitrate Reduced", feedback.warningMessage)
        assertFalse(feedback.shouldRaiseCriticalError)
    }

    @Test
    fun `bitrate mitigation feedback stays quiet when nothing changed`() {
        val feedback = ThermalRuntimePolicy.resolveBitrateMitigationFeedback(bitrateReduced = false)

        assertNull(feedback.warningMessage)
        assertFalse(feedback.shouldRaiseCriticalError)
    }

    @Test
    fun `strict high end still protects hdr pipeline under severe thermal load`() {
        val decision = ThermalRuntimePolicy.resolve(
            thermalLabel = "Severe",
            recordingActive = true,
            strictHighEnd = true
        )

        assertTrue(decision.shouldApplyPreviewGuard)
        assertFalse(decision.shouldThrottleBitrate)
        assertTrue(decision.shouldReduceAiLoad)
        assertTrue(decision.allowLut)
        assertTrue(decision.allowHighEndCodec)
        assertTrue(decision.thermalSeverity >= 3)
        assertTrue(decision.analysisFpsCap <= 20)
        assertTrue(decision.previewMaxLongEdge <= 2560)
    }

    @Test
    fun `standard thermal policy can still throttle bitrate outside strict high end`() {
        val decision = ThermalRuntimePolicy.resolve(
            thermalLabel = "Severe",
            recordingActive = true,
            strictHighEnd = false
        )

        assertTrue(decision.shouldThrottleBitrate)
        assertFalse(decision.allowLut)
    }
}
