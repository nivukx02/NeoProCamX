package com.neoproc.neoproccamx.rendering

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullColorEngineRecordFlagTest {

    @After
    fun tearDown() {
        FullColorEngineRecordFlag.registerOnChanged(null)
        FullColorEngineRecordFlag.set(true)
    }

    @Test
    fun `default is on so recorded sdr uses the full color engine`() {
        assertTrue(FullColorEngineRecordFlag.enabled)
    }

    @Test
    fun `set toggles and fires onChanged only on a real change`() {
        var count = 0
        FullColorEngineRecordFlag.registerOnChanged { count++ }
        FullColorEngineRecordFlag.set(true)
        assertEquals(0, count)
        FullColorEngineRecordFlag.set(false)
        assertFalse(FullColorEngineRecordFlag.enabled)
        assertEquals(1, count)
        FullColorEngineRecordFlag.set(false)
        assertEquals(1, count)
        FullColorEngineRecordFlag.set(true)
        assertTrue(FullColorEngineRecordFlag.enabled)
        assertEquals(2, count)
    }
}
