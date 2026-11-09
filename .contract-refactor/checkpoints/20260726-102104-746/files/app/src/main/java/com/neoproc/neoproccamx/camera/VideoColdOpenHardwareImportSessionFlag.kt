package com.neoproc.neoproccamx.camera

/**
 * VideoColdOpenHardwareImportSessionFlag — reversible A/B gate (DEFAULT OFF, device-verify) for FIX D-9.
 *
 * WHY: at VIDEO-mode cold open the camera opens with a preview-only createSession and then immediately
 * does a forced prearm that REBUILDS the whole Camera2 session just to attach the P010 hardware-import
 * source (CameraViewModel.openCamera onReady -> requestRecordingSurfacePrearm(forceSessionRefresh=true)
 * -> prearmOffscreenHardwareImportSource -> refreshOutputsAndSession(forceRebuild=true)). Including the
 * P010 source reader in the FIRST createSession would let that rebuild be skipped, shaving a full session
 * reconfigure off cold-start-to-record-ready.
 *
 * STATUS / NO-DOWNGRADE: the reader-in-initial-session behavior is NOT wired yet — it is too intertwined
 * to prove byte-identical off-device (prefs-derived encoder size must match the prepared encoder size;
 * feed-path/mode gating must be replicated pre-open; the prearm state machine must be seeded to actually
 * skip the rebuild; the preview compositor bring-up must not reorder). Until a device-verified follow-up
 * lands, enabled=true is behaviorally identical to enabled=false (ON only logs intent), so NEITHER state
 * can regress resolution/fps/bitrate/bit-depth/look/stability. OFF is the shipped default.
 *
 * ADB A/B (takes effect on the next camera cold open):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.video_cold_open_hw_import_session true
 */
object VideoColdOpenHardwareImportSessionFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
