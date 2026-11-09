package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99ExactFrameJoinPolicyTest {
    @Test
    fun `25 fps join is bounded to its complete 40 millisecond sensor interval`() {
        assertEquals(40_000_000L, Niv99ExactFrameJoinPolicy.timeoutNs(25f))
    }

    @Test
    fun `higher frame rates shorten the exact frame join`() {
        assertEquals(33_333_334L, Niv99ExactFrameJoinPolicy.timeoutNs(30f))
        assertEquals(16_666_667L, Niv99ExactFrameJoinPolicy.timeoutNs(60f))
    }

    @Test
    fun `join remains bounded for extreme or invalid cadence`() {
        assertEquals(12_000_000L, Niv99ExactFrameJoinPolicy.timeoutNs(120f))
        assertEquals(40_000_000L, Niv99ExactFrameJoinPolicy.timeoutNs(1f))
        assertEquals(40_000_000L, Niv99ExactFrameJoinPolicy.timeoutNs(Float.NaN))
        assertEquals(40_000_000L, Niv99ExactFrameJoinPolicy.timeoutNs(0f))
    }

    @Test
    fun `clock alignment accepts only same-frame translation skew`() {
        assertEquals(2_000_000L, Niv99ExactFrameJoinPolicy.timestampAlignmentNs(25f))
        assertTrue(
            Niv99ExactFrameJoinPolicy.isSamePhysicalFrame(
                imageTimestampNs = 40_000_317L,
                metadataTimestampNs = 40_000_000L,
                targetFps = 25f
            )
        )
        assertFalse(
            Niv99ExactFrameJoinPolicy.isSamePhysicalFrame(
                imageTimestampNs = 43_000_000L,
                metadataTimestampNs = 40_000_000L,
                targetFps = 25f
            )
        )
    }

    @Test
    fun `preview exact rendezvous leaves render budget inside the current sensor interval`() {
        assertEquals(28_000_000L, Niv99PreviewFrameJoinPolicy.timeoutNs(25f))
        assertEquals(23_333_334L, Niv99PreviewFrameJoinPolicy.timeoutNs(30f))
        assertEquals(11_666_667L, Niv99PreviewFrameJoinPolicy.timeoutNs(60f))
    }

    @Test
    fun `preview rendezvous remains latency bounded at extreme or invalid cadence`() {
        assertEquals(8_000_000L, Niv99PreviewFrameJoinPolicy.timeoutNs(120f))
        assertEquals(28_000_000L, Niv99PreviewFrameJoinPolicy.timeoutNs(1f))
        assertEquals(28_000_000L, Niv99PreviewFrameJoinPolicy.timeoutNs(Float.NaN))
        assertEquals(28_000_000L, Niv99PreviewFrameJoinPolicy.timeoutNs(0f))
    }

    @Test
    fun `preview metadata wakes only its exact held frame`() {
        assertEquals(true, Niv99PreviewMetadataWakePolicy.isExactWake(123L, 123L))
        assertEquals(false, Niv99PreviewMetadataWakePolicy.isExactWake(123L, 122L))
        assertEquals(false, Niv99PreviewMetadataWakePolicy.isExactWake(0L, 123L))
        assertEquals(false, Niv99PreviewMetadataWakePolicy.isExactWake(123L, 0L))
    }

    @Test
    fun `preview deadline fallback rounds up and never busy polls`() {
        assertEquals(80L, Niv99PreviewMetadataWakePolicy.deadlineDelayMs(10L, 79_000_011L))
        assertEquals(40L, Niv99PreviewMetadataWakePolicy.deadlineDelayMs(1_000_000L, 41_000_000L))
        assertEquals(1L, Niv99PreviewMetadataWakePolicy.deadlineDelayMs(41_000_000L, 41_000_000L))
    }

    @Test
    fun `copied frame rendezvous spans four sensor intervals without extending image ownership`() {
        assertEquals(160_000_000L, Niv99DeferredMetadataJoinPolicy.timeoutNs(25f))
        assertEquals(133_333_336L, Niv99DeferredMetadataJoinPolicy.timeoutNs(30f))
        assertEquals(66_666_668L, Niv99DeferredMetadataJoinPolicy.timeoutNs(60f))
    }

    @Test
    fun `copied frame rendezvous remains independently bounded`() {
        assertEquals(48_000_000L, Niv99DeferredMetadataJoinPolicy.timeoutNs(120f))
        assertEquals(160_000_000L, Niv99DeferredMetadataJoinPolicy.timeoutNs(1f))
        assertEquals(160_000_000L, Niv99DeferredMetadataJoinPolicy.timeoutNs(Float.NaN))
        assertEquals(160_000_000L, Niv99DeferredMetadataJoinPolicy.timeoutNs(0f))
    }
}
