package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden LOCK for the AE/AWB metering hysteresis + hold ([MeteringConvergenceHysteresis]) and its
 * shipping runtime gate ([MeteringDampingFlag]).
 *
 * Pins the on-device-validated tuning (stabilize=2, hold=8, afScan-hold=20) and the exact
 * shipping-default behaviour that eliminates the reframe washout / skin-shift, so a future
 * "harmless" retune of a constant or an inversion of the default flag fails loudly here rather
 * than silently regressing the fix in the field.
 */
class AeAwbHysteresisGoldenTest {

    // --- (1) golden-lock the on-device-validated tuning constants ---------------------------------

    @Test
    fun `default tuning constants are the on-device-validated values`() {
        assertEquals(2, MeteringConvergenceHysteresis.DEFAULT_STABILIZE_FRAMES)
        assertEquals(8, MeteringConvergenceHysteresis.DEFAULT_HOLD_FRAMES)
        assertEquals(20, MeteringConvergenceHysteresis.DEFAULT_AF_SCAN_HOLD_FRAMES)
    }

    // --- (2) shipping-default behaviour via the no-arg ctor ---------------------------------------

    @Test
    fun `shipping default latches stable after two converged frames`() {
        val h = MeteringConvergenceHysteresis()
        // First converged frame: run=1, still below DEFAULT_STABILIZE_FRAMES(2) -> not yet stable.
        assertFalse(h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = false))
        assertFalse(h.aeStable)
        assertFalse(h.awbStable)
        // Second converged frame: run=2 == stabilizeFrames -> latch both.
        assertTrue(h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = false))
        assertTrue(h.aeStable)
        assertTrue(h.awbStable)
    }

    @Test
    fun `shipping default holds an eight frame de-converged blip then releases on the ninth`() {
        val h = MeteringConvergenceHysteresis()
        // Latch stable.
        h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = false)
        h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = false)
        assertTrue(h.aeStable)
        assertTrue(h.awbStable)

        // AF is NOT scanning, so the plain DEFAULT_HOLD_FRAMES(8) window applies.
        // De-converged runs 1..8 are all held (release requires run > 8).
        repeat(8) { frame ->
            assertFalse(
                "de-converged frame ${frame + 1} within the 8-frame hold must not change the lock",
                h.update(aeConvergedRaw = false, awbConvergedRaw = false, afScanActive = false)
            )
            assertTrue("lock must be HELD through the 8-frame blip", h.aeStable)
            assertTrue("lock must be HELD through the 8-frame blip", h.awbStable)
        }
        // The 9th de-converged frame (run=9 > 8) releases the lock.
        assertTrue(
            "the frame beyond the hold window must report a change",
            h.update(aeConvergedRaw = false, awbConvergedRaw = false, afScanActive = false)
        )
        assertFalse(h.aeStable)
        assertFalse(h.awbStable)
    }

    // --- (3) AF-scan window holds twenty frames ---------------------------------------------------

    @Test
    fun `AF scan window holds twenty frames then releases on the twenty-first`() {
        val h = MeteringConvergenceHysteresis()
        // Latch stable.
        h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = false)
        h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = false)
        assertTrue(h.aeStable)
        assertTrue(h.awbStable)

        // With AF actively scanning the extended DEFAULT_AF_SCAN_HOLD_FRAMES(20) window applies;
        // this is well past the plain 8-frame hold that would otherwise have released the lock.
        repeat(20) { frame ->
            h.update(aeConvergedRaw = false, awbConvergedRaw = false, afScanActive = true)
            assertTrue(
                "AF-scan blip frame ${frame + 1} within the 20-frame window must hold the lock",
                h.aeStable
            )
            assertTrue(h.awbStable)
        }
        // The 21st scanning de-converged frame (run=21 > 20) releases.
        assertTrue(
            "the frame beyond the AF-scan hold window must report a change",
            h.update(aeConvergedRaw = false, awbConvergedRaw = false, afScanActive = true)
        )
        assertFalse(h.aeStable)
        assertFalse(h.awbStable)
    }

    // --- (4) shipping flag default -----------------------------------------------------------------

    @Test
    fun `metering damping flag ships enabled`() {
        assertTrue(
            "the reframe-washout fix must be ON by default",
            MeteringDampingFlag.enabled
        )
    }

    @Test
    fun `session rebuild color hold object default stays disabled for reversible baseline`() {
        assertFalse(
            "RC5 object default must stay OFF so the debug baseline remains reversible",
            SessionRebuildColorHoldFlag.enabled
        )
    }

    @Test
    fun `product startup enables session rebuild color hold`() {
        val candidates = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx/ui/ProductDefaultsApplier.kt"),
            File("src/main/java/com/neoproc/neoproccamx/ui/ProductDefaultsApplier.kt")
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(
            "Production startup must prevent a stale post-rebuild white point from being locked",
            source.contains("SessionRebuildColorHoldFlag.set(true)")
        )
    }

    // --- (5) identity passthrough at (1,0,0) ------------------------------------------------------

    @Test
    fun `identity passthrough locks and releases per-frame with no hysteresis`() {
        val h = MeteringConvergenceHysteresis(stabilizeFrames = 1, holdFrames = 0, afScanHoldFrames = 0)
        // stabilizeFrames=1 -> a single converged frame latches immediately.
        assertTrue(h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = false))
        assertTrue(h.aeStable)
        assertTrue(h.awbStable)
        // holdFrames=0 -> the first de-converged frame (run=1 > 0) releases immediately.
        assertTrue(h.update(aeConvergedRaw = false, awbConvergedRaw = false, afScanActive = false))
        assertFalse(h.aeStable)
        assertFalse(h.awbStable)
        // afScanHoldFrames=0 -> no extra hold even while AF scans: still per-frame identity.
        assertTrue(h.update(aeConvergedRaw = true, awbConvergedRaw = true, afScanActive = true))
        assertTrue(h.aeStable)
        assertTrue(h.awbStable)
        assertTrue(h.update(aeConvergedRaw = false, awbConvergedRaw = false, afScanActive = true))
        assertFalse(h.aeStable)
        assertFalse(h.awbStable)
    }
}
