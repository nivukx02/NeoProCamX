package com.neoproc.neoproccamx.rendering

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-THEN-DEVELOP MODE FLAG  (reversible master switch, default OFF)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The single master switch for the RECORD-THEN-DEVELOP engine. When ON, the LIVE record lane runs
 * LIGHT — it strips every heavy per-frame enhancement (spatial/chroma/temporal denoise, unsharp,
 * creative record grade + film-print LUT) so the single-GPU record path can hold a locked full-sensor
 * cadence with zero drops and lay down a FLAT, high-fidelity 10-bit intermediate. The full elite colour
 * stack (SNCE v2 / CAM16 / ACES / OmniClarity-NRLI, see
 * [com.neoproc.neoproccamx.recording.VideoDevelopColorPipeline]) is then applied OFFLINE by the
 * SDR-Convert / NDR-Convert develop passes instead of on the live path.
 *
 * WIRED live behaviours, each reading `.enabled` at its decision point:
 *   1. [com.neoproc.neoproccamx.rendering.OffscreenAhbEglEncoderBridge] forces the record enhancement
 *      uniforms + creative grade/LUT to their no-op values (flat intermediate). For the NDR/HDR lane the
 *      selected NDR/NDR10+/NIVUKX transfer is preserved. Record-develop changes enhancement timing only;
 *      surface binding, codec/MediaStore tags, sidecar metadata, preview, and conversion remain coherent.
 *   2. [com.neoproc.neoproccamx.ui.ProcessedGpuRecordFpsPolicy] +
 *      [com.neoproc.neoproccamx.recording.RecordingPreviewFrameRatePolicy] lift the full-sensor record-fps
 *      cap to 30 (encoder + preview + capture in lockstep). DEVICE-GATED: the sensor AE ceiling still governs
 *      the ACHIEVED rate (AlwaysSaveAtAchievedFpsFlag saves at whatever is achieved).
 *   3. [onChanged] re-syncs the record grade/LUT on a mid-session flip (wired in CameraViewModel).
 *
 * WIRED (Phase-1 remainder — DONE): the record-stop path registers the intermediate into the SDR/NDR master
 * store ([com.neoproc.neoproccamx.recording.RecordDevelopMasterStore]) and writes the colour sidecar
 * ([com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar]) from the frozen recorded colour facts, so
 * the offline SDR/NDR develop cards can grade the intermediate later. The full offline develop stack (Phases
 * 2–7) is built + tested.
 *
 * DEFAULT: **OFF** for finished video. Normal Photo/Video/Pro/NDR recordings therefore retain the complete
 * live detail, denoise, colour-science, grade, and LUT pipeline. Record-then-develop remains available as an
 * explicit opt-in workflow for users who intentionally want a flat intermediate and a later offline pass.
 * The gate is read live at each decision point (never cached), so a debug flip takes effect on the next
 * recording, and [onChanged] re-syncs the pipeline.
 *
 * Runtime A/B (debug builds), instant revert:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_develop_mode true
 */
internal object RecordDevelopModeFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    /** Sets the gate and, if it changed, notifies the listener to re-sync the record lane. */
    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    /** Registered by the ViewModel to re-sync the record pipeline when the gate flips (on-device A/B). */
    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }
}
