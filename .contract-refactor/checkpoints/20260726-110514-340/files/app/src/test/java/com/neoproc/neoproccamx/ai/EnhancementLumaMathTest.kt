package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementLumaMathTest {
    @Test
    fun luminanceByteUsesSameScaleAsDenoiseAndSharpenThresholds() {
        assertEquals(255f, EnhancementLumaMath.luminanceByte(0xFFFFFFFF.toInt()), 0.01f)
        assertEquals(0f, EnhancementLumaMath.luminanceByte(0xFF000000.toInt()), 0.01f)

        val neutralGray = EnhancementLumaMath.luminanceByte(0xFF808080.toInt())
        assertTrue(neutralGray in 127f..129f)
    }
}
