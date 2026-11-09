package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFocusControlSignalTest {
    @Test
    fun `distance confidence and generation publish atomically`() {
        val signal = AiFocusControlSignal.create(2.5f, 0.73f, 41L)
        assertEquals(2.5f, signal.distanceDiopters)
        assertEquals(0.73f, signal.confidence)
        assertTrue(signal.isCurrent(41L))
        assertFalse(signal.isCurrent(42L))
    }

    @Test
    fun `invalid or confidence-free publications fail closed`() {
        assertEquals(AiFocusControlSignal.NONE, AiFocusControlSignal.create(Float.NaN, 0.8f, 1L))
        assertEquals(AiFocusControlSignal.NONE, AiFocusControlSignal.create(1f, 0f, 1L))
        assertEquals(AiFocusControlSignal.NONE, AiFocusControlSignal.create(1f, 0.8f, -1L))
    }

    @Test
    fun `confidence is bounded before control admission`() {
        val signal = AiFocusControlSignal.create(1f, 2f, 1L)
        assertEquals(1f, signal.confidence)
        assertTrue(signal.isCurrent(1L))
    }
}
