package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSourceResolutionPolicyTest {

    @Test
    fun `sensor native open gate plans use sensor native tier label`() {
        val maxSensor = RecordingSourceResolutionPolicy.resolve(8192, 6144, 30)
        val altSensor = RecordingSourceResolutionPolicy.resolve(8000, 6000, 30)
        val true8k = RecordingSourceResolutionPolicy.resolve(7680, 4320, 30)

        assertEquals("SENSOR_NATIVE", maxSensor.tierLabel)
        assertEquals("SENSOR_NATIVE", altSensor.tierLabel)
        assertEquals("8K_UHD", true8k.tierLabel)
    }
}
