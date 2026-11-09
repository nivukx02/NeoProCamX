package com.neoproc.neoproccamx.niv99.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99LensShadingMapPolicyTest {
    @Test
    fun requiredGainCount_acceptsExactCapacityWithoutChangingFourChannelLayout() {
        assertEquals(16, Niv99LensShadingMapPolicy.requiredGainCount(2, 2, 16))
        assertEquals(4 * 64 * 64, Niv99LensShadingMapPolicy.requiredGainCount(64, 64, 4 * 64 * 64))
    }

    @Test
    fun requiredGainCount_rejectsInvalidDimensionsOverflowAndInsufficientCapacity() {
        assertEquals(-1, Niv99LensShadingMapPolicy.requiredGainCount(0, 2, 16))
        assertEquals(-1, Niv99LensShadingMapPolicy.requiredGainCount(2, -1, 16))
        assertEquals(-1, Niv99LensShadingMapPolicy.requiredGainCount(3, 2, 23))
        assertEquals(-1, Niv99LensShadingMapPolicy.requiredGainCount(Int.MAX_VALUE, Int.MAX_VALUE, 16))
    }

    @Test
    fun containsOnlyPhysicalGains_checksOnlyTheCopiedPayload() {
        val payload = floatArrayOf(1f, 1.1f, 1.2f, 1.3f, Float.NaN)
        assertTrue(Niv99LensShadingMapPolicy.containsOnlyPhysicalGains(payload, 4))
        assertFalse(Niv99LensShadingMapPolicy.containsOnlyPhysicalGains(payload, 5))
    }

    @Test
    fun containsOnlyPhysicalGains_rejectsZeroNegativeAndInfiniteValues() {
        assertFalse(Niv99LensShadingMapPolicy.containsOnlyPhysicalGains(floatArrayOf(1f, 0f), 2))
        assertFalse(Niv99LensShadingMapPolicy.containsOnlyPhysicalGains(floatArrayOf(1f, -0.1f), 2))
        assertFalse(
            Niv99LensShadingMapPolicy.containsOnlyPhysicalGains(
                floatArrayOf(1f, Float.POSITIVE_INFINITY),
                2
            )
        )
    }
}
