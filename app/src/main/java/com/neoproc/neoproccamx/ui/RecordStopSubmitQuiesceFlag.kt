package com.neoproc.neoproccamx.ui

/**
 * RecordStopSubmitQuiesceFlag — reversible, default-ON gate for a bounded native-submit quiesce poll on
 * the record-stop timeout branch of detachInternalRecordingFeedBeforeEncoderStop.
 *
 * WHY: after `awaitIdleBeforeReaderClose(2_000ms)` TIMES OUT on the offscreen-EGL EOS path, the code
 * proceeds to setOffscreenAhbEglSubmissionEnabled(false) and (shortly after, in RecordingManager during
 * gpuRecording.stop()) signalEndOfInputStream(), possibly while a native hardware-import frame is still
 * mid `eglSwapBuffers` — a tear-down race that can orphan the tail frame. When ON it first spins a
 * strictly-bounded ([QUIESCE_TIMEOUT_MS] ms) poll over FramePipelineManager.hardwareSubmitInFlight so an
 * in-flight submit can finish. It is a bounded elapsed-time loop over an AtomicBoolean — never an
 * unbounded native mutex — so it cannot ANR.
 *
 * NO-DOWNGRADE: ON only adds a tiny bounded wait in a rare corner (the 2 s drain already failed),
 * reducing a native tear-down race, never altering
 * pixels/resolution/fps/bitrate/bit-depth. Reversible for on-device A/B:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_stop_submit_quiesce true
 */
object RecordStopSubmitQuiesceFlag {

    /** Hard upper bound on the quiesce poll — a rare, bounded wait; never blocks a full frame interval. */
    const val QUIESCE_TIMEOUT_MS: Long = 150L

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
