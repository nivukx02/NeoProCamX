package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawLensShadingGridPolicyTest {
    @Test
    fun identityGridIsNeutralAndHasNineRggbCells() {
        val grid = RawLensShadingGridPolicy.identityGrid()
        assertEquals(36, grid.size)
        assertArrayEquals(FloatArray(36) { 1f }, grid, 0f)
    }

    @Test
    fun resamplePreservesCornersCentreAndRggbOrder() {
        val grid = RawLensShadingGridPolicy.resample(rows = 3, columns = 3) { channel, column, row ->
            1f + channel * 0.1f + column * 0.01f + row * 0.001f
        }!!

        fun at(x: Int, y: Int, channel: Int) = grid[((y * 3 + x) * 4) + channel]
        assertEquals(1f, at(0, 0, 0), 0.00001f)
        assertEquals(1.111f, at(1, 1, 1), 0.00001f)
        assertEquals(1.322f, at(2, 2, 3), 0.00001f)
    }

    @Test
    fun invalidCalibrationFailsClosedToCallerIdentityFallback() {
        assertNull(RawLensShadingGridPolicy.resample(0, 3) { _, _, _ -> 1f })
        assertNull(RawLensShadingGridPolicy.resample(3, 3) { _, _, _ -> Float.NaN })
    }
}
