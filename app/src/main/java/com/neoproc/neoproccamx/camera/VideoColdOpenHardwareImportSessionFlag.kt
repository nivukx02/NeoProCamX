package com.neoproc.neoproccamx.camera

/**
 * Reversible gate for priming the optional P010 hardware-import source in the authoritative Camera2
 * session transaction used by cold open and RAW-to-processed-mode switches.
 *
 * Without priming, Camera2 first configures a session without HARDWARE_IMPORT and encoder pre-arm
 * immediately requests a second session solely to add that source. Priming declares the already
 * selected resolution, FPS, and 10-bit source before session construction. It does not change
 * encoder quality, preview quality, bitrate, colour science, or the RAW graph. Unsupported output
 * combinations retain the existing capability-gated path and Camera2 optional-output fallback.
 *
 * ADB A/B (takes effect on the next camera cold open or mode transition):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver \
 *       --ez debug.video_cold_open_hw_import_session true
 */
object VideoColdOpenHardwareImportSessionFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
