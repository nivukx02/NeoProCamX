package com.neoproc.neoproccamx.cinema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaResolutionCatalogTest {

    @Test
    fun `catalog exposes sensor native through 8k tiers`() {
        val names = CinemaResolutionCatalog.resolutions.map { it.name }.toSet()

        assertTrue("NATIVE SENSOR" in names)
        assertTrue("8K" in names)
        assertTrue("6K" in names)
        assertTrue("5K" in names)
        assertTrue("4K" in names)
        assertTrue("2K" in names)
        assertTrue("1K" in names)
    }

    @Test
    fun `8k preset is present and preferred as highest non native tier`() {
        val preset = CinemaResolutionCatalog.getResolution("8K")
        assertNotNull(preset)
        assertEquals("8K", CinemaResolutionCatalog.getHighestResolution().name)
    }

    @Test
    fun `sensor max presets use generic sensor native display names`() {
        val sensorMax50 = CinemaResolutionCatalog.getResolution("50MP NATIVE")
        val sensorMax48 = CinemaResolutionCatalog.getResolution("48MP NATIVE")

        assertNotNull(sensorMax50)
        assertNotNull(sensorMax48)
        assertEquals("Sensor Max 8192x6144", sensorMax50?.displayName)
        assertEquals("Sensor Max 8000x6000", sensorMax48?.displayName)
    }
}

