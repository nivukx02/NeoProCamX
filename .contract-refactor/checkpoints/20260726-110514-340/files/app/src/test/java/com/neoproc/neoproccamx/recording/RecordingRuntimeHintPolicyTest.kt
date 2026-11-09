package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRuntimeHintPolicyTest {
    @Test
    fun `high fidelity sessions keep runtime bitrate hints in high-end mode`() {
        val decision = RecordingRuntimeHintPolicy.decide(
            width = 3840,
            height = 2160,
            hdrMode = "HDR10",
            tenBitEnabled = true,
            sceneMetadataDescription = ";log=true",
            activeOutputBitrate = 120_000_000,
            lastHintedBitrate = 100_000_000,
            nowMs = 10_000L,
            lastSyncFrameRequestUptimeMs = 0L,
            syncFrameBitrateDeltaRatio = 0.35f,
            syncFrameRequestIntervalMs = 12_000L
        )

        assertTrue(decision.highFidelitySession)
        assertTrue(decision.shouldPushBitrate)
        assertFalse(decision.shouldRequestSyncFrame)
    }

    @Test
    fun `unchanged high fidelity bitrate does not resend runtime hint`() {
        val decision = RecordingRuntimeHintPolicy.decide(
            width = 3840,
            height = 2160,
            hdrMode = "HDR10",
            tenBitEnabled = true,
            sceneMetadataDescription = ";log=false",
            activeOutputBitrate = 160_000_000,
            lastHintedBitrate = 160_000_000,
            nowMs = 20_000L,
            lastSyncFrameRequestUptimeMs = 0L,
            syncFrameBitrateDeltaRatio = 0.35f,
            syncFrameRequestIntervalMs = 12_000L
        )

        assertTrue(decision.highFidelitySession)
        assertFalse(decision.shouldPushBitrate)
        assertFalse(decision.shouldRequestSyncFrame)
    }

    @Test
    fun `large bitrate shift requests sync frame after interval`() {
        val decision = RecordingRuntimeHintPolicy.decide(
            width = 1920,
            height = 1080,
            hdrMode = "OFF",
            tenBitEnabled = false,
            sceneMetadataDescription = "",
            activeOutputBitrate = 80_000_000,
            lastHintedBitrate = 40_000_000,
            nowMs = 20_000L,
            lastSyncFrameRequestUptimeMs = 1_000L,
            syncFrameBitrateDeltaRatio = 0.35f,
            syncFrameRequestIntervalMs = 12_000L
        )

        assertTrue(decision.shouldPushBitrate)
        assertTrue(decision.shouldRequestSyncFrame)
    }

    @Test
    fun `small bitrate shift does not request sync frame`() {
        val decision = RecordingRuntimeHintPolicy.decide(
            width = 1920,
            height = 1080,
            hdrMode = "OFF",
            tenBitEnabled = false,
            sceneMetadataDescription = "",
            activeOutputBitrate = 105_000_000,
            lastHintedBitrate = 100_000_000,
            nowMs = 20_000L,
            lastSyncFrameRequestUptimeMs = 1_000L,
            syncFrameBitrateDeltaRatio = 0.35f,
            syncFrameRequestIntervalMs = 12_000L
        )

        assertFalse(decision.shouldPushBitrate)
        assertFalse(decision.shouldRequestSyncFrame)
    }
}
