package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeConditionalPipelineRoutingContractTest {

    private val activation = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
        .readText()
        .replace("\r\n", "\n")
        .substringAfter("private fun activateModePreset(preset: CameraModePreset)")
        .substringBefore("private fun resetAutoImagingStateForPreset()")

    @Test
    fun `public mode dispatch is an exclusive video pro raw condition chain`() {
        val video = activation.indexOf("if (preset == CameraModePreset.VIDEO) {")
        val pro = activation.indexOf("} else if (preset == CameraModePreset.PRO) {")
        val raw = activation.indexOf("check(preset == CameraModePreset.RAW_MODE)")

        assertTrue(video >= 0)
        assertTrue(pro > video)
        assertTrue(raw > pro)
        assertTrue(activation.substring(0, video).contains("val publicVideoMode ="))
    }

    @Test
    fun `each public condition owns its complete mode pipeline`() {
        val video = activation.substringAfter("if (preset == CameraModePreset.VIDEO) {")
            .substringBefore("} else if (preset == CameraModePreset.PRO) {")
        val pro = activation.substringAfter("} else if (preset == CameraModePreset.PRO) {")
            .substringBefore("check(preset == CameraModePreset.RAW_MODE)")
        val raw = activation.substringAfter("check(preset == CameraModePreset.RAW_MODE)")
            .substringBefore("markSwitchCheckpoint(\"raw-depth\")")

        assertTrue(video.contains("MODE_PIPELINE_ROUTE mode=VIDEO"))
        assertTrue(video.contains("SdrQualityIntent.NORMAL"))
        assertTrue(video.contains("CinemaVideoMode.PRO"))
        assertTrue(video.contains("setVideoHdrMode(\"OFF\")"))
        assertTrue(video.contains("setTenBitRecordingEnabled(true)"))

        assertTrue(pro.contains("MODE_PIPELINE_ROUTE mode=PRO"))
        assertTrue(pro.contains("SdrQualityIntent.PRO"))
        assertTrue(pro.contains("CinemaVideoMode.PRO"))
        assertTrue(pro.contains("setVideoHdrMode(\"OFF\")"))
        assertTrue(pro.contains("setTenBitRecordingEnabled(true)"))

        assertTrue(raw.contains("MODE_PIPELINE_ROUTE mode=RAW"))
        assertTrue(raw.contains("setVideoHdrMode(\"OFF\")"))
        assertTrue(raw.contains("CinemaVideoMode.RAW"))
        assertTrue(raw.contains("setVideoColorSpace(\"Rec.2020\")"))
        assertTrue(raw.contains("setTenBitRecordingEnabled(false)"))
    }

    @Test
    fun `home rail exposes exactly the three conditionally routed modes`() {
        assertEquals(
            listOf(CameraModePreset.VIDEO, CameraModePreset.PRO, CameraModePreset.RAW_MODE),
            CameraModePresetOrderPolicy.videoRailOrder()
        )
    }
}
