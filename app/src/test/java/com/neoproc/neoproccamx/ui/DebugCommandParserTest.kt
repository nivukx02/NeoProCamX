package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugCommandParserTest {

    @Test
    fun parse_mapsPhotoPresetToStandardSdrStillCaptureContext() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "capture_photo",
                preset = "PHOTO"
            )
        )

        requireNotNull(result)
        assertEquals(CameraModePreset.VIDEO, result.preset)
        assertEquals(AppMode.VIDEO, result.mode)
        assertEquals("OFF", result.videoHdrMode)
        assertEquals("Rec.709", result.videoColorSpace)
        assertEquals(false, result.raw)
        assertEquals(false, result.log)
    }

    @Test
    fun parse_appliesHdrPresetDefaults() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                preset = "HDR",
                durationMs = "2500"
            )
        )

        requireNotNull(result)
        assertEquals(CameraModePreset.HDR, result.preset)
        assertEquals(AppMode.VIDEO, result.mode)
        assertEquals("HDR10", result.videoHdrMode)
        assertEquals("Rec.2020", result.videoColorSpace)
        assertEquals(CinemaVideoMode.PRO, result.videoMode)
        assertEquals(false, result.raw)
        assertEquals(false, result.log)
    }

    @Test
    fun parse_proVideoPresetCannotBeRedirectedToStillProMode() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                mode = "PRO",
                preset = "PRO",
                action = "start_recording"
            )
        )

        requireNotNull(result)
        assertEquals(AppMode.VIDEO, result.mode)
        assertEquals(CameraModePreset.PRO, result.preset)
    }

    @Test
    fun parse_clampsDurationFromString() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                preset = "PRO",
                durationMs = "999999"
            )
        )

        requireNotNull(result)
        assertEquals(120_000L, result.durationMs)
    }

    @Test
    fun parse_parsesResolutionAndCompression() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                preset = "RAW",
                videoResolution = "4096x3072",
                videoFps = "24",
                rawCompression = "HIGH_COMPRESSION"
            )
        )

        requireNotNull(result)
        assertEquals(Size(4096, 3072).width, result.videoResolution?.width)
        assertEquals(Size(4096, 3072).height, result.videoResolution?.height)
        assertEquals(24, result.videoFps)
        assertEquals(RawCompressionMode.HIGH_COMPRESSION, result.rawCompression)
    }

    @Test
    fun parse_clampsVideoFpsToRecordingContract() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                videoFps = "240"
            )
        )

        requireNotNull(result)
        assertEquals(60, result.videoFps)
    }

    @Test
    fun parse_parsesVideoDetailProfileForRecordingQualityAutomation() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                videoDetailProfile = "EXTREME_DETAIL"
            )
        )

        requireNotNull(result)
        assertEquals(VideoDetailProfile.EXTREME_DETAIL, result.videoDetailProfile)
    }

    @Test
    fun parse_preservesUncompressedCinemaPipelineSwitchForAutomation() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                uncompressedCinemaPipeline = true
            )
        )

        requireNotNull(result)
        assertEquals(true, result.uncompressedCinemaPipeline)
    }

    @Test
    fun parsePreservesSdrConversionDestinationForAutomation() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "start_recording",
                sdrConversionPipeline = true
            )
        )

        requireNotNull(result)
        assertEquals(true, result.sdrConversionPipeline)
    }

    @Test
    fun parse_parsesResolutionWithWhitespaceAroundSeparator() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                videoResolution = "4096 x 3072"
            )
        )

        requireNotNull(result)
        assertEquals(Size(4096, 3072).width, result.videoResolution?.width)
        assertEquals(Size(4096, 3072).height, result.videoResolution?.height)
    }

    @Test
    fun parse_returnsNullWhenNoDebugExtrasArePresent() {
        val result = DebugCommandParser.parse(DebugCommandExtras())

        assertNull(result)
    }
}
