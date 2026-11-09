package com.neoproc.neoproccamx.recording

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-DEVELOP — SALVAGE-ON-STOP-FAILURE  (reversible, default ON)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * WHY THIS EXISTS
 * ───────────────
 * With [com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag] ON (the shipped default) a VIDEO
 * recording is written ONLY as a private develop-master intermediate — it is never published to
 * MediaStore, so the private `.mov` is the SOLE copy. The develop card in Settings then enumerates
 * that intermediate so the user can convert it into the final high-quality video.
 *
 * On the vivo offscreen-EGL / P010 stop path the muxer writer occasionally does not fully drain
 * within [RecordingStopFinalizationPolicy.WRITER_COMPLETION_BUDGET_MS]. When that happens
 * [RecordingStopFinalizationPolicy.resolveCloseDecision] returns `waitForWriter`, and the stop code
 * calls `movSessionController.abort()` + throws. `abort()` tears the container down WITHOUT writing
 * the `moov` atom, so the file is an unplayable stub — and the `catch` then runs
 * `cleanupFailedOutputTarget()`, which DELETES the sole copy and nulls the colour sidecar. The
 * recording is lost from BOTH the gallery and the converter list: exactly the "recorded video is not
 * saved and does not reflect in converters" symptom.
 *
 * WHAT ON DOES
 * ────────────
 * ON  → when the stop decision would abort a PRIVATE_DEVELOP_MASTER target that ALREADY has a real,
 *       finalisable video stream (`muxerStarted && videoTrackIndex >= 0 && encodedVideoSampleCount > 0`),
 *       finalise the container with `movSessionController.close()` (writes the `moov`) and take the
 *       normal KEEP/finalise path instead of aborting. A short but valid clip is retained and
 *       enumerates in the converter list — far better than silently losing the recording. `close()`
 *       runs under `muxerLock`, and `RecordingMovSessionController.writeSample()` is guarded by
 *       `opened`, so a late writer packet after close is a safe no-op.
 *
 * OFF → byte-identical to the legacy behaviour (abort + delete). Kept for instant A/B revert.
 *
 * SCOPE: this ONLY converts an abort into a finalise for a genuinely-encoded develop master; it never
 * fabricates a clip (zero samples / no track still abort), never touches the live record pixel path,
 * codecs, resolution, fps, bitrate, 10-bit or colour — it only stops the sole copy of a real
 * recording from being thrown away at stop.
 *
 * Runtime A/B (debug builds), instant revert:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_develop_salvage_on_stop true|false
 */
internal object RecordDevelopSalvageOnStopFailureFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    /** Flip the salvage behaviour (read live at each stop; no rebind needed). */
    fun set(value: Boolean) {
        enabled = value
    }
}
