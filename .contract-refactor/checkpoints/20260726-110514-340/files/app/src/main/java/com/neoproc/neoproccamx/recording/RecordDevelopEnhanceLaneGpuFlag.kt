package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopEnhanceLaneGpuFlag — reversible A/B lever (default OFF) that lets the SDR/NDR develop
 * converter run the CPU-only CHROMA enhance lane (temporal denoise + chroma-NR + shadow deadband +
 * chroma-spike suppression + blue-noise dither) on the GPU ([RecordDevelopEnhanceLaneGpuBridge])
 * instead of the CPU. This is the last CPU stage of the develop before pack/mux; moving it to the GPU
 * pushes the conversion further toward end-to-end GPU (the colour develop already offloads via
 * [RecordDevelopGpuAccelerator] and the luma parity pass via [RecordDevelopParityEnhanceGpuFlag]).
 *
 * ON  => enhanceP010ForDevelop routes the chroma enhance lane to the GPU, GATED by a first-frame
 *        CPU-vs-GPU parity check on the full P010 (via the same [RecordDevelopParityDiagnostics] /
 *        [RecordDevelopParityGatePolicy] harness the colour develop uses). If the GPU is unavailable,
 *        the pipeline was not built on this device, the frame's temporal lane is genuinely active
 *        beyond the validated first frame, or the GPU output diverges beyond tolerance, the frame
 *        falls back to the byte-exact CPU [Npv10ExportEnhancementPolicy.enhanceP010].
 * OFF => the chroma enhance lane always runs on the CPU (byte-identical to before this lever).
 *
 * DEFAULT OFF: this lane is fp-noise (double-vs-float32 retain/smoothstep/dither compares), so it must
 * be device-A/B-validated (residual within the gate) before enabling. NO-DOWNGRADE: it only MOVES the
 * already-approved enhance math onto the GPU behind a parity gate + CPU fallback; worst case == today.
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_enhance_lane_gpu true
 */
internal object RecordDevelopEnhanceLaneGpuFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
