package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [RawSensorMaxResCapabilityPolicy]: a max-res tier is only claimed when the un-binned
 * RAW size is materially larger than the default active-array RAW (rejecting HALs that echo the
 * same size in both configuration maps).
 */
class RawSensorMaxResCapabilityPolicyTest {

    private val active = CameraStreamSize(4080, 3072)   // ~12.5MP binned
    private val quadNative = CameraStreamSize(8160, 6144) // ~50MP un-binned (4x)

    @Test
    fun quadBayerNativeIsMeaningfullyAvailable() {
        val cap = RawSensorMaxResCapabilityPolicy.resolve(
            activeArrayRawSize = active,
            maxResMapRawSizes = listOf(quadNative)
        )
        assertTrue(cap.maxResAvailable)
        assertEquals(quadNative, cap.maxResRawSize)
        assertEquals(active, cap.activeArrayRawSize)
        assertTrue(cap.areaGainRatio >= 3.9)
    }

    @Test
    fun echoedSameSizeIsNotMeaningful() {
        val cap = RawSensorMaxResCapabilityPolicy.resolve(
            activeArrayRawSize = active,
            maxResMapRawSizes = listOf(active)
        )
        assertFalse(cap.maxResAvailable)
        assertNull(cap.maxResRawSize)
        assertEquals(1.0, cap.areaGainRatio, 1e-6)
    }

    @Test
    fun noMaxResMapMeansUnavailable() {
        val cap = RawSensorMaxResCapabilityPolicy.resolve(
            activeArrayRawSize = active,
            maxResMapRawSizes = emptyList()
        )
        assertFalse(cap.maxResAvailable)
        assertNull(cap.maxResRawSize)
        assertEquals(active, cap.activeArrayRawSize)
    }

    @Test
    fun picksLargestMaxResCandidate() {
        val cap = RawSensorMaxResCapabilityPolicy.resolve(
            activeArrayRawSize = active,
            maxResMapRawSizes = listOf(CameraStreamSize(6000, 4500), quadNative, CameraStreamSize(4080, 3072))
        )
        assertTrue(cap.maxResAvailable)
        assertEquals(quadNative, cap.maxResRawSize)
    }

    @Test
    fun justBelowThresholdIsRejected() {
        // 1.4x gain < MEANINGFUL_AREA_GAIN (1.5x).
        val small = CameraStreamSize(4800, 3480) // ~1.33x area of active
        val cap = RawSensorMaxResCapabilityPolicy.resolve(active, listOf(small))
        assertFalse(cap.maxResAvailable)
    }

    @Test
    fun zeroSizedEntriesAreIgnored() {
        val cap = RawSensorMaxResCapabilityPolicy.resolve(active, listOf(CameraStreamSize(0, 0), quadNative))
        assertTrue(cap.maxResAvailable)
        assertEquals(quadNative, cap.maxResRawSize)
    }
}
