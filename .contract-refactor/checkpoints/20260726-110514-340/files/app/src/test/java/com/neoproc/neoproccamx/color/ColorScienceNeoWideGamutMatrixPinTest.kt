package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Neo Wide Gamut color transform that ColorScience routes through its private
 * NEO_WIDE_GAMUT_TO_XYZ_MATRIX (a 7-decimal mirror of the authoritative NeoWideGamut2.NEO_TO_XYZ),
 * and asserts the two definitions still agree to display precision.
 *
 * Two guards:
 *  - [neoWideGamutTransformIsPinned] freezes the end-to-end convertToColorSpace output, catching any
 *    change to ColorScience's matrix (or the surrounding transform). The live path is
 *    inverse(NEO_WIDE_GAMUT_TO_XYZ_MATRIX) * rec709ToXyz followed by eliteBoundedRgb gamut bounding,
 *    so these goldens pin that whole composition (the blue-primary Z=1.0 row pins the gamut clamp).
 *  - [centralNeoToXyzMatchesColorScienceLiterals] asserts NeoWideGamut2.NEO_TO_XYZ matches the values
 *    ColorScience hard-codes, so a recalibration of the central source-of-truth surfaces the
 *    duplicated-constant divergence instead of silently diverging the CPU/preview path from the
 *    AcesColorEngine/NeoGamutCat16Math path and the shaders.
 *
 * Test-only: no production code changed (behavior-preserving Option A — pin, don't re-point).
 */
class ColorScienceNeoWideGamutMatrixPinTest {

    private val cs = ColorScience()

    private data class M(
        val r: Float, val g: Float, val b: Float,
        val x: Float, val y: Float, val z: Float
    )

    private val goldens = listOf(
        M(1.0f, 0.0f, 0.0f, 0.5182688f, 0.0950485f, 0.016677972f),
        M(0.0f, 1.0f, 0.0f, 0.34084174f, 0.87218606f, 0.08624822f),
        M(0.0f, 0.0f, 1.0f, 0.11202721f, 0.04904245f, 1.0f),
        M(0.18f, 0.18f, 0.18f, 0.1746239f, 0.18160424f, 0.21448454f),
        M(0.5f, 0.3f, 0.7f, 0.43910253f, 0.33835456f, 0.79627156f)
    )

    @Test
    fun neoWideGamutTransformIsPinned() {
        for (m in goldens) {
            val (x, y, z) = cs.convertToColorSpace(m.r, m.g, m.b, "Neo Wide Gamut")
            val label = "NeoWG(${m.r},${m.g},${m.b})"
            assertEquals("$label X", m.x, x, 1e-5f)
            assertEquals("$label Y", m.y, y, 1e-5f)
            assertEquals("$label Z", m.z, z, 1e-5f)
        }
    }

    @Test
    fun centralNeoToXyzMatchesColorScienceLiterals() {
        val csLiterals = floatArrayOf(
            0.774888889f, 0.098962557f, 0.082970662f,
            0.279812198f, 0.709575275f, 0.010612527f,
            0.000000000f, 0.050512138f, 0.871191949f
        )
        for (i in csLiterals.indices) {
            assertEquals("NEO_TO_XYZ[$i] diverged from ColorScience literal", csLiterals[i], NeoWideGamut2.NEO_TO_XYZ[i], 1e-6f)
        }
    }
}
