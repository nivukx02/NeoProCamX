package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSourceResolutionPolicyTest {

    @Test
    fun resolvePlan_keepsWidescreen4kVideoOnDeliveryMatchedReadout() {
        val plan = RecordingSourceResolutionPolicy.resolveSensorBackedReadout(
            requestedWidth = 3840,
            requestedHeight = 2160,
            pixelArrayWidth = 8160,
            pixelArrayHeight = 6144,
            maxRawWidth = 4624,
            maxRawHeight = 3472,
            fallbackSensorWidth = 4032,
            fallbackSensorHeight = 3024
        )

        assertFalse(plan.fullSensorReadoutForced)
        assertFalse(plan.appliesCineSourcePolicy)
        assertEquals("YUV_420_888_4K", plan.deliveryFormat)
        assertEquals(50.135f, plan.fullSensorMegapixels, 0.01f)
        assertTrue(plan.preferredReadoutMegapixels >= 8.29f)
    }

    @Test
    fun resolvePlan_allowsFullSensorWhenVideoDeliveryMatchesSensorReadout() {
        val plan = RecordingSourceResolutionPolicy.resolveSensorBackedReadout(
            requestedWidth = 4096,
            requestedHeight = 3072,
            pixelArrayWidth = 4096,
            pixelArrayHeight = 3072,
            maxRawWidth = 4096,
            maxRawHeight = 3072,
            fallbackSensorWidth = 4096,
            fallbackSensorHeight = 3072
        )

        assertTrue(plan.fullSensorReadoutForced)
        assertTrue(plan.appliesCineSourcePolicy)
        assertEquals("FULL_SENSOR_READOUT", plan.deliveryFormat)
        assertEquals(plan.fullSensorMegapixels, plan.preferredReadoutMegapixels, 0.0001f)
    }

    @Test
    fun resolvePlan_keepsLowerResolutionEncodedVideoOnDeliveryReadoutForStableFps() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        val previousFullSensor = HighEndQualityPolicy.forceFullSensorReadout
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            HighEndQualityPolicy.forceFullSensorReadout = true

            val plan = RecordingSourceResolutionPolicy.resolveSensorBackedReadout(
                requestedWidth = 1920,
                requestedHeight = 1080,
                pixelArrayWidth = 8000,
                pixelArrayHeight = 6000,
                maxRawWidth = 8000,
                maxRawHeight = 6000,
                fallbackSensorWidth = 8000,
                fallbackSensorHeight = 6000
            )

            assertFalse(plan.fullSensorReadoutForced)
            assertEquals("YUV_420_888", plan.deliveryFormat)
            assertEquals(1920, plan.requestedWidth)
            assertEquals(1080, plan.requestedHeight)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
            HighEndQualityPolicy.forceFullSensorReadout = previousFullSensor
        }
    }
}
