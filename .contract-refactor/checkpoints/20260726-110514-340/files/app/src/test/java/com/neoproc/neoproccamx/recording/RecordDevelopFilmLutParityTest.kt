package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.snce.SnceNeuralEnhancement
import com.neoproc.neoproccamx.snce.SnceNrliAnalyticLut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Locks the record-develop SDR film-print GPU-vs-CPU parity mechanism.
 *
 * ON-DEVICE the develop parity gate flagged a CONSTANT ~35-code divergence on channel 2 (BLUE) at the
 * shadow floor: GPU blue ≈ 0 where CPU blue ≈ 35. Root cause: the CPU golden path applies Stage-C
 * film-print by TETRAHEDRAL-interpolating a baked 33³ LUT ([SnceNrliAnalyticLut.buildFilmPrintLut] +
 * [SnceNeuralEnhancement.tetrahedralSampleLut]), while the GPU shader used to evaluate
 * [SnceNrliAnalyticLut.gradeFilmPrint] ANALYTICALLY per pixel. Near black the split-tone drives blue
 * negative; the analytic path clamps it to 0, but the LUT holds a small POSITIVE blue at the neighbour
 * node, so interpolation reconstructs a positive blue floor. This test proves that divergence in pure JVM
 * (no device) and documents WHY the shader must sample the SAME baked LUT to reach parity.
 */
class RecordDevelopFilmLutParityTest {

    private fun linearToRec709(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x < 0.018f) 4.5f * x else 1.099f * x.pow(0.45f) - 0.099f
    }

    private fun toCode(linear: Float): Int =
        (linearToRec709(linear) * 1023f).roundToInt().coerceIn(0, 1023)

    @Test
    fun analyticClampsShadowBlueToZeroButLutHoldsAPositiveFloor() {
        // A near-black, faintly-blue shadow sample HALF-WAY between the black corner and the first blue node
        // (1/32). This is exactly where the analytic vs interpolated behaviours diverge.
        val r = 0f
        val g = 0f
        val b = 1f / 64f // 0.015625, midway to the b=1/32 node

        val analytic = SnceNrliAnalyticLut.gradeFilmPrint(r, g, b)
        val lut = SnceNrliAnalyticLut.buildFilmPrintLut()
        val interpolated = SnceNeuralEnhancement.tetrahedralSampleLut(lut, floatArrayOf(r, g, b))

        // Analytic drives shadow blue below zero -> clamped to exactly 0.
        assertEquals("analytic shadow blue clamps to 0", 0f, analytic[2], 1e-6f)
        // The baked LUT interpolates a small POSITIVE blue floor (the CPU golden output).
        assertTrue("LUT reconstructs a positive blue floor: ${interpolated[2]}", interpolated[2] > 0.004f)

        // The floor lands in code space where the analytic path is 0 — a large channel-2 shadow divergence
        // (device saw ~35; the exact code depends on the real post-SNCE input, the mechanism is identical).
        val analyticCode = toCode(analytic[2])
        val lutCode = toCode(interpolated[2])
        assertEquals("analytic blue encodes to 0", 0, analyticCode)
        assertTrue("LUT blue encodes to a materially higher code: $lutCode", lutCode >= 20)
    }

    @Test
    fun analyticAndLutAgreeAtTheBlackCornerNode() {
        // AT a LUT node the tetrahedral sample returns the node value exactly, so analytic == LUT there.
        // This confirms the divergence is an interpolation artefact BETWEEN nodes, not a grade-math bug.
        val lut = SnceNrliAnalyticLut.buildFilmPrintLut()
        val atNode = SnceNeuralEnhancement.tetrahedralSampleLut(lut, floatArrayOf(0f, 0f, 0f))
        val analytic = SnceNrliAnalyticLut.gradeFilmPrint(0f, 0f, 0f)
        assertEquals("black-corner blue matches", analytic[2], atNode[2], 1e-6f)
        assertEquals("black-corner red matches", analytic[0], atNode[0], 1e-6f)
    }

    @Test
    fun redShadowMatchesBecauseSplitToneDrivesItPositive() {
        // The divergence is channel-2-only: the split-tone drives RED positive near black, so it never hits
        // the [0,1] clamp and the analytic and interpolated reds stay close (no systematic offset).
        val r = 1f / 64f
        val analyticR = SnceNrliAnalyticLut.gradeFilmPrint(r, 0f, 0f)[0]
        val lut = SnceNrliAnalyticLut.buildFilmPrintLut()
        val lutR = SnceNeuralEnhancement.tetrahedralSampleLut(lut, floatArrayOf(r, 0f, 0f))[0]
        assertTrue("analytic red stays positive (no clamp): $analyticR", analyticR > 0f)
        // Red codes stay within a few codes (no constant ~35-style offset like blue).
        assertTrue(
            "red divergence is small: a=${toCode(analyticR)} lut=${toCode(lutR)}",
            kotlin.math.abs(toCode(analyticR) - toCode(lutR)) <= 6
        )
    }
}
