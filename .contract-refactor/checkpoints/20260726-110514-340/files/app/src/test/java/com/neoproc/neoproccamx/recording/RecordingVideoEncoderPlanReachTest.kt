package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f02 reach gates: AV1 all-intra (CP70) and HEVC Main12 (CP71). Both default OFF; the default
 * (gate-off) path is proven identical to today, and the gated branches are honest (no false
 * profile / no false 12-bit claim).
 */
class RecordingVideoEncoderPlanReachTest {

    private fun bt2020Pq() = RecordingColorEncodingConfig(
        colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
        colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
        colorRange = MediaFormat.COLOR_RANGE_FULL,
        requiresTenBit = true,
        main10Requested = true
    )

    private fun plan(
        mime: String = "video/hevc",
        width: Int = 3840,
        height: Int = 2160,
        fps: Int = 60,
        tenBitEnabled: Boolean = true,
        hardwareTenBitSupported: Boolean = true,
        av1Main10Profile: Int? = null,
        hevcMain12Profile: Int? = null,
        eliteQualityTier: EliteVideoQualityTier? = null,
        av1AllIntraRequested: Boolean = false,
        twelveBitRequested: Boolean = false,
        hardwareTwelveBitSupported: Boolean = false
    ) = RecordingVideoEncoderPlanResolver.resolvePlan(
        mime = mime,
        width = width,
        height = height,
        fps = fps,
        requestedBitrate = 200_000_000,
        tenBitEnabled = tenBitEnabled,
        colorEncoding = bt2020Pq(),
        hardwareTenBitSupported = hardwareTenBitSupported,
        av1Main10Profile = av1Main10Profile,
        hevcMain12Profile = hevcMain12Profile,
        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        minimumBitrateFloorFor = { _, _ -> 120_000_000 },
        clampBitrate = { it },
        eliteQualityTier = eliteQualityTier,
        av1AllIntraRequested = av1AllIntraRequested,
        twelveBitRequested = twelveBitRequested,
        hardwareTwelveBitSupported = hardwareTwelveBitSupported
    )

    // ── CP70: AV1 all-intra ────────────────────────────────────────────────────────────────────

    @Test
    fun av1AllIntra_gateOff_isNotAllIntra() {
        val p = plan(mime = "video/av01", av1Main10Profile = 4096, av1AllIntraRequested = false)
        assertFalse(p.allIntraMastering)
        assertTrue("non-all-intra AV1 keeps a finite GOP", p.iFrameIntervalSeconds != 0f)
    }

    @Test
    fun av1AllIntra_gateOn_resolvesKeyframeEveryFrameCbrAndAllIntraFlag() {
        val p = plan(mime = "video/av01", av1Main10Profile = 4096, av1AllIntraRequested = true)
        assertTrue(p.allIntraMastering)
        assertEquals(0f, p.iFrameIntervalSeconds, 0.0001f)
        assertEquals(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            p.preferredBitrateModes.first()
        )
    }

    @Test
    fun av1AllIntra_gateOn_picksAv1Main10ProfileWhenPresent() {
        val p = plan(mime = "video/av01", av1Main10Profile = 4096, av1AllIntraRequested = true)
        assertEquals(4096, p.profile)
    }

    @Test
    fun av1AllIntra_gateOn_honestNullProfileWhenAv1Main10Absent() {
        // resolveProfile returns null for AV1 without an advertised Main10 profile — no false claim.
        val p = plan(mime = "video/av01", av1Main10Profile = null, av1AllIntraRequested = true)
        assertNull(p.profile)
        assertTrue(p.allIntraMastering)
    }

    @Test
    fun av1AllIntra_gateOnForHevcMimeIsIgnored() {
        // The AV1 gate only fires for an AV1 request; an HEVC request is unaffected.
        val p = plan(mime = "video/hevc", av1AllIntraRequested = true)
        assertFalse(p.allIntraMastering)
    }

    // ── CP71: HEVC Main12 ──────────────────────────────────────────────────────────────────────

    @Test
    fun main12_gateOff_usesMain10() {
        val p = plan(hevcMain12Profile = 123_456, twelveBitRequested = false)
        assertFalse(p.useTwelveBit)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, p.profile)
        assertEquals("Hardware 10-bit", p.activeBitDepthLabel)
    }

    @Test
    fun main12_gateOn_supported_resolvesTwelveBitProfileAndCapability() {
        val p = plan(
            hevcMain12Profile = 123_456,
            twelveBitRequested = true,
            hardwareTwelveBitSupported = true
        )
        assertTrue(p.useTwelveBit)
        assertEquals(123_456, p.profile)
        assertEquals("Hardware 12-bit", p.activeBitDepthLabel)
        assertEquals("HEVC_MAIN12_SUPPORTED", p.encoderQualityTelemetry.capabilityCheck)
    }

    @Test
    fun main12_gateOn_unsupportedProfile_honestMain10Fallback() {
        val p = plan(
            hevcMain12Profile = null,
            twelveBitRequested = true,
            hardwareTwelveBitSupported = true
        )
        assertFalse(p.useTwelveBit)
        assertTrue(p.useTenBit)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, p.profile)
    }

    @Test
    fun main12_gateOn_unsupportedHardware_honestMain10Fallback() {
        val p = plan(
            hevcMain12Profile = 123_456,
            twelveBitRequested = true,
            hardwareTwelveBitSupported = false
        )
        assertFalse(p.useTwelveBit)
        assertTrue(p.useTenBit)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, p.profile)
    }

    @Test
    fun main12_gateOn_av1MimeNeverResolvesTwelveBit() {
        // Main12 is HEVC-only; an AV1 request never flips useTwelveBit even with the gate on.
        val p = plan(
            mime = "video/av01",
            av1Main10Profile = 4096,
            hevcMain12Profile = 123_456,
            twelveBitRequested = true,
            hardwareTwelveBitSupported = true
        )
        assertFalse(p.useTwelveBit)
    }

    @Test
    fun main12_masteringTier_gateOverridesForcedMain10AndZeroesP010Required() {
        // Documented gated side-effect (verifier): on a mastering tier, enabling Main12 makes the
        // Main12 profile win over the forced-Main10 path AND zeroes p010Required (the surface signal).
        val ungated = plan(eliteQualityTier = EliteVideoQualityTier.ELITE_PRO_SDR_10BIT)
        assertFalse(ungated.useTwelveBit)
        assertTrue(ungated.p010Required)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, ungated.profile)

        val gated = plan(
            eliteQualityTier = EliteVideoQualityTier.ELITE_PRO_SDR_10BIT,
            hevcMain12Profile = 123_456,
            twelveBitRequested = true,
            hardwareTwelveBitSupported = true
        )
        assertTrue(gated.useTwelveBit)
        assertEquals(123_456, gated.profile)
        assertFalse("Main12 path drops the P010 surface signal (intentional, documented)", gated.p010Required)
    }
}
