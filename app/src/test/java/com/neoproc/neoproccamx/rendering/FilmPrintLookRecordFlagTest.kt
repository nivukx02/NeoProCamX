package com.neoproc.neoproccamx.rendering

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmPrintLookRecordFlagTest {

    @After
    fun tearDown() {
        FilmPrintLookRecordFlag.registerOnChanged(null)
        FilmPrintLookRecordFlag.set(true)
    }

    @Test
    fun `default is on so sdr recordings carry the film print look`() {
        assertTrue(FilmPrintLookRecordFlag.enabled)
    }

    @Test
    fun `set toggles and fires onChanged only on a real change`() {
        var count = 0
        FilmPrintLookRecordFlag.registerOnChanged { count++ }
        FilmPrintLookRecordFlag.set(true) // no change from default
        assertEquals(0, count)
        FilmPrintLookRecordFlag.set(false)
        assertFalse(FilmPrintLookRecordFlag.enabled)
        assertEquals(1, count)
        FilmPrintLookRecordFlag.set(false) // no change
        assertEquals(1, count)
        FilmPrintLookRecordFlag.set(true)
        assertTrue(FilmPrintLookRecordFlag.enabled)
        assertEquals(2, count)
    }

    @Test
    fun `record LUT is a 33-cube in the creative-LUT packing with display-referred channels`() {
        val (rgb, size) = FilmPrintLookRecordFlag.recordLut
        assertEquals(33, size)
        assertEquals(size * size * size * 3, rgb.size)
        assertTrue("film-print LUT channels must be in [0,1]", rgb.all { it in 0f..1f })
    }
}
