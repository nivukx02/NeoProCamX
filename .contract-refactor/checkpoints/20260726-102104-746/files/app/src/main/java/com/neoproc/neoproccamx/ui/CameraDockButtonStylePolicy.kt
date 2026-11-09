package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.recording.GpuRecordingState

enum class CameraDockButtonAction {
    SETTINGS,
    PAUSE,
    RESUME
}

data class CameraDockButtonStyle(
    val backgroundRes: Int,
    val flipIconRes: Int,
    val settingsIconRes: Int,
    val settingsAction: CameraDockButtonAction,
    val iconTint: Int?,
    val alpha: Float
)

object CameraDockButtonStylePolicy {
    fun resolve(state: GpuRecordingState): CameraDockButtonStyle {
        val action = when (state) {
            GpuRecordingState.Recording -> CameraDockButtonAction.PAUSE
            GpuRecordingState.Paused -> CameraDockButtonAction.RESUME
            else -> CameraDockButtonAction.SETTINGS
        }
        return CameraDockButtonStyle(
            backgroundRes = R.drawable.bg_bottom_round_button,
            flipIconRes = R.drawable.ic_glass_flip_camera,
            settingsIconRes = when (action) {
                CameraDockButtonAction.SETTINGS -> R.drawable.ic_glass_settings
                CameraDockButtonAction.PAUSE -> R.drawable.ic_record_pause
                CameraDockButtonAction.RESUME -> R.drawable.ic_record_resume
            },
            settingsAction = action,
            iconTint = null,
            alpha = when (action) {
                CameraDockButtonAction.SETTINGS -> 1.0f
                CameraDockButtonAction.PAUSE -> 0.92f
                CameraDockButtonAction.RESUME -> 1.0f
            }
        )
    }

    fun resolve(recording: Boolean): CameraDockButtonStyle =
        CameraDockButtonStyle(
            backgroundRes = R.drawable.bg_bottom_round_button,
            flipIconRes = R.drawable.ic_glass_flip_camera,
            settingsIconRes = if (recording) R.drawable.ic_record_pause else R.drawable.ic_glass_settings,
            settingsAction = if (recording) CameraDockButtonAction.PAUSE else CameraDockButtonAction.SETTINGS,
            iconTint = null,
            alpha = if (recording) 0.92f else 1.0f
        )
}
