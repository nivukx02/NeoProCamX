package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraDockButtonStylePolicyTest {

    @Test
    fun resolveUsesSharedReferenceStyleForFlipAndSettingsButtons() {
        val style = CameraDockButtonStylePolicy.resolve(GpuRecordingState.Idle)

        assertEquals(R.drawable.bg_bottom_round_button, style.backgroundRes)
        assertEquals(R.drawable.ic_glass_flip_camera, style.flipIconRes)
        assertEquals(R.drawable.ic_glass_settings, style.settingsIconRes)
        assertEquals(CameraDockButtonAction.SETTINGS, style.settingsAction)
        assertNull(style.iconTint)
        assertEquals(1.0f, style.alpha, 0.001f)
    }

    @Test
    fun resolvePlacesPauseActionInSettingsSlotWhileRecording() {
        val style = CameraDockButtonStylePolicy.resolve(GpuRecordingState.Recording)

        assertEquals(CameraDockButtonAction.PAUSE, style.settingsAction)
        assertEquals(R.drawable.ic_record_pause, style.settingsIconRes)
        assertNull(style.iconTint)
        assertEquals(0.92f, style.alpha, 0.001f)
    }

    @Test
    fun resolvePlacesResumeActionInSettingsSlotWhilePaused() {
        val style = CameraDockButtonStylePolicy.resolve(GpuRecordingState.Paused)

        assertEquals(CameraDockButtonAction.RESUME, style.settingsAction)
        assertEquals(R.drawable.ic_record_resume, style.settingsIconRes)
        assertNull(style.iconTint)
        assertEquals(1.0f, style.alpha, 0.001f)
    }
}
