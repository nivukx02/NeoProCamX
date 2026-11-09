package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-golden mirror of the shared `neoHighEndKneeCurve` in ycbcr_preview.frag
 * and neo_p010_export_enhance.comp (identical in both — the universal NDR/NIVUKX
 * high-end highlight knee applied on preview, record, and NPV10 export).
 *
 * The previous tanh(strength*n)/tanh(strength) shoulder had slope
 * strength/tanh(strength) (~2.3) at the knee and EXPANDED upper-midtones — output
 * exceeded input just above the knee, printing a Mach band (audit rank 3). The
 * fixed C1 Reinhard shoulder has unit slope at the knee join and compresses
 * (output <= input) monotonically. These goldens lock that behavior so both
 * shaders stay converged and never re-expand.
 */
class NeoHighEndKneeCurveParityTest {

    /** Exact port of the fixed shader function. */
    private fun kneeCurve(luma: Double, knee: Double, shoulder: Double, strength: Double): Double {
        val x = maxOf(luma, 0.0)
        val safeKnee = knee.coerceIn(0.18, 0.92)
        val safeShoulder = shoulder.coerceIn(safeKnee + 0.02, 1.0)
        val safeStrength = maxOf(strength, 0.05)
        if (x <= safeKnee) return x
        val span = maxOf(safeShoulder - safeKnee, 1e-5)
        val normalized = maxOf((x - safeKnee) / span, 0.0)
        val a = ((safeStrength - 1.0) * 0.14).coerceIn(0.02, 0.35)
        val mapped = normalized / (1.0 + a * normalized)
        return (safeKnee + span * mapped).coerceIn(0.0, safeShoulder)
    }

    // NIVUKX 0.54/1.00/2.25 and NDR10+ 0.58/1.00/2.05 (resolveNeoHighEndKneeParams).
    private val modes = listOf(
        Triple(0.54, 1.00, 2.25),
        Triple(0.58, 1.00, 2.05)
    )

    @Test
    fun belowKneeIsIdentity() {
        for ((knee, shoulder, strength) in modes) {
            assertEquals(knee, kneeCurve(knee, knee, shoulder, strength), 1e-9)
            assertEquals(0.30, kneeCurve(0.30, knee, shoulder, strength), 1e-9)
        }
    }

    @Test
    fun neverExpandsHighlights() {
        // The core rank-3 fix: above the knee, output must never exceed input.
        for ((knee, shoulder, strength) in modes) {
            var x = knee
            while (x <= 1.0 + 1e-9) {
                val y = kneeCurve(x, knee, shoulder, strength)
                assertTrue("knee EXPANDED at x=$x (knee=$knee): $y > $x", y <= x + 1e-9)
                x += 0.002
            }
            // Explicit old-bug point: 0.55 used to map to ~0.563 (expansion).
            val justAbove = knee + 0.01
            assertTrue(kneeCurve(justAbove, knee, shoulder, strength) <= justAbove + 1e-9)
        }
    }

    @Test
    fun unitSlopeAtKneeJoin() {
        val h = 1e-5
        for ((knee, shoulder, strength) in modes) {
            val slope = (kneeCurve(knee + h, knee, shoulder, strength) - knee) / h
            assertTrue("knee slope kick (>1) at join: $slope", slope <= 1.0 + 1e-3)
            assertTrue("knee slope should stay positive: $slope", slope > 0.5)
        }
    }

    @Test
    fun monotoneAndBoundedByShoulder() {
        for ((knee, shoulder, strength) in modes) {
            var prev = -1.0
            var x = 0.0
            while (x <= 1.2) {
                val y = kneeCurve(x, knee, shoulder, strength)
                assertTrue("non-monotone at x=$x", y >= prev - 1e-12)
                assertTrue("exceeds shoulder at x=$x: $y", y <= shoulder + 1e-9)
                prev = y
                x += 0.002
            }
            // Highlights still roll UP into headroom (gentle, not crushed).
            assertTrue(kneeCurve(1.0, knee, shoulder, strength) > knee)
            assertTrue(kneeCurve(1.0, knee, shoulder, strength) > 0.88)
        }
    }
}
