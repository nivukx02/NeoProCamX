package com.neoproc.neoproccamx.camera

/**
 * SessionSupportProbeFlag — reversible gate (default OFF) for the pre-createCaptureSession diagnostic
 * that asks the camera HAL `CameraDevice.isSessionConfigurationSupported(...)` and logs whether the
 * negotiated Camera2 stream graph is legal on this exact hardware (the
 * "createSession isSessionConfigurationSupported=..." + "PREDICTED-UNSUPPORTED" NeoLogger lines).
 *
 * WHY skip it by default: the probe is a synchronous, potentially-blocking HAL round-trip issued on the
 * camera thread immediately before every session (re)build. It is PURELY diagnostic — its result feeds
 * into NOTHING except the two log lines; `dev.createCaptureSession(sessionConfiguration)` runs
 * byte-identically whether or not the probe ran. When a stream combination genuinely is unsupported the
 * framework's own `configureFailed` callback still fires and the reactive
 * CameraSessionCreateFailurePolicy / handleCreateSessionIllegalState fallback still engages. Skipping it
 * removes that per-session HAL IPC from the session-setup latency path and loses only a predictive log.
 *
 * NO-DOWNGRADE: default OFF (probe skipped) changes no pixel, no capture parameter, and no session
 * output — only the diagnostic HAL query + its two log lines are elided (a permitted pure-diagnostic
 * elision, not a quality/feature/stability regression). Flip ON to restore today's exact probe + logs
 * for on-device diagnosis (read once per session build; takes effect on the next (re)build):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.session_support_probe true
 */
object SessionSupportProbeFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
