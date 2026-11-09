package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AISubjectTrackingMathTest {

    @Test
    fun initialTrackingRegion_createsCenteredBox() {
        val region = AISubjectTrackingMath.initialTrackingRegion(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            frameWidth = 4000,
            frameHeight = 3000
        )

        assertEquals(1775f, region.left, 0.001f)
        assertEquals(1275f, region.top, 0.001f)
        assertEquals(2225f, region.right, 0.001f)
        assertEquals(1725f, region.bottom, 0.001f)
    }

    @Test
    fun searchRange_expandsWithMissCount() {
        assertEquals(28, AISubjectTrackingMath.searchRange(28, 0))
        assertEquals(44, AISubjectTrackingMath.searchRange(28, 2))
    }

    @Test
    fun updateTrackingState_persistsSmoothedRegionOnGoodConfidence() {
        val update = AISubjectTrackingMath.updateTrackingState(
            region = TrackingRect(100f, 100f, 200f, 200f),
            velocityX = 10f,
            velocityY = 0f,
            frameWidth = 1000,
            frameHeight = 1000,
            bestDx = 4,
            bestDy = 6,
            confidence = 0.8f,
            smoothAlpha = 0.34f,
            clearThreshold = 0.22f,
            maxMisses = 4,
            missCount = 0
        )

        assertFalse(update.shouldClear)
        assertEquals(update.displayRegion, update.persistedRegion)
        assertEquals(104.76f, update.displayRegion.left, 0.001f)
        assertEquals(102.04f, update.displayRegion.top, 0.001f)
        assertEquals(10f * 0.45f + 14f * 0.55f, update.velocityX, 0.001f)
    }

    @Test
    fun updateTrackingState_clearsAfterTooManyMisses() {
        val update = AISubjectTrackingMath.updateTrackingState(
            region = TrackingRect(100f, 100f, 200f, 200f),
            velocityX = 8f,
            velocityY = 6f,
            frameWidth = 1000,
            frameHeight = 1000,
            bestDx = 0,
            bestDy = 0,
            confidence = 0.1f,
            smoothAlpha = 0.34f,
            clearThreshold = 0.22f,
            maxMisses = 4,
            missCount = 3
        )

        assertTrue(update.shouldClear)
        assertNull(update.persistedRegion)
        assertEquals(4, update.missCount)
        assertEquals(4f, update.velocityX, 0.001f)
        assertEquals(3f, update.velocityY, 0.001f)
    }
}
