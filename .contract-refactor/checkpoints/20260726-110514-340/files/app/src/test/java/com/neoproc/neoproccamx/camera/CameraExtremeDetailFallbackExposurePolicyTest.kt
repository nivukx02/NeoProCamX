package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraExtremeDetailFallbackExposurePolicyTest {

    @Test
    fun `extreme detail fallback trades shutter headroom for lower iso`() {
        val plan = CameraExtremeDetailFallbackExposurePolicy.resolve(
            active = true,
            supportsManualSensor = true,
            userManualIso = -1,
            userManualExposureNs = -1L,
            latestIso = 1600,
            latestExposureNs = 16_666_666L,
            frameDurationNs = 33_333_333L,
            exposureRange = CameraLongRange(100_000L, 100_000_000L),
            isoRange = CameraIntRange(50, 6400)
        )

        assertTrue(plan.activateManualExposure)
        assertEquals(800, plan.iso)
        assertEquals(33_333_333L, plan.exposureNs)
        assertEquals(800, plan.isoCeiling)
        assertEquals("EXTREME_DETAIL_CAMERA2_LOW_NOISE_LONG_SHUTTER", plan.reason)
    }

    @Test
    fun `extreme detail fallback preserves brightness when scene already needs full frame shutter`() {
        val plan = CameraExtremeDetailFallbackExposurePolicy.resolve(
            active = true,
            supportsManualSensor = true,
            userManualIso = -1,
            userManualExposureNs = -1L,
            latestIso = 3200,
            latestExposureNs = 33_333_333L,
            frameDurationNs = 33_333_333L,
            exposureRange = CameraLongRange(100_000L, 100_000_000L),
            isoRange = CameraIntRange(50, 6400)
        )

        assertTrue(plan.activateManualExposure)
        assertEquals(3200, plan.iso)
        assertEquals(33_333_333L, plan.exposureNs)
        assertEquals(800, plan.isoCeiling)
        assertEquals("EXTREME_DETAIL_CAMERA2_LOW_NOISE_BRIGHTNESS_PRESERVED", plan.reason)
    }

    @Test
    fun `extreme detail fallback does not recursively darken its own full shutter result`() {
        val plan = CameraExtremeDetailFallbackExposurePolicy.resolve(
            active = true,
            supportsManualSensor = true,
            userManualIso = -1,
            userManualExposureNs = -1L,
            latestIso = 1600,
            latestExposureNs = 40_000_000L,
            frameDurationNs = 40_000_000L,
            exposureRange = CameraLongRange(100_000L, 100_000_000L),
            isoRange = CameraIntRange(50, 6400)
        )

        assertTrue(plan.activateManualExposure)
        assertEquals(1600, plan.iso)
        assertEquals(40_000_000L, plan.exposureNs)
        assertEquals("EXTREME_DETAIL_CAMERA2_LOW_NOISE_BRIGHTNESS_PRESERVED", plan.reason)
    }

    @Test
    fun `extreme detail fallback does not override explicit manual exposure`() {
        val plan = CameraExtremeDetailFallbackExposurePolicy.resolve(
            active = true,
            supportsManualSensor = true,
            userManualIso = 400,
            userManualExposureNs = -1L,
            latestIso = 1600,
            latestExposureNs = 16_666_666L,
            frameDurationNs = 33_333_333L,
            exposureRange = CameraLongRange(100_000L, 100_000_000L),
            isoRange = CameraIntRange(50, 6400)
        )

        assertFalse(plan.activateManualExposure)
        assertEquals("USER_MANUAL_SENSOR", plan.reason)
    }

    @Test
    fun `detail profile never overrides auto exposure while recording`() {
        assertFalse(
            CameraExtremeDetailFallbackExposurePolicy.isGateActive(
                videoRenderContractActive = true,
                videoRecordingRequestActive = true,
                meteredReady = false,
                rawVideoCaptureEnabled = false,
                encodedVideoStrict = true,
                extremeDetailProfile = true
            )
        )
    }

    @Test
    fun `detail profile never overrides auto exposure in preview`() {
        assertFalse(
            CameraExtremeDetailFallbackExposurePolicy.isGateActive(
                videoRenderContractActive = true,
                videoRecordingRequestActive = false,
                meteredReady = true,
                rawVideoCaptureEnabled = false,
                encodedVideoStrict = true,
                extremeDetailProfile = true
            )
        )
    }

    @Test
    fun `gate stays inactive for idle preview before metered values arrive to avoid cold-start pop`() {
        assertFalse(
            CameraExtremeDetailFallbackExposurePolicy.isGateActive(
                videoRenderContractActive = true,
                videoRecordingRequestActive = false,
                meteredReady = false,
                rawVideoCaptureEnabled = false,
                encodedVideoStrict = true,
                extremeDetailProfile = true
            )
        )
    }

    @Test
    fun `gate inactive off the video render contract and for raw or non-strict or non-extreme`() {
        // Not a video render surface (e.g. stills): never gated on.
        assertFalse(
            CameraExtremeDetailFallbackExposurePolicy.isGateActive(
                videoRenderContractActive = false,
                videoRecordingRequestActive = false,
                meteredReady = true,
                rawVideoCaptureEnabled = false,
                encodedVideoStrict = true,
                extremeDetailProfile = true
            )
        )
        // RAW video bypasses the processed exposure posture.
        assertFalse(
            CameraExtremeDetailFallbackExposurePolicy.isGateActive(
                videoRenderContractActive = true,
                videoRecordingRequestActive = true,
                meteredReady = true,
                rawVideoCaptureEnabled = true,
                encodedVideoStrict = true,
                extremeDetailProfile = true
            )
        )
        // Non-encoded-strict noise profile.
        assertFalse(
            CameraExtremeDetailFallbackExposurePolicy.isGateActive(
                videoRenderContractActive = true,
                videoRecordingRequestActive = true,
                meteredReady = true,
                rawVideoCaptureEnabled = false,
                encodedVideoStrict = false,
                extremeDetailProfile = true
            )
        )
        // Not the EXTREME_DETAIL profile.
        assertFalse(
            CameraExtremeDetailFallbackExposurePolicy.isGateActive(
                videoRenderContractActive = true,
                videoRecordingRequestActive = true,
                meteredReady = true,
                rawVideoCaptureEnabled = false,
                encodedVideoStrict = true,
                extremeDetailProfile = false
            )
        )
    }

    @Test
    fun `extreme detail fallback bootstraps low noise request before metered exposure exists`() {
        val plan = CameraExtremeDetailFallbackExposurePolicy.resolve(
            active = true,
            supportsManualSensor = true,
            userManualIso = -1,
            userManualExposureNs = -1L,
            latestIso = null,
            latestExposureNs = 0L,
            frameDurationNs = 33_333_333L,
            exposureRange = CameraLongRange(100_000L, 100_000_000L),
            isoRange = CameraIntRange(50, 6400)
        )

        assertTrue(plan.activateManualExposure)
        assertEquals(800, plan.iso)
        assertEquals(33_333_333L, plan.exposureNs)
        assertEquals("EXTREME_DETAIL_CAMERA2_LOW_NOISE_BOOTSTRAP", plan.reason)
    }
}
