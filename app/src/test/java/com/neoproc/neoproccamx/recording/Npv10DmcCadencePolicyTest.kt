package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10DmcCadencePolicyTest {
    @Test
    fun `4k class accepts exact 25 fps without rewriting it`() {
        listOf(3840 to 2160, 4096 to 1716, 4096 to 3072).forEach { (width, height) ->
            val admission = Npv10DmcCadencePolicy.evaluate(width, height, 25)
            assertTrue(admission.accepted)
            assertTrue(admission.fourKClass)
            assertEquals(25, admission.requestedFps)
            assertEquals(25, admission.maximumFps)
        }
    }

    @Test
    fun `4k class rejects rather than silently reducing a higher selection`() {
        val admission = Npv10DmcCadencePolicy.evaluate(4096, 3072, 60)

        assertFalse(admission.accepted)
        assertEquals(60, admission.requestedFps)
        assertTrue(admission.failureReason.contains("maximum is 25 FPS"))
    }

    @Test
    fun `lower resolutions preserve every selected cadence through 60 fps`() {
        listOf(24, 25, 30, 48, 50, 60).forEach { fps ->
            val admission = Npv10DmcCadencePolicy.evaluate(2048, 1080, fps)
            assertTrue("$fps FPS should be accepted", admission.accepted)
            assertEquals(fps, admission.requestedFps)
            assertEquals(60, admission.maximumFps)
        }
    }

    @Test
    fun `lower resolutions reject above 60 instead of clamping`() {
        val admission = Npv10DmcCadencePolicy.evaluate(1920, 1080, 61)

        assertFalse(admission.accepted)
        assertEquals(61, admission.requestedFps)
        assertTrue(admission.failureReason.contains("maximum is 60 FPS"))
    }
}
