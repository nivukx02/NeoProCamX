package com.neoproc.neoproccamx.ui

import android.os.PowerManager
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

    @Test
    fun `resolver cache distinguishes strict recording protection from standard policy`() {
        val resolver = ThermalRuntimePolicyResolver()
        val strict = resolver.resolve(
            thermalLabel = "SEVERE",
            recordingActive = true,
            strictHighEnd = true
        )
        val standard = resolver.resolve(
            thermalLabel = "SEVERE",
            recordingActive = true,
            strictHighEnd = false
        )

        assertFalse(strict.shouldThrottleBitrate)
        assertTrue(standard.shouldThrottleBitrate)
    }

    @Test
    fun `emergency reaches the final preview and analysis safety envelope`() {
        val decision = ThermalRuntimePolicy.resolve(
            thermalLabel = "EMERGENCY",
            recordingActive = true,
            strictHighEnd = true
        )

        assertEquals(5, decision.analysisFpsCap)
        assertEquals(854, decision.previewMaxLongEdge)
        assertTrue(decision.isEmergency)
        assertFalse(decision.shouldThrottleBitrate)
        assertTrue(decision.shouldStopStrictHighEndRecording)
    }

    @Test
    fun `strict recording stops only at emergency without lowering quality`() {
        val critical = ThermalRuntimePolicy.resolve("CRITICAL", recordingActive = true, strictHighEnd = true)
        val idleEmergency = ThermalRuntimePolicy.resolve("EMERGENCY", recordingActive = false, strictHighEnd = true)
        val standardEmergency = ThermalRuntimePolicy.resolve("EMERGENCY", recordingActive = true, strictHighEnd = false)

        assertFalse(critical.shouldStopStrictHighEndRecording)
        assertFalse(idleEmergency.shouldStopStrictHighEndRecording)
        assertFalse(standardEmergency.shouldStopStrictHighEndRecording)
        assertFalse(critical.shouldThrottleBitrate)
    }

    @Test
    fun `resolver invalidates cached decision when only platform status changes`() {
        val resolver = ThermalRuntimePolicyResolver()
        val nominal = resolver.resolveWithPlatformThermalStatus(
            thermalLabel = "",
            recordingActive = true,
            platformThermalStatus = PowerManager.THERMAL_STATUS_NONE,
            strictHighEnd = true
        )
        val emergency = resolver.resolveWithPlatformThermalStatus(
            thermalLabel = "",
            recordingActive = true,
            platformThermalStatus = PowerManager.THERMAL_STATUS_EMERGENCY,
            strictHighEnd = true
        )

        assertEquals(0, nominal.thermalSeverity)
        assertEquals(5, emergency.thermalSeverity)
        assertTrue(emergency.shouldStopStrictHighEndRecording)
    }

    @Test
    fun `merge conservatively combines every independent protection`() {
        val higherSeverity = ThermalRuntimePolicy.ThermalDecision(
            analysisFpsCap = 20,
            previewMaxLongEdge = 1920,
            shouldApplyPreviewGuard = false,
            shouldThrottleBitrate = false,
            shouldReduceAiLoad = false,
            shouldThrottleRaw = false,
            shouldStopStrictHighEndRecording = false,
            allowLut = true,
            allowHighEndCodec = true,
            thermalLabel = "Critical",
            thermalSeverity = 4
        )
        val independentProtections = ThermalRuntimePolicy.ThermalDecision(
            analysisFpsCap = 10,
            previewMaxLongEdge = 1280,
            shouldApplyPreviewGuard = true,
            shouldThrottleBitrate = true,
            shouldReduceAiLoad = true,
            shouldThrottleRaw = true,
            shouldStopStrictHighEndRecording = true,
            allowLut = false,
            allowHighEndCodec = false,
            thermalLabel = "Elevated",
            thermalSeverity = 2
        )

        val merged = ThermalRuntimePolicy.merge(higherSeverity, independentProtections)

        assertEquals(4, merged.thermalSeverity)
        assertEquals("Critical", merged.thermalLabel)
        assertEquals(10, merged.analysisFpsCap)
        assertEquals(1280, merged.previewMaxLongEdge)
        assertTrue(merged.shouldApplyPreviewGuard)
        assertTrue(merged.shouldThrottleBitrate)
        assertTrue(merged.shouldReduceAiLoad)
        assertTrue(merged.shouldThrottleRaw)
        assertTrue(merged.shouldStopStrictHighEndRecording)
        assertFalse(merged.allowLut)
        assertFalse(merged.allowHighEndCodec)
    }
}
