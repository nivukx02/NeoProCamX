package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s8-f01 — vectorscope polar mapping. The 75% colour bars must be saturated and rotationally
 * distinct, complementary colours must sit ≈180° apart, and skin tones must cluster along the
 * I-line (≈123°).
 */
class VectorscopeMappingTest {

    private fun angularDelta(a: Float, b: Float): Float {
        var d = abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    @Test
    fun seventyFivePercentBarsAreSaturatedAndDistinct() {
        val bars = VectorscopeMath.BarTarget.entries.map { VectorscopeMath.targetBox75(it) }
        bars.forEach { assertTrue("bar radius ${it.radius01} should be saturated", it.radius01 > 0.4f) }
        for (i in bars.indices) {
            for (j in i + 1 until bars.size) {
                assertTrue(
                    "bars $i and $j must be rotationally distinct",
                    angularDelta(bars[i].angleDeg, bars[j].angleDeg) > 20f
                )
            }
        }
    }

    @Test
    fun complementaryBarsAreOppositeOnTheScope() {
        val red = VectorscopeMath.targetBox75(VectorscopeMath.BarTarget.RED)
        val cyan = VectorscopeMath.targetBox75(VectorscopeMath.BarTarget.CYAN)
        val green = VectorscopeMath.targetBox75(VectorscopeMath.BarTarget.GREEN)
        val magenta = VectorscopeMath.targetBox75(VectorscopeMath.BarTarget.MAGENTA)
        val blue = VectorscopeMath.targetBox75(VectorscopeMath.BarTarget.BLUE)
        val yellow = VectorscopeMath.targetBox75(VectorscopeMath.BarTarget.YELLOW)
        assertTrue(angularDelta(red.angleDeg, cyan.angleDeg) > 168f)
        assertTrue(angularDelta(green.angleDeg, magenta.angleDeg) > 168f)
        assertTrue(angularDelta(blue.angleDeg, yellow.angleDeg) > 168f)
    }

    @Test
    fun skinSamplesClusterOnILine() {
        val skin = listOf(
            VectorscopeMath.map(230, 180, 150),
            VectorscopeMath.map(210, 160, 130),
            VectorscopeMath.map(240, 200, 170)
        )
        val angles = skin.map { it.angleDeg }
        // Tight cluster.
        assertTrue("skin angles must cluster", (angles.max() - angles.min()) < 8f)
        // Along the I-line (skin axis ≈123°) within the simplified-mapping tolerance.
        angles.forEach {
            assertTrue("skin $it near I-line ${VectorscopeMath.I_LINE_DEG}", abs(it - VectorscopeMath.I_LINE_DEG) < 15f)
        }
    }
}
