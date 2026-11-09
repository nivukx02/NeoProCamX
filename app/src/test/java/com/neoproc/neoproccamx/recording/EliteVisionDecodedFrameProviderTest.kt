package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f08 — decoded-frame provider contracts: the synthetic provider is non-null + deterministic
 * (CP-Q5), the unavailable provider yields null (honest NOT_PROVEN source).
 */
class EliteVisionDecodedFrameProviderTest {

    @Test
    fun deterministicSyntheticProviderYieldsNonNullGoldenFrames() {
        val frames = EliteVisionDecodedFrameProvider.DeterministicSynthetic.framesOrNull()
        assertNotNull(frames)
        assertTrue(EliteVisionDecodedFrameProvider.DeterministicSynthetic.canMeasureAllRequiredCriteria)
        assertEquals("SYNTHETIC", EliteVisionDecodedFrameProvider.DeterministicSynthetic.producerLabel)
    }

    @Test
    fun deterministicSyntheticProviderIsDeterministicAcrossCalls() {
        val a = EliteVisionDecodedFrameProvider.DeterministicSynthetic.framesOrNull()
        val b = EliteVisionDecodedFrameProvider.DeterministicSynthetic.framesOrNull()
        assertArrayEquals(a.edgeLuma, b.edgeLuma, 0f)
        assertArrayEquals(a.baseIsoFlatLuma, b.baseIsoFlatLuma, 0f)
        assertArrayEquals(a.highIsoFlatLuma, b.highIsoFlatLuma, 0f)
        assertEquals(a.edgeWidth, b.edgeWidth)
        assertEquals(a.referenceRawMtf50CyPerPx, b.referenceRawMtf50CyPerPx, 0.0)
    }

    @Test
    fun unavailableProviderReturnsNullForHonestNotProven() {
        assertNull(EliteVisionDecodedFrameProvider.Unavailable.framesOrNull())
        assertFalse(EliteVisionDecodedFrameProvider.Unavailable.canMeasureAllRequiredCriteria)
        assertEquals("NOT_AVAILABLE", EliteVisionDecodedFrameProvider.Unavailable.producerLabel)
    }
}
