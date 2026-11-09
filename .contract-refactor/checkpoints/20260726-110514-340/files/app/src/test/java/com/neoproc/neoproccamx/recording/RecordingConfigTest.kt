package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingConfigTest {

    @Test
    fun `sensor native resolutions use sensor native label instead of fixed megapixel branding`() {
        val maxSensor = RecordingConfig(widthPx = 8192, heightPx = 6144)
        val altSensor = RecordingConfig(widthPx = 8000, heightPx = 6000)

        assertEquals("Sensor Native", maxSensor.resolutionLabel)
        assertEquals("Sensor Native", altSensor.resolutionLabel)
    }
}
