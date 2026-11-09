package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DebugRecordingConfigurationAdmissionTest {
    private val expected = DebugRecordingConfigurationAdmission.Expected(
        appMode = AppMode.VIDEO,
        preset = CameraModePreset.PRO,
        width = 1920,
        height = 1080,
        fps = 30,
        cinemaMode = CinemaVideoMode.PRO,
        rawEnabled = false,
        logEnabled = false,
        colorSpace = "Rec.709",
        hdrMode = "OFF",
        detailProfile = VideoDetailProfile.FOCUSED_DETAIL,
        rawCompression = RawCompressionMode.OFF,
        uncompressedCinemaPipeline = true,
        sdrConversionPipeline = false
    )

    private fun applied(width: Int = 1920, appMode: AppMode = AppMode.VIDEO) =
        DebugRecordingConfigurationAdmission.Applied(
            appMode = appMode,
            preset = CameraModePreset.PRO,
            selectedWidth = width,
            selectedHeight = 1080,
            encoderWidth = width,
            encoderHeight = 1080,
            selectedFps = 30,
            encoderFps = 30,
            cinemaMode = CinemaVideoMode.PRO,
            rawEnabled = false,
            logEnabled = false,
            colorSpace = "Rec.709",
            hdrMode = "OFF",
            detailProfile = VideoDetailProfile.FOCUSED_DETAIL,
            rawCompression = RawCompressionMode.OFF,
            uncompressedCinemaPipeline = true,
            sdrConversionPipeline = false
        )

    @Test
    fun `exact requested recording configuration is admitted`() {
        assertTrue(DebugRecordingConfigurationAdmission.evaluate(expected, applied()).admitted)
    }

    @Test
    fun `rejected resolution and wrong app mode fail closed`() {
        val result = DebugRecordingConfigurationAdmission.evaluate(
            expected,
            applied(width = 4096, appMode = AppMode.PRO)
        )
        assertFalse(result.admitted)
        assertTrue(result.mismatches.any { it.startsWith("appMode expected=") })
        assertTrue(result.mismatches.any { it.startsWith("selectedResolution expected=") })
        assertTrue(result.mismatches.any { it.startsWith("encoderResolution expected=") })
    }

    @Test
    fun `debug automation applies cinema mode before exact resolution and cadence`() {
        val source = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt"),
            File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
        ).first { it.isFile }.readText()
        val block = source.substringAfter("pendingDebugVideoHdrMode?.let")
            .substringBefore("debugConfigAppliedForPendingCommand = true")
        assertTrue(block.indexOf("pendingDebugVideoMode?.let") < block.indexOf("pendingDebugVideoResolution?.let"))
        assertTrue(block.indexOf("pendingDebugVideoResolution?.let") < block.indexOf("pendingDebugVideoFps?.let"))
        assertTrue(source.contains("DEBUG_RECORDING_CONFIG_REJECTED"))
    }
}
