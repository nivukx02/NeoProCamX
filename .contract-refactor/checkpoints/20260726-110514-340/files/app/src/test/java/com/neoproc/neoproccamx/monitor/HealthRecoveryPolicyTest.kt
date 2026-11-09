package com.neoproc.neoproccamx.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthRecoveryPolicyTest {

    private val diag = HealthDiagnostics(pipelineStage = "test")

    private fun errorReading(module: MonitoredModule, recoverable: Boolean = true) =
        ModuleHealthReading(module, HealthState.ERROR, "err", diag, recoverable = recoverable)

    @Test
    fun nonErrorReading_yieldsNone() {
        val d = HealthRecoveryPolicy.decide(
            ModuleHealthReading(MonitoredModule.LAB, HealthState.WARNING, "w", diag),
            attemptsSoFar = 0, lastAttemptElapsedMs = null, nowMs = 0
        )
        assertEquals(RecoveryAction.NONE, d.action)
    }

    @Test
    fun nonRecoverableError_yieldsNone() {
        val d = HealthRecoveryPolicy.decide(
            errorReading(MonitoredModule.PROV_CORE, recoverable = false),
            attemptsSoFar = 0, lastAttemptElapsedMs = null, nowMs = 0
        )
        assertEquals(RecoveryAction.NONE, d.action)
    }

    @Test
    fun provCore_resetsImportHealth() {
        val d = HealthRecoveryPolicy.decide(
            errorReading(MonitoredModule.PROV_CORE), attemptsSoFar = 0, lastAttemptElapsedMs = null, nowMs = 0
        )
        assertEquals(RecoveryAction.RESET_IMPORT_HEALTH, d.action)
    }

    @Test
    fun lab_refreshesGpuCapabilities() {
        val d = HealthRecoveryPolicy.decide(
            errorReading(MonitoredModule.LAB), attemptsSoFar = 0, lastAttemptElapsedMs = null, nowMs = 0
        )
        assertEquals(RecoveryAction.REFRESH_GPU_CAPABILITIES, d.action)
    }

    @Test
    fun native_rearmsIspReadback() {
        val d = HealthRecoveryPolicy.decide(
            errorReading(MonitoredModule.NATIVE), attemptsSoFar = 0, lastAttemptElapsedMs = null, nowMs = 0
        )
        assertEquals(RecoveryAction.REARM_ISP_READBACK, d.action)
    }

    @Test
    fun p010_hasNoSafeAutomatedRecovery() {
        val d = HealthRecoveryPolicy.decide(
            errorReading(MonitoredModule.P010), attemptsSoFar = 0, lastAttemptElapsedMs = null, nowMs = 0
        )
        assertEquals(RecoveryAction.NONE, d.action)
    }

    @Test
    fun withinBackoff_yieldsNone() {
        val cfg = HealthRecoveryConfig(backoffMs = 1_500, maxAttempts = 5)
        val d = HealthRecoveryPolicy.decide(
            errorReading(MonitoredModule.LAB), attemptsSoFar = 1, lastAttemptElapsedMs = 1_000, nowMs = 1_500, config = cfg
        )
        assertEquals(RecoveryAction.NONE, d.action)
    }

    @Test
    fun afterBackoff_actsAgain() {
        val cfg = HealthRecoveryConfig(backoffMs = 1_500, maxAttempts = 5)
        val d = HealthRecoveryPolicy.decide(
            errorReading(MonitoredModule.LAB), attemptsSoFar = 1, lastAttemptElapsedMs = 1_000, nowMs = 2_600, config = cfg
        )
        assertEquals(RecoveryAction.REFRESH_GPU_CAPABILITIES, d.action)
    }

    @Test
    fun exhaustedAttempts_yieldsNoneWithRationale() {
        val cfg = HealthRecoveryConfig(backoffMs = 1_500, maxAttempts = 5)
        val d = HealthRecoveryPolicy.decide(
            errorReading(MonitoredModule.LAB), attemptsSoFar = 5, lastAttemptElapsedMs = 0, nowMs = 100_000, config = cfg
        )
        assertEquals(RecoveryAction.NONE, d.action)
        assertEquals(true, d.rationale.contains("exhausted", ignoreCase = true))
    }
}
