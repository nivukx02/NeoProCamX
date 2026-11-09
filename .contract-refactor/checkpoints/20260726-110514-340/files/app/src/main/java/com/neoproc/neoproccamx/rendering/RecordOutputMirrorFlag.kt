package com.neoproc.neoproccamx.rendering

import android.hardware.camera2.CameraCharacteristics

/**
 * RecordOutputMirrorFlag — MIRRORFIX v5 record-output orientation control for the live offscreen-EGL
 * encoder (the active recorder on this device).
 *
 * The record path samples the camera AHardwareBuffer directly (no SurfaceTexture / getTransformMatrix,
 * unlike the preview), so the recorded frame displayed UPRIGHT but HORIZONTALLY MIRRORED (back-camera
 * text read backwards). The correction is a per-axis flip applied ONCE on the final encoder-writing pass
 * (it survives the default-ON decode-once two-pass path, where a flip baked into the shared quad would
 * cancel). See offscreen_egl_encoder_bridge.cpp "MIRROR FIX v5".
 *
 * Axis: the container tags a 90-degree display rotation (no reflection), so a DISPLAYED-horizontal mirror
 * is undone by flipping the ENCODED-vertical (T) axis. Hence the default is [flipX=false, flipY=true]
 * (portrait un-mirror). LANDSCAPE capture wants the opposite axis (flipX=true, flipY=false).
 *
 * Runtime A/B (takes effect on the next recording, pushed at setEncoderSurface):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_flip_x true
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_flip_y false
 * Setting both to false = byte-identical raw-buffer passthrough (no un-mirror).
 */
data class RecordOutputMirrorPlan(
    val flipX: Boolean,
    val flipY: Boolean,
    val source: String
)

object RecordOutputMirrorFlag {

    @Volatile
    var flipX: Boolean = false
        private set

    @Volatile
    var flipY: Boolean = true
        private set

    @Volatile
    private var manualOverride: Boolean = false

    fun setFlipX(value: Boolean) {
        manualOverride = true
        flipX = value
    }

    fun setFlipY(value: Boolean) {
        manualOverride = true
        flipY = value
    }

    fun currentManualPlan(): RecordOutputMirrorPlan =
        RecordOutputMirrorPlan(flipX = flipX, flipY = flipY, source = "manual-or-default")

    /**
     * Resolve the single final-pass un-mirror axis from the encoded output rotation.
     *
     * A displayed-horizontal mirror maps to encoded-X when the saved frame is 0/180 degrees, and
     * encoded-Y when the container/video orientation is 90/270 degrees. The shader still performs one
     * final texture-coordinate flip; no resampling, scaling, colour, or bitrate path changes are made.
     */
    fun resolveForRecording(
        lensFacing: Int,
        sensorOrientationDegrees: Int,
        orientationHintDegrees: Int
    ): RecordOutputMirrorPlan {
        if (manualOverride) return currentManualPlan().copy(source = "manual-debug")
        val orientation = normalizeDegrees(orientationHintDegrees)
        val flipEncodedY = orientation == 90 || orientation == 270
        val lensLabel = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        return RecordOutputMirrorPlan(
            flipX = !flipEncodedY,
            flipY = flipEncodedY,
            source = "auto-orientation lens=$lensLabel sensor=${normalizeDegrees(sensorOrientationDegrees)} output=$orientation"
        )
    }

    private fun normalizeDegrees(degrees: Int): Int =
        ((degrees % 360) + 360) % 360
}
