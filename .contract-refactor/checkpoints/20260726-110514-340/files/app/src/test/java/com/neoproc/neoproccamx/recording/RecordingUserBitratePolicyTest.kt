package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingUserBitratePolicyTest {
    @Test
    fun `1080p master request uses strict high end floor instead of legacy low ceiling`() {
        assertEquals(
            420_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 90_000_000,
                minimumBitrateFloor = 18_000_000,
                encoderCapabilityUpperBound = null,
                width = 1920,
                height = 1080,
                cinemaMasteringEnabled = true
            )
        )
    }

    @Test
    fun `1080p ordinary request keeps high end automatic floor for all modes`() {
        assertEquals(
            420_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 90_000_000,
                minimumBitrateFloor = 18_000_000,
                encoderCapabilityUpperBound = null,
                width = 1920,
                height = 1080,
                cinemaMasteringEnabled = false
            )
        )
    }

    @Test
    fun `four k master request uses strict high end floor instead of legacy low ceiling`() {
        assertEquals(
            1_000_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 180_000_000,
                minimumBitrateFloor = 40_000_000,
                encoderCapabilityUpperBound = null,
                width = 3840,
                height = 2160,
                cinemaMasteringEnabled = true
            )
        )
    }

    @Test
    fun `four k ordinary request keeps high end automatic floor for all modes`() {
        assertEquals(
            1_000_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 180_000_000,
                minimumBitrateFloor = 40_000_000,
                encoderCapabilityUpperBound = null,
                width = 3840,
                height = 2160,
                cinemaMasteringEnabled = false
            )
        )
    }

    @Test
    fun `eight k master request uses strict high end floor instead of legacy low ceiling`() {
        assertEquals(
            2_000_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 260_000_000,
                minimumBitrateFloor = 100_000_000,
                encoderCapabilityUpperBound = null,
                width = 7680,
                height = 4320,
                cinemaMasteringEnabled = true
            )
        )
    }

    @Test
    fun `eight k ordinary request keeps high end automatic floor for all modes`() {
        assertEquals(
            2_000_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 260_000_000,
                minimumBitrateFloor = 100_000_000,
                encoderCapabilityUpperBound = null,
                width = 7680,
                height = 4320,
                cinemaMasteringEnabled = false
            )
        )
    }

    @Test
    fun `strict auto request honors advertised encoder ceiling instead of impossible floor`() {
        assertEquals(
            32_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 45_000_000,
                minimumBitrateFloor = 18_000_000,
                qualityBudgetFloor = 24_000_000,
                encoderCapabilityUpperBound = 32_000_000,
                width = 1920,
                height = 1080,
                cinemaMasteringEnabled = true
            )
        )
    }

    @Test
    fun `manual 1080p custom request preserves user target when encoder advertises support`() {
        assertEquals(
            180_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 180_000_000,
                minimumBitrateFloor = 1_000_000,
                encoderCapabilityUpperBound = 240_000_000,
                width = 1920,
                height = 1080,
                manualSelectionEnabled = true
            )
        )
    }

    @Test
    fun `manual request reports applied encoder cap when target exceeds advertised ceiling`() {
        assertEquals(
            72_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 180_000_000,
                minimumBitrateFloor = 1_000_000,
                encoderCapabilityUpperBound = 72_000_000,
                width = 1920,
                height = 1080,
                manualSelectionEnabled = true
            )
        )
    }

    @Test
    fun `auto request with high quality floor uses strict high end ceiling when encoder capability is unknown`() {
        assertEquals(
            420_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 20_000_000,
                minimumBitrateFloor = 80_000_000,
                qualityBudgetFloor = 220_000_000,
                encoderCapabilityUpperBound = null,
                width = 1920,
                height = 1080,
                cinemaMasteringEnabled = false
            )
        )
    }

    @Test
    fun `manual request without encoder report preserves user target under strict high end ceiling`() {
        assertEquals(
            200_000_000,
            RecordingUserBitratePolicy.resolveRequestedBitrate(
                requestedBitrate = 200_000_000,
                minimumBitrateFloor = 1_000_000,
                encoderCapabilityUpperBound = null,
                width = 1920,
                height = 1080,
                manualSelectionEnabled = true
            )
        )
    }

    @Test
    fun `camera view model requests the strict imax floor for cinema master`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()

        assertTrue(source.contains("strictImaxFloorBps"))
        assertTrue(source.contains("HighEndQualityPolicy.minimumBitrateBps"))
    }
}
