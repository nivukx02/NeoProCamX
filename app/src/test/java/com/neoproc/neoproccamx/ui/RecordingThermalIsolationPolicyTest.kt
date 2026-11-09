package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingThermalIsolationPolicyTest {
    @Test
    fun `moderate heat proactively isolates only secondary recording work`() {
        val decision = RecordingThermalIsolationPolicy.resolve(
            recordingActive = true,
            thermalSeverity = 2
        )

        assertTrue(decision.active)
        assertTrue(decision.suspendAiInference)
        assertTrue(decision.suspendSceneAnalysis)
        assertTrue(decision.suspendCameraAnalysisTarget)
        assertTrue(decision.suppressProcessMemoryTelemetry)
    }

    @Test
    fun `critical heat keeps isolation active`() {
        val decision = RecordingThermalIsolationPolicy.resolve(
            recordingActive = true,
            thermalSeverity = 4
        )

        assertTrue(decision.active)
    }

    @Test
    fun `idle preview and cool recording retain all secondary features`() {
        val idleHot = RecordingThermalIsolationPolicy.resolve(
            recordingActive = false,
            thermalSeverity = 4
        )
        val recordingCool = RecordingThermalIsolationPolicy.resolve(
            recordingActive = true,
            thermalSeverity = 1
        )

        listOf(idleHot, recordingCool).forEach { decision ->
            assertFalse(decision.active)
            assertFalse(decision.suspendAiInference)
            assertFalse(decision.suspendSceneAnalysis)
            assertFalse(decision.suspendCameraAnalysisTarget)
            assertFalse(decision.suppressProcessMemoryTelemetry)
        }
    }
}
