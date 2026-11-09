package com.neoproc.neoproccamx.quality

import com.neoproc.neoproccamx.recording.EliteVideoPipelineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s9-f14 (CP-QV14) — honesty proof for [CapabilityBadgePolicy]. The badge may advertise ONLY
 * capabilities the [OmegaCapabilityGate] certifies as SUPPORTED, and it must never fabricate an
 * audio / 48kHz claim (the gate has no audio [OmegaCapabilityId]). Every report here is built from
 * a real [OmegaCapabilityGate.evaluate] over a real [OmegaCapabilityInput].
 */
class CapabilityBadgeHonestyTest {

    @Test
    fun nullReportProducesEmptyBadge() {
        assertEquals("", CapabilityBadgePolicy.badgeFor(null))
        assertEquals("", CapabilityBadgePolicy.badgeFor(null, encodedBitDepth = 10))
    }

    @Test
    fun sdrReportAdvertisesNoNeoTransportOrGpuTokens() {
        val report = OmegaCapabilityGate.evaluate(sdrInput())
        val badge = CapabilityBadgePolicy.badgeFor(report)

        // None of the Neo-transport / HDR / GPU / RAW ids are SUPPORTED in a plain SDR run.
        assertFalse("SDR badge must not claim NDR", badge.contains("NDR"))
        assertFalse("SDR badge must not claim HDR10", badge.contains("HDR10"))
        assertFalse("SDR badge must not claim HEVC", badge.contains("HEVC"))
        assertFalse("SDR badge must not claim Vulkan", badge.contains("Vulkan"))
        assertFalse("SDR badge must not claim RAW", badge.contains("RAW"))
    }

    @Test
    fun proven10BitHdrBadgeContainsBitDepthHevcAndHdr10() {
        val report = OmegaCapabilityGate.evaluate(provenHdrInput())
        // Guard the premise: the gate really did certify the Neo HDR transport.
        assertEquals(
            OmegaCapabilityStatus.SUPPORTED,
            report.statusOf(OmegaCapabilityId.NDR_NEO_TRANSPORT)
        )

        val badge = CapabilityBadgePolicy.badgeFor(report)
        assertTrue("Proven HDR badge must advertise 10-bit HEVC, was: $badge", badge.contains("10-bit HEVC"))
        assertTrue("Proven HDR badge must advertise HDR10, was: $badge", badge.contains("HDR10"))
    }

    @Test
    fun badgeNeverContainsAudioOrF48kHzToken() {
        // The gate has no audio capability id, so no report can ever authorize an audio token.
        val reports = listOf(
            OmegaCapabilityGate.evaluate(sdrInput()),
            OmegaCapabilityGate.evaluate(provenHdrInput()),
            OmegaCapabilityGate.evaluate(provenRawVulkanInput())
        )
        reports.forEach { report ->
            val badge = CapabilityBadgePolicy.badgeFor(report, encodedBitDepth = 10)
            assertFalse("Badge must never claim 48kHz, was: $badge", badge.contains("48kHz"))
            assertFalse("Badge must never claim audio, was: $badge", badge.contains("Audio", ignoreCase = true))
            assertFalse("Badge must never claim kHz, was: $badge", badge.contains("kHz"))
        }
    }

    @Test
    fun everyEmittedTokenIsBackedBySupportedCapability() {
        val report = OmegaCapabilityGate.evaluate(provenRawVulkanInput())
        val badge = CapabilityBadgePolicy.badgeFor(report)

        // Vulkan and RAW are the only proven ids here → those tokens are allowed.
        val vulkanSupported =
            report.statusOf(OmegaCapabilityId.VULKAN_RECORDING_PATH) == OmegaCapabilityStatus.SUPPORTED
        val rawSupported =
            report.statusOf(OmegaCapabilityId.RAW_SENSOR_TRUTH) == OmegaCapabilityStatus.SUPPORTED
        assertTrue("premise: Vulkan must be SUPPORTED for this fixture", vulkanSupported)

        // A Vulkan token may appear only when Vulkan is SUPPORTED.
        if (badge.contains("Vulkan")) assertTrue(vulkanSupported)
        // A RAW token may appear only when RAW is SUPPORTED.
        if (badge.contains("RAW")) assertTrue(rawSupported)

        // The Neo-transport ids are all NOT_AVAILABLE in RAW mode, so no HEVC/HDR10 token may leak.
        val anyNeoSupported = listOf(
            OmegaCapabilityId.NDR_NEO_TRANSPORT,
            OmegaCapabilityId.NIVUKX_NEO_TRANSPORT,
            OmegaCapabilityId.N_LOG_SDR_TRUTH
        ).any { report.statusOf(it) == OmegaCapabilityStatus.SUPPORTED }
        assertFalse("premise: no Neo transport is SUPPORTED in RAW mode", anyNeoSupported)
        assertFalse("HEVC token requires a SUPPORTED Neo transport, was: $badge", badge.contains("HEVC"))
        assertFalse("HDR10 token requires a SUPPORTED Neo transport, was: $badge", badge.contains("HDR10"))
    }

    private companion object {

        /** A plain, honest SDR Rec.709 run: no HDR, no proven GPU path, not RAW. */
        private fun sdrInput() = OmegaCapabilityInput(
            mode = EliteVideoPipelineMode.NORMAL,
            selectedProfile = "SDR REC709",
            activeTransport = "STANDARD",
            transfer = "BT.709",
            primaries = "BT.709",
            matrix = "BT.709",
            codec = "hevc",
            encodedBitDepth = 8,
            internalMathBitDepth = 8,
            sourceBitDepth = 8,
            storageBitDepth = 8,
            width = 1920,
            height = 1080,
            fps = 30,
            bitrateBps = 20_000_000,
            selectedResolution = "1920x1080",
            actualResolution = "1920x1080",
            rawSensorOriginal = false,
            ffprobeValidated = false,
            mediaStoreValidated = false,
            deviceLogValidated = false,
            gpuProcessingValidated = false,
            gpuProcessingPath = "CPU_FALLBACK"
        )

        /** A proven 10-bit Neo HDR (NDR) run: BT.2020/PQ, 10-bit everywhere, NDR token present. */
        private fun provenHdrInput() = OmegaCapabilityInput(
            mode = EliteVideoPipelineMode.HDR,
            selectedProfile = "NDR10+ NEO",
            activeTransport = "NDR_PQ_MAIN10",
            transfer = "ST2084/PQ",
            primaries = "BT.2020",
            matrix = "BT.2020",
            codec = "hevc",
            encodedBitDepth = 10,
            internalMathBitDepth = 12,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            width = 3840,
            height = 2160,
            fps = 30,
            bitrateBps = 120_000_000,
            selectedResolution = "3840x2160",
            actualResolution = "3840x2160",
            rawSensorOriginal = false,
            ffprobeValidated = true,
            mediaStoreValidated = true,
            deviceLogValidated = true,
            gpuProcessingValidated = false,
            gpuProcessingPath = "CPU_FALLBACK"
        )

        /** A proven RAW-sensor + Vulkan run: RAW mode, 12-bit original sensor, proven Vulkan path. */
        private fun provenRawVulkanInput() = OmegaCapabilityInput(
            mode = EliteVideoPipelineMode.RAW,
            selectedProfile = "RAW SENSOR",
            activeTransport = "RAW",
            transfer = "LINEAR",
            primaries = "SENSOR",
            matrix = "SENSOR",
            codec = "dng",
            encodedBitDepth = 12,
            internalMathBitDepth = 12,
            sourceBitDepth = 12,
            storageBitDepth = 12,
            width = 4032,
            height = 3024,
            fps = 30,
            bitrateBps = 0,
            selectedResolution = "4032x3024",
            actualResolution = "4032x3024",
            rawSensorOriginal = true,
            ffprobeValidated = true,
            mediaStoreValidated = true,
            deviceLogValidated = true,
            gpuProcessingValidated = true,
            gpuProcessingPath = "VULKAN_COMPUTE"
        )
    }
}
