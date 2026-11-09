package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.R

internal data class CameraRecordingButtonVisualState(
    val centerBackgroundRes: Int,
    val centerScale: Float,
    val centerAlpha: Float,
    val cornerRadiusRatio: Float,
    val outerRingVisible: Boolean,
    val outerRingAlpha: Float,
    val pulseVisible: Boolean
)

internal object CameraRecordingButtonVisualPolicy {
    fun resolve(recording: Boolean): CameraRecordingButtonVisualState =
        if (recording) {
            CameraRecordingButtonVisualState(
                centerBackgroundRes = R.drawable.bg_shutter_recording,
                centerScale = 0.50f,
                centerAlpha = 0.98f,
                cornerRadiusRatio = 0.18f,
                outerRingVisible = true,
                outerRingAlpha = 1.0f,
                pulseVisible = true
            )
        } else {
            CameraRecordingButtonVisualState(
                centerBackgroundRes = R.drawable.bg_shutter_button_cinematic,
                centerScale = 1.0f,
                centerAlpha = 1.0f,
                cornerRadiusRatio = 0.50f,
                outerRingVisible = true,
                outerRingAlpha = 1.0f,
                pulseVisible = false
            )
        }
}
