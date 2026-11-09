package com.neoproc.neoproccamx.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthStabilizerTest {

    private val diag = HealthDiagnostics(pipelineStage = "test")

    private fun reading(state: HealthState, hardFault: Boolean = false) =
        ModuleHealthReading(MonitoredModule.PROV_CORE, state, "r", diag, hardFault = hardFault)

    private fun stabilizer() =
        HealthStabilizer(MonitoredModule.PROV_CORE, HealthStabilizerConfig(worsenTicks = 2, improveTicks = 3))

    @Test
    fun firstReadingAdoptedImmediately() {
        val s = stabilizer()
        val out = s.accept(reading(HealthState.HEALTHY), 0)
        assertEquals(HealthState.HEALTHY, out.state)
        assertEquals(0L, out.stateSinceElapsedMs)
    }

    @Test
    fun hardFaultAdoptedImmediately() {
        val s = stabilizer()
        s.accept(reading(HealthState.HEALTHY), 0)
        val out = s.accept(reading(HealthState.ERROR, hardFault = true), 100)
        assertEquals(HealthState.ERROR, out.state)
        assertEquals(100L, out.stateSinceElapsedMs)
    }

    @Test
    fun inactiveAdoptedImmediately() {
        val s = stabilizer()
        s.accept(reading(HealthState.HEALTHY), 0)
        val out = s.accept(reading(HealthState.INACTIVE), 100)
        assertEquals(HealthState.INACTIVE, out.state)
    }

    @Test
    fun worseningRequiresTwoConfirmations() {
        val s = stabilizer()
        s.accept(reading(HealthState.HEALTHY), 0)
        // First WARNING sample is held (still HEALTHY) to absorb a single-sample blip.
        assertEquals(HealthState.HEALTHY, s.accept(reading(HealthState.WARNING), 100).state)
        // Second confirming WARNING commits.
        assertEquals(HealthState.WARNING, s.accept(reading(HealthState.WARNING), 200).state)
    }

    @Test
    fun singleBlipDoesNotFlip() {
        val s = stabilizer()
        s.accept(reading(HealthState.HEALTHY), 0)
        assertEquals(HealthState.HEALTHY, s.accept(reading(HealthState.WARNING), 100).state)
        // Recovered before confirmation — stays HEALTHY, blip absorbed.
        assertEquals(HealthState.HEALTHY, s.accept(reading(HealthState.HEALTHY), 200).state)
    }

    @Test
    fun improvingRequiresThreeConfirmations() {
        val s = stabilizer()
        s.accept(reading(HealthState.ERROR, hardFault = true), 0)
        assertEquals(HealthState.ERROR, s.accept(reading(HealthState.HEALTHY), 100).state)
        assertEquals(HealthState.ERROR, s.accept(reading(HealthState.HEALTHY), 200).state)
        // Third confirming HEALTHY finally promotes.
        assertEquals(HealthState.HEALTHY, s.accept(reading(HealthState.HEALTHY), 300).state)
    }

    @Test
    fun oscillatingWorseStatesDoNotHoldFalseHealthy() {
        // Committed HEALTHY; the probe alternates between two DISTINCT worse states. The debounce is
        // keyed on direction-vs-committed (not exact state), so it must still commit a worse state
        // (the worst seen) rather than holding a false-green HEALTHY forever.
        val s = stabilizer()
        s.accept(reading(HealthState.HEALTHY), 0)
        assertEquals(HealthState.HEALTHY, s.accept(reading(HealthState.WARNING), 100).state) // held
        val out = s.accept(reading(HealthState.ERROR), 200) // second worse tick -> commits worst
        assertEquals(HealthState.ERROR, out.state)
    }

    @Test
    fun improvingCommitsMostConservativeStateSeen() {
        // Recovering from ERROR but the window still contains a WARNING: must land on WARNING, never
        // jump straight to HEALTHY.
        val s = stabilizer()
        s.accept(reading(HealthState.ERROR, hardFault = true), 0)
        assertEquals(HealthState.ERROR, s.accept(reading(HealthState.HEALTHY), 100).state)
        assertEquals(HealthState.ERROR, s.accept(reading(HealthState.WARNING), 200).state)
        val out = s.accept(reading(HealthState.HEALTHY), 300) // 3rd improving tick -> commit worst=WARNING
        assertEquals(HealthState.WARNING, out.state)
    }

    @Test
    fun strictAlternationStillCommitsTheWorseState() {
        // A genuinely intermittent degradation that strictly alternates worse/better around a committed
        // benign state (e.g. GPU latency jittering across the WARN threshold every tick: WARNING/ACTIVE)
        // must NOT be held as the committed good state forever — the worsening observations accumulate
        // across the improving interruptions and commit the worse state.
        val s = stabilizer()
        s.accept(reading(HealthState.READY), 0)
        assertEquals(HealthState.READY, s.accept(reading(HealthState.WARNING), 100).state) // worse #1, held
        assertEquals(HealthState.READY, s.accept(reading(HealthState.ACTIVE), 200).state)  // better blip
        // Second worsening observation (interleaved, not consecutive) reaches worsenTicks -> commits.
        assertEquals(HealthState.WARNING, s.accept(reading(HealthState.WARNING), 300).state)
    }

    @Test
    fun singleBlipThenReturnToCommittedIsStillAbsorbed() {
        // The absorb-a-single-blip guarantee is preserved: one worse reading followed by a genuine
        // return to the committed state resets the accumulator (no spurious commit).
        val s = stabilizer()
        s.accept(reading(HealthState.HEALTHY), 0)
        assertEquals(HealthState.HEALTHY, s.accept(reading(HealthState.WARNING), 100).state)
        assertEquals(HealthState.HEALTHY, s.accept(reading(HealthState.HEALTHY), 200).state)
        // A later lone WARNING is again just #1 (streak was reset) — still held.
        assertEquals(HealthState.HEALTHY, s.accept(reading(HealthState.WARNING), 300).state)
    }

    @Test
    fun sameStateKeepsOriginalEntryTime() {
        val s = stabilizer()
        s.accept(reading(HealthState.HEALTHY), 50)
        val out = s.accept(reading(HealthState.HEALTHY), 5_000)
        assertEquals(50L, out.stateSinceElapsedMs)
        assertEquals(5_000L, out.updatedElapsedMs)
    }
}
