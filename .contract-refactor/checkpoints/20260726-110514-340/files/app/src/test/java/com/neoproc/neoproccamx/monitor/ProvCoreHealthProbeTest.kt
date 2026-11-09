package com.neoproc.neoproccamx.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvCoreHealthProbeTest {

    private fun eval(s: RuntimeSignalSnapshot) = ProvCoreHealthProbe.evaluate(s)

    @Test
    fun healthyRecording_isActive() {
        // Frames positively flowing + all signals healthy = ACTIVE (pipeline delivering this instant).
        val r = eval(healthySnapshot())
        assertEquals(HealthState.ACTIVE, r.state)
        assertFalse(r.hardFault)
    }

    @Test
    fun capabilityReport_surfacesExactReasons_withoutFaulting() {
        // Issue 3: the real-hardware capability report is DESCRIPTIVE — it appears in diagnostics with
        // its exact limitation reasons, and it never downgrades the (healthy) pipeline state.
        val r = eval(
            healthySnapshot(
                capabilityBadge = "Pro Cinema 83/100",
                capabilityScore = 83,
                capabilityLimitations = listOf("RAW_SENSOR 4000x3000 is below active sensor 8000x6000")
            )
        )
        assertEquals(HealthState.ACTIVE, r.state)
        val m = r.diagnostics.metrics.toMap()
        assertEquals("Pro Cinema 83/100", m["HAL profile"])
        assertEquals("83/100", m["Capability score"])
        assertEquals("RAW_SENSOR 4000x3000 is below active sensor 8000x6000", m["Limitation 1"])
    }

    @Test
    fun hardwareAdvisory_showsSpecificReasonNotGeneric() {
        // Issue 3: a genuine advisory shows its EXACT classified reason, never a bare "Hardware advisory".
        val r = eval(healthySnapshot(hardwareWarning = "Thermal Throttling: Bitrate Reduced"))
        assertEquals(HealthState.WARNING, r.state)
        assertEquals("Thermal throttling", r.reason)
    }

    @Test
    fun cameraClosed_isInactive() {
        val r = eval(healthySnapshot(cameraOpen = false))
        assertEquals(HealthState.INACTIVE, r.state)
    }

    @Test
    fun cameraError_isImmediateError() {
        val r = eval(healthySnapshot(cameraErrorMessage = "session died"))
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.hardFault)
        assertEquals("session died", r.diagnostics.runtimeException)
    }

    @Test
    fun encoderError_isImmediateError() {
        val r = eval(healthySnapshot(gpuRecordErrorMessage = "codec configure failed"))
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.hardFault)
    }

    @Test
    fun strictHighEndAdmissionRefusal_isWarningNotEncoderFault() {
        val r = eval(
            healthySnapshot(
                recording = false,
                gpuRecordErrorMessage = "Strict high-end recording refused: thermal=Severe cannot guarantee 4096x3072@25fps without quality downgrade"
            )
        )
        assertEquals(HealthState.WARNING, r.state)
        assertEquals("Strict quality admission refused", r.reason)
        assertEquals("Recording admission control", r.diagnostics.pipelineStage)
        assertFalse(r.hardFault)
        assertFalse(r.recoverable)
    }

    @Test
    fun hardwareAdvisory_isWarningNotFabricatedFault() {
        // A surfaced advisory (thermal throttle, background/battery restriction, memory pressure) is
        // informational, NOT a pipeline hardware fault. It must surface as a visible WARNING — never a
        // hard "Hardware fault surfaced" ERROR that spins a doomed import-health recovery while the
        // EGL/GPU pipeline is actually healthy. The advisory text is still carried for the user.
        val r = eval(healthySnapshot(hardwareWarning = "Battery optimization active"))
        assertEquals(HealthState.WARNING, r.state)
        assertFalse(r.hardFault)
        assertFalse(r.recoverable)
        assertEquals("Battery optimization active", r.diagnostics.runtimeException)
    }

    @Test
    fun bufferQueueImportFailures_isRecoverableError() {
        val r = eval(healthySnapshot(consecutiveFailedImports = 10, vulkanPipelineHealthy = false))
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.recoverable)
    }

    @Test
    fun frameStallBeyondErrorBudget_isRecoverableError() {
        val r = eval(healthySnapshot(sampleElapsedMs = 12_000L, lastFrameDeliveryElapsedMs = 10_000L))
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.recoverable)
    }

    @Test
    fun frameStallInWarnBand_isWarning() {
        val r = eval(healthySnapshot(sampleElapsedMs = 10_800L, lastFrameDeliveryElapsedMs = 10_000L))
        assertEquals(HealthState.WARNING, r.state)
    }

    @Test
    fun vulkanUnsupported_isCpuFallbackWarning() {
        val r = eval(healthySnapshot(vulkanSupported = false))
        assertEquals(HealthState.WARNING, r.state)
        assertEquals("CPU compatibility path", r.diagnostics.currentFallback)
    }

    @Test
    fun intermittentImportFailures_isWarning() {
        val r = eval(healthySnapshot(consecutiveFailedImports = 3))
        assertEquals(HealthState.WARNING, r.state)
    }

    @Test
    fun droppedFramesWhileRecording_isWarning() {
        val r = eval(healthySnapshot(dropEstimate = 4))
        assertEquals(HealthState.WARNING, r.state)
    }

    @Test
    fun awaitingFirstFrame_isUnknownNotHealthy() {
        // No frame has ever been delivered (null delivery pulse) — liveness is unproven, so the probe
        // must NOT fall through to a green HEALTHY/ACTIVE; it is honestly UNKNOWN until a delivery pulse.
        val r = eval(healthySnapshot(recording = false, lastFrameDeliveryElapsedMs = null))
        assertEquals(HealthState.UNKNOWN, r.state)
        assertTrue(r.reason.contains("Awaiting", ignoreCase = true))
    }

    @Test
    fun genuineHardFaultTakesPriorityOverAdvisory() {
        // A genuine encoder fault outranks a mere hardware advisory: the real hard fault wins and the
        // advisory does not mask or downgrade it.
        val r = eval(healthySnapshot(gpuRecordErrorMessage = "codec died", hardwareWarning = "overheating"))
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.hardFault)
    }

    @Test
    fun advisoryDoesNotMaskImportFailureError() {
        // A hardware advisory must not preempt a genuine ERROR-level import failure.
        val r = eval(
            healthySnapshot(
                hardwareWarning = "overheating",
                consecutiveFailedImports = 10,
                vulkanPipelineHealthy = false
            )
        )
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.recoverable)
    }
}
