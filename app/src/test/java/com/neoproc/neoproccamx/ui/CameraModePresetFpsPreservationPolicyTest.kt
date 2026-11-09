package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraModePresetFpsPreservationPolicyTest {

    @Test
    fun `video pro hdr and raw presets preserve explicit 24 25 30 and 60 fps`() {
        val presets = listOf(
            CameraModePreset.VIDEO,
            CameraModePreset.PRO,
            CameraModePreset.HDR,
            CameraModePreset.RAW_MODE
        )
        for (fps in listOf(24, 25, 30, 60)) {
            for (preset in presets) {
                val unchanged = CameraModePresetFpsPreservationPolicy.resolve(
                    preset = preset,
                    explicitFps = fps,
                    observedFpsAfterPreset = fps
                )
                assertFalse("$preset must not refresh an unchanged ${fps}fps session", unchanged.restoreRequired)
                assertEquals(fps, unchanged.explicitFps)
            }
        }
    }

    @Test
    fun `legacy 25 fps overwrite is restored without substituting another cadence`() {
        for (fps in listOf(24, 30, 60)) {
            for (preset in CameraModePreset.values()) {
                val decision = CameraModePresetFpsPreservationPolicy.resolve(
                    preset = preset,
                    explicitFps = fps,
                    observedFpsAfterPreset = 25
                )
                assertTrue(decision.restoreRequired)
                assertEquals(fps, decision.explicitFps)
                assertEquals(25, decision.observedFpsAfterPreset)
            }
        }
    }

    @Test
    fun `unsupported target combinations remain selected for separate fail closed admission`() {
        val decision = CameraModePresetFpsPreservationPolicy.resolve(
            preset = CameraModePreset.HDR,
            explicitFps = 60,
            observedFpsAfterPreset = 25
        )

        assertTrue(decision.restoreRequired)
        assertEquals(60, decision.explicitFps)
    }

    @Test
    fun `main activity presets contain no hard coded cadence and guard against future overwrite`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()
        val activation = source.substringAfter("private fun activateModePreset(preset: CameraModePreset)")
            .substringBefore("private fun updatePresetTapTargets")

        assertFalse(activation.contains("vm.setFPS(25)"))
        assertTrue(activation.contains("val explicitFpsBeforePreset = vm.recording.fps"))
        assertTrue(activation.contains("CameraModePresetFpsPreservationPolicy.resolve("))
        assertTrue(activation.contains("if (fpsPreservation.restoreRequired)"))
        assertTrue(activation.contains("vm.setFPS(fpsPreservation.explicitFps)"))
    }
}
