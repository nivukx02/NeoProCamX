package com.neoproc.neoproccamx.ui

internal data class CameraPressMotion(
    val pressedScale: Float,
    val durationMs: Long
)

internal data class CameraPanelMotion(
    val offsetDp: Float,
    val showDurationMs: Long,
    val hideDurationMs: Long
)

internal data class CameraScalarMotion(
    val durationMs: Long
)

internal object CameraMotionPolicy {
    fun pressMotion(): CameraPressMotion =
        CameraPressMotion(pressedScale = 0.96f, durationMs = 90L)

    fun panelMotion(fromTop: Boolean): CameraPanelMotion =
        CameraPanelMotion(
            offsetDp = if (fromTop) -18f else 18f,
            showDurationMs = 170L,
            hideDurationMs = 160L
        )

    fun controlPressMotion(): CameraScalarMotion = CameraScalarMotion(durationMs = 120L)

    fun shutterMorphMotion(): CameraScalarMotion = CameraScalarMotion(durationMs = 165L)

    fun cameraSwitchMotion(): CameraScalarMotion = CameraScalarMotion(durationMs = 130L)

    fun saveWatermarkMotion(): CameraScalarMotion = CameraScalarMotion(durationMs = 160L)
}
