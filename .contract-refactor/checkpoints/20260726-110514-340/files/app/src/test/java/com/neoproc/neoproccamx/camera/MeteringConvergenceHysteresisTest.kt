package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringConvergenceHysteresisTest {

    @Test
    fun `requires stabilizeFrames consecutive converged frames before locking`() {
        val h = MeteringConvergenceHysteresis(stabilizeFrames = 2, holdFrames = 3, afScanHoldFrames = 6)
        assertFalse(h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = false)) // run=1, not yet
        assertFalse(h.aeStable)
        assertTrue(h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = false)) // run=2 -> stable
        assertTrue(h.aeStable)
        assertTrue(h.awbStable)
    }

    @Test
    fun `identity passthrough at (1,0,0)`() {
        val h = MeteringConvergenceHysteresis(stabilizeFrames = 1, holdFrames = 0, afScanHoldFrames = 0)
        assertTrue(h.update(true, true, false)) // locks immediately
        assertTrue(h.aeStable && h.awbStable)
        assertTrue(h.update(false, false, false)) // releases immediately (no hold)
        assertFalse(h.aeStable || h.awbStable)
    }

    @Test
    fun `holds the lock through a brief de-convergence blip then releases`() {
        val h = MeteringConvergenceHysteresis(stabilizeFrames = 2, holdFrames = 3, afScanHoldFrames = 6)
        h.update(true, true, false); h.update(true, true, false) // stable
        assertTrue(h.aeStable)
        // 3 de-converged frames: held (deconvergedRun 1,2,3 are <= holdFrames=3)
        assertFalse(h.update(false, false, false)); assertTrue(h.aeStable) // run=1
        assertFalse(h.update(false, false, false)); assertTrue(h.aeStable) // run=2
        assertFalse(h.update(false, false, false)); assertTrue(h.aeStable) // run=3
        // 4th de-converged frame exceeds the hold -> release
        assertTrue(h.update(false, false, false)); assertFalse(h.aeStable)  // run=4 > 3
    }

    @Test
    fun `AF scan extends the hold window`() {
        val h = MeteringConvergenceHysteresis(stabilizeFrames = 2, holdFrames = 3, afScanHoldFrames = 6)
        h.update(true, true, false); h.update(true, true, false) // stable
        // With AF actively scanning, the same 4th de-converged frame must STILL be held
        // (it would have released at frame 4 without the scan).
        repeat(4) { h.update(aeConvergedRaw = false, awbConvergedRaw = false, afScanActive = true) }
        assertTrue("AF-scan blip up to afScanHoldFrames must hold the lock", h.aeStable)
        // Beyond afScanHoldFrames it eventually releases.
        repeat(3) { h.update(false, false, true) } // total 7 > 6
        assertFalse(h.aeStable)
    }

    @Test
    fun `sustained de-convergence releases and then re-locks after restabilizing`() {
        val h = MeteringConvergenceHysteresis(stabilizeFrames = 2, holdFrames = 1, afScanHoldFrames = 1)
        h.update(true, true, false); h.update(true, true, false)
        assertTrue(h.aeStable)
        repeat(3) { h.update(false, false, false) } // sustained -> release
        assertFalse(h.aeStable)
        assertFalse(h.update(true, true, false)) // run=1, not yet
        assertTrue(h.update(true, true, false)) // run=2 -> re-lock
        assertTrue(h.aeStable)
    }

    @Test
    fun `AE and AWB are tracked independently`() {
        val h = MeteringConvergenceHysteresis(stabilizeFrames = 1, holdFrames = 2, afScanHoldFrames = 2)
        h.update(aeConvergedRaw = true, awbConvergedRaw = false, afScanActive = false)
        assertTrue(h.aeStable)
        assertFalse(h.awbStable)
    }

    @Test
    fun `update returns true only when a stable flag changes`() {
        val h = MeteringConvergenceHysteresis(stabilizeFrames = 1, holdFrames = 5, afScanHoldFrames = 5)
        assertTrue(h.update(true, true, false))   // false->true
        assertFalse(h.update(true, true, false))  // no change
        assertFalse(h.update(false, false, false)) // held, no change
    }

    @Test
    fun `reset clears all state`() {
        val h = MeteringConvergenceHysteresis(stabilizeFrames = 1, holdFrames = 1, afScanHoldFrames = 1)
        h.update(true, true, false)
        assertTrue(h.aeStable)
        h.reset()
        assertFalse(h.aeStable)
        assertFalse(h.awbStable)
    }
}
