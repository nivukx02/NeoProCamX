package com.neoproc.neoproccamx.camera

/**
 * Reversible A/B gate (default ON) — restore autofocus WHILE RECORDING.
 *
 * Under AUTO auto-exposure the CONTINUOUS_VIDEO AF servo runs the SAME request posture in armed preview
 * and in recording (TEMPLATE_RECORD, VIDEO_RECORD intent, re-stamped CONTROL_AF_MODE + AF regions), yet
 * AF works in armed preview and stalls the instant recording starts. The only AUTO-AE request delta at
 * record-start is an explicit SENSOR_FRAME_DURATION write: [CameraVideoSensorTimingPolicy] and the V20
 * processed-video timing path both emit a frame duration ONLY while recording. Some vivo HALs treat an
 * app-set SENSOR_FRAME_DURATION under AUTO AE as a fixed sensor-timing submode that freezes the AF servo.
 *
 * When ON: under AUTO AE we do NOT write an explicit SENSOR_FRAME_DURATION while recording. Recording
 * cadence is held by CONTROL_AE_TARGET_FPS_RANGE (unchanged), so there is NO resolution/fps/bit-depth
 * change. Manual AE (CONTROL_AE_MODE_OFF) still sets SENSOR_FRAME_DURATION as required for a fixed shutter.
 *
 * Default ON. Flip OFF for an instant on-device A/B (confirm with the "AFPROBE" logcat line):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.af_recording_unlock false
 */
internal object AfRecordingUnlockFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
