package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCropEngineTest {

    @Test
    fun fullSensorAspect_canBeConfiguredFromRealSensorInsteadOfHardcodedFourThree() {
        try {
            SensorCropEngine.setFullSensorAspectRatio(20f / 9f)

            assertEquals(20f / 9f, SensorCropEngine.fullSensorAspectRatio(), 0.0001f)
            assertEquals(20f / 9f, SensorCropEngine.normalizeLongShortRatio(null), 0.0001f)
        } finally {
            SensorCropEngine.setFullSensorAspectRatio(null)
        }
    }

    @Test
    fun displayCrop_defaultsToConfiguredFullSensorAspectWhenFullModeHasNoExplicitRatio() {
        try {
            SensorCropEngine.setFullSensorAspectRatio(3f / 2f)

            val crop = SensorCropEngine.centerCropDisplaySpace(
                sourceRatio = null,
                targetRatio = 16f / 9f
            )

            assertTrue(crop.width in 0f..1f)
            assertTrue(crop.height in 0f..1f)
            assertEquals(0f, crop.left, 0.0001f)
            assertEquals(1f, crop.right, 0.0001f)
        } finally {
            SensorCropEngine.setFullSensorAspectRatio(null)
        }
    }

    @Test
    fun displayCrop_withoutConfiguredSensorAspect_doesNotAssumeFourThree() {
        try {
            SensorCropEngine.setFullSensorAspectRatio(null)

            val crop = SensorCropEngine.centerCropDisplaySpace(
                sourceRatio = 20f / 9f,
                targetRatio = 16f / 9f
            )

            assertTrue(crop.width in 0f..1f)
            assertTrue(crop.height in 0f..1f)
            assertEquals(0f, crop.top, 0.0001f)
            assertEquals(1f, crop.bottom, 0.0001f)
        } finally {
            SensorCropEngine.setFullSensorAspectRatio(null)
        }
    }

    @Test
    fun displayAspectForBounds_returnsNullUntilViewportMeasured() {
        assertNull(SensorCropEngine.displayAspectForBounds(16f / 9f, width = 0, height = 0))
        assertNull(SensorCropEngine.displayAspectForBounds(16f / 9f, width = 1080, height = 0))
        assertNull(SensorCropEngine.displayAspectForBounds(16f / 9f, width = 0, height = 2400))
    }
}
