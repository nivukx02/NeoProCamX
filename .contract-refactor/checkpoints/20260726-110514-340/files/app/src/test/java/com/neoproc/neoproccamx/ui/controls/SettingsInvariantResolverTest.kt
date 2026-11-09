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
        fps: Int = 30,
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
}
