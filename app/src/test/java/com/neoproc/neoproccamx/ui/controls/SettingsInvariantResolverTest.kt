package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f03 (CP-U3) — illegal cross-section combinations are rejected before commit; every legal
 * current selection stays Valid (never altered).
 */
class SettingsInvariantResolverTest {

    private fun commit(
        mode: CinemaVideoMode = CinemaVideoMode.PRO,
        hdrMode: String = "OFF",
        bitrateMode: VideoBitrateMode = VideoBitrateMode.AUTO,
        rawCompression: RawCompressionMode = RawCompressionMode.HIGH_COMPRESSION,
        fps: Int = 25,
        codec: VideoCodecType = VideoCodecType.HEVC
    ) = SettingsCommit(mode, hdrMode, bitrateMode, rawCompression, fps, codec)

    @Test
    fun legalDefaultCommitIsValid() {
        assertEquals(SettingsInvariantResult.Valid, SettingsInvariantResolver.validate(commit()))
    }

    @Test
    fun customBitrateIsLegal() {
        assertEquals(
            SettingsInvariantResult.Valid,
            SettingsInvariantResolver.validate(commit(bitrateMode = VideoBitrateMode.CUSTOM))
        )
    }

    @Test
    fun rawWithHdrIsRejected() {
        val result = SettingsInvariantResolver.validate(commit(mode = CinemaVideoMode.RAW, hdrMode = "NDR10+"))
        assertTrue(result is SettingsInvariantResult.Rejected)
        assertEquals("RAW_EXCLUDES_HDR", (result as SettingsInvariantResult.Rejected).ruleId)
    }

    @Test
    fun rawWithNonUncompressedPayloadIsRejected() {
        val result = SettingsInvariantResolver.validate(
            commit(mode = CinemaVideoMode.RAW, hdrMode = "OFF", rawCompression = RawCompressionMode.OFF)
        )
        assertTrue(result is SettingsInvariantResult.Rejected)
        assertEquals("RAW_PINS_UNCOMPRESSED_12BIT", (result as SettingsInvariantResult.Rejected).ruleId)
    }

    @Test
    fun legalRawMasterIsValid() {
        assertEquals(
            SettingsInvariantResult.Valid,
            SettingsInvariantResolver.validate(
                commit(mode = CinemaVideoMode.RAW, hdrMode = "OFF", rawCompression = RawCompressionMode.HIGH_COMPRESSION)
            )
        )
    }

    @Test
    fun nonPositiveFpsIsRejected() {
        val result = SettingsInvariantResolver.validate(commit(fps = 0))
        assertTrue(result is SettingsInvariantResult.Rejected)
        assertEquals("FPS_MUST_BE_POSITIVE", (result as SettingsInvariantResult.Rejected).ruleId)
    }

    @Test
    fun fpsAboveVerifiedProductLimitIsRejectedInsteadOfClamped() {
        val result = SettingsInvariantResolver.validate(commit(fps = 120))
        assertTrue(result is SettingsInvariantResult.Rejected)
        assertEquals("FPS_EXCEEDS_PRODUCT_LIMIT", (result as SettingsInvariantResult.Rejected).ruleId)
    }

    @Test
    fun nonCatalogFpsIsRejectedInsteadOfSnapped() {
        val result = SettingsInvariantResolver.validate(commit(fps = 26))
        assertTrue(result is SettingsInvariantResult.Rejected)
        assertEquals("FPS_NOT_A_VERIFIED_CADENCE", (result as SettingsInvariantResult.Rejected).ruleId)
    }

    @Test
    fun fourKCadenceAbove25IsRejectedInEveryVideoEngine() {
        CinemaVideoMode.entries.forEach { mode ->
            listOf(
                3840 to 2160,
                4096 to 1716,
                2160 to 3840,
                7680 to 4320
            ).forEach { (width, height) ->
                val result = SettingsInvariantResolver.validate(
                    commit(mode = mode, fps = 30).copy(width = width, height = height)
                )
                assertTrue("$mode ${width}x${height}@30 must be rejected", result is SettingsInvariantResult.Rejected)
                assertEquals(
                    "FPS_EXCEEDS_RESOLUTION_LIMIT",
                    (result as SettingsInvariantResult.Rejected).ruleId
                )
            }
        }
    }

    @Test
    fun onlyFourK25RemainsValidInEveryVideoEngine() {
        CinemaVideoMode.entries.forEach { mode ->
            assertEquals(
                "$mode 4K25 must remain valid",
                SettingsInvariantResult.Valid,
                SettingsInvariantResolver.validate(
                    commit(mode = mode, fps = 25).copy(width = 4096, height = 3072)
                )
            )
            val rejected = SettingsInvariantResolver.validate(
                commit(mode = mode, fps = 24).copy(width = 4096, height = 3072)
            )
            assertTrue("$mode 4K24 must be rejected", rejected is SettingsInvariantResult.Rejected)
            assertEquals(
                "FPS_EXCEEDS_RESOLUTION_LIMIT",
                (rejected as SettingsInvariantResult.Rejected).ruleId
            )
        }
    }

    @Test
    fun lowerResolutionRejectsCadenceAboveLaunchApproved30Fps() {
        val result = SettingsInvariantResolver.validate(
            commit(fps = 60).copy(width = 1920, height = 1080)
        )

        assertTrue(result is SettingsInvariantResult.Rejected)
        assertEquals(
            "FPS_EXCEEDS_RESOLUTION_LIMIT",
            (result as SettingsInvariantResult.Rejected).ruleId
        )
    }

    @Test
    fun logWithHdrIsRejected() {
        val result = SettingsInvariantResolver.validate(commit(mode = CinemaVideoMode.LOG, hdrMode = "NIVUKXVISION"))
        assertTrue(result is SettingsInvariantResult.Rejected)
        assertEquals("LOG_EXCLUDES_HDR", (result as SettingsInvariantResult.Rejected).ruleId)
    }

    @Test
    fun nonHevcCodecIsRejectedInsteadOfSilentlySubstituted() {
        listOf(VideoCodecType.AVC, VideoCodecType.AV1).forEach { codec ->
            val result = SettingsInvariantResolver.validate(commit(codec = codec))
            assertTrue(result is SettingsInvariantResult.Rejected)
            assertEquals("STRICT_MAIN10_REQUIRES_HEVC", (result as SettingsInvariantResult.Rejected).ruleId)
        }
    }

    @Test
    fun nonPositiveAndExcessiveCustomBitratesAreRejected() {
        val zero = SettingsInvariantResolver.validate(
            commit(bitrateMode = VideoBitrateMode.CUSTOM).copy(customBitrateBps = 0)
        )
        val excessive = SettingsInvariantResolver.validate(
            commit(bitrateMode = VideoBitrateMode.CUSTOM).copy(customBitrateBps = 2_000_000_001)
        )
        assertEquals(
            "CUSTOM_BITRATE_MUST_BE_POSITIVE",
            (zero as SettingsInvariantResult.Rejected).ruleId
        )
        assertEquals(
            "CUSTOM_BITRATE_EXCEEDS_PRODUCT_LIMIT",
            (excessive as SettingsInvariantResult.Rejected).ruleId
        )
    }

    @Test
    fun invalidDimensionsAreRejectedBeforeResolutionSanitization() {
        val zero = SettingsInvariantResolver.validate(commit().copy(width = 0, height = 2160))
        val excessive = SettingsInvariantResolver.validate(commit().copy(width = 20_000, height = 10_000))
        assertEquals(
            "RESOLUTION_MUST_BE_POSITIVE",
            (zero as SettingsInvariantResult.Rejected).ruleId
        )
        assertEquals(
            "RESOLUTION_EXCEEDS_PRODUCT_LIMIT",
            (excessive as SettingsInvariantResult.Rejected).ruleId
        )
    }

    @Test
    fun processedVideoCannotDisableStrictTenBitContract() {
        val result = SettingsInvariantResolver.validate(commit().copy(encodedTenBitRequested = false))
        assertTrue(result is SettingsInvariantResult.Rejected)
        assertEquals(
            "STRICT_PROCESSED_VIDEO_REQUIRES_10_BIT",
            (result as SettingsInvariantResult.Rejected).ruleId
        )
    }

    @Test
    fun rawMasterUsesItsOwnSensorDepthAndDoesNotRequireEncodedTenBitFlag() {
        assertEquals(
            SettingsInvariantResult.Valid,
            SettingsInvariantResolver.validate(
                commit(mode = CinemaVideoMode.RAW).copy(encodedTenBitRequested = false)
            )
        )
    }
}
