package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraViewModelLegacyP010FpsPolicyTest {
    private val uiSourceDir = File("src/main/java/com/neoproc/neoproccamx/ui")

    @Test
    fun highResolutionP010CapsThirtyFpsToSustainableCinemaCadence() {
        assertEquals(
            25,
            LegacyP010FpsPolicy.resolve(
                requestedFps = 30,
                width = 3264,
                height = 2448
            )
        )
    }

    @Test
    fun highResolutionP010KeepsRequestedTwentyFiveFps() {
        assertEquals(
            25,
            LegacyP010FpsPolicy.resolve(
                requestedFps = 25,
                width = 3840,
                height = 2160
            )
        )
    }

    @Test
    fun lowerResolutionP010KeepsRequestedThirtyFps() {
        assertEquals(
            30,
            LegacyP010FpsPolicy.resolve(
                requestedFps = 30,
                width = 1920,
                height = 1080
            )
        )
    }

    @Test
    fun legacyP010FpsPolicyIsExtractedFromCameraViewModel() {
        val policySource = File(uiSourceDir, "LegacyP010FpsPolicy.kt")
        val viewModelSource = File(uiSourceDir, "CameraViewModel.kt").readText()

        assertTrue(
            "Legacy P010 cadence policy must live in its own file so CameraViewModel stays a facade.",
            policySource.isFile
        )
        assertFalse(
            "CameraViewModel must not declare LegacyP010FpsPolicy inline.",
            viewModelSource.contains("internal object LegacyP010FpsPolicy")
        )
    }
}
