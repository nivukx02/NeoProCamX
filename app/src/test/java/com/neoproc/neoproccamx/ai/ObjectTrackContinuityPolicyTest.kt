package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f06 / CP96 — pure continuity helpers. Hand-verifiable. */
class ObjectTrackContinuityPolicyTest {

    @Test
    fun centroidDistanceIsEuclidean() {
        assertEquals(0.5f, ObjectTrackContinuityPolicy.centroidDistance(0f, 0f, 0.3f, 0.4f), 1e-6f)
        assertEquals(0f, ObjectTrackContinuityPolicy.centroidDistance(0.2f, 0.2f, 0.2f, 0.2f), 0f)
    }

    @Test
    fun centroidBridgeRespectsRadius() {
        assertTrue(ObjectTrackContinuityPolicy.isCentroidBridge(0.10f, 0.12f))
        assertFalse(ObjectTrackContinuityPolicy.isCentroidBridge(0.20f, 0.12f))
        assertEquals(0.12f, ObjectTrackContinuityPolicy.bridgeRadius(strictHighEnd = false), 0f)
        assertEquals(0.10f, ObjectTrackContinuityPolicy.bridgeRadius(strictHighEnd = true), 0f)
    }

    @Test
    fun classicalFallbackIsHonestlyClassified() {
        assertTrue(ObjectTrackContinuityPolicy.isClassicalFallback("chroma_fallback"))
        assertTrue(ObjectTrackContinuityPolicy.isClassicalFallback("salient_fallback"))
        assertFalse(ObjectTrackContinuityPolicy.isClassicalFallback("person"))
        assertFalse(ObjectTrackContinuityPolicy.isClassicalFallback("car"))
    }
}
