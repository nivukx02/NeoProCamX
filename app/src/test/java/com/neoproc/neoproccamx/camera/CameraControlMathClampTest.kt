package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for [CameraControlMath.clampMeteringRect] (CameraRect overload): a degenerate /
 * empty bounds previously made `coerceIn(min,max)` throw an empty-range exception.
 */
class CameraControlMathClampTest {

    @Test
    fun `degenerate bounds do not throw and collapse to the bounds`() {
        val region = CameraRect(10, 10, 50, 50)
        val emptyW = CameraRect(100, 100, 100, 200) // right == left
        val invertedH = CameraRect(0, 200, 100, 100) // bottom < top
        assertEquals(emptyW, CameraControlMath.clampMeteringRect(region, emptyW))
        assertEquals(invertedH, CameraControlMath.clampMeteringRect(region, invertedH))
    }

    @Test
    fun `valid bounds clamp the region inside`() {
        val bounds = CameraRect(0, 0, 100, 100)
        val outside = CameraRect(-20, -20, 250, 250)
        val r = CameraControlMath.clampMeteringRect(outside, bounds)
        assertTrue(r.left in 0..99 && r.top in 0..99)
        assertTrue(r.right in (r.left + 1)..100 && r.bottom in (r.top + 1)..100)
    }
}
