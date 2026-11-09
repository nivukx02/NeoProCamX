package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostRebuildConvergenceGateTest {

    @Test fun `disarmed gate is a pure identity`() {
        val g = PostRebuildConvergenceGate()
        assertTrue(g.lockPermitted)
        assertFalse(g.update(awbConvergedRaw = true))   // no-op
        assertFalse(g.update(awbConvergedRaw = false))
        assertTrue(g.lockPermitted)
    }

    @Test fun `RC5 - stale converged first frame does NOT permit the lock`() {
        val g = PostRebuildConvergenceGate(maxDeferFrames = 30)
        g.armForRebuild()
        assertFalse(g.lockPermitted)
        // pre-rebuild CONVERGED bleeding into the first fresh-session frame must be ignored
        assertFalse(g.update(awbConvergedRaw = true))
        assertFalse(g.lockPermitted)
    }

    @Test fun `permits only after a genuine unconverged-then-converged cycle`() {
        val g = PostRebuildConvergenceGate(maxDeferFrames = 30)
        g.armForRebuild()
        g.update(true)                       // stale converged: blocked
        assertFalse(g.lockPermitted)
        g.update(false)                      // fresh AWB re-metering flushes the stale state
        assertFalse(g.lockPermitted)
        assertTrue(g.update(true))           // genuine convergence -> permit + reports change
        assertTrue(g.lockPermitted)
    }

    @Test fun `safety cap always eventually permits (no HAL unconverged frame)`() {
        val g = PostRebuildConvergenceGate(maxDeferFrames = 3)
        g.armForRebuild()
        assertFalse(g.update(true))          // frame 1
        assertFalse(g.update(true))          // frame 2
        assertTrue(g.update(true))           // frame 3 == cap -> permit
        assertTrue(g.lockPermitted)
    }

    @Test fun `disarm restores identity immediately`() {
        val g = PostRebuildConvergenceGate()
        g.armForRebuild()
        assertFalse(g.lockPermitted)
        g.disarm()
        assertTrue(g.lockPermitted)
        assertFalse(g.update(true))
    }
}
