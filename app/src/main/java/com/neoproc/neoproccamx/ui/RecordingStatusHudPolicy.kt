package com.neoproc.neoproccamx.ui

import java.util.Locale
import kotlin.math.max

internal data class RecordingStatusHudState(
    val primaryText: String,
    val primaryTextColor: Int,
    val timerActive: Boolean
)

internal object RecordingStatusHudPolicy {
    private const val IDLE_TEXT_COLOR = 0xFFF4F7FA.toInt()
    private const val RECORDING_TEXT_COLOR = 0xFFFF3030.toInt()
    private const val PAUSED_TEXT_COLOR = 0xFFB46CFF.toInt()

    fun resolve(
        recording: Boolean,
        paused: Boolean = false,
        durationMs: Long,
        cameraStatusText: String
    ): RecordingStatusHudState {
        if (!recording) {
            return RecordingStatusHudState(
                primaryText = cameraStatusText,
                primaryTextColor = IDLE_TEXT_COLOR,
                timerActive = false
            )
        }
        if (paused) {
            return RecordingStatusHudState(
                primaryText = "PAUSED ${formatDuration(durationMs)}",
                primaryTextColor = PAUSED_TEXT_COLOR,
                timerActive = true
            )
        }
        return RecordingStatusHudState(
            primaryText = "REC ${formatDuration(durationMs)}",
            primaryTextColor = RECORDING_TEXT_COLOR,
            timerActive = true
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = max(0L, durationMs / 1000L)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
