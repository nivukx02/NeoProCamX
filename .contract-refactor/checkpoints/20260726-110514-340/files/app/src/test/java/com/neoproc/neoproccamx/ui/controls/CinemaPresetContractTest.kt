package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaPresetContractTest {

    @Test
    fun `all exposed cinema presets stay at or below 60fps`() {
        assertTrue(
            CinemaPreset.entries.all { preset -> preset.targetFps <= 60 }
        )
    }
}
