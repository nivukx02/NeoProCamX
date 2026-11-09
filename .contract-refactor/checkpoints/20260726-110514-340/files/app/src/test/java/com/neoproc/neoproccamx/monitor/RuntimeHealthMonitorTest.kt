package com.neoproc.neoproccamx.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHealthMonitorTest {

    private class FakeExecutor(
        var succeed: Boolean = true,
        var throwOnExecute: Boolean = false
    ) : HealthRecoveryExecutor {
        val actions = mutableListOf<RecoveryAction>()
        override fun execute(action: RecoveryAction): Boolean {
            actions += action
            if (throwOnExecute) throw IllegalStateException("boom")
            return succeed
        }
    }

    private class FakeLogger : HealthEventLogger {
        data class Transition(val module: MonitoredModule, val previous: HealthState?, val next: HealthState)
        data class Recovery(val module: MonitoredModule, val action: RecoveryAction, val success: Boolean)
        val transitions = mutableListOf<Transition>()
        val recoveries = mutableListOf<Recovery>()
        override fun onStateTransition(module: MonitoredModule, previous: HealthState?, next: ModuleHealthStatus) {
            transitions += Transition(module, previous, next.state)
        }
        override fun onRecoveryAttempt(module: MonitoredModule, action: RecoveryAction, attempt: Int, success: Boolean, rationale: String) {
            recoveries += Recovery(module, action, success)
        }
    }

    @Test
    fun healthySnapshot_allActive_noRecovery() {
        val exec = FakeExecutor()
        val monitor = RuntimeHealthMonitor(recoveryExecutor = exec)
        val out = monitor.sample(healthySnapshot())
        // Recording + frames flowing + proven 10-bit + ISP honored + compute dispatching = all ACTIVE.
        assertEquals(HealthState.ACTIVE, out.getValue(MonitoredModule.PROV_CORE).state)
        assertEquals(HealthState.ACTIVE, out.getValue(MonitoredModule.P010).state)
        assertEquals(HealthState.ACTIVE, out.getValue(MonitoredModule.LAB).state)
        assertEquals(HealthState.ACTIVE, out.getValue(MonitoredModule.NATIVE).state)
        assertTrue(exec.actions.isEmpty())
        // The StateFlow mirrors the returned map.
        assertEquals(out, monitor.status.value)
    }

    @Test
    fun previewSnapshot_p010Ready_ispReady_pipelineActive() {
        val monitor = RuntimeHealthMonitor()
        val out = monitor.sample(previewSnapshot())
        assertEquals(HealthState.READY, out.getValue(MonitoredModule.P010).state) // 10-bit capable, idle
        assertEquals(HealthState.READY, out.getValue(MonitoredModule.NATIVE).state) // processed, HAL alive
        assertEquals(HealthState.READY, out.getValue(MonitoredModule.LAB).state) // compute armed, idle
        assertEquals(HealthState.ACTIVE, out.getValue(MonitoredModule.PROV_CORE).state) // frames flowing
    }

    @Test
    fun ispFault_triggersRecoveryThenCommitsError() {
        val exec = FakeExecutor(succeed = true)
        val logger = FakeLogger()
        val monitor = RuntimeHealthMonitor(recoveryExecutor = exec, logger = logger)
        val fault = healthySnapshot(ispVerdictLabel = "NOT_PROVEN", ispReadbackWarning = "NR NOT_HONORED")

        // Recovery fires on the first raw ERROR; the chip commits ERROR after the worsen debounce.
        monitor.sample(fault.copy(sampleElapsedMs = 10_000))
        val second = monitor.sample(fault.copy(sampleElapsedMs = 10_150))

        val native = second.getValue(MonitoredModule.NATIVE)
        assertEquals(HealthState.ERROR, native.state)
        assertTrue(exec.actions.contains(RecoveryAction.REARM_ISP_READBACK))
        assertTrue(native.recoveryAttempts >= 1)
        assertTrue(native.recovering)
    }

    @Test
    fun recoveryReturnsToHealthy_reportsRecovered() {
        val exec = FakeExecutor(succeed = true)
        val monitor = RuntimeHealthMonitor(recoveryExecutor = exec)
        val fault = healthySnapshot(ispVerdictLabel = "NOT_PROVEN")

        // Drive into a committed ERROR.
        monitor.sample(fault.copy(sampleElapsedMs = 10_000))
        monitor.sample(fault.copy(sampleElapsedMs = 10_150))

        // Now recovered at the source: enough healthy ticks to clear the improve debounce.
        var last = monitor.sample(healthySnapshot(sampleElapsedMs = 12_000))
        last = monitor.sample(healthySnapshot(sampleElapsedMs = 12_150))
        last = monitor.sample(healthySnapshot(sampleElapsedMs = 12_300))

        val native = last.getValue(MonitoredModule.NATIVE)
        assertEquals(HealthState.ACTIVE, native.state)
        assertFalse(native.recovering)
        assertNotNull(native.recoveryResult)
        assertTrue(native.recoveryResult!!.contains("Recovered", ignoreCase = true))
    }

    @Test
    fun exhaustedRecovery_clearsRecovering_soChipStopsClaimingInFlight() {
        val exec = FakeExecutor(succeed = false) // never fixes the fault
        val monitor = RuntimeHealthMonitor(
            recoveryExecutor = exec,
            recoveryConfig = HealthRecoveryConfig(backoffMs = 0, maxAttempts = 2)
        )
        val fault = healthySnapshot(ispVerdictLabel = "NOT_PROVEN")
        var last = monitor.sample(fault.copy(sampleElapsedMs = 1))
        last = monitor.sample(fault.copy(sampleElapsedMs = 2))
        last = monitor.sample(fault.copy(sampleElapsedMs = 3))
        last = monitor.sample(fault.copy(sampleElapsedMs = 4))

        val native = last.getValue(MonitoredModule.NATIVE)
        assertEquals(HealthState.ERROR, native.state)
        // Once recovery is exhausted, the chip must show a true (red) ERROR, not a perpetual
        // "recovering" — and the panel must say why.
        assertFalse(native.recovering)
        assertTrue(native.recoveryResult!!.contains("exhausted", ignoreCase = true))
    }

    @Test
    fun executorThrowing_isContainedAndReportedAsNoEffect() {
        val exec = FakeExecutor(throwOnExecute = true)
        val monitor = RuntimeHealthMonitor(recoveryExecutor = exec)
        val fault = healthySnapshot(ispVerdictLabel = "NOT_PROVEN")
        monitor.sample(fault.copy(sampleElapsedMs = 10_000))
        val out = monitor.sample(fault.copy(sampleElapsedMs = 10_150))
        val native = out.getValue(MonitoredModule.NATIVE)
        // No crash; the honest ledger reports the failed effect.
        assertTrue(native.recoveryResult!!.contains("no effect", ignoreCase = true))
    }

    @Test
    fun transitionsAreLogged() {
        val logger = FakeLogger()
        val monitor = RuntimeHealthMonitor(logger = logger)
        monitor.sample(healthySnapshot(sampleElapsedMs = 1_000))
        // First sample logs the initial state adoption for every module (ACTIVE for a live pipeline).
        assertTrue(logger.transitions.any { it.module == MonitoredModule.PROV_CORE && it.next == HealthState.ACTIVE })
    }
}
