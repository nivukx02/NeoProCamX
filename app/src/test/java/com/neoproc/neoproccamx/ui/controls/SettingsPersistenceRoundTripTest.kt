package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.ui.PreviewAspectRatio
import com.neoproc.neoproccamx.ui.overlays.GridMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * s8-f03 (CP-U3) — settings persist and round-trip across power cycles: serialize→deserialize is the
 * identity over a typed state matrix, serialization is deterministic, and unknown/garbage tokens are
 * rejected (never silently coerced).
 */
class SettingsPersistenceRoundTripTest {

    @Test
    fun roundTripsAcrossTheStateMatrix() {
        for (mode in CinemaVideoMode.entries) {
            for (bitrateMode in VideoBitrateMode.entries) {
                for (stab in StabilizationMode.entries) {
                    for (grid in GridMode.entries) {
                        for (aspect in PreviewAspectRatio.entries) {
                            val state = SettingsState(
                                mode = mode,
                                hdrMode = "OFF",
                                bitrateMode = bitrateMode,
                                bitrateMbps = 180,
                                rawCompression = RawCompressionMode.HIGH_COMPRESSION,
                                fps = 30,
                                codec = VideoCodecType.HEVC,
                                stabilization = stab,
                                grid = grid,
                                previewAspect = aspect
                            )
                            assertEquals(state, SettingsStateSerializer.deserialize(SettingsStateSerializer.serialize(state)))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun serializeIsDeterministic() {
        val state = SettingsState(
            CinemaVideoMode.LOG, "NDR10+", VideoBitrateMode.CUSTOM, 240,
            RawCompressionMode.LOSSLESS, 24, VideoCodecType.AV1,
            StabilizationMode.HYBRID, GridMode.THIRDS, PreviewAspectRatio.entries.first()
        )
        assertEquals(SettingsStateSerializer.serialize(state), SettingsStateSerializer.serialize(state))
    }

    @Test
    fun unknownEnumTokenIsRejected() {
        val state = SettingsState(
            CinemaVideoMode.PRO, "OFF", VideoBitrateMode.AUTO, 180,
            RawCompressionMode.HIGH_COMPRESSION, 30, VideoCodecType.HEVC,
            StabilizationMode.OIS, GridMode.NONE, PreviewAspectRatio.entries.first()
        )
        val corrupted = SettingsStateSerializer.serialize(state).replace("mode=PRO", "mode=BOGUS")
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStateSerializer.deserialize(corrupted)
        }
    }

    @Test
    fun missingKeyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStateSerializer.deserialize("mode=PRO;hdr=OFF")
        }
    }
}
