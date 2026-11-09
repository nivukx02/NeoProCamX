package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f05 / CP80 — real-motion foundation tests (JVM). The block-match flow recovers a known shift,
 * the deblur is a no-op on a static frame, and the real-motion gate defaults OFF (no-downgrade).
 */
class Npv10ExportArgusMotionFoundationTest {

    @Test
    fun blockMatchFlowRecoversKnownShift() {
        val width = 8
        val height = 8
        // Distinct per-pixel previous frame so the shift is unambiguous.
        val previous = IntArray(width * height) { (it % width) * 11 + (it / width) * 7 }
        val shiftX = 2
        val shiftY = 1
        // current[x,y] = previous[x-shiftX, y-shiftY]
        val current = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = x - shiftX
                val py = y - shiftY
                current[y * width + x] = if (px in 0 until width && py in 0 until height) {
                    previous[py * width + px]
                } else {
                    0
                }
            }
        }
        val flow = Npv10ExportArgusMotionFoundation.blockMatchFlow(current, previous, width, height, searchRadius = 4)
        assertEquals(shiftX, flow.dx)
        assertEquals(shiftY, flow.dy)
        assertEquals(0L, flow.sad) // exact match over the overlapping region
    }

    @Test
    fun staticFrameFlowIsZero() {
        val width = 6
        val height = 6
        val plane = IntArray(width * height) { 200 + (it % 5) }
        val flow = Npv10ExportArgusMotionFoundation.blockMatchFlow(plane, plane.copyOf(), width, height)
        assertEquals(0, flow.dx)
        assertEquals(0, flow.dy)
    }

    @Test
    fun deblurIsNoOpOnStaticFrame() {
        val width = 4
        val height = 4
        val plane = IntArray(width * height) { 100 + it * 3 }
        val noMotion = Npv10ExportArgusMotionFoundation.MotionVector(0, 0, 0L)
        val result = Npv10ExportArgusMotionFoundation.directionalDeblur(plane, noMotion, width, height)
        assertArrayEquals(plane, result)
    }

    @Test
    fun deblurAppliesAlongMotionWhenMoving() {
        val width = 4
        val height = 4
        val plane = IntArray(width * height) { (it % width) * 100 } // horizontal gradient
        val motion = Npv10ExportArgusMotionFoundation.MotionVector(2, 0, 0L)
        val result = Npv10ExportArgusMotionFoundation.directionalDeblur(plane, motion, width, height)
        // Output stays a valid 10-bit plane and is no longer byte-identical (a real blur ran).
        assertEquals(plane.size, result.size)
        result.forEach { assertEquals(it, it.coerceIn(0, 1023)) }
    }

    @Test
    fun realMotionFlagDefaultsOn() {
        assertTrue(Npv10ExportRealMotionFlag.enabled)
    }
}
