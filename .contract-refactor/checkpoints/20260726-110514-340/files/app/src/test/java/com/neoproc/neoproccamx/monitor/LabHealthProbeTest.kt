package com.neoproc.neoproccamx.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabHealthProbeTest {

    private fun eval(s: RuntimeSignalSnapshot) = LabHealthProbe.evaluate(s)

    @Test
    fun healthyCompute_isActive() {
        // A dispatching GPU compute path with healthy latency is ACTIVE (verified live work), not merely
        // HEALTHY.
        val r = eval(healthySnapshot(computeAverageMs = 8.0))
        assertEquals(HealthState.ACTIVE, r.state)
    }

    @Test
    fun initFailedSummary_isImmediateError() {
        val r = eval(healthySnapshot(capabilitySummary = "Vulkan init failed: RuntimeException"))
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.hardFault)
        assertTrue(r.recoverable)
    }

    @Test
    fun vulkanUnsupported_isUnsupportedNotError() {
        // Vulkan genuinely absent on the device (init has settled — summary is a resolved reason, not the
        // pre-init sentinel) is UNSUPPORTED (a capability gap), never a fault.
        val r = eval(healthySnapshot(vulkanSupported = false, capabilitySummary = "Vulkan AHardwareBuffer import requires API 29+"))
        assertEquals(HealthState.UNSUPPORTED, r.state)
    }

    @Test
    fun vulkanNotYetInitialized_isUnknownNotUnsupported() {
        // Cold-start async init: vulkanSupported is transiently false while the summary is still the
        // pre-init sentinel. Must NOT fabricate a definite UNSUPPORTED — report UNKNOWN until it settles.
        val r = eval(healthySnapshot(vulkanSupported = false, capabilitySummary = "Vulkan runtime not initialized"))
        assertEquals(HealthState.UNKNOWN, r.state)
    }

    @Test
    fun dispatchNotAdvancing_isReadyEvenWithStaleAverage() {
        // The native compute counters are lifetime-cumulative; a non-null (stale) average with NO fresh
        // dispatch this tick must read READY (armed/idle), never a fabricated ACTIVE. This is the
        // preview-after-recording case on the direct-camera-preview device.
        val r = eval(healthySnapshot(computeAverageMs = 8.0, computeDispatched = false))
        assertEquals(HealthState.READY, r.state)
    }

    @Test
    fun pipelineUnhealthy_isRecoverableError() {
        val r = eval(healthySnapshot(vulkanPipelineHealthy = false, consecutiveFailedImports = 12))
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.recoverable)
    }

    @Test
    fun criticalLatency_isError() {
        val r = eval(healthySnapshot(computeAverageMs = 50.0))
        assertEquals(HealthState.ERROR, r.state)
    }

    @Test
    fun highLatency_isWarning() {
        val r = eval(healthySnapshot(computeAverageMs = 30.0))
        assertEquals(HealthState.WARNING, r.state)
    }

    @Test
    fun noTimingButDispatched_isActive() {
        val r = eval(healthySnapshot(computeAverageMs = null, computeDispatched = true))
        assertEquals(HealthState.ACTIVE, r.state)
        assertTrue(r.reason.contains("active", ignoreCase = true))
    }

    @Test
    fun noTimingNotDispatched_isReadyArmed() {
        // Armed-but-idle is honest READY (initialized + pipeline healthy), not a false "active" claim and
        // not a misleading green HEALTHY.
        val r = eval(healthySnapshot(computeAverageMs = null, computeDispatched = false))
        assertEquals(HealthState.READY, r.state)
        assertTrue(r.reason.contains("armed", ignoreCase = true))
    }

    @Test
    fun activeCompute_surfacesDispatchCountPeakAndUtilization() {
        val r = eval(
            healthySnapshot(
                computeAverageMs = 8.0,
                computeTotalDispatches = 555L,
                computePeakMs = 14.0,
                computeGpuTimestamped = true
            )
        )
        assertEquals(HealthState.ACTIVE, r.state)
        val m = r.diagnostics.metrics.toMap()
        assertEquals("555", m["Dispatch count"])
        assertEquals("14.0 ms", m["Peak compute"])
        assertTrue(m["GPU utilization"]?.endsWith("%") == true)
    }
}
