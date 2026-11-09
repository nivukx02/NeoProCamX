package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugNdrPresetAtomicRoutingContractTest {

    private val source = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
        .readText()
        .replace("\r\n", "\n")

    @Test
    fun explicitDebugNdrLookIsTheFirstProfileAppliedByTheHdrPreset() {
        val debugConfiguration = source
            .substringAfter("pendingDebugPreset?.let { requestedPreset ->")
            .substringBefore("pendingDebugMode?.let { requestedMode ->")
        val modeActivation = source
            .substringAfter("private fun activateModePreset(preset: CameraModePreset)")
            .substringBefore("private fun resetAutoImagingStateForPreset()")
        val hdrPreset = modeActivation
            .substringAfter("} else if (preset == CameraModePreset.HDR) {")
            .substringBefore("check(preset == CameraModePreset.LOG_VIDEO)")

        assertTrue(debugConfiguration.contains("debugPresetHdrModeOverride = pendingDebugVideoHdrMode"))
        assertTrue(debugConfiguration.contains("requestedPreset == CameraModePreset.HDR"))
        assertTrue(debugConfiguration.contains("try {\n                        activateModePreset(requestedPreset)"))
        assertTrue(debugConfiguration.contains("finally {\n                        debugPresetHdrModeOverride = previousHdrOverride"))
        assertTrue(
            hdrPreset.contains(
                "vm.setVideoHdrMode(debugPresetHdrModeOverride ?: vm.preferredVideoHdrMode)"
            )
        )
    }
}
