package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for [AIFocusMeteringMath.clampMeteringRect]: a degenerate/empty bounds
 * (right<=left or bottom<=top) previously made `coerceIn(min,max)` throw an empty-range exception.
 */
class AiFocusMeteringClampTest {

    @Test
    fun `degenerate bounds do not throw and collapse to the bounds`() {
        val region = AIMeteringRect(10, 10, 50, 50)
        val emptyW = AIMeteringRect(100, 100, 100, 200) // right == left
        val invertedH = AIMeteringRect(0, 200, 100, 100) // bottom < top
        assertEquals(emptyW, AIFocusMeteringMath.clampMeteringRect(region, emptyW))
        assertEquals(invertedH, AIFocusMeteringMath.clampMeteringRect(region, invertedH))
    }

    @Test
    fun `valid bounds clamp the region inside`() {
        val bounds = AIMeteringRect(0, 0, 100, 100)
        val outside = AIMeteringRect(-20, -20, 250, 250)
        val r = AIFocusMeteringMath.clampMeteringRect(outside, bounds)
        assertTrue(r.left in 0..99 && r.top in 0..99)
        assertTrue(r.right in (r.left + 1)..100 && r.bottom in (r.top + 1)..100)
    }
}
