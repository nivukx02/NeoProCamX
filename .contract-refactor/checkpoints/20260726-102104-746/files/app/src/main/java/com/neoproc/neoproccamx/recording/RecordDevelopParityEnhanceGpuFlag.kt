package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopParityEnhanceGpuFlag — reversible A/B lever (default ON) that lets the SDR/NDR develop
 * converter run the LIVE-CINEMA parity luma enhance (bilateral denoise + halo-guarded unsharp) on the
 * GPU ([RecordDevelopParityEnhanceGpuBridge]) instead of the CPU, so the conversion uses the GPU for
 * the heaviest enhance stage while the CPU keeps the colour-lane develop, packing and muxing busy
 * (a true CPU+GPU hybrid). The colour develop already offloads via [RecordDevelopGpuAccelerator].
 *
 * ON  => enhanceP010ForDevelop routes the live-parity luma pass to the GPU, GATED by a first-frame
 *        (per operating-point) CPU-vs-GPU parity check on the luma plane. If the GPU is unavailable,
 *        the pipeline was not built on this device, or the GPU output diverges from the CPU parity
 *        reference beyond a tight code tolerance, EVERY frame falls back to the byte-exact CPU pass.
 * OFF => the luma parity pass always runs on the CPU (byte-identical to before this lever).
 *
 * NO-DOWNGRADE: this only MOVES the already-approved parity look onto the GPU behind a parity gate; it
 * never changes resolution, fps, bit-depth (10-bit 4:2:0), colour, or the look beyond the sub-code
 * float32-vs-float64 tolerance the gate enforces. Reversible for on-device A/B.
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_parity_enhance_gpu false
 */
internal object RecordDevelopParityEnhanceGpuFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
