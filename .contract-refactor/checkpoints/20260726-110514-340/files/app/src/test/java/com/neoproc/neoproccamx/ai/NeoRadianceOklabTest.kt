package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Golden for the corrected NeoRadianceMath.srgbToOklab (audit rank 31).
 *
 * The previous implementation multiplied LINEAR sRGB by the XYZ->LMS (M1) matrix
 * (and used a wrong M2 b-row), so neutral white mapped to a~=0.03, b~=-1.14 and a
 * neutral grey came back strongly chromatic. The canonical Ottosson matrices now
 * put neutrals on the achromatic axis (a=b=0) and white at L=1.
 */
class NeoRadianceOklabTest {

    @Test
    fun neutralWhiteIsAchromaticAtLightnessOne() {
        val (l, a, b) = NeoRadianceMath.srgbToOklab(1f, 1f, 1f)
        assertEquals(1f, l, 2e-3f)
        assertEquals(0f, a, 2e-3f)
        assertEquals(0f, b, 2e-3f)
    }

    @Test
    fun neutralGreyStaysOnAchromaticAxis() {
        for (v in listOf(0.25f, 0.5f, 0.75f)) {
            val (_, a, b) = NeoRadianceMath.srgbToOklab(v, v, v)
            assertTrue("grey $v not neutral: a=$a b=$b", abs(a) < 2e-3f && abs(b) < 2e-3f)
        }
    }

    @Test
    fun blackMapsToZero() {
        val (l, a, b) = NeoRadianceMath.srgbToOklab(0f, 0f, 0f)
        assertEquals(0f, l, 1e-6f)
        assertEquals(0f, a, 1e-6f)
        assertEquals(0f, b, 1e-6f)
    }

    @Test
    fun saturatedRedHasPositiveAAndB() {
        // Reference Oklab for pure sRGB red is roughly a=+0.22, b=+0.13.
        val (_, a, b) = NeoRadianceMath.srgbToOklab(1f, 0f, 0f)
        assertTrue("red a should be strongly positive: $a", a > 0.15f)
        assertTrue("red b should be positive: $b", b > 0.05f)
    }
}
