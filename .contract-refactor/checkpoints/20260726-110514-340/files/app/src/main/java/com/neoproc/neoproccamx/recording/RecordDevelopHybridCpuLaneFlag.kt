package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopHybridCpuLaneFlag — reversible A/B lever (default OFF) that adds a CONCURRENT CPU develop
 * lane to the SDR/NDR record-develop converter so the CPU and GPU develop different frames at the same
 * time (GPU stays busy while the CPU develops its share), shortening the conversion.
 *
 * ON  => when [RecordDevelopHybridCpuLanePolicy.cpuShareBps] is above 0, a GPU accelerator is chosen, AND
 *        the heap can safely hold a second (CPU) develop frame beside
 *        the cheap GPU frame ([RecordDevelopHybridCpuLanePolicy.developConcurrency]), the converter runs
 *        two develop lanes: ~[RecordDevelopHybridCpuLanePolicy.cpuShareBps] of frames on the byte-exact CPU
 *        path, the rest on the GPU, bounded by a gpuPermit(1) + cpuLanePermit(1) so at most one CPU frame
 *        is resident at a time. The writer reorders by index, so output is byte-identical to single-lane.
 * OFF or cpuShareBps=0 => single-lane GPU develop when strict GPU is requested; CPU remains responsible
 *        for decode, scheduling, muxing, file writing, publish/copy, and UI/progress.
 *
 * NO-DOWNGRADE: CPU-lane frames are the byte-exact golden reference and GPU frames are already the
 * (parity-accepted) GPU output, so mixing them changes only WHICH engine produced each frame, never the
 * pixels beyond the sub-code tolerance the develop gate already enforces. It is a THROUGHPUT lever only —
 * same resolution / fps / bit-depth / colour. Memory-guarded so it can never exceed the heap; if the guard
 * refuses (tight heap) it silently stays single-lane. [RecordDevelopHybridCpuLaneForceFlag] bypasses the
 * guard for on-device A/B measurement of the headroom.
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_hybrid_cpu_lane true
 */
internal object RecordDevelopHybridCpuLaneFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * Bypasses the [RecordDevelopHybridCpuLanePolicy.developConcurrency] heap guard (default OFF) to FORCE the
 * two-lane hybrid for on-device measurement of the actual headroom/throughput. Use only while A/B-testing;
 * leave OFF for shipping (the guard is what keeps the converter OOM-safe on tight-heap devices).
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_hybrid_cpu_force true
 */
internal object RecordDevelopHybridCpuLaneForceFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
