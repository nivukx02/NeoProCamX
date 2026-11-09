package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualGainHdrFusionPolicyTest {

    @Test
    fun defaultRequestIsOnButFailsClosedWithoutSynchronizedHardwarePair() {
        val plan = DualGainHdrFusionPolicy.resolve(DualGainHdrFusionRequest())

        assertTrue(DualGainHdrFusionPolicy.DEFAULT_REQUESTED)
        assertEquals(DualGainHdrFusionPath.EXISTING_HDR_MERGE_FALLBACK, plan.path)
        assertFalse(plan.supported)
        assertEquals("PUBLIC_SYNCHRONIZED_DUAL_GAIN_NOT_AVAILABLE", plan.fallbackReason)
    }

    @Test
    fun synchronizedPublicDualGainBuffersEnableHardwareFusion() {
        val plan = DualGainHdrFusionPolicy.resolve(
            DualGainHdrFusionRequest(
                enabled = true,
                publicSynchronizedDualGainAvailable = true,
                privateExtensionRequested = false,
                lcgFrame = frame(timestampNs = 1_000_000_000L),
                hcgFrame = frame(timestampNs = 1_000_000_500L),
                kGain = 8.0f,
                noiseFloor = 0.012f,
                saturationThreshold = 0.92f,
                existingHdrMergeFallbackAvailable = true
            )
        )

        assertEquals(DualGainHdrFusionPath.HARDWARE_SYNCHRONIZED_DUAL_GAIN, plan.path)
        assertTrue(plan.supported)
        assertTrue(plan.motionMaskEnabled)
        assertTrue(plan.feedsAcesColorEngine)
        assertTrue(plan.feedsDenoiseMotionConfidence)
        assertTrue(plan.signature.contains("PUBLIC_CAMERA2_DUAL_GAIN"))
        assertFalse(plan.signature.contains("Sony", ignoreCase = true))
        assertFalse(plan.signature.contains("OmniVision", ignoreCase = true))
        assertFalse(plan.signature.contains("Dolby", ignoreCase = true))
    }

    @Test
    fun privateSensorExtensionRequestFallsBackWithoutVendorClaim() {
        val plan = DualGainHdrFusionPolicy.resolve(
            DualGainHdrFusionRequest(
                enabled = true,
                publicSynchronizedDualGainAvailable = true,
                privateExtensionRequested = true,
                lcgFrame = frame(timestampNs = 10_000L),
                hcgFrame = frame(timestampNs = 10_000L),
                kGain = 8.0f,
                noiseFloor = 0.012f,
                saturationThreshold = 0.92f,
                existingHdrMergeFallbackAvailable = true
            )
        )

        assertEquals(DualGainHdrFusionPath.EXISTING_HDR_MERGE_FALLBACK, plan.path)
        assertFalse(plan.supported)
        assertEquals("PRIVATE_EXTENSION_REJECTED_PUBLIC_CAMERA2_ONLY", plan.fallbackReason)
        assertFalse(plan.signature.contains("Sony", ignoreCase = true))
        assertFalse(plan.signature.contains("OmniVision", ignoreCase = true))
    }

    @Test
    fun timestampMismatchFallsBackToExistingHdrMerge() {
        val plan = DualGainHdrFusionPolicy.resolve(
            DualGainHdrFusionRequest(
                enabled = true,
                publicSynchronizedDualGainAvailable = true,
                privateExtensionRequested = false,
                lcgFrame = frame(timestampNs = 1_000_000_000L),
                hcgFrame = frame(timestampNs = 1_004_500_000L),
                kGain = 8.0f,
                noiseFloor = 0.012f,
                saturationThreshold = 0.92f,
                existingHdrMergeFallbackAvailable = true
            )
        )

        assertEquals(DualGainHdrFusionPath.EXISTING_HDR_MERGE_FALLBACK, plan.path)
        assertFalse(plan.supported)
        assertTrue(plan.fallbackReason.startsWith("DUAL_GAIN_TIMESTAMP_SKEW_TOO_HIGH"))
        assertEquals(4_500_000L, plan.timestampSkewNs)
    }

    @Test
    fun timestampSkewAboveOneMicrosecondFallsBackBeforeFusion() {
        val plan = DualGainHdrFusionPolicy.resolve(
            DualGainHdrFusionRequest(
                enabled = true,
                publicSynchronizedDualGainAvailable = true,
                privateExtensionRequested = false,
                lcgFrame = frame(timestampNs = 1_000_000_000L),
                hcgFrame = frame(timestampNs = 1_000_001_001L),
                kGain = 8.0f,
                noiseFloor = 0.012f,
                saturationThreshold = 0.92f,
                existingHdrMergeFallbackAvailable = true
            )
        )

        assertEquals(DualGainHdrFusionPath.EXISTING_HDR_MERGE_FALLBACK, plan.path)
        assertFalse(plan.supported)
        assertEquals(1_001L, plan.timestampSkewNs)
        assertTrue(plan.fallbackReason.startsWith("DUAL_GAIN_TIMESTAMP_SKEW_TOO_HIGH"))
        assertTrue(plan.signature.contains("opticalFlowPreWarp=true"))
    }

    @Test
    fun invalidGainCoefficientFailsClosed() {
        val plan = DualGainHdrFusionPolicy.resolve(
            DualGainHdrFusionRequest(
                enabled = true,
                publicSynchronizedDualGainAvailable = true,
                privateExtensionRequested = false,
                lcgFrame = frame(timestampNs = 1L),
                hcgFrame = frame(timestampNs = 1L),
                kGain = 0.5f,
                noiseFloor = 0.012f,
                saturationThreshold = 0.92f,
                existingHdrMergeFallbackAvailable = true
            )
        )

        assertEquals(DualGainHdrFusionPath.EXISTING_HDR_MERGE_FALLBACK, plan.path)
        assertFalse(plan.supported)
        assertEquals("INVALID_SENSOR_GAIN_COEFFICIENT", plan.fallbackReason)
    }

    private fun frame(timestampNs: Long): DualGainHdrFrameMetadata =
        DualGainHdrFrameMetadata(
            timestampNs = timestampNs,
            width = 3840,
            height = 2160,
            format = "P010",
            exposureTimeNs = 8_333_333L,
            analogGain = 1.0f,
            hardwareBufferBacked = true
        )
}
